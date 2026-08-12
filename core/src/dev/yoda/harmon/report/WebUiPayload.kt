package dev.yoda.harmon.report

import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessTreeNode
import dev.yoda.harmon.model.ProcessTreeSnapshot
import dev.yoda.harmon.model.ProcessTreeSnapshotBuilder
import dev.yoda.harmon.model.SystemUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

const val WEB_UI_SCHEMA_VERSION = 1

@Serializable
enum class WebUiStatus {
    WARMING,
    READY,
    STALE,
}

@Serializable
data class WebUiSystemSummary(
    val cpuTotalPercent: Double,
    val physicalMemoryBytes: String,
    val swapUsedBytes: String,
    val swapTotalBytes: String,
    val swapAvailableBytes: String,
    val swapEncrypted: Boolean,
    val batteryAvailable: Boolean,
    val onBattery: Boolean,
    val charging: Boolean,
    val batteryPercentage: Int?,
    val loadAverageOneMinute: Double,
)

@Serializable
data class WebUiAlertSummary(
    val key: String,
    val severity: String,
    val title: String,
    val message: String,
)

@Serializable
data class WebUiPayload(
    val schemaVersion: Int = WEB_UI_SCHEMA_VERSION,
    val sequence: String,
    val status: WebUiStatus,
    val generatedAt: String,
    val capturedAt: String?,
    val sampleIntervalSeconds: Double?,
    val elapsedSeconds: Double?,
    val processTree: ProcessTreeSnapshot?,
    val system: WebUiSystemSummary?,
    val alerts: List<WebUiAlertSummary>,
    val suppressedAlertKeys: List<String>,
    val reportText: String,
    val error: String?,
    val staleSince: String?,
    val retrySeconds: Double?,
)

object WebUiPayloadFactory {
    fun staticSnapshot(
        report: MonitoringReport,
        reportText: String = ReportFormatter.text(report),
        generatedAt: Instant = Clock.System.now(),
    ): WebUiPayload = ready(
        usage = report.usage,
        sequence = 0u,
        sampleIntervalSeconds = report.usage.elapsedSeconds,
        generatedAt = generatedAt,
        alerts = report.alerts.map {
            WebUiAlertSummary(
                key = it.key,
                severity = it.severity.name.lowercase(),
                title = it.title,
                message = it.message,
            )
        },
        suppressedAlertKeys = report.suppressedAlertKeys,
        reportText = reportText,
    )

    fun live(
        usage: SystemUsage,
        sequence: ULong,
        sampleIntervalSeconds: Double = usage.elapsedSeconds,
        reportText: String = ReportFormatter.text(
            MonitoringReport(
                usage = usage,
                alerts = emptyList(),
                topProcessCount = DEFAULT_LIVE_REPORT_PROCESS_COUNT,
            ),
        ),
        generatedAt: Instant = Clock.System.now(),
    ): WebUiPayload = ready(
        usage = usage,
        sequence = sequence,
        sampleIntervalSeconds = sampleIntervalSeconds,
        generatedAt = generatedAt,
        alerts = emptyList(),
        suppressedAlertKeys = emptyList(),
        reportText = reportText,
    )

    fun warming(
        sampleIntervalSeconds: Double = 1.0,
        retrySeconds: Double = sampleIntervalSeconds,
        generatedAt: Instant = Clock.System.now(),
        reportText: String = "Waiting for the first live metrics sample.",
    ): WebUiPayload = WebUiPayload(
        sequence = "0",
        status = WebUiStatus.WARMING,
        generatedAt = generatedAt.toString(),
        capturedAt = null,
        sampleIntervalSeconds = sampleIntervalSeconds.finiteNonNegative(),
        elapsedSeconds = null,
        processTree = null,
        system = null,
        alerts = emptyList(),
        suppressedAlertKeys = emptyList(),
        reportText = reportText,
        error = null,
        staleSince = null,
        retrySeconds = retrySeconds.finiteNonNegative(),
    )

    fun stale(
        lastGood: WebUiPayload,
        error: String,
        staleSince: Instant,
        retrySeconds: Double = lastGood.sampleIntervalSeconds ?: 1.0,
        generatedAt: Instant = Clock.System.now(),
    ): WebUiPayload = lastGood.copy(
        status = WebUiStatus.STALE,
        generatedAt = generatedAt.toString(),
        error = error.safeError(),
        staleSince = staleSince.toString(),
        retrySeconds = retrySeconds.finiteNonNegative(),
    )

    private fun ready(
        usage: SystemUsage,
        sequence: ULong,
        sampleIntervalSeconds: Double,
        generatedAt: Instant,
        alerts: List<WebUiAlertSummary>,
        suppressedAlertKeys: List<String>,
        reportText: String,
    ): WebUiPayload = WebUiPayload(
        sequence = sequence.toString(),
        status = WebUiStatus.READY,
        generatedAt = generatedAt.toString(),
        capturedAt = usage.capturedAt.toString(),
        sampleIntervalSeconds = sampleIntervalSeconds.finiteNonNegative(),
        elapsedSeconds = usage.elapsedSeconds.finiteNonNegative(),
        processTree = ProcessTreeSnapshotBuilder.build(usage),
        system = usage.toWebUiSummary(),
        alerts = alerts,
        suppressedAlertKeys = suppressedAlertKeys,
        reportText = reportText,
        error = null,
        staleSince = null,
        retrySeconds = null,
    )
}

object WebUiPayloadJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(payload: WebUiPayload): String =
        json.encodeToString(payload.normalizedForJson())
}

private fun SystemUsage.toWebUiSummary(): WebUiSystemSummary = WebUiSystemSummary(
    cpuTotalPercent = processor.totalPercent.finiteNonNegative(),
    physicalMemoryBytes = physicalMemoryBytes.toString(),
    swapUsedBytes = swap.usedBytes.toString(),
    swapTotalBytes = swap.totalBytes.toString(),
    swapAvailableBytes = swap.availableBytes.toString(),
    swapEncrypted = swap.encrypted,
    batteryAvailable = power.batteryAvailable,
    onBattery = power.onBattery,
    charging = power.charging,
    batteryPercentage = power.percentage,
    loadAverageOneMinute = loadAverages.oneMinute.finiteNonNegative(),
)

private fun WebUiPayload.normalizedForJson(): WebUiPayload = copy(
    sampleIntervalSeconds = sampleIntervalSeconds?.finiteNonNegative(),
    elapsedSeconds = elapsedSeconds?.finiteNonNegative(),
    processTree = processTree?.copy(roots = processTree.roots.map(ProcessTreeNode::normalized)),
    system = system?.copy(
        cpuTotalPercent = system.cpuTotalPercent.finiteNonNegative(),
        loadAverageOneMinute = system.loadAverageOneMinute.finiteNonNegative(),
    ),
    retrySeconds = retrySeconds?.finiteNonNegative(),
)

private fun ProcessTreeNode.normalized(): ProcessTreeNode = copy(
    cpuSelfPercent = cpuSelfPercent.finiteNonNegative(),
    cpuTotalPercent = cpuTotalPercent.finiteNonNegative(),
    children = children.map(ProcessTreeNode::normalized),
)

private fun Double.finiteNonNegative(): Double =
    takeIf { isFinite() && this >= 0.0 } ?: 0.0

private fun String.safeError(): String {
    val normalized = asSequence()
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString(separator = "")
        .trim()
        .take(MAX_ERROR_LENGTH)
    return normalized.ifEmpty { "Live metrics temporarily unavailable." }
}

private const val DEFAULT_LIVE_REPORT_PROCESS_COUNT = 10
private const val MAX_ERROR_LENGTH = 512
