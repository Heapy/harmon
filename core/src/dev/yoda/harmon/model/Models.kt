package dev.yoda.harmon.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

@Serializable
data class ProcessIdentity(
    val pid: Int,
    val startedAt: ULong,
)

@Serializable
data class RawProcessSample(
    val identity: ProcessIdentity,
    val parentPid: Int,
    val uid: UInt?,
    val name: String,
    val executablePath: String?,
    val userTimeNs: ULong,
    val systemTimeNs: ULong,
    val packageIdleWakeups: ULong,
    val interruptWakeups: ULong,
    val pageIns: ULong,
    val diskBytesRead: ULong,
    val diskBytesWritten: ULong,
    val logicalWritesBytes: ULong,
    val instructions: ULong,
    val cycles: ULong,
    val energyNanojoules: ULong,
    val wiredBytes: ULong,
    val residentBytes: ULong,
    val physicalFootprintBytes: ULong,
    val lifetimeMaxPhysicalFootprintBytes: ULong,
    val compressedOrPagedOutBytes: ULong?,
    val virtualMemoryRegionCount: Int?,
    val faults: ULong,
    val copyOnWriteFaults: ULong,
    val machSystemCalls: ULong,
    val unixSystemCalls: ULong,
    val contextSwitches: ULong,
    val threadCount: Int,
    val runningThreadCount: Int,
    val billedEnergy: ULong,
)

@Serializable
enum class ProcessCollectionIssueReason {
    PERMISSION_DENIED,
    EXITED_DURING_COLLECTION,
    RESOURCE_USAGE_UNAVAILABLE,
    CAPACITY_LIMIT,
}

@Serializable
data class ProcessCollectionIssue(
    val pid: Int,
    val parentPid: Int?,
    val uid: UInt?,
    val name: String?,
    val executablePath: String?,
    val reason: ProcessCollectionIssueReason,
    val errorCode: Int?,
)

@Serializable
data class SwapUsage(
    val totalBytes: ULong,
    val availableBytes: ULong,
    val usedBytes: ULong,
    val encrypted: Boolean,
)

@Serializable
data class PowerState(
    val batteryAvailable: Boolean,
    val onBattery: Boolean,
    val charging: Boolean,
    val percentage: Int?,
    val minutesRemaining: Int?,
)

@Serializable
data class ProcessorCounters(
    val userTicks: ULong,
    val systemTicks: ULong,
    val idleTicks: ULong,
    val niceTicks: ULong,
)

@Serializable
data class LoadAverages(
    val oneMinute: Double,
    val fiveMinutes: Double,
    val fifteenMinutes: Double,
)

@Serializable
data class VirtualMemoryCounters(
    val pageSizeBytes: ULong,
    val freeBytes: ULong,
    val activeBytes: ULong,
    val inactiveBytes: ULong,
    val wiredBytes: ULong,
    val purgeableBytes: ULong,
    val compressedBytes: ULong,
    val uncompressedBytesInCompressor: ULong,
    val swapBackedUncompressedBytes: ULong,
    val pageIns: ULong,
    val pageOuts: ULong,
    val faults: ULong,
    val copyOnWriteFaults: ULong,
    val compressions: ULong,
    val decompressions: ULong,
    val swapIns: ULong,
    val swapOuts: ULong,
)

@Serializable
data class StorageCounters(
    val available: Boolean,
    val deviceCount: Int,
    val bytesRead: ULong,
    val bytesWritten: ULong,
    val readOperations: ULong,
    val writeOperations: ULong,
    val readTimeNs: ULong,
    val writeTimeNs: ULong,
    val rootFileSystemTotalBytes: ULong,
    val rootFileSystemAvailableBytes: ULong,
)

