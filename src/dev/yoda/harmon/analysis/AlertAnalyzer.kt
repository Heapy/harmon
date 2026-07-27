package dev.yoda.harmon.analysis

import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.Severity
import dev.yoda.harmon.model.SystemUsage
import dev.yoda.harmon.util.Format

class AlertAnalyzer {
    fun analyze(usage: SystemUsage, config: HarmonConfig): List<Alert> = buildList {
        val thresholds = config.thresholds
        thresholds.applicationCpuPercent?.let { threshold ->
            usage.applications
                .asSequence()
                .filter { it.cpuPercent >= threshold }
                .sortedByDescending { it.cpuPercent }
                .take(config.maxAlertsPerCategory)
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
            val thresholdBytes = thresholdMiB.toULong() * BYTES_PER_MEBIBYTE
            usage.applications
                .asSequence()
                .filter { it.physicalFootprintBytes >= thresholdBytes }
                .sortedByDescending { it.physicalFootprintBytes }
                .take(config.maxAlertsPerCategory)
                .forEach { application ->
                    add(
                        Alert(
                            key = "memory:${application.id}",
                            severity = if (
                                application.physicalFootprintBytes >= thresholdBytes * 2u
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
                .asSequence()
                .filter { it.diskWriteBytesPerSecond >= thresholdBytesPerSecond }
                .sortedByDescending { it.diskWriteBytesPerSecond }
                .take(config.maxAlertsPerCategory)
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
            val thresholdBytes = thresholdMiB.toULong() * BYTES_PER_MEBIBYTE
            if (usage.swap.usedBytes >= thresholdBytes) {
                add(
                    Alert(
                        key = "swap",
                        severity = if (usage.swap.usedBytes >= thresholdBytes * 2u) {
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
            if (usage.virtualMemory.swapOutBytesPerSecond >= thresholdBytesPerSecond) {
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
                    .asSequence()
                    .filter { it.batteryImpactScore >= threshold }
                    .sortedByDescending { it.batteryImpactScore }
                    .take(config.maxAlertsPerCategory)
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

    private fun ApplicationUsage.alertLabel(): String =
        if (processCount == 1) {
            "$name (PID $rootPid)"
        } else {
            "$name ($processCount processes)"
        }

    private companion object {
        const val BYTES_PER_MEBIBYTE: ULong = 1_048_576u
        const val BYTES_PER_MEBIBYTE_DOUBLE = 1_048_576.0
    }
}
