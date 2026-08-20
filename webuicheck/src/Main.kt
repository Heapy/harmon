package io.heapy.harmon.webuicheck

import io.heapy.harmon.model.ProcessCollectionIssueReason
import io.heapy.harmon.model.ProcessMetricValue
import io.heapy.harmon.model.ProcessTreeNode
import io.heapy.harmon.model.ProcessTreeMetrics
import io.heapy.harmon.model.ProcessTreeSnapshot
import io.heapy.harmon.monitor.CollectionProfile
import io.heapy.harmon.report.ProcessPage
import io.heapy.harmon.report.WebUiAlertSummary
import io.heapy.harmon.report.WebUiLoadSummary
import io.heapy.harmon.report.WebUiPayload
import io.heapy.harmon.report.WebUiPayloadFactory
import io.heapy.harmon.report.WebUiPayloadJson
import io.heapy.harmon.report.WebUiPowerSummary
import io.heapy.harmon.report.WebUiProcessSummary
import io.heapy.harmon.report.WebUiProcessorSummary
import io.heapy.harmon.report.WebUiStatus
import io.heapy.harmon.report.WebUiStorageSummary
import io.heapy.harmon.report.WebUiSystemSummary
import io.heapy.harmon.report.WebUiSwapSummary
import io.heapy.harmon.report.WebUiVirtualMemorySummary
import io.heapy.harmon.util.printError
import io.heapy.harmon.web.LiveUiEndpoint
import io.heapy.harmon.web.LiveHttpSocketKind
import io.heapy.harmon.web.LiveUiServer
import io.heapy.harmon.web.LiveUiState
import io.heapy.harmon.web.configureLiveHttpSocket
import io.heapy.harmon.web.liveUiEndpointResponds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.stdout
import platform.posix.unlink
import platform.posix.usleep
import kotlin.system.exitProcess
import kotlin.time.Instant

private const val TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val watchdogMillis = args.singleOrNull { it.startsWith("--exit-after-ms=") }
        ?.substringAfter('=')
        ?.toLongOrNull()
        ?: DEFAULT_WATCHDOG_MILLIS
    startWatchdog(watchdogMillis)

    val first = sampleOne()
    val state = LiveUiState(WebUiPayloadJson.encode(first))
    val watchCounter = LiveHttpWatchCounter()
    val acceptedSetup = LiveHttpAcceptedSetupControl()
    val server = LiveUiServer(
        state = state,
        token = TOKEN,
        onWatch = watchCounter::increment,
        logError = ::printError,
        configureSocket = { descriptor, kind ->
            configureLiveHttpSocket(descriptor, kind)
                ?: if (kind == LiveHttpSocketKind.CLIENT && acceptedSetup.rejectsClients()) {
                    "injected accepted socket setup failure"
                } else {
                    null
                }
        },
    )
    var port = server.start()
    check(liveUiEndpointResponds(LiveUiEndpoint(port, TOKEN))) {
        "production endpoint probe rejected the live fixture"
    }
    check(!liveUiEndpointResponds(LiveUiEndpoint(port, "b".repeat(64)))) {
        "production endpoint probe accepted the wrong token"
    }
    val snapshotPath = writeSnapshot(first)

    println("PORT=$port")
    println("TOKEN=$TOKEN")
    println("SNAPSHOT=$snapshotPath")
    println("READY")
    fflush(stdout)

    try {
        while (true) {
            val command = readlnOrNull()?.trim() ?: break
            when (command) {
                "sample 1" -> state.update(WebUiPayloadJson.encode(sampleOne()))
                "sample 2" -> state.update(WebUiPayloadJson.encode(sampleTwo()))
                "stale" -> state.update(WebUiPayloadJson.encode(staleSample()))
                "warming" -> state.update(
                    WebUiPayloadJson.encode(
                        WebUiPayloadFactory.warming(
                            generatedAt = STALE_TIME,
                            previous = sampleTwo(),
                        ),
                    ),
                )
                "watch count" -> {
                    println("WATCH=${watchCounter.current()}")
                    fflush(stdout)
                    continue
                }
                "reject clients on" -> acceptedSetup.setRejectClients(true)
                "reject clients off" -> acceptedSetup.setRejectClients(false)
                "" -> Unit
                else -> when {
                    command.startsWith("watch delay ") -> {
                        val delay = command.substringAfterLast(' ').toLongOrNull()
                            ?: exitProcess(3)
                        watchCounter.setDelayMillis(delay)
                    }
                    command.startsWith("restart ") -> {
                        val count = command.substringAfterLast(' ').toIntOrNull()
                            ?.takeIf { it in 1..1_000 }
                            ?: exitProcess(3)
                        repeat(count) {
                            server.stop()
                            port = server.start()
                        }
                        println("PORT=$port")
                        fflush(stdout)
                    }
                    else -> exitProcess(3)
                }
            }
            if (command.isNotEmpty()) {
                println("ACK=$command")
                fflush(stdout)
            }
        }
    } finally {
        server.stop()
        unlink(snapshotPath)
    }
}

