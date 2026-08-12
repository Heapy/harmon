package dev.yoda.harmon.analysis

import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.INIT_PID
import dev.yoda.harmon.model.ProcessUsage
import dev.yoda.harmon.model.Severity
import dev.yoda.harmon.model.SystemUsage
import dev.yoda.harmon.util.Format

/**
 * [firingKeys] retains capped-out active alerts but excludes newly suppressed alerts. This
 * preserves hysteresis without admitting an alert that was never reported.
 */
data class AlertOutcome(
    val alerts: List<Alert>,
    val firingKeys: Set<String>,
    val suppressedKeys: Set<String>,
)

/** Applies clear-threshold hysteresis to active rules; low battery is excluded because lower is worse. */
class AlertAnalyzer {
    fun analyze(
        usage: SystemUsage,
        config: HarmonConfig,
        activeKeys: Set<String>,
    ): AlertOutcome {
        val suppressed = mutableSetOf<String>()
        val alerts = alertsFor(usage, config, activeKeys, suppressed)
        return AlertOutcome(
            alerts = alerts,
            firingKeys = alerts.mapTo(mutableSetOf()) { it.key } +
                (suppressed intersect activeKeys),
            suppressedKeys = suppressed,
        )
    }

    private fun alertsFor(
        usage: SystemUsage,
        config: HarmonConfig,
        activeKeys: Set<String>,
        suppressed: MutableSet<String>,
    ): List<Alert> = buildList {
        val thresholds = config.thresholds
        thresholds.applicationCpuPercent?.let { threshold ->
            usage.applications
                .selectAlerting(
                    maxPerCategory = config.maxAlertsPerCategory,
                    activeKeys = activeKeys,
                    suppressed = suppressed,
                    key = { "cpu:${it.id}" },
                    value = { it.cpuPercent },
                    threshold = threshold,
                    clearThreshold = threshold.cleared(),
                )
                .forEach { (key, application) ->
                    add(
                        Alert(
                            key = key,
                            severity = if (application.cpuPercent >= threshold * 2) {
                                Severity.CRITICAL
                            } else {
                                Severity.WARNING
                            },
                            title = "High application CPU",
                            message = "${application.alertLabel()} uses " +
                                "${Format.decimal(application.cpuPercent)}% CPU",
                        ),
                    )
                }
        }

        thresholds.applicationMemoryMiB?.let { thresholdMiB ->
            val thresholdBytes = thresholdMiB.mebibytesToBytes()
            usage.applications
                .selectAlerting(
                    maxPerCategory = config.maxAlertsPerCategory,
                    activeKeys = activeKeys,
                    suppressed = suppressed,
                    key = { "memory:${it.id}" },
                    value = { it.physicalFootprintBytes },
                    threshold = thresholdBytes,
                    clearThreshold = thresholdBytes.cleared(),
                )
                .forEach { (key, application) ->
                    add(
                        Alert(
                            key = key,
                            severity = if (
                                application.physicalFootprintBytes >= thresholdBytes.doubled()
                            ) {
                                Severity.CRITICAL
                            } else {
                                Severity.WARNING
                            },
                            title = "High application memory",
                            message = "${application.alertLabel()} uses " +
                                "${Format.bytes(application.physicalFootprintBytes)} memory",
                        ),
                    )
                }
        }

        thresholds.applicationDiskWriteMiBPerSecond?.let { thresholdMiB ->
            val thresholdBytesPerSecond = thresholdMiB * BYTES_PER_MEBIBYTE_DOUBLE
            usage.applications
                .selectAlerting(
                    maxPerCategory = config.maxAlertsPerCategory,
                    activeKeys = activeKeys,
                    suppressed = suppressed,
                    key = { "disk-write:${it.id}" },
                    value = { it.diskWriteBytesPerSecond },
                    threshold = thresholdBytesPerSecond,
                    clearThreshold = thresholdBytesPerSecond.cleared(),
                )
                .forEach { (key, application) ->
                    add(
                        Alert(
                            key = key,
                            severity = if (
                                application.diskWriteBytesPerSecond >=
                                thresholdBytesPerSecond * 2.0
                            ) {
                                Severity.CRITICAL
                            } else {
                                Severity.WARNING
                            },
                            title = "High application storage writes",
                            message = "${application.alertLabel()} writes " +
                                "${Format.bytesPerSecond(
                                    application.diskWriteBytesPerSecond,
                                )} to physical storage",
                        ),
                    )
                }
        }

        thresholds.swapUsedMiB?.let { thresholdMiB ->
            val key = "swap"
            val thresholdBytes = thresholdMiB.mebibytesToBytes()
            val alertThreshold = if (key in activeKeys) {
                thresholdBytes.cleared()
            } else {
                thresholdBytes
            }
            if (usage.swap.usedBytes >= alertThreshold) {
                add(
                    Alert(
                        key = key,
                        severity = if (usage.swap.usedBytes >= thresholdBytes.doubled()) {
                            Severity.CRITICAL
                        } else {
                            Severity.WARNING
                        },
                        title = "High swap usage",
                        message = "${Format.bytes(usage.swap.usedBytes)} of swap is in use",
                    ),
                )
            }
        }

        thresholds.swapOutMiBPerSecond?.let { thresholdMiB ->
            val key = "swap-out"
            val thresholdBytesPerSecond = thresholdMiB * BYTES_PER_MEBIBYTE_DOUBLE
            val alertThreshold = if (key in activeKeys) {
                thresholdBytesPerSecond.cleared()
            } else {
                thresholdBytesPerSecond
            }
            if (usage.virtualMemory.swapOutBytesPerSecond >= alertThreshold) {
                add(
                    Alert(
                        key = key,
                        severity = if (
                            usage.virtualMemory.swapOutBytesPerSecond >=
                            thresholdBytesPerSecond * 2.0
                        ) {
                            Severity.CRITICAL
                        } else {
                            Severity.WARNING
                        },
                        title = "High swap-out traffic",
                        message = "macOS is writing " +
                            "${Format.bytesPerSecond(
                                usage.virtualMemory.swapOutBytesPerSecond,
                            )} to swap",
                    ),
                )
            }
        }

        // A threshold disables only its own counter-availability regime; the two never fall back.
        if (usage.power.onBattery) {
            if (usage.energyAccounted) {
                thresholds.applicationPowerWatts?.let { threshold ->
                    usage.applications
                        .selectAlerting(
                            maxPerCategory = config.maxAlertsPerCategory,
                            activeKeys = activeKeys,
                            suppressed = suppressed,
                            key = { "power:${it.id}" },
                            value = { it.energyWatts },
                            threshold = threshold,
                            clearThreshold = threshold.cleared(),
                        )
                        .forEach { (key, application) ->
                            add(
                                Alert(
                                    key = key,
                                    severity = if (application.energyWatts >= threshold * 2) {
                                        Severity.CRITICAL
                                    } else {
                                        Severity.WARNING
                                    },
                                    title = "Likely battery drain",
                                    message = "${application.alertLabel()} draws " +
                                        Format.power(application.energyWatts),
                                ),
                            )
                        }
                }
            } else {
                thresholds.applicationBatteryImpactScore?.let { threshold ->
                    usage.applications
                        .selectAlerting(
                            maxPerCategory = config.maxAlertsPerCategory,
                            activeKeys = activeKeys,
                            suppressed = suppressed,
                            key = { "battery-impact:${it.id}" },
                            value = { it.batteryImpactScore },
                            threshold = threshold,
                            clearThreshold = threshold.cleared(),
                        )
                        .forEach { (key, application) ->
                            add(
                                Alert(
                                    key = key,
                                    severity = if (
                                        application.batteryImpactScore >= threshold * 2
                                    ) {
                                        Severity.CRITICAL
                                    } else {
                                        Severity.WARNING
                                    },
                                    title = "Likely battery drain",
                                    message = "${application.alertLabel()} has impact score " +
                                        Format.decimal(application.batteryImpactScore),
                                ),
                            )
                        }
                }
            }
        }

        thresholds.batteryLowPercent?.let { threshold ->
            val percentage = usage.power.percentage
            if (
                usage.power.batteryAvailable &&
                usage.power.onBattery &&
                percentage != null &&
                percentage <= threshold
            ) {
                add(
                    Alert(
                        key = "battery-low",
                        severity = if (percentage <= 10) {
                            Severity.CRITICAL
                        } else {
                            Severity.WARNING
                        },
                        title = "Low battery",
                        message = "Battery is at $percentage%",
                    ),
                )
            }
        }

        if (config.orphanAlerts) {
            addAll(
                orphanAlertsFor(
                    processes = usage.processes,
                    maxPerCategory = config.maxAlertsPerCategory,
                    suppressed = suppressed,
                ),
            )
        }
    }

    /**
     * Reports only transitions to launchd, so first-seen daemons stay quiet. PID ordering makes
     * capped selection deterministic; suppressed one-shot events are intentionally not deferred.
     */
    private fun orphanAlertsFor(
        processes: List<ProcessUsage>,
        maxPerCategory: Int,
        suppressed: MutableSet<String>,
    ): List<Alert> {
        val orphaned = processes
            .mapNotNull { process ->
                if (process.parentPid != INIT_PID) return@mapNotNull null
                val lostParent = process.reparentedFrom ?: return@mapNotNull null
                process to lostParent
            }
            .sortedBy { (process, _) -> process.identity.pid }
        orphaned.asSequence()
            .drop(maxPerCategory)
            .mapTo(suppressed) { (process, _) -> process.orphanKey() }
        return orphaned.take(maxPerCategory).map { (process, parent) ->
            val parentLabel = parent.name?.let { "$it " }.orEmpty()
            Alert(
                key = process.orphanKey(),
                severity = Severity.WARNING,
                title = "Orphaned process",
                message = "${process.name} (pid ${process.identity.pid}) lost its parent " +
                    "$parentLabel(pid ${parent.pid})",
            )
        }
    }

    private fun ProcessUsage.orphanKey(): String =
        "orphan:process:${identity.pid}:${identity.startedAt}"

    /** Returns the highest matching values and records capped-out keys for report/state reconciliation. */
    private fun <R : Comparable<R>> List<ApplicationUsage>.selectAlerting(
        maxPerCategory: Int,
        activeKeys: Set<String>,
        suppressed: MutableSet<String>,
        key: (ApplicationUsage) -> String,
        value: (ApplicationUsage) -> R,
        threshold: R,
        clearThreshold: R,
    ): List<Pair<String, ApplicationUsage>> {
        val ranked = asSequence()
            .map { application -> key(application) to application }
            .filter { (alertKey, application) ->
                val effective = if (alertKey in activeKeys) clearThreshold else threshold
                value(application) >= effective
            }
            .sortedByDescending { (_, application) -> value(application) }
            .toList()
        ranked.asSequence()
            .drop(maxPerCategory)
            .mapTo(suppressed) { (alertKey, _) -> alertKey }
        return ranked.take(maxPerCategory)
    }

    private fun Double.cleared(): Double =
        this * CLEAR_NUMERATOR.toDouble() / CLEAR_DENOMINATOR.toDouble()

    /** Dividing first prevents the hysteresis product from overflowing. */
    private fun ULong.cleared(): ULong = this / CLEAR_DENOMINATOR * CLEAR_NUMERATOR

    /** Saturates instead of letting a huge threshold wrap into a small one; non-positive means zero. */
    private fun Long.mebibytesToBytes(): ULong {
        if (this <= 0L) {
            return 0uL
        }
        val mebibytes = toULong()
        return if (mebibytes > ULong.MAX_VALUE / BYTES_PER_MEBIBYTE) {
            ULong.MAX_VALUE
        } else {
            mebibytes * BYTES_PER_MEBIBYTE
        }
    }

    /** Saturates the critical threshold instead of wrapping it to zero. */
    private fun ULong.doubled(): ULong =
        if (this > ULong.MAX_VALUE / 2u) ULong.MAX_VALUE else this * 2u

    private fun ApplicationUsage.alertLabel(): String =
        if (processCount == 1) {
            "$name (PID $rootPid)"
        } else {
            "$name ($processCount processes)"
        }

    private companion object {
        const val BYTES_PER_MEBIBYTE: ULong = 1_048_576u
        const val BYTES_PER_MEBIBYTE_DOUBLE = 1_048_576.0

        /** Shared fraction keeps integer and floating-point hysteresis identical. */
        const val CLEAR_NUMERATOR: ULong = 9u
        const val CLEAR_DENOMINATOR: ULong = 10u
    }
}
