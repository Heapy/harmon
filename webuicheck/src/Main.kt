package dev.yoda.harmon.webuicheck

import dev.yoda.harmon.model.ProcessCollectionIssueReason
import dev.yoda.harmon.model.ProcessTreeNode
import dev.yoda.harmon.model.ProcessTreeSnapshot
import dev.yoda.harmon.report.ProcessPage
import dev.yoda.harmon.report.WebUiAlertSummary
import dev.yoda.harmon.report.WebUiPayload
import dev.yoda.harmon.report.WebUiPayloadFactory
import dev.yoda.harmon.report.WebUiPayloadJson
import dev.yoda.harmon.report.WebUiStatus
import dev.yoda.harmon.report.WebUiSystemSummary
import dev.yoda.harmon.util.printError
import dev.yoda.harmon.web.LiveUiEndpoint
import dev.yoda.harmon.web.LiveUiServer
import dev.yoda.harmon.web.LiveUiState
import dev.yoda.harmon.web.liveUiEndpointResponds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
    val server = LiveUiServer(state, TOKEN, logError = ::printError)
    val port = server.start()
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
                    WebUiPayloadJson.encode(WebUiPayloadFactory.warming(generatedAt = SAMPLE_ONE_TIME)),
                )
                "" -> Unit
                else -> exitProcess(3)
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
        cpuTotalPercent = cpuTotal,
        physicalMemoryBytes = gibibytes(32),
        swapUsedBytes = gibibytes(2),
        swapTotalBytes = gibibytes(8),
        swapAvailableBytes = gibibytes(6),
        swapEncrypted = true,
        batteryAvailable = true,
        onBattery = true,
        charging = false,
        batteryPercentage = 72,
        loadAverageOneMinute = 2.4,
    ),
    alerts = listOf(
        WebUiAlertSummary(
            key = "memory:firefox",
            severity = "warning",
            title = "Firefox subtree uses 10.0 GiB",
            message = "The total includes readable descendants.",
        ),
    ),
    suppressedAlertKeys = emptyList(),
    reportText = "Fake Harmon report for browser tests.",
    error = null,
    staleSince = null,
    retrySeconds = null,
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
        cpuSelfPercent = 9.0,
        cpuTotalPercent = 62.0,
        memorySelfBytes = gibibytes(2),
        memoryTotalBytes = gibibytes(13),
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
        cpuSelfPercent = 0.0,
        cpuTotalPercent = 2.0,
        memorySelfBytes = "0",
        memoryTotalBytes = gibibytes(1),
        unavailableProcessCount = 1,
        totalsPartial = true,
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
    cpuSelfPercent = cpuSelf,
    cpuTotalPercent = cpuTotal,
    memorySelfBytes = gibibytes(memorySelfGiB),
    memoryTotalBytes = gibibytes(memoryTotalGiB),
    unavailableProcessCount = children.sumOf(ProcessTreeNode::unavailableProcessCount),
    totalsPartial = children.any(ProcessTreeNode::totalsPartial),
    children = children,
)

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
    val queue = dispatch_queue_create("dev.yoda.harmon.webuicheck.watchdog", null)
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
