#include "harmon_native.h"

#include <pthread.h>
#include <signal.h>

#include "harness.h"

/*
 * The wire framing of the collector protocol: four big-endian length bytes
 * followed by exactly that many bytes of JSON. Every message in both directions
 * goes through this pair of functions, so a framing bug is a protocol bug on
 * every message, and the interesting cases are the ones a peer can provoke —
 * a length that lies, a payload that stops halfway, a frame too large for one
 * write.
 *
 * Every socket here comes from `socketpair`, which does not inherit the send and
 * receive timeouts `hm_set_socket_options` puts on the bridge's own descriptors,
 * so the tests set them explicitly. `hm_send_all` and `hm_receive_all` retry only
 * on EINTR, which turns a timeout into a failed check instead of a `./kotlin
 * test` that never returns; the alarm in `main.c` is the second line of defence.
 * MSG_DONTWAIT is not an alternative — measured on this machine, a unix-domain
 * `send` ignores it and blocks anyway.
 */

#define HM_TEST_SOCKET_TIMEOUT_SECONDS 5
#define HM_TEST_LARGE_SOCKET_BUFFER (1 << 20)
#define HM_TEST_SMALL_SOCKET_BUFFER 4096
#define HM_TEST_LEAK_ITERATIONS 64
#define HM_TEST_LEAK_PAYLOAD_BYTES (64 * 1024)
#define HM_TEST_LEAK_TOLERANCE_BYTES (64 * 1024)
#define HM_TEST_PARTIAL_WRITE_BYTES (64 * 1024)
#define HM_TEST_PROBE_BYTES (16 * 1024)

/*
 * A pair with explicit timeouts, and optionally with a buffer size of its own:
 * a large one lets a whole frame sit in the socket with no reader attached, a
 * small one keeps the sender waiting on a reader that has fallen behind.
 */
static int hm_test_socket_pair(int pair[2], int buffer_bytes) {
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) {
        return -1;
    }
    struct timeval timeout = {
        .tv_sec = HM_TEST_SOCKET_TIMEOUT_SECONDS,
        .tv_usec = 0,
    };
    for (int end = 0; end < 2; end++) {
        int failed =
            setsockopt(
                pair[end],
                SOL_SOCKET,
                SO_SNDTIMEO,
                &timeout,
                (socklen_t)sizeof(timeout)
            ) != 0 ||
            setsockopt(
                pair[end],
                SOL_SOCKET,
                SO_RCVTIMEO,
                &timeout,
                (socklen_t)sizeof(timeout)
            ) != 0;
        if (!failed && buffer_bytes > 0) {
            failed =
                setsockopt(
                    pair[end],
                    SOL_SOCKET,
                    SO_SNDBUF,
                    &buffer_bytes,
                    (socklen_t)sizeof(buffer_bytes)
                ) != 0 ||
                setsockopt(
                    pair[end],
                    SOL_SOCKET,
                    SO_RCVBUF,
                    &buffer_bytes,
                    (socklen_t)sizeof(buffer_bytes)
                ) != 0;
        }
        if (failed) {
            const int saved_errno = errno;
            close(pair[0]);
            close(pair[1]);
            errno = saved_errno;
            return -1;
        }
    }
    return 0;
}

/*
 * The declared length is a parameter of its own, because half of the checks
 * below need a header that lies about what follows it.
 */
static size_t hm_build_frame(
    uint8_t *buffer,
    uint32_t declared_length,
    const void *payload,
    size_t payload_bytes
) {
    const uint32_t network_length = htonl(declared_length);
    memcpy(buffer, &network_length, sizeof(network_length));
    if (payload_bytes > 0) {
        memcpy(buffer + sizeof(network_length), payload, payload_bytes);
    }
    return sizeof(network_length) + payload_bytes;
}

typedef struct {
    char *json;
    uint32_t size;
    int failure;
} HMFrameOutcome;

/*
 * Feeds bytes to a receiver verbatim and closes the writing end first, so a
 * receive that wants more than was written ends in a clean end of stream rather
 * than in the socket timeout. Every rejection the reader can be driven into — a
 * short header, a length it will not accept, a payload that never arrives in
 * full — is expressible as one call.
 */
static HMFrameOutcome hm_receive_bytes(
    const void *bytes,
    size_t size,
    uint32_t maximum_size,
    int buffer_bytes
) {
    HMFrameOutcome outcome = {NULL, 0, 0};
    int pair[2];
    if (hm_test_socket_pair(pair, buffer_bytes) != 0) {
        outcome.failure = errno;
        return outcome;
    }
    if (size > 0 && hm_send_all(pair[0], bytes, size) != 0) {
        outcome.failure = errno;
        close(pair[0]);
        close(pair[1]);
        return outcome;
    }
    close(pair[0]);

    errno = 0;
    outcome.json = hm_receive_json_frame(pair[1], maximum_size, &outcome.size);
    outcome.failure = errno;
    close(pair[1]);
    return outcome;
}

