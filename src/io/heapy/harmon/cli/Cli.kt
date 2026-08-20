package io.heapy.harmon.cli

import io.heapy.harmon.BuildInfo
import io.heapy.harmon.config.ConfigException
import io.heapy.harmon.config.ConfigLoader
import io.heapy.harmon.config.HarmonConfig
import io.heapy.harmon.config.SAMPLE_SECONDS_RANGE
import io.heapy.harmon.history.History
import io.heapy.harmon.report.ReportFormatter
import io.heapy.harmon.runtime.HarmonService
import io.heapy.harmon.setup.SetupRequest
import io.heapy.harmon.setup.StopRequest
import io.heapy.harmon.setup.UninstallRequest
import io.heapy.harmon.util.failureDescription
import io.heapy.harmon.util.printError
import kotlin.system.exitProcess

object HarmonApplication {
    fun run(
        arguments: Array<String>,
        serviceFactory: (HarmonConfig, History?) -> HarmonService,
        runService: (HarmonConfig, History?) -> Nothing,
        historyFactory: (HarmonConfig) -> History?,
        setup: (SetupRequest) -> Unit,
        status: () -> Int,
        stop: (StopRequest) -> Unit,
        uninstall: (UninstallRequest) -> Unit,
        openUi: () -> Unit,
    ) {
        val command = try {
            CliParser.parse(arguments)
        } catch (failure: CliException) {
            printError("error: ${failure.message}")
            printError("")
            printError(CliParser.help())
            exitProcess(2)
        }

        when (command) {
            Command.Help -> println(CliParser.help())
            Command.Version -> println("harmon ${BuildInfo.VERSION}")
            is Command.Run -> withConfig(command.configPath) { config ->
                runService(config, historyFactory(config))
            }
            is Command.Once -> withConfig(command.configPath) { config ->
                val service = serviceFactory(config, null)
                val report = service.sampleOnce(command.sampleSeconds ?: config.onceSampleSeconds)
                val reportText = ReportFormatter.text(report)
                println(reportText)
                if (command.notify) {
                    val results = service.deliver(report, reportText)
                    printDeliveryResults(results)
                    if (results.any { !it.successful }) {
                        exitProcess(1)
                    }
                }
            }
            is Command.Diagnose -> withConfig(command.configPath) { config ->
                val report = serviceFactory(config, null).sampleOnce(
                    command.sampleSeconds ?: config.onceSampleSeconds,
                )
                println(ReportFormatter.diagnostics(report))
            }
            is Command.CheckConfig -> withConfig(command.configPath) { config ->
                println("Configuration is valid.")
                println(config.redactedDescription())
            }
            is Command.TestNotifications -> withConfig(command.configPath) { config ->
                val results = serviceFactory(config, null).testNotifications()
                if (results.isEmpty()) {
                    println("No notification channels are enabled.")
                } else {
                    printDeliveryResults(results)
                    if (results.any { !it.successful }) {
                        exitProcess(1)
                    }
                }
            }
            is Command.Setup -> {
                try {
                    setup(
                        SetupRequest(
                            system = command.system,
                            userId = command.userId,
                            groupId = command.groupId,
                        ),
                    )
                } catch (failure: Throwable) {
                    printError("setup error: ${failureDescription(failure)}")
                    exitProcess(1)
                }
            }
            Command.Status -> {
                try {
                    val exitCode = status()
                    if (exitCode != 0) {
                        exitProcess(exitCode)
                    }
                } catch (failure: Throwable) {
                    printError("status error: ${failureDescription(failure)}")
                    exitProcess(1)
                }
            }
            is Command.Stop -> {
                try {
                    stop(
                        StopRequest(
                            system = command.system,
                            userId = command.userId,
                        ),
                    )
                } catch (failure: Throwable) {
                    printError("stop error: ${failureDescription(failure)}")
                    exitProcess(1)
                }
            }
            Command.Ui -> {
                try {
                    openUi()
                } catch (failure: Throwable) {
                    printError("ui error: ${failureDescription(failure)}")
                    exitProcess(1)
                }
            }
            is Command.Uninstall -> {
                try {
                    uninstall(
                        UninstallRequest(
                            system = command.system,
                            userId = command.userId,
                            purge = command.purge,
                        ),
                    )
                } catch (failure: Throwable) {
                    printError("uninstall error: ${failureDescription(failure)}")
                    exitProcess(1)
                }
            }
        }
    }

    private fun withConfig(path: String?, block: (HarmonConfig) -> Unit) {
        try {
            val effectivePath = path ?: ConfigLoader.defaultPath()
            val config = if (path != null) {
                ConfigLoader.load(effectivePath)
            } else {
                ConfigLoader.loadOrDefaults(effectivePath)
            }
            try {
                block(config)
            } catch (failure: Throwable) {
                printError("runtime error: ${failure.message ?: failure::class.simpleName}")
                exitProcess(1)
            }
        } catch (failure: ConfigException) {
            printError("configuration error: ${failure.message}")
            exitProcess(2)
        }
    }

