import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ProcessCollectionIssue
import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.report.ReportFormatter
import kotlin.test.Test
import kotlin.test.assertContains

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
}
