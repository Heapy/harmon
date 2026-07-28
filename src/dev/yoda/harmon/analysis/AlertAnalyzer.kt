package dev.yoda.harmon.analysis

import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.Severity
import dev.yoda.harmon.model.SystemUsage
import dev.yoda.harmon.util.Format

/**
 * What one sample's rules produced: the alerts a report carries, the keys the alert state has to
 * remember, and the keys the per-category cap kept out of the report.
 *
 * The three differ on purpose. [alerts] is capped per category so a report stays readable.
 * [suppressedKeys] is every key over its threshold that the cap dropped, whether or not it was
 * already alerting, so a report naming them cannot understate what is happening. [firingKeys] is
 * what `AlertState` must remember: the reported keys plus the already-active ones among the
 * suppressed. Dropping an active key from the state because a report had no room for it would look
 * like the alert clearing, and its next appearance in the top slice would push again as new.
 *
 * A key crossing its threshold for the first time below the cut is therefore in [suppressedKeys]
 * but not in [firingKeys]: it was never pushed, and putting it in the state would grade it against
 * the lowered clear threshold from the next sample on, so an application hovering just under the
 * threshold that crossed once would stay alerting indefinitely.
 */
data class AlertOutcome(
    val alerts: List<Alert>,
    val firingKeys: Set<String>,
    val suppressedKeys: Set<String>,
)

/**
 * Turns a usage sample into alerts.
 *
 * [analyze] takes the keys that were already firing on the previous sample. Such a key is
 * compared against a threshold lowered by [CLEAR_RATIO], so a value hovering around the
 * threshold does not flip the alert on and off; severity is still graded against the original
 * threshold. Low battery is excluded: it is the only rule comparing with "less than or equal",
 * where a lower threshold would drop the alert while its condition still holds.
 */
class AlertAnalyzer {
    fun analyze(
        usage: SystemUsage,
        config: HarmonConfig,
        activeKeys: Set<String> = emptySet(),
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
                .forEach { application ->
                    add(
                        Alert(
                            key = "cpu:${application.id}",
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
                .forEach { application ->
                    add(
                        Alert(
                            key = "memory:${application.id}",
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
                .forEach { application ->
                    add(
                        Alert(
                            key = "disk-write:${application.id}",
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
            val thresholdBytes = thresholdMiB.mebibytesToBytes()
            val alertThreshold = if ("swap" in activeKeys) {
                thresholdBytes.cleared()
            } else {
                thresholdBytes
            }
            if (usage.swap.usedBytes >= alertThreshold) {
                add(
                    Alert(
                        key = "swap",
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
            val thresholdBytesPerSecond = thresholdMiB * BYTES_PER_MEBIBYTE_DOUBLE
            val alertThreshold = if ("swap-out" in activeKeys) {
                thresholdBytesPerSecond.cleared()
            } else {
                thresholdBytesPerSecond
            }
            if (usage.virtualMemory.swapOutBytesPerSecond >= alertThreshold) {
                add(
                    Alert(
                        key = "swap-out",
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

        thresholds.applicationBatteryImpactScore?.let { threshold ->
            if (usage.power.onBattery) {
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
                    .forEach { application ->
                        add(
                            Alert(
                                key = "battery-impact:${application.id}",
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
    }

    /**
     * The [maxPerCategory] applications above the threshold with the highest [value].
     *
     * A key that did not survive the cut is collected into [suppressed] instead of being appended
     * to the result. It stays out of the alert list, where an uncapped tail makes a category
     * configured for three alerts carry dozens on a busy machine and never shrink, but it is still
     * over its threshold and the report has to name it — otherwise the overflow the report admits
     * to is smaller than the overflow that exists. Which of those keys the alert state keeps is
     * decided in [analyze]: only the ones that were already active.
     */
    private fun <R : Comparable<R>> List<ApplicationUsage>.selectAlerting(
        maxPerCategory: Int,
        activeKeys: Set<String>,
        suppressed: MutableSet<String>,
        key: (ApplicationUsage) -> String,
        value: (ApplicationUsage) -> R,
        threshold: R,
        clearThreshold: R,
    ): List<ApplicationUsage> {
        val ranked = asSequence()
            .filter { application ->
                val effective = if (key(application) in activeKeys) clearThreshold else threshold
                value(application) >= effective
            }
            .sortedByDescending(value)
            .toList()
        ranked.asSequence()
            .drop(maxPerCategory)
            .mapTo(suppressed, key)
        return ranked.take(maxPerCategory)
    }

    private fun Double.cleared(): Double =
        this * CLEAR_NUMERATOR.toDouble() / CLEAR_DENOMINATOR.toDouble()

    /** Dividing first keeps the product from overflowing. */
    private fun ULong.cleared(): ULong = this / CLEAR_DENOMINATOR * CLEAR_NUMERATOR

    /**
     * MiB to bytes, saturating at [ULong.MAX_VALUE]. Wrapping would turn a huge threshold into a
     * small one — at 2^44 MiB it wraps to zero and every application alerts as critical.
     * `ConfigLoader` keeps configured values far below that; this covers thresholds built in code.
     *
     * A non-positive threshold means every application is over it. It is folded to zero rather
     * than reinterpreted as unsigned, where -1 MiB would saturate instead and silently switch the
     * rule off — a nonsensical threshold has to be loud, not invisible.
     */
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

    /** The critical bound, saturating so an unreachable threshold does not wrap into zero. */
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

        /**
         * The hysteresis factor, as a fraction so the integer and the floating-point form cannot
         * drift apart: an active alert only clears below nine tenths of its threshold.
         */
        const val CLEAR_NUMERATOR: ULong = 9u
        const val CLEAR_DENOMINATOR: ULong = 10u
    }
}
