package dev.yoda.harmon.web

import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.CollectionProfile
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.report.WebUiPayload
import dev.yoda.harmon.report.WebUiPayloadFactory
import dev.yoda.harmon.report.WebUiPayloadJson
import dev.yoda.harmon.report.WebUiStatus
import dev.yoda.harmon.runtime.LiveSamplingLease
import dev.yoda.harmon.runtime.LiveSamplingSession
import dev.yoda.harmon.util.failureDescription
import platform.Foundation.NSLock
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

enum class LiveUiSamplerLifecycle {
    NEW,
    RUNNING,
    STOPPED,
}

data class LiveUiSamplerDiagnostic(
    val profile: CollectionProfile,
    val captureStartedAtNanoseconds: ULong,
    val captureEndedAtNanoseconds: ULong,
    val missedSlotCount: ULong,
    val inFlightCount: Int,
)

/** Returns the first cadence tick strictly after [captureEndedAtNanoseconds]. */
fun nextLiveSampleDeadlineNanoseconds(
    captureStartedAtNanoseconds: ULong,
    captureEndedAtNanoseconds: ULong,
    samplePeriodNanoseconds: ULong,
): ULong {
    require(samplePeriodNanoseconds > 0uL) { "samplePeriodNanoseconds must be positive" }
    val elapsed = if (captureEndedAtNanoseconds > captureStartedAtNanoseconds) {
        captureEndedAtNanoseconds - captureStartedAtNanoseconds
    } else {
        0uL
    }
    val completedTicks = elapsed / samplePeriodNanoseconds
    val nextTick = completedTicks.saturatingIncrement()
    return captureStartedAtNanoseconds.saturatingAdd(
        nextTick.saturatingMultiply(samplePeriodNanoseconds),
    )
}

