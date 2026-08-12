import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.analysis.DELIVERY_RETRY_THRESHOLD
import dev.yoda.harmon.model.MonitoringReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val SNAPSHOT_INTERVAL_SECONDS = 300L

private val SNAPSHOT_SAVED_AT = Instant.parse("2026-07-29T00:00:00Z")

private val SNAPSHOT_FIRING = setOf("cpu:firefox", "memory:chrome")

class HistoryAlertStateSnapshotTest {
    @Test
    fun theSnapshotSurvivesTheDatabaseWithItsCounter() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = SNAPSHOT_INTERVAL_SECONDS) { store ->
            val state = AlertState()
            state.commit(SNAPSHOT_FIRING, setOf("memory:chrome"))
            repeat(DELIVERY_RETRY_THRESHOLD) {
                state.commit(
                    SNAPSHOT_FIRING,
                    emptySet(),
                    failedKeys = setOf("cpu:firefox"),
                )
            }
            val snapshot = state.snapshot()
            assertTrue(
                snapshot.keys.values.any { it.settled } &&
                    snapshot.keys.values.any { it.failures > 0 },
                "a round trip over rows that are all zero would prove nothing",
            )

            store.record(snapshotReportAt(SNAPSHOT_SAVED_AT), alertState = snapshot)

            assertEquals(
                snapshot,
                store.restorableAlertState(now = SNAPSHOT_SAVED_AT + 60.seconds),
            )
        }
    }

    @Test
    fun aSnapshotOlderThanTheTtlIsNotHandedBack() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = SNAPSHOT_INTERVAL_SECONDS) { store ->
            val state = AlertState()
            state.commit(SNAPSHOT_FIRING, setOf("memory:chrome"))
            store.record(snapshotReportAt(SNAPSHOT_SAVED_AT), alertState = state.snapshot())

            assertNotNull(
                store.restorableAlertState(
                    now = SNAPSHOT_SAVED_AT + (SNAPSHOT_INTERVAL_SECONDS * 2).seconds,
                ),
            )
            assertNull(
                store.restorableAlertState(
                    now = SNAPSHOT_SAVED_AT + (SNAPSHOT_INTERVAL_SECONDS * 3).seconds,
                ),
                "a snapshot this old belongs to a machine the agent no longer knows",
            )
        }
    }

    @Test
    fun aSampleWrittenWithoutAlertStateLeavesNoneToRestore() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = SNAPSHOT_INTERVAL_SECONDS) { store ->
            store.record(snapshotReportAt(SNAPSHOT_SAVED_AT))

            assertNull(store.restorableAlertState(now = SNAPSHOT_SAVED_AT))
        }
    }
}

private fun snapshotReportAt(capturedAt: Instant): MonitoringReport = MonitoringReport(
    usage = systemUsage(processes = listOf(processUsage(pid = 11, name = "firefox")))
        .copy(capturedAt = capturedAt),
    alerts = listOf(alert("cpu:firefox")),
    topProcessCount = 1,
)
