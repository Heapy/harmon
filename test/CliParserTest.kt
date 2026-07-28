import dev.yoda.harmon.cli.CliException
import dev.yoda.harmon.cli.CliParser
import dev.yoda.harmon.cli.Command
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliParserTest {
    @Test
    fun parsesProcessDiagnosticsCommand() {
        val command = assertIs<Command.Diagnose>(
            CliParser.parse(
                arrayOf(
                    "diagnose",
                    "--config",
                    "/tmp/harmon.conf",
                    "--sample-seconds",
                    "3",
                ),
            ),
        )

        assertEquals("/tmp/harmon.conf", command.configPath)
        assertEquals(3, command.sampleSeconds)
    }

    @Test
    fun parsesPrivilegedCollectorCommand() {
        val command = assertIs<Command.Collector>(
            CliParser.parse(
                arrayOf(
                    "collector",
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
        assertEquals(true, command.allowUnprivileged)
    }

    @Test
    fun rejectsASampleWindowFarBeyondTheAllowedRange() {
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("once", "--sample-seconds", "99999999999"))
        }
    }

    @Test
    fun acceptsTheLargestAllowedSampleWindowAndRejectsTheNextSecond() {
        val command = assertIs<Command.Once>(
            CliParser.parse(arrayOf("once", "--sample-seconds", "300")),
        )
        assertEquals(SAMPLE_SECONDS_RANGE.last, command.sampleSeconds)

        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("once", "--sample-seconds", "301"))
        }
    }

    @Test
    fun namesTheAllowedRangeWhenTheSampleWindowIsRejected() {
        val failure = assertFailsWith<CliException> {
            CliParser.parse(arrayOf("once", "--sample-seconds", "0"))
        }

        val message = failure.message.orEmpty()
        assertTrue(
            message.contains(SAMPLE_SECONDS_RANGE.first.toString()) &&
                message.contains(SAMPLE_SECONDS_RANGE.last.toString()),
            "expected the allowed range in '$message'",
        )
    }
}
