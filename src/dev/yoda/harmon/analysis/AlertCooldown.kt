package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.Alert
import kotlin.time.Clock
import kotlin.time.Instant

class AlertCooldown(
    private val cooldownSeconds: Long,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lastDeliveredAt = mutableMapOf<String, Instant>()

    init {
        require(cooldownSeconds >= 0) { "cooldownSeconds must be non-negative" }
    }

    fun newAlerts(alerts: List<Alert>): List<Alert> {
        if (cooldownSeconds == 0L) {
            return alerts
        }

        val timestamp = now()
        return alerts.filter { alert ->
            val previous = lastDeliveredAt[alert.key]
            previous == null || timestamp.epochSeconds - previous.epochSeconds >= cooldownSeconds
        }
    }

    fun markDelivered(alerts: List<Alert>) {
        val timestamp = now()
        alerts.forEach { lastDeliveredAt[it.key] = timestamp }
    }
}

