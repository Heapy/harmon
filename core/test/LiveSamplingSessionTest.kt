import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.report.WebUiStatus
import dev.yoda.harmon.runtime.LiveSamplingSession
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class LiveSamplingSessionTest {
    @Test
    fun warmsUpThenPublishesAndAggregatesAProcessTree() {
        val first = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(pid = 100, name = "firefox", footprint = GIBIBYTE),
                rawProcess(pid = 101, parentPid = 100, footprint = 9uL * GIBIBYTE),
            ),
        )
        val second = rawSnapshot(
            monotonicNs = 2_000_000_000u,
            processes = listOf(
                rawProcess(
                    pid = 100,
                    name = "firefox",
                    footprint = GIBIBYTE,
                    userTimeNs = 100_000_000u,
                ),
                rawProcess(
                    pid = 101,
                    parentPid = 100,
                    footprint = 9uL * GIBIBYTE,
                    userTimeNs = 200_000_000u,
                ),
            ),
        )
        val session = session(ScriptedCollector(listOf(first, second)))

        val warming = session.capture()
        val ready = session.capture()

        assertEquals(WebUiStatus.WARMING, warming.status)
        assertNull(warming.processTree)
        assertEquals(WebUiStatus.READY, ready.status)
        assertEquals("1", ready.sequence)
        val firefox = assertNotNull(ready.processTree).roots.single()
        assertEquals(GIBIBYTE.toString(), firefox.memorySelfBytes)
        assertEquals((10uL * GIBIBYTE).toString(), firefox.memoryTotalBytes)
        assertEquals(30.0, firefox.cpuTotalPercent)
    }

    @Test
    fun keepsTheLastGoodSampleStaleAndRecoversAgainstTheLastBaseline() {
        val first = rawSnapshot(1_000_000_000u, listOf(rawProcess(userTimeNs = 0u)))
        val second = rawSnapshot(2_000_000_000u, listOf(rawProcess(userTimeNs = 100_000_000u)))
        val third = rawSnapshot(4_000_000_000u, listOf(rawProcess(userTimeNs = 300_000_000u)))
        val collector = ScriptedCollector(
            listOf(first, second, IllegalStateException("collector\nfailed"), third),
        )
        val times = ArrayDeque(
            listOf(
                Instant.parse("2026-08-12T10:00:00Z"),
                Instant.parse("2026-08-12T10:00:01Z"),
                Instant.parse("2026-08-12T10:00:02Z"),
                Instant.parse("2026-08-12T10:00:03Z"),
            ),
        )
        val session = session(collector) { times.removeFirst() }

        session.capture()
        val ready = session.capture()
        val stale = session.capture()
        val recovered = session.capture()

        assertEquals(WebUiStatus.STALE, stale.status)
        assertEquals(ready.processTree, stale.processTree)
        assertEquals(ready.sequence, stale.sequence)
        assertContains(stale.error.orEmpty(), "collector failed")
        assertEquals("2026-08-12T10:00:02Z", stale.staleSince)
        assertEquals(WebUiStatus.READY, recovered.status)
        assertEquals("2", recovered.sequence)
        assertEquals(10.0, recovered.processTree?.roots?.single()?.cpuTotalPercent)
        assertNull(recovered.staleSince)
    }

    private fun session(
        collector: SystemCollector,
        now: () -> Instant = { Instant.parse("2026-08-12T10:00:00Z") },
    ): LiveSamplingSession = LiveSamplingSession(
        collector = collector,
        calculator = UsageCalculator(),
        sampleSeconds = 1,
        now = now,
    )

    private class ScriptedCollector(
        values: List<Any>,
    ) : SystemCollector {
        private val remaining = ArrayDeque(values)

        override fun capture(): dev.yoda.harmon.model.RawSystemSnapshot {
            return when (val next = remaining.removeFirst()) {
                is dev.yoda.harmon.model.RawSystemSnapshot -> next
                is Throwable -> throw next
                else -> error("unsupported fake value")
            }
        }
    }

    private companion object {
        const val GIBIBYTE = 1_073_741_824uL
    }
}
