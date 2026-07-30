# Junie CLI 2548.3

**Verdict: shame on both axes.** A JVM application that ships without a single
heap flag, so its resident size is a function of the host's RAM rather than of
the work it does — and which writes 3.7 GB a day from two processes while
keeping five copies of itself on disk.

Measured 2026-07-30 on macOS 25.5.0, Apple Silicon, 36 GB RAM. Memory against
two live `junie` processes that had been running for about 1h35m each; disk
across 18.2 hours of Harmon history (216 samples) covering four Junie
processes.

## What it is

| | |
|---|---|
| Main class | `com.intellij.ml.llm.matterhorn.ej.app.cli.standalone.MainKt` — the `Kt` suffix means **Kotlin** |
| Runtime | JetBrains Runtime **21.0.11** (`JBR-21.0.11+10-1163.116-nomod`, aarch64), bundled in `.app/Contents/runtime` |
| Packaging | `jpackage` 21.0.8, a single fat jar of **190 MB** |
| Jar contents | **74 460 classes**, 81 567 entries, 446 MB uncompressed |

The jar is effectively a slice of the IntelliJ Platform plus everything else,
by class count:

```
11727  com/intellij/ml        3506  org/apache/poi      2916  org/apache/commons
 2486  com/slack/api          2223  org/openxmlformats  2017  com/google/common
 1642  org/eclipse/jgit       1447  io/lettuce/core     1409  com/sun/jna
  965  ai/koog/prompt          921  ai/koog/agents       822  com/agentclientprotocol
```