static void hm_check_send_rejects_bad_payload(void) {
    int pair[2];
    if (hm_test_socket_pair(pair, 0) != 0) {
        CHECK("framing.send-rejects-null", 0, "socketpair failed: %s", strerror(errno));
        CHECK("framing.send-rejects-empty", 0, "socketpair failed: %s", strerror(errno));
        CHECK("framing.send-rejects-oversized", 0, "socketpair failed: %s", strerror(errno));
        return;
    }

    errno = 0;
    const int null_status = hm_send_json_frame(pair[0], NULL);
    const int null_failure = errno;
    CHECK(
        "framing.send-rejects-null",
        null_status == -1 && null_failure == EINVAL,
        "expected -1/EINVAL, got %d/%s",
        null_status,
        strerror(null_failure)
    );

    /*
     * An empty payload is not a frame the other side would accept: the reader
     * rejects a zero length outright, so a sender that let one through would
     * leave the peer waiting for a message that never comes.
     */
    errno = 0;
    const int empty_status = hm_send_json_frame(pair[0], "");
    const int empty_failure = errno;
    CHECK(
        "framing.send-rejects-empty",
        empty_status == -1 && empty_failure == EMSGSIZE,
        "expected -1/EMSGSIZE, got %d/%s",
        empty_status,
        strerror(empty_failure)
    );

    /*
     * The same cap the reader applies, on the writing side. The receiver's half
     * is covered three ways above; a sender that let an oversized frame out
     * would have it rejected by the peer as a protocol error instead of failing
     * where the payload was built.
     */
    char *oversized = (char *)malloc((size_t)HM_MAX_JSON_FRAME_SIZE + 2U);
    if (oversized == NULL) {
        CHECK("framing.send-rejects-oversized", 0, "out of memory");
    } else {
        memset(oversized, 'a', (size_t)HM_MAX_JSON_FRAME_SIZE + 1U);
        oversized[HM_MAX_JSON_FRAME_SIZE + 1U] = '\0';
        errno = 0;
        const int oversized_status = hm_send_json_frame(pair[0], oversized);
        const int oversized_failure = errno;
        CHECK(
            "framing.send-rejects-oversized",
            oversized_status == -1 && oversized_failure == EMSGSIZE,
            "expected -1/EMSGSIZE for %u bytes, got %d/%s",
            HM_MAX_JSON_FRAME_SIZE + 1U,
            oversized_status,
            strerror(oversized_failure)
        );
        free(oversized);
    }

    close(pair[0]);
    close(pair[1]);
}

static void hm_check_round_trip(void) {
    int pair[2];
    if (hm_test_socket_pair(pair, 0) != 0) {
        CHECK("framing.round-trips-a-frame", 0, "socketpair failed: %s", strerror(errno));
        CHECK("framing.receive-accepts-a-null-size", 0, "socketpair failed: %s", strerror(errno));
        return;
    }

    const char *request = "{\"command\":\"snapshot\",\"protocol\":1}";
    const int sent = hm_send_json_frame(pair[0], request);

    uint32_t size = 0;
    errno = 0;
    char *received = hm_receive_json_frame(pair[1], HM_MAX_JSON_FRAME_SIZE, &size);
    const int failure = errno;

    CHECK(
        "framing.round-trips-a-frame",
        sent == 0 &&
            received != NULL &&
            size == (uint32_t)strlen(request) &&
            strcmp(received, request) == 0,
        "expected 0 and %zu bytes of %s, got %d and %u bytes of %s",
        strlen(request),
        request,
        sent,
        size,
        received == NULL ? strerror(failure) : received
    );

    free(received);

    /*
     * The size is an optional output, and a caller that already knows the length
     * from the payload passes NULL for it. A guard written the wrong way round
     * crashes there rather than here.
     */
    const int sent_again = hm_send_json_frame(pair[0], request);
    errno = 0;
    char *sized_elsewhere = hm_receive_json_frame(pair[1], HM_MAX_JSON_FRAME_SIZE, NULL);
    const int null_size_failure = errno;
    CHECK(
        "framing.receive-accepts-a-null-size",
        sent_again == 0 &&
            sized_elsewhere != NULL &&
            strcmp(sized_elsewhere, request) == 0,
        "expected 0 and %s without an output size, got %d and %s",
        request,
        sent_again,
        sized_elsewhere == NULL ? strerror(null_size_failure) : sized_elsewhere
    );
    free(sized_elsewhere);

    close(pair[0]);
    close(pair[1]);
}

