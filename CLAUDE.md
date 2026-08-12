# Harmon

Kotlin/Native macOS workload monitor with separate unprivileged `harmon` and
root `harmon-collector` executables.

Read [`docs/INTENT.md`](docs/INTENT.md) for product intent. Active implementation
plans belong in `docs/plans/`. Before completing one, move its durable product
intent, user behavior, architecture constraints, and testing policy to their
authoritative homes; carry any unfinished work into an active plan or backlog,
then delete the completed plan. Do not archive completed plans.

## Build and test

Use the checked-in Kotlin Toolchain wrapper; this is not a Gradle project.

```shell
./kotlin build
./kotlin test
./kotlin build --variant release
```

Run the debug build before tests. `SelftestBridgeTest` executes `selftest`, and
the JVM Playwright tests execute `webuicheck`; `./kotlin test` does not link
either binary. Build outputs and local smoke-test commands are documented in
[`README.md`](README.md#build-and-test).

Every module must apply `harmon.module-template.yaml`; root Kotlin settings do
not propagate to child modules.

## Boundaries

- Keep `core` free of project cinterop and SQLDelight types.
- Keep bridge-backed and app-specific construction in `src/main.kt` and
  `harmon-collector/src/main.kt`. Do not hide it in default constructor values.
- Test compilations cannot access `internal` declarations from production
  sources or a module's own project cinterop bindings. Directly tested helpers
  therefore need to be public; native binding checks belong in the external
  harnesses. This is the KTC-5573 limitation described in
  [`docs/native-testing.md`](docs/native-testing.md).
- Tests within a module are package-less and share one namespace. Give private
  top-level classifiers unique names across files.

Read [`docs/architecture.md`](docs/architecture.md) before changing process
boundaries and [`docs/collection.md`](docs/collection.md) before changing
collection limits, metric calculations, grouping, or alert semantics.

## Live process UI

Keep process snapshots, payloads, page rendering, and sampling policy in
`core`; keep the loopback HTTP server and app-specific wiring under
`src/dev/yoda/harmon/web`. `webuicheck` is the deterministic native fixture for
the JVM Playwright suite in `webuitest`; build before running
`./kotlin test -m webuitest`.

## Native layer

[`docs/native-testing.md`](docs/native-testing.md) owns the harness protocol,
machine requirements, staleness guard, and accepted gaps. A new harness check
must also be added to `C_HARNESS_CHECKS` in `test/NativeCTest.kt` or
`SELFTEST_CHECKS` in `test/SelftestBridgeTest.kt`.

## History

[`docs/history.md`](docs/history.md) owns the schema and migration procedure.
Generated SQLDelight migrations are disabled; evolve existing databases in
`history-sqlite/src/dev/yoda/harmon/history/SchemaMigration.kt`, not with a
`.sqm` file. Exercise storage through `HistoryStore.openOrNull` so tests use
the production driver configuration.

Keep the SQLDelight compiler, dialect, and native driver on the shared
`version.ref` in `libs.versions.toml`. Both `history-sqlite/module.yaml` and the
root `module.yaml` need their module-local `-lsqlite3` linker option.

Update the relevant authoritative document in the same change when behavior,
schema, or native-test coverage changes.
