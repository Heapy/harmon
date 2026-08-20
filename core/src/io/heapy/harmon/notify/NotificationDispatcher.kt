package io.heapy.harmon.notify

import io.heapy.harmon.model.DeliveryResult
import io.heapy.harmon.model.NotificationPayload
import io.heapy.harmon.util.failureDescription

interface NotificationChannel {
    val name: String

    /** Optimistic success from an asynchronously confirmed channel cannot settle an alert. */
    val bestEffort: Boolean get() = false

    fun deliver(payload: NotificationPayload): DeliveryResult
}

data class DeliverySummary(
    val results: List<DeliveryResult>,
    val decisiveSuccess: Boolean,
)

class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
) {
    /** Reported failures remain decisive; only optimistic best-effort successes are discounted. */
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
