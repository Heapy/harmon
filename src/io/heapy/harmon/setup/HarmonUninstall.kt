package io.heapy.harmon.setup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.geteuid
import platform.posix.getenv

data class UninstallRequest(
    val system: Boolean,
    val userId: UInt? = null,
    val purge: Boolean = false,
)

/**
 * The trees a purge removes. Whole trees rather than named files: the WAL and SHM sidecars of
 * history.db and the report temporaries carry pid and sequence suffixes, and SetupFileSystem
 * cannot enumerate a directory, so any explicit file list would be incomplete by construction.
 */
object UninstallPurge {
    /** The support tree hosts the running executable, so it is removed last. */
    fun userTrees(paths: UserSetupPaths): List<String> = listOf(
        paths.configDirectory,
        paths.logDirectory,
        paths.supportDirectory,
    )

    val systemTrees: List<String> = listOf(SystemSetupPaths.logDirectory)
}

class HarmonUninstall(
    private val commandRunner: CommandRunner = PosixCommandRunner,
    private val fileSystem: SetupFileSystem = PosixSetupFileSystem,
    private val effectiveUserId: () -> UInt = ::uninstallEffectiveUserId,
    private val homeDirectory: () -> String = ::uninstallHomeDirectory,
    private val executablePath: () -> String = ExecutablePath::current,
) {
    fun run(request: UninstallRequest) {
        if (request.system) {
            runSystem(request)
        } else {
            runUser(request)
        }
    }

    private fun runUser(request: UninstallRequest) {
        val userId = effectiveUserId()
        TwoPhaseCommandValidation.validateUserPhase(
            commandName = "uninstall",
            effectiveUserId = userId,
            requestedUserId = request.userId,
        )
        val home = homeDirectory()
        if (request.purge) {
            // The full blast radius, root paths included, before sudo can prompt for a password.
            println("Removing all Harmon data:")
            UninstallPurge.userTrees(UserSetupPaths.forHome(home)).forEach { println("  $it") }
            UninstallPurge.systemTrees.forEach {
                println("  $it (root-owned, removed via sudo)")
            }
        }
        UserUninstall(
            executablePath = executablePath(),
            userId = userId,
            home = home,
            purge = request.purge,
            fileSystem = fileSystem,
            commandRunner = commandRunner,
        ).run()
        println("Harmon services and deployed binaries were removed.")
        println(
            if (request.purge) {
                "Configuration, logs, reports, and sample history were removed."
            } else {
                "Configuration, logs, reports, and sample history were preserved."
            },
        )
    }

    private fun runSystem(request: UninstallRequest) {
        val targetUserId = TwoPhaseCommandValidation.validateSystemPhase(
            commandName = "uninstall",
            effectiveUserId = effectiveUserId(),
            requestedUserId = request.userId,
        )
        if (request.purge) {
            // The public --system form must not delete a root directory silently.
            println("Removing root-owned Harmon data:")
            UninstallPurge.systemTrees.forEach { println("  $it") }
        }
        SystemUninstall(
            targetUserId = targetUserId,
            purge = request.purge,
            fileSystem = fileSystem,
            commandRunner = commandRunner,
        ).run()
    }
}

class UserUninstall(
    private val executablePath: String,
    private val userId: UInt,
    private val home: String,
    private val purge: Boolean,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun run() {
        val paths = UserSetupPaths.forHome(home)
        val agentDomain = "gui/$userId"
        val services = listOf(
            "$agentDomain/$AGENT_LABEL",
            "$agentDomain/$PREVIOUS_AGENT_LABEL",
            "$agentDomain/$LEGACY_AGENT_LABEL",
        )
        services.forEach(commandRunner::bootoutIfLoaded)
        // The root phase owns override cleanup on success, avoiding a duplicate GUI-domain query.
        // If local cleanup or the re-exec fails, the catch preserves the user-phase guarantee.
        try {
            fileSystem.removeFileIfExists(paths.stagedAgentPlist)
            fileSystem.removeFileIfExists(paths.agentPlist)
            fileSystem.removeFileIfExists(paths.previousAgentPlist)
            fileSystem.removeFileIfExists(paths.legacyAgentPlist)
            fileSystem.removeFileIfExists(paths.liveUiEndpoint)
            removeLegacyCommandLink(paths)

            // Legacy installs may be executing this command from inside Harmon.app.
            commandRunner.requireSuccess(
                arguments = buildList {
                    add("/usr/bin/sudo")
                    add(executablePath)
                    add("uninstall")
                    add("--system")
                    add("--uid")
                    add(userId.toString())
                    if (purge) add("--purge")
                },
                captureOutput = false,
            )
        } catch (failure: Throwable) {
            // The root phase normally clears every override from one domain snapshot. If it
            // never completes, preserve that cleanup guarantee before reporting its failure.
            try {
                commandRunner.enableDisabledServices(services)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }

        // Everything above is recoverable with 'harmon setup'; a purge is not. Deleting only after
        // the privileged phase returns means a declined password leaves user data intact.
        if (purge) {
            UninstallPurge.userTrees(paths).forEach(fileSystem::removeTreeIfExists)
        } else {
            fileSystem.removeTreeIfExists(paths.appBundle)
        }
    }

    private fun removeLegacyCommandLink(paths: UserSetupPaths) {
        if (!fileSystem.isSymbolicLink(paths.legacyCommandLink)) {
            return
        }
        val target = fileSystem.readSymbolicLink(paths.legacyCommandLink)
        if (shouldRemoveLegacyCommandLink(target, paths)) {
            fileSystem.removeFileIfExists(paths.legacyCommandLink)
        }
    }
}

class SystemUninstall(
    private val targetUserId: UInt,
    private val purge: Boolean,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun run() {
        // Root can reach the user's GUI domain, so the public --system form is a complete
        // uninstall of the launchd state rather than half of one.
        val services = listOf(
            "system/$COLLECTOR_LABEL",
            "system/$PREVIOUS_COLLECTOR_LABEL",
            "gui/$targetUserId/$AGENT_LABEL",
            "gui/$targetUserId/$PREVIOUS_AGENT_LABEL",
            "gui/$targetUserId/$LEGACY_AGENT_LABEL",
        )
        services.forEach(commandRunner::bootoutIfLoaded)
        commandRunner.enableDisabledServices(services)
        fileSystem.removeFileIfExists(SystemSetupPaths.collectorPlist)
        fileSystem.removeFileIfExists(SystemSetupPaths.previousCollectorPlist)
        fileSystem.removeFileIfExists(SystemSetupPaths.collectorBinary)
        fileSystem.removeFileIfExists(SystemSetupPaths.legacyCollectorBinary)
        fileSystem.removeFileIfExists(SystemSetupPaths.socket)
        if (purge) {
            UninstallPurge.systemTrees.forEach(fileSystem::removeTreeIfExists)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun uninstallEffectiveUserId(): UInt = geteuid()

@OptIn(ExperimentalForeignApi::class)
private fun uninstallHomeDirectory(): String =
    getenv("HOME")?.toKString()
        ?: throw SetupException("HOME is not set")
