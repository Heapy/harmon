package dev.yoda.harmon.config

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv

data class AlertThresholds(
    val applicationCpuPercent: Double? = 150.0,
    val applicationMemoryMiB: Long? = 2_048,
    val swapUsedMiB: Long? = 1_024,
    val applicationBatteryImpactScore: Double? = 100.0,
    val batteryLowPercent: Int? = 20,
)

data class NotificationConfig(
    val systemEnabled: Boolean = true,
    val webhookUrl: String? = null,
    val webhookBearerToken: String? = null,
    val telegramBotToken: String? = null,
    val telegramChatId: String? = null,
    val notifyEverySample: Boolean = false,
    val timeoutSeconds: Long = 15,
)

data class HarmonConfig(
    val intervalSeconds: Long = 300,
    val onceSampleSeconds: Long = 2,
    val topProcessCount: Int = 8,
    val maxAlertsPerCategory: Int = 3,
    val alertCooldownSeconds: Long = 1_800,
    val thresholds: AlertThresholds = AlertThresholds(),
    val notifications: NotificationConfig = NotificationConfig(),
) {
    fun redactedDescription(): String = buildString {
        appendLine("intervalSeconds=$intervalSeconds")
        appendLine("onceSampleSeconds=$onceSampleSeconds")
        appendLine("topProcessCount=$topProcessCount")
        appendLine("maxAlertsPerCategory=$maxAlertsPerCategory")
        appendLine("alertCooldownSeconds=$alertCooldownSeconds")
        appendLine("applicationCpuAlertPercent=${thresholds.applicationCpuPercent ?: 0}")
        appendLine("applicationMemoryAlertMiB=${thresholds.applicationMemoryMiB ?: 0}")
        appendLine("swapAlertMiB=${thresholds.swapUsedMiB ?: 0}")
        appendLine(
            "applicationBatteryImpactAlertScore=" +
                (thresholds.applicationBatteryImpactScore ?: 0),
        )
        appendLine("batteryLowAlertPercent=${thresholds.batteryLowPercent ?: 0}")
        appendLine("systemNotifications=${notifications.systemEnabled}")
        appendLine("notifyEverySample=${notifications.notifyEverySample}")
        appendLine("httpTimeoutSeconds=${notifications.timeoutSeconds}")
        appendLine(
            "webhookUrl=" +
                if (notifications.webhookUrl == null) "" else "<configured>",
        )
        appendLine(
            "webhookBearerToken=" +
                if (notifications.webhookBearerToken == null) "" else "<redacted>",
        )
        appendLine(
            "telegramBotToken=" +
                if (notifications.telegramBotToken == null) "" else "<redacted>",
        )
        append(
            "telegramChatId=" +
                if (notifications.telegramChatId == null) "" else "<configured>",
        )
    }
}

class ConfigException(message: String) : IllegalArgumentException(message)

object ConfigLoader {
    private const val LINE_BUFFER_SIZE = 8_192

    private val legacyKeyAliases = mapOf(
        "processCpuAlertPercent" to "applicationCpuAlertPercent",
        "processMemoryAlertMiB" to "applicationMemoryAlertMiB",
        "batteryImpactAlertScore" to "applicationBatteryImpactAlertScore",
    )

    private val knownKeys = setOf(
        "intervalSeconds",
        "onceSampleSeconds",
        "topProcessCount",
        "maxAlertsPerCategory",
        "alertCooldownSeconds",
        "applicationCpuAlertPercent",
        "applicationMemoryAlertMiB",
        "swapAlertMiB",
        "applicationBatteryImpactAlertScore",
        "batteryLowAlertPercent",
        "systemNotifications",
        "notifyEverySample",
        "httpTimeoutSeconds",
        "webhookUrl",
        "webhookBearerToken",
        "telegramBotToken",
        "telegramChatId",
    ) + legacyKeyAliases.keys

    @OptIn(ExperimentalForeignApi::class)
    fun defaultPath(): String {
        val home = getenv("HOME")?.toKString()
            ?: throw ConfigException("HOME is not set; pass --config explicitly")
        return "$home/.config/harmon/config"
    }

    @OptIn(ExperimentalForeignApi::class)
    fun exists(path: String): Boolean = access(path, F_OK) == 0

    fun parse(lines: Sequence<String>, environment: Map<String, String> = environment()): HarmonConfig {
        val values = linkedMapOf<String, String>()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEachIndexed
            }

