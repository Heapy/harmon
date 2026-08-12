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

    /**
     * The fixture reads no energy on purpose: this is about the storage and compressed-memory
     * signals, and a positive `energyWatts` would make the sample accounted and switch the
     * battery-impact table to its other form. The two forms have their own tests below.
     */
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

    /**
     * The heading and the list have to move together: an accounted heading over the score-ranked
     * list is the failure this switch can introduce, and a row assertion alone would miss it. The
     * whole table is compared rather than one line — `topEnergy` puts `charlie` where the score
     * ranking has `bravo`, and orders what is left by watts, so membership, order and the row shape
     * all fail separately here. What this fixture cannot separate is the `> 0` filter, because
     * three of its five processes draw power and the table takes three;
     * [theAccountedTableIsShorterThanTopProcessCountWhenOnlySomeApplicationsDraw] does that.
     */
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

    /**
     * The accounted list drops what draws nothing instead of printing zero rows, so it renders
     * fewer rows than `topProcessCount` while applications with a score to show still exist —
     * `bravo` carries a score of 4.0 and appears in every other table of the same report.
     */
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

    /**
     * A sample where every process reads zero is a sample the kernel counter is dead in, and the
     * table is then exactly what it has always been: the heuristic score, ranked by score, with no
     * watt figure anywhere in it.
     */
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
            "- 2 more matching, past maxAlertsPerCategory: memory:one, memory:two",
        )
    }

    /**
     * The orphan rule carries no rendering of its own: its alert reaches the reader through the
     * same `Alerts:` block every other rule uses, which is what this asserts end to end from the
     * analyzer rather than from a hand-built alert.
     */
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

    /** The rendered rows of one table, heading included in the lookup so a missing one fails. */
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
