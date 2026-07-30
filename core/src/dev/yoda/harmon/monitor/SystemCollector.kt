package dev.yoda.harmon.monitor

import dev.yoda.harmon.model.RawSystemSnapshot

const val MIN_PROCESS_CAPACITY = 512
const val PROCESS_CAPACITY_HEADROOM = 256

/**
 * Number of slots to reserve for [count] processes, never more than [capacity].
 *
 * [PROCESS_CAPACITY_HEADROOM] covers processes that start between the kernel's PID count and the
 * listing call, and [MIN_PROCESS_CAPACITY] prevents an ordinary burst from exhausting a tightly
 * sized array. A non-positive count means the kernel refused to answer, so the full capacity is
 * reserved.
 */
fun processCapacityFor(count: Int, capacity: Int): Int {
    if (count <= 0) {
        return capacity
    }
    val requested = maxOf(count.toLong() + PROCESS_CAPACITY_HEADROOM, MIN_PROCESS_CAPACITY.toLong())
    return minOf(requested, capacity.toLong()).toInt()
}

interface SystemCollector {
    fun capture(): RawSystemSnapshot
}

class CollectionException(message: String) : IllegalStateException(message)
