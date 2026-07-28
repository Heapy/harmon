#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)
USER_ID=$(/usr/bin/id -u)
GROUP_ID=$(/usr/bin/id -g)

if [ "$USER_ID" -eq 0 ]; then
    echo "Run this installer as the login user; it will request sudo when needed." >&2
    exit 1
fi

INSTALL_BIN_DIR="$HOME/.local/bin"
INSTALL_CONFIG_DIR="$HOME/.config/harmon"
INSTALL_LOG_DIR="$HOME/Library/Logs/Harmon"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
HARMON_SUPPORT_DIR="$HOME/Library/Application Support/Harmon"
HARMON_APP="$HARMON_SUPPORT_DIR/Harmon.app"
HARMON_APP_CONTENTS="$HARMON_APP/Contents"
HARMON_AGENT_BINARY="$HARMON_APP_CONTENTS/MacOS/harmon"

HARMON_BINARY="$INSTALL_BIN_DIR/harmon"
HARMON_CONFIG="$INSTALL_CONFIG_DIR/config"
HARMON_SOCKET="/var/run/harmon.collector.sock"
HARMON_AGENT_PLIST="$LAUNCH_AGENTS_DIR/dev.yoda.harmon.agent.plist"
HARMON_AGENT_SERVICE="gui/$USER_ID/dev.yoda.harmon.agent"
HARMON_COLLECTOR_BINARY="/Library/PrivilegedHelperTools/dev.yoda.harmon"
HARMON_COLLECTOR_PLIST="/Library/LaunchDaemons/dev.yoda.harmon.collector.plist"
HARMON_COLLECTOR_SERVICE="system/dev.yoda.harmon.collector"
LEGACY_PLIST="$LAUNCH_AGENTS_DIR/dev.yoda.harmon.plist"
LEGACY_SERVICE="gui/$USER_ID/dev.yoda.harmon"

if [ -x "$PROJECT_DIR/kotlin" ]; then
    TOOLCHAIN="$PROJECT_DIR/kotlin"
elif command -v kotlin >/dev/null 2>&1; then
    TOOLCHAIN=$(command -v kotlin)
else
    echo "Kotlin Toolchain is not installed and the project wrapper is missing." >&2
    exit 1
fi

echo "Building release binary..."
(
    cd "$PROJECT_DIR"
    "$TOOLCHAIN" build --variant release
)

BUILT_BINARY="$PROJECT_DIR/build/tasks/_harmon_linkMacosArm64Release/harmon.kexe"
if [ ! -x "$BUILT_BINARY" ]; then
    echo "Release binary was not found at $BUILT_BINARY" >&2
    exit 1
fi

echo "Checking sudo access for the system LaunchDaemon..."
/usr/bin/sudo -v

/bin/mkdir -p \
    "$INSTALL_BIN_DIR" \
    "$INSTALL_CONFIG_DIR" \
    "$INSTALL_LOG_DIR" \
    "$LAUNCH_AGENTS_DIR" \
    "$HARMON_APP_CONTENTS/MacOS" \
    "$HARMON_APP_CONTENTS/Resources"

/usr/bin/install -m 0755 "$BUILT_BINARY" "$HARMON_AGENT_BINARY"
/usr/bin/install -m 0644 \
    "$PROJECT_DIR/launchd/Harmon.Info.plist" \
    "$HARMON_APP_CONTENTS/Info.plist"
# Before codesign: a resource added to a signed bundle invalidates its signature.
/usr/bin/install -m 0644 \
    "$PROJECT_DIR/launchd/Harmon.icns" \
    "$HARMON_APP_CONTENTS/Resources/Harmon.icns"
SIGNING_IDENTITY=$(
    /usr/bin/security find-identity -v -p codesigning |
        /usr/bin/awk '/^[[:space:]]*[0-9]+\)/ { print $2; exit }'
)
if [ -n "$SIGNING_IDENTITY" ]; then
    /usr/bin/codesign --force --sign "$SIGNING_IDENTITY" "$HARMON_APP"
    echo "Signed Harmon.app with a trusted local code-signing identity."
else
    /usr/bin/codesign --force --sign - "$HARMON_APP"
    echo "No trusted code-signing identity found; using an ad-hoc signature."
fi
/bin/rm -f "$HARMON_BINARY"
/bin/ln -s "$HARMON_AGENT_BINARY" "$HARMON_BINARY"
if [ ! -f "$HARMON_CONFIG" ]; then
    /usr/bin/install -m 0600 \
        "$PROJECT_DIR/config/harmon.conf.example" \
        "$HARMON_CONFIG"
    echo "Created $HARMON_CONFIG"
else
    echo "Keeping existing $HARMON_CONFIG"
fi
/bin/chmod 0600 "$HARMON_CONFIG"

