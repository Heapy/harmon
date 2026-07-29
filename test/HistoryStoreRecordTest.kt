import dev.yoda.harmon.analysis.AlertKeyState
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.history.selectAlertKeyStates
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every table one stored sample writes to.
 *
 * `alert_state` and `agent_state` are deliberately absent: they are the agent's own state rather
 * than the sample's, they hold one row however many samples were written, and the test that has to
 * see them roll back compares their contents rather than their counts.
 */
private val SAMPLE_TABLES = listOf(
    "sample",
    "process",
    "process_sample",
    "application",
    "application_sample",
    "alert",
    "alert_delivery",
)

/**
 * [SAMPLE_TABLES] without `alert`, which `aFailedWriteLeavesNothingOfItsSample` drops and can no
 * longer count. `alert_delivery` is written after `alert` and so never reached by that failing
 * sample; it stays because the first sample fills it, and a rollback that took the wrong rows would
 * show.
 */
private val SURVIVING_TABLES = SAMPLE_TABLES - "alert"

/**
 * Fails the write on the very last statement `record` runs, and only that statement.
 *
 * `BEFORE INSERT` rather than a dropped table, because the table has to survive: `alert_state` is
 * rewritten one statement earlier and the point of the test is to read back what was in it before.
 * `RAISE(ABORT, …)` undoes its own statement and nothing else, so whatever the rest of the
 * transaction leaves behind is left behind by the transaction rather than by the trigger.
 */
private const val AGENT_STATE_REFUSES_WRITES =
    "CREATE TRIGGER agent_state_is_closed BEFORE INSERT ON agent_state " +
        "BEGIN SELECT RAISE(ABORT, 'agent_state is closed'); END"

/**
 * Covers `HistoryStore.record` against the database the agent actually writes to.
 *
 * The scratch home is not a detail here. `record` reads the id of the sample it just wrote from
 * `last_insert_rowid()`, which is per connection, and the configured driver keeps a writer apart from
 * a pool of readers — so the id is only correct while the read stays inside the writing transaction.
 * On `inMemoryDriver` there is one connection and foreign keys are off, and both of those failures
 * pass unnoticed.
 */
class HistoryStoreRecordTest {

    @Test
    fun oneReportIsStoredWhole() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(recordedReport(), deliveries())

            val sample = store.samples().single()
            assertEquals("1970-01-01T00:01:40Z", sample.captured_at)
            assertEquals(3L, sample.total_process_count)

            val processes = store.database.processesQueries
            val nameById = processes.selectProcesses().executeAsList().associate { it.id to it.name }
            assertEquals(setOf("Marked", "helper", "loose"), nameById.values.toSet())

            val application = store.database.applicationsQueries.selectApplications().executeAsOne()
            assertEquals("/Applications/Marked.app", application.bundle_path)

            assertEquals(
                mapOf("Marked" to application.id, "helper" to application.id, "loose" to null),
                processes.selectProcessSamples(sample.id).executeAsList().associate {
                    nameById.getValue(it.process_id) to it.application_id
                },
                "a process outside a bundle belongs to no stored application",
            )

            val stored = store.database.applicationsQueries
                .selectApplicationSamples(sample.id)
                .executeAsOne()
            assertEquals(application.id, stored.application_id)
            assertEquals(2L, stored.process_count, "the singleton group of `loose` is not stored")

