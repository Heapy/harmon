#include "harmon_probe.h"

#include <fcntl.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/wait.h>

#include "anchors.h"
#include "harness.h"


#define HM_PROCESS_COUNT_TOLERANCE 64

#define HM_ISSUE_METADATA_MINIMUM 32
#define HM_ISSUE_METADATA_MISMATCH_DIVISOR 16

#define HM_LISTING_SAMPLE_CAPACITY 64
#define HM_LISTING_ISSUE_CAPACITY 256
#define HM_LISTING_OWN_MARGIN 16
#define HM_LISTING_OWN_MINIMUM \
    (HM_LISTING_SAMPLE_CAPACITY + HM_ISSUE_METADATA_MINIMUM + HM_LISTING_OWN_MARGIN)
#define HM_LISTING_TOP_UP_LIMIT HM_LISTING_OWN_MINIMUM

typedef struct {
    int owned;
    pid_t *placeholders;
    int forks;
} HMOwnProcesses;

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

static const char *hm_malformed_sample(const HMProcessSample *sample) {
    if (sample->pid <= 0) {
        return "pid is not positive";
    }
    if (memchr(sample->name, '\0', sizeof(sample->name)) == NULL) {
        return "name is not terminated within HM_PROCESS_NAME_SIZE";
    }
    if (sample->name[0] == '\0') {
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

static const char *hm_malformed_issue(const HMProcessIssue *issue) {
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

#define HM_OWN_RESIDENCY_SLACK (4ULL * 1024ULL * 1024ULL)

typedef struct {
    uint64_t written_bytes;
    uint64_t read_bytes;
    int disk_failure;
    uint64_t locked_bytes;
    int lock_failure;
    int parked;
} HMOwnPreparations;

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

#define HM_RUSAGE_ISSUE_MINIMUM 16
#define HM_RUSAGE_ISSUE_MISMATCH_DIVISOR 16

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

#define HM_EXEC_PATH_DIRECTORY "/tmp/harmon-exec-path-XXXXXX"
#define HM_EXEC_ATTEMPTS 1000

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

static void hm_check_exec_path_fallback(void) {
    char own[HM_PROCESS_PATH_SIZE];
    memset(own, 0, sizeof(own));
    char template[] = HM_EXEC_PATH_DIRECTORY;
    char directory[HM_PROCESS_PATH_SIZE];
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
