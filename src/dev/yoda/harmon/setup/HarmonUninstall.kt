package dev.yoda.harmon.setup

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
 * history.db and the report temporaries are named nowhere in the code, and SetupFileSystem cannot
 * enumerate a directory, so any explicit file list would be incomplete by construction.
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

object UninstallValidation {
    fun validateUserPhase(effectiveUserId: UInt, requestedUserId: UInt?) {
        if (requestedUserId != null) {
            throw SetupException("--uid is valid only with --system")
        }
        if (effectiveUserId == 0u) {
            throw SetupException(
                "Run 'harmon uninstall' as the login user; it requests sudo once.",
            )
        }
    }

    fun validateSystemPhase(
        effectiveUserId: UInt,
        requestedUserId: UInt?,
    ): UInt {
        if (effectiveUserId != 0u) {
            throw SetupException("'harmon uninstall --system' must run as root")
        }
        val userId = requestedUserId
            ?: throw SetupException("--uid is required with --system")
        if (userId == 0u) {
            throw SetupException("--uid must identify a non-root login user")
        }
        return userId
    }
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
        UninstallValidation.validateUserPhase(userId, request.userId)
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
        val targetUserId = UninstallValidation.validateSystemPhase(
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
        bootoutIfLoaded("gui/$userId/$AGENT_LABEL")
        bootoutIfLoaded("gui/$userId/$LEGACY_AGENT_LABEL")
        fileSystem.removeFileIfExists(paths.agentPlist)
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

    private fun bootoutIfLoaded(service: String) {
        val result = commandRunner.run(
            listOf("/bin/launchctl", "bootout", service),
        )
        if (!result.successful && !isMissingLaunchdJob(result)) {
            throw CommandExecutionException(result)
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
        bootoutIfLoaded("system/$COLLECTOR_LABEL")
        bootoutIfLoaded("gui/$targetUserId/$AGENT_LABEL")
        bootoutIfLoaded("gui/$targetUserId/$LEGACY_AGENT_LABEL")
        fileSystem.removeFileIfExists(SystemSetupPaths.collectorPlist)
        fileSystem.removeFileIfExists(SystemSetupPaths.collectorBinary)
        fileSystem.removeFileIfExists(SystemSetupPaths.legacyCollectorBinary)
        fileSystem.removeFileIfExists(SystemSetupPaths.socket)
        if (purge) {
            UninstallPurge.systemTrees.forEach(fileSystem::removeTreeIfExists)
        }
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

@OptIn(ExperimentalForeignApi::class)
private fun uninstallEffectiveUserId(): UInt = geteuid()

@OptIn(ExperimentalForeignApi::class)
private fun uninstallHomeDirectory(): String =
    getenv("HOME")?.toKString()
        ?: throw SetupException("HOME is not set")
