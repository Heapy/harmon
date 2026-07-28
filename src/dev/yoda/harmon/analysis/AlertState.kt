package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.Alert

/**
 * Edge detection for alerts: a push goes out when an alert key appears that was not
 * confirmed as delivered before, not on a timer.
 *
 * Two sets are tracked instead of one. [firing] holds the keys whose condition held on the
 * previous sample and feeds hysteresis in `AlertAnalyzer`; it is committed unconditionally.
 * [notified] holds the keys whose delivery actually succeeded and decides what counts as new.
 * Keeping them apart means a failed delivery does not also switch off hysteresis, which would
 * silently drop the alert instead of retrying it.
 *
 * Both sets are bounded by the number of alerts in the current sample, so they cannot grow
 * without limit.
 */
class AlertState {
    private var firing: Set<String> = emptySet()
    private var notified: Set<String> = emptySet()

    val activeKeys: Set<String> get() = firing

    val notifiedKeys: Set<String> get() = notified

    fun newlyActive(alerts: List<Alert>): List<Alert> = alerts.filter { it.key !in notified }

    /**
     * Records the outcome of a sample. Must be called on every sample, including the ones with
     * no delivery at all: otherwise a key that stopped firing would stay in [notified] forever
     * and its next appearance would never produce a push.
     */
    fun commit(alerts: List<Alert>, deliveredKeys: Set<String>) {
        val keys = alerts.mapTo(mutableSetOf()) { it.key }
        firing = keys
        notified = keys intersect (notified + deliveredKeys)
    }
}
