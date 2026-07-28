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
#include <stdio.h>
#include <string.h>

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
