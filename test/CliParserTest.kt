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
    fun parsesUiAndRejectsOptions() {
        assertEquals(Command.Ui, CliParser.parse(arrayOf("ui")))
        listOf(
            arrayOf("ui", "--config", "/tmp/config"),
            arrayOf("ui", "--sample-seconds", "1"),
            arrayOf("ui", "--notify"),
        ).forEach { arguments ->
            assertFailsWith<CliException> { CliParser.parse(arguments) }
        }
    }

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

    @Test
    fun parsesStatusAndRejectsEveryOption() {
        assertEquals(Command.Status, CliParser.parse(arrayOf("status")))
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("status", "--config", "/tmp/config"))
        }
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("status", "--notify"))
        }
    }

    @Test
    fun parsesUserAndPublicSystemStopForms() {
        assertEquals(
            Command.Stop(system = false, userId = null),
            CliParser.parse(arrayOf("stop")),
        )
        assertEquals(
            Command.Stop(system = true, userId = 501u),
            CliParser.parse(arrayOf("stop", "--system", "--uid", "501")),
        )

        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("stop", "--uid", "501"))
        }
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("stop", "--system"))
        }
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("stop", "--config", "/tmp/config"))
        }
    }

    @Test
    fun parsesUserAndPublicSystemUninstallForms() {
        assertEquals(
            Command.Uninstall(system = false, userId = null),
            CliParser.parse(arrayOf("uninstall")),
        )
        assertEquals(
            Command.Uninstall(system = true, userId = 501u),
            CliParser.parse(arrayOf("uninstall", "--system", "--uid", "501")),
        )

        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("uninstall", "--uid", "501"))
        }
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("uninstall", "--system"))
        }
        assertFailsWith<CliException> {
            CliParser.parse(arrayOf("uninstall", "--system", "--gid", "20"))
        }
    }
}
