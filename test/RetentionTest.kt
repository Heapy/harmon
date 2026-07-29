import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.yoda.harmon.history.PRUNE_PERIOD_SECONDS
import dev.yoda.harmon.history.retentionCutoff
import dev.yoda.harmon.history.shouldPrune
import dev.yoda.harmon.history.toSqlTimestamp
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A cutoff no stored sample can be on the safe side of. */
private const val AFTER_ALL_HISTORY = "9999-12-31T23:59:59Z"

/** Tables that hold history and are expected to empty out behind a deleted sample. */
private val HISTORY_TABLES =
    listOf("sample", "process_sample", "application_sample", "alert", "alert_delivery")

/**
 * Covers the retention pass: when it runs, where it cuts, what it takes with it — and, above all,
 * that the write path runs it without being asked.
 *
 * The database tests go through a scratch home rather than `inMemoryDriver`, which is not optional
 * here: an in-memory driver leaves foreign keys off, and every cascade assertion below would pass
 * against a configuration in which the cascade does nothing.
 */
class RetentionTest {

    /**
     * The schedule is about how much history piles up between passes, not about a sample count, so
     * this states that property directly — at intervals that divide the hour and at ones that do not.
     * The gap may exceed the period by less than one interval and must never fall below it.
     */
    @Test
    fun theScheduleKeepsAboutAnHourBetweenPasses() {
        for (intervalSeconds in listOf(1L, 30L, 60L, 300L, 500L, 900L, 3_600L)) {
            val firing = (0L until 86_400L / intervalSeconds)
                .filter { shouldPrune(it, intervalSeconds) }
            val gaps = firing
                .zipWithNext { earlier, later -> (later - earlier) * intervalSeconds }
                .distinct()

            assertEquals(0L, firing.first(), "the first sample of a run carries the start-up pass")
            assertEquals(1, gaps.size, "at ${intervalSeconds}s the passes were uneven: $gaps")
            assertTrue(
                gaps.single() in PRUNE_PERIOD_SECONDS..<PRUNE_PERIOD_SECONDS + intervalSeconds,
                "at ${intervalSeconds}s the pass ran every ${gaps.single()} seconds",
            )
        }
    }

    /**
     * Rounding the samples-per-pass down would give zero here and turn the modulo into a division by
     * zero — or, guarded, into a pass on every sample by accident rather than by decision.
     */
    @Test
    fun anIntervalLongerThanThePeriodPrunesOnEverySample() {
        assertTrue((0L until 5L).all { shouldPrune(it, PRUNE_PERIOD_SECONDS * 2) })
    }

    @Test
    fun theCutoffCountsBackFromNow() {
        assertEquals(
            "2026-07-22T12:00:00Z",
            retentionCutoff(Instant.parse("2026-07-29T12:00:00Z"), retentionDays = 7),
        )
    }

    /**
     * The cutoff is compared against `captured_at` as a string, so it has to be truncated the same
     * way those are: a fraction on it would sort below every whole second and strand a whole second's
     * worth of samples past the window.
     */
    @Test
    fun theCutoffIsTruncatedLikeTheColumnItIsComparedAgainst() {
        assertEquals(
            "2026-07-22T12:00:00Z",
            retentionCutoff(Instant.parse("2026-07-29T12:00:00.750Z"), retentionDays = 7),
        )
    }

    /** `captured_at < cutoff`, so the sample sitting exactly on the boundary lives one pass longer. */
    @Test
    fun aSampleExactlyOnTheCutoffStays() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            val boundary = Instant.parse("2026-07-22T12:00:00Z")
            store.record(reportOf("Marked", firstPid = 11, capturedAt = boundary))
            store.record(reportOf("Marked", firstPid = 11, capturedAt = boundary - 1.seconds))

            store.prune(retentionCutoff(Instant.parse("2026-07-29T12:00:00Z"), retentionDays = 7))