/*
 * Three ways a declared length is refused. The absolute cap protects the reader
 * from a peer that asks it to allocate 4 GiB; `maximum_size` is the caller's own,
 * tighter limit; a zero length would produce an empty message that means nothing.
 *
 * The cap is exercised with `maximum_size` at UINT32_MAX so that the cap is the
 * only clause that can refuse the frame. Passing the cap itself — which is what
 * the one caller in `CollectorSocket.kt` does, and what this check used to do —
 * lets `length > maximum_size` do the refusing, and deleting
 * `length > HM_MAX_JSON_FRAME_SIZE` from the bridge then leaves both this check
 * and the one below it green.
 */
static void hm_check_receive_rejects_lengths(void) {
    uint8_t payload[16];
    memset(payload, 'a', sizeof(payload));
    uint8_t frame[sizeof(uint32_t) + sizeof(payload)];

    size_t size = hm_build_frame(frame, HM_MAX_JSON_FRAME_SIZE + 1U, NULL, 0);
    HMFrameOutcome oversized = hm_receive_bytes(frame, size, UINT32_MAX, 0);
    CHECK(
        "framing.receive-rejects-oversized-length",
        oversized.json == NULL && oversized.failure == EMSGSIZE,
        "expected NULL/EMSGSIZE for a declared %u bytes, got %s/%s",
        HM_MAX_JSON_FRAME_SIZE + 1U,
        oversized.json == NULL ? "NULL" : "a frame",
        strerror(oversized.failure)
    );
    free(oversized.json);

    size = hm_build_frame(frame, (uint32_t)sizeof(payload), payload, sizeof(payload));
    HMFrameOutcome above_maximum = hm_receive_bytes(frame, size, 8, 0);
    CHECK(
        "framing.receive-rejects-length-above-maximum",
        above_maximum.json == NULL && above_maximum.failure == EMSGSIZE,
        "expected NULL/EMSGSIZE for %zu bytes against a maximum of 8, got %s/%s",
        sizeof(payload),
        above_maximum.json == NULL ? "NULL" : "a frame",
        strerror(above_maximum.failure)
    );
    free(above_maximum.json);

    size = hm_build_frame(frame, 0, NULL, 0);
    HMFrameOutcome zero = hm_receive_bytes(frame, size, HM_MAX_JSON_FRAME_SIZE, 0);
    CHECK(
        "framing.receive-rejects-zero-length",
        zero.json == NULL && zero.failure == EMSGSIZE,
        "expected NULL/EMSGSIZE for a declared 0 bytes, got %s/%s",
        zero.json == NULL ? "NULL" : "a frame",
        strerror(zero.failure)
    );
    free(zero.json);
}

/*
 * The payload becomes a C string, so a NUL inside it would truncate the JSON
 * silently and hand the parser a prefix of what the peer sent.
 *
 * Two payloads, because the scan is over a length and the last byte is where a
 * length is got wrong: a frame ending in NUL is rejected by `memchr(json, 0,
 * length)` and accepted by the same call over `length - 1`, which is otherwise a
 * mutation neither harness notices.
 */
static void hm_check_receive_rejects_embedded_nul(void) {
    uint8_t payload[16];
    memset(payload, 'a', sizeof(payload));
    payload[sizeof(payload) / 2] = '\0';

    uint8_t frame[sizeof(uint32_t) + sizeof(payload)];
    size_t size = hm_build_frame(frame, (uint32_t)sizeof(payload), payload, sizeof(payload));
    HMFrameOutcome inside = hm_receive_bytes(frame, size, HM_MAX_JSON_FRAME_SIZE, 0);

    memset(payload, 'a', sizeof(payload));
    payload[sizeof(payload) - 1] = '\0';
    size = hm_build_frame(frame, (uint32_t)sizeof(payload), payload, sizeof(payload));
    HMFrameOutcome trailing = hm_receive_bytes(frame, size, HM_MAX_JSON_FRAME_SIZE, 0);

    CHECK(
        "framing.receive-rejects-embedded-nul",
        inside.json == NULL && inside.failure == EILSEQ && inside.size == 0 &&
            trailing.json == NULL && trailing.failure == EILSEQ && trailing.size == 0,
        "expected NULL/EILSEQ and no size from both, got %s/%s and %u for a NUL in "
            "the middle, %s/%s and %u for one as the last byte",
        inside.json == NULL ? "NULL" : "a frame",
        strerror(inside.failure),
        inside.size,
        trailing.json == NULL ? "NULL" : "a frame",
        strerror(trailing.failure),
        trailing.size
    );
    free(inside.json);
    free(trailing.json);
}

