#include "harmon_native.h"

#include <fcntl.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/wait.h>

#include "anchors.h"
#include "harness.h"

/*
 * The listing, the sample it writes about this process, and the issue array that
 * says why the rest are missing from it.
 *
 * The suite reads live machine state, so what it can prove depends on the account
 * it runs as, in two opposite directions.
 *
 * Not root: `processes.samples-are-well-formed` requires every sampled pid to be
 * positive, which holds only because proc_pid_rusage denies pid 0 — kernel_task,
 * which proc_listallpids does report — to an ordinary user.
 * `socket.accept-rejects-foreign-uid` in the socket suite has the same
 * requirement for a different reason. Running the harness as root is not
 * supported, and CLAUDE.md says so.
 *
 * Enough processes of its own: the capacity branch only fires once the sample
 * array is full, so `processes.issues-are-well-formed` needs the account to own
 * the `HM_LISTING_SAMPLE_CAPACITY` rusage-readable processes that fill it, and
 * `processes.issue-metadata-matches-a-fresh-read` a further
 * `HM_ISSUE_METADATA_MINIMUM` behind them. A desktop session is far past both
 * (585 here) and a freshly booted CI runner under a service account need not be,
 * so `hm_top_up_own_processes` makes the condition rather than asking for it.
 *
 * Enough processes of *other* users: `processes.rusage-issue-path-matches-a-fresh-read`
 * and `processes.rusage-issue-uid-is-unknown` read the rusage branch, whose whole
 * population is processes this account cannot read — 275 of them here, and every
 * macOS carries a hundred of root's. That one cannot be made, so both fail naming
 * what they counted, and CLAUDE.md records the requirement.
 */

/*
 * How far `total_processes` may sit from a count taken moments earlier. Process
 * churn over the microseconds between the two calls is a handful at most, while
 * the regression this guards against — an intermediate PID list narrower than
 * the machine, the shape of 881195d — costs hundreds.
 */
#define HM_PROCESS_COUNT_TOLERANCE 64

/*
 * How much of the issue array may disagree with a second read of the same pids,
 * and how much of it has to be compared for the comparison to mean anything.
 *
 * Neither number is a tolerance on the bridge: a process that execs between the
 * two reads changes its own name and path, and one that exits is skipped
 * entirely, so a live machine produces the occasional legitimate disagreement.
 * Measured here: 0 disagreements over 174 to 177 comparable issues, six runs out
 * of six. The thinnest mutation measured — a parent pid hard-coded to 1, which
 * many processes legitimately have — still disagreed on 24 % of them, against a
 * budget of 6.25 %.
 */
#define HM_ISSUE_METADATA_MINIMUM 32
#define HM_ISSUE_METADATA_MISMATCH_DIVISOR 16

/*
 * The narrow listing that `hm_check_process_listing` takes for five checks, and
 * how many processes of this account it takes for them to mean anything.
 *
 * The sample array has to fill before the capacity branch fires at all, and
 * `HM_ISSUE_METADATA_MINIMUM` processes of this account have to reach the issue
 * array behind it with metadata a second read can compare. Both are properties of
 * the account rather than of the bridge, so the check makes them the way
 * `processes.own-sample-matches-a-fresh-rusage` makes its own: it counts the
 * rusage-readable processes first and forks the shortfall, which is nothing at
 * all on the desktop session measured here (585 of them) and a few dozen on a
 * freshly booted CI runner under a service account. proc_listallpids answers
 * newest first, so children forked immediately before the listing occupy the
 * front of it; topping up by the shortfall alone leaves a machine that already
 * qualifies with exactly the process mix it would have had.
 *
 * The margin above the two minima is for the processes that exit between the
 * count and the listing. The cap keeps a machine that reports an implausible
 * count from forking without bound.
 */
#define HM_LISTING_SAMPLE_CAPACITY 64
#define HM_LISTING_ISSUE_CAPACITY 256
#define HM_LISTING_OWN_MARGIN 16
#define HM_LISTING_OWN_MINIMUM \
    (HM_LISTING_SAMPLE_CAPACITY + HM_ISSUE_METADATA_MINIMUM + HM_LISTING_OWN_MARGIN)
#define HM_LISTING_TOP_UP_LIMIT HM_LISTING_OWN_MINIMUM

/* What the account brought to the listing, and what this check added to it. */
typedef struct {
    int owned;
    pid_t *placeholders;
    int forks;
} HMOwnProcesses;

/*
 * The saved exec path of `pid`, verbatim: relative whenever the process was
 * started that way, and empty when the region cannot be read at all.
 *
 * Deliberately without the absolute-path filter the bridge applies. Two of the
 * checks below have to separate "the bridge refused a relative path" from "there
 * was nothing there to refuse", and a reader that had already dropped the
 * relative ones could not tell them apart.
 */
static int hm_test_saved_exec_path(pid_t pid, char *out, size_t out_size) {
    out[0] = '\0';
    int name[3] = {CTL_KERN, KERN_PROCARGS2, (int)pid};
    size_t region_size = 0;
    if (sysctl(name, 3, NULL, &region_size, NULL, 0) != 0 || region_size <= sizeof(int)) {
        return 0;
    }

    char *region = (char *)malloc(region_size);
    if (region == NULL) {
        return 0;
    }
    int found = 0;
    if (sysctl(name, 3, region, &region_size, NULL, 0) == 0 && region_size > sizeof(int)) {
        const char *path = region + sizeof(int);
        if (memchr(path, '\0', region_size - sizeof(int)) != NULL) {
            snprintf(out, out_size, "%s", path);
            found = out[0] != '\0';
        }
    }
    free(region);
    return found;
}

/*
 * A second read of one process's executable path, in the order the bridge reads
 * it: `proc_pidpath`, and the saved exec path only when that fails and only when
 * it is absolute.
 *
 * Mirroring the fallback is the point, the same way the name anchor mirrors
 * proc_name → `pbi_name` → `pbi_comm`: what the two anchors below assert is that
 * `hm_read_process_metadata` performed this sequence, so an anchor that stopped
 * at `proc_pidpath` would call every process with a replaced binary a
 * disagreement — four of them here, all of them right.
 */
static void hm_fresh_exec_path(pid_t pid, char *out, size_t out_size) {
    memset(out, 0, out_size);
    if (proc_pidpath(pid, out, (uint32_t)out_size) > 0) {
        return;
    }
    char saved[HM_PROCESS_PATH_SIZE];
    if (hm_test_saved_exec_path(pid, saved, sizeof(saved)) && saved[0] == '/') {
        snprintf(out, out_size, "%s", saved);
    } else {
        out[0] = '\0';
    }
}

/*
 * The processes this account can read the rusage of, which is exactly the
 * population `hm_list_processes` turns into samples. Counting them with the same
 * call the bridge uses is the only way to know whether the sample array can fill.
 */
static int hm_count_readable_processes(void) {
    const int capacity = hm_count_processes() + HM_PROCESS_LIST_HEADROOM;
    pid_t *pids = (pid_t *)calloc((size_t)capacity, sizeof(pid_t));
    if (pids == NULL) {
        return -1;
    }
    const int listed = proc_listallpids(pids, capacity * (int)sizeof(pid_t));
    int readable = 0;
    for (int index = 0; index < listed; ++index) {
        if (pids[index] <= 0) {
            continue;
        }
        struct rusage_info_v6 usage;
        memset(&usage, 0, sizeof(usage));
        if (proc_pid_rusage(pids[index], RUSAGE_INFO_V6, (rusage_info_t *)&usage) == 0) {
            ++readable;
        }
    }
    free(pids);
    return readable;
}

static HMOwnProcesses hm_top_up_own_processes(void) {
    HMOwnProcesses own = {hm_count_readable_processes(), NULL, 0};
    if (own.owned < 0 || own.owned >= HM_LISTING_OWN_MINIMUM) {
        return own;
    }

    int wanted = HM_LISTING_OWN_MINIMUM - own.owned;
    if (wanted > HM_LISTING_TOP_UP_LIMIT) {
        wanted = HM_LISTING_TOP_UP_LIMIT;
    }
    own.placeholders = (pid_t *)calloc((size_t)wanted, sizeof(pid_t));
    if (own.placeholders == NULL) {
        return own;
    }
    for (int index = 0; index < wanted; ++index) {
        const pid_t child = fork();
        if (child == 0) {
            hm_test_park_forever();
        }
        if (child < 0) {
            break;
        }
        own.placeholders[own.forks++] = child;
    }
    return own;
}

