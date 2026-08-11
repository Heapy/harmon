# Accounted power alert

## Overview

Harmon reads the kernel's own energy counter — `ri_energy_nj` from
`rusage_info_v6` — and turns it into `energyWatts`, but nothing alerts on it.
Every battery alert goes through `batteryImpactScore`, a heuristic whose weights
are far heavier than Apple's ever were: 0.25 per wakeup/s against Apple's
historical 0.05 (OS X 10.9) and 0.02 (late Intel `pmenergy` plist), plus an I/O
term of 2.0 per MiB/s that Apple has no analogue for. The practical consequence
is a false positive class: 61 MiB/s of disk traffic scores 123 and wakes the
user, while costing the battery almost nothing.

This change makes the accurate counter the metric of record wherever it works,
and leaves the heuristic as the silent fallback where it does not.

- alerting gains `applicationPowerAlertWatts`, a threshold in watts;
- the text report shows watts instead of the score when the counter is live;
- the JSON payload gains one boolean so an agent can tell which regime produced
  the numbers it is reading.

Nothing in the collector, the bridges, the IPC protocol or the history schema
changes. The protocol stays at version 2, so `harmon setup` is not required.

## Context (from discovery)

Files involved, all in `core` except the shipped example config:

- `core/src/dev/yoda/harmon/model/Models.kt:317` — `SystemUsage`
- `core/src/dev/yoda/harmon/config/Config.kt:45` — `AlertThresholds`;
  `redactedDescription()` at `:96` with the threshold block at `:116`;
  `configurableKeys` at `:171`; the threshold parse block at `:289-316`
- `config/harmon.conf.example:29` — the shipped example, which
  `test/ExampleConfigTest.kt:60` asserts covers every configurable key
- `core/src/dev/yoda/harmon/analysis/AlertAnalyzer.kt:206` — the battery-impact
  rule
- `core/src/dev/yoda/harmon/report/ReportFormatter.kt:66` — the
  "Likely application battery impact" table
- `core/src/dev/yoda/harmon/report/ReportJson.kt` — the root DTO

Patterns found that shape the work:

- **`ApplicationRankings` already has both lists.** `topBatteryImpact` (`:22`)
  sorts by score; `topEnergy` (`:57`) filters `energyWatts > 0` and sorts by
  watts. The text report uses only the first, the JSON payload uses both. So the
  metric switch is a choice between two existing lists in the formatter — the
  rankings class is not modified, and the JSON payload keeps its current sort
  without any special handling.
- **The existing rule nests its `onBattery` guard inside the threshold's
  `?.let`** (`AlertAnalyzer.kt:206-207`), and `optionalPositiveDouble` maps `0`
  to `null` (`Config.kt:501-513`). Adding the new branch inside that block would
  make `applicationBatteryImpactAlertScore=0` disable the watt rule too. The
  nesting has to be inverted — see Task 3.
- **`AlertState` prunes to the keys firing in the current sample**
  (`AlertState.kt:97`), so a key that stops firing disappears rather than
  hanging. Changing the alert key is therefore safe.
- **Two independent copies of `TestFixtures.kt`** exist (`core/test/` and
  `history-sqlite/test/`) because test sources are not exported across modules.
  A new `SystemUsage` constructor parameter would have to be added to both; a
  computed property costs nothing.
- **The counter's availability is inferable from the sample.** Verified against
  the live database: 1720 of 1720 samples carry positive energy, with a minimum
  of 388 positive processes per sample.

Dependencies: none added.

## Development Approach

- **testing approach**: Regular (code first, then tests within the same task)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in
  that task
- **CRITICAL: all tests must pass before starting next task**
- **CRITICAL: update this plan file when scope changes during implementation**
- `./kotlin build` before `./kotlin test` — the test task does not link
  `selftest`, and a stale binary fails `SelftestBridgeTest` with that
  instruction
