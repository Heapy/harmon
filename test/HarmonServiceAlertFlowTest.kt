import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.notify.NotificationChannel
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.runtime.HarmonService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
     * Building the dispatcher builds the system channel, and that boots AppKit. Commands that
     * never push must not pay for it, so the holder has to stay untouched — even on a sample that
     * would have alerted.
     */
    @Test
    fun sampleOnceNeverBuildsTheDispatcher() {
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

        service.sampleOnce(sampleSeconds = 1)

        assertEquals(0, initializations)
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

private class RecordingChannel(
    private val successful: Boolean = true,
) : NotificationChannel {
    override val name: String = "recording"
    val payloads = mutableListOf<NotificationPayload>()

    override fun deliver(payload: NotificationPayload): DeliveryResult {
        payloads += payload
        return DeliveryResult(channel = name, successful = successful, detail = "recorded")
    }
}
