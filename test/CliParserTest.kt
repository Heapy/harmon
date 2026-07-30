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

    @Test
    fun parsesTheUserAndPublicSystemSetupForms() {
        val user = assertIs<Command.Setup>(
            CliParser.parse(arrayOf("setup")),
        )
        assertEquals(false, user.system)
        assertEquals(null, user.userId)
        assertEquals(null, user.groupId)

        val system = assertIs<Command.Setup>(
            CliParser.parse(
                arrayOf(
                    "setup",
                    "--system",
                    "--uid",
                    "501",
                    "--gid",
                    "20",
                ),
            ),
        )
        assertTrue(system.system)
        assertEquals(501u, system.userId)
        assertEquals(20u, system.groupId)
    }

    @Test
    fun keepsSystemIdentityOptionsOutOfTheUserPhase() {
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("setup", "--uid", "501", "--gid", "20"))
        }
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("setup", "--system", "--uid", "501"))
        }
        assertFailsWith<CliException> {
            CliParser.parse(
                arrayOf("setup", "--system", "--uid", "0", "--gid", "invalid"),
            )
        }
    }
}
