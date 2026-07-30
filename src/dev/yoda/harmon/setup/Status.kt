package dev.yoda.harmon.setup

import dev.yoda.harmon.BuildInfo
import dev.yoda.harmon.ipc.CollectorClient
import dev.yoda.harmon.util.failureDescription
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.geteuid
import platform.posix.getenv

data class BinaryVersionObservation(
    val path: String,
    val version: String? = null,
    val error: String? = null,
)

data class ProtocolObservation(
    val socket: String,
    val version: Int? = null,
    val error: String? = null,
)

data class LaunchdServiceObservation(
    val service: String,
    val loaded: Boolean,
    val state: String? = null,
    val processId: Int? = null,
    val program: String? = null,
    val error: String? = null,
) {
    val running: Boolean
        get() = loaded && state == "running"
}

data class HarmonStatusSnapshot(
    val runningCliVersion: String,
    val runningCliPath: String,
    val sourceCollector: BinaryVersionObservation,
    val installedAgent: BinaryVersionObservation,
    val installedCollector: BinaryVersionObservation,
    val expectedProtocol: Int,
    val liveProtocol: ProtocolObservation,
    val agentService: LaunchdServiceObservation,
    val collectorService: LaunchdServiceObservation,
    val expectedAgentProgram: String,
    val expectedCollectorProgram: String,
)

data class HarmonStatusReport(
    val snapshot: HarmonStatusSnapshot,
    val issues: List<String>,
) {
    val exitCode: Int
        get() = if (issues.isEmpty()) 0 else 1

    fun render(): String = buildString {
        appendLine("Harmon status")
        appendLine("  running CLI:        ${snapshot.runningCliVersion} (${snapshot.runningCliPath})")
        appendLine("  source collector:   ${snapshot.sourceCollector.description()}")
        appendLine("  installed agent:    ${snapshot.installedAgent.description()}")
        appendLine("  installed collector: ${snapshot.installedCollector.description()}")
        appendLine(
            "  collector protocol: expected ${snapshot.expectedProtocol}, " +
                snapshot.liveProtocol.description(),
        )
        appendLine("  agent service:      ${snapshot.agentService.description()}")
        appendLine("  collector service:  ${snapshot.collectorService.description()}")
        if (issues.isEmpty()) {
            appendLine()
            append("Installation is healthy.")
        } else {
            appendLine()
            appendLine("Problems:")
            issues.forEach { issue -> appendLine("  - $issue") }
        }
    }

    private fun BinaryVersionObservation.description(): String =
        version?.let { "$it ($path)" }
            ?: "unavailable ($path: ${error.orEmpty().oneLine()})"

    private fun ProtocolObservation.description(): String =
        version?.let { "live $it ($socket)" }
            ?: "unavailable ($socket: ${error.orEmpty().oneLine()})"

    private fun LaunchdServiceObservation.description(): String {
        if (!loaded) {
            return "unloaded (${error.orEmpty().oneLine()})"
        }
        val details = buildList {
            state?.let { add("state $it") }
            processId?.let { add("pid $it") }
            program?.let { add("program $it") }
        }
        return details.joinToString().ifEmpty { "loaded" }
    }
}

