import io.heapy.harmon.analysis.AlertKeyState
import io.heapy.harmon.analysis.AlertStateSnapshot
import io.heapy.harmon.model.DeliveryResult
import io.heapy.harmon.model.MonitoringReport
import io.heapy.harmon.model.SystemUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryEdgeCaseTest {

    @Test
    fun aSampleWithNoProcessesIsStillWritten() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(emptyMachineReport())

            assertEquals(0L, store.samples().single().total_process_count)
            for (table in listOf("process", "process_sample", "application", "application_sample")) {
                assertEquals(0L, store.driver.countRows(table), "$table gained a row from nothing")
            }
        }
    }

    @Test
    fun anAbsentBatteryAndAnUnreadableDriveSurviveTheWholeWritePath() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(MonitoringReport(unequippedMachine(), emptyList(), topProcessCount = 3))

            val sample = store.samples().single()
            assertEquals(0L, sample.battery_available)
            assertNull(sample.battery_percentage, "no battery is not a battery at zero percent")
            assertNull(sample.battery_minutes_remaining)
            assertEquals(0L, sample.storage_available)
        }
    }

    @Test
    fun aQuietSampleClearsTheAlertStateItInherited() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(
                report = alertingReport(),
                deliveries = listOf(DeliveryResult("webhook", successful = true, detail = "200")),
                alertState = snapshotOf("cpu:Marked"),
            )
            assertEquals(1L, store.driver.countRows("alert_state"), "there was nothing to clear")

            store.record(quietReport(), alertState = snapshotOf())

            val quiet = store.samples().last()
            assertEquals(2, store.samples().size)
            assertTrue(store.database.alertsQueries.selectAlerts(quiet.id).executeAsList().isEmpty())
            assertEquals(0L, store.driver.countRows("alert_state"))
            assertEquals(
                1L,
                store.driver.countRows("alert_delivery"),
                "the earlier sample kept its own",
            )
        }
    }

    @Test
    fun aMachineWithNoBundledProcessLeavesTheApplicationTablesEmpty() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(unbundledReport())

            val sample = store.samples().single()
            assertEquals(0L, store.driver.countRows("application"))
            assertEquals(0L, store.driver.countRows("application_sample"))
            assertEquals(3L, store.driver.countRows("process_sample"))
            assertTrue(
                store.database.processesQueries.selectProcessSamples(sample.id).executeAsList()
                    .all { it.application_id == null },
                "a process outside a bundle belongs to no stored application",
            )
        }
    }

    @Test
    fun theRetentionPassSurvivesSamplesThatFilledNothing() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(emptyMachineReport())
            store.record(unbundledReport())
            store.driver.execute(null, ORPHANED_APPLICATION, 0)

            val emptied = listOf("sample", "process_sample", "process", "application")
            for (table in emptied) {
                assertTrue(store.driver.countRows(table) > 0, "$table has nothing to lose")
            }

            store.prune(AFTER_EVERY_SAMPLE)

            for (table in emptied) {
                assertEquals(0L, store.driver.countRows(table), "$table outlived the window")
            }
        }
    }

    @Test
    fun anUnbundledSampleInsideTheWindowKeepsItsLookupRows() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(unbundledReport())

            store.prune(BEFORE_EVERY_SAMPLE)

            assertEquals(1, store.samples().size)
            assertEquals(3L, store.driver.countRows("process"), "the processes are still referenced")
            assertEquals(3L, store.driver.countRows("process_sample"))
        }
    }
}

private const val ORPHANED_APPLICATION =
    "INSERT INTO application(key, name, bundle_path) " +
        "VALUES ('bundle:orphan', 'Orphan', '/Applications/Orphan.app')"

private fun emptyMachineReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(processes = emptyList()),
    alerts = emptyList(),
    topProcessCount = 3,
)

private fun unequippedMachine(): SystemUsage {
    val usage = systemUsage(processes = listOf(processUsage(pid = 11, name = "solo")))
    return usage.copy(
        power = usage.power.copy(
            batteryAvailable = false,
            onBattery = false,
            charging = false,
            percentage = null,
            minutesRemaining = null,
        ),
        storage = usage.storage.copy(available = false),
    )
}

private fun unbundledReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(pid = 11, name = "zsh"),
            processUsage(pid = 12, name = "sshd"),
            processUsage(pid = 13, name = "kernel_task"),
        ),
    ).copy(capturedAt = Instant.parse("2026-07-29T00:05:00Z")),
    alerts = emptyList(),
    topProcessCount = 3,
)

private fun alertingReport(): MonitoringReport = MonitoringReport(
    usage = bundledUsage(Instant.parse("2026-07-29T00:00:00Z")),
    alerts = listOf(alert(key = "cpu:Marked")),
    topProcessCount = 3,
)

private fun quietReport(): MonitoringReport = MonitoringReport(
    usage = bundledUsage(Instant.parse("2026-07-29T00:05:00Z")),
    alerts = emptyList(),
    topProcessCount = 3,
)

private fun bundledUsage(capturedAt: Instant): SystemUsage = systemUsage(
    processes = listOf(
        processUsage(
            pid = 11,
            name = "Marked",
            executablePath = "/Applications/Marked.app/Contents/MacOS/Marked",
        ),
    ),
).copy(capturedAt = capturedAt)

private fun snapshotOf(vararg keys: String): AlertStateSnapshot = AlertStateSnapshot(
    sampleCounter = keys.size.toLong() + 1,
    keys = keys.associateWith {
        AlertKeyState(settled = true, failures = 0, retryAtSample = 0)
    },
)
