import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.Severity
import dev.yoda.harmon.report.ReportFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReportFormatterTest {
    @Test
    fun diagnosticsExplainProcessesWithoutResourceMetrics() {
        val usage = systemUsage(processes = emptyList()).copy(
            totalProcessCount = 1,
            inaccessibleProcessCount = 1,
            processIssues = listOf(
                ProcessCollectionIssue(
                    pid = 123,
                    parentPid = 1,
                    uid = 0u,
                    name = "protected",
                    executablePath = "/usr/libexec/protected",
                    reason = ProcessCollectionIssueReason.PERMISSION_DENIED,
                    errorCode = 1,
                ),
            ),
        )

        val output = ReportFormatter.diagnostics(
            MonitoringReport(
                usage = usage,
                alerts = emptyList(),
                topProcessCount = 5,
            ),
        )

        assertContains(output, "permission-denied=1")
        assertContains(output, "PID 123 protected")
        assertContains(output, "/usr/libexec/protected")
    }

    @Test
    fun reportShowsSystemStorageAndCompressedMemorySignals() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(
                    name = "writer",
                    diskWriteBytesPerSecond = 64.0 * 1_048_576.0,
                    logicalWriteBytesPerSecond = 96.0 * 1_048_576.0,
                    compressedOrPagedOutBytes = 512uL * 1_048_576uL,
                    energyWatts = 0.012,
                ),
            ),
        )

        val output = ReportFormatter.text(
            MonitoringReport(
                usage = usage,
                alerts = emptyList(),
                topProcessCount = 5,
            ),
        )

        assertContains(output, "Internal storage:")
        assertContains(output, "Top application storage writes")
        assertContains(output, "Top application compressed/paged-out memory")
        assertContains(output, "writer")
        assertContains(output, "12.0 mW accounted")
    }

    @Test
    fun notificationContainsACompleteEscapedHtmlReport() {
        val report = MonitoringReport(
            usage = systemUsage(
                processes = listOf(
                    processUsage(name = "browser <helper> & worker"),
                ),
            ),
            alerts = emptyList(),
            topProcessCount = 5,
        )

        val payload = ReportFormatter.notification(report)

        assertContains(payload.html, "<!doctype html>")
        assertContains(payload.html, "Top application CPU")
        assertContains(payload.html, "browser &lt;helper&gt; &amp; worker")
        assertFalse("browser <helper> & worker" in payload.html)
    }

    @Test
    fun pushCarriesOnlyTheNewAlertWhileTheAttachedReportKeepsAllOfThem() {
        val report = alertingReport()

        val payload = ReportFormatter.notification(
            report = report,
            highlighted = listOf(report.alerts.first()),
        )

        assertEquals("Harmon: system warning", payload.title)
        assertEquals("title of alert-0", payload.subtitle)
        assertEquals("message of alert-0", payload.text)
        (1..4).forEach { index ->
            assertFalse("message of alert-$index" in payload.text)
        }
    }

    @Test
    fun attachedHtmlListsEveryActiveAlertNotOnlyTheHighlightedOne() {
        val report = alertingReport()

        val payload = ReportFormatter.notification(
            report = report,
            highlighted = listOf(report.alerts.first()),
        )

        assertContains(payload.html, "Alerts:")
        (0..4).forEach { index ->
            assertContains(payload.html, "message of alert-$index")
        }
    }

    @Test
    fun attachedJsonKeepsEveryAlertAndNamesTheNewOnes() {
        val report = alertingReport()

        val payload = ReportFormatter.notification(
            report = report,
            highlighted = listOf(report.alerts.first()),
        )
        val json = Json.parseToJsonElement(payload.json).jsonObject

        assertEquals(5, json.getValue("alerts").jsonArray.size)
        assertEquals(
            listOf("alert-0"),
            json.getValue("newAlertKeys").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun highlightsEveryAlertWhenTheCallerDoesNotNarrowThePush() {
        val report = alertingReport()

        val payload = ReportFormatter.notification(report)
        val json = Json.parseToJsonElement(payload.json).jsonObject

        assertEquals("Harmon: critical alert", payload.title)
        assertEquals("5 alerts", payload.subtitle)
        (0..4).forEach { index ->
            assertContains(payload.text, "message of alert-$index")
        }
        assertEquals(
            report.alerts.map { it.key },
            json.getValue("newAlertKeys").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun reusesTheSuppliedReportTextInsteadOfRenderingTheReportTwice() {
        val report = alertingReport()

        val payload = ReportFormatter.notification(
            report = report,
            reportText = "already rendered elsewhere",
        )

        assertContains(payload.html, "already rendered elsewhere")
        assertFalse("Harmon sample at" in payload.html)
    }

    private fun alertingReport(): MonitoringReport = MonitoringReport(
        usage = systemUsage(processes = listOf(processUsage(name = "noisy"))),
        alerts = List(5) { index ->
            alert(
                key = "alert-$index",
                severity = if (index == 4) Severity.CRITICAL else Severity.WARNING,
            )
        },
        topProcessCount = 5,
    )
}
