package io.heapy.harmon.runtime

const val MIN_LIVE_LEASE_SECONDS = 5L
const val LIVE_LEASE_SAMPLE_MULTIPLIER = 3L
const val LIVE_FULL_CAPTURE_INTERVAL_SECONDS = 30L

/** Pure monotonic lease state; callers provide synchronization when used across threads. */
class LiveSamplingLease(sampleSeconds: Long) {
    val durationNanoseconds: ULong =
        maxOf(MIN_LIVE_LEASE_SECONDS, sampleSeconds.saturatingMultiply(LIVE_LEASE_SAMPLE_MULTIPLIER))
            .secondsToNanoseconds()

    private var deadlineNanoseconds: ULong? = null
    private var generation = 0uL

    init {
        require(sampleSeconds > 0) { "sampleSeconds must be positive" }
    }

    /** Returns one stable generation while any visible Live tab keeps renewing the shared lease. */
    fun renew(nowNanoseconds: ULong): ULong {
        if (!isActive(nowNanoseconds)) {
            generation = generation.saturatingIncrement()
        }
        deadlineNanoseconds = nowNanoseconds.saturatingAdd(durationNanoseconds)
        return generation
    }

    fun activeGeneration(nowNanoseconds: ULong): ULong? =
        generation.takeIf { isActive(nowNanoseconds) }

    fun accepts(ticket: ULong, nowNanoseconds: ULong): Boolean =
        ticket == generation && isActive(nowNanoseconds)

    fun remainingNanoseconds(nowNanoseconds: ULong): ULong {
        val deadline = deadlineNanoseconds ?: return 0u
        return if (deadline > nowNanoseconds) deadline - nowNanoseconds else 0u
    }

    private fun isActive(nowNanoseconds: ULong): Boolean =
        deadlineNanoseconds?.let { nowNanoseconds < it } == true
}

private fun Long.saturatingMultiply(other: Long): Long = when {
    this <= 0 || other <= 0 -> 0
    this > Long.MAX_VALUE / other -> Long.MAX_VALUE
    else -> this * other
}

private fun Long.secondsToNanoseconds(): ULong {
    val seconds = toULong()
    val nanosecondsPerSecond = 1_000_000_000uL
    return if (ULong.MAX_VALUE / nanosecondsPerSecond < seconds) {
        ULong.MAX_VALUE
    } else {
        seconds * nanosecondsPerSecond
    }
}

private fun ULong.saturatingAdd(other: ULong): ULong =
    if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

private fun ULong.saturatingIncrement(): ULong =
    if (this == ULong.MAX_VALUE) this else this + 1u
