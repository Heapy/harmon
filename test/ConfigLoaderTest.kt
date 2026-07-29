import dev.yoda.harmon.config.ConfigException
import dev.yoda.harmon.config.ConfigLoader
import dev.yoda.harmon.config.DEFAULT_TERMINAL_APPLICATIONS
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.config.SAMPLE_SECONDS_RANGE
import dev.yoda.harmon.history.HistoryStore
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertContains(
            config.redactedDescription(),
            "terminalApplications=" + DEFAULT_TERMINAL_APPLICATIONS.joinToString(","),
        )
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
                ConfigLoader.parse(sequenceOf("webhookUrl=$url"), environment = emptyMap())
            }

            assertContains(assertNotNull(failure.message), "webhookUrl must use HTTPS")
        }
    }

    /** Userinfo in front of a genuine loopback host is still loopback, and still cleartext-safe. */
    @Test
    fun acceptsCredentialsInFrontOfALoopbackHost() {
        val config = ConfigLoader.parse(
            lines = sequenceOf("webhookUrl=http://user:secret@127.0.0.1:9000/hook"),
            environment = emptyMap(),
        )

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

    /** `check-config` is where a user finds out history is off, so the key has to appear disabled. */
    @Test
    fun reportsTheHistoryRetentionEvenWhenItIsDisabled() {
        assertContains(parseConfig().redactedDescription(), "historyRetentionDays=7")
        assertContains(
            parseConfig("historyRetentionDays=0").redactedDescription(),
            "historyRetentionDays=0",
        )
    }

    /**
     * The `Cli.kt` side of this cannot be reached from a test — `Command.Run` disappears into
     * `runForever()` — but everything it rests on can: a retention of null is a store that is never
     * opened, and only an opened store ever creates the file. The second half is the control;
     * without it the first would pass just as well against a path Harmon never writes to.
     */
    @Test
    fun aDisabledHistoryLeavesNoDatabaseFileBehind() = withScratchHome { home ->
        assertNull(historyFor(parseConfig("historyRetentionDays=0"), home))
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(databasePath(home)),
            "history off must not so much as create the file",
        )

        val store = assertNotNull(historyFor(parseConfig("historyRetentionDays=1"), home))
        /* sqliter connects on first use, so the file appears with the first sample, not on open. */
        store.record(rankingReport())
        store.close()

        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(databasePath(home)))
    }
}

private fun parseConfig(vararg lines: String): HarmonConfig =
    ConfigLoader.parse(lines = lines.asSequence(), environment = emptyMap())

/** What `harmon run` does with the key, over a scratch home instead of the user's own. */
private fun historyFor(config: HarmonConfig, home: String): HistoryStore? =
    config.historyRetentionDays?.let { retentionDays ->
        HistoryStore.openOrNull(
            retentionDays = retentionDays,
            intervalSeconds = config.intervalSeconds,
            homeDirectory = home,
        )
    }

private fun databasePath(home: String): String =
    "$home/Library/Application Support/Harmon/history.db"
