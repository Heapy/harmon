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

    /**
     * `onceSampleSeconds`, `--sample-seconds` and `HarmonService.sampleOnce` share one range, so
     * a config file cannot set a window the other two would reject.
     */
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

    /**
     * Userinfo is not a host. libcurl resolves each of these to `evil.example`, so reading the
     * part before the `@` as the host would send the payload and its bearer token to an arbitrary
     * server over plaintext HTTP — which is the one thing the loopback exemption exists to stop.
     */
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

    /** Userinfo in front of a genuine loopback host is still loopback, and still cleartext-safe. */
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

    /**
     * The upper bound exists because the failure at the other end is silent. `7000` is the typo for
     * `7` that costs nothing to make, and it parses into a cutoff no stored sample is ever older
     * than — so the pass deletes nothing, the file grows for as long as the agent runs, and nothing
     * anywhere says a word about it.
     */
    @Test
    fun rejectsAHistoryRetentionLongerThanAnyUseForTheData() {
        assertEquals(3_650L, parseConfig("historyRetentionDays=3650").historyRetentionDays)

        val failure = assertFailsWith<ConfigException> { parseConfig("historyRetentionDays=7000") }

        assertContains(
            assertNotNull(failure.message),
            "historyRetentionDays must be between 0 and 3650",
        )
    }

    /**
     * The one rule with no threshold to zero out. Every other one is disabled by setting its
     * number to zero, so this key is what keeps that promise for orphan alerts.
     */
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

    /** `check-config` is where a user finds out history is off, so the key has to appear disabled. */
    @Test
    fun reportsTheHistoryRetentionEvenWhenItIsDisabled() {
        assertContains(parseConfig().redactedDescription(), "historyRetentionDays=7")
        assertContains(
            parseConfig("historyRetentionDays=0").redactedDescription(),
            "historyRetentionDays=0",
        )
    }
}

/**
 * A config file and nothing else — no environment, no warning sink.
 *
 * Which is what almost every test here wants. The two that call `ConfigLoader.parse` directly are
 * the two with something to say about the other arguments.
 */
private fun parseConfig(vararg lines: String): HarmonConfig =
    ConfigLoader.parse(lines = lines.asSequence(), environment = emptyMap())