/*
 * Rejects the same frame `HM_TEST_LEAK_ITERATIONS` times, so that the heap
 * measurement around it sees the same allocation made and dropped that many
 * times. `size` is what reaches the reader, which is how a payload is truncated:
 * the header still declares the full length, so the allocation happens either
 * way and only the release differs.
 */
static int hm_reject_repeatedly(
    const uint8_t *frame,
    size_t size,
    int expected_failure,
    int *observed_failure
) {
    for (int iteration = 0; iteration < HM_TEST_LEAK_ITERATIONS; iteration++) {
        HMFrameOutcome outcome = hm_receive_bytes(
            frame,
            size,
            HM_MAX_JSON_FRAME_SIZE,
            HM_TEST_LARGE_SOCKET_BUFFER
        );
        if (outcome.json != NULL || outcome.failure != expected_failure) {
            *observed_failure = outcome.json != NULL ? 0 : outcome.failure;
            free(outcome.json);
            return 0;
        }
    }
    return 1;
}

/*
 * A rejected frame has already been allocated by the time it is rejected, and
 * nothing in the return value says whether it was released. Losing that `free`
 * would leak the whole frame on every malformed message a peer sends — the
 * cheapest denial of service the protocol has to offer — while every check above
 * stays green.
 *
 * Both rejections that happen after the allocation are measured, not just one:
 * the embedded NUL, and the payload that stops halfway. They are separate `free`
 * calls in the bridge, and the truncated frame is the cheaper of the two for a
 * hostile peer to send — four bytes of header and a disconnect.
 *
 * `malloc_zone_statistics` accounts for this exactly rather than approximately:
 * measured on this machine, 64 allocations of 64 KiB that are freed move
 * `size_in_use` by 0 bytes, and 64 that are not move it by 5 MiB. The tolerance
 * is a sixty-fourth of the leak it is meant to catch.
 */
static void hm_check_receive_frees_rejected_frame(void) {
    const size_t payload_bytes = HM_TEST_LEAK_PAYLOAD_BYTES;
    uint8_t *frame = (uint8_t *)malloc(sizeof(uint32_t) + payload_bytes);
    uint8_t *payload = (uint8_t *)malloc(payload_bytes);
    if (frame == NULL || payload == NULL) {
        free(frame);
        free(payload);
        CHECK("framing.receive-frees-rejected-frame", 0, "out of memory");
        return;
    }
    memset(payload, 'a', payload_bytes);
    payload[payload_bytes / 2] = '\0';
    const size_t size =
        hm_build_frame(frame, (uint32_t)payload_bytes, payload, payload_bytes);
    const size_t truncated_size = sizeof(uint32_t) + 1024U;

    /* One warm-up run of each keeps first-touch allocations out of the measurement. */
    HMFrameOutcome warm_up = hm_receive_bytes(
        frame,
        size,
        HM_MAX_JSON_FRAME_SIZE,
        HM_TEST_LARGE_SOCKET_BUFFER
    );
    free(warm_up.json);
    HMFrameOutcome truncated_warm_up = hm_receive_bytes(
        frame,
        truncated_size,
        HM_MAX_JSON_FRAME_SIZE,
        HM_TEST_LARGE_SOCKET_BUFFER
    );
    free(truncated_warm_up.json);

    int embedded_nul_failure = 0;
    int truncated_failure = 0;
    const size_t before = hm_test_heap_bytes_in_use();
    const int embedded_nul_rejected =
        hm_reject_repeatedly(frame, size, EILSEQ, &embedded_nul_failure);
    const size_t midpoint = hm_test_heap_bytes_in_use();
    const int truncated_rejected =
        hm_reject_repeatedly(frame, truncated_size, ECONNRESET, &truncated_failure);
    const long long embedded_nul_growth =
        (long long)midpoint - (long long)before;
    const long long truncated_growth =
        (long long)hm_test_heap_bytes_in_use() - (long long)midpoint;

    CHECK(
        "framing.receive-frees-rejected-frame",
        embedded_nul_rejected &&
            truncated_rejected &&
            embedded_nul_growth < HM_TEST_LEAK_TOLERANCE_BYTES &&
            truncated_growth < HM_TEST_LEAK_TOLERANCE_BYTES,
        "expected the heap to grow by less than %d bytes over %d rejected frames "
            "of %zu bytes each way, grew by %lld over the embedded NUL (rejected: "
            "%d, %s) and by %lld over the truncated payload (rejected: %d, %s)",
        HM_TEST_LEAK_TOLERANCE_BYTES,
        HM_TEST_LEAK_ITERATIONS,
        payload_bytes,
        embedded_nul_growth,
        embedded_nul_rejected,
        embedded_nul_rejected ? "EILSEQ" : strerror(embedded_nul_failure),
        truncated_growth,
        truncated_rejected,
        truncated_rejected ? "ECONNRESET" : strerror(truncated_failure)
    );

    free(frame);
    free(payload);
}

