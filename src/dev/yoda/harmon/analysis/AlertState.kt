package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.Alert

/**
 * How many consecutive failed deliveries a key is retried on every sample before its retries start
 * backing off. Notification Center coalesces nothing, so a permanently broken decisive channel —
 * a typo'd webhook URL, a revoked bot token — would otherwise mean a fresh banner every interval
 * forever.
 */
const val DELIVERY_RETRY_THRESHOLD = 3

/** Longest gap, in samples, that a key's retry is deferred by. */
const val MAX_DELIVERY_RETRY_SAMPLES = 32L

/**
 * How many samples a key whose delivery failed [consecutiveFailures] times in a row waits before it
 * is pushed again.
 *
 * The first [DELIVERY_RETRY_THRESHOLD] failures retry immediately — a webhook that is down for
 * one interval must not delay the alert at all — and the gap then doubles up to
 * [MAX_DELIVERY_RETRY_SAMPLES]. It never becomes infinite: an alert whose condition still holds is
 * always pushed again eventually, so a channel that comes back finds the alert waiting.
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
 * Edge detection for alerts: a push goes out when an alert key appears that was not confirmed as
 * delivered before, not on a timer.
 *
 * Two sets are tracked instead of one. [activeKeys] feeds hysteresis in `AlertAnalyzer`, the
 * settled set decides what may be pushed; keeping them apart means a failed delivery does not
 * also switch off hysteresis, which would silently drop the alert instead of retrying it. A key
 * whose delivery keeps failing is never settled and never dropped, only deferred by
 * [deliveryRetryDelaySamples].
 *
 * Every map and set is pruned to the keys firing in the current sample, so none can grow without
 * limit.
 */
class AlertState {
    private var firing: Set<String> = emptySet()
    private var settled: Set<String> = emptySet()
    private var retries: Map<String, RetryState> = emptyMap()
    private var sample: Long = 0

    val activeKeys: Set<String> get() = firing

    /**
     * The alerts whose key has never been confirmed as delivered while it kept firing.
     *
     * This is what a caller that pushes on every sample has to name as new: it pushes regardless
     * of the retry backoff, so applying the backoff here as well would drop a key from the new set
     * of the very payload that finally carries it.
     */
    fun unsettled(alerts: List<Alert>): List<Alert> = alerts.filter { it.key !in settled }

    /** The subset of [unsettled] whose retry the delivery backoff is not currently deferring. */
    fun newlyActive(alerts: List<Alert>): List<Alert> =
        unsettled(alerts).filter { (retries[it.key]?.retryAtSample ?: 0L) <= sample }

    /**
     * Records the outcome of a sample and returns, for every key whose retry was deferred, how
     * many samples it now waits.
     *
     * [firingKeys] is neither the reported alerts nor every key over a threshold: a firing key the
     * report's per-category cap pushed out belongs here, while one that first crossed below the
     * cap was never pushed and must stay out of a state that decides what has been delivered and
     * what clears with hysteresis.
     *
     * Must be called on every sample, including the ones with no delivery at all: otherwise a key
     * that stopped firing would stay settled forever and never produce a push again.
     */
    fun commit(
        firingKeys: Set<String>,
        deliveredKeys: Set<String>,
        failedKeys: Set<String> = emptySet(),
    ): Map<String, Long> {
        sample += 1
        val settledNow = firingKeys intersect (settled + deliveredKeys)
        val deferred = backoff(firingKeys, failedKeys)
        firing = firingKeys
        settled = settledNow
        retries = retriesAfter(deliveredKeys, failedKeys, deferred)
            .filterKeys { it in firingKeys && it !in settledNow }
        return deferred
    }

    /** How many samples each key that failed on this sample now waits, deferred keys only. */
    private fun backoff(firingKeys: Set<String>, failedKeys: Set<String>): Map<String, Long> =
        failedKeys
            .filter { it in firingKeys }
            .associateWith { key -> deliveryRetryDelaySamples(failureCount(key)) }
            .filterValues { it > 0L }

    private fun retriesAfter(
        deliveredKeys: Set<String>,
        failedKeys: Set<String>,
        deferred: Map<String, Long>,
    ): Map<String, RetryState> {
        val carried = if (deliveredKeys.isEmpty()) retries else emptyMap()
        return carried + failedKeys.associateWith { key ->
            RetryState(
                failures = failureCount(key),
                retryAtSample = deferred[key]?.let { sample + it } ?: 0L,
            )
        }
    }

    private fun failureCount(key: String): Int = (retries[key]?.failures ?: 0) + 1

    /** What one key's failed deliveries have earned it: how many in a row, and until when. */
    private data class RetryState(
        val failures: Int,
        val retryAtSample: Long,
    )
}
