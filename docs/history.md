# Sample history

`harmon run` writes every sample it takes to
`~/Library/Application Support/Harmon/history.db`, and nothing reads it back
except the agent's own alert state. There is no `harmon history` command: the
schema below is the interface, and `sqlite3` is the client.

```shell
sqlite3 "$HOME/Library/Application Support/Harmon/history.db"
```

The agent holds the file open for the length of its run in WAL mode, so a
reader can query it while the agent samples. `once` and `diagnose` measure a
window of seconds — `onceSampleSeconds`, or `--sample-seconds`, anywhere in
1…300 — rather than the sampling interval, so their rates mean something
different from the ones around them and they write nothing at any of those
values. `historyRetentionDays=0` writes nothing at all and creates no file.

## Timestamps

`sample.captured_at` is the only clock in the schema, and it is ISO-8601 **UTC**
truncated to whole seconds — always exactly twenty characters,
`YYYY-MM-DDTHH:MM:SSZ`. Every ordering and every retention comparison is a string
comparison over that form, which is why it is fixed-width: `Instant.toString()`
prints 0, 3, 6 or 9 fractional digits depending on the value, and
`'2026-07-29T00:05:00.500Z' < '2026-07-29T00:05:00Z'` sorts the later moment
first.

Two consequences for anyone querying it:

- questions about local time need `datetime(captured_at, 'localtime')`, or the
  answer is off by the machine's offset — at UTC+3 the "three in the morning"
  sample is stored as `00:00:00Z`;
- a bound compared against `captured_at` has to be built in the same form.
  `strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-1 day')` produces it;
  `datetime('now', '-1 day')` does not — it yields a space instead of the `T`,
  and the comparison then silently matches nothing.

Going the other way, the `utc` modifier turns a local wall-clock time into the
instant to look for: `strftime('%s', '2026-07-29 03:00:00', 'utc')`.

`min()`, `max()` and `ORDER BY` over the column need no conversion at all; the
fixed width is what makes the lexicographic order the chronological one.

`process.started_at` is not a timestamp of this kind. It is the kernel's
`ri_proc_start_abstime` — mach absolute ticks — carried through unchanged. It
identifies one tenant of a pid and is not a date.

## Queries

The sample nearest a moment, and what was running in it:

```sql
SELECT p.name, p.pid, round(ps.cpu_percent, 1) AS cpu,
       ps.physical_footprint_bytes / 1048576 AS mib
FROM process_sample ps
JOIN process p ON p.id = ps.process_id
WHERE ps.sample_id = (
    SELECT id FROM sample
    ORDER BY abs(strftime('%s', captured_at) -
                 strftime('%s', '2026-07-29 03:00:00', 'utc'))
    LIMIT 1)
ORDER BY ps.cpu_percent DESC
LIMIT 20;
```

The system side of the last day, in local time:

```sql
SELECT datetime(captured_at, 'localtime') AS local_time,
       round(processor_total_percent, 1)  AS cpu,
       vm_free_bytes   / 1048576          AS free_mib,
       swap_used_bytes / 1048576          AS swap_mib
FROM sample
WHERE captured_at >= strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-1 day')
ORDER BY captured_at;
```

The heaviest applications over a window:

```sql
SELECT a.name,
       max(aps.physical_footprint_bytes) / 1048576 AS peak_mib,
       round(avg(aps.cpu_percent), 1)              AS mean_cpu,
       max(aps.process_count)                      AS processes
FROM application_sample aps
JOIN application a ON a.id = aps.application_id
JOIN sample s      ON s.id = aps.sample_id
WHERE s.captured_at BETWEEN '2026-07-29T00:00:00Z' AND '2026-07-29T06:00:00Z'
GROUP BY a.id
ORDER BY peak_mib DESC
LIMIT 10;
```

Which helpers of one application were busy, which is what
`process_sample.application_id` exists for:

```sql
SELECT datetime(s.captured_at, 'localtime') AS local_time,
       p.name, p.pid, round(ps.cpu_percent, 1) AS cpu
FROM process_sample ps
JOIN sample s      ON s.id = ps.sample_id
JOIN process p     ON p.id = ps.process_id
JOIN application a ON a.id = ps.application_id
WHERE a.name = 'Google Chrome'
  AND s.captured_at BETWEEN '2026-07-29T00:00:00Z' AND '2026-07-29T06:00:00Z'
ORDER BY s.captured_at, ps.cpu_percent DESC;
```