- maintain backward compatibility: no config key is removed, no JSON field
  changes meaning

## Testing Strategy

- **unit tests**: required for every task. Most land in `core/test/`, but Task 2
  is also gated by `test/ExampleConfigTest.kt` in the **root** module — a
  configurable key missing from `config/harmon.conf.example` fails the suite.
  No new test files: every target already has one.
- **e2e tests**: the project has none of the UI kind. End-to-end verification is
  `harmon diagnose` against a local unprivileged collector, which belongs to
  Post-Completion because it needs a real machine.
- test source cannot see `internal` declarations from `src/`, so anything a test
  must name has to be public. `ApplicationRankings` is `internal` and stays
  untested directly; it is covered through the formatter and the JSON payload.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

One decision drives everything: **which metric leads is decided per sample, not
per row.** A process reading zero watts on a machine whose counter works is a
process that genuinely slept, not a measurement failure. Mixing watts for some
rows and score for others inside one table would produce a column that is not
comparable with itself.

A consequence worth stating: the accounted-power table renders
`rankings.topEnergy`, which filters `energyWatts > 0`
(`ApplicationRankings.kt:59`). Applications drawing nothing drop out of the
table rather than appearing as zero rows, so the accounted table can be shorter
than `topProcessCount`. That is the accepted behaviour — a list of applications
costing nothing is not worth the lines.

The availability signal is a computed property on `SystemUsage`:

```kotlin
val energyAccounted: Boolean
    get() = processes.any { it.energyWatts > 0.0 }
```

Computed rather than stored, for the fixture-duplication reason above, and it is
correct retroactively for history written before this change.

Three consumers read it, all the same way:

- `AlertAnalyzer` picks the rule;
- `ReportFormatter` picks the table's metric, its heading, and which of the two
  ranking lists to render;
- `ReportJson` exposes it as a field and changes nothing else.

The asymmetry between the text and JSON surfaces is deliberate. The text report
switches, because a human needs one column. The JSON payload does not switch,
because `topBatteryImpact` and `topEnergy` are both already there and renaming
what a field sorts by would make the field lie. An agent gets the flag and
decides for itself — and a flag is the only form of caveat an agent will not
skip, unlike a footnote.

## Technical Details

**Config key.** `applicationPowerAlertWatts`, default `1.5`, `0` disables. Named
by the existing `application<What>Alert<Unit>` shape. Not `...Energy...`: a watt
is power, not energy. No upper bound, matching `applicationCpuAlertPercent`.

The default is derived from the live database rather than guessed. Per-sample
application watts: p99 = 0.276, p99.9 = 1.108, max = 3.717. At 1.5 W that is 54
firings across 1725 samples — Firefox 29, IntelliJ IDEA 21, everything else in
single digits — and 8 criticals at the 2x mark, all Firefox. One watt sustained
across an eight-hour day is roughly 8 Wh, about 15% of a typical MacBook
battery, so the threshold sits where it means something.

**Alert rule.** The current shape is `threshold?.let { if (onBattery) { … } }`.
It has to become `if (onBattery) { if (accounted) watts?.let {…} else score?.let
{…} }` — the `onBattery` guard moves outward and each regime reads its own
threshold. Without that inversion `applicationBatteryImpactAlertScore=0` would
silently disable the watt rule, since `optionalPositiveDouble` turns `0` into
`null`.

Each key governs its own regime and nothing else:

| Counter | `applicationPowerAlertWatts` | `applicationBatteryImpactAlertScore` | Result |
|---|---|---|---|
| live | 1.5 | any | watt alert |
| live | 0 | any | silent — no fallback to the score |
| dead | any | 100 | score alert, exactly as today |
| dead | any | 0 | silent |

No fallback from a disabled watt threshold to the score: disabling the accurate
rule on a machine that supports it is a deliberate choice, and quietly
substituting the heuristic would defeat it.

