package dev.yoda.harmon.report

import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessTreeSnapshot
import dev.yoda.harmon.model.ProcessTreeSnapshotBuilder
import dev.yoda.harmon.model.SystemUsage
import dev.yoda.harmon.monitor.CollectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

const val WEB_UI_SCHEMA_VERSION = 3

@Serializable
enum class WebUiStatus {
    WARMING,
    READY,
    STALE,
}

@Serializable
data class WebUiProcessorSummary(
    val totalPercent: Double,
    val userPercent: Double,
    val systemPercent: Double,
    val nicePercent: Double,
    val idlePercent: Double,
)

@Serializable
data class WebUiLoadSummary(
    val oneMinute: Double,
    val fiveMinutes: Double,
    val fifteenMinutes: Double,
)

@Serializable
data class WebUiSwapSummary(
    val usedBytes: String,
    val totalBytes: String,
    val availableBytes: String,
    val encrypted: Boolean,
)

@Serializable
data class WebUiPowerSummary(
    val batteryAvailable: Boolean,
    val onBattery: Boolean,
    val charging: Boolean,
    val percentage: Int?,
    val minutesRemaining: Int?,
)

@Serializable
data class WebUiVirtualMemorySummary(
    val freeBytes: String,
    val activeBytes: String,
    val inactiveBytes: String,
    val wiredBytes: String,
    val purgeableBytes: String,
    val compressedBytes: String,
    val uncompressedBytesInCompressor: String,
    val swapBackedUncompressedBytes: String,
    val pageInBytesPerSecond: Double,
    val pageOutBytesPerSecond: Double,
    val faultRate: Double,
    val copyOnWriteFaultRate: Double,
    val compressionBytesPerSecond: Double,
    val decompressionBytesPerSecond: Double,
    val swapInBytesPerSecond: Double,
    val swapOutBytesPerSecond: Double,
)

@Serializable
data class WebUiStorageSummary(
    val available: Boolean,
    val deviceCount: Int,
    val readBytesPerSecond: Double,
    val writeBytesPerSecond: Double,
    val readOperationsPerSecond: Double,
    val writeOperationsPerSecond: Double,
    val readServiceTimePercent: Double,
    val writeServiceTimePercent: Double,
    val rootFileSystemTotalBytes: String,
    val rootFileSystemAvailableBytes: String,
)

@Serializable
data class WebUiProcessSummary(
    val total: Int,
    val inaccessible: Int,
    val compressedAttributionAvailable: Int,
    val compressedAttributionFailures: Int,
)

@Serializable
data class WebUiSystemSummary(
    val physicalMemoryBytes: String,
    val processor: WebUiProcessorSummary,
    val load: WebUiLoadSummary,
    val swap: WebUiSwapSummary,
    val power: WebUiPowerSummary,
    val virtualMemory: WebUiVirtualMemorySummary,
    val storage: WebUiStorageSummary,
    val processes: WebUiProcessSummary,
    val energyAccounted: Boolean,
)

/** [pids] is empty for machine-wide alerts, which are listed but never mark a row. */
@Serializable
data class WebUiAlertSummary(
    val key: String,
    val category: String,
    val severity: String,
    val title: String,
    val message: String,
    val pids: List<Int>,
)

