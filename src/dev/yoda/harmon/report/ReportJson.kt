package dev.yoda.harmon.report

import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ReportJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(report: MonitoringReport): String =
        json.encodeToString(report.toDto())

    fun testEvent(): String =
        json.encodeToString(
            TestEventDto(
                message = "Notification test",
            ),
        )

    fun telegramRequest(chatId: String, text: String): String =
        json.encodeToString(
            TelegramRequestDto(
                chatId = chatId,
                text = text,
            ),
        )

    private fun MonitoringReport.toDto(): ReportEventDto {
        val usage = usage
        return ReportEventDto(
            capturedAt = usage.capturedAt.toString(),
            elapsedSeconds = usage.elapsedSeconds,
            power = usage.power.toDto(),
            swap = SwapDto(
                usedBytes = usage.swap.usedBytes,
                totalBytes = usage.swap.totalBytes,
                availableBytes = usage.swap.availableBytes,
                encrypted = usage.swap.encrypted,
            ),
            system = SystemDto(
                cpu = CpuDto(
                    totalPercent = usage.processor.totalPercent,
                    userPercent = usage.processor.userPercent,
                    systemPercent = usage.processor.systemPercent,
                    nicePercent = usage.processor.nicePercent,
                    idlePercent = usage.processor.idlePercent,
                ),
                load = LoadDto(
                    oneMinute = usage.loadAverages.oneMinute,
                    fiveMinutes = usage.loadAverages.fiveMinutes,
                    fifteenMinutes = usage.loadAverages.fifteenMinutes,
                ),
                virtualMemory = VirtualMemoryDto(
                    freeBytes = usage.virtualMemory.freeBytes,
                    activeBytes = usage.virtualMemory.activeBytes,
                    inactiveBytes = usage.virtualMemory.inactiveBytes,
                    wiredBytes = usage.virtualMemory.wiredBytes,
                    purgeableBytes = usage.virtualMemory.purgeableBytes,
                    compressedBytes = usage.virtualMemory.compressedBytes,
                    uncompressedBytesInCompressor =
                        usage.virtualMemory.uncompressedBytesInCompressor,
                    swapBackedUncompressedBytes =
                        usage.virtualMemory.swapBackedUncompressedBytes,
                    compressionBytesPerSecond =
                        usage.virtualMemory.compressionBytesPerSecond,
                    decompressionBytesPerSecond =
                        usage.virtualMemory.decompressionBytesPerSecond,
                    swapInBytesPerSecond = usage.virtualMemory.swapInBytesPerSecond,
                    swapOutBytesPerSecond = usage.virtualMemory.swapOutBytesPerSecond,
                    pageInBytesPerSecond = usage.virtualMemory.pageInBytesPerSecond,
                    pageOutBytesPerSecond = usage.virtualMemory.pageOutBytesPerSecond,
                    faultRate = usage.virtualMemory.faultRate,
                    copyOnWriteFaultRate = usage.virtualMemory.copyOnWriteFaultRate,
                ),
                storage = StorageDto(
                    available = usage.storage.available,
                    deviceCount = usage.storage.deviceCount,
                    readBytesPerSecond = usage.storage.readBytesPerSecond,
                    writeBytesPerSecond = usage.storage.writeBytesPerSecond,
                    readOperationsPerSecond = usage.storage.readOperationsPerSecond,
                    writeOperationsPerSecond = usage.storage.writeOperationsPerSecond,
                    readServiceTimePercent = usage.storage.readServiceTimePercent,
                    writeServiceTimePercent = usage.storage.writeServiceTimePercent,
                    rootFileSystemTotalBytes = usage.storage.rootFileSystemTotalBytes,
                    rootFileSystemAvailableBytes =
                        usage.storage.rootFileSystemAvailableBytes,
                ),
            ),
            applications = ApplicationSetDto(
                total = usage.applications.size,
                topCpu = usage.applications
                    .sortedByDescending { it.cpuPercent }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topMemory = usage.applications
                    .sortedByDescending { it.physicalFootprintBytes }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topBatteryImpact = usage.applications
                    .sortedByDescending { it.batteryImpactScore }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topPhysicalWrites = usage.applications
                    .filter { it.diskWriteBytesPerSecond > 0.0 }
                    .sortedByDescending { it.diskWriteBytesPerSecond }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topInternalLogicalWrites = usage.applications
                    .filter { it.logicalWriteBytesPerSecond > 0.0 }
                    .sortedByDescending { it.logicalWriteBytesPerSecond }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topCompressedOrPagedOut = usage.applications
                    .filter { it.compressedAttributionProcessCount > 0 }
                    .sortedByDescending { it.compressedOrPagedOutBytes }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topEnergy = usage.applications
                    .filter { it.energyWatts > 0.0 }
                    .sortedByDescending { it.energyWatts }
                    .take(topProcessCount)
                    .map { it.toDto() },
            ),
            processes = ProcessSetDto(
                total = usage.totalProcessCount,
                readable = usage.processes.size,
                inaccessible = usage.inaccessibleProcessCount,
                compressedOrPagedOutMeasured =
                    usage.compressedAttributionProcessCount,
                compressedOrPagedOutFailures =
                    usage.compressedAttributionFailureCount,
                topCpu = usage.processes
                    .sortedByDescending { it.cpuPercent }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topMemory = usage.processes
                    .sortedByDescending { it.physicalFootprintBytes }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topBatteryImpact = usage.processes
                    .sortedByDescending { it.batteryImpactScore }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topPhysicalWrites = usage.processes
                    .filter { it.diskWriteBytesPerSecond > 0.0 }
                    .sortedByDescending { it.diskWriteBytesPerSecond }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topInternalLogicalWrites = usage.processes
                    .filter { it.logicalWriteBytesPerSecond > 0.0 }
                    .sortedByDescending { it.logicalWriteBytesPerSecond }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topCompressedOrPagedOut = usage.processes
                    .filter { it.compressedOrPagedOutBytes != null }
                    .sortedByDescending { it.compressedOrPagedOutBytes ?: 0u }
                    .take(topProcessCount)
                    .map { it.toDto() },
                topEnergy = usage.processes
                    .filter { it.energyWatts > 0.0 }
                    .sortedByDescending { it.energyWatts }
                    .take(topProcessCount)
                    .map { it.toDto() },
            ),
            alerts = alerts.map { it.toDto() },
        )
    }

    private fun PowerState.toDto() = PowerDto(
        available = batteryAvailable,
        onBattery = onBattery,
        charging = charging,
        percentage = percentage,
        minutesRemaining = minutesRemaining,
    )

    private fun ProcessUsage.toDto() = ProcessDto(
        pid = identity.pid,
        parentPid = parentPid,
        name = name,
        uid = uid,
        cpuPercent = cpuPercent,
        userCpuPercent = userCpuPercent,
        systemCpuPercent = systemCpuPercent,
        physicalFootprintBytes = physicalFootprintBytes,
        residentBytes = residentBytes,
        wiredBytes = wiredBytes,
        lifetimeMaxPhysicalFootprintBytes = lifetimeMaxPhysicalFootprintBytes,
        wakeupsPerSecond = wakeupsPerSecond,
        diskReadBytesPerSecond = diskReadBytesPerSecond,
        diskWriteBytesPerSecond = diskWriteBytesPerSecond,
        logicalWriteBytesPerSecond = logicalWriteBytesPerSecond,
        compressedOrPagedOutBytes = compressedOrPagedOutBytes,
        virtualMemoryRegionCount = virtualMemoryRegionCount,
        pageInsPerSecond = pageInsPerSecond,
        instructionsPerSecond = instructionsPerSecond,
        cyclesPerSecond = cyclesPerSecond,
        faultsPerSecond = faultsPerSecond,
        copyOnWriteFaultsPerSecond = copyOnWriteFaultsPerSecond,
        systemCallsPerSecond = systemCallsPerSecond,
        contextSwitchesPerSecond = contextSwitchesPerSecond,
        threadCount = threadCount,
        runningThreadCount = runningThreadCount,
        energyWatts = energyWatts,
        billedEnergyPerSecond = billedEnergyPerSecond,
        batteryImpactScore = batteryImpactScore,
    )

    private fun ApplicationUsage.toDto() = ApplicationDto(
        id = id,
        name = name,
        rootPid = rootPid,
        processCount = processCount,
        cpuPercent = cpuPercent,
        userCpuPercent = userCpuPercent,
        systemCpuPercent = systemCpuPercent,
        physicalFootprintBytes = physicalFootprintBytes,
        residentBytes = residentBytes,
        wiredBytes = wiredBytes,
        lifetimeMaxPhysicalFootprintBytes = lifetimeMaxPhysicalFootprintBytes,
        wakeupsPerSecond = wakeupsPerSecond,
        diskReadBytesPerSecond = diskReadBytesPerSecond,
        diskWriteBytesPerSecond = diskWriteBytesPerSecond,
        logicalWriteBytesPerSecond = logicalWriteBytesPerSecond,
        compressedOrPagedOutBytes = compressedOrPagedOutBytes,
        compressedAttributionProcessCount = compressedAttributionProcessCount,
        pageInsPerSecond = pageInsPerSecond,
        instructionsPerSecond = instructionsPerSecond,
        cyclesPerSecond = cyclesPerSecond,
        faultsPerSecond = faultsPerSecond,
        copyOnWriteFaultsPerSecond = copyOnWriteFaultsPerSecond,
        systemCallsPerSecond = systemCallsPerSecond,
        contextSwitchesPerSecond = contextSwitchesPerSecond,
        threadCount = threadCount,
        runningThreadCount = runningThreadCount,
        energyWatts = energyWatts,
        billedEnergyPerSecond = billedEnergyPerSecond,
        batteryImpactScore = batteryImpactScore,
    )

    private fun Alert.toDto() = AlertDto(
        key = key,
        severity = severity.name.lowercase(),
        title = title,
        message = message,
    )
}

