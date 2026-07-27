import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.report.ReportFormatter
import kotlin.test.Test
import kotlin.test.assertContains
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
}
