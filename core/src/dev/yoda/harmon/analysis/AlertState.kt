package dev.yoda.harmon.analysis

import dev.yoda.harmon.model.Alert
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Failures below this retry immediately; later failures back off to bound repeated notifications. */
const val DELIVERY_RETRY_THRESHOLD = 3

const val MAX_DELIVERY_RETRY_SAMPLES = 32L

/** Exponential retry delay, capped so a still-firing alert is always attempted again eventually. */
fun deliveryRetryDelaySamples(consecutiveFailures: Int): Long {
    if (consecutiveFailures < DELIVERY_RETRY_THRESHOLD) {
        return 0L
    }
    val exponent = minOf(consecutiveFailures - DELIVERY_RETRY_THRESHOLD + 1, MAX_RETRY_EXPONENT)
    return minOf(1L shl exponent, MAX_DELIVERY_RETRY_SAMPLES)
}

private const val MAX_RETRY_EXPONENT = 16

private const val SNAPSHOT_FRESH_INTERVALS = 2

/**
 * Restores only recent state. Future timestamps are rejected because a backwards clock makes age
 * unknowable, and stale state would apply hysteresis to an unrelated machine state.
 */
fun isSnapshotFresh(savedAt: Instant, now: Instant, intervalSeconds: Long): Boolean {
    val age = now - savedAt
    return !age.isNegative() && age <= (intervalSeconds * SNAPSHOT_FRESH_INTERVALS).seconds
}

data class AlertKeyState(
    val settled: Boolean,
    val failures: Int,
    val retryAtSample: Long,
)

/** [sampleCounter] must travel with absolute retry sample numbers across a restart. */
data class AlertStateSnapshot(
    val sampleCounter: Long,
    val keys: Map<String, AlertKeyState>,
)

/**
 * Tracks firing keys separately from delivered keys so delivery failures do not disable
 * hysteresis. All state is pruned to the current firing set and may resume from [restored].
 */
class AlertState(restored: AlertStateSnapshot? = null) {
    private var firing: Set<String> = restored?.keys?.keys.orEmpty()
    private var settled: Set<String> = restored?.keys.orEmpty()
        .filterValues { it.settled }
        .keys
    private var retries: Map<String, RetryState> = restored?.keys.orEmpty()
        // Zero failures is the persisted spelling of no retry state.
        .filterValues { it.failures > 0 }
        .mapValues { (_, state) -> RetryState(state.failures, state.retryAtSample) }
    private var sample: Long = restored?.sampleCounter ?: 0

    val activeKeys: Set<String> get() = firing

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

    fun unsettled(alerts: List<Alert>): List<Alert> = alerts.filter { it.key !in settled }

    fun newlyActive(alerts: List<Alert>): List<Alert> =
        unsettled(alerts).filter { (retries[it.key]?.retryAtSample ?: 0L) <= sample }

    /**
     * Advances every sample, including samples without delivery, so cleared keys leave the state.
     * [firingKeys] includes capped-out active alerts but excludes never-reported suppressed ones.
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

    private data class RetryState(
        val failures: Int,
        val retryAtSample: Long,
    )
}
