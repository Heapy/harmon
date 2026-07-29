#include "harmon_native.h"

#include "anchors.h"
#include "harness.h"

/*
 * Snapshots of machine state. Nothing here can assert an exact value — the
 * numbers belong to whatever the machine is doing at the moment — so the checks
 * come in two kinds. One asserts the invariants the callers depend on: a status
 * of 0, counters that only move forward, outputs consistent with each other. The
 * other reads the same kernel source a second time and compares the sample to it
 * field by field, because every invariant of the first kind survives a mapping
 * that puts the right numbers in the wrong fields.
 */

/*
 * The two readers with nothing but a plausibility bound on them, against the
 * sources they read.
 *
 * `hm_read_physical_memory` is a single sysctl and the number is constant, so the
 * anchor is an equality: a wrong sysctl name — `hw.pagesize` reads 16384, and
 * `hw.memsize_usable` a few hundred megabytes less — is otherwise caught only
 * incidentally, by `selftest`'s footprint bound.
 *
 * The three load averages are bracketed by two `getloadavg` calls around the
 * bridge's rather than compared with a tolerance. The kernel refreshes them every
 * five seconds and a refresh may land between the reads, so a fixed allowance
 * would have to be wide enough to cover a burst of processes starting — and a
 * transposed one-minute and fifteen-minute pair sits well inside such an
 * allowance on a machine under a build. Bracketing costs nothing and needs no
 * number: on an idle machine where all three averages are the same value, no
 * check can separate them at all.
 */
static int hm_within_load_range(double reported, double first, double second) {
    const double low = first < second ? first : second;
    const double high = first > second ? first : second;
    return reported >= low && reported <= high;
}

static void hm_check_memory_and_load_fields(void) {
    uint64_t physical = 0;
    const int memory_status = hm_read_physical_memory(&physical);
    uint64_t anchor_memory = 0;
    size_t anchor_size = sizeof(anchor_memory);
    const int anchor_memory_status =
        sysctlbyname("hw.memsize", &anchor_memory, &anchor_size, NULL, 0);

    double before[3] = {0.0, 0.0, 0.0};
    double after[3] = {0.0, 0.0, 0.0};
    HMLoadAverageSample load;
    memset(&load, 0, sizeof(load));
    const int before_status = getloadavg(before, 3);
    const int load_status = hm_read_load_averages(&load);
    const int after_status = getloadavg(after, 3);

    const int bracketed = before_status == 3 &&
        after_status == 3 &&
        hm_within_load_range(load.one_minute, before[0], after[0]) &&
        hm_within_load_range(load.five_minutes, before[1], after[1]) &&
        hm_within_load_range(load.fifteen_minutes, before[2], after[2]);

    CHECK(
        "snapshot.memory-and-load-match-a-fresh-read",
        memory_status == 0 &&
            anchor_memory_status == 0 &&
            physical == anchor_memory &&
            load_status == 0 &&
            bracketed,
        "expected hw.memsize itself and each load average inside the pair of "
            "getloadavg calls around the bridge's, got %d/%d and %llu against %llu, "
            "and %d/%d,%d with %.2f,%.2f,%.2f against %.2f,%.2f,%.2f then "
            "%.2f,%.2f,%.2f",
        memory_status,
        anchor_memory_status,
        (unsigned long long)physical,
        (unsigned long long)anchor_memory,
        load_status,
        before_status,
        after_status,
        load.one_minute,
        load.five_minutes,
        load.fifteen_minutes,
        before[0],
        before[1],
        before[2],
        after[0],
        after[1],
        after[2]
    );
}

static void hm_check_memory_and_load(void) {
    uint64_t physical = 0;
    const int memory_status = hm_read_physical_memory(&physical);

    HMLoadAverageSample load;
    memset(&load, 0, sizeof(load));
    const int load_status = hm_read_load_averages(&load);

    CHECK(
        "snapshot.memory-and-load-are-plausible",
        memory_status == 0 &&
            physical > 0 &&
            load_status == 0 &&
            load.one_minute >= 0.0 &&
            load.five_minutes >= 0.0 &&
            load.fifteen_minutes >= 0.0,
        "expected 0/non-zero memory and 0/non-negative load, "
            "got %d/%llu and %d/%.2f,%.2f,%.2f",
        memory_status,
        (unsigned long long)physical,
        load_status,
        load.one_minute,
        load.five_minutes,
        load.fifteen_minutes
    );
}