            val separator = line.indexOf('=')
            if (separator <= 0) {
                throw ConfigException("Invalid config line ${index + 1}: expected key=value")
            }

            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key !in knownKeys) {
                throw ConfigException("Unknown config key '$key' on line ${index + 1}")
            }
            values[legacyKeyAliases[key] ?: key] = value
        }

        environment["HARMON_WEBHOOK_URL"]?.let { values["webhookUrl"] = it }
        environment["HARMON_WEBHOOK_BEARER_TOKEN"]?.let {
            values["webhookBearerToken"] = it
        }
        environment["HARMON_TELEGRAM_BOT_TOKEN"]?.let {
            values["telegramBotToken"] = it
        }
        environment["HARMON_TELEGRAM_CHAT_ID"]?.let {
            values["telegramChatId"] = it
        }

        val defaults = HarmonConfig()
        val notificationDefaults = defaults.notifications
        val thresholdDefaults = defaults.thresholds

        val config = HarmonConfig(
            intervalSeconds = values.positiveLong(
                "intervalSeconds",
                defaults.intervalSeconds,
            ),
            onceSampleSeconds = values.positiveLong(
                "onceSampleSeconds",
                defaults.onceSampleSeconds,
            ),
            topProcessCount = values.positiveInt(
                "topProcessCount",
                defaults.topProcessCount,
            ),
            maxAlertsPerCategory = values.positiveInt(
                "maxAlertsPerCategory",
                defaults.maxAlertsPerCategory,
            ),
            alertCooldownSeconds = values.nonNegativeLong(
                "alertCooldownSeconds",
                defaults.alertCooldownSeconds,
            ),
            thresholds = AlertThresholds(
                applicationCpuPercent = values.optionalPositiveDouble(
                    "applicationCpuAlertPercent",
                    thresholdDefaults.applicationCpuPercent,
                ),
                applicationMemoryMiB = values.optionalPositiveLong(
                    "applicationMemoryAlertMiB",
                    thresholdDefaults.applicationMemoryMiB,
                ),
                swapUsedMiB = values.optionalPositiveLong(
                    "swapAlertMiB",
                    thresholdDefaults.swapUsedMiB,
                ),
                applicationBatteryImpactScore = values.optionalPositiveDouble(
                    "applicationBatteryImpactAlertScore",
                    thresholdDefaults.applicationBatteryImpactScore,
                ),
                batteryLowPercent = values.optionalPercentage(
                    "batteryLowAlertPercent",
                    thresholdDefaults.batteryLowPercent,
                ),
            ),
            notifications = NotificationConfig(
                systemEnabled = values.boolean(
                    "systemNotifications",
                    notificationDefaults.systemEnabled,
                ),
                webhookUrl = values.nonBlankOrNull("webhookUrl"),
                webhookBearerToken = values.nonBlankOrNull("webhookBearerToken"),
                telegramBotToken = values.nonBlankOrNull("telegramBotToken"),
                telegramChatId = values.nonBlankOrNull("telegramChatId"),
                notifyEverySample = values.boolean(
                    "notifyEverySample",
                    notificationDefaults.notifyEverySample,
                ),
                timeoutSeconds = values.positiveLong(
                    "httpTimeoutSeconds",
                    notificationDefaults.timeoutSeconds,
                ),
            ),
        )
        validate(config)
        return config
    }

    @OptIn(ExperimentalForeignApi::class)
    fun load(path: String): HarmonConfig {
        val file = fopen(path, "r")
            ?: throw ConfigException("Cannot open config file: $path")
        return try {
            memScoped {
                val buffer = allocArray<ByteVar>(LINE_BUFFER_SIZE)
                val lines = buildList {
                    while (fgets(buffer, LINE_BUFFER_SIZE, file) != null) {
                        add(buffer.toKString())
                    }
                }
                parse(lines.asSequence())
            }
        } finally {
            fclose(file)
        }
    }

    fun loadOrDefaults(path: String): HarmonConfig =
        if (exists(path)) load(path) else parse(emptySequence())

    private fun validate(config: HarmonConfig) {
        if (config.intervalSeconds !in 1..86_400) {
            throw ConfigException("intervalSeconds must be between 1 and 86400")
        }
        if (config.onceSampleSeconds !in 1..300) {
            throw ConfigException("onceSampleSeconds must be between 1 and 300")
        }
        if (config.topProcessCount !in 1..100) {
            throw ConfigException("topProcessCount must be between 1 and 100")
        }
        if (config.maxAlertsPerCategory !in 1..20) {
            throw ConfigException("maxAlertsPerCategory must be between 1 and 20")
        }
        if (config.alertCooldownSeconds > 604_800) {
            throw ConfigException("alertCooldownSeconds must not exceed 604800")
        }

        val notifications = config.notifications
        if (notifications.timeoutSeconds !in 1..300) {
            throw ConfigException("httpTimeoutSeconds must be between 1 and 300")
        }
        if ((notifications.telegramBotToken == null) != (notifications.telegramChatId == null)) {
            throw ConfigException(
                "telegramBotToken and telegramChatId must be configured together",
            )
        }
        notifications.webhookUrl?.let { url ->
            if (!isAllowedWebhookUrl(url)) {
                throw ConfigException(
                    "webhookUrl must use HTTPS (HTTP is allowed only for 127.0.0.1)",
                )
            }
        }
        if (notifications.webhookBearerToken?.any { it == '\r' || it == '\n' } == true) {
            throw ConfigException("webhookBearerToken must not contain newlines")
        }
    }

    private fun isAllowedWebhookUrl(url: String): Boolean {
        if (url.any { it.isWhitespace() || it.code < 0x20 }) {
            return false
        }
        val schemeSeparator = url.indexOf("://")
        if (schemeSeparator <= 0) {
            return false
        }
        val scheme = url.substring(0, schemeSeparator).lowercase()
        val authority = url
            .substring(schemeSeparator + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isBlank()) {
            return false
        }
        if (scheme == "https") {
            return true
        }
        val host = authority.substringBefore(':')
        return scheme == "http" && host == "127.0.0.1"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun environment(): Map<String, String> = listOf(
        "HARMON_WEBHOOK_URL",
        "HARMON_WEBHOOK_BEARER_TOKEN",
        "HARMON_TELEGRAM_BOT_TOKEN",
        "HARMON_TELEGRAM_CHAT_ID",
    ).mapNotNull { key ->
        getenv(key)?.toKString()?.let { key to it }
    }.toMap()
}