Unchanged: `selectAlerting` with `maxAlertsPerCategory`, the `cleared()`
hysteresis, CRITICAL at twice the threshold, and the "Likely battery drain"
title. The alert key becomes `power:${it.id}` for the watt branch. The message
mirrors the existing
`"${application.alertLabel()} has impact score ${Format.decimal(score)}"` as:

```kotlin
"${application.alertLabel()} draws ${Format.power(application.energyWatts)}"
```

Switching the key means a machine crossing between regimes clears one alert and
raises another for the same application. Accepted deliberately: the noise is
bounded to the moment of the switch, and in exchange `history.db`'s `alert`
table records which metric fired.

**Text report.** The table heading carries the distinction —
`Likely application battery impact (accounted power)` versus
`(heuristic score)` — because otherwise the same line on two machines would mean
different things with nothing to say so. When the counter is live the row shows
`2.4 W, 40 wakeups/s, 12 MiB/s I/O` and the trailing `, N W accounted` fragment
disappears, since the value has become the headline.

**JSON.** One new field on the root DTO, `energyAccounted: Boolean`. The webhook
payload inherits it, which makes it a `docs/collection.md` change too.

## What Goes Where

- **Implementation Steps**: all code, tests and documentation in this repository
- **Post-Completion**: verification that needs a real machine on battery

## Implementation Steps

### Task 1: Add the counter-availability signal to SystemUsage

**Files:**
- Modify: `core/src/dev/yoda/harmon/model/Models.kt`
- Modify: `core/test/UsageCalculatorTest.kt`

- [x] add `energyAccounted` as a computed `val` on `SystemUsage`
      (`Models.kt:317`), not a constructor parameter
- [x] document in a KDoc line why it is inferred rather than reported: the
      bridge zero-initializes and falls back to `RUSAGE_INFO_V4`, which leaves
      `ri_energy_nj` at zero, so a live counter is only knowable from the values
- [x] write a test that a sample with at least one positive `energyWatts`
      reports `energyAccounted == true`
- [x] write a test that a sample whose processes all read zero reports `false`,
      including the empty-process-list case
- [x] run `./kotlin build && ./kotlin test` — must pass before task 2

### Task 2: Add the applicationPowerAlertWatts config key

**Files:**
- Modify: `core/src/dev/yoda/harmon/config/Config.kt`
- Modify: `config/harmon.conf.example`
- Modify: `core/test/ConfigLoaderTest.kt`

- [x] add `applicationPowerWatts: Double? = 1.5` to `AlertThresholds`
      (`Config.kt:45`)
- [x] parse `applicationPowerAlertWatts` through `optionalPositiveDouble` in the
      threshold block (`Config.kt:289-316`)
- [x] emit it from `redactedDescription()` next to the existing threshold lines
      (`Config.kt:116`), following the `?: 0` convention — this is what
      `harmon check-config` prints, not a default-config generator
- [x] add the key to `configurableKeys` (`Config.kt:171`); no legacy alias is
      needed
- [x] add `applicationPowerAlertWatts=1.5` to `config/harmon.conf.example`
      beside `applicationBatteryImpactAlertScore=100` (`:29`) — **required**:
      `test/ExampleConfigTest.kt:60` asserts `configurableKeys` minus the keys
      the example names is empty, so omitting it fails this task's own gate
- [x] write tests: the key parses, the default is 1.5 when absent, `0` disables
      the rule, a negative value is rejected the way its neighbours are
- [x] run `./kotlin build && ./kotlin test` — must pass before task 3

### Task 3: Branch the battery alert rule on counter availability

**Files:**
- Modify: `core/src/dev/yoda/harmon/analysis/AlertAnalyzer.kt`
- Modify: `core/test/AlertAnalyzerTest.kt`

- [x] invert the existing nesting at `AlertAnalyzer.kt:206`: hoist
      `if (usage.power.onBattery)` outside, and read each threshold inside its
      own branch. Nesting the new rule inside
      `thresholds.applicationBatteryImpactScore?.let` would let
      `applicationBatteryImpactAlertScore=0` disable the watt rule as well
