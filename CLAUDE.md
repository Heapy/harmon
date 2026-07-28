# Harmon

Kotlin/Native macOS workload monitor built with the JetBrains Kotlin Toolchain
(Amper). One executable serves three roles: root collector, user agent, and CLI.

## Build and test

```shell
./kotlin test
./kotlin build
./kotlin build --variant release
```

Always the checked-in `./kotlin` wrapper — there is no Gradle build.
`module.yaml` sets `allWarningsAsErrors: true`, so an unused import, an unused
private function, or a deprecation warning fails the build like an error.

## Two verified constraints on what tests can reach

Both were established empirically in this repository; neither is obvious from
the source layout.

**The test source set does not see `internal` declarations from `src/`.** Amper
declares no associate compilation between the main and test Kotlin/Native
compilations, so an `internal` symbol fails to resolve from `test/` with
`cannot access '...': it is internal in file`. Any helper extracted for
testability must be **public**, even when `internal` would express the intent
better.

**The test binary does not link the cinterop klib.** `harmon_test.klib` depends
on `harmon`, but `harmon_native` is not linked into the test executable, so any
code path reaching `dev.yoda.harmon.nativebridge` dies at run time with
`IrLinkageError: Function 'hm_...' can not be called`. Platform libraries
(`platform.posix`, `platform.CoreFoundation`) link fine.

Partial linkage resolves per call site, not per file: a pure function may live
in a file that imports `nativebridge` and still be testable, as long as the
function itself does not call into the bridge. `processCapacityFor` in
`DarwinSystemCollector.kt` is the working example. The consequence is that the
native layer (`cinterop/harmon_native.def`) is verified by running
`harmon diagnose` on a real machine, not by unit tests.

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
