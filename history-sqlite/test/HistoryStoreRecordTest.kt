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

private val SAMPLE_TABLES = listOf(
    "sample",
    "process",
    "process_sample",
    "application",
    "application_sample",
    "alert",
    "alert_delivery",
)

private val SURVIVING_TABLES = SAMPLE_TABLES - "alert"

private const val AGENT_STATE_REFUSES_WRITES =
    "CREATE TRIGGER agent_state_is_closed BEFORE INSERT ON agent_state " +
        "BEGIN SELECT RAISE(ABORT, 'agent_state is closed'); END"

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

private fun firingState(): AlertStateSnapshot = AlertStateSnapshot(
    sampleCounter = 41,
    keys = mapOf("cpu:Marked" to AlertKeyState(settled = false, failures = 3, retryAtSample = 44)),
)

private fun settledState(): AlertStateSnapshot =
    AlertStateSnapshot(sampleCounter = 42, keys = emptyMap())

private fun deliveries(): List<DeliveryResult> = listOf(
    DeliveryResult(channel = "notification-center", successful = true, detail = "posted"),
    DeliveryResult(channel = "webhook", successful = false, detail = "HTTP 500 from example.com"),
)
