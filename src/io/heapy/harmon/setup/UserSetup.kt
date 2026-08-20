package io.heapy.harmon.setup

import io.heapy.harmon.BuildInfo

class UserSetup(
    private val validated: ValidatedInstallResources,
    private val userId: UInt,
    private val groupId: UInt,
    private val home: String,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun run(): UserSetupPaths {
        val paths = UserSetupPaths.forHome(home)
        createDirectories(paths)
        installApplication(paths)
        installConfiguration(paths)
        try {
            stageAgentPlist(paths)
            refreshApplicationRegistration(paths)
            runSystemPhase()
        } catch (failure: Throwable) {
            removeStagedAgentPlistBestEffort(paths)
            throw failure
        }
        publishAgentPlist(paths)
        clearCompatibilityAgentOverrides()
        removeLegacyCommandLink(paths)
        return paths
    }

    private fun createDirectories(paths: UserSetupPaths) {
        val privateDirectory = InstalledFileAttributes(InstallModes.USER_DIRECTORY)
        val publicDirectory = InstalledFileAttributes(InstallModes.PUBLIC_DIRECTORY)
        fileSystem.ensureDirectory(paths.supportDirectory, privateDirectory)
        fileSystem.ensureDirectory(paths.appBundle, publicDirectory)
        fileSystem.ensureDirectory(paths.appContents, publicDirectory)
        fileSystem.ensureDirectory(paths.appMacOsDirectory, publicDirectory)
        fileSystem.ensureDirectory(paths.appResourcesDirectory, publicDirectory)
        fileSystem.ensureDirectory(paths.configDirectory, privateDirectory)
        fileSystem.ensureDirectory(paths.logDirectory, privateDirectory)
        fileSystem.ensureDirectory(paths.launchAgentsDirectory, privateDirectory)
    }

    private fun installApplication(paths: UserSetupPaths) {
        fileSystem.copyFileAtomically(
            source = validated.resources.agentBinary,
            target = paths.installedAgent,
            attributes = InstalledFileAttributes(InstallModes.EXECUTABLE),
        )
        val infoPlist = renderApplicationInfoPlist(
            fileSystem.readText(validated.resources.infoPlist),
        )
        fileSystem.writeTextAtomically(
            target = paths.installedInfoPlist,
            content = infoPlist,
            attributes = InstalledFileAttributes(InstallModes.RESOURCE),
            validateTemporaryFile = { temporary ->
                commandRunner.requireSuccess(
                    listOf("/usr/bin/plutil", "-lint", temporary),
                )
            },
        )
        fileSystem.copyFileAtomically(
            source = validated.resources.icon,
            target = paths.installedIcon,
            attributes = InstalledFileAttributes(InstallModes.RESOURCE),
        )

        val identities = commandRunner.requireSuccess(
            listOf("/usr/bin/security", "find-identity", "-v", "-p", "codesigning"),
        ).output
        val identity = selectSigningIdentity(identities) ?: "-"
        commandRunner.requireSuccess(
            listOf("/usr/bin/codesign", "--force", "--sign", identity, paths.appBundle),
        )
        commandRunner.requireSuccess(
            listOf("/usr/bin/codesign", "--verify", "--deep", "--strict", paths.appBundle),
        )
    }

    private fun installConfiguration(paths: UserSetupPaths) {
        if (!fileSystem.exists(paths.config)) {
            fileSystem.copyFileAtomically(
                source = validated.resources.exampleConfig,
                target = paths.config,
                attributes = InstalledFileAttributes(InstallModes.USER_SECRET),
            )
        } else {
            if (!fileSystem.isRegularFile(paths.config)) {
                throw SetupException("Existing config is not a regular file: '${paths.config}'")
            }
            fileSystem.setMode(paths.config, InstallModes.USER_SECRET)
        }
    }

    private fun stageAgentPlist(paths: UserSetupPaths) {
        AtomicPlistWriter(fileSystem, commandRunner).write(
            target = paths.stagedAgentPlist,
            job = LaunchdJobs.agent(
                AgentLaunchdPaths(
                    agentBinary = paths.installedAgent,
                    config = paths.config,
                    logDirectory = paths.logDirectory,
                ),
            ),
            attributes = InstalledFileAttributes(InstallModes.USER_PLIST),
        )
    }

    private fun removeCompatibilityAgentPlists(paths: UserSetupPaths) {
        fileSystem.removeFileIfExists(paths.previousAgentPlist)
        fileSystem.removeFileIfExists(paths.legacyAgentPlist)
    }

    private fun publishAgentPlist(paths: UserSetupPaths) {
        fileSystem.copyFileAtomically(
            source = paths.stagedAgentPlist,
            target = paths.agentPlist,
            attributes = InstalledFileAttributes(InstallModes.USER_PLIST),
            validateTemporaryFile = { temporary ->
                commandRunner.requireSuccess(
                    listOf("/usr/bin/plutil", "-lint", temporary),
                )
            },
            // Never leave compatibility and current labels simultaneously discoverable at login.
            beforePublish = { removeCompatibilityAgentPlists(paths) },
        )
        fileSystem.removeFileIfExists(paths.stagedAgentPlist)
    }

    private fun clearCompatibilityAgentOverrides() {
        val agentDomain = "gui/$userId"
        commandRunner.enableDisabledServices(
            listOf(
                "$agentDomain/$PREVIOUS_AGENT_LABEL",
                "$agentDomain/$LEGACY_AGENT_LABEL",
            ),
            ignoreUnreadableSnapshots = true,
        )
    }

    private fun removeStagedAgentPlistBestEffort(paths: UserSetupPaths) {
        try {
            fileSystem.removeFileIfExists(paths.stagedAgentPlist)
        } catch (_: Throwable) {
            // Preserve the failure that prevented setup from reaching its publish step.
        }
    }

    private fun removeLegacyCommandLink(paths: UserSetupPaths) {
        if (fileSystem.isSymbolicLink(paths.legacyCommandLink)) {
            val target = fileSystem.readSymbolicLink(paths.legacyCommandLink)
            if (shouldRemoveLegacyCommandLink(target, paths)) {
                fileSystem.removeFileIfExists(paths.legacyCommandLink)
            }
        }
    }

    private fun refreshApplicationRegistration(paths: UserSetupPaths) {
        if (fileSystem.exists(LAUNCH_SERVICES_REGISTRAR)) {
            commandRunner.requireSuccess(
                listOf(LAUNCH_SERVICES_REGISTRAR, "-f", paths.appBundle),
            )
        }
        restartIfRunning("usernoted")
        restartIfRunning("NotificationCenter")
    }

    private fun restartIfRunning(processName: String) {
        val result = commandRunner.run(listOf("/usr/bin/killall", processName))
        if (!result.successful && result.exitCode != KILLALL_NOT_FOUND_EXIT_CODE) {
            throw CommandExecutionException(result)
        }
    }

    private fun runSystemPhase() {
        commandRunner.requireSuccess(
            arguments = listOf(
                "/usr/bin/sudo",
                validated.executablePath,
                "setup",
                "--system",
                "--staged-agent",
                "--uid",
                userId.toString(),
                "--gid",
                groupId.toString(),
            ),
            captureOutput = false,
        )
    }

    private companion object {
        const val LAUNCH_SERVICES_REGISTRAR =
            "/System/Library/Frameworks/CoreServices.framework/Frameworks/" +
                "LaunchServices.framework/Support/lsregister"
        const val KILLALL_NOT_FOUND_EXIT_CODE = 1
    }
}

