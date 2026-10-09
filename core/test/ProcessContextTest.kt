import io.heapy.harmon.ipc.CollectorProtocol
import io.heapy.harmon.model.MonitoringReport
import io.heapy.harmon.model.ProcessContext
import io.heapy.harmon.model.RawSystemSnapshot
import io.heapy.harmon.monitor.CollectionProfile
import io.heapy.harmon.monitor.SystemCollector
import io.heapy.harmon.monitor.UsageCalculator
import io.heapy.harmon.report.ReportFormatter
import io.heapy.harmon.report.WebUiPayloadFactory
import io.heapy.harmon.report.WebUiPayloadJson
import io.heapy.harmon.runtime.LiveSamplingSession
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProcessContextTest {
    private val context = ProcessContext(
        cwd = "/private/context-only-project",
        argv = listOf("node", "context-only-script.js", "", "space value", "Привет", "\"quoted\""),
        argvTruncated = true,
    )

    @Test
    fun contextSurvivesBothIpcProfilesAndCurrentUsageButDoesNotLeakIntoReports() {
        val previous = rawSnapshot(1_000_000_000u, listOf(rawProcess()))
        val current = rawSnapshot(2_000_000_000u, listOf(rawProcess().copy(context = context)))
        for (profile in CollectionProfile.entries) {
            val decoded = CollectorProtocol.decodeSnapshot(
                CollectorProtocol.encodeSnapshot(current, profile), profile,
            ).snapshot
            val usage = UsageCalculator().calculate(previous, decoded)
            assertEquals(context, usage.processes.single().context)
            val live = WebUiPayloadFactory.live(
                usage, 1u, current.capturedAt, null, profile,
            )
            assertEquals(context, live.processTree?.roots?.single()?.context)
            assertContains(WebUiPayloadJson.encode(live), "context-only-script.js")

            val report = MonitoringReport(usage, emptyList(), 5)
            val snapshot = WebUiPayloadFactory.staticSnapshot(report)
            assertNull(snapshot.processTree?.roots?.single()?.context)
            val notification = ReportFormatter.notification(report, emptyList(), emptyList())
            for (encoded in listOf(
                WebUiPayloadJson.encode(snapshot), notification.html, notification.json,
                notification.text, ReportFormatter.text(report), live.reportText,
            )) {
                assertFalse(encoded.contains("context-only-project"))
                assertFalse(encoded.contains("context-only-script.js"))
            }
        }
    }

    @Test
    fun liveFastUsesFreshContextAndDoesNotResurrectUnavailableOrExitedMetadata() {
        val snapshots = ArrayDeque(listOf(
            rawSnapshot(1_000_000_000u, listOf(rawProcess().copy(context = context))),
            rawSnapshot(2_000_000_000u, listOf(rawProcess().copy(context = context.copy(cwd = "/new")))),
            rawSnapshot(3_000_000_000u, listOf(rawProcess())),
            rawSnapshot(4_000_000_000u, emptyList()),
        ))
        val profiles = mutableListOf<CollectionProfile>()
        val collector = object : SystemCollector {
            override fun capture(profile: CollectionProfile): RawSystemSnapshot {
                profiles += profile
                return snapshots.removeFirst()
            }
        }
        var tick = 0uL
        val session = LiveSamplingSession(
            collector, UsageCalculator(), 1,
            monotonicNowNanoseconds = { tick++ * 1_000_000_000uL },
        )
        session.capture()
        assertEquals("/new", session.capture().processTree?.roots?.single()?.context?.cwd)
        assertNull(session.capture().processTree?.roots?.single()?.context)
        assertEquals(emptyList(), session.capture().processTree?.roots)
        assertEquals(listOf(CollectionProfile.FULL) + List(3) { CollectionProfile.LIVE_FAST }, profiles)
    }
}
