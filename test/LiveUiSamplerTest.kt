import dev.yoda.harmon.model.LoadAverages
import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessorCounters
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.StorageCounters
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.model.VirtualMemoryCounters
import dev.yoda.harmon.monitor.CollectionProfile
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.report.WebUiPayload
import dev.yoda.harmon.report.WebUiPayloadFactory
import dev.yoda.harmon.report.WebUiPayloadJson
import dev.yoda.harmon.report.WebUiStatus
import dev.yoda.harmon.web.LiveUiSampler
import dev.yoda.harmon.web.LiveUiSamplerDiagnostic
import dev.yoda.harmon.web.LiveUiSamplerLifecycle
import dev.yoda.harmon.web.LiveUiState
import dev.yoda.harmon.web.nextLiveSampleDeadlineNanoseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

class LiveUiSamplerTest {
    @Test
    fun cadenceDeadlineIsTheFirstTickStrictlyAfterCaptureCompletion() {
        assertEquals(
            1_000_000_000uL,
            nextLiveSampleDeadlineNanoseconds(0uL, 400_000_000uL, 1_000_000_000uL),
        )
        assertEquals(
            2_000_000_000uL,
            nextLiveSampleDeadlineNanoseconds(0uL, 1_200_000_000uL, 1_000_000_000uL),
        )
        assertEquals(
            3_000_000_000uL,
            nextLiveSampleDeadlineNanoseconds(0uL, 2_000_000_000uL, 1_000_000_000uL),
        )
    }

    @Test
    fun doesNotCaptureWithoutARenewedLease() {
        val clock = SamplerRuntimeClock()
        val fixture = samplerRuntimeFixture(
            clock = clock,
            actions = listOf({ samplerRuntimeSnapshot(clock.peek()) }),
        )

        fixture.sampler.start()
        try {
            samplerRuntimePause()
            assertEquals(0, fixture.collector.captureCount)
        } finally {
            fixture.sampler.stop()
        }
    }

