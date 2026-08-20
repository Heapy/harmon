import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.heapy.harmon.analysis.ApplicationGrouper
import io.heapy.harmon.db.HarmonDatabase
import io.heapy.harmon.history.HistoryStore
import io.heapy.harmon.history.insertSample
import io.heapy.harmon.model.Alert
import io.heapy.harmon.model.AlertCategory
import io.heapy.harmon.model.DeliveryResult
import io.heapy.harmon.model.INIT_PID
import io.heapy.harmon.model.LoadAverages
import io.heapy.harmon.model.MonitoringReport
import io.heapy.harmon.model.NotificationPayload
import io.heapy.harmon.model.PowerState
import io.heapy.harmon.model.ProcessorCounters
import io.heapy.harmon.model.ProcessorUsage
import io.heapy.harmon.model.ProcessIdentity
import io.heapy.harmon.model.ProcessUsage
import io.heapy.harmon.model.RawProcessSample
import io.heapy.harmon.model.RawSystemSnapshot
import io.heapy.harmon.model.ReparentedFrom
import io.heapy.harmon.model.Severity
import io.heapy.harmon.model.StorageCounters
import io.heapy.harmon.model.StorageUsage
import io.heapy.harmon.model.SwapUsage
import io.heapy.harmon.model.SystemUsage
import io.heapy.harmon.model.VirtualMemoryCounters
import io.heapy.harmon.model.VirtualMemoryUsage
import io.heapy.harmon.notify.NotificationChannel
import io.heapy.harmon.util.printError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.posix.mkdtemp
import kotlin.test.fail
import kotlin.time.Instant

fun rawProcess(
    pid: Int = 42,
    startedAt: ULong = 100u,
    name: String = "example",
    parentPid: Int = 1,
    executablePath: String? = null,
    userTimeNs: ULong = 0u,
    systemTimeNs: ULong = 0u,
    wakeups: ULong = 0u,
    pageIns: ULong = 0u,
    diskRead: ULong = 0u,
    diskWrite: ULong = 0u,
    logicalWrite: ULong = 0u,
    footprint: ULong = 512uL * 1_048_576uL,
    compressedOrPagedOut: ULong? = null,
    energyNanojoules: ULong = 0u,
    billedEnergy: ULong = 0u,
): RawProcessSample = RawProcessSample(
    identity = ProcessIdentity(pid, startedAt),
    parentPid = parentPid,
    uid = 501u,
    name = name,
    executablePath = executablePath,
    userTimeNs = userTimeNs,
    systemTimeNs = systemTimeNs,
    packageIdleWakeups = wakeups,
    interruptWakeups = 0u,
    pageIns = pageIns,
    diskBytesRead = diskRead,
    diskBytesWritten = diskWrite,
    logicalWritesBytes = logicalWrite,
    instructions = 0u,
    cycles = 0u,
    energyNanojoules = energyNanojoules,
    wiredBytes = 0u,
    residentBytes = footprint,
    physicalFootprintBytes = footprint,
    lifetimeMaxPhysicalFootprintBytes = footprint,
    compressedOrPagedOutBytes = compressedOrPagedOut,
    virtualMemoryRegionCount = compressedOrPagedOut?.let { 12 },
    faults = 0u,
    copyOnWriteFaults = 0u,
    machSystemCalls = 0u,
    unixSystemCalls = 0u,
    contextSwitches = 0u,
    threadCount = 1,
    runningThreadCount = 0,
    billedEnergy = billedEnergy,
)

