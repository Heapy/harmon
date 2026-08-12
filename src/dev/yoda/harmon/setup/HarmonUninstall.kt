package dev.yoda.harmon.setup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.geteuid
import platform.posix.getenv

data class UninstallRequest(
    val system: Boolean,
    val userId: UInt? = null,
)

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
        UserUninstall(
            executablePath = executablePath(),
            userId = userId,
            home = homeDirectory(),
            fileSystem = fileSystem,
            commandRunner = commandRunner,
        ).run()
        println("Harmon services and deployed binaries were removed.")
        println("Configuration, logs, reports, and sample history were preserved.")
    }

    private fun runSystem(request: UninstallRequest) {
        val targetUserId = UninstallValidation.validateSystemPhase(
            effectiveUserId = effectiveUserId(),
            requestedUserId = request.userId,
        )
        SystemUninstall(
            targetUserId = targetUserId,
            fileSystem = fileSystem,
            commandRunner = commandRunner,
        ).run()
    }
}

class UserUninstall(
    private val executablePath: String,
    private val userId: UInt,
    private val home: String,
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
            arguments = listOf(
                "/usr/bin/sudo",
                executablePath,
                "uninstall",
                "--system",
                "--uid",
                userId.toString(),
            ),
            captureOutput = false,
        )
        fileSystem.removeTreeIfExists(paths.appBundle)
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
