#include "harmon_http.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include "harness.h"

#define HM_HTTP_TEST_TIMEOUT_SECONDS 2L
#define HM_HTTP_TEST_ACCEPT_WAIT_MILLISECONDS 5000
#define HM_HTTP_TEST_URL_SIZE 64

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

static struct sockaddr_in hm_http_test_loopback(int port) {
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_len = sizeof(address);
    address.sin_family = AF_INET;
    address.sin_port = htons((uint16_t)port);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    return address;
}

static int hm_http_test_local_port(int descriptor) {
    struct sockaddr_in address;
    socklen_t length = sizeof(address);
    if (getsockname(descriptor, (struct sockaddr *)&address, &length) != 0) {
        return -1;
    }
    return ntohs(address.sin_port);
}

static int hm_http_test_listener(int *port) {
    const int descriptor = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (descriptor < 0) {
        return -1;
    }
    const struct sockaddr_in address = hm_http_test_loopback(0);
    if (
        bind(descriptor, (const struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(descriptor, 1) != 0 ||
        (*port = hm_http_test_local_port(descriptor)) < 0
    ) {
        const int failure = errno;
        close(descriptor);
        errno = failure;
        return -1;
    }
    return descriptor;
}

static void hm_http_test_url(char *url, int port) {
    snprintf(url, HM_HTTP_TEST_URL_SIZE, "http://127.0.0.1:%d/api/live", port);
}

/*
 * XNU drops a SYN to a port that is bound without listen(), so curl waits out its
 * timeout there (28). The local port of an established connection stays reserved
 * and refuses a new connection instead.
 */
static void hm_check_get_status_refused(void) {
    const char *check = "http.get-status-refused-port-reports-couldnt-connect";
    int listener_port = 0;
    const int listener = hm_http_test_listener(&listener_port);
    if (listener < 0) {
        CHECK(check, 0, "loopback listener failed: %s", strerror(errno));
        return;
    }
    const int client = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    const struct sockaddr_in address = hm_http_test_loopback(listener_port);
    const int port = client < 0 ||
            connect(client, (const struct sockaddr *)&address, sizeof(address)) != 0
        ? -1
        : hm_http_test_local_port(client);
    if (port < 0) {
        CHECK(check, 0, "loopback connection failed: %s", strerror(errno));
        if (client >= 0) {
            close(client);
        }
        close(listener);
        return;
    }

    char url[HM_HTTP_TEST_URL_SIZE];
    hm_http_test_url(url, port);
    HMHttpResult result;
    const int status = hm_http_get_status(url, HM_HTTP_TEST_TIMEOUT_SECONDS, &result);
    CHECK(
        check,
        status == CURLE_COULDNT_CONNECT &&
            result.curl_code == CURLE_COULDNT_CONNECT &&
            result.status_code == 0,
        "expected curl %d on the reserved port %d, got return %d, curl %d, HTTP %ld: %s",
        CURLE_COULDNT_CONNECT,
        port,
        status,
        result.curl_code,
        result.status_code,
        result.error
    );
    close(client);
    close(listener);
}

typedef struct {
    int listener;
    int accepted;
    int failure;
} HMHttpDropper;

static void *hm_http_accept_and_drop(void *argument) {
    HMHttpDropper *dropper = argument;
    struct pollfd ready = {dropper->listener, POLLIN, 0};
    const int polled = poll(&ready, 1, HM_HTTP_TEST_ACCEPT_WAIT_MILLISECONDS);
    if (polled != 1) {
        dropper->failure = polled == 0 ? ETIMEDOUT : errno;
        return NULL;
    }
    const int client = accept(dropper->listener, NULL, NULL);
    if (client < 0) {
        dropper->failure = errno;
        return NULL;
    }
    dropper->accepted = 1;
    close(client);
    return NULL;
}

static void hm_check_get_status_dropped(void) {
    const char *check = "http.get-status-dropped-connection-is-not-refused";
    int port = 0;
    const int listener = hm_http_test_listener(&port);
    if (listener < 0) {
        CHECK(check, 0, "loopback listener failed: %s", strerror(errno));
        return;
    }
    HMHttpDropper dropper = {listener, 0, 0};
    pthread_t thread;
    if (pthread_create(&thread, NULL, hm_http_accept_and_drop, &dropper) != 0) {
        CHECK(check, 0, "pthread_create failed: %s", strerror(errno));
        close(listener);
        return;
    }

    char url[HM_HTTP_TEST_URL_SIZE];
    hm_http_test_url(url, port);
    HMHttpResult result;
    const int status = hm_http_get_status(url, HM_HTTP_TEST_TIMEOUT_SECONDS, &result);
    pthread_join(thread, NULL);
    CHECK(
        check,
        dropper.accepted &&
            status == result.curl_code &&
            result.curl_code != CURLE_OK &&
            result.curl_code != CURLE_COULDNT_CONNECT,
        "expected neither curl %d nor %d after the listener dropped the connection, "
        "got curl %d (%s), accepted %d, helper error %s",
        CURLE_OK,
        CURLE_COULDNT_CONNECT,
        result.curl_code,
        result.error,
        dropper.accepted,
        strerror(dropper.failure)
    );
    close(listener);
}

void hm_run_http_tests(void) {
    hm_check_discard_http_response();
    const int initialized = hm_http_global_init();
    CHECK(
        "http.global-init-succeeds",
        initialized == CURLE_OK,
        "curl_global_init returned %d",
        initialized
    );
    hm_check_get_status_refused();
    hm_check_get_status_dropped();
}
