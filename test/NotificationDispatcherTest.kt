import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.notify.NotificationChannel
import dev.yoda.harmon.notify.NotificationDispatcher
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

    @Test
    fun emptyDispatcherIsNotTreatedAsAFailedDelivery() {
        val dispatcher = NotificationDispatcher(emptyList())

        assertTrue(dispatcher.isEmpty)
        assertTrue(dispatcher.decisiveSuccess(dispatcher.deliver(fakePayload())))
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