    @Test
    fun activationPublishesOneSynchronousWarmingAndReactivationStartsFull() {
        val clock = SamplerRuntimeClock()
        val activationEncoder = SamplerRuntimeActivationEncoder()
        val fixture = samplerRuntimeFixture(
            clock = clock,
            actions = listOf(
                { samplerRuntimeSnapshot(1_000_000_000uL) },
                { samplerRuntimeSnapshot(2_000_000_000uL) },
                { samplerRuntimeSnapshot(3_000_000_000uL) },
            ),
            encoder = activationEncoder::encode,
        )
        val firstRenewReturned = SamplerRuntimeFlag()
        val secondRenewReturned = SamplerRuntimeFlag()
        val firstRenewQueue = dispatch_queue_create("sampler-runtime-first-renew", null)
        val secondRenewQueue = dispatch_queue_create("sampler-runtime-second-renew", null)

        fixture.sampler.start()
        try {
            dispatch_async(firstRenewQueue) {
                fixture.sampler.renewLease()
                firstRenewReturned.set()
            }
            samplerRuntimeEventually("the activation encoder was not entered") {
                activationEncoder.entered
            }
            dispatch_async(secondRenewQueue) {
                fixture.sampler.renewLease()
                secondRenewReturned.set()
            }
            samplerRuntimePause()
            assertFalse(firstRenewReturned.value)
            assertFalse(secondRenewReturned.value)

            activationEncoder.release()
            samplerRuntimeEventually("the activating renew did not return") {
                firstRenewReturned.value
            }
            samplerRuntimeEventually("the concurrent renew did not share the activation") {
                secondRenewReturned.value
            }
            assertEquals("WARMING", samplerRuntimeJsonField(fixture.state, "status"))
            assertEquals(1, activationEncoder.warmingEncodeCount)
            samplerRuntimeEventually("the FULL baseline was not captured") {
                fixture.collector.captureCount == 1
            }

            clock.set(1_000_000_000uL)
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the LIVE_FAST sample did not become ready") {
                samplerRuntimeJsonField(fixture.state, "status") == "READY"
            }
            fixture.sampler.renewLease()
            assertEquals("READY", samplerRuntimeJsonField(fixture.state, "status"))
            assertEquals(1, activationEncoder.warmingEncodeCount)

            clock.set(6_000_000_000uL)
            fixture.sampler.renewLease()
            assertEquals("WARMING", samplerRuntimeJsonField(fixture.state, "status"))
            assertEquals("1970-01-01T00:00:06Z", samplerRuntimeJsonField(fixture.state, "generatedAt"))
            samplerRuntimeEventually("the reactivated FULL baseline was not captured") {
                fixture.collector.captureCount == 3
            }
            assertEquals(
                listOf(
                    CollectionProfile.FULL,
                    CollectionProfile.LIVE_FAST,
                    CollectionProfile.FULL,
                ),
                fixture.collector.profiles,
            )
        } finally {
            activationEncoder.release()
            fixture.sampler.stop()
        }
    }

    @Test
    fun successAndFailureOverrunsSkipMissedSlotsWithoutOverlap() {
        val clock = SamplerRuntimeClock()
        val diagnostics = SamplerRuntimeRecorder<LiveUiSamplerDiagnostic>()
        val fixture = samplerRuntimeFixture(
            clock = clock,
            actions = listOf(
                {
                    clock.advance(400_000_000uL)
                    samplerRuntimeSnapshot(clock.peek())
                },
                {
                    clock.advance(1_200_000_000uL)
                    samplerRuntimeSnapshot(clock.peek())
                },
                {
                    clock.advance(1_200_000_000uL)
                    throw IllegalStateException("synthetic fast failure")
                },
                { samplerRuntimeSnapshot(clock.peek()) },
            ),
            diagnostics = diagnostics::add,
        )

        fixture.sampler.start()
        try {
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the baseline capture did not finish") {
                fixture.collector.captureCount == 1 && diagnostics.size == 1
            }
            assertEquals(400_000_000uL, clock.peek())

            clock.set(1_000_000_000uL)
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the successful overrun did not finish") {
                fixture.collector.captureCount == 2 && diagnostics.size == 2
            }
            assertEquals(2_200_000_000uL, clock.peek())
            samplerRuntimePause()
            assertEquals(2, fixture.collector.captureCount)

            clock.set(3_000_000_000uL)
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the failed overrun did not finish") {
                fixture.collector.captureCount == 3 && diagnostics.size == 3
            }
            assertEquals(4_200_000_000uL, clock.peek())
            samplerRuntimePause()
            assertEquals(3, fixture.collector.captureCount)

            clock.set(5_000_000_000uL)
            fixture.sampler.renewLease()
            samplerRuntimeEventually("sampling did not resume on the next future slot") {
                fixture.collector.captureCount == 4
            }
            val overrunDiagnostics = diagnostics.values.take(3)
            assertEquals(listOf(0uL, 1uL, 1uL), overrunDiagnostics.map { it.missedSlotCount })
            assertEquals(
                listOf(CollectionProfile.FULL, CollectionProfile.LIVE_FAST, CollectionProfile.LIVE_FAST),
                overrunDiagnostics.map { it.profile },
            )
            assertEquals(
                listOf(0uL to 400_000_000uL, 1_000_000_000uL to 2_200_000_000uL, 3_000_000_000uL to 4_200_000_000uL),
                overrunDiagnostics.map {
                    it.captureStartedAtNanoseconds to it.captureEndedAtNanoseconds
                },
            )
            assertTrue(overrunDiagnostics.all { it.inFlightCount == 1 })
            assertEquals(1, fixture.collector.maxInFlight)
        } finally {
            fixture.sampler.stop()
        }
    }

    @Test
    fun expiredGenerationRejectsItsLateResult() {
        val clock = SamplerRuntimeClock()
        val oldCapture = SamplerRuntimeGate()
        val fixture = samplerRuntimeFixture(
            clock = clock,
            actions = listOf(
                {
                    oldCapture.awaitOpen()
                    samplerRuntimeSnapshot(1_000_000_000uL)
                },
                { samplerRuntimeSnapshot(6_000_000_000uL) },
            ),
        )

        fixture.sampler.start()
        try {
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the old generation did not enter capture") {
                fixture.collector.inFlightCount == 1
            }

            clock.set(5_000_000_000uL)
            fixture.sampler.renewLease()
            val newGenerationJson = fixture.state.currentJson()
            assertEquals("1970-01-01T00:00:05Z", samplerRuntimeJsonField(fixture.state, "generatedAt"))

            clock.set(6_000_000_000uL)
            oldCapture.open()
            samplerRuntimeEventually("the new generation baseline was not captured") {
                fixture.collector.captureCount == 2
            }
            assertEquals(newGenerationJson, fixture.state.currentJson())
            assertEquals(listOf(CollectionProfile.FULL, CollectionProfile.FULL), fixture.collector.profiles)
        } finally {
            oldCapture.open()
            fixture.sampler.stop()
        }
    }

    @Test
    fun encodingFailureKeepsThePublishedPairAndRecoversOnCadence() {
        val clock = SamplerRuntimeClock()
        val encoder = SamplerRuntimeFailingReadyEncoder(failures = 2)
        val errors = SamplerRuntimeRecorder<String>()
        val fixture = samplerRuntimeFixture(
            clock = clock,
            actions = List(4) { index ->
                { samplerRuntimeSnapshot((index + 1).toULong() * 1_000_000_000uL) }
            },
            encoder = encoder::encode,
            errors = errors::add,
        )

        fixture.sampler.start()
        try {
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the baseline capture did not finish") {
                fixture.collector.captureCount == 1
            }
            val lastGoodJson = fixture.state.currentJson()

            clock.set(1_000_000_000uL)
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the first failed publication did not finish") {
                fixture.collector.captureCount == 2 && encoder.readyAttempts >= 1
            }
            assertEquals(lastGoodJson, fixture.state.currentJson())

            clock.set(2_000_000_000uL)
            fixture.sampler.renewLease()
            samplerRuntimeEventually("the second failed publication did not finish") {
                fixture.collector.captureCount == 3 && encoder.readyAttempts >= 2
            }
            assertEquals(lastGoodJson, fixture.state.currentJson())
            assertEquals(1, errors.values.count { "publish failed" in it })

            clock.set(3_000_000_000uL)
            fixture.sampler.renewLease()
            samplerRuntimeEventually("publication did not recover on the next cadence") {
                samplerRuntimeJsonField(fixture.state, "status") == "READY"
            }
            assertNull(samplerRuntimeNullableJsonField(fixture.state, "staleSince"))
            assertEquals(1, errors.values.count { "publish failed" in it })
        } finally {
            fixture.sampler.stop()
        }
    }

    @Test
    fun activationEncodingFailureClearsTheReservationAndRecoversWithoutDeadlock() {
        val clock = SamplerRuntimeClock(1_000_000_000uL)
        val encoder = SamplerRuntimeFailingWarmingEncoder()
        val errors = SamplerRuntimeRecorder<String>()
        val fixture = samplerRuntimeFixture(
            clock = clock,
            actions = listOf({ samplerRuntimeSnapshot(clock.peek()) }),
            encoder = encoder::encode,
            errors = errors::add,
        )
        val initialJson = fixture.state.currentJson()

        fixture.sampler.start()
        try {
            fixture.sampler.renewLease()
            assertEquals(initialJson, fixture.state.currentJson())
            assertEquals(1, errors.values.count { "publish failed" in it })

            samplerRuntimeEventually("sampling did not recover after activation encoding failed") {
                fixture.collector.captureCount == 1 && fixture.state.currentJson() != initialJson
            }
            assertEquals("WARMING", samplerRuntimeJsonField(fixture.state, "status"))
            assertEquals("1970-01-01T00:00:01Z", samplerRuntimeJsonField(fixture.state, "generatedAt"))
            assertEquals(2, encoder.warmingAttempts)
        } finally {
            fixture.sampler.stop()
        }
    }

    @Test
    fun stopIsBoundedRejectsTheEventualResultAndMakesSamplerSingleShot() {
        val clock = SamplerRuntimeClock()
        val captureGate = SamplerRuntimeGate()
        val errors = SamplerRuntimeRecorder<String>()
        val fixture = samplerRuntimeFixture(
            clock = clock,
            actions = listOf(
                {
                    captureGate.awaitOpen()
                    samplerRuntimeSnapshot(clock.peek())
                },
            ),
            errors = errors::add,
        )

        fixture.sampler.start()
        try {
            fixture.sampler.renewLease()
            samplerRuntimeEventually("capture did not enter before stop") {
                fixture.collector.inFlightCount == 1
            }
            val beforeStop = fixture.state.currentJson()
            clock.advanceOnRead(36_000_000_000uL)
            val stopStarted = TimeSource.Monotonic.markNow()

            fixture.sampler.stop()

            assertTrue(stopStarted.elapsedNow() < 1.seconds)
            assertEquals(LiveUiSamplerLifecycle.STOPPED, fixture.sampler.lifecycleState)
            assertTrue(errors.values.any { "stop timed out" in it })
            assertFailsWith<IllegalStateException> { fixture.sampler.start() }

            captureGate.open()
            samplerRuntimeEventually("the stopped capture did not drain") {
                fixture.collector.inFlightCount == 0
            }
            samplerRuntimePause()
            assertEquals(beforeStop, fixture.state.currentJson())
        } finally {
            captureGate.open()
            fixture.sampler.stop()
        }
    }
}

