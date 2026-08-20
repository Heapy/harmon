import io.heapy.harmon.history.HistoryStore
import io.heapy.harmon.history.insertProcessUsage
import io.heapy.harmon.history.upsertProcess
import io.heapy.harmon.model.INIT_PID
import io.heapy.harmon.model.MonitoringReport
import io.heapy.harmon.model.ProcessIdentity
import io.heapy.harmon.model.ProcessUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val OTHER_PARENT_PID = 4242

private const val QUIET_PID_BEFORE = 10

private const val QUIET_PID_AFTER = 12

class HistoryProcessRowTest {

    @Test
    fun everyProcessUsageFieldLandsInItsOwnColumn() = withInMemoryDatabase { database ->
        val processes = database.processesQueries
        val sampleId = database.insertParentSample()

        val processId = processes.upsertProcess(markedProcess())
        processes.insertProcessUsage(
            sampleId = sampleId,
            processId = processId,
            applicationId = 7L,
            usage = markedProcess(),
        )

        val lookup = processes.selectProcesses().executeAsOne()
        assertEquals(4242L, lookup.pid)
        assertEquals(21_000_000_001L, lookup.started_at)
        assertEquals("marked-process", lookup.name)
        assertEquals("/Applications/Marked.app/Contents/MacOS/Marked", lookup.executable_path)
        assertEquals(502L, lookup.uid)
        assertEquals(4241L, lookup.parent_pid)

        val stored = processes.selectProcessSamples(sampleId).executeAsOne()
        assertEquals(sampleId, stored.sample_id)
        assertEquals(processId, stored.process_id)
        assertEquals(7L, stored.application_id)

        assertEquals(11.5, stored.cpu_percent)
        assertEquals(12.5, stored.user_cpu_percent)
        assertEquals(13.5, stored.system_cpu_percent)

        assertEquals(22_000_000_001L, stored.physical_footprint_bytes)
        assertEquals(22_000_000_002L, stored.resident_bytes)
        assertEquals(22_000_000_003L, stored.wired_bytes)
        assertEquals(22_000_000_004L, stored.lifetime_max_physical_footprint_bytes)
        assertEquals(22_000_000_005L, stored.compressed_or_paged_out_bytes)
        assertEquals(251L, stored.virtual_memory_region_count)

        assertEquals(31.5, stored.wakeups_per_second)
        assertEquals(32.5, stored.page_ins_per_second)
        assertEquals(33.5, stored.disk_read_bytes_per_second)
        assertEquals(34.5, stored.disk_write_bytes_per_second)
        assertEquals(35.5, stored.logical_write_bytes_per_second)
        assertEquals(36.5, stored.instructions_per_second)
        assertEquals(37.5, stored.cycles_per_second)
        assertEquals(38.5, stored.energy_watts)
        assertEquals(39.5, stored.faults_per_second)
        assertEquals(40.5, stored.copy_on_write_faults_per_second)
        assertEquals(41.5, stored.system_calls_per_second)
        assertEquals(42.5, stored.context_switches_per_second)
        assertEquals(61L, stored.thread_count)
        assertEquals(62L, stored.running_thread_count)
        assertEquals(43.5, stored.billed_energy_per_second)
        assertEquals(44.5, stored.battery_impact_score)
    }

    @Test
    fun theLookupHoldsOneRowPerProcessIdentity() = withInMemoryDatabase { database ->
        val processes = database.processesQueries
        val marked = markedProcess()

        val first = processes.upsertProcess(marked)
        val again = processes.upsertProcess(marked.copy(cpuPercent = 99.0))
        val reborn = processes.upsertProcess(
            marked.copy(identity = marked.identity.copy(startedAt = 21_000_000_002uL)),
        )

        assertEquals(first, again, "the same identity in a later sample reuses its row")
        assertNotEquals(first, reborn, "the same pid started later is a different process")
        assertEquals(2, processes.selectProcesses().executeAsList().size)
    }

    @Test
    fun theNamingColumnsKeepWhatTheProcessWasFirstSeenAs() = withInMemoryDatabase { database ->
        val processes = database.processesQueries
        val marked = markedProcess()

        processes.upsertProcess(marked)
        processes.upsertProcess(
            marked.copy(
                name = "renamed-itself",
                executablePath = "/usr/bin/somewhere-else",
                uid = 0u,
                parentPid = 1,
            ),
        )

        val lookup = processes.selectProcesses().executeAsOne()
        assertEquals("marked-process", lookup.name, "a later sighting rewrote the stored name")
        assertEquals("/Applications/Marked.app/Contents/MacOS/Marked", lookup.executable_path)
        assertEquals(502L, lookup.uid)
        assertEquals(4241L, lookup.parent_pid)
    }

    @Test
    fun anUnreadablePathIsFilledInOnceItBecomesReadable() = withInMemoryDatabase { database ->
        val processes = database.processesQueries
        val unreadable = markedProcess().copy(executablePath = null)

        val first = processes.upsertProcess(unreadable)
        val second = processes.upsertProcess(markedProcess())
        val third = processes.upsertProcess(
            markedProcess().copy(executablePath = "/usr/bin/somewhere-else"),
        )

        assertEquals(first, second, "filling the path in must not write a second row")
        assertEquals(first, third)
        assertEquals(
            "/Applications/Marked.app/Contents/MacOS/Marked",
            processes.selectProcesses().executeAsOne().executable_path,
        )
    }

