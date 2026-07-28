package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.Alert

/**
 * How many consecutive failed deliveries a key is retried on every sample before its retries start
 * backing off.
 *
 * A permanently broken decisive channel — a typo'd webhook URL, a revoked bot token — would
 * otherwise re-push the same alert set on every sample forever, and Notification Center coalesces
 * nothing, so the user would get a fresh banner every interval indefinitely.
 */
const val DELIVERY_RETRY_THRESHOLD = 3

/** Longest gap, in samples, that a key's retry is deferred by. */
const val MAX_DELIVERY_RETRY_SAMPLES = 32L

/**
 * How many samples a key whose delivery failed [consecutiveFailures] times in a row waits before it
 * is pushed again.
 *
 * The first [DELIVERY_RETRY_THRESHOLD] failures retry immediately — a webhook that is down for one
 * interval must not delay the alert at all — and the gap then doubles up to
 * [MAX_DELIVERY_RETRY_SAMPLES]. It never becomes infinite: an alert whose condition still holds is
 * always pushed again eventually, so a channel that comes back finds the alert waiting.
 *
 * Public so the schedule can be asserted without driving a whole agent loop.
 */
fun deliveryRetryDelaySamples(consecutiveFailures: Int): Long {
    if (consecutiveFailures < DELIVERY_RETRY_THRESHOLD) {
        return 0L
    }
    val exponent = minOf(consecutiveFailures - DELIVERY_RETRY_THRESHOLD + 1, MAX_RETRY_EXPONENT)
    return minOf(1L shl exponent, MAX_DELIVERY_RETRY_SAMPLES)
}

private const val MAX_RETRY_EXPONENT = 16

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
 * A key whose delivery keeps failing is never settled and never dropped. Its retries are spread
 * out by [deliveryRetryDelaySamples] instead, so a channel that is down for an hour delays the
 * alert rather than losing it, and a channel that never works cannot turn the agent into an
 * endless banner loop. A confirmed delivery clears every deferral, because one channel answering
 * again is evidence the outage is over.
 *
 * Every map and set is pruned to the keys firing in the current sample, so none of them can grow
 * without limit.
 */
class AlertState {
    private var firing: Set<String> = emptySet()
    private var settled: Set<String> = emptySet()
    private var failures: Map<String, Int> = emptyMap()
    private var retryAt: Map<String, Long> = emptyMap()
    private var sample: Long = 0

    val activeKeys: Set<String> get() = firing

    fun newlyActive(alerts: List<Alert>): List<Alert> =
        alerts.filter { it.key !in settled && (retryAt[it.key] ?: 0L) <= sample }

    /**
     * Records the outcome of a sample and returns, for every key whose retry was deferred, how
     * many samples it now waits.
     *
     * [firingKeys] is the complete set of keys whose condition holds, which is wider than the
     * alerts a report chose to carry: a key dropped from a report for readability is still firing
     * and must keep both its hysteresis and its settled state.
     *
     * Must be called on every sample, including the ones with no delivery at all: otherwise a key
     * that stopped firing would stay settled forever and its next appearance would never produce
     * a push.
     */
    fun commit(
        firingKeys: Set<String>,
        deliveredKeys: Set<String>,
        failedKeys: Set<String> = emptySet(),
    ): Map<String, Long> {
        sample += 1
        val attempts = failedKeys.associateWith { key -> (failures[key] ?: 0) + 1 }
        val deferred = attempts
            .filterKeys { it in firingKeys }
            .mapValues { (_, count) -> deliveryRetryDelaySamples(count) }
            .filterValues { it > 0L }
        firing = firingKeys
        settled = firingKeys intersect (settled + deliveredKeys)
        val recovered = deliveredKeys.isNotEmpty()
        failures = (if (recovered) attempts else failures + attempts)
            .filterKeys { it in firingKeys && it !in settled }
        retryAt = (if (recovered) emptyMap() else retryAt)
            .plus(deferred.mapValues { (_, delay) -> sample + delay })
            .filterKeys { it in failures }
        return deferred
    }
}
