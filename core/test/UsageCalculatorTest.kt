import dev.yoda.harmon.model.ProcessUsage
import dev.yoda.harmon.model.ReparentedFrom
import dev.yoda.harmon.monitor.CollectionException
import dev.yoda.harmon.monitor.UsageCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageCalculatorTest {
    @Test
    fun calculatesDeltasAcrossTheSamplingWindow() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(
                    userTimeNs = 1_000_000_000u,
                    systemTimeNs = 500_000_000u,
                    wakeups = 10u,
                    pageIns = 4u,
                    diskRead = 1_000u,
                    diskWrite = 2_000u,
                    logicalWrite = 3_000u,
                    energyNanojoules = 1_000_000_000u,
                    billedEnergy = 100u,
                ),
            ),
        )
        val current = rawSnapshot(
            monotonicNs = 3_000_000_000u,
            processes = listOf(
                rawProcess(
                    userTimeNs = 2_000_000_000u,
                    systemTimeNs = 1_000_000_000u,
                    wakeups = 30u,
                    pageIns = 10u,
                    diskRead = 3_000u,
                    diskWrite = 6_000u,
                    logicalWrite = 9_000u,
                    energyNanojoules = 3_000_000_000u,
                    billedEnergy = 140u,
                ),
            ),
        )

        val usage = UsageCalculator().calculate(previous, current)
        val process = usage.processes.single()

        assertEquals(2.0, usage.elapsedSeconds, absoluteTolerance = 0.0001)
        assertEquals(75.0, process.cpuPercent, absoluteTolerance = 0.0001)
        assertEquals(10.0, process.wakeupsPerSecond, absoluteTolerance = 0.0001)
        assertEquals(3.0, process.pageInsPerSecond, absoluteTolerance = 0.0001)
        assertEquals(1_000.0, process.diskReadBytesPerSecond, absoluteTolerance = 0.0001)
        assertEquals(2_000.0, process.diskWriteBytesPerSecond, absoluteTolerance = 0.0001)
        assertEquals(
            3_000.0,
            process.logicalWriteBytesPerSecond,
            absoluteTolerance = 0.0001,
        )
        assertEquals(1.0, process.energyWatts, absoluteTolerance = 0.0001)
        assertEquals(20.0, process.billedEnergyPerSecond, absoluteTolerance = 0.0001)
        assertEquals(60.0, usage.processor.totalPercent, absoluteTolerance = 0.0001)
        assertEquals(
            500_000_000.0,
            usage.storage.writeBytesPerSecond,
            absoluteTolerance = 0.0001,
        )
    }

    @Test
    fun newProcessHasNoInventedCpuDelta() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = emptyList(),
        )
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(rawProcess(userTimeNs = 10_000_000_000u)),
        )

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertEquals(0.0, process.cpuPercent)
    }

    @Test
    fun doesNotInventStorageRatesAcrossUnavailableSnapshots() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = emptyList(),
        ).let { snapshot ->
            snapshot.copy(
                storage = snapshot.storage.copy(
                    available = false,
                    deviceCount = 0,
                    bytesWritten = 0u,
                ),
            )
        }
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = emptyList(),
        )

        val storage = UsageCalculator().calculate(previous, current).storage

        assertFalse(storage.available)
        assertEquals(0.0, storage.writeBytesPerSecond)
    }

    /**
     * A stalled clock is a collection problem, not a programming error: the agent loop logs
     * [CollectionException] and keeps sampling, while an [IllegalArgumentException] from `require`
     * reads as a bug and carries no numbers to diagnose it with.
     */
    @Test
    fun rejectsSnapshotsThatDidNotAdvanceTheMonotonicClock() {
        val previous = rawSnapshot(monotonicNs = 1_000_000_000u, processes = emptyList())
        val current = rawSnapshot(monotonicNs = 1_000_000_000u, processes = emptyList())

        val failure = assertFailsWith<CollectionException> {
            UsageCalculator().calculate(previous, current)
        }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("monotonic"), message)
        assertTrue(message.contains("1000000000"), message)
    }

    @Test
    fun rejectsSnapshotsInReverseOrder() {
        val previous = rawSnapshot(monotonicNs = 2_000_000_000u, processes = emptyList())
        val current = rawSnapshot(monotonicNs = 1_000_000_000u, processes = emptyList())

        assertFailsWith<CollectionException> {
            UsageCalculator().calculate(previous, current)
        }
    }

    /**
     * The configured terminal list has to reach the grouper that actually builds the applications,
     * not stop at the constructor.
     */
    @Test
    fun handsTheConfiguredTerminalListToTheApplicationGrouper() {
        val processes = listOf(
            rawProcess(
                pid = 600,
                startedAt = 1u,
                name = "Terminal",
                executablePath = "/Applications/Terminal.app/Contents/MacOS/Terminal",
            ),
            rawProcess(
                pid = 601,
                startedAt = 2u,
                parentPid = 600,
                name = "zsh",
                executablePath = "/bin/zsh",
            ),
        )
        val previous = rawSnapshot(monotonicNs = 1_000_000_000u, processes = processes)
        val current = rawSnapshot(monotonicNs = 2_000_000_000u, processes = processes)

        val withoutTerminals = UsageCalculator(terminalApplications = emptySet())
            .calculate(previous, current)
            .applications
        val withDefaults = UsageCalculator().calculate(previous, current).applications

        assertEquals(
            listOf(600, 601),
            withoutTerminals.single { it.name == "Terminal" }.processIds,
        )
        assertEquals(listOf(600), withDefaults.single { it.name == "Terminal" }.processIds)
    }

    /**
     * The transition is the whole signal. A snapshot cannot tell an orphan from a daemon,
     * so the calculator has to report the parent the process had a sample ago.
     */
    @Test
    fun reportsThePreviousParentWhenItChanged() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44268, startedAt = 10u, name = "codex", parentPid = 500),
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 44268),
            ),
        )
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 1),
            ),
        )

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertEquals(ReparentedFrom(pid = 44268, name = "codex"), process.reparentedFrom)
        assertEquals(1, process.parentPid)
    }

    /**
     * A deliberate double fork lands entirely between two samples, so harmon meets the
     * process already detached. No previous sample means no transition, which is what keeps
     * `tmux -L` and `ssh -f` from reporting themselves.
     */
    @Test
    fun reportsNothingForAProcessMissingFromThePreviousSnapshot() {
        val previous = rawSnapshot(monotonicNs = 1_000_000_000u, processes = emptyList())
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "tmux", parentPid = 1),
            ),
        )

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertNull(process.reparentedFrom)
    }

    /**
     * The identity key is (pid, startedAt), so a recycled pid does not resolve to the
     * process that used to wear it and cannot be mistaken for a transition.
     */
    @Test
    fun reportsNothingWhenThePidWasReusedByAnotherProcess() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 44268),
            ),
        )
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 99u, name = "python", parentPid = 1),
            ),
        )

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertNull(process.reparentedFrom)
    }

    /**
     * The name is what turns the alert into a lead, but it is best effort: a parent already
     * gone in the previous snapshot leaves the pid alone to identify it.
     */
    @Test
    fun leavesTheParentNameNullWhenThePreviousSnapshotDidNotCarryIt() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 44268),
            ),
        )
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 1),
            ),
        )

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertEquals(ReparentedFrom(pid = 44268, name = null), process.reparentedFrom)
    }

    /**
     * The field means "changed its parent", not "was orphaned". Filtering on the new parent
     * being pid 1 belongs to the alert rule and to the history store, not here.
     */
    @Test
    fun reportsATransitionToAParentOtherThanPidOne() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(pid = 700, startedAt = 5u, name = "supervisor", parentPid = 1),
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 44268),
            ),
        )
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 700),
            ),
        )

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertEquals(ReparentedFrom(pid = 44268, name = null), process.reparentedFrom)
        assertEquals(700, process.parentPid)
    }

    @Test
    fun reportsNothingWhenTheParentDidNotChange() {
        val processes = listOf(
            rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 44268),
        )
        val previous = rawSnapshot(monotonicNs = 1_000_000_000u, processes = processes)
        val current = rawSnapshot(monotonicNs = 2_000_000_000u, processes = processes)

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertNull(process.reparentedFrom)
    }

    /**
     * Parent pid 0 is the collector's "could not read the metadata" sentinel, not a pid: the
     * bridge pre-sets the field to 0 and leaves it there when `proc_pidinfo` returns a short
     * struct, while the sample itself is still emitted. A read that failed in one sample and
     * succeeded in the next must not surface as a process handed to launchd — that alert would
     * name a parent that never existed, and the stamp it writes to history is permanent.
     */
    @Test
    fun reportsNothingWhenEitherSampleCouldNotReadTheParent() {
        val recovered = transition(previousParentPid = 0, currentParentPid = 1)
        val lost = transition(previousParentPid = 44268, currentParentPid = 0)

        assertNull(recovered.reparentedFrom, "0 -> 1 is a metadata read that recovered")
        assertNull(lost.reparentedFrom, "n -> 0 is a metadata read that failed")
    }

    /**
     * The parent is the process most likely to be one the collector could not measure — a
     * supervisor whose `proc_pid_rusage` was refused arrives as an issue rather than as a sample —
     * and that is exactly the case where naming it matters. A full sample still wins over an issue
     * for the same pid.
     */
    @Test
    fun namesAParentThePreviousSnapshotCouldOnlyRecordAsAnIssue() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 44268),
            ),
            processIssues = listOf(rawProcessIssue(pid = 44268, name = "supervisord")),
        )
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 1),
            ),
        )

        val process = UsageCalculator().calculate(previous, current).processes.single()

        assertEquals(ReparentedFrom(pid = 44268, name = "supervisord"), process.reparentedFrom)
    }

    /**
     * `previousNameByPid` is built once and shared across the loop, which is the shape that would
     * let one process's answer be applied to every other. Two processes losing two different
     * parents in one sample are what says it is not.
     */
    @Test
    fun resolvesEachProcessAgainstItsOwnParent() {
        val previous = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(pid = 300, startedAt = 3u, name = "codex", parentPid = 1),
                rawProcess(pid = 400, startedAt = 4u, name = "make", parentPid = 1),
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 300),
                rawProcess(pid = 44560, startedAt = 21u, name = "cc", parentPid = 400),
                rawProcess(pid = 44561, startedAt = 22u, name = "sshd", parentPid = 1),
            ),
        )
        val current = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = 1),
                rawProcess(pid = 44560, startedAt = 21u, name = "cc", parentPid = 1),
                rawProcess(pid = 44561, startedAt = 22u, name = "sshd", parentPid = 1),
            ),
        )

        val byPid = UsageCalculator().calculate(previous, current)
            .processes
            .associateBy { it.identity.pid }

        assertEquals(ReparentedFrom(pid = 300, name = "codex"), byPid.getValue(44559).reparentedFrom)
        assertEquals(ReparentedFrom(pid = 400, name = "make"), byPid.getValue(44560).reparentedFrom)
        assertNull(byPid.getValue(44561).reparentedFrom)
    }
}

/**
 * The one process of a two-sample pair, seen under [previousParentPid] and then under
 * [currentParentPid].
 */
private fun transition(previousParentPid: Int, currentParentPid: Int): ProcessUsage {
    fun snapshot(monotonicNs: ULong, parentPid: Int) = rawSnapshot(
        monotonicNs = monotonicNs,
        processes = listOf(
            rawProcess(pid = 44559, startedAt = 20u, name = "node", parentPid = parentPid),
        ),
    )

    return UsageCalculator()
        .calculate(
            snapshot(1_000_000_000u, previousParentPid),
            snapshot(2_000_000_000u, currentParentPid),
        )
        .processes
        .single()
}
