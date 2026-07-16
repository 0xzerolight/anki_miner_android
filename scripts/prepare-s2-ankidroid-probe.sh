#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

RECEIPT="${ANKI_MINER_ANDROID_TEST_RECEIPT:-}"
APK="${ANKI_MINER_ANKIDROID_APK:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/ankidroid/v2.24.0/variant-abi-AnkiDroid-2.24.0-x86_64.apk}"
PREPARER="${ANKI_MINER_EMULATOR_PREPARER:-$SCRIPT_DIR/prepare-emulator-tests.sh}"

if (($# == 2)) && [[ "$1" == --receipt ]]; then
    RECEIPT="$2"
elif (($#)); then
    echo "Usage: scripts/prepare-s2-ankidroid-probe.sh --receipt FILE" >&2
    exit 2
fi
[[ -n "$RECEIPT" ]] || {
    echo "Usage: scripts/prepare-s2-ankidroid-probe.sh --receipt FILE" >&2
    exit 2
}
if [[ "${ANKI_MINER_S2_ALLOW_COLLECTION_RESET:-}" != true ]]; then
    echo "S2 preparation requires ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true." >&2
    exit 2
fi

"$PREPARER" \
    --receipt "$RECEIPT" \
    --ankidroid-apk "$APK" \
    --s2-reset-opt-in
