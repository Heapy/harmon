import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.monitor.CollectionProfile
import dev.yoda.harmon.report.WEB_UI_SCHEMA_VERSION
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
    fun staticSnapshotIsReadyAndIncludesTheFullSystemContext() {
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

        assertEquals(WEB_UI_SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(2, payload.schemaVersion)
        assertEquals("0", payload.sequence)
        assertEquals(WebUiStatus.READY, payload.status)
        assertEquals("1970-01-01T00:03:20Z", payload.generatedAt)
        assertEquals(report.usage.capturedAt.toString(), payload.capturedAt)
        assertEquals(report.usage.capturedAt.toString(), payload.attributionCapturedAt)
        assertEquals(100.0, payload.attributionAgeSeconds)
        assertEquals(CollectionProfile.FULL, payload.appliedProfile)
        assertEquals(2.0, payload.sampleIntervalSeconds)
        assertEquals(2.0, payload.elapsedSeconds)
        assertEquals(101, payload.processTree?.roots?.single()?.pid)
        assertEquals("34359738368", payload.system?.physicalMemoryBytes)
        assertEquals("512", payload.system?.swap?.usedBytes)
        assertEquals(40.0, payload.system?.processor?.totalPercent)
        assertEquals(1.0, payload.system?.load?.oneMinute)
        assertEquals("1000000000000", payload.system?.storage?.rootFileSystemTotalBytes)
        assertEquals(75, payload.system?.power?.percentage)
        assertEquals("warning", payload.alerts.single().severity)
        assertEquals(listOf("memory:helper"), payload.suppressedAlertKeys)
        assertEquals("raw report fallback", payload.reportText)
        assertNull(payload.error)
        assertNull(payload.staleSince)
        assertNull(payload.retrySeconds)
    }

    @Test
    fun livePayloadRebuildsReportWithCurrentFastMetricsAndAttributionAge() {
        val process = processUsage(
            pid = 42,
            name = "busy process",
            diskWriteBytesPerSecond = 4_096.0,
            logicalWriteBytesPerSecond = 8_192.0,
            compressedOrPagedOutBytes = 512u,
            energyWatts = 2.5,
            impact = 7.0,
        ).copy(
            faultsPerSecond = 11.0,
            systemCallsPerSecond = 12.0,
            instructionsPerSecond = 13.0,
        )
        val payload = WebUiPayloadFactory.live(
            usage = systemUsage(listOf(process)),
            sequence = 9u,
            attributionCapturedAt = Instant.fromEpochSeconds(95),
            attributionWarning = "previous FULL failed",
            appliedProfile = CollectionProfile.LIVE_FAST,
            generatedAt = Instant.fromEpochSeconds(105),
        )

        assertEquals(CollectionProfile.LIVE_FAST, payload.appliedProfile)
        assertEquals("1970-01-01T00:01:35Z", payload.attributionCapturedAt)
        assertEquals(10.0, payload.attributionAgeSeconds)
        assertEquals("previous FULL failed", payload.attributionWarning)
        assertContains(
            payload.reportText,
            "Last FULL attribution captured at 1970-01-01T00:01:35Z; age 10 seconds",
        )
        assertContains(payload.reportText, "Last FULL attribution: 1 measured, 0 attempts failed")
        assertContains(payload.reportText, "Attribution warning: previous FULL failed")
        assertContains(payload.reportText, "Top application storage writes")
        assertContains(payload.reportText, "Likely application battery impact")
        assertContains(payload.reportText, "wakeups/s")
        val metrics = payload.processTree!!.roots.single().metrics
        assertEquals("4096.0", metrics.diskWriteBytesPerSecond.self)
        assertEquals("11.0", metrics.faultsPerSecond.self)
        assertEquals("12.0", metrics.systemCallsPerSecond.self)
        assertEquals("13.0", metrics.instructionsPerSecond.self)
        assertEquals("2.5", metrics.energyWatts.self)
    }

    @Test
    fun warmingCanCarryThePreviousTreeWhileStartingANewSession() {
        val previous = WebUiPayloadFactory.live(
            usage = systemUsage(listOf(processUsage(pid = 42, name = "last tree"))),
            sequence = 7u,
            attributionCapturedAt = Instant.fromEpochSeconds(100),
            attributionWarning = null,
            appliedProfile = CollectionProfile.FULL,
            generatedAt = Instant.fromEpochSeconds(101),
        )
        val payload = WebUiPayloadFactory.warming(
            sampleIntervalSeconds = Double.NaN,
            retrySeconds = Double.POSITIVE_INFINITY,
            generatedAt = Instant.fromEpochSeconds(110),
            previous = previous,
        )

        assertEquals(WebUiStatus.WARMING, payload.status)
        assertEquals("0", payload.sequence)
        assertEquals(0.0, payload.sampleIntervalSeconds)
        assertEquals(0.0, payload.retrySeconds)
        assertEquals(previous.capturedAt, payload.capturedAt)
        assertEquals(previous.processTree, payload.processTree)
        assertEquals(previous.system, payload.system)
        assertEquals(previous.reportText, payload.reportText)
        assertEquals(10.0, payload.attributionAgeSeconds)
        assertTrue(payload.alerts.isEmpty())
    }

    @Test
    fun warmingWithoutPreviousSampleHasNoMetrics() {
        val payload = WebUiPayloadFactory.warming(
            generatedAt = Instant.fromEpochSeconds(300),
        )

        assertNull(payload.capturedAt)
        assertNull(payload.attributionCapturedAt)
        assertNull(payload.attributionAgeSeconds)
        assertNull(payload.processTree)
        assertNull(payload.system)
        assertContains(payload.reportText, "Waiting")
    }

    @Test
    fun staleRetainsTheLastGoodSampleAndAdvancesAttributionAge() {
        val ready = WebUiPayloadFactory.live(
            usage = systemUsage(
                processes = listOf(processUsage(pid = 42, name = "last good")),
            ),
            sequence = 9u,
            attributionCapturedAt = Instant.fromEpochSeconds(390),
            attributionWarning = null,
            appliedProfile = CollectionProfile.LIVE_FAST,
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
        assertEquals(12.0, stale.attributionAgeSeconds)
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
            attributionCapturedAt = null,
            attributionWarning = null,
            appliedProfile = CollectionProfile.LIVE_FAST,
            sampleIntervalSeconds = Double.POSITIVE_INFINITY,
            generatedAt = Instant.fromEpochSeconds(500),
        )

        val json = Json.parseToJsonElement(WebUiPayloadJson.encode(payload)).jsonObject
        val system = json.getValue("system").jsonObject
        val root = json.getValue("processTree").jsonObject
            .getValue("roots").jsonArray.single().jsonObject
        val footprint = root.getValue("metrics").jsonObject
            .getValue("physicalFootprintBytes").jsonObject

        assertEquals(maximum.toString(), json.getValue("sequence").jsonPrimitive.content)
        assertTrue(json.getValue("sequence").jsonPrimitive.isString)
        assertEquals(maximum.toString(), system.getValue("physicalMemoryBytes").jsonPrimitive.content)
        assertTrue(system.getValue("physicalMemoryBytes").jsonPrimitive.isString)
        assertEquals(
            maximum.toString(),
            system.getValue("swap").jsonObject.getValue("usedBytes").jsonPrimitive.content,
        )
        assertEquals(maximum.toString(), footprint.getValue("self").jsonPrimitive.content)
        assertEquals(
            0.0,
            system.getValue("processor").jsonObject.getValue("totalPercent").jsonPrimitive.double,
        )
        assertEquals(
            0.0,
            system.getValue("load").jsonObject.getValue("oneMinute").jsonPrimitive.double,
        )
        assertEquals(0.0, json.getValue("sampleIntervalSeconds").jsonPrimitive.double)
        assertEquals(0.0, json.getValue("elapsedSeconds").jsonPrimitive.double)
        assertEquals(
            "0.0",
            root.getValue("metrics").jsonObject.getValue("cpuPercent").jsonObject
                .getValue("self").jsonPrimitive.content,
        )
        assertContains(payload.reportText, "attribution: unavailable")
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
