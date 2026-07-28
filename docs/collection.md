# Collection and metric model

This document defines what Harmon collects, how raw counters become interval
rates, what root access changes, and where the macOS APIs cannot support an
exact conclusion.

## Snapshot lifecycle

The root collector is request-driven. The user agent connects once for a
baseline, sleeps for `intervalSeconds`, and connects again for the current
snapshot.

```mermaid
flowchart LR
    A[Agent requests baseline] --> B[Collector scans macOS]
    B --> C[JSON snapshot]
    C --> D[Agent sleeps]
    D --> E[Agent requests current snapshot]
    E --> F[Collector scans macOS]
    F --> G[JSON snapshot]
    G --> H[Match PID + start time]
    H --> I[Calculate deltas and rates]
    I --> J[Group application processes]
    J --> K[Rules, logs, notifications]
```

Snapshots are not an atomic kernel transaction. Processes are inspected one at
a time, followed by global counters. A large process table therefore introduces
some scan skew. Harmon records a monotonic timestamp and divides by the actual
time between completed snapshots rather than assuming the configured interval
was exact.

## macOS data sources

The Kotlin collector calls a small C bridge in
`cinterop/harmon_native.def`.

| Scope | Public source | Values |
|---|---|---|
| Process enumeration | `proc_listallpids` | Candidate PIDs |
| Process identity | `proc_pidinfo(PROC_PIDTBSDINFO)` | Parent PID and UID |
| Process display metadata | `proc_name`, `proc_pidpath` | Name and executable path |
| Process resource ledger | `proc_pid_rusage(RUSAGE_INFO_V6)` | CPU, memory, wakeups, page-ins, physical disk I/O, logical writes, instructions, cycles, energy, start time |
| Older-kernel fallback | `proc_pid_rusage(RUSAGE_INFO_V4)` | Same prefix without V6 energy fields |
| Process task counters | `proc_pidinfo(PROC_PIDTASKINFO)` | Faults, COW faults, Mach/Unix calls, context switches, threads |
| Process compressor proxy | `proc_pidinfo(PROC_PIDREGIONINFO)` | Per-region `pri_pages_swapped_out` |
| System CPU | `host_statistics(HOST_CPU_LOAD_INFO)` | User, system, idle, and nice ticks |
| Load average | `getloadavg` | 1/5/15-minute run-queue load |
| Virtual memory | `host_statistics64(HOST_VM_INFO64)` | Memory queues, compressor, faults, paging, compression, and swap counters |
| Allocated swap | `sysctlbyname("vm.swapusage")` | Allocated, used, available, and encryption flag |
| Installed RAM | `sysctlbyname("hw.memsize")` | Physical memory |
| Internal storage | IOKit `IOBlockStorageDriver` statistics | Bytes, operations, and nanoseconds spent servicing reads/writes |
| File-system capacity | `statfs("/")` | Total and available root-file-system bytes |
| Power | IOKit power-source APIs | AC/battery, charging, percentage, and time estimate |
| Sampling clock | `clock_gettime(CLOCK_MONOTONIC)` | Stable interval duration |
| Report clock | `Clock.System.now()` | Human-readable timestamp |

Each snapshot sizes its process and failure arrays from the live PID count
(`proc_listallpids(NULL, 0)`) plus headroom for processes that start between
counting and listing, so a typical machine reserves a few hundred slots instead
of the maximum. The ceilings stay 16,384 process records and 4,096 detailed
process failures per snapshot.

## Process identity

A process is matched across snapshots by:

```text
(pid, proc_start_abstime)
```

PID alone is unsafe because macOS can reuse it after a process exits. A process
without a matching baseline has valid point-in-time gauges but receives zero
rates for that first interval.

## Per-process metrics

### Point-in-time gauges

| Field | Meaning |
|---|---|
| `residentBytes` | Pages currently resident for the task |
| `wiredBytes` | Wired memory charged to the task |
| `physicalFootprintBytes` | Current task physical-footprint ledger |
| `lifetimeMaxPhysicalFootprintBytes` | Lifetime peak physical footprint |
| `compressedOrPagedOutBytes` | Bounded compressor-pager proxy described below |
| `virtualMemoryRegionCount` | Regions visited while calculating that proxy |
| `threadCount` | Current task threads |
| `runningThreadCount` | Threads currently marked running |

