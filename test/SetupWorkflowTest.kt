import io.heapy.harmon.BuildInfo
import io.heapy.harmon.setup.CommandExecutionException
import io.heapy.harmon.setup.CommandInvocation
import io.heapy.harmon.setup.CommandResult
import io.heapy.harmon.setup.CommandRunner
import io.heapy.harmon.setup.FileOwnership
import io.heapy.harmon.setup.InstallResourceOrigin
import io.heapy.harmon.setup.InstallResources
import io.heapy.harmon.setup.InstalledFileAttributes
import io.heapy.harmon.setup.SetupException
import io.heapy.harmon.setup.SetupFileSystem
import io.heapy.harmon.setup.SystemSetup
import io.heapy.harmon.setup.SystemSetupPaths
import io.heapy.harmon.setup.SystemStop
import io.heapy.harmon.setup.SystemUninstall
import io.heapy.harmon.setup.UninstallPurge
import io.heapy.harmon.setup.UserSetup
import io.heapy.harmon.setup.UserSetupPaths
import io.heapy.harmon.setup.UserStop
import io.heapy.harmon.setup.UserUninstall
import io.heapy.harmon.setup.ValidatedInstallResources
import io.heapy.harmon.setup.renderApplicationInfoPlist
import io.heapy.harmon.setup.selectSigningIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SetupWorkflowTest {
    @Test
    fun userPhaseIsIdempotentAndNeverOverwritesTheExistingConfig() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner(fileSystem)
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.config] = "custom=true\n"
        fileSystem.files[paths.previousAgentPlist] = "pre-rename"
        fileSystem.files[paths.legacyAgentPlist] = "legacy"
        fileSystem.symlinks[paths.legacyCommandLink] = paths.installedAgent
        val setup = UserSetup(
            validated = validatedResources(),
            userId = 501u,
            groupId = 20u,
            home = TEST_HOME,
            fileSystem = fileSystem,
            commandRunner = runner,
        )

        setup.run()
        setup.run()

        assertEquals("custom=true\n", fileSystem.files[paths.config])
        assertEquals("0600".toUInt(8), fileSystem.attributes[paths.config]?.mode)
        assertFalse(paths.previousAgentPlist in fileSystem.files)
        assertFalse(paths.legacyAgentPlist in fileSystem.files)
        assertFalse(paths.stagedAgentPlist in fileSystem.files)
        assertFalse(paths.legacyCommandLink in fileSystem.symlinks)
        assertTrue(fileSystem.files.getValue(paths.installedInfoPlist).contains(BuildInfo.VERSION))
        assertFalse(fileSystem.files.getValue(paths.installedInfoPlist).contains("@HARMON_VERSION@"))
        assertEquals(
            2,
            runner.invocations.count { it.arguments.first() == "/usr/bin/sudo" },
        )
        val sudoSnapshots = runner.pathsPresentWhenInvoked
            .filter { it.first == "/usr/bin/sudo" }
            .map { it.second }
        assertEquals(2, sudoSnapshots.size)
        assertTrue(paths.previousAgentPlist in sudoSnapshots.first())
        assertTrue(paths.legacyAgentPlist in sudoSnapshots.first())
        assertFalse(paths.agentPlist in sudoSnapshots.first())
        assertTrue(paths.stagedAgentPlist in sudoSnapshots.first())
        assertFalse(paths.previousAgentPlist in sudoSnapshots.last())
        assertFalse(paths.legacyAgentPlist in sudoSnapshots.last())
        assertTrue(paths.agentPlist in sudoSnapshots.last())
        assertTrue(paths.stagedAgentPlist in sudoSnapshots.last())
        val firstPublishPaths = runner.invocations.zip(runner.pathsPresentWhenInvoked)
            .first { (invocation) ->
                invocation.arguments == listOf(
                    "/usr/bin/plutil",
                    "-lint",
                    "${paths.agentPlist}.fake.tmp",
                )
            }
            .second.second
        assertFalse(paths.previousAgentPlist in firstPublishPaths)
        assertFalse(paths.legacyAgentPlist in firstPublishPaths)
        assertFalse(paths.agentPlist in firstPublishPaths)
        assertTrue(paths.stagedAgentPlist in firstPublishPaths)
        assertEquals(
            listOf(
                "/usr/bin/sudo",
                TEST_AGENT_SOURCE,
                "setup",
                "--system",
                "--uid",
                "501",
                "--gid",
                "20",
            ),
            runner.invocations.last { it.arguments.first() == "/usr/bin/sudo" }.arguments,
        )
        assertFalse(
            fileSystem.writtenTargets.any { it.startsWith("/Library/") },
            "the user phase wrote a system path",
        )
    }

    @Test
    fun userPhaseKeepsPublishedDefinitionsWhenTheSystemPhaseFails() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.previousAgentPlist] = "pre-rename"
        fileSystem.files[paths.legacyAgentPlist] = "legacy"
        fileSystem.symlinks[paths.legacyCommandLink] = paths.installedAgent
        val sudo = listOf(
            "/usr/bin/sudo",
            TEST_AGENT_SOURCE,
            "setup",
            "--system",
            "--uid",
            "501",
            "--gid",
            "20",
        )
        val runner = WorkflowCommandRunner(fileSystem, failedArguments = setOf(sudo))

        assertFailsWith<CommandExecutionException> {
            UserSetup(
                validated = validatedResources(),
                userId = 501u,
                groupId = 20u,
                home = TEST_HOME,
                fileSystem = fileSystem,
                commandRunner = runner,
            ).run()
        }

        val pathsAtSudo = runner.pathsPresentWhenInvoked
            .single { it.first == "/usr/bin/sudo" }
            .second
        assertTrue(paths.previousAgentPlist in pathsAtSudo)
        assertTrue(paths.legacyAgentPlist in pathsAtSudo)
        assertTrue(paths.stagedAgentPlist in pathsAtSudo)
        assertFalse(paths.agentPlist in pathsAtSudo)
        assertEquals("pre-rename", fileSystem.files[paths.previousAgentPlist])
        assertEquals("legacy", fileSystem.files[paths.legacyAgentPlist])
        assertEquals(paths.installedAgent, fileSystem.symlinks[paths.legacyCommandLink])
        assertFalse(paths.agentPlist in fileSystem.files)
        assertFalse(paths.stagedAgentPlist in fileSystem.files)
    }

    @Test
    fun userPhaseDoesNotReplaceTheCurrentDefinitionWhenTheSystemPhaseFails() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.agentPlist] = "installed definition"
        val sudo = listOf(
            "/usr/bin/sudo",
            TEST_AGENT_SOURCE,
            "setup",
            "--system",
            "--uid",
            "501",
            "--gid",
            "20",
        )

        assertFailsWith<CommandExecutionException> {
            UserSetup(
                validated = validatedResources(),
                userId = 501u,
                groupId = 20u,
                home = TEST_HOME,
                fileSystem = fileSystem,
                commandRunner = WorkflowCommandRunner(
                    fileSystem,
                    failedArguments = setOf(sudo),
                ),
            ).run()
        }

        assertEquals("installed definition", fileSystem.files[paths.agentPlist])
        assertFalse(paths.stagedAgentPlist in fileSystem.files)
    }

    @Test
    fun userPhaseLeavesAnUnrelatedLocalBinSymlinkAlone() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.symlinks[paths.legacyCommandLink] = "/usr/local/bin/something-else"

        UserSetup(
            validated = validatedResources(),
            userId = 501u,
            groupId = 20u,
            home = TEST_HOME,
            fileSystem = fileSystem,
            commandRunner = WorkflowCommandRunner(),
        ).run()

        assertEquals(
            "/usr/local/bin/something-else",
            fileSystem.symlinks[paths.legacyCommandLink],
        )
    }

    @Test
    fun systemPhaseWritesOnlyRootOwnedSystemFilesAndUsesTheFixedServiceOrder() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner()
        val userPaths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[userPaths.agentPlist] = "agent"
        fileSystem.files[SystemSetupPaths.previousCollectorPlist] = "pre-rename daemon"
        fileSystem.files[SystemSetupPaths.legacyCollectorBinary] = "source-installer helper"

        SystemSetup(
            validated = validatedResources(),
            targetUserId = 501u,
            targetGroupId = 20u,
            targetHome = TEST_HOME,
            wheelGroupId = 0u,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertEquals(
            FileOwnership(0u, 0u),
            fileSystem.attributes[SystemSetupPaths.collectorBinary]?.ownership,
        )
        assertEquals(
            FileOwnership(0u, 0u),
            fileSystem.attributes[SystemSetupPaths.collectorPlist]?.ownership,
        )
        assertTrue(
            fileSystem.files.getValue(SystemSetupPaths.collectorPlist)
                .contains("<string>501</string>"),
        )
        assertTrue(
            fileSystem.files.getValue(SystemSetupPaths.collectorPlist)
                .contains("<string>20</string>"),
        )
        assertFalse(
            fileSystem.writtenTargets.any { it.startsWith(TEST_HOME) },
            "the system phase wrote into the user home",
        )
        assertFalse(SystemSetupPaths.previousCollectorPlist in fileSystem.files)
        assertFalse(SystemSetupPaths.legacyCollectorBinary in fileSystem.files)

        val launchctl = runner.invocations
            .map(CommandInvocation::arguments)
            .filter { it.first() == "/bin/launchctl" }
        assertEquals(
            listOf(
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "system/io.heapy.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "system/dev.yoda.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/io.heapy.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/dev.yoda.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/dev.yoda.harmon",
                ),
                listOf(
                    "/bin/launchctl",
                    "enable",
                    "system/io.heapy.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootstrap",
                    "system",
                    "/Library/LaunchDaemons/io.heapy.harmon.collector.plist",
                ),
                listOf(
                    "/bin/launchctl",
                    "kickstart",
                    "-k",
                    "system/io.heapy.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "enable",
                    "gui/501/io.heapy.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootstrap",
                    "gui/501",
                    "$TEST_HOME/Library/LaunchAgents/io.heapy.harmon.agent.plist",
                ),
                listOf(
                    "/bin/launchctl",
                    "kickstart",
                    "-k",
                    "gui/501/io.heapy.harmon.agent",
                ),
            ),
            launchctl,
        )

        runner.invocations.zip(runner.pathsPresentWhenInvoked)
            .filter { (invocation) ->
                invocation.arguments.getOrNull(1) in
                    setOf("enable", "bootstrap", "kickstart")
            }
            .forEach { (_, snapshot) ->
                assertFalse(SystemSetupPaths.previousCollectorPlist in snapshot.second)
                assertFalse(SystemSetupPaths.legacyCollectorBinary in snapshot.second)
            }
    }

    @Test
    fun systemPhaseRequiresTheUserOwnedPlistBeforeItsFirstWrite() {
        val fileSystem = workflowFileSystem()

        assertFailsWith<SetupException> {
            SystemSetup(
                validated = validatedResources(),
                targetUserId = 501u,
                targetGroupId = 20u,
                targetHome = TEST_HOME,
                wheelGroupId = 0u,
                fileSystem = fileSystem,
                commandRunner = WorkflowCommandRunner(),
            ).run()
        }

        assertTrue(fileSystem.writtenTargets.isEmpty())
    }

    @Test
    fun systemPhasePrefersTheStagedAgentDefinition() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.agentPlist] = "published agent"
        fileSystem.files[paths.stagedAgentPlist] = "staged agent"
        val runner = WorkflowCommandRunner()

        SystemSetup(
            validated = validatedResources(),
            targetUserId = 501u,
            targetGroupId = 20u,
            targetHome = TEST_HOME,
            wheelGroupId = 0u,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(
                "/bin/launchctl",
                "bootstrap",
                "gui/501",
                paths.stagedAgentPlist,
            ),
            runner.invocations
                .map(CommandInvocation::arguments)
                .single { it.take(3) == listOf("/bin/launchctl", "bootstrap", "gui/501") },
        )
    }

    @Test
    fun signingIdentitySelectionUsesTheFirstTrustedShaAndFallsBackToNull() {
        assertEquals(
            "0123456789ABCDEF0123456789ABCDEF01234567",
            selectSigningIdentity(
                """
                    1) 0123456789ABCDEF0123456789ABCDEF01234567 "Developer ID"
                    2) FEDCBA9876543210FEDCBA9876543210FEDCBA98 "Second"
                """.trimIndent(),
            ),
        )
        assertEquals(null, selectSigningIdentity("0 valid identities found"))
    }

    @Test
    fun appInfoRequiresBothVersionFields() {
        assertFailsWith<SetupException> {
            renderApplicationInfoPlist(
                "<key>CFBundleShortVersionString</key><string>@HARMON_VERSION@</string>",
            )
        }
    }

    @Test
    fun userUninstallRemovesOnlyManagedFilesAndKeepsItsExecutableUntilSudoReturns() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner(fileSystem)
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.stagedAgentPlist] = "staged agent plist"
        fileSystem.files[paths.agentPlist] = "agent plist"
        fileSystem.files[paths.previousAgentPlist] = "pre-rename plist"
        fileSystem.files[paths.legacyAgentPlist] = "legacy plist"
        fileSystem.files[paths.liveUiEndpoint] = "stale endpoint"
        fileSystem.files[paths.config] = "custom=true"
        fileSystem.files["${paths.supportDirectory}/history.db"] = "history"
        fileSystem.files["${paths.logDirectory}/agent.log"] = "log"
        fileSystem.directories += paths.appBundle
        fileSystem.files[paths.installedAgent] = "installed agent"
        fileSystem.symlinks[paths.legacyCommandLink] = paths.installedAgent

        UserUninstall(
            executablePath = paths.installedAgent,
            userId = 501u,
            home = TEST_HOME,
            purge = false,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertFalse(paths.stagedAgentPlist in fileSystem.files)
        assertFalse(paths.agentPlist in fileSystem.files)
        assertFalse(paths.previousAgentPlist in fileSystem.files)
        assertFalse(paths.legacyAgentPlist in fileSystem.files)
        assertFalse(paths.liveUiEndpoint in fileSystem.files)
        assertFalse(paths.legacyCommandLink in fileSystem.symlinks)
        assertFalse(paths.installedAgent in fileSystem.files)
        assertEquals("custom=true", fileSystem.files[paths.config])
        assertEquals("history", fileSystem.files["${paths.supportDirectory}/history.db"])
        assertEquals("log", fileSystem.files["${paths.logDirectory}/agent.log"])

        val invocations = runner.invocations.map(CommandInvocation::arguments)
        assertEquals(
            listOf(
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/io.heapy.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/dev.yoda.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/dev.yoda.harmon",
                ),
                listOf(
                    "/usr/bin/sudo",
                    paths.installedAgent,
                    "uninstall",
                    "--system",
                    "--uid",
                    "501",
                ),
            ),
            invocations,
        )
        assertTrue(
            runner.pathsPresentWhenInvoked
                .single { it.first == "/usr/bin/sudo" }
                .second.contains(paths.installedAgent),
            "the app-hosted executable was deleted before sudo re-exec",
        )
    }

    @Test
    fun userUninstallKeepsAnUnrelatedLegacyCommandSymlink() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.symlinks[paths.legacyCommandLink] = "/usr/local/bin/unrelated"

        UserUninstall(
            executablePath = TEST_AGENT_SOURCE,
            userId = 501u,
            home = TEST_HOME,
            purge = false,
            fileSystem = fileSystem,
            commandRunner = WorkflowCommandRunner(),
        ).run()

        assertEquals("/usr/local/bin/unrelated", fileSystem.symlinks[paths.legacyCommandLink])
    }

    @Test
    fun systemUninstallIsIdempotentAndPreservesCollectorLogs() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner()
        fileSystem.files[SystemSetupPaths.collectorPlist] = "daemon"
        fileSystem.files[SystemSetupPaths.previousCollectorPlist] = "pre-rename daemon"
        fileSystem.files[SystemSetupPaths.collectorBinary] = "collector"
        fileSystem.files[SystemSetupPaths.legacyCollectorBinary] = "legacy collector"
        fileSystem.files[SystemSetupPaths.socket] = "socket"
        fileSystem.files["${SystemSetupPaths.logDirectory}/collector.log"] = "log"
        val uninstall = SystemUninstall(
            targetUserId = 501u,
            purge = false,
            fileSystem = fileSystem,
            commandRunner = runner,
        )

        uninstall.run()
        uninstall.run()

        assertFalse(SystemSetupPaths.collectorPlist in fileSystem.files)
        assertFalse(SystemSetupPaths.previousCollectorPlist in fileSystem.files)
        assertFalse(SystemSetupPaths.collectorBinary in fileSystem.files)
        assertFalse(SystemSetupPaths.legacyCollectorBinary in fileSystem.files)
        assertFalse(SystemSetupPaths.socket in fileSystem.files)
        assertEquals(
            "log",
            fileSystem.files["${SystemSetupPaths.logDirectory}/collector.log"],
        )
        val expectedBootouts = listOf(
            listOf("/bin/launchctl", "bootout", "system/io.heapy.harmon.collector"),
            listOf("/bin/launchctl", "bootout", "system/dev.yoda.harmon.collector"),
            listOf("/bin/launchctl", "bootout", "gui/501/io.heapy.harmon.agent"),
            listOf("/bin/launchctl", "bootout", "gui/501/dev.yoda.harmon.agent"),
            listOf("/bin/launchctl", "bootout", "gui/501/dev.yoda.harmon"),
        )
        assertEquals(
            expectedBootouts + expectedBootouts,
            runner.invocations
                .map(CommandInvocation::arguments)
                .filter { it.getOrNull(1) == "bootout" },
        )
    }

    @Test
    fun userUninstallPurgeRemovesEveryUserTreeOnlyAfterTheSudoReExec() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner(fileSystem)
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.agentPlist] = "agent plist"
        fileSystem.files[paths.config] = "custom=true"
        fileSystem.files["${paths.supportDirectory}/history.db"] = "history"
        fileSystem.files["${paths.supportDirectory}/history.db-wal"] = "wal"
        fileSystem.files["${paths.supportDirectory}/history.db-shm"] = "shm"
        fileSystem.files["${paths.supportDirectory}/Reports/latest.html"] = "report"
        fileSystem.files["${paths.supportDirectory}/Reports/latest.html.4321.tmp"] = "partial"
        fileSystem.files["${paths.logDirectory}/agent.log"] = "log"
        fileSystem.directories += paths.appBundle
        fileSystem.files[paths.installedAgent] = "installed agent"

        UserUninstall(
            executablePath = paths.installedAgent,
            userId = 501u,
            home = TEST_HOME,
            purge = true,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertTrue(
            fileSystem.files.keys.none { it.startsWith(TEST_HOME) },
            "purge left ${fileSystem.files.keys.filter { it.startsWith(TEST_HOME) }}",
        )
        assertFalse(paths.appBundle in fileSystem.directories)

        assertEquals(
            listOf(
                "/usr/bin/sudo",
                paths.installedAgent,
                "uninstall",
                "--system",
                "--uid",
                "501",
                "--purge",
            ),
            runner.invocations.single { it.arguments.first() == "/usr/bin/sudo" }.arguments,
        )

        val presentAtSudo = runner.pathsPresentWhenInvoked
            .single { it.first == "/usr/bin/sudo" }
            .second
        assertTrue(
            paths.installedAgent in presentAtSudo,
            "the app-hosted executable was deleted before sudo re-exec",
        )
        assertTrue(
            paths.config in presentAtSudo &&
                "${paths.supportDirectory}/history.db" in presentAtSudo,
            "user data was destroyed before the privileged phase could fail",
        )
    }

    @Test
    fun systemUninstallPurgeRemovesRootLogsIdempotently() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner()
        fileSystem.files[SystemSetupPaths.collectorPlist] = "daemon"
        fileSystem.files["${SystemSetupPaths.logDirectory}/collector.log"] = "log"
        fileSystem.files["${SystemSetupPaths.logDirectory}/collector.error.log"] = "errors"
        val uninstall = SystemUninstall(
            targetUserId = 501u,
            purge = true,
            fileSystem = fileSystem,
            commandRunner = runner,
        )

        uninstall.run()
        uninstall.run()

        assertFalse(SystemSetupPaths.collectorPlist in fileSystem.files)
        assertTrue(
            fileSystem.files.keys.none { it.startsWith(SystemSetupPaths.logDirectory) },
        )
        assertEquals(
            2,
            runner.invocations.count {
                it.arguments == listOf(
                    "/bin/launchctl",
                    "bootout",
                    "system/io.heapy.harmon.collector",
                )
            },
        )
    }

    @Test
    fun userUninstallClearsTheDisableOverridesWhenTheSystemPhaseFails() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        val sudo = listOf(
            "/usr/bin/sudo",
            paths.installedAgent,
            "uninstall",
            "--system",
            "--uid",
            "501",
        )
        val runner = WorkflowCommandRunner(
            fileSystem,
            failedArguments = setOf(sudo),
            disabledLabels = setOf(
                "io.heapy.harmon.agent",
                "dev.yoda.harmon.agent",
                "dev.yoda.harmon",
            ),
        )

        assertFailsWith<CommandExecutionException> {
            UserUninstall(
                executablePath = paths.installedAgent,
                userId = 501u,
                home = TEST_HOME,
                purge = false,
                fileSystem = fileSystem,
                commandRunner = runner,
            ).run()
        }

        assertEquals(
            listOf(listOf("/bin/launchctl", "print-disabled", "gui/501")),
            runner.invocations
                .map(CommandInvocation::arguments)
                .filter { it.getOrNull(1) == "print-disabled" },
        )
        val enables = runner.invocations
            .map(CommandInvocation::arguments)
            .filter { it.getOrNull(1) == "enable" }
        assertEquals(
            listOf(
                listOf("/bin/launchctl", "enable", "gui/501/io.heapy.harmon.agent"),
                listOf("/bin/launchctl", "enable", "gui/501/dev.yoda.harmon.agent"),
                listOf("/bin/launchctl", "enable", "gui/501/dev.yoda.harmon"),
            ),
            enables,
        )
    }

    @Test
    fun userUninstallPreservesTheSystemFailureWhenOverrideCleanupAlsoFails() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        val sudo = listOf(
            "/usr/bin/sudo",
            paths.installedAgent,
            "uninstall",
            "--system",
            "--uid",
            "501",
        )
        val failedEnable = listOf(
            "/bin/launchctl",
            "enable",
            "gui/501/io.heapy.harmon.agent",
        )
        val runner = WorkflowCommandRunner(
            fileSystem,
            failedArguments = setOf(sudo, failedEnable),
            disabledLabels = setOf("io.heapy.harmon.agent"),
        )

        val failure = assertFailsWith<CommandExecutionException> {
            UserUninstall(
                executablePath = paths.installedAgent,
                userId = 501u,
                home = TEST_HOME,
                purge = false,
                fileSystem = fileSystem,
                commandRunner = runner,
            ).run()
        }

        assertEquals(sudo, failure.result.invocation.arguments)
        val cleanupFailure = assertIs<CommandExecutionException>(
            failure.suppressedExceptions.single(),
        )
        assertEquals(failedEnable, cleanupFailure.result.invocation.arguments)
    }

    @Test
    fun uninstallDoesNotEnableAnyGenerationThatWasNeverDisabled() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner(fileSystem)

        UserUninstall(
            executablePath = UserSetupPaths.forHome(TEST_HOME).installedAgent,
            userId = 501u,
            home = TEST_HOME,
            purge = false,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()
        SystemUninstall(
            targetUserId = 501u,
            purge = false,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertTrue(
            runner.invocations.none { it.arguments.getOrNull(1) == "enable" },
            "uninstall added launchd rows for labels that had none",
        )
        assertEquals(
            listOf(
                listOf("/bin/launchctl", "print-disabled", "system"),
                listOf("/bin/launchctl", "print-disabled", "gui/501"),
            ),
            runner.invocations
                .map(CommandInvocation::arguments)
                .filter { it.getOrNull(1) == "print-disabled" },
        )
    }

    @Test
    fun systemUninstallLeavesOverridesAloneWhenDomainSnapshotsCannotBeRead() {
        val systemSnapshot = listOf("/bin/launchctl", "print-disabled", "system")
        val userSnapshot = listOf("/bin/launchctl", "print-disabled", "gui/501")
        val runner = WorkflowCommandRunner(
            failedArguments = setOf(systemSnapshot, userSnapshot),
        )

        SystemUninstall(
            targetUserId = 501u,
            purge = false,
            fileSystem = workflowFileSystem(),
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(systemSnapshot, userSnapshot),
            runner.invocations
                .map(CommandInvocation::arguments)
                .filter { it.getOrNull(1) == "print-disabled" },
        )
        assertTrue(runner.invocations.none { it.arguments.getOrNull(1) == "enable" })
    }

    @Test
    fun systemUninstallClearsCurrentAndPreRenameCollectorDisableOverrides() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner(
            fileSystem,
            disabledLabels = setOf(
                "io.heapy.harmon.collector",
                "dev.yoda.harmon.collector",
            ),
        )

        SystemUninstall(
            targetUserId = 501u,
            purge = false,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(
                listOf("/bin/launchctl", "enable", "system/io.heapy.harmon.collector"),
                listOf("/bin/launchctl", "enable", "system/dev.yoda.harmon.collector"),
            ),
            runner.invocations
                .map(CommandInvocation::arguments)
                .filter { it.getOrNull(1) == "enable" },
        )
    }

    @Test
    fun theSystemUninstallFormClearsTheUserOverridesItAlsoUnloads() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner(
            fileSystem,
            disabledLabels = setOf(
                "io.heapy.harmon.collector",
                "dev.yoda.harmon.collector",
                "io.heapy.harmon.agent",
                "dev.yoda.harmon.agent",
                "dev.yoda.harmon",
            ),
        )

        SystemUninstall(
            targetUserId = 501u,
            purge = false,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(
                listOf("/bin/launchctl", "print-disabled", "system"),
                listOf("/bin/launchctl", "print-disabled", "gui/501"),
            ),
            runner.invocations
                .map(CommandInvocation::arguments)
                .filter { it.getOrNull(1) == "print-disabled" },
        )
        assertEquals(
            listOf(
                listOf("/bin/launchctl", "enable", "system/io.heapy.harmon.collector"),
                listOf("/bin/launchctl", "enable", "system/dev.yoda.harmon.collector"),
                listOf("/bin/launchctl", "enable", "gui/501/io.heapy.harmon.agent"),
                listOf("/bin/launchctl", "enable", "gui/501/dev.yoda.harmon.agent"),
                listOf("/bin/launchctl", "enable", "gui/501/dev.yoda.harmon"),
            ),
            runner.invocations
                .map(CommandInvocation::arguments)
                .filter { it.getOrNull(1) == "enable" },
        )
    }

    @Test
    fun uninstallPurgeListsUserTreesWithTheSupportTreeLast() {
        assertEquals(
            listOf(
                "$TEST_HOME/.config/harmon",
                "$TEST_HOME/Library/Logs/Harmon",
                "$TEST_HOME/Library/Application Support/Harmon",
            ),
            UninstallPurge.userTrees(UserSetupPaths.forHome(TEST_HOME)),
        )
        assertEquals(listOf("/Library/Logs/Harmon"), UninstallPurge.systemTrees)
    }

    @Test
    fun userStopTouchesLaunchdOnlyThroughTheSystemPhase() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner(fileSystem)
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.liveUiEndpoint] = "stale endpoint"

        UserStop(
            executablePath = TEST_AGENT_SOURCE,
            userId = 501u,
            home = TEST_HOME,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(
                listOf(
                    "/usr/bin/sudo",
                    TEST_AGENT_SOURCE,
                    "stop",
                    "--system",
                    "--uid",
                    "501",
                ),
            ),
            runner.invocations.map(CommandInvocation::arguments),
        )
        assertFalse(runner.invocations.last().captureOutput)
        assertFalse(paths.liveUiEndpoint in fileSystem.files)
        assertTrue(
            runner.pathsPresentWhenInvoked
                .single { it.first == "/usr/bin/sudo" }
                .second.contains(paths.liveUiEndpoint),
            "the endpoint was removed before the privileged phase could fail",
        )
    }

    @Test
    fun userStopKeepsTheEndpointWhenTheSystemPhaseFails() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.liveUiEndpoint] = "stale endpoint"
        val sudo = listOf(
            "/usr/bin/sudo",
            TEST_AGENT_SOURCE,
            "stop",
            "--system",
            "--uid",
            "501",
        )
        val runner = WorkflowCommandRunner(fileSystem, failedArguments = setOf(sudo))

        assertFailsWith<CommandExecutionException> {
            UserStop(
                executablePath = TEST_AGENT_SOURCE,
                userId = 501u,
                home = TEST_HOME,
                fileSystem = fileSystem,
                commandRunner = runner,
            ).run()
        }

        assertEquals(listOf(sudo), runner.invocations.map(CommandInvocation::arguments))
        assertTrue(
            paths.liveUiEndpoint in fileSystem.files,
            "a declined password still removed the endpoint",
        )
    }

    @Test
    fun systemStopDisablesAndUnloadsAPreRenameInstallation() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[SystemSetupPaths.previousCollectorPlist] = "pre-rename daemon"
        fileSystem.files[paths.previousAgentPlist] = "pre-rename agent"
        val runner = WorkflowCommandRunner(fileSystem)

        SystemStop(
            targetUserId = 501u,
            targetHome = TEST_HOME,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(
                listOf(
                    "/bin/launchctl",
                    "disable",
                    "system/dev.yoda.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "disable",
                    "gui/501/dev.yoda.harmon.agent",
                ),
                listOf("/bin/launchctl", "bootout", "system/io.heapy.harmon.collector"),
                listOf("/bin/launchctl", "bootout", "system/dev.yoda.harmon.collector"),
                listOf("/bin/launchctl", "bootout", "gui/501/io.heapy.harmon.agent"),
                listOf("/bin/launchctl", "bootout", "gui/501/dev.yoda.harmon.agent"),
                listOf("/bin/launchctl", "bootout", "gui/501/dev.yoda.harmon"),
            ),
            runner.invocations.map(CommandInvocation::arguments),
        )
    }

    @Test
    fun systemStopBootsOutNothingWhenADisableFails() {
        val failedDisable = listOf(
            "/bin/launchctl",
            "disable",
            "gui/501/io.heapy.harmon.agent",
        )
        val fileSystem = installedStopFileSystem()
        val runner = WorkflowCommandRunner(
            fileSystem,
            failedArguments = setOf(failedDisable),
        )

        val failure = assertFailsWith<SetupException> {
            SystemStop(
                targetUserId = 501u,
                targetHome = TEST_HOME,
                fileSystem = fileSystem,
                commandRunner = runner,
            ).run()
        }

        assertEquals(
            listOf(
                listOf(
                    "/bin/launchctl",
                    "disable",
                    "system/io.heapy.harmon.collector",
                ),
                failedDisable,
            ),
            runner.invocations.map(CommandInvocation::arguments),
        )
        assertTrue(
            "system/io.heapy.harmon.collector is now disabled" in failure.message.orEmpty(),
            "the partial state was not reported: ${failure.message}",
        )
    }

    @Test
    fun systemStopToleratesADomainThatDoesNotResolve() {
        val runner = WorkflowMissingDomainRunner("gui/501")

        SystemStop(
            targetUserId = 501u,
            targetHome = TEST_HOME,
            fileSystem = installedStopFileSystem(),
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(
                listOf("/bin/launchctl", "disable", "system/io.heapy.harmon.collector"),
                listOf("/bin/launchctl", "disable", "gui/501/io.heapy.harmon.agent"),
                listOf("/bin/launchctl", "disable", "gui/501/dev.yoda.harmon"),
                listOf("/bin/launchctl", "bootout", "system/io.heapy.harmon.collector"),
                listOf("/bin/launchctl", "bootout", "system/dev.yoda.harmon.collector"),
                listOf("/bin/launchctl", "bootout", "gui/501/io.heapy.harmon.agent"),
                listOf("/bin/launchctl", "bootout", "gui/501/dev.yoda.harmon.agent"),
                listOf("/bin/launchctl", "bootout", "gui/501/dev.yoda.harmon"),
            ),
            runner.invocations.map(CommandInvocation::arguments),
        )
    }

    @Test
    fun systemStopNeverNamesALabelWithNoJobDefinition() {
        val fileSystem = workflowFileSystem()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[SystemSetupPaths.collectorPlist] = "daemon"
        fileSystem.files[paths.agentPlist] = "agent"
        val runner = WorkflowCommandRunner(fileSystem)

        SystemStop(
            targetUserId = 501u,
            targetHome = TEST_HOME,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        val arguments = runner.invocations.map(CommandInvocation::arguments)
        assertEquals(
            listOf(
                listOf("/bin/launchctl", "disable", "system/io.heapy.harmon.collector"),
                listOf("/bin/launchctl", "disable", "gui/501/io.heapy.harmon.agent"),
            ),
            arguments.filter { it.getOrNull(1) == "disable" },
            "a label with no plist was named, which adds a launchd row nothing can remove",
        )
        assertEquals(
            5,
            arguments.count { it.getOrNull(1) == "bootout" },
            "an already loaded job must still be unloaded: $arguments",
        )
    }

    @Test
    fun systemStopDisablesEveryServiceBeforeIdempotentBootout() {
        val fileSystem = installedStopFileSystem()
        val runner = WorkflowCommandRunner(fileSystem)

        SystemStop(
            targetUserId = 501u,
            targetHome = TEST_HOME,
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertEquals(
            listOf(
                listOf(
                    "/bin/launchctl",
                    "disable",
                    "system/io.heapy.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "disable",
                    "gui/501/io.heapy.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "disable",
                    "gui/501/dev.yoda.harmon",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "system/io.heapy.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "system/dev.yoda.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/io.heapy.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/dev.yoda.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/dev.yoda.harmon",
                ),
            ),
            runner.invocations.map(CommandInvocation::arguments),
        )
    }
}

private class WorkflowCommandRunner(
    private val fileSystem: WorkflowFileSystem? = null,
    private val failedArguments: Set<List<String>> = emptySet(),
    private val disabledLabels: Set<String> = emptySet(),
) : CommandRunner {
    val invocations = mutableListOf<CommandInvocation>()
    val pathsPresentWhenInvoked = mutableListOf<Pair<String, Set<String>>>()

    override fun run(invocation: CommandInvocation): CommandResult {
        invocations += invocation
        val arguments = invocation.arguments
        pathsPresentWhenInvoked += arguments.first() to
            (fileSystem?.files?.keys?.toSet() ?: emptySet())
        return when {
            arguments in failedArguments ->
                CommandResult(invocation, 77, "Operation not permitted")
            arguments.take(2) == listOf("/bin/launchctl", "print-disabled") ->
                success(
                    invocation,
                    buildString {
                        appendLine("\tdisabled services = {")
                        disabledLabels.forEach { appendLine("\t\t\"$it\" => disabled") }
                        append("\t}")
                    },
                )
            arguments.take(2) == listOf("/usr/bin/security", "find-identity") ->
                success(
                    invocation,
                    """  1) 0123456789ABCDEF0123456789ABCDEF01234567 "Local identity"""",
                )
            arguments.take(2) == listOf("/usr/bin/killall", "usernoted") ->
                CommandResult(invocation, 1, "No matching processes")
            arguments.take(2) == listOf("/usr/bin/killall", "NotificationCenter") ->
                CommandResult(invocation, 1, "No matching processes")
            arguments.take(2) == listOf("/bin/launchctl", "bootout") ->
                CommandResult(invocation, 3, "No such process")
            else -> success(invocation)
        }
    }

    private fun success(
        invocation: CommandInvocation,
        output: String = "",
    ): CommandResult = CommandResult(invocation, 0, output)
}

/** Current and source-installer plists used by the stop sequencing tests. */
private fun installedStopFileSystem(): WorkflowFileSystem {
    val fileSystem = workflowFileSystem()
    val paths = UserSetupPaths.forHome(TEST_HOME)
    fileSystem.files[SystemSetupPaths.collectorPlist] = "daemon"
    fileSystem.files[paths.agentPlist] = "agent"
    fileSystem.files[paths.legacyAgentPlist] = "legacy agent"
    return fileSystem
}

/** Every call against the named domain fails the way launchd answers for a logged-out user. */
private class WorkflowMissingDomainRunner(
    private val domain: String,
) : CommandRunner {
    val invocations = mutableListOf<CommandInvocation>()

    override fun run(invocation: CommandInvocation): CommandResult {
        invocations += invocation
        val target = invocation.arguments.last()
        return if (target.startsWith("$domain/") || target == domain) {
            CommandResult(
                invocation,
                112,
                "Bad request.\nCould not find domain for user gui: 501",
            )
        } else {
            CommandResult(invocation, 0, "")
        }
    }
}

private class WorkflowFileSystem : SetupFileSystem {
    val files = mutableMapOf<String, String>()
    val directories = mutableSetOf<String>()
    val symlinks = mutableMapOf<String, String>()
    val attributes = mutableMapOf<String, InstalledFileAttributes>()
    val writtenTargets = mutableListOf<String>()

    override fun ensureDirectory(path: String, attributes: InstalledFileAttributes) {
        directories += path
        this.attributes[path] = attributes
    }

    override fun copyFileAtomically(
        source: String,
        target: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)?,
    ) {
        val temporary = "$target.fake.tmp"
        validateTemporaryFile?.invoke(temporary)
        files[target] = files[source] ?: "<binary:$source>"
        this.attributes[target] = attributes
        writtenTargets += target
    }

    override fun writeTextAtomically(
        target: String,
        content: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)?,
    ) {
        val temporary = "$target.fake.tmp"
        validateTemporaryFile?.invoke(temporary)
        files[target] = content
        this.attributes[target] = attributes
        writtenTargets += target
    }

    override fun setMode(path: String, mode: UInt) {
        attributes[path] = InstalledFileAttributes(mode)
    }

    override fun exists(path: String): Boolean =
        path in files || path in directories || path in symlinks

    override fun isRegularFile(path: String): Boolean = path in files

    override fun isReadable(path: String): Boolean = path in files

    override fun isSymbolicLink(path: String): Boolean = path in symlinks

    override fun readSymbolicLink(path: String): String? = symlinks[path]

    override fun readText(path: String): String =
        files[path] ?: error("missing fake file $path")

    override fun removeFileIfExists(path: String) {
        files.remove(path)
        symlinks.remove(path)
    }

    override fun removeTreeIfExists(path: String) {
        files.keys.filter { it == path || it.startsWith("$path/") }.forEach(files::remove)
        directories.removeAll { it == path || it.startsWith("$path/") }
        symlinks.keys.filter { it == path || it.startsWith("$path/") }.forEach(symlinks::remove)
    }
}

