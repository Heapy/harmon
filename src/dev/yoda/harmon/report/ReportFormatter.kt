package dev.yoda.harmon.report

import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.Severity
import dev.yoda.harmon.util.Format

object ReportFormatter {
    fun text(report: MonitoringReport): String = buildString {
        val usage = report.usage
        appendLine("Harmon sample at ${usage.capturedAt}")
        appendLine("Window: ${Format.decimal(usage.elapsedSeconds)}s")
        appendLine("Power: ${powerText(usage.power)}")
        appendLine(
            "Swap: ${Format.bytes(usage.swap.usedBytes)} used / " +
                "${Format.bytes(usage.swap.totalBytes)} allocated" +
                if (usage.swap.encrypted) " (encrypted)" else "",
        )
        appendLine(
            "Processes: ${usage.processes.size}/${usage.totalProcessCount} readable, " +
                "${usage.inaccessibleProcessCount} inaccessible",
        )
        appendLine(
            "Applications: ${usage.applications.size} groups from " +
                "${usage.processes.size} readable processes",
        )

        appendApplicationTable(
            heading = "Top application CPU",
            applications = usage.applications.sortedByDescending { it.cpuPercent },
            limit = report.topProcessCount,
            metric = { "${Format.decimal(it.cpuPercent)}%" },
        )
        appendApplicationTable(
            heading = "Top application memory",
            applications = usage.applications.sortedByDescending {
                it.physicalFootprintBytes
            },
            limit = report.topProcessCount,
            metric = { Format.bytes(it.physicalFootprintBytes) },
        )
        appendApplicationTable(
            heading = "Likely application battery impact",
            applications = usage.applications.sortedByDescending {
                it.batteryImpactScore
            },
            limit = report.topProcessCount,
            metric = { application ->
                "score ${Format.decimal(application.batteryImpactScore)}, " +
                    "${Format.decimal(application.wakeupsPerSecond)} wakeups/s, " +
                    "${Format.bytesPerSecond(
                        application.diskReadBytesPerSecond +
                            application.diskWriteBytesPerSecond,
                    )} I/O"
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
    }.trimEnd()

    fun notification(report: MonitoringReport): NotificationPayload {
        val alerts = report.alerts
        val title = when {
            alerts.any { it.severity == Severity.CRITICAL } -> "Harmon: critical alert"
            alerts.isNotEmpty() -> "Harmon: system warning"
            else -> "Harmon: system sample"
        }
        val subtitle = when (alerts.size) {
            0 -> powerText(report.usage.power)
            1 -> alerts.single().title
            else -> "${alerts.size} alerts"
        }
        val message = if (alerts.isEmpty()) {
            "Swap ${Format.bytes(report.usage.swap.usedBytes)}; " +
                "top CPU ${topCpuSummary(report)}"
        } else {
            alerts.joinToString(separator = "\n") { it.message }.take(MAX_NOTIFICATION_TEXT)
        }

        return NotificationPayload(
            title = title,
            subtitle = subtitle,
            text = message,
            json = json(report),
        )
    }

    fun json(report: MonitoringReport): String = ReportJson.encode(report)

    fun testPayload(): NotificationPayload = NotificationPayload(
        title = "Harmon",
        subtitle = "Notification test",
        text = "System monitoring notifications are configured correctly.",
        json = ReportJson.testEvent(),
    )

    private fun StringBuilder.appendApplicationTable(
        heading: String,
        applications: List<ApplicationUsage>,
        limit: Int,
        metric: (ApplicationUsage) -> String,
    ) {
        appendLine()
        appendLine("$heading:")
        applications.take(limit).forEachIndexed { index, application ->
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

    private const val MAX_NOTIFICATION_TEXT = 1_000
}
