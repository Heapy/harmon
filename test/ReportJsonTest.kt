import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.report.ReportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

        val payload = Json.parseToJsonElement(ReportJson.encode(report)).jsonObject

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
        assertFalse("/Applications/Private.app" in ReportJson.encode(report))
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
    fun treatsEveryAlertAsNewWhenTheCallerDoesNotSayOtherwise() {
        val report = MonitoringReport(
            usage = systemUsage(processes = listOf(processUsage())),
            alerts = listOf(alert(key = "swap")),
            topProcessCount = 5,
        )

        val payload = Json.parseToJsonElement(ReportJson.encode(report)).jsonObject

        assertEquals(
            listOf("swap"),
            payload.getValue("newAlertKeys").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun ranksTiedApplicationsExactlyAsASortedByDescendingSliceWould() {
        val report = rankingReport()
        val expected = report.usage.applications
            .sortedByDescending { it.cpuPercent }
            .take(report.topProcessCount)
            .map { it.name }

        val payload = Json.parseToJsonElement(ReportJson.encode(report)).jsonObject

        assertEquals(
            expected,
            payload
                .getValue("applications")
                .jsonObject
                .getValue("topCpu")
                .jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertEquals(
            listOf("alpha", "bravo"),
            expected.take(2),
            "the fixture has to keep a tie, otherwise the order proves nothing",
        )
    }

    @Test
    fun keepsEveryRankedSliceIdenticalToTheGoldenSample() {
        assertEquals(SLICES_GOLDEN, rankedSliceSummary(ReportJson.encode(rankingReport())))
    }

    /**
     * Every ranked slice of the payload, as an ordered list of the names it selected: the shape
     * the ranking refactor could change, without a ten-kilobyte golden blob of DTO fields.
     */
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

/**
 * Every ranked slice of [rankingReport] as the payload carried it before the ranked slices became
 * shared between the text and JSON renderers.
 */
private val SLICES_GOLDEN = """
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
