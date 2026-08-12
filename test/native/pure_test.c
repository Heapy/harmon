#include "harmon_probe.h"

#include "anchors.h"
#include "harness.h"


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
    CHECK(
        "pure.saturating-multiply-reaches-max",
        hm_saturating_multiply_u64(UINT64_MAX / 2, 2) == UINT64_MAX - 1,
        "expected %llu, got %llu",
        (unsigned long long)(UINT64_MAX - 1),
        (unsigned long long)hm_saturating_multiply_u64(UINT64_MAX / 2, 2)
    );
    CHECK(
        "pure.saturating-multiply-clamps",
        hm_saturating_multiply_u64(1ULL << 32, 1ULL << 32) == UINT64_MAX,
        "expected %llu, got %llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_saturating_multiply_u64(1ULL << 32, 1ULL << 32)
    );
}

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

static void hm_check_process_list_capacity(void) {
    const int headroom = HM_PROCESS_LIST_HEADROOM;
    CHECK(
        "pure.list-capacity-ignores-failed-count",
        hm_process_list_capacity(-1, 1024) == 1024 + headroom &&
            hm_process_list_capacity(0, 1024) == 1024 + headroom,
        "expected %d for a failed and a zero count, got %d and %d",
        1024 + headroom,
        hm_process_list_capacity(-1, 1024),
        hm_process_list_capacity(0, 1024)
    );
    CHECK(
        "pure.list-capacity-uses-output-when-count-is-lower",
        hm_process_list_capacity(700, 1024) == 1024 + headroom,
        "expected %d, got %d",
        1024 + headroom,
        hm_process_list_capacity(700, 1024)
    );
    CHECK(
        "pure.list-capacity-uses-count-when-it-is-higher",
        hm_process_list_capacity(4096, 1024) == 4096 + headroom,
        "expected %d, got %d",
        4096 + headroom,
        hm_process_list_capacity(4096, 1024)
    );
    const int last_exact = HM_MAX_PROCESS_LIST - headroom;
    CHECK(
        "pure.list-capacity-clamps-at-maximum",
        hm_process_list_capacity(last_exact, 1) == HM_MAX_PROCESS_LIST &&
            hm_process_list_capacity(last_exact + 1, 1) == HM_MAX_PROCESS_LIST &&
            hm_process_list_capacity(INT32_MAX, 1) == HM_MAX_PROCESS_LIST &&
            hm_process_list_capacity(1, INT32_MAX) == HM_MAX_PROCESS_LIST,
        "expected %d for all four, got %d, %d, %d and %d",
        HM_MAX_PROCESS_LIST,
        hm_process_list_capacity(last_exact, 1),
        hm_process_list_capacity(last_exact + 1, 1),
        hm_process_list_capacity(INT32_MAX, 1),
        hm_process_list_capacity(1, INT32_MAX)
    );
    CHECK(
        "pure.list-capacity-holds-the-minimum",
        hm_process_list_capacity(8, 16) == HM_MIN_PROCESS_LIST &&
            hm_process_list_capacity(-1, 1) == HM_MIN_PROCESS_LIST,
        "expected %d twice, got %d and %d",
        HM_MIN_PROCESS_LIST,
        hm_process_list_capacity(8, 16),
        hm_process_list_capacity(-1, 1)
    );
}

static int hm_compare_candidates(
    int32_t left_index,
    uint64_t left_footprint,
    int32_t right_index,
    uint64_t right_footprint
) {
    HMProcessAttributionCandidate left = {left_index, left_footprint};
    HMProcessAttributionCandidate right = {right_index, right_footprint};
    return hm_compare_process_candidates(&left, &right);
}