Physical footprint is used for normal memory ranking. Summing footprints across
an application is useful for attribution but may count some shared resources
more than once.

### Cumulative counters

| Field | Unit or interpretation |
|---|---|
| `userTimeNs`, `systemTimeNs` | Nanoseconds of CPU time |
| `packageIdleWakeups`, `interruptWakeups` | Wakeup counts |
| `pageIns` | Actual page-ins charged to the task |
| `diskBytesRead`, `diskBytesWritten` | Physical I/O bytes charged to the task |
| `logicalWritesBytes` | Bytes logically written to internal storage before physical writeback/coalescing |
| `instructions`, `cycles` | Hardware-accounted work where available |
| `energyNanojoules` | Accounted energy in nJ where macOS supplies it |
| `billedEnergy` | Older raw billed-energy ledger, retained for compatibility |
| `faults`, `copyOnWriteFaults` | Task VM fault counters |
| `machSystemCalls`, `unixSystemCalls` | Task call counters |
| `contextSwitches` | Task context-switch counter |

Some hardware or protected tasks return zero for optional ledger fields. Zero
means “no value was charged during the interval or the platform did not expose
one”; Harmon does not invent a replacement.

The distinction between physical and logical writes is important:

- logical writes explain an application's write workload before cache and
  file-system coalescing;
- physical writes are the better per-process signal for actual block-device
  traffic, but the process ledger is not device-specific;
- IOKit device totals confirm how much work reached the internal block device,
  including kernel and unattributed work.

## Per-process swap attribution

### What is global

`vm.swapusage.usedBytes` reports the global amount of used swap space.

`HOST_VM_INFO64.swapped_count` is different. XNU increments it by a compressor
segment's `c_slots_used`, so:

```text
swapBackedUncompressedBytes = swapped_count × pageSize
```

This is the uncompressed size of original VM pages represented by compressor
slots currently on disk. It is not the physical number of bytes occupied by
their compressed representation and can be larger than
`vm.swapusage.usedBytes`.

`swapins` and `swapouts` are lifetime counters at the compressor swap-I/O
layer. XNU increments them by the page-rounded byte size actually passed to the
swap-file I/O path. Their deltas therefore produce system-wide compressor
swap-I/O rates for the interval. Device-level IOKit totals remain the final
measure of all traffic that reached internal storage.

### What is only a proxy

`PROC_PIDREGIONINFO` exposes the historically named
`pri_pages_swapped_out`. In current XNU, a page contributes when it is found in
the compressor pager or is marked compressed by the pmap. That does not reveal
whether the compressed data is still in RAM or whether its compressor segment
has been written to a swap file.

Harmon therefore names the result:

```text
compressedOrPagedOutBytes
```

It never labels this value “per-process swap bytes.”

Walking every VM region of every process would make the monitor itself
expensive. Harmon sorts readable processes by physical footprint and attempts
region attribution for the largest 256. Two limits bound the work:

- a per-process limit of 8,192 regions (`HM_ATTRIBUTION_REGION_LIMIT`);
- a sample-wide budget of 100,000 regions shared by all attempted processes.

The budget is shared, not first-come. Candidates are visited in descending
physical-footprint order and each one is given an equal split of whatever is
left of the budget, capped by the per-process limit, so a few large processes
can no longer starve the tail of the candidate list. A walk spends only the
regions it actually read, so an unspent share stays available to later
candidates. A share below `HM_ATTRIBUTION_MIN_REGION_SHARE` (64 regions) buys
nothing but a truncated walk, so it is raised to that minimum while the budget
still affords one and the loop stops once it does not. A candidate skipped that
way is left unattempted rather than counted as an attribution failure.

Reports expose:

- `compressedAttributionProcessCount`;
- `compressedAttributionFailureCount`;
- per-application measured-process count.

A process outside that bounded set has `null`, which is distinct from a
measured value of zero.

The counters mean exactly what they say:

- only a walk that reached the end of the address space produces a measured
  value; libproc reports that by answering `ESRCH` for the address above the
  last region. A walk cut short by its region share, and a walk that stopped on
  a read error partway through, are both undercounts: the partial sum is
  discarded and `compressedOrPagedOutBytes` stays `null`, because an undercount
  must never look like a measurement;
- such a truncated walk is counted in `compressedAttributionFailureCount`: it
  was attempted and it did not produce a usable value;
