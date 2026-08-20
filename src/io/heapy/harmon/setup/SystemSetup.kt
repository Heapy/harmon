package io.heapy.harmon.setup

class SystemSetup(
    private val validated: ValidatedInstallResources,
    private val targetUserId: UInt,
    private val targetGroupId: UInt,
    private val targetHome: String,
    private val wheelGroupId: UInt,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
    private val useStagedAgentPlist: Boolean = false,
) {
    fun run() {
        val userPaths = UserSetupPaths.forHome(targetHome)
        val agentPlist = selectAgentPlist(userPaths)

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

        replaceServices(agentPlist)
    }

    private fun selectAgentPlist(userPaths: UserSetupPaths): String {
        val agentPlist = if (useStagedAgentPlist) {
            userPaths.stagedAgentPlist
        } else {
            userPaths.agentPlist
        }
        if (!fileSystem.isRegularFile(agentPlist) || !fileSystem.isReadable(agentPlist)) {
            val kind = if (useStagedAgentPlist) "staged" else "installed"
            throw SetupException(
                "The $kind user LaunchAgent plist is missing or unreadable at '$agentPlist'. " +
                    "Run the user phase before --system.",
            )
        }
        return agentPlist
    }

    private fun replaceServices(agentPlist: String) {
        val collectorService = "system/$COLLECTOR_LABEL"
        val previousCollectorService = "system/$PREVIOUS_COLLECTOR_LABEL"
        val agentDomain = "gui/$targetUserId"
        val agentService = "$agentDomain/$AGENT_LABEL"
        val previousAgentService = "$agentDomain/$PREVIOUS_AGENT_LABEL"
        val legacyAgentService = "$agentDomain/$LEGACY_AGENT_LABEL"

        bootoutIfLoaded(collectorService)
        bootoutIfLoaded(previousCollectorService)
        bootoutIfLoaded(agentService)
        bootoutIfLoaded(previousAgentService)
        bootoutIfLoaded(legacyAgentService)
        fileSystem.removeFileIfExists(SystemSetupPaths.previousCollectorPlist)
        fileSystem.removeFileIfExists(SystemSetupPaths.legacyCollectorBinary)
        commandRunner.enableDisabledServices(
            listOf(previousCollectorService),
            ignoreUnreadableSnapshots = true,
        )

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
            listOf("/bin/launchctl", "bootstrap", agentDomain, agentPlist),
        )
        commandRunner.requireSuccess(
            listOf("/bin/launchctl", "kickstart", "-k", agentService),
        )
    }

    private fun bootoutIfLoaded(service: String) = commandRunner.bootoutIfLoaded(service)
}
