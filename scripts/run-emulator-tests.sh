#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

BOOT_TIMEOUT_SECONDS="${EMULATOR_BOOT_TIMEOUT_SECONDS:-1200}"
KEEP_EMULATOR=false
EMULATOR_ARGS=(--headless)

if [[ "${1:-}" == "--keep" ]]; then
    KEEP_EMULATOR=true
    shift
fi
EMULATOR_ARGS+=("$@")

started_emulator=false
emulator_pid=""

stop_emulator() {
    if [[ "$started_emulator" == true && "$KEEP_EMULATOR" != true ]]; then
        adb -e emu kill >/dev/null 2>&1 || true
        if [[ -n "$emulator_pid" ]]; then
            wait "$emulator_pid" 2>/dev/null || true
        fi
    fi
}
trap stop_emulator EXIT

if ! adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { found = 1 } END { exit !found }'; then
    echo "Starting $ANDROID_AVD_NAME"
    "$SCRIPT_DIR/emulator.sh" "${EMULATOR_ARGS[@]}" \
        >"$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator.log" 2>&1 &
    emulator_pid="$!"
    started_emulator=true
fi

deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while ((SECONDS < deadline)); do
    if [[ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
    fi
    if [[ "$started_emulator" == true ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "Emulator exited before boot; see $ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator.log" >&2
        exit 1
    fi
    sleep 5
done

if [[ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    echo "Emulator did not boot within $BOOT_TIMEOUT_SECONDS seconds." >&2
    exit 1
fi

adb -e shell input keyevent 82 >/dev/null 2>&1 || true
"$SCRIPT_DIR/health.sh" --connected
