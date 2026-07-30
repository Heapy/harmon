# Claude Code 2.1.220

**Verdict: honour on both axes — narrowly on memory, cleanly on disk.** A
JavaScript agent whose live working set after three days is about 44 MB and
which writes 0.04% of this machine's disk traffic across 29 concurrent
sessions. The memory honour is narrow because 85% of what the process actually
costs is a GPU allocator region this note cannot explain.

Measured 2026-07-30 on macOS 25.5.0, Apple Silicon, 36 GB RAM. Memory across
29 live sessions with uptimes from 33 minutes to 5 days, plus one throwaway
process started for the startup floor; disk across 18.2 hours of Harmon
history (216 samples) covering 32 Claude Code processes.

## What it is

| | |
|---|---|
| Binary | single Mach-O arm64 executable, 245 MB, at `~/.local/share/claude/versions/2.1.220`, with `~/.local/bin/claude` a symlink to it |
| Runtime | **Bun 1.4.0** compiled to a single-file executable — a `__BUN` segment in the Mach-O and a `bun/1.4.0` string in the binary; JavaScriptCore, not V8 |
| Distribution | versioned directory plus a symlink, so a rollback is a symlink swap |

## The case

The interesting number is what the JavaScript side costs on a session that has
been running for three days:

```
=== pid 58775, uptime 3d00h, footprint 366 MB ===
 316 MB   IOAccelerator
  27 MB   JS JIT generated code
  10 MB   MALLOC_SMALL
7008 KB   JS VM Gigacage
1728 KB   stack (66 regions)
  22 MB   __TEXT (clean)
```

27 + 10 + 7 = **44 MB of actual JavaScript memory after three days**. There is
no `-Xmx` equivalent to forget here, and nothing is sized from the host's
36 GB: a fresh process holds 3.8 MB of JIT code and 6.8 MB of Gigacage.

Session age does not predict size, which is the property Junie lacks:

| Uptime | Footprint | IOAccelerator | JS JIT |
|---|---|---|---|
| 30 s | 196 MB | 172 MB | 3.8 MB |
| 33 min | 357 MB | 312 MB | 22 MB |
| 2 h 53 m | 283 MB | 243 MB | 18 MB |
| 25 h | 215 MB | 189 MB | 6.4 MB |
| 3 d | 366 MB | 316 MB | 27 MB |
| 5 d 09 h | 308 MB | 262 MB | 19 MB |

The 25-hour process is smaller than the 33-minute one. Size tracks what the
session is doing, not how long it has been alive, and it comes back down —
the thing G1 declined to do for Junie.

## Numbers

Across the 29 live sessions in one `ps` snapshot:

| | RSS |
|---|---|
| total | **5459 MB** |
| min | 111 MB |
| **median** | **160 MB** |
| max | 526 MB |

That total is the largest single consumer on this machine, roughly seven times
the two Junie processes combined. It is also 29 sessions rather than 2, run
deliberately under kotgent; per session it is 160 MB against Junie's 400 MB
idle floor. The aggregate is a usage pattern, the per-session figure is the
tool's.

## Disk: the cleanest result in this directory

Over the 18.2 hours Harmon retains, this machine wrote 147.9 GB. Thirty-two
Claude Code processes wrote **0.06 GB of it — 0.04%**, or roughly 0.1 GB/day.
[Codex](codex.md) wrote 350 times as much from a comparable number of
processes.

Nothing is amplified because nothing is a database. Writes go to append-only
JSONL transcripts, one per session:

```
T0  ~/.claude/projects = 941920 KB
T1  ~/.claude/projects = 941984 KB     (181 s later, +64 KB)
```

64 KB in three minutes across 29 running sessions, about **30 MB/day of
durable transcript**, against ~100 MB/day of physical writes. The gap is
journal and metadata overhead on an APFS append, not rewrite churn — the
figure to compare it against is Codex writing 27.8 GB/day into a directory
whose size did not change by one kilobyte over the same window.

`~/.claude` holds 1.0 GB: 920 MB of transcripts across 309 files, 40 MB of
plugins, 35 MB of file history. All of it is data a session produced and can
be read back.

### The one blemish: four copies of the binary

`~/.local/share/claude/versions` retains **972 MB** in four builds:

| | |
|---|---|
| `2.1.220` | 245 MB ← the symlink target |
| `2.1.219` | 245 MB |
| `2.1.218` | 243 MB |
| `2.1.217` | 239 MB |

The scheme itself is good — `~/.local/bin/claude` is a symlink, so a rollback
is a symlink swap. But nothing prunes, and three of the four builds exist only
to be old. [Junie](junie.md) makes the identical mistake at 1.7 GB across five
versions; Codex keeps one.

## Open question: 172–316 MB of IOAccelerator

Every process measured carries a large `IOAccelerator` region — the GPU
allocator — and in every one of them it is **85% to 88% of the footprint**. It
is present 30 seconds after startup at 172 MB, so it is not accumulated: the
process maps it at launch. This is a terminal UI drawing text.

This note does not call that waste, because it does not know what the region
is for. Image decoding through CoreGraphics would explain a mapping appearing
on demand; it does not obviously explain 172 MB before the session has done
anything. On Apple Silicon this is unified memory, so it is real RAM, but the
process's RSS (297 MB) sits below its footprint (366 MB), which suggests not
all of it is resident against the task.

What can be said without guessing: **strip the GPU region and Claude Code is a
40–50 MB process**, and that is where the honour in this note is earned. The
other 300 MB is the largest unexplained number in this directory.

## How this was measured

```bash
ps -axo pid,rss,etime,command       # snapshot, classified by command line
/usr/bin/footprint -p <pid>
file ~/.local/share/claude/versions/2.1.220
strings -a <binary> | grep -oiE 'bun/[0-9.]+'
script -q /dev/null claude          # throwaway process for the startup floor
du -sk ~/.claude/projects           # sampled twice, 181 s apart
```

Disk figures come from Harmon's own `history.db`, time-weighted per process
and grouped by `executable_path LIKE '%/share/claude/versions/%'`, since the
process name is the version string rather than `claude`.

`script` supplies the pty the TUI needs when started from a non-terminal
parent. The throwaway process was left idle at its prompt, measured, and
killed; no prompt was submitted to it.

One caveat on provenance: these measurements were taken by Claude Code, and
the 526 MB maximum in the table above is the session that took them.

Compare [codex.md](codex.md), which reaches a smaller footprint without the
open question, and [junie.md](junie.md), which fails on the axis both of these
pass.
