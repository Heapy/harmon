import dev.yoda.harmon.monitor.MIN_PROCESS_CAPACITY
import dev.yoda.harmon.monitor.PROCESS_CAPACITY_HEADROOM
import dev.yoda.harmon.monitor.processCapacityFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CAPACITY = 16_384
private const val TYPICAL_PROCESS_COUNT = 700

class ProcessCapacityTest {
    @Test
    fun reservesHeadroomOnTopOfATypicalProcessCount() {
        assertEquals(
            TYPICAL_PROCESS_COUNT + PROCESS_CAPACITY_HEADROOM,
            processCapacityFor(TYPICAL_PROCESS_COUNT, CAPACITY),
        )
    }

    @Test
    fun reservesTheWholeCapacityWhenTheKernelRefusesToCount() {
        assertEquals(CAPACITY, processCapacityFor(count = 0, capacity = CAPACITY))
        assertEquals(CAPACITY, processCapacityFor(count = -1, capacity = CAPACITY))
    }

    @Test
    fun clampsACountAboveTheCapacityToTheCapacity() {
        assertEquals(CAPACITY, processCapacityFor(CAPACITY, CAPACITY))
        assertEquals(CAPACITY, processCapacityFor(CAPACITY * 4, CAPACITY))
        assertEquals(CAPACITY, processCapacityFor(Int.MAX_VALUE, CAPACITY))
    }

    @Test
    fun neverReservesFewerThanTheMinimumForASmallMachine() {
        assertEquals(MIN_PROCESS_CAPACITY, processCapacityFor(count = 1, capacity = CAPACITY))
        assertTrue(
            MIN_PROCESS_CAPACITY < TYPICAL_PROCESS_COUNT + PROCESS_CAPACITY_HEADROOM,
            "the floor must not swallow a typical machine's process count",
        )
    }

    @Test
    fun letsTheCapacityWinOverTheMinimumWhenTheCallerAsksForLess() {
        assertEquals(64, processCapacityFor(count = 8, capacity = 64))
    }
}
