import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.inMemoryDriver
import dev.yoda.harmon.db.HarmonDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves the premise the whole history design rests on: SQLDelight's native driver reaches a real
 * SQLite from the TEST binary.
 *
 * KT-78062 keeps a module's own cinterop klib out of every test binary, which is why
 * `dev.yoda.harmon.nativebridge` cannot be called from here. `native-driver` carries its cinterop
 * in a third-party klib instead, and those link fine — this test is the standing evidence.
 */
class HistoryDriverSmokeTest {

    @Test
    fun driverRunsRealSqliteInTheTestBinary() {
        val database = HarmonDatabase(inMemoryDriver(HarmonDatabase.Schema))
        val samples = database.samplesQueries

        samples.insert(
            captured_at = "2026-07-29T00:05:00Z",
            elapsed_seconds = 300.5,
            total_process_count = 772,
            battery_percentage = 87,
        )
        samples.insert(
            captured_at = "2026-07-29T00:10:00Z",
            elapsed_seconds = 300.0,
            total_process_count = 780,
            battery_percentage = null,
        )

        val all = samples.selectBetween("2026-07-29T00:00:00Z", "2026-07-29T01:00:00Z").executeAsList()
        assertEquals(2, all.size, "both samples round-trip")
        assertEquals(300.5, all.first().elapsed_seconds, "REAL survives the round trip")
        assertEquals(772L, all.first().total_process_count, "INTEGER survives the round trip")
        assertNull(all.last().battery_percentage, "a nullable column comes back as null, not 0")
    }

    @Test
    fun retentionDeleteRemovesOnlyTheOlderWindow() {
        val database = HarmonDatabase(inMemoryDriver(HarmonDatabase.Schema))
        val samples = database.samplesQueries

        samples.insert("2026-07-20T00:00:00Z", 300.0, 700, null)
        samples.insert("2026-07-29T00:00:00Z", 300.0, 772, null)

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
