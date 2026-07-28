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
const char *hm_test_filter = NULL;

int main(int argc, char **argv) {
    int self_check = 0;
    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--self-check") == 0) {
            self_check = 1;
        } else if (hm_test_filter == NULL) {
            hm_test_filter = argv[index];
        } else {
            fprintf(stderr, "usage: %s [--self-check] [name-prefix]\n", argv[0]);
            return 2;
        }
    }

    alarm(HM_TEST_TIMEOUT_SECONDS);

    hm_run_pure_tests();
    hm_run_kernel_tests();
    hm_run_framing_tests();
    hm_run_socket_tests();

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

    return hm_test_failures == 0 ? 0 : 1;
}
