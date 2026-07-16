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

SERIAL="${ANKI_MINER_S2_SERIAL:-emulator-5554}"
APK="${ANKI_MINER_ANKIDROID_APK:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/ankidroid/v2.24.0/variant-abi-AnkiDroid-2.24.0-x86_64.apk}"
RECEIPT="${ANKI_MINER_ANDROID_TEST_RECEIPT:-}"
RECEIPT_COMMAND="${ANKI_MINER_RECEIPT_COMMAND:-$SCRIPT_DIR/android_test_receipt.py}"
INSTRUMENTATION_TIMEOUT_SECONDS="${ANKI_MINER_INSTRUMENTATION_TIMEOUT_SECONDS:-900}"
ADB_TIMEOUT_SECONDS="${ANKI_MINER_ADB_TIMEOUT_SECONDS:-15}"
EXPECTED_SHA256="b8aaef8c8ed13e96b7bbafbc46e690490684192147ab445db8a193c4ef6989b0"
EXPECTED_CERT_SHA256="2071534f0f4b5e54ae952dd275d70da6e3459ee69909d2ab1b4843c4c5b21a45"

if (($# == 2)) && [[ "$1" == --receipt ]]; then
    RECEIPT="$2"
elif (($#)); then
    echo "Usage: scripts/run-s2-ankidroid-probe.sh --receipt FILE" >&2
    exit 2
fi

[[ "$SERIAL" == emulator-5554 ]] || { echo "S2 is pinned to the API 36 4K lane." >&2; exit 2; }
for timeout_value in "$INSTRUMENTATION_TIMEOUT_SECONDS" "$ADB_TIMEOUT_SECONDS"; do
    [[ "$timeout_value" =~ ^[1-9][0-9]*$ ]] || {
        echo "S2 adb and instrumentation timeouts must be positive numbers of seconds." >&2
        exit 2
    }
done
if [[ "${ANKI_MINER_S2_ALLOW_COLLECTION_RESET:-}" != true ]]; then
    echo "S2 requires a disposable emulator collection." >&2
    echo "It will delete exactly /storage/emulated/0/AnkiDroid on emulator-5554." >&2
    echo "Set ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true only after confirming that path is disposable." >&2
    exit 2
fi
[[ -n "$RECEIPT" && -f "$RECEIPT" ]] || {
    echo "S2 requires a receipt from prepare-s2-ankidroid-probe.sh." >&2
    exit 2
}
anki_miner_require_no_gradle
[[ -f "$APK" ]] || { echo "Pinned AnkiDroid APK is missing: $APK" >&2; exit 1; }
APK="$(realpath "$APK")"
RECEIPT="$(realpath "$RECEIPT")"
[[ "$(sha256sum "$APK" | awk '{print $1}')" == "$EXPECTED_SHA256" ]] || {
    echo "AnkiDroid APK SHA-256 mismatch." >&2
    exit 1
}
[[ "$(apkanalyzer manifest application-id "$APK")" == com.ichi2.anki ]] || {
    echo "AnkiDroid package mismatch." >&2
    exit 1
}
[[ "$(apkanalyzer manifest version-name "$APK")" == 2.24.0 ]] || {
    echo "AnkiDroid version name mismatch." >&2
    exit 1
}
[[ "$(apkanalyzer manifest version-code "$APK")" == 422400300 ]] || {
    echo "AnkiDroid version code mismatch." >&2
    exit 1
}
[[ "$(apkanalyzer manifest min-sdk "$APK")" == 24 ]] || {
    echo "AnkiDroid minimum SDK mismatch." >&2
    exit 1
}
actual_cert="$({ apksigner verify --print-certs "$APK"; } | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ "$actual_cert" == "$EXPECTED_CERT_SHA256" ]] || {
    echo "AnkiDroid signing certificate mismatch." >&2
    exit 1
}

"$RECEIPT_COMMAND" validate \
    --repo-root "$REPO_ROOT" \
    --receipt "$RECEIPT" \
    --require-s2 \
    --ankidroid-apk "$APK" \
    --s2-reset-opt-in

"$SCRIPT_DIR/verify-emulator-runtime.sh" --lane 4k

app_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
s2_log="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator-4k-s2.logcat.txt"
cleanup() {
    local status="$1"
    if ((status != 0)); then
        timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
            adb -s "$SERIAL" logcat -d >"$s2_log" 2>&1 || true
        echo "S2 failure logcat retained at $s2_log" >&2
    fi
}
trap 'status=$?; cleanup "$status"; exit "$status"' EXIT

anki_miner_require_no_gradle
adb -s "$SERIAL" uninstall com.ankiminer.android.test >/dev/null 2>&1 || true
adb -s "$SERIAL" uninstall com.ankiminer.android >/dev/null 2>&1 || true
adb -s "$SERIAL" uninstall com.ichi2.anki >/dev/null 2>&1 || true
# Uninstall does not remove AnkiDroid's legacy shared-storage collection. This
# lane is a dedicated disposable emulator; remove only its hard-coded test path.
adb -s "$SERIAL" shell rm -rf -- /storage/emulated/0/AnkiDroid
if ! adb -s "$SERIAL" shell test ! -e /storage/emulated/0/AnkiDroid; then
    echo "Dedicated emulator AnkiDroid test collection could not be removed." >&2
    exit 1
fi
adb -s "$SERIAL" install --no-streaming "$APK" >/dev/null
# A clean 2.24.0 install has not completed onboarding, so its provider still
# targets /storage/emulated/0/AnkiDroid. Simulate an already operational
# AnkiDroid install for this emulator-only capability probe.
adb -s "$SERIAL" shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow
if ! adb -s "$SERIAL" shell appops get com.ichi2.anki MANAGE_EXTERNAL_STORAGE \
    | tr -d '\r' \
    | grep -F 'MANAGE_EXTERNAL_STORAGE: allow' >/dev/null; then
    echo "AnkiDroid storage initialization app-op was not enabled." >&2
    exit 1
fi
echo "S2_ANKIDROID_STORAGE_PRECONDITION=emulator-only MANAGE_EXTERNAL_STORAGE:allow; simulates an initialized, operational AnkiDroid install"
adb -s "$SERIAL" install --no-streaming "$app_apk" >/dev/null
adb -s "$SERIAL" install --no-streaming "$test_apk" >/dev/null
adb -s "$SERIAL" shell pm grant \
    com.ankiminer.android com.ichi2.anki.permission.READ_WRITE_DATABASE
if ! adb -s "$SERIAL" shell dumpsys package com.ankiminer.android \
    | tr -d '\r' \
    | grep -F 'com.ichi2.anki.permission.READ_WRITE_DATABASE: granted=true' >/dev/null; then
    echo "AnkiDroid provider permission was not granted to the probe app." >&2
    exit 1
fi

adb -s "$SERIAL" logcat -c
instrumentation_output="$(
    timeout --kill-after=2s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        adb -s "$SERIAL" shell am instrument -w -r \
            -e ankiMinerRunS2 true \
            -e class com.ankiminer.android.anki.s2.AnkiDroidS2CapabilityInstrumentedTest \
            com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner
)" || {
    printf '%s\n' "$instrumentation_output" >&2
    echo "S2 AnkiDroid instrumentation command failed or timed out." >&2
    exit 1
}
printf '%s\n' "$instrumentation_output"
android_instrumentation_output_passed "$instrumentation_output" 1 || {
    echo "S2 AnkiDroid instrumentation failed." >&2
    exit 1
}
mapfile -t evidence_lines < <(
    adb -s "$SERIAL" logcat -d -s 'AnkiMinerS2:I' '*:S' \
        | grep -F 'ANKI_MINER_S2_PROBE=' || true
)
if [[ "${#evidence_lines[@]}" != 1 ]]; then
    echo "S2 probe emitted ${#evidence_lines[@]} evidence lines; expected exactly one." >&2
    exit 1
fi
printf '%s\n' "${evidence_lines[0]}"

echo "S2 AnkiDroid capability probe: OK"
