package dev.yoda.harmon.report

import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.MonitoringReport

/** Lazy, stable full-sort slices shared by text and JSON renderers. */
internal class ApplicationRankings(private val report: MonitoringReport) {
    val topCpu: List<ApplicationUsage> by lazy { rank { it.cpuPercent } }

    val topMemory: List<ApplicationUsage> by lazy { rank { it.physicalFootprintBytes } }

    val topBatteryImpact: List<ApplicationUsage> by lazy { rank { it.batteryImpactScore } }

    val topStorageWrites: List<ApplicationUsage> by lazy {
        rank(
            candidates = report.usage.applications.filter {
                it.diskWriteBytesPerSecond > 0.0 || it.logicalWriteBytesPerSecond > 0.0
            },
        ) { maxOf(it.diskWriteBytesPerSecond, it.logicalWriteBytesPerSecond) }
    }

    val topPhysicalWrites: List<ApplicationUsage> by lazy {
        rank(
            candidates = report.usage.applications.filter {
                it.diskWriteBytesPerSecond > 0.0
            },
        ) { it.diskWriteBytesPerSecond }
    }

    val topInternalLogicalWrites: List<ApplicationUsage> by lazy {
        rank(
            candidates = report.usage.applications.filter {
                it.logicalWriteBytesPerSecond > 0.0
            },
        ) { it.logicalWriteBytesPerSecond }
    }

    val topCompressedOrPagedOut: List<ApplicationUsage> by lazy {
        rank(
            candidates = report.usage.applications.filter {
                it.compressedAttributionProcessCount > 0
            },
        ) { it.compressedOrPagedOutBytes }
    }

    val topEnergy: List<ApplicationUsage> by lazy {
        rank(
            candidates = report.usage.applications.filter { it.energyWatts > 0.0 },
        ) { it.energyWatts }
    }

    private fun <R : Comparable<R>> rank(
        candidates: List<ApplicationUsage> = report.usage.applications,
        metric: (ApplicationUsage) -> R,
    ): List<ApplicationUsage> = candidates
        .sortedByDescending(metric)
        .take(report.topProcessCount)
}
