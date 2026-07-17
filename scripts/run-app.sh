#!/usr/bin/env bash
# One-command dev launcher: boot the emulator (if needed), build+install the
# app, and start it. Usage:
#   scripts/run-app.sh            # emulatorDebug (fast, default)
#   scripts/run-app.sh release    # emulatorRelease (R8 + release signing)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
cd "$REPO_ROOT"

case "${1:-debug}" in
    debug)   VARIANT=EmulatorDebug ;;
    release) VARIANT=EmulatorRelease ;;
    *) echo "usage: scripts/run-app.sh [debug|release]" >&2; exit 2 ;;
esac

SERIAL="$ANDROID_EMULATOR_API26_SERIAL"
APP_ID="com.ankiminer.android"

# Launch the emulator only if it is not already online (with a window unless
# there is no display, e.g. over SSH).
if ! adb devices | grep -q "^$SERIAL[[:space:]]*device$"; then
    echo "Starting emulator $ANDROID_AVD_API26_NAME ..."
    window_args=()
    [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]] && window_args=(-no-window)
    nohup "$ANDROID_HOME/emulator/emulator" \
        -avd "$ANDROID_AVD_API26_NAME" \
        -port "$ANDROID_EMULATOR_API26_PORT" \
        -no-snapshot -no-audio -gpu swiftshader_indirect \
        "${window_args[@]}" >/tmp/anki-miner-emulator.log 2>&1 &
    adb -s "$SERIAL" wait-for-device
fi

echo "Waiting for boot to finish ..."
until [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
done

echo "Building and installing $VARIANT ..."
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1 -Xmx2g"
ANDROID_SERIAL="$SERIAL" ./gradlew ":app:install$VARIANT" --console=plain --no-daemon

echo "Launching $APP_ID ..."
adb -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Anki Miner is running on $SERIAL. Stop the emulator with: adb -s $SERIAL emu kill"
