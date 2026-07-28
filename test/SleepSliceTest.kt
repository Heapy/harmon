import dev.yoda.harmon.runtime.sleepSliceMillis
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NANOS_PER_MILLISECOND = 1_000_000uL
private const val MAX_SLICE_MS = 30_000uL

class SleepSliceTest {
    @Test
    fun asksForNoSleepWhenTheDeadlineHasPassed() {
        assertEquals(0uL, sleepSliceMillis(remainingNs = 0uL, maxSliceMs = MAX_SLICE_MS))
    }

    @Test
    fun capsALongRemainderAtTheSliceLength() {
        val fiveMinutesNs = 300_000uL * NANOS_PER_MILLISECOND

        assertEquals(MAX_SLICE_MS, sleepSliceMillis(fiveMinutesNs, MAX_SLICE_MS))
    }

    @Test
    fun sleepsTheWholeRemainderWhenItFitsInOneSlice() {
        val remainingNs = 1_500uL * NANOS_PER_MILLISECOND

        assertEquals(1_500uL, sleepSliceMillis(remainingNs, MAX_SLICE_MS))
    }

    @Test
    fun neverRoundsASubMillisecondRemainderDownToABusyWait() {
        assertEquals(1uL, sleepSliceMillis(remainingNs = 1uL, maxSliceMs = MAX_SLICE_MS))
        assertEquals(
            1uL,
            sleepSliceMillis(remainingNs = NANOS_PER_MILLISECOND - 1uL, maxSliceMs = MAX_SLICE_MS),
        )
    }

    @Test
    fun truncatesAPartialMillisecondOfALongerRemainder() {
        val remainingNs = 2uL * NANOS_PER_MILLISECOND + 999_999uL

        assertEquals(2uL, sleepSliceMillis(remainingNs, MAX_SLICE_MS))
    }
}
