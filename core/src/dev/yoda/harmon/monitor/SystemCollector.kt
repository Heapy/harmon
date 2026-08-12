package dev.yoda.harmon.monitor

import dev.yoda.harmon.model.RawSystemSnapshot
import kotlinx.serialization.Serializable

const val MIN_PROCESS_CAPACITY = 512
const val PROCESS_CAPACITY_HEADROOM = 256

/**
 * Reserves headroom for processes started between the count and listing calls. A failed count uses
 * the full caller capacity rather than guessing low.
 */
fun processCapacityFor(count: Int, capacity: Int): Int {
    if (count <= 0) {
        return capacity
    }
    val requested = maxOf(count.toLong() + PROCESS_CAPACITY_HEADROOM, MIN_PROCESS_CAPACITY.toLong())
    return minOf(requested, capacity.toLong()).toInt()
}

@Serializable
enum class CollectionProfile {
    FULL,
    LIVE_FAST,
}

interface SystemCollector {
    fun capture(profile: CollectionProfile): RawSystemSnapshot
}

class CollectionException(message: String) : IllegalStateException(message)
