package dev.yoda.harmon.cli

import dev.yoda.harmon.config.ConfigException
import dev.yoda.harmon.config.ConfigLoader
import dev.yoda.harmon.report.ReportFormatter
import dev.yoda.harmon.runtime.HarmonService
import dev.yoda.harmon.util.printError
import kotlin.system.exitProcess

object HarmonApplication {
    fun run(arguments: Array<String>) {
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
            Command.Version -> println("harmon 0.1.0")
            is Command.Run -> withConfig(command.configPath) { config ->
                HarmonService(config).runForever()
            }
            is Command.Once -> withConfig(command.configPath) { config ->
                val service = HarmonService(config)
                val report = service.sampleOnce(command.sampleSeconds ?: config.onceSampleSeconds)
                println(ReportFormatter.text(report))
                if (command.notify) {
                    val results = service.deliver(report)
                    printDeliveryResults(results)
                    if (results.any { !it.successful }) {
                        exitProcess(1)
                    }
                }
            }
            is Command.Diagnose -> withConfig(command.configPath) { config ->
                val report = HarmonService(config).sampleOnce(
                    command.sampleSeconds ?: config.onceSampleSeconds,
                )
                println(ReportFormatter.diagnostics(report))
            }
            is Command.CheckConfig -> withConfig(command.configPath) { config ->
                println("Configuration is valid.")
                println(config.redactedDescription())
            }
            is Command.TestNotifications -> withConfig(command.configPath) { config ->
                val results = HarmonService(config).testNotifications()
                if (results.isEmpty()) {
                    println("No notification channels are enabled.")
                } else {
                    printDeliveryResults(results)
                    if (results.any { !it.successful }) {
                        exitProcess(1)
                    }
                }
            }
        }
    }

    private fun withConfig(path: String?, block: (dev.yoda.harmon.config.HarmonConfig) -> Unit) {
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

    private fun printDeliveryResults(results: List<dev.yoda.harmon.model.DeliveryResult>) {
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
                    sampleSeconds = raw.toLongOrNull()?.takeIf { it > 0 }
                        ?: throw CliException("--sample-seconds must be a positive integer")
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
          harmon --help
          harmon --version

        With no command, Harmon runs continuously. If --config is omitted and
        ~/.config/harmon/config does not exist, safe defaults are used.

        Secret settings can be supplied via HARMON_WEBHOOK_BEARER_TOKEN,
        HARMON_TELEGRAM_BOT_TOKEN and HARMON_TELEGRAM_CHAT_ID.
    """.trimIndent()

    private fun Array<String>.valueAfter(index: Int, option: String): String =
        getOrNull(index + 1)?.takeUnless { it.startsWith('-') }
            ?: throw CliException("$option requires a value")

    private fun rejectSampleOptions(sampleSeconds: Long?, notify: Boolean) {
        if (sampleSeconds != null) {
            throw CliException("--sample-seconds is available only for 'once' or 'diagnose'")
        }
        if (notify) {
            throw CliException("--notify is available only for 'once'")
        }
    }
}