@Serializable
data class RawSystemSnapshot(
    @Serializable(with = InstantAsStringSerializer::class)
    val capturedAt: Instant,
    val monotonicTimeNs: ULong,
    val physicalMemoryBytes: ULong,
    val swap: SwapUsage,
    val power: PowerState,
    val processor: ProcessorCounters,
    val loadAverages: LoadAverages,
    val virtualMemory: VirtualMemoryCounters,
    val storage: StorageCounters,
    val totalProcessCount: Int,
    val inaccessibleProcessCount: Int,
    val compressedAttributionProcessCount: Int,
    val compressedAttributionFailureCount: Int,
    val processes: List<RawProcessSample>,
    val processIssues: List<ProcessCollectionIssue>,
)

object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "dev.yoda.harmon.model.InstantAsString",
            PrimitiveKind.STRING,
        )

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}

/**
 * launchd, the parent every process whose own parent died is handed to on Darwin.
 *
 * Named once and shared rather than repeated, because it is a fact about the platform and not a
 * policy either consumer of [ReparentedFrom] gets to hold an opinion about. What each of them
 * still decides for itself is whether to act on a transition to it at all.
 */
const val INIT_PID = 1

/**
 * The parent a process had before it changed, together with that parent's name when it is
 * known.
 *
 * The name is resolved from the previous snapshot rather than the current one: by the time
 * the change is visible the old parent is usually gone, so the current snapshot can no
 * longer name it. A parent missing from the previous snapshot too leaves [name] null while
 * [pid] stays populated.
 */
data class ReparentedFrom(
    val pid: Int,
    val name: String?,
)

data class ProcessUsage(
    val identity: ProcessIdentity,
    val parentPid: Int,
    val uid: UInt?,
    val name: String,
    val executablePath: String?,
    val cpuPercent: Double,
    val userCpuPercent: Double,
    val systemCpuPercent: Double,
    val physicalFootprintBytes: ULong,
    val residentBytes: ULong,
    val wiredBytes: ULong,
    val lifetimeMaxPhysicalFootprintBytes: ULong,
    val compressedOrPagedOutBytes: ULong?,
    val virtualMemoryRegionCount: Int?,
    val wakeupsPerSecond: Double,
    val pageInsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
    val logicalWriteBytesPerSecond: Double,
    val instructionsPerSecond: Double,
    val cyclesPerSecond: Double,
    val energyWatts: Double,
    val faultsPerSecond: Double,
    val copyOnWriteFaultsPerSecond: Double,
    val systemCallsPerSecond: Double,
    val contextSwitchesPerSecond: Double,
    val threadCount: Int,
    val runningThreadCount: Int,
    val billedEnergyPerSecond: Double,
    val batteryImpactScore: Double,
    /**
     * Who the parent was in the previous sample when it differs from [parentPid] in this
     * one; null when the parent did not change, or when the process was not in the previous
     * sample at all.
     *
     * The field states "changed its parent", not "was orphaned": whether the new parent
     * being pid 1 makes this an orphan is a question for the consumer, which is why the
     * `parentPid == 1` check lives there and not here. From a single snapshot an orphan is
     * indistinguishable from a process that daemonised on purpose; only the transition
     * between two snapshots tells them apart.
     */
    val reparentedFrom: ReparentedFrom? = null,
)

data class ApplicationUsage(
    val id: String,
    val name: String,
    val bundlePath: String?,
    val rootPid: Int,
    val processIds: List<Int>,
    val cpuPercent: Double,
    val userCpuPercent: Double,
    val systemCpuPercent: Double,
    val physicalFootprintBytes: ULong,
    val residentBytes: ULong,
    val wiredBytes: ULong,
    val lifetimeMaxPhysicalFootprintBytes: ULong,
    val compressedOrPagedOutBytes: ULong,
    val compressedAttributionProcessCount: Int,
    val wakeupsPerSecond: Double,
    val pageInsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
    val logicalWriteBytesPerSecond: Double,
    val instructionsPerSecond: Double,
    val cyclesPerSecond: Double,
    val energyWatts: Double,
    val faultsPerSecond: Double,
    val copyOnWriteFaultsPerSecond: Double,
    val systemCallsPerSecond: Double,
    val contextSwitchesPerSecond: Double,
    val threadCount: Int,
    val runningThreadCount: Int,
    val billedEnergyPerSecond: Double,
    val batteryImpactScore: Double,
) {
    val processCount: Int
        get() = processIds.size
}

