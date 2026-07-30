#include "harmon_http.h"

#include "harness.h"

/*
 * The curl write callback of the webhook and Telegram senders. Returning
 * anything other than the full byte count aborts the transfer, so the body has
 * to be counted even though it is thrown away.
 *
 * This used to sit in pure_test.c while every native function lived in one
 * header. Keeping it there after the bridge split would make that otherwise
 * probe-only translation unit depend on libcurl, or tempt us to test a duplicate
 * callback instead of the implementation hm_http_post_json actually installs.
 */
static void hm_check_discard_http_response(void) {
    char body[] = "{\"ok\":true}";
    const size_t consumed = hm_discard_http_response(body, 7, 11, NULL);
    CHECK(
        "http.discard-response-consumes-everything",
        consumed == 77 && hm_discard_http_response(body, 4, 0, NULL) == 0,
        "expected 77 and 0, got %zu and %zu",
        consumed,
        hm_discard_http_response(body, 4, 0, NULL)
    );
}

void hm_run_http_tests(void) {
    hm_check_discard_http_response();
}
