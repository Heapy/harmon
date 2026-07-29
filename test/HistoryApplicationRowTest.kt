import app.cash.sqldelight.driver.native.inMemoryDriver
import dev.yoda.harmon.db.HarmonDatabase
import dev.yoda.harmon.history.insertApplicationUsage
import dev.yoda.harmon.history.insertSample
import dev.yoda.harmon.history.upsertApplication
import dev.yoda.harmon.model.ApplicationUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Round-trips the `application` lookup and every column of `application_sample` through a real
 * SQLite.
 *
 * As in `HistorySampleRowTest` and `HistoryProcessRowTest`, the values come from a local builder
 * rather than `TestFixtures`: a transposed pair of same-typed columns survives the fixture, where
 * `userCpuPercent == cpuPercent` and most rates are zero. Here every field carries a value no other
 * field carries.
 */
class HistoryApplicationRowTest {

    @Test
    fun everyApplicationUsageFieldLandsInItsOwnColumn() {
        val database = openApplicationDatabase()
        val applications = database.applicationsQueries
        val sampleId = database.insertOwningSample()

        val applicationId = assertNotNull(applications.upsertApplication(markedApplication()))
        applications.insertApplicationUsage(sampleId, applicationId, markedApplication())

        val lookup = applications.selectApplications().executeAsOne()
        assertEquals("bundle:00000000deadbeef", lookup.key)
        assertEquals("Marked", lookup.name)
        assertEquals("/Applications/Marked.app", lookup.bundle_path)

        val stored = applications.selectApplicationSamples(sampleId).executeAsOne()
        assertEquals(sampleId, stored.sample_id)
        assertEquals(applicationId, stored.application_id)
        assertEquals(4242L, stored.root_pid)
        assertEquals(3L, stored.process_count)

        assertEquals(11.5, stored.cpu_percent)
        assertEquals(12.5, stored.user_cpu_percent)
        assertEquals(13.5, stored.system_cpu_percent)

        assertEquals(22_000_000_001L, stored.physical_footprint_bytes)
        assertEquals(22_000_000_002L, stored.resident_bytes)
        assertEquals(22_000_000_003L, stored.wired_bytes)
        assertEquals(22_000_000_004L, stored.lifetime_max_physical_footprint_bytes)
        assertEquals(22_000_000_005L, stored.compressed_or_paged_out_bytes)
        assertEquals(2L, stored.compressed_attribution_process_count)

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
     * The singleton group `ApplicationGrouper` hands to every process outside an `.app` bundle would
     * repeat a `process_sample` row for no gain, so it leaves no trace in either table. Both kinds of
     * group go through the loop `record` will run, so the test cannot pass by storing nothing at all.
     */
    @Test
    fun aGroupWithoutABundleIsNotStoredAtAll() {
        val database = openApplicationDatabase()
        val applications = database.applicationsQueries
        val sampleId = database.insertOwningSample()

        val loose = markedApplication().copy(
            id = "process:4242:21000000001",
            name = "marked-process",
            bundlePath = null,
        )
        for (application in listOf(markedApplication(), loose)) {
            val applicationId = applications.upsertApplication(application) ?: continue
            applications.insertApplicationUsage(sampleId, applicationId, application)
        }

        assertNull(applications.upsertApplication(loose), "a group with no bundle has no lookup id")
        assertEquals(
            listOf("bundle:00000000deadbeef"),
            applications.selectApplications().executeAsList().map { it.key },
        )
        assertEquals(1, applications.selectApplicationSamples(sampleId).executeAsList().size)
    }

    /**
     * The lookup is what makes the design pay: an application seen 288 times a day must cost one row,
     * and two applications must not collapse into one.
     */
    @Test
    fun theLookupHoldsOneRowPerApplicationKey() {
        val applications = openApplicationDatabase().applicationsQueries
        val marked = markedApplication()

        val first = applications.upsertApplication(marked)
        val again = applications.upsertApplication(marked.copy(cpuPercent = 99.0))
        val other = applications.upsertApplication(
            marked.copy(
                id = "bundle:00000000cafebabe",
                name = "Other",
                bundlePath = "/Applications/Other.app",
            ),
        )

        assertEquals(first, again, "the same key in a later sample reuses its row")
        assertNotEquals(first, other, "a different bundle is a different application")
        assertEquals(2, applications.selectApplications().executeAsList().size)
    }
}

private fun openApplicationDatabase(): HarmonDatabase =
    HarmonDatabase(inMemoryDriver(HarmonDatabase.Schema))

/**
 * A sample row for the application rows to hang off. Nothing about it is asserted — `sample_id` just
 * needs a parent that exists — so the fixture is the right source here.
 */
private fun HarmonDatabase.insertOwningSample(): Long {
    samplesQueries.insertSample(systemUsage(emptyList()))
    return samplesQueries.lastInsertedId().executeAsOne()
}

/**
 * An `ApplicationUsage` in which no two columns of `application` or `application_sample` share a
 * value. Deliberately not in `TestFixtures`: its whole purpose is to be unrealistic.
 */
private fun markedApplication(): ApplicationUsage = ApplicationUsage(
    id = "bundle:00000000deadbeef",
    name = "Marked",
    bundlePath = "/Applications/Marked.app",
    rootPid = 4242,
    processIds = listOf(4242, 4243, 4244),
    cpuPercent = 11.5,
    userCpuPercent = 12.5,
    systemCpuPercent = 13.5,
    physicalFootprintBytes = 22_000_000_001uL,
    residentBytes = 22_000_000_002uL,
    wiredBytes = 22_000_000_003uL,
    lifetimeMaxPhysicalFootprintBytes = 22_000_000_004uL,
    compressedOrPagedOutBytes = 22_000_000_005uL,
    compressedAttributionProcessCount = 2,
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
