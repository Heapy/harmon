#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd -P)

if [ "$(/usr/bin/id -u)" -eq 0 ]; then
    echo "Run this uninstaller as the login user; harmon uninstall requests sudo once." >&2
    exit 1
fi

if [ -x "$PROJECT_DIR/kotlin" ]; then
    TOOLCHAIN="$PROJECT_DIR/kotlin"
elif command -v kotlin >/dev/null 2>&1; then
    TOOLCHAIN=$(command -v kotlin)
else
    echo "Kotlin Toolchain is not installed and the project wrapper is missing." >&2
    exit 1
fi

echo "Building the paired release binaries..."
(
    cd "$PROJECT_DIR"
    "$TOOLCHAIN" build --variant release
)

set -- "$PROJECT_DIR"/build/tasks/*_linkMacosArm64Release/harmon.kexe
if [ "$#" -ne 1 ] || [ ! -x "$1" ]; then
    echo "Expected exactly one release harmon.kexe under build/tasks." >&2
    exit 1
fi

echo "Delegating removal to harmon uninstall..."
exec "$1" uninstall
