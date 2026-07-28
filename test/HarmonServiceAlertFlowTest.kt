import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.notify.NotificationChannel
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.runtime.HarmonService
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

/** Well above the 2,048 MiB default threshold. */
private val ALERTING_FOOTPRINT = 5_000uL * MIB

/** Below 90% of the threshold, so hysteresis does not keep the alert firing. */
private val QUIET_FOOTPRINT = 512uL * MIB

/** Between 90% and 100% of the threshold: alerts only while the key is already firing. */
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
        val channel = RecordingChannel(successful = false)
        val service = serviceWith(channel)

        service.handleSample(snapshot(0uL, ALERTING_FOOTPRINT), snapshot(1uL, ALERTING_FOOTPRINT))
        service.handleSample(snapshot(1uL, ALERTING_FOOTPRINT), snapshot(2uL, ALERTING_FOOTPRINT))

        assertEquals(2, channel.payloads.size)
    }

    /**
     * A channel that can never succeed — a typo'd webhook URL, a revoked token — must not turn
     * Notification Center into an endless banner loop. Each push carries a fresh identifier, so
     * nothing coalesces them: the retries widen instead. The alert is never given up on, because
     * its condition still holds.
     */
    @Test
    fun spreadsOutRetriesOfAnAlertWhoseDeliveryNeverSucceeds() {
        val channel = RecordingChannel(successful = false)
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

    /**
     * `notifyEverySample` widens what the push shows, not what counts as new. A consumer reading
     * the payload still has to be able to tell a fresh alert from one that has been firing for an
     * hour, so the edge detection keeps running underneath.
     */
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

    /**
     * With no channels there is nothing to observe on the wire, so the state update shows up
     * through hysteresis: the second sample sits below the threshold but above its cleared bound,
     * and only stays alerting because the first sample committed the key as firing.
     */
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

    /**
     * The agent loop is a daemon: a collector that is down for one interval must not end it, and
     * the window must stay where it was, so the next capture still has something to diff against.
     */
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

    /**
     * A pair that cannot be turned into a usage window still advances the window, otherwise the
     * broken snapshot would be replayed against every following capture and the agent would log
     * the same failure forever.
     */
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

        // a previous snapshot from the future: the monotonic clock cannot go backwards
        val afterFailure = service.runCycle(snapshot(5uL, QUIET_FOOTPRINT))
        service.runCycle(afterFailure)

        assertContains(errors.single(), "sample handling failed")
        assertEquals(1, reports.size)
    }

    /**
     * Building the dispatcher builds the system channel, and that boots AppKit. Commands that
     * never push must not pay for it, so the holder has to stay untouched — even on a sample that
     * would have alerted. The window is also the one thing `sampleOnce` measures, so the second
     * it spends waiting is asserted rather than merely endured.
     */
    @Test
    fun sampleOnceWaitsOutItsWindowWithoutBuildingTheDispatcher() {
        var initializations = 0
        val service = HarmonService(
            config = alertConfig(),
            collector = ScriptedCollector(
                snapshot(0uL, ALERTING_FOOTPRINT),
                snapshot(1uL, ALERTING_FOOTPRINT),
            ),
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

    /**
     * `once --notify` runs with fresh state, so every active alert is new and the payload has to
     * say so. It also accepts the already rendered text, so the report is not rendered twice.
     */
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

    /**
     * launchd splits an agent's stdout and stderr into two files. A channel that failed belongs
     * in the error one, otherwise nobody reading the logs after a silent night finds it.
     */
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
                        RecordingChannel(name = "bad", successful = false),
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

    /**
     * Building the dispatcher runs arbitrary AppKit code and can fail. The sample has to survive
     * it: the agent loop is a daemon, and a thrown push would otherwise end it.
     */
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

    /**
     * Two things at once: the sample after a thrown delivery is handled normally, and the throwing
     * sample still committed its state. The second footprint sits below the threshold, so it only
     * alerts — and only reaches the channel — because the failed sample committed the key as
     * firing (see [aFootprintBelowTheThresholdAlertsOnlyBecauseOfTheCommittedState]).
     */
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

    /** Guards the ordering: nothing about the dispatcher may be read before the push decision. */
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
    Json.parseToJsonElement(payload.json)
        .jsonObject
        .getValue("newAlertKeys")
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

private object UnusedCollector : SystemCollector {
    override fun capture(): RawSystemSnapshot = error("handleSample must not capture")
}

private class ScriptedCollector(vararg snapshots: RawSystemSnapshot) : SystemCollector {
    private val remaining = snapshots.toMutableList()

    override fun capture(): RawSystemSnapshot = remaining.removeFirst()
}

/** Down for exactly one capture, the way a collector restarting under launchd is. */
private class FlakyCollector(private val snapshot: RawSystemSnapshot) : SystemCollector {
    private var attempts = 0

    override fun capture(): RawSystemSnapshot {
        attempts += 1
        if (attempts == 1) {
            error("collector socket refused the connection")
        }
        return snapshot
    }
}

private class RecordingChannel(
    override val name: String = "recording",
    private val successful: Boolean = true,
) : NotificationChannel {
    val payloads = mutableListOf<NotificationPayload>()

    override fun deliver(payload: NotificationPayload): DeliveryResult {
        payloads += payload
        return DeliveryResult(channel = name, successful = successful, detail = "recorded")
    }
}