/*
 * The collector turns these counters into deltas between samples, so a counter
 * that went backwards would produce a negative busy time or a nonsensical
 * utilisation. Two reads moments apart cannot straddle the 32-bit wrap the
 * kernel counters are subject to, so monotonicity here is unconditional — and
 * that, plus a non-zero aggregate, is exactly what the name promises.
 *
 * Growth is not asserted, and the check is not named as if it were:
 * host_statistics serves HOST_CPU_LOAD_INFO from a cache the kernel refreshes
 * once a second, measured on this machine at 1.000 s between changes. An earlier
 * revision burned 100 ms of CPU between the two reads to force an advance; over
 * that window the aggregate still came back byte-identical in 5 of 12 probe
 * runs, so the burn bought nothing the assertion uses and was removed.
 */
static void hm_check_processor_counters(void) {
    HMProcessorSample before;
    HMProcessorSample after;
    memset(&before, 0, sizeof(before));
    memset(&after, 0, sizeof(after));

    const int first = hm_read_processor(&before);
    const int second = hm_read_processor(&after);

    const uint64_t after_total = after.user_ticks + after.system_ticks +
        after.idle_ticks + after.nice_ticks;

    CHECK(
        "snapshot.processor-counters-never-go-backwards",
        first == 0 &&
            second == 0 &&
            after_total > 0 &&
            after.user_ticks >= before.user_ticks &&
            after.system_ticks >= before.system_ticks &&
            after.idle_ticks >= before.idle_ticks &&
            after.nice_ticks >= before.nice_ticks,
        "expected 0/0 and non-zero counters that never go backwards, "
            "got %d/%d and %llu,%llu,%llu,%llu then %llu,%llu,%llu,%llu",
        first,
        second,
        (unsigned long long)before.user_ticks,
        (unsigned long long)before.system_ticks,
        (unsigned long long)before.idle_ticks,
        (unsigned long long)before.nice_ticks,
        (unsigned long long)after.user_ticks,
        (unsigned long long)after.system_ticks,
        (unsigned long long)after.idle_ticks,
        (unsigned long long)after.nice_ticks
    );
}

/*
 * Which kernel counter ends up in which field. The check above holds whatever
 * that mapping is — transposed user and system ticks are both monotonic, and so
 * is an idle field filled from the user counter — so the four fields are compared
 * against a second `host_statistics` call.
 *
 * The tolerance is for the refresh, not for the reads: measured here, 2000
 * back-to-back pairs never differed by a tick, because the kernel serves this
 * from a cache it refreshes once a second. One refresh is worth a second of
 * ticks, which across the 14 cores of this machine is under 1400 in any one
 * field, while the fields the comparison separates are millions apart (375M
 * user, 222M system, 2036M idle).
 *
 * `nice_ticks` is the exception, and the reason it is only half covered: it reads
 * 0 on this machine and a 300 ms burn at nice 19 does not move it, so a bridge
 * that hard-coded it to zero would agree with the anchor. CLAUDE.md lists that
 * among the accepted gaps.
 */
#define HM_TICK_DRIFT 4096ULL

static void hm_check_processor_fields(void) {
    HMProcessorSample sample;
    memset(&sample, 0, sizeof(sample));
    const int status = hm_read_processor(&sample);

    host_cpu_load_info_data_t anchor;
    memset(&anchor, 0, sizeof(anchor));
    mach_msg_type_number_t count = HOST_CPU_LOAD_INFO_COUNT;
    const mach_port_t host = mach_host_self();
    const kern_return_t anchor_status = host_statistics(
        host,
        HOST_CPU_LOAD_INFO,
        (host_info_t)&anchor,
        &count
    );
    mach_port_deallocate(mach_task_self(), host);

    const HMAnchoredField fields[] = {
        {"user_ticks", sample.user_ticks, anchor.cpu_ticks[CPU_STATE_USER], HM_TICK_DRIFT},
        {"system_ticks", sample.system_ticks, anchor.cpu_ticks[CPU_STATE_SYSTEM], HM_TICK_DRIFT},
        {"idle_ticks", sample.idle_ticks, anchor.cpu_ticks[CPU_STATE_IDLE], HM_TICK_DRIFT},
        {"nice_ticks", sample.nice_ticks, anchor.cpu_ticks[CPU_STATE_NICE], HM_TICK_DRIFT},
    };
    uint64_t reported = 0;
    uint64_t anchored = 0;
    const char *mismatch = HM_FIRST_MISMATCH(fields, &reported, &anchored);

    CHECK(
        "snapshot.processor-ticks-match-a-fresh-read",
        status == 0 && anchor_status == KERN_SUCCESS && mismatch == NULL,
        "expected every tick counter within %llu of a second host_statistics call, "
            "got status %d/%d and %s reporting %llu against %llu",
        (unsigned long long)HM_TICK_DRIFT,
        status,
        (int)anchor_status,
        mismatch == NULL ? "no mismatch" : mismatch,
        (unsigned long long)reported,
        (unsigned long long)anchored
    );
}

