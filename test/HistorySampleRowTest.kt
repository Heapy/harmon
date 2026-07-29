import dev.yoda.harmon.db.Sample
import dev.yoda.harmon.history.insertSample
import dev.yoda.harmon.model.LoadAverages
import dev.yoda.harmon.model.PowerState
import dev.yoda.harmon.model.ProcessorUsage
import dev.yoda.harmon.model.StorageUsage
import dev.yoda.harmon.model.SwapUsage
import dev.yoda.harmon.model.SystemUsage
import dev.yoda.harmon.model.VirtualMemoryUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Round-trips every column of `sample` through a real SQLite.
 *
 * The values come from a local builder rather than `TestFixtures` on purpose: the fixture has
 * `activeBytes == inactiveBytes`, `userCpuPercent == cpuPercent` and zero in almost every rate, so a
 * transposed pair of same-typed columns would round-trip through it looking correct. Here every one
 * of the fifty columns carries a value no other column carries, which is the only thing that turns
 * this into a real check.
 */
class HistorySampleRowTest {

    @Test
    fun everySystemUsageFieldLandsInItsOwnColumn() {
        val stored = roundTrip(markedUsage())

        assertEquals("2026-07-29T00:05:07Z", stored.captured_at)
        assertEquals(300.25, stored.elapsed_seconds)
        assertEquals(68_719_476_736L, stored.physical_memory_bytes)

        assertEquals(11_000_000_001L, stored.swap_total_bytes)
        assertEquals(11_000_000_002L, stored.swap_available_bytes)
        assertEquals(11_000_000_003L, stored.swap_used_bytes)
        assertEquals(1L, stored.swap_encrypted)

        assertEquals(1L, stored.battery_available)
        assertEquals(0L, stored.on_battery)
        assertEquals(1L, stored.charging)
        assertEquals(87L, stored.battery_percentage)
        assertEquals(133L, stored.battery_minutes_remaining)

        assertEquals(21.5, stored.processor_total_percent)
        assertEquals(22.5, stored.processor_user_percent)
        assertEquals(23.5, stored.processor_system_percent)
        assertEquals(24.5, stored.processor_nice_percent)
        assertEquals(25.5, stored.processor_idle_percent)

        assertEquals(31.5, stored.load_average_1m)
        assertEquals(32.5, stored.load_average_5m)
        assertEquals(33.5, stored.load_average_15m)

        assertEquals(12_000_000_001L, stored.vm_free_bytes)
        assertEquals(12_000_000_002L, stored.vm_active_bytes)
        assertEquals(12_000_000_003L, stored.vm_inactive_bytes)
        assertEquals(12_000_000_004L, stored.vm_wired_bytes)
        assertEquals(12_000_000_005L, stored.vm_purgeable_bytes)
        assertEquals(12_000_000_006L, stored.vm_compressed_bytes)
        assertEquals(12_000_000_007L, stored.vm_uncompressed_bytes_in_compressor)
        assertEquals(12_000_000_008L, stored.vm_swap_backed_uncompressed_bytes)
        assertEquals(41.5, stored.vm_page_in_bytes_per_second)
        assertEquals(42.5, stored.vm_page_out_bytes_per_second)
        assertEquals(43.5, stored.vm_fault_rate)
        assertEquals(44.5, stored.vm_copy_on_write_fault_rate)
        assertEquals(45.5, stored.vm_compression_bytes_per_second)
        assertEquals(46.5, stored.vm_decompression_bytes_per_second)
        assertEquals(47.5, stored.vm_swap_in_bytes_per_second)
        assertEquals(48.5, stored.vm_swap_out_bytes_per_second)

        assertEquals(0L, stored.storage_available)
        assertEquals(3L, stored.storage_device_count)
        assertEquals(51.5, stored.storage_read_bytes_per_second)
        assertEquals(52.5, stored.storage_write_bytes_per_second)
        assertEquals(53.5, stored.storage_read_operations_per_second)
        assertEquals(54.5, stored.storage_write_operations_per_second)
        assertEquals(55.5, stored.storage_read_service_time_percent)
        assertEquals(56.5, stored.storage_write_service_time_percent)
        assertEquals(13_000_000_001L, stored.storage_root_total_bytes)
        assertEquals(13_000_000_002L, stored.storage_root_available_bytes)

        assertEquals(772L, stored.total_process_count)
        assertEquals(61L, stored.inaccessible_process_count)
        assertEquals(62L, stored.compressed_attribution_process_count)
        assertEquals(63L, stored.compressed_attribution_failure_count)
    }

