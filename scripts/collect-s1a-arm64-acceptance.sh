#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=instrumentation-result.sh
source "$SCRIPT_DIR/instrumentation-result.sh"
# shellcheck source=android-test-resources.sh
source "$SCRIPT_DIR/android-test-resources.sh"

ADB_COMMAND="${ANKI_MINER_ADB_COMMAND:-adb}"
PYTHON_COMMAND="${ANKI_MINER_PYTHON_COMMAND:-python3.13}"
APKANALYZER_COMMAND="${ANKI_MINER_APKANALYZER_COMMAND:-apkanalyzer}"
PARITY_RUNNER="${ANKI_MINER_S1A_PARITY_RUNNER:-$SCRIPT_DIR/run-s1a-arm64-tests.sh}"
COLLECTOR="${ANKI_MINER_S1A_ACCEPTANCE_COLLECTOR:-$REPO_ROOT/tools/wheels/s1a_acceptance_collect.py}"
APP_APK="${ANKI_MINER_S1A_APP_APK:-$REPO_ROOT/app/build/outputs/apk/device/debug/app-device-debug.apk}"
TEST_RUNNER="com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner"
ACCEPTANCE_CLASS="com.ankiminer.android.S1aAcceptanceInstrumentedTest"
NOVEL_DEVICE_PATH="/data/local/tmp/anki-miner-s1a-novel.txt"

usage() {
    cat <<'EOF'
Usage: scripts/collect-s1a-arm64-acceptance.sh \
    --serial SERIAL \
    --manifest FILE \
    --unidic-dir DIR \
    --page-size 4k|16k \
    --image-fingerprint FINGERPRINT \
    --novel FILE \
    --output FILE

Builds the exact S1a deviceDebug APK, proves tokenizer parity, then records
three fresh-process cold starts and one representative novel parse on physical
ARM64 hardware. FILE must contain at least 50,000 Japanese characters. The
receipt output must be outside the Git checkout.
EOF
}

fail() {
    echo "S1a acceptance collection: $*" >&2
    exit 1
}

resolve_command() {
    local command_name="$1"
    local label="$2"
    local resolved
    resolved="$(command -v "$command_name" 2>/dev/null)" || fail "$label is unavailable: $command_name"
    [[ -x "$resolved" ]] || fail "$label is not executable: $resolved"
    printf '%s\n' "$resolved"
}

serial=""
manifest=""
dicdir=""
page_lane=""
fingerprint=""
novel=""
output=""

if (($# == 1)) && [[ "$1" == "--help" || "$1" == "-h" ]]; then
    usage
    exit 0
fi

while (($#)); do
    option="$1"
    shift
    [[ $# -ge 1 ]] || { usage >&2; exit 2; }
    case "$option" in
        --serial) serial="$1" ;;
        --manifest) manifest="$1" ;;
        --unidic-dir) dicdir="$1" ;;
        --page-size) page_lane="$1" ;;
        --image-fingerprint) fingerprint="$1" ;;
        --novel) novel="$1" ;;
        --output) output="$1" ;;
        *) echo "Unknown argument: $option" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

[[ -n "$serial" && -n "$manifest" && -n "$dicdir" && -n "$page_lane" \
    && -n "$fingerprint" && -n "$novel" && -n "$output" ]] || {
    usage >&2
    exit 2
}
[[ "$serial" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]] \
    || fail "serial must be an explicit ADB serial"
[[ -f "$novel" ]] || fail "novel corpus is not a file: $novel"
[[ -f "$manifest" ]] || fail "S1a publication manifest is not a file: $manifest"
[[ -d "$dicdir" ]] || fail "UniDic directory is missing: $dicdir"

ADB_COMMAND="$(resolve_command "$ADB_COMMAND" adb)"
PYTHON_COMMAND="$(resolve_command "$PYTHON_COMMAND" 'Python 3.13')"
APKANALYZER_COMMAND="$(resolve_command "$APKANALYZER_COMMAND" apkanalyzer)"
PARITY_RUNNER="$(resolve_command "$PARITY_RUNNER" 'S1a parity runner')"
COLLECTOR="$(resolve_command "$COLLECTOR" 'S1a receipt collector')"

