#!/bin/bash

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 VERSION SHA256 OUTPUT" >&2
    exit 2
fi

VERSION=$1
SHA256=$2
OUTPUT=$3
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
TEMPLATE="$PROJECT_DIR/packaging/homebrew/Formula/harmon.rb.in"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.+-][0-9A-Za-z.-]+)?$ ]]; then
    echo "Invalid Harmon version: $VERSION" >&2
    exit 2
fi
if [[ ! "$SHA256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid SHA-256: $SHA256" >&2
    exit 2
fi
if [[ ! -f "$TEMPLATE" ]]; then
    echo "Formula template is missing: $TEMPLATE" >&2
    exit 1
fi

/bin/mkdir -p "$(dirname "$OUTPUT")"
/usr/bin/sed \
    -e "s/@VERSION@/$VERSION/g" \
    -e "s/@SHA256@/$SHA256/g" \
    "$TEMPLATE" > "$OUTPUT"

if /usr/bin/grep -Eq '@(VERSION|SHA256)@' "$OUTPUT"; then
    echo "Generated formula still contains an unresolved token: $OUTPUT" >&2
    exit 1
fi

/usr/bin/ruby -c "$OUTPUT" >/dev/null
