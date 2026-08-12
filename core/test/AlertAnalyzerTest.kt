import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.config.AlertThresholds
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.model.ReparentedFrom
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

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts
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

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts

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

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts

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

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts

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

    @Test
    fun alertsOnAccountedWattsWhereTheEnergyCounterIsLive() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 2.0, impact = 4.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts
        val alert = alerts.single()

        assertEquals("power:process:42:42", alert.key)
        assertEquals(Severity.WARNING, alert.severity)
        assertEquals("Likely battery drain", alert.title)
        assertEquals("example (PID 42) draws 2.0 W", alert.message)
    }

    @Test
    fun raisesNoBatteryDrainAlertBelowTheWattThreshold() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 1.0, impact = 260.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts

        assertEquals(emptyList(), alerts)
    }

    @Test
    fun fallsBackToTheHeuristicScoreWhereTheEnergyCounterIsDead() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 0.0, impact = 130.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts
        val alert = alerts.single()

        assertEquals("battery-impact:process:42:42", alert.key)
        assertEquals("Likely battery drain", alert.title)
        assertEquals("example (PID 42) has impact score 130.0", alert.message)
    }

    @Test
    fun raisesNoBatteryDrainAlertInEitherRegimeWhileOnWallPower() {
        val analyzer = AlertAnalyzer()

        listOf(
            processUsage(energyWatts = 3.0, impact = 4.0),
            processUsage(energyWatts = 0.0, impact = 260.0),
        ).forEach { process ->
            val sampled = systemUsage(processes = listOf(process))
            val usage = sampled.copy(power = sampled.power.copy(onBattery = false, charging = true))

            assertEquals(
                emptyList(),
                analyzer.analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts,
                process.toString(),
            )
        }
    }

    /**
     * The two thresholds govern one regime each. Nesting the watt rule inside the score threshold's
     * `?.let` would tie them together, because `optionalPositiveDouble` turns a configured `0` into
     * a `null`.
     */
    @Test
    fun doesNotFallBackToTheScoreWhenTheWattThresholdIsDisabled() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 3.0, impact = 260.0)))

        val alerts = AlertAnalyzer()
            .analyze(usage, batteryRegimeThresholds(watts = null), activeKeys = emptySet())
            .alerts

        assertEquals(emptyList(), alerts)
    }

    @Test
    fun alertsOnWattsWhileTheScoreThresholdIsDisabled() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 3.0, impact = 260.0)))

        val alerts = AlertAnalyzer()
            .analyze(usage, batteryRegimeThresholds(score = null), activeKeys = emptySet())
            .alerts

        assertEquals(listOf("power:process:42:42"), alerts.map { it.key })
    }

    @Test
    fun gradesTheWattRuleAsCriticalAtTwiceItsThreshold() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 3.0, impact = 4.0)))

        val alert = AlertAnalyzer()
            .analyze(usage, HarmonConfig(), activeKeys = emptySet())
            .alerts
            .single()

        assertEquals(Severity.CRITICAL, alert.severity)
        assertEquals("example (PID 42) draws 3.0 W", alert.message)
    }

    /** The comparison is `>=`, so the configured watts are over the line rather than under it. */
    @Test
    fun alertsAtExactlyTheWattThreshold() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 1.5)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts

        assertEquals(listOf("power:process:42:42"), alerts.map { it.key })
        assertEquals(Severity.WARNING, alerts.single().severity)
    }

    /**
     * The watt rule clears at nine tenths of its threshold like every other application rule, so an
     * application hovering between 1.35 W and 1.5 W holds one alert instead of pushing a fresh one
     * on every sample it crosses back over. Only an already-active key is graded against the
     * lowered bound.
     */
    @Test
    fun holdsThePowerAlertBetweenItsClearRatioAndItsThresholdOnlyWhileActive() {
        val usage = systemUsage(processes = listOf(processUsage(energyWatts = 1.4)))
        val analyzer = AlertAnalyzer()

        assertEquals(
            listOf(POWER_KEY),
            analyzer.analyze(usage, HarmonConfig(), setOf(POWER_KEY)).alerts.map { it.key },
        )
        assertEquals(
            emptyList(),
            analyzer.analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts,
        )
    }

    /**
     * The regime belongs to the sample, so a counter falling silent between two samples clears one
     * key and raises the other for the same application — and the only way to say that is to run
     * the second sample with the first one's state.
     *
     * The `power:` key is handed back as `activeKeys` and must appear in neither the alerts nor the
     * firing set: a rule evaluating both branches would report it beside the score key, and a state
     * retaining a key no rule matched would leave it firing with nothing left to clear it.
     */
    @Test
    fun clearsTheWattKeyWhenTheCounterFallsSilentBetweenSamples() {
        val analyzer = AlertAnalyzer()
        val accounted = systemUsage(
            processes = listOf(processUsage(energyWatts = 2.0, impact = 260.0)),
        )

        val first = analyzer.analyze(accounted, HarmonConfig(), activeKeys = emptySet())

        assertEquals(listOf(POWER_KEY), first.alerts.map { it.key })
        assertEquals(setOf(POWER_KEY), first.firingKeys)

        val silent = systemUsage(processes = listOf(processUsage(impact = 260.0)))

        val second = analyzer.analyze(silent, HarmonConfig(), activeKeys = first.firingKeys)

        assertEquals(listOf(BATTERY_IMPACT_KEY), second.alerts.map { it.key })
        assertEquals(setOf(BATTERY_IMPACT_KEY), second.firingKeys)
        assertEquals(emptySet(), second.suppressedKeys)
    }

    /** The two system-wide rules spell their hysteresis out by hand, one rule at a time. */
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
        assertTrue(
            analyzer.analyze(usage, HarmonConfig(), activeKeys = emptySet())
                .alerts
                .none { it.key == "swap" },
        )
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
        assertTrue(
            analyzer.analyze(usage, HarmonConfig(), activeKeys = emptySet())
                .alerts
                .none { it.key == "swap-out" },
        )
    }

    /**
     * Every rule that has a magnitude, and both battery regimes, because the watt rule and the
     * score rule are one rule with two thresholds and cannot both fire for one sample: the eighth
     * key only appears in the pass whose counter is alive. That pass is also the only place the
     * watt rule is seen beside the other rules rather than alone in the list.
     *
     * `orphan` is in neither pass. It reads an event, has no threshold to double, and is always a
     * warning.
     */
    @Test
    fun gradesEveryRuleAsCriticalAtTwiceItsThreshold() {
        listOf(0.0 to "battery-impact", 4.0 to "power").forEach { (energyWatts, batteryKey) ->
            val usage = systemUsage(
                processes = listOf(
                    processUsage(
                        name = "runaway",
                        cpuPercent = 320.0,
                        footprint = 5_000uL * 1_048_576uL,
                        diskWriteBytesPerSecond = 120.0 * 1_048_576.0,
                        energyWatts = energyWatts,
                        impact = 260.0,
                    ),
                ),
                swapUsed = 4uL * 1_073_741_824uL,
                swapOutBytesPerSecond = 60.0 * 1_048_576.0,
                batteryPercentage = 8,
            )

            val alerts = AlertAnalyzer()
                .analyze(usage, HarmonConfig(), activeKeys = emptySet())
                .alerts

            assertEquals(
                listOf(
                    batteryKey,
                    "battery-low",
                    "cpu",
                    "disk-write",
                    "memory",
                    "swap",
                    "swap-out",
                ).sorted(),
                alerts.map { it.key.substringBefore(':') }.sorted(),
                batteryKey,
            )
            assertTrue(
                alerts.all { it.severity == Severity.CRITICAL },
                alerts.filterNot { it.severity == Severity.CRITICAL }.toString(),
            )
        }
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

        val outcome = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet())

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

        val alerts = AlertAnalyzer()
            .analyze(usage, onlyMemoryThreshold(-1), activeKeys = emptySet())
            .alerts

        assertEquals(listOf("memory:process:42:42"), alerts.map { it.key })
    }

    @Test
    fun doesNotTurnAnOverflowingMemoryThresholdIntoAnAlwaysFiringAlert() {
        val usage = systemUsage(processes = listOf(processUsage()))

        val analyzer = AlertAnalyzer()

        assertEquals(
            listOf("memory:process:42:42"),
            analyzer
                .analyze(usage, onlyMemoryThreshold(256), activeKeys = emptySet())
                .alerts
                .map { it.key },
        )
        assertEquals(
            emptyList(),
            analyzer
                .analyze(usage, onlyMemoryThreshold(OVERFLOWING_MIB), activeKeys = emptySet())
                .alerts,
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
            analyzer
                .analyze(usage, onlySwapThreshold(1_024), activeKeys = emptySet())
                .alerts
                .map { it.key },
        )
        assertEquals(
            emptyList(),
            analyzer
                .analyze(usage, onlySwapThreshold(OVERFLOWING_MIB), activeKeys = emptySet())
                .alerts,
        )
    }

    @Test
    fun doesNotOverflowTheDoubledThresholdWhenGradingSeverity() {
        val usage = systemUsage(processes = listOf(processUsage(footprint = 1uL shl 63)))

        val alerts = AlertAnalyzer()
            .analyze(usage, onlyMemoryThreshold(1L shl 43), activeKeys = emptySet())
            .alerts

        assertEquals(Severity.WARNING, alerts.single().severity)
    }

    @Test
    fun reportsAProcessWhoseParentBecamePidOne() {
        val usage = systemUsage(processes = listOf(orphan()))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts

        val alert = alerts.single()
        assertEquals("orphan:process:44559:44559", alert.key)
        assertEquals(Severity.WARNING, alert.severity)
        assertEquals("node (pid 44559) lost its parent codex (pid 44268)", alert.message)
    }

    /**
     * `UsageCalculator` records every parent change, not only the one that ends at pid 1, so the
     * gate that turns a change into orphanhood is this rule's own. On Darwin no other transition
     * exists, but the rule states the condition rather than assuming it.
     */
    @Test
    fun raisesNoAlertWhenTheNewParentIsNotPidOne() {
        val usage = systemUsage(processes = listOf(orphan(parentPid = 300)))

        val outcome = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet())

        assertEquals(emptyList(), outcome.alerts)
        assertEquals(emptySet(), outcome.suppressedKeys)
    }

    /** The parent was already gone in the previous sample too, so only its pid can be named. */
    @Test
    fun namesTheParentByPidAloneWhenThePreviousSampleDidNotHoldItsName() {
        val usage = systemUsage(processes = listOf(orphan(parentName = null)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet()).alerts

        assertEquals("node (pid 44559) lost its parent (pid 44268)", alerts.single().message)
    }

    /**
     * The pid order is what makes the cut deterministic: orphanhood has no metric to rank by, so
     * without it the two processes that lose their notification would depend on sample order.
     *
     * Every second process in the list is an ordinary one, and it is there so that the cap is seen
     * to count orphans rather than processes: a rule that ranked or took over `usage.processes`
     * would keep the wrong three, and one that paired a process with someone else's
     * `reparentedFrom` would name the wrong pid.
     */
    @Test
    fun capsOrphanAlertsByPidAndSuppressesTheRest() {
        val usage = systemUsage(
            processes = listOf(50, 30, 10, 40, 20).flatMap { pid ->
                listOf(
                    orphan(pid = pid),
                    processUsage(pid = pid + 1, name = "ordinary", parentPid = 1),
                )
            },
        )

        val outcome = AlertAnalyzer()
            .analyze(usage, HarmonConfig(maxAlertsPerCategory = 3), activeKeys = emptySet())

        assertEquals(
            listOf("orphan:process:10:10", "orphan:process:20:20", "orphan:process:30:30"),
            outcome.alerts.map { it.key },
        )
        assertEquals(
            setOf("orphan:process:40:40", "orphan:process:50:50"),
            outcome.suppressedKeys,
        )
    }

    /**
     * A suppressed orphan never joins the firing set, so its edge is gone for good — and the only
     * way to say that is to run the sample after it.
     *
     * The second sample carries the same five processes, still under launchd, with no transition:
     * that is what the next sample of a real supervisor collapse looks like. The suppressed keys
     * are handed in as `activeKeys` anyway, to state that even a caller who kept them would get no
     * alert back — `analyze` re-admits a suppressed key only when the rule suppressed it again,
     * and there is nothing left for the rule to suppress.
     */
    @Test
    fun doesNotKeepASuppressedOrphanFiringForALaterSample() {
        val analyzer = AlertAnalyzer()
        val config = HarmonConfig(maxAlertsPerCategory = 3)
        val transitionSample = systemUsage(processes = (1..5).map { pid -> orphan(pid = pid) })

        val firstSample = analyzer.analyze(transitionSample, config, activeKeys = emptySet())

        assertEquals(3, firstSample.alerts.size)
        assertEquals(2, firstSample.suppressedKeys.size)
        assertTrue(
            firstSample.suppressedKeys.none { it in firstSample.firingKeys },
            "a suppressed orphan must not enter the state: ${firstSample.firingKeys}",
        )

        val stillOrphanedSample = systemUsage(
            processes = (1..5).map { pid -> processUsage(pid = pid, name = "node", parentPid = 1) },
        )

        val secondSample = analyzer.analyze(
            stillOrphanedSample,
            config,
            activeKeys = firstSample.firingKeys + firstSample.suppressedKeys,
        )

        assertEquals(emptyList(), secondSample.alerts)
        assertEquals(emptySet(), secondSample.suppressedKeys)
        assertEquals(emptySet(), secondSample.firingKeys)
    }

    @Test
    fun raisesNoAlertForAProcessThatDidNotChangeItsParent() {
        val usage = systemUsage(processes = listOf(processUsage(pid = 44559, name = "node")))

        val outcome = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet())

        assertEquals(emptyList(), outcome.alerts)
        assertEquals(emptySet(), outcome.suppressedKeys)
    }

    /**
     * The rule has no threshold to set to zero, so `orphanAlerts=false` is the only way a user
     * can silence it. Off means nothing at all, not even a suppressed key in the report.
     */
    @Test
    fun raisesNoAlertForAnOrphanWhenTheRuleIsSwitchedOff() {
        val usage = systemUsage(
            processes = (1..5).map { pid -> orphan(pid = pid) },
        )

        val outcome = AlertAnalyzer().analyze(
            usage,
            HarmonConfig(orphanAlerts = false, maxAlertsPerCategory = 3),
            activeKeys = emptySet(),
        )

        assertEquals(emptyList(), outcome.alerts)
        assertEquals(emptySet(), outcome.suppressedKeys)
        assertEquals(emptySet(), outcome.firingKeys)
    }

    private companion object {
        const val CPU_KEY = "cpu:process:42:42"
        const val POWER_KEY = "power:process:42:42"
        const val BATTERY_IMPACT_KEY = "battery-impact:process:42:42"

        fun orphan(
            pid: Int = 44559,
            parentPid: Int = 1,
            parentName: String? = "codex",
        ) = processUsage(
            pid = pid,
            parentPid = parentPid,
            name = "node",
            reparentedFrom = ReparentedFrom(pid = 44268, name = parentName),
        )

        /** 2^44 MiB: the byte value wraps to zero without a saturating conversion. */
        const val OVERFLOWING_MIB = 1L shl 44

        fun onlyMemoryThreshold(mib: Long): HarmonConfig =
            singleThreshold(applicationMemoryMiB = mib)

        fun onlySwapThreshold(mib: Long): HarmonConfig = singleThreshold(swapUsedMiB = mib)

        /** Every rule but the ones named disabled, so a test observes exactly what it enables. */
        fun singleThreshold(
            applicationMemoryMiB: Long? = null,
            swapUsedMiB: Long? = null,
            applicationBatteryImpactScore: Double? = null,
            applicationPowerWatts: Double? = null,
        ): HarmonConfig = HarmonConfig(
            thresholds = AlertThresholds(
                applicationCpuPercent = null,
                applicationMemoryMiB = applicationMemoryMiB,
                applicationDiskWriteMiBPerSecond = null,
                swapUsedMiB = swapUsedMiB,
                swapOutMiBPerSecond = null,
                applicationBatteryImpactScore = applicationBatteryImpactScore,
                applicationPowerWatts = applicationPowerWatts,
                batteryLowPercent = null,
            ),
        )

        /**
         * Only the two battery-drain thresholds, at their shipped defaults unless a test disables
         * one, so the cross product of the two keys is observed without another rule's alert in
         * the list. A `null` is what `optionalPositiveDouble` makes of a configured `0`.
         */
        fun batteryRegimeThresholds(
            watts: Double? = 1.5,
            score: Double? = 100.0,
        ): HarmonConfig = singleThreshold(
            applicationBatteryImpactScore = score,
            applicationPowerWatts = watts,
        )
    }
}
