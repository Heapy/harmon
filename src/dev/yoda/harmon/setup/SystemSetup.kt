package dev.yoda.harmon.setup

class SystemSetup(
    private val validated: ValidatedInstallResources,
    private val targetUserId: UInt,
    private val targetGroupId: UInt,
    private val targetHome: String,
    private val wheelGroupId: UInt,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun run() {
        val userPaths = UserSetupPaths.forHome(targetHome)
        if (!fileSystem.isRegularFile(userPaths.agentPlist) ||
            !fileSystem.isReadable(userPaths.agentPlist)
        ) {
            throw SetupException(
                "The user LaunchAgent plist is missing or unreadable at " +
                    "'${userPaths.agentPlist}'. Run the user phase before --system.",
            )
        }

        val rootOwnership = FileOwnership(userId = 0u, groupId = wheelGroupId)
        val rootDirectory = InstalledFileAttributes(
            mode = InstallModes.PUBLIC_DIRECTORY,
            ownership = rootOwnership,
        )
        fileSystem.ensureDirectory(SystemSetupPaths.helperDirectory, rootDirectory)
        fileSystem.ensureDirectory(SystemSetupPaths.launchDaemonsDirectory, rootDirectory)
        fileSystem.ensureDirectory(SystemSetupPaths.logDirectory, rootDirectory)
        fileSystem.copyFileAtomically(
            source = validated.resources.collectorBinary,
            target = SystemSetupPaths.collectorBinary,
            attributes = InstalledFileAttributes(
                mode = InstallModes.EXECUTABLE,
                ownership = rootOwnership,
            ),
        )
        AtomicPlistWriter(fileSystem, commandRunner).write(
            target = SystemSetupPaths.collectorPlist,
            job = LaunchdJobs.collector(
                CollectorLaunchdSettings(
                    collectorBinary = SystemSetupPaths.collectorBinary,
                    socket = SystemSetupPaths.socket,
                    allowedUserId = targetUserId,
                    allowedGroupId = targetGroupId,
                    logDirectory = SystemSetupPaths.logDirectory,
                ),
            ),
            attributes = InstalledFileAttributes(
                mode = InstallModes.SYSTEM_PLIST,
                ownership = rootOwnership,
            ),
        )

        replaceServices(userPaths)
    }

    private fun replaceServices(userPaths: UserSetupPaths) {
        val collectorService = "system/$COLLECTOR_LABEL"
        val agentDomain = "gui/$targetUserId"
        val agentService = "$agentDomain/$AGENT_LABEL"
        val legacyAgentService = "$agentDomain/$LEGACY_AGENT_LABEL"

        bootoutIfLoaded(collectorService)
        bootoutIfLoaded(agentService)
        bootoutIfLoaded(legacyAgentService)
        fileSystem.removeFileIfExists(SystemSetupPaths.legacyCollectorBinary)

        commandRunner.requireSuccess(
            listOf("/bin/launchctl", "enable", collectorService),
        )
        commandRunner.requireSuccess(
            listOf("/bin/launchctl", "bootstrap", "system", SystemSetupPaths.collectorPlist),
        )
        commandRunner.requireSuccess(
            listOf("/bin/launchctl", "kickstart", "-k", collectorService),
        )
        commandRunner.requireSuccess(
            listOf("/bin/launchctl", "enable", agentService),
        )
        commandRunner.requireSuccess(
            listOf("/bin/launchctl", "bootstrap", agentDomain, userPaths.agentPlist),
        )
        commandRunner.requireSuccess(
            listOf("/bin/launchctl", "kickstart", "-k", agentService),
        )
    }

    private fun bootoutIfLoaded(service: String) {
        val result = commandRunner.run(
            listOf("/bin/launchctl", "bootout", service),
        )
        if (!result.successful && !isMissingLaunchdJob(result)) {
            throw CommandExecutionException(result)
        }
    }
}

fun isMissingLaunchdJob(result: CommandResult): Boolean {
    if (result.exitCode == LAUNCHCTL_NO_SUCH_PROCESS_EXIT_CODE) {
        return true
    }
    val output = result.output.lowercase()
    return "no such process" in output ||
        "could not find service" in output ||
        "service not found" in output
}

private const val LAUNCHCTL_NO_SUCH_PROCESS_EXIT_CODE = 3
