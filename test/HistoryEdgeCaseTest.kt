import dev.yoda.harmon.analysis.AlertKeyState
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.SystemUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The states a real machine reaches in which one of the lists a sample is made of, or one of the
 * subsystems it describes, is simply not there.
 *
 * None of these is hypothetical: a desktop Mac has no battery, an unreadable drive yields no storage
 * counters, a quiet hour produces no alerts, and a sample in which every readable process lives
 * outside an `.app` bundle leaves both application tables empty. Each of them empties a list the
 * write path iterates or a subquery the retention pass compares against — and `NOT IN` over an empty
 * subquery is true for every row, where `NOT IN` over an occupied one is true for almost none.
 *
 * All of it goes through the configured store rather than `inMemoryDriver`: the questions here are
 * about foreign keys and about what a cascade leaves behind, and an in-memory driver has foreign
 * keys off.
 */
class HistoryEdgeCaseTest {

    /**
     * The collector can come back with nothing readable — every process refused, or none matched. The
     * sample is still a reading and still belongs in the series: it says the machine looked empty at
     * this moment, and dropping it would leave a gap no reader could tell from an agent that was down.
     */
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

    /**
     * A desktop Mac reports no battery and an unreadable drive reports no counters, and both have to
     * survive the write as absences. The battery columns are nullable precisely so that "there is no
     * battery" cannot read back as "the battery is flat"; `storage_available` says the same about the
     * rates beside it, which are 0 because nothing was measured rather than because nothing happened.
     */
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

    /**
     * The ordinary sample: nothing over a threshold, nothing delivered. Every alert table stays empty
     * for it, and `alert_state` is emptied rather than left holding what the sample before it was
     * alerting on — a key that stopped firing has to leave, or its return is taken for a repeat and
     * never pushed.
     */
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
            assertEquals(1L, store.driver.countRows("alert_delivery"), "the earlier sample kept its own")
        }
    }

    /**
     * `ApplicationGrouper` wraps every process outside an `.app` in a singleton group of its own, and
     * those groups are deliberately not stored. On a sample where no process is inside a bundle that
     * leaves both application tables empty and every `process_sample.application_id` null — the one
     * arrangement in which that null has to mean "outside a bundle" rather than "the writer stopped
     * halfway".
     */
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

    /**
     * The retention pass over samples that never filled the tables it cleans. Both orphan deletes are
     * `NOT IN (SELECT …)`, and over an empty subquery that predicate holds for every row instead of
     * for none — so a database that only ever held such samples is exactly where a lookup would be
     * swept while a sample still names it, or kept forever while none does.
     */
    @Test
    fun theRetentionPassSurvivesSamplesThatFilledNothing() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(emptyMachineReport())
            store.record(unbundledReport())

            store.prune(AFTER_EVERY_SAMPLE)

            for (table in listOf("sample", "process_sample", "process", "application")) {
                assertEquals(0L, store.driver.countRows(table), "$table outlived the window")
            }
        }
    }

    /**
     * The other half of the pass above: this sample is inside the window, so its lookup rows are in
     * use and have to stay — even though `application_sample`, the table that decides which
     * applications are still referenced, holds nothing at all.
     */
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

/** A cutoff every stored sample falls before, so the pass takes the lot. */
private const val AFTER_EVERY_SAMPLE = "9999-12-31T23:59:59Z"

/** A cutoff every stored sample falls after, so the pass may take nothing. */
private const val BEFORE_EVERY_SAMPLE = "1970-01-01T00:00:00Z"

/** A sample in which the collector could read no process at all. */
private fun emptyMachineReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(processes = emptyList()),
    alerts = emptyList(),
    topProcessCount = 3,
)

/** A machine with no battery and no readable storage counters. */
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

/** A sample where every process runs outside an `.app`, so every group is a singleton. */
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
