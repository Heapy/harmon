package io.heapy.harmon.setup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.geteuid
import platform.posix.getenv

data class StopRequest(
    val system: Boolean,
    val userId: UInt? = null,
)

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
        TwoPhaseCommandValidation.validateUserPhase(
            commandName = "stop",
            effectiveUserId = userId,
            requestedUserId = request.userId,
        )
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
        val targetUserId = TwoPhaseCommandValidation.validateSystemPhase(
            commandName = "stop",
            effectiveUserId = effectiveUserId(),
            requestedUserId = request.userId,
        )
        val targetHome = accountDirectory.homeDirectory(targetUserId)
            ?: throw SetupException("No local account exists for uid $targetUserId")
        SystemStop(
            targetUserId = targetUserId,
            targetHome = targetHome,
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
        // The root phase stops every managed label, including the user agents, so a declined
        // password leaves launchd exactly as it was instead of a half-stopped installation.
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
    private val targetHome: String,
    private val fileSystem: SetupFileSystem,
    private val commandRunner: CommandRunner,
) {
    fun run() {
        val userDomain = "gui/$targetUserId"
        val userPaths = UserSetupPaths.forHome(targetHome)
        commandRunner.disableAndBootout(
            listOf(
                StoppedService("$userDomain/$AGENT_LABEL", userPaths.agentPlist),
                StoppedService(
                    "$userDomain/$PREVIOUS_AGENT_LABEL",
                    userPaths.previousAgentPlist,
                ),
                StoppedService("$userDomain/$LEGACY_AGENT_LABEL", userPaths.legacyAgentPlist),
                StoppedService("system/$COLLECTOR_LABEL", SystemSetupPaths.collectorPlist),
                StoppedService(
                    "system/$PREVIOUS_COLLECTOR_LABEL",
                    SystemSetupPaths.previousCollectorPlist,
                ),
            ),
            fileSystem,
        )
    }
}

/** A launchd target and the job definition that would make launchd load it again. */
private data class StoppedService(val target: String, val plist: String)

/**
 * Every disable is written before the first bootout, so an unexpected failure aborts while all
 * services are still loaded and running. A failed disable is fatal when its job definition is
 * present; bootout alone remains tolerant of a job launchd does not know.
 */
private fun CommandRunner.disableAndBootout(
    services: List<StoppedService>,
    fileSystem: SetupFileSystem,
) {
    val disabled = mutableListOf<String>()
    services.forEach { service ->
        // launchd keeps a row for every label it is told about and cannot delete one, so a label
        // with no job definition is left unnamed instead of being disabled against nothing.
        if (!fileSystem.isRegularFile(service.plist)) {
            return@forEach
        }
        val result = run(listOf("/bin/launchctl", "disable", service.target))
        if (!result.successful) {
            throw SetupException(partialStopMessage(service.target, result, disabled))
        }
        disabled += service.target
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