/*
 * How far a byte figure of the swap or virtual memory sample may sit from a read
 * of the same source moments later. One constant for both, because the rule that
 * fixes it is the same: a tolerance wider than the value it is applied to checks
 * nothing, so it is chosen against the smallest field it has to keep separated
 * from zero.
 *
 * It was 64 MiB and is 8 MiB, from measurement rather than caution. 3000
 * back-to-back pairs of `host_statistics64` never differed by a page in any of
 * the eight page counts, and 2000 pairs spread over ten seconds under four
 * processes churning 64 MiB blocks moved at most 130 pages — 2.03 MB of free
 * memory — with only five pairs in the whole run differing at all. The allowance
 * is four times that worst case. `vm.swapusage` is quieter still: zero drift over
 * 2000 back-to-back pairs.
 *
 * What the old figure cost is why it moved: at 64 MiB a bridge reporting a hard
 * zero was inside the allowance for 56 MB of purgeable memory here, for
 * `compressed_bytes` on a runner with no memory pressure — the headline number of
 * this monitor — and for `xsu_used` and `xsu_avail` on any machine holding less
 * than 64 MiB of swap, which a CI runner that has never paged is. The fields it
 * separates on this machine are 9.3 GB of used swap against 928 MB available.
 *
 * The event counters carry their own allowance, which is not in the same trouble:
 * they are 394M pageins against 11M pageouts, 63M swapins against 70M swapouts.
 */
#define HM_MEMORY_DRIFT_BYTES (8ULL * 1024ULL * 1024ULL)
#define HM_MEMORY_DRIFT_EVENTS 1000000ULL

static void hm_check_swap_and_virtual_memory(void) {
    HMSwapSample swap;
    memset(&swap, 0, sizeof(swap));
    const int swap_status = hm_read_swap(&swap);

    struct xsw_usage anchor;
    memset(&anchor, 0, sizeof(anchor));
    size_t anchor_size = sizeof(anchor);
    const int anchor_status = sysctlbyname("vm.swapusage", &anchor, &anchor_size, NULL, 0);

    HMVirtualMemorySample memory;
    memset(&memory, 0, sizeof(memory));
    const int memory_status = hm_read_virtual_memory(&memory);

    /*
     * Every byte figure of the virtual memory sample is a page count multiplied
     * by the page size, so a page size of zero — or one that is not a power of
     * two — would silently zero or skew the whole sample.
     *
     * The swap figures are asserted against a second, independent read of the
     * same sysctl, field by field. `used + available == total` is worth nothing
     * on its own: it survives a transposed `xsu_used`/`xsu_avail` pair intact,
     * because `available <= total` keeps `total >= used` true as well, and all
     * three figures would keep adding up while `used` reported free space. The
     * anchor is the only thing that says which field went where. Swap may
     * legitimately be empty, and on a machine with no swap file at all the three
     * figures are zero and a transposition is invisible to any check.
     *
     * `encrypted` is compared too, normalised on both sides the way the bridge
     * normalises it, because the report prints "(encrypted)" from it and the JSON
     * carries it: inverting the flag is otherwise invisible. A machine whose swap
     * is not encrypted has both sides at zero and cannot separate an inversion
     * either.
     */
    const HMAnchoredField swap_fields[] = {
        {"total_bytes", swap.total_bytes, anchor.xsu_total, HM_MEMORY_DRIFT_BYTES},
        {"used_bytes", swap.used_bytes, anchor.xsu_used, HM_MEMORY_DRIFT_BYTES},
        {"available_bytes", swap.available_bytes, anchor.xsu_avail, HM_MEMORY_DRIFT_BYTES},
        {
            "encrypted",
            (uint64_t)swap.encrypted,
            (uint64_t)(anchor.xsu_encrypted ? 1 : 0),
            0,
        },
    };
    uint64_t reported = 0;
    uint64_t anchored = 0;
    const char *mismatch = HM_FIRST_MISMATCH(swap_fields, &reported, &anchored);

    const uint64_t resident = memory.free_bytes + memory.active_bytes +
        memory.inactive_bytes + memory.wired_bytes;
    CHECK(
        "snapshot.swap-and-virtual-memory-readable",
        swap_status == 0 &&
            swap.total_bytes >= swap.used_bytes &&
            swap.used_bytes + swap.available_bytes == swap.total_bytes &&
            anchor_status == 0 &&
            mismatch == NULL &&
            memory_status == 0 &&
            memory.page_size_bytes > 0 &&
            (memory.page_size_bytes & (memory.page_size_bytes - 1)) == 0 &&
            resident > 0,
        "expected 0 and used + available == total, got %d and %llu + %llu != %llu; "
            "expected each within %llu bytes of vm.swapusage, got status %d and %s "
            "reporting %llu against %llu; "
            "expected 0 with a power-of-two page size and non-zero pages, "
            "got %d with page size %llu over %llu bytes",
        swap_status,
        (unsigned long long)swap.used_bytes,
        (unsigned long long)swap.available_bytes,
        (unsigned long long)swap.total_bytes,
        (unsigned long long)HM_MEMORY_DRIFT_BYTES,
        anchor_status,
        mismatch == NULL ? "no mismatch" : mismatch,
        (unsigned long long)reported,
        (unsigned long long)anchored,
        memory_status,
        (unsigned long long)memory.page_size_bytes,
        (unsigned long long)resident
    );
}

