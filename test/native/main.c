#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "harness.h"

/*
 * Sockets created by the framing and socket suites do not inherit the send and
 * receive timeouts the bridge sets on its own descriptors, so a missed wakeup
 * would hang `./kotlin test` forever. The alarm — `HM_TEST_TIMEOUT_SECONDS` in
 * `harness.h`, shared with every child the suites fork — turns that into a signal
 * the Kotlin bridge reports as an abnormal termination.
 */

int hm_test_failures = 0;
int hm_test_reported = 0;
const char *hm_test_filter = NULL;

/*
 * The name prefix each suite reports under, so that a filtered run skips the
 * suites it cannot select instead of running them and swallowing their output.
 * A suite missing from this table is not run at all; a prefix that disagrees with
 * what the suite reports costs a filtered run the suite entirely, and the
 * unfiltered run that `./kotlin test` performs is the one that compares the
 * reported names against the expected list.
 */
typedef struct {
    void (*run)(void);
    const char *prefix;
} HMTestSuite;

static const HMTestSuite hm_test_suites[] = {
    {hm_run_pure_tests, "pure."},
    {hm_run_attribution_tests, "attribution."},
    {hm_run_processes_tests, "processes."},
    {hm_run_snapshot_tests, "snapshot."},
    {hm_run_framing_tests, "framing."},
    {hm_run_socket_tests, "socket."},
};

int main(int argc, char **argv) {
    int self_check = 0;
    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--self-check") == 0) {
            self_check = 1;
        } else if (strcmp(argv[index], "--park") == 0) {
            /*
             * Runs no check and never returns: this is the harness as a child of itself, which
             * `processes.exec-path-survives-a-deleted-binary` execs a deletable copy of. Handled
             * before anything is reported, so a copy started by hand prints nothing either.
             */
            hm_test_park_forever();
        } else if (argv[index][0] == '-' || hm_test_filter != NULL) {
            /* Why a dash is a usage error: CLAUDE.md, the protocol paragraph. */
            fprintf(stderr, "usage: %s [--self-check|--park] [name-prefix]\n", argv[0]);
            return 2;
        } else {
            hm_test_filter = argv[index];
        }
    }

    alarm(HM_TEST_TIMEOUT_SECONDS);

    for (size_t index = 0; index < sizeof(hm_test_suites) / sizeof(*hm_test_suites); index++) {
        if (hm_test_suite_selected(hm_test_suites[index].prefix)) {
            hm_test_suites[index].run();
        }
    }

    /*
     * Why the filter is dropped before the deliberate failure: CLAUDE.md, the
     * protocol paragraph. Nothing after this point reads the filter.
     */
    if (self_check) {
        hm_test_filter = NULL;
        CHECK(
            "harness.self-check",
            0,
            "deliberate failure that proves the fail branch runs"
        );
    }

    /* Why an empty selection still prints a line: CLAUDE.md, the protocol paragraph. */
    if (hm_test_reported == 0) {
        printf("ok   harness.no-checks-selected\n");
    }

    return hm_test_failures == 0 ? 0 : 1;
}
