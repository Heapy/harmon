#include "harmon_native.h"

#include <pthread.h>
#include <sys/mman.h>
#include <sys/wait.h>

#include "anchors.h"
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

/*
 * The budget the walk of this process is given. It is the production limit in the
 * ordinary build, and the check is then also the statement that a real address
 * space fits inside it. The sanitized build needs a larger one: its shadow map
 * turns the same process into 163966 regions against the 54 of the plain binary
 * (measured), so under the production limit the walk would report an undercount
 * of the sanitizer rather than anything about the bridge.
 */
#if HM_TEST_SANITIZED
#define HM_SELF_WALK_LIMIT (1 << 22)
#else
#define HM_SELF_WALK_LIMIT HM_ATTRIBUTION_REGION_LIMIT
#endif

static void hm_check_attribution_self_walk(void) {
    uint64_t bytes = 0;
    int32_t regions = 0;
    int consumed = 0;
    const int status = hm_read_compressed_or_paged_out(
        getpid(),
        HM_SELF_WALK_LIMIT,
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
            consumed <= HM_SELF_WALK_LIMIT &&
            consumed >= (int)regions,
        "expected %d < consumed <= %d for %d regions, got %d",
        0,
        HM_SELF_WALK_LIMIT,
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
 * The three lines before that are what keeps a hung run from hanging `./kotlin
 * test` instead of the harness. The child waits to be killed, and `fork` cleared
 * the alarm `main` armed, so a parent that dies on SIGALRM before reaching the
 * `kill` below leaves it running forever — holding a write end of the pipe
 * `NativeHarness.kt` reads until EOF.
 *
 * *Both* standard descriptors have to go, and only measuring says so: the bridge
 * builds every command with `2>&1`, so descriptors 1 and 2 are the same pipe and
 * closing one releases nothing. Measured with this exact shape — a parent that
 * exits while the child pauses, read through `popen` with `2>&1`: closing
 * neither gives no EOF in 10 s, closing stdout alone gives no EOF in 10 s
 * either, closing both gives EOF in 0.02 s. The re-armed alarm keeps the orphan
 * itself bounded by the same timeout, and it is what bounded the hang while the
 * `close` was doing nothing.
 */
static void hm_run_vanishing_child(int ready_descriptor) {
    alarm(HM_TEST_TIMEOUT_SECONDS);
    close(STDOUT_FILENO);
    close(STDERR_FILENO);
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

/*
 * The walk the bridge performs, performed by the test. `stop_at_first_swapped`
 * is what turns it into the probe the candidate scan runs over every process of
 * the account: the scan only needs to know whether the compressor holds anything
 * of a pid, and answering that at the first swapped page rather than at the end
 * of the address space is the difference between a few walks and hundreds. Both
 * callers need the same overflow guard on `pri_address + pri_size`, which is the
 * reason they are one function rather than two.
 */
static HMRegionWalk hm_walk_regions(pid_t pid, int stop_at_first_swapped) {
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
        if (stop_at_first_swapped && walk.swapped_pages > 0) {
            break;
        }
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
    return hm_walk_regions(pid, 1).swapped_pages > 0;
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
    const HMRegionWalk before = hm_walk_regions(pid, 0);
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
    const HMRegionWalk after = hm_walk_regions(pid, 0);
    if (status < 0 || after.regions == 0) {
        return 0;
    }

    result->target = pid;
    result->lowest_pages = hm_lowest(before.swapped_pages, after.swapped_pages);
    result->highest_pages = hm_highest(before.swapped_pages, after.swapped_pages);
    result->resident_pages = hm_lowest(before.resident_pages, after.resident_pages);
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

void hm_run_attribution_tests(void) {
    hm_check_attribution_self_walk();
    hm_check_attribution_bytes();
    hm_check_attribution_dead_pid();
    hm_check_attribution_vanishing_pid();
    hm_check_attribution_region_limit();
    hm_check_attribution_invalid_arguments();
}
