import dev.yoda.harmon.collector.CollectorCliException
import dev.yoda.harmon.collector.CollectorCliParser
import dev.yoda.harmon.collector.CollectorCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CollectorCliParserTest {
    @Test
    fun parsesPrivilegedCollectorOptions() {
        val command = assertIs<CollectorCommand.Run>(
            CollectorCliParser.parse(
                arrayOf(
                    "--socket",
                    "/tmp/harmon.sock",
                    "--allowed-uid",
                    "501",
                    "--allowed-gid",
                    "20",
                    "--allow-unprivileged",
                ),
            ),
        )

        assertEquals("/tmp/harmon.sock", command.socketPath)
        assertEquals(501u, command.allowedUserId)
        assertEquals(20u, command.socketGroupId)
        assertTrue(command.allowUnprivileged)
    }

    @Test
    fun requiresBothIdentityOptions() {
        assertFailsWith<CollectorCliException> {
            CollectorCliParser.parse(arrayOf("--allowed-uid", "501"))
        }
        assertFailsWith<CollectorCliException> {
            CollectorCliParser.parse(arrayOf("--allowed-gid", "20"))
        }
    }

    @Test
    fun rejectsRelativeAndOverlongSocketPaths() {
        assertFailsWith<CollectorCliException> {
            CollectorCliParser.parse(
                arrayOf(
                    "--socket",
                    "tmp/harmon.sock",
                    "--allowed-uid",
                    "501",
                    "--allowed-gid",
                    "20",
                ),
            )
        }
        val overlong = "/" + "x".repeat(100)
        assertFailsWith<CollectorCliException> {
            CollectorCliParser.parse(
                arrayOf(
                    "--socket",
                    overlong,
                    "--allowed-uid",
                    "501",
                    "--allowed-gid",
                    "20",
                ),
            )
        }
    }
}
