import io.heapy.harmon.setup.BinaryVersionObservation
import io.heapy.harmon.setup.CommandInvocation
import io.heapy.harmon.setup.CommandResult
import io.heapy.harmon.setup.HarmonStatusSnapshot
import io.heapy.harmon.setup.LaunchdEnablement
import io.heapy.harmon.setup.LaunchdEnablement.DISABLED
import io.heapy.harmon.setup.LaunchdEnablement.ENABLED
import io.heapy.harmon.setup.LaunchdEnablement.UNKNOWN
import io.heapy.harmon.setup.LaunchdEnablementObservation
import io.heapy.harmon.setup.LaunchdLoadState
import io.heapy.harmon.setup.LaunchdServiceObservation
import io.heapy.harmon.setup.ProtocolObservation
import io.heapy.harmon.setup.StatusEvaluator
import io.heapy.harmon.setup.parseBinaryVersion
import io.heapy.harmon.setup.parseLaunchctlPrint
import io.heapy.harmon.setup.parseLaunchctlPrintDisabled
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
                    load = LaunchdLoadState.ABSENT,
                    error = "No such process",
                    enablement = enabled(),
                ),
            ),
        )

        assertEquals(1, report.exitCode)
        assertTrue(report.render().contains("Connection refused"))
        assertTrue(report.render().contains("No such process"))
    }

    @Test
    fun reportsBothDisabledServicesAsAnIntentionalStop() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                liveProtocol = ProtocolObservation(
                    SOCKET,
                    error = "not probed because both services are disabled",
                    notProbed = true,
                ),
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
                agentService = stoppedService(AGENT_SERVICE),
            ),
        )

        assertEquals(1, report.exitCode)
        assertFalse(report.intentionallyStopped)
        assertTrue(report.issues.any { "agent service is disabled and not loaded" in it })
    }

    @Test
    fun anUninstalledPairWithLeftoverOverridesIsNotAnIntentionalStop() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                installedAgent = BinaryVersionObservation(
                    INSTALLED_AGENT,
                    error = "file is missing",
                ),
                installedCollector = BinaryVersionObservation(
                    INSTALLED_COLLECTOR,
                    error = "file is missing",
                ),
                liveProtocol = ProtocolObservation(SOCKET, error = "Connection refused"),
                agentService = stoppedService(AGENT_SERVICE),
                collectorService = stoppedService(COLLECTOR_SERVICE),
            ),
        )

        assertFalse(report.intentionallyStopped, report.render())
        assertFalse(
            report.render().contains("Harmon is intentionally stopped."),
            report.render(),
        )
        assertTrue(report.render().contains("Problems:"), report.render())
        assertTrue(report.issues.any { "file is missing" in it }, report.render())
    }

    @Test
    fun aRealSocketErrorSurvivesEvenWhileTheServicesReadAsStopped() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                liveProtocol = ProtocolObservation(SOCKET, error = "Connection refused"),
                agentService = stoppedService(AGENT_SERVICE),
                collectorService = stoppedService(COLLECTOR_SERVICE),
            ),
        )

        assertTrue(report.render().contains("Connection refused"), report.render())
        assertFalse(
            report.render().contains("not running (intentionally stopped)"),
            report.render(),
        )
    }

    @Test
    fun aPrintFailureThatDoesNotProveAbsenceIsNotAnIntentionalStop() {
        val unknownService: (String) -> LaunchdServiceObservation = { service ->
            LaunchdServiceObservation(
                service = service,
                load = LaunchdLoadState.UNKNOWN,
                error = "Could not find domain for user gui: 501",
                enablement = LaunchdEnablementObservation(LaunchdEnablement.DISABLED),
            )
        }
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                liveProtocol = ProtocolObservation(SOCKET, error = "Connection refused"),
                agentService = unknownService(AGENT_SERVICE),
                collectorService = unknownService(COLLECTOR_SERVICE),
            ),
        )

        assertEquals(1, report.exitCode)
        assertFalse(report.intentionallyStopped)
        assertTrue(report.render().contains("Could not find domain"), report.render())
        assertTrue(report.issues.any { "agent service is not loaded" in it }, report.render())
    }

    @Test
    fun aRunningServiceWithUninspectableEnablementIsNotHealthy() {
        val report = StatusEvaluator.evaluate(
            healthyStatusSnapshot().copy(
                agentService = healthyStatusSnapshot().agentService.copy(
                    enablement = LaunchdEnablementObservation(
                        LaunchdEnablement.UNKNOWN,
                        "Could not find domain for user gui: 501",
                    ),
                ),
            ),
        )

        assertEquals(1, report.exitCode)
        assertTrue(
            report.issues.any {
                "agent service is running but its persistent enablement could not be " +
                    "inspected" in it
            },
            report.render(),
        )
        assertTrue(
            report.render().contains("enablement unknown (Could not find domain"),
            report.render(),
        )
        assertFalse(report.render().contains("Installation is healthy."), report.render())
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
                    gui/501/io.heapy.harmon.agent = {
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
    fun onlyAMissingServiceProvesTheJobIsUnloaded() {
        val invocation = CommandInvocation(
            listOf("/bin/launchctl", "print", AGENT_SERVICE),
        )
        val absent = parseLaunchctlPrint(
            AGENT_SERVICE,
            CommandResult(
                invocation,
                113,
                "Could not find service \"io.heapy.harmon.agent\" in domain for user gui: 501",
            ),
        )
        val unknown = parseLaunchctlPrint(
            AGENT_SERVICE,
            CommandResult(invocation, 112, "Could not find domain for user gui: 501"),
        )

        assertEquals(LaunchdLoadState.ABSENT, absent.load)
        assertEquals(LaunchdLoadState.UNKNOWN, unknown.load)
        assertFalse(unknown.loaded)
    }

    @Test
    fun parsesEnabledDisabledAndLegacyBooleanOverrides() {
        val result = printDisabled(
            """
                disabled services = {
                    "enabled.service" => enabled
                    "disabled.service" => disabled
                    "legacy.disabled" => true
                    "legacy.enabled" => false
                }
            """,
        )

        assertEquals(ENABLED, parseLaunchctlPrintDisabled("enabled.service", result).state)
        assertEquals(DISABLED, parseLaunchctlPrintDisabled("disabled.service", result).state)
        assertEquals(DISABLED, parseLaunchctlPrintDisabled("legacy.disabled", result).state)
        assertEquals(ENABLED, parseLaunchctlPrintDisabled("legacy.enabled", result).state)
        assertEquals(ENABLED, parseLaunchctlPrintDisabled("missing.service", result).state)
    }

    @Test
    fun parsesTheTabIndentedShapeLaunchctlActuallyPrints() {
        val real = CommandResult(
            CommandInvocation(listOf("/bin/launchctl", "print-disabled", "gui/501")),
            0,
            "\n\tdisabled services = {\n" +
                "\t\t\"com.docker.helper\" => enabled\n" +
                "\t\t\"io.heapy.harmon.agent\" => disabled\n" +
                "\t}\n",
        )

        assertEquals(DISABLED, parseLaunchctlPrintDisabled("io.heapy.harmon.agent", real).state)
        assertEquals(ENABLED, parseLaunchctlPrintDisabled("com.docker.helper", real).state)
        assertEquals(ENABLED, parseLaunchctlPrintDisabled("io.heapy.harmon", real).state)
    }

    @Test
    fun reportsWhyPersistentEnablementCouldNotBeRead() {
        val failed = parseLaunchctlPrintDisabled(
            "disabled.service",
            CommandResult(
                CommandInvocation(listOf("/bin/launchctl", "print-disabled", "gui/501")),
                112,
                "Could not find domain for user gui: 501",
            ),
        )

        assertEquals(UNKNOWN, failed.state)
        assertEquals("Could not find domain for user gui: 501", failed.error)
    }

    @Test
    fun trustsOnlyEntriesInsideTheDisabledServicesDictionary() {
        val trailingEntry = printDisabled(
            """
                disabled services = {
                    "svc" => disabled
                }
                "svc" => enabled
            """,
        )
        val headerAfterEntry = printDisabled(
            """
                "svc" => disabled
                disabled services = {
                }
            """,
        )

        assertEquals(DISABLED, parseLaunchctlPrintDisabled("svc", trailingEntry).state)
        assertEquals(ENABLED, parseLaunchctlPrintDisabled("svc", headerAfterEntry).state)
    }

    @Test
    fun anUnterminatedDictionaryIsUnknownRatherThanEnabled() {
        val truncated = printDisabled(
            """
                disabled services = {
                    "other.service" => disabled
            """,
        )

        assertEquals(UNKNOWN, parseLaunchctlPrintDisabled("svc", truncated).state)
    }

    @Test
    fun parsesOnlyTheNamedBinaryVersionFormat() {
        assertEquals("0.4.0", parseBinaryVersion("harmon 0.4.0\n", "harmon"))
        assertEquals(null, parseBinaryVersion("harmon-collector 0.4.0", "harmon"))
        assertFalse(parseBinaryVersion("harmon 0.4.0 extra", "harmon") != null)
    }
}

private fun enabled(): LaunchdEnablementObservation =
    LaunchdEnablementObservation(LaunchdEnablement.ENABLED)

private fun stoppedService(service: String): LaunchdServiceObservation =
    LaunchdServiceObservation(
        service = service,
        load = LaunchdLoadState.ABSENT,
        error = "Could not find service",
        enablement = LaunchdEnablementObservation(LaunchdEnablement.DISABLED),
    )

private fun printDisabled(output: String): CommandResult = CommandResult(
    CommandInvocation(listOf("/bin/launchctl", "print-disabled", "gui/501")),
    0,
    output.trimIndent(),
)

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
        load = LaunchdLoadState.LOADED,
        state = "running",
        processId = 123,
        program = INSTALLED_AGENT,
        enablement = enabled(),
    ),
    collectorService = LaunchdServiceObservation(
        service = COLLECTOR_SERVICE,
        load = LaunchdLoadState.LOADED,
        state = "running",
        processId = 456,
        program = INSTALLED_COLLECTOR,
        enablement = enabled(),
    ),
    expectedAgentProgram = INSTALLED_AGENT,
    expectedCollectorProgram = INSTALLED_COLLECTOR,
)

private const val SOURCE_COLLECTOR = "/Cellar/harmon/0.4.0/libexec/harmon-collector"
private const val INSTALLED_AGENT =
    "/Users/tester/Library/Application Support/Harmon/Harmon.app/Contents/MacOS/harmon"
private const val INSTALLED_COLLECTOR = "/Library/PrivilegedHelperTools/harmon-collector"
private const val SOCKET = "/var/run/harmon.collector.sock"
private const val AGENT_SERVICE = "gui/501/io.heapy.harmon.agent"
private const val COLLECTOR_SERVICE = "system/io.heapy.harmon.collector"