/*
 * The other half of the virtual memory sample: which statistic lands in which
 * field. The check above asserts only the page size and a non-zero sum of the
 * four residency figures, which a sample with `compressed_bytes` zeroed or
 * `pageins` and `pageouts` transposed satisfies exactly as well — and
 * `compressed_bytes` is the headline number of this whole monitor.
 *
 * Every page count is multiplied here as it is there, so the multiplication is
 * mirrored rather than checked; the swap check covers a page size that is zero or
 * not a power of two, which is the failure that arithmetic has.
 *
 * The two tolerances are the ones the swap check above declares, with the
 * measurements that fixed them.
 */
static void hm_check_virtual_memory_fields(void) {
    HMVirtualMemorySample sample;
    memset(&sample, 0, sizeof(sample));
    const int status = hm_read_virtual_memory(&sample);

    const mach_port_t host = mach_host_self();
    vm_size_t page_size = 0;
    const kern_return_t page_status = host_page_size(host, &page_size);
    vm_statistics64_data_t anchor;
    memset(&anchor, 0, sizeof(anchor));
    mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
    const kern_return_t anchor_status = host_statistics64(
        host,
        HOST_VM_INFO64,
        (host_info64_t)&anchor,
        &count
    );
    mach_port_deallocate(mach_task_self(), host);

    const uint64_t page = (uint64_t)page_size;
    const HMAnchoredField fields[] = {
        {"page_size_bytes", sample.page_size_bytes, page, 0},
        {"free_bytes", sample.free_bytes, anchor.free_count * page, HM_MEMORY_DRIFT_BYTES},
        {"active_bytes", sample.active_bytes, anchor.active_count * page, HM_MEMORY_DRIFT_BYTES},
        {"inactive_bytes", sample.inactive_bytes, anchor.inactive_count * page, HM_MEMORY_DRIFT_BYTES},
        {"wired_bytes", sample.wired_bytes, anchor.wire_count * page, HM_MEMORY_DRIFT_BYTES},
        {"purgeable_bytes", sample.purgeable_bytes, anchor.purgeable_count * page, HM_MEMORY_DRIFT_BYTES},
        {"compressed_bytes", sample.compressed_bytes, anchor.compressor_page_count * page, HM_MEMORY_DRIFT_BYTES},
        {
            "uncompressed_bytes_in_compressor",
            sample.uncompressed_bytes_in_compressor,
            anchor.total_uncompressed_pages_in_compressor * page,
            HM_MEMORY_DRIFT_BYTES,
        },
        {
            "swap_backed_uncompressed_bytes",
            sample.swap_backed_uncompressed_bytes,
            anchor.swapped_count * page,
            HM_MEMORY_DRIFT_BYTES,
        },
        {"pageins", sample.pageins, anchor.pageins, HM_MEMORY_DRIFT_EVENTS},
        {"pageouts", sample.pageouts, anchor.pageouts, HM_MEMORY_DRIFT_EVENTS},
        {"faults", sample.faults, anchor.faults, HM_MEMORY_DRIFT_EVENTS},
        {"copy_on_write_faults", sample.copy_on_write_faults, anchor.cow_faults, HM_MEMORY_DRIFT_EVENTS},
        {"compressions", sample.compressions, anchor.compressions, HM_MEMORY_DRIFT_EVENTS},
        {"decompressions", sample.decompressions, anchor.decompressions, HM_MEMORY_DRIFT_EVENTS},
        {"swapins", sample.swapins, anchor.swapins, HM_MEMORY_DRIFT_EVENTS},
        {"swapouts", sample.swapouts, anchor.swapouts, HM_MEMORY_DRIFT_EVENTS},
    };
    uint64_t reported = 0;
    uint64_t anchored = 0;
    const char *mismatch = HM_FIRST_MISMATCH(fields, &reported, &anchored);

    CHECK(
        "snapshot.virtual-memory-matches-a-fresh-read",
        status == 0 &&
            page_status == KERN_SUCCESS &&
            anchor_status == KERN_SUCCESS &&
            mismatch == NULL,
        "expected every field within %llu bytes or %llu events of a second "
            "host_statistics64 call, got status %d/%d/%d and %s reporting %llu "
            "against %llu",
        (unsigned long long)HM_MEMORY_DRIFT_BYTES,
        (unsigned long long)HM_MEMORY_DRIFT_EVENTS,
        status,
        (int)page_status,
        (int)anchor_status,
        mismatch == NULL ? "no mismatch" : mismatch,
        (unsigned long long)reported,
        (unsigned long long)anchored
    );
}

