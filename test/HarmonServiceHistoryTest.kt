import app.cash.sqldelight.db.SqlDriver
import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.history.HistoryStore
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
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val MEBIBYTE = 1_048_576uL

/** Well above the 2,048 MiB default threshold. */
private val OVER_THRESHOLD = 5_000uL * MEBIBYTE

/** Between 90% and 100% of the threshold: alerts only while the key is already firing. */
private val WITHIN_HYSTERESIS = 1_950uL * MEBIBYTE

/**
 * The memory alert every sample here raises. `rawProcess` has no executable path, so the grouper
 * gives it a group of its own keyed by pid and start time, and the analyzer keys the alert by that.
 */
private const val FIRING_KEY = "memory:process:42:100"

private const val INTERVAL_SECONDS = 300L

/**
 * The `CREATE TABLE` sqlite itself holds for [table], read back so that a test can drop the table
 * and put it back exactly as `Alerts.sq` declared it.
 *
 * Read rather than written out here on purpose: a hand-copied DDL goes stale the day a column is
 * added, and a green test against a table that no longer matches the schema is worth nothing —
 * which is precisely the recovery path this file exists to prove.
 */
private fun SqlDriver.createStatementFor(table: String): String =
    scalar("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'") {
        it.getString(0)
    } ?: fail("sqlite_master has no CREATE TABLE for $table")

/**
 * Covers the history write where the agent loop performs it, against the database it writes to.
 *
 * Nothing here reads `handleSample`'s return value or the outcome of a delivery, because there is
 * none to read: what a sample did is asserted through the rows it left, which is also the interface
 * the feature ships.
 */
class HarmonServiceHistoryTest {

    /**
     * The sample and the alert state land together, and the state is the one the *next* sample
     * starts from: a counter of one and a settled key, neither of which exists before the commit.
     */
    @Test
    fun writesTheSampleWithTheAlertStateTheCommitLeftBehind() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            val service = serviceOver(store, channels = listOf(RecordingChannel()))

            service.handleSample(snapshotAt(0uL, OVER_THRESHOLD), snapshotAt(1uL, OVER_THRESHOLD))

            val sample = store.samples().single()
            assertEquals("1970-01-01T00:00:01Z", sample.captured_at)
            assertEquals(
                listOf(FIRING_KEY),
                store.database.alertsQueries.selectAlerts(sample.id).executeAsList().map { it.key },
            )