One process across the window it lived in. The subquery is what makes this a
question about a process rather than about a pid: a pid is recycled within
minutes on a busy machine, and `(pid, started_at)` is what separates the tenants.

```sql
SELECT datetime(s.captured_at, 'localtime') AS local_time,
       round(ps.cpu_percent, 1) AS cpu,
       ps.physical_footprint_bytes / 1048576 AS mib
FROM process_sample ps
JOIN sample s ON s.id = ps.sample_id
WHERE ps.process_id = (
    SELECT id FROM process WHERE pid = 4711 ORDER BY started_at DESC LIMIT 1)
ORDER BY s.captured_at;
```

Alerts, with the application an application alert names. The key of an
application alert is the rule prefixed to `application.key`, so the join is a
concatenation; a `LEFT JOIN` because not every alert is about an application, and
because an alert about a bundle-less group has no row in `application` to reach —
see below.

```sql
SELECT datetime(s.captured_at, 'localtime') AS local_time,
       al.key, al.reported, al.severity, al.message, a.name AS application
FROM alert al
JOIN sample s           ON s.id = al.sample_id
LEFT JOIN application a ON a.key = substr(al.key, instr(al.key, ':') + 1)
ORDER BY s.captured_at, al.key;
```

Splitting the key at its first `:` rather than concatenating each rule prefix in
turn is what keeps this correct as rules are added: there are four
per-application rules today (`cpu:`, `memory:`, `disk-write:`, `battery-impact:`)
and a list of them written into the join answers `NULL` for any rule it forgot —
which reads exactly like "this alert is not about an application".

What a notification channel actually did — the one record of a webhook that has
been answering 500 all night:

```sql
SELECT datetime(s.captured_at, 'localtime') AS local_time, d.channel, d.detail
FROM alert_delivery d
JOIN sample s ON s.id = d.sample_id
WHERE d.successful = 0
ORDER BY s.captured_at DESC
LIMIT 20;
```

How far back the file goes:

```sql
SELECT count(*)                                AS samples,
       datetime(min(captured_at), 'localtime') AS oldest,
       datetime(max(captured_at), 'localtime') AS newest
FROM sample;
```

## Schema

`sqlite3 history.db .schema` prints the authoritative version; what follows is
the same DDL with its inline comments taken out, and what each column carries.
The source of every number is `docs/collection.md` — this document says which
model field a column comes from, not what the metric means.

Booleans are plain `INTEGER` 0/1 throughout. `INTEGER AS Boolean` would make
SQLDelight demand a `ColumnAdapter<Boolean, Long>` through the database
constructor, which is the plumbing the explicit conversions exist to avoid.
`ULong` counters are stored as signed `INTEGER` clamped at `Long.MAX_VALUE`
(`SqlConversions.kt`), so a byte count past 9.2 exabytes reads as that boundary
rather than as a negative number.

All three surrogate keys — `sample.id`, `process.id`, `application.id` — are
`AUTOINCREMENT`, so an id retention frees is never handed to a different row.

### sample

One row per sample, holding the whole of `SystemUsage` except its three lists.

