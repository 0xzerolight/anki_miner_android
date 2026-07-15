#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

if (($# != 2)) || [[ "$1" != "--dicdir" ]]; then
    echo "Usage: scripts/provision-tokenizer-test-unidic.sh --dicdir DIR" >&2
    exit 2
fi
serial="${ANDROID_SERIAL:?ANDROID_SERIAL must select the owned test target}"
archive="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/assets/tokenizer-test-unidic.zip"
python3.13 "$REPO_ROOT/tools/tokenizer/package_test_unidic.py" \
    --dicdir "$2" \
    --golden "$REPO_ROOT/golden/engine-v1.json" \
    --output "$archive"
adb -s "$serial" shell rm -f /data/local/tmp/anki-miner-tokenizer-unidic.zip
adb -s "$serial" push "$archive" /data/local/tmp/anki-miner-tokenizer-unidic.zip
adb -s "$serial" shell chmod 0644 /data/local/tmp/anki-miner-tokenizer-unidic.zip