private fun Map<String, String>.positiveLong(key: String, default: Long): Long {
    val raw = this[key] ?: return default
    return raw.toLongOrNull()?.takeIf { it > 0 }
        ?: throw ConfigException("$key must be a positive integer")
}

private fun Map<String, String>.nonNegativeLong(key: String, default: Long): Long {
    val raw = this[key] ?: return default
    return raw.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw ConfigException("$key must be a non-negative integer")
}

private fun Map<String, String>.positiveInt(key: String, default: Int): Int {
    val raw = this[key] ?: return default
    return raw.toIntOrNull()?.takeIf { it > 0 }
        ?: throw ConfigException("$key must be a positive integer")
}

private fun Map<String, String>.optionalPositiveLong(key: String, default: Long?): Long? {
    val raw = this[key] ?: return default
    val value = raw.toLongOrNull()
        ?: throw ConfigException("$key must be a non-negative integer")
    return when {
        value < 0 -> throw ConfigException("$key must be a non-negative integer")
        value == 0L -> null
        else -> value
    }
}

private fun Map<String, String>.optionalPositiveDouble(
    key: String,
    default: Double?,
): Double? {
    val raw = this[key] ?: return default
    val value = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: throw ConfigException("$key must be a non-negative number")
    return when {
        value < 0.0 -> throw ConfigException("$key must be a non-negative number")
        value == 0.0 -> null
        else -> value
    }
}

private fun Map<String, String>.optionalPercentage(key: String, default: Int?): Int? {
    val raw = this[key] ?: return default
    val value = raw.toIntOrNull()
        ?: throw ConfigException("$key must be an integer from 0 to 100")
    return when (value) {
        0 -> null
        in 1..100 -> value
        else -> throw ConfigException("$key must be an integer from 0 to 100")
    }
}

private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
    when (val raw = this[key]?.lowercase()) {
        null -> default
        "true", "yes", "1", "on" -> true
        "false", "no", "0", "off" -> false
        else -> throw ConfigException("$key must be true or false, got '$raw'")
    }

private fun Map<String, String>.nonBlankOrNull(key: String): String? =
    this[key]?.takeIf { it.isNotBlank() }
