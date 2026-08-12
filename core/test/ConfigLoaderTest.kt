import dev.yoda.harmon.config.ConfigException
import dev.yoda.harmon.config.ConfigLoader
import dev.yoda.harmon.config.DEFAULT_TERMINAL_APPLICATIONS
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigLoaderTest {
    @Test
    fun parsesValuesAndAcceptsLegacyProcessThresholdKeys() {
        val config = parseConfig(
            "# harmon",
            "intervalSeconds=60",
            "processCpuAlertPercent=0",
            "applicationMemoryAlertMiB=4096",
            "systemNotifications=no",
            "webhookUrl=https://example.test/events",
        )

        assertEquals(60, config.intervalSeconds)
        assertNull(config.thresholds.applicationCpuPercent)
        assertEquals(4_096L, config.thresholds.applicationMemoryMiB)
        assertEquals(false, config.notifications.systemEnabled)
        assertEquals("https://example.test/events", config.notifications.webhookUrl)
    }

    @Test
    fun environmentOverridesSecretsAndDestinations() {
        val config = ConfigLoader.parse(
            lines = sequenceOf(
                "webhookUrl=https://old.example/events",
                "telegramBotToken=file-token",
                "telegramChatId=file-chat",
            ),
            environment = mapOf(
                "HARMON_COLLECTOR_SOCKET" to "/tmp/harmon-test.sock",
                "HARMON_WEBHOOK_URL" to "https://new.example/events",
                "HARMON_TELEGRAM_BOT_TOKEN" to "env-token",
                "HARMON_TELEGRAM_CHAT_ID" to "env-chat",
            ),
        )

        assertEquals("/tmp/harmon-test.sock", config.collectorSocket)
        assertEquals("https://new.example/events", config.notifications.webhookUrl)
        assertEquals("env-token", config.notifications.telegramBotToken)
        assertEquals("env-chat", config.notifications.telegramChatId)
    }

    @Test
    fun acceptsAndReportsTheRetiredCooldownKey() {
        val warnings = mutableListOf<String>()

        val config = ConfigLoader.parse(
            lines = sequenceOf(
                "alertCooldownSeconds=1800",
                "intervalSeconds=60",
            ),
            environment = emptyMap(),
            warn = { warnings += it },
        )

        assertEquals(60, config.intervalSeconds)
        assertEquals(1, warnings.size)
        assertContains(warnings.single(), "alertCooldownSeconds")
        assertContains(warnings.single(), "line 1")
    }

    @Test
    fun rejectsASampleWindowOutsideTheSharedRange() {
        val failure = assertFailsWith<ConfigException> {
            parseConfig("onceSampleSeconds=${SAMPLE_SECONDS_RANGE.last + 1}")
        }

        assertContains(failure.message.orEmpty(), "onceSampleSeconds must be between")
    }

    @Test
    fun rejectsUnknownKeys() {
        assertFailsWith<ConfigException> {
            parseConfig("intervallSeconds=60")
        }
    }

    @Test
    fun rejectsMemoryAndSwapThresholdsAboveOneTebibyte() {
        assertFailsWith<ConfigException> {
            parseConfig("applicationMemoryAlertMiB=1048577")
        }
        assertFailsWith<ConfigException> {
            parseConfig("swapAlertMiB=1048577")
        }
    }

    @Test
    fun acceptsMemoryAndSwapThresholdsAtOneTebibyte() {
        val config = parseConfig(
            "applicationMemoryAlertMiB=1048576",
            "swapAlertMiB=1048576",
        )

        assertEquals(1_048_576L, config.thresholds.applicationMemoryMiB)
        assertEquals(1_048_576L, config.thresholds.swapUsedMiB)
    }

    @Test
    fun readsTerminalApplicationsAsALowerCasedListThatReplacesTheDefaults() {
        val config = parseConfig("terminalApplications= Foo , bar ,, Foo ")

        assertEquals(setOf("foo", "bar"), config.terminalApplications)
    }

    @Test
    fun readsAnEmptyTerminalApplicationsValueAsNoTerminalsAtAll() {
        val config = parseConfig("terminalApplications=")

        assertEquals(emptySet(), config.terminalApplications)
    }

    @Test
    fun keepsTheDefaultTerminalListWhenTheKeyIsAbsent() {
        val config = parseConfig()

        assertEquals(DEFAULT_TERMINAL_APPLICATIONS, config.terminalApplications)
        assertContains(
            config.redactedDescription(),
            "terminalApplications=" + DEFAULT_TERMINAL_APPLICATIONS.joinToString(","),
        )
    }

    @Test
    fun doesNotMistakeAHostnamePrefixForLocalhost() {
        assertFailsWith<ConfigException> {
            parseConfig("webhookUrl=http://127.0.0.1.evil.example/events")
        }
    }

    @Test
    fun doesNotMistakeLocalhostInUserinfoForTheHost() {
        listOf(
            "http://127.0.0.1:80@evil.example/hook",
            "http://127.0.0.1@evil.example/hook",
            "http://127.0.0.1\\@evil.example/hook",
        ).forEach { url ->
            val failure = assertFailsWith<ConfigException>(url) {
                parseConfig("webhookUrl=$url")
            }

            assertContains(assertNotNull(failure.message), "webhookUrl must use HTTPS")
        }
    }

    @Test
    fun acceptsCredentialsInFrontOfALoopbackHost() {
        val config = parseConfig("webhookUrl=http://user:secret@127.0.0.1:9000/hook")

        assertEquals(
            "http://user:secret@127.0.0.1:9000/hook",
            config.notifications.webhookUrl,
        )
    }

    @Test
    fun readsTheHistoryRetentionAndTakesZeroAsNoHistoryAtAll() {
        assertEquals(7L, parseConfig().historyRetentionDays)
        assertEquals(30L, parseConfig("historyRetentionDays=30").historyRetentionDays)
        assertNull(
            parseConfig("historyRetentionDays=0").historyRetentionDays,
            "zero is how every optional key here spells 'off', and off means no database",
        )
    }

    @Test
    fun rejectsAHistoryRetentionThatIsNotAWholeNumberOfDays() {
        listOf("historyRetentionDays=-1", "historyRetentionDays=week").forEach { line ->
            val failure = assertFailsWith<ConfigException>(line) { parseConfig(line) }

            assertContains(
                assertNotNull(failure.message),
                "historyRetentionDays must be a non-negative integer",
            )
        }
    }

    @Test
    fun rejectsAHistoryRetentionLongerThanAnyUseForTheData() {
        assertEquals(3_650L, parseConfig("historyRetentionDays=3650").historyRetentionDays)

        val failure = assertFailsWith<ConfigException> { parseConfig("historyRetentionDays=7000") }

        assertContains(
            assertNotNull(failure.message),
            "historyRetentionDays must be between 0 and 3650",
        )
    }

    @Test
    fun readsTheOrphanAlertSwitchAndLeavesItOnByDefault() {
        assertEquals(true, parseConfig().orphanAlerts)
        assertEquals(false, parseConfig("orphanAlerts=off").orphanAlerts)
        assertEquals(false, parseConfig("orphanAlerts=FALSE").orphanAlerts)
        assertEquals(true, parseConfig("orphanAlerts=yes").orphanAlerts)
        assertContains(parseConfig().redactedDescription(), "orphanAlerts=true")
        assertContains(
            parseConfig("orphanAlerts=0").redactedDescription(),
            "orphanAlerts=false",
        )
    }

    @Test
    fun rejectsAnOrphanAlertSwitchThatIsNotABoolean() {
        val failure = assertFailsWith<ConfigException> { parseConfig("orphanAlerts=sometimes") }

        assertContains(assertNotNull(failure.message), "orphanAlerts must be true or false")
    }

    @Test
    fun readsTheApplicationPowerThresholdAndTakesZeroAsDisabled() {
        assertEquals(1.5, parseConfig().thresholds.applicationPowerWatts)
        assertEquals(
            2.5,
            parseConfig("applicationPowerAlertWatts=2.5").thresholds.applicationPowerWatts,
        )
        assertNull(
            parseConfig("applicationPowerAlertWatts=0").thresholds.applicationPowerWatts,
            "zero is how every optional threshold here spells 'off'",
        )
        assertEquals(
            100.0,
            parseConfig("applicationPowerAlertWatts=0").thresholds.applicationBatteryImpactScore,
            "disabling one regime must leave the other one's threshold alone",
        )
    }

    @Test
    fun rejectsANegativeApplicationPowerThreshold() {
        val failure = assertFailsWith<ConfigException> {
            parseConfig("applicationPowerAlertWatts=-1")
        }

        assertContains(
            assertNotNull(failure.message),
            "applicationPowerAlertWatts must be a non-negative number",
        )
    }

    @Test
    fun reportsTheApplicationPowerThresholdEvenWhenItIsDisabled() {
        assertContains(parseConfig().redactedDescription(), "applicationPowerAlertWatts=1.5")
        assertContains(
            parseConfig("applicationPowerAlertWatts=0").redactedDescription(),
            "applicationPowerAlertWatts=0",
        )
    }

    @Test
    fun reportsTheHistoryRetentionEvenWhenItIsDisabled() {
        assertContains(parseConfig().redactedDescription(), "historyRetentionDays=7")
        assertContains(
            parseConfig("historyRetentionDays=0").redactedDescription(),
            "historyRetentionDays=0",
        )
    }
}

private fun parseConfig(vararg lines: String): HarmonConfig =
    ConfigLoader.parse(lines = lines.asSequence(), environment = emptyMap())
