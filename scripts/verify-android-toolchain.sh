#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

python3.13 "$SCRIPT_DIR/verify_android_toolchain.py" \
    --sdk-root "$ANDROID_HOME" \
    --installed-list "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/installed-packages.txt" \
    --avd-home "$ANDROID_AVD_HOME" \
    --lock "$SCRIPT_DIR/android-sdk-packages.lock" \
    --avd "$ANDROID_AVD_API26_NAME|$ANDROID_SYSTEM_IMAGE_API26|pixel_6|Google" \
    --avd "$ANDROID_AVD_4K_NAME|$ANDROID_SYSTEM_IMAGE_4K|pixel_6|Google" \
    --avd "$ANDROID_AVD_16K_NAME|$ANDROID_SYSTEM_IMAGE_16K|pixel_6|Google"
