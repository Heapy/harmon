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

    @Test
    fun terminalApplicationsDoNotClaimExternalDescendants() {
        val applications = ApplicationGrouper().group(
            listOf(
                processUsage(
                    pid = 300,
                    name = "ghostty",
                    executablePath = "/Applications/Ghostty.app/Contents/MacOS/ghostty",
                ),
                processUsage(
                    pid = 301,
                    parentPid = 300,
                    name = "ghostty-helper",
                    executablePath = "/Applications/Ghostty.app/Contents/MacOS/ghostty-helper",
                ),
                processUsage(
                    pid = 302,
                    parentPid = 300,
                    name = "zsh",
                    executablePath = "/bin/zsh",
                ),
                processUsage(
                    pid = 303,
                    parentPid = 302,
                    name = "codex",
                    executablePath = null,
                ),
                processUsage(
                    pid = 304,
                    parentPid = 302,
                    name = "firefox",
                    executablePath = "/Applications/Firefox.app/Contents/MacOS/firefox",
                ),
                processUsage(
                    pid = 305,
                    parentPid = 304,
                    name = "firefox-helper",
                    executablePath = null,
                ),
                processUsage(
                    pid = 400,
                    name = "agterm",
                    executablePath = "/Applications/agterm.app/Contents/MacOS/agterm",
                ),
                processUsage(
                    pid = 401,
                    parentPid = 400,
                    name = "bash",
                    executablePath = "/bin/bash",
                ),
            ),
        )

        assertEquals(
            listOf(300, 301),
            applications.single { it.name == "Ghostty" }.processIds,
        )
        assertEquals(
            listOf(304, 305),
            applications.single { it.name == "Firefox" }.processIds,
        )
        assertEquals(
            listOf(400),
            applications.single { it.name == "agterm" }.processIds,
        )
        assertEquals(null, applications.single { it.rootPid == 302 }.bundlePath)
        assertEquals(null, applications.single { it.rootPid == 303 }.bundlePath)
        assertEquals(null, applications.single { it.rootPid == 401 }.bundlePath)
    }

    @Test
    fun namesTheBundleOfAPathWhoseCaseFoldingChangesItsLength() {
        val application = ApplicationGrouper().group(
            listOf(
                processUsage(
                    pid = 500,
                    name = "app",
                    executablePath = "/Applications/İstanbul.app/Contents/MacOS/app",
                ),
            ),
        ).single()

        assertEquals("İstanbul", application.name)
        assertEquals("/Applications/İstanbul.app", application.bundlePath)
    }

    @Test
    fun doesNotOverrunAPathWhoseCaseFoldingGrowsPastTheMarkerOffset() {
        val application = ApplicationGrouper().group(
            listOf(processUsage(pid = 501, name = "x", executablePath = "/İİİ.app/x")),
        ).single()

        assertEquals("İİİ", application.name)
        assertEquals("/İİİ.app", application.bundlePath)
    }

    @Test
    fun namesTheBundleWhenCaseFoldingShiftsTheMarkerPastTheExtension() {
        val application = ApplicationGrouper().group(
            listOf(processUsage(pid = 502, name = "x", executablePath = "/İİ.app/x")),
        ).single()

        assertEquals("İİ", application.name)
        assertEquals("/İİ.app", application.bundlePath)
    }

    @Test
    fun matchesTheBundleMarkerRegardlessOfItsCase() {
        val application = ApplicationGrouper().group(
            listOf(
                processUsage(
                    pid = 503,
                    name = "foo",
                    executablePath = "/Applications/Foo.APP/Contents/MacOS/foo",
                ),
            ),
        ).single()

        assertEquals("Foo", application.name)
        assertEquals("/Applications/Foo.APP", application.bundlePath)
    }

    @Test
    fun treatsAPathWithAnEmptyBundleNameAsUnbundled() {
        val application = ApplicationGrouper().group(
            listOf(processUsage(pid = 504, name = "helper", executablePath = "/.app/helper")),
        ).single()

        assertEquals("helper", application.name)
        assertEquals(null, application.bundlePath)
    }
}