escape_xml_replacement() {
    /usr/bin/sed \
        -e 's/&/\&amp;/g' \
        -e 's/</\&lt;/g' \
        -e 's/>/\&gt;/g' \
        -e 's/[&|\\]/\\&/g'
}

AGENT_BINARY_XML=$(printf '%s' "$HARMON_AGENT_BINARY" | escape_xml_replacement)
CONFIG_XML=$(printf '%s' "$HARMON_CONFIG" | escape_xml_replacement)
LOG_DIR_XML=$(printf '%s' "$INSTALL_LOG_DIR" | escape_xml_replacement)
COLLECTOR_BINARY_XML=$(printf '%s' "$HARMON_COLLECTOR_BINARY" | escape_xml_replacement)
SOCKET_XML=$(printf '%s' "$HARMON_SOCKET" | escape_xml_replacement)

AGENT_PLIST_TEMP=$(/usr/bin/mktemp -t harmon-agent-plist)
COLLECTOR_PLIST_TEMP=$(/usr/bin/mktemp -t harmon-collector-plist)
cleanup() {
    /bin/rm -f "$AGENT_PLIST_TEMP" "$COLLECTOR_PLIST_TEMP"
}
trap cleanup EXIT HUP INT TERM

/usr/bin/sed \
    -e "s|@HARMON_AGENT_BINARY@|$AGENT_BINARY_XML|g" \
    -e "s|@HARMON_CONFIG@|$CONFIG_XML|g" \
    -e "s|@HARMON_LOG_DIR@|$LOG_DIR_XML|g" \
    "$PROJECT_DIR/launchd/dev.yoda.harmon.agent.plist.template" >"$AGENT_PLIST_TEMP"

/usr/bin/sed \
    -e "s|@HARMON_COLLECTOR_BINARY@|$COLLECTOR_BINARY_XML|g" \
    -e "s|@HARMON_SOCKET@|$SOCKET_XML|g" \
    -e "s|@HARMON_USER_ID@|$USER_ID|g" \
    -e "s|@HARMON_GROUP_ID@|$GROUP_ID|g" \
    "$PROJECT_DIR/launchd/dev.yoda.harmon.collector.plist.template" \
    >"$COLLECTOR_PLIST_TEMP"

/usr/bin/plutil -lint "$AGENT_PLIST_TEMP"
/usr/bin/plutil -lint "$COLLECTOR_PLIST_TEMP"

echo "Installing the privileged collector (sudo is required)..."
/usr/bin/sudo /bin/launchctl bootout "$HARMON_COLLECTOR_SERVICE" \
    >/dev/null 2>&1 || true
/bin/launchctl bootout "$HARMON_AGENT_SERVICE" >/dev/null 2>&1 || true
/bin/launchctl bootout "$LEGACY_SERVICE" >/dev/null 2>&1 || true

/usr/bin/sudo /usr/bin/install -d -o root -g wheel -m 0755 \
    /Library/PrivilegedHelperTools \
    /Library/Logs/Harmon
/usr/bin/sudo /usr/bin/install -o root -g wheel -m 0755 \
    "$BUILT_BINARY" \
    "$HARMON_COLLECTOR_BINARY"
/usr/bin/sudo /usr/bin/install -o root -g wheel -m 0644 \
    "$COLLECTOR_PLIST_TEMP" \
    "$HARMON_COLLECTOR_PLIST"
/usr/bin/install -m 0600 "$AGENT_PLIST_TEMP" "$HARMON_AGENT_PLIST"
/bin/rm -f "$LEGACY_PLIST"

/usr/bin/sudo /bin/launchctl bootstrap system "$HARMON_COLLECTOR_PLIST"
/usr/bin/sudo /bin/launchctl enable "$HARMON_COLLECTOR_SERVICE"
/usr/bin/sudo /bin/launchctl kickstart -k "$HARMON_COLLECTOR_SERVICE"

LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
if [ -x "$LSREGISTER" ]; then
    "$LSREGISTER" -f "$HARMON_APP"
fi
# Notification Center caches the sender icon per bundle identifier, so a
# reinstall that changes the icon keeps showing the old one until these restart.
/usr/bin/killall usernoted NotificationCenter >/dev/null 2>&1 || true

/bin/launchctl bootstrap "gui/$USER_ID" "$HARMON_AGENT_PLIST"
/bin/launchctl enable "$HARMON_AGENT_SERVICE"
/bin/launchctl kickstart -k "$HARMON_AGENT_SERVICE"

echo
echo "Harmon is installed and running."
echo "Config:           $HARMON_CONFIG"
echo "Agent app:        $HARMON_APP"
echo "Agent logs:       $INSTALL_LOG_DIR"
echo "Collector logs:   /Library/Logs/Harmon"
echo "Agent status:     launchctl print $HARMON_AGENT_SERVICE"
echo "Collector status: sudo launchctl print $HARMON_COLLECTOR_SERVICE"