fun rawSnapshot(
    monotonicNs: ULong,
    processes: List<RawProcessSample>,
    swapUsed: ULong = 0u,
): RawSystemSnapshot = RawSystemSnapshot(
    capturedAt = Instant.fromEpochSeconds(monotonicNs.toLong() / 1_000_000_000),
    monotonicTimeNs = monotonicNs,
    physicalMemoryBytes = 32uL * 1_073_741_824uL,
    swap = SwapUsage(
        totalBytes = 4uL * 1_073_741_824uL,
        availableBytes = (4uL * 1_073_741_824uL) - swapUsed,
        usedBytes = swapUsed,
        encrypted = true,
    ),
    power = PowerState(
        batteryAvailable = true,
        onBattery = true,
        charging = false,
        percentage = 75,
        minutesRemaining = 240,
    ),
    processor = ProcessorCounters(
        userTicks = monotonicNs / 1_000_000u,
        systemTicks = monotonicNs / 2_000_000u,
        idleTicks = monotonicNs / 1_000_000u,
        niceTicks = 0u,
    ),
    loadAverages = LoadAverages(
        oneMinute = 1.0,
        fiveMinutes = 0.8,
        fifteenMinutes = 0.5,
    ),
    virtualMemory = VirtualMemoryCounters(
        pageSizeBytes = 16_384u,
        freeBytes = 4uL * 1_073_741_824uL,
        activeBytes = 8uL * 1_073_741_824uL,
        inactiveBytes = 8uL * 1_073_741_824uL,
        wiredBytes = 4uL * 1_073_741_824uL,
        purgeableBytes = 128uL * 1_048_576uL,
        compressedBytes = 2uL * 1_073_741_824uL,
        uncompressedBytesInCompressor = 4uL * 1_073_741_824uL,
        swapBackedUncompressedBytes = swapUsed,
        pageIns = monotonicNs / 10_000_000u,
        pageOuts = monotonicNs / 20_000_000u,
        faults = monotonicNs / 1_000_000u,
        copyOnWriteFaults = monotonicNs / 2_000_000u,
        compressions = monotonicNs / 5_000_000u,
        decompressions = monotonicNs / 6_000_000u,
        swapIns = monotonicNs / 20_000_000u,
        swapOuts = monotonicNs / 25_000_000u,
    ),
    storage = StorageCounters(
        available = true,
        deviceCount = 1,
        bytesRead = monotonicNs,
        bytesWritten = monotonicNs / 2u,
        readOperations = monotonicNs / 1_000_000u,
        writeOperations = monotonicNs / 2_000_000u,
        readTimeNs = monotonicNs / 4u,
        writeTimeNs = monotonicNs / 5u,
        rootFileSystemTotalBytes = 1_000_000_000_000u,
        rootFileSystemAvailableBytes = 500_000_000_000u,
    ),
    totalProcessCount = processes.size,
    inaccessibleProcessCount = 0,
    compressedAttributionProcessCount = processes.count {
        it.compressedOrPagedOutBytes != null
    },
    compressedAttributionFailureCount = 0,
    processes = processes,
    processIssues = emptyList(),
)

fun processUsage(
    pid: Int = 42,
    startedAt: ULong = pid.toULong(),
    parentPid: Int = 1,
    name: String = "example",
    executablePath: String? = null,
    cpuPercent: Double = 0.0,
    footprint: ULong = 512uL * 1_048_576uL,
    diskWriteBytesPerSecond: Double = 0.0,
    logicalWriteBytesPerSecond: Double = 0.0,
    compressedOrPagedOutBytes: ULong? = null,
    energyWatts: Double = 0.0,
    impact: Double = 0.0,
    reparentedFrom: ReparentedFrom? = null,
): ProcessUsage = ProcessUsage(
    identity = ProcessIdentity(pid, startedAt),
    parentPid = parentPid,
    uid = 501u,
    name = name,
    executablePath = executablePath,
    cpuPercent = cpuPercent,
    userCpuPercent = cpuPercent,
    systemCpuPercent = 0.0,
    physicalFootprintBytes = footprint,
    residentBytes = footprint,
    wiredBytes = 0u,
    lifetimeMaxPhysicalFootprintBytes = footprint,
    compressedOrPagedOutBytes = compressedOrPagedOutBytes,
    virtualMemoryRegionCount = compressedOrPagedOutBytes?.let { 12 },
    wakeupsPerSecond = impact,
    pageInsPerSecond = 0.0,
    diskReadBytesPerSecond = 0.0,
    diskWriteBytesPerSecond = diskWriteBytesPerSecond,
    logicalWriteBytesPerSecond = logicalWriteBytesPerSecond,
    instructionsPerSecond = 0.0,
    cyclesPerSecond = 0.0,
    energyWatts = energyWatts,
    faultsPerSecond = 0.0,
    copyOnWriteFaultsPerSecond = 0.0,
    systemCallsPerSecond = 0.0,
    contextSwitchesPerSecond = 0.0,
    threadCount = 1,
    runningThreadCount = 0,
    billedEnergyPerSecond = 0.0,
    batteryImpactScore = impact,
    reparentedFrom = reparentedFrom,
)

