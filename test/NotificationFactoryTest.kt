import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.notify.SYSTEM_CHANNEL_BEST_EFFORT
import dev.yoda.harmon.notify.from
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationFactoryTest {
    @Test
    fun buildsNoChannelWhenNothingIsConfigured() {
        val dispatcher = NotificationDispatcher.from(
            NotificationConfig(systemEnabled = false),
        )

        assertTrue(dispatcher.isEmpty)
    }

    @Test
    fun buildsAWebhookChannelFromAUrlAlone() {
        val dispatcher = NotificationDispatcher.from(
            NotificationConfig(
                systemEnabled = false,
                webhookUrl = "https://example.invalid/hook",
            ),
        )

        assertFalse(dispatcher.isEmpty)
    }

    /** Telegram needs both halves; half a configuration must not produce a channel. */
    @Test
    fun buildsATelegramChannelOnlyWhenBothTokenAndChatIdAreSet() {
        val tokenOnly = NotificationDispatcher.from(
            NotificationConfig(systemEnabled = false, telegramBotToken = "token"),
        )
        val chatOnly = NotificationDispatcher.from(
            NotificationConfig(systemEnabled = false, telegramChatId = "chat"),
        )
        val both = NotificationDispatcher.from(
            NotificationConfig(
                systemEnabled = false,
                telegramBotToken = "token",
                telegramChatId = "chat",
            ),
        )

        assertTrue(tokenOnly.isEmpty)
        assertTrue(chatOnly.isEmpty)
        assertFalse(both.isEmpty)
    }

    @Test
    fun systemChannelIsBestEffort() {
        assertTrue(SYSTEM_CHANNEL_BEST_EFFORT)
    }
}
