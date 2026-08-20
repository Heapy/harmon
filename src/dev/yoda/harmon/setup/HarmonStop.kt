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
    private val accountDirectory: AccountDirectory = PosixAccountDirectory,
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
            targetHome = accountDirectory.homeDirectory(targetUserId),
            fileSystem = fileSystem,
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
        // The root phase stops all three labels, including the user agent, so a declined password
        // leaves launchd exactly as it was instead of a half-stopped pair.
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
        fileSystem.removeFileIfExists(UserSetupPaths.forHome(home).liveUiEndpoint)
    }
}

class SystemStop(
    private val targetUserId: UInt,
    private val targetHome: String?,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun run() {
        val userDomain = "gui/$targetUserId"
        val userPaths = targetHome?.let(UserSetupPaths::forHome)
        commandRunner.disableAndBootout(
            listOf(
                StoppedService("system/$COLLECTOR_LABEL", SystemSetupPaths.collectorPlist),
                StoppedService("$userDomain/$AGENT_LABEL", userPaths?.agentPlist),
                StoppedService("$userDomain/$LEGACY_AGENT_LABEL", userPaths?.legacyAgentPlist),
            ),
            fileSystem,
        )
    }
}

/** A launchd target and the job definition that would make launchd load it again. */
private data class StoppedService(val target: String, val plist: String?)

/**
 * Every disable is written before the first bootout, so an unexpected failure aborts while all
 * three services are still loaded and running. A domain or job launchd does not know needs no
 * override to stay unloadable, so those failures are tolerated rather than fatal.
 */
private fun CommandRunner.disableAndBootout(
    services: List<StoppedService>,
    fileSystem: SetupFileSystem,
) {
    val disabled = mutableListOf<String>()
    services.forEach { service ->
        // launchd keeps a row for every label it is told about and cannot delete one, so a label
        // with no job definition is left unnamed instead of being disabled against nothing.
        if (service.plist == null || !fileSystem.isRegularFile(service.plist)) {
            return@forEach
        }
        val result = run(listOf("/bin/launchctl", "disable", service.target))
        when {
            result.successful -> disabled += service.target
            isMissingLaunchdTarget(result) -> Unit
            else -> throw SetupException(partialStopMessage(service.target, result, disabled))
        }
    }
    services.forEach { service -> bootoutIfLoaded(service.target) }
}

private fun partialStopMessage(
    service: String,
    result: CommandResult,
    disabled: List<String>,
): String = buildString {
    append("Unable to disable $service: ")
    append(result.output.lineSequence().joinToString(" ") { it.trim() }.trim().ifEmpty {
        "exit code ${result.exitCode}"
    })
    append(". Nothing was unloaded")
    if (disabled.isNotEmpty()) {
        append(", but ${disabled.joinToString()} ${if (disabled.size == 1) "is" else "are"} " +
            "now disabled")
    }
    append(". Run 'harmon stop' again to finish, or 'harmon setup' to start Harmon.")
}

@OptIn(ExperimentalForeignApi::class)
private fun stopEffectiveUserId(): UInt = geteuid()

@OptIn(ExperimentalForeignApi::class)
private fun stopHomeDirectory(): String =
    getenv("HOME")?.toKString()
        ?: throw SetupException("HOME is not set")
