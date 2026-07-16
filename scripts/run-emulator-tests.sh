#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=emulator-lanes.sh
source "$SCRIPT_DIR/emulator-lanes.sh"
# shellcheck source=android-test-resources.sh
source "$SCRIPT_DIR/android-test-resources.sh"

BOOT_TIMEOUT_SECONDS="${EMULATOR_BOOT_TIMEOUT_SECONDS:-1200}"
ADB_TIMEOUT_SECONDS="${ANKI_MINER_ADB_TIMEOUT_SECONDS:-15}"
IDENTITY_TIMEOUT_SECONDS="${ANKI_MINER_IDENTITY_TIMEOUT_SECONDS:-90}"
CONNECTED_TIMEOUT_SECONDS="${ANKI_MINER_CONNECTED_TIMEOUT_SECONDS:-1800}"
EMULATOR_LAUNCHER="${ANKI_MINER_EMULATOR_LAUNCHER:-$SCRIPT_DIR/emulator.sh}"
CONNECTED_RUNNER="${ANKI_MINER_CONNECTED_EMULATOR_RUNNER:-$SCRIPT_DIR/run-connected-emulator-tests.sh}"
S2_CONNECTED_RUNNER="${ANKI_MINER_S2_CONNECTED_RUNNER:-$SCRIPT_DIR/run-s2-ankidroid-probe.sh}"
RECEIPT_COMMAND="${ANKI_MINER_RECEIPT_COMMAND:-$SCRIPT_DIR/android_test_receipt.py}"
RECEIPT="${ANKI_MINER_ANDROID_TEST_RECEIPT:-}"
S2_MODE=false
EMULATOR_LANE=4k
LANE_SELECTOR=""
TEST_UNIDIC_DIR="${ANKI_MINER_TEST_UNIDIC_DIR:-}"
EMULATOR_ARGS=()

usage() {
    cat <<'EOF'
Usage: scripts/run-emulator-tests.sh --receipt FILE --unidic-dir DIR
                                     [--lane api26|4k|16k]
                                     [--page-size 4k|16k]
                                     [emulator options]
       scripts/run-emulator-tests.sh --s2 --receipt FILE

Starts one deterministic headless emulator, verifies its AVD identity and page
size, API level, and pinned fingerprint where applicable, runs the adb-only
connected gate against a host-prepared receipt, then always stops the emulator
it started. --page-size is a backward-compatible alias for the API 36 lanes.
EOF
}

while (($#)); do
    case "$1" in
        --receipt)
            (($# >= 2)) || { usage >&2; exit 2; }
            RECEIPT="$2"
            shift
            ;;
        --s2)
            [[ "$S2_MODE" == false ]] || { usage >&2; exit 2; }
            S2_MODE=true
            ;;
        --lane)
            (($# >= 2)) || { usage >&2; exit 2; }
            if [[ -n "$LANE_SELECTOR" ]]; then
                echo "Use exactly one --lane or --page-size selector." >&2
                exit 2
            fi
            LANE_SELECTOR=lane
            EMULATOR_LANE="$2"
            shift
            ;;
        --page-size)
            (($# >= 2)) || { usage >&2; exit 2; }
            if [[ -n "$LANE_SELECTOR" ]]; then
                echo "Use exactly one --lane or --page-size selector." >&2
                exit 2
            fi
            case "$2" in
                4k|16k) ;;
                *)
                    echo "--page-size accepts only 4k or 16k." >&2
                    exit 2
                    ;;
            esac
            LANE_SELECTOR=page-size
            EMULATOR_LANE="$2"
            shift
            ;;
        --lane=*|--page-size=*)
            echo "Lane selectors require a separate value argument." >&2
            exit 2
            ;;
        --unidic-dir)
            (($# >= 2)) || { usage >&2; exit 2; }
            TEST_UNIDIC_DIR="$2"
            shift
            ;;
        --keep)
            echo "Emulators are always stopped after the focused test run." >&2
            exit 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            EMULATOR_ARGS+=("$1")
            ;;
    esac
    shift
done

if [[ "$S2_MODE" == true ]]; then
    if [[ -n "$LANE_SELECTOR" && "$EMULATOR_LANE" != 4k ]]; then
        echo "S2 is pinned to the 4k emulator lane." >&2
        exit 2
    fi
    EMULATOR_LANE=4k
fi
for timeout_value in "$BOOT_TIMEOUT_SECONDS" "$ADB_TIMEOUT_SECONDS" \
    "$IDENTITY_TIMEOUT_SECONDS" "$CONNECTED_TIMEOUT_SECONDS"; do
    [[ "$timeout_value" =~ ^[1-9][0-9]*$ ]] || {
        echo "Emulator and adb timeouts must be positive numbers of seconds." >&2
        exit 2
    }
done

resolve_android_emulator_lane "$EMULATOR_LANE"
AVD_NAME="$ANDROID_LANE_AVD_NAME"
EMULATOR_SERIAL="$ANDROID_LANE_EMULATOR_SERIAL"

started_emulator=false
emulator_pid=""

stop_emulator() {
    if [[ "$started_emulator" == true ]]; then
        timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
            adb -s "$EMULATOR_SERIAL" emu kill >/dev/null 2>&1 || true
        for _ in {1..30}; do
            kill -0 "$emulator_pid" 2>/dev/null || break
            sleep 0.5
        done
        if kill -0 "$emulator_pid" 2>/dev/null; then
            kill -TERM "$emulator_pid" 2>/dev/null || true
            for _ in {1..10}; do
                kill -0 "$emulator_pid" 2>/dev/null || break
                sleep 0.5
            done
        fi
        if kill -0 "$emulator_pid" 2>/dev/null; then
            kill -KILL "$emulator_pid" 2>/dev/null || true
        fi
        wait "$emulator_pid" 2>/dev/null || true
    fi
}
trap stop_emulator EXIT

[[ -n "$RECEIPT" && -f "$RECEIPT" ]] || {
    echo "A host-prepared receipt is required via --receipt." >&2
    exit 2
}
RECEIPT="$(realpath "$RECEIPT")"
anki_miner_require_no_gradle
anki_miner_require_emulator_capacity
"$RECEIPT_COMMAND" validate \
    --repo-root "$(cd "$SCRIPT_DIR/.." && pwd)" \
    --receipt "$RECEIPT"
if [[ "$S2_MODE" == true ]]; then
    [[ "${ANKI_MINER_S2_ALLOW_COLLECTION_RESET:-}" == true ]] || {
        echo "S2 requires ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true." >&2
        exit 2
    }
    s2_apk="${ANKI_MINER_ANKIDROID_APK:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/ankidroid/v2.24.0/variant-abi-AnkiDroid-2.24.0-x86_64.apk}"
    [[ -f "$s2_apk" ]] || { echo "Pinned AnkiDroid APK is missing: $s2_apk" >&2; exit 1; }
    "$RECEIPT_COMMAND" validate \
        --repo-root "$(cd "$SCRIPT_DIR/.." && pwd)" \
        --receipt "$RECEIPT" \
        --require-s2 \
        --ankidroid-apk "$(realpath "$s2_apk")" \
        --s2-reset-opt-in
fi

adb_output="$(timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" adb devices)" || {
    echo "Could not list ADB devices." >&2
    exit 1
}
emulator_state="$(awk -v serial="$EMULATOR_SERIAL" \
    '$1 == serial { print $2; exit }' <<<"$adb_output")"