fun systemUsage(
    processes: List<ProcessUsage>,
    swapUsed: ULong = 0u,
    swapOutBytesPerSecond: Double = 0.0,
    batteryPercentage: Int = 75,
): SystemUsage = SystemUsage(
    capturedAt = Instant.fromEpochSeconds(100),
    elapsedSeconds = 2.0,
    physicalMemoryBytes = 32uL * 1_073_741_824uL,
    swap = SwapUsage(
        totalBytes = 4uL * 1_073_741_824uL,
        availableBytes = (4uL * 1_073_741_824uL) - swapUsed,
        usedBytes = swapUsed,
        encrypted = true,
    ),
    power = PowerState(
        batteryAvailable = true,
        onBattery = true,
        charging = false,
        percentage = batteryPercentage,
        minutesRemaining = 180,
    ),
    processor = ProcessorUsage(
        totalPercent = 40.0,
        userPercent = 30.0,
        systemPercent = 10.0,
        nicePercent = 0.0,
        idlePercent = 60.0,
    ),
    loadAverages = LoadAverages(
        oneMinute = 1.0,
        fiveMinutes = 0.8,
        fifteenMinutes = 0.5,
    ),
    virtualMemory = VirtualMemoryUsage(
        freeBytes = 4uL * 1_073_741_824uL,
        activeBytes = 8uL * 1_073_741_824uL,
        inactiveBytes = 8uL * 1_073_741_824uL,
        wiredBytes = 4uL * 1_073_741_824uL,
        purgeableBytes = 128uL * 1_048_576uL,
        compressedBytes = 2uL * 1_073_741_824uL,
        uncompressedBytesInCompressor = 4uL * 1_073_741_824uL,
        swapBackedUncompressedBytes = swapUsed,
        pageInBytesPerSecond = 0.0,
        pageOutBytesPerSecond = 0.0,
        faultRate = 0.0,
        copyOnWriteFaultRate = 0.0,
        compressionBytesPerSecond = 0.0,
        decompressionBytesPerSecond = 0.0,
        swapInBytesPerSecond = 0.0,
        swapOutBytesPerSecond = swapOutBytesPerSecond,
    ),
    storage = StorageUsage(
        available = true,
        deviceCount = 1,
        readBytesPerSecond = 0.0,
        writeBytesPerSecond = 0.0,
        readOperationsPerSecond = 0.0,
        writeOperationsPerSecond = 0.0,
        readServiceTimePercent = 0.0,
        writeServiceTimePercent = 0.0,
        rootFileSystemTotalBytes = 1_000_000_000_000u,
        rootFileSystemAvailableBytes = 500_000_000_000u,
    ),
    totalProcessCount = processes.size,
    inaccessibleProcessCount = 0,
    compressedAttributionProcessCount = processes.count {
        it.compressedOrPagedOutBytes != null
    },
    compressedAttributionFailureCount = 0,
    processes = processes,
    applications = ApplicationGrouper().group(processes),
    processIssues = emptyList(),
)

fun rankingReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(
                pid = 11,
                name = "alpha",
                cpuPercent = 12.0,
                footprint = 2uL * 1_073_741_824uL,
                diskWriteBytesPerSecond = 8.0 * 1_048_576.0,
                logicalWriteBytesPerSecond = 2.0 * 1_048_576.0,
                compressedOrPagedOutBytes = 512uL * 1_048_576uL,
                energyWatts = 0.9,
                impact = 4.0,
            ),
            processUsage(
                pid = 12,
                name = "bravo",
                cpuPercent = 12.0,
                footprint = 1uL * 1_073_741_824uL,
                logicalWriteBytesPerSecond = 6.0 * 1_048_576.0,
                impact = 4.0,
            ),
            processUsage(
                pid = 13,
                name = "charlie",
                cpuPercent = 3.0,
                footprint = 4uL * 1_073_741_824uL,
                diskWriteBytesPerSecond = 1.0 * 1_048_576.0,
                compressedOrPagedOutBytes = 128uL * 1_048_576uL,
                energyWatts = 0.2,
                impact = 1.0,
            ),
            processUsage(pid = 14, name = "delta"),
            processUsage(
                pid = 15,
                name = "echo",
                cpuPercent = 7.0,
                footprint = 3uL * 1_073_741_824uL,
                diskWriteBytesPerSecond = 4.0 * 1_048_576.0,
                logicalWriteBytesPerSecond = 4.0 * 1_048_576.0,
                compressedOrPagedOutBytes = 64uL * 1_048_576uL,
                energyWatts = 0.05,
                impact = 2.0,
            ),
        ),
    ),
    alerts = listOf(alert(key = "cpu:alpha")),
    topProcessCount = 3,
)

