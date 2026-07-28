import dev.yoda.harmon.config.ConfigException
import dev.yoda.harmon.config.ConfigLoader
import dev.yoda.harmon.config.DEFAULT_TERMINAL_APPLICATIONS
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigLoaderTest {
    @Test
    fun parsesValuesAndAcceptsLegacyProcessThresholdKeys() {
        val config = ConfigLoader.parse(
            lines = sequenceOf(
                "# harmon",
                "intervalSeconds=60",
                "processCpuAlertPercent=0",
                "applicationMemoryAlertMiB=4096",
                "systemNotifications=no",
                "webhookUrl=https://example.test/events",
            ),
            environment = emptyMap(),
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

    /**
     * `onceSampleSeconds`, `--sample-seconds` and `HarmonService.sampleOnce` share one range, so
     * a config file cannot set a window the other two would reject.
     */
    @Test
    fun rejectsASampleWindowOutsideTheSharedRange() {
        val failure = assertFailsWith<ConfigException> {
            ConfigLoader.parse(
                lines = sequenceOf("onceSampleSeconds=${SAMPLE_SECONDS_RANGE.last + 1}"),
                environment = emptyMap(),
            )
        }

        assertContains(failure.message.orEmpty(), "onceSampleSeconds must be between")
    }

    @Test
    fun rejectsUnknownKeys() {
        assertFailsWith<ConfigException> {
            ConfigLoader.parse(
                lines = sequenceOf("intervallSeconds=60"),
                environment = emptyMap(),
            )
        }
    }

    @Test
    fun rejectsMemoryAndSwapThresholdsAboveOneTebibyte() {
        assertFailsWith<ConfigException> {
            ConfigLoader.parse(
                lines = sequenceOf("applicationMemoryAlertMiB=1048577"),
                environment = emptyMap(),
            )
        }
        assertFailsWith<ConfigException> {
            ConfigLoader.parse(
                lines = sequenceOf("swapAlertMiB=1048577"),
                environment = emptyMap(),
            )
        }
    }

    @Test
    fun acceptsMemoryAndSwapThresholdsAtOneTebibyte() {
        val config = ConfigLoader.parse(
            lines = sequenceOf(
                "applicationMemoryAlertMiB=1048576",
                "swapAlertMiB=1048576",
            ),
            environment = emptyMap(),
        )

        assertEquals(1_048_576L, config.thresholds.applicationMemoryMiB)
        assertEquals(1_048_576L, config.thresholds.swapUsedMiB)
    }

    @Test
    fun readsTerminalApplicationsAsALowerCasedListThatReplacesTheDefaults() {
        val config = ConfigLoader.parse(
            lines = sequenceOf("terminalApplications= Foo , bar ,, Foo "),
            environment = emptyMap(),
        )

        assertEquals(setOf("foo", "bar"), config.terminalApplications)
    }

    @Test
    fun readsAnEmptyTerminalApplicationsValueAsNoTerminalsAtAll() {
        val config = ConfigLoader.parse(
            lines = sequenceOf("terminalApplications="),
            environment = emptyMap(),
        )

        assertEquals(emptySet(), config.terminalApplications)
    }

    @Test
    fun keepsTheDefaultTerminalListWhenTheKeyIsAbsent() {
        val config = ConfigLoader.parse(lines = emptySequence(), environment = emptyMap())

        assertEquals(DEFAULT_TERMINAL_APPLICATIONS, config.terminalApplications)
        assertContains(config.redactedDescription(), "terminalApplications=terminal,iterm2")
    }

    @Test
    fun doesNotMistakeAHostnamePrefixForLocalhost() {
        assertFailsWith<ConfigException> {
            ConfigLoader.parse(
                lines = sequenceOf("webhookUrl=http://127.0.0.1.evil.example/events"),
                environment = emptyMap(),
            )
        }
    }
}