static void hm_check_candidate_order(void) {
    CHECK(
        "pure.candidates-order-by-footprint",
        hm_compare_candidates(0, 2048, 1, 1024) < 0 &&
            hm_compare_candidates(1, 1024, 0, 2048) > 0,
        "expected the larger footprint first, got %d and %d",
        hm_compare_candidates(0, 2048, 1, 1024),
        hm_compare_candidates(1, 1024, 0, 2048)
    );
    CHECK(
        "pure.candidates-tie-breaks-by-index",
        hm_compare_candidates(1, 4096, 5, 4096) < 0 &&
            hm_compare_candidates(5, 4096, 1, 4096) > 0 &&
            hm_compare_candidates(3, 4096, 3, 4096) == 0,
        "expected the lower index first, got %d, %d and %d",
        hm_compare_candidates(1, 4096, 5, 4096),
        hm_compare_candidates(5, 4096, 1, 4096),
        hm_compare_candidates(3, 4096, 3, 4096)
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

#define HM_UPTIME_TOLERANCE_NS 2000000000ULL

static void hm_check_mach_time(void) {
    mach_timebase_info_data_t timebase = {0, 0};
    if (mach_timebase_info(&timebase) != KERN_SUCCESS) {
        timebase.numer = 0;
        timebase.denom = 0;
    }

    const uint64_t converted = hm_mach_time_to_ns(mach_absolute_time());
    const uint64_t uptime = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);

    CHECK(
        "pure.mach-time-matches-uptime-clock",
        uptime >= converted && uptime - converted < HM_UPTIME_TOLERANCE_NS,
        "expected the converted mach time within %llu ns of CLOCK_UPTIME_RAW at "
            "timebase %u/%u, got %llu against %llu",
        (unsigned long long)HM_UPTIME_TOLERANCE_NS,
        timebase.numer,
        timebase.denom,
        (unsigned long long)converted,
        (unsigned long long)uptime
    );
    CHECK(
        "pure.mach-time-converts-zero",
        hm_mach_time_to_ns(0) == 0,
        "expected 0, got %llu",
        (unsigned long long)hm_mach_time_to_ns(0)
    );
}

static void hm_check_monotonic_time(void) {
    const uint64_t reported = hm_monotonic_time_ns();
    const uint64_t anchor = clock_gettime_nsec_np(CLOCK_MONOTONIC);
    const uint64_t difference = hm_absolute_difference(reported, anchor);

    CHECK(
        "pure.monotonic-time-matches-the-posix-clock",
        reported > 0 && anchor > 0 && difference < HM_UPTIME_TOLERANCE_NS,
        "expected the monotonic clock within %llu ns of clock_gettime_nsec_np, got "
            "%llu against %llu, %llu ns apart",
        (unsigned long long)HM_UPTIME_TOLERANCE_NS,
        (unsigned long long)reported,
        (unsigned long long)anchor,
        (unsigned long long)difference
    );
}

static void hm_check_constants(void) {
    CHECK(
        "pure.constants-are-pinned",
        HM_PROCESS_NAME_SIZE == 128 &&
            HM_PROCESS_PATH_SIZE == PROC_PIDPATHINFO_SIZE &&
            HM_ATTRIBUTION_REGION_LIMIT == 8192 &&
            HM_PROCESS_LIST_HEADROOM == 256 &&
            HM_MIN_PROCESS_LIST == 512 &&
            HM_MAX_PROCESS_LIST == 1048576 &&
            HM_PROCESS_ISSUE_RUSAGE == 1 &&
            HM_PROCESS_ISSUE_CAPACITY == 2,
        "expected 128/%d/8192/256/512/1048576/1/2, "
            "got %d/%d/%d/%d/%d/%d/%d/%d",
        PROC_PIDPATHINFO_SIZE,
        HM_PROCESS_NAME_SIZE,
        HM_PROCESS_PATH_SIZE,
        HM_ATTRIBUTION_REGION_LIMIT,
        HM_PROCESS_LIST_HEADROOM,
        HM_MIN_PROCESS_LIST,
        HM_MAX_PROCESS_LIST,
        HM_PROCESS_ISSUE_RUSAGE,
        HM_PROCESS_ISSUE_CAPACITY
    );
}

static void hm_check_anchor_arithmetic(void) {
    CHECK(
        "pure.anchor-absolute-difference",
        hm_absolute_difference(7, 3) == 4 && hm_absolute_difference(3, 7) == 4 &&
            hm_absolute_difference(5, 5) == 0 &&
            hm_absolute_difference(UINT64_MAX, 0) == UINT64_MAX,
        "expected 4/4/0/%llu, got %llu/%llu/%llu/%llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_absolute_difference(7, 3),
        (unsigned long long)hm_absolute_difference(3, 7),
        (unsigned long long)hm_absolute_difference(5, 5),
        (unsigned long long)hm_absolute_difference(UINT64_MAX, 0)
    );
    CHECK(
        "pure.anchor-below-floors-at-zero",
        hm_below(10, 4) == 6 && hm_below(4, 4) == 0 && hm_below(4, 10) == 0 &&
            hm_below(0, 1) == 0 && hm_below(UINT64_MAX, 1) == UINT64_MAX - 1,
        "expected 6/0/0/0/%llu, got %llu/%llu/%llu/%llu/%llu",
        (unsigned long long)(UINT64_MAX - 1),
        (unsigned long long)hm_below(10, 4),
        (unsigned long long)hm_below(4, 4),
        (unsigned long long)hm_below(4, 10),
        (unsigned long long)hm_below(0, 1),
        (unsigned long long)hm_below(UINT64_MAX, 1)
    );
    CHECK(
        "pure.anchor-lowest-and-highest",
        hm_lowest(3, 9) == 3 && hm_lowest(9, 3) == 3 && hm_lowest(4, 4) == 4 &&
            hm_lowest(0, UINT64_MAX) == 0 && hm_highest(3, 9) == 9 &&
            hm_highest(9, 3) == 9 && hm_highest(4, 4) == 4 &&
            hm_highest(0, UINT64_MAX) == UINT64_MAX,
        "expected 3/3/4/0 lowest and 9/9/4/%llu highest, got %llu/%llu/%llu/%llu "
            "and %llu/%llu/%llu/%llu",
        (unsigned long long)UINT64_MAX,
        (unsigned long long)hm_lowest(3, 9),
        (unsigned long long)hm_lowest(9, 3),
        (unsigned long long)hm_lowest(4, 4),
        (unsigned long long)hm_lowest(0, UINT64_MAX),
        (unsigned long long)hm_highest(3, 9),
        (unsigned long long)hm_highest(9, 3),
        (unsigned long long)hm_highest(4, 4),
        (unsigned long long)hm_highest(0, UINT64_MAX)
    );
    CHECK(
        "pure.anchor-lowest-and-highest-int",
        hm_lowest_int(3, 9) == 3 && hm_lowest_int(9, 3) == 3 &&
            hm_lowest_int(4, 4) == 4 && hm_lowest_int(-1, 0) == -1 &&
            hm_highest_int(3, 9) == 9 && hm_highest_int(9, 3) == 9 &&
            hm_highest_int(4, 4) == 4 && hm_highest_int(-1, 0) == 0,
        "expected 3/3/4/-1 lowest and 9/9/4/0 highest, got %d/%d/%d/%d and %d/%d/%d/%d",
        hm_lowest_int(3, 9),
        hm_lowest_int(9, 3),
        hm_lowest_int(4, 4),
        hm_lowest_int(-1, 0),
        hm_highest_int(3, 9),
        hm_highest_int(9, 3),
        hm_highest_int(4, 4),
        hm_highest_int(-1, 0)
    );
}

static void hm_check_anchor_tables(void) {
    const HMAnchoredField anchored[] = {
        {"agrees-exactly", 4096, 4096, 0},
        {"at-the-tolerance", 4096, 4196, 100},
        {"past-the-tolerance", 4096, 4197, 100},
        {"further-still", 4096, 8192, 100},
    };
    uint64_t reported = 0;
    uint64_t anchor = 0;
    const char *mismatch = HM_FIRST_MISMATCH(anchored, &reported, &anchor);
    CHECK(
        "pure.anchor-first-mismatch-names-the-first-outlier",
        mismatch != NULL && strcmp(mismatch, "past-the-tolerance") == 0 &&
            reported == 4096 && anchor == 4197,
        "expected past-the-tolerance reporting 4096 against 4197, got %s reporting "
            "%llu against %llu",
        mismatch == NULL ? "no mismatch" : mismatch,
        (unsigned long long)reported,
        (unsigned long long)anchor
    );

    const HMAnchoredField agreeing[] = {
        {"agrees-exactly", 4096, 4096, 0},
        {"below-the-anchor", 4096, 4196, 100},
        {"above-the-anchor", 4196, 4096, 100},
    };
    reported = 0;
    anchor = 0;
    const char *no_mismatch = HM_FIRST_MISMATCH(agreeing, &reported, &anchor);
    CHECK(
        "pure.anchor-first-mismatch-passes-a-table-within-tolerance",
        no_mismatch == NULL,
        "expected no mismatch, got %s reporting %llu against %llu",
        no_mismatch == NULL ? "none" : no_mismatch,
        (unsigned long long)reported,
        (unsigned long long)anchor
    );

    const HMBracketedField bracketed[] = {
        {"a-degenerate-range", 4096, 4096, 4096},
        {"at-the-low-edge", 1024, 1024, 8192},
        {"below-the-low-edge", 1023, 1024, 8192},
        {"above-the-high-edge", 8193, 1024, 8192},
    };
    uint64_t low = 0;
    uint64_t high = 0;
    reported = 0;
    const char *outside = HM_FIRST_OUTSIDE_RANGE(bracketed, &reported, &low, &high);
    CHECK(
        "pure.anchor-first-outside-range-names-the-first-outlier",
        outside != NULL && strcmp(outside, "below-the-low-edge") == 0 &&
            reported == 1023 && low == 1024 && high == 8192,
        "expected below-the-low-edge reporting 1023 against 1024..8192, got %s "
            "reporting %llu against %llu..%llu",
        outside == NULL ? "nothing outside" : outside,
        (unsigned long long)reported,
        (unsigned long long)low,
        (unsigned long long)high
    );

    const HMBracketedField inside[] = {
        {"at-the-low-edge", 1024, 1024, 8192},
        {"between-the-edges", 4096, 1024, 8192},
        {"at-the-high-edge", 8192, 1024, 8192},
    };
    reported = 0;
    low = 0;
    high = 0;
    const char *nothing_outside =
        HM_FIRST_OUTSIDE_RANGE(inside, &reported, &low, &high);
    CHECK(
        "pure.anchor-first-outside-range-passes-a-bracketed-table",
        nothing_outside == NULL,
        "expected nothing outside its range, got %s reporting %llu against %llu..%llu",
        nothing_outside == NULL ? "none" : nothing_outside,
        (unsigned long long)reported,
        (unsigned long long)low,
        (unsigned long long)high
    );
}

void hm_run_pure_tests(void) {
    hm_check_constants();
    hm_check_saturating_add();
    hm_check_saturating_multiply();
    hm_check_uint32_counter();
    hm_check_process_list_capacity();
    hm_check_candidate_order();
    hm_check_mach_time();
    hm_check_monotonic_time();
    hm_check_anchor_arithmetic();
    hm_check_anchor_tables();
}