So one binary carries Apache POI + XMLBeans + PDFBox (office document
parsing), the Slack API, JGit, Netty, Lettuce (a Redis client), Grizzly,
Jackson, Guava, fastutil, JNA, Koog (JetBrains' agent framework), Grazie, and
a Copilot integration.

## The charge

`jcmd <pid> VM.command_line` on a live process returns:

```
jvm_args: -Djpackage.app-version=2548.3 -Djpackage.app-path=...
```

That is the whole list. No `-Xmx`, no `-Xms`, no `-XX:MaxMetaspaceSize`; the
`[JavaOptions]` section of `Contents/app/junie.cfg` holds exactly one line,
and it carries the version string. The JVM therefore falls back to defaults
derived from the 36 GB of physical memory on the host:

- `InitialHeapSize` = **576 MB** (1/64 of RAM), committed at startup whether
  or not the process does anything;
- `MaxHeapSize` = **9 GB** (1/4 of RAM), the ceiling G1 may grow into freely,
  with `SoftMaxHeapSize` set to the same value;
- `ReservedCodeCacheSize` = 240 MB, Metaspace reserved = 1.15 GB, of which
  compressed class space accounts for 1 GB.

The baseline cost of the process scales with the machine it lands on. The same
build on a 64 GB host would start with a 1 GB heap and a 16 GB ceiling.

## Numbers

| PID | Uptime | Heap committed | Heap used | Metaspace used | RSS | Footprint | Java threads |
|---|---|---|---|---|---|---|---|
| 53189 | 1h35m | 272 MB | 150 MB | 84 MB | 290 MB | 539 MB | 86 |
| 52553 (`--resume`) | 1h38m | **1.4 GB** | 485 MB | 82 MB | 462 MB | — | 83 |

17 860 classes loaded, which is what the 84 MB of Metaspace pays for. The
footprint breakdown shows 241 stack regions (Java threads plus native GC and
JIT threads) for 7 MB of stacks, and 27 MB of mapped file — the fat jar.

Contributions, largest first:

1. **No `-Xmx`.** G1 grew the resumed session to 1.4 GB committed against
   485 MB live and gave almost none of it back. This is not a leak; it is a
   collector that was never told where to stop.
2. **The default `-Xms` of 576 MB**, charged before any work happens.
3. **A monolithic fat jar carrying half the IntelliJ Platform** — 17.8k loaded
   classes in Metaspace, plus the mapping of a 190 MB archive.
4. **~85 Java threads**, worth 7 MB of stacks. Noise next to the rest.
5. **Two concurrent instances share nothing.** Each carries its own heap,
   Metaspace, and code cache.

## Mitigation, verified

An environment variable fixes it. `~/.local/bin/junie` is a shell shim that
`exec`s the bundle binary, so the environment survives into the JVM:

```bash
JAVA_TOOL_OPTIONS="-Xmx2g -Xms256m -XX:MaxMetaspaceSize=256m" junie ...
```

The JVM acknowledges it on stderr — `Picked up JAVA_TOOL_OPTIONS: ...` — and
`VM.command_line` on the resulting process shows the flags injected ahead of
the bundle's own options, where nothing later overrides them:

```
jvm_args: -Xmx1g -Xms256m -XX:MaxMetaspaceSize=256m -Djpackage.app-version=2548.3 ...
```

Measured on a fresh interactive process started with `-Xmx1g -Xms256m
-XX:MaxMetaspaceSize=256m`, against the uncapped processes above:

| Flag | Default (uncapped) | With `JAVA_TOOL_OPTIONS` |
|---|---|---|
| `MaxHeapSize` | 9 GB | **1 GB** |
| `SoftMaxHeapSize` | 9 GB | 1 GB |
| `InitialHeapSize` | 576 MB | 256 MB |
| `MaxMetaspaceSize` | unset | 256 MB |
| Metaspace reserved | 1.15 GB | 272 MB |
| class space reserved | 1 GB | 208 MB |
| `G1HeapRegionSize` | 8 MB | 1 MB (auto-derived from the smaller heap) |

The capped process came up normally into its interactive UI and sat at 256 MB
heap total against 53 MB used, 56 MB of committed Metaspace, and a 399 MB
footprint.

Two limits on the fix, both measured:

- **It bounds growth, not the floor.** 399 MB of footprint with 53 MB of live
  heap is the fixed cost of the runtime: Metaspace for 17.8k classes, the
  240 MB code cache reservation, the mapped 190 MB jar, and ~85 threads. No
  heap flag touches any of that.
- **1 GB is too tight for a real session.** Over the ~25 minutes this test
  took, the uncapped resumed session's live set grew from 485 MB to 576 MB
  and its RSS from 462 MB to 966 MB. A ceiling of 1 GB would have put that
  session into continuous full GC. `-Xmx2g` is the number to actually use;
  1 GB was chosen here to make the flag's effect unambiguous.

`Picked up JAVA_TOOL_OPTIONS` goes to stderr, so it does not contaminate
`--output-format=json` on stdout. It cannot be suppressed.

Editing `Contents/app/junie.cfg` to add `java-options=-Xmx2g` would work too,
but the bundle is signed (`_CodeSignature` and `CodeResources` are present),
so the edit breaks the signature, and the install path is versioned
(`~/.local/share/junie/versions/2548.3/`), so it is lost on the next update.

## Disk: the second charge

Over the 18.2 hours Harmon retains, this machine wrote 147.9 GB. Four Junie
processes wrote **2.82 GB of it — 1.9%, or 3.7 GB/day**, with logical writes
of 3.24 GB against 2.82 GB physical. That ratio is the important one: unlike
[Codex](codex.md), Junie is not amplifying a small stream through a database.
It is genuinely emitting that many bytes.

Only two of those processes were running at a time, so the per-session rate is
roughly **1.85 GB/day each**, peaking at 1.16 MB/s. What it writes:

```
3.7 MB  ~/.junie/logs/log-2026-07-30_170619-pid-53189.log
3.3 MB  ~/.junie/logs/log-2026-07-30_170338-pid-52553.log
15.7 MB ~/.junie/sessions/session-260730-015553-1j1h/events.jsonl
```

A per-PID plain-text log and an append-only event stream. Nothing exotic, no
rewrite churn — just a session emitting almost two gigabytes a day into files
that end up a few megabytes long, which means the overwhelming majority of it
is rotated away or written and never read.

`~/.junie` holds 618 MB, 537 MB of it sessions and 76 MB logs.

### Five copies of itself

The far less defensible number is the install tree. `~/.local/share/junie` is
**1.7 GB**, and all of it is old versions nothing will ever run again:

| | |
|---|---|
| `versions/2383.3` | 371 MB |
| `versions/2383.2` | 371 MB |
| `versions/2548.3` | 351 MB ← the only one in use |
| `versions/2144.5` | 330 MB |
| `versions/1755.50` | 329 MB |

Each is a full `.app` with its own bundled 190 MB jar and its own copy of the
JetBrains Runtime. The updater adds a version and removes nothing. Claude Code
uses the same versioned-directory scheme and retains four builds for 972 MB,
which is the same mistake at half the price; Codex keeps one.

## For scale

Two other agent CLIs run on this machine, measured the same day by the same
method. Startup floor, each process freshly launched and left idle at its
prompt:

| Tool | Fresh footprint | What dominates it |
|---|---|---|
| [Codex CLI](codex.md) 0.146.0 (Rust) | **66 MB** | `MALLOC_SMALL` 35 MB, `MALLOC_LARGE` 19 MB |
| [Claude Code](claude-code.md) 2.1.220 (Bun) | **196 MB** | `IOAccelerator` 172 MB; the JS side is 11 MB |
| Junie 2548.3 (JVM), with `-Xmx1g` | **399 MB** | heap 256 MB, Metaspace 56 MB, code cache reservation |

And in steady state, across every live process of each in one snapshot:

| Tool | n | Median RSS | Total RSS |
|---|---|---|---|
| Codex CLI | 19 | **34 MB** | 782 MB |
| Claude Code | 29 | 160 MB | 5459 MB |
| Junie | 2 | 397 MB | 794 MB |

Nineteen concurrent Codex sessions cost less than two Junie processes. The
comparison is not about the language — all three ship a ~200–260 MB binary
and two of the three are garbage-collected. It is about whether the runtime
was given a size, and Codex is the case where the question never arises
because there is no managed heap to size.

On disk the ranking inverts, which is why this directory records verdicts per
axis. Over the same 18.2 hours, against 147.9 GB written machine-wide:

| Tool | Procs | GB written | GB/day | Share | Retained on disk |
|---|---|---|---|---|---|
| [Codex CLI](codex.md) | 42 | 21.11 | 27.8 | **14.3%** | 3.2 GB data, 1 version |
| **Junie** | 4 | 2.82 | 3.7 | 1.9% | 618 MB data, **1.7 GB in 5 versions** |
| [Claude Code](claude-code.md) | 32 | 0.06 | 0.1 | 0.04% | 1.0 GB data, 972 MB in 4 versions |

Junie is a fifth of Codex's write volume and thirty times Claude Code's. It is
the only one of the three that is on the wrong side of both tables.

## How this was measured

```bash
ps aux | grep -i junie
jcmd <pid> VM.command_line
jcmd <pid> VM.flags
jcmd <pid> GC.heap_info
jcmd <pid> Thread.print | grep -c '^"'
/usr/bin/footprint -p <pid>
unzip -l .../junie-eap-2548.3.jar
```

The bundled JBR's own `bin/java` exits with 133 (SIGTRAP) when invoked
directly, so the `jcmd` above is an unrelated JDK 25 from sdkman attaching to
the JDK 21 target. `jps` does not list either process; attaching by explicit
PID does work.

The mitigation was checked on a throwaway process rather than on a live
session:

```bash
JAVA_TOOL_OPTIONS="-Xmx1g -Xms256m -XX:MaxMetaspaceSize=256m" \
  script -q /dev/null junie -p <scratch dir>
```

`script` supplies the pty that interactive mode needs when it is started from
a non-terminal parent. The process was left idle at its prompt, inspected with
the same `jcmd` calls, and killed; no task was submitted, so these figures
describe startup cost only and not behaviour under load.
