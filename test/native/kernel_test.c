#include "harmon_native.h"

#include <fcntl.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/wait.h>

#include "harness.h"

/*
 * The suite reads live machine state, and three of its checks depend on the
 * account it runs as. `processes.samples-are-well-formed` requires a pid of its
 * own to be positive, which holds only because proc_pid_rusage denies pid 0 —
 * kernel_task, which proc_listallpids does report — to an ordinary user; and
 * `socket.accept-rejects-foreign-uid` in the socket suite cannot impersonate a
 * foreign user under uid 0. Running the harness as root is not supported, and
 * CLAUDE.md says so.
 *
 * `processes.issues-are-well-formed` needs the opposite kind of account: the
 * capacity branch it covers only fires once the sample array is full, so the
 * caller must own at least the 64 rusage-readable processes that fill it. An
 * ordinary desktop session is far past that (566 here), a stripped service
 * account might not be — the check then fails naming the capacity issues it
 * counted, which is the same signal it gives for a truncated PID list. Two more
 * checks ask for a little more of the same machine:
 * `processes.issue-metadata-matches-a-fresh-read` for 32 issues that are still
 * readable moments later, and `processes.rusage-issue-path-matches-a-fresh-read`
 * for 16 processes of *other* users, which is what the rusage branch reports. All
 * of them fail naming what they counted rather than passing vacuously, and
 * CLAUDE.md records the requirements alongside the non-root one.
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
 * How many regions the child below maps for itself, how long the killer thread
 * waits once the walk has started, and how many times the parent may try to catch
 * the child dying inside it.
 *
 * The child does not time its own death: it maps its regions, reports itself
 * ready and then waits to be killed, so nothing about the hit depends on how
 * promptly the parent gets from the handshake to the first `proc_pidinfo`. An
 * earlier revision had the child exit a fixed time after the handshake, which
 * made every deschedule of the parent in that window an attempt spent, and the
 * see-saw that adjusted the lifetime could walk away from the answer instead of
 * towards it. The killer thread starts counting from the instant before the
 * bridge call instead — the only reference point that makes the window a property
 * of the walk rather than of the scheduler.
 *
 * Measured on this machine: 20000 regions cost about 6 ms to map and 0.19 us
 * each to walk, so the walk lasts some 4 ms, and a kill 1.5 ms in lands about
 * 8000 regions in — 10 times out of 10, as it does at 0.5 ms and at 3 ms. The
 * attempts and the adjustment are for a killer thread starved past the end of the
 * walk on a loaded machine; under a mutation every attempt reports the same wrong
 * answer and the check fails anyway.
 */
#define HM_VANISHING_REGIONS 20000
#define HM_VANISHING_REGION_LIMIT (HM_VANISHING_REGIONS * 2)
#define HM_VANISHING_DELAY_MICROSECONDS 1500
#define HM_VANISHING_ATTEMPTS 6

static void hm_spin_microseconds(uint64_t microseconds) {
    const uint64_t deadline = hm_monotonic_time_ns() + (microseconds * 1000ULL);
    while (hm_monotonic_time_ns() < deadline) {
    }
}

/*
 * Never returns. Maps enough regions to make a walk of this child take
 * milliseconds.
 *
 * The two lines before that are what keeps a hung run from hanging `./kotlin
 * test` instead of the harness. The child waits to be killed, and `fork` cleared
 * the alarm `main` armed, so a parent that dies on SIGALRM before reaching the
 * `kill` below leaves it running forever — holding the write end of the stdout
 * pipe, which is the descriptor `NativeHarness.kt` reads until EOF. Closing
 * stdout here makes that EOF arrive with the parent's death, and the re-armed
 * alarm keeps the orphan itself bounded by the same timeout.
 */
static void hm_run_vanishing_child(int ready_descriptor) {
    alarm(HM_TEST_TIMEOUT_SECONDS);
    close(STDOUT_FILENO);
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
    for (;;) {
        pause();
    }
}

typedef struct {
    pid_t child;
    uint64_t delay_microseconds;
    volatile int walking;
    int killed;
} HMVanishingKiller;

/*
 * Waits for the walk to start, then for a fraction of it, then kills the child.
 * It spins rather than sleeps for the reason the child used to: usleep overshoots
 * a millisecond request by more than the whole walk lasts.
 */
static void *hm_kill_mid_walk(void *argument) {
    HMVanishingKiller *killer = (HMVanishingKiller *)argument;
    while (!killer->walking) {
    }
    hm_spin_microseconds(killer->delay_microseconds);
    killer->killed = kill(killer->child, SIGKILL) == 0;
    return NULL;
}

typedef struct {
    int status;
    int32_t regions;
    int consumed;
    int failure;
    int killed;
} HMVanishingWalk;