@Serializable
data class WebUiPayload(
    val schemaVersion: Int = WEB_UI_SCHEMA_VERSION,
    val sequence: String,
    val status: WebUiStatus,
    val generatedAt: String,
    val capturedAt: String?,
    val attributionCapturedAt: String?,
    val attributionAgeSeconds: Double?,
    val attributionWarning: String?,
    val appliedProfile: CollectionProfile?,
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
        attributionCapturedAt = report.usage.capturedAt,
        attributionWarning = null,
        appliedProfile = CollectionProfile.FULL,
        alerts = report.alerts.map(Alert::toWebUiSummary),
        suppressedAlertKeys = report.suppressedAlertKeys,
        reportText = reportText,
    )

    fun live(
        usage: SystemUsage,
        sequence: ULong,
        attributionCapturedAt: Instant?,
        attributionWarning: String?,
        appliedProfile: CollectionProfile,
        alerts: List<Alert> = emptyList(),
        suppressedAlertKeys: List<String> = emptyList(),
        sampleIntervalSeconds: Double = usage.elapsedSeconds,
        generatedAt: Instant = Clock.System.now(),
    ): WebUiPayload {
        val report = MonitoringReport(
            usage = usage,
            alerts = alerts,
            topProcessCount = DEFAULT_LIVE_REPORT_PROCESS_COUNT,
            suppressedAlertKeys = suppressedAlertKeys,
        )
        return ready(
            usage = usage,
            sequence = sequence,
            sampleIntervalSeconds = sampleIntervalSeconds,
            generatedAt = generatedAt,
            attributionCapturedAt = attributionCapturedAt,
            attributionWarning = attributionWarning,
            appliedProfile = appliedProfile,
            alerts = alerts.map(Alert::toWebUiSummary),
            suppressedAlertKeys = suppressedAlertKeys,
            reportText = attributionReport(
                capturedAt = attributionCapturedAt,
                generatedAt = generatedAt,
                warning = attributionWarning,
            ) + ReportFormatter.text(report),
        )
    }

    fun warming(
        sampleIntervalSeconds: Double = 1.0,
        retrySeconds: Double = sampleIntervalSeconds,
        generatedAt: Instant = Clock.System.now(),
        previous: WebUiPayload? = null,
        error: String? = null,
        reportText: String = "Waiting for the first live metrics sample.",
    ): WebUiPayload {
        val carried = previous
        return WebUiPayload(
            sequence = "0",
            status = WebUiStatus.WARMING,
            generatedAt = generatedAt.toString(),
            capturedAt = carried?.capturedAt,
            attributionCapturedAt = carried?.attributionCapturedAt,
            attributionAgeSeconds = attributionAgeSeconds(
                carried?.attributionCapturedAt,
                generatedAt,
            ),
            attributionWarning = carried?.attributionWarning,
            appliedProfile = carried?.appliedProfile,
            sampleIntervalSeconds = sampleIntervalSeconds.finiteNonNegative(),
            elapsedSeconds = null,
            processTree = carried?.processTree,
            system = carried?.system,
            alerts = carried?.alerts.orEmpty(),
            suppressedAlertKeys = carried?.suppressedAlertKeys.orEmpty(),
            reportText = carried?.reportText?.takeIf { it.isNotBlank() } ?: reportText,
            error = error?.safeError(),
            staleSince = null,
            retrySeconds = retrySeconds.finiteNonNegative(),
        )
    }

    fun stale(
        lastGood: WebUiPayload,
        error: String,
        staleSince: Instant,
        retrySeconds: Double = lastGood.sampleIntervalSeconds ?: 1.0,
        generatedAt: Instant = Clock.System.now(),
    ): WebUiPayload = lastGood.copy(
        status = WebUiStatus.STALE,
        generatedAt = generatedAt.toString(),
        attributionAgeSeconds = attributionAgeSeconds(
            lastGood.attributionCapturedAt,
            generatedAt,
        ),
        error = error.safeError(),
        staleSince = staleSince.toString(),
        retrySeconds = retrySeconds.finiteNonNegative(),
    )

    private fun ready(
        usage: SystemUsage,
        sequence: ULong,
        sampleIntervalSeconds: Double,
        generatedAt: Instant,
        attributionCapturedAt: Instant?,
        attributionWarning: String?,
        appliedProfile: CollectionProfile,
        alerts: List<WebUiAlertSummary>,
        suppressedAlertKeys: List<String>,
        reportText: String,
    ): WebUiPayload = WebUiPayload(
        sequence = sequence.toString(),
        status = WebUiStatus.READY,
        generatedAt = generatedAt.toString(),
        capturedAt = usage.capturedAt.toString(),
        attributionCapturedAt = attributionCapturedAt?.toString(),
        attributionAgeSeconds = attributionAgeSeconds(
            attributionCapturedAt?.toString(),
            generatedAt,
        ),
        attributionWarning = attributionWarning?.safeError(),
        appliedProfile = appliedProfile,
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

private fun Alert.toWebUiSummary(): WebUiAlertSummary = WebUiAlertSummary(
    key = key,
    category = category.name.lowercase(),
    severity = severity.name.lowercase(),
    title = title,
    message = message,
    pids = pids,
)

object WebUiPayloadJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(payload: WebUiPayload): String = json.encodeToString(payload.normalizedForJson())
}

