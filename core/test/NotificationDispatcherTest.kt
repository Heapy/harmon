import io.heapy.harmon.model.NotificationPayload
import io.heapy.harmon.notify.NotificationDispatcher
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
