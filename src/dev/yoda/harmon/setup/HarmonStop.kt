package dev.yoda.harmon.setup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.geteuid
import platform.posix.getenv

data class StopRequest(
    val system: Boolean,
    val userId: UInt? = null,
)

object StopValidation {
    fun validateUserPhase(effectiveUserId: UInt, requestedUserId: UInt?) {
        if (requestedUserId != null) {
            throw SetupException("--uid is valid only with --system")
        }
        if (effectiveUserId == 0u) {
            throw SetupException(
                "Run 'harmon stop' as the login user; it requests sudo once.",
            )
        }
    }

    fun validateSystemPhase(
        effectiveUserId: UInt,
        requestedUserId: UInt?,
    ): UInt {
        if (effectiveUserId != 0u) {
            throw SetupException("'harmon stop --system' must run as root")
        }
        val userId = requestedUserId
            ?: throw SetupException("--uid is required with --system")
        if (userId == 0u) {
            throw SetupException("--uid must identify a non-root login user")
        }
        return userId
    }
}

class HarmonStop(
    private val commandRunner: CommandRunner = PosixCommandRunner,
    private val fileSystem: SetupFileSystem = PosixSetupFileSystem,
    private val effectiveUserId: () -> UInt = ::stopEffectiveUserId,
    private val homeDirectory: () -> String = ::stopHomeDirectory,
    private val executablePath: () -> String = ExecutablePath::current,
) {
    fun run(request: StopRequest) {
        if (request.system) {
            runSystem(request)
        } else {
            runUser(request)
        }
    }

    private fun runUser(request: StopRequest) {
        val userId = effectiveUserId()
        StopValidation.validateUserPhase(userId, request.userId)
        UserStop(
            executablePath = executablePath(),
            userId = userId,
            home = homeDirectory(),
            fileSystem = fileSystem,
            commandRunner = commandRunner,
        ).run()
        println("Harmon services are stopped and disabled.")
        println("Run 'harmon setup' to start them again.")
    }

    private fun runSystem(request: StopRequest) {
        val targetUserId = StopValidation.validateSystemPhase(
            effectiveUserId = effectiveUserId(),
            requestedUserId = request.userId,
        )
        SystemStop(
            targetUserId = targetUserId,
            commandRunner = commandRunner,
        ).run()
    }
}

class UserStop(
    private val executablePath: String,
    private val userId: UInt,
    private val home: String,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun run() {
        val userDomain = "gui/$userId"
        commandRunner.disableAndBootout(
            listOf(
                "$userDomain/$AGENT_LABEL",
                "$userDomain/$LEGACY_AGENT_LABEL",
            ),
        )
        fileSystem.removeFileIfExists(UserSetupPaths.forHome(home).liveUiEndpoint)
        commandRunner.requireSuccess(
            arguments = listOf(
                "/usr/bin/sudo",
                executablePath,
                "stop",
                "--system",
                "--uid",
                userId.toString(),
            ),
            captureOutput = false,
        )
    }
}

class SystemStop(
    private val targetUserId: UInt,
    private val commandRunner: CommandRunner,
) {
    fun run() {
        val userDomain = "gui/$targetUserId"
        commandRunner.disableAndBootout(
            listOf(
                "system/$COLLECTOR_LABEL",
                "$userDomain/$AGENT_LABEL",
                "$userDomain/$LEGACY_AGENT_LABEL",
            ),
        )
    }
}

private fun CommandRunner.disableAndBootout(services: List<String>) {
    services.forEach { service ->
        requireSuccess(listOf("/bin/launchctl", "disable", service))
    }
    services.forEach { service ->
        val result = run(listOf("/bin/launchctl", "bootout", service))
        if (!result.successful && !isMissingLaunchdJob(result)) {
            throw CommandExecutionException(result)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun stopEffectiveUserId(): UInt = geteuid()

@OptIn(ExperimentalForeignApi::class)
private fun stopHomeDirectory(): String =
    getenv("HOME")?.toKString()
        ?: throw SetupException("HOME is not set")
