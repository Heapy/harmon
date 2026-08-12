import dev.yoda.harmon.history.PRUNE_PERIOD_SECONDS
import dev.yoda.harmon.history.retentionCutoff
import dev.yoda.harmon.history.shouldPrune
import dev.yoda.harmon.history.toSqlTimestamp
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val HISTORY_TABLES =
    listOf("sample", "process_sample", "application_sample", "alert", "alert_delivery")

class RetentionTest {

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

    @Test
    fun anIntervalLongerThanThePeriodPrunesOnEverySample() {
        assertTrue((0L until 5L).all { shouldPrune(it, PRUNE_PERIOD_SECONDS * 2) })
    }

    @Test
    fun anIntervalBelowASecondIsFlooredRatherThanDividedBy() {
        for (intervalSeconds in listOf(0L, -1L, -3_600L)) {
            assertEquals(
                (0L until 4L).map { shouldPrune(it, 1L) },
                (0L until 4L).map { shouldPrune(it, intervalSeconds) },
                "an interval of $intervalSeconds must behave as one second",
            )
        }
    }

    @Test
    fun theCutoffCountsBackFromNowByTheConfiguredNumberOfDays() {
        val now = Instant.parse("2026-07-29T12:00:00Z")

        assertEquals("2026-07-28T12:00:00Z", retentionCutoff(now, retentionDays = 1))
        assertEquals("2026-07-22T12:00:00Z", retentionCutoff(now, retentionDays = 7))
        assertEquals("2026-06-29T12:00:00Z", retentionCutoff(now, retentionDays = 30))
    }

    @Test
    fun theStorePrunesByTheWindowItWasConfiguredWith() = withScratchHome { home ->
        withHistoryStore(home, retentionDays = 1) { store ->
            val inside = RECENT - 12.hours
            val outside = RECENT - 36.hours
            store.record(reportOf("Marked", firstPid = 11, capturedAt = inside))
            store.record(reportOf("Notes", firstPid = 21, capturedAt = outside))

            store.prune()

            assertEquals(
                listOf(inside.toSqlTimestamp()),
                store.samples().map { it.captured_at },
                "a sample 36 hours old survived a one-day window",
            )
        }
    }

    @Test
    fun theCutoffIsTruncatedLikeTheColumnItIsComparedAgainst() {
        assertEquals(
            "2026-07-22T12:00:00Z",
            retentionCutoff(Instant.parse("2026-07-29T12:00:00.750Z"), retentionDays = 7),
        )
    }

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

    @Test
    fun droppingASampleDropsEverythingHangingOffIt() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(reportOf("Marked", firstPid = 11, capturedAt = ANCIENT), deliveries())
            for (table in HISTORY_TABLES) {
                assertTrue(store.driver.countRows(table) > 0, "$table has nothing to lose")
            }

            store.prune(AFTER_EVERY_SAMPLE)

            for (table in HISTORY_TABLES) {
                assertEquals(0L, store.driver.countRows(table), "$table outlived its sample")
            }
        }
    }

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

    @Test
    fun theReparentingStampOutlivesTheSampleThatWroteItButNotTheProcess() =
        withScratchHome { home ->
            withHistoryStore(home) { store ->
                store.record(orphanReport(capturedAt = ANCIENT))
                store.record(orphanReport(capturedAt = RECENT, lostParent = null))

                store.prune(retentionCutoff(RECENT, retentionDays = 7))

                val survivor = store.database.processesQueries.selectProcesses().executeAsOne()
                assertEquals(
                    ANCIENT.toSqlTimestamp(),
                    survivor.reparented_at,
                    "the sample that carried the transition is gone; the transition is not",
                )

                store.prune(AFTER_EVERY_SAMPLE)

                assertTrue(
                    store.database.processesQueries.selectProcesses().executeAsList().isEmpty(),
                    "a process whose last sample left the window is dead weight, stamp and all",
                )
            }
        }

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

            store.prune(AFTER_EVERY_SAMPLE)

            assertEquals(0L, store.driver.countRows("sample"), "the history was meant to go")
            assertEquals(1L, store.driver.countRows("alert_state"), "the state was not")
            assertEquals(1L, store.driver.countRows("agent_state"))
        }
    }

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

    @Test
    fun theRetentionPassReturnsTheSpaceAndNotOnlyTheRows() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            repeat(4) { store.record(crowdedReport()) }
            val allocated = store.driver.pageCount()

            store.prune(AFTER_EVERY_SAMPLE)

            assertEquals(0L, store.driver.countRows("process_sample"), "the rows were meant to go")
            assertTrue(
                store.driver.pageCount() < allocated,
                "the file still spans $allocated pages with nothing left in it",
            )
        }
    }

    @Test
    fun aFailingPassCostsTheSpaceRatherThanTheSampleOrTheSamplesAfterIt() = withScratchHome { home ->
        val logged = mutableListOf<String>()

        withHistoryStore(home, intervalSeconds = 1_200, logError = { logged += it }) { store ->
            store.record(reportOf("Marked", firstPid = 11, capturedAt = ANCIENT))
            store.driver.execute(null, REFUSE_THE_RETENTION_DELETE, 0)

            repeat(6) { store.record(reportOf("Notes", firstPid = 21, capturedAt = RECENT)) }

            assertEquals(7, store.samples().size, "a pass that threw took the sample down with it")
            assertEquals(
                2,
                logged.count { "history retention failed" in it },
                "one report per due pass, and only for the passes that were due: $logged",
            )
        }
    }
}

private const val CROWD_SIZE = 250

private const val REFUSE_THE_RETENTION_DELETE =
    "CREATE TRIGGER refuse_delete BEFORE DELETE ON sample " +
        "BEGIN SELECT RAISE(ABORT, 'the disk is full'); END"

private fun crowdedReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = (1..CROWD_SIZE).map { pid -> processUsage(pid = pid, name = "process-$pid") },
    ).copy(capturedAt = ANCIENT),
    alerts = emptyList(),
    topProcessCount = 2,
    suppressedAlertKeys = emptyList(),
)

private val ANCIENT = Clock.System.now() - 3_650.days

private val RECENT = Clock.System.now()

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
