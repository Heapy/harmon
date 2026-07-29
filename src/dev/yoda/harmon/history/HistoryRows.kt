package dev.yoda.harmon.history

import dev.yoda.harmon.analysis.AlertKeyState
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.db.AlertsQueries
import dev.yoda.harmon.db.ApplicationsQueries
import dev.yoda.harmon.db.ProcessesQueries
import dev.yoda.harmon.db.SamplesQueries
import dev.yoda.harmon.model.Alert
import dev.yoda.harmon.model.ApplicationUsage
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.ProcessUsage
import dev.yoda.harmon.model.SystemUsage
import kotlin.time.Instant

/**
 * The instant as `sample.captured_at` stores it: ISO-8601 UTC truncated to whole seconds, so the
 * string is always exactly `YYYY-MM-DDTHH:MM:SSZ`.
 *
 * Fixed width is the requirement, not the precision. [Instant.toString] prints 0, 3, 6 or 9
 * fractional digits depending on the value, and
 * `'2026-07-29T00:05:00.500Z' < '2026-07-29T00:05:00Z'` — the later moment sorts first.
 * `ORDER BY captured_at` and the retention cutoff both compare these strings, so a variable-width
 * fraction would silently reorder history and strand rows past the cutoff. The sampling interval
 * is hundreds of seconds; sub-second precision carries no information.
 */
fun Instant.toSqlTimestamp(): String = Instant.fromEpochSeconds(epochSeconds).toString()

/**
 * Writes [usage] as one `sample` row. The caller takes the new id from `lastInsertedId`.
 *
 * The whole point of routing this through the generated named parameters is that `sample` has fifty
 * insertable columns, of which twenty-six are `INTEGER`, twenty-three `REAL` and one `TEXT`: a
 * transposed pair of arguments would type-check, round-trip, and be wrong forever. Naming every one
 * of them makes the mistake visible in the diff instead of in a year-old chart.
 */
fun SamplesQueries.insertSample(usage: SystemUsage) {
    val swap = usage.swap
    val power = usage.power
    val processor = usage.processor
    val load = usage.loadAverages
    val vm = usage.virtualMemory
    val storage = usage.storage

    insert(
        captured_at = usage.capturedAt.toSqlTimestamp(),
        elapsed_seconds = usage.elapsedSeconds,
        physical_memory_bytes = usage.physicalMemoryBytes.toSqlLong(),

        swap_total_bytes = swap.totalBytes.toSqlLong(),
        swap_available_bytes = swap.availableBytes.toSqlLong(),
        swap_used_bytes = swap.usedBytes.toSqlLong(),
        swap_encrypted = swap.encrypted.toSqlLong(),

        battery_available = power.batteryAvailable.toSqlLong(),
        on_battery = power.onBattery.toSqlLong(),
        charging = power.charging.toSqlLong(),
        battery_percentage = power.percentage?.toLong(),
        battery_minutes_remaining = power.minutesRemaining?.toLong(),

        processor_total_percent = processor.totalPercent,
        processor_user_percent = processor.userPercent,
        processor_system_percent = processor.systemPercent,
        processor_nice_percent = processor.nicePercent,
        processor_idle_percent = processor.idlePercent,

        load_average_1m = load.oneMinute,
        load_average_5m = load.fiveMinutes,
        load_average_15m = load.fifteenMinutes,

        vm_free_bytes = vm.freeBytes.toSqlLong(),
        vm_active_bytes = vm.activeBytes.toSqlLong(),
        vm_inactive_bytes = vm.inactiveBytes.toSqlLong(),
        vm_wired_bytes = vm.wiredBytes.toSqlLong(),
        vm_purgeable_bytes = vm.purgeableBytes.toSqlLong(),
        vm_compressed_bytes = vm.compressedBytes.toSqlLong(),
        vm_uncompressed_bytes_in_compressor = vm.uncompressedBytesInCompressor.toSqlLong(),
        vm_swap_backed_uncompressed_bytes = vm.swapBackedUncompressedBytes.toSqlLong(),
        vm_page_in_bytes_per_second = vm.pageInBytesPerSecond,
        vm_page_out_bytes_per_second = vm.pageOutBytesPerSecond,
        vm_fault_rate = vm.faultRate,
        vm_copy_on_write_fault_rate = vm.copyOnWriteFaultRate,
        vm_compression_bytes_per_second = vm.compressionBytesPerSecond,
        vm_decompression_bytes_per_second = vm.decompressionBytesPerSecond,
        vm_swap_in_bytes_per_second = vm.swapInBytesPerSecond,
        vm_swap_out_bytes_per_second = vm.swapOutBytesPerSecond,

        storage_available = storage.available.toSqlLong(),
        storage_device_count = storage.deviceCount.toLong(),
        storage_read_bytes_per_second = storage.readBytesPerSecond,
        storage_write_bytes_per_second = storage.writeBytesPerSecond,
        storage_read_operations_per_second = storage.readOperationsPerSecond,
        storage_write_operations_per_second = storage.writeOperationsPerSecond,
        storage_read_service_time_percent = storage.readServiceTimePercent,
        storage_write_service_time_percent = storage.writeServiceTimePercent,
        storage_root_total_bytes = storage.rootFileSystemTotalBytes.toSqlLong(),
        storage_root_available_bytes = storage.rootFileSystemAvailableBytes.toSqlLong(),

        total_process_count = usage.totalProcessCount.toLong(),
        inaccessible_process_count = usage.inaccessibleProcessCount.toLong(),
        compressed_attribution_process_count = usage.compressedAttributionProcessCount.toLong(),
        compressed_attribution_failure_count = usage.compressedAttributionFailureCount.toLong(),
    )
}

