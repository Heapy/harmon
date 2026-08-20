package io.heapy.harmon.ipc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Native accept sentinel for a peer rejected by the UID gate. */
const val UNAUTHORIZED_CLIENT = -2

const val CONSECUTIVE_ACCEPT_FAILURE_LIMIT = 16

val REJECTION_LOG_INTERVAL: Duration = 60.seconds

/**
 * Coalesces rejected peers so accounts with socket access cannot fill the root-owned collector
 * log. A backwards clock ends the current window instead of extending silence.
 */
class RejectionLog(private val interval: Duration = REJECTION_LOG_INTERVAL) {
    private var loggedAt: Instant? = null
    private var coalesced = 0

    fun record(peerUserId: UInt, now: Instant): String? {
        val elapsed = loggedAt?.let { now - it }
        if (elapsed != null && elapsed >= Duration.ZERO && elapsed < interval) {
            coalesced += 1
            return null
        }
        val suppressed = coalesced
        loggedAt = now
        coalesced = 0
        return "$now rejected collector client UID=$peerUserId" +
            if (suppressed > 0) " (and $suppressed more since the last line)" else ""
    }
}

enum class AcceptDecision {
    SERVE,

    REJECT,

    RETRY,

    FATAL,
}

fun acceptDecision(result: Int, consecutiveFailures: Int): AcceptDecision = when {
    result >= 0 -> AcceptDecision.SERVE
    result == UNAUTHORIZED_CLIENT -> AcceptDecision.REJECT
    consecutiveFailures >= CONSECUTIVE_ACCEPT_FAILURE_LIMIT -> AcceptDecision.FATAL
    else -> AcceptDecision.RETRY
}

fun consecutiveFailuresAfter(result: Int, consecutiveFailures: Int): Int = when {
    result >= 0 -> 0
    result == UNAUTHORIZED_CLIENT -> consecutiveFailures
    else -> consecutiveFailures + 1
}
