import dev.yoda.harmon.analysis.ApplicationGrouper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ApplicationGrouperTest {
    @Test
    fun aggregatesProcessesFromTheSameOutermostApplicationBundle() {
        val processes = listOf(
            processUsage(
                pid = 100,
                name = "firefox",
                executablePath = "/Applications/Firefox.app/Contents/MacOS/firefox",
                cpuPercent = 20.0,
                footprint = 100u,
                impact = 4.0,
            ),
            processUsage(
                pid = 101,
                name = "crashhelper",
                executablePath = "/Applications/Firefox.app/Contents/MacOS/crashhelper",
                cpuPercent = 5.0,
                footprint = 50u,
                impact = 2.0,
            ),
            processUsage(
                pid = 102,
                parentPid = 100,
                name = "plugin-container",
                executablePath = "/Applications/Firefox.app/Contents/MacOS/" +
                    "plugin-container.app/Contents/MacOS/plugin-container",
                cpuPercent = 30.0,
                footprint = 200u,
                impact = 8.0,
            ),
            processUsage(
                pid = 103,
                parentPid = 102,
                name = "pathless-helper",
                executablePath = null,
                cpuPercent = 5.0,
                footprint = 25u,
                impact = 1.0,
            ),
        )

        val application = ApplicationGrouper().group(processes).single()

        assertEquals("Firefox", application.name)
        assertEquals(100, application.rootPid)
        assertEquals(listOf(100, 101, 102, 103), application.processIds)
        assertEquals(60.0, application.cpuPercent)
        assertEquals(375u, application.physicalFootprintBytes)
        assertEquals(15.0, application.batteryImpactScore)
    }

    @Test
    fun doesNotMergeUnbundledProcessesOnlyBecauseTheirNamesMatch() {
        val applications = ApplicationGrouper().group(
            listOf(
                processUsage(pid = 200, name = "worker"),
                processUsage(pid = 201, name = "worker"),
            ),
        )

        assertEquals(2, applications.size)
        assertNotEquals(applications[0].id, applications[1].id)
    }
}
