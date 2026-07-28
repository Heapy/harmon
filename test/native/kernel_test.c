#include "harmon_native.h"

#include <sys/wait.h>

#include "harness.h"

/*
 * The contract `hm_read_compressed_or_paged_out` holds with the kernel. The
 * three-valued result is the whole point of the suite: 0 means the walk reached
 * the end of the address space and the sum may be reported as measured, 1 means
 * it stopped early and the sum is an undercount, -1 means the process could not
 * be read at all. Commit be88557 fixed a regression in exactly this distinction
 * — every errno other than EINVAL ends the walk as an undercount, because ESRCH
 * says the process vanished mid-walk rather than that the walk ran out of
 * regions — and nothing but a check like these would have caught it.
 */

/*
 * A pid that is guaranteed not to resolve: a child that has exited and been
 * reaped. Picking a large constant instead would be a guess about what the
 * machine happens to be running.
 */
static pid_t hm_reaped_pid(void) {
    const pid_t child = fork();
    if (child == 0) {
        _exit(0);
    }
    if (child < 0) {
        return -1;
    }
    int status = 0;
    while (waitpid(child, &status, 0) < 0) {
        if (errno != EINTR) {
            return -1;
        }
    }
    return child;
}

static int hm_attribution_rejects(
    int region_limit,
    uint64_t *output_bytes,
    int32_t *output_regions,
    int *consumed_regions
) {
    errno = 0;
    const int result = hm_read_compressed_or_paged_out(
        getpid(),
        region_limit,
        output_bytes,
        output_regions,
        consumed_regions
    );
    return result == -1 && errno == EINVAL;
}

static void hm_check_attribution_self_walk(void) {
    uint64_t bytes = 0;
    int32_t regions = 0;
    int consumed = 0;
    const int status = hm_read_compressed_or_paged_out(
        getpid(),
        HM_ATTRIBUTION_REGION_LIMIT,
        &bytes,
        &regions,
        &consumed
    );

    CHECK(
        "attribution.self-walk-completes",
        status == 0 && regions > 0,
        "expected status 0 over a non-empty address space, got status %d over %d regions",
        status,
        (int)regions
    );
    /*
     * The caller charges the walk against a sample-wide budget, so an
     * uncounted call would let one process spend another's share. Every
     * iteration costs one call, and the call that ends the walk is counted too,
     * hence consumed is never below the number of regions actually summed.
     */
    CHECK(
        "attribution.consumed-is-reported",
        consumed > 0 &&
            consumed <= HM_ATTRIBUTION_REGION_LIMIT &&
            consumed >= (int)regions,
        "expected %d < consumed <= %d for %d regions, got %d",
        0,
        HM_ATTRIBUTION_REGION_LIMIT,
        (int)regions,
        consumed
    );
}

static void hm_check_attribution_dead_pid(void) {
    const pid_t dead = hm_reaped_pid();
    uint64_t bytes = 0;
    int32_t regions = 0;
    int consumed = 0;

    errno = 0;
    const int status = hm_read_compressed_or_paged_out(
        dead,
        HM_ATTRIBUTION_REGION_LIMIT,
        &bytes,
        &regions,
        &consumed
    );
    const int failure = errno;

    CHECK(
        "attribution.dead-pid-not-measured",
        dead > 0 && status == -1 && failure == ESRCH,
        "expected -1/ESRCH for reaped pid %d, got %d/%s",
        (int)dead,
        status,
        strerror(failure)
    );
}

/*
 * A walk that ran out of budget must not look like a walk that finished. The
 * first region is read successfully, the loop then finds no room for a second,
 * and the sum is an undercount even though nothing failed.
 */
static void hm_check_attribution_region_limit(void) {
    uint64_t bytes = 0;
    int32_t regions = 0;
    int consumed = 0;
    const int status = hm_read_compressed_or_paged_out(
        getpid(),
        1,
        &bytes,
        &regions,
        &consumed
    );

    CHECK(
        "attribution.region-limit-undercount",
        status == 1 && regions == 1 && consumed == 1,
        "expected status 1 after 1 region and 1 call, got status %d after %d regions and %d calls",
        status,
        (int)regions,
        consumed
    );
}

static void hm_check_attribution_invalid_arguments(void) {
    uint64_t bytes = 0;
    int32_t regions = 0;
    int consumed = 0;

    const int zero_limit = hm_attribution_rejects(0, &bytes, &regions, &consumed);
    const int negative_limit = hm_attribution_rejects(-1, &bytes, &regions, &consumed);
    const int null_bytes = hm_attribution_rejects(
        HM_ATTRIBUTION_REGION_LIMIT,
        NULL,
        &regions,
        &consumed
    );
    const int null_regions = hm_attribution_rejects(
        HM_ATTRIBUTION_REGION_LIMIT,
        &bytes,
        NULL,
        &consumed
    );
    const int null_consumed = hm_attribution_rejects(
        HM_ATTRIBUTION_REGION_LIMIT,
        &bytes,
        &regions,
        NULL
    );

    CHECK(
        "attribution.rejects-invalid-arguments",
        zero_limit && negative_limit && null_bytes && null_regions && null_consumed,
        "expected -1/EINVAL from each; rejected zero-limit=%d negative-limit=%d "
            "null-bytes=%d null-regions=%d null-consumed=%d",
        zero_limit,
        negative_limit,
        null_bytes,
        null_regions,
        null_consumed
    );
}