device_state="$("$ADB_COMMAND" -s "$serial" get-state 2>/dev/null)" \
    || fail "cannot query $serial"
[[ "$device_state" == "device" ]] || fail "$serial is not an online device"
kernel_qemu="$("$ADB_COMMAND" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"
boot_qemu="$("$ADB_COMMAND" -s "$serial" shell getprop ro.boot.qemu | tr -d '\r')"
[[ "$kernel_qemu" != "1" && "$boot_qemu" != "1" ]] \
    || fail "physical ARM64 hardware is required; $serial reports an emulator"

source_status="$(git -C "$REPO_ROOT" status --porcelain=v2 --untracked-files=all)"
[[ -z "$source_status" ]] \
    || fail "the receipt is source-bound and requires a clean Git checkout"

temporary="$(mktemp -d /tmp/anki-miner-s1a-acceptance.XXXXXX)"
novel_staged=false
cleanup() {
    if [[ "$novel_staged" == true ]]; then
        "$ADB_COMMAND" -s "$serial" shell rm -f "$NOVEL_DEVICE_PATH" >/dev/null 2>&1 || true
    fi
    rm -rf "$temporary"
}
trap cleanup EXIT

parity_output="$("$PARITY_RUNNER" \
    --serial "$serial" \
    --manifest "$manifest" \
    --unidic-dir "$dicdir" \
    --page-size "$page_lane" \
    --image-fingerprint "$fingerprint")" \
    || fail "tokenizer parity runner failed"
printf '%s\n' "$parity_output"
printf '%s\n' "$parity_output" > "$temporary/parity.log"
[[ -f "$APP_APK" ]] || fail "the tested deviceDebug APK is missing: $APP_APK"

anki_miner_require_no_gradle || fail "Gradle did not exit before device measurement"
novel_staged=true
"$ADB_COMMAND" -s "$serial" push "$novel" "$NOVEL_DEVICE_PATH" >/dev/null \
    || fail "cannot stage the representative novel"

run_acceptance() {
    local mode="$1"
    local log_path="$2"
    local instrumentation_output
    "$ADB_COMMAND" -s "$serial" shell am force-stop com.ankiminer.android \
        || fail "cannot force-stop the app before a fresh $mode measurement"
    instrumentation_output="$(
        "$ADB_COMMAND" -s "$serial" shell am instrument -w -r \
            -e ankiMinerRunS1aAcceptance true \
            -e ankiMinerS1aAcceptanceMode "$mode" \
            -e class "$ACCEPTANCE_CLASS" \
            "$TEST_RUNNER" 2>&1
    )" || fail "$mode instrumentation command failed"
    printf '%s\n' "$instrumentation_output"
    android_instrumentation_output_passed "$instrumentation_output" 1 \
        || fail "$mode instrumentation did not pass"
    printf '%s\n' "$instrumentation_output" > "$log_path"
}

for run in 1 2 3; do
    run_acceptance cold "$temporary/cold-$run.log"
done
run_acceptance workload "$temporary/workload.log"

"$PYTHON_COMMAND" "$COLLECTOR" \
    --repo-root "$REPO_ROOT" \
    --manifest "$manifest" \
    --golden "$REPO_ROOT/golden/engine-v1.json" \
    --apk "$APP_APK" \
    --serial "$serial" \
    --adb "$ADB_COMMAND" \
    --apkanalyzer "$APKANALYZER_COMMAND" \
    --parity-log "$temporary/parity.log" \
    --cold-log "$temporary/cold-1.log" \
    --cold-log "$temporary/cold-2.log" \
    --cold-log "$temporary/cold-3.log" \
    --workload-log "$temporary/workload.log" \
    --output "$output"

echo "S1a physical ARM64 acceptance: OK ($serial, receipt $output)"
