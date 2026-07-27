# Harmon

Harmon is a lightweight macOS process monitor written in Kotlin/Native. It
periodically samples process, swap, and battery metrics, writes a compact
report, and sends alerts to Notification Center, Telegram, and JSON webhooks.

The current application is a launchd-managed CLI without a UI. Its monitoring
core, macOS collector, rule analysis, and delivery channels are separated so a
SwiftUI or Compose UI can be added without moving the monitoring logic.

See [docs/collection.md](docs/collection.md) for the complete specification of
data sources, formulas, access limitations, and external delivery.

## Features

- CPU usage for every readable process over the actual sampling window;
- resident memory and physical footprint;
- automatic grouping by the outermost macOS `.app` bundle, including helper
  and descendant processes;
- allocated and used swap, including its encryption status;
- AC or battery power, charging state, percentage, and remaining-time estimate;
- process wakeups, disk I/O, and the system `billed_energy` counter;
- transparent application-level battery-impact ranking;
- application-level threshold rules, alert cooldowns, and per-category limits;
- local diagnostics showing grouped PIDs and every process whose resource
  metrics could not be read;
- Notification Center, Telegram Bot API, and arbitrary HTTPS webhook delivery;
- typed JSON encoded with `kotlinx.serialization`;
- installation as a user LaunchAgent.

## Important macOS limitation

Activity Monitor does not publish its exact `Energy Impact` algorithm as a
supported API. Harmon therefore does not pretend to reproduce that value.
`batteryImpactScore` is a documented heuristic:

```text
CPU % + wakeups/s × 0.25 + disk I/O MiB/s × 2
```

The system `billed_energy` value is exported separately in webhook payloads
without assuming a physical unit.

The user LaunchAgent is intentional: it runs in the GUI session and can display
notifications. Some protected macOS processes do not expose metrics to an
unprivileged user, so reports explicitly include the number of `inaccessible`
processes. Full root-level visibility would require two components in the
future: a privileged LaunchDaemon collector and a user agent for delivery and
UI.

Run `harmon diagnose` to see multi-process application groups and the PID,
executable path, failure category, and `errno` for each process without
resource metrics.

## Requirements

- Apple Silicon Mac;
- Xcode Command Line Tools or Xcode;
- Kotlin Toolchain 0.11.1 through the project wrapper;
- Kotlin 2.4.10, downloaded automatically by the toolchain.

## Build and test

```shell
./kotlin build
./kotlin test
./kotlin run -- once --sample-seconds 2
```

Build the release variant with:

```shell
./kotlin build --variant release
```

The resulting arm64 executable is written to
`build/tasks/_harmon_linkMacosArm64Release/harmon.kexe`.

## Commands

```text
harmon run [--config PATH]
harmon once [--config PATH] [--sample-seconds N] [--notify]
harmon diagnose [--config PATH] [--sample-seconds N]
harmon check-config [--config PATH]
harmon test-notifications [--config PATH]
harmon --help
harmon --version
```

With no command, Harmon starts its continuous monitoring loop. `once` takes two
snapshots over a short interval and prints one report. Add `--notify` to deliver
that report through every configured channel. `diagnose` takes the same sampled
report and appends the full grouping and collection-failure inventory.

## Configuration

Harmon reads `~/.config/harmon/config` by default. If the file does not exist,
safe defaults are used. See
[config/harmon.conf.example](config/harmon.conf.example).

Key settings:

```properties
intervalSeconds=300
applicationCpuAlertPercent=150
applicationMemoryAlertMiB=2048
swapAlertMiB=1024
applicationBatteryImpactAlertScore=100
batteryLowAlertPercent=20
systemNotifications=true
notifyEverySample=false
```

A threshold of `0` disables its rule. Swap is checked by absolute usage because
macOS dynamically changes the allocated swap pool.

The former `processCpuAlertPercent`, `processMemoryAlertMiB`, and
`batteryImpactAlertScore` names are accepted as compatibility aliases.

The webhook receives an `application/json` POST. External endpoints must use
HTTPS; `http://127.0.0.1` is allowed for local development. Telegram and
webhook credentials can be stored in a configuration file with `0600`
permissions. For manual runs or CI, provide them through the environment:

```text
HARMON_WEBHOOK_URL
HARMON_WEBHOOK_BEARER_TOKEN
HARMON_TELEGRAM_BOT_TOKEN
HARMON_TELEGRAM_CHAT_ID
```

A LaunchAgent does not inherit variables from the current terminal. For a
normal installation, keep secrets in the generated `0600` configuration file
or explicitly add them to the launchd environment.

Validate configuration and delivery without printing secrets:

```shell
harmon check-config
harmon test-notifications
```

## Install with launchd

```shell
./scripts/install-launch-agent.sh
```

The installer:

- builds the release executable;
- installs it as `~/.local/bin/harmon`;
- creates `~/.config/harmon/config` without overwriting an existing file;
- registers `~/Library/LaunchAgents/dev.yoda.harmon.plist`;
- writes logs under `~/Library/Logs/Harmon`.

Inspect status and logs:

```shell
launchctl print "gui/$(id -u)/dev.yoda.harmon"
tail -f ~/Library/Logs/Harmon/harmon.log
tail -f ~/Library/Logs/Harmon/harmon.error.log
```

Remove the agent and executable while preserving configuration and logs:

```shell
./scripts/uninstall-launch-agent.sh
```

## Project structure

```text
src/
  config/      configuration loading and validation
  monitor/     macOS collection and interval calculations
  analysis/    application grouping, alert rules, and cooldowns
  report/      text output and kotlinx.serialization DTOs
  notify/      Notification Center, webhook, and Telegram delivery
  runtime/     continuous service loop
cinterop/      libproc, sysctl, IOKit, and libcurl bridge
launchd/       LaunchAgent template
docs/          collection model, formulas, and exported data
LICENSE        GPL-3.0-only terms
```

A future UI can consume the same `SystemUsage` and `MonitoringReport` models,
with local time-series storage and IPC between the collector process and the
application.

## License

Harmon is free software licensed under the
[GNU General Public License version 3 only](LICENSE)
(`GPL-3.0-only`).
