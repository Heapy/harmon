import dev.yoda.harmon.setup.AGENT_LABEL
import dev.yoda.harmon.setup.AgentLaunchdPaths
import dev.yoda.harmon.setup.COLLECTOR_LABEL
import dev.yoda.harmon.setup.CollectorLaunchdSettings
import dev.yoda.harmon.setup.LaunchdJobs
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
