import dev.yoda.harmon.analysis.AlertState
import dev.yoda.harmon.analysis.MAX_DELIVERY_ATTEMPTS
import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertStateTest {
    @Test
    fun treatsKeyMissingFromPreviousSampleAsNew() {
        val state = AlertState()
        val alerts = listOf(stateAlert("cpu:firefox"), stateAlert("memory:chrome"))

        assertEquals(
            listOf("cpu:firefox", "memory:chrome"),
            state.newlyActive(alerts).map { it.key },
        )
    }

    @Test
    fun stopsReportingKeyOnceDeliveryIsCommitted() {
        val state = AlertState()
        val alerts = listOf(stateAlert("cpu:firefox"))

        state.commit(alerts, setOf("cpu:firefox"))

        assertTrue(state.newlyActive(alerts).isEmpty())
    }

    @Test
    fun treatsKeyAsNewAgainAfterItStoppedFiring() {
        val state = AlertState()
        val alerts = listOf(stateAlert("cpu:firefox"))

        state.commit(alerts, setOf("cpu:firefox"))
        state.commit(emptyList(), emptySet())

        assertEquals(listOf("cpu:firefox"), state.newlyActive(alerts).map { it.key })
    }

    @Test
    fun keepsKeyNewWhenDeliveryDidNotSucceed() {
        val state = AlertState()
        val alerts = listOf(stateAlert("cpu:firefox"))

        state.commit(alerts, emptySet())

        assertEquals(listOf("cpu:firefox"), state.newlyActive(alerts).map { it.key })
    }

    @Test
    fun keepsFailedDeliveryKeyActiveForHysteresis() {
        val state = AlertState()
        val alerts = listOf(stateAlert("cpu:firefox"))

        state.commit(alerts, emptySet(), failedKeys = setOf("cpu:firefox"))

        assertEquals(setOf("cpu:firefox"), state.activeKeys)
    }

    @Test
    fun keepsOnlyDeliveredKeysThatStillFire() {
        val state = AlertState()
        val firefox = listOf(stateAlert("cpu:firefox"))
        val both = firefox + stateAlert("memory:chrome")

        state.commit(both, setOf("cpu:firefox", "memory:chrome"))
        state.commit(firefox, emptySet())

        assertEquals(setOf("cpu:firefox"), state.activeKeys)
        assertTrue(state.newlyActive(firefox).isEmpty())
        assertEquals(listOf("memory:chrome"), state.newlyActive(both).map { it.key })
    }

    /**
     * A channel that can never succeed — a typo'd webhook URL, a revoked bot token — would
     * otherwise re-push the same alert on every sample forever, and Notification Center coalesces
     * nothing, so every one of those pushes is another banner.
     */
    @Test
    fun stopsRetryingAKeyThatNeverGetsDelivered() {
        val state = AlertState()
        val alerts = listOf(stateAlert("cpu:firefox"))

        repeat(MAX_DELIVERY_ATTEMPTS - 1) { attempt ->
            val exhausted = state.commit(alerts, emptySet(), failedKeys = setOf("cpu:firefox"))

            assertTrue(exhausted.isEmpty(), "gave up after ${attempt + 1} attempts")
            assertEquals(listOf("cpu:firefox"), state.newlyActive(alerts).map { it.key })
        }
        val exhausted = state.commit(alerts, emptySet(), failedKeys = setOf("cpu:firefox"))

        assertEquals(setOf("cpu:firefox"), exhausted)
        assertTrue(state.newlyActive(alerts).isEmpty())
        assertEquals(setOf("cpu:firefox"), state.activeKeys, "hysteresis has to stay on")
    }

    @Test
    fun givesAKeyAFreshRetryBudgetAfterItCleared() {
        val state = AlertState()
        val alerts = listOf(stateAlert("cpu:firefox"))

        repeat(MAX_DELIVERY_ATTEMPTS) {
            state.commit(alerts, emptySet(), failedKeys = setOf("cpu:firefox"))
        }
        state.commit(emptyList(), emptySet())
        val exhausted = state.commit(alerts, emptySet(), failedKeys = setOf("cpu:firefox"))

        assertTrue(exhausted.isEmpty())
        assertEquals(listOf("cpu:firefox"), state.newlyActive(alerts).map { it.key })
    }

    @Test
    fun boundsTheStateUnderAStreamOfSingleUseKeys() {
        val state = AlertState()

        repeat(1_000) { index ->
            val alerts = listOf(stateAlert("process:$index:${index}00"))
            state.newlyActive(alerts)
            state.commit(alerts, alerts.mapTo(mutableSetOf()) { it.key })

            assertEquals(1, state.activeKeys.size)
            assertTrue(state.newlyActive(alerts).isEmpty())
        }
    }
}

private fun stateAlert(key: String): Alert = Alert(
    key = key,
    severity = Severity.WARNING,
    title = "title",
    message = "message",
)
