import dev.yoda.harmon.monitor.DarwinSystemCollector
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DarwinCollectorLimitsTest {
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

    @Test
    fun acceptsAZeroRegionBudgetAsAWayToTurnAttributionOff() {
        DarwinSystemCollector(attributionRegionBudget = 0)
        DarwinSystemCollector(compressedAttributionProcessLimit = 0)
    }
}