```sql
CREATE TABLE sample (
  id                                   INTEGER PRIMARY KEY AUTOINCREMENT,
  captured_at                          TEXT NOT NULL,
  elapsed_seconds                      REAL NOT NULL,
  physical_memory_bytes                INTEGER NOT NULL,

  swap_total_bytes                     INTEGER NOT NULL,
  swap_available_bytes                 INTEGER NOT NULL,
  swap_used_bytes                      INTEGER NOT NULL,
  swap_encrypted                       INTEGER NOT NULL,

  battery_available                    INTEGER NOT NULL,
  on_battery                           INTEGER NOT NULL,
  charging                             INTEGER NOT NULL,
  battery_percentage                   INTEGER,
  battery_minutes_remaining            INTEGER,

  processor_total_percent              REAL NOT NULL,
  processor_user_percent               REAL NOT NULL,
  processor_system_percent             REAL NOT NULL,
  processor_nice_percent               REAL NOT NULL,
  processor_idle_percent               REAL NOT NULL,

  load_average_1m                      REAL NOT NULL,
  load_average_5m                      REAL NOT NULL,
  load_average_15m                     REAL NOT NULL,

  vm_free_bytes                        INTEGER NOT NULL,
  vm_active_bytes                      INTEGER NOT NULL,
  vm_inactive_bytes                    INTEGER NOT NULL,
  vm_wired_bytes                       INTEGER NOT NULL,
  vm_purgeable_bytes                   INTEGER NOT NULL,
  vm_compressed_bytes                  INTEGER NOT NULL,
  vm_uncompressed_bytes_in_compressor  INTEGER NOT NULL,
  vm_swap_backed_uncompressed_bytes    INTEGER NOT NULL,
  vm_page_in_bytes_per_second          REAL NOT NULL,
  vm_page_out_bytes_per_second         REAL NOT NULL,
  vm_fault_rate                        REAL NOT NULL,
  vm_copy_on_write_fault_rate          REAL NOT NULL,
  vm_compression_bytes_per_second      REAL NOT NULL,
  vm_decompression_bytes_per_second    REAL NOT NULL,
  vm_swap_in_bytes_per_second          REAL NOT NULL,
  vm_swap_out_bytes_per_second         REAL NOT NULL,

  storage_available                    INTEGER NOT NULL,
  storage_device_count                 INTEGER NOT NULL,
  storage_read_bytes_per_second        REAL NOT NULL,
  storage_write_bytes_per_second       REAL NOT NULL,
  storage_read_operations_per_second   REAL NOT NULL,
  storage_write_operations_per_second  REAL NOT NULL,
  storage_read_service_time_percent    REAL NOT NULL,
  storage_write_service_time_percent   REAL NOT NULL,
  storage_root_total_bytes             INTEGER NOT NULL,
  storage_root_available_bytes         INTEGER NOT NULL,

  total_process_count                  INTEGER NOT NULL,
  inaccessible_process_count           INTEGER NOT NULL,
  compressed_attribution_process_count INTEGER NOT NULL,
  compressed_attribution_failure_count INTEGER NOT NULL
);

CREATE INDEX sample_captured_at ON sample(captured_at);
```

| Column group | Model | Notes |
|---|---|---|
| `captured_at` | `SystemUsage.capturedAt` | wall clock of the second of the two snapshots |
| `elapsed_seconds` | `SystemUsage.elapsedSeconds` | the monotonic distance between those two snapshots, which every rate in the sample is divided by. Not the configured interval: a slow collector call widens it |
| `physical_memory_bytes` | `SystemUsage.physicalMemoryBytes` | installed RAM |
| `swap_*` | `SystemUsage.swap` | `swap_encrypted` is 0/1 |
| `battery_*`, `on_battery`, `charging` | `SystemUsage.power` | the three flags are 0/1. `battery_percentage` and `battery_minutes_remaining` are null on a machine with no battery — null rather than 0, because 0 percent is a reading |
| `processor_*` | `SystemUsage.processor` | percentages over `elapsed_seconds` |
| `load_average_*` | `SystemUsage.loadAverages` | instantaneous, not derived from the interval |
| `vm_*` | `SystemUsage.virtualMemory` | `*_bytes` are levels at the moment of the sample; `*_per_second` and `*_rate` are derived over `elapsed_seconds` |
| `storage_*` | `SystemUsage.storage` | `storage_available = 0` means the device counters were not comparable across the two snapshots, and then all six rate columns are a written 0.0 rather than a reading. `storage_root_*` come from the snapshot either way |
| the four counts | `SystemUsage.totalProcessCount`, `.inaccessibleProcessCount`, `.compressedAttributionProcessCount`, `.compressedAttributionFailureCount` | `total_process_count - inaccessible_process_count` is the number of `process_sample` rows the sample has |

The index carries both readers: the time-slice query and the retention delete.

### process, process_sample

