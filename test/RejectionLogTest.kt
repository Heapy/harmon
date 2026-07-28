import dev.yoda.harmon.ipc.RejectionLog
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RejectionLogTest {
    /**
     * The socket is reachable by every local account on a stock install, so a rejected peer is
     * attacker-controlled and can arrive as fast as `connect` returns. One line per rejection let
     * any local user fill the root-owned, unrotated collector log and with it the boot volume.
     */
    @Test
    fun writesOneRejectionLineAWindowHoweverManyPeersAreRejected() {
        val rejectionLog = RejectionLog(interval = 60.seconds)
        val started = Instant.fromEpochSeconds(1_000)

        val lines = (0..600).mapNotNull { tenth ->
            rejectionLog.record(peerUserId = 999u, now = started + (tenth * 100).milliseconds)
        }

        assertEquals(2, lines.size)
        assertContains(lines[0], "rejected collector client UID=999")
        assertContains(lines[1], "and 599 more since the last line")
    }

    @Test
    fun logsTheFirstRejectionOfEachWindowImmediately() {
        val rejectionLog = RejectionLog(interval = 60.seconds)
        val started = Instant.fromEpochSeconds(1_000)

        val first = rejectionLog.record(peerUserId = 501u, now = started)
        val withinWindow = rejectionLog.record(peerUserId = 502u, now = started + 59.seconds)
        val nextWindow = rejectionLog.record(peerUserId = 503u, now = started + 60.seconds)

        assertNotNull(first)
        assertNull(withinWindow)
        assertContains(assertNotNull(nextWindow), "UID=503")
        assertContains(nextWindow, "and 1 more since the last line")
    }

    /** A wall clock that jumped backwards must end the window, not silence the log until it. */
    @Test
    fun keepsLoggingAfterTheClockJumpedBackwards() {
        val rejectionLog = RejectionLog(interval = 60.seconds)
        val started = Instant.fromEpochSeconds(1_000)

        rejectionLog.record(peerUserId = 501u, now = started)
        val afterJump = rejectionLog.record(peerUserId = 501u, now = started - 1.hours)

        assertNotNull(afterJump)
    }
}