    private fun printDeliveryResults(results: List<io.heapy.harmon.model.DeliveryResult>) {
        results.forEach { result ->
            val status = if (result.successful) "ok" else "failed"
            println("${result.channel}: $status (${result.detail})")
        }
    }
}

sealed interface Command {
    data object Help : Command

    data object Version : Command

    data class Run(
        val configPath: String?,
    ) : Command

    data class Once(
        val configPath: String?,
        val sampleSeconds: Long?,
        val notify: Boolean,
    ) : Command

    data class CheckConfig(
        val configPath: String?,
    ) : Command

    data class Diagnose(
        val configPath: String?,
        val sampleSeconds: Long?,
    ) : Command

    data class TestNotifications(
        val configPath: String?,
    ) : Command

    data class Setup(
        val system: Boolean,
        val userId: UInt?,
        val groupId: UInt?,
    ) : Command

    data object Status : Command

    data class Stop(
        val system: Boolean,
        val userId: UInt?,
    ) : Command

    data object Ui : Command

    data class Uninstall(
        val system: Boolean,
        val userId: UInt?,
        val purge: Boolean,
    ) : Command
}

class CliException(message: String) : IllegalArgumentException(message)

object CliParser {
    fun parse(arguments: Array<String>): Command {
        if (arguments.isEmpty()) {
            return Command.Run(configPath = null)
        }
        if (arguments.size == 1) {
            when (arguments.single()) {
                "-h", "--help", "help" -> return Command.Help
                "-v", "--version", "version" -> return Command.Version
            }
        }
        val commandName = arguments.first().takeUnless { it.startsWith('-') } ?: "run"
        val optionStart = if (commandName == "run" && arguments.first().startsWith('-')) 0 else 1
        if (commandName == "setup") {
            return parseSetup(arguments.drop(1))
        }
        if (commandName == "stop") {
            return parseStop(arguments.drop(1))
        }
        if (commandName == "uninstall") {
            return parseUninstall(arguments.drop(1))
        }
        var configPath: String? = null
        var sampleSeconds: Long? = null
        var notify = false

        var index = optionStart
        while (index < arguments.size) {
            when (val option = arguments[index]) {
                "--config" -> {
                    configPath = arguments.valueAfter(index, option)
                    index += 2
                }
                "--sample-seconds" -> {
                    val raw = arguments.valueAfter(index, option)
                    sampleSeconds = raw.toLongOrNull()
                        ?.takeIf { it in SAMPLE_SECONDS_RANGE }
                        ?: throw CliException(
                            "--sample-seconds must be an integer between " +
                                "${SAMPLE_SECONDS_RANGE.first} and ${SAMPLE_SECONDS_RANGE.last}",
                        )
                    index += 2
                }
                "--notify" -> {
                    notify = true
                    index += 1
                }
                else -> throw CliException("unknown option '$option'")
            }
        }

        return when (commandName) {
            "run" -> {
                rejectSampleOptions(sampleSeconds, notify)
                Command.Run(configPath)
            }
            "once" -> Command.Once(configPath, sampleSeconds, notify)
            "diagnose" -> {
                if (notify) {
                    throw CliException("--notify is available only for 'once'")
                }
                Command.Diagnose(configPath, sampleSeconds)
            }
            "check-config" -> {
                rejectSampleOptions(sampleSeconds, notify)
                Command.CheckConfig(configPath)
            }
            "test-notifications" -> {
                rejectSampleOptions(sampleSeconds, notify)
                Command.TestNotifications(configPath)
            }
            "status" -> {
                rejectSampleOptions(sampleSeconds, notify)
                if (configPath != null) {
                    throw CliException("--config is not available for 'status'")
                }
                Command.Status
            }
            "ui" -> {
                rejectSampleOptions(sampleSeconds, notify)
                if (configPath != null) {
                    throw CliException("--config is not available for 'ui'")
                }
                Command.Ui
            }
            else -> throw CliException("unknown command '$commandName'")
        }
    }

