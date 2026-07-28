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

`scripts/test-native.sh` regenerates the header the C tests include straight
from the `.def` (`sed '1,/^---$/d'` into `build/native-test/`), compiles every
`test/native/*.c` into one binary with `clang -std=c11 -Wall -Wextra -Werror`
plus the `.def`'s own `linkerOpts` frameworks, and runs it. The `.def` stays the
single source of truth — cinterop cannot be pointed at a separate header, so no
checked-in `.h` exists — and the generated copy lives for one run.

Both harnesses speak the same protocol: `ok <name>` and
`fail <name>: <detail>` lines, exit status 0 or 1, an optional name-prefix
filter as the first argument, and `--self-check`, which adds a deliberately
failing check so the `fail` branch is executed rather than assumed.
`test/NativeHarness.kt` drives them through `popen`, turns each `fail` into its
own assertion, requires a normal exit with status 0, reports a death by signal
separately, fails on empty output when no filter was given, and compares the
set of executed check names against the expected list — so a check that falls
out of a harness cannot disappear quietly.

**Running one harness, or one check**, without going through `./kotlin test`:

```shell
scripts/test-native.sh                 # every C check
scripts/test-native.sh socket.         # one suite, by name prefix
scripts/test-native.sh --self-check    # exit 1, proves the fail branch runs
build/tasks/_selftest_linkMacosArm64Debug/selftest.kexe binding.
```

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
`selftest/src/` or `nativebridge/cinterop/harmon_native.def` fails as well;
watching only the `.def` would miss the common case, which is editing the checks
themselves. The path is deliberately tied to the debug variant: after
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
- the `chown` branch of `hm_unix_server_open` — root only;
- `hm_read_battery` on a machine without a battery — only the availability flag
  is checked;
- `DarwinSystemCollector` as a consumer of the bridge — `selftest` cannot depend
  on an application module, so it checks the bridge, not how the collector uses
  it. The collector's own arguments are covered by `DarwinCollectorLimitsTest`.

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