/**
 * The id of [process] in the `process` lookup, inserting the row first if this identity is new.
 *
 * The id comes back from a `SELECT` rather than from `last_insert_rowid()`, which would be wrong
 * here in a way it is not for `sample`; `selectProcessId` in `Processes.sq` carries the reasoning.
 */
fun ProcessesQueries.upsertProcess(process: ProcessUsage): Long {
    val pid = process.identity.pid.toLong()
    val startedAt = process.identity.startedAt.toSqlLong()

    insertProcess(
        pid = pid,
        started_at = startedAt,
        name = process.name,
        executable_path = process.executablePath,
        uid = process.uid?.toLong(),
        parent_pid = process.parentPid.toLong(),
    )

    return selectProcessId(pid = pid, started_at = startedAt).executeAsOne()
}

/**
 * Writes [usage] as one `process_sample` row against an already-written sample and lookup row.
 *
 * [applicationId] is null for a process that runs outside an `.app` bundle: `ApplicationGrouper`
 * wraps such a process in a singleton group of its own, and those groups are not stored at all.
 */
fun ProcessesQueries.insertProcessUsage(
    sampleId: Long,
    processId: Long,
    applicationId: Long?,
    usage: ProcessUsage,
) {
    insertProcessSample(
        sample_id = sampleId,
        process_id = processId,
        application_id = applicationId,

        cpu_percent = usage.cpuPercent,
        user_cpu_percent = usage.userCpuPercent,
        system_cpu_percent = usage.systemCpuPercent,

        physical_footprint_bytes = usage.physicalFootprintBytes.toSqlLong(),
        resident_bytes = usage.residentBytes.toSqlLong(),
        wired_bytes = usage.wiredBytes.toSqlLong(),
        lifetime_max_physical_footprint_bytes = usage.lifetimeMaxPhysicalFootprintBytes.toSqlLong(),
        compressed_or_paged_out_bytes = usage.compressedOrPagedOutBytes?.toSqlLong(),
        virtual_memory_region_count = usage.virtualMemoryRegionCount?.toLong(),

        wakeups_per_second = usage.wakeupsPerSecond,
        page_ins_per_second = usage.pageInsPerSecond,
        disk_read_bytes_per_second = usage.diskReadBytesPerSecond,
        disk_write_bytes_per_second = usage.diskWriteBytesPerSecond,
        logical_write_bytes_per_second = usage.logicalWriteBytesPerSecond,
        instructions_per_second = usage.instructionsPerSecond,
        cycles_per_second = usage.cyclesPerSecond,
        energy_watts = usage.energyWatts,
        faults_per_second = usage.faultsPerSecond,
        copy_on_write_faults_per_second = usage.copyOnWriteFaultsPerSecond,
        system_calls_per_second = usage.systemCallsPerSecond,
        context_switches_per_second = usage.contextSwitchesPerSecond,
        thread_count = usage.threadCount.toLong(),
        running_thread_count = usage.runningThreadCount.toLong(),
        billed_energy_per_second = usage.billedEnergyPerSecond,
        battery_impact_score = usage.batteryImpactScore,
    )
}

/**
 * The id of [application] in the `application` lookup, inserting the row first if this key is new,
 * or null for a group that is deliberately not stored.
 *
 * Null means the group has no bundle path. `ApplicationGrouper` wraps every process outside an
 * `.app` in a singleton group of its own, and such a group repeats one `process_sample` row
 * without adding anything to it. Returning null rather than an id is what keeps the rest of the
 * write path honest: [insertApplicationUsage] demands a non-null id, so a skipped lookup row cannot
 * be followed by an `application_sample` row for the same group.
 *
 * The id comes from a `SELECT` for the same reason as in [upsertProcess].
 */
fun ApplicationsQueries.upsertApplication(application: ApplicationUsage): Long? {
    val bundlePath = application.bundlePath ?: return null

    insertApplication(
        key = application.id,
        name = application.name,
        bundle_path = bundlePath,
    )

    return selectApplicationId(application.id).executeAsOne()
}

