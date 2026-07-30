# Harmon

Kotlin/Native macOS workload monitor built with the JetBrains Kotlin Toolchain
(Amper). Two executables enforce the privilege boundary: `harmon` is the user
agent and CLI; `harmon-collector` is the root collector.

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

Debug application outputs are:

```text
build/tasks/_harmon_linkMacosArm64Debug/harmon.kexe
build/tasks/_harmon-collector_linkMacosArm64Debug/harmon-collector.kexe
```

The root module takes its name from the checkout directory, so `_harmon_` can
be `_<checkout>_` in a differently named clone or worktree. The collector is a
physical `harmon-collector/` module, so its task directory and executable name
are stable. Replace `Debug` with `Release` for `--variant release`.

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

**The test compilation cannot reach a module's own cinterop bindings.** The cause
is [KTC-5573](https://youtrack.jetbrains.com/issue/KTC-5573): the cinterop klib
is registered only for the non-test fragment, the test compilation asks for the
artifact with `isTest=true`, does not find it, and the `AnyOrNone` quantifier
lets the miss pass silently. Platform libraries (`platform.posix`,
`platform.Foundation`) link fine.

**Only a module's own.** A third-party klib that carries cinterop of its own
links into the test binary normally, and this branch depends on it:
`$libs.sqldelight.native.driver` reaches a real SQLite through sqliter's
cinterop, which is why the whole storage layer is unit-tested against actual
files instead of through `selftest`. See the dependency comment in
`history-sqlite/module.yaml`. So "cinterop is unreachable from tests" is true
of the project-owned `bridge-*` modules and false in general — do not conclude
from it that a test cannot open a database.

A test source file cannot name the project bridge bindings at all:
referencing `dev.yoda.harmon.nativebridge.ipc`, `.probe`, or `.http` from an
ordinary module test fails with an unresolved reference. Adding the bridge as a
test dependency does not help; that is exactly the registration KTC-5573 is
about. This is why bridge-free policy and calculations live in `core`, while
the two app modules own the native call sites.

The consequence is not that the native layer is unverified — see the next
section — but that neither harness can be an ordinary `kotlin.test` class. When
KTC-5573 is fixed, `selftest` collapses into normal tests almost mechanically:
its assertions are already Kotlin, and the bridge plus the staleness guard are
what gets deleted.

## How the native layer is tested

The native layer is split into three responsibility-specific definitions:
`bridge-ipc/cinterop/harmon_ipc.def`,
`bridge-probe/cinterop/harmon_probe.def`, and
`bridge-http/cinterop/harmon_http.def`. Two external harnesses cover them, both
driven from `./kotlin test`: a C test binary built from `test/native/` for
everything assertable from C, and a `selftest` macos/app module depending only
on `bridge-probe` for the one thing plain C cannot check about itself — that
Kotlin sees the structs and types the probe bridge actually exports.
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
build/tasks/_harmon-collector_linkMacosArm64Debug/harmon-collector.kexe \
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

## The history database

`harmon run` writes every sample to
`~/Library/Application Support/Harmon/history.db` through SQLDelight. The `.sq`
schema is in `history-sqlite/sqldelight/dev/yoda/harmon/db/`, and
`plugins/sqldelight-gen` — a `jvm/amper-plugin` module registered in
`project.yaml` and enabled only by `history-sqlite/module.yaml` — runs the
SQLDelight compiler over it at build time. The compiler, its dialect and the
runtime `native-driver` share one `version.ref` in `libs.versions.toml`; a
mismatch between generator and driver surfaces as a compile error against a
stranger's API rather than as a resolution failure. `docs/history.md` is the
schema reference.

Six facts that each cost a day to find. All were established in this
repository; none are visible from the code that depends on them.

**A `.sqm` file never runs.** The plugin generates with
`deriveSchemaFromMigrations = false` and `verifyMigrations = false`, so `.sqm`
files contribute nothing and the generated `Schema.migrate()` is a body that
returns `QueryResult.Unit`. Adding a migration file and waiting for the driver
to apply it fails silently. Schema evolution is hand-rolled in the store;
`docs/history.md` has the two forms.

**`PRAGMA auto_vacuum` belongs in `lifecycleConfig.onCreateConnection`.** That
is the only hook sqliter runs before it applies `journal_mode`
(open → onCreateConnection → synchronous → foreign keys → journal_mode →
migrateIfNeeded). Once WAL has written a fresh file's header, `auto_vacuum` is
frozen at 0 for the life of the file and only a full `VACUUM` cures it —
retention then deletes rows and returns no space, which nothing reports.

**Foreign keys are off on a fresh connection, and `inMemoryDriver(Schema)` does
not turn them on.** A cascade test written against that driver passes without
asserting anything. Tests open the store through `openOrNull` over a scratch
`HOME` so they run the production `openHistoryDriver` configuration.

**`last_insert_rowid()` has to be read inside the transaction that wrote.** The
driver keeps a transaction pool apart from a reader pool; outside a transaction
the query lands on a reader whose counter is 0, and every child row then fails
its `sample_id` foreign key. `inMemoryDriver` hides this — it has one
connection.

**`PRAGMA incremental_vacuum` returns one empty row per freed page.** So
`driver.execute` throws on the first `SQLITE_ROW`, after the preceding `DELETE`
has already committed, and a bare `driver.executeQuery` hits the read-only pool
and fails `SQLITE_READONLY`. It works as an `executeQuery` inside its own
transaction. Invisible in a small database: a delete that frees no page returns
no row.

**The explicit `-lsqlite3` compiler setting is module-local.** The bridge spike
proved that `linkerOpts` declared by a `.def` do reach a dependent app. That is
not what this setting is: `freeCompilerArgs` does not cross a module boundary.
`history-sqlite/module.yaml` therefore carries
`[-linker-option, -lsqlite3]` for its own test executable, and the root
`module.yaml` repeats it for the final `harmon` link. `harmon-collector` is a
separate app module and inherits neither copy. `-lsqlite3` is a system library
in every macOS SDK, so no `-L` is needed.

To see what a `.sq` file actually generated, read
`build/tasks/_history-sqlite_generate@sqldelight-gen/dev/yoda/harmon/db/harmon/HarmonDatabaseImpl.kt`.
That is where a named parameter, a nullable column or a missing query shows up
as Kotlin.

## Layout

```text
core/         kmp/lib with model, protocol, policy, config, reports, and runtime
  src/        no project cinterop and no SQLDelight types
  test/       pure/core tests and module-local TestFixtures.kt
history-sqlite/
  src/        HistoryStore, row mappings, and retention
  sqldelight/ schema and queries; generated code goes to the build tree
  test/       real-sqlite tests and its own duplicated model fixtures
harmon-collector/
  src/        collector CLI, IPC server, Darwin collector, and composition main
  test/       collector parser and constructor-limit tests
src/          harmon CLI, IPC client, notification implementations, and main
test/         root-only CLI, notification factory, and external harness tests
  native/     the C harness: one binary from every *.c, no main outside main.c,
              one suite per file named after the prefix it reports under
bridge-ipc/   socket/framing cinterop, no linker options
bridge-install/
              root-app-only _NSGetExecutablePath cinterop, no linker options
bridge-probe/ libproc/Mach/sysctl/IOKit cinterop, collector-only
bridge-http/  libcurl cinterop, harmon-only
selftest/     macos/app depending only on bridge-probe; the binding checks
              that ./kotlin test cannot reach
plugins/sqldelight-gen/
              jvm/amper-plugin module driving the SQLDelight compiler
scripts/      install and uninstall flows, plus test-native.sh
project.yaml  the extra modules and the plugin; the root module is included
              implicitly
libs.versions.toml
              one version.ref for the SQLDelight compiler and driver
harmon.module-template.yaml
              settings.kotlin applied by every module
```

Each module's `test/` is flat and its files declare no package, so a
**private top-level classifier collides across files in that module**: two
files each declaring
`private object Fake` fail with `redeclaration`, and the second file's own
reference to it then fails with `it is private in file`. Private top-level
functions and properties do not collide — same name, same signature, two files
compile. Test source is not exported across module dependencies, so shared
builders are duplicated in `core/test` and `history-sqlite/test`, never moved
into production just to make tests share them.

Dependency injection is through constructor parameters, but a default must not
construct a bridge-backed or otherwise heavy implementation. `HarmonService`
therefore requires its `SystemCollector` and lazy `NotificationDispatcher`;
`CollectorServer` requires its `SystemCollector`. The root `src/main.kt`
constructs `CollectorClient`, notification implementations, and
`HistoryStore`; `harmon-collector/src/main.kt` constructs
`DarwinSystemCollector` and `CollectorServer`. Defaults for pure collaborators
such as `UsageCalculator`, `AlertAnalyzer`, and `ApplicationGrouper`, optional
`history = null`, scalar capacities, and log sinks remain legitimate. Keep
future app-specific assembly in the two `main.kt` composition roots.

## Documentation

`README.md`, `docs/collection.md`, `docs/architecture.md`, `docs/history.md`,
and `docs/native-testing.md` describe observed behaviour, not intent. When
collection limits, alert semantics, or metric definitions change, the doc change
belongs in the same commit. `docs/history.md` carries the same obligation for
the schema: it is the only interface the stored samples have, so a column added
or renamed is a doc change in the same commit. The same rule binds
`docs/native-testing.md` harder than the rest: its accepted-gaps list is only
worth having while it is exhaustive, so a check that closes a gap removes its
entry in the same commit.
