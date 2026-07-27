#!/bin/sh

set -eu

USER_ID=$(/usr/bin/id -u)
if [ "$USER_ID" -eq 0 ]; then
    echo "Run this uninstaller as the login user; it will request sudo when needed." >&2
    exit 1
fi

HARMON_AGENT_SERVICE="gui/$USER_ID/dev.yoda.harmon.agent"
HARMON_AGENT_PLIST="$HOME/Library/LaunchAgents/dev.yoda.harmon.agent.plist"
HARMON_COLLECTOR_SERVICE="system/dev.yoda.harmon.collector"
HARMON_COLLECTOR_PLIST="/Library/LaunchDaemons/dev.yoda.harmon.collector.plist"
HARMON_COLLECTOR_BINARY="/Library/PrivilegedHelperTools/dev.yoda.harmon"
HARMON_SOCKET="/var/run/harmon.collector.sock"
LEGACY_SERVICE="gui/$USER_ID/dev.yoda.harmon"
LEGACY_PLIST="$HOME/Library/LaunchAgents/dev.yoda.harmon.plist"
HARMON_BINARY="$HOME/.local/bin/harmon"
HARMON_APP="$HOME/Library/Application Support/Harmon/Harmon.app"

/bin/launchctl bootout "$HARMON_AGENT_SERVICE" >/dev/null 2>&1 || true
/bin/launchctl bootout "$LEGACY_SERVICE" >/dev/null 2>&1 || true
/usr/bin/sudo /bin/launchctl bootout "$HARMON_COLLECTOR_SERVICE" \
    >/dev/null 2>&1 || true
/bin/rm -f "$HARMON_AGENT_PLIST" "$LEGACY_PLIST" "$HARMON_BINARY"
/bin/rm -rf "$HARMON_APP"
/usr/bin/sudo /bin/rm -f \
    "$HARMON_COLLECTOR_PLIST" \
    "$HARMON_COLLECTOR_BINARY" \
    "$HARMON_SOCKET"

echo "Harmon collector, user agent, and installed binaries were removed."
echo "User configuration and logs were preserved."
echo "Generated HTML reports were preserved under ~/Library/Application Support/Harmon."
echo "Collector logs were preserved under /Library/Logs/Harmon."