            assertEquals(
                listOf(boundary.toSqlTimestamp()),
                store.samples().map { it.captured_at },
            )
        }
    }

    /**
     * Retention names `sample` and nothing else; every other history table leaves through its
     * `sample_id` foreign key. Without the driver's `foreignKeyConstraints` those children would all
     * stay behind, pointing at a sample that no longer exists.
     */
    @Test
    fun droppingASampleDropsEverythingHangingOffIt() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(reportOf("Marked", firstPid = 11, capturedAt = ANCIENT), deliveries())
            for (table in HISTORY_TABLES) {
                assertTrue(store.driver.countRows(table) > 0, "$table has nothing to lose")
            }

            store.prune(AFTER_ALL_HISTORY)

            for (table in HISTORY_TABLES) {
                assertEquals(0L, store.driver.countRows(table), "$table outlived its sample")
            }
        }
    }

    /**
     * The lookups are the reason history fits on disk, and they are also the one thing the cascade
     * cannot clean: nothing points at them, they point at nothing. Left alone they would grow with
     * process churn for as long as the agent runs.
     */
    @Test
    fun onlyTheLookupRowsNothingPointsAtAreDropped() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(reportOf("Marked", firstPid = 11, capturedAt = ANCIENT))
            store.record(reportOf("Notes", firstPid = 21, capturedAt = RECENT))

            store.prune(retentionCutoff(RECENT, retentionDays = 7))

            assertEquals(
                listOf("Notes", "loose"),
                store.database.processesQueries.selectProcesses().executeAsList().map { it.name },
                "a process is orphaned only once its last sample is gone",
            )
            assertEquals(
                listOf("/Applications/Notes.app"),
                store.database.applicationsQueries.selectApplications().executeAsList()
                    .map { it.bundle_path },
            )
        }
    }

    /**
     * `alert_state` and `agent_state` are what the agent knows about itself, not what it saw. Sweeping
     * them out with the history would push an alert that was already firing a second time after every
     * restart and reset the backoff a broken channel earned.
     */
    @Test
    fun theAgentsOwnStateSurvivesTheWholeWindowGoing() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(reportOf("Marked", firstPid = 11, capturedAt = ANCIENT))
            store.driver.execute(
                identifier = null,
                sql = "INSERT INTO alert_state VALUES ('cpu:Marked', 1, 2, 41)",
                parameters = 0,
            )
            store.driver.execute(
                identifier = null,
                sql = "INSERT INTO agent_state VALUES (1, 42, '2026-07-29T00:00:00Z')",
                parameters = 0,
            )

            store.prune(AFTER_ALL_HISTORY)

            assertEquals(0L, store.driver.countRows("sample"), "the history was meant to go")
            assertEquals(1L, store.driver.countRows("alert_state"), "the state was not")
            assertEquals(1L, store.driver.countRows("agent_state"))
        }
    }

    /**
     * The test the rest of this file is worthless without: nothing here calls `prune`. A retention
     * that only ran when a test asked for it would leave every other assertion above green while the
     * database grew forever.
     *
     * At a 1200-second interval the pass falls on samples 0, 3, 6…, so the three stale samples are
     * written unmolested and then swept by the fourth `record` — before that sample is written, which
     * is why the fresh one is the only survivor rather than one of two.
     */
    @Test
    fun recordRunsTheRetentionPassItself() = withScratchHome { home ->
        withHistoryStore(home, intervalSeconds = 1_200) { store ->
            repeat(3) { store.record(reportOf("Marked", firstPid = 11, capturedAt = ANCIENT)) }
            assertEquals(3, store.samples().size, "no pass is due while the window is being filled")

            val now = Clock.System.now()
            store.record(reportOf("Notes", firstPid = 21, capturedAt = now))

            assertEquals(listOf(now.toSqlTimestamp()), store.samples().map { it.captured_at })
            assertEquals(2L, store.driver.countRows("process"), "the stale lookup rows went too")
        }
    }

    /**
     * The pass has to return the space and not only drop the rows: `auto_vacuum` is INCREMENTAL, so
     * pages a `DELETE` frees stay inside the file until the vacuum hands them back, and nothing but
     * the pass ever asks it to.
     *
     * The width of the report is the point rather than a detail. `PRAGMA incremental_vacuum` yields
     * one row per page it returns, a driver path that refuses rows throws on the first one, and on a
     * database holding two processes per sample the free list is empty and the statement is done
     * after a single step — so every other test in this file passes over a vacuum that never ran.
     */
    @Test
    fun theRetentionPassReturnsTheSpaceAndNotOnlyTheRows() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            repeat(4) { store.record(crowdedReport()) }
            val allocated = store.driver.pageCount()

            store.prune(AFTER_ALL_HISTORY)

            assertEquals(0L, store.driver.countRows("process_sample"), "the rows were meant to go")
            assertTrue(
                store.driver.pageCount() < allocated,
                "the file still spans $allocated pages with nothing left in it",
            )
        }
    }
}

/** Processes per sample in [crowdedReport]; enough rows that deleting them frees whole pages. */
private const val CROWD_SIZE = 250

/** `PRAGMA page_count` — the size of the file in pages, which is what the vacuum changes. */
private fun SqlDriver.pageCount(): Long = executeQuery(
    identifier = null,
    sql = "PRAGMA page_count",
    mapper = { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0))
    },
    parameters = 0,
).value ?: fail("PRAGMA page_count returned no row")

/** A sample wide enough to span pages of its own, so that dropping it can give pages back. */
private fun crowdedReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = (1..CROWD_SIZE).map { pid -> processUsage(pid = pid, name = "process-$pid") },
    ).copy(capturedAt = ANCIENT),
    alerts = emptyList(),
    topProcessCount = 2,
    suppressedAlertKeys = emptyList(),
)

/** Far enough back that every plausible retention window has already passed it by. */
private val ANCIENT = Instant.parse("2020-01-01T00:00:00Z")

/** Inside every plausible retention window, since the cutoff is counted back from it. */
private val RECENT = Clock.System.now()

/**
 * A report with one bundled application and one process outside any bundle, plus an alert of each
 * kind, so that every table the retention pass has to reach holds at least one row.
 */
private fun reportOf(
    application: String,
    firstPid: Int,
    capturedAt: Instant,
): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(
                pid = firstPid,
                name = application,
                executablePath = "/Applications/$application.app/Contents/MacOS/$application",
            ),
            processUsage(pid = firstPid + 1, name = "loose"),
        ),
    ).copy(capturedAt = capturedAt),
    alerts = listOf(alert(key = "cpu:$application")),
    topProcessCount = 2,
    suppressedAlertKeys = listOf("memory:loose"),
)

private fun deliveries(): List<DeliveryResult> = listOf(
    DeliveryResult(channel = "notification-center", successful = true, detail = "posted"),
)
