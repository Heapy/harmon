import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.yoda.harmon.history.HistoryStore
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.fail
import kotlin.time.Instant

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
     * Dropping `application_sample` fails the write halfway through, after the sample, its processes
     * and both lookups are already in. A partial sample is worse than a missing one — a process count
     * read back from it would be a lie no reader could detect — so what matters is not that `record`
     * threw but that nothing of the sample is left.
     */
    @Test
    fun aFailedWriteLeavesNothingOfItsSample() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.driver.execute(null, "DROP TABLE application_sample", 0)

            assertFails { store.record(recordedReport(), deliveries()) }

            for (table in listOf("sample", "process_sample", "process", "application", "alert")) {
                assertEquals(0L, store.driver.countRows(table), "$table kept a row of a rolled-back sample")
            }
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

private fun deliveries(): List<DeliveryResult> = listOf(
    DeliveryResult(channel = "notification-center", successful = true, detail = "posted"),
    DeliveryResult(channel = "webhook", successful = false, detail = "HTTP 500 from example.com"),
)

/** Every sample in the database, in the order the retention window reads them. */
private fun HistoryStore.samples() = database.samplesQueries
    .selectBetween("0000-01-01T00:00:00Z", "9999-12-31T23:59:59Z")
    .executeAsList()

/**
 * The row count of [table], asked of the database rather than of a generated query: after
 * `DROP TABLE application_sample` half the generated queries no longer compile against the file, and
 * a rollback has to be provable table by table.
 */
private fun SqlDriver.countRows(table: String): Long = executeQuery(
    identifier = null,
    sql = "SELECT count(*) FROM $table",
    mapper = { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0))
    },
    parameters = 0,
).value ?: fail("counting $table returned no row")