fun renderApplicationInfoPlist(
    source: String,
    version: String = BuildInfo.VERSION,
): String {
    val rendered = if (HARMON_VERSION_TOKEN in source) {
        source.replace(HARMON_VERSION_TOKEN, version)
    } else {
        source
    }
    if (HARMON_VERSION_TOKEN in rendered) {
        throw SetupException("Harmon.Info.plist still contains an unresolved version token")
    }
    val versionOccurrences = rendered.windowed(
        size = "<string>$version</string>".length,
        step = 1,
        partialWindows = false,
    ).count { it == "<string>$version</string>" }
    if (versionOccurrences < 2) {
        throw SetupException(
            "Harmon.Info.plist does not carry version $version in both bundle version fields",
        )
    }
    return rendered
}

fun selectSigningIdentity(securityOutput: String): String? =
    securityOutput.lineSequence()
        .mapNotNull { line -> SIGNING_IDENTITY_PATTERN.find(line)?.groupValues?.get(1) }
        .firstOrNull()

fun shouldRemoveLegacyCommandLink(
    target: String?,
    paths: UserSetupPaths,
): Boolean {
    if (target == null) {
        return false
    }
    return target == paths.installedAgent ||
        target.endsWith("/Harmon.app/Contents/MacOS/harmon")
}

private const val HARMON_VERSION_TOKEN = "@HARMON_VERSION@"
private val SIGNING_IDENTITY_PATTERN =
    Regex("""^\s*\d+\)\s+([0-9A-Fa-f]{40})\s+""")