private fun SystemUsage.toWebUiSummary(): WebUiSystemSummary = WebUiSystemSummary(
    physicalMemoryBytes = physicalMemoryBytes.toString(),
    processor = WebUiProcessorSummary(
        totalPercent = processor.totalPercent.finiteNonNegative(),
        userPercent = processor.userPercent.finiteNonNegative(),
        systemPercent = processor.systemPercent.finiteNonNegative(),
        nicePercent = processor.nicePercent.finiteNonNegative(),
        idlePercent = processor.idlePercent.finiteNonNegative(),
    ),
    load = WebUiLoadSummary(
        oneMinute = loadAverages.oneMinute.finiteNonNegative(),
        fiveMinutes = loadAverages.fiveMinutes.finiteNonNegative(),
        fifteenMinutes = loadAverages.fifteenMinutes.finiteNonNegative(),
    ),
    swap = WebUiSwapSummary(
        usedBytes = swap.usedBytes.toString(),
        totalBytes = swap.totalBytes.toString(),
        availableBytes = swap.availableBytes.toString(),
        encrypted = swap.encrypted,
    ),
    power = WebUiPowerSummary(
        batteryAvailable = power.batteryAvailable,
        onBattery = power.onBattery,
        charging = power.charging,
        percentage = power.percentage,
        minutesRemaining = power.minutesRemaining,
    ),
    virtualMemory = WebUiVirtualMemorySummary(
        freeBytes = virtualMemory.freeBytes.toString(),
        activeBytes = virtualMemory.activeBytes.toString(),
        inactiveBytes = virtualMemory.inactiveBytes.toString(),
        wiredBytes = virtualMemory.wiredBytes.toString(),
        purgeableBytes = virtualMemory.purgeableBytes.toString(),
        compressedBytes = virtualMemory.compressedBytes.toString(),
        uncompressedBytesInCompressor = virtualMemory.uncompressedBytesInCompressor.toString(),
        swapBackedUncompressedBytes = virtualMemory.swapBackedUncompressedBytes.toString(),
        pageInBytesPerSecond = virtualMemory.pageInBytesPerSecond.finiteNonNegative(),
        pageOutBytesPerSecond = virtualMemory.pageOutBytesPerSecond.finiteNonNegative(),
        faultRate = virtualMemory.faultRate.finiteNonNegative(),
        copyOnWriteFaultRate = virtualMemory.copyOnWriteFaultRate.finiteNonNegative(),
        compressionBytesPerSecond = virtualMemory.compressionBytesPerSecond.finiteNonNegative(),
        decompressionBytesPerSecond = virtualMemory.decompressionBytesPerSecond.finiteNonNegative(),
        swapInBytesPerSecond = virtualMemory.swapInBytesPerSecond.finiteNonNegative(),
        swapOutBytesPerSecond = virtualMemory.swapOutBytesPerSecond.finiteNonNegative(),
    ),
    storage = WebUiStorageSummary(
        available = storage.available,
        deviceCount = storage.deviceCount,
        readBytesPerSecond = storage.readBytesPerSecond.finiteNonNegative(),
        writeBytesPerSecond = storage.writeBytesPerSecond.finiteNonNegative(),
        readOperationsPerSecond = storage.readOperationsPerSecond.finiteNonNegative(),
        writeOperationsPerSecond = storage.writeOperationsPerSecond.finiteNonNegative(),
        readServiceTimePercent = storage.readServiceTimePercent.finiteNonNegative(),
        writeServiceTimePercent = storage.writeServiceTimePercent.finiteNonNegative(),
        rootFileSystemTotalBytes = storage.rootFileSystemTotalBytes.toString(),
        rootFileSystemAvailableBytes = storage.rootFileSystemAvailableBytes.toString(),
    ),
    processes = WebUiProcessSummary(
        total = totalProcessCount,
        inaccessible = inaccessibleProcessCount,
        compressedAttributionAvailable = compressedAttributionProcessCount,
        compressedAttributionFailures = compressedAttributionFailureCount,
    ),
    energyAccounted = energyAccounted,
)

