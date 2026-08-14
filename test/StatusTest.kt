import dev.yoda.harmon.setup.BinaryVersionObservation
import dev.yoda.harmon.setup.CommandInvocation
import dev.yoda.harmon.setup.CommandResult
import dev.yoda.harmon.setup.HarmonStatusSnapshot
import dev.yoda.harmon.setup.LaunchdServiceObservation
import dev.yoda.harmon.setup.ProtocolObservation
import dev.yoda.harmon.setup.StatusEvaluator
import dev.yoda.harmon.setup.parseBinaryVersion
import dev.yoda.harmon.setup.parseLaunchctlPrint
import dev.yoda.harmon.setup.parseLaunchctlPrintDisabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusTest {
    @Test
    fun aCompletelyMatchedRunningPairIsHealthy() {
        val report = StatusEvaluator.evaluate(healthyStatusSnapshot())

        assertEquals(0, report.exitCode)
        assertTrue(report.issues.isEmpty())
        assertTrue(report.render().contains("Installation is healthy."))
    }

    @Test
    fun namesTheBrewUpgradeGapAndTheRequiredAction() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                runningCliVersion = "0.5.0",
                sourceCollector = BinaryVersionObservation(SOURCE_COLLECTOR, "0.5.0"),
            ),
        )

        assertEquals(1, report.exitCode)
        assertTrue(
            report.issues.any {
                "Homebrew/source is 0.5.0" in it &&
                    "installed agent bundle is 0.4.0" in it &&
                    "harmon setup" in it
            },
        )
    }

    @Test
    fun namesAMixedInstalledPairDirectly() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                installedCollector = BinaryVersionObservation(INSTALLED_COLLECTOR, "0.3.0"),
            ),
        )

        assertTrue(report.issues.any { "installed pair is mixed" in it })
        assertTrue(report.issues.all { "harmon setup" in it })
    }

    @Test
    fun detectsAnOldLoadedDaemonEvenWhenTheFilesMatch() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                liveProtocol = ProtocolObservation(SOCKET, version = 1),
            ),
        )

        assertTrue(
            report.issues.any {
                "loaded collector speaks protocol 1" in it &&
                    "old copy" in it
            },
        )
    }

    @Test
    fun reportsSocketAndServiceFailuresWithoutNeedingSudo() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                liveProtocol = ProtocolObservation(SOCKET, error = "Connection refused"),
                agentService = LaunchdServiceObservation(
                    service = AGENT_SERVICE,
                    loaded = false,
                    error = "No such process",
                ),
            ),
        )

        assertEquals(1, report.exitCode)
        assertTrue(report.render().contains("Connection refused"))
        assertTrue(report.render().contains("No such process"))
    }

    @Test
    fun reportsBothDisabledServicesAsAnIntentionalStop() {
        val stoppedService: (String) -> LaunchdServiceObservation = { service ->
            LaunchdServiceObservation(
                service = service,
                loaded = false,
                error = "Could not find service",
                disabled = true,
            )
        }
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                liveProtocol = ProtocolObservation(SOCKET, error = "Connection refused"),
                agentService = stoppedService(AGENT_SERVICE),
                collectorService = stoppedService(COLLECTOR_SERVICE),
            ),
        )

        assertEquals(1, report.exitCode)
        assertTrue(report.intentionallyStopped)
        assertTrue(report.issues.isEmpty())
        assertTrue(report.render().contains("Harmon is intentionally stopped."))
        assertTrue(report.render().contains("agent service:      stopped (disabled)"))
        assertTrue(report.render().contains("not running (intentionally stopped)"))
        assertFalse(report.render().contains("Problems:"))
    }

    @Test
    fun aSingleDisabledServiceIsStillAnIncompleteState() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                agentService = LaunchdServiceObservation(
                    service = AGENT_SERVICE,
                    loaded = false,
                    error = "Could not find service",
                    disabled = true,
                ),
            ),
        )

        assertEquals(1, report.exitCode)
        assertFalse(report.intentionallyStopped)
        assertTrue(report.issues.any { "agent service is disabled and not loaded" in it })
    }

    @Test
    fun parsesLaunchctlStatePidAndExecutablePath() {
        val invocation = CommandInvocation(
            listOf("/bin/launchctl", "print", AGENT_SERVICE),
        )
        val observation = parseLaunchctlPrint(
            AGENT_SERVICE,
            CommandResult(
                invocation,
                0,
                """
                    gui/501/dev.yoda.harmon.agent = {
                        state = running
                        program = $INSTALLED_AGENT
                        pid = 4321
                        resource coalition = {
                            state = active
                        }
                    }
                """.trimIndent(),
            ),
        )

        assertTrue(observation.running)
        assertEquals(4321, observation.processId)
        assertEquals(INSTALLED_AGENT, observation.program)
    }

    @Test
    fun parsesEnabledDisabledAndLegacyBooleanOverrides() {
        val invocation = CommandInvocation(
            listOf("/bin/launchctl", "print-disabled", "gui/501"),
        )
        val result = CommandResult(
            invocation,
            0,
            """
                disabled services = {
                    "enabled.service" => enabled
                    "disabled.service" => disabled
                    "legacy.disabled" => true
                    "legacy.enabled" => false
                }
            """.trimIndent(),
        )

        assertEquals(false, parseLaunchctlPrintDisabled("enabled.service", result))
        assertEquals(true, parseLaunchctlPrintDisabled("disabled.service", result))
        assertEquals(true, parseLaunchctlPrintDisabled("legacy.disabled", result))
        assertEquals(false, parseLaunchctlPrintDisabled("legacy.enabled", result))
        assertEquals(false, parseLaunchctlPrintDisabled("missing.service", result))
        assertEquals(
            null,
            parseLaunchctlPrintDisabled(
                "disabled.service",
                CommandResult(invocation, 1, "permission denied"),
            ),
        )
    }

    @Test
    fun parsesOnlyTheNamedBinaryVersionFormat() {
        assertEquals("0.4.0", parseBinaryVersion("harmon 0.4.0\n", "harmon"))
        assertEquals(null, parseBinaryVersion("harmon-collector 0.4.0", "harmon"))
        assertFalse(parseBinaryVersion("harmon 0.4.0 extra", "harmon") != null)
    }
}

