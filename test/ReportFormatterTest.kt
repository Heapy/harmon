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

    /**
     * `alpha` and `bravo` tie on CPU in the fixture, so a selection that reorders equal metrics
     * — anything but a stable sort — shows up here as a swapped pair. The expected order is
     * written out rather than derived from the fixture: computing it with the expression the
     * renderer uses would make the two fail only together.
     */
    @Test
    fun ranksTiedApplicationsInTheOrderTheyWereSampled() {
        val output = ReportFormatter.text(rankingReport())

        assertEquals(
            listOf("alpha", "bravo", "echo"),
            rankedNames(output, "Top application CPU"),
        )
        assertEquals(
            listOf("charlie", "echo", "alpha"),
            rankedNames(output, "Top application memory"),
        )
        assertEquals(
            listOf("alpha", "charlie", "echo"),
            rankedNames(output, "Top application compressed/paged-out memory"),
        )
    }

    /** The quiet sample: no alert to name, so the push carries a state-of-the-machine line. */
    @Test
    fun aNotificationWithoutAlertsCarriesThePowerStateAndTheTopCpuApplication() {
        val payload = ReportFormatter.notification(rankingReport().copy(alerts = emptyList()))

        assertEquals("Harmon: system sample", payload.title)
        assertEquals("battery 75%, 3h 0m remaining", payload.subtitle)
        assertEquals("Swap 0 B; top CPU alpha 12.0%", payload.text)
    }

    @Test
    fun aNotificationWithoutAnyApplicationReportsTheTopCpuAsUnavailable() {
        val report = MonitoringReport(
            usage = systemUsage(processes = emptyList()),
            alerts = emptyList(),
            topProcessCount = 5,
        )

        val payload = ReportFormatter.notification(report)

        assertContains(payload.text, "top CPU n/a")
    }

    /**
     * `notifyEverySample` widens what the push shows, not what counts as new, so the caller has
     * to be able to push every active alert while still naming only the fresh ones.
     */
    @Test
    fun namesTheNewKeysIndependentlyOfTheAlertsThePushCarries() {
        val report = alertingReport()

        val payload = ReportFormatter.notification(
            report = report,
            highlighted = report.alerts,
            newAlertKeys = listOf("alert-3"),
        )
        val json = Json.parseToJsonElement(payload.json).jsonObject

        assertEquals("5 alerts", payload.subtitle)
        assertEquals(
            listOf("alert-3"),
            json.getValue("newAlertKeys").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * The capped alert list is only honest if the keys it dropped are still named somewhere. A
     * reader of the text report — and of the HTML built from it — has to see that the list is
     * not everything that crossed a threshold.
     */
    @Test
    fun namesTheOverThresholdKeysTheCappedAlertListLeftOut() {
        val report = alertingReport().copy(
            suppressedAlertKeys = listOf("memory:one", "memory:two"),
        )

        val output = ReportFormatter.text(report)

        assertContains(
            output,
            "- 2 more over threshold, past maxAlertsPerCategory: memory:one, memory:two",
        )
    }

    private fun rankedNames(output: String, heading: String): List<String> = output
        .substringAfter("$heading:\n")
        .substringBefore("\n\n")
        .lines()
        .map { it.substringAfter(". ").substringBefore(" (") }

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
