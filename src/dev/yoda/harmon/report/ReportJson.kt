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
                encrypted = usage.swap.encrypted,
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
            ),
            processes = ProcessSetDto(
                total = usage.totalProcessCount,
                readable = usage.processes.size,
                inaccessible = usage.inaccessibleProcessCount,
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
        name = name,
        uid = uid,
        cpuPercent = cpuPercent,
        physicalFootprintBytes = physicalFootprintBytes,
        wakeupsPerSecond = wakeupsPerSecond,
        diskReadBytesPerSecond = diskReadBytesPerSecond,
        diskWriteBytesPerSecond = diskWriteBytesPerSecond,
        billedEnergyPerSecond = billedEnergyPerSecond,
        batteryImpactScore = batteryImpactScore,
    )

    private fun ApplicationUsage.toDto() = ApplicationDto(
        id = id,
        name = name,
        rootPid = rootPid,
        processCount = processCount,
        cpuPercent = cpuPercent,
        physicalFootprintBytes = physicalFootprintBytes,
        wakeupsPerSecond = wakeupsPerSecond,
        diskReadBytesPerSecond = diskReadBytesPerSecond,
        diskWriteBytesPerSecond = diskWriteBytesPerSecond,
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
    val encrypted: Boolean,
)

@Serializable
private data class ProcessSetDto(
    val total: Int,
    val readable: Int,
    val inaccessible: Int,
    val topCpu: List<ProcessDto>,
    val topMemory: List<ProcessDto>,
    val topBatteryImpact: List<ProcessDto>,
)

@Serializable
private data class ApplicationSetDto(
    val total: Int,
    val topCpu: List<ApplicationDto>,
    val topMemory: List<ApplicationDto>,
    val topBatteryImpact: List<ApplicationDto>,
)

@Serializable
private data class ApplicationDto(
    val id: String,
    val name: String,
    val rootPid: Int,
    val processCount: Int,
    val cpuPercent: Double,
    val physicalFootprintBytes: ULong,
    val wakeupsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
    val billedEnergyPerSecond: Double,
    val batteryImpactScore: Double,
)

@Serializable
private data class ProcessDto(
    val pid: Int,
    val name: String,
    val uid: UInt?,
    val cpuPercent: Double,
    val physicalFootprintBytes: ULong,
    val wakeupsPerSecond: Double,
    val diskReadBytesPerSecond: Double,
    val diskWriteBytesPerSecond: Double,
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
