package dev.yoda.harmon.ipc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** `hm_unix_accept` result for a peer whose UID is not allowed to talk to the collector. */
const val UNAUTHORIZED_CLIENT = -2

/** Consecutive failed `accept` calls the collector tolerates before it stops serving. */
const val CONSECUTIVE_ACCEPT_FAILURE_LIMIT = 16

/** Shortest gap between two logged rejections; the ones in between are counted, not written. */
val REJECTION_LOG_INTERVAL: Duration = 60.seconds

/**
 * Coalesces the rejection log of the collector into at most one line per [interval].
 *
 * The collector socket is group-owned, and the installer configures the login user's primary
 * group, which on a stock macOS install is `staff` — so every local account can reach the socket
 * and be rejected on its UID. A line per rejection let any of them drive an unbounded stream into
 * the root-owned, unrotated collector log and fill the boot volume. Each line now stands for
 * however many rejections it coalesced, so the event stays visible while its cost stays bounded.
 *
 * A clock that jumped backwards ends the window rather than extending it: the point is a bound on
 * how often a line is written, and a wall-clock adjustment must not turn that into silence.
 */
class RejectionLog(private val interval: Duration = REJECTION_LOG_INTERVAL) {
    private var loggedAt: Instant? = null
    private var coalesced = 0

    /** The line to log for a peer rejected at [now], or null while the current window holds. */
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

/** What the collector loop should do with the outcome of a single `accept` call. */
enum class AcceptDecision {
    /** The descriptor is usable; serve the client. */
    SERVE,

    /** The peer failed the UID check; it was already closed, keep listening. */
    REJECT,

    /** `accept` failed; pause and keep listening. */
    RETRY,

    /** `accept` keeps failing; the listener is broken and the daemon must stop. */
    FATAL,
}

/**
 * Decides what an `hm_unix_accept` [result] means for a collector that has now seen
 * [consecutiveFailures] failed accepts in a row, the current one included.
 */
fun acceptDecision(result: Int, consecutiveFailures: Int): AcceptDecision = when {
    result >= 0 -> AcceptDecision.SERVE
    result == UNAUTHORIZED_CLIENT -> AcceptDecision.REJECT
    consecutiveFailures >= CONSECUTIVE_ACCEPT_FAILURE_LIMIT -> AcceptDecision.FATAL
    else -> AcceptDecision.RETRY
}

/**
 * The consecutive-failure count after an `hm_unix_accept` returning [result]: a served client
 * clears it, a rejected peer leaves it, and only a genuine error raises it.
 */
fun consecutiveFailuresAfter(result: Int, consecutiveFailures: Int): Int = when {
    result >= 0 -> 0
    result == UNAUTHORIZED_CLIENT -> consecutiveFailures
    else -> consecutiveFailures + 1
}