static void hm_release_own_processes(HMOwnProcesses *own) {
    for (int index = 0; index < own->forks; ++index) {
        kill(own->placeholders[index], SIGKILL);
    }
    for (int index = 0; index < own->forks; ++index) {
        int status = 0;
        while (waitpid(own->placeholders[index], &status, 0) < 0 && errno == EINTR) {
        }
    }
    free(own->placeholders);
    own->placeholders = NULL;
    own->forks = 0;
}

/*
 * The metadata on the issue path, against a second read the test performs
 * itself. `processes.issues-are-well-formed` is satisfied by the zeroed struct
 * the fields start from, so without this the whole `hm_read_process_metadata`
 * call could go and nothing would notice. It also covers what that function puts
 * where: a hard-coded uid, a parent pid that is always 1, a `proc_pidpath` that
 * is never called.
 *
 * This covers the *capacity* branch only, and it is the branch
 * `hm_check_process_listing` produces (247 of its 256 issues here). A pid whose `proc_bsdinfo` cannot
 * be read at all is skipped, and that is exactly the population of the other
 * branch: `PROC_PIDTBSDINFO` is refused for another user's process, 0 of 275
 * readable here, so anchoring against it would compare nothing.
 * `processes.rusage-issue-path-matches-a-fresh-read` covers the rusage branch
 * instead, over the one field that survives the refusal.
 *
 * The second read mirrors the bridge's fallback order — proc_name first, then
 * `pbi_name` and `pbi_comm`, and `proc_pidpath` before the saved exec path —
 * because that order is the mapping under test.
 */
static void hm_check_issue_metadata(
    const HMProcessIssue *issues,
    int written_issues,
    const HMOwnProcesses *own
) {
    int compared = 0;
    int mismatched = 0;
    int first = -1;
    const char *reason = "";
    for (int index = 0; index < written_issues; ++index) {
        const HMProcessIssue *issue = &issues[index];
        struct proc_bsdinfo info;
        memset(&info, 0, sizeof(info));
        if (proc_pidinfo(issue->pid, PROC_PIDTBSDINFO, 0, &info, (int)sizeof(info)) !=
            (int)sizeof(info)) {
            continue;
        }

        char name[HM_PROCESS_NAME_SIZE];
        memset(name, 0, sizeof(name));
        if (proc_name(issue->pid, name, (uint32_t)sizeof(name)) <= 0) {
            snprintf(
                name,
                sizeof(name),
                "%s",
                info.pbi_name[0] != '\0' ? info.pbi_name : info.pbi_comm
            );
        }
        char path[HM_PROCESS_PATH_SIZE];
        hm_fresh_exec_path(issue->pid, path, sizeof(path));

        ++compared;
        const char *disagreement = NULL;
        if (strcmp(issue->name, name) != 0) {
            disagreement = "name";
        } else if (issue->uid != info.pbi_uid) {
            disagreement = "uid";
        } else if (issue->parent_pid != (int32_t)info.pbi_ppid) {
            disagreement = "parent pid";
        } else if (strcmp(issue->executable_path, path) != 0) {
            disagreement = "executable path";
        }
        if (disagreement != NULL) {
            ++mismatched;
            if (first < 0) {
                first = index;
                reason = disagreement;
            }
        }
    }

    CHECK(
        "processes.issue-metadata-matches-a-fresh-read",
        compared >= HM_ISSUE_METADATA_MINIMUM &&
            mismatched * HM_ISSUE_METADATA_MISMATCH_DIVISOR <= compared,
        "expected at least %d issues comparable against a fresh read and at most a "
            "%dth of them to disagree, compared %d of %d and %d disagreed "
            "(first at %d over the %s: '%s'/uid %u/parent %d); the comparable ones "
            "are this account's own, which numbered %d before this check forked %d "
            "more",
        HM_ISSUE_METADATA_MINIMUM,
        HM_ISSUE_METADATA_MISMATCH_DIVISOR,
        compared,
        written_issues,
        mismatched,
        first,
        reason,
        first >= 0 ? issues[first].name : "",
        first >= 0 ? issues[first].uid : 0U,
        first >= 0 ? issues[first].parent_pid : 0,
        own->owned,
        own->forks
    );
}

/*
 * The caller reports `total` as the number of processes on the machine and
 * `written` as the number it could measure, so a total below the written count
 * would understate coverage. Issues are capped by their own array, while
 * `inaccessible` counts every miss including the ones that did not fit, which is
 * why every listed pid has to end up in exactly one of the two.
 */
static void hm_check_listing_consistency(
    int written,
    int total,
    int inaccessible,
    int written_issues
) {
    CHECK(
        "processes.listing-is-consistent",
        written > 0 &&
            written <= HM_LISTING_SAMPLE_CAPACITY &&
            total >= written &&
            written_issues > 0 &&
            written_issues <= HM_LISTING_ISSUE_CAPACITY &&
            inaccessible >= written_issues &&
            written + inaccessible == total,
        "expected 0 < written <= %d, written + inaccessible == total, "
            "0 < issues <= %d, inaccessible >= issues; "
            "got written=%d total=%d issues=%d inaccessible=%d",
        HM_LISTING_SAMPLE_CAPACITY,
        HM_LISTING_ISSUE_CAPACITY,
        written,
        total,
        written_issues,
        inaccessible
    );
}

/*
 * `total` comes out of the same listing the samples do, so every invariant
 * `hm_check_listing_consistency` asserts survives a PID list truncated to a fraction of the machine: the numbers stay
 * consistent with each other and understate the machine together. Only a count
 * taken independently notices, which is the whole reason
 * `hm_process_list_capacity` sizes that list from the caller's capacity as well
 * as from a fresh count.
 */
static void hm_check_total_against_a_fresh_count(int total, int before, int after) {
    CHECK(
        "processes.total-matches-a-fresh-count",
        before > 0 &&
            after > 0 &&
            total >= hm_lowest_int(before, after) - HM_PROCESS_COUNT_TOLERANCE &&
            total <= hm_highest_int(before, after) + HM_PROCESS_COUNT_TOLERANCE,
        "expected a total within %d of a fresh count, got total=%d against %d then %d",
        HM_PROCESS_COUNT_TOLERANCE,
        total,
        before,
        after
    );
}

/* What is wrong with the first sample that is not well formed, or NULL. */
static const char *hm_malformed_sample(const HMProcessSample *sample) {
    if (sample->pid <= 0) {
        return "pid is not positive";
    }
    if (memchr(sample->name, '\0', sizeof(sample->name)) == NULL) {
        return "name is not terminated within HM_PROCESS_NAME_SIZE";
    }
    if (sample->name[0] == '\0') {
        /*
         * A guard, not coverage of the `pid-N` fallback that makes it hold: on a
         * live machine proc_name answers for every process the caller can read
         * the rusage of, so this branch never fires and deleting the fallback
         * leaves the check green. Reaching it needs a process that vanishes
         * between the rusage read and the metadata read, which cannot be forced
         * from outside — CLAUDE.md lists the fallback among the accepted gaps.
         */
        return "name is empty";
    }
    if (memchr(sample->executable_path, '\0', sizeof(sample->executable_path)) == NULL) {
        return "executable path is not terminated within HM_PROCESS_PATH_SIZE";
    }
    return NULL;
}

static void hm_check_samples_are_well_formed(const HMProcessSample *samples, int written) {
    int malformed = -1;
    const char *reason = "";
    for (int index = 0; index < written && malformed < 0; ++index) {
        reason = hm_malformed_sample(&samples[index]);
        if (reason != NULL) {
            malformed = index;
        }
    }
    CHECK(
        "processes.samples-are-well-formed",
        written > 0 && malformed < 0,
        "sample %d of %d (pid %d): %s",
        malformed,
        written,
        malformed >= 0 ? (int)samples[malformed].pid : 0,
        malformed >= 0 ? reason : ""
    );
}

