#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

"$SCRIPT_DIR/android-licenses.sh" check

sdkmanager="$ANDROID_CMDLINE_TOOLS_HOME/bin/sdkmanager"
[[ -x "$sdkmanager" ]] || {
    echo "Android command-line tools are missing; run scripts/provision-android.sh." >&2
    exit 1
}

mkdir -p "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"
available_staging="$(mktemp "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/sdkmanager-list.XXXXXX")"
trap 'rm -f "${available_staging:-}"' EXIT

LC_ALL=C "$sdkmanager" --sdk_root="$ANDROID_HOME" --channel=0 --list \
    >"$available_staging"
python3.13 "$SCRIPT_DIR/preflight_android_packages.py" \
    --lock "$SCRIPT_DIR/android-sdk-packages.lock" \
    --sdkmanager-list "$available_staging"
mv "$available_staging" "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/available-packages.txt"
available_staging=""

packages=(
    "platform-tools"
    "emulator"
    "platforms;android-$ANDROID_API_LEVEL"
    "build-tools;$ANDROID_BUILD_TOOLS_VERSION"
    "cmake;$ANDROID_CMAKE_VERSION"
    "ndk;$ANDROID_NDK_VERSION"
    "$ANDROID_SYSTEM_IMAGE_API26"
    "$ANDROID_SYSTEM_IMAGE_4K"
    "$ANDROID_SYSTEM_IMAGE_16K"
)

echo "Installing locked Android SDK packages"
"$sdkmanager" --sdk_root="$ANDROID_HOME" --channel=0 "${packages[@]}"
LC_ALL=C "$sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed \
    >"$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/installed-packages.txt"
