import dev.yoda.harmon.BuildInfo
import dev.yoda.harmon.setup.CommandInvocation
import dev.yoda.harmon.setup.CommandResult
import dev.yoda.harmon.setup.CommandRunner
import dev.yoda.harmon.setup.FileOwnership
import dev.yoda.harmon.setup.InstallResourceOrigin
import dev.yoda.harmon.setup.InstallResources
import dev.yoda.harmon.setup.InstalledFileAttributes
import dev.yoda.harmon.setup.SetupException
import dev.yoda.harmon.setup.SetupFileSystem
import dev.yoda.harmon.setup.SystemSetup
import dev.yoda.harmon.setup.SystemSetupPaths
import dev.yoda.harmon.setup.SystemUninstall
import dev.yoda.harmon.setup.UserSetup
import dev.yoda.harmon.setup.UserSetupPaths
import dev.yoda.harmon.setup.UserUninstall
import dev.yoda.harmon.setup.ValidatedInstallResources
import dev.yoda.harmon.setup.renderApplicationInfoPlist
import dev.yoda.harmon.setup.selectSigningIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupWorkflowTest {
    @Test
    fun userPhaseIsIdempotentAndNeverOverwritesTheExistingConfig() {
        val fileSystem = workflowFileSystem()
        val runner = WorkflowCommandRunner()
        val paths = UserSetupPaths.forHome(TEST_HOME)
        fileSystem.files[paths.config] = "custom=true\n"
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
        assertFalse(paths.legacyAgentPlist in fileSystem.files)
        assertFalse(paths.legacyCommandLink in fileSystem.symlinks)
        assertTrue(fileSystem.files.getValue(paths.installedInfoPlist).contains(BuildInfo.VERSION))
        assertFalse(fileSystem.files.getValue(paths.installedInfoPlist).contains("@HARMON_VERSION@"))
        assertEquals(
            2,
            runner.invocations.count { it.arguments.first() == "/usr/bin/sudo" },
        )
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

        val launchctl = runner.invocations
            .map(CommandInvocation::arguments)
            .filter { it.first() == "/bin/launchctl" }
        assertEquals(
            listOf(
                listOf(
                    "/bin/launchctl",
                    "bootout",
                    "system/dev.yoda.harmon.collector",
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
                    "bootstrap",
                    "system",
                    "/Library/LaunchDaemons/dev.yoda.harmon.collector.plist",
                ),
                listOf(
                    "/bin/launchctl",
                    "enable",
                    "system/dev.yoda.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "kickstart",
                    "-k",
                    "system/dev.yoda.harmon.collector",
                ),
                listOf(
                    "/bin/launchctl",
                    "bootstrap",
                    "gui/501",
                    "$TEST_HOME/Library/LaunchAgents/dev.yoda.harmon.agent.plist",
                ),
                listOf(
                    "/bin/launchctl",
                    "enable",
                    "gui/501/dev.yoda.harmon.agent",
                ),
                listOf(
                    "/bin/launchctl",
                    "kickstart",
                    "-k",
                    "gui/501/dev.yoda.harmon.agent",
                ),
            ),
            launchctl,
        )
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
        fileSystem.files[paths.agentPlist] = "agent plist"
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
            fileSystem = fileSystem,
            commandRunner = runner,
        ).run()

        assertFalse(paths.agentPlist in fileSystem.files)
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
        fileSystem.files[SystemSetupPaths.collectorBinary] = "collector"
        fileSystem.files[SystemSetupPaths.legacyCollectorBinary] = "legacy collector"
        fileSystem.files[SystemSetupPaths.socket] = "socket"
        fileSystem.files["${SystemSetupPaths.logDirectory}/collector.log"] = "log"
        val uninstall = SystemUninstall(
            targetUserId = 501u,
            fileSystem = fileSystem,
            commandRunner = runner,
        )

        uninstall.run()
        uninstall.run()

        assertFalse(SystemSetupPaths.collectorPlist in fileSystem.files)
        assertFalse(SystemSetupPaths.collectorBinary in fileSystem.files)
        assertFalse(SystemSetupPaths.legacyCollectorBinary in fileSystem.files)
        assertFalse(SystemSetupPaths.socket in fileSystem.files)
        assertEquals(
            "log",
            fileSystem.files["${SystemSetupPaths.logDirectory}/collector.log"],
        )
        assertEquals(
            2,
            runner.invocations.count {
                it.arguments == listOf(
                    "/bin/launchctl",
                    "bootout",
                    "system/dev.yoda.harmon.collector",
                )
            },
        )
        assertEquals(
            2,
            runner.invocations.count {
                it.arguments == listOf(
                    "/bin/launchctl",
                    "bootout",
                    "gui/501/dev.yoda.harmon.agent",
                )
            },
        )
    }
}

private class WorkflowCommandRunner(
    private val fileSystem: WorkflowFileSystem? = null,
) : CommandRunner {
    val invocations = mutableListOf<CommandInvocation>()
    val pathsPresentWhenInvoked = mutableListOf<Pair<String, Set<String>>>()

    override fun run(invocation: CommandInvocation): CommandResult {
        invocations += invocation
        val arguments = invocation.arguments
        pathsPresentWhenInvoked += arguments.first() to
            (fileSystem?.files?.keys?.toSet() ?: emptySet())
        return when {
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