/* What is wrong with the first issue that is not well formed, or NULL. */
static const char *hm_malformed_issue(const HMProcessIssue *issue) {
    /*
     * A pid of zero is allowed on purpose: proc_listallpids reports kernel_task,
     * whose rusage an ordinary user cannot read, so it arrives as an issue rather
     * than as a sample.
     */
    if (issue->pid < 0) {
        return "pid is negative";
    }
    if (issue->reason != HM_PROCESS_ISSUE_CAPACITY &&
        issue->reason != HM_PROCESS_ISSUE_RUSAGE) {
        return "reason is neither capacity nor rusage";
    }
    if (issue->reason == HM_PROCESS_ISSUE_RUSAGE && issue->error_code == 0) {
        return "an unreadable rusage carries no errno";
    }
    if (memchr(issue->name, '\0', sizeof(issue->name)) == NULL) {
        return "name is not terminated within HM_PROCESS_NAME_SIZE";
    }
    if (memchr(issue->executable_path, '\0', sizeof(issue->executable_path)) == NULL) {
        return "executable path is not terminated within HM_PROCESS_PATH_SIZE";
    }
    return NULL;
}

/*
 * The issue array is where the caller learns why a process is missing from the
 * sample. With the sample array far narrower than the machine the capacity branch
 * fires for most of the listing, so a run that produced no capacity issue at all
 * means that branch stopped being reached — and a reason that is neither
 * constant, or a name running past its array, would reach the report unnoticed.
 *
 * What the metadata on an issue actually says is a separate check: everything
 * asserted here is satisfied by the memset that precedes the
 * `hm_read_process_metadata` call, so deleting that call leaves this green.
 * `processes.issue-metadata-matches-a-fresh-read` is what reads the fields.
 */
static void hm_check_issues_are_well_formed(
    const HMProcessIssue *issues,
    int written_issues,
    const HMOwnProcesses *own
) {
    int malformed = -1;
    const char *reason = "";
    int capacity_issues = 0;
    for (int index = 0; index < written_issues; ++index) {
        if (issues[index].reason == HM_PROCESS_ISSUE_CAPACITY) {
            ++capacity_issues;
        }
        if (malformed < 0) {
            reason = hm_malformed_issue(&issues[index]);
            if (reason != NULL) {
                malformed = index;
            }
        }
    }
    CHECK(
        "processes.issues-are-well-formed",
        written_issues > 0 && malformed < 0 && capacity_issues > 0,
        "issue %d of %d (pid %d, reason %d): %s; %d of them blamed capacity, which "
            "needs the account to own more than the %d rusage-readable processes "
            "that fill the sample array — it owned %d and this check forked %d more",
        malformed,
        written_issues,
        malformed >= 0 ? (int)issues[malformed].pid : 0,
        malformed >= 0 ? issues[malformed].reason : 0,
        malformed >= 0 ? reason : "",
        capacity_issues,
        HM_LISTING_SAMPLE_CAPACITY,
        own->owned,
        own->forks
    );
}

/*
 * One listing feeds five checks. Attribution is switched off (both budgets zero):
 * the walk it would perform is what the attribution checks cover directly, and
 * running it over every sample here would cost seconds and prove nothing new.
 *
 * The sample array is deliberately far narrower than the machine, so that the
 * capacity branch — and with it the issue array — is exercised on every run.
 */
static void hm_check_process_listing(void) {
    HMProcessSample *samples = (HMProcessSample *)calloc(
        HM_LISTING_SAMPLE_CAPACITY,
        sizeof(HMProcessSample)
    );
    HMProcessIssue *issues = (HMProcessIssue *)calloc(
        HM_LISTING_ISSUE_CAPACITY,
        sizeof(HMProcessIssue)
    );
    if (samples == NULL || issues == NULL) {
        free(samples);
        free(issues);
        CHECK("processes.listing-is-consistent", 0, "out of memory");
        CHECK("processes.total-matches-a-fresh-count", 0, "out of memory");
        CHECK("processes.samples-are-well-formed", 0, "out of memory");
        CHECK("processes.issues-are-well-formed", 0, "out of memory");
        CHECK("processes.issue-metadata-matches-a-fresh-read", 0, "out of memory");
        return;
    }

    HMOwnProcesses own = hm_top_up_own_processes();

    int total = -1;
    int inaccessible = -1;
    int written_issues = -1;
    const int counted_before = hm_count_processes();
    const int written = hm_list_processes(
        samples,
        HM_LISTING_SAMPLE_CAPACITY,
        issues,
        HM_LISTING_ISSUE_CAPACITY,
        0,
        0,
        &total,
        &inaccessible,
        &written_issues
    );
    const int counted_after = hm_count_processes();

    hm_check_listing_consistency(written, total, inaccessible, written_issues);
    hm_check_total_against_a_fresh_count(total, counted_before, counted_after);
    hm_check_samples_are_well_formed(samples, written);
    hm_check_issues_are_well_formed(issues, written_issues, &own);
    hm_check_issue_metadata(issues, written_issues, &own);

    hm_release_own_processes(&own);
    free(samples);
    free(issues);
}

/*
 * The sample the harness reports about itself, against what the harness knows
 * about itself.
 *
 * This is the only check that reads the four fields `hm_read_process_metadata`
 * fills on the sample path. Deleting the call from that path leaves every other
 * `processes.*` check green — the `pid-N` fallback refills the name, which is all
 * `processes.samples-are-well-formed` asks for — and so does replacing the
 * guarded fallback with an unconditional `pid-%d`, which would hand application
 * grouping a machine of processes named after their pids.
 *
 * proc_name truncates a long name, so the name is compared as a prefix of the
 * basename `proc_pidpath` reports rather than as its equal; the path itself is
 * compared whole.
 */
static void hm_check_own_metadata(const HMProcessSample *own, int written, const char *own_path) {
    const char *separator = strrchr(own_path, '/');
    const char *own_name = separator != NULL ? separator + 1 : own_path;
    const size_t reported_length = own == NULL ? 0 : strlen(own->name);

    CHECK(
        "processes.own-sample-carries-metadata",
        own != NULL &&
            reported_length > 0 &&
            strncmp(own_name, own->name, reported_length) == 0 &&
            strcmp(own->executable_path, own_path) == 0 &&
            own->uid == (uint32_t)geteuid() &&
            own->parent_pid == (int32_t)getppid(),
        "expected pid %d in a listing of %d to carry a name starting '%s', the path "
            "'%s', uid %u and parent %d; got name '%s', path '%s', uid %u, parent %d",
        (int)getpid(),
        written,
        own_name,
        own_path,
        (uint32_t)geteuid(),
        (int)getppid(),
        own == NULL ? "(no sample of this process)" : own->name,
        own == NULL ? "" : own->executable_path,
        own == NULL ? 0U : own->uid,
        own == NULL ? 0 : own->parent_pid
    );
}

/*
 * Both readings of this process, which every field of its sample is bracketed by.
 *
 * A bracket rather than a tolerance, because the bridge reads its value between
 * the two: a counter that only grows is pinned exactly by them, whatever the
 * machine did in between, and no allowance has to be guessed. Only the figures
 * that also fall — the residency ones — carry slack. `anchors.h` holds the table
 * the pair is compared through.
 */
typedef struct {
    struct rusage_info_v6 usage;
    struct proc_taskinfo task;
    int usage_status;
    int task_size;
} HMOwnAnchor;

static HMOwnAnchor hm_read_own_anchor(void) {
    HMOwnAnchor anchor;
    memset(&anchor, 0, sizeof(anchor));
    anchor.usage_status = proc_pid_rusage(
        getpid(),
        RUSAGE_INFO_V6,
        (rusage_info_t *)&anchor.usage
    );
    anchor.task_size = proc_pidinfo(
        getpid(),
        PROC_PIDTASKINFO,
        0,
        &anchor.task,
        (int)sizeof(anchor.task)
    );
    return anchor;
}

/*
 * How far the three residency figures may sit outside the pair that brackets
 * them. They are the only fields of the sample that both rise and fall, and the
 * listing allocates and touches megabytes between the two readings; measured
 * here, the pair moved 16 KiB — one page — over the listing. The allowance is
 * far below the distance between the fields it separates (5.8 MB resident
 * against 1.5 MB of footprint on this process).
 */
#define HM_OWN_RESIDENCY_SLACK (4ULL * 1024ULL * 1024ULL)

/*
 * What `hm_check_own_listing` did to this process before the readings, and
 * whether each of it took.
 *
 * Four fields of the sample say nothing about an untouched harness — it reads and
 * writes no disk, runs one thread and wires no memory — so the check makes those
 * conditions itself. Each preparation can fail for a reason that belongs to the
 * machine and not to the bridge: a kernel that refuses `mlock`, a filesystem that
 * serves the read from cache despite `F_NOCACHE`, a `pthread_create` that does
 * not. Carrying the outcome to the check is what lets the failure name that
 * instead of reading as a mapping regression.
 */
