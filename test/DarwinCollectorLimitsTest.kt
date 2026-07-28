import dev.yoda.harmon.monitor.DarwinSystemCollector
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DarwinCollectorLimitsTest {
    @Test
    fun rejectsANegativeRegionBudget() {
        val failure = assertFailsWith<IllegalArgumentException> {
            DarwinSystemCollector(attributionRegionBudget = -1)
        }
        assertTrue(
            failure.message.orEmpty().contains("attributionRegionBudget"),
            "the message must name the offending parameter, got: ${failure.message}",
        )
    }

    @Test
    fun acceptsAZeroRegionBudgetAsAWayToTurnAttributionOff() {
        DarwinSystemCollector(attributionRegionBudget = 0)
    }
}