- a candidate the remaining budget could not afford is not counted at all —
  neither attempted nor failed. It is simply outside the bounded set, like
  every process below the top 256.

A walk that ends exactly on the last allowed region is reported as truncated
even when the address space happened to end there too. The bias is deliberate
and one-directional: a missed measurement is recoverable on the next sample, a
fabricated one is not.

On a workstation running an IDE, a browser, and a virtual machine the budget,
not the 256-process limit, is what actually ends attribution, so coverage stays
well below 256 measured processes. Sharing the budget changes which processes
are covered rather than how much work a sample costs: the largest processes are
truncated at their share instead of consuming the whole budget, and the tail of
the candidate list is still attempted. That is the intended trade. Attribution
is a bounded proxy, and the bound costs a predictable number of system calls
per sample instead of up to 8.4 million.

### Why root still does not make this exact

Root satisfies the normal same-user policy used by `proc_pid_rusage` and
`proc_pidinfo`, so it makes many previously denied processes readable.
Mandatory access-control hooks run separately and may still reject protected
targets. More importantly, root access does not create a public kernel API that
maps swapped compressor segments back to individual PIDs.

`TASK_VM_INFO` contains useful compressed-memory and task swap-in ledgers, but
it requires a task port. `task_for_pid`/task-read access is deliberately
restricted for hardened and platform processes, so it cannot provide complete
system-wide attribution even to a normal root daemon. Harmon uses the more
widely available `libproc` path and reports its limits.

## System virtual-memory metrics

Point-in-time values:

- free, active, inactive, wired, and purgeable bytes;
- physical bytes occupied by the compressor;
- logical uncompressed bytes represented in the compressor;
- used swap space from `vm.swapusage`;
- uncompressed source bytes represented by compressor slots on disk.

Interval values:

```text
pageInBytesPerSecond     = delta(pageins)       × pageSize / elapsedSeconds
pageOutBytesPerSecond    = delta(pageouts)      × pageSize / elapsedSeconds
compressionBytesPerSecond
                         = delta(compressions)  × pageSize / elapsedSeconds
decompressionBytesPerSecond
                         = delta(decompressions)× pageSize / elapsedSeconds
swapInBytesPerSecond     = delta(swapins)       × pageSize / elapsedSeconds
swapOutBytesPerSecond    = delta(swapouts)      × pageSize / elapsedSeconds
```

Page-in/out includes general VM paging and is not synonymous with swap.
Swap-in/out specifically refers to page-rounded compressor-segment I/O, not the
uncompressed size of the pages represented by those segments.

## CPU and process rate calculations

For any monotonic process counter:

```text
rate = max(current - previous, 0) / elapsedSeconds
```

CPU percentage is:

```text
userCpuPercent =
    delta(userTimeNs) / 1,000,000,000 / elapsedSeconds × 100

systemCpuPercent =
    delta(systemTimeNs) / 1,000,000,000 / elapsedSeconds × 100

cpuPercent = userCpuPercent + systemCpuPercent
```

A process can exceed 100% by using multiple logical cores.

`proc_pid_rusage` reports its CPU counters in mach absolute time, so the
collector converts them with `mach_timebase_info` before storing them as
`userTimeNs`/`systemTimeNs`. On Apple Silicon a tick is 125/3 ns; reading the
counters raw would understate every process by a factor of ~41.

Energy is converted to average accounted power:

```text
energyWatts =
    delta(energyNanojoules) / elapsedSeconds / 1,000,000,000
```

System CPU uses deltas of the four host tick counters. The total percentage is
`(user + system + nice) / all ticks × 100`. The collector handles the 32-bit
tick counter wrapping.

If a cumulative counter moves backward because of reuse, wrap, or an
unavailable source, Harmon reports zero for that interval rather than treating
a lifetime total as recent work.

## Internal-storage metrics

Harmon selects `IOBlockStorageDriver` entries with a direct whole, nonremovable,
nonejectable `IOMedia` child. This targets internal physical media and avoids
counting detached block drivers and ordinary disk images.

The device counters are cumulative:

```text
readBytesPerSecond  = delta(deviceBytesRead) / elapsedSeconds
writeBytesPerSecond = delta(deviceBytesWritten) / elapsedSeconds
writeOperationsPerSecond
                    = delta(deviceWriteOperations) / elapsedSeconds
writeServiceTimePercent
                    = delta(deviceWriteTimeNs) / elapsedNs × 100
```

