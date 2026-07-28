import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.notify.NotificationChannel
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
                fakeChannel("system", successful = true, bestEffort = true),
                fakeChannel("webhook", successful = false),
            ),
        )

        val results = dispatcher.deliver(fakePayload())

        assertEquals(listOf(true, false), results.map { it.successful })
        assertFalse(dispatcher.decisiveSuccess(results))
    }

    @Test
    fun bestEffortChannelAloneCountsAsSuccess() {
        val dispatcher = NotificationDispatcher(
            listOf(fakeChannel("system", successful = true, bestEffort = true)),
        )

        assertTrue(dispatcher.decisiveSuccess(dispatcher.deliver(fakePayload())))
    }

    /**
     * The default configuration has Notification Center and nothing else. Its optimistic success
     * is discounted, but an outright failure — `HtmlReportStore.write` on a full disk or a
     * read-only home — is something it did observe, and it must keep the alert pushable instead of
     * settling it as delivered.
     */
    @Test
    fun aBestEffortChannelFailingOnItsOwnIsAFailedDelivery() {
        val dispatcher = NotificationDispatcher(
            listOf(fakeChannel("system", successful = false, bestEffort = true)),
        )

        assertFalse(dispatcher.decisiveSuccess(dispatcher.deliver(fakePayload())))
    }

    @Test
    fun aThrowingBestEffortChannelAloneIsAFailedDelivery() {
        val dispatcher = NotificationDispatcher(
            listOf(
                fakeChannel(
                    "system",
                    bestEffort = true,
                    failure = IllegalStateException("read-only file system"),
                ),
            ),
        )

        assertFalse(dispatcher.decisiveSuccess(dispatcher.deliver(fakePayload())))
    }

    @Test
    fun oneSucceedingDecisiveChannelIsEnough() {
        val dispatcher = NotificationDispatcher(
            listOf(
                fakeChannel("webhook", successful = true),
                fakeChannel("telegram", successful = false),
            ),
        )

        assertTrue(dispatcher.decisiveSuccess(dispatcher.deliver(fakePayload())))
    }

    @Test
    fun everyDecisiveChannelFailingIsAFailure() {
        val dispatcher = NotificationDispatcher(
            listOf(
                fakeChannel("webhook", successful = false),
                fakeChannel("telegram", successful = false),
            ),
        )

        assertFalse(dispatcher.decisiveSuccess(dispatcher.deliver(fakePayload())))
    }

    /**
     * The system channel cannot be built here — it is internal and constructing it boots AppKit —
     * so the flag it overrides is asserted directly, and then the behaviour that depends on it.
     * Without this the override could be deleted with a fully green suite.
     */
    @Test
    fun theSystemChannelIsBestEffortAndCannotConfirmADelivery() {
        val dispatcher = NotificationDispatcher(
            listOf(
                fakeChannel("system", successful = true, bestEffort = SYSTEM_CHANNEL_BEST_EFFORT),
                fakeChannel("webhook", successful = false),
            ),
        )

        assertTrue(SYSTEM_CHANNEL_BEST_EFFORT)
        assertFalse(dispatcher.decisiveSuccess(dispatcher.deliver(fakePayload())))
    }

    /** A results list this dispatcher did not produce must not index past its channels. */
    @Test
    fun aResultWithoutAChannelIsTreatedAsDecisiveRatherThanIndexedBlindly() {
        val dispatcher = NotificationDispatcher(
            listOf(fakeChannel("system", successful = true, bestEffort = true)),
        )
        val strayResults = dispatcher.deliver(fakePayload()) +
            DeliveryResult(channel = "stray", successful = false, detail = "not ours")

        assertFalse(dispatcher.decisiveSuccess(strayResults))
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
            listOf(fakeChannel("webhook", failure = IllegalStateException("socket closed"))),
        )

        val results = dispatcher.deliver(fakePayload())

        assertEquals(1, results.size)
        assertEquals("webhook", results.single().channel)
        assertFalse(results.single().successful)
        assertEquals("socket closed", results.single().detail)
        assertFalse(dispatcher.decisiveSuccess(results))
    }
}

private fun fakeChannel(
    name: String,
    successful: Boolean = false,
    bestEffort: Boolean = false,
    failure: Throwable? = null,
): NotificationChannel = FakeNotificationChannel(name, successful, bestEffort, failure)

private class FakeNotificationChannel(
    override val name: String,
    private val successful: Boolean,
    override val bestEffort: Boolean,
    private val failure: Throwable?,
) : NotificationChannel {
    override fun deliver(payload: NotificationPayload): DeliveryResult {
        failure?.let { throw it }
        return DeliveryResult(channel = name, successful = successful, detail = "fake")
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
