package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.Alert
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The consecutive-failure count at which a key's retries start backing off: every failure below it
 * is retried on the very next sample; this one and each later one is deferred. Notification Center
 * coalesces nothing, so a permanently broken decisive channel — a typo'd webhook URL, a revoked
 * bot token — would otherwise mean a fresh banner every interval forever.
 */
const val DELIVERY_RETRY_THRESHOLD = 3

/** Longest gap, in samples, that a key's retry is deferred by. */
const val MAX_DELIVERY_RETRY_SAMPLES = 32L

/**
 * How many samples a key whose delivery failed [consecutiveFailures] times in a row waits before it
 * is pushed again.
 *
 * Every failure below [DELIVERY_RETRY_THRESHOLD] retries immediately — a webhook that is down for
 * one interval must not delay the alert at all. The [DELIVERY_RETRY_THRESHOLD]th defers the retry
 * by two samples, and the gap doubles from there up to [MAX_DELIVERY_RETRY_SAMPLES]. It never
 * becomes infinite: an alert whose condition still holds is always pushed again eventually, so a
 * channel that comes back finds the alert waiting.
 */
fun deliveryRetryDelaySamples(consecutiveFailures: Int): Long {
    if (consecutiveFailures < DELIVERY_RETRY_THRESHOLD) {
        return 0L
    }
    val exponent = minOf(consecutiveFailures - DELIVERY_RETRY_THRESHOLD + 1, MAX_RETRY_EXPONENT)
    return minOf(1L shl exponent, MAX_DELIVERY_RETRY_SAMPLES)
}

private const val MAX_RETRY_EXPONENT = 16

/** How many sampling intervals a stored snapshot stays worth restoring; see [isSnapshotFresh]. */
private const val SNAPSHOT_FRESH_INTERVALS = 2

/**
 * Whether a snapshot saved at [savedAt] still describes the machine [now], at a sampling interval
 * of [intervalSeconds].
 *
 * Two intervals is a launchd restart with slack, not a return after a day. Restoring is not free:
 * the keys it brings back feed the hysteresis in `AlertAnalyzer`, which applies a lowered clear
 * threshold to whatever was firing — and applying it to a state from yesterday morning would hold
 * an alert open against a machine that has since been rebooted twice.
 *
 * A [now] before [savedAt] is not fresh either. The clock moving backwards — a time-zone database
 * update, an NTP step after a flat battery — makes the age of the snapshot unknowable, and the safe
 * reading of an unknown age is "too old".
 */
fun isSnapshotFresh(savedAt: Instant, now: Instant, intervalSeconds: Long): Boolean {
    val age = now - savedAt
    return !age.isNegative() && age <= (intervalSeconds * SNAPSHOT_FRESH_INTERVALS).seconds
}

/**
 * What one alert key carries across a restart: whether its delivery was ever confirmed, and how far
 * the backoff of a failing one has run.
 *
 * There is no `firing` flag because there could be no other value. Only firing keys reach a
 * snapshot: `AlertState.commit` prunes every map and set it keeps down to the keys of the current
 * sample.
 */
data class AlertKeyState(
    val settled: Boolean,
    val failures: Int,
    val retryAtSample: Long,
)

/**
 * The whole of an [AlertState] as it stood after a commit, in a form that survives the process.
 *
 * [sampleCounter] is not bookkeeping. [AlertKeyState.retryAtSample] is an absolute sample number,
 * so restoring the keys against a counter that starts at zero would leave every deferred key
 * waiting for a sample thousands of intervals away — an alert with an earned backoff would stop
 * being pushed altogether, which is a worse failure than the repeated push restoring exists to
 * prevent.
 */
data class AlertStateSnapshot(
    val sampleCounter: Long,
    val keys: Map<String, AlertKeyState>,
)

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
 *
 * [restored] resumes the state a previous run of the agent left behind, so that a restart neither
 * pushes an alert that never stopped firing a second time nor hands a broken channel a fresh retry
 * budget. It seeds the sample counter along with the keys, for the reason [AlertStateSnapshot]
 * gives.
 */
class AlertState(restored: AlertStateSnapshot? = null) {
    private var firing: Set<String> = restored?.keys?.keys.orEmpty()
    private var settled: Set<String> = restored?.keys.orEmpty()
        .filterValues { it.settled }
        .keys
    private var retries: Map<String, RetryState> = restored?.keys.orEmpty()
        /* A key that never failed has no retry state to restore; `failures` starts at one. */
        .filterValues { it.failures > 0 }
        .mapValues { (_, state) -> RetryState(state.failures, state.retryAtSample) }
    private var sample: Long = restored?.sampleCounter ?: 0

    val activeKeys: Set<String> get() = firing

    /**
     * The state as it stands now, for a caller that will hand it back through [restored].
     *
     * Taken after [commit], which is what makes it a plain list of the firing keys: every key still
     * held is one of them, so a key that stopped firing is absent rather than marked as cleared.
     */
    fun snapshot(): AlertStateSnapshot = AlertStateSnapshot(
        sampleCounter = sample,
        keys = firing.associateWith { key ->
            val retry = retries[key]
            AlertKeyState(
                settled = key in settled,
                failures = retry?.failures ?: 0,
                retryAtSample = retry?.retryAtSample ?: 0L,
            )
        },
    )

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
