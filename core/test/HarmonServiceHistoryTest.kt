import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.history.History
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.notify.NotificationChannel
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.runtime.HarmonService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val HISTORY_TEST_MEBIBYTE = 1_048_576uL

private val HISTORY_OVER_THRESHOLD = 5_000uL * HISTORY_TEST_MEBIBYTE

private val HISTORY_WITHIN_HYSTERESIS = 1_950uL * HISTORY_TEST_MEBIBYTE

private const val HISTORY_FIRING_KEY = "memory:process:42:100"

class HarmonServiceHistoryTest {
    @Test
    fun writesTheSampleWithTheAlertStateTheCommitLeftBehind() {
        val history = RecordingHistory()
        val service = historyService(history, channels = listOf(RecordingChannel()))

        service.handleSample(
            historySnapshotAt(0uL, HISTORY_OVER_THRESHOLD),
            historySnapshotAt(1uL, HISTORY_OVER_THRESHOLD),
        )

        val write = history.writes.single()
        assertEquals(Instant.fromEpochSeconds(1), write.report.usage.capturedAt)
        assertEquals(listOf(HISTORY_FIRING_KEY), write.report.alerts.map { it.key })

        val state = assertNotNull(write.alertState)
        assertEquals(1L, state.sampleCounter, "the snapshot predates the commit")
        assertEquals(setOf(HISTORY_FIRING_KEY), state.keys.keys)
        assertTrue(
            state.keys.getValue(HISTORY_FIRING_KEY).settled,
            "the push had landed by the time the state was written",
        )
    }

    @Test
    fun journalsWhatEveryChannelDidWithThePush() {
        val history = RecordingHistory()
        val service = historyService(
            history,
            channels = listOf(
                RecordingChannel(name = "good"),
                RecordingChannel(name = "bad", successful = { false }),
            ),
        )

        service.handleSample(
            historySnapshotAt(0uL, HISTORY_OVER_THRESHOLD),
            historySnapshotAt(1uL, HISTORY_OVER_THRESHOLD),
        )

        assertEquals(
            mapOf("bad" to false, "good" to true),
            history.writes.single().deliveries.associate {
                it.channel to it.successful
            },
        )
    }

    @Test
    fun aFailedWriteEndsNeitherTheSampleNorTheCommitAfterIt() {
        val history = RecordingHistory(
            recordFailure = { IllegalStateException("disk full") },
        )
        val reports = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val service = historyService(
            history,
            log = { reports += it },
            logError = { errors += it },
        )

        service.handleSample(
            historySnapshotAt(0uL, HISTORY_OVER_THRESHOLD),
            historySnapshotAt(1uL, HISTORY_OVER_THRESHOLD),
        )
        service.handleSample(
            historySnapshotAt(1uL, HISTORY_WITHIN_HYSTERESIS),
            historySnapshotAt(2uL, HISTORY_WITHIN_HYSTERESIS),
        )

        assertEquals(2, history.recordCalls)
        assertEquals(2, reports.size)
        assertTrue(
            reports[1].contains("Alerts:"),
            "the sample whose write failed never committed its state",
        )
        assertTrue(errors.any { "history write failed" in it }, errors.toString())
    }

    @Test
    fun aWriteThatKeepsFailingIsReportedOnceRatherThanEverySample() {
        val history = RecordingHistory(
            recordFailure = { IllegalStateException("disk full") },
        )
        val errors = mutableListOf<String>()
        val service = historyService(history, logError = { errors += it })

        repeat(3) { index ->
            val started = index.toULong()
            service.handleSample(
                historySnapshotAt(started, HISTORY_OVER_THRESHOLD),
                historySnapshotAt(started + 1uL, HISTORY_OVER_THRESHOLD),
            )
        }

        assertEquals(3, history.recordCalls)
        assertEquals(
            1,
            errors.count { "history write failed" in it },
            errors.toString(),
        )
    }

    @Test
    fun aWriteThatRecoversAndThenFailsAgainIsReportedAgain() {
        val history = RecordingHistory(
            recordFailure = { call ->
                if (call == 2) null else IllegalStateException("disk full")
            },
        )
        val errors = mutableListOf<String>()
        val service = historyService(history, logError = { errors += it })

        repeat(3) { index ->
            val started = (index * 2).toULong()
            service.handleSample(
                historySnapshotAt(started, HISTORY_OVER_THRESHOLD),
                historySnapshotAt(started + 1uL, HISTORY_OVER_THRESHOLD),
            )
        }

        assertEquals(1, history.writes.size, "only the middle write succeeded")
        assertEquals(
            2,
            errors.count { "history write failed" in it },
            "the write recovered in between, so the second failure is news again: $errors",
        )
    }

