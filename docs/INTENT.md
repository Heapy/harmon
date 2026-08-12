# Product intent

Harmon helps one Mac user understand which applications and processes create
resource pressure, why it happened, and when it started. It turns public macOS
signals into honest interval measurements, actionable notifications, and local
history without becoming a significant workload itself.

## User outcomes

- See current CPU, memory, swap, storage, and battery pressure attributed to the
  applications and processes responsible for it.
- Be notified when a meaningful condition begins without receiving reminders on
  every sample, while retaining a complete report for investigation.
- Distinguish a real zero from unavailable, partial, or proxy data and inspect
  collection failures instead of silently losing them.
- Look back after an incident through bounded, queryable local history and ask
  what was happening at a particular time.
- Install and upgrade the matched agent and collector as one release, then
  verify their deployed and running state without mutation.

## Product principles

- **Be honest about the signal.** Prefer supported public APIs and explicit
  availability, coverage, units, and proxy labels over an exact-looking number
  the platform cannot justify. Never turn missing data into zero.
- **Keep observation bounded.** Collection time, allocations, syscalls, report
  size, retries, logs, and stored history all need limits. A monitor must not
  become a material source of the pressure it explains.
- **Use the least privilege that works.** Root access belongs only to the small,
  request-driven collector. Configuration, credentials, history, reports,
  notifications, network delivery, CLI behavior, and any future UI stay in the
  login-user process.
- **Stay local and private by default.** Persistent data remains on the Mac.
  Nothing is sent over the network unless the user configures a destination,
  and normal payloads omit sensitive diagnostic detail.
- **Make notifications actionable.** Notify on a new condition, use hysteresis
  to resist noise, retry only while the condition can still be represented, and
  keep the full sample in the attached report. Do not claim asynchronous or
  failed delivery was confirmed.
- **Treat time as evidence.** Some facts, such as a process losing its parent,
  are transitions that no single snapshot can recover. Prefer an observed
  transition over a plausible but noisy heuristic, and state the resulting
  blind spots.
- **Keep history optional.** History supports monitoring rather than enabling
  it. A missing, full, locked, or incompatible database must not stop live
  collection, and disabling retention must leave no database behind.
- **Degrade explicitly.** Per-process access failures and optional platform
  signals reduce coverage; they do not invalidate unrelated measurements.
  Fail a request when a required global reading is unavailable rather than
  returning a misleading partial snapshot.

## Deliberate boundaries

- Harmon targets Apple Silicon Macs and is not a cross-platform or hosted
  monitoring service.
- Root improves process visibility but does not bypass SIP, mandatory access
  controls, or every macOS protection; coverage is reported, not assumed.
- Harmon does not claim exact per-process physical swap, reproduce Activity
  Monitor's private Energy Impact formula, or use private GPU, SMC, or
  `powermetrics` interfaces.
- It does not collect command lines, environments, document contents, open-file
  paths, window titles, screenshots, keystrokes, clipboard contents, or network
  destinations.
- The collector protocol is a narrow, read-only snapshot boundary, not a
  general privileged command channel. A future UI must remain on the user side.
- SQLite is the current history interface. A dedicated history CLI or UI should
  follow demonstrated repeated queries, not duplicate `sqlite3` speculatively.