- [x] add the watt branch under `usage.energyAccounted`: key `power:${it.id}`,
      value `it.energyWatts`, `clearThreshold = threshold.cleared()`,
      `maxPerCategory = config.maxAlertsPerCategory` — the same `selectAlerting`
      call shape as the score branch
- [x] keep severity CRITICAL at `>= threshold * 2` and the title
      "Likely battery drain"; message reads
      `"${application.alertLabel()} draws ${Format.power(...)}"`
- [x] leave the score branch behaviour untouched for the unavailable-counter
      case
- [x] write tests for the four regime cases: live counter over threshold fires a
      watt alert; live counter under threshold is silent; dead counter fires the
      score rule; not on battery is silent in both regimes
- [x] write tests for the two-key cross product: `applicationPowerAlertWatts=0`
      with a live counter is silent and does **not** fall back to the score;
      `applicationBatteryImpactAlertScore=0` with a live counter still fires the
      watt alert — this is the regression the inversion exists for
- [x] write tests: CRITICAL at twice the threshold, the key is `power:`, and the
      message text matches what Task 6 documents
- [x] run `./kotlin build && ./kotlin test` — must pass before task 4

### Task 4: Switch the text report's metric and heading

**Files:**
- Modify: `core/src/dev/yoda/harmon/report/ReportFormatter.kt`
- Modify: `core/test/ReportFormatterTest.kt`

- [x] at `ReportFormatter.kt:66`, choose `rankings.topEnergy` when
      `usage.energyAccounted` and `rankings.topBatteryImpact` otherwise —
      `ApplicationRankings` itself is not modified
- [x] make the heading carry the regime: `(accounted power)` versus
      `(heuristic score)`
- [x] in the accounted-power form, lead with `Format.power(energyWatts)` and
      drop the trailing `, N W accounted` fragment
- [x] leave the heuristic form exactly as it renders today — the rendering is
      byte-identical; the source lost the per-row `, N W accounted` conditional
      because it is unreachable in that branch (application `energyWatts` is a
      plain sum of process watts, so `energyAccounted == false` forces every
      application to zero)
- [x] fix `reportShowsSystemStorageAndCompressedMemorySignals`
      (`core/test/ReportFormatterTest.kt:76`): it asserts
      `"12.0 mW accounted"` against a fixture with `energyWatts = 0.012`, which
      now makes the sample accounted and removes that fragment. Either retarget
      the assertion at the new form or set the fixture to `0.0` so it keeps
      covering the heuristic path — **chose `0.0`**, which keeps the test about
      the storage and compressed-memory signals it is named for
- [x] write tests for both forms, asserting the heading as well as the row — the
      heading is the easiest part to forget
- [x] run `./kotlin build && ./kotlin test` — must pass before task 5

### Task 5: Expose the regime in the JSON payload

**Files:**
- Modify: `core/src/dev/yoda/harmon/report/ReportJson.kt`
- Modify: `core/test/ReportJsonTest.kt`

- [x] add `energyAccounted: Boolean` to the root DTO and populate it from
      `usage.energyAccounted`
- [x] change nothing else: `topBatteryImpact` keeps sorting by score,
      `topEnergy` keeps filtering `> 0`
- [x] write tests for the field in both regimes
- [x] write a regression test that `topBatteryImpact` is still ordered by score
      even when the counter is live — this is the guard on the text/JSON
      asymmetry surviving future refactors. Verified by mutation: switching
      either the application slice to `rankings.topEnergy` or the inline process
      sort to `energyWatts` fails it, and the process slice is covered because
      it is sorted in `ReportJson` rather than by `ApplicationRankings`
- [x] run `./kotlin build && ./kotlin test` — must pass before task 6

