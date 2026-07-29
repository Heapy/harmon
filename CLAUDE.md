# Harmon

Kotlin/Native macOS workload monitor built with the JetBrains Kotlin Toolchain
(Amper). One executable serves three roles: root collector, user agent, and CLI.

## Build and test

```shell
./kotlin build
./kotlin build --variant release
./kotlin test
```

That order is a requirement, not a habit: `SelftestBridgeTest` runs the
`selftest` binary, and `./kotlin test` does not link it. See *How the native
layer is tested* below.

Always the checked-in `./kotlin` wrapper — there is no Gradle build.
`harmon.module-template.yaml` carries `settings.kotlin`, including
`allWarningsAsErrors: true`, into every module: a new module does **not**
inherit the root module's Kotlin settings, so without the template the
strictness would silently not apply to it.

What that strictness actually catches is narrower than it sounds. A deprecation
warning fails the build like an error (`warnings found and -Werror specified`).
An unused import and an unused private top-level function do **not** — the
Kotlin 2.4.10 CLI does not warn on either, verified A/B in this repository. The
C side has the same setting spelled `-Wall -Wextra -Werror` in
`scripts/test-native.sh`, and there an unused parameter or variable really does
fail the build.

## Two verified constraints on what tests can reach

Both were established empirically in this repository; neither is obvious from
the source layout.

**The test source set does not see `internal` declarations from `src/`.** Amper
declares no associate compilation between the main and test Kotlin/Native
compilations, so an `internal` symbol fails to resolve from `test/` with
`cannot access '...': it is internal in file`. Any helper extracted for
testability must be **public**, even when `internal` would express the intent
better.

