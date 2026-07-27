import dev.yoda.harmon.monitor.UsageCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