object StatusEvaluator {
    fun evaluate(snapshot: HarmonStatusSnapshot): HarmonStatusReport {
        val issues = mutableListOf<String>()
        val sourceVersion = snapshot.runningCliVersion
        val sourceCollectorVersion = snapshot.sourceCollector.version
        if (sourceCollectorVersion == null) {
            issues += actionable(
                "The paired source collector could not be inspected: " +
                    snapshot.sourceCollector.error.orEmpty().oneLine() + ".",
            )
        } else if (sourceCollectorVersion != sourceVersion) {
            issues += actionable(
                "The running CLI is $sourceVersion but its source collector is " +
                    "$sourceCollectorVersion; the release pair is inconsistent.",
            )
        }

        inspectInstalledCopy(
            label = "agent bundle",
            observation = snapshot.installedAgent,
            sourceVersion = sourceVersion,
            issues = issues,
        )
        inspectInstalledCopy(
            label = "collector helper",
            observation = snapshot.installedCollector,
            sourceVersion = sourceVersion,
            issues = issues,
        )
        val agentVersion = snapshot.installedAgent.version
        val collectorVersion = snapshot.installedCollector.version
        if (agentVersion != null && collectorVersion != null && agentVersion != collectorVersion) {
            issues += actionable(
                "The installed agent is $agentVersion but the installed collector is " +
                    "$collectorVersion; the installed pair is mixed.",
            )
        }

        val liveVersion = snapshot.liveProtocol.version
        if (liveVersion == null) {
            issues += actionable(
                "The collector socket is not healthy: " +
                    snapshot.liveProtocol.error.orEmpty().oneLine() + ".",
            )
        } else if (liveVersion != snapshot.expectedProtocol) {
            issues += actionable(
                "The loaded collector speaks protocol $liveVersion but this CLI expects " +
                    "${snapshot.expectedProtocol}; the daemon may still be running an old copy.",
            )
        }

        inspectService(
            label = "agent",
            observation = snapshot.agentService,
            expectedProgram = snapshot.expectedAgentProgram,
            issues = issues,
        )
        inspectService(
            label = "collector",
            observation = snapshot.collectorService,
            expectedProgram = snapshot.expectedCollectorProgram,
            issues = issues,
        )
        return HarmonStatusReport(snapshot, issues)
    }

    private fun inspectInstalledCopy(
        label: String,
        observation: BinaryVersionObservation,
        sourceVersion: String,
        issues: MutableList<String>,
    ) {
        val version = observation.version
        if (version == null) {
            issues += actionable(
                "The installed $label could not be inspected at ${observation.path}: " +
                    observation.error.orEmpty().oneLine() + ".",
            )
        } else if (version != sourceVersion) {
            issues += actionable(
                "Homebrew/source is $sourceVersion but the installed $label is $version; " +
                    "the latest upgrade has not been installed into the services.",
            )
        }
    }

    private fun inspectService(
        label: String,
        observation: LaunchdServiceObservation,
        expectedProgram: String,
        issues: MutableList<String>,
    ) {
        when {
            !observation.loaded -> issues += actionable(
                "The $label service is not loaded: ${observation.error.orEmpty().oneLine()}.",
            )
            !observation.running -> issues += actionable(
                "The $label service is loaded but its state is " +
                    "'${observation.state ?: "unknown"}'.",
            )
            observation.program != expectedProgram -> issues += actionable(
                "The $label service runs '${observation.program ?: "an unknown program"}' " +
                    "instead of '$expectedProgram'.",
            )
        }
    }

    private fun actionable(message: String): String =
        "${message.trim()} Run 'harmon setup'."
}