/*
 * The byte after the payload. What `hm_receive_json_frame` returns is used as a
 * C string by every caller, and the only thing that makes it one is the
 * `json[length] = '\0'` at the end of the function: the allocation is
 * `length + 1` bytes and nothing else writes the last of them. Dropping that line
 * leaves the string terminated by whatever the allocator happened to hand over,
 * which is a zeroed page most of the time — so the check frees a block of exactly
 * the size the reader is about to ask for, filled with non-zero bytes, and
 * asserts that the reader got that block back. Measured here: at 64 KiB the very
 * next `malloc` of the same size returns the freed block with the dirt intact,
 * over 40 runs of the harness including 18 under full CPU load. Without the reuse
 * the assertion would be about zeroed memory, so it is asserted rather than hoped
 * for.
 *
 * The sanitized build cannot ask for the reuse: a freed block goes to the
 * quarantine of AddressSanitizer and the next allocation is a fresh one behind a
 * redzone. It does not need to — the missing terminator is a one-byte write past
 * the end of that allocation, which is the sanitizer's own subject, and it
 * reports `heap-buffer-overflow harmon_native.h:494 in hm_receive_json_frame`
 * before this check gets a chance to look at anything. So the reuse is required
 * of the plain build only, and the two builds together cover the line from both
 * sides: the dirt says the byte was written, the redzone says it was written
 * inside the allocation.
 */
static void hm_check_receive_terminates_payload(void) {
    const size_t payload_bytes = HM_TEST_LEAK_PAYLOAD_BYTES;
    uint8_t *frame = (uint8_t *)malloc(sizeof(uint32_t) + payload_bytes);
    uint8_t *payload = (uint8_t *)malloc(payload_bytes);
    if (frame == NULL || payload == NULL) {
        free(frame);
        free(payload);
        CHECK("framing.receive-terminates-the-payload", 0, "out of memory");
        return;
    }
    memset(payload, 'a', payload_bytes);
    const size_t size =
        hm_build_frame(frame, (uint32_t)payload_bytes, payload, payload_bytes);

    int pair[2];
    if (hm_test_socket_pair(pair, HM_TEST_LARGE_SOCKET_BUFFER) != 0) {
        free(frame);
        free(payload);
        CHECK(
            "framing.receive-terminates-the-payload",
            0,
            "socketpair failed: %s",
            strerror(errno)
        );
        return;
    }
    const int sent = hm_send_all(pair[0], frame, size);
    close(pair[0]);

    char *dirt = (char *)malloc(payload_bytes + 1U);
    const char *dirtied_block = dirt;
    if (dirt != NULL) {
        memset(dirt, 0xFF, payload_bytes + 1U);
        free(dirt);
    }

    uint32_t received_size = 0;
    errno = 0;
    char *received = hm_receive_json_frame(pair[1], HM_MAX_JSON_FRAME_SIZE, &received_size);
    const int failure = errno;
    close(pair[1]);

    const int reused = received != NULL && received == dirtied_block;
    const size_t length = received == NULL ? 0 : strlen(received);
    CHECK(
        "framing.receive-terminates-the-payload",
        sent == 0 &&
            received != NULL &&
            (reused || HM_TEST_SANITIZED) &&
            received_size == (uint32_t)payload_bytes &&
            received[payload_bytes] == '\0' &&
            length == payload_bytes,
        "expected %zu bytes terminated in the dirtied block, got send %d, %s, "
            "size %u, strlen %zu, reused block %d",
        payload_bytes,
        sent,
        received == NULL ? strerror(failure) : "a frame",
        received_size,
        length,
        reused
    );

    free(received);
    free(frame);
    free(payload);
}

/*
 * A connection that dies mid-frame must look like a broken connection, never
 * like a short message: the reader has no way to tell a truncated payload from a
 * complete one except by counting, so a partial frame reaching the parser would
 * be indistinguishable from a peer sending garbage.
 */