/* Walks a child killed `delay_microseconds` after the walk of it started. */
static int hm_walk_vanishing_child(uint64_t delay_microseconds, HMVanishingWalk *walk) {
    int ready[2];
    if (pipe(ready) != 0) {
        return -1;
    }
    const pid_t child = fork();
    if (child == 0) {
        close(ready[0]);
        hm_run_vanishing_child(ready[1]);
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

    HMVanishingKiller killer = {child, delay_microseconds, 0, 0};
    pthread_t thread;
    const int armed = handshake == 1 &&
        pthread_create(&thread, NULL, hm_kill_mid_walk, &killer) == 0;

    uint64_t bytes = 0;
    walk->regions = 0;
    walk->consumed = 0;
    errno = 0;
    if (armed) {
        killer.walking = 1;
        walk->status = hm_read_compressed_or_paged_out(
            child,
            HM_VANISHING_REGION_LIMIT,
            &bytes,
            &walk->regions,
            &walk->consumed
        );
    } else {
        walk->status = -1;
    }
    walk->failure = errno;
    if (armed) {
        pthread_join(thread, NULL);
    }
    walk->killed = killer.killed;

    /* The child waits to be killed, so it outlives a walk the killer never reached. */
    kill(child, SIGKILL);
    int wait_status = 0;
    while (waitpid(child, &wait_status, 0) < 0 && errno == EINTR) {
    }
    return armed ? 0 : -1;
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
 * ESRCH is asserted and not merely reported: it is the errno the fix turned on,
 * and the only one that reaches this branch from a process that stopped existing.
 */
static void hm_check_attribution_vanishing_pid(void) {
    HMVanishingWalk walk = {0, 0, 0, 0, 0};
    uint64_t delay = HM_VANISHING_DELAY_MICROSECONDS;
    int spawned = 0;
    int attempts = 0;

    for (int attempt = 0; attempt < HM_VANISHING_ATTEMPTS; attempt++) {
        attempts++;
        if (hm_walk_vanishing_child(delay, &walk) != 0) {
            break;
        }
        spawned = 1;
        if (walk.status == 1 &&
            walk.regions > 0 &&
            walk.consumed == (int)walk.regions + 1 &&
            walk.failure == ESRCH) {
            break;
        }
        /* Landed after the last region, or before the first one. */
        delay = walk.status == 0 ? (delay / 2) + 1 : delay * 2;
    }

    CHECK(
        "attribution.vanishing-pid-is-an-undercount",
        spawned &&
            walk.status == 1 &&
            walk.regions > 0 &&
            walk.consumed == (int)walk.regions + 1 &&
            walk.failure == ESRCH,
        "expected status 1 and ESRCH after a failed call mid-walk, got spawned=%d "
            "status=%d over %d regions in %d calls (%s, killed=%d) after %d attempts",
        spawned,
        walk.status,
        (int)walk.regions,
        walk.consumed,
        strerror(walk.failure),
        walk.killed,
        attempts
    );
}

/*
 * The bytes the walk sums are what the whole feature reports, and nothing else in
 * either harness looks at them: every other attribution check reads the status,
 * the region count and the consumed budget. Summing `pri_pages_resident` instead
 * of `pri_pages_swapped_out`, dropping the `getpagesize()` multiplication and
 * returning a hard zero are invisible to all of them.
 *
 * The anchor is the same walk, performed by the test before and after the
 * bridge's, so that a target whose pages move between the two is caught by the
 * sandwich instead of reported as a mismatch. Measured here: 120 sandwiches over
 * 6 targets, never a page apart.
 *
 * The target is preferably another process of this user, because the harness has
 * no swapped-out pages seconds after it started (measured: 0 over 55 regions),
 * and over a zero sum a missing multiplication and a hard zero are both
 * invisible. The scan walks every same-user process, newest first, and stops at
 * the first one the compressor is holding pages of — 568 of this account's 594
 * processes qualified when measured, the first at the tenth of them, and the
 * probe walk gives up on a process at its first swapped page, so the usual cost
 * is a few walks.
 *
 * An earlier revision spent a budget of 24 candidates whether or not their walk
 * found anything, which made the check fail on any account with two dozen recent
 * processes — 30 background `sleep`s were enough, and so were two harnesses of
 * the same user running at once. Nothing about that failure was a property of the
 * bridge.
 *
 * A machine whose compressor holds nothing of this account (a freshly booted CI
 * runner with no memory pressure, say) has no such target at all. The check then
 * walks this process instead, where the swapped sum is zero and the resident sum
 * is not, and requires the bridge to report exactly zero bytes: that still fails
 * a bridge summing the resident pages, and it fails nothing on a quiet machine.
 * What it cannot separate there is a hard-coded zero — CLAUDE.md lists that among
 * the accepted gaps.
 */
#define HM_ANCHOR_REGION_LIMIT 1024
#define HM_ANCHOR_ATTEMPTS 8

typedef struct {
    uint64_t swapped_pages;
    uint64_t resident_pages;
    int32_t regions;
} HMRegionWalk;

static HMRegionWalk hm_walk_regions(pid_t pid) {
    HMRegionWalk walk = {0, 0, 0};
    uint64_t address = 0;
    while (walk.regions < HM_ANCHOR_REGION_LIMIT) {
        struct proc_regioninfo region;
        memset(&region, 0, sizeof(region));
        if (proc_pidinfo(
                pid,
                PROC_PIDREGIONINFO,
                address,
                &region,
                (int)sizeof(region)
            ) != (int)sizeof(region)) {
            break;
        }
        walk.swapped_pages += region.pri_pages_swapped_out;
        walk.resident_pages += region.pri_pages_resident;
        const uint64_t next = region.pri_address + region.pri_size;
        if (next <= address || next < region.pri_address) {
            break;
        }
        address = next;
        ++walk.regions;
    }
    return walk;
}

/* Whether the compressor holds anything of `pid`, answered at the first page. */
static int hm_carries_swapped_pages(pid_t pid) {
    uint64_t address = 0;
    for (int32_t region_index = 0; region_index < HM_ANCHOR_REGION_LIMIT; ++region_index) {
        struct proc_regioninfo region;
        memset(&region, 0, sizeof(region));
        if (proc_pidinfo(
                pid,
                PROC_PIDREGIONINFO,
                address,
                &region,
                (int)sizeof(region)
            ) != (int)sizeof(region)) {
            return 0;
        }
        if (region.pri_pages_swapped_out > 0) {
            return 1;
        }
        const uint64_t next = region.pri_address + region.pri_size;
        if (next <= address || next < region.pri_address) {
            return 0;
        }
        address = next;
    }
    return 0;
}

typedef struct {
    pid_t target;
    int examined;
    int carriers;
    int attempted;
    uint64_t lowest_pages;
    uint64_t highest_pages;
    uint64_t resident_pages;
    uint64_t bytes;
    int32_t regions;
    int status;
} HMAnchoredWalk;

/* One target walked by the test, then by the bridge, then by the test again. */
static int hm_sandwich_walk(pid_t pid, HMAnchoredWalk *result) {
    const HMRegionWalk before = hm_walk_regions(pid);
    uint64_t bytes = 0;
    int32_t regions = 0;
    int consumed = 0;
    const int status = hm_read_compressed_or_paged_out(
        pid,
        HM_ANCHOR_REGION_LIMIT,
        &bytes,
        &regions,
        &consumed
    );
    const HMRegionWalk after = hm_walk_regions(pid);
    if (status < 0 || after.regions == 0) {
        return 0;
    }

    result->target = pid;
    result->lowest_pages = before.swapped_pages < after.swapped_pages
        ? before.swapped_pages
        : after.swapped_pages;
    result->highest_pages = before.swapped_pages > after.swapped_pages
        ? before.swapped_pages
        : after.swapped_pages;
    result->resident_pages = before.resident_pages < after.resident_pages
        ? before.resident_pages
        : after.resident_pages;
    result->bytes = bytes;
    result->regions = regions;
    result->status = status;
    return 1;
}

/*
 * The first process of this user whose pages the compressor is holding, or this
 * process when the compressor holds none of them. A candidate that stops existing
 * in the middle costs an attempt rather than the check: `proc_listallpids` answers
 * newest first, and the newest processes of a desktop session include the
 * Spotlight workers, which exit on their own schedule. Attempts are capped so
 * that a bridge refusing every walk fails in bounded time instead of retrying
 * every carrier on the machine.
 */
static HMAnchoredWalk hm_anchored_walk(void) {
    HMAnchoredWalk result = {0, 0, 0, 0, 0, 0, 0, 0, 0, -1};
    const int capacity = hm_count_processes() + HM_PROCESS_LIST_HEADROOM;
    pid_t *pids = (pid_t *)calloc((size_t)capacity, sizeof(pid_t));
    if (pids == NULL) {
        return result;
    }
    const int listed = proc_listallpids(pids, capacity * (int)sizeof(pid_t));
    for (int index = 0; index < listed && result.attempted < HM_ANCHOR_ATTEMPTS; ++index) {
        const pid_t pid = pids[index];
        if (pid <= 0 || pid == getpid()) {
            continue;
        }
        struct proc_bsdinfo info;
        memset(&info, 0, sizeof(info));
        if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, (int)sizeof(info)) !=
                (int)sizeof(info) ||
            info.pbi_uid != (uint32_t)geteuid()) {
            continue;
        }
        ++result.examined;
        if (!hm_carries_swapped_pages(pid)) {
            continue;
        }

        ++result.carriers;
        ++result.attempted;
        if (hm_sandwich_walk(pid, &result)) {
            break;
        }
    }
    free(pids);

    /*
     * Only when the machine offered no subject at all. A carrier the bridge
     * refused to walk is a failure to report, not a reason to fall back to a
     * target where the sum is zero.
     */
    if (result.target == 0 && result.carriers == 0) {
        hm_sandwich_walk(getpid(), &result);
    }
    return result;
}

