#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=instrumentation-result.sh
source "$SCRIPT_DIR/instrumentation-result.sh"

ADB_COMMAND="${ANKI_MINER_ADB_COMMAND:-adb}"
GRADLEW_COMMAND="${ANKI_MINER_GRADLEW_COMMAND:-$REPO_ROOT/gradlew}"
APKANALYZER_COMMAND="${ANKI_MINER_APKANALYZER_COMMAND:-apkanalyzer}"
NATIVE_CHECKER="${ANKI_MINER_NATIVE_CHECKER:-$SCRIPT_DIR/check-native-artifact.sh}"
PROVISIONER="${ANKI_MINER_S1A_PROVISIONER:-$SCRIPT_DIR/provision-tokenizer-test-unidic.sh}"
WHEEL_TOOL="${ANKI_MINER_S1A_WHEEL_TOOL:-$REPO_ROOT/tools/wheels/s1a_wheels.py}"
PYTHON_COMMAND="${ANKI_MINER_PYTHON_COMMAND:-python3.13}"
APP_APK="${ANKI_MINER_S1A_APP_APK:-$REPO_ROOT/app/build/outputs/apk/device/debug/app-device-debug.apk}"
TEST_APK="${ANKI_MINER_S1A_TEST_APK:-$REPO_ROOT/app/build/outputs/apk/androidTest/device/debug/\
app-device-debug-androidTest.apk}"
TEST_CLASS="com.ankiminer.android.TokenizerS1aInstrumentedTest"
TEST_RUNNER="com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner"
TEST_UNIDIC_ARCHIVE="/data/local/tmp/anki-miner-tokenizer-unidic.zip"

usage() {
    cat <<'EOF'
Usage: scripts/run-s1a-arm64-tests.sh \
    --serial SERIAL \
    --manifest FILE \
    --unidic-dir DIR \
    --page-size 4k|16k \
    --image-fingerprint FINGERPRINT

Runs the S1a Fugashi parity class on one explicitly named arm64 target.
The runner never starts, stops, selects, or accepts an arbitrary device.
EOF
}

fail() {
    echo "S1a arm64 test: $*" >&2
    exit 1
}

serial=""
manifest=""
dicdir=""
page_lane=""
expected_fingerprint=""
seen_serial=false
seen_manifest=false
seen_dicdir=false
seen_page=false
seen_fingerprint=false

