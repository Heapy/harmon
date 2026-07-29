package dev.yoda.harmon.history

import dev.yoda.harmon.db.SamplesQueries
import dev.yoda.harmon.model.SystemUsage
import kotlin.time.Instant

/**
 * The instant as `sample.captured_at` stores it: ISO-8601 UTC truncated to whole seconds, so the
 * string is always exactly `YYYY-MM-DDTHH:MM:SSZ`.
 *
 * Fixed width is the requirement, not the precision. [Instant.toString] prints 0, 3, 6 or 9
 * fractional digits depending on the value, and `'2026-07-29T00:05:00.500Z' < '2026-07-29T00:05:00Z'`
 * — the later moment sorts first. `ORDER BY captured_at` and the retention cutoff both compare these
 * strings, so a variable-width fraction would silently reorder history and strand rows past the
 * cutoff. The sampling interval is hundreds of seconds; sub-second precision carries no information.
 */
fun Instant.toSqlTimestamp(): String = Instant.fromEpochSeconds(epochSeconds).toString()

/**
 * Writes [usage] as one `sample` row. The caller takes the new id from `lastInsertedId`.
 *
 * The whole point of routing this through the generated named parameters is that `sample` has fifty
 * columns, of which twenty-three are `REAL` and nineteen `INTEGER`: a transposed pair of arguments
 * would type-check, round-trip, and be wrong forever. Naming every one of them makes the mistake
 * visible in the diff instead of in a year-old chart.
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
 * SQLite has no boolean type and SQLDelight's `INTEGER AS Boolean` would demand a
 * `ColumnAdapter<Boolean, Long>` threaded through the database constructor. One conversion at the
 * call site is cheaper than that plumbing, and matches how the `ULong` columns are handled.
 */
private fun Boolean.toSqlLong(): Long = if (this) 1L else 0L
