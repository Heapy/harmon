import dev.yoda.harmon.monitor.CollectionException
import dev.yoda.harmon.monitor.UsageCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