/*
 * Storage is asserted against the machine, not against the implementation line
 * that sets `available`: a Mac running this harness has an internal block
 * storage driver, and that driver has read bytes since boot, at least one per
 * read operation. Those are the figures the collector reports; a run on a
 * machine with no internal device fails here with the device count in the
 * detail rather than passing vacuously.
 *
 * The battery is the opposite case — a Mac without one is a normal machine — so
 * everything about it is conditional on the availability flag. When there is a
 * battery, the percentage is a percentage.
 */
static void hm_check_storage_and_battery(void) {
    HMStorageSample storage;
    memset(&storage, 0, sizeof(storage));
    const int storage_status = hm_read_storage(&storage);

    HMBatterySample battery;
    memset(&battery, 0, sizeof(battery));
    const int battery_status = hm_read_battery(&battery);

    CHECK(
        "snapshot.storage-and-battery-readable",
        storage_status == 0 &&
            storage.root_filesystem_total_bytes > 0 &&
            storage.root_filesystem_available_bytes <=
                storage.root_filesystem_total_bytes &&
            storage.available == 1 &&
            storage.device_count > 0 &&
            storage.read_operations > 0 &&
            storage.bytes_read >= storage.read_operations &&
            battery_status == 0 &&
            (battery.available == 0 ||
                (battery.percentage >= 0 && battery.percentage <= 100)),
        "expected 0 with %llu >= %llu bytes on / and an internal device that has "
            "read at least one byte per operation, got %d/available=%d over %d "
            "devices and %llu bytes in %llu reads; expected 0 with a percentage "
            "in 0..100 when a battery exists, got %d/available=%d at %d%%",
        (unsigned long long)storage.root_filesystem_total_bytes,
        (unsigned long long)storage.root_filesystem_available_bytes,
        storage_status,
        storage.available,
        storage.device_count,
        (unsigned long long)storage.bytes_read,
        (unsigned long long)storage.read_operations,
        battery_status,
        battery.available,
        battery.percentage
    );
}

/*
 * Which IOKit key ends up in which storage field, and which `statfs` member ends
 * up in which filesystem figure. The check above asserts one relation between two
 * of the seven numbers; the rest — the written bytes, the write operations, both
 * service times — are unconstrained by it, and a read time filled from the write
 * time is exactly the kind of transposition it cannot see.
 *
 * The anchor walks the registry a second time, decides for itself which drivers
 * count and reads the keys out explicitly. It used to ask the bridge —
 * `hm_storage_driver_is_internal` — which moved the anchor with any mutation of
 * the filter: a bridge accepting every driver was green, while on a machine with
 * an external or a removable disk it folds that disk's I/O into the figures the
 * collector reports for the internal one. This machine carries five
 * `IOBlockStorageDriver` instances of which one is internal, so the two walks
 * disagree on the device count the moment the predicate moves; a machine with a
 * single driver cannot tell the filter from "accept everything", and CLAUDE.md
 * records that.
 *
 * The tolerances are for a machine that is doing I/O while the check runs, which
 * it is: measured over 50 back-to-back pairs, at most 61 KiB read, 16 KiB
 * written, 3 operations and 220 us of service time apart. The fields they
 * separate are 4 TB and 48 hours apart.
 *
 * `root_filesystem_available_bytes` is compared against `f_bavail`, which is what
 * the bridge multiplies. On the APFS root of this machine `f_bfree` and `f_bavail`
 * are equal, so reading the wrong one of the two is invisible here; CLAUDE.md
 * records that.
 */
