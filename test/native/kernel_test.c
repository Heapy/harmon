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

void hm_run_kernel_tests(void) {
    hm_check_attribution_self_walk();
    hm_check_attribution_dead_pid();
    hm_check_attribution_region_limit();
    hm_check_attribution_invalid_arguments();
}
