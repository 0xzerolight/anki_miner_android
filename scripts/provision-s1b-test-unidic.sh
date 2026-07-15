#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

if (($# != 2)) || [[ "$1" != "--dicdir" ]]; then
    echo "Usage: scripts/provision-s1b-test-unidic.sh --dicdir DIR" >&2
    exit 2
fi
dicdir="$(realpath "$2")"
serial="${ANDROID_SERIAL:-}"
[[ -n "$serial" ]] || {
    echo "S1b test provisioning requires ANDROID_SERIAL" >&2
    exit 2
}
ADB_COMMAND="${ANKI_MINER_ADB_COMMAND:-adb}"
ADB_COMMAND="$(command -v "$ADB_COMMAND" 2>/dev/null)" || {
    echo "S1b test provisioning requires an executable adb command" >&2
    exit 1
}
[[ "$("$ADB_COMMAND" -s "$serial" get-state 2>/dev/null)" == "device" ]] || {
    echo "S1b test provisioning requires online device $serial" >&2
    exit 1
}

archive="$(mktemp "${TMPDIR:-/tmp}/anki-miner-s1b-unidic.XXXXXX.zip")"
remote_staging_started=false
remote_ready=false
cleanup() {
    rm -f "$archive"
    if [[ "$remote_staging_started" == true && "$remote_ready" == false ]]; then
        "$ADB_COMMAND" -s "$serial" shell rm -f "$ANDROID_S1B_TEST_UNIDIC_ARCHIVE" \
            >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
python3.13 "$REPO_ROOT/tools/tokenizer/package_s1b_test_unidic.py" \
    --dicdir "$dicdir" \
    --golden "$REPO_ROOT/golden/engine-v1.json" \
    --output "$archive"

remote_staging_started=true
"$ADB_COMMAND" -s "$serial" shell rm -f "$ANDROID_S1B_TEST_UNIDIC_ARCHIVE"
"$ADB_COMMAND" -s "$serial" push "$archive" "$ANDROID_S1B_TEST_UNIDIC_ARCHIVE"
"$ADB_COMMAND" -s "$serial" shell chmod 0644 "$ANDROID_S1B_TEST_UNIDIC_ARCHIVE"
remote_ready=true
echo "S1b external test UniDic staged at $ANDROID_S1B_TEST_UNIDIC_ARCHIVE"
