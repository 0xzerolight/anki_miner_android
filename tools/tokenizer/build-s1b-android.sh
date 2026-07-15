#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# The path is resolved from this script at runtime.
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/android-env.sh"

"$REPO_ROOT/scripts/android-licenses.sh" check || {
    echo "S1b Android build is blocked until the user accepts the SDK license." >&2
    exit 2
}
[[ -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]] || {
    echo "S1b Android build: locked NDK $ANDROID_NDK_VERSION is missing" >&2
    exit 1
}
[[ -x "$ANDROID_CMAKE_HOME/bin/cmake" ]] || {
    echo "S1b Android build: locked CMake $ANDROID_CMAKE_VERSION is missing" >&2
    exit 1
}

python3 "$SCRIPT_DIR/vendor_s1b_mecab.py" --check
cd "$REPO_ROOT"
./gradlew \
    --no-daemon \
    --stacktrace \
    --dependency-verification strict \
    :app:assembleEmulatorDebug \
    :app:assembleDeviceRelease

"$REPO_ROOT/scripts/check-native-artifact.sh" \
    --artifact "$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk" \
    --allow-abi x86_64 \
    --require-entry lib/x86_64/libanki_miner_mecab.so
"$REPO_ROOT/scripts/check-native-artifact.sh" \
    --artifact "$REPO_ROOT/app/build/outputs/apk/device/release/app-device-release-unsigned.apk" \
    --allow-abi arm64-v8a \
    --require-entry lib/arm64-v8a/libanki_miner_mecab.so

echo "S1b Android native builds: OK"
