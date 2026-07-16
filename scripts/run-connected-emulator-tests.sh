#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=emulator-lanes.sh
source "$SCRIPT_DIR/emulator-lanes.sh"
# shellcheck source=instrumentation-result.sh
source "$SCRIPT_DIR/instrumentation-result.sh"
# shellcheck source=android-test-resources.sh
source "$SCRIPT_DIR/android-test-resources.sh"

RECEIPT="${ANKI_MINER_ANDROID_TEST_RECEIPT:-}"
EMULATOR_LANE=4k
LANE_SELECTOR=""
TEST_UNIDIC_DIR="${ANKI_MINER_TEST_UNIDIC_DIR:-}"
INSTRUMENTATION_TIMEOUT_SECONDS="${ANKI_MINER_INSTRUMENTATION_TIMEOUT_SECONDS:-900}"
ADB_TIMEOUT_SECONDS="${ANKI_MINER_ADB_TIMEOUT_SECONDS:-15}"
RECEIPT_COMMAND="${ANKI_MINER_RECEIPT_COMMAND:-$SCRIPT_DIR/android_test_receipt.py}"

usage() {
    cat <<'EOF' >&2
Usage: scripts/run-connected-emulator-tests.sh --receipt FILE --unidic-dir DIR
       [--lane api26|4k|16k] [--page-size 4k|16k]

Runs only adb/instrumentation work against an already-running owned emulator.
The receipt must come from prepare-emulator-tests.sh at the exact clean commit.
EOF
}