private class LiveHttpWatchCounter {
    private val lock = NSLock()
    private var value = 0
    private var delayMillis = 0L

    fun increment() {
        val delay: Long
        lock.lock()
        try {
            value += 1
            delay = delayMillis
        } finally {
            lock.unlock()
        }
        if (delay > 0L) usleep((delay * 1_000L).toUInt())
    }

    fun current(): Int {
        lock.lock()
        return try {
            value
        } finally {
            lock.unlock()
        }
    }

    fun setDelayMillis(next: Long) {
        require(next in 0L..10_000L) { "watch delay must be between 0 and 10000 ms" }
        lock.lock()
        try {
            delayMillis = next
        } finally {
            lock.unlock()
        }
    }
}

private class LiveHttpAcceptedSetupControl {
    private val lock = NSLock()
    private var rejectClients = false

    fun rejectsClients(): Boolean {
        lock.lock()
        return try {
            rejectClients
        } finally {
            lock.unlock()
        }
    }

    fun setRejectClients(next: Boolean) {
        lock.lock()
        try {
            rejectClients = next
        } finally {
            lock.unlock()
        }
    }
}

private fun sampleOne(): WebUiPayload = payload(
    sequence = "1",
    capturedAt = SAMPLE_ONE_TIME.toString(),
    roots = listOf(firefoxSampleOne(), codeNode(), unavailableRoot()),
)

private fun sampleTwo(): WebUiPayload = payload(
    sequence = "2",
    capturedAt = SAMPLE_TWO_TIME.toString(),
    roots = listOf(firefoxSampleTwo(), codeNode(), unavailableRoot()),
    cpuTotal = 37.5,
)

private fun staleSample(): WebUiPayload = WebUiPayloadFactory.stale(
    lastGood = sampleTwo(),
    error = "fake collector unavailable",
    staleSince = STALE_TIME,
    retrySeconds = 1.0,
    generatedAt = STALE_TIME,
)

