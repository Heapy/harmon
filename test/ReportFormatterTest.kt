import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.Severity
import dev.yoda.harmon.report.ApplicationRankings
import dev.yoda.harmon.report.ReportFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

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

    @Test
    fun ranksTiedApplicationsExactlyAsASortedByDescendingSliceWould() {
        val report = rankingReport()
        val expected = report.usage.applications
            .sortedByDescending { it.cpuPercent }
            .take(report.topProcessCount)
            .map { it.name }

        val output = ReportFormatter.text(report)

        assertEquals(expected, rankedNames(output, "Top application CPU"))
        assertEquals(
            listOf("alpha", "bravo"),
            expected.take(2),
            "the fixture has to keep a tie, otherwise the order proves nothing",
        )
    }

    @Test
    fun keepsTheRenderedTextIdenticalToTheGoldenSample() {
        assertEquals(TEXT_GOLDEN, ReportFormatter.text(rankingReport()))
    }

    @Test
    fun ranksASliceOnceAndHandsTheSameListToEveryReader() {
        val rankings = ApplicationRankings(rankingReport())

        assertSame(rankings.topCpu, rankings.topCpu)
        assertSame(rankings.topCompressedOrPagedOut, rankings.topCompressedOrPagedOut)
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

/**
 * The text report of [rankingReport] as it was rendered before the ranked slices became shared
 * between the text and JSON renderers. The refactor is not allowed to move a single character.
 */
private val TEXT_GOLDEN = """
    Harmon sample at 1970-01-01T00:01:40Z
    Window: 2.0s
    Power: battery 75%, 3h 0m remaining
    System CPU: 40.0% (user 30.0%, system 10.0%); load 1.0 / 0.8 / 0.5
    Swap: 0 B used / 4.0 GiB allocated (encrypted)
    VM: 2.0 GiB compressor RAM, 0 B uncompressed memory represented in swap; 0 B/s compress, 0 B/s swap-out
    Internal storage: 0 B/s read, 0 B/s write; 0.0 writes/s, 0.0% write service time; 465.7 GiB filesystem available
    Processes: 5/5 readable, 0 inaccessible
    Applications: 5 groups from 5 readable processes
    Compressed/paged-out attribution: 3 processes measured, 0 failed

    Top application CPU:
    1. alpha (PID 11): 12.0%
    2. bravo (PID 12): 12.0%
    3. echo (PID 15): 7.0%

    Top application memory:
    1. charlie (PID 13): 4.0 GiB
    2. echo (PID 15): 3.0 GiB
    3. alpha (PID 11): 2.0 GiB

    Likely application battery impact:
    1. alpha (PID 11): score 4.0, 4.0 wakeups/s, 8.0 MiB/s I/O, 900.0 mW accounted
    2. bravo (PID 12): score 4.0, 4.0 wakeups/s, 0 B/s I/O
    3. echo (PID 15): score 2.0, 2.0 wakeups/s, 4.0 MiB/s I/O, 50.0 mW accounted

    Top application storage writes:
    1. alpha (PID 11): 8.0 MiB/s physical (all devices), 2.0 MiB/s logical (internal)
    2. bravo (PID 12): 0 B/s physical (all devices), 6.0 MiB/s logical (internal)
    3. echo (PID 15): 4.0 MiB/s physical (all devices), 4.0 MiB/s logical (internal)

    Top application compressed/paged-out memory:
    1. alpha (PID 11): 512.0 MiB proxy (1/1 processes measured)
    2. charlie (PID 13): 128.0 MiB proxy (1/1 processes measured)
    3. echo (PID 15): 64.0 MiB proxy (1/1 processes measured)

    Alerts:
    - warning: message of cpu:alpha
""".trimIndent()