private fun workflowFileSystem(): WorkflowFileSystem = WorkflowFileSystem().apply {
    files[TEST_AGENT_SOURCE] = "agent"
    files[TEST_COLLECTOR_SOURCE] = "collector"
    files[TEST_INFO_SOURCE] = """
        <plist><dict>
        <key>CFBundleShortVersionString</key><string>@HARMON_VERSION@</string>
        <key>CFBundleVersion</key><string>@HARMON_VERSION@</string>
        </dict></plist>
    """.trimIndent()
    files[TEST_ICON_SOURCE] = "icon"
    files[TEST_CONFIG_SOURCE] = "intervalSeconds=300\n"
}

private fun validatedResources(): ValidatedInstallResources = ValidatedInstallResources(
    executablePath = TEST_AGENT_SOURCE,
    resources = InstallResources(
        agentBinary = TEST_AGENT_SOURCE,
        collectorBinary = TEST_COLLECTOR_SOURCE,
        infoPlist = TEST_INFO_SOURCE,
        icon = TEST_ICON_SOURCE,
        exampleConfig = TEST_CONFIG_SOURCE,
        origin = InstallResourceOrigin.INSTALLED,
    ),
)

private const val TEST_HOME = "/Users/tester"
private const val TEST_AGENT_SOURCE = "/Cellar/harmon/0.4.0/bin/harmon"
private const val TEST_COLLECTOR_SOURCE = "/Cellar/harmon/0.4.0/libexec/harmon-collector"
private const val TEST_INFO_SOURCE = "/Cellar/harmon/0.4.0/share/harmon/Harmon.Info.plist"
private const val TEST_ICON_SOURCE = "/Cellar/harmon/0.4.0/share/harmon/Harmon.icns"
private const val TEST_CONFIG_SOURCE = "/Cellar/harmon/0.4.0/share/harmon/harmon.conf.example"
