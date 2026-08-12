#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "harness.h"


int hm_test_failures = 0;
int hm_test_reported = 0;
const char *hm_test_filter = NULL;

typedef struct {
    void (*run)(void);
    const char *prefix;
} HMTestSuite;

static const HMTestSuite hm_test_suites[] = {
    {hm_run_pure_tests, "pure."},
    {hm_run_http_tests, "http."},
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
            hm_test_park_forever();
        } else if (argv[index][0] == '-' || hm_test_filter != NULL) {
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

    if (self_check) {
        hm_test_filter = NULL;
        CHECK(
            "harness.self-check",
            0,
            "deliberate failure that proves the fail branch runs"
        );
    }

    if (hm_test_reported == 0) {
        printf("ok   harness.no-checks-selected\n");
    }

    return hm_test_failures == 0 ? 0 : 1;
}
