#include "harmon_native.h"

#include <sys/mman.h>
#include <sys/wait.h>

#include "harness.h"

/*
 * The suite reads live machine state, and two of its checks depend on the user
 * it runs as. `processes.samples-are-well-formed` requires a pid of its own to
 * be positive, which holds only because proc_pid_rusage denies pid 0 —
 * kernel_task, which proc_listallpids does report — to an ordinary user; and
 * `socket.accept-rejects-foreign-uid` in the socket suite cannot impersonate a
 * foreign user under uid 0. Running the harness as root is not supported, and
 * CLAUDE.md says so.
 */

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

    /*
     * A reaped pid is free for the kernel to hand to the next process, and a
     * recycled one would resolve and make this check fail for a reason that has
     * nothing to do with the bridge. Confirming it is still gone immediately
     * before the walk narrows that window to the two calls below.
     */
    errno = 0;
    const int vacant = kill(dead, 0) == -1 && errno == ESRCH;

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
        dead > 0 && vacant && status == -1 && failure == ESRCH,
        "expected -1/ESRCH for reaped pid %d (still vacant=%d), got %d/%s",
        (int)dead,
        vacant,
        status,
        strerror(failure)
    );
}

/*
 * How many regions the child below maps for itself, how long it stays alive once
 * it has them, and how many times the parent may try to catch it dying.
 *
 * Measured on this machine: the walk costs about 0.25 us per region, so 20000
 * regions take some 5 ms, and a child that lives 1.5 ms past the handshake dies
 * a quarter of the way in — 10 times out of 10. The child spins rather than
 * sleeps because usleep overshoots a millisecond request badly enough that the
 * child regularly outlived the whole walk (measured: 4 hits in 8 attempts with
 * usleep, 10 in 10 with a spin).
 */
#define HM_VANISHING_REGIONS 20000
#define HM_VANISHING_REGION_LIMIT (HM_VANISHING_REGIONS * 2)
#define HM_VANISHING_LIFE_MICROSECONDS 1500
#define HM_VANISHING_ATTEMPTS 4

static void hm_spin_microseconds(uint64_t microseconds) {
    const uint64_t deadline = hm_monotonic_time_ns() + (microseconds * 1000ULL);
    while (hm_monotonic_time_ns() < deadline) {
    }
}

/* Never returns. Maps enough regions to make a walk of this child take milliseconds. */
static void hm_run_vanishing_child(int ready_descriptor, uint64_t life_microseconds) {
    const size_t page = (size_t)getpagesize();
    for (int index = 0; index < HM_VANISHING_REGIONS; index++) {
        void *mapped = mmap(
            NULL,
            page,
            (index % 2) ? PROT_READ : PROT_NONE,
            MAP_PRIVATE | MAP_ANON,
            -1,
            0
        );
        if (mapped == MAP_FAILED) {
            break;
        }
    }
    const char ready = 'r';
    if (write(ready_descriptor, &ready, 1) != 1) {
        _exit(1);
    }
    hm_spin_microseconds(life_microseconds);
    _exit(0);
}

typedef struct {
    int status;
    int32_t regions;
    int consumed;
    int failure;
} HMVanishingWalk;

/* Walks a child that exits `life_microseconds` after it reports itself ready. */
static int hm_walk_vanishing_child(uint64_t life_microseconds, HMVanishingWalk *walk) {
    int ready[2];
    if (pipe(ready) != 0) {
        return -1;
    }
    const pid_t child = fork();
    if (child == 0) {
        close(ready[0]);
        hm_run_vanishing_child(ready[1], life_microseconds);
    }
    if (child < 0) {
        close(ready[0]);
        close(ready[1]);
        return -1;
    }
    close(ready[1]);

    char ready_byte = 0;
    const ssize_t handshake = read(ready[0], &ready_byte, 1);
    close(ready[0]);

    uint64_t bytes = 0;
    walk->regions = 0;
    walk->consumed = 0;
    errno = 0;
    walk->status = handshake == 1
        ? hm_read_compressed_or_paged_out(
              child,
              HM_VANISHING_REGION_LIMIT,
              &bytes,
              &walk->regions,
              &walk->consumed
          )
        : -1;
    walk->failure = errno;

    int wait_status = 0;
    while (waitpid(child, &wait_status, 0) < 0 && errno == EINTR) {
    }
    return handshake == 1 ? 0 : -1;
}