**The test compilation cannot reach the cinterop bindings at all.** The cause is
[KTC-5573](https://youtrack.jetbrains.com/issue/KTC-5573): the cinterop klib is
registered only for the non-test fragment, the test compilation asks for the
artifact with `isTest=true`, does not find it, and the `AnyOrNone` quantifier
lets the miss pass silently. Platform libraries (`platform.posix`,
`platform.Foundation`) link fine.

The symptom depends on where the bridge lives, and it moved earlier once
`nativebridge` became its own module. Previously the bridge compiled into the
root module and the failure was at run time —
`IrLinkageError: Function 'hm_...' can not be called`. Now it arrives as a
module dependency, and a test source file cannot even name it: referencing
`dev.yoda.harmon.nativebridge` from `test/` fails to compile with
`unresolved reference 'nativebridge'`. Adding the dependency to the test
fragment does not help; that is exactly the registration KTC-5573 is about.

Partial linkage still resolves per call site for code that lives in `src/`: a
pure function may sit in a file that imports `nativebridge` and stay testable,
as long as the function itself does not call into the bridge.
`processCapacityFor` in `DarwinSystemCollector.kt` is the working example.

The consequence is not that the native layer is unverified — see the next
section — but that neither harness can be an ordinary `kotlin.test` class. When
KTC-5573 is fixed, `selftest` collapses into normal tests almost mechanically:
its assertions are already Kotlin, and the bridge plus the staleness guard are
what gets deleted.

## How the native layer is tested

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

Two shapes of anchor are in use, and neither is exact everywhere. Against a value
that only grows the test reads the source before *and* after the bridge does and
requires the bridge's answer to lie between the two: for a counter that needs no
tolerance at all and cannot drift on a busy machine, and it is how most of the
process sample, the load averages and the compressed bytes are checked. Three
fields of that sample both rise and fall — wired, resident and footprint bytes —
so their bracket carries 4 MiB of slack (`HM_OWN_RESIDENCY_SLACK`), and the slack
rather than the bracket is what decided `wired_bytes`: an ordinary process reports
0 there, which was inside it. The harness therefore locks 16 MiB before the
readings and asserts that the lock took, the same way it writes 4 MiB and reads
1 MiB back past the cache so that the two disk directions are two different
non-zero numbers. Where the source is read twice in a row instead of around the
call there is a tolerance from the start: the tick counters, the virtual memory
statistics, storage, swap and the battery estimate all carry one, and each is
documented at its constant with what it was measured at. A tolerance wider than
the value it is applied to checks nothing — the virtual memory one was 64 MiB
against 56 MB of purgeable memory, and is 8 MiB now, measured against a worst
observed drift of 2 MB under four processes churning memory.

Neither harness is meant to run as root: `processes.samples-are-well-formed`
holds because an ordinary user cannot read the rusage of pid 0, and
`socket.accept-rejects-foreign-uid` has no foreign user to name when the caller
is uid 0. The other way round, `processes.issues-are-well-formed` needs the
account to own at least 64 rusage-readable processes, because the capacity branch
it covers only fires once the 64-slot sample array is full — an ordinary session
is far past that (566 here), a stripped service account might not be.
`processes.issue-metadata-matches-a-fresh-read` needs 32 of those issues to still
be readable moments later (174 to 177 here), and
`processes.rusage-issue-path-matches-a-fresh-read` and
`processes.rusage-issue-uid-is-unknown` need 16 processes of *other* users, which
is what the rusage branch of the listing reports (256 to 276 here). Each of them
fails naming what it counted rather than passing vacuously.
`processes.own-sample-matches-a-fresh-rusage` makes its conditions instead of
asking for them: before taking the listing it writes and flushes 4 MiB, reads
1 MiB of it back with the cache turned off, parks a second thread and locks
16 MiB of memory — because a harness that touched no disk, ran one thread and
wired nothing reports `disk_bytes_read` equal to `disk_bytes_written`,
`thread_count` equal to `running_thread_count` and `wired_bytes` at 0, and
neither a transposed pair of equal numbers nor a hard-coded zero is visible then.
It asserts that each of those took effect, which is the only way a machine that
refuses `mlock` or serves the read from cache says so instead of leaving the
fields unchecked.
No check depends on how many processes the account has started recently: an
earlier `attribution.bytes-match-an-independent-walk` gave up after 24 of them and
failed for a whole build running alongside it, or for a second copy of the
harness.

`scripts/test-native.sh` regenerates the header the C tests include straight
from the `.def` (`sed '1,/^---$/d'` into `build/native-test/`), compiles every
`test/native/*.c` into one binary with `clang -std=c11 -Wall -Wextra -Werror`
plus the `.def`'s own `linkerOpts` frameworks, and runs it. The `.def` stays the
single source of truth — cinterop cannot be pointed at a separate header, so no
checked-in `.h` exists — and the generated copy lives for one run.

**The sanitized pass.** `./kotlin test` runs the C harness twice: once as above,
once with `--sanitize`, which builds the same sources with
`-fsanitize=address,undefined -fno-sanitize-recover=all` into a binary of its own.
That pass is not about any check — all of them pass either way — but about what no
assertion over return values can see: a write past an allocation, a use after
free, a signed overflow. `malloc((size_t)length)` in place of
`malloc((size_t)length + 1U)` in `hm_receive_json_frame`, a one-byte heap
overflow, leaves the ordinary pass green at exit 0 and stops the sanitized one
with `heap-buffer-overflow harmon_native.h:494`, which the bridge reports as a
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
to cover everything:

- `hm_http_post_json` with `hm_http_global_init`/`hm_http_global_cleanup` — would
  need a local HTTP server in the tests;
- the attribution budget split inside `hm_list_processes` (head/tail passes,
  `hm_attribute_candidate`) — both harnesses take the listing with attribution
  off, so the budget policy never runs; the region walk itself is covered
  directly;
- the one-line wrappers that exist only for Kotlin — `hm_free`,
  `hm_close_descriptor`, `hm_sleep_millis`;
- the `RUSAGE_INFO_V6 → V4` fallback — only fires on older macOS;
- the `pid-N` name fallback in `hm_list_processes` — it needs a process that
  vanishes between the rusage read and the metadata read, so proc_name and
  `proc_bsdinfo` both come back empty; deleting the fallback leaves every
  `processes.*` check green, because on a live machine the branch never fires.
  The empty-name branch of `processes.samples-are-well-formed` is a guard against
  that, not coverage of it. What *is* covered is the fallback firing when it
  should not: `processes.own-sample-carries-metadata` compares this process's own
  sample against `proc_pidpath`, so an unconditional `pid-%d` — every process on
  the machine named after its pid — fails it;
- `nice_ticks` in `hm_read_processor` — the counter reads 0 on this machine and a
  300 ms burn at nice 19 does not move it, so the anchor agrees with a bridge that
  hard-coded the field to zero. The other three tick counters are anchored;
- `package_idle_wakeups` in the process sample, for the same reason and with the
  same shape: `ri_pkg_idle_wkups` reads 0 for a process as short-lived as the
  harness, so its bracket is 0..0 and a hard-coded zero is inside it. The counter
  itself is live — 354 of the 631 processes this account can read carry a non-zero
  one, up to 2955467 — but they accumulated it over hours of the package actually
  going idle, and neither 200 wakeups at 2 ms nor 20 at 50 ms move it inside a
  one-second run. Closing it would mean bracketing the sample of *another*
  process, one this harness does not otherwise need. The two disk figures and
  `wired_bytes`, which had the same 0..0 problem, are made non-zero instead;
- any virtual memory figure below the 8 MiB tolerance of
  `snapshot.virtual-memory-matches-a-fresh-read`, which cannot be told from a
  hard-coded zero. It is the small fields that are exposed: `purgeable_bytes` is
  25 to 56 MB here and separated, but a machine holding less than 8 MB of it is
  not, and neither is `compressed_bytes` on a runner with no memory pressure at
  all;
- `root_filesystem_available_bytes` from `f_bfree` instead of `f_bavail` — the two
  are equal on the APFS root here, so the anchor cannot tell them apart. It would
  on a filesystem that reserves blocks for root. `hm_read_storage` also hard-codes
  `/`, so no check can tell it from another mount point;
- the summation across devices in `hm_add_storage_statistic` — it adds the
  statistics of every `IOBlockStorageDriver` in the registry, and a machine with
  one internal drive cannot distinguish adding them from overwriting. The filter
  that decides which drivers are summed is anchored: `hm_read_storage_anchor` used
  to call the bridge's own `hm_storage_driver_is_internal` and moved with it, and
  now decides for itself, so a bridge accepting every driver reports five devices
  against the anchor's one. On a machine whose registry holds a single driver even
  that is indistinguishable;
- what the swap sample cannot separate on a given machine: with no swap file all
  three figures are zero and a transposed `xsu_used`/`xsu_avail` is invisible, and
  with unencrypted swap the `encrypted` flag cannot be told from a hard-coded 0.
  Both are anchored against a second `vm.swapusage` where the machine has them;
- the load averages on an idle machine, where all three read the same value and no
  anchor can tell a transposed one-minute and fifteen-minute pair from the right
  one. The bracketing read separates them whenever they differ at all;
- the compressed-bytes sum on a machine whose compressor holds nothing of this
  account. `attribution.bytes-match-an-independent-walk` prefers a process the
  compressor is holding pages of, scanning every process of the account to find
  one (568 of 594 qualified here); with none it walks this process instead, where
  both sums are zero. A bridge summing the resident pages fails it either way, a
  hard-coded zero only in the first case;
- the `chown` branch of `hm_unix_server_open` — root only — and the `st_uid`
  half of its occupancy test, which needs a socket at the path owned by a second
  user; the non-socket half of the same test is covered by
  `socket.refuses-foreign-occupant`;
- the peer-uid gate inside `hm_unix_connect` (`EACCES` on a socket owned by
  someone else), `hm_unix_accept` with a null `peer_user_id`, and its bypass for
  uid 0 — all three need a second user or root; the server-side gate that
  matters, `hm_unix_accept` refusing a foreign uid, is covered;
- `hm_read_battery` beyond what the machine's state exposes. Every field is
  anchored against a second IOPS read, so the anchor is only as strong as the
  present state: a machine on mains power leaves `minutes_remaining` at -1 in both
  the sample and the anchor, a battery that is not charging cannot distinguish the
  charging flag from a hard-coded 0, and a machine without a battery exercises
  none of it;
- `DarwinSystemCollector.capture()`, which is the largest hole on this list.
  `capture()` (`DarwinSystemCollector.kt:156-325`) re-performs by hand every field
  mapping the C harness spends 2500 lines pinning — some thirty
  `field = sample.field` assignments — and nothing executes it: the only test that
  names `DarwinSystemCollector` is `DarwinCollectorLimitsTest`, which covers the
  constructor arguments and never calls `capture()`. A `userTimeNs =
  sample.system_time_ns` written there produces exactly the report
  `processes.own-sample-matches-a-fresh-rusage` exists to prevent, and not one
  check turns red. It stays that way because the test compilation cannot reach
  cinterop at all (KTC-5573) and `selftest` cannot depend on an application
  module; moving the collector into `nativebridge` would drag `model/` along with
  it. What covers it today is `harmon diagnose` on a real machine, read by a
  human.

End-to-end verification is still `harmon diagnose` on a real machine. The
recipe, without installing launchd services: start a local unprivileged
collector on a development socket,

```shell
build/tasks/_harmon_linkMacosArm64Debug/harmon.kexe collector \
  --allow-unprivileged \
  --socket /tmp/harmon-dev.sock \
  --allowed-uid "$(id -u)" \
  --allowed-gid "$(id -g)"
```

point the agent at that socket through the `collectorSocket` config key or the
`HARMON_COLLECTOR_SOCKET` environment variable, and diagnose through it:

```shell
HARMON_COLLECTOR_SOCKET=/tmp/harmon-dev.sock \
  build/tasks/_harmon_linkMacosArm64Debug/harmon.kexe \
  diagnose --sample-seconds 2
```

Such a collector sees only what the login user can see, not what root can. To
A/B a native change, run two collectors on two separate sockets — one binary
built before the change, one after — and diagnose against each.

The `_harmon_` in those paths is the *directory* name: the root module has no
`name:` key, so a checkout under a different directory — a git worktree, say —
produces `build/tasks/_<directory>_linkMacosArm64Debug/harmon.kexe` instead.
`selftest` lives in its own directory, so `_selftest_linkMacosArm64Debug` is
stable everywhere.

## Layout

```text
src/dev/yoda/harmon/
  ipc/        versioned protocol and Unix socket roles
  monitor/    privileged macOS collection and interval calculations
  analysis/   application grouping, alert rules, alert state
  config/     user-agent configuration
  report/     text, HTML, and JSON reporting
  notify/     Notification Center, webhook, Telegram delivery
  runtime/    user-agent monitoring loop
test/         flat directory, kotlin.test, shared fixtures in TestFixtures.kt
  native/     the C harness: one binary from every *.c, no main outside main.c
nativebridge/ kmp/lib module; cinterop/harmon_native.def holds the whole C
              bridge inline and is compiled by the Kotlin/Native cinterop tool
selftest/     macos/app module depending on nativebridge; the binding checks
              that ./kotlin test cannot reach
scripts/      install and uninstall flows, plus test-native.sh
project.yaml  the extra modules; the root module is included implicitly
harmon.module-template.yaml
              settings.kotlin applied by every module
```

Dependency injection is through constructor parameters with default values
(`HarmonService`, `CollectorServer`, `UsageCalculator`, `DarwinSystemCollector`,
`ApplicationGrouper`). Use that seam for testability instead of adding
abstractions. A default parameter can only reference parameters declared before
it, which fixes the order in some of those constructors.

## Documentation

`README.md`, `docs/collection.md`, and `docs/architecture.md` describe observed
behaviour, not intent. When collection limits, alert semantics, or metric
definitions change, the doc change belongs in the same commit.