```sql
CREATE TABLE process (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  pid             INTEGER NOT NULL,
  started_at      INTEGER NOT NULL,
  name            TEXT NOT NULL,
  executable_path TEXT,
  uid             INTEGER,
  parent_pid      INTEGER NOT NULL,
  UNIQUE (pid, started_at)
);

CREATE TABLE process_sample (
  sample_id                             INTEGER NOT NULL REFERENCES sample(id) ON DELETE CASCADE,
  process_id                            INTEGER NOT NULL,
  application_id                        INTEGER,

  cpu_percent                           REAL NOT NULL,
  user_cpu_percent                      REAL NOT NULL,
  system_cpu_percent                    REAL NOT NULL,

  physical_footprint_bytes              INTEGER NOT NULL,
  resident_bytes                        INTEGER NOT NULL,
  wired_bytes                           INTEGER NOT NULL,
  lifetime_max_physical_footprint_bytes INTEGER NOT NULL,
  compressed_or_paged_out_bytes         INTEGER,
  virtual_memory_region_count           INTEGER,

  wakeups_per_second                    REAL NOT NULL,
  page_ins_per_second                   REAL NOT NULL,
  disk_read_bytes_per_second            REAL NOT NULL,
  disk_write_bytes_per_second           REAL NOT NULL,
  logical_write_bytes_per_second        REAL NOT NULL,
  instructions_per_second               REAL NOT NULL,
  cycles_per_second                     REAL NOT NULL,
  energy_watts                          REAL NOT NULL,
  faults_per_second                     REAL NOT NULL,
  copy_on_write_faults_per_second       REAL NOT NULL,
  system_calls_per_second               REAL NOT NULL,
  context_switches_per_second           REAL NOT NULL,
  thread_count                          INTEGER NOT NULL,
  running_thread_count                  INTEGER NOT NULL,
  billed_energy_per_second              REAL NOT NULL,
  battery_impact_score                  REAL NOT NULL
);

CREATE INDEX process_sample_sample_id ON process_sample(sample_id);
```

`process` is the lookup: `pid` and `started_at` are `ProcessUsage.identity`, the
rest is the naming half of `ProcessUsage`, written once instead of 288 times a
day. `uid` is null when the collector could not read it, and `executable_path` is
null when it read an empty one. `name`, `uid` and `parent_pid` freeze at first
sighting: the insert conflicts into a no-op, so a process that renames itself
keeps the name it was first seen under. `executable_path` is the one column a
later sighting may still write, and only from null to non-null; see below.

`process_sample` is one row per readable process per sample — around 222 000 a
day on a machine running several hundred processes — and every column after the
three ids is a numeric field of `ProcessUsage` under the same name.
`compressed_or_paged_out_bytes` and `virtual_memory_region_count` are null when
the kernel refused that reading for the process, null rather than 0 for the same
reason as the battery columns.

`application_id` is the group the process was charged to, and null means the
process runs outside an `.app` bundle rather than that the grouping failed. There
is deliberately no `(process_id, sample_id)` index: it would add another 222 000
entries a day to speed up a question a full scan of the window answers in a
couple of seconds.

Only `sample_id` is a declared foreign key. `process_id` and `application_id`
hold ids from the two lookups but carry no `REFERENCES` clause, and the omission
is what makes the missing index affordable: foreign keys are enforced, and SQLite
proves a parent row has no children by scanning the child table once per deleted
parent — with no index on the child column that is a full scan of the largest
table in the schema for every lookup row retention collects. Measured at this
shape (800 000 child rows, 200 orphan parents) it is 5.66 s of CPU against
0.13 s, spent inside the pass's write transaction while the next sample waits.
Nothing is bought back: retention collects orphans with an explicit `NOT IN` over
exactly these columns, so a row the constraint would refuse to orphan is one
`NOT IN` never selects. A join from `process_sample` to `process` is therefore
guaranteed by the writer rather than by the database.

### application, application_sample

```sql
CREATE TABLE application (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  key         TEXT NOT NULL UNIQUE,
  name        TEXT NOT NULL,
  bundle_path TEXT NOT NULL
);

CREATE TABLE application_sample (
  sample_id                             INTEGER NOT NULL REFERENCES sample(id) ON DELETE CASCADE,
  application_id                        INTEGER NOT NULL REFERENCES application(id),
  root_pid                              INTEGER NOT NULL,
  process_count                         INTEGER NOT NULL,

  cpu_percent                           REAL NOT NULL,
  user_cpu_percent                      REAL NOT NULL,
  system_cpu_percent                    REAL NOT NULL,

  physical_footprint_bytes              INTEGER NOT NULL,
  resident_bytes                        INTEGER NOT NULL,
  wired_bytes                           INTEGER NOT NULL,
  lifetime_max_physical_footprint_bytes INTEGER NOT NULL,
  compressed_or_paged_out_bytes         INTEGER NOT NULL,
  compressed_attribution_process_count  INTEGER NOT NULL,

  wakeups_per_second                    REAL NOT NULL,
  page_ins_per_second                   REAL NOT NULL,
  disk_read_bytes_per_second            REAL NOT NULL,
  disk_write_bytes_per_second           REAL NOT NULL,
  logical_write_bytes_per_second        REAL NOT NULL,
  instructions_per_second               REAL NOT NULL,
  cycles_per_second                     REAL NOT NULL,
  energy_watts                          REAL NOT NULL,
  faults_per_second                     REAL NOT NULL,
  copy_on_write_faults_per_second       REAL NOT NULL,
  system_calls_per_second               REAL NOT NULL,
  context_switches_per_second           REAL NOT NULL,
  thread_count                          INTEGER NOT NULL,
  running_thread_count                  INTEGER NOT NULL,
  billed_energy_per_second              REAL NOT NULL,
  battery_impact_score                  REAL NOT NULL
);

CREATE INDEX application_sample_sample_id ON application_sample(sample_id);
```

