# Releasing Harmon

Harmon is released as one prebuilt Apple Silicon archive. The archive is the
unit of compatibility: `harmon` and `harmon-collector` are built from the same
clean commit, report the same application version, and cannot be downloaded as
separate release assets.

## Prepare a release

Update `BuildInfo.VERSION`, commit the change, and create a matching `vX.Y.Z`
tag. Before tagging, run the normal verification:

```shell
./kotlin build
./kotlin test
scripts/test-native.sh
```

The tag workflow invokes:

```shell
scripts/package-release.sh --output-dir dist --tag vX.Y.Z
```

The packaging script always runs `./kotlin build --variant release`; it has no
skip-build mode. It rejects a dirty checkout, a tag/version mismatch, a
non-arm64 or mixed-version pair, invalid signatures, an unexpected archive
layout, and non-deterministic output. It ad-hoc signs both standalone
executables with timestamps disabled, renders and lints the application
`Info.plist`, creates the archive twice and compares the bytes, then extracts
the result for a complete smoke check.

The output is:

```text
dist/
  harmon-X.Y.Z-macos-arm64.tar.gz
  harmon-X.Y.Z-macos-arm64.tar.gz.sha256
  harmon-X.Y.Z-macos-arm64.tar.gz.provenance.json
  Formula/harmon.rb
```

The workflow publishes all four files and adds a GitHub artifact attestation
for the tarball.

The release binaries are intentionally ad-hoc signed, and Homebrew is the only
supported distribution channel. A direct browser-download channel would require
a separately designed Developer ID and notarization flow; do not describe the
current archive as notarized.

## Homebrew tap handoff

The tap is intentionally a separate repository. After the GitHub Release is
published, copy the generated formula, not the checked-in template, into that
repository:

```shell
cp dist/Formula/harmon.rb /path/to/homebrew-tap/Formula/harmon.rb
brew tap Heapy/tap /path/to/homebrew-tap
brew style Heapy/tap/harmon
brew audit --strict --formula Heapy/tap/harmon
brew install --build-from-source Heapy/tap/harmon
brew test Heapy/tap/harmon
```

Commit and publish that tap change only after these checks pass. The checked-in
`packaging/homebrew/Formula/harmon.rb.in` is the version-independent source;
`scripts/render-homebrew-formula.sh` replaces its version and SHA-256 tokens
after the tarball exists.

The formula installs only:

```text
bin/harmon
libexec/harmon-collector
share/harmon/Harmon.Info.plist
share/harmon/Harmon.icns
share/harmon/PREACT-LICENSE
share/harmon/harmon.conf.example
```

It does not build Kotlin/Native, run sudo, mutate launchd, or define a Homebrew
service. Its caveat directs the user to run `harmon setup` after install and
after every upgrade, then `harmon status`.

## Verify published provenance

After publishing, verify the checksum and GitHub attestation:

```shell
shasum -a 256 -c harmon-X.Y.Z-macos-arm64.tar.gz.sha256
gh attestation verify harmon-X.Y.Z-macos-arm64.tar.gz -R Heapy/harmon
```

## Manual setup and status acceptance

Run this on a disposable Apple Silicon test account after publishing the
release and updating `Heapy/tap`. Do not run setup through sudo; the ordinary
process owns the user half and requests sudo exactly once for the system half.
Using the Homebrew path explicitly also migrates a machine where the former
`~/.local/bin/harmon` symlink shadows the new command.

```shell
brew install Heapy/tap/harmon
HARMON_ACCEPTANCE_CLI="$(brew --prefix)/bin/harmon"
HARMON_ACCEPTANCE_CONFIG="$HOME/.config/harmon/config"

"$HARMON_ACCEPTANCE_CLI" --version
"$(brew --prefix)/opt/harmon/libexec/harmon-collector" --version

# A fresh or legacy install is expected to be unhealthy before setup.
if "$HARMON_ACCEPTANCE_CLI" status; then
  echo "expected status to require setup" >&2
  exit 1
fi

sudo -k
"$HARMON_ACCEPTANCE_CLI" setup
"$HARMON_ACCEPTANCE_CLI" status
```

Validate the generated files, ownership boundary, signatures, and service
programs:

```shell
/usr/bin/plutil -lint \
  "$HOME/Library/LaunchAgents/io.heapy.harmon.agent.plist"
sudo /usr/bin/plutil -lint \
  /Library/LaunchDaemons/io.heapy.harmon.collector.plist

/usr/bin/stat -f '%Lp %Su:%Sg %N' \
  "$HARMON_ACCEPTANCE_CONFIG" \
  "$HOME/Library/LaunchAgents/io.heapy.harmon.agent.plist" \
  "$HOME/Library/Application Support/Harmon/Harmon.app/Contents/MacOS/harmon"
sudo /usr/bin/stat -f '%Lp %Su:%Sg %N' \
  /Library/PrivilegedHelperTools/harmon-collector \
  /Library/LaunchDaemons/io.heapy.harmon.collector.plist

/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' \
  "$HOME/Library/LaunchAgents/io.heapy.harmon.agent.plist"
sudo /usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' \
  /Library/LaunchDaemons/io.heapy.harmon.collector.plist

/usr/bin/codesign --verify --deep --strict \
  "$HOME/Library/Application Support/Harmon/Harmon.app"
test ! -e \
  "$HOME/Library/Application Support/Harmon/io.heapy.harmon.agent.pending.plist"
/bin/launchctl print "gui/$(id -u)/io.heapy.harmon.agent"
sudo /bin/launchctl print system/io.heapy.harmon.collector
```

