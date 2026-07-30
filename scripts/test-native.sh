#!/bin/sh

# Compiles and runs the C test harness over the native bridge.
#
# The three bridges live entirely inside their own `.def` files; cinterop cannot
# be pointed at a portable checked-in header (CLAUDE.md, "How the native layer is
# tested"), so the headers the tests include are generated from the definitions
# on every run. Their lifetime is one run and they live under `build/`, which is
# already ignored by git, so the copies cannot drift.
#
# `--sanitize` builds the same sources under AddressSanitizer and
# UndefinedBehaviorSanitizer instead. That pass is not about any one check: it
# watches every allocation the bridge makes for an overflow, a use after free or
# a double free, which no assertion over return values can see. A one-byte
# `malloc` short of the terminator in `hm_receive_json_frame` leaves every check
# of the ordinary pass green and stops this pass with a `heap-buffer-overflow`
# reported inside that function.
# `-fno-sanitize-recover=all` is what makes an undefined-behaviour report fail the
# run rather than print and continue. Leaks are *not* covered — LeakSanitizer
# refuses to start on macOS ("detect_leaks is not supported on this platform"),
# which is why the two leaks that matter are measured by checks instead.
#
# Usage: scripts/test-native.sh [--sanitize] [--self-check] [name-prefix]

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)

# `--sanitize` is the script's own flag, so it is removed from what the harness
# sees; everything else is passed through untouched, including the flags the
# harness rejects.
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

# Everything after the `---` separator is the C body of its bridge.
sed '1,/^---$/d' "$IPC_DEF_FILE" > "$IPC_HEADER_FILE"
sed '1,/^---$/d' "$PROBE_DEF_FILE" > "$PROBE_HEADER_FILE"
sed '1,/^---$/d' "$HTTP_DEF_FILE" > "$HTTP_HEADER_FILE"

# The libraries come from the `.def` files themselves rather than from a copy
# kept in step by hand: a framework added there has to reach this link too, and
# a hand-written copy would only report the omission as an undefined symbol.
# IPC deliberately has no linkerOpts, so only the two non-empty definitions are
# required to contribute a line.
LINKER_OPTS=$(sed -n 's/^linkerOpts *= *//p' "$PROBE_DEF_FILE" "$HTTP_DEF_FILE")
if [ -z "$LINKER_OPTS" ]; then
    echo "no linkerOpts found in probe or HTTP bridge definitions" >&2
    exit 2
fi

# `-Wall -Wextra -Werror` is the C counterpart of `allWarningsAsErrors` in
# module.yaml. $LINKER_OPTS and $SANITIZER_OPTS are deliberately unquoted: both
# are lists of arguments, and the second one is empty in the ordinary pass.
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
