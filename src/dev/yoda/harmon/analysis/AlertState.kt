package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.Alert

/**
 * How often a single alert key is pushed without a confirmed delivery before it is given up on.
 *
 * A permanently broken decisive channel — a typo'd webhook URL, a revoked bot token — would
 * otherwise re-push the same alert set on every sample forever, and Notification Center coalesces
 * nothing, so the user would get a fresh banner every interval indefinitely.
 */
const val MAX_DELIVERY_ATTEMPTS = 3

/**
 * Edge detection for alerts: a push goes out when an alert key appears that was not
 * confirmed as delivered before, not on a timer.
 *
 * Two sets are tracked instead of one. [activeKeys] holds the keys whose condition held on the
 * previous sample and feeds hysteresis in `AlertAnalyzer`; it is committed unconditionally. The
 * settled set holds the keys that must not be pushed again while they keep firing and decides
 * what counts as new. Keeping them apart means a failed delivery does not also switch off
 * hysteresis, which would silently drop the alert instead of retrying it.
 *
 * A key whose delivery keeps failing is retried [MAX_DELIVERY_ATTEMPTS] times and then settled
 * anyway, so a channel that can never succeed cannot turn the agent into an endless banner loop.
 * Its counter is dropped as soon as the alert clears, so the key gets a fresh retry budget the
 * next time it fires.
 *
 * Every set is bounded by the number of alerts in the current sample, so none of them can grow
 * without limit.
 */
class AlertState {
    private var firing: Set<String> = emptySet()
    private var settled: Set<String> = emptySet()
    private var failedAttempts: Map<String, Int> = emptyMap()

    val activeKeys: Set<String> get() = firing

    fun newlyActive(alerts: List<Alert>): List<Alert> = alerts.filter { it.key !in settled }

    /**
     * Records the outcome of a sample and returns the keys that were pushed
     * [MAX_DELIVERY_ATTEMPTS] times without a confirmed delivery and will not be retried until
     * they clear.
     *
     * Must be called on every sample, including the ones with no delivery at all: otherwise a key
     * that stopped firing would stay settled forever and its next appearance would never produce
     * a push.
     */
    fun commit(
        alerts: List<Alert>,
        deliveredKeys: Set<String>,
        failedKeys: Set<String> = emptySet(),
    ): Set<String> {
        val keys = alerts.mapTo(mutableSetOf()) { it.key }
        val attempts = failedKeys.associateWith { key -> (failedAttempts[key] ?: 0) + 1 }
        val exhausted = attempts
            .filterValues { it >= MAX_DELIVERY_ATTEMPTS }
            .keys
            .intersect(keys)
        firing = keys
        settled = keys intersect (settled + deliveredKeys + exhausted)
        failedAttempts = (failedAttempts + attempts).filterKeys { it in keys && it !in settled }
        return exhausted
    }
}