The expected modes are `0600` for config and LaunchAgent, `0755` for both
executables, and `0644 root:wheel` for the LaunchDaemon. Its first
`ProgramArguments` value must be exactly
`/Library/PrivilegedHelperTools/harmon-collector`, never a Homebrew path.

Prove idempotence without exposing config contents:

```shell
HARMON_ACCEPTANCE_CONFIG_SHA=$(
  /usr/bin/shasum -a 256 "$HARMON_ACCEPTANCE_CONFIG" |
    /usr/bin/awk '{print $1}'
)
"$HARMON_ACCEPTANCE_CLI" setup
test "$HARMON_ACCEPTANCE_CONFIG_SHA" = "$(
  /usr/bin/shasum -a 256 "$HARMON_ACCEPTANCE_CONFIG" |
    /usr/bin/awk '{print $1}'
)"
"$HARMON_ACCEPTANCE_CLI" status
```

On a machine migrated from a pre-rename release or the old source installer,
also verify that only their managed compatibility artifacts disappeared:

```shell
test ! -e "$HOME/Library/LaunchAgents/dev.yoda.harmon.agent.plist"
test ! -e "$HOME/Library/LaunchAgents/dev.yoda.harmon.plist"
test ! -L "$HOME/.local/bin/harmon"
sudo test ! -e /Library/LaunchDaemons/dev.yoda.harmon.collector.plist
sudo test ! -e /Library/PrivilegedHelperTools/dev.yoda.harmon

! /bin/launchctl print "gui/$(id -u)/dev.yoda.harmon.agent"
! /bin/launchctl print "gui/$(id -u)/dev.yoda.harmon"
! sudo /bin/launchctl print system/dev.yoda.harmon.collector
```

Finally publish a newer release into the same tap and exercise the upgrade gap:

```shell
brew update
brew upgrade harmon
HARMON_ACCEPTANCE_CLI="$(brew --prefix)/bin/harmon"

if "$HARMON_ACCEPTANCE_CLI" status; then
  echo "expected stale deployed copies after brew upgrade" >&2
  exit 1
fi

"$HARMON_ACCEPTANCE_CLI" setup
"$HARMON_ACCEPTANCE_CLI" status
"$HOME/Library/Application Support/Harmon/Harmon.app/Contents/MacOS/harmon" \
  --version
/Library/PrivilegedHelperTools/harmon-collector --version
```

The first post-upgrade status must name the source/deployed mismatch and say
`Run 'harmon setup'`; the final status must exit zero with both versions equal
to the upgraded CLI.

## Manual uninstall acceptance

On the same disposable account, verify that uninstall removes managed services
and binaries while preserving user data:

```shell
"$HARMON_ACCEPTANCE_CLI" uninstall

test ! -e "$HOME/Library/LaunchAgents/io.heapy.harmon.agent.plist"
test ! -e "$HOME/Library/LaunchAgents/dev.yoda.harmon.agent.plist"
test ! -e "$HOME/Library/LaunchAgents/dev.yoda.harmon.plist"
test ! -e \
  "$HOME/Library/Application Support/Harmon/io.heapy.harmon.agent.pending.plist"
test ! -e "$HOME/Library/Application Support/Harmon/Harmon.app"
sudo test ! -e /Library/LaunchDaemons/io.heapy.harmon.collector.plist
sudo test ! -e /Library/LaunchDaemons/dev.yoda.harmon.collector.plist
sudo test ! -e /Library/PrivilegedHelperTools/harmon-collector
sudo test ! -e /Library/PrivilegedHelperTools/dev.yoda.harmon
sudo test ! -e /var/run/harmon.collector.sock

! /bin/launchctl print "gui/$(id -u)/io.heapy.harmon.agent"
! /bin/launchctl print "gui/$(id -u)/dev.yoda.harmon.agent"
! /bin/launchctl print "gui/$(id -u)/dev.yoda.harmon"
! sudo /bin/launchctl print system/io.heapy.harmon.collector
! sudo /bin/launchctl print system/dev.yoda.harmon.collector

test -f "$HARMON_ACCEPTANCE_CONFIG"
test -d "$HOME/Library/Logs/Harmon"
```

Then verify the purging form. It needs an installation to remove, so set up
again first:

```shell
"$HARMON_ACCEPTANCE_CLI" setup
"$HARMON_ACCEPTANCE_CLI" uninstall --purge

test ! -e "$HOME/Library/Application Support/Harmon"
test ! -e "$HOME/.config/harmon"
test ! -e "$HOME/Library/Logs/Harmon"
sudo test ! -e /Library/Logs/Harmon
test ! -e "$HOME/Library/LaunchAgents/io.heapy.harmon.agent.plist"
test ! -e "$HOME/Library/LaunchAgents/dev.yoda.harmon.agent.plist"
test ! -e "$HOME/Library/LaunchAgents/dev.yoda.harmon.plist"
sudo test ! -e /Library/LaunchDaemons/io.heapy.harmon.collector.plist
sudo test ! -e /Library/LaunchDaemons/dev.yoda.harmon.collector.plist
sudo test ! -e /Library/PrivilegedHelperTools/harmon-collector
sudo test ! -e /Library/PrivilegedHelperTools/dev.yoda.harmon
```

The command prints each removed tree before deleting anything and never prompts,
so the block stays non-interactive apart from sudo's own password prompt. This
is the last step that needs `$HARMON_ACCEPTANCE_CONFIG`.

The Homebrew Cellar copy remains until `brew uninstall harmon`. Legacy-path
cleanup is transitional compatibility behavior; remove it only after the
supported migration window from the source installer has closed.
