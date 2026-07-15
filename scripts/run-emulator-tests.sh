#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=emulator-lanes.sh
source "$SCRIPT_DIR/emulator-lanes.sh"

BOOT_TIMEOUT_SECONDS="${EMULATOR_BOOT_TIMEOUT_SECONDS:-1200}"
EMULATOR_LAUNCHER="${ANKI_MINER_EMULATOR_LAUNCHER:-$SCRIPT_DIR/emulator.sh}"
HEALTH_SCRIPT="${ANKI_MINER_HEALTH_SCRIPT:-$SCRIPT_DIR/health.sh}"
KEEP_EMULATOR=false
EMULATOR_LANE=4k
LANE_SELECTOR=""
TEST_UNIDIC_DIR="${ANKI_MINER_TEST_UNIDIC_DIR:-}"
EMULATOR_ARGS=()

usage() {
    cat <<'EOF'
Usage: scripts/run-emulator-tests.sh --unidic-dir DIR [--lane api26|4k|16k]
                                     [--page-size 4k|16k] [--keep]
                                     [emulator options]

Starts one deterministic headless emulator, verifies its AVD identity and page
size, API level, and pinned fingerprint where applicable, provisions the
golden-pinned external UniDic, runs the connected health gate, then stops only
the emulator it started. --page-size is a backward-compatible alias for the
API 36 lanes. ANKI_MINER_TEST_UNIDIC_DIR may be used instead of --unidic-dir.
EOF
}

while (($#)); do
    case "$1" in
        --keep)
            KEEP_EMULATOR=true
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

resolve_android_emulator_lane "$EMULATOR_LANE"
AVD_NAME="$ANDROID_LANE_AVD_NAME"
EMULATOR_SERIAL="$ANDROID_LANE_EMULATOR_SERIAL"

started_emulator=false
emulator_pid=""

stop_emulator() {
    if [[ "$started_emulator" == true && "$KEEP_EMULATOR" != true ]]; then
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

adb_output="$(adb devices)" || {
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
[[ -n "$TEST_UNIDIC_DIR" && -d "$TEST_UNIDIC_DIR" ]] || {
    echo "A golden-pinned UniDic directory is required via --unidic-dir." >&2
    exit 2
}
ANKI_MINER_TEST_UNIDIC_DIR="$(realpath "$TEST_UNIDIC_DIR")"
export ANKI_MINER_TEST_UNIDIC_DIR
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
    if [[ "$(adb -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
    fi
    if [[ "$started_emulator" == true ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "Emulator exited before boot; see $ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator-$EMULATOR_LANE.log" >&2
        exit 1
    fi
    sleep 5
done

if [[ "$(adb -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    echo "Emulator did not boot within $BOOT_TIMEOUT_SECONDS seconds." >&2
    exit 1
fi

"$SCRIPT_DIR/verify-emulator-runtime.sh" --lane "$EMULATOR_LANE"

adb -s "$EMULATOR_SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
"$HEALTH_SCRIPT" --connected "$EMULATOR_LANE"