`application.key` is `ApplicationUsage.id`, which for a stored row is always
`bundle:<hash of the bundle path>`. `name` and `bundle_path` are the rest of the
group's identity; `bundle_path` is `NOT NULL` because a group without one is
never written.

`application_sample` is the aggregate `ApplicationGrouper` produced over the
member processes, not a second copy of them: `root_pid` and `process_count` come
from `ApplicationUsage`, and the rest are its numeric fields under the same names.
`compressed_or_paged_out_bytes` is `NOT NULL` here while its `process_sample`
counterpart is nullable — the grouper sums a refused reading as 0 and records how
many members it could attribute in `compressed_attribution_process_count`, so the
uncertainty is already a count.

Both `application_sample_sample_id` and `process_sample_sample_id` are as much
about the retention cascade as about reading: `DELETE FROM sample` does one child
lookup per removed sample, and without an index each of those scans the whole
window.

### alert, alert_delivery

```sql
CREATE TABLE alert (
  sample_id INTEGER NOT NULL REFERENCES sample(id) ON DELETE CASCADE,
  key       TEXT NOT NULL,
  reported  INTEGER NOT NULL,
  severity  TEXT,
  title     TEXT,
  message   TEXT
);

CREATE TABLE alert_delivery (
  sample_id  INTEGER NOT NULL REFERENCES sample(id) ON DELETE CASCADE,
  channel    TEXT NOT NULL,
  successful INTEGER NOT NULL,
  detail     TEXT NOT NULL
);

CREATE INDEX alert_sample_id ON alert(sample_id);

CREATE INDEX alert_delivery_sample_id ON alert_delivery(sample_id);
```

`reported = 1` is an alert the report carried, with its `Alert.severity`,
`.title` and `.message`. `reported = 0` is a key from
`MonitoringReport.suppressedAlertKeys` — over its threshold, but pushed out of
the report by `maxAlertsPerCategory` — and that list carries nothing but the key,
hence the three nulls. A suppressed alert that was already firing is never pushed
again, so the row is the only trace it leaves anywhere.

`severity` holds the enum name (`INFO`, `WARNING`, `CRITICAL`) rather than its
ordinal, so that inserting a constant into `Severity` cannot re-point rows
written before it.

`alert.key` is the rule's key: `swap`, `swap-out` and `battery-low` for the
global rules; `cpu:`, `memory:`, `disk-write:` and `battery-impact:` prefixed to
the application key for the per-application ones. A key appears at most once per
sample.

`alert_delivery` is what each notification channel did with this sample's push —
`system`, `webhook` or `telegram`, at most once per sample each, failures
included. `detail` is the channel's own account of the outcome. Rows appear only
for the samples that actually pushed: a sample that raised nothing, or that
raised only keys already firing, never reaches the dispatcher and writes none.
With `notifyEverySample` on, every sample carrying an alert writes one row per
enabled channel.

### alert_state, agent_state

```sql
CREATE TABLE alert_state (
  key             TEXT NOT NULL PRIMARY KEY,
  settled         INTEGER NOT NULL,
  failures        INTEGER NOT NULL,
  retry_at_sample INTEGER NOT NULL
);

CREATE TABLE agent_state (
  singleton      INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
  sample_counter INTEGER NOT NULL,
  last_sample_at TEXT NOT NULL
);
```

These two are the agent's own state rather than history. They hang off no sample,
retention never touches them, and they are rewritten whole inside every sample
transaction.

`alert_state` holds the keys that were firing after the last sample, with
`settled` — whether a channel ever confirmed delivery — and the delivery backoff
a failing channel earned. `agent_state.sample_counter` is the absolute sample
number `retry_at_sample` counts against, which is why the two have to be restored
together: keys restored against a counter starting from zero would defer
themselves for the life of the run.