private fun payload(
    sequence: String,
    capturedAt: String,
    roots: List<ProcessTreeNode>,
    cpuTotal: Double = 31.5,
): WebUiPayload = WebUiPayload(
    sequence = sequence,
    status = WebUiStatus.READY,
    generatedAt = capturedAt,
    capturedAt = capturedAt,
    attributionCapturedAt = SAMPLE_ONE_TIME.toString(),
    attributionAgeSeconds = if (sequence == "1") 0.0 else 1.0,
    attributionWarning = null,
    appliedProfile = if (sequence == "1") CollectionProfile.FULL else CollectionProfile.LIVE_FAST,
    sampleIntervalSeconds = 1.0,
    elapsedSeconds = 1.0,
    processTree = ProcessTreeSnapshot(
        capturedAt = capturedAt,
        totalProcessCount = 9,
        displayedProcessCount = roots.sumOf(ProcessTreeNode::size),
        measuredProcessCount = roots.sumOf(ProcessTreeNode::measuredSize),
        inaccessibleProcessCount = 1,
        roots = roots,
    ),
    system = WebUiSystemSummary(
        physicalMemoryBytes = gibibytes(32),
        processor = WebUiProcessorSummary(
            totalPercent = cpuTotal,
            userPercent = cpuTotal * 0.75,
            systemPercent = cpuTotal * 0.25,
            nicePercent = 0.0,
            idlePercent = 100.0 - cpuTotal,
        ),
        load = WebUiLoadSummary(oneMinute = 2.4, fiveMinutes = 1.8, fifteenMinutes = 1.2),
        swap = WebUiSwapSummary(
            usedBytes = gibibytes(2),
            totalBytes = gibibytes(8),
            availableBytes = gibibytes(6),
            encrypted = true,
        ),
        power = WebUiPowerSummary(
            batteryAvailable = true,
            onBattery = true,
            charging = false,
            percentage = 72,
            minutesRemaining = 180,
        ),
        virtualMemory = WebUiVirtualMemorySummary(
            freeBytes = gibibytes(4),
            activeBytes = gibibytes(10),
            inactiveBytes = gibibytes(8),
            wiredBytes = gibibytes(4),
            purgeableBytes = gibibytes(1),
            compressedBytes = gibibytes(2),
            uncompressedBytesInCompressor = gibibytes(3),
            swapBackedUncompressedBytes = gibibytes(2),
            pageInBytesPerSecond = 1_024.0,
            pageOutBytesPerSecond = 512.0,
            faultRate = 120.0,
            copyOnWriteFaultRate = 12.0,
            compressionBytesPerSecond = 2_048.0,
            decompressionBytesPerSecond = 1_024.0,
            swapInBytesPerSecond = 256.0,
            swapOutBytesPerSecond = 128.0,
        ),
        storage = WebUiStorageSummary(
            available = true,
            deviceCount = 1,
            readBytesPerSecond = 4_096.0,
            writeBytesPerSecond = 8_192.0,
            readOperationsPerSecond = 4.0,
            writeOperationsPerSecond = 8.0,
            readServiceTimePercent = 1.0,
            writeServiceTimePercent = 2.0,
            rootFileSystemTotalBytes = "1000000000000",
            rootFileSystemAvailableBytes = "500000000000",
        ),
        processes = WebUiProcessSummary(
            total = 9,
            inaccessible = 1,
            compressedAttributionAvailable = 8,
            compressedAttributionFailures = 1,
        ),
        energyAccounted = true,
    ),
    alerts = FIXTURE_ALERTS,
    suppressedAlertKeys = listOf("cpu:com.example.capped"),
    reportText = "Fake Harmon report for browser tests.",
    error = null,
    staleSince = null,
    retrySeconds = null,
)

/** Root, nested and doubly nested pids, so a test sees badges and the collapsed-parent counter. */
private val FIXTURE_ALERTS = listOf(
    WebUiAlertSummary(
        key = "memory:firefox",
        category = "memory",
        severity = "warning",
        title = "High application memory",
        message = "Firefox uses 10.0 GiB memory",
        pids = listOf(100, 101, 102, 103),
    ),
    WebUiAlertSummary(
        key = "cpu:code",
        category = "cpu",
        severity = "critical",
        title = "High application CPU",
        message = "Code Helper uses 80.0% CPU",
        pids = listOf(200),
    ),
    WebUiAlertSummary(
        key = "disk-write:firefox-gpu",
        category = "disk",
        severity = "warning",
        title = "High application storage writes",
        message = "Firefox GPU writes 10.0 MiB/s to physical storage",
        pids = listOf(102),
    ),
    WebUiAlertSummary(
        key = "power:firefox-extension",
        category = "energy",
        severity = "warning",
        title = "Likely battery drain",
        message = "Firefox WebExtension draws 4.20 W",
        pids = listOf(103),
    ),
    WebUiAlertSummary(
        key = "orphan:process:301:301000",
        category = "orphan",
        severity = "warning",
        title = "Orphaned process",
        message = "Readable Worker (pid 301) lost its parent Protected Supervisor (pid 300)",
        pids = listOf(301),
    ),
    WebUiAlertSummary(
        key = "swap",
        category = "swap",
        severity = "warning",
        title = "High swap usage",
        message = "2.0 GiB of swap is in use",
        pids = emptyList(),
    ),
)