### Task 6: Update the documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/collection.md`
- Modify: `docs/history.md`

- [x] add `applicationPowerAlertWatts=1.5` to the example config block in
      `README.md`
- [x] state plainly in `README.md` that on a machine with a working counter
      `applicationBatteryImpactAlertScore` no longer fires and the watt threshold
      takes over — existing configurations change behaviour, so this is not a
      footnote. Placed in the Configuration prose beside the score key and its
      aliases, not in "Upgrading from an earlier build", which narrates the
      0.5.0 protocol-2 migration and nothing else
- [x] correct the two `README.md` passages that name the score as the alerting
      metric: the feature list at `:50-51` and the `### Battery impact` section
      at `:100-110` ("useful for ranking and alerting")
- [x] add the rule to the alert table in `docs/collection.md` and describe in
      "Battery-impact ranking" which metric appears when. Both battery rows are
      qualified by regime — "counter reporting" against "counter silent" —
      because the unqualified score row became wrong the moment the watt row
      joined it. The rule's key and message forms are documented under the
      table, which is what `AlertAnalyzerTest` pins
- [x] add `energyAccounted` to the `harmon.sample` payload description
      (`docs/collection.md:624-632`)
- [x] add the weight comparison to `docs/collection.md`: 0.25 per wakeup/s
      against Apple's 0.05 (OS X 10.9) and 0.02 (late Intel plist), and an I/O
      term with no Apple analogue — this is why the score never matched Activity
      Monitor. Written as a reconstruction, since Apple has never published the
      formula and the current guide calls it only a relative measure
