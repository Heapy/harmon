#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "harness.h"

/*
 * Sockets created by the framing and socket suites do not inherit the send and
 * receive timeouts the bridge sets on its own descriptors, so a missed wakeup
 * would hang `./kotlin test` forever. The alarm turns that into a signal the
 * Kotlin bridge reports as an abnormal termination.
 */
#define HM_TEST_TIMEOUT_SECONDS 60

int hm_test_failures = 0;
int hm_test_reported = 0;
const char *hm_test_filter = NULL;

/*
 * The name prefixes each suite reports under, so that a filtered run skips the
 * suites it cannot select instead of running them and swallowing their output.
 * A prefix missing from this table costs a filtered run the suite entirely; the
 * unfiltered run that `./kotlin test` performs is unaffected, and it is the one
 * that compares the reported names against the expected list.
 */
typedef struct {
    void (*run)(void);
    const char *const *prefixes;
} HMTestSuite;

static const char *const hm_pure_prefixes[] = {"pure.", NULL};
static const char *const hm_kernel_prefixes[] = {
    "attribution.",
    "processes.",
    "snapshot.",
    NULL
};
static const char *const hm_framing_prefixes[] = {"framing.", NULL};
static const char *const hm_socket_prefixes[] = {"socket.", NULL};

static const HMTestSuite hm_test_suites[] = {
    {hm_run_pure_tests, hm_pure_prefixes},
    {hm_run_kernel_tests, hm_kernel_prefixes},
    {hm_run_framing_tests, hm_framing_prefixes},
    {hm_run_socket_tests, hm_socket_prefixes},
};

static int hm_suite_selected(const HMTestSuite *suite) {
    for (const char *const *prefix = suite->prefixes; *prefix != NULL; prefix++) {
        if (hm_test_suite_selected(*prefix)) {
            return 1;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    int self_check = 0;
    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--self-check") == 0) {
            self_check = 1;
        } else if (strncmp(argv[index], "--", 2) == 0 || hm_test_filter != NULL) {
            /*
             * An unknown flag taken as a name filter would match nothing, print
             * nothing and exit 0 — a mistyped `--self-check` would read as a
             * clean run of a harness that checked nothing at all.
             */
            fprintf(stderr, "usage: %s [--self-check] [name-prefix]\n", argv[0]);
            return 2;
        } else {
            hm_test_filter = argv[index];
        }
    }

    alarm(HM_TEST_TIMEOUT_SECONDS);

    for (size_t index = 0; index < sizeof(hm_test_suites) / sizeof(*hm_test_suites); index++) {
        if (hm_suite_selected(&hm_test_suites[index])) {
            hm_test_suites[index].run();
        }
    }

    /*
     * Without this flag the `fail` branch never executes, and a silently broken
     * `CHECK` would report an all-green run forever.
     */
    if (self_check) {
        CHECK(
            "harness.self-check",
            0,
            "deliberate failure that proves the fail branch runs"
        );
    }

    /*
     * The bridge reads an empty output as a harness that died before reporting
     * anything, so a filter that selected nothing has to say so out loud. The
     * same line, for the same reason, is what `selftest` prints.
     */
    if (hm_test_reported == 0) {
        printf("ok   harness.no-checks-selected\n");
    }

    return hm_test_failures == 0 ? 0 : 1;
}
