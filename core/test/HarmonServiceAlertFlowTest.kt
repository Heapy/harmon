import io.heapy.harmon.analysis.DELIVERY_RETRY_THRESHOLD
import io.heapy.harmon.config.HarmonConfig
import io.heapy.harmon.config.NotificationConfig
import io.heapy.harmon.config.SAMPLE_SECONDS_RANGE
import io.heapy.harmon.model.NotificationPayload
import io.heapy.harmon.model.RawSystemSnapshot
import io.heapy.harmon.monitor.CollectionProfile
import io.heapy.harmon.monitor.SystemCollector
import io.heapy.harmon.notify.NotificationChannel
import io.heapy.harmon.notify.NotificationDispatcher
import io.heapy.harmon.runtime.HarmonService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MIB = 1_048_576uL

private val ALERTING_FOOTPRINT = 5_000uL * MIB

private val QUIET_FOOTPRINT = 512uL * MIB

private val HYSTERESIS_FOOTPRINT = 1_950uL * MIB

class HarmonServiceAlertFlowTest {
    @Test
    fun pushesAnAlertOnceWhileItKeepsFiring() {
        val channel = RecordingChannel()
        val service = serviceWith(channel)

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(snapshot(1uL, ALERTING_FOOTPRINT), snapshot(2uL, ALERTING_FOOTPRINT))

        assertEquals(1, channel.payloads.size)
        assertTrue(channel.payloads.single().text.endsWith("memory"))
    }

    @Test
    fun retriesTheAlertAfterAFailedDelivery() {
        val channel = RecordingChannel(successful = { false })
        val service = serviceWith(channel)

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(snapshot(1uL, ALERTING_FOOTPRINT), snapshot(2uL, ALERTING_FOOTPRINT))

        assertEquals(2, channel.payloads.size)
    }

    @Test
    fun spreadsOutRetriesOfAnAlertWhoseDeliveryNeverSucceeds() {
        val channel = RecordingChannel(successful = { false })
        val errors = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazyOf(NotificationDispatcher(listOf(channel))),
            log = {},
            logError = { errors += it },
        )

        repeat(12) { index ->
            val started = index.toULong()
            service.handleSample(
                snapshot(started, ALERTING_FOOTPRINT),
                snapshot(started + 1uL, ALERTING_FOOTPRINT),
            )
        }

