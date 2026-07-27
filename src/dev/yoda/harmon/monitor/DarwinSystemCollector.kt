package dev.yoda.harmon.monitor

import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.ProcessIdentity
import dev.yoda.harmon.model.RawProcessSample
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.nativebridge.HMBatterySample
import dev.yoda.harmon.nativebridge.HMProcessIssue
import dev.yoda.harmon.nativebridge.HMProcessSample
import dev.yoda.harmon.nativebridge.HM_PROCESS_ISSUE_CAPACITY
import dev.yoda.harmon.nativebridge.HMSwapSample
import dev.yoda.harmon.nativebridge.hm_list_processes
import dev.yoda.harmon.nativebridge.hm_monotonic_time_ns
import dev.yoda.harmon.nativebridge.hm_read_battery
import dev.yoda.harmon.nativebridge.hm_read_physical_memory
import dev.yoda.harmon.nativebridge.hm_read_swap
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

interface SystemCollector {
    fun capture(): RawSystemSnapshot
}

class CollectionException(message: String) : IllegalStateException(message)

class DarwinSystemCollector(
    private val processCapacity: Int = DEFAULT_PROCESS_CAPACITY,
    private val issueCapacity: Int = DEFAULT_ISSUE_CAPACITY,
) : SystemCollector {
    init {
        require(processCapacity > 0) { "processCapacity must be positive" }
        require(issueCapacity > 0) { "issueCapacity must be positive" }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun capture(): RawSystemSnapshot = memScoped {
        val nativeProcesses = allocArray<HMProcessSample>(processCapacity)
        val nativeIssues = allocArray<HMProcessIssue>(issueCapacity)
        val totalProcesses = alloc<IntVar>()
        val inaccessibleProcesses = alloc<IntVar>()
        val writtenIssues = alloc<IntVar>()
        val processCount = hm_list_processes(
            nativeProcesses,
            processCapacity,
            nativeIssues,
            issueCapacity,
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
                        diskBytesRead = sample.disk_bytes_read,
                        diskBytesWritten = sample.disk_bytes_written,
                        residentBytes = sample.resident_bytes,
                        physicalFootprintBytes = sample.physical_footprint_bytes,
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
            totalProcessCount = totalProcesses.value,
            inaccessibleProcessCount = inaccessibleProcesses.value +
                (totalProcesses.value - processCount - inaccessibleProcesses.value).coerceAtLeast(0),
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
    }
}
