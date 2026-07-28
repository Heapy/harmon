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
 * Output protocol, shared with `selftest` and parsed by the Kotlin bridge:
 *
 *   ok   suite.check-name
 *   fail suite.check-name: expected 3, got 4
 */

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

extern int hm_test_failures;
extern const char *hm_test_filter;

void hm_run_pure_tests(void);
void hm_run_kernel_tests(void);
void hm_run_framing_tests(void);
void hm_run_socket_tests(void);

static inline int hm_test_selected(const char *name) {
    return hm_test_filter == NULL ||
        strncmp(name, hm_test_filter, strlen(hm_test_filter)) == 0;
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
