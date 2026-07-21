#!/usr/bin/env bash
# One-command dev launcher: build without an emulator, then boot exactly one
# emulator, install the prebuilt APK without clearing app data, and start it.
# Usage:
#   scripts/run-app.sh            # emulatorDebug (fast, default)
#   scripts/run-app.sh release    # emulatorRelease (requires release identity/signing)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=android-test-resources.sh
source "$SCRIPT_DIR/android-test-resources.sh"
cd "$REPO_ROOT"

case "${1:-debug}" in
    debug)
        VARIANT=EmulatorDebug
        APK="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
        gradle_args=()
        ;;
    release)
        VARIANT=EmulatorRelease
        APK="$REPO_ROOT/app/build/outputs/apk/emulator/release/app-emulator-release.apk"
        source_commit="$(git rev-parse HEAD)"
        gradle_args=(-PankiMinerSourceCommit="$source_commit")
        ;;
    *) echo "usage: scripts/run-app.sh [debug|release]" >&2; exit 2 ;;
esac

SERIAL="$ANDROID_EMULATOR_API26_SERIAL"
APP_ID="com.ankiminer.android"

echo "Building $VARIANT before starting an emulator ..."
anki_miner_run_gradle ./gradlew "${gradle_args[@]}" ":app:assemble$VARIANT"
[[ -f "$APK" ]] || {
    echo "Expected APK was not produced: $APK" >&2
    exit 1
}

anki_miner_require_no_gradle
if anki_miner_emulator_is_running; then
    echo "An emulator started during the build; refusing to start another." >&2
    exit 1
fi
anki_miner_require_emulator_capacity

echo "Starting emulator $ANDROID_AVD_API26_NAME ..."
window_args=()
[[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]] && window_args=(-no-window)
nohup "$ANDROID_HOME/emulator/emulator" \
    -avd "$ANDROID_AVD_API26_NAME" \
    -port "$ANDROID_EMULATOR_API26_PORT" \
    -no-snapshot -no-audio -gpu swiftshader_indirect \
    "${window_args[@]}" >/tmp/anki-miner-emulator.log 2>&1 &
adb -s "$SERIAL" wait-for-device

echo "Waiting for boot to finish ..."
until [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
done

echo "Installing the prebuilt $VARIANT APK ..."
if ! adb -s "$SERIAL" install -r "$APK"; then
    echo "Install failed. A different signing key may already be installed." >&2
    echo "App data was preserved; uninstall it manually only if losing that data is acceptable." >&2
    adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    exit 1
fi

echo "Launching $APP_ID ..."
adb -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Anki Miner is running on $SERIAL. Stop the emulator with: adb -s $SERIAL emu kill"