            val restored = assertNotNull(
                store.restorableAlertState(now = Instant.fromEpochSeconds(1)),
            )
            assertEquals(1L, restored.sampleCounter, "the snapshot predates the commit")
            assertEquals(setOf(FIRING_KEY), restored.keys.keys)
            assertTrue(
                restored.keys.getValue(FIRING_KEY).settled,
                "the push had landed by the time the state was written",
            )
        }
    }

    /**
     * The journal is the only record of who received a push. A channel that failed matters most of
     * all, since the report names who was pushed and never who took it.
     */
    @Test
    fun journalsWhatEveryChannelDidWithThePush() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            val service = serviceOver(
                store,
                channels = listOf(
                    RecordingChannel(name = "good"),
                    RecordingChannel(name = "bad", successful = { false }),
                ),
            )

            service.handleSample(snapshotAt(0uL, OVER_THRESHOLD), snapshotAt(1uL, OVER_THRESHOLD))

            assertEquals(
                mapOf("bad" to 0L, "good" to 1L),
                store.database.alertsQueries
                    .selectAlertDeliveries(store.samples().single().id)
                    .executeAsList()
                    .associate { it.channel to it.successful },
            )
        }
    }

    /**
     * Dropping `alert` breaks every write from here on, the way a full disk does.
     *
     * The second sample sits below the threshold and alerts only because the first one committed
     * the key as firing (see `HarmonServiceAlertFlowTest`), so its report is what proves the failed
     * write neither ended the sample nor cost the commit that follows it.
     */
    @Test
    fun aFailedWriteEndsNeitherTheSampleNorTheCommitAfterIt() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            store.driver.execute(null, "DROP TABLE alert", 0)
            val reports = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val service = serviceOver(
                store,
                log = { reports += it },
                logError = { errors += it },
            )

            service.handleSample(snapshotAt(0uL, OVER_THRESHOLD), snapshotAt(1uL, OVER_THRESHOLD))
            service.handleSample(
                snapshotAt(1uL, WITHIN_HYSTERESIS),
                snapshotAt(2uL, WITHIN_HYSTERESIS),
            )

            assertEquals(2, reports.size)
            assertTrue(
                reports[1].contains("Alerts:"),
                "the sample whose write failed never committed its state",
            )
            assertTrue(errors.any { "history write failed" in it }, errors.toString())
        }
    }

    /**
     * sqliter prints the whole stack trace before it throws. Once per sample, that is a wall of red
     * in the launchd log every interval for as long as the disk stays full — so the agent says it
     * once and shuts up until a write succeeds again.
     */
    @Test
    fun aWriteThatKeepsFailingIsReportedOnceRatherThanEverySample() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            store.driver.execute(null, "DROP TABLE alert", 0)
            val errors = mutableListOf<String>()
            val service = serviceOver(store, logError = { errors += it })

            repeat(3) { index ->
                val started = index.toULong()
                service.handleSample(
                    snapshotAt(started, OVER_THRESHOLD),
                    snapshotAt(started + 1uL, OVER_THRESHOLD),
                )
            }

            assertEquals(
                1,
                errors.count { "history write failed" in it },
                errors.toString(),
            )
        }
    }

    /**
     * "Reported once until it recovers" is two claims, and the test above only makes the first.
     * A write that fails, works, and fails again has to speak twice, which means the success in
     * between has to clear the flag that silences the repeat. Delete the one line that clears it and
     * nothing else in this file notices — while every failure after the first transient one goes
     * silent for the life of the process, which is the opposite of what is documented.
     */
    @Test
    fun aWriteThatRecoversAndThenFailsAgainIsReportedAgain() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            val errors = mutableListOf<String>()
            val service = serviceOver(store, logError = { errors += it })
            val recreateAlert = store.driver.createStatementFor("alert")

            store.driver.execute(null, "DROP TABLE alert", 0)
            service.handleSample(snapshotAt(0uL, OVER_THRESHOLD), snapshotAt(1uL, OVER_THRESHOLD))

            store.driver.execute(null, recreateAlert, 0)
            service.handleSample(snapshotAt(2uL, OVER_THRESHOLD), snapshotAt(3uL, OVER_THRESHOLD))

            store.driver.execute(null, "DROP TABLE alert", 0)
            service.handleSample(snapshotAt(4uL, OVER_THRESHOLD), snapshotAt(5uL, OVER_THRESHOLD))

            assertEquals(1, store.samples().size, "only the middle sample had a table to write to")
            assertEquals(
                2,
                errors.count { "history write failed" in it },
                "the write recovered in between, so the second failure is news again: $errors",
            )
        }
    }

    /**
     * What `once` and `diagnose` get: the whole sample, and not a row written anywhere.
     *
     * The same sample is put through a service that has the store first, so that "nothing was
     * written" is a difference rather than a description of a database nobody asked to write to.
     * Both services run against one open store under one home; only the second is denied it.
     */
    @Test
    fun withoutAStoreTheSampleIsHandledAndNothingIsWritten() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            serviceOver(store).handleSample(
                snapshotAt(0uL, OVER_THRESHOLD),
                snapshotAt(1uL, OVER_THRESHOLD),
            )
            assertEquals(1, store.samples().size, "the same sample writes a row when there is one")

            val channel = RecordingChannel()
            val service = serviceOver(store = null, channels = listOf(channel))

            service.handleSample(snapshotAt(0uL, OVER_THRESHOLD), snapshotAt(1uL, OVER_THRESHOLD))

            assertEquals(1, channel.payloads.size, "the sample was never handled")
            assertEquals(1, store.samples().size, "a service without a store wrote a sample")
        }
    }

    /**
     * The point of storing the state at all: an alert that was already firing when launchd
     * restarted the agent must not produce a second banner.
     */
    @Test
    fun resumesAFiringKeyFromAFreshSnapshotInsteadOfPushingItAgain() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            storeFiringState(store, capturedAt = Clock.System.now())
            val channel = RecordingChannel()

            serviceOver(store, channels = listOf(channel)).handleSample(
                snapshotAt(0uL, OVER_THRESHOLD),
                snapshotAt(1uL, OVER_THRESHOLD),
            )

            assertEquals(0, channel.payloads.size, "the resumed key was pushed a second time")
        }
    }

    /**
     * The failure `openOrNull` cannot stand in for: the database opened, and then refused the very
     * first read. `DROP TABLE agent_state` stands in for what does it in the field — a page lost to
     * the panic `synchronousFlag = NORMAL` deliberately accepts, or a table an older build never
     * created.
     *
     * That read happens while the agent is being constructed, so unguarded it is not a run without
     * history: the process dies before its first sample and launchd restarts it into the same death,
     * leaving the machine unmonitored because yesterday's record of it went bad. What must survive is
     * the monitoring, from a clean alert state and with the reason said out loud.
     */
    @Test
    fun anUnreadableDatabaseCostsTheRestoreRatherThanTheAgent() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            store.driver.execute(null, "DROP TABLE agent_state", 0)
            val errors = mutableListOf<String>()
            val channel = RecordingChannel()

            val service = serviceOver(store, listOf(channel), logError = { errors += it })
            service.handleSample(snapshotAt(0uL, OVER_THRESHOLD), snapshotAt(1uL, OVER_THRESHOLD))

            assertEquals(1, channel.payloads.size, "the sample was never handled")
            assertTrue(errors.any { "history restore failed" in it }, errors.toString())
        }
    }

    /**
     * Past the TTL the machine has moved on, and resuming would hand the hysteresis in
     * `AlertAnalyzer` a lowered clear threshold for a state nothing in the room still matches.
     */
    @Test
    fun startsFromNothingWhenTheStoredSnapshotIsTooOldToResume() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = INTERVAL_SECONDS) { store ->
            storeFiringState(
                store,
                capturedAt = Clock.System.now() - (INTERVAL_SECONDS * 3).seconds,
            )
            val channel = RecordingChannel()

            serviceOver(store, channels = listOf(channel)).handleSample(
                snapshotAt(0uL, OVER_THRESHOLD),
                snapshotAt(1uL, OVER_THRESHOLD),
            )

            assertEquals(1, channel.payloads.size, "a snapshot this old was resumed anyway")
        }
    }
}

