import dev.yoda.harmon.ipc.AcceptDecision
import dev.yoda.harmon.ipc.CONSECUTIVE_ACCEPT_FAILURE_LIMIT
import dev.yoda.harmon.ipc.UNAUTHORIZED_CLIENT
import dev.yoda.harmon.ipc.acceptDecision
import dev.yoda.harmon.ipc.consecutiveFailuresAfter
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ACCEPT_FAILED = -1

class AcceptDecisionTest {
    @Test
    fun servesAnAcceptedDescriptorAndForgetsEarlierFailures() {
        val failures = consecutiveFailuresAfter(result = 7, consecutiveFailures = 3)

        assertEquals(0, failures)
        assertEquals(AcceptDecision.SERVE, acceptDecision(result = 7, consecutiveFailures = 0))
    }

    @Test
    fun rejectsAnUnauthorizedPeerWithoutCountingItAsAFailure() {
        val failures = consecutiveFailuresAfter(UNAUTHORIZED_CLIENT, consecutiveFailures = 2)

        assertEquals(2, failures)
        assertEquals(AcceptDecision.REJECT, acceptDecision(UNAUTHORIZED_CLIENT, failures))
    }

    @Test
    fun retriesAFailedAcceptAndCountsIt() {
        val failures = consecutiveFailuresAfter(ACCEPT_FAILED, consecutiveFailures = 0)

        assertEquals(1, failures)
        assertEquals(AcceptDecision.RETRY, acceptDecision(ACCEPT_FAILED, failures))
    }

    @Test
    fun aFloodOfUnauthorizedPeersNeverBecomesFatal() {
        var failures = 0

        repeat(CONSECUTIVE_ACCEPT_FAILURE_LIMIT * 10) {
            failures = consecutiveFailuresAfter(UNAUTHORIZED_CLIENT, failures)
            assertEquals(AcceptDecision.REJECT, acceptDecision(UNAUTHORIZED_CLIENT, failures))
        }

        assertEquals(0, failures)
    }

    @Test
    fun givesUpOnlyAfterTheConsecutiveFailureLimit() {
        var failures = 0

        repeat(CONSECUTIVE_ACCEPT_FAILURE_LIMIT - 1) {
            failures = consecutiveFailuresAfter(ACCEPT_FAILED, failures)
            assertEquals(AcceptDecision.RETRY, acceptDecision(ACCEPT_FAILED, failures))
        }
        failures = consecutiveFailuresAfter(ACCEPT_FAILED, failures)

        assertEquals(CONSECUTIVE_ACCEPT_FAILURE_LIMIT, failures)
        assertEquals(AcceptDecision.FATAL, acceptDecision(ACCEPT_FAILED, failures))
    }

    @Test
    fun oneServedClientBuysTheWholeFailureBudgetBack() {
        var failures = 0
        repeat(CONSECUTIVE_ACCEPT_FAILURE_LIMIT - 1) {
            failures = consecutiveFailuresAfter(ACCEPT_FAILED, failures)
        }

        failures = consecutiveFailuresAfter(result = 9, consecutiveFailures = failures)
        failures = consecutiveFailuresAfter(ACCEPT_FAILED, failures)

        assertEquals(1, failures)
        assertEquals(AcceptDecision.RETRY, acceptDecision(ACCEPT_FAILED, failures))
    }
}
