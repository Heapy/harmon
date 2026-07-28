import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.notify.SYSTEM_CHANNEL_BEST_EFFORT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDispatcherTest {
    @Test
    fun bestEffortSuccessDoesNotCoverForAFailedDecisiveChannel() {
        val dispatcher = NotificationDispatcher(
            listOf(
                RecordingChannel("system", bestEffort = true),
                RecordingChannel("webhook", successful = { false }),
            ),
        )

        val summary = dispatcher.deliver(fakePayload())

        assertEquals(listOf(true, false), summary.results.map { it.successful })
        assertFalse(summary.decisiveSuccess)
    }

    @Test
    fun bestEffortChannelAloneCountsAsSuccess() {
        val dispatcher = NotificationDispatcher(
            listOf(RecordingChannel("system", bestEffort = true)),
        )

        assertTrue(dispatcher.deliver(fakePayload()).decisiveSuccess)
    }

    /**
     * The default configuration has Notification Center and nothing else. Its optimistic success
     * is discounted, but an outright failure — `HtmlReportStore.write` on a full disk or a
     * read-only home — is something it did observe, and it must keep the alert pushable instead
     * of settling it as delivered.
     */
    @Test
    fun aBestEffortChannelFailingOnItsOwnIsAFailedDelivery() {
        val dispatcher = NotificationDispatcher(
            listOf(RecordingChannel("system", bestEffort = true, successful = { false })),
        )

        assertFalse(dispatcher.deliver(fakePayload()).decisiveSuccess)
    }

    @Test
    fun aThrowingBestEffortChannelAloneIsAFailedDelivery() {
        val dispatcher = NotificationDispatcher(
            listOf(
                RecordingChannel(
                    "system",
                    bestEffort = true,
                    failure = IllegalStateException("read-only file system"),
                ),
            ),
        )

        assertFalse(dispatcher.deliver(fakePayload()).decisiveSuccess)
    }

    @Test
    fun oneSucceedingDecisiveChannelIsEnough() {
        val dispatcher = NotificationDispatcher(
            listOf(
                RecordingChannel("webhook"),
                RecordingChannel("telegram", successful = { false }),
            ),
        )

        assertTrue(dispatcher.deliver(fakePayload()).decisiveSuccess)
    }

    @Test
    fun everyDecisiveChannelFailingIsAFailure() {
        val dispatcher = NotificationDispatcher(
            listOf(
                RecordingChannel("webhook", successful = { false }),
                RecordingChannel("telegram", successful = { false }),
            ),
        )

        assertFalse(dispatcher.deliver(fakePayload()).decisiveSuccess)
    }

    /**
     * The system channel cannot be built here — it is internal and constructing it boots AppKit
     * — so the flag it overrides is asserted directly, and then the behaviour that depends on it.
     * Without this the override could be deleted with a fully green suite.
     */
    @Test
    fun theSystemChannelIsBestEffortAndCannotConfirmADelivery() {
        val dispatcher = NotificationDispatcher(
            listOf(
                RecordingChannel("system", bestEffort = SYSTEM_CHANNEL_BEST_EFFORT),
                RecordingChannel("webhook", successful = { false }),
            ),
        )

        assertTrue(SYSTEM_CHANNEL_BEST_EFFORT)
        assertFalse(dispatcher.deliver(fakePayload()).decisiveSuccess)
    }

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
    fun throwingChannelIsReportedAsAFailedDelivery() {
        val dispatcher = NotificationDispatcher(
            listOf(RecordingChannel("webhook", failure = IllegalStateException("socket closed"))),
        )

        val summary = dispatcher.deliver(fakePayload())

        assertEquals(1, summary.results.size)
        assertEquals("webhook", summary.results.single().channel)
        assertFalse(summary.results.single().successful)
        assertEquals("socket closed", summary.results.single().detail)
        assertFalse(summary.decisiveSuccess)
    }
}

private fun fakePayload(): NotificationPayload = NotificationPayload(
    identifier = "harmon-test",
    title = "title",
    subtitle = "subtitle",
    text = "text",
    html = "<!doctype html><html></html>",
    json = "{}",
)
