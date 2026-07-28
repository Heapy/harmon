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
)

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
 * [suppressedAlertKeys] names the keys that are still firing but did not fit. Without it a
 * consumer diffing the alert list could not tell a demoted alert from a cleared one, and would
 * never see it again: the alert state still holds it as firing, so it is never pushed as new.
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
