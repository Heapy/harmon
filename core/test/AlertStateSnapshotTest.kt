import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.analysis.DELIVERY_RETRY_THRESHOLD
import dev.yoda.harmon.analysis.deliveryRetryDelaySamples
import dev.yoda.harmon.analysis.isSnapshotFresh
import dev.yoda.harmon.model.Alert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The interval the shipped configuration samples at, and the one the TTL is counted in. */
private const val INTERVAL_SECONDS = 300L

/** Enough samples that a key still waiting is waiting on a bug rather than on the backoff. */
private const val PUSHABLE_SAMPLE_LIMIT = 100

private val SAVED_AT = Instant.parse("2026-07-29T00:00:00Z")

/** Covers the pure alert-state snapshot and freshness policy used across an agent restart. */
class AlertStateSnapshotTest {

    /**
     * The point of putting the sample counter in the snapshot. `retryAtSample` is an absolute sample
     * number, so keys restored against a counter starting at zero would be waiting for a sample
     * thousands of intervals out — an alert with an earned backoff would never be pushed again,
     * which is a worse outcome than the duplicate push restoring exists to prevent.
     */
    @Test
    fun aRestoredKeyWaitsOutExactlyTheBackoffItHadLeft() {
        val alerts = listOf(alert("cpu:firefox"))
        val original = AlertState()

        repeat(DELIVERY_RETRY_THRESHOLD) {
            original.commit(setOf("cpu:firefox"), emptySet(), failedKeys = setOf("cpu:firefox"))
        }
        assertTrue(original.newlyActive(alerts).isEmpty(), "the retry has to be deferred first")

        val restoredWait = samplesUntilPushable(AlertState(restored = original.snapshot()), alerts)

        assertEquals(
            samplesUntilPushable(original, alerts),
            restoredWait,
            "a restart may neither shorten the backoff nor extend it",
        )
        assertEquals(
            deliveryRetryDelaySamples(DELIVERY_RETRY_THRESHOLD).toInt(),
            restoredWait,
            "the key was deferred by this many samples and had waited none of them off",
        )
    }

    /**
     * Two intervals is a launchd restart with slack. Restoring what an agent saw a day ago would hand
     * the hysteresis in `AlertAnalyzer` a lowered clear threshold for a machine that has moved on.
     */
    @Test
    fun aSnapshotStaysFreshForTwoIntervalsAndNoLonger() {
        assertTrue(isSnapshotFresh(SAVED_AT, SAVED_AT, INTERVAL_SECONDS))
        assertTrue(isSnapshotFresh(SAVED_AT, SAVED_AT + INTERVAL_SECONDS.seconds, INTERVAL_SECONDS))
        assertTrue(
            isSnapshotFresh(SAVED_AT, SAVED_AT + (INTERVAL_SECONDS * 2).seconds, INTERVAL_SECONDS),
        )
        assertFalse(
            isSnapshotFresh(SAVED_AT, SAVED_AT + (INTERVAL_SECONDS * 3).seconds, INTERVAL_SECONDS),
        )
    }

    /**
     * An NTP step or a time-zone update can put the clock behind the last sample. The age of the
     * snapshot is then unknowable, and the safe reading of an unknown age is "too old".
     */
    @Test
    fun aSnapshotSavedAfterTheCurrentMomentIsNotFresh() {
        assertFalse(isSnapshotFresh(SAVED_AT, SAVED_AT - 1.seconds, INTERVAL_SECONDS))
        assertFalse(isSnapshotFresh(SAVED_AT, SAVED_AT - 1.days, INTERVAL_SECONDS))
    }
}

/**
 * How many samples [state] takes before it will push one of [alerts] again, committing a sample at a
 * time with nothing delivered and nothing failing — the machine still over its threshold and the
 * channel still not answering.
 */
private fun samplesUntilPushable(state: AlertState, alerts: List<Alert>): Int {
    val keys = alerts.mapTo(mutableSetOf()) { it.key }
    var samples = 0

    while (state.newlyActive(alerts).isEmpty()) {
        if (samples >= PUSHABLE_SAMPLE_LIMIT) {
            fail("the key was still deferred after $PUSHABLE_SAMPLE_LIMIT samples")
        }
        state.commit(keys, emptySet())
        samples += 1
    }
    return samples
}