- [x] add `power:` to the per-application alert key prefixes in
      `docs/history.md`, which enumerates them as exhaustive in two places:
      `:155-157` (the `substr` join explanation, "there are four
      per-application rules today") and `:513-515` (the `alert.key` reference).
      Both become wrong the moment the new key ships
- [x] leave `docs/native-testing.md` untouched: no C change, no new harness
      check

### Task 7: Verify acceptance criteria

- [x] `applicationPowerAlertWatts` parses, defaults to 1.5, and `0` disables —
      `optionalPositiveDouble` (`Config.kt:521-533`) returns the default when the
      key is absent, `null` on `0`, and throws on a negative; pinned by
      `ConfigLoaderTest.readsTheApplicationPowerThresholdAndTakesZeroAsDisabled`,
      `.rejectsANegativeApplicationPowerThreshold`, and
      `.reportsTheApplicationPowerThresholdEvenWhenItIsDisabled`
- [x] the two keys are independent: neither one set to `0` disables the other's
      rule — `AlertAnalyzer.kt:206` reads `if (usage.power.onBattery)` first, then
      branches on `usage.energyAccounted`, and only inside each branch does the
      regime's own `?.let` run, so neither `null` can reach the other's rule;
      pinned by `AlertAnalyzerTest.doesNotFallBackToTheScoreWhenTheWattThresholdIsDisabled`
      and `.alertsOnWattsWhileTheScoreThresholdIsDisabled`
- [x] the watt rule fires only on battery and only when the counter is live —
      `AlertAnalyzerTest.alertsOnAccountedWattsWhereTheEnergyCounterIsLive`,
      `.raisesNoBatteryDrainAlertBelowTheWattThreshold` (impact 260 stays silent,
      so the score is genuinely not consulted in this regime), and
      `.raisesNoBatteryDrainAlertInEitherRegimeWhileOnWallPower`
- [x] the score rule still fires unchanged when the counter is dead — the block
      moved into the `else` branch with a byte-identical body (key, value,
      severity, title and message all unchanged in the diff);
      `AlertAnalyzerTest.fallsBackToTheHeuristicScoreWhereTheEnergyCounterIsDead`
- [x] the text report switches metric and heading together — one `energyAccounted`
      read drives the heading, the list and the metric lambda
      (`ReportFormatter.kt:66-92`); both
      `ReportFormatterTest.theBatteryImpactTableLeadsWithWattsWhenTheCounterIsAccounted`
      and `.theBatteryImpactTableKeepsTheHeuristicScoreWhenNothingIsAccounted`
      assert the heading and the full row list in one comparison, so a heading
      that moved without its list fails
- [x] the JSON payload gained exactly one field and changed no sort order —
      `git diff --numstat` on `ReportJson.kt` is `10 0`, all ten added lines being
      `energyAccounted` and its KDoc, and `ApplicationRankings.kt` is absent from
      the diff entirely; `ReportJsonTest.keepsTopBatteryImpactOnTheHeuristicScoreWhileTheCounterIsLive`
      pins both the application and the process slice, and
      `.reportsWhetherTheKernelEnergyCounterProducedThisSample` pins both regimes
- [x] `config/harmon.conf.example` names every configurable key
      (`test/ExampleConfigTest.kt` proves it) —
      `ExampleConfigTest.theShippedExampleParsesAndNamesEveryConfigurableKey`
      ran and passed
- [x] the collector, the bridges, `CollectorProtocol` and the `.sq` schema are
      untouched — confirm with `git diff --stat`: `git diff --stat main...HEAD --
      harmon-collector/ 'bridge-*' history-sqlite/ '*CollectorProtocol.kt' '*.sq'`
      returns nothing
- [x] run the full suite: `./kotlin build && ./kotlin test` — 352 tests across the
      ten test tasks, 0 failures, `SelftestBridgeTest` and `NativeCTest` included
- [x] run `./kotlin build --variant release` to confirm the release variant links —
      "Build successful"; the `'+zcm' is not a recognized feature` lines the
      linker prints are LLVM notices on stderr, not build failures

### Task 8: [Final] Close out

- [x] re-read `README.md`, `docs/collection.md` and `docs/history.md` against
      the shipped behaviour — no drift. Task 6 was the sixth of seven commits and
      the seventh touched only this plan file, so nothing shipped after the prose
      was written. Re-verified against the code anyway: the key name, default and
      `?: 0` reporting (`Config.kt:65,133,201,329`), both alert keys and their
      messages (`AlertAnalyzer.kt:218,233,246,263`), `Format.power` rendering
      `2.4 W` (`Format.kt:36-43`), CRITICAL at twice the threshold behind the
      `3 W` table cell, the heading/list pair (`ReportFormatter.kt:73-92`), the
      root-DTO field and the untouched sorts (`ReportJson.kt:49,110,118,136,260`),
      `topEnergy`'s `> 0` filter and shared `topProcessCount` take
      (`ApplicationRankings.kt:57-68`), the five per-application prefixes now
      claimed by `docs/history.md`, and `battery_impact_score` still stored on
      both tables either way
- [x] update `CLAUDE.md` only if a new non-obvious constraint was discovered
      during implementation — nothing qualified, so it is untouched. The one
      candidate, "a configurable key fails the suite unless the shipped example
      names it too, and that test lives in the root module", is already written
      out in `test/ExampleConfigTest.kt`'s own KDoc, including why it sits in the
      root module; it also fails a named test rather than hiding
- [x] move this plan to `docs/plans/completed/` — deferred to the harness at the
      end of the run; the review, finalize and stats phases still read the file
      at this path

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes,
informational only*

**Manual verification:**

- run a local unprivileged collector and diagnose through it, per the recipe in
  `CLAUDE.md`, to see the accounted-power table on a real machine
- unplug and confirm a watt alert fires against a genuinely busy application;
  Firefox and IntelliJ IDEA are the two that cross 1.5 W in the existing history
- confirm the score table still appears on a machine or kernel where the counter
  reads zero — this is the branch no local test exercises against real data

**Threshold tuning:**

- 1.5 W is calibrated against six days of one machine's history. If it proves
  noisy or silent in practice, the value is a config key and needs no code
  change to adjust.