#define HM_STORAGE_DRIFT_BYTES (256ULL * 1024ULL * 1024ULL)
#define HM_STORAGE_DRIFT_OPERATIONS 1000000ULL
#define HM_STORAGE_DRIFT_NANOSECONDS 1000000000ULL
#define HM_FILESYSTEM_DRIFT_BYTES (1024ULL * 1024ULL * 1024ULL)

typedef struct {
    uint64_t bytes_read;
    uint64_t bytes_written;
    uint64_t read_operations;
    uint64_t write_operations;
    uint64_t read_time_ns;
    uint64_t write_time_ns;
    int32_t device_count;
} HMStorageAnchor;

static uint64_t hm_storage_statistic(CFDictionaryRef statistics, CFStringRef key) {
    uint64_t value = 0;
    hm_cf_number_to_u64(CFDictionaryGetValue(statistics, key), &value);
    return value;
}

/* A boolean property, with the answer to give when the registry has none. */
static int hm_registry_flag(io_registry_entry_t entry, CFStringRef key, int fallback) {
    CFTypeRef value = IORegistryEntryCreateCFProperty(entry, key, kCFAllocatorDefault, 0);
    int flag = fallback;
    if (value != NULL) {
        if (CFGetTypeID(value) == CFBooleanGetTypeID()) {
            flag = CFBooleanGetValue((CFBooleanRef)value) ? 1 : 0;
        }
        CFRelease(value);
    }
    return flag;
}

/*
 * The whole media of a fixed disk: not a partition, not removable, not ejectable.
 * A property the registry does not carry counts against the media, which is the
 * conservative direction — an unnamed disk is not assumed to be built in.
 */
static int hm_media_is_internal(io_registry_entry_t media) {
    return hm_registry_flag(media, CFSTR(kIOMediaWholeKey), 0) &&
        !hm_registry_flag(media, CFSTR(kIOMediaRemovableKey), 1) &&
        !hm_registry_flag(media, CFSTR(kIOMediaEjectableKey), 1);
}

static int hm_driver_is_internal(io_registry_entry_t driver) {
    io_iterator_t children = IO_OBJECT_NULL;
    if (IORegistryEntryGetChildIterator(driver, kIOServicePlane, &children) !=
        KERN_SUCCESS) {
        return 0;
    }
    int internal = 0;
    io_registry_entry_t child;
    while (!internal && (child = IOIteratorNext(children)) != IO_OBJECT_NULL) {
        if (IOObjectConformsTo(child, kIOMediaClass)) {
            internal = hm_media_is_internal(child);
        }
        IOObjectRelease(child);
    }
    IOObjectRelease(children);
    return internal;
}

static HMStorageAnchor hm_read_storage_anchor(void) {
    HMStorageAnchor anchor;
    memset(&anchor, 0, sizeof(anchor));

    CFMutableDictionaryRef matching = IOServiceMatching(kIOBlockStorageDriverClass);
    if (matching == NULL) {
        return anchor;
    }
    io_iterator_t iterator = IO_OBJECT_NULL;
    if (IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iterator) !=
        KERN_SUCCESS) {
        return anchor;
    }

    io_registry_entry_t driver;
    while ((driver = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        if (hm_driver_is_internal(driver)) {
            CFTypeRef value = IORegistryEntryCreateCFProperty(
                driver,
                CFSTR(kIOBlockStorageDriverStatisticsKey),
                kCFAllocatorDefault,
                0
            );
            if (value != NULL && CFGetTypeID(value) == CFDictionaryGetTypeID()) {
                CFDictionaryRef statistics = (CFDictionaryRef)value;
                anchor.bytes_read += hm_storage_statistic(
                    statistics,
                    CFSTR(kIOBlockStorageDriverStatisticsBytesReadKey)
                );
                anchor.bytes_written += hm_storage_statistic(
                    statistics,
                    CFSTR(kIOBlockStorageDriverStatisticsBytesWrittenKey)
                );
                anchor.read_operations += hm_storage_statistic(
                    statistics,
                    CFSTR(kIOBlockStorageDriverStatisticsReadsKey)
                );
                anchor.write_operations += hm_storage_statistic(
                    statistics,
                    CFSTR(kIOBlockStorageDriverStatisticsWritesKey)
                );
                anchor.read_time_ns += hm_storage_statistic(
                    statistics,
                    CFSTR(kIOBlockStorageDriverStatisticsTotalReadTimeKey)
                );
                anchor.write_time_ns += hm_storage_statistic(
                    statistics,
                    CFSTR(kIOBlockStorageDriverStatisticsTotalWriteTimeKey)
                );
                ++anchor.device_count;
            }
            if (value != NULL) {
                CFRelease(value);
            }
        }
        IOObjectRelease(driver);
    }
    IOObjectRelease(iterator);
    return anchor;
}