while (($#)); do
    case "$1" in
        --receipt)
            (($# >= 2)) || { usage; exit 2; }
            RECEIPT="$2"
            shift
            ;;
        --unidic-dir)
            (($# >= 2)) || { usage; exit 2; }
            TEST_UNIDIC_DIR="$2"
            shift
            ;;
        --lane)
            (($# >= 2)) || { usage; exit 2; }
            [[ -z "$LANE_SELECTOR" ]] || { usage; exit 2; }
            LANE_SELECTOR=lane
            EMULATOR_LANE="$2"
            shift
            ;;
        --page-size)
            (($# >= 2)) || { usage; exit 2; }
            [[ -z "$LANE_SELECTOR" ]] || { usage; exit 2; }
            case "$2" in 4k|16k) ;; *) usage; exit 2 ;; esac
            LANE_SELECTOR=page-size
            EMULATOR_LANE="$2"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
    shift
done

[[ -n "$RECEIPT" ]] || { usage; exit 2; }
[[ -n "$TEST_UNIDIC_DIR" && -d "$TEST_UNIDIC_DIR" ]] || {
    echo "A golden-pinned UniDic directory is required via --unidic-dir." >&2
    exit 2
}
[[ "$INSTRUMENTATION_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || {
    echo "Instrumentation timeout must be a positive number of seconds." >&2
    exit 2
}
[[ "$ADB_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || {
    echo "ADB timeout must be a positive number of seconds." >&2
    exit 2
}
resolve_android_emulator_lane "$EMULATOR_LANE"
export ANDROID_SERIAL="$ANDROID_LANE_EMULATOR_SERIAL"
TEST_UNIDIC_DIR="$(realpath "$TEST_UNIDIC_DIR")"
RECEIPT="$(realpath "$RECEIPT")"

fail() {
    echo "connected tests: $*" >&2
    exit 1
}

anki_miner_require_no_gradle || fail "Gradle must exit before connected tests"
"$RECEIPT_COMMAND" validate \
    --repo-root "$REPO_ROOT" \
    --receipt "$RECEIPT" \
    || fail "receipt validation failed"
"$SCRIPT_DIR/verify-emulator-runtime.sh" --lane "$EMULATOR_LANE" \
    || fail "connected emulator identity mismatch"

app_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
s1a_manifest="$(
    "$RECEIPT_COMMAND" field \
        --receipt "$RECEIPT" --name manifests.s1a.path
)"
staged_s1a_dictionary=false
staged_s1b_dictionary=false
connected_log="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator-$EMULATOR_LANE-connected.logcat.txt"

cleanup() {
    local status="$1"
    if [[ "$staged_s1a_dictionary" == true ]]; then
        timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
            adb -s "$ANDROID_SERIAL" shell rm -f /data/local/tmp/anki-miner-tokenizer-unidic.zip \
            >/dev/null 2>&1 || true
    fi
    if [[ "$staged_s1b_dictionary" == true ]]; then
        timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
            adb -s "$ANDROID_SERIAL" shell rm -f "$ANDROID_S1B_TEST_UNIDIC_ARCHIVE" \
            >/dev/null 2>&1 || true
    fi
    if ((status != 0)); then
        timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
            adb -s "$ANDROID_SERIAL" logcat -d >"$connected_log" 2>&1 || true
        echo "Connected-test logcat retained at $connected_log" >&2
    fi
}
trap 'status=$?; cleanup "$status"; exit "$status"' EXIT

run_instrumentation_any() {
    local label="$1"
    shift
    local output
    anki_miner_require_no_gradle || fail "Gradle started before $label instrumentation"
    output="$(
        timeout --kill-after=2s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
            adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
            "$@" \
            com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    )" || {
        printf '%s\n' "$output" >&2
        fail "$label instrumentation command failed or timed out"
    }
    printf '%s\n' "$output"
    android_instrumentation_output_passed_any "$output" \
        || fail "$label instrumentation did not report one clean terminal result"
}

run_instrumentation_exact() {
    local label="$1"
    local expected_count="$2"
    shift 2
    local output
    anki_miner_require_no_gradle || fail "Gradle started before $label instrumentation"
    output="$(
        timeout --kill-after=2s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
            adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
            "$@" \
            com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    )" || {
        printf '%s\n' "$output" >&2
        fail "$label instrumentation command failed or timed out"
    }
    printf '%s\n' "$output"
    android_instrumentation_output_passed "$output" "$expected_count" \
        || fail "$label instrumentation did not pass exactly $expected_count test(s)"
}

anki_miner_require_no_gradle || fail "Gradle started before artifact installation"
adb -s "$ANDROID_SERIAL" install -r "$app_apk" >/dev/null \
    || fail "cannot install the prepared app APK"
adb -s "$ANDROID_SERIAL" install -r -t "$test_apk" >/dev/null \
    || fail "cannot install the prepared test APK"
instrumentation="$(adb -s "$ANDROID_SERIAL" shell pm list instrumentation)" \
    || fail "cannot list installed instrumentation"
grep -Fqx \
    'instrumentation:com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner (target=com.ankiminer.android)' \
    <<<"$(tr -d '\r' <<<"$instrumentation")" \
    || fail "prepared instrumentation is not registered"

"$SCRIPT_DIR/provision-s1b-test-unidic.sh" --dicdir "$TEST_UNIDIC_DIR"
staged_s1b_dictionary=true
if [[ -n "$s1a_manifest" ]]; then
    "$SCRIPT_DIR/provision-tokenizer-test-unidic.sh" --dicdir "$TEST_UNIDIC_DIR"
    staged_s1a_dictionary=true
fi

adb -s "$ANDROID_SERIAL" shell am force-stop com.ankiminer.android \
    || fail "cannot stop app before combined instrumentation"
run_instrumentation_any combined

if [[ -n "$s1a_manifest" ]]; then
    adb -s "$ANDROID_SERIAL" shell am force-stop com.ankiminer.android \
        || fail "cannot stop app before S4 instrumentation"
    adb -s "$ANDROID_SERIAL" logcat -c \
        || fail "cannot clear logcat before S4 instrumentation"
    run_instrumentation_exact S4 1 \
        -e ankiMinerRunS4 true \
        -e ankiMinerExpectedFreshProcess true \
        -e class com.ankiminer.android.S4EngineSmokeInstrumentedTest
    metrics="$(
        adb -s "$ANDROID_SERIAL" logcat -d -s 'AnkiMinerS4:I' '*:S' \
            | grep -F 'S4_EMULATOR_METRICS ' \
            | tail -n 1
    )"
    [[ -n "$metrics" ]] || fail "isolated S4 instrumentation emitted no metrics"
    printf '%s\n' "$metrics"

    adb -s "$ANDROID_SERIAL" shell am force-stop com.ankiminer.android \
        || fail "cannot stop app before complete golden v2 instrumentation"
    run_instrumentation_exact golden-v2 1 \
        -e ankiMinerRunGoldenV2 true \
        -e class com.ankiminer.android.EngineGoldenV2InstrumentedTest
fi

adb -s "$ANDROID_SERIAL" shell am force-stop com.ankiminer.android \
    || fail "cannot stop app before S1b instrumentation"
run_instrumentation_exact S1b 2 \
    -e ankiMinerExpectedTokenizerPath engine_shared_tagger \
    -e class com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest

if [[ -n "$s1a_manifest" ]]; then
    adb -s "$ANDROID_SERIAL" shell am force-stop com.ankiminer.android \
        || fail "cannot stop app before S1a instrumentation"
    run_instrumentation_exact S1a 1 \
        -e ankiMinerExpectedTokenizerPath engine_shared_tagger \
        -e class com.ankiminer.android.TokenizerS1aInstrumentedTest
fi

echo "connected emulator tests ($EMULATOR_LANE): OK"
