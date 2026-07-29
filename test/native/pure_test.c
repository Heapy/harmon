#include "harmon_native.h"

#include "anchors.h"
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

/*
 * Sizing of the intermediate PID list. The property that matters is structural:
 * whatever the caller reserved is already in `output_capacity`, so taking the
 * larger of the two arguments keeps the list from ever being the narrower one —
 * no constant here has to mirror anything in Kotlin. The checks below pin the
 * two ends (a count that failed, a count that dwarfs the arrays) and both
 * saturation points.
 */
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
    /*
     * The ceiling is the one place the invariant yields: a byte size beyond it
     * would overflow the int proc_listallpids takes. HM_MAX_PROCESS_LIST minus
     * the headroom is the last count that still gets its full headroom.
     */
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

/*
 * How far the two clock readings below may sit apart. They are taken on
 * consecutive lines and measured 125 ns apart here, but the pair straddles
 * whatever the scheduler decides to do between them, and a single preemption
 * outlasts any millisecond bound: an earlier revision allowed 5 ms, which is
 * half a macOS quantum, and would have failed for a reason indistinguishable
 * from the regression below.
 *
 * Two seconds is chosen against that regression instead of against the clocks. A
 * transposed timebase converts by 3/125 rather than 125/3, i.e. 1736 times short,
 * so it is off by days on an uptime of days and still off by four seconds on an
 * uptime of four. Only a machine that booted two seconds ago could hide it.
 */
#define HM_UPTIME_TOLERANCE_NS 2000000000ULL

/*
 * Checked against a second clock rather than against the same arithmetic the
 * bridge performs. Recomputing `ticks * numer / denom` here would agree with
 * `hm_mach_time_to_ns` for any change that kept the two in step — a swapped
 * numerator and denominator included — whereas CLOCK_UPTIME_RAW counts the same
 * interval the bridge is converting to and is derived independently. On this
 * machine (timebase 125/3) the two readings came out identical to within 125 ns;
 * a transposed timebase would be 40 times off an uptime measured in days.
 *
 * The conversion of zero stays a separate check: it is the one input the bridge
 * short-circuits whatever the timebase says.
 */
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

/*
 * The scale of `hm_monotonic_time_ns`, which is the divisor of every rate and CPU
 * percentage the collector computes: it turns two readings into an interval, and
 * an interval a thousand times too short turns 3 % of a core into 3000 %.
 *
 * Nothing else checks it. The check above anchors `hm_mach_time_to_ns`, a
 * different function on a different clock, and `binding.monotonic-clock-advances`
 * in `selftest` asserts only that two readings come back in order — which they do
 * whatever the seconds are multiplied by. Scaling `tv_sec` by 1e6 instead of 1e9
 * passed every check in both harnesses.
 *
 * `clock_gettime_nsec_np` reads the same clock through a different entry point
 * and does the arithmetic inside libsystem, so it is an anchor rather than the
 * same expression written twice. The two lines are consecutive; the allowance is
 * the one the check above uses, and for the same reason — it is chosen against
 * the regression, not against the clocks. A wrong scale is off by the whole
 * uptime, seconds after boot.
 */
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

/*
 * The constants the rest of the harness reads out of the bridge instead of
 * spelling out, and the two the Kotlin side reads the same way.
 *
 * Every other check that touches one of them takes it from the same `#define` it
 * is testing, so shrinking the constant moves both sides at once and nothing
 * turns red. `HM_PROCESS_NAME_SIZE` at 16 was green across every other check while
 * truncating every process name on the machine to fifteen characters — the names
 * application grouping keys off — because `processes.own-sample-carries-metadata`
 * compares the name as a prefix and the metadata check declares its own buffer as
 * `char name[HM_PROCESS_NAME_SIZE]`. `HM_MAX_JSON_FRAME_SIZE` at 1 MiB was green
 * for the same reason: the framing checks build their frames from the constant.
 *
 * So the values are written out here, once, as the contract they are. The two
 * issue reasons are a contract with `DarwinSystemCollector.toReason()`, which
 * compares `reason` against `HM_PROCESS_ISSUE_CAPACITY` and treats everything
 * else as the rusage branch; the sizing constants are the ones
 * `hm_process_list_capacity` applies. A deliberate change to any of them changes
 * this line too, which is the point: it is the one place where the change is
 * visible rather than implied.
 *
 * `HM_MAX_PROCESS_LIST` is here for exactly that reason and was missing for a
 * while: `pure.list-capacity-clamps-at-maximum` builds all four of its
 * expectations out of the same `#define` it is testing, so a ceiling lowered to
 * the point of truncating the PID list on a busy machine moved both sides at once
 * and stayed green.
 */
static void hm_check_constants(void) {
    CHECK(
        "pure.constants-are-pinned",
        HM_PROCESS_NAME_SIZE == 128 &&
            HM_PROCESS_PATH_SIZE == PROC_PIDPATHINFO_SIZE &&
            HM_MAX_JSON_FRAME_SIZE == 32U * 1024U * 1024U &&
            HM_ATTRIBUTION_REGION_LIMIT == 8192 &&
            HM_PROCESS_LIST_HEADROOM == 256 &&
            HM_MIN_PROCESS_LIST == 512 &&
            HM_MAX_PROCESS_LIST == 1048576 &&
            HM_PROCESS_ISSUE_RUSAGE == 1 &&
            HM_PROCESS_ISSUE_CAPACITY == 2,
        "expected 128/%d/33554432/8192/256/512/1048576/1/2, "
            "got %d/%d/%u/%d/%d/%d/%d/%d/%d",
        PROC_PIDPATHINFO_SIZE,
        HM_PROCESS_NAME_SIZE,
        HM_PROCESS_PATH_SIZE,
        HM_MAX_JSON_FRAME_SIZE,
        HM_ATTRIBUTION_REGION_LIMIT,
        HM_PROCESS_LIST_HEADROOM,
        HM_MIN_PROCESS_LIST,
        HM_MAX_PROCESS_LIST,
        HM_PROCESS_ISSUE_RUSAGE,
        HM_PROCESS_ISSUE_CAPACITY
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
    hm_check_discard_http_response();
}