typedef struct {
    uint64_t written_bytes;
    uint64_t read_bytes;
    int disk_failure;
    uint64_t locked_bytes;
    int lock_failure;
    int parked;
} HMOwnPreparations;

/*
 * Every number the bridge copies out of `proc_pid_rusage` and `PROC_PIDTASKINFO`,
 * against the same two calls made by the test around the listing.
 *
 * Nothing else in either harness reads this mapping. `own-sample-carries-metadata`
 * reads the four metadata fields, and `selftest` bounds the footprint, the
 * *sum* of the two CPU times and two counters against zero — so every "right
 * number in the wrong field" mutation survives all of them: user against system
 * time, disk bytes read against written, `resident_bytes` filled from
 * `ri_phys_footprint`, faults against copy-on-write faults, `thread_count` from
 * `pti_numrunning`, a `started_at` of zero, `pageins` from `ri_interrupt_wkups`.
 * All eight fields reach the report through `DarwinSystemCollector`.
 *
 * An untouched harness leaves four of the fields unable to say anything: it
 * neither reads nor writes a disk, it runs one thread, and it wires no memory, so
 * `disk_bytes_read` equals `disk_bytes_written` at zero, `thread_count` equals
 * `running_thread_count`, and `wired_bytes` sits at a zero the bracket cannot
 * tell from a hard-coded one. `hm_check_own_listing` therefore gives it 4 MiB of
 * flushed writes, 1 MiB read back past the cache, a parked thread and 16 MiB of
 * locked memory before taking the readings, and the check below asserts that each
 * of those took effect.
 *
 * The two time fields are converted with the bridge's own `hm_mach_time_to_ns`
 * and the four 32-bit counters widened with its own `hm_uint32_counter`, so this
 * check is about which field went where and not about that arithmetic; the
 * arithmetic is `pure.mach-time-matches-uptime-clock` and the `pure.uint32-*`
 * checks.
 */
static void hm_check_own_fields(
    const HMProcessSample *own,
    const HMOwnAnchor *before,
    const HMOwnAnchor *after,
    const HMOwnPreparations *prepared
) {
    const struct rusage_info_v6 *first = &before->usage;
    const struct rusage_info_v6 *last = &after->usage;
    const struct proc_taskinfo *first_task = &before->task;
    const struct proc_taskinfo *last_task = &after->task;
    const int readable = own != NULL &&
        before->usage_status == 0 &&
        after->usage_status == 0 &&
        before->task_size == (int)sizeof(before->task) &&
        after->task_size == (int)sizeof(after->task);
    if (!readable) {
        CHECK(
            "processes.own-sample-matches-a-fresh-rusage",
            0,
            "no pair of readings to compare against: own sample %d, rusage %d/%d, "
                "task info %d/%d",
            own != NULL,
            before->usage_status,
            after->usage_status,
            before->task_size,
            after->task_size
        );
        return;
    }

    const HMBracketedField fields[] = {
        {"started_at", own->started_at, first->ri_proc_start_abstime, last->ri_proc_start_abstime},
        {
            "user_time_ns",
            own->user_time_ns,
            hm_mach_time_to_ns(first->ri_user_time),
            hm_mach_time_to_ns(last->ri_user_time),
        },
        {
            "system_time_ns",
            own->system_time_ns,
            hm_mach_time_to_ns(first->ri_system_time),
            hm_mach_time_to_ns(last->ri_system_time),
        },
        {
            "package_idle_wakeups",
            own->package_idle_wakeups,
            first->ri_pkg_idle_wkups,
            last->ri_pkg_idle_wkups,
        },
        {
            "interrupt_wakeups",
            own->interrupt_wakeups,
            first->ri_interrupt_wkups,
            last->ri_interrupt_wkups,
        },
        {"pageins", own->pageins, first->ri_pageins, last->ri_pageins},
        {
            "disk_bytes_read",
            own->disk_bytes_read,
            first->ri_diskio_bytesread,
            last->ri_diskio_bytesread,
        },
        {
            "disk_bytes_written",
            own->disk_bytes_written,
            first->ri_diskio_byteswritten,
            last->ri_diskio_byteswritten,
        },
        {
            "logical_writes_bytes",
            own->logical_writes_bytes,
            first->ri_logical_writes,
            last->ri_logical_writes,
        },
        {"instructions", own->instructions, first->ri_instructions, last->ri_instructions},
        {"cycles", own->cycles, first->ri_cycles, last->ri_cycles},
        {"energy_nanojoules", own->energy_nanojoules, first->ri_energy_nj, last->ri_energy_nj},
        {"billed_energy", own->billed_energy, first->ri_billed_energy, last->ri_billed_energy},
        {
            "lifetime_max_physical_footprint_bytes",
            own->lifetime_max_physical_footprint_bytes,
            first->ri_lifetime_max_phys_footprint,
            last->ri_lifetime_max_phys_footprint,
        },
        {
            "wired_bytes",
            own->wired_bytes,
            hm_below(hm_lowest(first->ri_wired_size, last->ri_wired_size), HM_OWN_RESIDENCY_SLACK),
            hm_highest(first->ri_wired_size, last->ri_wired_size) + HM_OWN_RESIDENCY_SLACK,
        },
        {
            "resident_bytes",
            own->resident_bytes,
            hm_below(
                hm_lowest(first->ri_resident_size, last->ri_resident_size),
                HM_OWN_RESIDENCY_SLACK
            ),
            hm_highest(first->ri_resident_size, last->ri_resident_size) + HM_OWN_RESIDENCY_SLACK,
        },
        {
            "physical_footprint_bytes",
            own->physical_footprint_bytes,
            hm_below(
                hm_lowest(first->ri_phys_footprint, last->ri_phys_footprint),
                HM_OWN_RESIDENCY_SLACK
            ),
            hm_highest(first->ri_phys_footprint, last->ri_phys_footprint) + HM_OWN_RESIDENCY_SLACK,
        },
        {
            "faults",
            own->faults,
            hm_uint32_counter(first_task->pti_faults),
            hm_uint32_counter(last_task->pti_faults),
        },
        {
            "copy_on_write_faults",
            own->copy_on_write_faults,
            hm_uint32_counter(first_task->pti_cow_faults),
            hm_uint32_counter(last_task->pti_cow_faults),
        },
        {
            "mach_system_calls",
            own->mach_system_calls,
            hm_uint32_counter(first_task->pti_syscalls_mach),
            hm_uint32_counter(last_task->pti_syscalls_mach),
        },
        {
            "unix_system_calls",
            own->unix_system_calls,
            hm_uint32_counter(first_task->pti_syscalls_unix),
            hm_uint32_counter(last_task->pti_syscalls_unix),
        },
        {
            "context_switches",
            own->context_switches,
            hm_uint32_counter(first_task->pti_csw),
            hm_uint32_counter(last_task->pti_csw),
        },
        {
            "thread_count",
            own->thread_count,
            hm_lowest((uint64_t)first_task->pti_threadnum, (uint64_t)last_task->pti_threadnum),
            hm_highest((uint64_t)first_task->pti_threadnum, (uint64_t)last_task->pti_threadnum),
        },
        {
            "running_thread_count",
            own->running_thread_count,
            hm_lowest((uint64_t)first_task->pti_numrunning, (uint64_t)last_task->pti_numrunning),
            hm_highest((uint64_t)first_task->pti_numrunning, (uint64_t)last_task->pti_numrunning),
        },
    };
    uint64_t reported = 0;
    uint64_t low = 0;
    uint64_t high = 0;
    const char *mismatch = HM_FIRST_OUTSIDE_RANGE(fields, &reported, &low, &high);

    /*
     * The four pairs a transposition would hide in are asserted to differ, so
     * that a green result means the comparison could have separated them: the two
     * CPU times, the two disk directions, the two thread counts and the two
     * residency figures.
     *
     * Two fields need more than that, because a bracket whose low end is zero
     * accepts a field that is always zero: `disk_bytes_read` unless the read back
     * before the listing reached the device, and `wired_bytes` unless the lock
     * before it holds more than the residency slack. Both preparations are
     * asserted rather than assumed, and the detail quotes what each of them did —
     * a machine that refuses `mlock`, or serves the read from cache, says so in
     * those words instead of leaving two fields quietly unchecked or looking like
     * a mapping regression.
     *
     * The sanitized build is the one machine known not to lock: AddressSanitizer
     * intercepts `mlock` and makes it a no-op — measured, it returns 0 while
     * `ri_wired_size` stays at 0 — so the demand is the ordinary pass's, which is
     * where `wired_bytes` is pinned. The read back works under both.
     */
    const int separated = last->ri_user_time != last->ri_system_time &&
        last->ri_diskio_bytesread != last->ri_diskio_byteswritten &&
        last_task->pti_threadnum != last_task->pti_numrunning &&
        last->ri_resident_size != last->ri_phys_footprint;
    const int lifted = first->ri_diskio_bytesread > 0 &&
        (HM_TEST_SANITIZED ||
            hm_lowest(first->ri_wired_size, last->ri_wired_size) > HM_OWN_RESIDENCY_SLACK);

    CHECK(
        "processes.own-sample-matches-a-fresh-rusage",
        mismatch == NULL && separated && lifted,
        "expected every field of pid %d's sample within the pair of readings taken "
            "around the listing, got %s reporting %llu against %llu..%llu; the "
            "readings themselves separate user from system time by %llu ticks, read "
            "from written bytes by %llu, threads from running threads by %d and "
            "resident bytes from the footprint by %llu, and each has to be non-zero; "
            "the read back before the listing has to have reached the device (%llu "
            "bytes) and the wired figure to exceed the %llu bytes of slack (%llu). "
            "This machine wrote %llu bytes and read %llu back past the cache (%s), "
            "locked %llu bytes (%s) and parked a second thread (%d) — each of those "
            "is a property of the machine, not of the bridge",
        (int)getpid(),
        mismatch == NULL ? "no mismatch" : mismatch,
        (unsigned long long)reported,
        (unsigned long long)low,
        (unsigned long long)high,
        (unsigned long long)hm_absolute_difference(last->ri_user_time, last->ri_system_time),
        (unsigned long long)hm_absolute_difference(
            last->ri_diskio_bytesread,
            last->ri_diskio_byteswritten
        ),
        last_task->pti_threadnum - last_task->pti_numrunning,
        (unsigned long long)hm_absolute_difference(
            last->ri_resident_size,
            last->ri_phys_footprint
        ),
        (unsigned long long)first->ri_diskio_bytesread,
        (unsigned long long)HM_OWN_RESIDENCY_SLACK,
        (unsigned long long)hm_lowest(first->ri_wired_size, last->ri_wired_size),
        (unsigned long long)prepared->written_bytes,
        (unsigned long long)prepared->read_bytes,
        prepared->disk_failure == 0 ? "no error" : strerror(prepared->disk_failure),
        (unsigned long long)prepared->locked_bytes,
        prepared->lock_failure == 0 ? "no error" : strerror(prepared->lock_failure),
        prepared->parked
    );
}

