import dev.yoda.harmon.setup.CommandExecutionException
import dev.yoda.harmon.setup.CommandInvocation
import dev.yoda.harmon.setup.CommandResult
import dev.yoda.harmon.setup.CommandRunner
import dev.yoda.harmon.setup.requireSuccess
import dev.yoda.harmon.setup.run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandRunnerTest {
    @Test
    fun passesArgumentsLiterallyWithoutShellInterpolation() {
        val runner = RecordingCommandRunner()
        val arguments = listOf(
            "/usr/bin/printf",
            "%s",
            """$(touch /tmp/never) ; & <space>""",
        )

        runner.requireSuccess(arguments)

        assertEquals(arguments, runner.invocations.single().arguments)
    }

    @Test
    fun capturesStderrAndTheExitCode() {
        val runner = RecordingCommandRunner(exitCode = 7, output = "failure")
        val result = runner.run(listOf("/usr/bin/false"))

        assertEquals(7, result.exitCode)
        assertEquals("failure", result.output)
    }

    @Test
    fun namesTheFailedArgvInTheException() {
        val runner = RecordingCommandRunner(exitCode = 1)
        val failure = assertFailsWith<CommandExecutionException> {
            runner.requireSuccess(listOf("/usr/bin/false", "literal argument"))
        }

        assertTrue(failure.message.orEmpty().contains("/usr/bin/false literal argument"))
    }
}

private class RecordingCommandRunner(
    private val exitCode: Int = 0,
    private val output: String = "",
) : CommandRunner {
    val invocations = mutableListOf<CommandInvocation>()

    override fun run(invocation: CommandInvocation): CommandResult {
        invocations += invocation
        return CommandResult(invocation, exitCode, output)
    }
}
