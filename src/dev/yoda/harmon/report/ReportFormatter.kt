package dev.yoda.harmon.report

import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.Severity
import dev.yoda.harmon.util.Format

object ReportFormatter {
    /**
     * [rankings] is accepted so a caller that renders both the text and the JSON payload of the
     * same report ranks its applications once instead of once per renderer.
     */
    fun text(
        report: MonitoringReport,
        rankings: ApplicationRankings = ApplicationRankings(report),
    ): String = buildString {
        val usage = report.usage
        appendLine("Harmon sample at ${usage.capturedAt}")
        appendLine("Window: ${Format.decimal(usage.elapsedSeconds)}s")
        appendLine("Power: ${powerText(usage.power)}")
        appendLine(
            "System CPU: ${Format.decimal(usage.processor.totalPercent)}% " +
                "(user ${Format.decimal(usage.processor.userPercent)}%, " +
                "system ${Format.decimal(usage.processor.systemPercent)}%); " +
                "load ${Format.decimal(usage.loadAverages.oneMinute)} / " +
                "${Format.decimal(usage.loadAverages.fiveMinutes)} / " +
                Format.decimal(usage.loadAverages.fifteenMinutes),
        )
        appendLine(
            "Swap: ${Format.bytes(usage.swap.usedBytes)} used / " +
                "${Format.bytes(usage.swap.totalBytes)} allocated" +
                if (usage.swap.encrypted) " (encrypted)" else "",
        )
        appendLine(
            "VM: ${Format.bytes(usage.virtualMemory.compressedBytes)} compressor RAM, " +
                "${Format.bytes(
                    usage.virtualMemory.swapBackedUncompressedBytes,
                )} uncompressed memory represented in swap; " +
                "${Format.bytesPerSecond(usage.virtualMemory.compressionBytesPerSecond)} " +
                "compress, " +
                "${Format.bytesPerSecond(usage.virtualMemory.swapOutBytesPerSecond)} swap-out",
        )
        appendLine(storageText(usage))
        appendLine(
            "Processes: ${usage.processes.size}/${usage.totalProcessCount} readable, " +
                "${usage.inaccessibleProcessCount} inaccessible",
        )
        appendLine(
            "Applications: ${usage.applications.size} groups from " +
                "${usage.processes.size} readable processes",
        )
        appendLine(
            "Compressed/paged-out attribution: " +
                "${usage.compressedAttributionProcessCount} processes measured, " +
                "${usage.compressedAttributionFailureCount} failed",
        )

        appendApplicationTable(
            heading = "Top application CPU",
            applications = rankings.topCpu,
            metric = { "${Format.decimal(it.cpuPercent)}%" },
        )
        appendApplicationTable(
            heading = "Top application memory",
            applications = rankings.topMemory,
            metric = { Format.bytes(it.physicalFootprintBytes) },
        )
        appendApplicationTable(
            heading = "Likely application battery impact",
            applications = rankings.topBatteryImpact,
            metric = { application ->
                "score ${Format.decimal(application.batteryImpactScore)}, " +
                    "${Format.decimal(application.wakeupsPerSecond)} wakeups/s, " +
                    "${Format.bytesPerSecond(
                        application.diskReadBytesPerSecond +
                            application.diskWriteBytesPerSecond,
                    )} I/O" +
                    if (application.energyWatts > 0.0) {
                        ", ${Format.power(application.energyWatts)} accounted"
                    } else {
                        ""
                    }
            },
        )
        appendApplicationTable(
            heading = "Top application storage writes",
            applications = rankings.topStorageWrites,
            metric = { application ->
                "${Format.bytesPerSecond(
                    application.diskWriteBytesPerSecond,
                )} physical (all devices), " +
                    "${Format.bytesPerSecond(
                        application.logicalWriteBytesPerSecond,
                    )} logical (internal)"
            },
        )
        appendApplicationTable(
            heading = "Top application compressed/paged-out memory",
            applications = rankings.topCompressedOrPagedOut,
            metric = { application ->
                "${Format.bytes(application.compressedOrPagedOutBytes)} proxy " +
                    "(${application.compressedAttributionProcessCount}/" +
                    "${application.processCount} processes measured)"
            },
        )

        if (report.alerts.isNotEmpty()) {
            appendLine()
            appendLine("Alerts:")
            report.alerts.forEach { alert ->
                appendLine("- ${alert.severity.name.lowercase()}: ${alert.message}")
            }
        }
    }.trimEnd()

    fun diagnostics(report: MonitoringReport): String = buildString {
        appendLine(text(report))
        appendLine()
        appendLine("Multi-process application groups:")
        val groupedApplications = report.usage.applications
            .asSequence()
            .filter { it.processCount > 1 }
            .sortedWith(
                compareByDescending<ApplicationUsage> { it.processCount }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
        if (groupedApplications.isEmpty()) {
            appendLine("- none")
        } else {
            groupedApplications.forEach { application ->
                appendLine(
                    "- ${application.name}: ${application.processCount} processes, " +
                        "root PID ${application.rootPid}",
                )
                application.bundlePath?.let { appendLine("  bundle: $it") }
                appendLine("  PIDs: ${application.processIds.joinToString()}")
            }
        }

        appendLine()
        appendLine(
            "Processes without resource metrics: " +
                "${report.usage.inaccessibleProcessCount}",
        )
        val issues = report.usage.processIssues.sortedBy { it.pid }
        if (issues.isEmpty()) {
            appendLine("- no diagnostic details were retained")
        } else {
            val reasonSummary = issues
                .groupingBy { it.reason }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .joinToString { (reason, count) ->
                    "${reason.name.lowercase().replace('_', '-')}=$count"
                }
            appendLine("Reasons: $reasonSummary")
            issues.forEach { issue -> appendIssue(issue) }
            val omittedDetails = report.usage.inaccessibleProcessCount - issues.size
            if (omittedDetails > 0) {
                appendLine("- $omittedDetails additional issues exceeded diagnostic capacity")
            }
        }

        appendLine()
        appendLine("Compressed/paged-out attribution details:")
        appendLine(
            "- measured ${report.usage.compressedAttributionProcessCount} of " +
                "${report.usage.processes.size} readable processes; " +
                "${report.usage.compressedAttributionFailureCount} attempts failed",
        )
        appendLine(
            "- this is compressor-pager ownership, not exact per-process disk swap; " +
                "see docs/collection.md",
        )
    }.trimEnd()

    /**
     * The push itself carries only [highlighted] — the alerts that crossed their threshold on
     * this sample — while the attached HTML and JSON carry the whole [report], so the reader
     * still sees the full picture. [reportText] is accepted already rendered to avoid a second
     * pass over the same report.
     */
    fun notification(
        report: MonitoringReport,
        highlighted: List<Alert> = report.alerts,
        rankings: ApplicationRankings = ApplicationRankings(report),
        reportText: String = text(report, rankings),
    ): NotificationPayload {
        val title = when {
            highlighted.any { it.severity == Severity.CRITICAL } -> "Harmon: critical alert"
            highlighted.isNotEmpty() -> "Harmon: system warning"
            else -> "Harmon: system sample"
        }
        val subtitle = when (highlighted.size) {
            0 -> powerText(report.usage.power)
            1 -> highlighted.single().title
            else -> "${highlighted.size} alerts"
        }
        val message = if (highlighted.isEmpty()) {
            "Swap ${Format.bytes(report.usage.swap.usedBytes)}; " +
                "top CPU ${topCpuSummary(report)}"
        } else {
            highlighted.joinToString(separator = "\n") { it.message }.take(MAX_NOTIFICATION_TEXT)
        }

        return NotificationPayload(
            identifier = "harmon-${report.usage.capturedAt.toEpochMilliseconds()}",
            title = title,
            subtitle = subtitle,
            text = message,
            html = ReportHtml.document(
                title = title,
                subtitle = subtitle,
                reportText = reportText,
            ),
            json = json(report, highlighted.map { it.key }, rankings),
        )
    }

    fun json(
        report: MonitoringReport,
        newAlertKeys: List<String> = report.alerts.map { it.key },
        rankings: ApplicationRankings = ApplicationRankings(report),
    ): String = ReportJson.encode(report, newAlertKeys, rankings)

    fun testPayload(): NotificationPayload = NotificationPayload(
        identifier = "harmon-notification-test",
        title = "Harmon",
        subtitle = "Notification test",
        text = "System monitoring notifications are configured correctly.",
        html = ReportHtml.document(
            title = "Harmon",
            subtitle = "Notification test",
            reportText = "System monitoring notifications are configured correctly.\n\n" +
                "Clicking a Harmon notification opens the latest local HTML report.",
        ),
        json = ReportJson.testEvent(),
    )

    private fun StringBuilder.appendApplicationTable(
        heading: String,
        applications: List<ApplicationUsage>,
        metric: (ApplicationUsage) -> String,
    ) {
        appendLine()
        appendLine("$heading:")
        if (applications.isEmpty()) {
            appendLine("No matching application activity in this sample.")
        }
        applications.forEachIndexed { index, application ->
            appendLine(
                "${index + 1}. ${application.reportLabel()}: ${metric(application)}",
            )
        }
    }

    private fun StringBuilder.appendIssue(issue: ProcessCollectionIssue) {
        val metadata = buildList {
            issue.parentPid?.let { add("PPID $it") }
            issue.uid?.let { add("UID $it") }
            issue.errorCode?.let { add("errno $it") }
        }.joinToString()
        appendLine(
            "- PID ${issue.pid} ${issue.name ?: "<unknown>"}: " +
                issue.reason.name.lowercase().replace('_', '-') +
                if (metadata.isEmpty()) "" else " ($metadata)",
        )
        issue.executablePath?.let { appendLine("  path: $it") }
    }

    private fun ApplicationUsage.reportLabel(): String =
        if (processCount == 1) "$name (PID $rootPid)" else "$name ($processCount processes)"

    private fun powerText(power: PowerState): String = when {
        !power.batteryAvailable -> "battery unavailable"
        power.charging -> "charging, ${power.percentage?.let { "$it%" } ?: "level unknown"}"
        power.onBattery -> buildString {
            append("battery")
            power.percentage?.let { append(" $it%") }
            power.minutesRemaining?.let { append(", ${it / 60}h ${it % 60}m remaining") }
        }
        else -> "AC power, ${power.percentage?.let { "$it%" } ?: "battery level unknown"}"
    }

    private fun topCpuSummary(report: MonitoringReport): String {
        val application = report.usage.applications.maxByOrNull { it.cpuPercent }
            ?: return "n/a"
        return "${application.name} ${Format.decimal(application.cpuPercent)}%"
    }

    private fun storageText(usage: dev.yoda.harmon.model.SystemUsage): String {
        val storage = usage.storage
        if (!storage.available) {
            return "Internal storage: counters unavailable" +
                if (storage.rootFileSystemTotalBytes > 0u) {
                    "; ${Format.bytes(
                        storage.rootFileSystemAvailableBytes,
                    )} filesystem available"
                } else {
                    ""
                }
        }
        return "Internal storage: " +
            "${Format.bytesPerSecond(storage.readBytesPerSecond)} read, " +
            "${Format.bytesPerSecond(storage.writeBytesPerSecond)} write; " +
            "${Format.decimal(storage.writeOperationsPerSecond)} writes/s, " +
            "${Format.decimal(storage.writeServiceTimePercent)}% write service time; " +
            "${Format.bytes(storage.rootFileSystemAvailableBytes)} filesystem available"
    }

    private const val MAX_NOTIFICATION_TEXT = 1_000
}
