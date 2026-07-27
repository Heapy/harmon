import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.report.ReportJson
import kotlinx.serialization.json.Json
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
        assertTrue(payload.getValue("processes").jsonObject.containsKey("topCpu"))
        assertFalse("/Applications/Private.app" in ReportJson.encode(report))
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