Service-time percentage is an accounting ratio, not a strict utilization
gauge. It can exceed 100% when devices or operations overlap.

Per-process physical writes are not restricted to the internal device and do
not necessarily sum to its total. Kernel writeback, metadata, swap, drivers,
external-device I/O, processes that exited inside the interval, and
inaccessible processes can create a difference. That difference is itself
diagnostically useful.

Harmon currently monitors write rate, not SSD wear indicators such as NVMe
percentage-used or media-error SMART data. Those properties are not uniformly
available through a stable public macOS user-space API.

## Application grouping

Rules and primary reports operate on applications, not isolated helper PIDs:

1. A process inside an `.app` belongs to its outermost application bundle.
2. An unbundled process inherits the nearest readable ancestor's bundle.
3. The bundles named by the `terminalApplications` config key are terminal
   boundaries. Their own bundled helpers remain grouped with the terminal, but
   shells and other external descendants do not inherit the terminal bundle.
   The key holds a comma-separated list of bundle names without `.app`, matched
   case-insensitively, defaulting to `terminal, iterm2, iterm, alacritty,
   wezterm, kitty, ghostty, warp, hyper, tabby, agterm`. It replaces the default
   list outright; an empty value disables this rule.
4. If none of these rules resolves a bundle, the process remains its own group.

For example, all of these belong to `/Applications/Firefox.app`:

```text
/Applications/Firefox.app/Contents/MacOS/firefox
/Applications/Firefox.app/Contents/MacOS/crashhelper
/Applications/Firefox.app/Contents/MacOS/plugin-container.app/Contents/MacOS/plugin-container
```

Outermost-bundle matching handles nested helper bundles and reparented helpers
whose executable remains inside the application. A helper outside the bundle
and reparented away from a readable ancestor cannot be inferred reliably; a
future configuration layer may add explicit grouping overrides.

This boundary keeps a terminal from absorbing an entire interactive process
tree. An application launched from a terminal still uses its own direct
`.app` bundle, while an unbundled shell or command remains an independent
process group.

Rates and gauges are summed across readable members. The application record
also carries the number of members for which compressed/paged-out attribution
was actually measured.

## Battery-impact ranking

Harmon exports `energyWatts` when the V6 ledger changes. It also keeps a stable
cross-process heuristic for systems and tasks where energy accounting is zero:

```text
I/O MiB/s =
    (diskReadBytesPerSecond + diskWriteBytesPerSecond) / 1,048,576

batteryImpactScore =
    cpuPercent + wakeupsPerSecond × 0.25 + I/O MiB/s × 2
```

The score is calculated on AC and battery so trends remain comparable.
Battery-impact alerts fire only while on battery.

The score does not directly include GPU engines, network radio activity,
display brightness, thermal state, or external peripherals.

## Alerts

| Rule | Default | Critical at |
|---|---:|---:|
| Application CPU | 150% | 300% |
| Application physical footprint | 2,048 MiB | 4,096 MiB |
| Application physical storage writes | 50 MiB/s | 100 MiB/s |
| Allocated-swap usage | 1,024 MiB | 2,048 MiB |
| System swap-out traffic | 25 MiB/s | 50 MiB/s |
| Application battery-impact score | 100, on battery | 200 |
| Low battery | 20% | 10% |

Zero disables a threshold. For each application rule the analyzer selects at
most `maxAlertsPerCategory` applications by the rule's metric, and additionally
retains every already-active key that is still above its cleared threshold but
did not survive that cut. A category can therefore carry more than
`maxAlertsPerCategory` alerts; the extra ones were already firing and already
delivered, so they never produce a new push. A push carries only the alerts that
crossed their threshold on this sample; while the condition holds there is no
repeat, and a key becomes pushable again only after it stops firing. Delivery
has to succeed for a key to count as pushed, so a failed webhook or Telegram
call is retried on the next sample instead of being silently dropped.
Notification Center does not count towards that success: macOS returns no
synchronous confirmation to a launchd agent, so the channel is treated as
best-effort. Alert state resets with the agent.

Only the push text is narrowed that way. The HTML report attached to a system
notification and the JSON webhook payload both carry every alert active in the
sample, and the payload's `newAlertKeys` names the subset the push was about.
With `notifyEverySample=true` the agent sends on every sample and treats every
active alert as push content.

