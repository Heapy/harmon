# Sample history

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
