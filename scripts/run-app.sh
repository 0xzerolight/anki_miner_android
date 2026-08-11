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
EMULATOR_LOG="${ANKI_MINER_EMULATOR_LOG:-/tmp/anki-miner-emulator.log}"
EMULATOR_BOOT_TIMEOUT_SECONDS="${ANKI_MINER_EMULATOR_BOOT_TIMEOUT_SECONDS:-600}"
EMULATOR_BOOT_POLL_SECONDS="${ANKI_MINER_EMULATOR_BOOT_POLL_SECONDS:-2}"
EMULATOR_ADB_TIMEOUT_SECONDS="${ANKI_MINER_EMULATOR_ADB_TIMEOUT_SECONDS:-5}"

[[ "$EMULATOR_BOOT_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || {
    echo "ANKI_MINER_EMULATOR_BOOT_TIMEOUT_SECONDS must be a positive integer." >&2
    exit 2
}
[[ "$EMULATOR_ADB_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || {
    echo "ANKI_MINER_EMULATOR_ADB_TIMEOUT_SECONDS must be a positive integer." >&2
    exit 2
}
[[ "$EMULATOR_BOOT_POLL_SECONDS" =~ ^([1-9][0-9]*([.][0-9]+)?|0[.][0-9]*[1-9][0-9]*)$ ]] || {
    echo "ANKI_MINER_EMULATOR_BOOT_POLL_SECONDS must be a positive number." >&2
    exit 2
}
command -v timeout >/dev/null 2>&1 || {
    echo "timeout is required to monitor emulator boot." >&2
    exit 1
}

echo "Building $VARIANT before starting an emulator ..."
anki_miner_run_gradle ./gradlew "${gradle_args[@]}" ":app:assemble$VARIANT"
[[ -f "$APK" ]] || {
    echo "Expected APK was not produced: $APK" >&2
    exit 1
}

anki_miner_require_no_gradle
anki_miner_acquire_workload_lock
anki_miner_acquire_emulator_lock
if anki_miner_emulator_is_running; then
    echo "An emulator started during the build; refusing to start another." >&2
    exit 1
fi

echo "Starting emulator $ANDROID_AVD_API26_NAME ..."
window_args=()
[[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]] && window_args=(-no-window)
emulator_pid=""
emulator_owned=false
emulator_process_is_running() {
    local running_pid

    while IFS= read -r running_pid; do
        if [[ "$running_pid" == "$emulator_pid" ]]; then
            return 0
        fi
    done < <(jobs -pr)
    return 1
}

cleanup_owned_emulator() {
    local exit_status=$?

    if [[ "$emulator_owned" == true ]]; then
        if emulator_process_is_running; then
            kill "$emulator_pid" 2>/dev/null || true
        fi
        wait "$emulator_pid" 2>/dev/null || true
    fi
    if ((exit_status != 0)); then
        echo "Emulator startup failed; log preserved at $EMULATOR_LOG" >&2
    fi
    return "$exit_status"
}
trap cleanup_owned_emulator EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

nohup "$ANDROID_HOME/emulator/emulator" \
    -avd "$ANDROID_AVD_API26_NAME" \
    -port "$ANDROID_EMULATOR_API26_PORT" \
    -no-snapshot -no-audio -gpu swiftshader_indirect \
    "${window_args[@]}" >"$EMULATOR_LOG" 2>&1 &
emulator_pid=$!
emulator_owned=true

echo "Waiting for boot to finish ..."
boot_deadline=$((SECONDS + EMULATOR_BOOT_TIMEOUT_SECONDS))
while true; do
    if ! emulator_process_is_running; then
        emulator_exit_status=0
        wait "$emulator_pid" || emulator_exit_status=$?
        emulator_owned=false
        echo \
            "Emulator process exited before boot completed (status $emulator_exit_status)." \
            >&2
        exit 1
    fi

    device_state="$(
        timeout --foreground "$EMULATOR_ADB_TIMEOUT_SECONDS" \
            adb -s "$SERIAL" get-state 2>/dev/null || true
    )"
    if [[ "$device_state" == "device" ]]; then
        boot_completed="$(
            timeout --foreground "$EMULATOR_ADB_TIMEOUT_SECONDS" \
                adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null \
                | tr -d '\r' || true
        )"
        if [[ "$boot_completed" == "1" ]]; then
            break
        fi
    fi

    if ((SECONDS >= boot_deadline)); then
        echo \
            "Emulator did not boot within $EMULATOR_BOOT_TIMEOUT_SECONDS seconds." \
            >&2
        exit 1
    fi
    sleep "$EMULATOR_BOOT_POLL_SECONDS"
done

echo "Installing the prebuilt $VARIANT APK ..."
if ! adb -s "$SERIAL" install -r "$APK"; then
    echo "Install failed. A different signing key may already be installed." >&2
    echo "App data was preserved; uninstall it manually only if losing that data is acceptable." >&2
    exit 1
fi

echo "Launching $APP_ID ..."
adb -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
emulator_owned=false
trap - EXIT INT TERM
echo "Anki Miner is running on $SERIAL. Stop the emulator with: adb -s $SERIAL emu kill"
