import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.report.ReportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportJsonTest {
    @Test
    fun serializesWebhookPayloadWithKotlinxSerialization() {
        val report = MonitoringReport(
            usage = systemUsage(
                processes = listOf(
                    processUsage(
                        name = "quoted \"name\"",
                        executablePath = "/Applications/Private.app/Contents/MacOS/private",
                    ),
                ),
            ),
            alerts = emptyList(),
            topProcessCount = 5,
        )

        val payload = Json
            .parseToJsonElement(ReportJson.encode(report, newAlertKeys = emptyList()))
            .jsonObject

        assertEquals("harmon.sample", payload.getValue("event").jsonPrimitive.content)
        assertTrue(payload.getValue("applications").jsonObject.containsKey("topCpu"))
        assertTrue(
            payload.getValue("applications").jsonObject.containsKey("topPhysicalWrites"),
        )
        assertTrue(
            payload
                .getValue("applications")
                .jsonObject
                .containsKey("topInternalLogicalWrites"),
        )
        assertTrue(payload.getValue("processes").jsonObject.containsKey("topCpu"))
        assertTrue(payload.getValue("system").jsonObject.containsKey("virtualMemory"))
        assertTrue(payload.getValue("system").jsonObject.containsKey("storage"))
        val virtualMemory = payload
            .getValue("system")
            .jsonObject
            .getValue("virtualMemory")
            .jsonObject
        assertTrue(virtualMemory.containsKey("swapBackedUncompressedBytes"))
        assertFalse(virtualMemory.containsKey("swappedBytes"))
        assertFalse(
            "/Applications/Private.app" in ReportJson.encode(report, newAlertKeys = emptyList()),
        )
    }

    @Test
    fun reportsEveryAlertAlongsideTheKeysThatAreNewOnThisSample() {
        val report = MonitoringReport(
            usage = systemUsage(processes = listOf(processUsage())),
            alerts = List(5) { index -> alert(key = "alert-$index") },
            topProcessCount = 5,
        )

        val payload = Json
            .parseToJsonElement(ReportJson.encode(report, listOf("alert-0")))
            .jsonObject

        assertEquals(
            List(5) { index -> "alert-$index" },
            payload.getValue("alerts").jsonArray.map {
                it.jsonObject.getValue("key").jsonPrimitive.content
            },
        )
        assertEquals(
            listOf("alert-0"),
            payload.getValue("newAlertKeys").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun namesTheFiringKeysThatDidNotFitTheCappedAlertList() {
        val report = MonitoringReport(
            usage = systemUsage(processes = listOf(processUsage())),
            alerts = listOf(alert(key = "memory:kept")),
            topProcessCount = 5,
            suppressedAlertKeys = listOf("memory:demoted"),
        )

        val payload = Json
            .parseToJsonElement(ReportJson.encode(report, newAlertKeys = emptyList()))
            .jsonObject

        assertEquals(
            listOf("memory:demoted"),
            payload.getValue("suppressedAlertKeys").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun selectsEveryRankedSliceByItsOwnMetric() {
        assertEquals(
            EXPECTED_RANKED_SLICES,
            rankedSliceSummary(ReportJson.encode(rankingReport(), newAlertKeys = emptyList())),
        )
    }

    private fun rankedSliceSummary(payload: String): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        return listOf("applications", "processes").joinToString(separator = "\n") { section ->
            root
                .getValue(section)
                .jsonObject
                .entries
                .filter { it.value is JsonArray }
                .joinToString(separator = "\n") { (slice, members) ->
                    "$section.$slice=" + members.jsonArray.joinToString { member ->
                        member.jsonObject.getValue("name").jsonPrimitive.content
                    }
                }
        }
    }

    @Test
    fun reportsWhetherTheKernelEnergyCounterProducedThisSample() {
        val accounted = Json
            .parseToJsonElement(ReportJson.encode(rankingReport(), newAlertKeys = emptyList()))
            .jsonObject

        assertTrue(accounted.getValue("energyAccounted").jsonPrimitive.boolean)

        val heuristic = Json
            .parseToJsonElement(
                ReportJson.encode(zeroEnergyReport(), newAlertKeys = emptyList()),
            )
            .jsonObject

        assertFalse(heuristic.getValue("energyAccounted").jsonPrimitive.boolean)
    }

    @Test
    fun keepsTopBatteryImpactOnTheHeuristicScoreWhileTheCounterIsLive() {
        val payload = Json
            .parseToJsonElement(ReportJson.encode(rankingReport(), newAlertKeys = emptyList()))
            .jsonObject

        assertTrue(payload.getValue("energyAccounted").jsonPrimitive.boolean)
        assertEquals(
            listOf("alpha", "bravo", "echo"),
            sliceNames(payload, "applications", "topBatteryImpact"),
        )
        assertEquals(
            listOf("alpha", "bravo", "echo"),
            sliceNames(payload, "processes", "topBatteryImpact"),
        )
        assertEquals(
            listOf("alpha", "charlie", "echo"),
            sliceNames(payload, "applications", "topEnergy"),
        )
        assertEquals(
            listOf("alpha", "charlie", "echo"),
            sliceNames(payload, "processes", "topEnergy"),
        )
    }

    private fun sliceNames(payload: JsonObject, section: String, slice: String): List<String> =
        payload
            .getValue(section)
            .jsonObject
            .getValue(slice)
            .jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }

    @Test
    fun usesTelegramApiFieldNames() {
        val payload = Json.parseToJsonElement(
            ReportJson.telegramRequest("chat", "line 1\nline 2"),
        ).jsonObject

        assertEquals("chat", payload.getValue("chat_id").jsonPrimitive.content)
        assertEquals("line 1\nline 2", payload.getValue("text").jsonPrimitive.content)
        assertEquals(
            "true",
            payload.getValue("disable_web_page_preview").jsonPrimitive.content,
        )
    }
}

private val EXPECTED_RANKED_SLICES = """
    applications.topCpu=alpha, bravo, echo
    applications.topMemory=charlie, echo, alpha
    applications.topBatteryImpact=alpha, bravo, echo
    applications.topPhysicalWrites=alpha, echo, charlie
    applications.topInternalLogicalWrites=bravo, echo, alpha
    applications.topCompressedOrPagedOut=alpha, charlie, echo
    applications.topEnergy=alpha, charlie, echo
    processes.topCpu=alpha, bravo, echo
    processes.topMemory=charlie, echo, alpha
    processes.topBatteryImpact=alpha, bravo, echo
    processes.topPhysicalWrites=alpha, echo, charlie
    processes.topInternalLogicalWrites=bravo, echo, alpha
    processes.topCompressedOrPagedOut=alpha, charlie, echo
    processes.topEnergy=alpha, charlie, echo
""".trimIndent()
