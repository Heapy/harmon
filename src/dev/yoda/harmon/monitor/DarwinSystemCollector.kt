package dev.yoda.harmon.monitor

import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessorCounters
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.ProcessIdentity
import dev.yoda.harmon.model.RawProcessSample
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.LoadAverages
import dev.yoda.harmon.model.StorageCounters
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.model.VirtualMemoryCounters
import dev.yoda.harmon.nativebridge.HMBatterySample
import dev.yoda.harmon.nativebridge.HMLoadAverageSample
import dev.yoda.harmon.nativebridge.HMProcessorSample
import dev.yoda.harmon.nativebridge.HMProcessIssue
import dev.yoda.harmon.nativebridge.HMProcessSample
import dev.yoda.harmon.nativebridge.HM_PROCESS_ISSUE_CAPACITY
import dev.yoda.harmon.nativebridge.HMStorageSample
import dev.yoda.harmon.nativebridge.HMSwapSample
import dev.yoda.harmon.nativebridge.HMVirtualMemorySample
import dev.yoda.harmon.nativebridge.hm_count_processes
import dev.yoda.harmon.nativebridge.hm_list_processes
import dev.yoda.harmon.nativebridge.hm_monotonic_time_ns
import dev.yoda.harmon.nativebridge.hm_read_battery
import dev.yoda.harmon.nativebridge.hm_read_load_averages
import dev.yoda.harmon.nativebridge.hm_read_physical_memory
import dev.yoda.harmon.nativebridge.hm_read_processor
import dev.yoda.harmon.nativebridge.hm_read_storage
import dev.yoda.harmon.nativebridge.hm_read_swap
import dev.yoda.harmon.nativebridge.hm_read_virtual_memory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.EACCES
import platform.posix.EPERM
import platform.posix.ESRCH
import kotlin.time.Clock

const val MIN_PROCESS_CAPACITY = 512
const val PROCESS_CAPACITY_HEADROOM = 256

/**
 * Number of slots to reserve for [count] processes, never more than [capacity]. Both per-process
 * arrays of a collection are sized with it — the samples and the collection issues — each
 * against its own [capacity].
 *
 * [PROCESS_CAPACITY_HEADROOM] covers the processes that start between the kernel's PID count and
 * the listing call, and [MIN_PROCESS_CAPACITY] keeps a small machine from tracking its PID count
 * so tightly that an ordinary burst of short-lived processes exhausts it. A non-positive [count]
 * means the kernel refused to answer, so the full [capacity] is reserved as before.
 *
 * `hm_list_processes` sizes its own intermediate PID list from the larger of a fresh count and
 * the capacity returned here, so that list is never the narrower of the two: processes beyond
 * what these arrays hold are reported as capacity issues rather than vanishing from a truncated
 * listing. The invariant holds structurally, so the headroom above needs no counterpart in C.
 */
fun processCapacityFor(count: Int, capacity: Int): Int {
    if (count <= 0) {
        return capacity
    }
    val requested = maxOf(count.toLong() + PROCESS_CAPACITY_HEADROOM, MIN_PROCESS_CAPACITY.toLong())
    return minOf(requested, capacity.toLong()).toInt()
}

interface SystemCollector {
    fun capture(): RawSystemSnapshot
}

class CollectionException(message: String) : IllegalStateException(message)

