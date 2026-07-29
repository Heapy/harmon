#ifndef HARMON_TEST_HARNESS_H
#define HARMON_TEST_HARNESS_H

/*
 * Build scheme, fixed once so that later suites slot in without touching the
 * entry point:
 *
 *   - `main.c` owns `main`, the harness state, the prefix filter, the alarm and
 *     the `--self-check` flag; it calls every suite declared below in order;
 *   - every `*_test.c` defines exactly one `hm_run_*_tests(void)` suite and has
 *     no `main` of its own;
 *   - `scripts/test-native.sh` regenerates `harmon_native.h` from the `.def` and
 *     compiles every C source under `test/native` into one binary on every run.
 *
 * The output protocol this harness shares with `selftest` is described once, in
 * the "How the native layer is tested" section of CLAUDE.md. In short:
 *
 *   ok   suite.check-name
 *   fail suite.check-name: expected 3, got 4
 */

#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

/*
 * Whether this build is the sanitized one `scripts/test-native.sh --sanitize`
 * produces. Five checks need to know, and each says why where it branches: the
 * shadow map of AddressSanitizer turns the address space into six figures of
 * regions, and its allocator replaces the one `malloc_zone_statistics` reports on
 * and never hands a freed block straight back.
 */
#if defined(__has_feature)
#  if __has_feature(address_sanitizer)
#    define HM_TEST_SANITIZED 1
#  endif
#endif
#ifndef HM_TEST_SANITIZED
#  define HM_TEST_SANITIZED 0
#endif

#if HM_TEST_SANITIZED
#include <sanitizer/allocator_interface.h>
#else
#include <malloc/malloc.h>
#endif

/*
 * Bytes held in live heap allocations, which is how both leak checks measure a
 * `free` that is not there. Each allocator is asked with its own accounting call;
 * measured, both report growth 0 over 64 allocations of 64 KiB that are released
 * and the full 4 MiB over 64 that are not.
 */
static inline size_t hm_test_heap_bytes_in_use(void) {
#if HM_TEST_SANITIZED
    return __sanitizer_get_current_allocated_bytes();
#else
    malloc_statistics_t statistics;
    malloc_zone_statistics(malloc_default_zone(), &statistics);
    return statistics.size_in_use;
#endif
}

/*
 * How long a whole run may take before `main` dies on SIGALRM. It lives here
 * rather than in `main.c` because every child the suites fork has to bound
 * itself by the same number: `fork` clears the parent's alarm, so a child that
 * outlives a parent killed by one would hold the harness's output pipe open and
 * hang the reader in `NativeHarness.kt` instead of hanging the harness. Such a
 * child closes stdout *and* stderr, which are the same pipe under the `2>&1` the
 * bridge runs the harness with.
 */
#define HM_TEST_TIMEOUT_SECONDS 60

extern int hm_test_failures;
extern int hm_test_reported;
extern const char *hm_test_filter;

void hm_run_pure_tests(void);
void hm_run_kernel_tests(void);
void hm_run_framing_tests(void);
void hm_run_socket_tests(void);

static inline int hm_test_selected(const char *name) {
    return hm_test_filter == NULL ||
        strncmp(name, hm_test_filter, strlen(hm_test_filter)) == 0;
}

/*
 * Whether a suite reporting names under `prefix` can contribute anything to the
 * current filter, and so whether it is worth running at all. The filter gates
 * execution and not just reporting: an unselected suite would otherwise still
 * fork children, burn CPU and open sockets, and a crash inside it would still
 * take down a run that never wanted it.
 *
 * Either string may be the shorter one — `socket.` selects the whole socket
 * suite, `socket.accept` selects two of its checks — so the comparison runs over
 * the length of the shorter.
 */
static inline int hm_test_suite_selected(const char *prefix) {
    if (hm_test_filter == NULL) {
        return 1;
    }
    const size_t prefix_length = strlen(prefix);
    const size_t filter_length = strlen(hm_test_filter);
    const size_t shared = prefix_length < filter_length ? prefix_length : filter_length;
    return strncmp(prefix, hm_test_filter, shared) == 0;
}

__attribute__((format(printf, 3, 4)))
static inline void hm_test_report(
    const char *name,
    int passed,
    const char *format,
    ...
) {
    if (!hm_test_selected(name)) {
        return;
    }
    hm_test_reported++;
    if (passed) {
        printf("ok   %s\n", name);
    } else {
        hm_test_failures++;
        printf("fail %s: ", name);
        va_list arguments;
        va_start(arguments, format);
        vprintf(format, arguments);
        va_end(arguments);
        printf("\n");
    }
    fflush(stdout);
}

/*
 * The detail is printed only for a failure, so it may reference values that are
 * meaningless when the condition holds.
 */
#define CHECK(name, condition, ...) \
    hm_test_report((name), (condition) ? 1 : 0, __VA_ARGS__)

#endif
