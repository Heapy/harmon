# Codex CLI 0.146.0

**Verdict: honour on memory, shame on disk.** A 34 MB median process with no
managed heap to mistune — that writes 28 GB a day, 14% of everything this
machine commits to disk, into two SQLite databases that do not grow.

Measured 2026-07-30 on macOS 25.5.0, Apple Silicon, 36 GB RAM. Memory across
19 live CLI processes plus one throwaway started for the startup floor; disk
across 18.2 hours of Harmon history (216 samples, 2026-07-29T22:17Z to
2026-07-30T16:21Z) covering 42 Codex processes.

## What it is

| | |
|---|---|
| Binary | single Mach-O arm64 executable, 258 MB, at `@openai/codex-darwin-arm64/vendor/aarch64-apple-darwin/bin/codex` |
| Language | **Rust** — `strings` finds `/rustc/59807616e1fa2540724bfbac14d7976d7e4a3860` and no interpreter or JIT markers |
| Distribution | npm, `@openai/codex` with a per-platform vendored binary |
| Local state | `~/.codex`, 3.2 GB, including two SQLite databases held open in WAL mode by every running session |

## Memory: the case for honour

There is no managed heap, so there is no ceiling to get wrong. `footprint`
shows the entire working set living in the system allocator:

```
=== fresh process, 26 s old, footprint 66 MB ===
  35 MB   MALLOC_SMALL
  19 MB   MALLOC_LARGE
4928 KB   __DATA_CONST
3616 KB   stack (53 regions)
```

No `JS JIT generated code`, no `JS VM Gigacage`, no `garbage-first heap`, no
Metaspace, no reserved code cache. Nothing is committed in advance against a
limit derived from the machine's 36 GB, which is the whole of the charge
against Junie. Growth is bounded by work rather than by time — 66 MB fresh,
134 MB at 12 minutes, 177 MB at 17 hours — and across the 19 live processes
the total was 782 MB, min 24 MB, **median 34 MB**, max 170 MB. Nineteen
concurrent sessions cost less than two Junie processes.

## Disk: the case for shame

Over the 18.2 hours Harmon retains, this machine wrote **147.9 GB**. Codex
processes wrote **21.1 GB of it — 14.3%**, second only to Firefox:

| Tool | Procs | GB written | GB/day | Share of system writes |
|---|---|---|---|---|
| **Codex CLI** | **42** | **21.11** | **27.8** | **14.3%** |
| [Junie](junie.md) CLI | 4 | 2.82 | 3.7 | 1.9% |
| Codex.app helpers | 67 | 0.11 | 0.1 | 0.07% |
| [Claude Code](claude-code.md) CLI | 32 | 0.06 | 0.1 | 0.04% |

Logical writes were 23.3 GB against 21.1 GB physical, so this is not the
device inflating a modest stream: the process really does issue that many
bytes through `write()`.

It buys nothing. Over a 181-second window in which the machine wrote 2.7 GB,
`du -sk ~/.codex` did not move by a single kilobyte:

```
T0  codexdir=3346588   rows=197018   seq=264714662
T1  codexdir=3346588   rows=197021   seq=264717647     (181 s later)
```

Three net new rows. **2985 inserts.** The AUTOINCREMENT counter stands at
264.7 million against 197 021 live rows, which is the rotation churn
[the kortex write-up][doc] identified: rows inserted and recycled almost
immediately, so the database rewrites its pages forever at a constant size
and every standard disk utility reports nothing is happening.

Four long-lived sessions did most of it — roughly 4 GB each — while the
remaining 38 processes shared the rest.

[doc]: https://github.com/Heapy/kortex/blob/main/docs/codex-sqlite-trace-disk-writes.md

### The documented fix is installed here, and this is the state after it

`~/.codex/logs_2.sqlite` carries the `codex_block_trace_logs` trigger from the
write-up, and it works: the TRACE count sat at exactly 87 463 across the whole
181-second window while 2985 rows were inserted, so nothing new is getting
through. The insert rate is down to **16.5/sec from the documented 122/sec**,
and the freelist is 660 pages of 77 742 rather than the 57% the write-up
found.

That fix removed 87% of the insert rate and Codex still writes 27.8 GB/day.
The TRACE flood was the largest symptom, not the disease. What remains is
`logs_2.sqlite` (304 MB) and `state_5.sqlite` (79 MB), both in WAL mode, both
held open by every concurrent session, both rewritten continuously — plus the
session rollout JSONL that is the only file in the set whose growth is
legitimate.

The write-up's own caveat applies to this machine: the trigger lives in the
database and will be silently dropped by the next schema migration, at which
point 122 inserts/sec resumes with nothing to announce it.

### And it keeps everything

`~/.codex` is 3.2 GB:

| | |
|---|---|
| `sessions/` | 1.8 GB |
| `archived_sessions/` | 366 MB |
| `plugins/` | 364 MB |
| `logs_2.sqlite` | 311 MB |
| `state_5.sqlite` | 80 MB |
| `computer-use/` | 61 MB |

Archiving a session moves it to a second directory on the same disk. Nothing
in this tree expires.

## The packaging blemish

The npm entry point is a Node shim that exists only to `exec` the vendored
native binary, and it stays resident for the life of the session:

```
23.8 MB  node /opt/homebrew/opt/nvm/versions/node/v24.18.0/bin/codex ...
```

Twenty were resident during the measurement, 186 MB in total. A native binary
whose launcher drags a JavaScript runtime along costs more than the median
Codex session it launches.

## How this was measured

```bash
ps -axo pid,rss,etime,command       # memory snapshot, classified by command line
/usr/bin/footprint -p <pid>
script -q /dev/null codex           # throwaway process for the startup floor
sqlite3 -readonly ~/.codex/logs_2.sqlite \
  "SELECT seq FROM sqlite_sequence; PRAGMA freelist_count; PRAGMA page_count;"
du -sk ~/.codex                     # sampled twice, 181 s apart
```

Disk figures come from Harmon's own `history.db`, time-weighted per process:

```sql
SELECT sum(ps.disk_write_bytes_per_second * s.elapsed_seconds)
FROM process_sample ps JOIN sample s ON s.id = ps.sample_id
JOIN process p ON p.id = ps.process_id WHERE lower(p.name) = 'codex';
```

Grouping by `executable_path` instead of by name attributes only 4.65 GB to
Codex. The other 16.3 GB belongs to four proceswses whose `executable_path`
Harmon recorded as null; `ps` shows them to be Codex CLI sessions launched
from an older nvm prefix (`nvm/0.40.5/.../node/v24.16.0`) whose binary has
since been replaced. They are Codex, and the name-based total is the correct
one. **This is a Harmon bug worth fixing** — a null executable path silently
moved 77% of the machine's second-largest writer out of its own report.

Compare [junie.md](junie.md), which fails on memory and writes a fifth as
much, and [claude-code.md](claude-code.md), which is the only entry so far
that passes on both axes.
