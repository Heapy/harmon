import dev.yoda.harmon.monitor.DarwinSystemCollector
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DarwinCollectorLimitsTest {
    /**
     * Every limit is checked at construction rather than at the first capture: the collector runs
     * as a root daemon under launchd, and a bad limit has to stop it before it starts serving,
     * not on some later sample.
     */
    @Test
    fun rejectsALimitThatCannotBeHonoured() {
        listOf<Pair<String, () -> DarwinSystemCollector>>(
            "processCapacity" to { DarwinSystemCollector(processCapacity = 0) },
            "issueCapacity" to { DarwinSystemCollector(issueCapacity = 0) },
            "compressedAttributionProcessLimit" to {
                DarwinSystemCollector(compressedAttributionProcessLimit = -1)
            },
            "attributionRegionBudget" to { DarwinSystemCollector(attributionRegionBudget = -1) },
        ).forEach { (parameter, construct) ->
            val failure = assertFailsWith<IllegalArgumentException>(parameter) { construct() }

            assertTrue(
                failure.message.orEmpty().contains(parameter),
                "the message must name the offending parameter, got: ${failure.message}",
            )
        }
    }

    /**
     * Zero is not a broken limit but a switch: it turns compressed-memory attribution off, which
     * is the cheapest way to run the collector on a machine where the VM-region walk costs more
     * than the numbers are worth.
     */
    @Test
    fun acceptsAZeroRegionBudgetAsAWayToTurnAttributionOff() {
        DarwinSystemCollector(attributionRegionBudget = 0)
        DarwinSystemCollector(compressedAttributionProcessLimit = 0)
    }
}