`agent_state.last_sample_at` is the `captured_at` of the sample the state was
written with, and it is what decides whether the state is still worth restoring.
The agent drops it past two sampling intervals and starts from an empty alert
state.

Both tables assume one writer. `agent_state` is a single row pinned to a
singleton key and `alert_state` is emptied and rewritten on every sample, so two
agents against the same home — a `harmon run` by hand while the LaunchAgent is
up — overwrite each other's counter and keys rather than merging them, and a
restart then restores state belonging to the other process. Run a second agent
against a `HOME` of its own.

## Three things the schema does not carry

**The first name a process was seen under.** The lookup insert conflicts into a
no-op against `UNIQUE(pid, started_at)`, so `name`, `uid` and `parent_pid` freeze
at first sighting and are never corrected. Two ordinary events make them wrong
afterwards: an `exec()` replaces the image without changing the pid or its start
time, so the row keeps the name of the program that came before; and a process
whose parent exits is re-parented to launchd, so `parent_pid` names a parent that
is no longer the real one. Correcting either would cost an `UPDATE` per process
per sample — around 222 000 writes a day — to track something that almost never
changes, and the row would then no longer describe the moment it was written
either.

`executable_path` is outside that freeze in one direction. Null there is not a
value the process ever had; it is the collector saying it could not look, and the
answer can arrive later — when a collector gains the privilege to read another
user's process, or when the bridge that once reported nothing for a replaced
binary learns to read the saved exec path. So the conflict clause is
`DO UPDATE SET executable_path = excluded.executable_path` guarded by
`process.executable_path IS NULL AND excluded.executable_path IS NOT NULL`: it
writes only for rows that carry no path, and never replaces one. Without it the
first sighting of a long-running session decides its path for the life of that
session — days of samples that no query grouping by path can attribute — and a
fix in the collector would reach only processes started after it. The cost the
freeze exists to avoid does not apply: the guard is false for every row that
already has a path, which is all but a handful of them.

**Application groups without a bundle.** `ApplicationGrouper` gives every process
it cannot tie to an `.app` bundle a singleton group of its own, keyed
`process:<pid>:<startedAt>`. Such a group is one process, and its aggregate is
that process's own `process_sample` row copied line for line — several hundred a
sample on an ordinary machine, and a lookup table growing at the speed of process
churn. Neither the group nor its lookup row is written, which is what
`process_sample.application_id IS NULL` means. Nothing is lost: the numbers are in
the process row. Two consequences when querying: a `count(*)` over
`application_sample` is a count of bundled applications only, and an alert key of
the form `cpu:process:200:666` has no row in `application` to join to.

**The per-process access failures.** `SystemUsage.processIssues` is not stored row
by row. The four counters in `sample` are the historical record of it; the list of
which process was refused for which reason is a `harmon diagnose` concern.

## Changing the schema

Nothing here is a migration yet. The schema has exactly one version, sqliter
maintains `user_version` from it, and the generated `Schema.migrate()` is a body
that returns `QueryResult.Unit`.

**A `.sqm` file never runs.** `plugins/sqldelight-gen` drives the SQLDelight
compiler with `deriveSchemaFromMigrations = false` and `verifyMigrations = false`,
so `.sqm` files contribute nothing to generation and the empty `migrate()` is not
an oversight — it is what those two flags produce. Adding a migration file and
expecting the driver to apply it is the one mistake this section exists to
prevent.

Schema evolution therefore belongs in `HistoryStore` itself, in the two forms
SQLite allows without one:

- a new table as `CREATE TABLE IF NOT EXISTS` when the store opens;
- a new column as an `ALTER TABLE … ADD COLUMN` guarded by a read of
  `PRAGMA table_info(<table>)`, **not** by `runCatching`. sqliter prints the full
  stack trace of a failing statement before it throws, so a swallowed exception is
  still a wall of red in the launchd log on every agent start.

None of this is implemented. The first change to the schema is where it gets
written.

## Checking a change against a live machine

The storage layer is covered by unit tests against real SQLite, but three of the
things that decide whether history works cannot be reached from a test: the
pragmas the driver applies to a file it creates itself, the alert state a second
process finds when the first one dies, and a retention pass with enough data in
front of it to have something to reclaim. What follows is the run that covers
them, and the exact form it was last run in.

