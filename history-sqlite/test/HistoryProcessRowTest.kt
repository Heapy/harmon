import dev.yoda.harmon.history.HistoryStore
import dev.yoda.harmon.history.insertProcessUsage
import dev.yoda.harmon.history.upsertProcess
import dev.yoda.harmon.model.INIT_PID
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessIdentity
import dev.yoda.harmon.model.ProcessUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** A parent that is not launchd, so a change to it is a reparenting the store must not stamp. */
private const val OTHER_PARENT_PID = 4242

/** The two processes sampled beside the orphan, which nothing must stamp. */
private const val QUIET_PID_BEFORE = 10

private const val QUIET_PID_AFTER = 12

/**
 * Round-trips the `process` lookup and every column of `process_sample` through a real SQLite.
 *
 * As in `HistorySampleRowTest`, the values come from a local builder rather than `TestFixtures`:
 * the fixture sets `userCpuPercent == cpuPercent`, `residentBytes == physicalFootprintBytes` and
 * zero in most rates, so a transposed pair of same-typed columns would round-trip through it
 * looking correct. Here every field carries a value no other field carries.
 *
 * `reparented_at` is the one column with no round trip to test, because nothing writes it from a
 * `ProcessUsage` field. It is written by `record`, for the processes it decides are worth stamping,
 * so the tests for it go through a store over a scratch home rather than through the driver here.
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
     * The naming half of the row freezes at first sighting, which is the only reason the lookup
     * costs one write per process rather than 288 a day.
     *
     * Worth pinning because the alternative reads like a bug fix: widening the conflict clause so
     * that a renamed process shows its new name would rewrite every row already written under the
     * old one, and every assertion above would stay green while it happened. The clause does update
     * one column, and the case below is the whole of what it may touch.
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

    /**
     * A path that was unreadable at first sighting is filled in by the sighting that reads it, and
     * by no other.
     *
     * The one column the freeze above does not cover, because null is not a value the process ever
     * had — it is the collector saying it could not look. A process whose binary has been replaced
     * refuses `proc_pidpath` for as long as it runs, which for a long-lived session is days of
     * samples: frozen, every one of them would carry a null the reports cannot group, and the
     * bridge learning to read the path would fix nothing for the processes already running. The
     * second half of the assertion is the direction that must not happen — a path already stored is
     * not replaced by a later reading of it, or the freeze is gone.
     */
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

    /**
     * The stamp is the sample's own moment rather than a clock read at the statement, which is what
     * lets a row in `process` be lined up with the sample that produced it — the two are the same
     * string, and the assertion says so rather than repeating the literal twice.
     */
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

    /**
     * `reparentedFrom` says the parent changed and nothing more. Only a change to launchd is a
     * process that lost its parent; a change to any other pid is a reparenting the store has no
     * reason to record, and the gate that decides so lives in `record` rather than in the
     * calculator.
     */
    @Test
    fun aChangeToAnyOtherParentIsNotStamped() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(orphanReport(parentPid = OTHER_PARENT_PID))

            assertNull(store.storedProcess().reparented_at)
        }
    }

    /**
     * The other half of that gate, and the half that keeps every daemon on the machine out of the
     * column: a process first seen already under launchd has `parentPid == 1` and no transition to
     * go with it. A stamp written off the parent alone would mark hundreds of rows a day.
     */
    @Test
    fun aProcessThatWasAlwaysUnderLaunchdIsNotStamped() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            store.record(orphanReport(lostParent = null))

            assertNull(store.storedProcess().reparented_at)
        }
    }

    /**
     * `parent_pid` freezes at first sighting because the lookup insert conflicts into a no-op, and
     * here that freeze is the feature: the column keeps the parent that died while the new column
     * records that it did. Stamping through the upsert instead — the obvious shortcut — would
     * replace the culprit with pid 1 and leave nothing pointing at what went away.
     */
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

    /**
     * What is stored is when the parent was lost, not when the loss was last noticed, so a second
     * sighting must not move the stamp. `reparented_at IS NULL` in the statement is what holds it
     * still; the second sample is deliberately taken five minutes later than the first, or an
     * unguarded write would land the same string and the test would prove nothing.
     */
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

    /**
     * The stamp has to find the row of the process it is about, and one process cannot say that it
     * does: with a single row in `process`, a `record` that stamped every process it wrote, or
     * reused one id for all of them, passes every assertion above.
     *
     * Three processes, all under launchd, of which only the middle one carries a transition. The
     * two quiet ones are ordinary daemons — the state a machine is in a few hundred times over —
     * and they are placed on either side so that neither "the first row" nor "the last row" is the
     * answer by accident.
     */
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

/**
 * The one row the stamping tests write to `process`, which is also all of it they read back.
 */
private fun HistoryStore.storedProcess() =
    database.processesQueries.selectProcesses().executeAsOne()

/** One sample holding the orphan of this file between two daemons that never lost anything. */
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
