import dev.yoda.harmon.analysis.AlertAnalyzer
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

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig())
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

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig())

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

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig())

        assertTrue(alerts.any { it.key.startsWith("disk-write:") })
        assertTrue(alerts.any { it.key == "swap-out" })
    }

    @Test
    fun holdsAlertBetweenClearRatioAndThresholdWhenKeyIsActive() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 140.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(CPU_KEY))

        assertEquals(listOf(CPU_KEY), alerts.map { it.key })
    }

    @Test
    fun doesNotRaiseAlertBelowThresholdWhenKeyIsNotActive() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 140.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig())

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun clearsAlertBelowClearRatioEvenWhenKeyIsActive() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 130.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(CPU_KEY))

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun gradesSeverityAgainstTheOriginalThresholdNotTheLoweredOne() {
        val usage = systemUsage(processes = listOf(processUsage(cpuPercent = 280.0)))

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(CPU_KEY))

        assertEquals(Severity.WARNING, alerts.single { it.key == CPU_KEY }.severity)
    }

    @Test
    fun keepsLowBatteryAlertBecauseHysteresisIsNotAppliedToIt() {
        val usage = systemUsage(processes = emptyList(), batteryPercentage = 19)

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf("battery-low"))

        assertEquals(Severity.WARNING, alerts.single { it.key == "battery-low" }.severity)
    }

    @Test
    fun keepsActiveKeyThatFellOutOfTheTopSlice() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(pid = 1, cpuPercent = 400.0),
                processUsage(pid = 2, cpuPercent = 300.0),
                processUsage(pid = 3, cpuPercent = 200.0),
                processUsage(pid = 4, cpuPercent = 160.0),
            ),
        )
        val demotedKey = "cpu:process:4:4"

        val alerts = AlertAnalyzer().analyze(usage, HarmonConfig(), setOf(demotedKey))

        val keys = alerts.map { it.key }

        assertEquals(4, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(demotedKey in keys)
    }

    private companion object {
        const val CPU_KEY = "cpu:process:42:42"
    }
}