private fun firefoxSampleOne(): ProcessTreeNode {
    val extension = measuredNode(
        pid = 103,
        parentPid = 101,
        name = "Firefox WebExtension",
        cpuSelf = 10.0,
        memorySelfGiB = 5,
    )
    val content = measuredNode(
        pid = 101,
        parentPid = 100,
        name = "Firefox Content",
        cpuSelf = 15.0,
        cpuTotal = 25.0,
        memorySelfGiB = 1,
        memoryTotalGiB = 6,
        children = listOf(extension),
    )
    val gpu = measuredNode(
        pid = 102,
        parentPid = 100,
        name = "Firefox GPU",
        cpuSelf = 20.0,
        memorySelfGiB = 3,
    )
    return measuredNode(
        pid = 100,
        parentPid = 1,
        name = "Firefox",
        cpuSelf = 5.0,
        cpuTotal = 50.0,
        memorySelfGiB = 1,
        memoryTotalGiB = 10,
        children = listOf(content, gpu),
    )
}

private fun firefoxSampleTwo(): ProcessTreeNode {
    val first = firefoxSampleOne()
    val utility = measuredNode(
        pid = 104,
        parentPid = 100,
        name = "Firefox Utility",
        cpuSelf = 8.0,
        memorySelfGiB = 2,
    )
    return first.copy(
        metrics = first.metrics.copy(
            cpuPercent = decimalMetric(9.0, 62.0),
            userCpuPercent = decimalMetric(7.0, 48.0),
            systemCpuPercent = decimalMetric(2.0, 14.0),
            physicalFootprintBytes = integerMetric(gibibytes(2), gibibytes(13)),
            residentBytes = integerMetric(gibibytes(2), gibibytes(13)),
        ),
        children = first.children + utility,
    )
}

private fun codeNode(): ProcessTreeNode = measuredNode(
    pid = 200,
    parentPid = 1,
    name = "Code Helper",
    cpuSelf = 80.0,
    memorySelfGiB = 8,
)

private fun unavailableRoot(): ProcessTreeNode {
    val child = measuredNode(
        pid = 301,
        parentPid = 300,
        name = "Readable Worker",
        cpuSelf = 2.0,
        memorySelfGiB = 1,
    )
    return ProcessTreeNode(
        key = "process:300:unavailable",
        pid = 300,
        startedAt = null,
        parentPid = 1,
        uid = 0u,
        name = "Protected Supervisor",
        executablePath = "/usr/libexec/protected",
        measured = false,
        issueReason = ProcessCollectionIssueReason.PERMISSION_DENIED,
        errorCode = 1,
        metrics = unavailableMetrics(
            cpuTotal = 2.0,
            memoryTotal = gibibytes(1),
        ),
        unavailableProcessCount = 1,
        children = listOf(child),
    )
}