static void hm_check_receive_rejects_truncated_frames(void) {
    const uint8_t half_header[2] = {0x00, 0x00};
    HMFrameOutcome header = hm_receive_bytes(
        half_header,
        sizeof(half_header),
        HM_MAX_JSON_FRAME_SIZE,
        0
    );
    CHECK(
        "framing.receive-rejects-truncated-header",
        header.json == NULL && header.failure == ECONNRESET,
        "expected NULL/ECONNRESET after %zu of 4 header bytes, got %s/%s",
        sizeof(half_header),
        header.json == NULL ? "NULL" : "a frame",
        strerror(header.failure)
    );
    free(header.json);

    uint8_t payload[8];
    memset(payload, 'a', sizeof(payload));
    uint8_t frame[sizeof(uint32_t) + sizeof(payload)];
    const size_t size = hm_build_frame(frame, 32, payload, sizeof(payload));

    HMFrameOutcome body = hm_receive_bytes(frame, size, HM_MAX_JSON_FRAME_SIZE, 0);
    CHECK(
        "framing.receive-rejects-truncated-payload",
        body.json == NULL && body.failure == ECONNRESET && body.size == 0,
        "expected NULL/ECONNRESET and no size after %zu of 32 payload bytes, "
            "got %s/%s and %u",
        sizeof(payload),
        body.json == NULL ? "NULL" : "a frame",
        strerror(body.failure),
        body.size
    );
    free(body.json);
}

static void hm_test_pause(long milliseconds) {
    struct timespec pause = {
        .tv_sec = milliseconds / 1000,
        .tv_nsec = (milliseconds % 1000) * 1000000L,
    };
    nanosleep(&pause, NULL);
}

typedef struct {
    int descriptor;
    const char *payload;
    size_t length;
    size_t first_chunk;
    int status;
} HMSplitWriter;

static void *hm_write_split_frame(void *argument) {
    HMSplitWriter *writer = (HMSplitWriter *)argument;
    const uint32_t network_length = htonl((uint32_t)writer->length);
    writer->status = hm_send_all(
        writer->descriptor,
        &network_length,
        sizeof(network_length)
    );
    if (writer->status == 0) {
        writer->status =
            hm_send_all(writer->descriptor, writer->payload, writer->first_chunk);
    }
    /*
     * Long enough for the reader to be sitting inside `recv` with the payload
     * half delivered, which is the state the loop in `hm_receive_all` exists for.
     */
    hm_test_pause(20);
    if (writer->status == 0) {
        writer->status = hm_send_all(
            writer->descriptor,
            writer->payload + writer->first_chunk,
            writer->length - writer->first_chunk
        );
    }
    return NULL;
}

static void hm_check_receive_assembles_split_payload(void) {
    int pair[2];
    if (hm_test_socket_pair(pair, 0) != 0) {
        CHECK(
            "framing.receive-assembles-split-payload",
            0,
            "socketpair failed: %s",
            strerror(errno)
        );
        return;
    }

    const char *payload = "{\"kind\":\"snapshot\",\"processes\":[1,2,3]}";
    HMSplitWriter writer = {pair[0], payload, strlen(payload), 7, -1};
    pthread_t thread;
    if (pthread_create(&thread, NULL, hm_write_split_frame, &writer) != 0) {
        CHECK(
            "framing.receive-assembles-split-payload",
            0,
            "pthread_create failed: %s",
            strerror(errno)
        );
        close(pair[0]);
        close(pair[1]);
        return;
    }

    uint32_t size = 0;
    errno = 0;
    char *received = hm_receive_json_frame(pair[1], HM_MAX_JSON_FRAME_SIZE, &size);
    const int failure = errno;
    pthread_join(thread, NULL);

    CHECK(
        "framing.receive-assembles-split-payload",
        writer.status == 0 &&
            received != NULL &&
            size == (uint32_t)strlen(payload) &&
            strcmp(received, payload) == 0,
        "expected %zu bytes of %s after two writes, got writer status %d and %s",
        strlen(payload),
        payload,
        writer.status,
        received == NULL ? strerror(failure) : received
    );

    free(received);
    close(pair[0]);
    close(pair[1]);
}

typedef struct {
    int descriptor;
    uint8_t *buffer;
    size_t capacity;
    size_t received;
    long pause_milliseconds;
    int failure;
    int chunks;
} HMThrottledReader;

/*
 * Drains the socket a kilobyte at a time with a pause between reads. The pause
 * is what keeps the sender waiting on a full socket buffer long enough to be
 * interrupted; a reader running at full speed empties the buffer faster than the
 * sender can fill it, and the send never has to resume anything.
 */
