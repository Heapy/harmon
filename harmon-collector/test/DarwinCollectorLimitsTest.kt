import io.heapy.harmon.monitor.CollectionProfile
import io.heapy.harmon.monitor.DarwinSystemCollector
import io.heapy.harmon.monitor.FULL_ATTRIBUTION_REGION_BUDGET
import io.heapy.harmon.monitor.FULL_COMPRESSED_ATTRIBUTION_PROCESS_LIMIT
import io.heapy.harmon.monitor.attributionLimitsFor
import kotlin.test.assertEquals
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

    @Test
    fun liveFastDisablesEveryRegionWalkWhileFullPreservesTheProductionLimits() {
        val fast = attributionLimitsFor(CollectionProfile.LIVE_FAST)
        val full = attributionLimitsFor(CollectionProfile.FULL)

        assertEquals(0, fast.processLimit)
        assertEquals(0, fast.regionBudget)
        assertEquals(FULL_COMPRESSED_ATTRIBUTION_PROCESS_LIMIT, full.processLimit)
        assertEquals(FULL_ATTRIBUTION_REGION_BUDGET, full.regionBudget)
        assertEquals(256, full.processLimit)
        assertEquals(100_000, full.regionBudget)
    }
}