    @Test
    fun anUnavailableFieldStaysNullRatherThanZero() = withInMemoryDatabase { database ->
        val processes = database.processesQueries
        val sampleId = database.insertParentSample()

        val unattributed = markedProcess().copy(
            uid = null,
            executablePath = null,
            compressedOrPagedOutBytes = null,
            virtualMemoryRegionCount = null,
        )
        processes.insertProcessUsage(
            sampleId = sampleId,
            processId = processes.upsertProcess(unattributed),
            applicationId = null,
            usage = unattributed,
        )

        val lookup = processes.selectProcesses().executeAsOne()
        assertNull(lookup.uid)
        assertNull(lookup.executable_path)

        val stored = processes.selectProcessSamples(sampleId).executeAsOne()
        assertNull(stored.compressed_or_paged_out_bytes)
        assertNull(stored.virtual_memory_region_count)
    }

    @Test
    fun aProcessHandedToLaunchdIsStampedWithItsSample() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(orphanReport())

            val lookup = store.storedProcess()
            assertEquals(FIRST_SAMPLE_AT, lookup.reparented_at)
            assertEquals(
                store.samples().single().captured_at,
                lookup.reparented_at,
                "the mark and the sample that carried it must be the same moment",
            )
        }
    }

    @Test
    fun aChangeToAnyOtherParentIsNotStamped() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(orphanReport(parentPid = OTHER_PARENT_PID))

            assertNull(store.storedProcess().reparented_at)
        }
    }

    @Test
    fun aProcessThatWasAlwaysUnderLaunchdIsNotStamped() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(orphanReport(lostParent = null))

            assertNull(store.storedProcess().reparented_at)
        }
    }

    @Test
    fun theStampLeavesTheParentThatDiedInPlace() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(orphanReport(parentPid = LOST_PARENT_PID, lostParent = null))
            store.record(orphanReport(capturedAt = SECOND_SAMPLE))

            val lookup = store.storedProcess()
            assertEquals(LOST_PARENT_PID.toLong(), lookup.parent_pid, "the culprit was overwritten")
            assertEquals(SECOND_SAMPLE_AT, lookup.reparented_at)
        }
    }

    @Test
    fun aSecondSightingDoesNotMoveTheStamp() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            val transition = orphanReport()
            store.record(transition)
            store.record(
                transition.copy(usage = transition.usage.copy(capturedAt = SECOND_SAMPLE)),
            )

            assertEquals(
                listOf(FIRST_SAMPLE_AT, SECOND_SAMPLE_AT),
                store.samples().map { it.captured_at },
                "the second sample has to carry a different moment for the assertion below to bite",
            )
            assertEquals(
                FIRST_SAMPLE_AT,
                store.storedProcess().reparented_at,
            )
        }
    }

    @Test
    fun onlyTheOrphanAmongItsNeighboursIsStamped() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(sampleAroundOneOrphan())

            val byPid = store.database.processesQueries.selectProcesses()
                .executeAsList()
                .associateBy { it.pid }

            assertEquals(3, byPid.size, "all three processes must reach the lookup")
            assertNull(byPid.getValue(QUIET_PID_BEFORE.toLong()).reparented_at)
            assertEquals(FIRST_SAMPLE_AT, byPid.getValue(ORPHANED_PID.toLong()).reparented_at)
            assertNull(byPid.getValue(QUIET_PID_AFTER.toLong()).reparented_at)
        }
    }
}

private fun HistoryStore.storedProcess() =
    database.processesQueries.selectProcesses().executeAsOne()

private fun sampleAroundOneOrphan(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(pid = QUIET_PID_BEFORE, name = "quiet-before", parentPid = INIT_PID),
            processUsage(
                pid = ORPHANED_PID,
                name = "abandoned",
                parentPid = INIT_PID,
                reparentedFrom = LOST_PARENT,
            ),
            processUsage(pid = QUIET_PID_AFTER, name = "quiet-after", parentPid = INIT_PID),
        ),
    ).copy(capturedAt = FIRST_SAMPLE),
    alerts = emptyList(),
    topProcessCount = 3,
)

private fun markedProcess(): ProcessUsage = ProcessUsage(
    identity = ProcessIdentity(pid = 4242, startedAt = 21_000_000_001uL),
    parentPid = 4241,
    uid = 502u,
    name = "marked-process",
    executablePath = "/Applications/Marked.app/Contents/MacOS/Marked",
    cpuPercent = 11.5,
    userCpuPercent = 12.5,
    systemCpuPercent = 13.5,
    physicalFootprintBytes = 22_000_000_001uL,
    residentBytes = 22_000_000_002uL,
    wiredBytes = 22_000_000_003uL,
    lifetimeMaxPhysicalFootprintBytes = 22_000_000_004uL,
    compressedOrPagedOutBytes = 22_000_000_005uL,
    virtualMemoryRegionCount = 251,
    wakeupsPerSecond = 31.5,
    pageInsPerSecond = 32.5,
    diskReadBytesPerSecond = 33.5,
    diskWriteBytesPerSecond = 34.5,
    logicalWriteBytesPerSecond = 35.5,
    instructionsPerSecond = 36.5,
    cyclesPerSecond = 37.5,
    energyWatts = 38.5,
    faultsPerSecond = 39.5,
    copyOnWriteFaultsPerSecond = 40.5,
    systemCallsPerSecond = 41.5,
    contextSwitchesPerSecond = 42.5,
    threadCount = 61,
    runningThreadCount = 62,
    billedEnergyPerSecond = 43.5,
    batteryImpactScore = 44.5,
)