/*
 * Snapshots of machine state. Nothing here can assert an exact value — the
 * numbers belong to whatever the machine is doing at the moment — so the checks
 * assert the invariants the callers actually depend on: a status of 0, counters
 * that only move forward, and outputs consistent with each other.
 */

/*
 * One listing feeds two checks. Attribution is switched off (both budgets zero):
 * the walk it would perform is what the attribution checks above cover directly,
 * and running it over every sample here would cost seconds and prove nothing new.
 */
static void hm_check_process_listing(void) {
    const int sample_capacity = 64;
    const int issue_capacity = 32;
    HMProcessSample *samples = (HMProcessSample *)calloc(
        (size_t)sample_capacity,
        sizeof(HMProcessSample)
    );
    HMProcessIssue *issues = (HMProcessIssue *)calloc(
        (size_t)issue_capacity,
        sizeof(HMProcessIssue)
    );
    if (samples == NULL || issues == NULL) {
        free(samples);
        free(issues);
        CHECK("processes.listing-is-consistent", 0, "out of memory");
        CHECK("processes.samples-are-well-formed", 0, "out of memory");
        return;
    }

    int total = -1;
    int inaccessible = -1;
    int written_issues = -1;
    const int written = hm_list_processes(
        samples,
        sample_capacity,
        issues,
        issue_capacity,
        0,
        0,
        0,
        &total,
        &inaccessible,
        &written_issues
    );

    /*
     * The caller reports `total` as the number of processes on the machine and
     * `written` as the number it could measure, so a total below the written
     * count would understate coverage. Issues are capped by their own array,
     * while `inaccessible` counts every miss including the ones that did not fit.
     */
    CHECK(
        "processes.listing-is-consistent",
        written > 0 &&
            written <= sample_capacity &&
            total >= written &&
            written_issues >= 0 &&
            written_issues <= issue_capacity &&
            inaccessible >= written_issues,
        "expected 0 < written <= %d, total >= written, 0 <= issues <= %d, "
            "inaccessible >= issues; got written=%d total=%d issues=%d inaccessible=%d",
        sample_capacity,
        issue_capacity,
        written,
        total,
        written_issues,
        inaccessible
    );

    int malformed = -1;
    const char *reason = "";
    for (int index = 0; index < written; ++index) {
        const HMProcessSample *sample = &samples[index];
        if (sample->pid <= 0) {
            malformed = index;
            reason = "pid is not positive";
        } else if (memchr(sample->name, '\0', sizeof(sample->name)) == NULL) {
            malformed = index;
            reason = "name is not terminated within HM_PROCESS_NAME_SIZE";
        } else if (sample->name[0] == '\0') {
            /* The bridge substitutes `pid-N` when the kernel reports no name. */
            malformed = index;
            reason = "name is empty";
        } else if (memchr(
                       sample->executable_path,
                       '\0',
                       sizeof(sample->executable_path)
                   ) == NULL) {
            malformed = index;
            reason = "executable path is not terminated within HM_PROCESS_PATH_SIZE";
        }
        if (malformed >= 0) {
            break;
        }
    }
    CHECK(
        "processes.samples-are-well-formed",
        written > 0 && malformed < 0,
        "sample %d of %d (pid %d): %s",
        malformed,
        written,
        malformed >= 0 ? (int)samples[malformed].pid : 0,
        reason
    );

    free(samples);
    free(issues);
}

static int hm_listing_rejects(
    HMProcessSample *samples,
    int sample_capacity,
    HMProcessIssue *issues,
    int issue_capacity,
    int attribution_process_limit,
    int attribution_region_budget
) {
    errno = 0;
    const int result = hm_list_processes(
        samples,
        sample_capacity,
        issues,
        issue_capacity,
        0,
        attribution_process_limit,
        attribution_region_budget,
        NULL,
        NULL,
        NULL
    );
    return result == -1 && errno == EINVAL;
}

/*
 * Rejected arguments never reach the buffers, so the two samples stay
 * deliberately uninitialised: a call that touched them would be the bug.
 */
