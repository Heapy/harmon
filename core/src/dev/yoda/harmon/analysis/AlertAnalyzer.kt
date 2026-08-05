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
 * What one sample's rules produced: the capped [alerts] a report carries, every other key its rule
 * matched in [suppressedKeys] — over its threshold where the rule has one, orphaned where it has
 * none — and the [firingKeys] the alert state has to remember.
 *
 * [firingKeys] is the reported keys plus the already-active ones among the suppressed. Dropping an
 * active key because a report had no room for it would look like the alert clearing; admitting a
 * key that first crossed below the cut would grade it against the lowered clear threshold from the
 * next sample on, so an application hovering just under its threshold would stay alerting forever.
 */
data class AlertOutcome(
    val alerts: List<Alert>,
    val firingKeys: Set<String>,
    val suppressedKeys: Set<String>,
)

/**
 * Turns a usage sample into alerts.
 *
 * A key that was already firing is compared against a threshold lowered by [CLEAR_RATIO], so a
 * value hovering around the threshold does not flip the alert on and off; severity is still graded
 * against the original threshold. Low battery is excluded: it is the only rule comparing with
 * "less than or equal", where a lower threshold would drop the alert while its condition holds.
 */
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
     * One alert per process whose parent changed to [INIT_PID] since the previous sample.
     *
     * The rule reads a transition, not a state. [ProcessUsage.reparentedFrom] only says the parent
     * changed, so the `parentPid == INIT_PID` half of the condition belongs here, in the consumer.
     * A process first seen with pid 1 as its parent has no transition to report, which is what
     * keeps an ordinary double-forking daemon out of this list without any heuristic.
     *
     * Severity is always [Severity.WARNING]. Every other rule reads `CRITICAL` off a value at twice
     * its threshold; an event has no magnitude, so there is no reading of it that would be worse
     * than another.
     *
     * The cap is applied here instead of through [selectAlerting]: that helper is typed for
     * `ApplicationUsage` and ranks by a numeric metric, and orphanhood has none. Sorting by pid is
     * what makes the choice deterministic rather than dependent on sample order. Everything past
     * [maxPerCategory] goes to [suppressed], and there it is lost for good rather than deferred:
     * [analyze] keeps a suppressed key firing only when it was already active, a brand-new orphan
     * never was, so it never enters the alert state and its edge does not occur a second time.
     *
     * The key says `orphan` while the history column recording the same fact is `reparented_at`;
     * `Processes.sq` carries why the two vocabularies differ.
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

    /**
     * The [maxPerCategory] applications above the threshold with the highest [value], each paired
     * with its alert key, so a rule spells that key out once for both the selection and the [Alert]
     * built from it.
     *
     * A key that did not survive the cut goes to [suppressed] instead: an uncapped tail would make
     * a category configured for three alerts carry dozens on a busy machine, but the key is still
     * over its threshold and the report has to name it. Which of them the alert state keeps is
     * decided in [analyze].
     */
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