static void *hm_read_throttled(void *argument) {
    HMThrottledReader *reader = (HMThrottledReader *)argument;
    while (reader->received < reader->capacity) {
        size_t remaining = reader->capacity - reader->received;
        if (remaining > 1024) {
            remaining = 1024;
        }
        const ssize_t chunk =
            recv(reader->descriptor, reader->buffer + reader->received, remaining, 0);
        if (chunk < 0 && errno == EINTR) {
            continue;
        }
        if (chunk <= 0) {
            reader->failure = chunk == 0 ? ECONNRESET : errno;
            break;
        }
        reader->received += (size_t)chunk;
        reader->chunks++;
        hm_test_pause(reader->pause_milliseconds);
    }
    return NULL;
}

typedef struct {
    pthread_t target;
    volatile int stop;
    int delivered;
} HMSenderInterrupter;

static void hm_ignore_signal(int number) {
    (void)number;
}

static void *hm_interrupt_sender(void *argument) {
    HMSenderInterrupter *interrupter = (HMSenderInterrupter *)argument;
    while (!interrupter->stop) {
        pthread_kill(interrupter->target, SIGUSR1);
        interrupter->delivered++;
        hm_test_pause(1);
    }
    return NULL;
}

/*
 * The same loop `hm_send_all` runs, with the two returns that loop exists for
 * counted. It is sent down the same descriptor, against the same throttled reader
 * and the same interrupter, immediately before the frame — a control that says
 * whether a short write or an EINTR happens under these conditions at all.
 */
static int hm_send_counting(
    int descriptor,
    const char *buffer,
    size_t size,
    int *short_writes,
    int *interruptions
) {
    const uint8_t *cursor = (const uint8_t *)buffer;
    size_t remaining = size;
    while (remaining > 0) {
        const ssize_t written = send(descriptor, cursor, remaining, 0);
        if (written < 0 && errno == EINTR) {
            ++(*interruptions);
            continue;
        }
        if (written <= 0) {
            return -1;
        }
        if ((size_t)written < remaining) {
            ++(*short_writes);
        }
        cursor += (size_t)written;
        remaining -= (size_t)written;
    }
    return 0;
}

/*
 * The one case in which `send` hands back fewer bytes than it was given, and so
 * the only way to reach the arithmetic that resumes the transfer. Measured on
 * this machine: a blocking unix-domain `send` moves the whole buffer in a single
 * call however small SO_SNDBUF is — it waits for the reader inside the kernel —
 * so a shrunken buffer and a reader thread on their own never produce a short
 * write. A signal delivered to the sending thread while it waits does: roughly
 * half of the calls here come back short and the rest with EINTR, which is the
 * second branch of the same loop.
 *
 * Getting the resumption wrong truncates or duplicates a slice in the middle of a
 * message, hence a payload of repeating letters rather than one repeated byte: a
 * misplaced chunk shows up as a mismatch instead of comparing equal by accident.
 */
