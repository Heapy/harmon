import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.inMemoryDriver
import dev.yoda.harmon.db.HarmonDatabase
import dev.yoda.harmon.history.insertSample
import dev.yoda.harmon.model.PowerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Proves the premise the whole history design rests on: SQLDelight's native driver reaches a real
 * SQLite from the TEST binary.
 *
 * KT-78062 keeps a module's own cinterop klib out of every test binary, which is why
 * `dev.yoda.harmon.nativebridge` cannot be called from here. `native-driver` carries its cinterop
 * in a third-party klib instead, and those link fine — this test is the standing evidence.
 *
 * Column-by-column verification of the write path lives in `HistorySampleRowTest`; what is checked
 * here is that the driver, the schema and the generated queries work at all.
 */
class HistoryDriverSmokeTest {

    @Test
    fun driverRunsRealSqliteInTheTestBinary() {
        val database = HarmonDatabase(inMemoryDriver(HarmonDatabase.Schema))
        val samples = database.samplesQueries

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

        val all = samples.selectBetween("2026-07-29T00:00:00Z", "2026-07-29T01:00:00Z").executeAsList()
        assertEquals(2, all.size, "both samples round-trip")
        assertEquals(2.0, all.first().elapsed_seconds, "REAL survives the round trip")
        assertEquals(1_000_000_000_000L, all.first().storage_root_total_bytes, "INTEGER survives the round trip")
        assertNull(all.last().battery_percentage, "a nullable column comes back as null, not 0")
    }

    @Test
    fun retentionDeleteRemovesOnlyTheOlderWindow() {
        val database = HarmonDatabase(inMemoryDriver(HarmonDatabase.Schema))
        val samples = database.samplesQueries

        samples.insertSample(systemUsage(emptyList()).copy(capturedAt = Instant.parse("2026-07-20T00:00:00Z")))
        samples.insertSample(systemUsage(emptyList()).copy(capturedAt = Instant.parse("2026-07-29T00:00:00Z")))

        samples.deleteOlderThan("2026-07-22T00:00:00Z")

        val remaining = samples
            .selectBetween("2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z")
            .executeAsList()
        assertEquals(1, remaining.size, "only the sample outside the window is deleted")
        assertEquals("2026-07-29T00:00:00Z", remaining.single().captured_at)
    }

    /**
     * `PRAGMA journal_mode` returns a row, so sqliter's `execute()` rejects it and it has to go
     * through `executeQuery`. An in-memory database answers MEMORY rather than WAL; what is being
     * pinned here is the calling convention, which a file-backed store depends on.
     */
    @Test
    fun journalModePragmaGoesThroughExecuteQuery() {
        val driver = inMemoryDriver(HarmonDatabase.Schema)

        val mode = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA journal_mode",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 0,
        ).value

        assertEquals("memory", mode?.lowercase(), "the pragma returns a row and must be queried")
    }
}