class HarmonStatus(
    private val commandRunner: CommandRunner = PosixCommandRunner,
    private val fileSystem: SetupFileSystem = PosixSetupFileSystem,
    private val resourcePathProbe: ResourcePathProbe = PosixResourcePathProbe,
    private val executablePath: () -> String = ExecutablePath::current,
    private val effectiveUserId: () -> UInt = ::statusEffectiveUserId,
    private val homeDirectory: () -> String = ::statusHomeDirectory,
    private val protocolProbe: (String) -> Int = { socket ->
        CollectorClient(socket).probeProtocolVersion()
    },
) {
    fun run(): Int {
        val userId = effectiveUserId()
        SetupValidation.validateUserPhase(userId)
        val self = executablePath()
        val resources = ResourceLocator.locate(self, resourcePathProbe)
        val userPaths = UserSetupPaths.forHome(homeDirectory())
        val sourceCollector = inspectBinary(
            path = resources.collectorBinary,
            expectedName = "harmon-collector",
        )
        val installedAgent = inspectBinary(
            path = userPaths.installedAgent,
            expectedName = "harmon",
        )
        val installedCollector = inspectBinary(
            path = SystemSetupPaths.collectorBinary,
            expectedName = "harmon-collector",
        )
        val liveProtocol = try {
            ProtocolObservation(
                socket = SystemSetupPaths.socket,
                version = protocolProbe(SystemSetupPaths.socket),
            )
        } catch (failure: Throwable) {
            ProtocolObservation(
                socket = SystemSetupPaths.socket,
                error = failureDescription(failure),
            )
        }
        val agentServiceName = "gui/$userId/$AGENT_LABEL"
        val collectorServiceName = "system/$COLLECTOR_LABEL"
        val agentService = inspectService(agentServiceName)
        val collectorService = inspectService(collectorServiceName)

        val report = StatusEvaluator.evaluate(
            HarmonStatusSnapshot(
                runningCliVersion = BuildInfo.VERSION,
                runningCliPath = self,
                sourceCollector = sourceCollector,
                installedAgent = installedAgent,
                installedCollector = installedCollector,
                expectedProtocol = BuildInfo.COLLECTOR_PROTOCOL_VERSION,
                liveProtocol = liveProtocol,
                agentService = agentService,
                collectorService = collectorService,
                expectedAgentProgram = userPaths.installedAgent,
                expectedCollectorProgram = SystemSetupPaths.collectorBinary,
            ),
        )
        println(report.render())
        return report.exitCode
    }

    private fun inspectBinary(
        path: String,
        expectedName: String,
    ): BinaryVersionObservation {
        if (!fileSystem.isRegularFile(path)) {
            return BinaryVersionObservation(path, error = "file is missing")
        }
        val result = commandRunner.run(listOf(path, "--version"))
        if (!result.successful) {
            return BinaryVersionObservation(
                path,
                error = result.output.ifBlank { "exit code ${result.exitCode}" },
            )
        }
        val version = parseBinaryVersion(result.output, expectedName)
            ?: return BinaryVersionObservation(
                path,
                error = "unexpected version output '${result.output.trim().oneLine()}'",
            )
        return BinaryVersionObservation(path, version = version)
    }

    private fun inspectService(service: String): LaunchdServiceObservation {
        val result = commandRunner.run(
            listOf("/bin/launchctl", "print", service),
        )
        return parseLaunchctlPrint(service, result)
    }
}

fun parseBinaryVersion(output: String, expectedName: String): String? {
    val prefix = "$expectedName "
    val value = output.trim()
    return value.takeIf { it.startsWith(prefix) && it.length > prefix.length }
        ?.removePrefix(prefix)
        ?.takeIf { it.none(Char::isWhitespace) }
}

fun parseLaunchctlPrint(
    service: String,
    result: CommandResult,
): LaunchdServiceObservation {
    if (!result.successful) {
        return LaunchdServiceObservation(
            service = service,
            loaded = false,
            error = result.output.ifBlank { "exit code ${result.exitCode}" },
        )
    }
    var state: String? = null
    var processId: Int? = null
    var program: String? = null
    result.output.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            state == null && line.startsWith("state = ") ->
                state = line.removePrefix("state = ").trim()
            processId == null && line.startsWith("pid = ") -> {
                processId = line.removePrefix("pid = ").trim().toIntOrNull()
            }
            program == null && line.startsWith("program = ") ->
                program = line.removePrefix("program = ").trim()
        }
    }
    return LaunchdServiceObservation(
        service = service,
        loaded = true,
        state = state,
        processId = processId,
        program = program,
    )
}

private fun String.oneLine(): String =
    lineSequence().joinToString(" ") { it.trim() }.trim().ifEmpty { "unknown error" }

@OptIn(ExperimentalForeignApi::class)
private fun statusEffectiveUserId(): UInt = geteuid()

@OptIn(ExperimentalForeignApi::class)
private fun statusHomeDirectory(): String =
    getenv("HOME")?.toKString()
        ?: throw SetupException("HOME is not set")
