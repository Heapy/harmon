# How the native layer is tested

The whole C bridge is `nativebridge/cinterop/harmon_native.def`. Two external
harnesses cover it, both driven from `./kotlin test`, split by what the assert
is actually about:

| Harness | Covers | Why that one |
|---|---|---|
| C tests (`test/native/*.c`) — the main one | pure functions, the kernel contract, machine-state snapshots, IPC framing, Unix sockets | exact values, direct `errno`, real `socketpair`; self-contained and needs no Kotlin build |
| `selftest` (`selftest/src/main.kt`) — minimal | only what crosses the Kotlin↔C boundary: struct layout and type mapping | the one thing plain C cannot check about itself |
| `SystemCollector` and its fakes | everything above cinterop | ordinary unit tests, untouched by any of this |

One check is in both on purpose: `selftest` repeats
`attribution.self-walk-completes` because the address space of a Kotlin/Native
process holds far more regions than that of a 30-kilobyte C binary, which makes
the walk both a longer walk and the binding check in its most meaningful form.

Wherever a reader could put the right number in the wrong field, the check reads
the same kernel source a second time and compares field by field instead of
asserting that the aggregate looks plausible: `vm.swapusage` for the swap
figures, `host_statistics` for the tick counters, `host_statistics64` for the
virtual memory sample, `hw.memsize` and `getloadavg` for the two the collector
divides by, a second walk of the block storage registry, a second IOPS read for
the battery, a second region walk for the compressed bytes, a second
`proc_pidinfo` for the process metadata, a second `proc_pid_rusage` plus
`PROC_PIDTASKINFO` for the 24 numbers of a process sample. A plausibility
assertion survives a transposed pair of fields; an anchor does not. Where an
anchor cannot separate two fields, the gap is listed below rather than left to
the comment.

Two shapes of anchor are in use — a *bracket* around the bridge's read and a
*tolerance* on two reads in a row — and `test/native/anchors.h` describes both.
The comparison helpers both shapes run through are pinned to exact values by the
`pure.anchor-` checks, because between them they gate nine anchored checks: one
that agreed with everything would turn all nine green over a broken bridge, and
each corruption was measured doing exactly that. Which fields take which: the bracket covers most of the process sample, the load
averages and the compressed bytes; a tolerance covers the tick counters, the
virtual memory statistics, storage, swap and the battery estimate, and each is
documented at its constant with what it was measured at. Neither is exact
everywhere. Three fields of the process sample both rise and fall — wired,
resident and footprint bytes — so their bracket carries 4 MiB of slack
(`HM_OWN_RESIDENCY_SLACK`), and the slack rather than the bracket is what decided
`wired_bytes`: an ordinary process reports 0 there, which was inside it. A
tolerance wider than the value it is applied to checks nothing — the byte one was
64 MiB against 56 MB of purgeable memory, and is 8 MiB now, measured against a
worst observed drift of 2 MB under four processes churning memory. Swap shares
that constant rather than keeping its own: at 64 MiB `xsu_used` and `xsu_avail`
were unpinned on every machine holding less swap than that.

**What the harnesses need from the machine.** Neither is meant to run as root:
`processes.samples-are-well-formed` holds because an ordinary user cannot read
the rusage of pid 0, and `socket.accept-rejects-foreign-uid` has no foreign user
to name when the caller is uid 0. Everything else the checks need they make
rather than ask for, which is what keeps them off the shape of the account they
run under:

- `processes.own-sample-matches-a-fresh-rusage` writes and flushes 4 MiB, reads
  1 MiB of it back with the cache turned off, parks a second thread and locks
  16 MiB of memory before the listing, because a harness that touched no disk,
  ran one thread and wired nothing reports `disk_bytes_read` equal to
  `disk_bytes_written`, `thread_count` equal to `running_thread_count` and
  `wired_bytes` at 0;
- `processes.issues-are-well-formed` and
  `processes.issue-metadata-matches-a-fresh-read` need the account to own more
  rusage-readable processes than the sample array holds, plus 32 behind them, so
  the listing forks the shortfall as placeholder children first — nothing at all
  on a desktop session (585 here), a few dozen under a service account.

Both report what they did in every failure, so a machine that refuses `mlock`,
serves the read from cache or cannot fork says so in those words instead of
looking like a mapping regression. The one requirement that cannot be made is 16
processes of *other* users, which is the whole population of the rusage branch
(256 to 276 here) that `processes.rusage-issue-path-matches-a-fresh-read` and
`processes.rusage-issue-uid-is-unknown` read; every macOS carries a hundred of
root's, and both checks print what they counted. No check depends on how many
processes the account has started *recently*: an earlier
`attribution.bytes-match-an-independent-walk` gave up after 24 of them and failed
for a whole build running alongside it, or for a second copy of the harness.

`scripts/test-native.sh` regenerates the header the C tests include straight
from the `.def` (`sed '1,/^---$/d'` into `build/native-test/`), compiles every
`test/native/*.c` into one binary with `clang -std=c11 -Wall -Wextra -Werror`
plus the `.def`'s own `linkerOpts` frameworks, and runs it. The `.def` stays the
single source of truth — cinterop cannot be pointed at a separate header, so no
checked-in `.h` exists — and the generated copy lives for one run.

