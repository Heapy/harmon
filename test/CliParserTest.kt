import dev.yoda.harmon.cli.CliParser
import dev.yoda.harmon.cli.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}
