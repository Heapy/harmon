package dev.yoda.harmon.monitor

import dev.yoda.harmon.analysis.ApplicationGrouper
import dev.yoda.harmon.config.DEFAULT_TERMINAL_APPLICATIONS
import dev.yoda.harmon.model.ProcessorCounters
import dev.yoda.harmon.model.ProcessorUsage
import dev.yoda.harmon.model.ProcessUsage
import dev.yoda.harmon.model.RawProcessSample
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.StorageUsage
import dev.yoda.harmon.model.SystemUsage
import dev.yoda.harmon.model.VirtualMemoryUsage

class UsageCalculator(
    terminalApplications: Set<String> = DEFAULT_TERMINAL_APPLICATIONS,
) {
    private val applicationGrouper = ApplicationGrouper(terminalApplications)

    fun calculate(
        previous: RawSystemSnapshot,
        current: RawSystemSnapshot,
    ): SystemUsage {
        if (current.monotonicTimeNs <= previous.monotonicTimeNs) {
            throw CollectionException(
                "Snapshots must advance the monotonic clock, but the previous one reads " +
                    "${previous.monotonicTimeNs} ns and the current one " +
                    "${current.monotonicTimeNs} ns",
            )
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
            processor = calculateProcessorUsage(previous.processor, current.processor),
            loadAverages = current.loadAverages,
            virtualMemory = calculateVirtualMemoryUsage(previous, current, elapsedSeconds),
            storage = calculateStorageUsage(previous, current, elapsedSeconds),
            totalProcessCount = current.totalProcessCount,
            inaccessibleProcessCount = current.inaccessibleProcessCount,
            compressedAttributionProcessCount = current.compressedAttributionProcessCount,
            compressedAttributionFailureCount = current.compressedAttributionFailureCount,
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
        val pageInsPerSecond = delta(
            current.pageIns,
            previous?.pageIns,
        ).toDouble() / elapsedSeconds
        val readBytesPerSecond = delta(
            current.diskBytesRead,
            previous?.diskBytesRead,
        ).toDouble() / elapsedSeconds
        val writeBytesPerSecond = delta(
            current.diskBytesWritten,
            previous?.diskBytesWritten,
        ).toDouble() / elapsedSeconds
        val logicalWriteBytesPerSecond = delta(
            current.logicalWritesBytes,
            previous?.logicalWritesBytes,
        ).toDouble() / elapsedSeconds
        val instructionsPerSecond = delta(
            current.instructions,
            previous?.instructions,
        ).toDouble() / elapsedSeconds
        val cyclesPerSecond = delta(
            current.cycles,
            previous?.cycles,
        ).toDouble() / elapsedSeconds
        val energyWatts = delta(
            current.energyNanojoules,
            previous?.energyNanojoules,
        ).toDouble() / elapsedSeconds / NANOJOULES_PER_JOULE
        val faultsPerSecond = wrappingUInt32Delta(
            current.faults,
            previous?.faults,
        ).toDouble() / elapsedSeconds
        val copyOnWriteFaultsPerSecond = wrappingUInt32Delta(
            current.copyOnWriteFaults,
            previous?.copyOnWriteFaults,
        ).toDouble() / elapsedSeconds
        val systemCallsPerSecond = (
            wrappingUInt32Delta(current.machSystemCalls, previous?.machSystemCalls) +
                wrappingUInt32Delta(current.unixSystemCalls, previous?.unixSystemCalls)
            ).toDouble() / elapsedSeconds
        val contextSwitchesPerSecond = wrappingUInt32Delta(
            current.contextSwitches,
            previous?.contextSwitches,
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
            wiredBytes = current.wiredBytes,
            lifetimeMaxPhysicalFootprintBytes = current.lifetimeMaxPhysicalFootprintBytes,
            compressedOrPagedOutBytes = current.compressedOrPagedOutBytes,
            virtualMemoryRegionCount = current.virtualMemoryRegionCount,
            wakeupsPerSecond = wakeups.finiteNonNegative(),
            pageInsPerSecond = pageInsPerSecond.finiteNonNegative(),
            diskReadBytesPerSecond = readBytesPerSecond.finiteNonNegative(),
            diskWriteBytesPerSecond = writeBytesPerSecond.finiteNonNegative(),
            logicalWriteBytesPerSecond = logicalWriteBytesPerSecond.finiteNonNegative(),
            instructionsPerSecond = instructionsPerSecond.finiteNonNegative(),
            cyclesPerSecond = cyclesPerSecond.finiteNonNegative(),
            energyWatts = energyWatts.finiteNonNegative(),
            faultsPerSecond = faultsPerSecond.finiteNonNegative(),
            copyOnWriteFaultsPerSecond = copyOnWriteFaultsPerSecond.finiteNonNegative(),
            systemCallsPerSecond = systemCallsPerSecond.finiteNonNegative(),
            contextSwitchesPerSecond = contextSwitchesPerSecond.finiteNonNegative(),
            threadCount = current.threadCount,
            runningThreadCount = current.runningThreadCount,
            billedEnergyPerSecond = billedEnergyPerSecond.finiteNonNegative(),
            batteryImpactScore = impactScore.finiteNonNegative(),
        )
    }

    private fun calculateProcessorUsage(
        previous: ProcessorCounters,
        current: ProcessorCounters,
    ): ProcessorUsage {
        val user = wrappingTickDelta(current.userTicks, previous.userTicks)
        val system = wrappingTickDelta(current.systemTicks, previous.systemTicks)
        val idle = wrappingTickDelta(current.idleTicks, previous.idleTicks)
        val nice = wrappingTickDelta(current.niceTicks, previous.niceTicks)
        val total = user + system + idle + nice

        fun percent(value: ULong): Double =
            if (total == 0uL) 0.0 else (value.toDouble() / total.toDouble()) * 100.0

        return ProcessorUsage(
            totalPercent = percent(user + system + nice).finiteNonNegative(),
            userPercent = percent(user).finiteNonNegative(),
            systemPercent = percent(system).finiteNonNegative(),
            nicePercent = percent(nice).finiteNonNegative(),
            idlePercent = percent(idle).finiteNonNegative(),
        )
    }

    private fun calculateVirtualMemoryUsage(
        previous: RawSystemSnapshot,
        current: RawSystemSnapshot,
        elapsedSeconds: Double,
    ): VirtualMemoryUsage {
        val memory = current.virtualMemory
        val pageSize = memory.pageSizeBytes.toDouble()

        fun bytesPerSecond(currentValue: ULong, previousValue: ULong): Double =
            (delta(currentValue, previousValue).toDouble() * pageSize / elapsedSeconds)
                .finiteNonNegative()

        return VirtualMemoryUsage(
            freeBytes = memory.freeBytes,
            activeBytes = memory.activeBytes,
            inactiveBytes = memory.inactiveBytes,
            wiredBytes = memory.wiredBytes,
            purgeableBytes = memory.purgeableBytes,
            compressedBytes = memory.compressedBytes,
            uncompressedBytesInCompressor = memory.uncompressedBytesInCompressor,
            swapBackedUncompressedBytes = memory.swapBackedUncompressedBytes,
            pageInBytesPerSecond = bytesPerSecond(
                memory.pageIns,
                previous.virtualMemory.pageIns,
            ),
            pageOutBytesPerSecond = bytesPerSecond(
                memory.pageOuts,
                previous.virtualMemory.pageOuts,
            ),
            faultRate = (
                delta(memory.faults, previous.virtualMemory.faults).toDouble() /
                    elapsedSeconds
                ).finiteNonNegative(),
            copyOnWriteFaultRate = (
                delta(
                    memory.copyOnWriteFaults,
                    previous.virtualMemory.copyOnWriteFaults,
                ).toDouble() / elapsedSeconds
                ).finiteNonNegative(),
            compressionBytesPerSecond = bytesPerSecond(
                memory.compressions,
                previous.virtualMemory.compressions,
            ),
            decompressionBytesPerSecond = bytesPerSecond(
                memory.decompressions,
                previous.virtualMemory.decompressions,
            ),
            swapInBytesPerSecond = bytesPerSecond(
                memory.swapIns,
                previous.virtualMemory.swapIns,
            ),
            swapOutBytesPerSecond = bytesPerSecond(
                memory.swapOuts,
                previous.virtualMemory.swapOuts,
            ),
        )
    }

    private fun calculateStorageUsage(
        previous: RawSystemSnapshot,
        current: RawSystemSnapshot,
        elapsedSeconds: Double,
    ): StorageUsage {
        val storage = current.storage
        val previousStorage = previous.storage
        val comparableCounters =
            storage.available &&
                previousStorage.available &&
                storage.deviceCount > 0 &&
                storage.deviceCount == previousStorage.deviceCount
        fun rate(currentValue: ULong, previousValue: ULong): Double =
            if (comparableCounters) {
                (delta(currentValue, previousValue).toDouble() / elapsedSeconds)
                    .finiteNonNegative()
            } else {
                0.0
            }

        return StorageUsage(
            available = comparableCounters,
            deviceCount = storage.deviceCount,
            readBytesPerSecond = rate(storage.bytesRead, previousStorage.bytesRead),
            writeBytesPerSecond = rate(storage.bytesWritten, previousStorage.bytesWritten),
            readOperationsPerSecond = rate(
                storage.readOperations,
                previousStorage.readOperations,
            ),
            writeOperationsPerSecond = rate(
                storage.writeOperations,
                previousStorage.writeOperations,
            ),
            readServiceTimePercent = (
                rate(storage.readTimeNs, previousStorage.readTimeNs) /
                    NANOSECONDS_PER_SECOND * 100.0
                ).finiteNonNegative(),
            writeServiceTimePercent = (
                rate(storage.writeTimeNs, previousStorage.writeTimeNs) /
                    NANOSECONDS_PER_SECOND * 100.0
                ).finiteNonNegative(),
            rootFileSystemTotalBytes = storage.rootFileSystemTotalBytes,
            rootFileSystemAvailableBytes = storage.rootFileSystemAvailableBytes,
        )
    }

    private fun delta(current: ULong, previous: ULong?): ULong =
        if (previous != null && current >= previous) current - previous else 0u

    private fun wrappingTickDelta(current: ULong, previous: ULong): ULong = when {
        current >= previous -> current - previous
        previous <= UInt.MAX_VALUE.toULong() && current <= UInt.MAX_VALUE.toULong() ->
            UInt.MAX_VALUE.toULong() - previous + 1u + current
        else -> 0u
    }

    private fun wrappingUInt32Delta(current: ULong, previous: ULong?): ULong = when {
        previous == null -> 0u
        current >= previous -> current - previous
        previous <= UInt.MAX_VALUE.toULong() && current <= UInt.MAX_VALUE.toULong() ->
            UInt.MAX_VALUE.toULong() - previous + 1u + current
        else -> 0u
    }

    private fun Double.finiteNonNegative(): Double =
        takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    private companion object {
        const val NANOSECONDS_PER_SECOND = 1_000_000_000.0
        const val NANOJOULES_PER_JOULE = 1_000_000_000.0
        const val BYTES_PER_MEBIBYTE = 1_048_576.0

        // This is deliberately a transparent heuristic, not Activity Monitor's
        // private "Energy Impact" metric.
        const val WAKEUP_SCORE_WEIGHT = 0.25
        const val IO_SCORE_WEIGHT = 2.0
    }
}