Each `*_test.c` is one suite reporting under one name prefix, named after it, and
`main.c` maps the two; `harness.h` carries the protocol and the alarm, `anchors.h`
what more than one suite needs to compare a sample against a second reading.

**The sanitized pass.** `./kotlin test` runs the C harness twice: once as above,
once with `--sanitize`, which builds the same sources with
`-fsanitize=address,undefined -fno-sanitize-recover=all` into a binary of its own.
That pass is not about any check — all of them pass either way — but about what no
assertion over return values can see: a write past an allocation, a use after
free, a signed overflow. `malloc((size_t)length)` in place of
`malloc((size_t)length + 1U)` in `hm_receive_json_frame`, a one-byte heap
overflow, leaves the ordinary pass green at exit 0 and stops the sanitized one
with a `heap-buffer-overflow` inside that function, which the bridge reports as a
death on signal 6. It costs about 1.2 s over the ordinary pass.

Leaks are **not** part of it: LeakSanitizer refuses to start on macOS
("detect_leaks is not supported on this platform"), so the two `free` calls whose
absence would matter are measured by checks that ask the allocator how much it
holds — `framing.receive-frees-rejected-frame` and
`processes.listing-frees-its-pid-list`. Five checks know they are in a sanitized
build, each for a property of the sanitizer rather than of the bridge, and each
says so where it branches: `attribution.self-walk-completes` gets a larger region
budget (the shadow map turns this process into 163966 regions against 54),
`framing.receive-terminates-the-payload` cannot ask for a freed block back
(quarantine), both leak checks read the sanitizer's allocator accounting instead
of `malloc_zone_statistics`, and `processes.own-sample-matches-a-fresh-rusage`
cannot lock memory (`mlock` is intercepted into a no-op — it returns 0 and
`ri_wired_size` stays 0).

Both harnesses speak the same protocol, and this paragraph is the description of
it — the harnesses and the bridge point here rather than restating it. Output is
`ok <name>` and `fail <name>: <detail>` lines; the exit status is 0 when every
executed check passed and 1 otherwise. Arguments are `--self-check`, which adds a
deliberately failing check so the `fail` branch is executed rather than assumed,
and one name-prefix filter, which is the first argument that is not a flag — in
any position, so `--self-check harness.` is how both are combined. Anything else
beginning with a dash, one or two, or a second filter, is a usage error and exits
2: an unknown flag taken for a filter would select nothing and exit 0, which is
how a mistyped `--self-check` would read as a clean run, and `-selfcheck` is the
likelier typo of the two. The deliberate failure ignores the filter for the same
reason — under `--self-check pure.` a filtered-out self-check would report
success from a harness whose `fail` branch never ran. A filter that selects
nothing prints `ok harness.no-checks-selected`. Both harnesses arm a
60-second `alarm` before their first check, so a walk or a socket that hung
inside the kernel dies on SIGALRM instead of hanging `./kotlin test`; the bridge
reports that as "died on signal 14". A child the C harness forks re-arms that
alarm and closes **both** standard descriptors: `fork` clears the parent's alarm,
and a child that outlives a parent killed by one holds the harness's output pipe
open, so the reader in `NativeHarness.kt` waits for an EOF that never comes.
Both descriptors, because the bridge runs every command with `2>&1` and 1 and 2
are then the same pipe — measured with a parent that exits while the child pauses:
closing neither gives no EOF in ten seconds, closing stdout alone gives no EOF
either, closing both gives EOF in 0.02 s.

`test/NativeHarness.kt` drives them through `popen`, turns the first `fail` into
an assertion naming it and the rest, requires a normal exit with status 0,
reports a death by signal separately, fails whenever no check line was parsed at
all — with or without a filter, which is what the sentinel line above exists for
— and compares the set of executed check names against the expected list, so a
check that falls out of a harness cannot disappear quietly. That list is
`C_HARNESS_CHECKS` in `test/NativeCTest.kt` and `SELFTEST_CHECKS` in
`test/SelftestBridgeTest.kt`, which makes a new check a two-file change: the
`CHECK`/`check` call and its name in the list, or the run fails as `unexpected`.

**Running one harness, or one check**, without going through `./kotlin test`:

```shell
scripts/test-native.sh                 # every C check
scripts/test-native.sh socket.         # one suite, by name prefix
scripts/test-native.sh --self-check    # exit 1, proves the fail branch runs
scripts/test-native.sh --sanitize      # the same checks under ASan and UBSan
build/tasks/_selftest_linkMacosArm64Debug/selftest.kexe binding.
```

The filter gates execution, not just reporting: `main.c` maps each suite to the
prefixes it reports under and skips the ones the filter cannot select, so a
filtered run neither forks children nor opens sockets for the suites it left out
(0.02 s against 0.36 s here). A prefix missing from that table costs a filtered
run the suite entirely — the unfiltered run `./kotlin test` performs is the one
that checks the names.

