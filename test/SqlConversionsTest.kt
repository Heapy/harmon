import dev.yoda.harmon.history.toSqlLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlConversionsTest {

    /**
     * `ULong.MAX_VALUE.toLong()` is `-1`, and a negative byte count is indistinguishable on the
     * read side from a value that was always negative. Clamping keeps every stored number
     * readable as what it means.
     */
    @Test
    fun clampsAValueAboveTheSignedBoundaryInsteadOfWrappingItNegative() {
        val stored = ULong.MAX_VALUE.toSqlLong()

        assertEquals(Long.MAX_VALUE, stored)
        assertTrue(stored > 0L, "the clamped value must not be negative")
    }

    @Test
    fun clampsEveryValueAboveTheBoundary() {
        val justAbove = Long.MAX_VALUE.toULong() + 1uL

        assertEquals(Long.MAX_VALUE, justAbove.toSqlLong())
        assertEquals(Long.MAX_VALUE, (ULong.MAX_VALUE - 1uL).toSqlLong())
    }

    @Test
    fun convertsValuesBelowTheBoundaryExactly() {
        assertEquals(0L, 0uL.toSqlLong())
        assertEquals(1L, 1uL.toSqlLong())
        assertEquals(17_179_869_184L, 17_179_869_184uL.toSqlLong())
        assertEquals(Long.MAX_VALUE, Long.MAX_VALUE.toULong().toSqlLong())
    }

}
