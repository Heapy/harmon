#include "harmon_native.h"

#include <dirent.h>
#include <limits.h>

#include "harness.h"

/*
 * The Unix socket the collector listens on. Two properties of this suite are the
 * reason it exists at all: the server refuses to take over a path it does not
 * recognise as its own stale socket, and `hm_unix_accept` is the access gate —
 * the only thing between a collector running as root and any local process that
 * can reach the socket file. Both are testable without privileges, since a
 * foreign uid can be named without being owned.
 *
 * Every path lives in one `mkdtemp` directory removed at exit, so a failed check
 * cannot leave a socket behind for the next run to trip over.
 */

static char hm_socket_test_directory[PATH_MAX] = "";

static void hm_remove_test_directory(void) {
    if (hm_socket_test_directory[0] == '\0') {
        return;
    }
    DIR *directory = opendir(hm_socket_test_directory);
    if (directory != NULL) {
        const struct dirent *entry;
        while ((entry = readdir(directory)) != NULL) {
            if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
                continue;
            }
            char path[PATH_MAX];
            snprintf(
                path,
                sizeof(path),
                "%s/%s",
                hm_socket_test_directory,
                entry->d_name
            );
            unlink(path);
        }
        closedir(directory);
    }
    rmdir(hm_socket_test_directory);
    hm_socket_test_directory[0] = '\0';
}

/*
 * Under /tmp rather than TMPDIR: a per-user TMPDIR on macOS is long enough that
 * the socket path would come close to the 104 bytes `sun_path` holds, and the
 * suite would start failing for a reason that has nothing to do with the bridge.
 */
static int hm_socket_test_path(char *buffer, size_t size, const char *name) {
    if (hm_socket_test_directory[0] == '\0') {
        snprintf(
            hm_socket_test_directory,
            sizeof(hm_socket_test_directory),
            "/tmp/harmon-native-test.XXXXXX"
        );
        if (mkdtemp(hm_socket_test_directory) == NULL) {
            hm_socket_test_directory[0] = '\0';
            return -1;
        }
        atexit(hm_remove_test_directory);
    }
    snprintf(buffer, size, "%s/%s", hm_socket_test_directory, name);
    return 0;
}

/*
 * Every check below opens with the same three lines, and a missing temporary
 * directory has to be reported as the failure of the check that wanted it rather
 * than swallowed — the harness has no way of saying "skipped". Written out eight
 * times it was eight chances to report it under the wrong name.
 */
#define HM_SOCKET_PATH_OR_FAIL(buffer, name, check)                              \
    do {                                                                         \
        if (hm_socket_test_path((buffer), sizeof(buffer), (name)) != 0) {        \
            CHECK((check), 0, "no temporary directory: %s", strerror(errno));    \
            return;                                                              \
        }                                                                        \
    } while (0)

static int hm_server_open_rejects(const char *path, int expected_failure) {
    errno = 0;
    const int descriptor = hm_unix_server_open(path, (uint32_t)getegid());
    const int failure = errno;
    if (descriptor >= 0) {
        close(descriptor);
        if (path != NULL && path[0] != '\0') {
            unlink(path);
        }
        return 0;
    }
    return descriptor == -1 && failure == expected_failure;
}

/*
 * A path the address cannot hold must be refused before the socket exists:
 * `snprintf` into `sun_path` would otherwise truncate it silently and bind a
 * different path than the caller asked for — one the agent would never find.
 *
 * Both ends of the socket answer for this, and each gets a check of its own
 * because each has its own copy of the guard; what they share is only the three
 * paths to try and the shape of the report, which is this function.
 */
