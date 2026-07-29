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
layer is tested* below, and [`docs/native-testing.md`](docs/native-testing.md)
for the detail.

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

The whole C bridge is `nativebridge/cinterop/harmon_native.def`, and two
external harnesses cover it, both driven from `./kotlin test`: a C test binary
built from `test/native/` for everything assertable from C, and a `selftest`
macos/app module for the one thing plain C cannot check about itself — that
Kotlin sees the structs and types the bridge actually exports.
`test/NativeHarness.kt` drives both through `popen` and turns their output into
assertions.

Two consequences for everyday work:

- **`./kotlin build` before `./kotlin test`**, because `./kotlin test` does not
  link `selftest`. A missing or stale binary fails the test with that
  instruction rather than skipping it.
- **A new check is a two-file change** — the `CHECK`/`check` call, and its name
  in `C_HARNESS_CHECKS` (`test/NativeCTest.kt`) or `SELFTEST_CHECKS`
  (`test/SelftestBridgeTest.kt`) — or the run fails the name comparison as
  `unexpected`.

Running one harness, or one check, without going through `./kotlin test`:

```shell
scripts/test-native.sh                 # every C check
scripts/test-native.sh socket.         # one suite, by name prefix
scripts/test-native.sh --self-check    # exit 1, proves the fail branch runs
scripts/test-native.sh --sanitize      # the same checks under ASan and UBSan
build/tasks/_selftest_linkMacosArm64Debug/selftest.kexe binding.
```

[`docs/native-testing.md`](docs/native-testing.md) carries the rest: the
protocol both harnesses speak, why a check anchors against a second reading of
the kernel instead of asserting that a value looks plausible, what the harnesses
need from the machine they run on, and the accepted coverage gaps. That last
list is maintained as an exhaustive account of what is *not* covered — including
one hole that is Kotlin rather than C — so it is worth reading before assuming a
field is checked.

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
  native/     the C harness: one binary from every *.c, no main outside main.c,
              one suite per file named after the prefix it reports under
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

`README.md`, `docs/collection.md`, `docs/architecture.md`, and
`docs/native-testing.md` describe observed behaviour, not intent. When
collection limits, alert semantics, or metric definitions change, the doc change
belongs in the same commit. The same rule binds `docs/native-testing.md` harder
than the rest: its accepted-gaps list is only worth having while it is exhaustive,
so a check that closes a gap removes its entry in the same commit.
