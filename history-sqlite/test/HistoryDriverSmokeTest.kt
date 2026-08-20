import io.heapy.harmon.db.HarmonDatabase
import io.heapy.harmon.history.insertSample
import io.heapy.harmon.model.PowerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class HistoryDriverSmokeTest {

    @Test
    fun driverRunsRealSqliteInTheTestBinary() = withInMemoryDriver { driver ->
        val samples = HarmonDatabase(driver).samplesQueries

        samples.insertSample(
            systemUsage(emptyList()).copy(capturedAt = Instant.parse("2026-07-29T00:05:00Z")),
        )
        samples.insertSample(
            systemUsage(emptyList()).copy(
                capturedAt = Instant.parse("2026-07-29T00:10:00Z"),
                power = PowerState(
                    batteryAvailable = false,
                    onBattery = false,
                    charging = false,
                    percentage = null,
                    minutesRemaining = null,
                ),
            ),
        )

        val all = samples
            .selectBetween("2026-07-29T00:00:00Z", "2026-07-29T01:00:00Z")
            .executeAsList()
        assertEquals(2, all.size, "both samples round-trip")
        assertEquals(2.0, all.first().elapsed_seconds, "REAL survives the round trip")
        assertEquals(
            1_000_000_000_000L,
            all.first().storage_root_total_bytes,
            "INTEGER survives the round trip",
        )
        assertNull(all.last().battery_percentage, "a nullable column comes back as null, not 0")
    }

    @Test
    fun retentionDeleteRemovesOnlyTheOlderWindow() = withInMemoryDriver { driver ->
        val samples = HarmonDatabase(driver).samplesQueries

        samples.insertSample(
            systemUsage(emptyList()).copy(capturedAt = Instant.parse("2026-07-20T00:00:00Z")),
        )
        samples.insertSample(
            systemUsage(emptyList()).copy(capturedAt = Instant.parse("2026-07-29T00:00:00Z")),
        )

        samples.deleteOlderThan("2026-07-22T00:00:00Z")

        val remaining = samples
            .selectBetween("2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z")
            .executeAsList()
        assertEquals(1, remaining.size, "only the sample outside the window is deleted")
        assertEquals("2026-07-29T00:00:00Z", remaining.single().captured_at)
    }

    @Test
    fun journalModePragmaGoesThroughExecuteQuery() = withInMemoryDriver { driver ->
        val mode = driver.pragma("journal_mode") { it.getString(0) }

        assertEquals("memory", mode?.lowercase(), "the pragma returns a row and must be queried")
    }
}