static void hm_check_attribution_bytes(void) {
    const HMAnchoredWalk walk = hm_anchored_walk();
    const uint64_t page_size = (uint64_t)getpagesize();

    /*
     * The resident sum is asserted to differ from the swapped one so that the
     * comparison is discriminating: over a target where the two agreed, reading
     * the wrong field would pass. That is also what carries the fallback target,
     * where the swapped sum is zero and the resident one is thousands of pages.
     */
    CHECK(
        "attribution.bytes-match-an-independent-walk",
        walk.target > 0 &&
            walk.status >= 0 &&
            walk.regions > 0 &&
            walk.resident_pages > 0 &&
            walk.resident_pages != walk.lowest_pages &&
            walk.bytes >= walk.lowest_pages * page_size &&
            walk.bytes <= walk.highest_pages * page_size,
        "expected pid %d's %llu..%llu swapped pages at %llu bytes each, got %llu "
            "bytes (%llu pages) over %d regions at status %d, against %llu resident "
            "pages, after examining %d processes of this user, %d of which carried "
            "compressed pages and %d of which were walked",
        (int)walk.target,
        (unsigned long long)walk.lowest_pages,
        (unsigned long long)walk.highest_pages,
        (unsigned long long)page_size,
        (unsigned long long)walk.bytes,
        (unsigned long long)(walk.bytes / page_size),
        (int)walk.regions,
        walk.status,
        (unsigned long long)walk.resident_pages,
        walk.examined,
        walk.carriers,
        walk.attempted
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
 * come in two kinds. One asserts the invariants the callers depend on: a status
 * of 0, counters that only move forward, outputs consistent with each other. The
 * other reads the same kernel source a second time and compares the sample to it
 * field by field, because every invariant of the first kind survives a mapping
 * that puts the right numbers in the wrong fields.
 */

/*
 * One field of a sample against the same field read again, and how far apart the
 * two reads may be. A tolerance of zero means the two reads must agree exactly.
 */
typedef struct {
    const char *name;
    uint64_t reported;
    uint64_t anchor;
    uint64_t tolerance;
} HMAnchoredField;

static const char *hm_first_mismatch(
    const HMAnchoredField *fields,
    size_t count,
    uint64_t *reported,
    uint64_t *anchor
) {
    for (size_t index = 0; index < count; ++index) {
        const uint64_t difference = fields[index].reported > fields[index].anchor
            ? fields[index].reported - fields[index].anchor
            : fields[index].anchor - fields[index].reported;
        if (difference > fields[index].tolerance) {
            *reported = fields[index].reported;
            *anchor = fields[index].anchor;
            return fields[index].name;
        }
    }
    return NULL;
}

/*
 * How far `total_processes` may sit from a count taken moments earlier. Process
 * churn over the microseconds between the two calls is a handful at most, while
 * the regression this guards against — an intermediate PID list narrower than
 * the machine, the shape of 881195d — costs hundreds.
 */
#define HM_PROCESS_COUNT_TOLERANCE 64

/*
 * How much of the issue array may disagree with a second read of the same pids,
 * and how much of it has to be compared for the comparison to mean anything.
 *
 * Neither number is a tolerance on the bridge: a process that execs between the
 * two reads changes its own name and path, and one that exits is skipped
 * entirely, so a live machine produces the occasional legitimate disagreement.
 * Measured here: 0 disagreements over 174 to 177 comparable issues, six runs out
 * of six. The thinnest mutation measured — a parent pid hard-coded to 1, which
 * many processes legitimately have — still disagreed on 24 % of them, against a
 * budget of 6.25 %.
 */
#define HM_ISSUE_METADATA_MINIMUM 32
#define HM_ISSUE_METADATA_MISMATCH_DIVISOR 16

/*
 * The metadata on the issue path, against a second read the test performs
 * itself. `processes.issues-are-well-formed` is satisfied by the zeroed struct
 * the fields start from, so without this the whole `hm_read_process_metadata`
 * call could go and nothing would notice. It also covers what that function puts
 * where: a hard-coded uid, a parent pid that is always 1, a `proc_pidpath` that
 * is never called.
 *
 * This covers the *capacity* branch only, and it is the branch the narrow listing
 * above produces (247 of its 256 issues here). A pid whose `proc_bsdinfo` cannot
 * be read at all is skipped, and that is exactly the population of the other
 * branch: `PROC_PIDTBSDINFO` is refused for another user's process, 0 of 275
 * readable here, so anchoring against it would compare nothing.
 * `processes.rusage-issue-path-matches-a-fresh-read` covers the rusage branch
 * instead, over the one field that survives the refusal.
 *
 * The second read mirrors the bridge's fallback order — proc_name first, then
 * `pbi_name` and `pbi_comm` — because that order is the mapping under test.
 */
static void hm_check_issue_metadata(const HMProcessIssue *issues, int written_issues) {
    int compared = 0;
    int mismatched = 0;
    int first = -1;
    const char *reason = "";
    for (int index = 0; index < written_issues; ++index) {
        const HMProcessIssue *issue = &issues[index];
        struct proc_bsdinfo info;
        memset(&info, 0, sizeof(info));
        if (proc_pidinfo(issue->pid, PROC_PIDTBSDINFO, 0, &info, (int)sizeof(info)) !=
            (int)sizeof(info)) {
            continue;
        }

        char name[HM_PROCESS_NAME_SIZE];
        memset(name, 0, sizeof(name));
        if (proc_name(issue->pid, name, (uint32_t)sizeof(name)) <= 0) {
            snprintf(
                name,
                sizeof(name),
                "%s",
                info.pbi_name[0] != '\0' ? info.pbi_name : info.pbi_comm
            );
        }
        char path[HM_PROCESS_PATH_SIZE];
        memset(path, 0, sizeof(path));
        if (proc_pidpath(issue->pid, path, (uint32_t)sizeof(path)) <= 0) {
            path[0] = '\0';
        }

        ++compared;
        const char *disagreement = NULL;
        if (strcmp(issue->name, name) != 0) {
            disagreement = "name";
        } else if (issue->uid != info.pbi_uid) {
            disagreement = "uid";
        } else if (issue->parent_pid != (int32_t)info.pbi_ppid) {
            disagreement = "parent pid";
        } else if (strcmp(issue->executable_path, path) != 0) {
            disagreement = "executable path";
        }
        if (disagreement != NULL) {
            ++mismatched;
            if (first < 0) {
                first = index;
                reason = disagreement;
            }
        }
    }

    CHECK(
        "processes.issue-metadata-matches-a-fresh-read",
        compared >= HM_ISSUE_METADATA_MINIMUM &&
            mismatched * HM_ISSUE_METADATA_MISMATCH_DIVISOR <= compared,
        "expected at least %d issues comparable against a fresh read and at most a "
            "%dth of them to disagree, compared %d of %d and %d disagreed "
            "(first at %d over the %s: '%s'/uid %u/parent %d)",
        HM_ISSUE_METADATA_MINIMUM,
        HM_ISSUE_METADATA_MISMATCH_DIVISOR,
        compared,
        written_issues,
        mismatched,
        first,
        reason,
        first >= 0 ? issues[first].name : "",
        first >= 0 ? issues[first].uid : 0U,
        first >= 0 ? issues[first].parent_pid : 0
    );
}

/*
 * One listing feeds five checks. Attribution is switched off (both budgets zero):
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
        CHECK("processes.issue-metadata-matches-a-fresh-read", 0, "out of memory");
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
            /*
             * A guard, not coverage of the `pid-N` fallback that makes it hold:
             * on a live machine proc_name answers for every process the caller
             * can read the rusage of, so this branch never fires and deleting
             * the fallback leaves the check green. Reaching it needs a process
             * that vanishes between the rusage read and the metadata read,
             * which cannot be forced from outside — CLAUDE.md lists the fallback
             * among the accepted gaps.
             */
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
     * the sample. With 64 slots against the hundreds of processes on any live
     * machine the capacity branch fires for most of the listing, so a run that
     * produced no capacity issue at all means that branch stopped being reached
     * — and a reason that is neither constant, or a name running past its array,
     * would reach the report unnoticed.
     *
     * What the metadata on an issue actually says is a separate check:
     * everything asserted here is satisfied by the memset that precedes the
     * `hm_read_process_metadata` call, so deleting that call leaves this green.
     * `processes.issue-metadata-matches-a-fresh-read` is what reads the fields.
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

    hm_check_issue_metadata(issues, written_issues);

    free(samples);
    free(issues);
}

/*
 * The sample the harness reports about itself, against what the harness knows
 * about itself.
 *
 * This is the only check that reads the four fields `hm_read_process_metadata`
 * fills on the sample path. Deleting the call from that path leaves every other
 * `processes.*` check green — the `pid-N` fallback refills the name, which is all
 * `processes.samples-are-well-formed` asks for — and so does replacing the
 * guarded fallback with an unconditional `pid-%d`, which would hand application
 * grouping a machine of processes named after their pids.
 *
 * proc_name truncates a long name, so the name is compared as a prefix of the
 * basename `proc_pidpath` reports rather than as its equal; the path itself is
 * compared whole.
 */
static void hm_check_own_metadata(const HMProcessSample *own, int written, const char *own_path) {
    const char *separator = strrchr(own_path, '/');
    const char *own_name = separator != NULL ? separator + 1 : own_path;
    const size_t reported_length = own == NULL ? 0 : strlen(own->name);

    CHECK(
        "processes.own-sample-carries-metadata",
        own != NULL &&
            reported_length > 0 &&
            strncmp(own_name, own->name, reported_length) == 0 &&
            strcmp(own->executable_path, own_path) == 0 &&
            own->uid == (uint32_t)geteuid() &&
            own->parent_pid == (int32_t)getppid(),
        "expected pid %d in a listing of %d to carry a name starting '%s', the path "
            "'%s', uid %u and parent %d; got name '%s', path '%s', uid %u, parent %d",
        (int)getpid(),
        written,
        own_name,
        own_path,
        (uint32_t)geteuid(),
        (int)getppid(),
        own == NULL ? "(no sample of this process)" : own->name,
        own == NULL ? "" : own->executable_path,
        own == NULL ? 0U : own->uid,
        own == NULL ? 0 : own->parent_pid
    );
}

/*
 * One field of the sample against the range the same field occupied around the
 * listing, and the first field that fell outside its range.
 *
 * A range rather than a tolerance, because the bridge reads its value between
 * the test's two reads: a counter that only grows is bracketed exactly by them,
 * whatever the machine did in between, and no allowance has to be guessed. Only
 * the figures that also fall — the residency ones — carry slack.
 */
typedef struct {
    const char *name;
    uint64_t reported;
    uint64_t low;
    uint64_t high;
} HMBracketedField;

static const char *hm_first_outside_range(
    const HMBracketedField *fields,
    size_t count,
    uint64_t *reported,
    uint64_t *low,
    uint64_t *high
) {
    for (size_t index = 0; index < count; ++index) {
        if (fields[index].reported >= fields[index].low &&
            fields[index].reported <= fields[index].high) {
            continue;
        }
        *reported = fields[index].reported;
        *low = fields[index].low;
        *high = fields[index].high;
        return fields[index].name;
    }
    return NULL;
}

/* Both readings of one process, the pair the ranges above are built from. */
typedef struct {
    struct rusage_info_v6 usage;
    struct proc_taskinfo task;
    int usage_status;
    int task_size;
} HMOwnAnchor;

static HMOwnAnchor hm_read_own_anchor(void) {
    HMOwnAnchor anchor;
    memset(&anchor, 0, sizeof(anchor));
    anchor.usage_status = proc_pid_rusage(
        getpid(),
        RUSAGE_INFO_V6,
        (rusage_info_t *)&anchor.usage
    );
    anchor.task_size = proc_pidinfo(
        getpid(),
        PROC_PIDTASKINFO,
        0,
        &anchor.task,
        (int)sizeof(anchor.task)
    );
    return anchor;
}

static uint64_t hm_lowest(uint64_t first, uint64_t second) {
    return first < second ? first : second;
}

static uint64_t hm_highest(uint64_t first, uint64_t second) {
    return first > second ? first : second;
}

/*
 * How far the three residency figures may sit outside the pair that brackets
 * them. They are the only fields of the sample that both rise and fall, and the
 * listing allocates and touches megabytes between the two readings; measured
 * here, the pair moved 16 KiB — one page — over the listing. The allowance is
 * far below the distance between the fields it separates (5.8 MB resident
 * against 1.5 MB of footprint on this process).
 */
#define HM_OWN_RESIDENCY_SLACK (4ULL * 1024ULL * 1024ULL)

static uint64_t hm_below(uint64_t value, uint64_t slack) {
    return value > slack ? value - slack : 0;
}

/*
 * Every number the bridge copies out of `proc_pid_rusage` and `PROC_PIDTASKINFO`,
 * against the same two calls made by the test around the listing.
 *
 * Nothing else in either harness reads this mapping. `own-sample-carries-metadata`
 * above reads the four metadata fields, and `selftest` bounds the footprint, the
 * *sum* of the two CPU times and two counters against zero — so every "right
 * number in the wrong field" mutation survives all of them: user against system
 * time, disk bytes read against written, `resident_bytes` filled from
 * `ri_phys_footprint`, faults against copy-on-write faults, `thread_count` from
 * `pti_numrunning`, a `started_at` of zero, `pageins` from `ri_interrupt_wkups`.
 * All eight fields reach the report through `DarwinSystemCollector`.
 *
 * Two fields would not separate on an untouched harness — it neither reads nor
 * writes a disk, and it runs one thread — so `hm_check_own_listing` gives it 4 MiB
 * of flushed writes and a parked thread before taking the readings, which is what
 * makes `disk_bytes_read` differ from `disk_bytes_written` and `thread_count`
 * from `running_thread_count`.
 *
 * The two time fields are converted with the bridge's own `hm_mach_time_to_ns`
 * and the four 32-bit counters widened with its own `hm_uint32_counter`, so this
 * check is about which field went where and not about that arithmetic; the
 * arithmetic is `pure.mach-time-matches-uptime-clock` and the `pure.uint32-*`
 * checks.
 */
static void hm_check_own_fields(
    const HMProcessSample *own,
    const HMOwnAnchor *before,
    const HMOwnAnchor *after
) {
    const struct rusage_info_v6 *first = &before->usage;
    const struct rusage_info_v6 *last = &after->usage;
    const struct proc_taskinfo *first_task = &before->task;
    const struct proc_taskinfo *last_task = &after->task;
    const int readable = own != NULL &&
        before->usage_status == 0 &&
        after->usage_status == 0 &&
        before->task_size == (int)sizeof(before->task) &&
        after->task_size == (int)sizeof(after->task);
    if (!readable) {
        CHECK(
            "processes.own-sample-matches-a-fresh-rusage",
            0,
            "no pair of readings to compare against: own sample %d, rusage %d/%d, "
                "task info %d/%d",
            own != NULL,
            before->usage_status,
            after->usage_status,
            before->task_size,
            after->task_size
        );
        return;
    }

    const HMBracketedField fields[] = {
        {"started_at", own->started_at, first->ri_proc_start_abstime, last->ri_proc_start_abstime},
        {
            "user_time_ns",
            own->user_time_ns,
            hm_mach_time_to_ns(first->ri_user_time),
            hm_mach_time_to_ns(last->ri_user_time),
        },
        {
            "system_time_ns",
            own->system_time_ns,
            hm_mach_time_to_ns(first->ri_system_time),
            hm_mach_time_to_ns(last->ri_system_time),
        },
        {
            "package_idle_wakeups",
            own->package_idle_wakeups,
            first->ri_pkg_idle_wkups,
            last->ri_pkg_idle_wkups,
        },
        {
            "interrupt_wakeups",
            own->interrupt_wakeups,
            first->ri_interrupt_wkups,
            last->ri_interrupt_wkups,
        },
        {"pageins", own->pageins, first->ri_pageins, last->ri_pageins},
        {
            "disk_bytes_read",
            own->disk_bytes_read,
            first->ri_diskio_bytesread,
            last->ri_diskio_bytesread,
        },
        {
            "disk_bytes_written",
            own->disk_bytes_written,
            first->ri_diskio_byteswritten,
            last->ri_diskio_byteswritten,
        },
        {
            "logical_writes_bytes",
            own->logical_writes_bytes,
            first->ri_logical_writes,
            last->ri_logical_writes,
        },
        {"instructions", own->instructions, first->ri_instructions, last->ri_instructions},
        {"cycles", own->cycles, first->ri_cycles, last->ri_cycles},
        {"energy_nanojoules", own->energy_nanojoules, first->ri_energy_nj, last->ri_energy_nj},
        {"billed_energy", own->billed_energy, first->ri_billed_energy, last->ri_billed_energy},
        {
            "lifetime_max_physical_footprint_bytes",
            own->lifetime_max_physical_footprint_bytes,
            first->ri_lifetime_max_phys_footprint,
            last->ri_lifetime_max_phys_footprint,
        },
        {
            "wired_bytes",
            own->wired_bytes,
            hm_below(hm_lowest(first->ri_wired_size, last->ri_wired_size), HM_OWN_RESIDENCY_SLACK),
            hm_highest(first->ri_wired_size, last->ri_wired_size) + HM_OWN_RESIDENCY_SLACK,
        },
        {
            "resident_bytes",
            own->resident_bytes,
            hm_below(
                hm_lowest(first->ri_resident_size, last->ri_resident_size),
                HM_OWN_RESIDENCY_SLACK
            ),
            hm_highest(first->ri_resident_size, last->ri_resident_size) + HM_OWN_RESIDENCY_SLACK,
        },
        {
            "physical_footprint_bytes",
            own->physical_footprint_bytes,
            hm_below(
                hm_lowest(first->ri_phys_footprint, last->ri_phys_footprint),
                HM_OWN_RESIDENCY_SLACK
            ),
            hm_highest(first->ri_phys_footprint, last->ri_phys_footprint) + HM_OWN_RESIDENCY_SLACK,
        },
        {
            "faults",
            own->faults,
            hm_uint32_counter(first_task->pti_faults),
            hm_uint32_counter(last_task->pti_faults),
        },
        {
            "copy_on_write_faults",
            own->copy_on_write_faults,
            hm_uint32_counter(first_task->pti_cow_faults),
            hm_uint32_counter(last_task->pti_cow_faults),
        },
        {
            "mach_system_calls",
            own->mach_system_calls,
            hm_uint32_counter(first_task->pti_syscalls_mach),
            hm_uint32_counter(last_task->pti_syscalls_mach),
        },
        {
            "unix_system_calls",
            own->unix_system_calls,
            hm_uint32_counter(first_task->pti_syscalls_unix),
            hm_uint32_counter(last_task->pti_syscalls_unix),
        },
        {
            "context_switches",
            own->context_switches,
            hm_uint32_counter(first_task->pti_csw),
            hm_uint32_counter(last_task->pti_csw),
        },
        {
            "thread_count",
            own->thread_count,
            hm_lowest((uint64_t)first_task->pti_threadnum, (uint64_t)last_task->pti_threadnum),
            hm_highest((uint64_t)first_task->pti_threadnum, (uint64_t)last_task->pti_threadnum),
        },
        {
            "running_thread_count",
            own->running_thread_count,
            hm_lowest((uint64_t)first_task->pti_numrunning, (uint64_t)last_task->pti_numrunning),
            hm_highest((uint64_t)first_task->pti_numrunning, (uint64_t)last_task->pti_numrunning),
        },
    };
    uint64_t reported = 0;
    uint64_t low = 0;
    uint64_t high = 0;
    const char *mismatch = hm_first_outside_range(
        fields,
        sizeof(fields) / sizeof(fields[0]),
        &reported,
        &low,
        &high
    );

    /*
     * The four pairs a transposition would hide in are asserted to differ, so
     * that a green result means the comparison could have separated them: the two
     * CPU times, the two disk directions, the two thread counts and the two
     * residency figures.
     */
    const int separated = last->ri_user_time != last->ri_system_time &&
        last->ri_diskio_bytesread != last->ri_diskio_byteswritten &&
        last_task->pti_threadnum != last_task->pti_numrunning &&
        last->ri_resident_size != last->ri_phys_footprint;

    CHECK(
        "processes.own-sample-matches-a-fresh-rusage",
        mismatch == NULL && separated,
        "expected every field of pid %d's sample within the pair of readings taken "
            "around the listing, got %s reporting %llu against %llu..%llu; the "
            "readings themselves separate user from system time by %llu ticks, read "
            "from written bytes by %llu, threads from running threads by %d and "
            "resident bytes from the footprint by %llu, and each has to be non-zero",
        (int)getpid(),
        mismatch == NULL ? "no mismatch" : mismatch,
        (unsigned long long)reported,
        (unsigned long long)low,
        (unsigned long long)high,
        (unsigned long long)(hm_highest(last->ri_user_time, last->ri_system_time) -
            hm_lowest(last->ri_user_time, last->ri_system_time)),
        (unsigned long long)(hm_highest(last->ri_diskio_bytesread, last->ri_diskio_byteswritten) -
            hm_lowest(last->ri_diskio_bytesread, last->ri_diskio_byteswritten)),
        last_task->pti_threadnum - last_task->pti_numrunning,
        (unsigned long long)(hm_highest(last->ri_resident_size, last->ri_phys_footprint) -
            hm_lowest(last->ri_resident_size, last->ri_phys_footprint))
    );
}

/*
 * How many issues of the rusage branch have to be comparable against a fresh
 * `proc_pidpath`, and how many of them may disagree. The population is the
 * processes of other users — 275 of them here, of which 274 had a readable path —
 * and it is the branch `DarwinSystemCollector` actually takes: its sample array is
 * `MIN_PROCESS_CAPACITY` wide plus headroom, so the capacity branch never fires
 * and every issue it reports comes from here.
 *
 * The minimum is lower than the one `processes.issue-metadata-matches-a-fresh-read`
 * asks for because the population is smaller: an account that owns most of the
 * machine leaves few processes it cannot read the rusage of.
 */
#define HM_RUSAGE_ISSUE_MINIMUM 16
#define HM_RUSAGE_ISSUE_MISMATCH_DIVISOR 16

/*
 * The executable path of an issue the rusage branch produced, against a fresh
 * `proc_pidpath`.
 *
 * The sibling check on the narrow listing cannot see this branch at all: it needs
 * `PROC_PIDTBSDINFO` to build its anchor, and that call is refused for exactly
 * the processes the rusage branch reports — 0 of 275 were readable here. So
 * deleting `hm_read_process_metadata` from the rusage branch of
 * `hm_list_processes` used to leave both harnesses green while emptying the only
 * field of an issue that survives the refusal: proc_name is refused as well, and
 * the uid stays UINT32_MAX by design, but `proc_pidpath` answers for another
 * user's process and the report shows what it says.
 */
static void hm_check_rusage_issue_paths(const HMProcessIssue *issues, int written_issues) {
    int compared = 0;
    int mismatched = 0;
    int first = -1;
    for (int index = 0; index < written_issues; ++index) {
        const HMProcessIssue *issue = &issues[index];
        if (issue->reason != HM_PROCESS_ISSUE_RUSAGE) {
            continue;
        }
        char path[HM_PROCESS_PATH_SIZE];
        memset(path, 0, sizeof(path));
        if (proc_pidpath(issue->pid, path, (uint32_t)sizeof(path)) <= 0) {
            continue;
        }

        ++compared;
        if (strcmp(issue->executable_path, path) != 0) {
            ++mismatched;
            if (first < 0) {
                first = index;
            }
        }
    }

    CHECK(
        "processes.rusage-issue-path-matches-a-fresh-read",
        compared >= HM_RUSAGE_ISSUE_MINIMUM &&
            mismatched * HM_RUSAGE_ISSUE_MISMATCH_DIVISOR <= compared,
        "expected at least %d rusage issues with a readable path and at most a %dth "
            "of them to disagree with it, compared %d of %d issues and %d disagreed "
            "(first at %d, pid %d, reporting '%s')",
        HM_RUSAGE_ISSUE_MINIMUM,
        HM_RUSAGE_ISSUE_MISMATCH_DIVISOR,
        compared,
        written_issues,
        mismatched,
        first,
        first >= 0 ? (int)issues[first].pid : 0,
        first >= 0 ? issues[first].executable_path : ""
    );
}

/*
 * Writes and flushes enough to separate `disk_bytes_read` from
 * `disk_bytes_written`, which are both zero in a harness that has touched no
 * disk. `F_FULLFSYNC` rather than `fsync`, because only that reaches the device;
 * measured, 4 MiB arrive as 4194304 written bytes against 0 or 8192 read.
 */
#define HM_OWN_DISK_BYTES (4 * 1024 * 1024)

static void hm_write_to_disk(void) {
    char path[] = "/tmp/harmon-native-test-io.XXXXXX";
    const int descriptor = mkstemp(path);
    if (descriptor < 0) {
        return;
    }
    unlink(path);
    char *block = (char *)calloc(1, HM_OWN_DISK_BYTES);
    if (block != NULL) {
        memset(block, 'w', HM_OWN_DISK_BYTES);
        if (write(descriptor, block, HM_OWN_DISK_BYTES) == (ssize_t)HM_OWN_DISK_BYTES) {
            fcntl(descriptor, F_FULLFSYNC);
        }
        free(block);
    }
    close(descriptor);
}

/*
 * Parks a second thread for as long as the listing takes, so that
 * `pti_threadnum` (2) and `pti_numrunning` (1) are different numbers while the
 * bridge reads them. It blocks on a pipe rather than sleeping: a sleeper is
 * counted the same way, but a spinner would be running and the two counters would
 * agree again.
 */
typedef struct {
    int wake[2];
    int parked;
} HMParkedThread;

static void *hm_park_thread(void *argument) {
    HMParkedThread *thread = (HMParkedThread *)argument;
    char byte = 0;
    while (read(thread->wake[0], &byte, 1) < 0 && errno == EINTR) {
    }
    return NULL;
}

/*
 * Waits until the kernel stops counting the second thread as running, so that
 * `pti_threadnum` and `pti_numrunning` are two different numbers in both
 * readings. A thread just created reads as running for a moment — measured, in
 * the first of ten rounds and none of the others — and over a range that starts
 * at 2 a `running_thread_count` filled from `pti_threadnum` would pass.
 */
#define HM_PARK_ATTEMPTS 100

static void hm_wait_for_the_park(void) {
    for (int attempt = 0; attempt < HM_PARK_ATTEMPTS; ++attempt) {
        struct proc_taskinfo task;
        memset(&task, 0, sizeof(task));
        if (proc_pidinfo(getpid(), PROC_PIDTASKINFO, 0, &task, (int)sizeof(task)) !=
            (int)sizeof(task)) {
            return;
        }
        if (task.pti_numrunning < task.pti_threadnum) {
            return;
        }
        usleep(1000);
    }
}

/*
 * The full-width listing, and the three checks that read it.
 *
 * Full width so that this process is in it: the 64 slots the narrow listing uses
 * are filled long before a pid this recent. The width is also what makes the
 * issue array the rusage branch's, which is the branch the collector takes.
 */
static void hm_check_own_listing(void) {
    const int capacity = hm_count_processes() + HM_PROCESS_LIST_HEADROOM;
    const int issue_capacity = 256;
    HMProcessSample *samples = (HMProcessSample *)calloc(
        (size_t)capacity,
        sizeof(HMProcessSample)
    );
    HMProcessIssue *issues = (HMProcessIssue *)calloc(
        (size_t)issue_capacity,
        sizeof(HMProcessIssue)
    );
    char own_path[HM_PROCESS_PATH_SIZE];
    memset(own_path, 0, sizeof(own_path));
    const int path_length = proc_pidpath(getpid(), own_path, (uint32_t)sizeof(own_path));
    if (samples == NULL || issues == NULL || path_length <= 0) {
        free(samples);
        free(issues);
        CHECK(
            "processes.own-sample-carries-metadata",
            0,
            "no listing to take: allocation %d, own path %d",
            samples != NULL && issues != NULL,
            path_length
        );
        CHECK("processes.own-sample-matches-a-fresh-rusage", 0, "no listing to take");
        CHECK("processes.rusage-issue-path-matches-a-fresh-read", 0, "no listing to take");
        return;
    }

    hm_write_to_disk();
    HMParkedThread parked = {{-1, -1}, 0};
    pthread_t thread;
    if (pipe(parked.wake) == 0) {
        parked.parked = pthread_create(&thread, NULL, hm_park_thread, &parked) == 0;
    }
    if (parked.parked) {
        hm_wait_for_the_park();
    }

    int written_issues = 0;
    const HMOwnAnchor before = hm_read_own_anchor();
    const int written = hm_list_processes(
        samples,
        capacity,
        issues,
        issue_capacity,
        0,
        0,
        NULL,
        NULL,
        &written_issues
    );
    const HMOwnAnchor after = hm_read_own_anchor();

    const HMProcessSample *own = NULL;
    for (int index = 0; index < written; ++index) {
        if (samples[index].pid == getpid()) {
            own = &samples[index];
            break;
        }
    }

    hm_check_own_metadata(own, written, own_path);
    hm_check_own_fields(own, &before, &after);
    hm_check_rusage_issue_paths(issues, written_issues);

    if (parked.parked) {
        const char wake = 'w';
        while (write(parked.wake[1], &wake, 1) < 0 && errno == EINTR) {
        }
        pthread_join(thread, NULL);
    }
    if (parked.wake[0] >= 0) {
        close(parked.wake[0]);
        close(parked.wake[1]);
    }
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
    const char *mismatch = hm_first_mismatch(
        fields,
        sizeof(fields) / sizeof(fields[0]),
        &reported,
        &anchored
    );

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
 * How far a swap figure may sit from one read moments later. Measured here at
 * zero over 2000 back-to-back pairs of reads, so the allowance is for a machine
 * that swaps while the check runs, not for the reads themselves.
 */
#define HM_SWAP_DRIFT_BYTES (64ULL * 1024ULL * 1024ULL)

static int hm_matches_within_drift(uint64_t reported, uint64_t anchor) {
    const uint64_t difference = reported > anchor
        ? reported - anchor
        : anchor - reported;
    return difference <= HM_SWAP_DRIFT_BYTES;
}

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
    const int swap_matches_anchor = anchor_status == 0 &&
        hm_matches_within_drift(swap.total_bytes, anchor.xsu_total) &&
        hm_matches_within_drift(swap.used_bytes, anchor.xsu_used) &&
        hm_matches_within_drift(swap.available_bytes, anchor.xsu_avail) &&
        swap.encrypted == (anchor.xsu_encrypted ? 1 : 0);

    const uint64_t resident = memory.free_bytes + memory.active_bytes +
        memory.inactive_bytes + memory.wired_bytes;
    CHECK(
        "snapshot.swap-and-virtual-memory-readable",
        swap_status == 0 &&
            swap.total_bytes >= swap.used_bytes &&
            swap.used_bytes + swap.available_bytes == swap.total_bytes &&
            swap_matches_anchor &&
            memory_status == 0 &&
            memory.page_size_bytes > 0 &&
            (memory.page_size_bytes & (memory.page_size_bytes - 1)) == 0 &&
            resident > 0,
        "expected 0 and used + available == total, got %d and %llu + %llu != %llu; "
            "expected each within %llu bytes of vm.swapusage, got %d/matched=%d "
            "against total=%llu used=%llu available=%llu; "
            "expected 0 with a power-of-two page size and non-zero pages, "
            "got %d with page size %llu over %llu bytes",
        swap_status,
        (unsigned long long)swap.used_bytes,
        (unsigned long long)swap.available_bytes,
        (unsigned long long)swap.total_bytes,
        HM_SWAP_DRIFT_BYTES,
        anchor_status,
        swap_matches_anchor,
        (unsigned long long)anchor.xsu_total,
        (unsigned long long)anchor.xsu_used,
        (unsigned long long)anchor.xsu_avail,
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
 * The tolerances are for a cache refresh between the two reads, like the tick
 * ones: measured here, 500 back-to-back pairs never differed at all in any of the
 * seven fields probed — free, compressed, pageins, pageouts, faults, compressions
 * and swapouts. They stay far below the distances between the fields they
 * separate: 394M pageins against 11M pageouts, 63M swapins against 70M swapouts.
 */
#define HM_MEMORY_DRIFT_BYTES (64ULL * 1024ULL * 1024ULL)
#define HM_MEMORY_DRIFT_EVENTS 1000000ULL

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
    const char *mismatch = hm_first_mismatch(
        fields,
        sizeof(fields) / sizeof(fields[0]),
        &reported,
        &anchored
    );

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
 * The anchor walks the registry a second time and reads the keys out explicitly.
 * The device filter is the bridge's own `hm_storage_driver_is_internal`, because
 * the mapping under test is key-to-field and not which device counts; sharing the
 * filter also keeps the two walks over the same set of devices when an external
 * disk is attached.
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
        if (hm_storage_driver_is_internal(driver)) {
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
    const char *mismatch = hm_first_mismatch(
        fields,
        sizeof(fields) / sizeof(fields[0]),
        &reported,
        &anchored
    );

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
 */
#define HM_BATTERY_DRIFT_MINUTES 2

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

    const int32_t minutes_difference = sample.minutes_remaining > minutes_remaining
        ? sample.minutes_remaining - minutes_remaining
        : minutes_remaining - sample.minutes_remaining;

    CHECK(
        "snapshot.battery-matches-a-fresh-read",
        status == 0 &&
            sample.available == available &&
            sample.on_battery == on_battery &&
            sample.charging == charging &&
            sample.percentage == percentage &&
            minutes_difference <= HM_BATTERY_DRIFT_MINUTES,
        "expected available %d, on battery %d, charging %d, %d%% and %d minutes "
            "from a second IOPS read, got status %d with available %d, on battery "
            "%d, charging %d, %d%% and %d minutes",
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
        sample.minutes_remaining
    );
}

void hm_run_kernel_tests(void) {
    hm_check_attribution_self_walk();
    hm_check_attribution_bytes();
    hm_check_attribution_dead_pid();
    hm_check_attribution_vanishing_pid();
    hm_check_attribution_region_limit();
    hm_check_attribution_invalid_arguments();
    hm_check_process_listing();
    hm_check_own_listing();
    hm_check_process_listing_invalid_arguments();
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