private fun WebUiPayload.normalizedForJson(): WebUiPayload = copy(
    attributionAgeSeconds = attributionAgeSeconds?.finiteNonNegative(),
    sampleIntervalSeconds = sampleIntervalSeconds?.finiteNonNegative(),
    elapsedSeconds = elapsedSeconds?.finiteNonNegative(),
    system = system?.copy(
        processor = system.processor.copy(
            totalPercent = system.processor.totalPercent.finiteNonNegative(),
            userPercent = system.processor.userPercent.finiteNonNegative(),
            systemPercent = system.processor.systemPercent.finiteNonNegative(),
            nicePercent = system.processor.nicePercent.finiteNonNegative(),
            idlePercent = system.processor.idlePercent.finiteNonNegative(),
        ),
        load = system.load.copy(
            oneMinute = system.load.oneMinute.finiteNonNegative(),
            fiveMinutes = system.load.fiveMinutes.finiteNonNegative(),
            fifteenMinutes = system.load.fifteenMinutes.finiteNonNegative(),
        ),
        virtualMemory = system.virtualMemory.normalized(),
        storage = system.storage.normalized(),
    ),
    retrySeconds = retrySeconds?.finiteNonNegative(),
)

private fun WebUiVirtualMemorySummary.normalized(): WebUiVirtualMemorySummary = copy(
    pageInBytesPerSecond = pageInBytesPerSecond.finiteNonNegative(),
    pageOutBytesPerSecond = pageOutBytesPerSecond.finiteNonNegative(),
    faultRate = faultRate.finiteNonNegative(),
    copyOnWriteFaultRate = copyOnWriteFaultRate.finiteNonNegative(),
    compressionBytesPerSecond = compressionBytesPerSecond.finiteNonNegative(),
    decompressionBytesPerSecond = decompressionBytesPerSecond.finiteNonNegative(),
    swapInBytesPerSecond = swapInBytesPerSecond.finiteNonNegative(),
    swapOutBytesPerSecond = swapOutBytesPerSecond.finiteNonNegative(),
)

private fun WebUiStorageSummary.normalized(): WebUiStorageSummary = copy(
    readBytesPerSecond = readBytesPerSecond.finiteNonNegative(),
    writeBytesPerSecond = writeBytesPerSecond.finiteNonNegative(),
    readOperationsPerSecond = readOperationsPerSecond.finiteNonNegative(),
    writeOperationsPerSecond = writeOperationsPerSecond.finiteNonNegative(),
    readServiceTimePercent = readServiceTimePercent.finiteNonNegative(),
    writeServiceTimePercent = writeServiceTimePercent.finiteNonNegative(),
)

private fun attributionReport(
    capturedAt: Instant?,
    generatedAt: Instant,
    warning: String?,
): String = buildString {
    if (capturedAt == null) {
        appendLine("Last FULL attribution: unavailable.")
    } else {
        val age = (generatedAt - capturedAt).nonNegativeSeconds()
        appendLine(
            "Last FULL attribution captured at $capturedAt; " +
                "age ${formatAge(age)} seconds.",
        )
    }
    warning?.safeError()?.let { appendLine("Attribution warning: $it") }
    appendLine()
}

private fun attributionAgeSeconds(capturedAt: String?, generatedAt: Instant): Double? {
    val captured = capturedAt?.let {
        try {
            Instant.parse(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    } ?: return null
    return (generatedAt - captured).nonNegativeSeconds()
}

private fun Duration.nonNegativeSeconds(): Double =
    (inWholeMilliseconds.toDouble() / 1_000.0).coerceAtLeast(0.0).finiteNonNegative()

private fun formatAge(seconds: Double): String =
    if (seconds % 1.0 == 0.0) seconds.toLong().toString() else seconds.toString()

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
