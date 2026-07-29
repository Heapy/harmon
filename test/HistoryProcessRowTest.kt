import dev.yoda.harmon.db.HarmonDatabase
import dev.yoda.harmon.history.insertProcessUsage
import dev.yoda.harmon.history.insertSample
import dev.yoda.harmon.history.upsertProcess
import dev.yoda.harmon.model.ProcessIdentity
import dev.yoda.harmon.model.ProcessUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Round-trips the `process` lookup and every column of `process_sample` through a real SQLite.
 *
 * As in `HistorySampleRowTest`, the values come from a local builder rather than `TestFixtures`:
 * the fixture sets `userCpuPercent == cpuPercent`, `residentBytes == physicalFootprintBytes` and
 * zero in most rates, so a transposed pair of same-typed columns would round-trip through it
 * looking correct. Here every field carries a value no other field carries.
 */
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

    /**
     * The lookup is what makes the design pay: a process seen 288 times a day must cost one row, and
     * a pid handed to a new process must not silently inherit the old one's name.
     */
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

    /**
     * The naming half of the row freezes at first sighting, which is what `ON CONFLICT DO NOTHING`
     * buys and the only reason the lookup costs one write per process rather than 288 a day.
     *
     * Worth pinning because the alternative reads like a bug fix: turning the clause into
     * `DO UPDATE` so a renamed process shows its new name would rewrite every row already written
     * under the old one, and every assertion above would stay green while it happened.
     */
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

    /** A refused reading must stay distinguishable from a reading of zero. */
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

}

/**
 * A sample row for the process rows to hang off. Nothing about it is asserted — `sample_id` just
 * needs a parent that exists — so the fixture is the right source here.
 */
private fun HarmonDatabase.insertParentSample(): Long {
    samplesQueries.insertSample(systemUsage(emptyList()))
    return samplesQueries.lastInsertedId().executeAsOne()
}

/**
 * A `ProcessUsage` in which no two columns of `process` or `process_sample` share a value.
 * Deliberately not in `TestFixtures`: its whole purpose is to be unrealistic.
 */
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
