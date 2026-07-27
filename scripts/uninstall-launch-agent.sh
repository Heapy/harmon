#!/bin/sh

set -eu

USER_ID=$(/usr/bin/id -u)
HARMON_SERVICE="gui/$USER_ID/dev.yoda.harmon"
HARMON_PLIST="$HOME/Library/LaunchAgents/dev.yoda.harmon.plist"
HARMON_BINARY="$HOME/.local/bin/harmon"

/bin/launchctl bootout "$HARMON_SERVICE" >/dev/null 2>&1 || true
/bin/rm -f "$HARMON_PLIST" "$HARMON_BINARY"

echo "Harmon LaunchAgent and binary were removed."
echo "Config and logs were kept under ~/.config/harmon and ~/Library/Logs/Harmon."

