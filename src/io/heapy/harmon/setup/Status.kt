package io.heapy.harmon.setup

import io.heapy.harmon.BuildInfo
import io.heapy.harmon.ipc.CollectorClient
import io.heapy.harmon.util.failureDescription
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
    val notProbed: Boolean = false,
)

/**
 * Whether launchd knows the job. A failed `launchctl print` only proves the job is gone when it
 * says so; every other failure leaves the load state unknown and must not read as an unload.
 */
enum class LaunchdLoadState { LOADED, ABSENT, UNKNOWN }

enum class LaunchdEnablement { ENABLED, DISABLED, UNKNOWN }

data class LaunchdEnablementObservation(
    val state: LaunchdEnablement,
    val error: String? = null,
)

data class LaunchdServiceObservation(
    val service: String,
    val load: LaunchdLoadState,
    val state: String? = null,
    val processId: Int? = null,
    val program: String? = null,
    val error: String? = null,
    val enablement: LaunchdEnablementObservation =
        LaunchdEnablementObservation(LaunchdEnablement.UNKNOWN),
) {
    val loaded: Boolean
        get() = load == LaunchdLoadState.LOADED

    val running: Boolean
        get() = loaded && state == "running"

    val disabled: Boolean
        get() = enablement.state == LaunchdEnablement.DISABLED

    val intentionallyStopped: Boolean
        get() = load == LaunchdLoadState.ABSENT && disabled
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
    val intentionallyStopped: Boolean
        get() = StatusEvaluator.isIntentionallyStopped(snapshot)

    val exitCode: Int
        get() = if (issues.isEmpty() && !intentionallyStopped) 0 else 1

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
        if (intentionallyStopped) {
            appendLine()
            appendLine("Harmon is intentionally stopped.")
            append("Run 'harmon setup' to enable and start both services.")
        }
        if (issues.isNotEmpty()) {
            if (intentionallyStopped) {
                appendLine()
            }
            appendLine()
            appendLine(if (intentionallyStopped) "Other problems:" else "Problems:")
            issues.forEach { issue -> appendLine("  - $issue") }
        } else if (!intentionallyStopped) {
            appendLine()
            append("Installation is healthy.")
        }
    }

    private fun BinaryVersionObservation.description(): String =
        version?.let { "$it ($path)" }
            ?: "unavailable ($path: ${error.orEmpty().oneLine()})"

    private fun ProtocolObservation.description(): String = when {
        notProbed -> "not running (intentionally stopped)"
        version != null -> "live $version ($socket)"
        else -> "unavailable ($socket: ${error.orEmpty().oneLine()})"
    }

    private fun LaunchdServiceObservation.description(): String {
        if (!loaded) {
            if (intentionallyStopped) {
                return "stopped (disabled)"
            }
            val reason = error.orEmpty().oneLine()
            return when (load) {
                LaunchdLoadState.ABSENT -> "unloaded ($reason)"
                else -> "state unknown ($reason)"
            }
        }
        val details = buildList {
            state?.let { add("state $it") }
            processId?.let { add("pid $it") }
            program?.let { add("program $it") }
            when (enablement.state) {
                LaunchdEnablement.DISABLED -> add("disabled")
                LaunchdEnablement.UNKNOWN ->
                    add("enablement unknown (${enablement.error.orEmpty().oneLine()})")
                LaunchdEnablement.ENABLED -> Unit
            }
        }
        return details.joinToString().ifEmpty { "loaded" }
    }
}

object StatusEvaluator {
    /**
     * Disable flags outlive the installation that set them, so a stop is only claimed when both
     * deployed copies are still there to be started again. One predicate, because the socket probe,
     * the issue list, and the rendering all have to agree on the state.
     */
    fun isIntentionallyStopped(snapshot: HarmonStatusSnapshot): Boolean =
        snapshot.installedAgent.version != null &&
            snapshot.installedCollector.version != null &&
            snapshot.agentService.intentionallyStopped &&
            snapshot.collectorService.intentionallyStopped

