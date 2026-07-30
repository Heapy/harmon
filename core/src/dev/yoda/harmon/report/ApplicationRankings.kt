package dev.yoda.harmon.report

import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.MonitoringReport

/**
 * The ranked application slices of a single report: one definition of "the top applications by
 * this metric", computed per renderer, instead of the same filter and sort written out twice —
 * once in the text report and once in the JSON payload. The JSON payload's process slices stay
 * inline there because no other renderer shows them.
 *
 * `sortedByDescending` stays the selection mechanism on purpose: a hand-written partial selection
 * would save microseconds and could reorder applications whose metric ties, which is exactly what
 * the report must not do. Every slice is lazy, so rendering the text report alone still does not
 * pay for the slices only the JSON payload needs.
 */
internal class ApplicationRankings(private val report: MonitoringReport) {
    val topCpu: List<ApplicationUsage> by lazy { rank { it.cpuPercent } }

    val topMemory: List<ApplicationUsage> by lazy { rank { it.physicalFootprintBytes } }

    val topBatteryImpact: List<ApplicationUsage> by lazy { rank { it.batteryImpactScore } }

    /** Physical and logical writes in one list, the way the text report presents them. */
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
