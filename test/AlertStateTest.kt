import dev.yoda.harmon.analysis.AlertState
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

        state.commit(alerts, emptySet())

        assertEquals(setOf("cpu:firefox"), state.activeKeys)
        assertTrue(state.notifiedKeys.isEmpty())
    }

    @Test
    fun keepsOnlyDeliveredKeysThatStillFire() {
        val state = AlertState()

        state.commit(
            listOf(stateAlert("cpu:firefox"), stateAlert("memory:chrome")),
            setOf("cpu:firefox", "memory:chrome"),
        )
        state.commit(listOf(stateAlert("cpu:firefox")), emptySet())

        assertEquals(setOf("cpu:firefox"), state.activeKeys)
        assertEquals(setOf("cpu:firefox"), state.notifiedKeys)
    }

    @Test
    fun boundsBothSetsUnderStreamOfSingleUseKeys() {
        val state = AlertState()

        repeat(1_000) { index ->
            val alerts = listOf(stateAlert("process:$index:${index}00"))
            state.newlyActive(alerts)
            state.commit(alerts, alerts.mapTo(mutableSetOf()) { it.key })

            assertEquals(1, state.activeKeys.size)
            assertEquals(1, state.notifiedKeys.size)
        }
    }
}

private fun stateAlert(key: String): Alert = Alert(
    key = key,
    severity = Severity.WARNING,
    title = "title",
    message = "message",
)
