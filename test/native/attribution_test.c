#include "harmon_probe.h"

#include <pthread.h>
#include <sys/mman.h>
#include <sys/wait.h>

#include "anchors.h"
#include "harness.h"


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

#define HM_VANISHING_REGIONS 20000
#define HM_VANISHING_REGION_LIMIT (HM_VANISHING_REGIONS * 2)
#define HM_VANISHING_DELAY_MICROSECONDS 1500
#define HM_VANISHING_ATTEMPTS 6

static void hm_spin_microseconds(uint64_t microseconds) {
    const uint64_t deadline = hm_monotonic_time_ns() + (microseconds * 1000ULL);
    while (hm_monotonic_time_ns() < deadline) {
    }
}

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

    kill(child, SIGKILL);
    int wait_status = 0;
    while (waitpid(child, &wait_status, 0) < 0 && errno == EINTR) {
    }
    return armed ? 0 : -1;
}

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

#define HM_ANCHOR_REGION_LIMIT 1024
#define HM_ANCHOR_ATTEMPTS 8

typedef struct {
    uint64_t swapped_pages;
    uint64_t resident_pages;
    int32_t regions;
} HMRegionWalk;

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

    if (result.target == 0 && result.carriers == 0) {
        hm_sandwich_walk(getpid(), &result);
    }
    return result;
}

static void hm_check_attribution_bytes(void) {
    const HMAnchoredWalk walk = hm_anchored_walk();
    const uint64_t page_size = (uint64_t)getpagesize();

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