    @Test
    fun withoutAHistoryTheSampleIsStillHandled() {
        val channel = RecordingChannel()
        val service = historyService(history = null, channels = listOf(channel))

        service.handleSample(
            historySnapshotAt(0uL, HISTORY_OVER_THRESHOLD),
            historySnapshotAt(1uL, HISTORY_OVER_THRESHOLD),
        )

        assertEquals(1, channel.payloads.size)
    }

    @Test
    fun resumesAFiringKeyReturnedByHistoryInsteadOfPushingItAgain() {
        val restored = AlertState().also {
            it.commit(setOf(HISTORY_FIRING_KEY), setOf(HISTORY_FIRING_KEY))
        }.snapshot()
        val history = RecordingHistory(restored = restored)
        val channel = RecordingChannel()

        historyService(history, channels = listOf(channel)).handleSample(
            historySnapshotAt(0uL, HISTORY_OVER_THRESHOLD),
            historySnapshotAt(1uL, HISTORY_OVER_THRESHOLD),
        )

        assertEquals(0, channel.payloads.size, "the resumed key was pushed a second time")
    }

    @Test
    fun anUnreadableHistoryCostsTheRestoreRatherThanTheAgent() {
        val history = RecordingHistory(
            restoreFailure = IllegalStateException("unreadable history"),
        )
        val errors = mutableListOf<String>()
        val channel = RecordingChannel()

        val service = historyService(
            history,
            channels = listOf(channel),
            logError = { errors += it },
        )
        service.handleSample(
            historySnapshotAt(0uL, HISTORY_OVER_THRESHOLD),
            historySnapshotAt(1uL, HISTORY_OVER_THRESHOLD),
        )

        assertEquals(1, channel.payloads.size, "the sample was never handled")
        assertTrue(errors.any { "history restore failed" in it }, errors.toString())
    }

    @Test
    fun startsFromNothingWhenHistoryHasNoRestorableState() {
        val history = RecordingHistory(restored = null)
        val channel = RecordingChannel()

        historyService(history, channels = listOf(channel)).handleSample(
            historySnapshotAt(0uL, HISTORY_OVER_THRESHOLD),
            historySnapshotAt(1uL, HISTORY_OVER_THRESHOLD),
        )

        assertEquals(1, channel.payloads.size)
    }
}

private data class RecordedHistoryWrite(
    val report: MonitoringReport,
    val deliveries: List<DeliveryResult>,
    val alertState: AlertStateSnapshot?,
)

private class RecordingHistory(
    private val restored: AlertStateSnapshot? = null,
    private val restoreFailure: Throwable? = null,
    private val recordFailure: (Int) -> Throwable? = { null },
) : History {
    val writes = mutableListOf<RecordedHistoryWrite>()
    var recordCalls = 0
        private set

    override fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult>,
        alertState: AlertStateSnapshot?,
    ) {
        recordCalls += 1
        recordFailure(recordCalls)?.let { throw it }
        writes += RecordedHistoryWrite(report, deliveries, alertState)
    }

    override fun restorableAlertState(now: Instant): AlertStateSnapshot? {
        restoreFailure?.let { throw it }
        return restored
    }
}

private fun historyService(
    history: History?,
    channels: List<NotificationChannel> = emptyList(),
    log: (String) -> Unit = {},
    logError: (String) -> Unit = {},
): HarmonService = HarmonService(
    config = HarmonConfig(notifications = NotificationConfig(systemEnabled = false)),
    collector = NoHistoryCaptureCollector,
    notifications = lazyOf(NotificationDispatcher(channels)),
    log = log,
    logError = logError,
    history = history,
)

private fun historySnapshotAt(seconds: ULong, footprint: ULong): RawSystemSnapshot = rawSnapshot(
    monotonicNs = seconds * 1_000_000_000uL,
    processes = listOf(rawProcess(footprint = footprint)),
)

private object NoHistoryCaptureCollector : SystemCollector {
    override fun capture(profile: dev.yoda.harmon.monitor.CollectionProfile): RawSystemSnapshot =
        error("handleSample must not capture")
}