        assertEquals(5, channel.payloads.size)
        assertTrue(errors.any { "retrying it in" in it }, errors.toString())
    }

    @Test
    fun pushesAgainAfterTheAlertClearedOnASampleWithoutDeliveries() {
        val channel = RecordingChannel()
        val service = serviceWith(channel)

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(snapshot(1uL, QUIET_FOOTPRINT), snapshot(2uL, QUIET_FOOTPRINT))
        service.handleSample(snapshot(2uL, ALERTING_FOOTPRINT), snapshot(3uL, ALERTING_FOOTPRINT))

        assertEquals(2, channel.payloads.size)
    }

    @Test
    fun notifyEverySampleDeliversEverySampleWithEveryAlert() {
        val channel = RecordingChannel()
        val service = serviceWith(channel, notifyEverySample = true)

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(snapshot(1uL, ALERTING_FOOTPRINT), snapshot(2uL, ALERTING_FOOTPRINT))
        service.handleSample(snapshot(2uL, QUIET_FOOTPRINT), snapshot(3uL, QUIET_FOOTPRINT))

        assertEquals(3, channel.payloads.size)
        assertTrue(channel.payloads[0].text.endsWith("memory"))
        assertTrue(channel.payloads[1].text.endsWith("memory"))
        assertEquals("Harmon: system sample", channel.payloads[2].title)
    }

    @Test
    fun notifyEverySampleStillNamesOnlyTheAlertsThatAreNewOnThisSample() {
        val channel = RecordingChannel()
        val service = serviceWith(channel, notifyEverySample = true)

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(snapshot(1uL, ALERTING_FOOTPRINT), snapshot(2uL, ALERTING_FOOTPRINT))

        assertEquals(1, newAlertKeysOf(channel.payloads[0]).size)
        assertEquals(emptyList(), newAlertKeysOf(channel.payloads[1]))
        assertTrue(channel.payloads[1].text.endsWith("memory"), "the push still carries it")
    }

    @Test
    fun notifyEverySamplePushesEverySampleAndNamesTheRecoveredKeyAsNew() {
        val channel = RecordingChannel(
            successful = { delivery -> delivery > DELIVERY_RETRY_THRESHOLD },
        )
        val errors = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(notifyEverySample = true),
            collector = UnusedCollector,
            notifications = lazyOf(NotificationDispatcher(listOf(channel))),
            log = {},
            logError = { errors += it },
        )

        repeat(DELIVERY_RETRY_THRESHOLD + 1) { index ->
            val started = index.toULong()
            service.handleSample(
                snapshot(started, ALERTING_FOOTPRINT),
                snapshot(started + 1uL, ALERTING_FOOTPRINT),
            )
        }

        assertEquals(DELIVERY_RETRY_THRESHOLD + 1, channel.payloads.size)
        assertTrue(errors.none { "retrying it in" in it }, errors.toString())
        val landed = channel.payloads.last()
        assertEquals(1, newAlertKeysOf(landed).size, "the landed push named no new alert")
        assertTrue(landed.text.endsWith("memory"))
    }

    @Test
    fun namesEveryKeyOverThresholdThatDidNotFitTheCappedReport() {
        val channel = RecordingChannel()
        val reports = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazyOf(NotificationDispatcher(listOf(channel))),
            log = { reports += it },
            logError = {},
        )
        val crowded = listOf(5_000uL, 4_000uL, 3_000uL, 2_500uL).map { it * MIB }

        service.handleSample(crowdedSnapshot(0uL, crowded), crowdedSnapshot(1uL, crowded))

        assertContains(
            reports.last { it.startsWith("Harmon sample") },
            "1 more matching, past maxAlertsPerCategory: memory:process:13:100",
        )
        assertEquals(
            listOf("memory:process:13:100"),
            suppressedAlertKeysOf(channel.payloads.last()),
        )

        val overtaken = listOf(5_000uL, 4_000uL, 3_000uL, 6_000uL).map { it * MIB }
        service.handleSample(crowdedSnapshot(1uL, overtaken), crowdedSnapshot(2uL, overtaken))

        assertContains(
            reports.last { it.startsWith("Harmon sample") },
            "1 more matching, past maxAlertsPerCategory: memory:process:12:100",
        )
        assertEquals(
            listOf("memory:process:12:100"),
            suppressedAlertKeysOf(channel.payloads.last()),
        )
    }

    @Test
    fun anEmptyDispatcherDoesNotBlockTheStateUpdate() {
        val reports = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazyOf(NotificationDispatcher(emptyList())),
            log = { reports += it },
            logError = { reports += it },
        )

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(
            snapshot(1uL, HYSTERESIS_FOOTPRINT),
            snapshot(2uL, HYSTERESIS_FOOTPRINT),
        )

        assertEquals(2, reports.size)
        assertTrue(reports[0].contains("Alerts:"))
        assertTrue(reports[1].contains("Alerts:"))
    }

    @Test
    fun aFootprintBelowTheThresholdAlertsOnlyBecauseOfTheCommittedState() {
        val reports = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazyOf(NotificationDispatcher(emptyList())),
            log = { reports += it },
            logError = { reports += it },
        )

        service.handleSample(
            snapshot(0uL, HYSTERESIS_FOOTPRINT),
            snapshot(1uL, HYSTERESIS_FOOTPRINT),
        )

        assertFalse(reports.single().contains("Alerts:"))
    }

    @Test
    fun aFailedCaptureIsLoggedAndLeavesTheWindowWhereItWas() {
        val reports = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val first = snapshot(0uL, ALERTING_FOOTPRINT)
        val second = snapshot(1uL, ALERTING_FOOTPRINT)
        val service = HarmonService(
            config = alertConfig(),
            collector = FlakyCollector(second),
            notifications = lazyOf(NotificationDispatcher(emptyList())),
            log = { reports += it },
            logError = { errors += it },
        )

        val afterFailure = service.runCycle(first)
        val afterSuccess = service.runCycle(afterFailure)

        assertEquals(first, afterFailure)
        assertEquals(second, afterSuccess)
        assertContains(errors.single(), "collection failed")
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("Alerts:"))
    }

    @Test
    fun aSampleThatBlowsUpIsNotReplayedAgainstTheNextCapture() {
        val reports = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(),
            collector = ScriptedCollector(
                snapshot(1uL, QUIET_FOOTPRINT),
                snapshot(2uL, QUIET_FOOTPRINT),
            ),
            notifications = lazyOf(NotificationDispatcher(emptyList())),
            log = { reports += it },
            logError = { errors += it },
        )

        val afterFailure = service.runCycle(snapshot(5uL, QUIET_FOOTPRINT))
        service.runCycle(afterFailure)

        assertContains(errors.single(), "sample handling failed")
        assertEquals(1, reports.size)
    }

    @Test
    fun sampleOnceWaitsOutItsWindowWithoutBuildingTheDispatcher() {
        var initializations = 0
        val collector = ScriptedCollector(
            snapshot(0uL, ALERTING_FOOTPRINT),
            snapshot(1uL, ALERTING_FOOTPRINT),
        )
        val service = HarmonService(
            config = alertConfig(),
            collector = collector,
            notifications = lazy {
                initializations += 1
                NotificationDispatcher(listOf(RecordingChannel()))
            },
            log = {},
            logError = {},
        )
        val started = TimeSource.Monotonic.markNow()

        service.sampleOnce(sampleSeconds = 1)

        assertTrue(started.elapsedNow() >= 1.seconds, "the sample window was not waited out")
        assertEquals(0, initializations)
        assertEquals(listOf(CollectionProfile.FULL, CollectionProfile.FULL), collector.profiles)
    }

    @Test
    fun rejectsASampleWindowOutsideTheSharedRange() {
        val service = serviceWith(RecordingChannel())

        listOf(SAMPLE_SECONDS_RANGE.first - 1, SAMPLE_SECONDS_RANGE.last + 1).forEach { seconds ->
            val failure = assertFailsWith<IllegalArgumentException> {
                service.sampleOnce(seconds)
            }

            assertContains(assertNotNull(failure.message), "sampleSeconds must be between")
        }
    }

    @Test
    fun deliverPushesTheWholeReportAndTreatsEveryAlertAsNew() {
        val channel = RecordingChannel()
        val service = serviceWith(channel)
        val report = rankingReport()

        val results = service.deliver(report, reportText = "already rendered elsewhere")

        assertEquals(listOf(true), results.map { it.successful })
        assertContains(channel.payloads.single().html, "already rendered elsewhere")
        assertEquals(report.alerts.map { it.key }, newAlertKeysOf(channel.payloads.single()))
    }

    @Test
    fun testNotificationsPushesTheFixedTestPayload() {
        val channel = RecordingChannel()
        val service = serviceWith(channel)

        val results = service.testNotifications()

        assertEquals(listOf(true), results.map { it.successful })
        assertEquals("Notification test", channel.payloads.single().subtitle)
    }

    @Test
    fun logsAFailedChannelToTheErrorStreamAndASucceedingOneToTheNormalOne() {
        val messages = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazyOf(
                NotificationDispatcher(
                    listOf(
                        RecordingChannel(name = "good"),
                        RecordingChannel(name = "bad", successful = { false }),
                    ),
                ),
            ),
            log = { messages += it },
            logError = { errors += it },
        )

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))

        assertTrue(messages.any { "notification good:" in it }, messages.toString())
        assertTrue(errors.any { "notification bad:" in it }, errors.toString())
        assertTrue(messages.none { "notification bad:" in it }, messages.toString())
    }

    @Test
    fun handleSampleBuildsTheDispatcherToPushANewAlert() {
        var initializations = 0
        val channel = RecordingChannel()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazy {
                initializations += 1
                NotificationDispatcher(listOf(channel))
            },
            log = {},
            logError = {},
        )

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))

        assertEquals(1, initializations)
        assertEquals(1, channel.payloads.size)
    }

    @Test
    fun logsADeliveryFailureInsteadOfPropagatingIt() {
        val errors = mutableListOf<String>()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazy { error("dispatcher unavailable") },
            log = {},
            logError = { errors += it },
        )

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))

        assertTrue(errors.single().contains("dispatcher unavailable"), errors.toString())
    }

    @Test
    fun handlesTheNextSampleAfterADeliveryThrows() {
        var attempts = 0
        val channel = RecordingChannel()
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazy {
                attempts += 1
                if (attempts == 1) error("dispatcher unavailable")
                NotificationDispatcher(listOf(channel))
            },
            log = {},
            logError = {},
        )

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(
            snapshot(1uL, HYSTERESIS_FOOTPRINT),
            snapshot(2uL, HYSTERESIS_FOOTPRINT),
        )

        assertEquals(2, attempts)
        assertEquals(1, channel.payloads.size)
        assertTrue(channel.payloads.single().text.endsWith("memory"))
    }

    @Test
    fun aSampleWithoutNewAlertsLeavesTheDispatcherUnbuilt() {
        var initializations = 0
        val service = HarmonService(
            config = alertConfig(),
            collector = UnusedCollector,
            notifications = lazy {
                initializations += 1
                NotificationDispatcher(listOf(RecordingChannel()))
            },
            log = {},
            logError = {},
        )

        service.handleSample(snapshot(0uL, QUIET_FOOTPRINT), snapshot(1uL, QUIET_FOOTPRINT))

        assertEquals(0, initializations)
    }
}

