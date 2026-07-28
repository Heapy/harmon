#include "harmon_native.h"

#include "harness.h"

/*
 * The arithmetic helpers of the bridge. Every one of them is `static inline` and
 * free of side effects, so the checks below are exact equalities rather than
 * plausibility bounds — the only suite in the harness that can afford them.
 */

static void hm_check_saturating_add(void) {
    CHECK(
        "pure.saturating-add-zero",
        hm_saturating_add_u64(0, 0) == 0,
        "expected 0, got %llu",
        (unsigned long long)hm_saturating_add_u64(0, 0)
    );
    CHECK(
        "pure.saturating-add-adds",
        hm_saturating_add_u64(1, 2) == 3,
        "expected 3, got %llu",
        (unsigned long long)hm_saturating_add_u64(1, 2)
    );
    /* The largest sum that is still exact must not be mistaken for an overflow. */
    CHECK(
        "pure.saturating-add-reaches-max",
        hm_saturating_add_u64(UINT64_MAX - 1, 1) == UINT64_MAX,
        "expected %llu, got %llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_saturating_add_u64(UINT64_MAX - 1, 1)
    );
    CHECK(
        "pure.saturating-add-clamps-by-one",
        hm_saturating_add_u64(UINT64_MAX - 1, 2) == UINT64_MAX,
        "expected %llu, got %llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_saturating_add_u64(UINT64_MAX - 1, 2)
    );
    CHECK(
        "pure.saturating-add-clamps-both-max",
        hm_saturating_add_u64(UINT64_MAX, UINT64_MAX) == UINT64_MAX,
        "expected %llu, got %llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_saturating_add_u64(UINT64_MAX, UINT64_MAX)
    );
}

static void hm_check_saturating_multiply(void) {
    /* A zero factor short-circuits before the division that guards the product. */
    CHECK(
        "pure.saturating-multiply-by-zero",
        hm_saturating_multiply_u64(0, UINT64_MAX) == 0 &&
            hm_saturating_multiply_u64(UINT64_MAX, 0) == 0,
        "expected 0 both ways, got %llu and %llu",
        (unsigned long long)hm_saturating_multiply_u64(0, UINT64_MAX),
        (unsigned long long)hm_saturating_multiply_u64(UINT64_MAX, 0)
    );
    CHECK(
        "pure.saturating-multiply-by-one",
        hm_saturating_multiply_u64(1, UINT64_MAX) == UINT64_MAX &&
            hm_saturating_multiply_u64(UINT64_MAX, 1) == UINT64_MAX,
        "expected %llu both ways, got %llu and %llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_saturating_multiply_u64(1, UINT64_MAX),
        (unsigned long long)hm_saturating_multiply_u64(UINT64_MAX, 1)
    );
    /* The largest product that still fits, one below the clamp. */
    CHECK(
        "pure.saturating-multiply-reaches-max",
        hm_saturating_multiply_u64(UINT64_MAX / 2, 2) == UINT64_MAX - 1,
        "expected %llu, got %llu",
        (unsigned long long)(UINT64_MAX - 1),
        (unsigned long long)hm_saturating_multiply_u64(UINT64_MAX / 2, 2)
    );
    /* 2^32 * 2^32 is exactly 2^64: the first product that does not fit. */
    CHECK(
        "pure.saturating-multiply-clamps",
        hm_saturating_multiply_u64(1ULL << 32, 1ULL << 32) == UINT64_MAX,
        "expected %llu, got %llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_saturating_multiply_u64(1ULL << 32, 1ULL << 32)
    );
}

/*
 * The kernel reports its per-process counters as int32_t that has already
 * wrapped, so the conversion must reinterpret the bits rather than sign-extend
 * them: a negative input is a large counter, never a negative one.
 */
static void hm_check_uint32_counter(void) {
    CHECK(
        "pure.uint32-counter-zero",
        hm_uint32_counter(0) == 0,
        "expected 0, got %llu",
        (unsigned long long)hm_uint32_counter(0)
    );
    CHECK(
        "pure.uint32-counter-wraps-minus-one",
        hm_uint32_counter(-1) == 4294967295ULL,
        "expected 4294967295, got %llu",
        (unsigned long long)hm_uint32_counter(-1)
    );
    CHECK(
        "pure.uint32-counter-int32-min",
        hm_uint32_counter(INT32_MIN) == 2147483648ULL,
        "expected 2147483648, got %llu",
        (unsigned long long)hm_uint32_counter(INT32_MIN)
    );
    CHECK(
        "pure.uint32-counter-int32-max",
        hm_uint32_counter(INT32_MAX) == 2147483647ULL,
        "expected 2147483647, got %llu",
        (unsigned long long)hm_uint32_counter(INT32_MAX)
    );
}

static int compare_candidates(
    int32_t left_index,
    uint64_t left_footprint,
    int32_t right_index,
    uint64_t right_footprint
) {
    HMProcessAttributionCandidate left = {left_index, left_footprint};
    HMProcessAttributionCandidate right = {right_index, right_footprint};
    return hm_compare_process_candidates(&left, &right);
}