Everything below writes to a scratch `HOME`. The database path is derived from
`$HOME`, so relocating it keeps the check away from
`~/Library/Application Support/Harmon/history.db` — the real one, which a check
must never write into. Relocating `HOME` also moves the default config path,
which is why `--config` is always passed explicitly.

### Collector and agent

Build, then start an unprivileged collector on a development socket, as in
`CLAUDE.md`:

```shell
./kotlin build
build/tasks/_harmon_linkMacosArm64Debug/harmon.kexe collector \
  --allow-unprivileged --socket /tmp/harmon-dev.sock \
  --allowed-uid "$(id -u)" --allowed-gid "$(id -g)"
```

The agent needs a short interval so that samples accumulate in minutes rather
than hours, and no system notifications, so that the run does not fire real
banners:

```shell
mkdir -p /tmp/harmon-live-check
cat > /tmp/harmon-live-check/harmon.conf <<'EOF'
collectorSocket=/tmp/harmon-dev.sock
intervalSeconds=10
historyRetentionDays=7
systemNotifications=false
webhookUrl=http://127.0.0.1:8899/hook
EOF

HOME=/tmp/harmon-live-check \
  build/tasks/_harmon_linkMacosArm64Debug/harmon.kexe run \
  --config /tmp/harmon-live-check/harmon.conf
```

The webhook is optional but worth the trouble: with every channel off, nothing
is ever pushed, and "the alert was not pushed twice" becomes unobservable. Any
listener on `127.0.0.1` will do — HTTP is allowed for that host alone — and each
request it logs is one push the agent decided to make. Alerts on a loaded
machine tend to fire under the shipped thresholds without help; if none do,
lower `applicationMemoryAlertMiB` or `swapAlertMiB` until something on the
machine crosses.

Both processes run until killed. Kill them at the end of the check, including
the paths where it fails.

### What one sample should look like

```shell
sqlite3 "/tmp/harmon-live-check/Library/Application Support/Harmon/history.db"
```

```sql
PRAGMA journal_mode;   -- wal
PRAGMA auto_vacuum;    -- 2 (INCREMENTAL)
```

`PRAGMA foreign_keys` is not worth reading here: it is per connection, and the
answer describes the `sqlite3` session rather than the agent's. What the agent's
connection does with foreign keys shows in the retention pass below, where the
cascade either happens or does not.

Every readable process becomes one `process_sample` row, and the sample records
how many processes there were and how many of them it could not read:

```sql
SELECT s.id,
       s.total_process_count - s.inaccessible_process_count AS readable,
       (SELECT count(*) FROM process_sample p WHERE p.sample_id = s.id) AS rows
FROM sample s ORDER BY s.id;
```

`readable` and `rows` have to agree on every sample.

Only application groups with a bundle are stored, so the lookup holds no
`process:<pid>:<startedAt>` key and no null bundle path, and every process in a
bundle-less group carries `application_id IS NULL`:

```sql
SELECT count(*) AS applications,
       sum(key LIKE 'process:%') AS singletons,
       sum(bundle_path IS NULL) AS without_bundle
FROM application;

SELECT (SELECT count(*) FROM application_sample WHERE sample_id = :s)        AS app_rows,
       (SELECT count(DISTINCT application_id) FROM process_sample
          WHERE sample_id = :s)                                              AS distinct_ids,
       (SELECT coalesce(sum(process_count), 0) FROM application_sample
          WHERE sample_id = :s)                                              AS members,
       (SELECT count(*) FROM process_sample
          WHERE sample_id = :s AND application_id IS NOT NULL)               AS attributed;
```

`singletons` and `without_bundle` are zero, `app_rows` equals `distinct_ids`,
and `members` equals `attributed` — the applications named by the processes are
exactly the ones stored, and they account for exactly the processes charged to
them. The rest of the processes, several hundred a sample, have no application
at all, which is the point of leaving singleton groups out.

The lookups must not grow with the samples:

```sql
SELECT (SELECT count(*) FROM process)                                        AS process_rows,
       (SELECT count(*) FROM (SELECT DISTINCT pid, started_at FROM process)) AS distinct_processes,
       (SELECT count(*) FROM application)                                    AS application_rows,
       (SELECT count(DISTINCT key) FROM application)                         AS distinct_keys;
```

### Restarting with an alert firing

