#include "harmon_native.h"

#include "harness.h"

void hm_run_pure_tests(void) {
    CHECK(
        "pure.saturating-add-adds",
        hm_saturating_add_u64(1, 2) == 3,
        "expected 3, got %llu",
        (unsigned long long)hm_saturating_add_u64(1, 2)
    );
}
