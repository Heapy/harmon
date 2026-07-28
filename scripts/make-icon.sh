#!/bin/sh

set -eu

# Rebuilds launchd/Harmon.icns from logo.png. Run after replacing the logo;
# install.sh copies the committed .icns into the bundle rather than generating
# it, so the installer stays free of image tooling.
#
# logo.png must already carry its rounded corners in the alpha channel. macOS
# draws an .icns as-is, so opaque corners reach Notification Center as visible
# square edges around the artwork.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)

SOURCE_LOGO="$PROJECT_DIR/logo.png"
TARGET_ICNS="$PROJECT_DIR/launchd/Harmon.icns"

if [ ! -f "$SOURCE_LOGO" ]; then
    echo "Source logo was not found at $SOURCE_LOGO" >&2
    exit 1
fi

ICONSET=$(/usr/bin/mktemp -d -t harmon-iconset)
cleanup() {
    /bin/rm -rf "$ICONSET"
}
trap cleanup EXIT INT TERM

/bin/mkdir -p "$ICONSET/Harmon.iconset"

# iconutil requires every size below; @2x names carry twice their nominal size.
for entry in \
    16:icon_16x16 \
    32:icon_16x16@2x \
    32:icon_32x32 \
    64:icon_32x32@2x \
    128:icon_128x128 \
    256:icon_128x128@2x \
    256:icon_256x256 \
    512:icon_256x256@2x \
    512:icon_512x512 \
    1024:icon_512x512@2x; do
    size=${entry%%:*}
    name=${entry#*:}
    /usr/bin/sips --setProperty format png -z "$size" "$size" \
        "$SOURCE_LOGO" --out "$ICONSET/Harmon.iconset/$name.png" >/dev/null
done

/usr/bin/iconutil -c icns "$ICONSET/Harmon.iconset" -o "$TARGET_ICNS"

echo "Wrote $TARGET_ICNS"
