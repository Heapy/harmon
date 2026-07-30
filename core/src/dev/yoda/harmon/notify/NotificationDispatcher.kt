package dev.yoda.harmon.notify

import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.util.failureDescription

interface NotificationChannel {
    val name: String

    /**
     * A channel that cannot confirm delivery synchronously. Its result never decides whether the
     * sample was delivered, so a failure elsewhere is not masked by its optimistic success.
     */
    val bestEffort: Boolean get() = false

    fun deliver(payload: NotificationPayload): DeliveryResult
}

/** What one dispatch achieved: what each channel reported, and whether the sample was delivered. */
data class DeliverySummary(
    val results: List<DeliveryResult>,
    val decisiveSuccess: Boolean,
)

class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
) {
    /**
     * Delivers [payload] through every channel, deciding whether the sample counts as delivered.
     *
     * Only the optimistic success of a best-effort channel is discounted. A reported failure is
     * still decisive, and an empty dispatcher succeeds because nothing contradicts delivery.
     */
    fun deliver(payload: NotificationPayload): DeliverySummary {
        val delivered = channels.map { channel -> channel to channel.resultFor(payload) }
        val observed = delivered
            .filter { (channel, result) -> !channel.bestEffort || !result.successful }
            .map { (_, result) -> result }
        return DeliverySummary(
            results = delivered.map { (_, result) -> result },
            decisiveSuccess = observed.isEmpty() || observed.any { it.successful },
        )
    }

    private fun NotificationChannel.resultFor(payload: NotificationPayload): DeliveryResult =
        try {
            deliver(payload)
        } catch (failure: Throwable) {
            DeliveryResult(
                channel = name,
                successful = false,
                detail = failureDescription(failure),
            )
        }

    val isEmpty: Boolean
        get() = channels.isEmpty()

    companion object
}
