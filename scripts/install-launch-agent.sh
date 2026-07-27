#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)
USER_ID=$(/usr/bin/id -u)

INSTALL_BIN_DIR="$HOME/.local/bin"
INSTALL_CONFIG_DIR="$HOME/.config/harmon"
INSTALL_LOG_DIR="$HOME/Library/Logs/Harmon"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"

HARMON_BINARY="$INSTALL_BIN_DIR/harmon"
HARMON_CONFIG="$INSTALL_CONFIG_DIR/config"
HARMON_PLIST="$LAUNCH_AGENTS_DIR/dev.yoda.harmon.plist"
HARMON_SERVICE="gui/$USER_ID/dev.yoda.harmon"

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

/bin/mkdir -p \
    "$INSTALL_BIN_DIR" \
    "$INSTALL_CONFIG_DIR" \
    "$INSTALL_LOG_DIR" \
    "$LAUNCH_AGENTS_DIR"

/usr/bin/install -m 0755 "$BUILT_BINARY" "$HARMON_BINARY"
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

BINARY_XML=$(printf '%s' "$HARMON_BINARY" | escape_xml_replacement)
CONFIG_XML=$(printf '%s' "$HARMON_CONFIG" | escape_xml_replacement)
LOG_DIR_XML=$(printf '%s' "$INSTALL_LOG_DIR" | escape_xml_replacement)

/usr/bin/sed \
    -e "s|@HARMON_BINARY@|$BINARY_XML|g" \
    -e "s|@HARMON_CONFIG@|$CONFIG_XML|g" \
    -e "s|@HARMON_LOG_DIR@|$LOG_DIR_XML|g" \
    "$PROJECT_DIR/launchd/dev.yoda.harmon.plist.template" >"$HARMON_PLIST"
/bin/chmod 0600 "$HARMON_PLIST"

/usr/bin/plutil -lint "$HARMON_PLIST"
/bin/launchctl bootout "$HARMON_SERVICE" >/dev/null 2>&1 || true
/bin/launchctl bootstrap "gui/$USER_ID" "$HARMON_PLIST"
/bin/launchctl enable "$HARMON_SERVICE"
/bin/launchctl kickstart -k "$HARMON_SERVICE"

echo
echo "Harmon is installed and running."
echo "Config: $HARMON_CONFIG"
echo "Logs:   $INSTALL_LOG_DIR"
echo "Status: launchctl print $HARMON_SERVICE"
