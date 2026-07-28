import dev.yoda.harmon.runtime.MAX_SLEEP_SLICE_MILLISECONDS
import dev.yoda.harmon.runtime.sleepSliceMillis
import dev.yoda.harmon.runtime.spendSleepSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val NANOS_PER_MILLISECOND = 1_000_000uL

class SleepSliceTest {
    @Test
    fun capsALongRemainderAtTheSliceLength() {
        val fiveMinutesNs = 300_000uL * NANOS_PER_MILLISECOND

        assertEquals(MAX_SLEEP_SLICE_MILLISECONDS, sleepSliceMillis(fiveMinutesNs))
    }

    @Test
    fun sleepsTheWholeRemainderWhenItFitsInOneSlice() {
        val remainingNs = 1_500uL * NANOS_PER_MILLISECOND

        assertEquals(1_500uL, sleepSliceMillis(remainingNs))
    }

    @Test
    fun neverRoundsASubMillisecondRemainderDownToABusyWait() {
        assertEquals(1uL, sleepSliceMillis(remainingNs = 1uL))
        assertEquals(1uL, sleepSliceMillis(remainingNs = NANOS_PER_MILLISECOND - 1uL))
    }

    @Test
    fun truncatesAPartialMillisecondOfALongerRemainder() {
        val remainingNs = 2uL * NANOS_PER_MILLISECOND + 999_999uL

        assertEquals(2uL, sleepSliceMillis(remainingNs))
    }

    /**
     * With system notifications on, the slice is spent inside the CoreFoundation run loop. The
     * agent has no run loop sources at all before its first delivery, so that probe returns at
     * once and the rest of the slice has to be spent parked — re-entering the run loop instead
     * would busy-poll the whole interval.
     */
    @Test
    fun spendsTheWholeSliceWithSystemNotificationsOn() {
        val started = TimeSource.Monotonic.markNow()

        spendSleepSlice(sliceMs = 20uL, systemNotifications = true)

        val elapsed = started.elapsedNow()
        assertTrue(elapsed >= 15.milliseconds, "the slice was not spent, only $elapsed")
        assertTrue(elapsed < 2.seconds, "the slice overran at $elapsed")
    }

    @Test
    fun spendsTheWholeSliceWithSystemNotificationsOff() {
        val started = TimeSource.Monotonic.markNow()

        spendSleepSlice(sliceMs = 20uL, systemNotifications = false)

        val elapsed = started.elapsedNow()
        assertTrue(elapsed >= 15.milliseconds, "the slice was not spent, only $elapsed")
        assertTrue(elapsed < 2.seconds, "the slice overran at $elapsed")
    }
}
