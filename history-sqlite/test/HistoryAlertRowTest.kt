import dev.yoda.harmon.history.insertDeliveryResult
import dev.yoda.harmon.history.insertReportedAlert
import dev.yoda.harmon.history.insertSuppressedAlert
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Round-trips `alert` and `alert_delivery` through a real SQLite.
 *
 * Unlike the wide numeric tables, these columns need no marked builder: `TestFixtures.alert` already
 * derives the title and the message from the key, so a transposed pair shows up as a swapped string
 * rather than as an identical one.
 */
class HistoryAlertRowTest {

    /**
     * Severity is stored as the enum name, so the check that matters is the one that goes back
     * through `valueOf` — and for every constant, not just the one the fixture defaults to.
     */
    @Test
    fun aReportedAlertRoundTripsWithItsSeverity() = withInMemoryDatabase { database ->
        val alerts = database.alertsQueries
        val sampleId = database.insertParentSample()

        for (severity in Severity.entries) {
            alerts.insertReportedAlert(
                sampleId,
                alert(key = "cpu:${severity.name}", severity = severity),
            )
        }

        val stored = alerts.selectAlerts(sampleId).executeAsList()
        assertEquals(
            Severity.entries.associateBy { "cpu:${it.name}" },
            stored.associate { it.key to Severity.valueOf(assertNotNull(it.severity)) },
            "every constant comes back as itself",
        )

        val warning = stored.single { it.key == "cpu:WARNING" }
        assertEquals(sampleId, warning.sample_id)
        assertEquals(1L, warning.reported)
        assertEquals("title of cpu:WARNING", warning.title)
        assertEquals("message of cpu:WARNING", warning.message)
    }

    /**
     * A suppressed key is the one thing the report keeps about an alert the per-category cap pushed
     * out, and it is never pushed again — so the row has to be both stored and still tellable apart
     * from an alert that really was reported.
     */
    @Test
    fun aSuppressedKeyIsStoredWithoutTextAndApartFromAReportedAlert() =
        withInMemoryDatabase { database ->
            val alerts = database.alertsQueries
            val sampleId = database.insertParentSample()

            alerts.insertReportedAlert(sampleId, alert(key = "cpu:alpha"))
            alerts.insertSuppressedAlert(sampleId, "cpu:beta")

            val stored = alerts.selectAlerts(sampleId).executeAsList().associateBy { it.key }
            assertEquals(setOf("cpu:alpha", "cpu:beta"), stored.keys)

            val suppressed = stored.getValue("cpu:beta")
            assertEquals(0L, suppressed.reported, "a key the cap dropped is not a reported alert")
            assertNull(suppressed.severity)
            assertNull(suppressed.title)
            assertNull(suppressed.message)

            assertEquals(1L, stored.getValue("cpu:alpha").reported)
            assertEquals(Severity.WARNING.name, stored.getValue("cpu:alpha").severity)
        }

    /**
     * A failed channel leaves no trace anywhere else: the report names who was pushed, never who
     * received it, and the retry backoff keeps a count but no reason. `detail` is that reason.
     */
    @Test
    fun everyChannelsDeliveryIsStoredIncludingTheFailedOne() = withInMemoryDatabase { database ->
        val alerts = database.alertsQueries
        val sampleId = database.insertParentSample()

        val results = listOf(
            DeliveryResult(channel = "notification-center", successful = true, detail = "posted"),
            DeliveryResult(
                channel = "webhook",
                successful = false,
                detail = "HTTP 500 from example.com",
            ),
        )
        results.forEach { alerts.insertDeliveryResult(sampleId, it) }

        val stored = alerts.selectAlertDeliveries(sampleId).executeAsList().associateBy { it.channel }
        assertEquals(setOf("notification-center", "webhook"), stored.keys)

        assertEquals(1L, stored.getValue("notification-center").successful)
        assertEquals("posted", stored.getValue("notification-center").detail)

        val failed = stored.getValue("webhook")
        assertEquals(sampleId, failed.sample_id)
        assertEquals(0L, failed.successful)
        assertEquals("HTTP 500 from example.com", failed.detail, "the channel's own account survives")
    }
}