    fun evaluate(snapshot: HarmonStatusSnapshot): HarmonStatusReport {
        val issues = mutableListOf<String>()
        val intentionallyStopped = isIntentionallyStopped(snapshot)
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

        if (!intentionallyStopped) {
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
        }
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
            observation.intentionallyStopped -> issues += actionable(
                "The $label service is disabled and not loaded.",
            )
            !observation.loaded -> issues += actionable(
                "The $label service is not loaded: ${observation.error.orEmpty().oneLine()}.",
            )
            observation.disabled -> issues += actionable(
                "The $label service is loaded but disabled.",
            )
            !observation.running -> issues += actionable(
                "The $label service is loaded but its state is " +
                    "'${observation.state ?: "unknown"}'.",
            )
            observation.program != expectedProgram -> issues += actionable(
                "The $label service runs '${observation.program ?: "an unknown program"}' " +
                    "instead of '$expectedProgram'.",
            )
            // A running job that will not load after the next boot looks identical to a healthy
            // one, so an uninspectable override is a finding rather than a silent default.
            observation.enablement.state == LaunchdEnablement.UNKNOWN -> issues +=
                "The $label service is running but its persistent enablement could not be " +
                    "inspected: ${observation.enablement.error.orEmpty().oneLine()}."
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
        val agentServiceName = "gui/$userId/$AGENT_LABEL"
        val collectorServiceName = "system/$COLLECTOR_LABEL"
        val unprobed = HarmonStatusSnapshot(
            runningCliVersion = BuildInfo.VERSION,
            runningCliPath = self,
            sourceCollector = sourceCollector,
            installedAgent = installedAgent,
            installedCollector = installedCollector,
            expectedProtocol = BuildInfo.COLLECTOR_PROTOCOL_VERSION,
            liveProtocol = ProtocolObservation(
                socket = SystemSetupPaths.socket,
                error = "not probed because both services are disabled",
                notProbed = true,
            ),
            agentService = inspectService(agentServiceName),
            collectorService = inspectService(collectorServiceName),
            expectedAgentProgram = userPaths.installedAgent,
            expectedCollectorProgram = SystemSetupPaths.collectorBinary,
        )
        // The same predicate the report renders from, so a skipped probe and a stopped headline
        // cannot disagree.
        val snapshot = if (StatusEvaluator.isIntentionallyStopped(unprobed)) {
            unprobed
        } else {
            unprobed.copy(liveProtocol = probeProtocol())
        }

        val report = StatusEvaluator.evaluate(snapshot)
        println(report.render())
        return report.exitCode
    }

    private fun probeProtocol(): ProtocolObservation = try {
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
        val printResult = commandRunner.run(
            listOf("/bin/launchctl", "print", service),
        )
        val domain = service.substringBeforeLast('/')
        val label = service.substringAfterLast('/')
        val disabledResult = commandRunner.run(
            listOf("/bin/launchctl", "print-disabled", domain),
        )
        return parseLaunchctlPrint(
            service = service,
            result = printResult,
            enablement = parseLaunchctlPrintDisabled(label, disabledResult),
        )
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
    enablement: LaunchdEnablementObservation =
        LaunchdEnablementObservation(LaunchdEnablement.UNKNOWN),
): LaunchdServiceObservation {
    if (!result.successful) {
        return LaunchdServiceObservation(
            service = service,
            load = if (isMissingLaunchdJob(result)) {
                LaunchdLoadState.ABSENT
            } else {
                LaunchdLoadState.UNKNOWN
            },
            error = result.output.ifBlank { "exit code ${result.exitCode}" },
            enablement = enablement,
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
        load = LaunchdLoadState.LOADED,
        state = state,
        processId = processId,
        program = program,
        enablement = enablement,
    )
}

/**
 * Reads one label out of the single dictionary `launchctl print-disabled` prints. Entries are
 * only trusted between the header and its closing brace, and output that never closes the
 * dictionary is unknown rather than enabled: absent output must not read as an absent override.
 */
fun parseLaunchctlPrintDisabled(
    serviceLabel: String,
    result: CommandResult,
): LaunchdEnablementObservation {
    if (!result.successful) {
        return LaunchdEnablementObservation(
            LaunchdEnablement.UNKNOWN,
            result.output.ifBlank { "exit code ${result.exitCode}" },
        )
    }
    var inDictionary = false
    var closedDictionary = false
    var serviceState: String? = null
    result.output.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            !inDictionary -> if (line == LAUNCHCTL_DISABLED_HEADER) inDictionary = true
            closedDictionary -> Unit
            line == "}" -> closedDictionary = true
            serviceState == null -> {
                val match = LAUNCHCTL_DISABLED_ENTRY_PATTERN.matchEntire(line)
                if (match?.groupValues?.get(1) == serviceLabel) {
                    serviceState = match.groupValues[2]
                }
            }
        }
    }
    if (!inDictionary || !closedDictionary) {
        return LaunchdEnablementObservation(
            LaunchdEnablement.UNKNOWN,
            "unexpected 'launchctl print-disabled' output",
        )
    }
    val disabled = serviceState == "disabled" || serviceState == "true"
    return LaunchdEnablementObservation(
        if (disabled) LaunchdEnablement.DISABLED else LaunchdEnablement.ENABLED,
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

private const val LAUNCHCTL_DISABLED_HEADER = "disabled services = {"

private val LAUNCHCTL_DISABLED_ENTRY_PATTERN =
    Regex("""^"([^"]+)"\s*=>\s*(enabled|disabled|true|false)$""")