/*
 * `hm_list_processes` sorts its attribution candidates with this comparator, so
 * the order it imposes decides which processes get their memory attributed at
 * all. qsort is not a stable sort, which is exactly why the comparator breaks
 * ties by index instead of returning 0: equal footprints must still come out in
 * a fixed order, or the tail of the budget would land on a different process on
 * every sample.
 */
static void hm_check_candidate_order(void) {
    CHECK(
        "pure.candidates-order-by-footprint",
        compare_candidates(0, 2048, 1, 1024) < 0 &&
            compare_candidates(1, 1024, 0, 2048) > 0,
        "expected the larger footprint first, got %d and %d",
        compare_candidates(0, 2048, 1, 1024),
        compare_candidates(1, 1024, 0, 2048)
    );
    CHECK(
        "pure.candidates-tie-breaks-by-index",
        compare_candidates(1, 4096, 5, 4096) < 0 &&
            compare_candidates(5, 4096, 1, 4096) > 0 &&
            compare_candidates(3, 4096, 3, 4096) == 0,
        "expected the lower index first, got %d, %d and %d",
        compare_candidates(1, 4096, 5, 4096),
        compare_candidates(5, 4096, 1, 4096),
        compare_candidates(3, 4096, 3, 4096)
    );

    HMProcessAttributionCandidate sorted[] = {
        {0, 1024},
        {1, 8192},
        {2, 0},
        {3, 4096},
    };
    const size_t sorted_count = sizeof(sorted) / sizeof(sorted[0]);
    qsort(
        sorted,
        sorted_count,
        sizeof(sorted[0]),
        hm_compare_process_candidates
    );
    int descends = 1;
    for (size_t index = 1; index < sorted_count; index++) {
        if (sorted[index - 1].physical_footprint_bytes <
            sorted[index].physical_footprint_bytes) {
            descends = 0;
        }
    }
    CHECK(
        "pure.candidates-sort-descends",
        descends && sorted[0].index == 1 && sorted[sorted_count - 1].index == 2,
        "expected indices 1,3,0,2 by footprint, got %d,%d,%d,%d",
        sorted[0].index,
        sorted[1].index,
        sorted[2].index,
        sorted[3].index
    );

    HMProcessAttributionCandidate tied[] = {
        {7, 4096},
        {2, 4096},
        {9, 4096},
        {4, 4096},
    };
    const size_t tied_count = sizeof(tied) / sizeof(tied[0]);
    qsort(tied, tied_count, sizeof(tied[0]), hm_compare_process_candidates);
    int ascends_by_index = 1;
    for (size_t index = 1; index < tied_count; index++) {
        if (tied[index - 1].index >= tied[index].index) {
            ascends_by_index = 0;
        }
    }
    CHECK(
        "pure.candidates-sort-keeps-tie-order",
        ascends_by_index,
        "expected indices 2,4,7,9 on equal footprints, got %d,%d,%d,%d",
        tied[0].index,
        tied[1].index,
        tied[2].index,
        tied[3].index
    );
}

/*
 * Mirrors the conversion the bridge performs, from the same kernel-reported
 * timebase, so the check holds on both an Intel machine (1/1, ticks are already
 * nanoseconds) and Apple Silicon (125/3).
 */
static void hm_check_mach_time(void) {
    mach_timebase_info_data_t timebase = {0, 0};
    if (mach_timebase_info(&timebase) != KERN_SUCCESS || timebase.denom == 0) {
        timebase.numer = 1;
        timebase.denom = 1;
    }

    const uint64_t ticks = 1000000;
    uint64_t expected = ticks;
    if (timebase.numer != timebase.denom) {
        expected = ticks * (uint64_t)timebase.numer / (uint64_t)timebase.denom;
    }

    CHECK(
        "pure.mach-time-matches-timebase",
        hm_mach_time_to_ns(ticks) == expected,
        "expected %llu ns for %llu ticks at %u/%u, got %llu",
        (unsigned long long)expected,
        (unsigned long long)ticks,
        timebase.numer,
        timebase.denom,
        (unsigned long long)hm_mach_time_to_ns(ticks)
    );
    CHECK(
        "pure.mach-time-converts-zero",
        hm_mach_time_to_ns(0) == 0,
        "expected 0, got %llu",
        (unsigned long long)hm_mach_time_to_ns(0)
    );
}

/*
 * The curl write callback of the webhook and Telegram senders. Returning
 * anything other than the full byte count aborts the transfer, so the body has
 * to be counted even though it is thrown away.
 */
static void hm_check_discard_http_response(void) {
    char body[] = "{\"ok\":true}";
    const size_t consumed = hm_discard_http_response(body, 7, 11, NULL);
    CHECK(
        "pure.discard-http-response-consumes-everything",
        consumed == 77 && hm_discard_http_response(body, 4, 0, NULL) == 0,
        "expected 77 and 0, got %zu and %zu",
        consumed,
        hm_discard_http_response(body, 4, 0, NULL)
    );
}

void hm_run_pure_tests(void) {
    hm_check_saturating_add();
    hm_check_saturating_multiply();
    hm_check_uint32_counter();
    hm_check_candidate_order();
    hm_check_mach_time();
    hm_check_discard_http_response();
}
