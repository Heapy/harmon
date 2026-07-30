package dev.yoda.harmon.setup

const val AGENT_LABEL = "dev.yoda.harmon.agent"
const val COLLECTOR_LABEL = "dev.yoda.harmon.collector"
const val LEGACY_AGENT_LABEL = "dev.yoda.harmon"
const val DEFAULT_COLLECTOR_SOCKET = "/var/run/harmon.collector.sock"

data class LaunchdJob(
    val label: String,
    val programArguments: List<String>,
    val runAtLoad: Boolean,
    val keepAlive: LaunchdKeepAlive,
    val processType: String,
    val lowPriorityIo: Boolean,
    val nice: Long,
    val throttleIntervalSeconds: Long,
    val umask: Long,
    val standardOutPath: String,
    val standardErrorPath: String,
    val limitLoadToSessionType: String? = null,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank" }
        require(programArguments.isNotEmpty()) { "programArguments must not be empty" }
        require(programArguments.none { it.isBlank() }) {
            "programArguments must not contain blank values"
        }
        require(throttleIntervalSeconds > 0) { "throttleIntervalSeconds must be positive" }
        require(umask >= 0) { "umask must not be negative" }
    }

    fun plist(): PlistValue.Dictionary {
        val entries = mutableListOf(
            "Label" to PlistValue.StringValue(label),
            "ProgramArguments" to PlistValue.Array(
                programArguments.map(PlistValue::StringValue),
            ),
            "RunAtLoad" to PlistValue.BooleanValue(runAtLoad),
            "KeepAlive" to keepAlive.plist(),
            "ProcessType" to PlistValue.StringValue(processType),
            "LowPriorityIO" to PlistValue.BooleanValue(lowPriorityIo),
            "Nice" to PlistValue.IntegerValue(nice),
            "ThrottleInterval" to PlistValue.IntegerValue(throttleIntervalSeconds),
        )
        limitLoadToSessionType?.let { sessionType ->
            entries += "LimitLoadToSessionType" to PlistValue.StringValue(sessionType)
        }
        entries += listOf(
            "Umask" to PlistValue.IntegerValue(umask),
            "StandardOutPath" to PlistValue.StringValue(standardOutPath),
            "StandardErrorPath" to PlistValue.StringValue(standardErrorPath),
        )
        return PlistValue.Dictionary(entries)
    }

    fun xml(): String = PlistXml.encode(plist())
}

sealed interface LaunchdKeepAlive {
    fun plist(): PlistValue

    data object Always : LaunchdKeepAlive {
        override fun plist(): PlistValue = PlistValue.BooleanValue(true)
    }

    data class SuccessfulExit(
        val value: Boolean,
    ) : LaunchdKeepAlive {
        override fun plist(): PlistValue = PlistValue.Dictionary(
            listOf("SuccessfulExit" to PlistValue.BooleanValue(value)),
        )
    }
}

data class AgentLaunchdPaths(
    val agentBinary: String,
    val config: String,
    val logDirectory: String,
)

data class CollectorLaunchdSettings(
    val collectorBinary: String,
    val socket: String,
    val allowedUserId: UInt,
    val allowedGroupId: UInt,
    val logDirectory: String = "/Library/Logs/Harmon",
)

object LaunchdJobs {
    fun agent(paths: AgentLaunchdPaths): LaunchdJob = LaunchdJob(
        label = AGENT_LABEL,
        programArguments = listOf(
            paths.agentBinary,
            "run",
            "--config",
            paths.config,
        ),
        runAtLoad = true,
        keepAlive = LaunchdKeepAlive.SuccessfulExit(value = false),
        processType = "Background",
        lowPriorityIo = true,
        nice = 5,
        throttleIntervalSeconds = 30,
        limitLoadToSessionType = "Aqua",
        umask = "077".toLong(radix = 8),
        standardOutPath = "${paths.logDirectory}/agent.log",
        standardErrorPath = "${paths.logDirectory}/agent.error.log",
    )

    fun collector(settings: CollectorLaunchdSettings): LaunchdJob = LaunchdJob(
        label = COLLECTOR_LABEL,
        programArguments = listOf(
            settings.collectorBinary,
            "--socket",
            settings.socket,
            "--allowed-uid",
            settings.allowedUserId.toString(),
            "--allowed-gid",
            settings.allowedGroupId.toString(),
        ),
        runAtLoad = true,
        keepAlive = LaunchdKeepAlive.Always,
        processType = "Background",
        lowPriorityIo = true,
        nice = 5,
        throttleIntervalSeconds = 10,
        umask = "007".toLong(radix = 8),
        standardOutPath = "${settings.logDirectory}/collector.log",
        standardErrorPath = "${settings.logDirectory}/collector.error.log",
    )
}