data class ProcessorUsage(
    val totalPercent: Double,
    val userPercent: Double,
    val systemPercent: Double,
    val nicePercent: Double,
    val idlePercent: Double,
)

data class VirtualMemoryUsage(
    val freeBytes: ULong,
    val activeBytes: ULong,
    val inactiveBytes: ULong,
    val wiredBytes: ULong,
    val purgeableBytes: ULong,
    val compressedBytes: ULong,
    val uncompressedBytesInCompressor: ULong,
    val swapBackedUncompressedBytes: ULong,
    val pageInBytesPerSecond: Double,
    val pageOutBytesPerSecond: Double,
    val faultRate: Double,
    val copyOnWriteFaultRate: Double,
    val compressionBytesPerSecond: Double,
    val decompressionBytesPerSecond: Double,
    val swapInBytesPerSecond: Double,
    val swapOutBytesPerSecond: Double,
)

data class StorageUsage(
    val available: Boolean,
    val deviceCount: Int,
    val readBytesPerSecond: Double,
    val writeBytesPerSecond: Double,
    val readOperationsPerSecond: Double,
    val writeOperationsPerSecond: Double,
    val readServiceTimePercent: Double,
    val writeServiceTimePercent: Double,
    val rootFileSystemTotalBytes: ULong,
    val rootFileSystemAvailableBytes: ULong,
)

data class SystemUsage(
    val capturedAt: Instant,
    val elapsedSeconds: Double,
    val physicalMemoryBytes: ULong,
    val swap: SwapUsage,
    val power: PowerState,
    val processor: ProcessorUsage,
    val loadAverages: LoadAverages,
    val virtualMemory: VirtualMemoryUsage,
    val storage: StorageUsage,
    val totalProcessCount: Int,
    val inaccessibleProcessCount: Int,
    val compressedAttributionProcessCount: Int,
    val compressedAttributionFailureCount: Int,
    val processes: List<ProcessUsage>,
    val applications: List<ApplicationUsage>,
    val processIssues: List<ProcessCollectionIssue>,
) {
    /**
     * Whether the kernel's own energy counter produced numbers in this sample, inferred from the
     * values because nothing reports it.
     *
     * The bridge zero-initializes `struct rusage_info_v6` and falls back to `RUSAGE_INFO_V4` when
     * `RUSAGE_INFO_V6` is refused with `EINVAL`, which leaves `ri_energy_nj` at zero on a kernel
     * too old to carry it. A dead counter is therefore indistinguishable from a sample whose
     * processes every one slept or first appeared this interval — `UsageCalculator` reports zero
     * for a process the previous snapshot did not carry — and the reading is one way round only:
     * a sample with any process drawing power is a sample the counter is alive in, while a sample
     * without one is only probably a sample it is dead in.
     */
    val energyAccounted: Boolean
        get() = processes.any { it.energyWatts > 0.0 }
}

enum class Severity {
    INFO,
    WARNING,
    CRITICAL,
}

data class Alert(
    val key: String,
    val severity: Severity,
    val title: String,
    val message: String,
)

/**
 * [alerts] is capped at `maxAlertsPerCategory` per rule so a report stays readable, and
 * [suppressedAlertKeys] names every key its rule matched but that did not fit — over its threshold
 * for the rules that have one, orphaned for the one that has none. A dropped alert that was
 * already firing is never pushed again, so this is the only place a consumer sees it at all.
 */
data class MonitoringReport(
    val usage: SystemUsage,
    val alerts: List<Alert>,
    val topProcessCount: Int,
    val suppressedAlertKeys: List<String> = emptyList(),
)

data class NotificationPayload(
    val identifier: String,
    val title: String,
    val subtitle: String,
    val text: String,
    val html: String,
    val json: String,
)

data class DeliveryResult(
    val channel: String,
    val successful: Boolean,
    val detail: String,
)