class LiveUiSampler(
    private val collector: SystemCollector,
    private val calculator: UsageCalculator,
    private val sampleSeconds: Long,
    private val state: LiveUiState,
    initialPayload: WebUiPayload,
    private val logError: (String) -> Unit,
    private val monotonicNowNanoseconds: () -> ULong = monotonicNanosecondClock(),
    private val wallClock: () -> Instant = Clock.System::now,
    private val encoder: (WebUiPayload) -> String = WebUiPayloadJson::encode,
    private val diagnostics: (LiveUiSamplerDiagnostic) -> Unit = {},
) {
    private val lifecycleLock = LiveUiSamplerCondition()
    private val queue = dispatch_queue_create("dev.yoda.harmon.web.sample", null)
    private val lease = LiveSamplingLease(sampleSeconds)
    private val samplePeriodNanoseconds =
        sampleSeconds.saturatingMultiply(NANOSECONDS_PER_SECOND).toULong()
    private val diagnosticCollector = object : SystemCollector {
        override fun capture(profile: CollectionProfile): RawSystemSnapshot {
            if (captureProfile == null) captureProfile = profile
            return collector.capture(profile)
        }
    }

    private var lifecycle = LiveUiSamplerLifecycle.NEW
    private var loopFinished = true
    private var inFlightCount = 0
    private var activeGeneration: ULong? = null
    private var activeSession: LiveSamplingSession? = null
    private var activationPendingGeneration: ULong? = null
    private var warmingPublishedGeneration: ULong? = null
    private var activationTimeoutLoggedGeneration: ULong? = null
    private var encodeFailureLoggedGeneration: ULong? = null
    private var lastPublished = PublishedPayload(initialPayload, state.currentJson())
    private var captureProfile: CollectionProfile? = null

    init {
        require(sampleSeconds > 0) { "sampleSeconds must be positive" }
    }

    val lifecycleState: LiveUiSamplerLifecycle
        get() = withLifecycleLock { lifecycle }

    fun start() {
        lifecycleLock.lock()
        try {
            check(lifecycle == LiveUiSamplerLifecycle.NEW) {
                when (lifecycle) {
                    LiveUiSamplerLifecycle.RUNNING -> "live UI sampler is already running"
                    LiveUiSamplerLifecycle.STOPPED -> "live UI sampler cannot be restarted"
                    LiveUiSamplerLifecycle.NEW -> error("unreachable")
                }
            }
            lifecycle = LiveUiSamplerLifecycle.RUNNING
            loopFinished = false
        } finally {
            lifecycleLock.unlock()
        }
        dispatch_async(queue) { sampleLoop() }
    }

    fun stop() {
        var drainTimedOut = false
        lifecycleLock.lock()
        try {
            if (lifecycle == LiveUiSamplerLifecycle.STOPPED) return
            lifecycle = LiveUiSamplerLifecycle.STOPPED
            activeGeneration = null
            activeSession = null
            activationPendingGeneration = null
            lifecycleLock.broadcast()

            val deadline = monotonicNowNanoseconds().saturatingAdd(STOP_DRAIN_TIMEOUT_NANOSECONDS)
            while (!loopFinished) {
                val now = monotonicNowNanoseconds()
                if (now >= deadline) {
                    drainTimedOut = true
                    break
                }
                waitForSignalLocked(deadline)
            }
        } finally {
            lifecycleLock.unlock()
        }
        if (drainTimedOut) {
            logError("live UI sampler stop timed out while waiting for capture drain")
        }
    }

    /** Only an authenticated `/api/live?...&watch=1` request calls this. */
    fun renewLease() {
        var reservation: ActivationReservation? = null
        var activationWaitTimedOut = false
        var waitedGeneration: ULong? = null

        lifecycleLock.lock()
        try {
            if (lifecycle != LiveUiSamplerLifecycle.RUNNING) return
            val now = monotonicNowNanoseconds()
            val previousGeneration = lease.activeGeneration(now)
            val generation = lease.renew(now)
            lifecycleLock.broadcast()

            if (previousGeneration == null) {
                activationPendingGeneration = generation
                activationTimeoutLoggedGeneration = null
                reservation = ActivationReservation(generation, lastPublished)
            } else if (activationPendingGeneration == generation) {
                waitedGeneration = generation
                val deadline = now.saturatingAdd(ACTIVATION_WAIT_TIMEOUT_NANOSECONDS)
                while (
                    lifecycle == LiveUiSamplerLifecycle.RUNNING &&
                    activationPendingGeneration == generation
                ) {
                    val waitNow = monotonicNowNanoseconds()
                    if (waitNow >= deadline) {
                        activationWaitTimedOut =
                            activationTimeoutLoggedGeneration != generation
                        activationTimeoutLoggedGeneration = generation
                        break
                    }
                    waitForSignalLocked(deadline)
                }
            }
        } finally {
            lifecycleLock.unlock()
        }

        if (activationWaitTimedOut) {
            logError(
                "live UI activation for generation $waitedGeneration is still pending after 2 seconds",
            )
        }
        reservation?.let(::activate)
    }

    private fun activate(reservation: ActivationReservation) {
        var session: LiveSamplingSession? = null
        var warming: WebUiPayload? = null
        var encoded: String? = null
        var failure: Throwable? = null
        try {
            session = newSession(reservation.previous.payload)
            warming = session.warmingPayload()
            encoded = encoder(warming)
        } catch (caught: Throwable) {
            failure = caught
        }

        var logFailure = false
        lifecycleLock.lock()
        try {
            if (activationPendingGeneration != reservation.generation) return
            try {
                if (acceptsLocked(reservation.generation) && session != null) {
                    activeGeneration = reservation.generation
                    activeSession = session
                    if (warming != null && encoded != null) {
                        state.update(encoded)
                        lastPublished = PublishedPayload(warming, encoded)
                        warmingPublishedGeneration = reservation.generation
                        encodeFailureLoggedGeneration = null
                    } else if (failure != null) {
                        logFailure = markEncodeFailureLocked(reservation.generation)
                    }
                }
            } finally {
                activationPendingGeneration = null
                lifecycleLock.broadcast()
            }
        } finally {
            lifecycleLock.unlock()
        }
        if (logFailure) {
            logError("live UI sample publish failed: ${failureDescription(failure!!)}")
        }
    }

    private fun newSession(previousPayload: WebUiPayload): LiveSamplingSession =
        LiveSamplingSession(
            collector = diagnosticCollector,
            calculator = calculator,
            sampleSeconds = sampleSeconds,
            now = wallClock,
            monotonicNowNanoseconds = monotonicNowNanoseconds,
            previousPayload = previousPayload,
        )

    private fun sampleLoop() {
        try {
            while (true) {
                val work = awaitCaptureWork() ?: return
                sample(work)
            }
        } finally {
            lifecycleLock.lock()
            try {
                loopFinished = true
                lifecycleLock.broadcast()
            } finally {
                lifecycleLock.unlock()
            }
        }
    }

    private fun sample(work: CaptureWork) {
        val captureStartedAt = monotonicNowNanoseconds()
        captureProfile = null
        var profile = CollectionProfile.FULL
        try {
            val payload = work.session.capture()
            profile = captureProfile ?: payload.appliedProfile ?: CollectionProfile.FULL
            publishCaptureIfCurrent(work.generation, payload)
        } catch (failure: Throwable) {
            profile = captureProfile ?: CollectionProfile.FULL
            logError("live UI sample failed: ${failureDescription(failure)}")
        } finally {
            val captureEndedAt = monotonicNowNanoseconds()
            val deadline = nextLiveSampleDeadlineNanoseconds(
                captureStartedAtNanoseconds = captureStartedAt,
                captureEndedAtNanoseconds = captureEndedAt,
                samplePeriodNanoseconds = samplePeriodNanoseconds,
            )
            val missedSlots = missedSampleSlots(
                captureStartedAtNanoseconds = captureStartedAt,
                captureEndedAtNanoseconds = captureEndedAt,
                samplePeriodNanoseconds = samplePeriodNanoseconds,
            )
            finishCapture()
            diagnostics(
                LiveUiSamplerDiagnostic(
                    profile = profile,
                    captureStartedAtNanoseconds = captureStartedAt,
                    captureEndedAtNanoseconds = captureEndedAt,
                    missedSlotCount = missedSlots,
                    inFlightCount = work.inFlightCount,
                ),
            )
            waitForNextSample(work.generation, deadline)
        }
    }

    private fun publishCaptureIfCurrent(generation: ULong, payload: WebUiPayload) {
        val duplicateWarming = withLifecycleLock {
            acceptsLocked(generation) &&
                payload.status == WebUiStatus.WARMING &&
                payload.error == null &&
                warmingPublishedGeneration == generation
        }
        if (duplicateWarming) return

        val encoded = try {
            encoder(payload)
        } catch (failure: Throwable) {
            val shouldLog = withLifecycleLock {
                acceptsLocked(generation) && markEncodeFailureLocked(generation)
            }
            if (shouldLog) {
                logError("live UI sample publish failed: ${failureDescription(failure)}")
            }
            return
        }

        lifecycleLock.lock()
        try {
            if (!acceptsLocked(generation) || activeGeneration != generation) return
            state.update(encoded)
            lastPublished = PublishedPayload(payload, encoded)
            if (payload.status == WebUiStatus.WARMING) {
                warmingPublishedGeneration = generation
            }
            encodeFailureLoggedGeneration = null
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun awaitCaptureWork(): CaptureWork? {
        lifecycleLock.lock()
        try {
            while (lifecycle == LiveUiSamplerLifecycle.RUNNING) {
                val now = monotonicNowNanoseconds()
                val generation = lease.activeGeneration(now)
                if (generation == null) {
                    activeGeneration = null
                    activeSession = null
                    waitForSignalLocked(now.saturatingAdd(IDLE_WAIT_NANOSECONDS))
                    continue
                }
                if (activationPendingGeneration == generation) {
                    val deadline = now.saturatingAdd(PENDING_WAIT_SLICE_NANOSECONDS)
                    waitForSignalLocked(deadline)
                    continue
                }
                val session = activeSession.takeIf { activeGeneration == generation }
                if (session == null) {
                    val deadline = now.saturatingAdd(
                        minOf(PENDING_WAIT_SLICE_NANOSECONDS, lease.remainingNanoseconds(now)),
                    )
                    waitForSignalLocked(deadline)
                    continue
                }
                inFlightCount += 1
                return CaptureWork(generation, session, inFlightCount)
            }
            return null
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun finishCapture() {
        lifecycleLock.lock()
        try {
            inFlightCount -= 1
            check(inFlightCount >= 0) { "live UI sampler in-flight count underflow" }
            lifecycleLock.broadcast()
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun waitForNextSample(generation: ULong, deadline: ULong) {
        lifecycleLock.lock()
        try {
            while (acceptsLocked(generation)) {
                val now = monotonicNowNanoseconds()
                if (now >= deadline) return
                val leaseDeadline = now.saturatingAdd(lease.remainingNanoseconds(now))
                waitForSignalLocked(minOf(deadline, leaseDeadline))
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun waitForSignalLocked(deadlineNanoseconds: ULong) {
        val now = monotonicNowNanoseconds()
        if (now >= deadlineNanoseconds) return
        val remaining = deadlineNanoseconds - now
        val slice = minOf(remaining, CONDITION_WAIT_SLICE_NANOSECONDS)
        lifecycleLock.waitForNanoseconds(slice)
    }

    private fun acceptsLocked(generation: ULong): Boolean =
        lifecycle == LiveUiSamplerLifecycle.RUNNING &&
            lease.accepts(generation, monotonicNowNanoseconds())

    private fun markEncodeFailureLocked(generation: ULong): Boolean {
        if (encodeFailureLoggedGeneration == generation) return false
        encodeFailureLoggedGeneration = generation
        return true
    }

    private inline fun <T> withLifecycleLock(block: () -> T): T {
        lifecycleLock.lock()
        return try {
            block()
        } finally {
            lifecycleLock.unlock()
        }
    }

    private data class PublishedPayload(
        val payload: WebUiPayload,
        val json: String,
    )

    private data class ActivationReservation(
        val generation: ULong,
        val previous: PublishedPayload,
    )

    private data class CaptureWork(
        val generation: ULong,
        val session: LiveSamplingSession,
        val inFlightCount: Int,
    )

    private companion object {
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val ACTIVATION_WAIT_TIMEOUT_NANOSECONDS = 2_000_000_000uL
        const val STOP_DRAIN_TIMEOUT_NANOSECONDS = 35_000_000_000uL
        const val CONDITION_WAIT_SLICE_NANOSECONDS = 100_000_000uL
        const val PENDING_WAIT_SLICE_NANOSECONDS = 100_000_000uL
        const val IDLE_WAIT_NANOSECONDS = 250_000_000uL
    }
}

/** Condition-style broadcast with relative dispatch deadlines, which use the monotonic clock. */
private class LiveUiSamplerCondition {
    private val lock = NSLock()
    private val wakeup = dispatch_semaphore_create(0)
    private var waiterCount = 0

    fun lock() {
        lock.lock()
    }

    fun unlock() {
        lock.unlock()
    }

    fun broadcast() {
        repeat(waiterCount) { dispatch_semaphore_signal(wakeup) }
    }

    fun waitForNanoseconds(timeoutNanoseconds: ULong) {
        waiterCount += 1
        lock.unlock()
        try {
            dispatch_semaphore_wait(
                wakeup,
                dispatch_time(DISPATCH_TIME_NOW, timeoutNanoseconds.toLong()),
            )
        } finally {
            lock.lock()
            waiterCount -= 1
        }
    }
}

class LiveUiRuntime(
    private val state: LiveUiState,
    private val sampler: LiveUiSampler,
    private val token: String,
    private val endpointStore: LiveUiEndpointStore,
    logError: (String) -> Unit,
) {
    private val server = LiveUiServer(
        state = state,
        token = token,
        onWatch = sampler::renewLease,
        logError = logError,
    )
    private var lifecycle = LiveUiSamplerLifecycle.NEW
    private var endpoint: LiveUiEndpoint? = null

    fun start(): LiveUiEndpoint {
        check(lifecycle == LiveUiSamplerLifecycle.NEW) {
            when (lifecycle) {
                LiveUiSamplerLifecycle.RUNNING -> "live UI runtime is already running"
                LiveUiSamplerLifecycle.STOPPED -> "live UI runtime cannot be restarted"
                LiveUiSamplerLifecycle.NEW -> error("unreachable")
            }
        }
        lifecycle = LiveUiSamplerLifecycle.RUNNING
        val started = try {
            LiveUiEndpoint(server.start(), token)
        } catch (failure: Throwable) {
            lifecycle = LiveUiSamplerLifecycle.STOPPED
            sampler.stop()
            throw failure
        }
        try {
            endpointStore.publish(started)
            sampler.start()
        } catch (failure: Throwable) {
            lifecycle = LiveUiSamplerLifecycle.STOPPED
            sampler.stop()
            server.stop()
            endpointStore.removeIfCurrent(started)
            throw failure
        }
        endpoint = started
        return started
    }

    fun stop() {
        if (lifecycle == LiveUiSamplerLifecycle.STOPPED) return
        lifecycle = LiveUiSamplerLifecycle.STOPPED
        val current = endpoint
        endpoint = null
        sampler.stop()
        server.stop()
        if (current != null) endpointStore.removeIfCurrent(current)
    }

    companion object {
        fun production(
            collector: SystemCollector,
            terminalApplications: Set<String>,
            sampleSeconds: Long,
            endpointStore: LiveUiEndpointStore,
            token: String,
            logError: (String) -> Unit,
        ): LiveUiRuntime {
            val warming = WebUiPayloadFactory.warming(sampleSeconds.toDouble())
            val state = LiveUiState(WebUiPayloadJson.encode(warming))
            return LiveUiRuntime(
                state = state,
                sampler = LiveUiSampler(
                    collector = collector,
                    calculator = UsageCalculator(terminalApplications),
                    sampleSeconds = sampleSeconds,
                    state = state,
                    initialPayload = warming,
                    logError = logError,
                ),
                token = token,
                endpointStore = endpointStore,
                logError = logError,
            )
        }
    }
}

private fun monotonicNanosecondClock(): () -> ULong {
    val origin = TimeSource.Monotonic.markNow()
    return {
        origin.elapsedNow().inWholeNanoseconds.coerceAtLeast(0).toULong()
    }
}

private fun missedSampleSlots(
    captureStartedAtNanoseconds: ULong,
    captureEndedAtNanoseconds: ULong,
    samplePeriodNanoseconds: ULong,
): ULong {
    if (captureEndedAtNanoseconds <= captureStartedAtNanoseconds) return 0uL
    return (captureEndedAtNanoseconds - captureStartedAtNanoseconds) / samplePeriodNanoseconds
}

private fun Long.saturatingMultiply(other: Long): Long =
    if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private fun ULong.saturatingAdd(other: ULong): ULong =
    if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

private fun ULong.saturatingMultiply(other: ULong): ULong = when {
    this == 0uL || other == 0uL -> 0uL
    ULong.MAX_VALUE / other < this -> ULong.MAX_VALUE
    else -> this * other
}

private fun ULong.saturatingIncrement(): ULong =
    if (this == ULong.MAX_VALUE) this else this + 1uL
