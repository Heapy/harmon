import dev.yoda.harmon.history.toSqlTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryTimestampTest {

    /**
     * The bug this exists to prevent: `Instant.toString()` is variable width, and `'.'` sorts below
     * `'Z'`, so half a second past the minute compares *less* than the minute itself. Both the
     * history's `ORDER BY captured_at` and its retention cutoff are string comparisons, so that
     * inversion would shuffle the timeline and hide rows from the delete.
     */
    @Test
    fun lexicographicOrderFollowsTimeEvenAcrossAFraction() {
        val onTheSecond = Instant.parse("2026-07-29T00:05:00Z")
        val halfPast = Instant.parse("2026-07-29T00:05:00.500Z")
        val nextSecond = Instant.parse("2026-07-29T00:05:01Z")

        assertTrue(
            halfPast.toString() < onTheSecond.toString(),
            "the raw form really does sort the later moment first — this is the trap being avoided",
        )

        assertTrue(onTheSecond.toSqlTimestamp() <= halfPast.toSqlTimestamp())
        assertTrue(halfPast.toSqlTimestamp() < nextSecond.toSqlTimestamp())
        assertTrue(onTheSecond.toSqlTimestamp() < nextSecond.toSqlTimestamp())
    }

    @Test
    fun everyInstantFormatsToTheSameTwentyCharacterShape() {
        val shape = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""")
        val moments = listOf(
            Instant.parse("2026-07-29T00:05:00Z"),
            Instant.parse("2026-07-29T00:05:00.5Z"),
            Instant.parse("2026-07-29T00:05:07.123Z"),
            Instant.parse("2026-07-29T23:59:59.999999999Z"),
            Instant.fromEpochSeconds(0),
        )

        for (moment in moments) {
            val stored = moment.toSqlTimestamp()
            assertEquals(20, stored.length, "$stored is not 20 characters wide")
            assertTrue(shape.matches(stored), "$stored is not YYYY-MM-DDTHH:MM:SSZ")
        }
    }

    /**
     * A whole-minute instant is the one case where a java.time-style formatter would drop `:00` and
     * produce a 17-character string, which would then sort below every other second in the minute.
     */
    @Test
    fun truncatesTowardsTheSecondAndKeepsZeroSeconds() {
        assertEquals(
            "2026-07-29T00:05:00Z",
            Instant.parse("2026-07-29T00:05:00.999999999Z").toSqlTimestamp(),
        )
        assertEquals("2026-07-29T00:05:00Z", Instant.parse("2026-07-29T00:05:00Z").toSqlTimestamp())
        assertEquals(
            "2026-07-29T00:05:07Z",
            Instant.parse("2026-07-29T00:05:07.123456789Z").toSqlTimestamp(),
        )
        assertEquals("1970-01-01T00:00:00Z", Instant.fromEpochSeconds(0).toSqlTimestamp())
    }
}