/*
 * How many issues of the rusage branch have to be comparable against a fresh
 * `proc_pidpath`, and how many of them may disagree. The population is the
 * processes of other users — 275 of them here, of which 274 had a readable path —
 * and it is the branch `DarwinSystemCollector` actually takes: its sample array is
 * `MIN_PROCESS_CAPACITY` wide plus headroom, so the capacity branch never fires
 * and every issue it reports comes from here.
 *
 * The minimum is lower than the one `processes.issue-metadata-matches-a-fresh-read`
 * asks for because the population is smaller: an account that owns most of the
 * machine leaves few processes it cannot read the rusage of. It is also the one
 * requirement of this suite that cannot be made rather than asked for — a test
 * cannot start a process of another user — so both checks over this branch print
 * what they counted and say whose property it is. Every macOS carries a hundred
 * of root's, so the floor is far below what any machine offers.
 */
#define HM_RUSAGE_ISSUE_MINIMUM 16
#define HM_RUSAGE_ISSUE_MISMATCH_DIVISOR 16

/*
 * The executable path of an issue the rusage branch produced, against a fresh
 * read of it — `hm_fresh_exec_path`, so the fallback the bridge applies is part
 * of what is compared rather than a source of disagreement.
 *
 * The sibling check on the narrow listing cannot see this branch at all: it needs
 * `PROC_PIDTBSDINFO` to build its anchor, and that call is refused for exactly
 * the processes the rusage branch reports — 0 of 275 were readable here. So
 * deleting `hm_read_process_metadata` from the rusage branch of
 * `hm_list_processes` used to leave both harnesses green while emptying the only
 * field of an issue that survives the refusal: proc_name is refused as well, and
 * the uid stays UINT32_MAX by design, but `proc_pidpath` answers for another
 * user's process and the report shows what it says.
 */
static void hm_check_rusage_issue_paths(const HMProcessIssue *issues, int written_issues) {
    int compared = 0;
    int mismatched = 0;
    int first = -1;
    for (int index = 0; index < written_issues; ++index) {
        const HMProcessIssue *issue = &issues[index];
        if (issue->reason != HM_PROCESS_ISSUE_RUSAGE) {
            continue;
        }
        char path[HM_PROCESS_PATH_SIZE];
        hm_fresh_exec_path(issue->pid, path, sizeof(path));
        if (path[0] == '\0') {
            continue;
        }

        ++compared;
        if (strcmp(issue->executable_path, path) != 0) {
            ++mismatched;
            if (first < 0) {
                first = index;
            }
        }
    }

    CHECK(
        "processes.rusage-issue-path-matches-a-fresh-read",
        compared >= HM_RUSAGE_ISSUE_MINIMUM &&
            mismatched * HM_RUSAGE_ISSUE_MISMATCH_DIVISOR <= compared,
        "expected at least %d rusage issues with a readable path and at most a %dth "
            "of them to disagree with it, compared %d of %d issues and %d disagreed "
            "(first at %d, pid %d, reporting '%s'); the population is the processes "
            "of other users, which is a property of the machine and not of the bridge",
        HM_RUSAGE_ISSUE_MINIMUM,
        HM_RUSAGE_ISSUE_MISMATCH_DIVISOR,
        compared,
        written_issues,
        mismatched,
        first,
        first >= 0 ? (int)issues[first].pid : 0,
        first >= 0 ? issues[first].executable_path : ""
    );
}

/*
 * The uid of an issue the rusage branch produced, against a fresh
 * `PROC_PIDTBSDINFO`, in both directions: a process whose metadata the caller
 * cannot read has to be reported as unknown, and one whose metadata it can read
 * has to be reported with that uid.
 *
 * `UINT32_MAX` is the only way the bridge has of saying "unknown" —
 * `DarwinSystemCollector.toNullableUid()` maps exactly that value to null, and
 * the report prints "unknown" for it. Nothing else in either harness reads the
 * uid of a rusage issue: the metadata check next door needs `PROC_PIDTBSDINFO` to
 * build its anchor and skips every issue of this branch for that very reason, and
 * the path check compares only the path. So `*uid = 0` in
 * `hm_read_process_metadata` used to leave every other check green while turning
 * every process the collector could not read into root's — 276 of 276 issues on
 * this machine.
 *
 * A refusal is told from a process that has since exited by `kill(pid, 0)`,
 * whose ESRCH is the one thing that says "gone" rather than "not yours". The
 * mismatch allowance is the sibling check's, and for the same reason: a pid
 * reused between the listing and this loop would otherwise be a failure about the
 * machine. The sentinel mutation moves every issue at once and clears it by two
 * orders of magnitude.
 */
#define HM_ISSUE_UID_MINIMUM 16
#define HM_ISSUE_UID_MISMATCH_DIVISOR 16

