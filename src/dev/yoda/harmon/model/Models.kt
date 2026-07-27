package dev.yoda.harmon.model

import kotlin.time.Instant

data class ProcessIdentity(
    val pid: Int,
    val startedAt: ULong,
)

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
    val diskBytesRead: ULong,
    val diskBytesWritten: ULong,
    val residentBytes: ULong,
    val physicalFootprintBytes: ULong,
    val billedEnergy: ULong,
)

enum class ProcessCollectionIssueReason {
    PERMISSION_DENIED,
    EXITED_DURING_COLLECTION,
    RESOURCE_USAGE_UNAVAILABLE,
    CAPACITY_LIMIT,
}

data class ProcessCollectionIssue(
    val pid: Int,
    val parentPid: Int?,
    val uid: UInt?,
    val name: String?,
    val executablePath: String?,
    val reason: ProcessCollectionIssueReason,
    val errorCode: Int?,
)

data class SwapUsage(
    val totalBytes: ULong,
    val availableBytes: ULong,
    val usedBytes: ULong,
    val encrypted: Boolean,
)

data class PowerState(
    val batteryAvailable: Boolean,
    val onBattery: Boolean,
    val charging: Boolean,
    val percentage: Int?,
    val minutesRemaining: Int?,
)

data class RawSystemSnapshot(
    val capturedAt: Instant,
    val monotonicTimeNs: ULong,
    val physicalMemoryBytes: ULong,
    val swap: SwapUsage,
    val power: PowerState,
    val totalProcessCount: Int,
    val inaccessibleProcessCount: Int,
    val processes: List<RawProcessSample>,
    val processIssues: List<ProcessCollectionIssue>,
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
    val wakeupsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
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
    val wakeupsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
    val billedEnergyPerSecond: Double,
    val batteryImpactScore: Double,
) {
    val processCount: Int
        get() = processIds.size
}

data class SystemUsage(
    val capturedAt: Instant,
    val elapsedSeconds: Double,
    val physicalMemoryBytes: ULong,
    val swap: SwapUsage,
    val power: PowerState,
    val totalProcessCount: Int,
    val inaccessibleProcessCount: Int,
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

data class MonitoringReport(
    val usage: SystemUsage,
    val alerts: List<Alert>,
    val topProcessCount: Int,
)

data class NotificationPayload(
    val title: String,
    val subtitle: String,
    val text: String,
    val json: String,
)

data class DeliveryResult(
    val channel: String,
    val successful: Boolean,
    val detail: String,
)
