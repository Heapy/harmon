#ifndef HARMON_TEST_HARNESS_H
#define HARMON_TEST_HARNESS_H


#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

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

static inline size_t hm_test_heap_bytes_in_use(void) {
#if HM_TEST_SANITIZED
    return __sanitizer_get_current_allocated_bytes();
#else
    malloc_statistics_t statistics;
    malloc_zone_statistics(malloc_default_zone(), &statistics);
    return statistics.size_in_use;
#endif
}

#define HM_TEST_TIMEOUT_SECONDS 60

static inline void hm_test_park_forever(void) {
    alarm(HM_TEST_TIMEOUT_SECONDS);
    close(STDOUT_FILENO);
    close(STDERR_FILENO);
    for (;;) {
        pause();
    }
}

extern int hm_test_failures;
extern int hm_test_reported;
extern const char *hm_test_filter;

void hm_run_pure_tests(void);
void hm_run_http_tests(void);
void hm_run_attribution_tests(void);
void hm_run_processes_tests(void);
void hm_run_snapshot_tests(void);
void hm_run_framing_tests(void);
void hm_run_socket_tests(void);

static inline int hm_test_selected(const char *name) {
    return hm_test_filter == NULL ||
        strncmp(name, hm_test_filter, strlen(hm_test_filter)) == 0;
}

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

#define CHECK(name, condition, ...) \
    hm_test_report((name), (condition) ? 1 : 0, __VA_ARGS__)

#endif