    fun help(): String = """
        Harmon — lightweight macOS process and battery monitor

        Usage:
          harmon run [--config PATH]
          harmon once [--config PATH] [--sample-seconds N] [--notify]
          harmon diagnose [--config PATH] [--sample-seconds N]
          harmon check-config [--config PATH]
          harmon test-notifications [--config PATH]
          harmon setup
          harmon setup --system --uid UID --gid GID
          harmon status
          harmon stop
          harmon stop --system --uid UID
          harmon ui
          harmon uninstall [--purge]
          harmon uninstall --system --uid UID [--purge]
          harmon --help
          harmon --version

        --sample-seconds N is the window a single sample measures over, and
        takes ${SAMPLE_SECONDS_RANGE.first} to ${SAMPLE_SECONDS_RANGE.last} seconds.

        launchd runs `harmon run` as the logged-in user. With no command,
        Harmon starts the user agent. If --config is omitted and
        ~/.config/harmon/config does not exist, safe defaults are used.

        Secret settings can be supplied via HARMON_WEBHOOK_BEARER_TOKEN,
        HARMON_TELEGRAM_BOT_TOKEN and HARMON_TELEGRAM_CHAT_ID.

        setup creates the user-owned application, config, and LaunchAgent, then
        requests sudo once for the root-owned collector and LaunchDaemon.
        The --system form is public for MDM and other automation.

        status is read-only and exits non-zero when the installed copies,
        collector protocol, socket, or launchd jobs need setup.

        stop stops and disables both launchd services without removing the
        installation or user data. Run setup to enable and start them again.

        ui opens the authenticated loopback process tree published by the
        running user agent.

        uninstall removes both services and deployed binaries while preserving
        configuration, logs, reports, and sample history. Its --system form is
        public for automation.

        uninstall --purge additionally removes the configuration, logs, reports,
        and history database, printing each removed tree first. It never
        prompts, so it stays usable in scripts. That data is deleted only after
        the privileged phase succeeds; the services and deployed binaries are
        removed before it, and setup restores those. Either form clears the
        disable overrides a stop wrote and leaves no Harmon file outside the
        Homebrew Cellar. The --system form purges the root-owned log directory.
    """.trimIndent()

    private fun parseSetup(arguments: List<String>): Command.Setup {
        var system = false
        var userId: UInt? = null
        var groupId: UInt? = null
        var index = 0
        while (index < arguments.size) {
            when (val option = arguments[index]) {
                "--system" -> {
                    if (system) {
                        throw CliException("--system may be specified only once")
                    }
                    system = true
                    index += 1
                }
                "--uid" -> {
                    if (userId != null) {
                        throw CliException("--uid may be specified only once")
                    }
                    userId = arguments.unsignedValueAfter(index, option)
                    index += 2
                }
                "--gid" -> {
                    if (groupId != null) {
                        throw CliException("--gid may be specified only once")
                    }
                    groupId = arguments.unsignedValueAfter(index, option)
                    index += 2
                }
                else -> throw CliException("unknown setup option '$option'")
            }
        }
        if (!system && (userId != null || groupId != null)) {
            throw CliException("--uid and --gid require --system")
        }
        if (system && (userId == null || groupId == null)) {
            throw CliException("--system requires both --uid and --gid")
        }
        return Command.Setup(system, userId, groupId)
    }

    private fun parseUninstall(arguments: List<String>): Command.Uninstall {
        val options = parseTwoPhaseOptions(
            commandName = "uninstall",
            arguments = arguments,
            allowPurge = true,
        )
        return Command.Uninstall(options.system, options.userId, options.purge)
    }

    private fun parseStop(arguments: List<String>): Command.Stop {
        val options = parseTwoPhaseOptions(
            commandName = "stop",
            arguments = arguments,
            allowPurge = false,
        )
        return Command.Stop(options.system, options.userId)
    }

    private data class TwoPhaseOptions(
        val system: Boolean,
        val userId: UInt?,
        val purge: Boolean,
    )

    private fun parseTwoPhaseOptions(
        commandName: String,
        arguments: List<String>,
        allowPurge: Boolean,
    ): TwoPhaseOptions {
        var system = false
        var userId: UInt? = null
        var purge = false
        var index = 0
        while (index < arguments.size) {
            when (val option = arguments[index]) {
                "--system" -> {
                    if (system) {
                        throw CliException("--system may be specified only once")
                    }
                    system = true
                    index += 1
                }
                "--uid" -> {
                    if (userId != null) {
                        throw CliException("--uid may be specified only once")
                    }
                    userId = arguments.unsignedValueAfter(index, option)
                    index += 2
                }
                "--purge" -> {
                    if (!allowPurge) {
                        throw CliException("unknown $commandName option '$option'")
                    }
                    if (purge) {
                        throw CliException("--purge may be specified only once")
                    }
                    purge = true
                    index += 1
                }
                else -> throw CliException("unknown $commandName option '$option'")
            }
        }
        if (!system && userId != null) {
            throw CliException("--uid requires --system")
        }
        if (system && userId == null) {
            throw CliException("--system requires --uid")
        }
        return TwoPhaseOptions(system, userId, purge)
    }

    private fun Array<String>.valueAfter(index: Int, option: String): String =
        getOrNull(index + 1)?.takeUnless { it.startsWith('-') }
            ?: throw CliException("$option requires a value")

    private fun List<String>.unsignedValueAfter(index: Int, option: String): UInt =
        getOrNull(index + 1)
            ?.takeUnless { it.startsWith('-') }
            ?.toUIntOrNull()
            ?: throw CliException("$option requires an unsigned integer")

    private fun rejectSampleOptions(sampleSeconds: Long?, notify: Boolean) {
        if (sampleSeconds != null) {
            throw CliException("--sample-seconds is available only for 'once' or 'diagnose'")
        }
        if (notify) {
            throw CliException("--notify is available only for 'once'")
        }
    }

}
