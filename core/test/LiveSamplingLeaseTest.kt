import dev.yoda.harmon.runtime.LiveSamplingLease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSamplingLeaseTest {
    @Test
    fun staysIdleUntilAVisibleLiveRequestRenewsIt() {
        val lease = LiveSamplingLease(sampleSeconds = 1)

        assertNull(lease.activeGeneration(0uL))
        assertEquals(0uL, lease.remainingNanoseconds(0uL))

        val ticket = lease.renew(2_000_000_000u)
        assertEquals(ticket, lease.activeGeneration(6_999_999_999u))
        assertNull(lease.activeGeneration(7_000_000_000u))
    }

    @Test
    fun multipleTabsShareAndExtendOneGeneration() {
        val lease = LiveSamplingLease(sampleSeconds = 2)

        val firstTab = lease.renew(1_000_000_000u)
        val secondTab = lease.renew(4_000_000_000u)

        assertEquals(firstTab, secondTab)
        assertEquals(secondTab, lease.activeGeneration(9_999_999_999u))
        assertNull(lease.activeGeneration(10_000_000_000u))
    }

    @Test
    fun expiryStartsANewBaselineGenerationAndRejectsOldInflightResults() {
        val lease = LiveSamplingLease(sampleSeconds = 1)
        val oldTicket = lease.renew(0uL)

        assertFalse(lease.accepts(oldTicket, 5_000_000_000u))
        val newTicket = lease.renew(6_000_000_000u)

        assertNotEquals(oldTicket, newTicket)
        assertFalse(lease.accepts(oldTicket, 6_000_000_000u))
        assertTrue(lease.accepts(newTicket, 6_000_000_000u))
    }

    @Test
    fun leaseIsThreeSamplesWhenThatExceedsTheFiveSecondFloor() {
        val lease = LiveSamplingLease(sampleSeconds = 10)

        assertEquals(30_000_000_000u, lease.durationNanoseconds)
    }
}
