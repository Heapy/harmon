import dev.yoda.harmon.analysis.AlertAnalyzer
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.ReparentedFrom
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
                    energyWatts = 0.0,
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
    }

    @Test
    fun theBatteryImpactTableLeadsWithWattsWhenTheCounterIsAccounted() {
        val output = ReportFormatter.text(rankingReport())

        assertFalse("(heuristic score)" in output)
        assertEquals(
            listOf(
                "1. alpha (PID 11): 900.0 mW, 4.0 wakeups/s, 8.0 MiB/s I/O",
                "2. charlie (PID 13): 200.0 mW, 1.0 wakeups/s, 1.0 MiB/s I/O",
                "3. echo (PID 15): 50.0 mW, 2.0 wakeups/s, 4.0 MiB/s I/O",
            ),
            tableRows(output, "Likely application battery impact (accounted power)"),
        )
    }

    @Test
    fun theAccountedTableIsShorterThanTopProcessCountWhenOnlySomeApplicationsDraw() {
        val output = ReportFormatter.text(rankingReport().copy(topProcessCount = 5))

        assertEquals(
            listOf(
                "1. alpha (PID 11): 900.0 mW, 4.0 wakeups/s, 8.0 MiB/s I/O",
                "2. charlie (PID 13): 200.0 mW, 1.0 wakeups/s, 1.0 MiB/s I/O",
                "3. echo (PID 15): 50.0 mW, 2.0 wakeups/s, 4.0 MiB/s I/O",
            ),
            tableRows(output, "Likely application battery impact (accounted power)"),
        )
        assertEquals(
            listOf("alpha", "bravo", "echo", "charlie", "delta"),
            rankedNames(output, "Top application CPU"),
        )
    }

    @Test
    fun theBatteryImpactTableKeepsTheHeuristicScoreWhenNothingIsAccounted() {
        val output = ReportFormatter.text(zeroEnergyReport())

        assertFalse("(accounted power)" in output)
        assertEquals(
            listOf(
                "1. alpha (PID 11): score 4.0, 4.0 wakeups/s, 8.0 MiB/s I/O",
                "2. bravo (PID 12): score 4.0, 4.0 wakeups/s, 0 B/s I/O",
                "3. echo (PID 15): score 2.0, 2.0 wakeups/s, 4.0 MiB/s I/O",
            ),
            tableRows(output, "Likely application battery impact (heuristic score)"),
        )
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

    @Test
    fun namesTheOverThresholdKeysTheCappedAlertListLeftOut() {
        val report = alertingReport().copy(
            suppressedAlertKeys = listOf("memory:one", "memory:two"),
        )

        val output = ReportFormatter.text(report)

        assertContains(
            output,
            "- 2 more matching, past maxAlertsPerCategory: memory:one, memory:two",
        )
    }

    @Test
    fun rendersAnOrphanAlertThroughTheSharedAlertsBlock() {
        val usage = systemUsage(
            processes = listOf(
                processUsage(
                    pid = 44559,
                    name = "node",
                    reparentedFrom = ReparentedFrom(pid = 44268, name = "codex"),
                ),
            ),
        )
        val outcome = AlertAnalyzer().analyze(usage, HarmonConfig(), activeKeys = emptySet())

        val output = ReportFormatter.text(
            MonitoringReport(
                usage = usage,
                alerts = outcome.alerts,
                topProcessCount = 5,
            ),
        )

        assertContains(output, "Alerts:")
        assertContains(output, "- warning: node (pid 44559) lost its parent codex (pid 44268)")
    }

    private fun rankedNames(output: String, heading: String): List<String> =
        tableRows(output, heading)
            .map { it.substringAfter(". ").substringBefore(" (") }

    private fun tableRows(output: String, heading: String): List<String> {
        assertContains(output, "$heading:\n")
        return output
            .substringAfter("$heading:\n")
            .substringBefore("\n\n")
            .lines()
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
