package dev.yoda.harmon.config

import dev.yoda.harmon.util.printError
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

/**
 * Applications whose bundle stops being inherited by descendants that live outside it.
 *
 * A terminal launches unrelated commands, so charging them to the terminal would hide whatever
 * they actually are. The list is user-specific — hence a config key rather than a constant.
 */
val DEFAULT_TERMINAL_APPLICATIONS: Set<String> = setOf(
    "terminal",
    "iterm2",
    "iterm",
    "alacritty",
    "wezterm",
    "kitty",
    "ghostty",
    "warp",
    "hyper",
    "tabby",
    "agterm",
)

/**
 * How long a single sample window may last, in seconds.
 *
 * The one source of truth for `onceSampleSeconds`, the `--sample-seconds` option and
 * [dev.yoda.harmon.runtime.HarmonService.sampleOnce] — the three places that used to enforce
 * their own, drifting bounds.
 */
val SAMPLE_SECONDS_RANGE: LongRange = 1L..300L

data class AlertThresholds(
    val applicationCpuPercent: Double? = 150.0,
    val applicationMemoryMiB: Long? = 2_048,
    val applicationDiskWriteMiBPerSecond: Double? = 50.0,
    val swapUsedMiB: Long? = 1_024,
    val swapOutMiBPerSecond: Double? = 25.0,
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
    val collectorSocket: String = "/var/run/harmon.collector.sock",
    val intervalSeconds: Long = 300,
    val onceSampleSeconds: Long = 2,
    val topProcessCount: Int = 8,
    val maxAlertsPerCategory: Int = 3,
    /**
     * Days of samples kept in the history database, or null for no history at all.
     *
     * Null rather than zero because the two answers are different actions and not two values of
     * one: a retention of zero days would be a database opened and emptied on every pass, while
     * what the key means at zero is that the file is never created.
     */
    val historyRetentionDays: Long? = 7,
    val terminalApplications: Set<String> = DEFAULT_TERMINAL_APPLICATIONS,
    val thresholds: AlertThresholds = AlertThresholds(),
    val notifications: NotificationConfig = NotificationConfig(),
) {
    fun redactedDescription(): String = buildString {
        appendLine("collectorSocket=$collectorSocket")
        appendLine("intervalSeconds=$intervalSeconds")
        appendLine("onceSampleSeconds=$onceSampleSeconds")
        appendLine("topProcessCount=$topProcessCount")
        appendLine("maxAlertsPerCategory=$maxAlertsPerCategory")
        appendLine("historyRetentionDays=${historyRetentionDays ?: 0}")
        appendLine("terminalApplications=${terminalApplications.joinToString(",")}")
        appendLine("applicationCpuAlertPercent=${thresholds.applicationCpuPercent ?: 0}")
        appendLine("applicationMemoryAlertMiB=${thresholds.applicationMemoryMiB ?: 0}")
        appendLine(
            "applicationDiskWriteAlertMiBPerSecond=" +
                (thresholds.applicationDiskWriteMiBPerSecond ?: 0),
        )
        appendLine("swapAlertMiB=${thresholds.swapUsedMiB ?: 0}")
        appendLine(
            "swapOutAlertMiBPerSecond=${thresholds.swapOutMiBPerSecond ?: 0}",
        )
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

    /** 1 TiB. Byte thresholds stay well inside `ULong` and no real machine reaches it. */
    private const val MAX_THRESHOLD_MIB = 1_048_576L

    private val legacyKeyAliases = mapOf(
        "processCpuAlertPercent" to "applicationCpuAlertPercent",
        "processMemoryAlertMiB" to "applicationMemoryAlertMiB",
        "batteryImpactAlertScore" to "applicationBatteryImpactAlertScore",
    )

    /**
     * The one retired key. It no longer does anything, but it must not fail an existing config
     * file on agent start, so it is reported once and dropped.
     */
    private const val DEPRECATED_COOLDOWN_KEY = "alertCooldownSeconds"

    private val knownKeys = setOf(
        "intervalSeconds",
        "collectorSocket",
        "onceSampleSeconds",
        "topProcessCount",
        "maxAlertsPerCategory",
        "historyRetentionDays",
        "terminalApplications",
        "applicationCpuAlertPercent",
        "applicationMemoryAlertMiB",
        "applicationDiskWriteAlertMiBPerSecond",
        "swapAlertMiB",
        "swapOutAlertMiBPerSecond",
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

    fun parse(
        lines: Sequence<String>,
        environment: Map<String, String> = environment(),
        warn: (String) -> Unit = ::printError,
    ): HarmonConfig {
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
            if (key == DEPRECATED_COOLDOWN_KEY) {
                warn(
                    "Ignoring deprecated config key '$key' on line ${index + 1}: alerts now " +
                        "fire when a threshold is crossed, so repeats are not timed",
                )
                return@forEachIndexed
            }
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
        environment["HARMON_COLLECTOR_SOCKET"]?.let {
            values["collectorSocket"] = it
        }

        val defaults = HarmonConfig()
        val notificationDefaults = defaults.notifications
        val thresholdDefaults = defaults.thresholds

        val config = HarmonConfig(
            collectorSocket = values["collectorSocket"] ?: defaults.collectorSocket,
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
            historyRetentionDays = values.optionalPositiveLong(
                "historyRetentionDays",
                defaults.historyRetentionDays,
            ),
            terminalApplications = values.lowercaseNameSet(
                "terminalApplications",
                defaults.terminalApplications,
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
                applicationDiskWriteMiBPerSecond = values.optionalPositiveDouble(
                    "applicationDiskWriteAlertMiBPerSecond",
                    thresholdDefaults.applicationDiskWriteMiBPerSecond,
                ),
                swapUsedMiB = values.optionalPositiveLong(
                    "swapAlertMiB",
                    thresholdDefaults.swapUsedMiB,
                ),
                swapOutMiBPerSecond = values.optionalPositiveDouble(
                    "swapOutAlertMiBPerSecond",
                    thresholdDefaults.swapOutMiBPerSecond,
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
        if (
            !config.collectorSocket.startsWith('/') ||
            config.collectorSocket.length > 100 ||
            config.collectorSocket.any { it == '\u0000' }
        ) {
            throw ConfigException(
                "collectorSocket must be an absolute Unix socket path up to 100 characters",
            )
        }
        if (config.intervalSeconds !in 1..86_400) {
            throw ConfigException("intervalSeconds must be between 1 and 86400")
        }
        if (config.onceSampleSeconds !in SAMPLE_SECONDS_RANGE) {
            throw ConfigException(
                "onceSampleSeconds must be between ${SAMPLE_SECONDS_RANGE.first} " +
                    "and ${SAMPLE_SECONDS_RANGE.last}",
            )
        }
        if (config.topProcessCount !in 1..100) {
            throw ConfigException("topProcessCount must be between 1 and 100")
        }
        if (config.maxAlertsPerCategory !in 1..20) {
            throw ConfigException("maxAlertsPerCategory must be between 1 and 20")
        }
        /*
         * A retention nobody bounded is a database nobody bounded. `historyRetentionDays=7000`, the
         * typo for 7 that costs nothing to make, parses cleanly and yields a cutoff no stored
         * sample is ever older than, so the pass deletes nothing and the file grows for as long as
         * the agent runs, with nothing anywhere reporting it. Ten years is past any use for the
         * data and well short of that.
         */
        config.historyRetentionDays?.let { days ->
            if (days !in 1..3_650) {
                throw ConfigException("historyRetentionDays must be between 0 and 3650")
            }
        }

        val thresholds = config.thresholds
        if ((thresholds.applicationMemoryMiB ?: 0) > MAX_THRESHOLD_MIB) {
            throw ConfigException(
                "applicationMemoryAlertMiB must not exceed $MAX_THRESHOLD_MIB",
            )
        }
        if ((thresholds.swapUsedMiB ?: 0) > MAX_THRESHOLD_MIB) {
            throw ConfigException("swapAlertMiB must not exceed $MAX_THRESHOLD_MIB")
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

    /**
     * Whether [url] may carry the webhook payload and its bearer token.
     *
     * HTTPS goes anywhere; plaintext HTTP only to loopback. The host is taken from after the last
     * `@` in the authority, because everything before it is userinfo, not a host:
     * `http://127.0.0.1:80@evil.example/hook` is a request to `evil.example` — libcurl parses it
     * as `host=evil.example user=127.0.0.1` — and reading the host as `127.0.0.1` would send the
     * token to an arbitrary server in cleartext. libcurl rejects an authority with a second `@`
     * outright, so the last one is the delimiter it uses.
     */
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
        val host = authority.substringAfterLast('@').substringBefore(':')
        return scheme == "http" && host == "127.0.0.1"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun environment(): Map<String, String> = listOf(
        "HARMON_WEBHOOK_URL",
        "HARMON_WEBHOOK_BEARER_TOKEN",
        "HARMON_TELEGRAM_BOT_TOKEN",
        "HARMON_TELEGRAM_CHAT_ID",
        "HARMON_COLLECTOR_SOCKET",
    ).mapNotNull { key ->
        getenv(key)?.toKString()?.let { key to it }
    }.toMap()
}

private fun Map<String, String>.positiveLong(key: String, default: Long): Long {
    val raw = this[key] ?: return default
    return raw.toLongOrNull()?.takeIf { it > 0 }
        ?: throw ConfigException("$key must be a positive integer")
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

/**
 * A comma-separated list, folded to lower case for case-insensitive matching. The key replaces the
 * default list outright, so an empty value is a deliberate "no entries" rather than "use defaults".
 */
private fun Map<String, String>.lowercaseNameSet(
    key: String,
    default: Set<String>,
): Set<String> {
    val raw = this[key] ?: return default
    return raw.split(',')
        .map { it.trim().lowercase() }
        .filterTo(mutableSetOf()) { it.isNotEmpty() }
}

private fun Map<String, String>.nonBlankOrNull(key: String): String? =
    this[key]?.takeIf { it.isNotBlank() }
