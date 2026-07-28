import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.config.AlertThresholds
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertAnalyzerTest {
    @Test
    fun reportsCpuMemorySwapBatteryImpactAndLowBattery() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(
                    name = "hungry",
                    cpuPercent = 220.0,
                    footprint = 3uL * 1_073_741_824uL,
                    impact = 130.0,
                ),
            ),
            swapUsed = 2uL * 1_073_741_824uL,
            batteryPercentage = 15,
        )

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig()).alerts
        val keys = alerts.map { it.key.substringBefore(':') }.toSet()

        assertEquals(5, alerts.size)
        assertTrue("cpu" in keys)
        assertTrue("memory" in keys)
        assertTrue("swap" in keys)
        assertTrue("battery-impact" in keys)
        assertTrue("battery-low" in keys)
    }

    @Test
    fun evaluatesThresholdsAgainstTheWholeApplicationGroup() {
        val firefoxPath = "/Applications/Firefox.app/Contents/MacOS/"
        val usage = systemUsage(
            processes = listOf(
                processUsage(
                    pid = 100,
                    name = "firefox",
                    executablePath = "${firefoxPath}firefox",
                    cpuPercent = 80.0,
                    footprint = 1_100uL * 1_048_576uL,
                    impact = 60.0,
                ),
                processUsage(
                    pid = 101,
                    parentPid = 100,
                    name = "plugin-container",
                    executablePath = "${firefoxPath}plugin-container.app/" +
                        "Contents/MacOS/plugin-container",
                    cpuPercent = 90.0,
                    footprint = 1_100uL * 1_048_576uL,
                    impact = 60.0,
                ),
            ),
        )

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig()).alerts

        assertEquals(3, alerts.size)
        assertTrue(alerts.all { "Firefox (2 processes)" in it.message })
    }

    @Test
    fun alertsOnSustainedPhysicalWritesAndSwapOutTraffic() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(
                    name = "writer",
                    diskWriteBytesPerSecond = 60.0 * 1_048_576.0,
                ),
            ),
            swapOutBytesPerSecond = 30.0 * 1_048_576.0,
        )

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig()).alerts

        assertTrue(alerts.any { it.key.startsWith("disk-write:") })
        assertTrue(alerts.any { it.key == "swap-out" })
    }

    @Test
    fun holdsAlertBetweenClearRatioAndThresholdWhenKeyIsActive() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 140.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(CPU_KEY)).alerts

        assertEquals(listOf(CPU_KEY), alerts.map { it.key })
    }

    @Test
    fun doesNotRaiseAlertBelowThresholdWhenKeyIsNotActive() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 140.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig()).alerts

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun clearsAlertBelowClearRatioEvenWhenKeyIsActive() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 130.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(CPU_KEY)).alerts

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun gradesSeverityAgainstTheOriginalThresholdNotTheLoweredOne() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 280.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(CPU_KEY)).alerts

        assertEquals(Severity.WARNING, alerts.single { it.key == CPU_KEY }.severity)
    }

    @Test
    fun keepsLowBatteryAlertBecauseHysteresisIsNotAppliedToIt() {
        val usage = systemUsage(processes = emptyList(), batteryPercentage = 19)

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf("battery-low")).alerts

        assertEquals(Severity.WARNING, alerts.single { it.key == "battery-low" }.severity)
    }

    @Test
    fun raisesNoBatteryAlertsOnAMachineWithoutABattery() {
        val sampled = systemUsage(processes = emptyList(), batteryPercentage = 5)
        val usage = sampled.copy(
            power = sampled.power.copy(
                batteryAvailable = false,
                onBattery = false,
                percentage = null,
                minutesRemaining = null,
            ),
        )

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf("battery-low")).alerts

        assertEquals(emptyList(), alerts.filter { it.key.startsWith("battery") })
    }

    /**
     * The two system-wide rules spell their hysteresis out by hand, with the alert key repeated
     * as a literal next to the `Alert(key = …)` it has to match. Nothing but a test notices when
     * the two drift apart.
     */
    @Test
    fun holdsTheSwapAlertBetweenItsClearRatioAndItsThresholdOnlyWhileActive() {
        val usage = systemUsage(
            processes = emptyList(),
            swapUsed = 980uL * 1_048_576uL,
        )
        val analyzer = AlertAnalyzer()

        assertEquals(
            listOf("swap"),
            analyzer.analyze(usage, HarmonConfig(), setOf("swap")).alerts.map { it.key },
        )
        assertTrue(analyzer.analyze(usage, HarmonConfig()).alerts.none { it.key == "swap" })
    }

    @Test
    fun holdsTheSwapOutAlertBetweenItsClearRatioAndItsThresholdOnlyWhileActive() {
        val usage = systemUsage(
            processes = emptyList(),
            swapOutBytesPerSecond = 23.0 * 1_048_576.0,
        )
        val analyzer = AlertAnalyzer()

        assertEquals(
            listOf("swap-out"),
            analyzer.analyze(usage, HarmonConfig(), setOf("swap-out")).alerts.map { it.key },
        )
        assertTrue(analyzer.analyze(usage, HarmonConfig()).alerts.none { it.key == "swap-out" })
    }

    @Test
    fun gradesEveryRuleAsCriticalAtTwiceItsThreshold() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(
                    name = "runaway",
                    cpuPercent = 320.0,
                    footprint = 5_000uL * 1_048_576uL,
                    diskWriteBytesPerSecond = 120.0 * 1_048_576.0,
                    impact = 260.0,
                ),
            ),
            swapUsed = 4uL * 1_073_741_824uL,
            swapOutBytesPerSecond = 60.0 * 1_048_576.0,
            batteryPercentage = 8,
        )

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig()).alerts

        assertEquals(
            listOf(
                "battery-impact",
                "battery-low",
                "cpu",
                "disk-write",
                "memory",
                "swap",
                "swap-out",
            ),
            alerts.map { it.key.substringBefore(':') }.sorted(),
        )
        assertTrue(
            alerts.all { it.severity == Severity.CRITICAL },
            alerts.filterNot { it.severity == Severity.CRITICAL }.toString(),
        )
    }

    /**
     * An active key pushed out of the top slice stays in the firing set even though no report
     * carries it. Dropping it would leave the alert state, and its return to the slice would look
     * like a fresh alert and push again.
     */
    @Test
    fun keepsActiveKeyThatFellOutOfTheTopSliceFiringWithoutReportingIt() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(pid = 1, cpuPercent = 400.0),
                processUsage(pid = 2, cpuPercent = 300.0),
                processUsage(pid = 3, cpuPercent = 200.0),
                processUsage(pid = 4, cpuPercent = 160.0),
            ),
        )
        val demotedKey = "cpu:process:4:4"

        val outcome = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(demotedKey))

        assertEquals(3, outcome.alerts.size)
        assertTrue(demotedKey !in outcome.alerts.map { it.key })
        assertTrue(demotedKey in outcome.firingKeys, outcome.firingKeys.toString())
        assertEquals(setOf(demotedKey), outcome.suppressedKeys)
    }

    /**
     * The overflow a report admits to has to be the whole overflow, so a key crossing its
     * threshold for the first time below the cut is suppressed, not dropped silently. It still
     * stays out of the firing set: it was never pushed, and giving it the lowered clear threshold
     * from the next sample on would keep an application hovering just under the threshold alerting
     * indefinitely.
     */
    @Test
    fun suppressesAKeyOverTheThresholdForTheFirstTimeWithoutMakingItFire() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(pid = 1, cpuPercent = 400.0),
                processUsage(pid = 2, cpuPercent = 300.0),
                processUsage(pid = 3, cpuPercent = 200.0),
                processUsage(pid = 4, cpuPercent = 160.0),
            ),
        )
        val rankedOutKey = "cpu:process:4:4"

        val outcome = AlertAnalyzer().analyze(usage, HarmonConfig())

        assertEquals(3, outcome.alerts.size)
        assertEquals(setOf(rankedOutKey), outcome.suppressedKeys)
        assertTrue(rankedOutKey !in outcome.firingKeys, outcome.firingKeys.toString())
    }

    /**
     * The two sides of the split, on the case that motivates it: the firing set has no ceiling of
     * its own, because dropping an active key from it causes a spurious repeat push, while the
     * reported list stays at `maxAlertsPerCategory` — an uncapped report grows with every busy
     * sample and never shrinks.
     */
    @Test
    fun reportsAtMostTheCategoryCapWhileKeepingEveryActiveKeyFiring() {
        val alerting = (1..7).map { index ->
            processUsage(pid = index, cpuPercent = 400.0 - index * 10.0)
        }
        val usage = systemUsage(processes = alerting)
        val demoted = (4..7).map { pid -> "cpu:process:$pid:$pid" }.toSet()

        val outcome = AlertAnalyzer()
            .analyze(usage, HarmonConfig(maxAlertsPerCategory = 3), demoted)

        assertEquals(3, outcome.alerts.size)
        assertEquals(7, outcome.firingKeys.size)
        assertTrue(outcome.firingKeys.containsAll(demoted), outcome.firingKeys.toString())
        assertEquals(demoted, outcome.suppressedKeys)
    }

    /**
     * `ConfigLoader` rejects a negative threshold from a file, but one built in code reaches this
     * conversion directly. Reinterpreting it as unsigned would saturate and switch the rule off
     * without a word; folding it to zero makes the mistake fire instead of vanish.
     */
    @Test
    fun treatsANegativeMemoryThresholdAsZeroRatherThanAsUnreachable() {
        val usage = systemUsage(processes = listOf(processUsage()))

        val alerts = AlertAnalyzer().analyze(usage, onlyMemoryThreshold(-1)).alerts

        assertEquals(listOf("memory:process:42:42"), alerts.map { it.key })
    }

    @Test
    fun doesNotTurnAnOverflowingMemoryThresholdIntoAnAlwaysFiringAlert() {
        val usage = systemUsage(processes = listOf(processUsage()))

        val analyzer = AlertAnalyzer()

        assertEquals(
            listOf("memory:process:42:42"),
            analyzer.analyze(usage, onlyMemoryThreshold(256)).alerts.map { it.key },
        )
        assertEquals(
            emptyList(),
            analyzer.analyze(usage, onlyMemoryThreshold(OVERFLOWING_MIB)).alerts,
        )
    }

    @Test
    fun doesNotTurnAnOverflowingSwapThresholdIntoAnAlwaysFiringAlert() {
        val usage = systemUsage(
            processes = emptyList(),
            swapUsed = 2uL * 1_073_741_824uL,
        )

        val analyzer = AlertAnalyzer()

        assertEquals(
            listOf("swap"),
            analyzer.analyze(usage, onlySwapThreshold(1_024)).alerts.map { it.key },
        )
        assertEquals(
            emptyList(),
            analyzer.analyze(usage, onlySwapThreshold(OVERFLOWING_MIB)).alerts,
        )
    }

    @Test
    fun doesNotOverflowTheDoubledThresholdWhenGradingSeverity() {
        val usage = systemUsage(processes = listOf(processUsage(footprint = 1uL shl 63)))

        val alerts = AlertAnalyzer().analyze(usage, onlyMemoryThreshold(1L shl 43)).alerts

        assertEquals(Severity.WARNING, alerts.single().severity)
    }

    private companion object {
        const val CPU_KEY = "cpu:process:42:42"

        /** 2^44 MiB: the byte value wraps to zero without a saturating conversion. */
        const val OVERFLOWING_MIB = 1L shl 44

        fun onlyMemoryThreshold(mib: Long): HarmonConfig =
            singleThreshold(applicationMemoryMiB = mib)

        fun onlySwapThreshold(mib: Long): HarmonConfig = singleThreshold(swapUsedMiB = mib)

        /** Every other rule disabled, so a test observes exactly the rule it enables. */
        fun singleThreshold(
            applicationMemoryMiB: Long? = null,
            swapUsedMiB: Long? = null,
        ): HarmonConfig = HarmonConfig(
            thresholds = AlertThresholds(
                applicationCpuPercent = null,
                applicationMemoryMiB = applicationMemoryMiB,
                applicationDiskWriteMiBPerSecond = null,
                swapUsedMiB = swapUsedMiB,
                swapOutMiBPerSecond = null,
                applicationBatteryImpactScore = null,
                batteryLowPercent = null,
            ),
        )
    }
}
