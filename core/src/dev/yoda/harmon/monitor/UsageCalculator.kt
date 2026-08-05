package dev.yoda.harmon.monitor

import dev.yoda.harmon.analysis.ApplicationGrouper
import dev.yoda.harmon.config.DEFAULT_TERMINAL_APPLICATIONS
import dev.yoda.harmon.model.ProcessorCounters
import dev.yoda.harmon.model.ProcessorUsage
import dev.yoda.harmon.model.ProcessUsage
import dev.yoda.harmon.model.RawProcessSample
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.ReparentedFrom
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
        val previousNameByPid = buildNamesByPid(previous)

        val processes = current.processes.map { currentProcess ->
            val previousProcess = previousByIdentity[currentProcess.identity]
            calculateProcessUsage(
                previousProcess,
                currentProcess,
                elapsedSeconds,
                detectReparenting(previousProcess, currentProcess, previousNameByPid),
            )
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

    /**
     * Every pid the previous snapshot could put a name to, built once per call rather than
     * per process: the only place a dead parent can still be named is that snapshot, and
     * every process in the loop resolves against the same map.
     *
     * `processIssues` is read as well as `processes`, and it is read first so a full sample
     * wins over an issue for the same pid. A process the collector could not measure still
     * arrives with its pid and, usually, its name, and it is exactly the kind of process a
     * parent tends to be — a supervisor whose `proc_pid_rusage` was refused would otherwise
     * make the alert about it name a bare pid, in the case where naming it matters most.
     */
    private fun buildNamesByPid(previous: RawSystemSnapshot): Map<Int, String> = buildMap {
        for (issue in previous.processIssues) {
            issue.name?.let { put(issue.pid, it) }
        }
        for (process in previous.processes) {
            put(process.identity.pid, process.name)
        }
    }

    /**
     * Reports the parent a process had one sample ago when this sample shows a different
     * one, and null otherwise.
     *
     * A process absent from [previous] yields null rather than a transition. That is what
     * keeps a deliberate double fork quiet: such a process is first seen already detached,
     * so there is no earlier parent to compare against. It also settles pid reuse, because
     * the lookup key is the whole identity — a different process wearing a recycled pid has
     * a different `startedAt` and simply misses.
     *
     * A parent pid that is not positive on either side is not a pid at all. The collector
     * pre-sets the field to 0 and leaves it there whenever `proc_pidinfo(PROC_PIDTBSDINFO)`
     * does not return a whole struct, while still emitting the sample — the sample is decided
     * by `proc_pid_rusage` alone. So a process whose metadata read failed in one sample and
     * succeeded in the next would otherwise read as a `0 -> 1` transition and be reported as
     * having lost a parent that never existed. Zero is the only such value the collector
     * produces; the guard is written for every non-positive one because that is the reading
     * `DarwinSystemCollector` already takes on the issue path, where `takeIf { it > 0 }` maps
     * anything else to a null parent, and a guard narrower than that one would disagree with
     * it the day a kernel answered with something stranger.
     *
     * The result says the parent changed, nothing more. Whether the new parent being pid 1
     * makes this an orphan worth reporting is left to the consumer, so this calculator stays
     * a pure function of the two snapshots.
     */
    private fun detectReparenting(
        previous: RawProcessSample?,
        current: RawProcessSample,
        previousNameByPid: Map<Int, String>,
    ): ReparentedFrom? {
        if (previous == null || previous.parentPid <= 0 || current.parentPid <= 0) {
            return null
        }
        if (previous.parentPid == current.parentPid) {
            return null
        }
        return ReparentedFrom(previous.parentPid, previousNameByPid[previous.parentPid])
    }

    private fun calculateProcessUsage(
        previous: RawProcessSample?,
        current: RawProcessSample,
        elapsedSeconds: Double,
        reparentedFrom: ReparentedFrom?,
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
            reparentedFrom = reparentedFrom,
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