static void hm_check_bad_paths(
    const char *check,
    int (*rejects)(const char *path, int expected_failure)
) {
    const size_t limit = sizeof(((struct sockaddr_un *)0)->sun_path);
    char *long_path = (char *)malloc(limit + 1U);
    if (long_path == NULL) {
        CHECK(check, 0, "out of memory");
        return;
    }
    memset(long_path, 'a', limit);
    long_path[0] = '/';
    long_path[limit] = '\0';

    const int null_path = rejects(NULL, EINVAL);
    const int empty_path = rejects("", EINVAL);
    const int too_long = rejects(long_path, ENAMETOOLONG);

    CHECK(
        check,
        null_path && empty_path && too_long,
        "expected -1/EINVAL, -1/EINVAL and -1/ENAMETOOLONG for a %zu-byte path; "
            "rejected null=%d empty=%d too-long=%d",
        limit,
        null_path,
        empty_path,
        too_long
    );

    free(long_path);
}

/*
 * Anything at the path that is not a socket of this user is somebody else's
 * file, and the server unlinks before it binds. Taking over a regular file would
 * delete data the collector has no business touching; the same branch is what
 * stops it from displacing a socket owned by another uid.
 */
static void hm_check_refuses_foreign_occupant(void) {
    char path[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(path, "occupied.sock", "socket.refuses-foreign-occupant");

    const int file = open(path, O_CREAT | O_EXCL | O_WRONLY, 0600);
    if (file < 0) {
        CHECK(
            "socket.refuses-foreign-occupant",
            0,
            "cannot create %s: %s",
            path,
            strerror(errno)
        );
        return;
    }
    close(file);

    const int refused = hm_server_open_rejects(path, EEXIST);
    struct stat occupant;
    const int survived = lstat(path, &occupant) == 0 && S_ISREG(occupant.st_mode);

    CHECK(
        "socket.refuses-foreign-occupant",
        refused && survived,
        "expected -1/EEXIST over a regular file that stays put; "
            "refused=%d survived=%d (%s)",
        refused,
        survived,
        path
    );

    unlink(path);
}

/*
 * The collector's own socket file outlives a crash, so the common case of a
 * restart finds a stale socket of its own uid at the path. Refusing it would
 * make every unclean shutdown need a manual `rm`.
 */
static void hm_check_replaces_stale_socket(void) {
    char path[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(path, "stale.sock", "socket.replaces-stale-socket");

    const int first = hm_unix_server_open(path, (uint32_t)getegid());
    if (first >= 0) {
        close(first);
    }

    struct stat stale;
    const int left_behind = lstat(path, &stale) == 0 && S_ISSOCK(stale.st_mode);

    errno = 0;
    const int second = hm_unix_server_open(path, (uint32_t)getegid());
    const int failure = errno;

    CHECK(
        "socket.replaces-stale-socket",
        first >= 0 && left_behind && second >= 0,
        "expected a second open over the stale socket at %s to succeed, "
            "got first=%d stale-socket=%d second=%d/%s",
        path,
        first,
        left_behind,
        second,
        second >= 0 ? "ok" : strerror(failure)
    );

    if (second >= 0) {
        close(second);
    }
    unlink(path);
}

/*
 * The agent runs as the login user and the collector as root, so the group bit
 * is what lets them talk at all; the world bit is what would let any local
 * process query the collector. 0660 is the difference.
 */
static void hm_check_socket_mode(void) {
    char path[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(path, "mode.sock", "socket.mode-is-0660");

    errno = 0;
    const int descriptor = hm_unix_server_open(path, (uint32_t)getegid());
    const int failure = errno;
    struct stat created;
    memset(&created, 0, sizeof(created));
    const int described = descriptor >= 0 && lstat(path, &created) == 0;

    CHECK(
        "socket.mode-is-0660",
        described && S_ISSOCK(created.st_mode) && (created.st_mode & 07777) == 0660,
        "expected a socket with mode 0660 at %s, got open %d/%s and mode 0%o",
        path,
        descriptor,
        descriptor >= 0 ? "ok" : strerror(failure),
        described ? (unsigned int)(created.st_mode & 07777) : 0U
    );

    if (descriptor >= 0) {
        close(descriptor);
    }
    unlink(path);
}

typedef struct {
    int server;
    int client;
} HMSocketPairing;

/*
 * A connected pair over a real listening socket. `connect` completes as soon as
 * the connection is queued, so `hm_unix_accept` never blocks here and the alarm
 * in `main.c` stays a backstop rather than the thing that ends the suite.
 */
static int hm_open_pairing(const char *path, HMSocketPairing *pairing) {
    pairing->server = -1;
    pairing->client = -1;
    pairing->server = hm_unix_server_open(path, (uint32_t)getegid());
    if (pairing->server < 0) {
        return -1;
    }
    pairing->client = hm_unix_connect(path);
    if (pairing->client < 0) {
        const int saved_errno = errno;
        close(pairing->server);
        pairing->server = -1;
        errno = saved_errno;
        return -1;
    }
    return 0;
}

static void hm_close_pairing(HMSocketPairing *pairing, const char *path) {
    if (pairing->client >= 0) {
        close(pairing->client);
    }
    if (pairing->server >= 0) {
        close(pairing->server);
    }
    unlink(path);
}

/*
 * The client side validates the path on its own, and has to: the agent takes the
 * socket path from configuration, so an unset key arrives here as an empty
 * string and a path longer than `sun_path` arrives silently truncated unless it
 * is refused. Mirrors the guard in `hm_unix_server_open`, hence a check of its
 * own — a copy that stops mirroring is the failure mode.
 */
static int hm_connect_rejects(const char *path, int expected_failure) {
    errno = 0;
    const int descriptor = hm_unix_connect(path);
    const int failure = errno;
    if (descriptor >= 0) {
        close(descriptor);
        return 0;
    }
    return descriptor == -1 && failure == expected_failure;
}

static void hm_check_connect(void) {
    char path[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(path, "connect.sock", "socket.connect-to-live-and-missing");

    HMSocketPairing pairing;
    errno = 0;
    const int paired = hm_open_pairing(path, &pairing);
    const int pairing_failure = errno;

    char missing[PATH_MAX];
    snprintf(missing, sizeof(missing), "%s.missing", path);
    errno = 0;
    const int absent = hm_unix_connect(missing);
    const int absent_failure = errno;
    if (absent >= 0) {
        close(absent);
    }

    CHECK(
        "socket.connect-to-live-and-missing",
        paired == 0 && absent == -1 && absent_failure == ENOENT,
        "expected a descriptor for %s and -1/ENOENT for %s, "
            "got pairing %d/%s and %d/%s",
        path,
        missing,
        paired,
        paired == 0 ? "ok" : strerror(pairing_failure),
        absent,
        strerror(absent_failure)
    );

    if (paired == 0) {
        hm_close_pairing(&pairing, path);
    } else {
        unlink(path);
    }
}

static void hm_check_accept_peer_credentials(void) {
    char path[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(path, "accept.sock", "socket.accept-returns-peer-credentials");

    HMSocketPairing pairing;
    errno = 0;
    if (hm_open_pairing(path, &pairing) != 0) {
        CHECK(
            "socket.accept-returns-peer-credentials",
            0,
            "cannot pair over %s: %s",
            path,
            strerror(errno)
        );
        unlink(path);
        return;
    }

    const uint32_t own_user_id = (uint32_t)geteuid();
    uint32_t peer_user_id = UINT32_MAX;
    errno = 0;
    const int accepted =
        hm_unix_accept(pairing.server, own_user_id, &peer_user_id);
    const int failure = errno;

    CHECK(
        "socket.accept-returns-peer-credentials",
        accepted >= 0 && peer_user_id == own_user_id,
        "expected a descriptor and uid %u, got %d/%s and uid %u",
        own_user_id,
        accepted,
        accepted >= 0 ? "ok" : strerror(failure),
        peer_user_id
    );

    if (accepted >= 0) {
        close(accepted);
    }
    hm_close_pairing(&pairing, path);
}

/*
 * What `hm_set_socket_options` puts on every descriptor the bridge opens, read
 * back off the descriptor with the calls that set it. Nothing else in the suite
 * looks at any of it, and each of the three is a production failure of its own:
 * without SO_NOSIGPIPE the collector dies of SIGPIPE when an agent disappears
 * mid-answer, without the timeouts a peer that stops reading blocks it for good
 * instead of for thirty seconds, and without FD_CLOEXEC the listening socket is
 * inherited by everything the collector ever spawns.
 */
#define HM_SOCKET_OPTION_TIMEOUT_SECONDS 30

/*
 * Room for the longest line `hm_carries_options` writes, which is the timeout
 * mismatch with a `strerror` in it.
 */
#define HM_SOCKET_DETAIL_BYTES 128

static int hm_carries_options(int descriptor, char *detail, size_t size) {
    int no_sigpipe = 0;
    socklen_t no_sigpipe_size = (socklen_t)sizeof(no_sigpipe);
    if (getsockopt(
            descriptor,
            SOL_SOCKET,
            SO_NOSIGPIPE,
            &no_sigpipe,
            &no_sigpipe_size
        ) != 0) {
        snprintf(detail, size, "SO_NOSIGPIPE unreadable: %s", strerror(errno));
        return 0;
    }
    if (no_sigpipe == 0) {
        snprintf(detail, size, "SO_NOSIGPIPE is off");
        return 0;
    }

    const int names[2] = {SO_SNDTIMEO, SO_RCVTIMEO};
    const char *const labels[2] = {"SO_SNDTIMEO", "SO_RCVTIMEO"};
    for (int index = 0; index < 2; index++) {
        struct timeval timeout;
        memset(&timeout, 0, sizeof(timeout));
        socklen_t timeout_size = (socklen_t)sizeof(timeout);
        if (getsockopt(descriptor, SOL_SOCKET, names[index], &timeout, &timeout_size) != 0) {
            snprintf(detail, size, "%s unreadable: %s", labels[index], strerror(errno));
            return 0;
        }
        if (timeout.tv_sec != HM_SOCKET_OPTION_TIMEOUT_SECONDS || timeout.tv_usec != 0) {
            snprintf(
                detail,
                size,
                "%s is %lld.%06d s, expected %d s",
                labels[index],
                (long long)timeout.tv_sec,
                timeout.tv_usec,
                HM_SOCKET_OPTION_TIMEOUT_SECONDS
            );
            return 0;
        }
    }

    const int flags = fcntl(descriptor, F_GETFD);
    if (flags < 0) {
        snprintf(detail, size, "F_GETFD failed: %s", strerror(errno));
        return 0;
    }
    if ((flags & FD_CLOEXEC) == 0) {
        snprintf(detail, size, "FD_CLOEXEC is clear");
        return 0;
    }
    snprintf(detail, size, "ok");
    return 1;
}

static void hm_check_descriptor_options(void) {
    char path[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(path, "options.sock", "socket.descriptors-carry-options");

    HMSocketPairing pairing;
    errno = 0;
    if (hm_open_pairing(path, &pairing) != 0) {
        CHECK(
            "socket.descriptors-carry-options",
            0,
            "cannot pair over %s: %s",
            path,
            strerror(errno)
        );
        unlink(path);
        return;
    }

    uint32_t peer_user_id = UINT32_MAX;
    const int accepted =
        hm_unix_accept(pairing.server, (uint32_t)geteuid(), &peer_user_id);

    char server_detail[HM_SOCKET_DETAIL_BYTES] = "";
    char client_detail[HM_SOCKET_DETAIL_BYTES] = "";
    char accepted_detail[HM_SOCKET_DETAIL_BYTES] = "not accepted";
    const int server_carries = hm_carries_options(
        pairing.server,
        server_detail,
        sizeof(server_detail)
    );
    const int client_carries = hm_carries_options(
        pairing.client,
        client_detail,
        sizeof(client_detail)
    );
    const int accepted_carries = accepted >= 0 &&
        hm_carries_options(accepted, accepted_detail, sizeof(accepted_detail));

    CHECK(
        "socket.descriptors-carry-options",
        server_carries && client_carries && accepted_carries,
        "expected SO_NOSIGPIPE, %d s timeouts and FD_CLOEXEC on all three "
            "descriptors; listening: %s, connected: %s, accepted: %s",
        HM_SOCKET_OPTION_TIMEOUT_SECONDS,
        server_detail,
        client_detail,
        accepted_detail
    );

    if (accepted >= 0) {
        close(accepted);
    }
    hm_close_pairing(&pairing, path);
}

/*
 * The access gate. A collector running as root answers whoever it accepts, so a
 * connection from a uid other than the configured one has to be dropped before a
 * single frame is read. The peer uid is reported even then, because the caller
 * logs who was turned away.
 *
 * The check needs a non-root euid to mean anything: root is allowed through
 * unconditionally, so it cannot play the part of the stranger.
 */
static void hm_check_accept_rejects_foreign_uid(void) {
    char path[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(path, "foreign.sock", "socket.accept-rejects-foreign-uid");

    const uint32_t own_user_id = (uint32_t)geteuid();
    if (own_user_id == 0) {
        CHECK(
            "socket.accept-rejects-foreign-uid",
            0,
            "requires a non-root euid: uid 0 is allowed through unconditionally"
        );
        return;
    }

    HMSocketPairing pairing;
    errno = 0;
    if (hm_open_pairing(path, &pairing) != 0) {
        CHECK(
            "socket.accept-rejects-foreign-uid",
            0,
            "cannot pair over %s: %s",
            path,
            strerror(errno)
        );
        unlink(path);
        return;
    }

    const uint32_t foreign_user_id = own_user_id + 1U;
    uint32_t peer_user_id = UINT32_MAX;
    errno = 0;
    const int accepted =
        hm_unix_accept(pairing.server, foreign_user_id, &peer_user_id);
    const int failure = errno;

    CHECK(
        "socket.accept-rejects-foreign-uid",
        accepted == -2 && failure == EACCES && peer_user_id == own_user_id,
        "expected -2/EACCES and the peer uid %u reported, "
            "got %d/%s and uid %u for an allowed uid of %u",
        own_user_id,
        accepted,
        strerror(failure),
        peer_user_id,
        foreign_user_id
    );

    if (accepted >= 0) {
        close(accepted);
    }
    hm_close_pairing(&pairing, path);
}

static int hm_remove_socket_rejects(const char *path, int expected_failure) {
    errno = 0;
    const int status = hm_remove_socket(path);
    const int failure = errno;
    return status == -1 && failure == expected_failure;
}

/*
 * Shutdown runs this on paths that may never have existed — a collector that
 * failed to bind still tries to clean up — so every bad input has to come back
 * as an error rather than as a signal.
 */
static void hm_check_remove_bad_input(void) {
    char missing[PATH_MAX];
    HM_SOCKET_PATH_OR_FAIL(missing, "never-created.sock", "socket.remove-handles-bad-input");

    const int null_path = hm_remove_socket_rejects(NULL, EINVAL);
    const int empty_path = hm_remove_socket_rejects("", EINVAL);
    const int absent_path = hm_remove_socket_rejects(missing, ENOENT);

    CHECK(
        "socket.remove-handles-bad-input",
        null_path && empty_path && absent_path,
        "expected -1/EINVAL, -1/EINVAL and -1/ENOENT for %s; "
            "rejected null=%d empty=%d missing=%d",
        missing,
        null_path,
        empty_path,
        absent_path
    );
}

void hm_run_socket_tests(void) {
    hm_check_bad_paths("socket.rejects-bad-path", hm_server_open_rejects);
    hm_check_refuses_foreign_occupant();
    hm_check_replaces_stale_socket();
    hm_check_socket_mode();
    hm_check_bad_paths("socket.connect-rejects-bad-path", hm_connect_rejects);
    hm_check_connect();
    hm_check_accept_peer_credentials();
    hm_check_descriptor_options();
    hm_check_accept_rejects_foreign_uid();
    hm_check_remove_bad_input();
    /* The directory is removed by the `atexit` handler `hm_socket_test_path`
     * registers, which covers an early return out of any check as well. */
}