private fun healthyStatusSnapshot(): HarmonStatusSnapshot = HarmonStatusSnapshot(
    runningCliVersion = "0.4.0",
    runningCliPath = "/Cellar/harmon/0.4.0/bin/harmon",
    sourceCollector = BinaryVersionObservation(SOURCE_COLLECTOR, "0.4.0"),
    installedAgent = BinaryVersionObservation(INSTALLED_AGENT, "0.4.0"),
    installedCollector = BinaryVersionObservation(INSTALLED_COLLECTOR, "0.4.0"),
    expectedProtocol = 2,
    liveProtocol = ProtocolObservation(SOCKET, version = 2),
    agentService = LaunchdServiceObservation(
        service = AGENT_SERVICE,
        loaded = true,
        state = "running",
        processId = 123,
        program = INSTALLED_AGENT,
    ),
    collectorService = LaunchdServiceObservation(
        service = COLLECTOR_SERVICE,
        loaded = true,
        state = "running",
        processId = 456,
        program = INSTALLED_COLLECTOR,
    ),
    expectedAgentProgram = INSTALLED_AGENT,
    expectedCollectorProgram = INSTALLED_COLLECTOR,
)

private const val SOURCE_COLLECTOR = "/Cellar/harmon/0.4.0/libexec/harmon-collector"
private const val INSTALLED_AGENT =
    "/Users/tester/Library/Application Support/Harmon/Harmon.app/Contents/MacOS/harmon"
private const val INSTALLED_COLLECTOR = "/Library/PrivilegedHelperTools/harmon-collector"
private const val SOCKET = "/var/run/harmon.collector.sock"
private const val AGENT_SERVICE = "gui/501/dev.yoda.harmon.agent"
private const val COLLECTOR_SERVICE = "system/dev.yoda.harmon.collector"