private data class SamplerRuntimeFixture(
    val sampler: LiveUiSampler,
    val state: LiveUiState,
    val collector: SamplerRuntimeCollector,
)

private typealias SamplerRuntimeCaptureAction = (CollectionProfile) -> RawSystemSnapshot

private fun samplerRuntimeFixture(
    clock: SamplerRuntimeClock,
    actions: List<SamplerRuntimeCaptureAction>,
    encoder: (WebUiPayload) -> String = WebUiPayloadJson::encode,
    diagnostics: (LiveUiSamplerDiagnostic) -> Unit = {},
    errors: (String) -> Unit = {},
): SamplerRuntimeFixture {
    val initial = WebUiPayloadFactory.warming(generatedAt = Instant.fromEpochSeconds(0))
    val state = LiveUiState(WebUiPayloadJson.encode(initial))
    val collector = SamplerRuntimeCollector(actions)
    return SamplerRuntimeFixture(
        sampler = LiveUiSampler(
            collector = collector,
            calculator = UsageCalculator(),
            sampleSeconds = 1,
            state = state,
            initialPayload = initial,
            logError = errors,
            monotonicNowNanoseconds = clock::now,
            wallClock = { Instant.fromEpochSeconds((clock.peek() / 1_000_000_000uL).toLong()) },
            encoder = encoder,
            diagnostics = diagnostics,
        ),
        state = state,
        collector = collector,
    )
}

