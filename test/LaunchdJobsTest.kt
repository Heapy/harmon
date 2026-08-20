import io.heapy.harmon.setup.AGENT_LABEL
import io.heapy.harmon.setup.AgentLaunchdPaths
import io.heapy.harmon.setup.COLLECTOR_LABEL
import io.heapy.harmon.setup.CollectorLaunchdSettings
import io.heapy.harmon.setup.LEGACY_AGENT_LABEL
import io.heapy.harmon.setup.LaunchdJobs
import io.heapy.harmon.setup.PREVIOUS_AGENT_LABEL
import io.heapy.harmon.setup.PREVIOUS_COLLECTOR_LABEL
import io.heapy.harmon.setup.SystemSetupPaths
import io.heapy.harmon.setup.UserSetupPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdtemp
import platform.posix.system
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LaunchdJobsTest {
    @Test
    fun migrationIdentifiersRemainTiedToTheirInstallGeneration() {
        assertEquals("io.heapy.harmon.agent", AGENT_LABEL)
        assertEquals("io.heapy.harmon.collector", COLLECTOR_LABEL)
        assertEquals("dev.yoda.harmon.agent", PREVIOUS_AGENT_LABEL)
        assertEquals("dev.yoda.harmon.collector", PREVIOUS_COLLECTOR_LABEL)
        assertEquals("dev.yoda.harmon", LEGACY_AGENT_LABEL)

        val userPaths = UserSetupPaths.forHome("/Users/tester")
        assertEquals(
            "/Users/tester/Library/LaunchAgents/io.heapy.harmon.agent.plist",
            userPaths.agentPlist,
        )
        assertEquals(
            "/Users/tester/Library/LaunchAgents/dev.yoda.harmon.agent.plist",
            userPaths.previousAgentPlist,
        )
        assertEquals(
            "/Users/tester/Library/LaunchAgents/dev.yoda.harmon.plist",
            userPaths.legacyAgentPlist,
        )
        assertEquals(
            "/Library/LaunchDaemons/io.heapy.harmon.collector.plist",
            SystemSetupPaths.collectorPlist,
        )
        assertEquals(
            "/Library/LaunchDaemons/dev.yoda.harmon.collector.plist",
            SystemSetupPaths.previousCollectorPlist,
        )
        assertEquals(
            "/Library/PrivilegedHelperTools/dev.yoda.harmon",
            SystemSetupPaths.legacyCollectorBinary,
        )
    }

    @Test
    fun agentJobPreservesTheLaunchAgentContract() {
        val job = LaunchdJobs.agent(
            AgentLaunchdPaths(
                agentBinary = "/Users/A & B/Harmon.app/Contents/MacOS/harmon",
                config = "/Users/A & B/.config/harmon/config",
                logDirectory = "/Users/A & B/Library/Logs/Harmon",
            ),
        )

        assertEquals(AGENT_LABEL, job.label)
        assertEquals(
            listOf(
                "/Users/A & B/Harmon.app/Contents/MacOS/harmon",
                "run",
                "--config",
                "/Users/A & B/.config/harmon/config",
            ),
            job.programArguments,
        )
        assertEquals("Aqua", job.limitLoadToSessionType)
        assertEquals(63, job.umask)
        assertTrue(job.xml().contains("/Users/A &amp; B/"))
        assertPlistLints(job.xml())
    }

    @Test
    fun collectorJobCannotSwapTheNamedUidAndGidSettings() {
        val job = LaunchdJobs.collector(
            CollectorLaunchdSettings(
                collectorBinary = "/Library/PrivilegedHelperTools/harmon-collector",
                socket = "/var/run/harmon.collector.sock",
                allowedUserId = 501u,
                allowedGroupId = 20u,
            ),
        )

        assertEquals(COLLECTOR_LABEL, job.label)
        assertEquals(
            listOf(
                "/Library/PrivilegedHelperTools/harmon-collector",
                "--socket",
                "/var/run/harmon.collector.sock",
                "--allowed-uid",
                "501",
                "--allowed-gid",
                "20",
            ),
            job.programArguments,
        )
        assertEquals(7, job.umask)
        assertPlistLints(job.xml())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun assertPlistLints(xml: String) = memScoped {
    val root = mkdtemp("/tmp/harmon-plist-test.XXXXXX".cstr.getPointer(this))
        ?.toKString()
        ?: fail("cannot create plist test directory")
    val path = "$root/generated.plist"
    try {
        val file = fopen(path, "w") ?: fail("cannot create $path")
        fputs(xml, file)
        fclose(file)
        assertEquals(
            0,
            system("/usr/bin/plutil -lint $path >/dev/null"),
            "plutil rejected generated plist:\n$xml",
        )
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }
}