static void hm_check_storage_fields(void) {
    HMStorageSample sample;
    memset(&sample, 0, sizeof(sample));
    const int status = hm_read_storage(&sample);
    const HMStorageAnchor anchor = hm_read_storage_anchor();

    struct statfs filesystem;
    memset(&filesystem, 0, sizeof(filesystem));
    const int filesystem_status = statfs("/", &filesystem);
    const uint64_t block_size = (uint64_t)filesystem.f_bsize;

    const HMAnchoredField fields[] = {
        {"device_count", (uint64_t)sample.device_count, (uint64_t)anchor.device_count, 0},
        {"bytes_read", sample.bytes_read, anchor.bytes_read, HM_STORAGE_DRIFT_BYTES},
        {"bytes_written", sample.bytes_written, anchor.bytes_written, HM_STORAGE_DRIFT_BYTES},
        {
            "read_operations",
            sample.read_operations,
            anchor.read_operations,
            HM_STORAGE_DRIFT_OPERATIONS,
        },
        {
            "write_operations",
            sample.write_operations,
            anchor.write_operations,
            HM_STORAGE_DRIFT_OPERATIONS,
        },
        {"read_time_ns", sample.read_time_ns, anchor.read_time_ns, HM_STORAGE_DRIFT_NANOSECONDS},
        {
            "write_time_ns",
            sample.write_time_ns,
            anchor.write_time_ns,
            HM_STORAGE_DRIFT_NANOSECONDS,
        },
        {
            "root_filesystem_total_bytes",
            sample.root_filesystem_total_bytes,
            (uint64_t)filesystem.f_blocks * block_size,
            0,
        },
        {
            "root_filesystem_available_bytes",
            sample.root_filesystem_available_bytes,
            (uint64_t)filesystem.f_bavail * block_size,
            HM_FILESYSTEM_DRIFT_BYTES,
        },
    };
    uint64_t reported = 0;
    uint64_t anchored = 0;
    const char *mismatch = HM_FIRST_MISMATCH(fields, &reported, &anchored);

    CHECK(
        "snapshot.storage-matches-a-fresh-read",
        status == 0 &&
            filesystem_status == 0 &&
            anchor.device_count > 0 &&
            mismatch == NULL,
        "expected every field within a second walk of the block storage registry, "
            "got status %d/%d over %d anchored devices and %s reporting %llu "
            "against %llu",
        status,
        filesystem_status,
        anchor.device_count,
        mismatch == NULL ? "no mismatch" : mismatch,
        (unsigned long long)reported,
        (unsigned long long)anchored
    );
}

/*
 * The battery fields against a second reading of the same power source. The check
 * above asserts a percentage inside 0..100, which `(current * 10) / maximum`
 * satisfies just as well as `(current * 100) / maximum`, and says nothing at all
 * about the charging flag, the power source or the estimate.
 *
 * What the anchor can prove depends on where the machine is plugged in, and the
 * detail says which case it took. Measured here, unplugged: 56 %, not charging,
 * on battery, 293 minutes — all four asserted. On mains power
 * `IOPSGetTimeRemainingEstimate` answers "unlimited" rather than a duration, the
 * bridge leaves `minutes_remaining` at -1, and the anchor agrees with it without
 * either of them having computed anything. CLAUDE.md records that half.
 *
 * The five fields are `int32_t` and every one of them carries -1 or 0 as a
 * sentinel, so they reach the table widened through `int64_t`: the conversion is
 * modular and agreeing sides stay equal, while a sentinel reported against a real
 * value comes out the whole width of the type apart, which is a mismatch, which
 * is right.
 */
