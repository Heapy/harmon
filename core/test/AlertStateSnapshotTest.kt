import io.heapy.harmon.analysis.AlertState
import io.heapy.harmon.analysis.DELIVERY_RETRY_THRESHOLD
import io.heapy.harmon.analysis.deliveryRetryDelaySamples
import io.heapy.harmon.analysis.isSnapshotFresh
import io.heapy.harmon.model.Alert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val INTERVAL_SECONDS = 300L

private const val PUSHABLE_SAMPLE_LIMIT = 100

private val SAVED_AT = Instant.parse("2026-07-29T00:00:00Z")

class AlertStateSnapshotTest {

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

    @Test
    fun aSnapshotSavedAfterTheCurrentMomentIsNotFresh() {
        assertFalse(isSnapshotFresh(SAVED_AT, SAVED_AT - 1.seconds, INTERVAL_SECONDS))
        assertFalse(isSnapshotFresh(SAVED_AT, SAVED_AT - 1.days, INTERVAL_SECONDS))
    }
}

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
