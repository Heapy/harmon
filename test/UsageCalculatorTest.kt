import dev.yoda.harmon.monitor.UsageCalculator
import kotlin.test.Test
import kotlin.test.assertEquals

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
                    diskRead = 1_000u,
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
                    diskRead = 3_000u,
                    billedEnergy = 140u,
                ),
            ),
        )

        val usage = UsageCalculator().calculate(previous, current)
        val process = usage.processes.single()

        assertEquals(2.0, usage.elapsedSeconds, absoluteTolerance = 0.0001)
        assertEquals(75.0, process.cpuPercent, absoluteTolerance = 0.0001)
        assertEquals(10.0, process.wakeupsPerSecond, absoluteTolerance = 0.0001)
        assertEquals(1_000.0, process.diskReadBytesPerSecond, absoluteTolerance = 0.0001)
        assertEquals(20.0, process.billedEnergyPerSecond, absoluteTolerance = 0.0001)
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
}

