#!/bin/bash

set -euo pipefail

usage() {
    echo "Usage: $0 [--output-dir DIR] [--tag vVERSION]" >&2
}

PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
OUTPUT_DIR="$PROJECT_DIR/dist"
EXPECTED_TAG=

while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir)
            if [[ $# -lt 2 ]]; then
                usage
                exit 2
            fi
            OUTPUT_DIR=$2
            shift 2
            ;;
        --tag)
            if [[ $# -lt 2 ]]; then
                usage
                exit 2
            fi
            EXPECTED_TAG=$2
            shift 2
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

if [[ -n "$(git -C "$PROJECT_DIR" status --porcelain --untracked-files=normal)" ]]; then
    echo "Release packaging requires a clean checkout." >&2
    exit 1
fi

/bin/mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd "$OUTPUT_DIR" && pwd)

(
    cd "$PROJECT_DIR"
    ./kotlin build --variant release
)

shopt -s nullglob
AGENT_CANDIDATES=(
    "$PROJECT_DIR"/build/tasks/*_linkMacosArm64Release/harmon.kexe
)
shopt -u nullglob
if [[ ${#AGENT_CANDIDATES[@]} -ne 1 ]]; then
    echo "Expected exactly one release harmon.kexe, found ${#AGENT_CANDIDATES[@]}." >&2
    exit 1
fi

AGENT_BINARY=${AGENT_CANDIDATES[0]}
COLLECTOR_BINARY="$PROJECT_DIR/build/tasks/_harmon-collector_linkMacosArm64Release/harmon-collector.kexe"
if [[ ! -x "$COLLECTOR_BINARY" ]]; then
    echo "Release collector is missing or not executable: $COLLECTOR_BINARY" >&2
    exit 1
fi

AGENT_REPORT=$("$AGENT_BINARY" --version)
if [[ ! "$AGENT_REPORT" =~ ^harmon\ ([0-9]+\.[0-9]+\.[0-9]+([.+-][0-9A-Za-z.-]+)?)$ ]]; then
    echo "Unexpected agent version output: $AGENT_REPORT" >&2
    exit 1
fi
VERSION=${BASH_REMATCH[1]}
COLLECTOR_REPORT=$("$COLLECTOR_BINARY" --version)
if [[ "$COLLECTOR_REPORT" != "harmon-collector $VERSION" ]]; then
    echo "Version mismatch: '$AGENT_REPORT' versus '$COLLECTOR_REPORT'." >&2
    exit 1
fi
if [[ -n "$EXPECTED_TAG" && "$EXPECTED_TAG" != "v$VERSION" ]]; then
    echo "Tag $EXPECTED_TAG does not match binary version $VERSION." >&2
    exit 1
fi

for BINARY in "$AGENT_BINARY" "$COLLECTOR_BINARY"; do
    if [[ "$(/usr/bin/lipo -archs "$BINARY")" != "arm64" ]]; then
        echo "Release binary is not arm64-only: $BINARY" >&2
        exit 1
    fi
done

TEMP_DIR=$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/harmon-release.XXXXXX")
trap '/bin/rm -rf "$TEMP_DIR"' EXIT
ARCHIVE_NAME="harmon-$VERSION-macos-arm64.tar.gz"
STAGE_NAME=${ARCHIVE_NAME%.tar.gz}
STAGE="$TEMP_DIR/$STAGE_NAME"

/bin/mkdir -p "$STAGE/bin" "$STAGE/libexec" "$STAGE/share/harmon"
/bin/cp "$AGENT_BINARY" "$STAGE/bin/harmon"
/bin/cp "$COLLECTOR_BINARY" "$STAGE/libexec/harmon-collector"
/bin/chmod 0755 "$STAGE/bin/harmon" "$STAGE/libexec/harmon-collector"

/usr/bin/sed "s/@HARMON_VERSION@/$VERSION/g" \
    "$PROJECT_DIR/launchd/Harmon.Info.plist" \
    > "$STAGE/share/harmon/Harmon.Info.plist"
/bin/cp "$PROJECT_DIR/launchd/Harmon.icns" \
    "$STAGE/share/harmon/Harmon.icns"
/bin/cp "$PROJECT_DIR/config/harmon.conf.example" \
    "$STAGE/share/harmon/harmon.conf.example"
/bin/chmod 0644 "$STAGE/share/harmon/"*

if /usr/bin/grep -q '@HARMON_VERSION@' "$STAGE/share/harmon/Harmon.Info.plist"; then
    echo "Staged Harmon.Info.plist still contains the version token." >&2
    exit 1
fi
/usr/bin/plutil -lint "$STAGE/share/harmon/Harmon.Info.plist" >/dev/null

for BINARY in "$STAGE/bin/harmon" "$STAGE/libexec/harmon-collector"; do
    /usr/bin/codesign --force --sign - --timestamp=none "$BINARY"
    /usr/bin/codesign --verify --strict "$BINARY"
done

if [[ "$("$STAGE/bin/harmon" --version)" != "harmon $VERSION" ]]; then
    echo "The staged agent does not report version $VERSION." >&2
    exit 1
fi
if [[ "$("$STAGE/libexec/harmon-collector" --version)" != "harmon-collector $VERSION" ]]; then
    echo "The staged collector does not report version $VERSION." >&2
    exit 1
fi

EXPECTED_FILES=$'bin/harmon\nlibexec/harmon-collector\nshare/harmon/Harmon.Info.plist\nshare/harmon/Harmon.icns\nshare/harmon/harmon.conf.example'
ACTUAL_FILES=$(
    cd "$STAGE"
    /usr/bin/find . -type f -print |
        /usr/bin/sed 's|^\./||' |
        LC_ALL=C /usr/bin/sort
)
if [[ "$ACTUAL_FILES" != "$EXPECTED_FILES" ]]; then
    echo "Release staging layout is not the expected five files:" >&2
    echo "$ACTUAL_FILES" >&2
    exit 1
fi

/usr/bin/find "$STAGE" -exec /usr/bin/touch -h -t 200001010000.00 {} +
MANIFEST="$TEMP_DIR/archive-manifest"
(
    cd "$TEMP_DIR"
    /usr/bin/find "$STAGE_NAME" -print |
        LC_ALL=C /usr/bin/sort > "$MANIFEST"
)

create_archive() {
    local target=$1
    (
        cd "$TEMP_DIR"
        COPYFILE_DISABLE=1 /usr/bin/tar \
            -c -z -n -f "$target" \
            --format ustar \
            --uid 0 --gid 0 --uname root --gname wheel \
            --no-acls --no-fflags --no-mac-metadata --no-xattrs \
            --options gzip:!timestamp \
            -T "$MANIFEST"
    )
}

FIRST_ARCHIVE="$TEMP_DIR/first.tar.gz"
SECOND_ARCHIVE="$TEMP_DIR/second.tar.gz"
create_archive "$FIRST_ARCHIVE"
create_archive "$SECOND_ARCHIVE"
if ! /usr/bin/cmp -s "$FIRST_ARCHIVE" "$SECOND_ARCHIVE"; then
    echo "Release archive generation is not deterministic." >&2
    exit 1
fi

SMOKE_ROOT="$TEMP_DIR/smoke"
/bin/mkdir "$SMOKE_ROOT"
COPYFILE_DISABLE=1 /usr/bin/tar -x -f "$FIRST_ARCHIVE" -C "$SMOKE_ROOT"
SMOKE_STAGE="$SMOKE_ROOT/$STAGE_NAME"
SMOKE_FILES=$(
    cd "$SMOKE_STAGE"
    /usr/bin/find . -type f -print |
        /usr/bin/sed 's|^\./||' |
        LC_ALL=C /usr/bin/sort
)
if [[ "$SMOKE_FILES" != "$EXPECTED_FILES" ]]; then
    echo "Extracted release layout differs from staging." >&2
    exit 1
fi
if [[ ! -x "$SMOKE_STAGE/bin/harmon" || ! -x "$SMOKE_STAGE/libexec/harmon-collector" ]]; then
    echo "Extracted release binaries are not executable." >&2
    exit 1
fi
/usr/bin/codesign --verify --strict "$SMOKE_STAGE/bin/harmon"
/usr/bin/codesign --verify --strict "$SMOKE_STAGE/libexec/harmon-collector"
/usr/bin/plutil -lint "$SMOKE_STAGE/share/harmon/Harmon.Info.plist" >/dev/null
[[ "$("$SMOKE_STAGE/bin/harmon" --version)" == "harmon $VERSION" ]]
[[ "$("$SMOKE_STAGE/libexec/harmon-collector" --version)" == "harmon-collector $VERSION" ]]

ARCHIVE="$OUTPUT_DIR/$ARCHIVE_NAME"
/bin/mv -f "$FIRST_ARCHIVE" "$ARCHIVE"
SHA256=$(/usr/bin/shasum -a 256 "$ARCHIVE" | /usr/bin/awk '{print $1}')
(
    cd "$OUTPUT_DIR"
    /usr/bin/shasum -a 256 "$ARCHIVE_NAME" > "$ARCHIVE_NAME.sha256"
)

FORMULA="$OUTPUT_DIR/Formula/harmon.rb"
"$PROJECT_DIR/scripts/render-homebrew-formula.sh" "$VERSION" "$SHA256" "$FORMULA"
if /usr/bin/grep -Eq '(^|[[:space:]])(service do|post_install|sudo)([[:space:]]|$)' "$FORMULA"; then
    echo "Generated formula must not manage services or request privileges." >&2
    exit 1
fi

COMMIT=$(git -C "$PROJECT_DIR" rev-parse HEAD)
PROVENANCE="$OUTPUT_DIR/$ARCHIVE_NAME.provenance.json"
/usr/bin/printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    '  "artifact": "'"$ARCHIVE_NAME"'",' \
    '  "sha256": "'"$SHA256"'",' \
    '  "version": "'"$VERSION"'",' \
    '  "gitCommit": "'"$COMMIT"'",' \
    '  "platform": "macos-arm64",' \
    '  "contents": ["bin/harmon", "libexec/harmon-collector", "share/harmon/Harmon.Info.plist", "share/harmon/Harmon.icns", "share/harmon/harmon.conf.example"]' \
    '}' > "$PROVENANCE"
/usr/bin/ruby -rjson -e 'JSON.parse(File.read(ARGV.fetch(0)))' "$PROVENANCE"

echo "Packaged paired Harmon $VERSION release:"
echo "  $ARCHIVE"
echo "  $ARCHIVE.sha256"
echo "  $PROVENANCE"
echo "  $FORMULA"
