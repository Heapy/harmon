package dev.yoda.harmon.monitor

import dev.yoda.harmon.analysis.ApplicationGrouper
import dev.yoda.harmon.model.ProcessUsage
import dev.yoda.harmon.model.RawProcessSample
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.SystemUsage

class UsageCalculator(
    private val applicationGrouper: ApplicationGrouper = ApplicationGrouper(),
) {
    fun calculate(
        previous: RawSystemSnapshot,
        current: RawSystemSnapshot,
    ): SystemUsage {
        require(current.monotonicTimeNs > previous.monotonicTimeNs) {
            "Snapshots must be ordered by monotonic time"
        }

        val elapsedNanoseconds = current.monotonicTimeNs - previous.monotonicTimeNs
        val elapsedSeconds = elapsedNanoseconds.toDouble() / NANOSECONDS_PER_SECOND
        val previousByIdentity = previous.processes.associateBy { it.identity }

        val processes = current.processes.map { currentProcess ->
            val previousProcess = previousByIdentity[currentProcess.identity]
            calculateProcessUsage(previousProcess, currentProcess, elapsedSeconds)
        }

        return SystemUsage(
            capturedAt = current.capturedAt,
            elapsedSeconds = elapsedSeconds,
            physicalMemoryBytes = current.physicalMemoryBytes,
            swap = current.swap,
            power = current.power,
            totalProcessCount = current.totalProcessCount,
            inaccessibleProcessCount = current.inaccessibleProcessCount,
            processes = processes,
            applications = applicationGrouper.group(processes),
            processIssues = current.processIssues,
        )
    }

    private fun calculateProcessUsage(
        previous: RawProcessSample?,
        current: RawProcessSample,
        elapsedSeconds: Double,
    ): ProcessUsage {
        val userSeconds = delta(current.userTimeNs, previous?.userTimeNs)
            .toDouble() / NANOSECONDS_PER_SECOND
        val systemSeconds = delta(current.systemTimeNs, previous?.systemTimeNs)
            .toDouble() / NANOSECONDS_PER_SECOND
        val userPercent = (userSeconds / elapsedSeconds) * 100.0
        val systemPercent = (systemSeconds / elapsedSeconds) * 100.0

        val wakeups = delta(
            current.packageIdleWakeups + current.interruptWakeups,
            previous?.let { it.packageIdleWakeups + it.interruptWakeups },
        ).toDouble() / elapsedSeconds
        val readBytesPerSecond = delta(
            current.diskBytesRead,
            previous?.diskBytesRead,
        ).toDouble() / elapsedSeconds
        val writeBytesPerSecond = delta(
            current.diskBytesWritten,
            previous?.diskBytesWritten,
        ).toDouble() / elapsedSeconds
        val billedEnergyPerSecond = delta(
            current.billedEnergy,
            previous?.billedEnergy,
        ).toDouble() / elapsedSeconds

        val cpuPercent = userPercent + systemPercent
        val ioMiBPerSecond =
            (readBytesPerSecond + writeBytesPerSecond) / BYTES_PER_MEBIBYTE
        val impactScore =
            cpuPercent +
                (wakeups * WAKEUP_SCORE_WEIGHT) +
                (ioMiBPerSecond * IO_SCORE_WEIGHT)

        return ProcessUsage(
            identity = current.identity,
            parentPid = current.parentPid,
            uid = current.uid,
            name = current.name,
            executablePath = current.executablePath,
            cpuPercent = cpuPercent.finiteNonNegative(),
            userCpuPercent = userPercent.finiteNonNegative(),
            systemCpuPercent = systemPercent.finiteNonNegative(),
            physicalFootprintBytes = current.physicalFootprintBytes,
            residentBytes = current.residentBytes,
            wakeupsPerSecond = wakeups.finiteNonNegative(),
            diskReadBytesPerSecond = readBytesPerSecond.finiteNonNegative(),
            diskWriteBytesPerSecond = writeBytesPerSecond.finiteNonNegative(),
            billedEnergyPerSecond = billedEnergyPerSecond.finiteNonNegative(),
            batteryImpactScore = impactScore.finiteNonNegative(),
        )
    }

    private fun delta(current: ULong, previous: ULong?): ULong =
        if (previous != null && current >= previous) current - previous else 0u

    private fun Double.finiteNonNegative(): Double =
        takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    private companion object {
        const val NANOSECONDS_PER_SECOND = 1_000_000_000.0
        const val BYTES_PER_MEBIBYTE = 1_048_576.0

        // This is deliberately a transparent heuristic, not Activity Monitor's
        // private "Energy Impact" metric.
        const val WAKEUP_SCORE_WEIGHT = 0.25
        const val IO_SCORE_WEIGHT = 2.0
    }
}