class DarwinSystemCollector(
    private val processCapacity: Int = DEFAULT_PROCESS_CAPACITY,
    private val issueCapacity: Int = DEFAULT_ISSUE_CAPACITY,
    private val compressedAttributionProcessLimit: Int =
        DEFAULT_COMPRESSED_ATTRIBUTION_PROCESS_LIMIT,
    private val attributionRegionBudget: Int = DEFAULT_ATTRIBUTION_REGION_BUDGET,
) : SystemCollector {
    init {
        require(processCapacity > 0) { "processCapacity must be positive" }
        require(issueCapacity > 0) { "issueCapacity must be positive" }
        require(compressedAttributionProcessLimit >= 0) {
            "compressedAttributionProcessLimit must not be negative"
        }
        require(attributionRegionBudget >= 0) {
            "attributionRegionBudget must not be negative"
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun capture(): RawSystemSnapshot = memScoped {
        val pidCount = hm_count_processes()
        val sampleSlots = processCapacityFor(pidCount, processCapacity)
        val issueSlots = processCapacityFor(pidCount, issueCapacity)
        val nativeProcesses = allocArray<HMProcessSample>(sampleSlots)
        val nativeIssues = allocArray<HMProcessIssue>(issueSlots)
        val totalProcesses = alloc<IntVar>()
        val inaccessibleProcesses = alloc<IntVar>()
        val writtenIssues = alloc<IntVar>()
        val processCount = hm_list_processes(
            nativeProcesses,
            sampleSlots,
            nativeIssues,
            issueSlots,
            compressedAttributionProcessLimit,
            attributionRegionBudget,
            totalProcesses.ptr,
            inaccessibleProcesses.ptr,
            writtenIssues.ptr,
        )
        if (processCount < 0) {
            throw CollectionException("Unable to enumerate macOS processes")
        }

        val swapSample = alloc<HMSwapSample>()
        if (hm_read_swap(swapSample.ptr) != 0) {
            throw CollectionException("Unable to read vm.swapusage")
        }

        val physicalMemory = alloc<ULongVar>()
        if (hm_read_physical_memory(physicalMemory.ptr) != 0) {
            throw CollectionException("Unable to read hw.memsize")
        }

        val batterySample = alloc<HMBatterySample>()
        val batteryRead = hm_read_battery(batterySample.ptr) == 0

        val processorSample = alloc<HMProcessorSample>()
        if (hm_read_processor(processorSample.ptr) != 0) {
            throw CollectionException("Unable to read host CPU counters")
        }

        val loadAverageSample = alloc<HMLoadAverageSample>()
        if (hm_read_load_averages(loadAverageSample.ptr) != 0) {
            throw CollectionException("Unable to read system load averages")
        }

        val virtualMemorySample = alloc<HMVirtualMemorySample>()
        if (hm_read_virtual_memory(virtualMemorySample.ptr) != 0) {
            throw CollectionException("Unable to read HOST_VM_INFO64")
        }

        val storageSample = alloc<HMStorageSample>()
        val storageRead = hm_read_storage(storageSample.ptr) == 0

        val processes = buildList(processCount) {
            for (index in 0..<processCount) {
                val sample = nativeProcesses[index]
                add(
                    RawProcessSample(
                        identity = ProcessIdentity(
                            pid = sample.pid,
                            startedAt = sample.started_at,
                        ),
                        parentPid = sample.parent_pid,
                        uid = sample.uid.toNullableUid(),
                        name = sample.name.toKString(),
                        executablePath = sample.executable_path.toOptionalString(),
                        userTimeNs = sample.user_time_ns,
                        systemTimeNs = sample.system_time_ns,
                        packageIdleWakeups = sample.package_idle_wakeups,
                        interruptWakeups = sample.interrupt_wakeups,
                        pageIns = sample.pageins,
                        diskBytesRead = sample.disk_bytes_read,
                        diskBytesWritten = sample.disk_bytes_written,
                        logicalWritesBytes = sample.logical_writes_bytes,
                        instructions = sample.instructions,
                        cycles = sample.cycles,
                        energyNanojoules = sample.energy_nanojoules,
                        wiredBytes = sample.wired_bytes,
                        residentBytes = sample.resident_bytes,
                        physicalFootprintBytes = sample.physical_footprint_bytes,
                        lifetimeMaxPhysicalFootprintBytes =
                            sample.lifetime_max_physical_footprint_bytes,
                        compressedOrPagedOutBytes =
                            sample.compressed_or_paged_out_bytes.takeIf {
                                sample.compressed_attribution_available != 0
                            },
                        virtualMemoryRegionCount =
                            sample.virtual_memory_region_count.takeIf {
                                sample.compressed_attribution_available != 0
                            },
                        faults = sample.faults,
                        copyOnWriteFaults = sample.copy_on_write_faults,
                        machSystemCalls = sample.mach_system_calls,
                        unixSystemCalls = sample.unix_system_calls,
                        contextSwitches = sample.context_switches,
                        threadCount = sample.thread_count,
                        runningThreadCount = sample.running_thread_count,
                        billedEnergy = sample.billed_energy,
                    ),
                )
            }
        }
        val processIssues = buildList(writtenIssues.value) {
            for (index in 0..<writtenIssues.value) {
                val issue = nativeIssues[index]
                val executablePath = issue.executable_path.toOptionalString()
                add(
                    ProcessCollectionIssue(
                        pid = issue.pid,
                        parentPid = issue.parent_pid.takeIf { it > 0 },
                        uid = issue.uid.toNullableUid(),
                        name = issue.name.toOptionalString()
                            ?: executablePath?.substringAfterLast('/'),
                        executablePath = executablePath,
                        reason = issue.toReason(),
                        errorCode = issue.error_code.takeIf { it != 0 },
                    ),
                )
            }
        }

        RawSystemSnapshot(
            capturedAt = Clock.System.now(),
            monotonicTimeNs = hm_monotonic_time_ns(),
            physicalMemoryBytes = physicalMemory.value,
            swap = SwapUsage(
                totalBytes = swapSample.total_bytes,
                availableBytes = swapSample.available_bytes,
                usedBytes = swapSample.used_bytes,
                encrypted = swapSample.encrypted != 0,
            ),
            power = if (batteryRead) {
                PowerState(
                    batteryAvailable = batterySample.available != 0,
                    onBattery = batterySample.on_battery != 0,
                    charging = batterySample.charging != 0,
                    percentage = batterySample.percentage.takeIf { it >= 0 },
                    minutesRemaining = batterySample.minutes_remaining.takeIf { it >= 0 },
                )
            } else {
                PowerState(
                    batteryAvailable = false,
                    onBattery = false,
                    charging = false,
                    percentage = null,
                    minutesRemaining = null,
                )
            },
            processor = ProcessorCounters(
                userTicks = processorSample.user_ticks,
                systemTicks = processorSample.system_ticks,
                idleTicks = processorSample.idle_ticks,
                niceTicks = processorSample.nice_ticks,
            ),
            loadAverages = LoadAverages(
                oneMinute = loadAverageSample.one_minute,
                fiveMinutes = loadAverageSample.five_minutes,
                fifteenMinutes = loadAverageSample.fifteen_minutes,
            ),
            virtualMemory = VirtualMemoryCounters(
                pageSizeBytes = virtualMemorySample.page_size_bytes,
                freeBytes = virtualMemorySample.free_bytes,
                activeBytes = virtualMemorySample.active_bytes,
                inactiveBytes = virtualMemorySample.inactive_bytes,
                wiredBytes = virtualMemorySample.wired_bytes,
                purgeableBytes = virtualMemorySample.purgeable_bytes,
                compressedBytes = virtualMemorySample.compressed_bytes,
                uncompressedBytesInCompressor =
                    virtualMemorySample.uncompressed_bytes_in_compressor,
                swapBackedUncompressedBytes =
                    virtualMemorySample.swap_backed_uncompressed_bytes,
                pageIns = virtualMemorySample.pageins,
                pageOuts = virtualMemorySample.pageouts,
                faults = virtualMemorySample.faults,
                copyOnWriteFaults = virtualMemorySample.copy_on_write_faults,
                compressions = virtualMemorySample.compressions,
                decompressions = virtualMemorySample.decompressions,
                swapIns = virtualMemorySample.swapins,
                swapOuts = virtualMemorySample.swapouts,
            ),
            storage = if (storageRead) {
                StorageCounters(
                    available = storageSample.available != 0,
                    deviceCount = storageSample.device_count,
                    bytesRead = storageSample.bytes_read,
                    bytesWritten = storageSample.bytes_written,
                    readOperations = storageSample.read_operations,
                    writeOperations = storageSample.write_operations,
                    readTimeNs = storageSample.read_time_ns,
                    writeTimeNs = storageSample.write_time_ns,
                    rootFileSystemTotalBytes =
                        storageSample.root_filesystem_total_bytes,
                    rootFileSystemAvailableBytes =
                        storageSample.root_filesystem_available_bytes,
                )
            } else {
                StorageCounters(
                    available = false,
                    deviceCount = 0,
                    bytesRead = 0u,
                    bytesWritten = 0u,
                    readOperations = 0u,
                    writeOperations = 0u,
                    readTimeNs = 0u,
                    writeTimeNs = 0u,
                    rootFileSystemTotalBytes = 0u,
                    rootFileSystemAvailableBytes = 0u,
                )
            },
            totalProcessCount = totalProcesses.value,
            inaccessibleProcessCount = inaccessibleProcesses.value +
                (totalProcesses.value - processCount - inaccessibleProcesses.value).coerceAtLeast(0),
            compressedAttributionProcessCount = processes.count {
                it.compressedOrPagedOutBytes != null
            },
            compressedAttributionFailureCount = (0..<processCount).count { index ->
                val sample = nativeProcesses[index]
                sample.compressed_attribution_attempted != 0 &&
                    sample.compressed_attribution_available == 0
            },
            processes = processes,
            processIssues = processIssues,
        )
    }

    private fun UInt.toNullableUid(): UInt? =
        takeUnless { it == UInt.MAX_VALUE }

    @OptIn(ExperimentalForeignApi::class)
    private fun CArrayPointer<ByteVar>.toOptionalString(): String? =
        toKString().takeIf { it.isNotEmpty() }

    @OptIn(ExperimentalForeignApi::class)
    private fun HMProcessIssue.toReason(): ProcessCollectionIssueReason = when {
        reason == HM_PROCESS_ISSUE_CAPACITY ->
            ProcessCollectionIssueReason.CAPACITY_LIMIT
        error_code == EACCES || error_code == EPERM ->
            ProcessCollectionIssueReason.PERMISSION_DENIED
        error_code == ESRCH ->
            ProcessCollectionIssueReason.EXITED_DURING_COLLECTION
        else ->
            ProcessCollectionIssueReason.RESOURCE_USAGE_UNAVAILABLE
    }

    private companion object {
        const val DEFAULT_PROCESS_CAPACITY = 16_384
        const val DEFAULT_ISSUE_CAPACITY = 4_096
        const val DEFAULT_COMPRESSED_ATTRIBUTION_PROCESS_LIMIT = 256
        const val DEFAULT_ATTRIBUTION_REGION_BUDGET = 100_000
    }
}
