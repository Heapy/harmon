import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.report.WebUiPayloadFactory
import dev.yoda.harmon.report.WebUiPayloadJson
import dev.yoda.harmon.report.WebUiStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WebUiPayloadTest {
    @Test
    fun staticSnapshotIsReadyAndIncludesTheReportContext() {
        val report = MonitoringReport(
            usage = systemUsage(
                processes = listOf(
                    processUsage(pid = 101, name = "firefox", footprint = 1_024u),
                ),
                swapUsed = 512u,
            ),
            alerts = listOf(alert(key = "memory:firefox")),
            topProcessCount = 5,
            suppressedAlertKeys = listOf("memory:helper"),
        )

        val payload = WebUiPayloadFactory.staticSnapshot(
            report = report,
            reportText = "raw report fallback",
            generatedAt = Instant.fromEpochSeconds(200),
        )

        assertEquals(1, payload.schemaVersion)
        assertEquals("0", payload.sequence)
        assertEquals(WebUiStatus.READY, payload.status)
        assertEquals("1970-01-01T00:03:20Z", payload.generatedAt)
        assertEquals(report.usage.capturedAt.toString(), payload.capturedAt)
        assertEquals(2.0, payload.sampleIntervalSeconds)
        assertEquals(2.0, payload.elapsedSeconds)
        assertEquals(101, payload.processTree?.roots?.single()?.pid)
        assertEquals("34359738368", payload.system?.physicalMemoryBytes)
        assertEquals("512", payload.system?.swapUsedBytes)
        assertEquals("warning", payload.alerts.single().severity)
        assertEquals(listOf("memory:helper"), payload.suppressedAlertKeys)
        assertEquals("raw report fallback", payload.reportText)
        assertNull(payload.error)
        assertNull(payload.staleSince)
        assertNull(payload.retrySeconds)
    }

    @Test
    fun warmingHasNoSampleAndNormalizesRetryTiming() {
        val payload = WebUiPayloadFactory.warming(
            sampleIntervalSeconds = Double.NaN,
            retrySeconds = Double.POSITIVE_INFINITY,
            generatedAt = Instant.fromEpochSeconds(300),
        )

        assertEquals(WebUiStatus.WARMING, payload.status)
        assertEquals("0", payload.sequence)
        assertEquals(0.0, payload.sampleIntervalSeconds)
        assertEquals(0.0, payload.retrySeconds)
        assertNull(payload.capturedAt)
        assertNull(payload.elapsedSeconds)
        assertNull(payload.processTree)
        assertNull(payload.system)
        assertTrue(payload.alerts.isEmpty())
        assertContains(payload.reportText, "Waiting")
    }

    @Test
    fun staleRetainsTheLastGoodSampleAndAddsSafeFailureMetadata() {
        val ready = WebUiPayloadFactory.live(
            usage = systemUsage(
                processes = listOf(processUsage(pid = 42, name = "last good")),
            ),
            sequence = 9u,
            sampleIntervalSeconds = 1.0,
            reportText = "last good report",
            generatedAt = Instant.fromEpochSeconds(400),
        )

        val stale = WebUiPayloadFactory.stale(
            lastGood = ready,
            error = " collector\nfailed\u0000 now ",
            staleSince = Instant.fromEpochSeconds(401),
            retrySeconds = -1.0,
            generatedAt = Instant.fromEpochSeconds(402),
        )

        assertEquals(WebUiStatus.STALE, stale.status)
        assertEquals(ready.sequence, stale.sequence)
        assertEquals(ready.capturedAt, stale.capturedAt)
        assertEquals(ready.processTree, stale.processTree)
        assertEquals(ready.system, stale.system)
        assertEquals(ready.reportText, stale.reportText)
        assertEquals("1970-01-01T00:06:41Z", stale.staleSince)
        assertEquals("1970-01-01T00:06:42Z", stale.generatedAt)
        assertEquals(0.0, stale.retrySeconds)
        assertContains(stale.error.orEmpty(), "collector failed")
        assertFalse(stale.error.orEmpty().any(Char::isISOControl))
    }

    @Test
    fun jsonKeepsUnsignedValuesLosslessAndNormalizesNonFiniteNumbers() {
        val maximum = ULong.MAX_VALUE
        val base = systemUsage(
            processes = listOf(
                processUsage(
                    pid = 7,
                    cpuPercent = Double.NaN,
                    footprint = maximum,
                ),
            ),
        )
        val usage = base.copy(
            elapsedSeconds = Double.NEGATIVE_INFINITY,
            physicalMemoryBytes = maximum,
            swap = SwapUsage(
                totalBytes = maximum,
                availableBytes = maximum,
                usedBytes = maximum,
                encrypted = true,
            ),
            processor = base.processor.copy(totalPercent = Double.POSITIVE_INFINITY),
            loadAverages = base.loadAverages.copy(oneMinute = Double.NaN),
        )
        val payload = WebUiPayloadFactory.live(
            usage = usage,
            sequence = maximum,
            sampleIntervalSeconds = Double.POSITIVE_INFINITY,
            reportText = "lossless",
            generatedAt = Instant.fromEpochSeconds(500),
        )

        val json = Json.parseToJsonElement(WebUiPayloadJson.encode(payload)).jsonObject
        val system = json.getValue("system").jsonObject
        val root = json.getValue("processTree").jsonObject
            .getValue("roots").jsonArray.single().jsonObject

        assertEquals(maximum.toString(), json.getValue("sequence").jsonPrimitive.content)
        assertTrue(json.getValue("sequence").jsonPrimitive.isString)
        assertEquals(maximum.toString(), system.getValue("physicalMemoryBytes").jsonPrimitive.content)
        assertTrue(system.getValue("physicalMemoryBytes").jsonPrimitive.isString)
        assertEquals(maximum.toString(), system.getValue("swapUsedBytes").jsonPrimitive.content)
        assertEquals(maximum.toString(), root.getValue("memorySelfBytes").jsonPrimitive.content)
        assertEquals(0.0, system.getValue("cpuTotalPercent").jsonPrimitive.double)
        assertEquals(0.0, system.getValue("loadAverageOneMinute").jsonPrimitive.double)
        assertEquals(0.0, json.getValue("sampleIntervalSeconds").jsonPrimitive.double)
        assertEquals(0.0, json.getValue("elapsedSeconds").jsonPrimitive.double)
        assertEquals(0.0, root.getValue("cpuSelfPercent").jsonPrimitive.double)
    }

    @Test
    fun jsonRoundTripsHostileNamesAlertsAndReportTextWithoutHtmlEscaping() {
        val hostile = "</script><script>alert(\"x\")</script> & \\ \u2028"
        val report = MonitoringReport(
            usage = systemUsage(
                processes = listOf(processUsage(pid = 99, name = hostile)),
            ),
            alerts = listOf(
                alert(
                    key = "hostile",
                    title = hostile,
                    message = hostile,
                ),
            ),
            topProcessCount = 5,
        )
        val payload = WebUiPayloadFactory.staticSnapshot(
            report = report,
            reportText = hostile,
            generatedAt = Instant.fromEpochSeconds(600),
        )

        val encoded = WebUiPayloadJson.encode(payload)
        val json = Json.parseToJsonElement(encoded).jsonObject
        val rootName = json.getValue("processTree").jsonObject
            .getValue("roots").jsonArray.single().jsonObject
            .getValue("name").jsonPrimitive.content

        assertContains(encoded, "</script>")
        assertEquals(hostile, rootName)
        assertEquals(hostile, json.getValue("reportText").jsonPrimitive.content)
        assertEquals(
            hostile,
            json.getValue("alerts").jsonArray.single().jsonObject
                .getValue("message").jsonPrimitive.content,
        )
    }
}
