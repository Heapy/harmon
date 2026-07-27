import dev.yoda.harmon.analysis.ApplicationGrouper
import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessIdentity
import dev.yoda.harmon.model.ProcessUsage
import dev.yoda.harmon.model.RawProcessSample
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.model.SystemUsage
import kotlin.time.Instant

fun rawProcess(
    pid: Int = 42,
    startedAt: ULong = 100u,
    name: String = "example",
    parentPid: Int = 1,
    executablePath: String? = null,
    userTimeNs: ULong = 0u,
    systemTimeNs: ULong = 0u,
    wakeups: ULong = 0u,
    diskRead: ULong = 0u,
    diskWrite: ULong = 0u,
    footprint: ULong = 512uL * 1_048_576uL,
    billedEnergy: ULong = 0u,
): RawProcessSample = RawProcessSample(
    identity = ProcessIdentity(pid, startedAt),
    parentPid = parentPid,
    uid = 501u,
    name = name,
    executablePath = executablePath,
    userTimeNs = userTimeNs,
    systemTimeNs = systemTimeNs,
    packageIdleWakeups = wakeups,
    interruptWakeups = 0u,
    diskBytesRead = diskRead,
    diskBytesWritten = diskWrite,
    residentBytes = footprint,
    physicalFootprintBytes = footprint,
    billedEnergy = billedEnergy,
)

fun rawSnapshot(
    monotonicNs: ULong,
    processes: List<RawProcessSample>,
    swapUsed: ULong = 0u,
): RawSystemSnapshot = RawSystemSnapshot(
    capturedAt = Instant.fromEpochSeconds(monotonicNs.toLong() / 1_000_000_000),
    monotonicTimeNs = monotonicNs,
    physicalMemoryBytes = 32uL * 1_073_741_824uL,
    swap = SwapUsage(
        totalBytes = 4uL * 1_073_741_824uL,
        availableBytes = (4uL * 1_073_741_824uL) - swapUsed,
        usedBytes = swapUsed,
        encrypted = true,
    ),
    power = PowerState(
        batteryAvailable = true,
        onBattery = true,
        charging = false,
        percentage = 75,
        minutesRemaining = 240,
    ),
    totalProcessCount = processes.size,
    inaccessibleProcessCount = 0,
    processes = processes,
    processIssues = emptyList(),
)

fun processUsage(
    pid: Int = 42,
    startedAt: ULong = pid.toULong(),
    parentPid: Int = 1,
    name: String = "example",
    executablePath: String? = null,
    cpuPercent: Double = 0.0,
    footprint: ULong = 512uL * 1_048_576uL,
    impact: Double = 0.0,
): ProcessUsage = ProcessUsage(
    identity = ProcessIdentity(pid, startedAt),
    parentPid = parentPid,
    uid = 501u,
    name = name,
    executablePath = executablePath,
    cpuPercent = cpuPercent,
    userCpuPercent = cpuPercent,
    systemCpuPercent = 0.0,
    physicalFootprintBytes = footprint,
    residentBytes = footprint,
    wakeupsPerSecond = impact,
    diskReadBytesPerSecond = 0.0,
    diskWriteBytesPerSecond = 0.0,
    billedEnergyPerSecond = 0.0,
    batteryImpactScore = impact,
)

fun systemUsage(
    processes: List<ProcessUsage>,
    swapUsed: ULong = 0u,
    batteryPercentage: Int = 75,
): SystemUsage = SystemUsage(
    capturedAt = Instant.fromEpochSeconds(100),
    elapsedSeconds = 2.0,
    physicalMemoryBytes = 32uL * 1_073_741_824uL,
    swap = SwapUsage(
        totalBytes = 4uL * 1_073_741_824uL,
        availableBytes = (4uL * 1_073_741_824uL) - swapUsed,
        usedBytes = swapUsed,
        encrypted = true,
    ),
    power = PowerState(
        batteryAvailable = true,
        onBattery = true,
        charging = false,
        percentage = batteryPercentage,
        minutesRemaining = 180,
    ),
    totalProcessCount = processes.size,
    inaccessibleProcessCount = 0,
    processes = processes,
    applications = ApplicationGrouper().group(processes),
    processIssues = emptyList(),
)