@Serializable
private data class ReportEventDto(
    val event: String = "harmon.sample",
    val capturedAt: String,
    val elapsedSeconds: Double,
    val power: PowerDto,
    val swap: SwapDto,
    val system: SystemDto,
    val applications: ApplicationSetDto,
    val processes: ProcessSetDto,
    val alerts: List<AlertDto>,
)

@Serializable
private data class PowerDto(
    val available: Boolean,
    val onBattery: Boolean,
    val charging: Boolean,
    val percentage: Int?,
    val minutesRemaining: Int?,
)

@Serializable
private data class SwapDto(
    val usedBytes: ULong,
    val totalBytes: ULong,
    val availableBytes: ULong,
    val encrypted: Boolean,
)

@Serializable
private data class SystemDto(
    val cpu: CpuDto,
    val load: LoadDto,
    val virtualMemory: VirtualMemoryDto,
    val storage: StorageDto,
)

@Serializable
private data class CpuDto(
    val totalPercent: Double,
    val userPercent: Double,
    val systemPercent: Double,
    val nicePercent: Double,
    val idlePercent: Double,
)

@Serializable
private data class LoadDto(
    val oneMinute: Double,
    val fiveMinutes: Double,
    val fifteenMinutes: Double,
)

@Serializable
private data class VirtualMemoryDto(
    val freeBytes: ULong,
    val activeBytes: ULong,
    val inactiveBytes: ULong,
    val wiredBytes: ULong,
    val purgeableBytes: ULong,
    val compressedBytes: ULong,
    val uncompressedBytesInCompressor: ULong,
    val swapBackedUncompressedBytes: ULong,
    val compressionBytesPerSecond: Double,
    val decompressionBytesPerSecond: Double,
    val swapInBytesPerSecond: Double,
    val swapOutBytesPerSecond: Double,
    val pageInBytesPerSecond: Double,
    val pageOutBytesPerSecond: Double,
    val faultRate: Double,
    val copyOnWriteFaultRate: Double,
)