The alert state survives a restart, so an alert that never stopped firing is not
pushed a second time. The window is two sampling intervals, which is why the
restart has to follow a sample closely — at `intervalSeconds=10` there are
twenty seconds to work with. Note the last `captured_at`, the `sample_counter`
in `agent_state`, and how many requests the webhook has logged; then kill the
agent and start it again with the same command.

After the restart:

- the webhook log has not grown, and the new agent's output holds no
  `notification` line;
- `agent_state.sample_counter` continues from where it stopped rather than
  restarting at 1;
- `alert_state` still holds the keys that were firing.

The control is the same restart with the snapshot allowed to go stale — stop the
agent, wait past two intervals, start it again. Then the counter does restart at
1 and the alert is pushed again. Without that half, a check that observes
"nothing was pushed" cannot tell restoration from an agent that pushes nothing.

### Retention

The retention pass runs on the first sample of a run and roughly hourly after
that, so a restart is the way to trigger one on demand. Give the pass something
to delete by back-dating samples past the window — with the agent stopped, since
it holds the file open:

```sql
UPDATE sample
SET captured_at = strftime('%Y-%m-%dT%H:%M:%SZ',
                           datetime('now', '-30 days', '+' || id || ' seconds'))
WHERE id < (SELECT max(id) FROM sample);
```

Record `stat -f %z` on the database file first, and take it **after**
`PRAGMA wal_checkpoint(TRUNCATE)`, both times. Deleting rows in WAL mode returns
pages inside the file and shrinks nothing on disk until a checkpoint, so a size
read without one says nothing either way.

Start the agent, wait for one sample, stop it, checkpoint again. Then:

- the back-dated samples are gone and so is everything hanging off them —
  `process_sample`, `application_sample`, `alert`, `alert_delivery` — which is
  the cascade, and therefore the proof that the agent's connection had foreign
  keys on;
- `process` and `application` hold no row that no sample points at any more,
  while the rows the surviving sample still names are untouched;
- `alert_state` and `agent_state` are untouched: they are the agent's own state,
  not history;
- the file is smaller than it was.

To reach the orphan branch of the `application` lookup, back-date every sample
rather than all but the newest: an application present in the surviving sample is
not an orphan, and on a machine whose applications stay open for the length of
the check that is all of them.

### The last run

On 2026-07-29, at a 10-second interval on a machine running ~730 processes, of
which ~500 were readable:

| Check | Observed |
|---|---|
| `journal_mode` / `auto_vacuum` / directory mode | `wal` / `2` / `drwx------` |
| `process_sample` rows vs readable processes | equal on all 15 samples (498…519) |
| application lookup | 73 rows, 73 `bundle:` keys, 0 singletons, 0 null bundle paths |
| applications per sample | 72–73 rows = the same number of distinct ids; 139–140 members = 139–140 attributed processes; 359–380 processes with no application at all |
| lookups across samples | 554 `process` rows for 554 distinct `(pid, started_at)`, against 7 587 `process_sample` rows; 73 `application` rows for 73 keys; 481 processes shared one lookup row across all 15 samples |
| restart inside the window | counter 16 → 19 without a reset, 4 `alert_state` keys restored, nothing pushed |
| restart after two intervals | counter reset to 1, the same alert pushed again |
| retention | 20 samples → 1; `process_sample` 10 164 → 527; `application_sample` 1 450 → 73; `alert` 100 → 5; `alert_delivery` 2 → 0; all 596 stale `process` rows and all 73 stale `application` rows collected; no orphan and no dangling join left; `alert_state` and `agent_state` intact; `integrity_check` ok, no foreign key violations |
| file size | 1 433 600 → 241 664 bytes (350 → 59 pages) |

The last row is the one the check was worth running for; it read 1 474 560 →
1 470 464 the first time, one page out of 279 free ones. `PRAGMA
incremental_vacuum` yields one row per page it returns, and that one fact breaks
both obvious ways of issuing it: `driver.execute` throws on the first
`SQLITE_ROW` — after the `DELETE` has committed, so the pass took the sample
with it and left the space where it was — and a bare `driver.executeQuery` lands
on the driver's pool of read-only connections and fails with `SQLITE_READONLY`.
It has to be an `executeQuery` inside a transaction, where the driver serves
everything from the writing connection.

None of that was visible to the unit tests, for a reason worth keeping in mind
when writing more of them: a sample of two processes frees no page when it is
deleted, so the statement finished on its first step and never returned a row.
Both failures need a database with something to reclaim.