if (($# == 1)) && [[ "$1" == "--help" || "$1" == "-h" ]]; then
    usage
    exit 0
fi

while (($#)); do
    option="$1"
    shift
    case "$option" in
        --serial)
            [[ "$seen_serial" == false && $# -ge 1 ]] || { usage >&2; exit 2; }
            serial="$1"
            seen_serial=true
            shift
            ;;
        --manifest)
            [[ "$seen_manifest" == false && $# -ge 1 ]] || { usage >&2; exit 2; }
            manifest="$1"
            seen_manifest=true
            shift
            ;;
        --unidic-dir)
            [[ "$seen_dicdir" == false && $# -ge 1 ]] || { usage >&2; exit 2; }
            dicdir="$1"
            seen_dicdir=true
            shift
            ;;
        --page-size)
            [[ "$seen_page" == false && $# -ge 1 ]] || { usage >&2; exit 2; }
            page_lane="$1"
            seen_page=true
            shift
            ;;
        --image-fingerprint)
            [[ "$seen_fingerprint" == false && $# -ge 1 ]] || { usage >&2; exit 2; }
            expected_fingerprint="$1"
            seen_fingerprint=true
            shift
            ;;
        *)
            echo "Unknown argument: $option" >&2
            usage >&2
            exit 2
            ;;
    esac
done

[[ "$seen_serial" == true && "$seen_manifest" == true && "$seen_dicdir" == true \
    && "$seen_page" == true && "$seen_fingerprint" == true ]] || {
    usage >&2
    exit 2
}
[[ "$serial" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]] || {
    echo "Serial must be an explicit ADB serial without whitespace or options." >&2
    exit 2
}
[[ -n "$expected_fingerprint" && "$expected_fingerprint" != *[[:space:]]* ]] || {
    echo "Image fingerprint must be one non-whitespace value." >&2
    exit 2
}
case "$page_lane" in
    4k)
        expected_page_size=4096
        ;;
    16k)
        expected_page_size=16384
        ;;
    *)
        echo "Page size must be 4k or 16k." >&2
        exit 2
        ;;
esac
[[ -f "$manifest" ]] || {
    echo "A verified S1a wheel manifest is required." >&2
    exit 2
}
[[ -d "$dicdir" ]] || {
    echo "A golden-pinned UniDic directory is required." >&2
    exit 2
}
manifest="$(realpath "$manifest")"
dicdir="$(realpath "$dicdir")"

resolve_command() {
    local command_name="$1"
    local label="$2"
    local resolved
    resolved="$(command -v "$command_name" 2>/dev/null)" || fail "$label is unavailable: $command_name"
    [[ -x "$resolved" ]] || fail "$label is not executable: $resolved"
    printf '%s\n' "$resolved"
}

ADB_COMMAND="$(resolve_command "$ADB_COMMAND" adb)"
GRADLEW_COMMAND="$(resolve_command "$GRADLEW_COMMAND" Gradle)"
APKANALYZER_COMMAND="$(resolve_command "$APKANALYZER_COMMAND" apkanalyzer)"
NATIVE_CHECKER="$(resolve_command "$NATIVE_CHECKER" 'native artifact checker')"
PROVISIONER="$(resolve_command "$PROVISIONER" 'S1a UniDic provisioner')"
WHEEL_TOOL="$(resolve_command "$WHEEL_TOOL" 'S1a wheel tool')"
PYTHON_COMMAND="$(resolve_command "$PYTHON_COMMAND" 'Python 3.13')"

"$WHEEL_TOOL" verify-publication --manifest "$manifest" >/dev/null \
    || fail "S1a wheel publication is invalid or stale for the active builder"

device_state="$("$ADB_COMMAND" -s "$serial" get-state 2>/dev/null)" \
    || fail "cannot query target $serial"
[[ "$device_state" == "device" ]] || fail "$serial is not an online device"

read_property() {
    local property_name="$1"
    local value
    value="$("$ADB_COMMAND" -s "$serial" shell getprop "$property_name" 2>/dev/null)" \
        || fail "cannot read $property_name from $serial"
    printf '%s' "$value" | tr -d '\r'
}

boot_complete="$(read_property sys.boot_completed)"
[[ "$boot_complete" == "1" ]] || fail "$serial has not completed booting"
actual_abi="$(read_property ro.product.cpu.abi)"
[[ "$actual_abi" == "arm64-v8a" ]] \
    || fail "$serial ABI is ${actual_abi:-unknown}, expected arm64-v8a"
actual_api="$(read_property ro.build.version.sdk)"
[[ "$actual_api" == "$ANDROID_API_LEVEL" ]] \
    || fail "$serial API is ${actual_api:-unknown}, expected $ANDROID_API_LEVEL"
actual_page_size="$("$ADB_COMMAND" -s "$serial" shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')" \
    || fail "cannot read the page size from $serial"
[[ "$actual_page_size" == "$expected_page_size" ]] \
    || fail "$serial page size is ${actual_page_size:-unknown}, expected $expected_page_size"
actual_fingerprint="$(read_property ro.build.fingerprint)"
[[ "$actual_fingerprint" == "$expected_fingerprint" ]] \
    || fail "$serial image fingerprint does not match the requested image"

cd "$REPO_ROOT"
"$GRADLEW_COMMAND" \
    --no-daemon \
    --stacktrace \
    --dependency-verification strict \
    -PankiMinerS1aManifest="$manifest" \
    :app:assembleDeviceDebug \
    :app:assembleDeviceDebugAndroidTest

[[ -f "$APP_APK" ]] || fail "arm64 debug APK was not produced: $APP_APK"
[[ -f "$TEST_APK" ]] || fail "arm64 instrumentation APK was not produced: $TEST_APK"
"$NATIVE_CHECKER" \
    --artifact "$APP_APK" \
    --allow-abi arm64-v8a \
    --require-app-imy \
    --require-s1a \
    --s1a-manifest "$manifest"

app_id="$("$APKANALYZER_COMMAND" manifest application-id "$APP_APK")" \
    || fail "cannot inspect the arm64 debug APK identity"
[[ "$app_id" == "com.ankiminer.android" ]] \
    || fail "arm64 debug APK has unexpected application id $app_id"
test_id="$("$APKANALYZER_COMMAND" manifest application-id "$TEST_APK")" \
    || fail "cannot inspect the arm64 test APK identity"
[[ "$test_id" == "com.ankiminer.android.test" ]] \
    || fail "arm64 test APK has unexpected application id $test_id"
test_manifest="$("$APKANALYZER_COMMAND" manifest print "$TEST_APK")" \
    || fail "cannot inspect the arm64 test manifest"
grep -E "android:targetPackage([^=]*)?=['\"]com\.ankiminer\.android['\"]" \
    <<<"$test_manifest" >/dev/null \
    || fail "arm64 test APK targets the wrong application"
grep -E "android:name([^=]*)?=['\"]androidx\.test\.runner\.AndroidJUnitRunner['\"]" \
    <<<"$test_manifest" >/dev/null \
    || fail "arm64 test APK uses the wrong instrumentation runner"

staged_dictionary=false
cleanup_dictionary() {
    if [[ "$staged_dictionary" == true ]]; then
        "$ADB_COMMAND" -s "$serial" shell rm -f "$TEST_UNIDIC_ARCHIVE" \
            >/dev/null 2>&1 || true
    fi
}
trap cleanup_dictionary EXIT

staged_dictionary=true
ANKI_MINER_ADB_COMMAND="$ADB_COMMAND" \
    ANKI_MINER_PYTHON_COMMAND="$PYTHON_COMMAND" \
    ANDROID_SERIAL="$serial" \
    "$PROVISIONER" --dicdir "$dicdir"
"$ADB_COMMAND" -s "$serial" install -r -t "$APP_APK"
"$ADB_COMMAND" -s "$serial" install -r -t "$TEST_APK"
"$ADB_COMMAND" -s "$serial" shell am force-stop "$app_id" \
    || fail "cannot stop $app_id before isolated S1a instrumentation"

instrumentation_output="$(
    "$ADB_COMMAND" -s "$serial" shell am instrument -w -r \
        -e ankiMinerExpectedTokenizerPath engine_shared_tagger \
        -e class "$TEST_CLASS" \
        "$TEST_RUNNER" 2>&1
)" || fail "S1a arm64 instrumentation command failed"
printf '%s\n' "$instrumentation_output"
android_instrumentation_output_passed "$instrumentation_output" 1 \
    || fail "S1a arm64 instrumentation did not pass its parity test"

echo "S1a arm64 parity: OK ($serial, API $actual_api, ${actual_page_size}-byte pages)"