static void hm_check_process_listing_invalid_arguments(void) {
    HMProcessSample sample;
    HMProcessIssue issue;

    const int null_samples = hm_listing_rejects(NULL, 1, &issue, 1, 0, 0);
    const int zero_capacity = hm_listing_rejects(&sample, 0, &issue, 1, 0, 0);
    const int null_issues = hm_listing_rejects(&sample, 1, NULL, 1, 0, 0);
    const int zero_issue_capacity = hm_listing_rejects(&sample, 1, &issue, 0, 0, 0);
    const int negative_process_limit = hm_listing_rejects(&sample, 1, &issue, 1, -1, 0);
    const int negative_region_budget = hm_listing_rejects(&sample, 1, &issue, 1, 0, -1);

    CHECK(
        "processes.rejects-invalid-arguments",
        null_samples && zero_capacity && null_issues && zero_issue_capacity &&
            negative_process_limit && negative_region_budget,
        "expected -1/EINVAL from each; rejected null-samples=%d zero-capacity=%d "
            "null-issues=%d zero-issue-capacity=%d negative-process-limit=%d "
            "negative-region-budget=%d",
        null_samples,
        zero_capacity,
        null_issues,
        zero_issue_capacity,
        negative_process_limit,
        negative_region_budget
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
 * Burns CPU rather than sleeping: the tick counters are what the check is about,
 * and a sleeping process contributes none of its own.
 */
static void hm_burn_millis(uint64_t milliseconds) {
    const uint64_t deadline =
        hm_monotonic_time_ns() + (milliseconds * 1000000ULL);
    while (hm_monotonic_time_ns() < deadline) {
    }
}

/*
 * The collector turns these counters into deltas between samples, so a counter
 * that went backwards would produce a negative busy time or a nonsensical
 * utilisation. Two reads milliseconds apart cannot straddle the 32-bit wrap the
 * kernel counters are subject to, so monotonicity here is unconditional.
 *
 * A strict increase is deliberately not asserted, however much the name invites
 * it: host_statistics serves HOST_CPU_LOAD_INFO from a cache the kernel
 * refreshes once a second, measured on this machine at 1.000 s between changes.
 * Over a 100 ms window of full load the aggregate stayed byte-identical in 9 of
 * 20 probe runs, so requiring growth would fail about half the time, and waiting
 * out the refresh instead would pin the suite to an undocumented cadence.
 */
static void hm_check_processor_counters(void) {
    HMProcessorSample before;
    HMProcessorSample after;
    memset(&before, 0, sizeof(before));
    memset(&after, 0, sizeof(after));

    const int first = hm_read_processor(&before);
    hm_burn_millis(100);
    const int second = hm_read_processor(&after);

    const uint64_t after_total = after.user_ticks + after.system_ticks +
        after.idle_ticks + after.nice_ticks;

    CHECK(
        "snapshot.processor-counters-advance",
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

static void hm_check_swap_and_virtual_memory(void) {
    HMSwapSample swap;
    memset(&swap, 0, sizeof(swap));
    const int swap_status = hm_read_swap(&swap);

    HMVirtualMemorySample memory;
    memset(&memory, 0, sizeof(memory));
    const int memory_status = hm_read_virtual_memory(&memory);

    /*
     * Every byte figure is a page count multiplied by the page size, so a page
     * size of zero — or one that is not a power of two — would silently zero or
     * skew the whole sample. Swap may legitimately be empty, hence `>=` rather
     * than a positive total.
     */
    const uint64_t resident = memory.free_bytes + memory.active_bytes +
        memory.inactive_bytes + memory.wired_bytes;
    CHECK(
        "snapshot.swap-and-virtual-memory-readable",
        swap_status == 0 &&
            swap.total_bytes >= swap.used_bytes &&
            (swap.encrypted == 0 || swap.encrypted == 1) &&
            memory_status == 0 &&
            memory.page_size_bytes > 0 &&
            (memory.page_size_bytes & (memory.page_size_bytes - 1)) == 0 &&
            resident > 0,
        "expected 0 and total >= used, got %d and %llu >= %llu (encrypted=%d); "
            "expected 0 with a power-of-two page size and non-zero pages, "
            "got %d with page size %llu over %llu bytes",
        swap_status,
        (unsigned long long)swap.total_bytes,
        (unsigned long long)swap.used_bytes,
        swap.encrypted,
        memory_status,
        (unsigned long long)memory.page_size_bytes,
        (unsigned long long)resident
    );
}

/*
 * The battery is checked for its availability flag alone: a Mac without one is a
 * normal machine, and every other field is meaningless there.
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
            storage.device_count >= 0 &&
            storage.available == (storage.device_count > 0 ? 1 : 0) &&
            battery_status == 0 &&
            (battery.available == 0 || battery.available == 1),
        "expected 0 with %llu >= %llu bytes on / and availability matching "
            "%d devices, got %d/available=%d; expected 0 and a 0/1 battery flag, "
            "got %d/%d",
        (unsigned long long)storage.root_filesystem_total_bytes,
        (unsigned long long)storage.root_filesystem_available_bytes,
        storage.device_count,
        storage_status,
        storage.available,
        battery_status,
        battery.available
    );
}

void hm_run_kernel_tests(void) {
    hm_check_attribution_self_walk();
    hm_check_attribution_dead_pid();
    hm_check_attribution_region_limit();
    hm_check_attribution_invalid_arguments();
    hm_check_process_listing();
    hm_check_process_listing_invalid_arguments();
    hm_check_memory_and_load();
    hm_check_processor_counters();
    hm_check_swap_and_virtual_memory();
    hm_check_storage_and_battery();
}