    /**
     * Booleans only have two values, so a single row cannot tell a correct mapping from one that
     * writes a constant. Flipping all five and reading them back can.
     */
    @Test
    fun everyBooleanFollowsItsSourceFieldRatherThanAConstant() {
        val marked = markedUsage()
        val flipped = marked.copy(
            swap = marked.swap.copy(encrypted = !marked.swap.encrypted),
            power = marked.power.copy(
                batteryAvailable = !marked.power.batteryAvailable,
                onBattery = !marked.power.onBattery,
                charging = !marked.power.charging,
            ),
            storage = marked.storage.copy(available = !marked.storage.available),
        )

        val stored = roundTrip(flipped)

        assertEquals(0L, stored.swap_encrypted)
        assertEquals(0L, stored.battery_available)
        assertEquals(1L, stored.on_battery)
        assertEquals(0L, stored.charging)
        assertEquals(1L, stored.storage_available)
    }

    /** A machine with no battery must read back as "unknown", not as a flat and dead 0 percent. */
    @Test
    fun anAbsentBatteryStaysNullRatherThanZero() {
        val marked = markedUsage()
        val noBattery = marked.copy(
            power = marked.power.copy(
                batteryAvailable = false,
                percentage = null,
                minutesRemaining = null,
            ),
        )

        val stored = roundTrip(noBattery)

        assertNull(stored.battery_percentage)
        assertNull(stored.battery_minutes_remaining)
    }

    /**
     * Counters past the signed boundary are clamped rather than wrapped, so a byte count never
     * comes back negative. `toSqlLong` owns the rule; this pins that the write path actually goes
     * through it.
     */
    @Test
    fun aCounterAboveTheSignedBoundaryIsClampedOnTheWayIn() {
        val marked = markedUsage()
        val huge = marked.copy(physicalMemoryBytes = ULong.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, roundTrip(huge).physical_memory_bytes)
    }

    private fun roundTrip(usage: SystemUsage): Sample = withInMemoryDatabase { database ->
        database.samplesQueries.insertSample(usage)

        database.samplesQueries
            .selectBetween("1970-01-01T00:00:00Z", "2100-01-01T00:00:00Z")
            .executeAsOne()
    }
}

/**
 * A `SystemUsage` in which no two columns of `sample` share a value. Deliberately not in
 * `TestFixtures`: its whole purpose is to be unrealistic.
 */
private fun markedUsage(): SystemUsage = SystemUsage(
    capturedAt = Instant.parse("2026-07-29T00:05:07Z"),
    elapsedSeconds = 300.25,
    physicalMemoryBytes = 68_719_476_736uL,
    swap = SwapUsage(
        totalBytes = 11_000_000_001uL,
        availableBytes = 11_000_000_002uL,
        usedBytes = 11_000_000_003uL,
        encrypted = true,
    ),
    power = PowerState(
        batteryAvailable = true,
        onBattery = false,
        charging = true,
        percentage = 87,
        minutesRemaining = 133,
    ),
    processor = ProcessorUsage(
        totalPercent = 21.5,
        userPercent = 22.5,
        systemPercent = 23.5,
        nicePercent = 24.5,
        idlePercent = 25.5,
    ),
    loadAverages = LoadAverages(
        oneMinute = 31.5,
        fiveMinutes = 32.5,
        fifteenMinutes = 33.5,
    ),
    virtualMemory = VirtualMemoryUsage(
        freeBytes = 12_000_000_001uL,
        activeBytes = 12_000_000_002uL,
        inactiveBytes = 12_000_000_003uL,
        wiredBytes = 12_000_000_004uL,
        purgeableBytes = 12_000_000_005uL,
        compressedBytes = 12_000_000_006uL,
        uncompressedBytesInCompressor = 12_000_000_007uL,
        swapBackedUncompressedBytes = 12_000_000_008uL,
        pageInBytesPerSecond = 41.5,
        pageOutBytesPerSecond = 42.5,
        faultRate = 43.5,
        copyOnWriteFaultRate = 44.5,
        compressionBytesPerSecond = 45.5,
        decompressionBytesPerSecond = 46.5,
        swapInBytesPerSecond = 47.5,
        swapOutBytesPerSecond = 48.5,
    ),
    storage = StorageUsage(
        available = false,
        deviceCount = 3,
        readBytesPerSecond = 51.5,
        writeBytesPerSecond = 52.5,
        readOperationsPerSecond = 53.5,
        writeOperationsPerSecond = 54.5,
        readServiceTimePercent = 55.5,
        writeServiceTimePercent = 56.5,
        rootFileSystemTotalBytes = 13_000_000_001uL,
        rootFileSystemAvailableBytes = 13_000_000_002uL,
    ),
    totalProcessCount = 772,
    inaccessibleProcessCount = 61,
    compressedAttributionProcessCount = 62,
    compressedAttributionFailureCount = 63,
    processes = emptyList(),
    applications = emptyList(),
    processIssues = emptyList(),
)