**Working directory and overrides.** `NativeTestTask` runs the test process with
the module directory — which is the project root — as its working directory,
and that is the only reason the relative paths `scripts/test-native.sh` and
`build/tasks/_selftest_linkMacosArm64Debug/selftest.kexe` resolve. The
assumption breaks under `--build-dir` and `--project-dir`, so every failure
message quotes the *absolute* resolved path and the working directory, and
`HARMON_NATIVE_TEST_SCRIPT` and `HARMON_SELFTEST_BIN` override the two paths.

**Staleness guard.** Because `./kotlin test` does not link `selftest`,
`SelftestBridgeTest` checks the binary before running it. A missing binary
**fails** the test with the absolute path and "run `./kotlin build` first" — a
silent skip is not an option. A binary older than the newest file under
`selftest/src/`, or than `nativebridge/cinterop/harmon_native.def`,
`selftest/module.yaml`, `nativebridge/module.yaml` or
`harmon.module-template.yaml`, fails as well; watching only the `.def` would miss
the common case, which is editing the checks themselves, and watching only
sources would miss a change to what gets linked. The path is deliberately tied
to the debug variant: after
`./kotlin build --variant release` the debug binary stays where it was, and the
guard is what notices. The C tests need no guard — the script recompiles them
every run.

**Accepted coverage gaps**, listed so the table above does not read as a promise
to cover everything. Each entry names the check that owns it; the reasoning —
what was measured, what a mutation of that line survives — lives at that check
and is not repeated here.

Nothing reaches these at all:

- `hm_http_post_json` with `hm_http_global_init`/`hm_http_global_cleanup`: would
  need a local HTTP server in the tests;
- the attribution budget split inside `hm_list_processes` (head/tail passes,
  `hm_attribute_candidate`): both harnesses take the listing with attribution off,
  so the policy never runs; the region walk itself is covered directly;
- the one-line wrappers that exist only for Kotlin — `hm_free`,
  `hm_close_descriptor`, `hm_sleep_millis`;
- the `RUSAGE_INFO_V6 → V4` fallback: only fires on older macOS;
- the `pid-N` name fallback in `hm_list_processes`, which needs a process that
  vanishes mid-listing — `hm_malformed_sample` in `processes_test.c`. Covered the
  other way round: `processes.own-sample-carries-metadata` fails an unconditional
  `pid-%d`;
- the `chown` branch of `hm_unix_server_open` and the `st_uid` half of its
  occupancy test: root, or a second user. The non-socket half is
  `socket.refuses-foreign-occupant`;
- the peer-uid gate inside `hm_unix_connect`, `hm_unix_accept` with a null
  `peer_user_id`, and its bypass for uid 0: same reason. The server-side gate that
  matters, `socket.accept-rejects-foreign-uid`, is covered.

One piece of the C harness is on this list rather than the bridge:
`hm_top_up_own_processes` in `processes_test.c` forks the shortfall of
rusage-readable processes the two issue checks need, and no run on a desktop
account can tell whether it works — 585 are there already, so a version that
forks nothing leaves every check green. It matters only on the account that
needs it, which is the one this machine cannot be.

Anchored, but only as far as this machine's state separates the fields — each is
argued at its check in `snapshot_test.c` or `attribution_test.c`:

- `nice_ticks`, at `snapshot.processor-ticks-match-a-fresh-read`;
- `package_idle_wakeups`, at `processes.own-sample-matches-a-fresh-rusage`;
- any virtual memory or swap figure below the 8 MiB tolerance, and the swap
  `encrypted` flag, at `snapshot.virtual-memory-matches-a-fresh-read` and
  `snapshot.swap-and-virtual-memory-readable`;
- `f_bfree` against `f_bavail`, the hard-coded `/`, and the summation across
  block storage drivers, at `snapshot.storage-matches-a-fresh-read`;
- the load averages on an idle machine, at
  `snapshot.memory-and-load-match-a-fresh-read`;
- every battery field the present power state does not exercise, at
  `snapshot.battery-matches-a-fresh-read`;
- the compressed-bytes sum where the compressor holds nothing of this account, at
  `attribution.bytes-match-an-independent-walk`.

And the largest hole on the list, which is Kotlin rather than C:

- `DarwinSystemCollector.capture()` re-performs by hand every field mapping the C
  harness spends 2500 lines pinning — some thirty `field = sample.field`
  assignments — and nothing executes it: the only test that names
  `DarwinSystemCollector` is `DarwinCollectorLimitsTest`, which covers the
  constructor arguments and never calls `capture()`. A `userTimeNs =
  sample.system_time_ns` written there produces exactly the report
  `processes.own-sample-matches-a-fresh-rusage` exists to prevent, and not one
  check turns red. It stays that way because the test compilation cannot reach
  cinterop at all (KTC-5573) and `selftest` cannot depend on an application
  module; moving the collector into `nativebridge` would drag `model/` along with
  it. What covers it today is `harmon diagnose` on a real machine, read by a
  human.
