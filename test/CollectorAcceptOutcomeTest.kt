import dev.yoda.harmon.ipc.AcceptDecision
import dev.yoda.harmon.ipc.CONSECUTIVE_ACCEPT_FAILURE_LIMIT
import dev.yoda.harmon.ipc.UNAUTHORIZED_CLIENT
import dev.yoda.harmon.ipc.classifyAccept
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ACCEPT_FAILED = -1

class CollectorAcceptOutcomeTest {
    @Test
    fun servesAnAcceptedDescriptorAndForgetsEarlierFailures() {
        val outcome = classifyAccept(result = 7, consecutiveFailures = 3)

        assertEquals(AcceptDecision.SERVE, outcome.decision)
        assertEquals(0, outcome.consecutiveFailures)
    }

    @Test
    fun rejectsAnUnauthorizedPeerWithoutCountingItAsAFailure() {
        val outcome = classifyAccept(UNAUTHORIZED_CLIENT, consecutiveFailures = 2)

        assertEquals(AcceptDecision.REJECT, outcome.decision)
        assertEquals(2, outcome.consecutiveFailures)
    }

    @Test
    fun retriesAFailedAcceptAndCountsIt() {
        val outcome = classifyAccept(ACCEPT_FAILED, consecutiveFailures = 0)

        assertEquals(AcceptDecision.RETRY, outcome.decision)
        assertEquals(1, outcome.consecutiveFailures)
    }

    @Test
    fun aFloodOfUnauthorizedPeersNeverBecomesFatal() {
        var failures = 0

        repeat(CONSECUTIVE_ACCEPT_FAILURE_LIMIT * 10) {
            val outcome = classifyAccept(UNAUTHORIZED_CLIENT, failures)
            failures = outcome.consecutiveFailures
            assertEquals(AcceptDecision.REJECT, outcome.decision)
        }

        assertEquals(0, failures)
    }

    @Test
    fun givesUpOnlyAfterTheConsecutiveFailureLimit() {
        var failures = 0

        repeat(CONSECUTIVE_ACCEPT_FAILURE_LIMIT - 1) {
            val outcome = classifyAccept(ACCEPT_FAILED, failures)
            failures = outcome.consecutiveFailures
            assertEquals(AcceptDecision.RETRY, outcome.decision)
        }
        val fatal = classifyAccept(ACCEPT_FAILED, failures)

        assertEquals(AcceptDecision.FATAL, fatal.decision)
        assertEquals(CONSECUTIVE_ACCEPT_FAILURE_LIMIT, fatal.consecutiveFailures)
    }

    @Test
    fun oneServedClientBuysTheWholeFailureBudgetBack() {
        var failures = 0
        repeat(CONSECUTIVE_ACCEPT_FAILURE_LIMIT - 1) {
            failures = classifyAccept(ACCEPT_FAILED, failures).consecutiveFailures
        }

        failures = classifyAccept(result = 9, consecutiveFailures = failures).consecutiveFailures
        val afterServing = classifyAccept(ACCEPT_FAILED, failures)

        assertEquals(0, failures)
        assertEquals(AcceptDecision.RETRY, afterServing.decision)
    }
}