static void hm_check_rusage_issue_uids(const HMProcessIssue *issues, int written_issues) {
    int refused = 0;
    int readable = 0;
    int mismatched = 0;
    int first = -1;
    for (int index = 0; index < written_issues; ++index) {
        const HMProcessIssue *issue = &issues[index];
        if (issue->reason != HM_PROCESS_ISSUE_RUSAGE) {
            continue;
        }
        struct proc_bsdinfo info;
        memset(&info, 0, sizeof(info));
        const int size = proc_pidinfo(
            issue->pid,
            PROC_PIDTBSDINFO,
            0,
            &info,
            (int)sizeof(info)
        );
        int expected_unknown = 0;
        if (size == (int)sizeof(info)) {
            ++readable;
        } else {
            errno = 0;
            if (kill(issue->pid, 0) != 0 && errno == ESRCH) {
                continue;
            }
            ++refused;
            expected_unknown = 1;
        }
        const uint32_t expected = expected_unknown ? UINT32_MAX : info.pbi_uid;
        if (issue->uid != expected) {
            ++mismatched;
            if (first < 0) {
                first = index;
            }
        }
    }

    const int compared = refused + readable;
    CHECK(
        "processes.rusage-issue-uid-is-unknown",
        refused >= HM_ISSUE_UID_MINIMUM &&
            mismatched * HM_ISSUE_UID_MISMATCH_DIVISOR <= compared,
        "expected at least %d rusage issues whose metadata a fresh read is refused, "
            "all of them reporting uid %u, and every readable one reporting the uid "
            "of that read; got %d refused and %d readable of %d issues with %d "
            "disagreeing (first at %d, pid %d, reporting uid %u); the refused ones "
            "are the processes of other users, which is a property of the machine "
            "and not of the bridge",
        HM_ISSUE_UID_MINIMUM,
        UINT32_MAX,
        refused,
        readable,
        written_issues,
        mismatched,
        first,
        first >= 0 ? (int)issues[first].pid : 0,
        first >= 0 ? issues[first].uid : 0
    );
}

/*
 * Moves both disk figures of this process off zero before the readings are taken,
 * which is what lets the bracket separate `disk_bytes_read` from
 * `disk_bytes_written` — and each of them from a hard-coded zero. A harness that
 * has touched no disk reports both as 0, and a bracket that starts at 0 accepts a
 * field that is always 0.
 *
 * `F_FULLFSYNC` rather than `fsync`, because only that reaches the device.
 * `F_NOCACHE` on the same descriptor before the write is what makes the read
 * back count: without it the pages are still in the unified buffer cache and the
 * read is served from memory — measured, `ri_diskio_bytesread` stayed at 0 with
 * the flush alone. Measured with it, five runs out of five: 4194304 bytes written
 * and exactly 1048576 read, and the two stay different numbers as the
 * transposition guard needs them to be. Reading a file this process did not just
 * write is not an alternative — `/usr/lib/dyld` gave 413696 bytes on one run and
 * 0 on the next, depending on what the cache already held.
 *
 * What each step managed is carried back rather than dropped: on a machine whose
 * filesystem answers the read from somewhere other than the device, the
 * difference between "the bridge stopped filling `disk_bytes_read`" and "this run
 * never read a byte off a disk" is exactly this outcome.
 */
#define HM_OWN_DISK_BYTES (4 * 1024 * 1024)
#define HM_OWN_DISK_READ_BYTES (1024 * 1024)

static void hm_write_to_disk(HMOwnPreparations *prepared) {
    char path[] = "/tmp/harmon-native-test-io.XXXXXX";
    const int descriptor = mkstemp(path);
    if (descriptor < 0) {
        prepared->disk_failure = errno;
        return;
    }
    unlink(path);
    fcntl(descriptor, F_NOCACHE, 1);
    char *block = (char *)valloc(HM_OWN_DISK_BYTES);
    if (block == NULL) {
        prepared->disk_failure = ENOMEM;
        close(descriptor);
        return;
    }
    memset(block, 'w', HM_OWN_DISK_BYTES);
    if (write(descriptor, block, HM_OWN_DISK_BYTES) != (ssize_t)HM_OWN_DISK_BYTES) {
        prepared->disk_failure = errno;
    } else {
        prepared->written_bytes = HM_OWN_DISK_BYTES;
        fcntl(descriptor, F_FULLFSYNC);
        if (lseek(descriptor, 0, SEEK_SET) != 0) {
            prepared->disk_failure = errno;
        } else {
            const ssize_t read_bytes = read(descriptor, block, HM_OWN_DISK_READ_BYTES);
            if (read_bytes < 0) {
                prepared->disk_failure = errno;
            } else {
                prepared->read_bytes = (uint64_t)read_bytes;
            }
        }
    }
    free(block);
    close(descriptor);
}

/*
 * Wires memory so that `ri_wired_size` is a number this process chose rather than
 * the 0 every ordinary process reports. Without it the bracket around
 * `wired_bytes` runs from 0 to the 4 MiB of residency slack, and everything below
 * 4 MiB passes — a hard-coded zero, and `ri_resident_size / 4` with it. Wiring
 * 16 MiB puts the low end of the bracket at 12 MiB, which both of those fail.
 *
 * Measured here: `mlock` of 16 MiB succeeds for an ordinary user and moves
 * `ri_wired_size` from 0 to exactly 16777216. Neither limit that could refuse it
 * is anywhere near: RLIMIT_MEMLOCK is unlimited by default on macOS, and
 * `vm.user_wire_limit` is a fraction of the memory installed (30 GB here). A
 * machine that refuses the lock all the same leaves the field where it was, and
 * the check quotes the errno rather than skipping.
 */
#define HM_OWN_WIRED_BYTES (16 * 1024 * 1024)

typedef struct {
    void *region;
    int locked;
} HMWiredMemory;

static HMWiredMemory hm_wire_memory(HMOwnPreparations *prepared) {
    HMWiredMemory wired = {NULL, 0};
    void *region = mmap(
        NULL,
        HM_OWN_WIRED_BYTES,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANON,
        -1,
        0
    );
    if (region == MAP_FAILED) {
        prepared->lock_failure = errno;
        return wired;
    }
    memset(region, 'w', HM_OWN_WIRED_BYTES);
    wired.region = region;
    errno = 0;
    wired.locked = mlock(region, HM_OWN_WIRED_BYTES) == 0;
    if (wired.locked) {
        prepared->locked_bytes = HM_OWN_WIRED_BYTES;
    } else {
        prepared->lock_failure = errno;
    }
    return wired;
}

static void hm_release_wired_memory(HMWiredMemory *wired) {
    if (wired->region == NULL) {
        return;
    }
    if (wired->locked) {
        munlock(wired->region, HM_OWN_WIRED_BYTES);
    }
    munmap(wired->region, HM_OWN_WIRED_BYTES);
    wired->region = NULL;
    wired->locked = 0;
}

/*
 * Parks a second thread for as long as the listing takes, so that
 * `pti_threadnum` (2) and `pti_numrunning` (1) are different numbers while the
 * bridge reads them. It blocks on a pipe rather than sleeping: a sleeper is
 * counted the same way, but a spinner would be running and the two counters would
 * agree again.
 */
typedef struct {
    int wake[2];
    int parked;
} HMParkedThread;

static void *hm_park_thread(void *argument) {
    HMParkedThread *thread = (HMParkedThread *)argument;
    char byte = 0;
    while (read(thread->wake[0], &byte, 1) < 0 && errno == EINTR) {
    }
    return NULL;
}

/*
 * Waits until the kernel stops counting the second thread as running, so that
 * `pti_threadnum` and `pti_numrunning` are two different numbers in both
 * readings. A thread just created reads as running for a moment — measured, in
 * the first of ten rounds and none of the others — and over a range that starts
 * at 2 a `running_thread_count` filled from `pti_threadnum` would pass.
 */
#define HM_PARK_ATTEMPTS 100

static void hm_wait_for_the_park(void) {
    for (int attempt = 0; attempt < HM_PARK_ATTEMPTS; ++attempt) {
        struct proc_taskinfo task;
        memset(&task, 0, sizeof(task));
        if (proc_pidinfo(getpid(), PROC_PIDTASKINFO, 0, &task, (int)sizeof(task)) !=
            (int)sizeof(task)) {
            return;
        }
        if (task.pti_numrunning < task.pti_threadnum) {
            return;
        }
        usleep(1000);
    }
}

/*
 * The full-width listing, and the four checks that read it.
 *
 * Full width so that this process is in it: the slots the narrow listing uses are
 * filled long before a pid this recent. The width is also what makes the issue
 * array the rusage branch's, which is the branch the collector takes.
 */