private fun measuredNode(
    pid: Int,
    parentPid: Int,
    name: String,
    cpuSelf: Double,
    cpuTotal: Double = cpuSelf,
    memorySelfGiB: Int,
    memoryTotalGiB: Int = memorySelfGiB,
    children: List<ProcessTreeNode> = emptyList(),
): ProcessTreeNode = ProcessTreeNode(
    key = "process:$pid:${pid * 1_000}",
    pid = pid,
    startedAt = (pid * 1_000L).toString(),
    parentPid = parentPid,
    uid = 501u,
    name = name,
    executablePath = "/Applications/$name.app/Contents/MacOS/$name",
    measured = true,
    issueReason = null,
    errorCode = null,
    metrics = measuredMetrics(
        cpuSelf = cpuSelf,
        cpuTotal = cpuTotal,
        memorySelf = gibibytes(memorySelfGiB),
        memoryTotal = gibibytes(memoryTotalGiB),
        partial = children.any { it.unavailableProcessCount > 0 },
    ),
    unavailableProcessCount = children.sumOf(ProcessTreeNode::unavailableProcessCount),
    children = children,
)

private fun measuredMetrics(
    cpuSelf: Double,
    cpuTotal: Double,
    memorySelf: String,
    memoryTotal: String,
    partial: Boolean,
): ProcessTreeMetrics = ProcessTreeMetrics(
    cpuPercent = decimalMetric(cpuSelf, cpuTotal, partial),
    userCpuPercent = decimalMetric(cpuSelf * 0.75, cpuTotal * 0.75, partial),
    systemCpuPercent = decimalMetric(cpuSelf * 0.25, cpuTotal * 0.25, partial),
    physicalFootprintBytes = integerMetric(memorySelf, memoryTotal, partial),
    residentBytes = integerMetric(memorySelf, memoryTotal, partial),
    wiredBytes = integerMetric("0", "0", partial),
    compressedOrPagedOutBytes = integerMetric("0", "0", partial),
    virtualMemoryRegionCount = integerMetric("12", "12", partial),
    lifetimeMaxPhysicalFootprintBytes = selfOnlyMetric(memorySelf),
    diskReadBytesPerSecond = decimalMetric(cpuSelf * 1_024.0, cpuTotal * 1_024.0, partial),
    diskWriteBytesPerSecond = decimalMetric(cpuSelf * 512.0, cpuTotal * 512.0, partial),
    logicalWriteBytesPerSecond = decimalMetric(cpuSelf * 256.0, cpuTotal * 256.0, partial),
    pageInsPerSecond = decimalMetric(cpuSelf, cpuTotal, partial),
    wakeupsPerSecond = decimalMetric(cpuSelf, cpuTotal, partial),
    faultsPerSecond = decimalMetric(cpuSelf * 2.0, cpuTotal * 2.0, partial),
    copyOnWriteFaultsPerSecond = decimalMetric(cpuSelf / 2.0, cpuTotal / 2.0, partial),
    systemCallsPerSecond = decimalMetric(cpuSelf * 3.0, cpuTotal * 3.0, partial),
    contextSwitchesPerSecond = decimalMetric(cpuSelf * 4.0, cpuTotal * 4.0, partial),
    threadCount = integerMetric("1", childrenCount(cpuSelf, cpuTotal), partial),
    runningThreadCount = integerMetric("1", childrenCount(cpuSelf, cpuTotal), partial),
    instructionsPerSecond = decimalMetric(cpuSelf * 100.0, cpuTotal * 100.0, partial),
    cyclesPerSecond = decimalMetric(cpuSelf * 200.0, cpuTotal * 200.0, partial),
    energyWatts = decimalMetric(cpuSelf / 10.0, cpuTotal / 10.0, partial),
    billedEnergyPerSecond = decimalMetric(cpuSelf / 20.0, cpuTotal / 20.0, partial),
    batteryImpactScore = decimalMetric(cpuSelf, cpuTotal, partial),
)