static void hm_check_send_completes_partial_write(void) {
    int pair[2];
    if (hm_test_socket_pair(pair, HM_TEST_SMALL_SOCKET_BUFFER) != 0) {
        CHECK(
            "framing.send-completes-partial-write",
            0,
            "socketpair failed: %s",
            strerror(errno)
        );
        return;
    }

    char *payload = (char *)malloc(HM_TEST_PARTIAL_WRITE_BYTES + 1U);
    uint8_t *wire = (uint8_t *)malloc(
        HM_TEST_PROBE_BYTES + sizeof(uint32_t) + HM_TEST_PARTIAL_WRITE_BYTES
    );
    if (payload == NULL || wire == NULL) {
        free(payload);
        free(wire);
        CHECK("framing.send-completes-partial-write", 0, "out of memory");
        close(pair[0]);
        close(pair[1]);
        return;
    }
    for (size_t index = 0; index < HM_TEST_PARTIAL_WRITE_BYTES; index++) {
        payload[index] = (char)('a' + (index % 26));
    }
    payload[HM_TEST_PARTIAL_WRITE_BYTES] = '\0';

    /* Without SA_RESTART the kernel returns to the sender instead of resuming. */
    struct sigaction interrupting;
    struct sigaction previous;
    memset(&interrupting, 0, sizeof(interrupting));
    interrupting.sa_handler = hm_ignore_signal;
    sigemptyset(&interrupting.sa_mask);
    interrupting.sa_flags = 0;
    sigaction(SIGUSR1, &interrupting, &previous);

    HMThrottledReader reader = {
        pair[1],
        wire,
        HM_TEST_PROBE_BYTES + sizeof(uint32_t) + HM_TEST_PARTIAL_WRITE_BYTES,
        0,
        1,
        0,
        0,
    };
    HMSenderInterrupter interrupter = {pthread_self(), 0, 0};
    pthread_t reader_thread;
    pthread_t interrupter_thread;
    if (pthread_create(&reader_thread, NULL, hm_read_throttled, &reader) != 0) {
        CHECK(
            "framing.send-completes-partial-write",
            0,
            "pthread_create failed for the reader: %s",
            strerror(errno)
        );
        sigaction(SIGUSR1, &previous, NULL);
        free(payload);
        free(wire);
        close(pair[0]);
        close(pair[1]);
        return;
    }
    /*
     * A second `pthread_create` in the same condition would leave the reader
     * running against `wire` and `reader` after both had been freed and the
     * frame returned. It has to be joined first, and the only thing that ends
     * its loop is the socket going away.
     */
    if (pthread_create(&interrupter_thread, NULL, hm_interrupt_sender, &interrupter) != 0) {
        CHECK(
            "framing.send-completes-partial-write",
            0,
            "pthread_create failed for the interrupter: %s",
            strerror(errno)
        );
        close(pair[0]);
        pthread_join(reader_thread, NULL);
        sigaction(SIGUSR1, &previous, NULL);
        free(payload);
        free(wire);
        close(pair[1]);
        return;
    }

    int short_writes = 0;
    int interruptions = 0;
    const int probed = hm_send_counting(
        pair[0],
        payload,
        HM_TEST_PROBE_BYTES,
        &short_writes,
        &interruptions
    );

    errno = 0;
    const int sent = hm_send_json_frame(pair[0], payload);
    const int send_failure = errno;
    interrupter.stop = 1;
    pthread_join(interrupter_thread, NULL);
    pthread_join(reader_thread, NULL);
    sigaction(SIGUSR1, &previous, NULL);

    uint32_t declared = 0;
    if (reader.received >= HM_TEST_PROBE_BYTES + sizeof(declared)) {
        memcpy(&declared, wire + HM_TEST_PROBE_BYTES, sizeof(declared));
        declared = ntohl(declared);
    }

    /*
     * The evidence that the resumption path ran is the control send above: the
     * same loop, on the same descriptor, under the same reader and interrupter,
     * counting the two returns `hm_send_all` does not report. Measured here: 6
     * short writes and 5 EINTR returns over the 16 KiB probe, and 0 of each with
     * the interrupter silenced — which is the state this condition exists to
     * catch, and the state a `send` that stopped coming back short would leave
     * the check in.
     *
     * `interrupter.delivered` and `reader.chunks` are reported but not asserted:
     * both hold whatever the kernel does with the signal — the interrupter counts
     * its own `pthread_kill` calls, and the reader needs 81 chunks to reach its
     * capacity a kilobyte at a time — so an assertion on them would be true by
     * construction. Adding `SA_RESTART` to the `sigaction` above, for one, leaves
     * all three of them and the control send green, and correctly so: measured
     * with it, the EINTR returns fall to 0 while the short writes stay at 6,
     * because the kernel restarts only a `send` that transferred nothing.
     */
    CHECK(
        "framing.send-completes-partial-write",
        probed == 0 &&
            short_writes + interruptions > 0 &&
            sent == 0 &&
            reader.received ==
                HM_TEST_PROBE_BYTES + sizeof(uint32_t) + HM_TEST_PARTIAL_WRITE_BYTES &&
            declared == HM_TEST_PARTIAL_WRITE_BYTES &&
            memcmp(
                wire + HM_TEST_PROBE_BYTES + sizeof(uint32_t),
                payload,
                HM_TEST_PARTIAL_WRITE_BYTES
            ) == 0,
        "expected %d bytes through a %d-byte socket buffer after a probe that was "
            "interrupted at least once, got probe %d with %d short writes and %d "
            "EINTR returns, send %d/%s and %zu bytes declaring %u in %d reads "
            "under %d interrupts",
        HM_TEST_PARTIAL_WRITE_BYTES,
        HM_TEST_SMALL_SOCKET_BUFFER,
        probed,
        short_writes,
        interruptions,
        sent,
        sent == 0 ? "ok" : strerror(send_failure),
        reader.received,
        declared,
        reader.chunks,
        interrupter.delivered
    );

    free(payload);
    free(wire);
    close(pair[0]);
    close(pair[1]);
}

void hm_run_framing_tests(void) {
    hm_check_send_rejects_bad_payload();
    hm_check_round_trip();
    hm_check_receive_rejects_lengths();
    hm_check_receive_rejects_embedded_nul();
    hm_check_receive_frees_rejected_frame();
    hm_check_receive_terminates_payload();
    hm_check_receive_rejects_truncated_frames();
    hm_check_receive_assembles_split_payload();
    hm_check_send_completes_partial_write();
}
