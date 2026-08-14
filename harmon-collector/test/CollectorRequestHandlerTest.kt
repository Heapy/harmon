import dev.yoda.harmon.ipc.CollectorProtocol
import dev.yoda.harmon.ipc.CollectorProtocolException
import dev.yoda.harmon.ipc.CollectorRequestHandler
import dev.yoda.harmon.model.LoadAverages
import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessorCounters
import dev.yoda.harmon.model.RawSystemSnapshot
import dev.yoda.harmon.model.StorageCounters
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.model.VirtualMemoryCounters
import dev.yoda.harmon.monitor.CollectionProfile
import dev.yoda.harmon.monitor.SystemCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class CollectorRequestHandlerTest {
    @Test
    fun probeAcknowledgesWithoutCapturing() {
        val collector = CollectorIpcRecordingCollector()
        val response = CollectorRequestHandler(collector).respond(CollectorProtocol.encodeProbe())

        CollectorProtocol.decodeAck(response)
        assertEquals(emptyList(), collector.profiles)
    }

    @Test
    fun captureAppliesAndReportsBothRequestedProfiles() {
        val collector = CollectorIpcRecordingCollector()
        val handler = CollectorRequestHandler(collector)

        for (profile in CollectionProfile.entries) {
            val response = handler.respond(CollectorProtocol.encodeCapture(profile))
            val decoded = CollectorProtocol.decodeSnapshot(response, expectedProfile = profile)

            assertEquals(profile, decoded.appliedProfile)
            assertEquals(collector.snapshot, decoded.snapshot)
        }
        assertEquals(CollectionProfile.entries.toList(), collector.profiles)
    }

    @Test
    fun malformedAndUnknownRequestsNeverCapture() {
        val collector = CollectorIpcRecordingCollector()
        val handler = CollectorRequestHandler(collector)
        val invalidRequests = listOf(
            "{\"protocolVersion\":${CollectorProtocol.VERSION},",
            """{"protocolVersion":${CollectorProtocol.VERSION},"kind":"unknown"}""",
            """{"protocolVersion":${CollectorProtocol.VERSION},"kind":"capture","profile":"FUTURE"}""",
        )

        for (request in invalidRequests) {
            assertFailsWith<CollectorProtocolException> {
                handler.respond(request)
            }
        }

        assertEquals(emptyList(), collector.profiles)
    }

    private class CollectorIpcRecordingCollector : SystemCollector {
        val snapshot = collectorIpcEmptyRawSnapshot()
        val profiles = mutableListOf<CollectionProfile>()

        override fun capture(profile: CollectionProfile): RawSystemSnapshot {
            profiles += profile
            return snapshot
        }
    }
}

private fun collectorIpcEmptyRawSnapshot(): RawSystemSnapshot = RawSystemSnapshot(
    capturedAt = Instant.fromEpochSeconds(1),
    monotonicTimeNs = 1_000_000_000u,
    physicalMemoryBytes = 0u,
    swap = SwapUsage(0u, 0u, 0u, encrypted = true),
    power = PowerState(
        batteryAvailable = false,
        onBattery = false,
        charging = false,
        percentage = null,
        minutesRemaining = null,
    ),
    processor = ProcessorCounters(0u, 0u, 0u, 0u),
    loadAverages = LoadAverages(0.0, 0.0, 0.0),
    virtualMemory = VirtualMemoryCounters(
        pageSizeBytes = 16_384u,
        freeBytes = 0u,
        activeBytes = 0u,
        inactiveBytes = 0u,
        wiredBytes = 0u,
        purgeableBytes = 0u,
        compressedBytes = 0u,
        uncompressedBytesInCompressor = 0u,
        swapBackedUncompressedBytes = 0u,
        pageIns = 0u,
        pageOuts = 0u,
        faults = 0u,
        copyOnWriteFaults = 0u,
        compressions = 0u,
        decompressions = 0u,
        swapIns = 0u,
        swapOuts = 0u,
    ),
    storage = StorageCounters(
        available = false,
        deviceCount = 0,
        bytesRead = 0u,
        bytesWritten = 0u,
        readOperations = 0u,
        writeOperations = 0u,
        readTimeNs = 0u,
        writeTimeNs = 0u,
        rootFileSystemTotalBytes = 0u,
        rootFileSystemAvailableBytes = 0u,
    ),
    totalProcessCount = 0,
    inaccessibleProcessCount = 0,
    compressedAttributionProcessCount = 0,
    compressedAttributionFailureCount = 0,
    processes = emptyList(),
    processIssues = emptyList(),
)
