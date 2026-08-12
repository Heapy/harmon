#!/bin/sh

# Regenerates headers from the authoritative .def bodies, then builds and runs the C harness.
# --sanitize enables failing ASan/UBSan checks; macOS LeakSanitizer is unavailable.
# Usage: scripts/test-native.sh [--sanitize] [--self-check] [name-prefix]

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)

SANITIZE=0
REMAINING=$#
while [ "$REMAINING" -gt 0 ]; do
    ARGUMENT=$1
    shift
    if [ "$ARGUMENT" = "--sanitize" ]; then
        SANITIZE=1
    else
        set -- "$@" "$ARGUMENT"
    fi
    REMAINING=$((REMAINING - 1))
done

IPC_DEF_FILE="$PROJECT_DIR/bridge-ipc/cinterop/harmon_ipc.def"
PROBE_DEF_FILE="$PROJECT_DIR/bridge-probe/cinterop/harmon_probe.def"
HTTP_DEF_FILE="$PROJECT_DIR/bridge-http/cinterop/harmon_http.def"
SOURCE_DIR="$PROJECT_DIR/test/native"
OUTPUT_DIR="$PROJECT_DIR/build/native-test"
IPC_HEADER_FILE="$OUTPUT_DIR/harmon_ipc.h"
PROBE_HEADER_FILE="$OUTPUT_DIR/harmon_probe.h"
HTTP_HEADER_FILE="$OUTPUT_DIR/harmon_http.h"
if [ "$SANITIZE" -eq 1 ]; then
    BINARY_FILE="$OUTPUT_DIR/harmon-native-test-sanitized"
    SANITIZER_OPTS="-fsanitize=address,undefined -fno-sanitize-recover=all -g"
else
    BINARY_FILE="$OUTPUT_DIR/harmon-native-test"
    SANITIZER_OPTS=""
fi

for DEF_FILE in "$IPC_DEF_FILE" "$PROBE_DEF_FILE" "$HTTP_DEF_FILE"; do
    if [ ! -f "$DEF_FILE" ]; then
        echo "native bridge definition not found at $DEF_FILE" >&2
        exit 2
    fi
done

if [ ! -d "$SOURCE_DIR" ]; then
    echo "C test sources not found at $SOURCE_DIR" >&2
    exit 2
fi

mkdir -p "$OUTPUT_DIR"

# Everything after `---` is the generated header body.
sed '1,/^---$/d' "$IPC_DEF_FILE" > "$IPC_HEADER_FILE"
sed '1,/^---$/d' "$PROBE_DEF_FILE" > "$PROBE_HEADER_FILE"
sed '1,/^---$/d' "$HTTP_DEF_FILE" > "$HTTP_HEADER_FILE"

# Derive link options from the definitions so the test link cannot drift from cinterop.
LINKER_OPTS=$(sed -n 's/^linkerOpts *= *//p' "$PROBE_DEF_FILE" "$HTTP_DEF_FILE")
if [ -z "$LINKER_OPTS" ]; then
    echo "no linkerOpts found in probe or HTTP bridge definitions" >&2
    exit 2
fi

# LINKER_OPTS and SANITIZER_OPTS are argument lists and intentionally expand unquoted.
clang \
    -std=c11 \
    -Wall \
    -Wextra \
    -Werror \
    $SANITIZER_OPTS \
    -I"$OUTPUT_DIR" \
    -o "$BINARY_FILE" \
    "$SOURCE_DIR"/*.c \
    $LINKER_OPTS

exec "$BINARY_FILE" "$@"