fun alert(
    key: String,
    severity: Severity = Severity.WARNING,
    title: String = "title of $key",
    message: String = "message of $key",
    category: AlertCategory = AlertCategory.CPU,
    pids: List<Int> = emptyList(),
): Alert = Alert(
    key = key,
    category = category,
    severity = severity,
    title = title,
    message = message,
    pids = pids,
)

const val ORPHANED_PID = 11

const val LOST_PARENT_PID = 4_241

val LOST_PARENT: ReparentedFrom = ReparentedFrom(pid = LOST_PARENT_PID, name = "supervisor")

val FIRST_SAMPLE: Instant = Instant.fromEpochSeconds(100)

const val FIRST_SAMPLE_AT = "1970-01-01T00:01:40Z"

val SECOND_SAMPLE: Instant = Instant.fromEpochSeconds(400)

const val SECOND_SAMPLE_AT = "1970-01-01T00:06:40Z"

fun orphanReport(
    capturedAt: Instant = FIRST_SAMPLE,
    parentPid: Int = INIT_PID,
    lostParent: ReparentedFrom? = LOST_PARENT,
): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(
                pid = ORPHANED_PID,
                name = "abandoned",
                parentPid = parentPid,
                reparentedFrom = lostParent,
            ),
        ),
    ).copy(capturedAt = capturedAt),
    alerts = emptyList(),
    topProcessCount = 1,
)

const val HISTORY_DATABASE_NAME = "history.db"

fun historyDirectory(home: String): String = "$home/Library/Application Support/Harmon"

fun historyDatabasePath(home: String): String = "${historyDirectory(home)}/$HISTORY_DATABASE_NAME"

@OptIn(ExperimentalForeignApi::class)
fun withScratchHome(body: (String) -> Unit) {
    val home = memScoped {
        mkdtemp("/tmp/harmon-history-test.XXXXXX".cstr.getPointer(this))?.toKString()
    } ?: fail("cannot create a temporary directory")

    try {
        body(home)
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(home, null)
    }
}

fun withHistoryStore(
    home: String,
    retentionDays: Long = 7,
    intervalSeconds: Long = 300,
    logError: (String) -> Unit = ::printError,
    body: (HistoryStore) -> Unit,
) {
    val store = HistoryStore.openOrNull(
        retentionDays = retentionDays,
        intervalSeconds = intervalSeconds,
        homeDirectory = home,
        logError = logError,
    ) ?: fail("the store must open under $home")
    try {
        body(store)
    } finally {
        store.close()
    }
}

fun <T> withInMemoryDriver(body: (SqlDriver) -> T): T {
    val driver = inMemoryDriver(HarmonDatabase.Schema)
    return try {
        body(driver)
    } finally {
        driver.close()
    }
}

fun <T> withInMemoryDatabase(body: (HarmonDatabase) -> T): T =
    withInMemoryDriver { driver -> body(HarmonDatabase(driver)) }

fun HarmonDatabase.insertParentSample(): Long {
    samplesQueries.insertSample(systemUsage(emptyList()))
    return samplesQueries.lastInsertedId().executeAsOne()
}

fun HistoryStore.samples() = database.samplesQueries
    .selectBetween("0000-01-01T00:00:00Z", "9999-12-31T23:59:59Z")
    .executeAsList()

const val AFTER_EVERY_SAMPLE = "9999-12-31T23:59:59Z"

const val BEFORE_EVERY_SAMPLE = "1970-01-01T00:00:00Z"

fun <T : Any> SqlDriver.scalar(sql: String, read: (SqlCursor) -> T?): T? = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        cursor.next()
        QueryResult.Value(read(cursor))
    },
    parameters = 0,
).value

fun SqlDriver.countRows(table: String): Long =
    scalar("SELECT count(*) FROM $table") { it.getLong(0) }
        ?: fail("counting $table returned no row")

fun SqlDriver.pageCount(): Long =
    scalar("PRAGMA page_count") { it.getLong(0) } ?: fail("PRAGMA page_count returned no row")

fun <T : Any> SqlDriver.pragma(name: String, read: (SqlCursor) -> T?): T? =
    scalar("PRAGMA $name", read)

class RecordingChannel(
    override val name: String = "recording",
    override val bestEffort: Boolean = false,
    private val failure: Throwable? = null,
    private val successful: (Int) -> Boolean = { true },
) : NotificationChannel {
    val payloads = mutableListOf<NotificationPayload>()

    override fun deliver(payload: NotificationPayload): DeliveryResult {
        failure?.let { throw it }
        payloads += payload
        return DeliveryResult(
            channel = name,
            successful = successful(payloads.size),
            detail = "recorded",
        )
    }
}