private fun newAlertKeysOf(payload: NotificationPayload): List<String> =
    payload.keysOf("newAlertKeys")

private fun suppressedAlertKeysOf(payload: NotificationPayload): List<String> =
    payload.keysOf("suppressedAlertKeys")

private fun NotificationPayload.keysOf(field: String): List<String> =
    Json.parseToJsonElement(json)
        .jsonObject
        .getValue(field)
        .jsonArray
        .map { it.jsonPrimitive.content }

private fun serviceWith(
    channel: NotificationChannel,
    notifyEverySample: Boolean = false,
): HarmonService = HarmonService(
    config = alertConfig(notifyEverySample),
    collector = UnusedCollector,
    notifications = lazyOf(NotificationDispatcher(listOf(channel))),
    log = {},
    logError = {},
)

private fun alertConfig(notifyEverySample: Boolean = false): HarmonConfig = HarmonConfig(
    notifications = NotificationConfig(
        systemEnabled = false,
        notifyEverySample = notifyEverySample,
    ),
)

private fun snapshot(seconds: ULong, footprint: ULong): RawSystemSnapshot = rawSnapshot(
    monotonicNs = seconds * 1_000_000_000uL,
    processes = listOf(rawProcess(footprint = footprint)),
)

private fun crowdedSnapshot(seconds: ULong, footprints: List<ULong>): RawSystemSnapshot =
    rawSnapshot(
        monotonicNs = seconds * 1_000_000_000uL,
        processes = footprints.mapIndexed { index, footprint ->
            rawProcess(pid = index + 10, name = "application-$index", footprint = footprint)
        },
    )

private object UnusedCollector : SystemCollector {
    override fun capture(profile: io.heapy.harmon.monitor.CollectionProfile): RawSystemSnapshot =
        error("handleSample must not capture")
}

private class ScriptedCollector(vararg snapshots: RawSystemSnapshot) : SystemCollector {
    private val remaining = snapshots.toMutableList()
    val profiles = mutableListOf<CollectionProfile>()

    override fun capture(profile: io.heapy.harmon.monitor.CollectionProfile): RawSystemSnapshot {
        profiles += profile
        return remaining.removeFirst()
    }
}

private class FlakyCollector(private val snapshot: RawSystemSnapshot) : SystemCollector {
    private var attempts = 0

    override fun capture(profile: io.heapy.harmon.monitor.CollectionProfile): RawSystemSnapshot {
        attempts += 1
        if (attempts == 1) {
            error("collector socket refused the connection")
        }
        return snapshot
    }
}