`applicationMemoryAlertMiB` and `swapAlertMiB` are capped at 1,048,576 MiB
(1 TiB); a larger value is rejected. The MiB-to-byte conversion saturates rather
than wraps, so a threshold beyond what bytes can express stays unreachable
instead of turning into an alert that always fires.

An alert that fired on the previous sample clears only once its value drops
below 90% of the threshold. Severity is still graded against the full
threshold, so the lowered bound never turns a warning into a critical. Low
battery is exempt: it is the only rule comparing with "less than or equal", and
a lowered bound there would drop the alert while the battery is still low.

An application whose alert is already firing stays in the list even when
noisier applications push it out of the per-category top slice, however many of
them there are, so a category can hold more than `maxAlertsPerCategory` alerts.
Without that, eviction would look like the alert clearing and the alert would be
reported as new again on return.

## Access failures

Process inspection can fail because:

- the process exited during the scan;
- macOS denied the specific information flavor;
- the process or issue capacity was exceeded;
- metadata became unavailable between calls.

These failures do not abort the snapshot. Run:

```shell
harmon diagnose --sample-seconds 2
```

to print grouped PIDs, compressed-attribution coverage, normalized failure
reasons, available metadata, and original `errno` values.

Required global CPU, VM, swap, and physical-memory failures do abort the
request. Power and storage are optional and have explicit availability fields.

## JSON and privacy

Collector IPC and notification payloads are encoded by
`kotlinx.serialization`.

The standard `harmon.sample` webhook includes:

- power, swap, system CPU, load, VM, and internal-storage summaries;
- top applications and processes by CPU, memory, battery impact, physical
  writes, internal logical writes, compressed/paged-out proxy, and accounted
  energy;
- every alert active in the sample, `newAlertKeys` naming the ones that crossed
  their threshold on it, and collection coverage counts.

It excludes executable paths and detailed PID collection failures.

Harmon does not collect:

- command-line arguments or environments of other processes;
- open file names or document contents;
- window titles, screenshots, keystrokes, or clipboard contents;
- per-process network destinations;
- file-level write paths;
- GPU utilization;
- temperature or fan sensors;
- persistent history.

No network request occurs unless Telegram or a webhook is configured and a
notification is due.

## Additional macOS signals worth considering

Public or partly public signals that could improve a future version include:

- Foundation `ProcessInfo.thermalState` and low-power-mode state;
- per-interface network totals from supported networking APIs;
- file-system event summaries for user-selected directories;
- GPU/device utilization where a stable supported API exists;
- coalition identifiers as an additional application-ownership signal;
- a bounded history database for trends and baselines.

File-level attribution through Endpoint Security requires Apple entitlement and
user approval, so it does not belong in the default collector. Private SMC,
Activity Monitor, `powermetrics`, or undocumented GPU interfaces are
deliberately excluded.

## Primary references

- Apple XNU
  [`resource.h`](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/resource.h)
  defines `RUSAGE_INFO_V6`.
- Apple XNU
  [`proc_info.h`](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/proc_info.h)
  defines task and VM-region records.
- Apple XNU
  [`proc_info.c`](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/proc_info.c)
  shows process-information security checks and region dispatch.
- Apple XNU
  [`vm_map.c`](https://github.com/apple-oss-distributions/xnu/blob/main/osfmk/vm/vm_map.c)
  shows how `pages_swapped_out` is populated from compressor-pager state.
- Apple XNU
  [`vm_compressor_backing_store.c`](https://github.com/apple-oss-distributions/xnu/blob/main/osfmk/vm/vm_compressor_backing_store.c)
  shows the separate slot-count and swap-I/O byte accounting.
- Apple XNU
  [`task.c`](https://github.com/apple-oss-distributions/xnu/blob/main/osfmk/kern/task.c)
  defines logical-write, physical-write, swap-in, and energy ledgers.
- Apple documents
  [`vm_statistics64_data_t`](https://developer.apple.com/documentation/kernel/vm_statistics64_data_t)
  and its swap/compressor fields.
- Apple documents
  [`IOBlockStorageDriver` statistics keys](https://developer.apple.com/documentation/iokit/ioblockstoragedriver_h_user-space/defines)
  and the cumulative
  [bytes-written counter](https://developer.apple.com/documentation/iokit/kioblockstoragedriverstatisticsbyteswrittenkey).