/** Writes [usage] as one `application_sample` row against a written sample and lookup row. */
fun ApplicationsQueries.insertApplicationUsage(
    sampleId: Long,
    applicationId: Long,
    usage: ApplicationUsage,
) {
    insertApplicationSample(
        sample_id = sampleId,
        application_id = applicationId,
        root_pid = usage.rootPid.toLong(),
        process_count = usage.processCount.toLong(),

        cpu_percent = usage.cpuPercent,
        user_cpu_percent = usage.userCpuPercent,
        system_cpu_percent = usage.systemCpuPercent,

        physical_footprint_bytes = usage.physicalFootprintBytes.toSqlLong(),
        resident_bytes = usage.residentBytes.toSqlLong(),
        wired_bytes = usage.wiredBytes.toSqlLong(),
        lifetime_max_physical_footprint_bytes = usage.lifetimeMaxPhysicalFootprintBytes.toSqlLong(),
        compressed_or_paged_out_bytes = usage.compressedOrPagedOutBytes.toSqlLong(),
        compressed_attribution_process_count = usage.compressedAttributionProcessCount.toLong(),

        wakeups_per_second = usage.wakeupsPerSecond,
        page_ins_per_second = usage.pageInsPerSecond,
        disk_read_bytes_per_second = usage.diskReadBytesPerSecond,
        disk_write_bytes_per_second = usage.diskWriteBytesPerSecond,
        logical_write_bytes_per_second = usage.logicalWriteBytesPerSecond,
        instructions_per_second = usage.instructionsPerSecond,
        cycles_per_second = usage.cyclesPerSecond,
        energy_watts = usage.energyWatts,
        faults_per_second = usage.faultsPerSecond,
        copy_on_write_faults_per_second = usage.copyOnWriteFaultsPerSecond,
        system_calls_per_second = usage.systemCallsPerSecond,
        context_switches_per_second = usage.contextSwitchesPerSecond,
        thread_count = usage.threadCount.toLong(),
        running_thread_count = usage.runningThreadCount.toLong(),
        billed_energy_per_second = usage.billedEnergyPerSecond,
        battery_impact_score = usage.batteryImpactScore,
    )
}

/**
 * Writes [alert] as one `alert` row: an alert the report actually carried, text and all.
 *
 * The severity goes in by name. `Severity.ordinal` would be one byte instead of eight, and would
 * silently re-point every row already written the first time a constant is inserted into the enum —
 * a WARNING from last week reading back as CRITICAL, with nothing to fail on.
 */
fun AlertsQueries.insertReportedAlert(sampleId: Long, alert: Alert) {
    insertAlert(
        sample_id = sampleId,
        key = alert.key,
        reported = true.toSqlLong(),
        severity = alert.severity.name,
        title = alert.title,
        message = alert.message,
    )
}

/**
 * Writes one key from `MonitoringReport.suppressedAlertKeys` — over its threshold, but pushed out
 * of the report by the per-category cap — as an `alert` row with `reported = 0` and no text.
 *
 * The key is all there is to write: the report carries nothing else about a suppressed alert. It is
 * still worth a row, and the reason `reported` exists at all — a suppressed alert that was already
 * firing is never pushed again, so this is the only place it is ever visible.
 */
fun AlertsQueries.insertSuppressedAlert(sampleId: Long, key: String) {
    insertAlert(
        sample_id = sampleId,
        key = key,
        reported = false.toSqlLong(),
        severity = null,
        title = null,
        message = null,
    )
}

/** Writes [result] as one `alert_delivery` row: what one channel did with this sample's push. */
fun AlertsQueries.insertDeliveryResult(sampleId: Long, result: DeliveryResult) {
    insertAlertDelivery(
        sample_id = sampleId,
        channel = result.channel,
        successful = result.successful.toSqlLong(),
        detail = result.detail,
    )
}

/**
 * Puts [snapshot] in `alert_state` in place of whatever was there.
 *
 * Wholesale rather than as a diff, because a key that stopped firing has to leave the table and a
 * snapshot has no way to say so — it is the firing keys, not a change to them. At the handful of
 * rows an alerting machine holds, working that out would cost more than rewriting them.
 *
 * The counter the [AlertKeyState.retryAtSample] of these rows is measured against does not live
 * here; `agent_state` carries it, and both are written inside one sample transaction so they cannot
 * drift.
 */
fun AlertsQueries.replaceAlertState(snapshot: AlertStateSnapshot) {
    deleteAlertState()

    for ((key, state) in snapshot.keys) {
        insertAlertState(
            key = key,
            settled = state.settled.toSqlLong(),
            failures = state.failures.toLong(),
            retry_at_sample = state.retryAtSample,
        )
    }
}

/** The per-key half of a stored snapshot, as [replaceAlertState] left it. */
fun AlertsQueries.selectAlertKeyStates(): Map<String, AlertKeyState> =
    selectAlertState().executeAsList().associate { row ->
        row.key to AlertKeyState(
            settled = row.settled != 0L,
            failures = row.failures.toInt(),
            retryAtSample = row.retry_at_sample,
        )
    }

/**
 * SQLite has no boolean type and SQLDelight's `INTEGER AS Boolean` would demand a
 * `ColumnAdapter<Boolean, Long>` threaded through the database constructor. One conversion at the
 * call site is cheaper than that plumbing, and matches how the `ULong` columns are handled.
 */
private fun Boolean.toSqlLong(): Long = if (this) 1L else 0L
