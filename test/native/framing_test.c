#include "harmon_native.h"

#include <malloc/malloc.h>
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

    close(pair[0]);
    close(pair[1]);
}

static void hm_check_round_trip(void) {
    int pair[2];
    if (hm_test_socket_pair(pair, 0) != 0) {
        CHECK("framing.round-trips-a-frame", 0, "socketpair failed: %s", strerror(errno));
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
    close(pair[0]);
    close(pair[1]);
}

/*
 * Three ways a declared length is refused. The absolute cap protects the reader
 * from a peer that asks it to allocate 4 GiB; `maximum_size` is the caller's own,
 * tighter limit; a zero length would produce an empty message that means nothing.
 */
static void hm_check_receive_rejects_lengths(void) {
    uint8_t payload[16];
    memset(payload, 'a', sizeof(payload));
    uint8_t frame[sizeof(uint32_t) + sizeof(payload)];

    size_t size = hm_build_frame(frame, HM_MAX_JSON_FRAME_SIZE + 1U, NULL, 0);
    HMFrameOutcome oversized =
        hm_receive_bytes(frame, size, HM_MAX_JSON_FRAME_SIZE, 0);
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
 */
static void hm_check_receive_rejects_embedded_nul(void) {
    uint8_t payload[16];
    memset(payload, 'a', sizeof(payload));
    payload[sizeof(payload) / 2] = '\0';

    uint8_t frame[sizeof(uint32_t) + sizeof(payload)];
    const size_t size =
        hm_build_frame(frame, (uint32_t)sizeof(payload), payload, sizeof(payload));

    HMFrameOutcome outcome = hm_receive_bytes(frame, size, HM_MAX_JSON_FRAME_SIZE, 0);
    CHECK(
        "framing.receive-rejects-embedded-nul",
        outcome.json == NULL && outcome.failure == EILSEQ && outcome.size == 0,
        "expected NULL/EILSEQ and no size, got %s/%s and %u",
        outcome.json == NULL ? "NULL" : "a frame",
        strerror(outcome.failure),
        outcome.size
    );
    free(outcome.json);
}

static size_t hm_heap_bytes_in_use(void) {
    malloc_statistics_t statistics;
    malloc_zone_statistics(malloc_default_zone(), &statistics);
    return statistics.size_in_use;
}

/*
 * A rejected frame has already been allocated by the time it is rejected, and
 * nothing in the return value says whether it was released. Losing that `free`
 * would leak the whole frame on every malformed message a peer sends — the
 * cheapest denial of service the protocol has to offer — while every check above
 * stays green.
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

    /* One warm-up run keeps first-touch allocations out of the measurement. */
    HMFrameOutcome warm_up = hm_receive_bytes(
        frame,
        size,
        HM_MAX_JSON_FRAME_SIZE,
        HM_TEST_LARGE_SOCKET_BUFFER
    );
    free(warm_up.json);

    const size_t before = hm_heap_bytes_in_use();
    int rejected = 1;
    for (int iteration = 0; iteration < HM_TEST_LEAK_ITERATIONS; iteration++) {
        HMFrameOutcome outcome = hm_receive_bytes(
            frame,
            size,
            HM_MAX_JSON_FRAME_SIZE,
            HM_TEST_LARGE_SOCKET_BUFFER
        );
        if (outcome.json != NULL || outcome.failure != EILSEQ) {
            rejected = 0;
            free(outcome.json);
            break;
        }
    }
    const long long growth =
        (long long)hm_heap_bytes_in_use() - (long long)before;

    CHECK(
        "framing.receive-frees-rejected-frame",
        rejected && growth < HM_TEST_LEAK_TOLERANCE_BYTES,
        "expected the heap to grow by less than %d bytes over %d rejected frames "
            "of %zu bytes, grew by %lld (every frame rejected: %d)",
        HM_TEST_LEAK_TOLERANCE_BYTES,
        HM_TEST_LEAK_ITERATIONS,
        payload_bytes,
        growth,
        rejected
    );

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
    uint8_t *wire = (uint8_t *)malloc(sizeof(uint32_t) + HM_TEST_PARTIAL_WRITE_BYTES);
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
        sizeof(uint32_t) + HM_TEST_PARTIAL_WRITE_BYTES,
        0,
        1,
        0,
    };
    HMSenderInterrupter interrupter = {pthread_self(), 0, 0};
    pthread_t reader_thread;
    pthread_t interrupter_thread;
    if (pthread_create(&reader_thread, NULL, hm_read_throttled, &reader) != 0 ||
        pthread_create(&interrupter_thread, NULL, hm_interrupt_sender, &interrupter) != 0) {
        CHECK(
            "framing.send-completes-partial-write",
            0,
            "pthread_create failed: %s",
            strerror(errno)
        );
        interrupter.stop = 1;
        sigaction(SIGUSR1, &previous, NULL);
        free(payload);
        free(wire);
        close(pair[0]);
        close(pair[1]);
        return;
    }

    errno = 0;
    const int sent = hm_send_json_frame(pair[0], payload);
    const int send_failure = errno;
    interrupter.stop = 1;
    pthread_join(interrupter_thread, NULL);
    pthread_join(reader_thread, NULL);
    sigaction(SIGUSR1, &previous, NULL);

    uint32_t declared = 0;
    if (reader.received >= sizeof(declared)) {
        memcpy(&declared, wire, sizeof(declared));
        declared = ntohl(declared);
    }

    CHECK(
        "framing.send-completes-partial-write",
        sent == 0 &&
            reader.received == sizeof(uint32_t) + HM_TEST_PARTIAL_WRITE_BYTES &&
            declared == HM_TEST_PARTIAL_WRITE_BYTES &&
            memcmp(wire + sizeof(uint32_t), payload, HM_TEST_PARTIAL_WRITE_BYTES) == 0,
        "expected %d bytes through a %d-byte socket buffer under %d interrupts, "
            "got send %d/%s and %zu bytes declaring %u",
        HM_TEST_PARTIAL_WRITE_BYTES,
        HM_TEST_SMALL_SOCKET_BUFFER,
        interrupter.delivered,
        sent,
        sent == 0 ? "ok" : strerror(send_failure),
        reader.received,
        declared
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
    hm_check_receive_rejects_truncated_frames();
    hm_check_receive_assembles_split_payload();
    hm_check_send_completes_partial_write();
}
