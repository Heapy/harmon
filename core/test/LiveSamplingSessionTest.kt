import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.monitor.CollectionProfile
import dev.yoda.harmon.monitor.SystemCollector
import dev.yoda.harmon.monitor.UsageCalculator
import dev.yoda.harmon.report.WebUiStatus
import dev.yoda.harmon.runtime.LiveSamplingSession
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LiveSamplingSessionTest {
    @Test
    fun failedInitialFullKeepsWarmingAndRetriesANewFullBaseline() {
        val baseline = rawSnapshot(
            1_000_000_000u,
            listOf(rawProcess(compressedOrPagedOut = 32u)),
        )
        val current = rawSnapshot(2_000_000_000u, listOf(rawProcess(userTimeNs = 10u)))
        val collector = ScriptedCollector(
            listOf(IllegalStateException("baseline unavailable"), baseline, current),
        )
        val session = session(
            collector,
            monotonicTimes = listOf(0uL, 1_000_000_000uL, 2_000_000_000uL),
        )

        val failed = session.capture()
        val warming = session.capture()
        val ready = session.capture()

        assertEquals(WebUiStatus.WARMING, failed.status)
        assertContains(failed.error.orEmpty(), "baseline unavailable")
        assertEquals(WebUiStatus.WARMING, warming.status)
        assertNull(warming.error)
        assertEquals(WebUiStatus.READY, ready.status)
        assertEquals(
            listOf(CollectionProfile.FULL, CollectionProfile.FULL, CollectionProfile.LIVE_FAST),
            collector.profiles,
        )
    }

    @Test
    fun startsFullThenPublishesFastMetricsWithCachedAttribution() {
        val first = rawSnapshot(
            monotonicNs = 1_000_000_000u,
            processes = listOf(
                rawProcess(
                    pid = 100,
                    name = "firefox",
                    footprint = GIBIBYTE,
                    compressedOrPagedOut = 128uL * MEBIBYTE,
                ),
                rawProcess(
                    pid = 101,
                    parentPid = 100,
                    footprint = 9uL * GIBIBYTE,
                    compressedOrPagedOut = 256uL * MEBIBYTE,
                ),
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
        val collector = ScriptedCollector(listOf(first, second))
        val session = session(collector, monotonicTimes = listOf(0uL, 1_000_000_000uL))

        val warming = session.capture()
        val ready = session.capture()

        assertEquals(listOf(CollectionProfile.FULL, CollectionProfile.LIVE_FAST), collector.profiles)
        assertEquals(WebUiStatus.WARMING, warming.status)
        assertEquals(WebUiStatus.READY, ready.status)
        assertEquals(CollectionProfile.LIVE_FAST, ready.appliedProfile)
        assertEquals("1970-01-01T00:00:01Z", ready.attributionCapturedAt)
        val firefox = assertNotNull(ready.processTree).roots.single()
        assertEquals(GIBIBYTE.toString(), firefox.metrics.physicalFootprintBytes.self)
        assertEquals((10uL * GIBIBYTE).toString(), firefox.metrics.physicalFootprintBytes.total)
        assertEquals("30.0", firefox.metrics.cpuPercent.total)
        assertEquals(
            (384uL * MEBIBYTE).toString(),
            firefox.metrics.compressedOrPagedOutBytes.total,
        )
        assertFalse(firefox.metrics.compressedOrPagedOutBytes.totalPartial)
        assertContains(ready.reportText, "Compressed/paged-out attribution: captured")
    }

    @Test
    fun newProcessesWithoutCachedAttributionMakeOnlyThoseTotalsPartial() {
        val first = rawSnapshot(
            1_000_000_000u,
            listOf(rawProcess(pid = 100, compressedOrPagedOut = 10u)),
        )
        val second = rawSnapshot(
            2_000_000_000u,
            listOf(
                rawProcess(pid = 100, compressedOrPagedOut = null),
                rawProcess(pid = 101, parentPid = 100, startedAt = 200u),
            ),
        )
        val session = session(
            ScriptedCollector(listOf(first, second)),
            monotonicTimes = listOf(0uL, 1_000_000_000uL),
        )

        session.capture()
        val ready = session.capture()

        val root = assertNotNull(ready.processTree).roots.single()
        assertTrue(root.metrics.compressedOrPagedOutBytes.totalAvailable)
        assertTrue(root.metrics.compressedOrPagedOutBytes.totalPartial)
        assertEquals("10", root.metrics.compressedOrPagedOutBytes.total)
        assertFalse(root.children.single().metrics.compressedOrPagedOutBytes.selfAvailable)
        assertFalse(root.metrics.physicalFootprintBytes.totalPartial)
    }

    @Test
    fun pidReuseDoesNotInheritAttributionFromThePreviousIdentity() {
        val first = rawSnapshot(
            1_000_000_000u,
            listOf(rawProcess(pid = 100, startedAt = 100u, compressedOrPagedOut = 10u)),
        )
        val reused = rawSnapshot(
            2_000_000_000u,
            listOf(rawProcess(pid = 100, startedAt = 200u, compressedOrPagedOut = null)),
        )
        val session = session(
            ScriptedCollector(listOf(first, reused)),
            monotonicTimes = listOf(0uL, 1_000_000_000uL),
        )

        session.capture()
        val ready = session.capture()

        val attribution = assertNotNull(ready.processTree).roots.single()
            .metrics.compressedOrPagedOutBytes
        assertFalse(attribution.selfAvailable)
        assertFalse(attribution.totalAvailable)
        assertTrue(attribution.totalPartial)
    }

    @Test
    fun monotonicDeadlineSelectsFullAtMostOncePerThirtySeconds() {
        val snapshots = listOf(
            rawSnapshot(1_000_000_000u, listOf(rawProcess(compressedOrPagedOut = 1u))),
            rawSnapshot(2_000_000_000u, listOf(rawProcess())),
            rawSnapshot(3_000_000_000u, listOf(rawProcess(compressedOrPagedOut = 3u))),
            rawSnapshot(4_000_000_000u, listOf(rawProcess())),
        )
        val collector = ScriptedCollector(snapshots)
        val session = session(
            collector,
            monotonicTimes = listOf(
                0uL,
                29_999_999_999uL,
                30_000_000_000uL,
                59_999_999_999uL,
            ),
        )

        repeat(4) { session.capture() }

        assertEquals(
            listOf(
                CollectionProfile.FULL,
                CollectionProfile.LIVE_FAST,
                CollectionProfile.FULL,
                CollectionProfile.LIVE_FAST,
            ),
            collector.profiles,
        )
    }

    @Test
    fun aLateFullMovesTheNextDeadlineSoFullStartsStayThirtySecondsApart() {
        val collector = ScriptedCollector(
            listOf(
                rawSnapshot(1_000_000_000u, listOf(rawProcess(compressedOrPagedOut = 1u))),
                rawSnapshot(2_000_000_000u, listOf(rawProcess(compressedOrPagedOut = 2u))),
                rawSnapshot(3_000_000_000u, listOf(rawProcess())),
                rawSnapshot(4_000_000_000u, listOf(rawProcess(compressedOrPagedOut = 4u))),
            ),
        )
        val session = session(
            collector,
            monotonicTimes = listOf(
                0uL,
                30_500_000_000uL,
                60_000_000_000uL,
                60_500_000_000uL,
            ),
        )

        repeat(4) { session.capture() }

        assertEquals(
            listOf(
                CollectionProfile.FULL,
                CollectionProfile.FULL,
                CollectionProfile.LIVE_FAST,
                CollectionProfile.FULL,
            ),
            collector.profiles,
        )
    }

    @Test
    fun failedScheduledFullFallsBackToFastAndKeepsAWarningUntilFullRecovers() {
        val first = rawSnapshot(
            1_000_000_000u,
            listOf(rawProcess(compressedOrPagedOut = 64u)),
        )
        val second = rawSnapshot(2_000_000_000u, listOf(rawProcess(userTimeNs = 10u)))
        val collector = ScriptedCollector(
            listOf(first, IllegalStateException("regions failed"), second),
        )
        val session = session(
            collector,
            monotonicTimes = listOf(0uL, 30_000_000_000uL),
        )

        session.capture()
        val ready = session.capture()

        assertEquals(
            listOf(
                CollectionProfile.FULL,
                CollectionProfile.FULL,
                CollectionProfile.LIVE_FAST,
            ),
            collector.profiles,
        )
        assertEquals(WebUiStatus.READY, ready.status)
        assertContains(ready.attributionWarning.orEmpty(), "regions failed")
        assertContains(ready.reportText, "Attribution warning")
        assertEquals("64", ready.processTree?.roots?.single()?.metrics?.compressedOrPagedOutBytes?.self)
    }

    @Test
    fun successfulLaterFullReplacesTheAttributionTimestampAndClearsItsWarning() {
        val first = rawSnapshot(
            1_000_000_000u,
            listOf(rawProcess(compressedOrPagedOut = 64u)),
        )
        val fast = rawSnapshot(2_000_000_000u, listOf(rawProcess(userTimeNs = 10u)))
        val recoveredFull = rawSnapshot(
            3_000_000_000u,
            listOf(rawProcess(userTimeNs = 20u, compressedOrPagedOut = 128u)),
        )
        val collector = ScriptedCollector(
            listOf(first, IllegalStateException("regions failed"), fast, recoveredFull),
        )
        val session = session(
            collector,
            monotonicTimes = listOf(0uL, 30_000_000_000uL, 60_000_000_000uL),
        )

        session.capture()
        val warned = session.capture()
        val recovered = session.capture()

        assertContains(warned.attributionWarning.orEmpty(), "regions failed")
        assertNull(recovered.attributionWarning)
        assertEquals(CollectionProfile.FULL, recovered.appliedProfile)
        assertEquals("1970-01-01T00:00:03Z", recovered.attributionCapturedAt)
        assertEquals(
            "128",
            recovered.processTree?.roots?.single()?.metrics?.compressedOrPagedOutBytes?.self,
        )
    }

    @Test
    fun fastFailureKeepsTheLastGoodTreeStaleAndRecoversAgainstItsBaseline() {
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
        val session = session(
            collector = collector,
            now = { times.removeFirst() },
            monotonicTimes = listOf(0uL, 1_000_000_000uL, 2_000_000_000uL, 3_000_000_000uL),
        )

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
        assertEquals("10.0", recovered.processTree?.roots?.single()?.metrics?.cpuPercent?.total)
        assertNull(recovered.staleSince)
    }

    private fun session(
        collector: SystemCollector,
        monotonicTimes: List<ULong>,
        now: () -> Instant = { Instant.parse("2026-08-12T10:00:00Z") },
    ): LiveSamplingSession {
        val times = ArrayDeque(monotonicTimes)
        return LiveSamplingSession(
            collector = collector,
            calculator = UsageCalculator(),
            sampleSeconds = 1,
            now = now,
            monotonicNowNanoseconds = { times.removeFirst() },
        )
    }

    private class ScriptedCollector(values: List<Any>) : SystemCollector {
        private val remaining = ArrayDeque(values)
        val profiles = mutableListOf<CollectionProfile>()

        override fun capture(profile: CollectionProfile): RawSystemSnapshot {
            profiles += profile
            return when (val next = remaining.removeFirst()) {
                is RawSystemSnapshot -> next
                is Throwable -> throw next
                else -> error("unsupported fake value")
            }
        }
    }

    private companion object {
        const val MEBIBYTE = 1_048_576uL
        const val GIBIBYTE = 1_073_741_824uL
    }
}