private fun unavailableMetrics(cpuTotal: Double, memoryTotal: String): ProcessTreeMetrics {
    val missing = ProcessMetricValue(
        self = null,
        total = null,
        selfAvailable = false,
        totalAvailable = false,
        totalPartial = true,
    )
    return ProcessTreeMetrics(
        cpuPercent = unavailableTotal(cpuTotal.toString()),
        userCpuPercent = unavailableTotal((cpuTotal * 0.75).toString()),
        systemCpuPercent = unavailableTotal((cpuTotal * 0.25).toString()),
        physicalFootprintBytes = unavailableTotal(memoryTotal),
        residentBytes = unavailableTotal(memoryTotal),
        wiredBytes = missing,
        compressedOrPagedOutBytes = missing,
        virtualMemoryRegionCount = missing,
        lifetimeMaxPhysicalFootprintBytes = ProcessMetricValue(null, null, false, false, false),
        diskReadBytesPerSecond = missing,
        diskWriteBytesPerSecond = missing,
        logicalWriteBytesPerSecond = missing,
        pageInsPerSecond = missing,
        wakeupsPerSecond = missing,
        faultsPerSecond = missing,
        copyOnWriteFaultsPerSecond = missing,
        systemCallsPerSecond = missing,
        contextSwitchesPerSecond = missing,
        threadCount = missing,
        runningThreadCount = missing,
        instructionsPerSecond = missing,
        cyclesPerSecond = missing,
        energyWatts = missing,
        billedEnergyPerSecond = missing,
        batteryImpactScore = missing,
    )
}

private fun decimalMetric(self: Double, total: Double, partial: Boolean = false): ProcessMetricValue =
    ProcessMetricValue(self.toString(), total.toString(), true, true, partial)

private fun integerMetric(self: String, total: String, partial: Boolean = false): ProcessMetricValue =
    ProcessMetricValue(self, total, true, true, partial)

private fun selfOnlyMetric(self: String): ProcessMetricValue =
    ProcessMetricValue(self, null, true, false, false)

private fun unavailableTotal(total: String): ProcessMetricValue =
    ProcessMetricValue(null, total, false, true, true)

private fun childrenCount(self: Double, total: Double): String =
    maxOf(1, (total / maxOf(self, 1.0)).toInt()).toString()

private fun ProcessTreeNode.size(): Int = 1 + children.sumOf(ProcessTreeNode::size)

private fun ProcessTreeNode.measuredSize(): Int = (if (measured) 1 else 0) +
    children.sumOf(ProcessTreeNode::measuredSize)

private fun gibibytes(value: Int): String = (value.toULong() * 1_073_741_824uL).toString()

@OptIn(ExperimentalForeignApi::class)
private fun writeSnapshot(payload: WebUiPayload): String {
    val path = "/private/tmp/harmon-webuicheck-${getpid()}.html"
    val html = ProcessPage.document(
        payloadJson = WebUiPayloadJson.encode(payload),
        mode = "snapshot",
        fallbackText = payload.reportText,
    )
    val bytes = html.encodeToByteArray()
    val file = fopen(path, "wb") ?: error("unable to write $path")
    try {
        val written = bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
        }
        check(written == bytes.size.toULong()) { "incomplete snapshot write" }
    } finally {
        fclose(file)
    }
    check(chmod(path, (S_IRUSR or S_IWUSR).toUShort()) == 0) { "unable to protect $path" }
    return path
}

private fun startWatchdog(milliseconds: Long) {
    require(milliseconds > 0) { "watchdog must be positive" }
    val queue = dispatch_queue_create("io.heapy.harmon.webuicheck.watchdog", null)
    dispatch_async(queue) {
        var remaining = milliseconds
        while (remaining > 0) {
            val slice = minOf(remaining, 1_000L)
            usleep((slice * 1_000L).toUInt())
            remaining -= slice
        }
        exitProcess(4)
    }
}

private val SAMPLE_ONE_TIME = Instant.parse("2026-08-12T10:00:00Z")
private val SAMPLE_TWO_TIME = Instant.parse("2026-08-12T10:00:01Z")
private val STALE_TIME = Instant.parse("2026-08-12T10:00:02Z")
private const val DEFAULT_WATCHDOG_MILLIS = 300_000L
