import dev.yoda.harmon.config.ConfigException
import dev.yoda.harmon.config.ConfigLoader
import kotlin.test.Test
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
    fun rejectsUnknownKeys() {
        assertFailsWith<ConfigException> {
            ConfigLoader.parse(
                lines = sequenceOf("intervallSeconds=60"),
                environment = emptyMap(),
            )
        }
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