private class SamplerRuntimeCollector(
    actions: List<SamplerRuntimeCaptureAction>,
) : SystemCollector {
    private val lock = NSLock()
    private val remaining = ArrayDeque(actions)
    private val recordedProfiles = mutableListOf<CollectionProfile>()
    private var captures = 0
    private var inFlight = 0
    private var highestInFlight = 0

    val captureCount: Int
        get() = locked { captures }

    val inFlightCount: Int
        get() = locked { inFlight }

    val maxInFlight: Int
        get() = locked { highestInFlight }

    val profiles: List<CollectionProfile>
        get() = locked { recordedProfiles.toList() }

    override fun capture(profile: CollectionProfile): RawSystemSnapshot {
        val action = locked {
            captures += 1
            inFlight += 1
            highestInFlight = maxOf(highestInFlight, inFlight)
            recordedProfiles += profile
            check(remaining.isNotEmpty()) { "unexpected sampler capture $captures with profile $profile" }
            remaining.removeFirst()
        }
        return try {
            action(profile)
        } finally {
            locked { inFlight -= 1 }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private class SamplerRuntimeClock(initial: ULong = 0uL) {
    private val lock = NSLock()
    private var value = initial
    private var advancePerRead = 0uL

    fun now(): ULong = locked {
        val current = value
        value = value.saturatingAddForSamplerRuntime(advancePerRead)
        current
    }

    fun peek(): ULong = locked { value }

    fun set(next: ULong) {
        locked { value = next }
    }

    fun advance(delta: ULong) {
        locked { value = value.saturatingAddForSamplerRuntime(delta) }
    }

    fun advanceOnRead(delta: ULong) {
        locked { advancePerRead = delta }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private class SamplerRuntimeGate {
    private val lock = NSLock()
    private var opened = false

    fun open() {
        locked { opened = true }
    }

    fun awaitOpen() {
        samplerRuntimeEventually("test gate was not opened") { locked { opened } }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private class SamplerRuntimeFlag {
    private val lock = NSLock()
    private var current = false

    val value: Boolean
        get() = locked { current }

    fun set() {
        locked { current = true }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private class SamplerRuntimeRecorder<T> {
    private val lock = NSLock()
    private val recorded = mutableListOf<T>()

    val size: Int
        get() = locked { recorded.size }

    val values: List<T>
        get() = locked { recorded.toList() }

    fun add(value: T) {
        locked { recorded += value }
    }

    private inline fun <R> locked(block: () -> R): R {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private class SamplerRuntimeActivationEncoder {
    private val lock = NSLock()
    private val gate = SamplerRuntimeGate()
    private var enteredActivation = false
    private var warmingEncodes = 0

    val entered: Boolean
        get() = locked { enteredActivation }

    val warmingEncodeCount: Int
        get() = locked { warmingEncodes }

    fun encode(payload: WebUiPayload): String {
        if (payload.status == WebUiStatus.WARMING) {
            val shouldBlock = locked {
                warmingEncodes += 1
                if (!enteredActivation) {
                    enteredActivation = true
                    true
                } else {
                    false
                }
            }
            if (shouldBlock) gate.awaitOpen()
        }
        return WebUiPayloadJson.encode(payload)
    }

    fun release() {
        gate.open()
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private class SamplerRuntimeFailingReadyEncoder(
    private var failures: Int,
) {
    private val lock = NSLock()
    private var attempts = 0

    val readyAttempts: Int
        get() = locked { attempts }

    fun encode(payload: WebUiPayload): String {
        val fail = locked {
            if (payload.status != WebUiStatus.READY) return@locked false
            attempts += 1
            if (failures == 0) return@locked false
            failures -= 1
            true
        }
        if (fail) throw IllegalStateException("synthetic encoding failure")
        return WebUiPayloadJson.encode(payload)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private class SamplerRuntimeFailingWarmingEncoder {
    private val lock = NSLock()
    private var attempts = 0

    val warmingAttempts: Int
        get() = locked { attempts }

    fun encode(payload: WebUiPayload): String {
        val fail = locked {
            if (payload.status != WebUiStatus.WARMING) return@locked false
            attempts += 1
            attempts == 1
        }
        if (fail) throw IllegalStateException("synthetic activation encoding failure")
        return WebUiPayloadJson.encode(payload)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private fun samplerRuntimeSnapshot(monotonicNanoseconds: ULong): RawSystemSnapshot =
    RawSystemSnapshot(
        capturedAt = Instant.fromEpochSeconds((monotonicNanoseconds / 1_000_000_000uL).toLong()),
        monotonicTimeNs = monotonicNanoseconds,
        physicalMemoryBytes = 32uL * 1_073_741_824uL,
        swap = SwapUsage(
            totalBytes = 4uL * 1_073_741_824uL,
            availableBytes = 4uL * 1_073_741_824uL,
            usedBytes = 0uL,
            encrypted = true,
        ),
        power = PowerState(
            batteryAvailable = true,
            onBattery = false,
            charging = false,
            percentage = 100,
            minutesRemaining = null,
        ),
        processor = ProcessorCounters(
            userTicks = monotonicNanoseconds / 1_000_000uL,
            systemTicks = monotonicNanoseconds / 2_000_000uL,
            idleTicks = monotonicNanoseconds / 1_000_000uL,
            niceTicks = 0uL,
        ),
        loadAverages = LoadAverages(1.0, 0.8, 0.5),
        virtualMemory = VirtualMemoryCounters(
            pageSizeBytes = 16_384uL,
            freeBytes = 4uL * 1_073_741_824uL,
            activeBytes = 8uL * 1_073_741_824uL,
            inactiveBytes = 8uL * 1_073_741_824uL,
            wiredBytes = 4uL * 1_073_741_824uL,
            purgeableBytes = 128uL * 1_048_576uL,
            compressedBytes = 2uL * 1_073_741_824uL,
            uncompressedBytesInCompressor = 4uL * 1_073_741_824uL,
            swapBackedUncompressedBytes = 0uL,
            pageIns = monotonicNanoseconds / 10_000_000uL,
            pageOuts = monotonicNanoseconds / 20_000_000uL,
            faults = monotonicNanoseconds / 1_000_000uL,
            copyOnWriteFaults = monotonicNanoseconds / 2_000_000uL,
            compressions = monotonicNanoseconds / 5_000_000uL,
            decompressions = monotonicNanoseconds / 6_000_000uL,
            swapIns = monotonicNanoseconds / 20_000_000uL,
            swapOuts = monotonicNanoseconds / 25_000_000uL,
        ),
        storage = StorageCounters(
            available = true,
            deviceCount = 1,
            bytesRead = monotonicNanoseconds,
            bytesWritten = monotonicNanoseconds / 2uL,
            readOperations = monotonicNanoseconds / 1_000_000uL,
            writeOperations = monotonicNanoseconds / 2_000_000uL,
            readTimeNs = monotonicNanoseconds / 4uL,
            writeTimeNs = monotonicNanoseconds / 5uL,
            rootFileSystemTotalBytes = 1_000_000_000_000uL,
            rootFileSystemAvailableBytes = 500_000_000_000uL,
        ),
        totalProcessCount = 0,
        inaccessibleProcessCount = 0,
        compressedAttributionProcessCount = 0,
        compressedAttributionFailureCount = 0,
        processes = emptyList(),
        processIssues = emptyList(),
    )

private fun samplerRuntimeJsonField(state: LiveUiState, name: String): String =
    Json.parseToJsonElement(state.currentJson()).jsonObject.getValue(name).jsonPrimitive.content

private fun samplerRuntimeNullableJsonField(state: LiveUiState, name: String): String? {
    val value = Json.parseToJsonElement(state.currentJson()).jsonObject.getValue(name).jsonPrimitive
    return value.content.takeUnless { value.content == "null" }
}

private fun samplerRuntimeEventually(
    failureMessage: String,
    condition: () -> Boolean,
) {
    val started = TimeSource.Monotonic.markNow()
    while (!condition()) {
        check(started.elapsedNow() < 3.seconds) { failureMessage }
        usleep(1_000u)
    }
}

private fun samplerRuntimePause() {
    usleep(50_000u)
}

private fun ULong.saturatingAddForSamplerRuntime(other: ULong): ULong =
    if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other
