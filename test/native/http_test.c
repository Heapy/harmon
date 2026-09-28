#include "harmon_http.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include "harness.h"

#define HM_HTTP_TEST_TIMEOUT_SECONDS 2L
#define HM_HTTP_TEST_PEER_WAIT_SECONDS 5
#define HM_HTTP_TEST_URL_SIZE 64
#define HM_HTTP_TEST_REQUEST_SIZE 4096
#define HM_HTTP_TEST_OK_WITHOUT_MARKER \
    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n" \
    "Connection: close\r\n\r\n{}"
#define HM_HTTP_TEST_OK_WITH_MARKER \
    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n" \
    "X-Harmon-Live: 1\r\nConnection: close\r\n\r\n{}"

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
    const char *response;
    int accepted;
    int failure;
} HMHttpPeer;

static int hm_http_test_read_request(int client) {
    const struct timeval timeout = {HM_HTTP_TEST_PEER_WAIT_SECONDS, 0};
    if (setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
        return errno;
    }
    char request[HM_HTTP_TEST_REQUEST_SIZE];
    size_t received = 0;
    while (received < sizeof(request) - 1U) {
        const ssize_t chunk = recv(client, request + received, sizeof(request) - 1U - received, 0);
        if (chunk <= 0) {
            return chunk == 0 ? ECONNRESET : errno;
        }
        received += (size_t)chunk;
        request[received] = '\0';
        if (strstr(request, "\r\n\r\n") != NULL) {
            return 0;
        }
    }
    return EMSGSIZE;
}

static int hm_http_test_send_all(int client, const char *response) {
    size_t sent = 0;
    const size_t length = strlen(response);
    while (sent < length) {
        const ssize_t chunk = send(client, response + sent, length - sent, 0);
        if (chunk <= 0) {
            return errno;
        }
        sent += (size_t)chunk;
    }
    return 0;
}

static void *hm_http_test_serve_once(void *argument) {
    HMHttpPeer *peer = argument;
    struct pollfd ready = {peer->listener, POLLIN, 0};
    const int polled = poll(&ready, 1, HM_HTTP_TEST_PEER_WAIT_SECONDS * 1000);
    if (polled != 1) {
        peer->failure = polled == 0 ? ETIMEDOUT : errno;
        return NULL;
    }
    const int client = accept(peer->listener, NULL, NULL);
    if (client < 0) {
        peer->failure = errno;
        return NULL;
    }
    peer->accepted = 1;
    if (peer->response != NULL) {
        peer->failure = hm_http_test_read_request(client);
        if (peer->failure == 0) {
            peer->failure = hm_http_test_send_all(client, peer->response);
        }
    }
    close(client);
    return NULL;
}

static int hm_http_test_get_from_peer(HMHttpPeer *peer, HMHttpResult *result) {
    memset(result, 0, sizeof(*result));
    int port = 0;
    peer->listener = hm_http_test_listener(&port);
    if (peer->listener < 0) {
        snprintf(result->error, sizeof(result->error), "loopback listener failed: %s", strerror(errno));
        return -1;
    }
    pthread_t thread;
    if (pthread_create(&thread, NULL, hm_http_test_serve_once, peer) != 0) {
        snprintf(result->error, sizeof(result->error), "pthread_create failed: %s", strerror(errno));
        close(peer->listener);
        return -1;
    }
    char url[HM_HTTP_TEST_URL_SIZE];
    hm_http_test_url(url, port);
    const int status = hm_http_get_status(url, HM_HTTP_TEST_TIMEOUT_SECONDS, result);
    pthread_join(thread, NULL);
    close(peer->listener);
    return status;
}

static void hm_check_get_status_dropped(void) {
    const char *check = "http.get-status-dropped-connection-is-not-refused";
    HMHttpPeer peer = {-1, NULL, 0, 0};
    HMHttpResult result;
    const int status = hm_http_test_get_from_peer(&peer, &result);
    CHECK(
        check,
        peer.accepted &&
            status == result.curl_code &&
            result.curl_code != CURLE_OK &&
            result.curl_code != CURLE_COULDNT_CONNECT,
        "expected neither curl %d nor %d after the listener dropped the connection, "
        "got return %d, curl %d (%s), accepted %d, helper error %s",
        CURLE_OK,
        CURLE_COULDNT_CONNECT,
        status,
        result.curl_code,
        result.error,
        peer.accepted,
        strerror(peer.failure)
    );
}

static void hm_check_get_status_harmon_live_header(
    const char *check,
    const char *response,
    int expected
) {
    HMHttpPeer peer = {-1, response, 0, 0};
    HMHttpResult result;
    const int status = hm_http_test_get_from_peer(&peer, &result);
    CHECK(
        check,
        status == 0 &&
            peer.failure == 0 &&
            result.curl_code == CURLE_OK &&
            result.status_code == 200 &&
            result.harmon_live_header == expected,
        "expected HTTP 200 with harmon_live_header %d, got return %d, curl %d, HTTP %ld, "
        "harmon_live_header %d (%s), helper error %s",
        expected,
        status,
        result.curl_code,
        result.status_code,
        result.harmon_live_header,
        result.error,
        strerror(peer.failure)
    );
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
    hm_check_get_status_harmon_live_header(
        "http.get-status-reports-a-missing-harmon-live-header",
        HM_HTTP_TEST_OK_WITHOUT_MARKER,
        0
    );
    hm_check_get_status_harmon_live_header(
        "http.get-status-reports-the-harmon-live-header",
        HM_HTTP_TEST_OK_WITH_MARKER,
        1
    );
}
