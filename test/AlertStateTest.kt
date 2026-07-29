import dev.yoda.harmon.analysis.AlertKeyState
import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.analysis.DELIVERY_RETRY_THRESHOLD
import dev.yoda.harmon.analysis.MAX_DELIVERY_RETRY_SAMPLES
import dev.yoda.harmon.analysis.deliveryRetryDelaySamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertStateTest {
    @Test
    fun treatsKeyMissingFromPreviousSampleAsNew() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"), alert("memory:chrome"))

        assertEquals(
            listOf("cpu:firefox", "memory:chrome"),
            state.newlyActive(alerts).map { it.key },
        )
    }

    @Test
    fun stopsReportingKeyOnceDeliveryIsCommitted() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))

        state.commit(setOf("cpu:firefox"), setOf("cpu:firefox"))

        assertTrue(state.newlyActive(alerts).isEmpty())
    }

    @Test
    fun treatsKeyAsNewAgainAfterItStoppedFiring() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))

        state.commit(setOf("cpu:firefox"), setOf("cpu:firefox"))
        state.commit(emptySet(), emptySet())

        assertEquals(listOf("cpu:firefox"), state.newlyActive(alerts).map { it.key })
    }

    @Test
    fun keepsKeyNewWhenDeliveryDidNotSucceed() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))

        state.commit(setOf("cpu:firefox"), emptySet())

        assertEquals(listOf("cpu:firefox"), state.newlyActive(alerts).map { it.key })
    }

    @Test
    fun keepsFailedDeliveryKeyActiveForHysteresis() {
        val state = AlertState()

        state.commit(setOf("cpu:firefox"), emptySet(), failedKeys = setOf("cpu:firefox"))

        assertEquals(setOf("cpu:firefox"), state.activeKeys)
    }

    @Test
    fun keepsOnlyDeliveredKeysThatStillFire() {
        val state = AlertState()
        val firefox = listOf(alert("cpu:firefox"))
        val both = firefox + alert("memory:chrome")

        state.commit(setOf("cpu:firefox", "memory:chrome"), setOf("cpu:firefox", "memory:chrome"))
        state.commit(setOf("cpu:firefox"), emptySet())

        assertEquals(setOf("cpu:firefox"), state.activeKeys)
        assertTrue(state.newlyActive(firefox).isEmpty())
        assertEquals(listOf("memory:chrome"), state.newlyActive(both).map { it.key })
    }

    /**
     * A key the report had no room for is still firing. Committing only the reported alerts would
     * settle it out of the state and make its return to the top slice look like a fresh alert.
     */
    @Test
    fun remembersAFiringKeyThatNoReportCarried() {
        val state = AlertState()
        val demoted = listOf(alert("cpu:chrome"))

        state.commit(setOf("cpu:firefox", "cpu:chrome"), setOf("cpu:firefox", "cpu:chrome"))
        state.commit(setOf("cpu:firefox", "cpu:chrome"), emptySet())

        assertEquals(setOf("cpu:firefox", "cpu:chrome"), state.activeKeys)
        assertTrue(state.newlyActive(demoted).isEmpty(), "a demoted key must not re-push")
    }

    /**
     * A channel that can never succeed — a typo'd webhook URL, a revoked bot token — would push
     * a fresh banner every interval forever, because Notification Center coalesces nothing. The
     * retries widen instead.
     */
    @Test
    fun spreadsOutRetriesOfAKeyThatNeverGetsDelivered() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))
        val pushedOn = mutableListOf<Int>()

        repeat(12) { sample ->
            val pushed = state.newlyActive(alerts).isNotEmpty()
            if (pushed) {
                pushedOn += sample
            }
            state.commit(
                firingKeys = setOf("cpu:firefox"),
                deliveredKeys = emptySet(),
                failedKeys = if (pushed) setOf("cpu:firefox") else emptySet(),
            )
        }

        assertEquals(listOf(0, 1, 2, 5, 10), pushedOn)
    }

    /**
     * The condition still holds, so the alert is still true. Giving up on it permanently is the
     * silent drop the edge detection exists to avoid; the gap only widens up to a bound.
     */
    @Test
    fun neverStopsRetryingAStillFiringAlert() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))
        var pushes = 0
        var lastPush = 0

        repeat(600) { sample ->
            val pushed = state.newlyActive(alerts).isNotEmpty()
            if (pushed) {
                pushes += 1
                lastPush = sample
            }
            state.commit(
                firingKeys = setOf("cpu:firefox"),
                deliveredKeys = emptySet(),
                failedKeys = if (pushed) setOf("cpu:firefox") else emptySet(),
            )
        }

        assertTrue(pushes > 10, "the alert stopped being retried after $pushes pushes")
        assertTrue(
            600 - lastPush <= MAX_DELIVERY_RETRY_SAMPLES + 1,
            "the last retry was on sample $lastPush, more than the bounded gap ago",
        )
    }

    /**
     * The backoff decides when a key is pushed again, not whether it has been delivered. A caller
     * that pushes on every sample regardless of the backoff reads the unsettled keys, so that the
     * payload finally carrying a deferred alert still names it as new.
     */
    @Test
    fun keepsADeferredKeyUnsettledWhileItsRetryIsPostponed() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))

        repeat(DELIVERY_RETRY_THRESHOLD) {
            state.commit(setOf("cpu:firefox"), emptySet(), failedKeys = setOf("cpu:firefox"))
        }

        assertTrue(state.newlyActive(alerts).isEmpty(), "the retry has to be deferred first")
        assertEquals(listOf("cpu:firefox"), state.unsettled(alerts).map { it.key })
    }

    @Test
    fun countsADeliveredKeyAsSettledForBothViews() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))

        state.commit(setOf("cpu:firefox"), setOf("cpu:firefox"))

        assertTrue(state.unsettled(alerts).isEmpty())
        assertTrue(state.newlyActive(alerts).isEmpty())
    }

    /** One channel answering again is evidence the outage is over, so no key stays deferred. */
    @Test
    fun releasesADeferredKeyOnceAnotherDeliverySucceeds() {
        val state = AlertState()
        val firefox = listOf(alert("cpu:firefox"))
        val firing = setOf("cpu:firefox", "memory:chrome")

        repeat(DELIVERY_RETRY_THRESHOLD) {
            state.newlyActive(firefox)
            state.commit(setOf("cpu:firefox"), emptySet(), failedKeys = setOf("cpu:firefox"))
        }
        assertTrue(state.newlyActive(firefox).isEmpty(), "the key has to be deferred first")

        state.commit(firing, deliveredKeys = setOf("memory:chrome"))

        assertEquals(listOf("cpu:firefox"), state.newlyActive(firefox).map { it.key })
    }

    @Test
    fun givesAKeyAFreshRetryBudgetAfterItCleared() {
        val state = AlertState()
        val alerts = listOf(alert("cpu:firefox"))

        repeat(DELIVERY_RETRY_THRESHOLD) {
            state.commit(setOf("cpu:firefox"), emptySet(), failedKeys = setOf("cpu:firefox"))
        }
        state.commit(emptySet(), emptySet())
        val deferred = state.commit(
            setOf("cpu:firefox"),
            emptySet(),
            failedKeys = setOf("cpu:firefox"),
        )

        assertTrue(deferred.isEmpty())
        assertEquals(listOf("cpu:firefox"), state.newlyActive(alerts).map { it.key })
    }

    @Test
    fun retriesImmediatelyUntilTheThresholdAndThenDoublesUpToTheBound() {
        assertEquals(0L, deliveryRetryDelaySamples(0))
        assertEquals(0L, deliveryRetryDelaySamples(DELIVERY_RETRY_THRESHOLD - 1))
        assertEquals(2L, deliveryRetryDelaySamples(DELIVERY_RETRY_THRESHOLD))
        assertEquals(4L, deliveryRetryDelaySamples(DELIVERY_RETRY_THRESHOLD + 1))
        assertEquals(8L, deliveryRetryDelaySamples(DELIVERY_RETRY_THRESHOLD + 2))
        assertEquals(
            MAX_DELIVERY_RETRY_SAMPLES,
            deliveryRetryDelaySamples(DELIVERY_RETRY_THRESHOLD + 9),
        )
        assertEquals(MAX_DELIVERY_RETRY_SAMPLES, deliveryRetryDelaySamples(Int.MAX_VALUE))
    }

    /**
     * The snapshot is the firing keys and nothing else, which is what makes it small enough to
     * rewrite every sample: a key that cleared is gone from the state, so it is gone from here too
     * without a tombstone of any kind.
     */
    @Test
    fun snapshotHoldsEveryFiringKeyWithTheCounterItsRetryIsMeasuredAgainst() {
        val state = AlertState()
        val firing = setOf("cpu:firefox", "memory:chrome")

        state.commit(firing + "disk:transient", setOf("memory:chrome", "disk:transient"))
        repeat(DELIVERY_RETRY_THRESHOLD) {
            state.commit(firing, emptySet(), failedKeys = setOf("cpu:firefox"))
        }

        val samples = 1L + DELIVERY_RETRY_THRESHOLD
        assertEquals(
            AlertStateSnapshot(
                sampleCounter = samples,
                keys = mapOf(
                    "cpu:firefox" to AlertKeyState(
                        settled = false,
                        failures = DELIVERY_RETRY_THRESHOLD,
                        retryAtSample = samples + deliveryRetryDelaySamples(DELIVERY_RETRY_THRESHOLD),
                    ),
                    "memory:chrome" to AlertKeyState(
                        settled = true,
                        failures = 0,
                        retryAtSample = 0L,
                    ),
                ),
            ),
            state.snapshot(),
            "a key that stopped firing has no place in the state the next run resumes",
        )
    }

    /**
     * Restoring has to reproduce the state, not approximate it: the failure count decides the next
     * backoff step, so a key that had failed twice must earn the deferral on its next failure rather
     * than start counting again.
     */
    @Test
    fun aRestoredStateAnswersLikeTheOneItWasTakenFrom() {
        val alerts = listOf(alert("cpu:firefox"), alert("memory:chrome"))
        val firing = setOf("cpu:firefox", "memory:chrome")
        val original = AlertState()

        original.commit(firing, setOf("memory:chrome"), failedKeys = setOf("cpu:firefox"))
        original.commit(firing, emptySet(), failedKeys = setOf("cpu:firefox"))
        val restored = AlertState(restored = original.snapshot())

        assertEquals(original.activeKeys, restored.activeKeys)
        assertEquals(
            original.unsettled(alerts).map { it.key },
            restored.unsettled(alerts).map { it.key },
        )
        assertEquals(
            original.newlyActive(alerts).map { it.key },
            restored.newlyActive(alerts).map { it.key },
        )
        assertEquals(
            original.commit(firing, emptySet(), failedKeys = setOf("cpu:firefox")),
            restored.commit(firing, emptySet(), failedKeys = setOf("cpu:firefox")),
            "the third failure in a row defers the retry, whether or not a restart intervened",
        )
        assertEquals(original.snapshot(), restored.snapshot())
    }

    @Test
    fun boundsTheStateUnderAStreamOfSingleUseKeys() {
        val state = AlertState()

        repeat(1_000) { index ->
            val alerts = listOf(alert("process:$index:${index}00"))
            state.newlyActive(alerts)
            state.commit(
                alerts.mapTo(mutableSetOf()) { it.key },
                emptySet(),
                failedKeys = alerts.mapTo(mutableSetOf()) { it.key },
            )

            assertEquals(1, state.activeKeys.size)
        }
    }
}