/**
 * Leaves [store] holding a sample taken at [capturedAt] whose alert state has [FIRING_KEY] firing
 * and confirmed delivered — the state an agent that was pushed an alert and then died leaves.
 */
private fun storeFiringState(store: HistoryStore, capturedAt: Instant) {
    val state = AlertState()
    state.commit(setOf(FIRING_KEY), setOf(FIRING_KEY))
    store.record(
        MonitoringReport(
            usage = systemUsage(processes = listOf(processUsage(pid = 42, name = "example")))
                .copy(capturedAt = capturedAt),
            alerts = listOf(alert(FIRING_KEY)),
            topProcessCount = 1,
        ),
        alertState = state.snapshot(),
    )
}

private fun serviceOver(
    store: HistoryStore?,
    channels: List<NotificationChannel> = emptyList(),
    log: (String) -> Unit = {},
    logError: (String) -> Unit = {},
): HarmonService = HarmonService(
    config = HarmonConfig(notifications = NotificationConfig(systemEnabled = false)),
    collector = NoCaptureCollector,
    notifications = lazyOf(NotificationDispatcher(channels)),
    log = log,
    logError = logError,
    history = store,
)

private fun snapshotAt(seconds: ULong, footprint: ULong): RawSystemSnapshot = rawSnapshot(
    monotonicNs = seconds * 1_000_000_000uL,
    processes = listOf(rawProcess(footprint = footprint)),
)

private object NoCaptureCollector : SystemCollector {
    override fun capture(): RawSystemSnapshot = error("handleSample must not capture")
}