            assertEquals(
                mapOf("cpu:Marked" to 1L, "memory:loose" to 0L),
                store.database.alertsQueries.selectAlerts(sample.id).executeAsList()
                    .associate { it.key to it.reported },
                "a key the per-category cap dropped is stored apart from a reported alert",
            )
            assertEquals(
                mapOf("notification-center" to 1L, "webhook" to 0L),
                store.database.alertsQueries.selectAlertDeliveries(sample.id).executeAsList()
                    .associate { it.channel to it.successful },
            )
        }
    }

    /**
     * Dropping `alert` fails the write halfway through, after the sample, its processes, its
     * application and both lookup rows are already in. A partial sample is worse than a missing one
     * — a process count read back from it would be a lie no reader could detect — so what matters
     * is not that `record` threw but that nothing of the sample is left.
     *
     * `alert` rather than one of the tables written earlier, and that choice is the whole test.
     * `record` runs the retention pass before it opens the transaction, and the pass is due on the
     * first sample of every run: drop a table retention itself names — `application_sample`, say,
     * which `deleteOrphanApplications` reads — and `record` dies in `pruneIfDue` without writing a
     * row, leaving every count below zero for a reason that has nothing to do with a rollback.
     * Retention never names `alert`, so the failure lands where it is meant to. The message is
     * asserted for the same reason: it is the only evidence the write reached the alert insert
     * rather than falling over on the way to it.
     *
     * The sample recorded first is what gives the counts something to be. Every row below belongs
     * to it, so an assertion of "unchanged" is an assertion that the second sample left nothing —
     * and the second report names a different application and different processes, so its lookup
     * rows would be new rows rather than conflicts.
     */
    @Test
    fun aFailedWriteLeavesNothingOfItsSample() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(recordedReport(), deliveries())
            val kept = SURVIVING_TABLES.associateWith { store.driver.countRows(it) }
            assertTrue(kept.values.all { it > 0 }, "nothing was stored to roll back against: $kept")

            store.driver.execute(null, "DROP TABLE alert", 0)

            val failure = assertFails { store.record(secondReport(), deliveries()) }

            assertTrue(
                "alert" in (failure.message ?: ""),
                "the write must fail at the alert insert, past everything it has to undo: $failure",
            )
            assertEquals(
                kept,
                SURVIVING_TABLES.associateWith { store.driver.countRows(it) },
                "a rolled-back sample left rows behind",
            )
        }
    }

    /**
     * The other half of the same transaction: `alert_state` and `agent_state` are written last, and
     * they have to fall with the sample they were written for.
     *
     * The test above cannot see this. Its failure lands on the alert insert, which comes before
     * either state table is touched — so lifting both writes into a `database.transaction` of their
     * own, the regression `record`'s KDoc warns against, leaves it green. This one fails on the very
     * last statement of the transaction instead, past everything it has to undo, which is why the
     * trigger exists at all.
     *
     * The second snapshot is empty on purpose: `replaceAlertState` clears the table before it writes
     * anything, so the key asserted below is one the failing write had already deleted. Its being
     * there afterwards is the rollback, and the sample counter it is measured against — restored to
     * the value of the sample before — is the same statement's other half.
     */
    @Test
    fun aFailedWriteLeavesTheAlertStateOfTheSampleBeforeIt() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(recordedReport(), deliveries(), alertState = firingState())
            val kept = SAMPLE_TABLES.associateWith { store.driver.countRows(it) }
            assertTrue(kept.values.all { it > 0 }, "nothing was stored to roll back against: $kept")

            store.driver.execute(null, AGENT_STATE_REFUSES_WRITES, 0)

            val failure = assertFails {
                store.record(secondReport(), deliveries(), alertState = settledState())
            }

            assertTrue(
                "agent_state is closed" in (failure.message ?: ""),
                "the write must fail on the last statement of the transaction: $failure",
            )
            assertEquals(
                kept,
                SAMPLE_TABLES.associateWith { store.driver.countRows(it) },
                "a rolled-back sample left rows behind",
            )
            assertEquals(
                firingState().keys,
                store.database.alertsQueries.selectAlertKeyStates(),
                "the alert state rode out of the transaction that rolled back",
            )
            assertEquals(
                firingState().sampleCounter,
                store.database.samplesQueries.selectAgentState().executeAsOne().sample_counter,
                "the sample counter rode out of the transaction that rolled back",
            )
        }
    }

    /**
     * What the lookup tables are for: an application seen 288 times a day and a process that outlives
     * the interval must each cost one row, whatever the sample count.
     */
    @Test
    fun aSecondSampleReusesTheLookupRows() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            val report = recordedReport()
            store.record(report)
            store.record(
                report.copy(usage = report.usage.copy(capturedAt = Instant.fromEpochSeconds(400))),
            )

            assertEquals(
                listOf("1970-01-01T00:01:40Z", "1970-01-01T00:06:40Z"),
                store.samples().map { it.captured_at },
            )
            assertEquals(3L, store.driver.countRows("process"))
            assertEquals(1L, store.driver.countRows("application"))
            assertEquals(6L, store.driver.countRows("process_sample"))
            assertEquals(2L, store.driver.countRows("application_sample"))
        }
    }
}

/**
 * A report with both kinds of application group — two processes inside one bundle and one outside any
 * bundle — plus an alert of each kind, so nothing `record` writes is exercised by an empty list.
 */
private fun recordedReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(
                pid = 11,
                name = "Marked",
                executablePath = "/Applications/Marked.app/Contents/MacOS/Marked",
            ),
            processUsage(
                pid = 12,
                name = "helper",
                parentPid = 11,
                executablePath = "/Applications/Marked.app/Contents/Helpers/helper",
            ),
            processUsage(pid = 13, name = "loose"),
        ),
    ),
    alerts = listOf(alert(key = "cpu:Marked")),
    topProcessCount = 3,
    suppressedAlertKeys = listOf("memory:loose"),
)

/**
 * A second sample that shares nothing with [recordedReport] — another moment, another bundle,
 * another set of pids — so every table it touches would gain a row rather than conflict with one.
 */
private fun secondReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(
                pid = 21,
                name = "Notes",
                executablePath = "/Applications/Notes.app/Contents/MacOS/Notes",
            ),
            processUsage(pid = 22, name = "drifter"),
        ),
    ).copy(capturedAt = Instant.fromEpochSeconds(400)),
    alerts = listOf(alert(key = "cpu:Notes")),
    topProcessCount = 2,
    suppressedAlertKeys = listOf("memory:drifter"),
)

/**
 * The alert state the first sample leaves behind: one key still firing, with a backoff it earned and
 * a counter no other number in this file shares, so a value read back from either table can only
 * have come from here.
 */
private fun firingState(): AlertStateSnapshot = AlertStateSnapshot(
    sampleCounter = 41,
    keys = mapOf("cpu:Marked" to AlertKeyState(settled = false, failures = 3, retryAtSample = 44)),
)

/** The state the failing sample would have replaced it with: nothing firing, one sample later. */
private fun settledState(): AlertStateSnapshot =
    AlertStateSnapshot(sampleCounter = 42, keys = emptyMap())

private fun deliveries(): List<DeliveryResult> = listOf(
    DeliveryResult(channel = "notification-center", successful = true, detail = "posted"),
    DeliveryResult(channel = "webhook", successful = false, detail = "HTTP 500 from example.com"),
)