@Serializable
private data class StorageDto(
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

@Serializable
private data class ProcessSetDto(
    val total: Int,
    val readable: Int,
    val inaccessible: Int,
    val compressedOrPagedOutMeasured: Int,
    val compressedOrPagedOutFailures: Int,
    val topCpu: List<ProcessDto>,
    val topMemory: List<ProcessDto>,
    val topBatteryImpact: List<ProcessDto>,
    val topPhysicalWrites: List<ProcessDto>,
    val topInternalLogicalWrites: List<ProcessDto>,
    val topCompressedOrPagedOut: List<ProcessDto>,
    val topEnergy: List<ProcessDto>,
)

@Serializable
private data class ApplicationSetDto(
    val total: Int,
    val topCpu: List<ApplicationDto>,
    val topMemory: List<ApplicationDto>,
    val topBatteryImpact: List<ApplicationDto>,
    val topPhysicalWrites: List<ApplicationDto>,
    val topInternalLogicalWrites: List<ApplicationDto>,
    val topCompressedOrPagedOut: List<ApplicationDto>,
    val topEnergy: List<ApplicationDto>,
)

@Serializable
private data class ApplicationDto(
    val id: String,
    val name: String,
    val rootPid: Int,
    val processCount: Int,
    val cpuPercent: Double,
    val userCpuPercent: Double,
    val systemCpuPercent: Double,
    val physicalFootprintBytes: ULong,
    val residentBytes: ULong,
    val wiredBytes: ULong,
    val lifetimeMaxPhysicalFootprintBytes: ULong,
    val wakeupsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
    val logicalWriteBytesPerSecond: Double,
    val compressedOrPagedOutBytes: ULong,
    val compressedAttributionProcessCount: Int,
    val pageInsPerSecond: Double,
    val instructionsPerSecond: Double,
    val cyclesPerSecond: Double,
    val faultsPerSecond: Double,
    val copyOnWriteFaultsPerSecond: Double,
    val systemCallsPerSecond: Double,
    val contextSwitchesPerSecond: Double,
    val threadCount: Int,
    val runningThreadCount: Int,
    val energyWatts: Double,
    val billedEnergyPerSecond: Double,
    val batteryImpactScore: Double,
)

@Serializable
private data class ProcessDto(
    val pid: Int,
    val parentPid: Int,
    val name: String,
    val uid: UInt?,
    val cpuPercent: Double,
    val userCpuPercent: Double,
    val systemCpuPercent: Double,
    val physicalFootprintBytes: ULong,
    val residentBytes: ULong,
    val wiredBytes: ULong,
    val lifetimeMaxPhysicalFootprintBytes: ULong,
    val wakeupsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
    val logicalWriteBytesPerSecond: Double,
    val compressedOrPagedOutBytes: ULong?,
    val virtualMemoryRegionCount: Int?,
    val pageInsPerSecond: Double,
    val instructionsPerSecond: Double,
    val cyclesPerSecond: Double,
    val faultsPerSecond: Double,
    val copyOnWriteFaultsPerSecond: Double,
    val systemCallsPerSecond: Double,
    val contextSwitchesPerSecond: Double,
    val threadCount: Int,
    val runningThreadCount: Int,
    val energyWatts: Double,
    val billedEnergyPerSecond: Double,
    val batteryImpactScore: Double,
)

@Serializable
private data class AlertDto(
    val key: String,
    val severity: String,
    val title: String,
    val message: String,
)

@Serializable
private data class TestEventDto(
    val event: String = "harmon.test",
    val message: String,
)

@Serializable
private data class TelegramRequestDto(
    @kotlinx.serialization.SerialName("chat_id")
    val chatId: String,
    val text: String,
    @kotlinx.serialization.SerialName("disable_web_page_preview")
    val disableWebPagePreview: Boolean = true,
)