/*
 * A process that disappears in the middle of a walk. This is the half of the
 * be88557 contract nothing else reaches: `dead-pid-not-measured` fails on the
 * very first region and leaves through the `regions == 0` return, and
 * `region-limit-undercount` leaves through the loop condition, so neither
 * executes the branch that decides what a mid-walk failure means. Replacing
 * that branch with an unconditional `complete = 1` — "any failure means the walk
 * finished" — keeps every other check in the harness green while making an
 * undercount, and the partial sum of a process that vanished, look measured.
 *
 * The attempts exist for the machine, not for the bridge: under a mutation every
 * attempt reports the same wrong answer and the check fails anyway.
 */
static void hm_check_attribution_vanishing_pid(void) {
    HMVanishingWalk walk = {0, 0, 0, 0};
    uint64_t life = HM_VANISHING_LIFE_MICROSECONDS;
    int spawned = 0;
    int attempts = 0;

    for (int attempt = 0; attempt < HM_VANISHING_ATTEMPTS; attempt++) {
        attempts++;
        if (hm_walk_vanishing_child(life, &walk) != 0) {
            break;
        }
        spawned = 1;
        if (walk.status == 1 && walk.regions > 0 && walk.consumed == (int)walk.regions + 1) {
            break;
        }
        /* Overshot: the child outlived the walk. Undershot: it died before the first region. */
        life = walk.status == 0 ? (life / 2) + 1 : life * 2;
    }

    CHECK(
        "attribution.vanishing-pid-is-an-undercount",
        spawned &&
            walk.status == 1 &&
            walk.regions > 0 &&
            walk.consumed == (int)walk.regions + 1,
        "expected status 1 after a failed call mid-walk, got spawned=%d status=%d "
            "over %d regions in %d calls (%s) after %d attempts",
        spawned,
        walk.status,
        (int)walk.regions,
        walk.consumed,
        strerror(walk.failure),
        attempts
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
 * How far `total_processes` may sit from a count taken moments earlier. Process
 * churn over the microseconds between the two calls is a handful at most, while
 * the regression this guards against — an intermediate PID list narrower than
 * the machine, the shape of 881195d — costs hundreds.
 */
#define HM_PROCESS_COUNT_TOLERANCE 64

/*
 * One listing feeds four checks. Attribution is switched off (both budgets zero):
 * the walk it would perform is what the attribution checks above cover directly,
 * and running it over every sample here would cost seconds and prove nothing new.
 *
 * The sample array is deliberately far narrower than the machine, so that the
 * capacity branch — and with it the issue array — is exercised on every run.
 */
static void hm_check_process_listing(void) {
    const int sample_capacity = 64;
    const int issue_capacity = 256;
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
        CHECK("processes.total-matches-a-fresh-count", 0, "out of memory");
        CHECK("processes.samples-are-well-formed", 0, "out of memory");
        CHECK("processes.issues-are-well-formed", 0, "out of memory");
        return;
    }

    int total = -1;
    int inaccessible = -1;
    int written_issues = -1;
    const int counted_before = hm_count_processes();
    const int written = hm_list_processes(
        samples,
        sample_capacity,
        issues,
        issue_capacity,
        0,
        0,
        &total,
        &inaccessible,
        &written_issues
    );
    const int counted_after = hm_count_processes();

    /*
     * The caller reports `total` as the number of processes on the machine and
     * `written` as the number it could measure, so a total below the written
     * count would understate coverage. Issues are capped by their own array,
     * while `inaccessible` counts every miss including the ones that did not fit,
     * which is why every listed pid has to end up in exactly one of the two.
     */
    CHECK(
        "processes.listing-is-consistent",
        written > 0 &&
            written <= sample_capacity &&
            total >= written &&
            written_issues > 0 &&
            written_issues <= issue_capacity &&
            inaccessible >= written_issues &&
            written + inaccessible == total,
        "expected 0 < written <= %d, written + inaccessible == total, "
            "0 < issues <= %d, inaccessible >= issues; "
            "got written=%d total=%d issues=%d inaccessible=%d",
        sample_capacity,
        issue_capacity,
        written,
        total,
        written_issues,
        inaccessible
    );

    /*
     * `total` comes out of the same listing the samples do, so every invariant
     * above survives a PID list truncated to a fraction of the machine: the
     * numbers stay consistent with each other and understate the machine
     * together. Only a count taken independently notices, which is the whole
     * reason `hm_process_list_capacity` sizes that list from the caller's
     * capacity as well as from a fresh count.
     */
    const int lowest = counted_before < counted_after ? counted_before : counted_after;
    const int highest = counted_before > counted_after ? counted_before : counted_after;
    CHECK(
        "processes.total-matches-a-fresh-count",
        counted_before > 0 &&
            counted_after > 0 &&
            total >= lowest - HM_PROCESS_COUNT_TOLERANCE &&
            total <= highest + HM_PROCESS_COUNT_TOLERANCE,
        "expected a total within %d of a fresh count, got total=%d against %d then %d",
        HM_PROCESS_COUNT_TOLERANCE,
        total,
        counted_before,
        counted_after
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

    /*
     * The issue array is where the caller learns why a process is missing from
     * the sample, and it is the only consumer of `hm_read_process_metadata` on
     * this path. With 64 slots against the hundreds of processes on any live
     * machine the capacity branch fires for most of the listing, so a run that
     * produced no capacity issue at all means that branch stopped being reached
     * — and a reason that is neither constant, or a name running past its array,
     * would reach the report unnoticed.
     *
     * A pid of zero is allowed here on purpose: proc_listallpids reports
     * kernel_task, whose rusage an ordinary user cannot read, so it arrives as
     * an issue rather than as a sample.
     */
    int malformed_issue = -1;
    const char *issue_reason = "";
    int capacity_issues = 0;
    for (int index = 0; index < written_issues; ++index) {
        const HMProcessIssue *issue = &issues[index];
        if (issue->reason == HM_PROCESS_ISSUE_CAPACITY) {
            ++capacity_issues;
        }
        if (issue->pid < 0) {
            malformed_issue = index;
            issue_reason = "pid is negative";
        } else if (issue->reason != HM_PROCESS_ISSUE_CAPACITY &&
                   issue->reason != HM_PROCESS_ISSUE_RUSAGE) {
            malformed_issue = index;
            issue_reason = "reason is neither capacity nor rusage";
        } else if (issue->reason == HM_PROCESS_ISSUE_RUSAGE && issue->error_code == 0) {
            malformed_issue = index;
            issue_reason = "an unreadable rusage carries no errno";
        } else if (memchr(issue->name, '\0', sizeof(issue->name)) == NULL) {
            malformed_issue = index;
            issue_reason = "name is not terminated within HM_PROCESS_NAME_SIZE";
        } else if (memchr(
                       issue->executable_path,
                       '\0',
                       sizeof(issue->executable_path)
                   ) == NULL) {
            malformed_issue = index;
            issue_reason = "executable path is not terminated within HM_PROCESS_PATH_SIZE";
        }
        if (malformed_issue >= 0) {
            break;
        }
    }
    CHECK(
        "processes.issues-are-well-formed",
        written_issues > 0 && malformed_issue < 0 && capacity_issues > 0,
        "issue %d of %d (pid %d, reason %d): %s; %d of them blamed capacity",
        malformed_issue,
        written_issues,
        malformed_issue >= 0 ? (int)issues[malformed_issue].pid : 0,
        malformed_issue >= 0 ? issues[malformed_issue].reason : 0,
        issue_reason,
        capacity_issues
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
     * than a positive total; the three swap figures come from separate fields of
     * one sysctl and add up exactly, which is what a transposed pair breaks.
     */
    const uint64_t resident = memory.free_bytes + memory.active_bytes +
        memory.inactive_bytes + memory.wired_bytes;
    CHECK(
        "snapshot.swap-and-virtual-memory-readable",
        swap_status == 0 &&
            swap.total_bytes >= swap.used_bytes &&
            swap.used_bytes + swap.available_bytes == swap.total_bytes &&
            memory_status == 0 &&
            memory.page_size_bytes > 0 &&
            (memory.page_size_bytes & (memory.page_size_bytes - 1)) == 0 &&
            resident > 0,
        "expected 0 and used + available == total, got %d and %llu + %llu != %llu; "
            "expected 0 with a power-of-two page size and non-zero pages, "
            "got %d with page size %llu over %llu bytes",
        swap_status,
        (unsigned long long)swap.used_bytes,
        (unsigned long long)swap.available_bytes,
        (unsigned long long)swap.total_bytes,
        memory_status,
        (unsigned long long)memory.page_size_bytes,
        (unsigned long long)resident
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

void hm_run_kernel_tests(void) {
    hm_check_attribution_self_walk();
    hm_check_attribution_dead_pid();
    hm_check_attribution_vanishing_pid();
    hm_check_attribution_region_limit();
    hm_check_attribution_invalid_arguments();
    hm_check_process_listing();
    hm_check_process_listing_invalid_arguments();
    hm_check_memory_and_load();
    hm_check_processor_counters();
    hm_check_swap_and_virtual_memory();
    hm_check_storage_and_battery();
}