#define HM_BATTERY_DRIFT_MINUTES 2

static uint64_t hm_battery_field(int32_t value) {
    return (uint64_t)(int64_t)value;
}

static void hm_check_battery_fields(void) {
    HMBatterySample sample;
    memset(&sample, 0, sizeof(sample));
    const int status = hm_read_battery(&sample);

    CFTypeRef snapshot = IOPSCopyPowerSourcesInfo();
    if (snapshot == NULL) {
        CHECK(
            "snapshot.battery-matches-a-fresh-read",
            0,
            "no power source snapshot to anchor against, bridge status %d",
            status
        );
        return;
    }

    CFStringRef source_type = IOPSGetProvidingPowerSourceType(snapshot);
    const int on_battery = source_type != NULL &&
        CFStringCompare(source_type, CFSTR(kIOPMBatteryPowerKey), 0) == kCFCompareEqualTo;

    int available = 0;
    int percentage = -1;
    int charging = 0;
    CFArrayRef sources = IOPSCopyPowerSourcesList(snapshot);
    if (sources != NULL) {
        const CFIndex count = CFArrayGetCount(sources);
        for (CFIndex index = 0; index < count; ++index) {
            CFTypeRef source = CFArrayGetValueAtIndex(sources, index);
            CFDictionaryRef description = IOPSGetPowerSourceDescription(snapshot, source);
            if (description == NULL) {
                continue;
            }
            CFTypeRef type = CFDictionaryGetValue(description, CFSTR(kIOPSTypeKey));
            if (type == NULL ||
                CFGetTypeID(type) != CFStringGetTypeID() ||
                CFStringCompare(
                    (CFStringRef)type,
                    CFSTR(kIOPSInternalBatteryType),
                    0
                ) != kCFCompareEqualTo) {
                continue;
            }
            available = 1;
            int current = 0;
            int maximum = 0;
            hm_cf_number_to_int(
                CFDictionaryGetValue(description, CFSTR(kIOPSCurrentCapacityKey)),
                &current
            );
            hm_cf_number_to_int(
                CFDictionaryGetValue(description, CFSTR(kIOPSMaxCapacityKey)),
                &maximum
            );
            if (maximum > 0) {
                percentage = (current * 100) / maximum;
            }
            charging = CFDictionaryGetValue(description, CFSTR(kIOPSIsChargingKey)) ==
                kCFBooleanTrue;
            break;
        }
        CFRelease(sources);
    }

    const CFTimeInterval seconds_remaining = IOPSGetTimeRemainingEstimate();
    const int32_t minutes_remaining = seconds_remaining >= 0.0
        ? (int32_t)(seconds_remaining / 60.0)
        : -1;
    CFRelease(snapshot);

    const HMAnchoredField fields[] = {
        {"available", hm_battery_field(sample.available), hm_battery_field(available), 0},
        {"on_battery", hm_battery_field(sample.on_battery), hm_battery_field(on_battery), 0},
        {"charging", hm_battery_field(sample.charging), hm_battery_field(charging), 0},
        {"percentage", hm_battery_field(sample.percentage), hm_battery_field(percentage), 0},
        {
            "minutes_remaining",
            hm_battery_field(sample.minutes_remaining),
            hm_battery_field(minutes_remaining),
            HM_BATTERY_DRIFT_MINUTES,
        },
    };
    uint64_t reported = 0;
    uint64_t anchored = 0;
    const char *mismatch = HM_FIRST_MISMATCH(fields, &reported, &anchored);

    CHECK(
        "snapshot.battery-matches-a-fresh-read",
        status == 0 && mismatch == NULL,
        "expected available %d, on battery %d, charging %d, %d%% and %d minutes "
            "from a second IOPS read, got status %d with available %d, on battery "
            "%d, charging %d, %d%% and %d minutes; %s disagrees",
        available,
        on_battery,
        charging,
        percentage,
        minutes_remaining,
        status,
        sample.available,
        sample.on_battery,
        sample.charging,
        sample.percentage,
        sample.minutes_remaining,
        mismatch == NULL ? "no field" : mismatch
    );
}

void hm_run_snapshot_tests(void) {
    hm_check_memory_and_load();
    hm_check_memory_and_load_fields();
    hm_check_processor_counters();
    hm_check_processor_fields();
    hm_check_swap_and_virtual_memory();
    hm_check_virtual_memory_fields();
    hm_check_storage_and_battery();
    hm_check_storage_fields();
    hm_check_battery_fields();
}