static void hm_check_own_listing(void) {
    const int capacity = hm_count_processes() + HM_PROCESS_LIST_HEADROOM;
    const int issue_capacity = HM_LISTING_ISSUE_CAPACITY;
    HMProcessSample *samples = (HMProcessSample *)calloc(
        (size_t)capacity,
        sizeof(HMProcessSample)
    );
    HMProcessIssue *issues = (HMProcessIssue *)calloc(
        (size_t)issue_capacity,
        sizeof(HMProcessIssue)
    );
    char own_path[HM_PROCESS_PATH_SIZE];
    memset(own_path, 0, sizeof(own_path));
    const int path_length = proc_pidpath(getpid(), own_path, (uint32_t)sizeof(own_path));
    if (samples == NULL || issues == NULL || path_length <= 0) {
        free(samples);
        free(issues);
        CHECK(
            "processes.own-sample-carries-metadata",
            0,
            "no listing to take: allocation %d, own path %d",
            samples != NULL && issues != NULL,
            path_length
        );
        CHECK("processes.own-sample-matches-a-fresh-rusage", 0, "no listing to take");
        CHECK("processes.rusage-issue-path-matches-a-fresh-read", 0, "no listing to take");
        CHECK("processes.rusage-issue-uid-is-unknown", 0, "no listing to take");
        return;
    }

    HMOwnPreparations prepared;
    memset(&prepared, 0, sizeof(prepared));
    hm_write_to_disk(&prepared);
    HMWiredMemory wired = hm_wire_memory(&prepared);
    HMParkedThread parked = {{-1, -1}, 0};
    pthread_t thread;
    if (pipe(parked.wake) == 0) {
        parked.parked = pthread_create(&thread, NULL, hm_park_thread, &parked) == 0;
    }
    prepared.parked = parked.parked;
    if (parked.parked) {
        hm_wait_for_the_park();
    }

    int written_issues = 0;
    const HMOwnAnchor before = hm_read_own_anchor();
    const int written = hm_list_processes(
        samples,
        capacity,
        issues,
        issue_capacity,
        0,
        0,
        NULL,
        NULL,
        &written_issues
    );
    const HMOwnAnchor after = hm_read_own_anchor();

    const HMProcessSample *own = NULL;
    for (int index = 0; index < written; ++index) {
        if (samples[index].pid == getpid()) {
            own = &samples[index];
            break;
        }
    }

    hm_check_own_metadata(own, written, own_path);
    hm_check_own_fields(own, &before, &after, &prepared);
    hm_check_rusage_issue_paths(issues, written_issues);
    hm_check_rusage_issue_uids(issues, written_issues);

    if (parked.parked) {
        const char wake = 'w';
        while (write(parked.wake[1], &wake, 1) < 0 && errno == EINTR) {
        }
        pthread_join(thread, NULL);
    }
    if (parked.wake[0] >= 0) {
        close(parked.wake[0]);
        close(parked.wake[1]);
    }
    hm_release_wired_memory(&wired);
    free(samples);
    free(issues);
}

static int hm_listing_rejects(
    HMProcessSample *samples,
    int sample_capacity,
    HMProcessIssue *issues,
    int issue_capacity,
    int attribution_process_limit,
    int attribution_region_budget
) {
    errno = 0;
    const int result = hm_list_processes(
        samples,
        sample_capacity,
        issues,
        issue_capacity,
        attribution_process_limit,
        attribution_region_budget,
        NULL,
        NULL,
        NULL
    );
    return result == -1 && errno == EINVAL;
}

/*
 * The PID list `hm_list_processes` allocates for itself, and the `free` at the
 * end of it. Nothing in the return value says whether that call is there, and the
 * caller is a root daemon that samples for as long as the machine is up: deleting
 * it leaks the list on every sample, about 4 KB each here, for as long as harmon
 * runs. AddressSanitizer does not cover it either — LeakSanitizer refuses to start
 * on macOS — so it is measured the way the framing suite measures its own leak,
 * by asking the allocator what it holds.
 *
 * Measured over 16 listings of this machine's 917 processes: 0 bytes of growth
 * with the `free` in place, 81920 without it, three runs each. The tolerance is a
 * fifth of that, and the round count is what keeps the check at 11 ms.
 */
#define HM_LEAK_LISTING_ROUNDS 16
#define HM_LEAK_LISTING_TOLERANCE_BYTES (16 * 1024)
#define HM_LEAK_LISTING_CAPACITY 64

static int hm_take_narrow_listing(
    HMProcessSample *samples,
    HMProcessIssue *issues,
    int *written_issues
) {
    return hm_list_processes(
        samples,
        HM_LEAK_LISTING_CAPACITY,
        issues,
        HM_LEAK_LISTING_CAPACITY,
        0,
        0,
        NULL,
        NULL,
        written_issues
    );
}

static void hm_check_listing_frees_its_pid_list(void) {
    HMProcessSample *samples = (HMProcessSample *)calloc(
        HM_LEAK_LISTING_CAPACITY,
        sizeof(HMProcessSample)
    );
    HMProcessIssue *issues = (HMProcessIssue *)calloc(
        HM_LEAK_LISTING_CAPACITY,
        sizeof(HMProcessIssue)
    );
    if (samples == NULL || issues == NULL) {
        free(samples);
        free(issues);
        CHECK("processes.listing-frees-its-pid-list", 0, "out of memory");
        return;
    }

    int written_issues = 0;
    /* One listing first, so that first-touch allocations stay out of the window. */
    int written = hm_take_narrow_listing(samples, issues, &written_issues);
    const size_t before = hm_test_heap_bytes_in_use();
    for (int round = 0; round < HM_LEAK_LISTING_ROUNDS; ++round) {
        written = hm_take_narrow_listing(samples, issues, &written_issues);
    }
    const long long growth =
        (long long)hm_test_heap_bytes_in_use() - (long long)before;

    CHECK(
        "processes.listing-frees-its-pid-list",
        written > 0 && growth < HM_LEAK_LISTING_TOLERANCE_BYTES,
        "expected the heap to grow by less than %d bytes over %d listings, grew by "
            "%lld while writing %d samples of %d processes",
        HM_LEAK_LISTING_TOLERANCE_BYTES,
        HM_LEAK_LISTING_ROUNDS,
        growth,
        written,
        hm_count_processes()
    );

    free(samples);
    free(issues);
}

/*
 * Where the two exec-path checks build the binaries they delete, and how long
 * they will wait for a child to finish exec'ing one. The wait is bounded in
 * milliseconds against a fork+exec of this binary — 132 KiB, or 564 KiB
 * sanitized — which needs one or two of them; the bound is what keeps a machine
 * that cannot exec the copy at all from sitting in this loop until the harness
 * alarm, and a child that never execs is reported as `ready=0` rather than as a
 * bridge that lost the path.
 */
#define HM_EXEC_PATH_DIRECTORY "/tmp/harmon-exec-path-XXXXXX"
#define HM_EXEC_ATTEMPTS 1000

/* A child of this harness running a copy of this binary that the test then deletes. */
typedef struct {
    pid_t pid;
    char path[HM_PROCESS_PATH_SIZE];
} HMDeletedBinary;