if [[ -n "$emulator_state" ]]; then
    if [[ "$emulator_state" == "device" ]]; then
        echo "$EMULATOR_SERIAL is already online; test runs require a newly wiped emulator." >&2
    else
        echo "$EMULATOR_SERIAL already exists in ADB state '$emulator_state'; test runs require a newly wiped emulator." >&2
    fi
    echo "Stop it first with: adb -s $EMULATOR_SERIAL emu kill" >&2
    exit 1
fi
mapfile -t preexisting_adb_targets < <(awk 'NR > 1 && NF >= 2 { print $1 " (" $2 ")" }' <<<"$adb_output")
if [[ "${#preexisting_adb_targets[@]}" -ne 0 ]]; then
    echo "Test runs require no pre-existing ADB targets; found: ${preexisting_adb_targets[*]}" >&2
    exit 1
fi
if [[ "$S2_MODE" == false ]]; then
    [[ -n "$TEST_UNIDIC_DIR" && -d "$TEST_UNIDIC_DIR" ]] || {
        echo "A golden-pinned UniDic directory is required via --unidic-dir." >&2
        exit 2
    }
    ANKI_MINER_TEST_UNIDIC_DIR="$(realpath "$TEST_UNIDIC_DIR")"
    export ANKI_MINER_TEST_UNIDIC_DIR
fi
echo "Starting $AVD_NAME as $EMULATOR_SERIAL"
"$EMULATOR_LAUNCHER" \
    --headless \
    --test-session \
    --lane "$EMULATOR_LANE" \
    "${EMULATOR_ARGS[@]}" \
    >"$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator-$EMULATOR_LANE.log" 2>&1 &
emulator_pid="$!"
started_emulator=true

deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while ((SECONDS < deadline)); do
    if [[ "$(
        timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
            adb -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null \
            | tr -d '\r'
    )" == "1" ]]; then
        break
    fi
    if [[ "$started_emulator" == true ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "Emulator exited before boot; see $ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator-$EMULATOR_LANE.log" >&2
        exit 1
    fi
    sleep 5
done

if [[ "$(
    timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
        adb -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null \
        | tr -d '\r'
)" != "1" ]]; then
    echo "Emulator did not boot within $BOOT_TIMEOUT_SECONDS seconds." >&2
    exit 1
fi

timeout --kill-after=2s "${IDENTITY_TIMEOUT_SECONDS}s" \
    "$SCRIPT_DIR/verify-emulator-runtime.sh" --lane "$EMULATOR_LANE"

timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
    adb -s "$EMULATOR_SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
if [[ "$S2_MODE" == true ]]; then
    timeout --kill-after=2s "${CONNECTED_TIMEOUT_SECONDS}s" \
        "$S2_CONNECTED_RUNNER" --receipt "$RECEIPT"
else
    timeout --kill-after=2s "${CONNECTED_TIMEOUT_SECONDS}s" \
        "$CONNECTED_RUNNER" \
            --receipt "$RECEIPT" \
            --unidic-dir "$ANKI_MINER_TEST_UNIDIC_DIR" \
            --lane "$EMULATOR_LANE"
fi
