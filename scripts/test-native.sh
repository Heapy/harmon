#!/bin/sh

# Compiles and runs the C test harness over the native bridge.
#
# The bridge lives entirely inside `nativebridge/cinterop/harmon_native.def`;
# cinterop cannot be pointed at a separate header (see task 3 of
# docs/plans/20260728-native-bridge-tests.md), so the header the tests include is
# generated from the `.def` on every run. Its lifetime is one run and it lives
# under `build/`, which is already ignored by git, so the copies cannot drift.
#
# Usage: scripts/test-native.sh [--self-check] [name-prefix]

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)

DEF_FILE="$PROJECT_DIR/nativebridge/cinterop/harmon_native.def"
SOURCE_DIR="$PROJECT_DIR/test/native"
OUTPUT_DIR="$PROJECT_DIR/build/native-test"
HEADER_FILE="$OUTPUT_DIR/harmon_native.h"
BINARY_FILE="$OUTPUT_DIR/harmon-native-test"

if [ ! -f "$DEF_FILE" ]; then
    echo "native bridge definition not found at $DEF_FILE" >&2
    exit 2
fi

if [ ! -d "$SOURCE_DIR" ]; then
    echo "C test sources not found at $SOURCE_DIR" >&2
    exit 2
fi

mkdir -p "$OUTPUT_DIR"

# Everything after the `---` separator is the C body of the bridge.
sed '1,/^---$/d' "$DEF_FILE" > "$HEADER_FILE"

# The libraries come from the `.def` itself rather than from a copy kept in step
# by hand: a framework added there has to reach this link too, and a hand-written
# copy would only report the omission as an undefined symbol.
LINKER_OPTS=$(sed -n 's/^linkerOpts *= *//p' "$DEF_FILE")
if [ -z "$LINKER_OPTS" ]; then
    echo "no linkerOpts line in $DEF_FILE" >&2
    exit 2
fi

# `-Wall -Wextra -Werror` is the C counterpart of `allWarningsAsErrors` in
# module.yaml. $LINKER_OPTS is deliberately unquoted: it is a list of arguments.
clang \
    -std=c11 \
    -Wall \
    -Wextra \
    -Werror \
    -I"$OUTPUT_DIR" \
    -o "$BINARY_FILE" \
    "$SOURCE_DIR"/*.c \
    $LINKER_OPTS

exec "$BINARY_FILE" "$@"