static int hm_copy_executable(const char *from, const char *to) {
    const int source = open(from, O_RDONLY);
    if (source < 0) {
        return 0;
    }
    const int target = open(to, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (target < 0) {
        close(source);
        return 0;
    }

    char buffer[65536];
    int copied = 1;
    ssize_t taken;
    while (copied && (taken = read(source, buffer, sizeof(buffer))) != 0) {
        if (taken < 0) {
            copied = 0;
            break;
        }
        ssize_t written = 0;
        while (written < taken) {
            const ssize_t step = write(target, buffer + written, (size_t)(taken - written));
            if (step <= 0) {
                copied = 0;
                break;
            }
            written += step;
        }
    }
    close(source);
    close(target);
    return copied;
}

/*
 * A child parked in a private copy of `binary`, started through an absolute path
 * or through `./name` from the copy's own directory.
 *
 * The copy is of this harness rather than of something small and idle like
 * `/bin/cat` because a copy of a system binary does not run at all on Apple
 * silicon: the original is trusted through the kernel's trust cache rather than
 * through anything inside the file, and the copy is killed on exec before it
 * reaches a line of its own. This binary is signed ad-hoc by the compiler that
 * built it moments earlier, so a copy of it is as runnable as the original —
 * measured both ways here — and `--park` is the argument that makes it hold
 * still. Copying is the point: the checks delete the file out from under a
 * process that is still running it.
 */
static HMDeletedBinary hm_start_deleted_binary(
    const char *binary,
    const char *directory,
    const char *file,
    int relative
) {
    HMDeletedBinary child = {-1, {0}};
    snprintf(child.path, sizeof(child.path), "%s/%s", directory, file);
    if (!hm_copy_executable(binary, child.path)) {
        return child;
    }

    const pid_t started = fork();
    if (started == 0) {
        if (relative) {
            char here[HM_PROCESS_PATH_SIZE];
            snprintf(here, sizeof(here), "./%s", file);
            if (chdir(directory) == 0) {
                execl(here, here, "--park", (char *)NULL);
            }
        } else {
            execl(child.path, child.path, "--park", (char *)NULL);
        }
        _exit(127);
    }

    if (started < 0) {
        unlink(child.path);
        return child;
    }
    child.pid = started;
    return child;
}

/*
 * Whether the child has finished exec'ing its copy, which the unlink has to wait
 * for: a binary deleted before the exec reaches it fails the exec instead of the
 * path lookup, and the check would then be measuring a child that never ran.
 * `proc_pidpath` answering with the copy's path is the exec having completed —
 * for the relative child too, because it resolves what the process is running
 * rather than what it was named.
 */
static int hm_wait_for_exec(pid_t pid, const char *path) {
    for (int attempt = 0; attempt < HM_EXEC_ATTEMPTS; ++attempt) {
        char seen[HM_PROCESS_PATH_SIZE];
        memset(seen, 0, sizeof(seen));
        if (proc_pidpath(pid, seen, (uint32_t)sizeof(seen)) > 0 && strcmp(seen, path) == 0) {
            return 1;
        }
        usleep(1000);
    }
    return 0;
}

static void hm_release_deleted_binary(HMDeletedBinary *child) {
    if (child->pid > 0) {
        kill(child->pid, SIGKILL);
        int status = 0;
        while (waitpid(child->pid, &status, 0) < 0 && errno == EINTR) {
        }
        child->pid = -1;
    }
    unlink(child->path);
}

/*
 * The fallback `hm_read_process_metadata` reaches for once `proc_pidpath` has
 * refused, over the two processes the machine cannot be asked to provide.
 *
 * Both children are running a file that no longer exists, which is the state
 * every process is left in by an in-place upgrade of the prefix it was started
 * from — four codex sessions here, out of an nvm prefix a later install
 * replaced, and the whole reason the fallback exists. `proc_pidpath` fails with
 * ENOENT for them while `ps` still shows a path, so a bridge without the
 * fallback reports nothing and everything that groups by path loses them.
 *
 * The second child is the same state reached through `./cat-relative`, and it
 * asserts the opposite: the saved region holds a relative path, and the bridge
 * must refuse it rather than store it. Storing it would be worse than the empty
 * string it replaces — `./server` is not an identity, and every process on the
 * machine started that way would group as one. That is why the check reads the
 * region a second time itself: without that read, an empty result would prove
 * only that nothing was there.
 */
static void hm_check_exec_path_fallback(void) {
    char own[HM_PROCESS_PATH_SIZE];
    memset(own, 0, sizeof(own));
    char template[] = HM_EXEC_PATH_DIRECTORY;
    char directory[HM_PROCESS_PATH_SIZE];
    /*
     * Resolved, because `/tmp` is a symlink to `/private/tmp` and `proc_pidpath` answers with the
     * real path. Unresolved, the exec would still work and every comparison below would be against
     * a path the kernel never reports.
     */
    if (proc_pidpath(getpid(), own, (uint32_t)sizeof(own)) <= 0 ||
        mkdtemp(template) == NULL ||
        realpath(template, directory) == NULL) {
        CHECK(
            "processes.exec-path-survives-a-deleted-binary",
            0,
            "nothing to build a deletable binary from: own path '%s', directory %s",
            own,
            strerror(errno)
        );
        CHECK("processes.exec-path-ignores-a-relative-exec", 0, "nothing to build from");
        return;
    }

    HMDeletedBinary absolute = hm_start_deleted_binary(own, directory, "parked-absolute", 0);
    HMDeletedBinary relative = hm_start_deleted_binary(own, directory, "parked-relative", 1);
    const int started = absolute.pid > 0 && relative.pid > 0;
    const int ready = started &&
        hm_wait_for_exec(absolute.pid, absolute.path) &&
        hm_wait_for_exec(relative.pid, relative.path);
    if (ready) {
        unlink(absolute.path);
        unlink(relative.path);
    }

    char absolute_bridge[HM_PROCESS_PATH_SIZE];
    char relative_bridge[HM_PROCESS_PATH_SIZE];
    char relative_saved[HM_PROCESS_PATH_SIZE];
    char refused[HM_PROCESS_PATH_SIZE];
    memset(absolute_bridge, 0, sizeof(absolute_bridge));
    memset(relative_bridge, 0, sizeof(relative_bridge));
    memset(relative_saved, 0, sizeof(relative_saved));
    int absolute_resolved = 1;
    int relative_resolved = 1;
    if (ready) {
        absolute_resolved =
            proc_pidpath(absolute.pid, refused, (uint32_t)sizeof(refused)) > 0;
        relative_resolved =
            proc_pidpath(relative.pid, refused, (uint32_t)sizeof(refused)) > 0;
        hm_read_process_metadata(
            absolute.pid,
            NULL,
            NULL,
            NULL,
            0,
            absolute_bridge,
            (uint32_t)sizeof(absolute_bridge)
        );
        hm_read_process_metadata(
            relative.pid,
            NULL,
            NULL,
            NULL,
            0,
            relative_bridge,
            (uint32_t)sizeof(relative_bridge)
        );
        hm_test_saved_exec_path(relative.pid, relative_saved, sizeof(relative_saved));
    }

    CHECK(
        "processes.exec-path-survives-a-deleted-binary",
        ready && !absolute_resolved && strcmp(absolute_bridge, absolute.path) == 0,
        "expected the deleted '%s' to be refused by proc_pidpath and reported from the "
            "saved exec path anyway; started=%d ready=%d proc_pidpath-resolved=%d "
            "bridge reported '%s'",
        absolute.path,
        started,
        ready,
        absolute_resolved,
        absolute_bridge
    );
    CHECK(
        "processes.exec-path-ignores-a-relative-exec",
        ready && !relative_resolved && relative_saved[0] == '.' && relative_bridge[0] == '\0',
        "expected a relative exec of a since-deleted binary to be left empty rather than "
            "stored; started=%d ready=%d proc_pidpath-resolved=%d the saved region holds "
            "'%s' and the bridge reported '%s'",
        started,
        ready,
        relative_resolved,
        relative_saved,
        relative_bridge
    );

    hm_release_deleted_binary(&absolute);
    hm_release_deleted_binary(&relative);
    rmdir(directory);
}

/*
 * Rejected arguments never reach the buffers, so the two samples stay
 * deliberately uninitialised: a call that touched them would be the bug.
 */
static void hm_check_process_listing_invalid_arguments(void) {
    HMProcessSample sample;
    HMProcessIssue issue;

    const int null_samples = hm_listing_rejects(NULL, 1, &issue, 1, 0, 0);
    const int zero_capacity = hm_listing_rejects(&sample, 0, &issue, 1, 0, 0);
    const int null_issues = hm_listing_rejects(&sample, 1, NULL, 1, 0, 0);
    const int zero_issue_capacity = hm_listing_rejects(&sample, 1, &issue, 0, 0, 0);
    const int negative_process_limit = hm_listing_rejects(&sample, 1, &issue, 1, -1, 0);
    const int negative_region_budget = hm_listing_rejects(&sample, 1, &issue, 1, 0, -1);

    CHECK(
        "processes.rejects-invalid-arguments",
        null_samples && zero_capacity && null_issues && zero_issue_capacity &&
            negative_process_limit && negative_region_budget,
        "expected -1/EINVAL from each; rejected null-samples=%d zero-capacity=%d "
            "null-issues=%d zero-issue-capacity=%d negative-process-limit=%d "
            "negative-region-budget=%d",
        null_samples,
        zero_capacity,
        null_issues,
        zero_issue_capacity,
        negative_process_limit,
        negative_region_budget
    );
}

void hm_run_processes_tests(void) {
    hm_check_process_listing();
    hm_check_own_listing();
    hm_check_listing_frees_its_pid_list();
    hm_check_exec_path_fallback();
    hm_check_process_listing_invalid_arguments();
}
