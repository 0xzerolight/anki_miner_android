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
STABLE_APK="${ANKI_MINER_ANKIDROID_APK:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/ankidroid/v2.24.0/variant-abi-AnkiDroid-2.24.0-x86_64.apk}"
CANARY_APK="${ANKI_MINER_ANKIDROID_CANARY_APK:-}"
CANARY_MANIFEST="${ANKI_MINER_ANKIDROID_CANARY_MANIFEST:-}"
CANARY_VERIFIER="${ANKI_MINER_ANKIDROID_CANARY_VERIFIER:-$SCRIPT_DIR/resolve_ankidroid_canary.py}"
RECEIPT="${ANKI_MINER_ANDROID_TEST_RECEIPT:-}"
RECEIPT_COMMAND="${ANKI_MINER_RECEIPT_COMMAND:-$SCRIPT_DIR/android_test_receipt.py}"
INSTRUMENTATION_TIMEOUT_SECONDS="${ANKI_MINER_INSTRUMENTATION_TIMEOUT_SECONDS:-900}"
ADB_TIMEOUT_SECONDS="${ANKI_MINER_ADB_TIMEOUT_SECONDS:-15}"

usage() {
    cat <<'EOF' >&2
Usage: scripts/run-s2-ankidroid-prerelease-canary.sh --receipt FILE

Runs the separately resolved official AnkiDroid prerelease capability canary on
the already-running owned API 36 4 KiB emulator. The exact candidate APK and
resolution manifest are required through ANKI_MINER_ANKIDROID_CANARY_APK and
ANKI_MINER_ANKIDROID_CANARY_MANIFEST. The stable S2 receipt and destructive
reset opt-in remain mandatory.
EOF
}

fail() {
    echo "S2 AnkiDroid prerelease canary: $*" >&2
    exit 1
}

if (($# == 2)) && [[ "$1" == --receipt ]]; then
    RECEIPT="$2"
elif (($#)); then
    usage
    exit 2
fi

[[ "$SERIAL" == emulator-5554 ]] || fail "canary is pinned to the API 36 4 KiB lane"
for timeout_value in "$INSTRUMENTATION_TIMEOUT_SECONDS" "$ADB_TIMEOUT_SECONDS"; do
    [[ "$timeout_value" =~ ^[1-9][0-9]*$ ]] || fail "timeouts must be positive seconds"
done
if [[ "${ANKI_MINER_S2_ALLOW_COLLECTION_RESET:-}" != true ]]; then
    echo "The prerelease canary deletes exactly /storage/emulated/0/AnkiDroid on emulator-5554." >&2
    echo "Set ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true only for that disposable lane." >&2
    exit 2
fi
[[ -n "$RECEIPT" && -f "$RECEIPT" ]] || { usage; exit 2; }
[[ -n "$CANARY_APK" && -f "$CANARY_APK" ]] || fail "resolved prerelease APK is missing"
[[ -n "$CANARY_MANIFEST" && -f "$CANARY_MANIFEST" ]] || fail "prerelease resolution manifest is missing"
[[ -f "$STABLE_APK" ]] || fail "stable receipt-bound AnkiDroid APK is missing"
[[ -x "$CANARY_VERIFIER" ]] || fail "prerelease verifier is unavailable: $CANARY_VERIFIER"

RECEIPT="$(realpath "$RECEIPT")"
STABLE_APK="$(realpath "$STABLE_APK")"
CANARY_APK="$(realpath "$CANARY_APK")"
CANARY_MANIFEST="$(realpath "$CANARY_MANIFEST")"
anki_miner_require_no_gradle || fail "Gradle must exit before connected work"

# Do not relax the immutable stable receipt path for a moving canary. It still
# proves the exact app/test APKs, stable AnkiDroid identity, and reset opt-in;
# the candidate has a separate GitHub-release/digest/certificate contract.
"$RECEIPT_COMMAND" validate \
    --repo-root "$REPO_ROOT" \
    --receipt "$RECEIPT" \
    --require-s2 \
    --ankidroid-apk "$STABLE_APK" \
    --s2-reset-opt-in \
    || fail "stable S2 receipt or destructive reset proof is stale"
identity="$("$CANARY_VERIFIER" verify-apk \
    --manifest "$CANARY_MANIFEST" \
    --apk "$CANARY_APK" \
    --apkanalyzer apkanalyzer \
    --apksigner apksigner)" \
    || fail "prerelease APK identity verification failed"
version_name="$("$CANARY_VERIFIER" field \
    --manifest "$CANARY_MANIFEST" --name release.version_name)" \
    || fail "cannot read the verified prerelease version name"
version_code="$(apkanalyzer manifest version-code "$CANARY_APK")" \
    || fail "cannot read the verified prerelease version code"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "verified prerelease version code is invalid"

"$SCRIPT_DIR/verify-emulator-runtime.sh" --lane 4k \
    || fail "connected emulator identity changed"
app_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
[[ -f "$app_apk" && -f "$test_apk" ]] || fail "receipt-bound app/test APKs are missing"
canary_log="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/emulator-4k-ankidroid-prerelease.logcat.txt"

cleanup() {
    local status="$1"
    if ((status != 0)); then
        timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
            adb -s "$SERIAL" logcat -d >"$canary_log" 2>&1 || true
        echo "Prerelease canary failure logcat retained at $canary_log" >&2
    fi
}
trap 'status=$?; cleanup "$status"; exit "$status"' EXIT

adb -s "$SERIAL" uninstall com.ankiminer.android.test >/dev/null 2>&1 || true
adb -s "$SERIAL" uninstall com.ankiminer.android >/dev/null 2>&1 || true
adb -s "$SERIAL" uninstall com.ichi2.anki >/dev/null 2>&1 || true
adb -s "$SERIAL" shell rm -rf -- /storage/emulated/0/AnkiDroid
adb -s "$SERIAL" shell test ! -e /storage/emulated/0/AnkiDroid \
    || fail "disposable AnkiDroid collection could not be reset"
adb -s "$SERIAL" install --no-streaming "$CANARY_APK" >/dev/null \
    || fail "cannot install the verified prerelease APK"
adb -s "$SERIAL" shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow
if ! adb -s "$SERIAL" shell appops get com.ichi2.anki MANAGE_EXTERNAL_STORAGE \
    | tr -d '\r' \
    | grep -F 'MANAGE_EXTERNAL_STORAGE: allow' >/dev/null; then
    fail "prerelease storage initialization app-op was not enabled"
fi
adb -s "$SERIAL" install --no-streaming "$app_apk" >/dev/null \
    || fail "cannot install the receipt-bound app APK"
adb -s "$SERIAL" install --no-streaming "$test_apk" >/dev/null \
    || fail "cannot install the receipt-bound test APK"
adb -s "$SERIAL" shell pm grant \
    com.ankiminer.android com.ichi2.anki.permission.READ_WRITE_DATABASE \
    || fail "cannot grant the prerelease provider permission"
if ! adb -s "$SERIAL" shell dumpsys package com.ankiminer.android \
    | tr -d '\r' \
    | grep -F 'com.ichi2.anki.permission.READ_WRITE_DATABASE: granted=true' >/dev/null; then
    fail "prerelease provider permission was not granted to the probe app"
fi

adb -s "$SERIAL" logcat -c
instrumentation_output="$(
    timeout --kill-after=2s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
        adb -s "$SERIAL" shell am instrument -w -r \
            -e ankiMinerRunS2 true \
            -e ankiMinerExpectedAnkiDroidVersionName "$version_name" \
            -e ankiMinerExpectedAnkiDroidVersionCode "$version_code" \
            -e class com.ankiminer.android.anki.s2.AnkiDroidS2CapabilityInstrumentedTest \
            com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner
)" || {
    printf '%s\n' "$instrumentation_output" >&2
    fail "prerelease instrumentation command failed or timed out"
}
printf '%s\n' "$instrumentation_output"
android_instrumentation_output_passed "$instrumentation_output" 1 \
    || fail "prerelease provider/adapter capability probe failed"
mapfile -t evidence_lines < <(
    adb -s "$SERIAL" logcat -d -s 'AnkiMinerS2:I' '*:S' \
        | grep -F 'ANKI_MINER_S2_PROBE=' || true
)
[[ "${#evidence_lines[@]}" == 1 ]] \
    || fail "prerelease probe did not emit exactly one evidence record"
printf '%s\n' "$identity"
printf '%s\n' "${evidence_lines[0]}"
echo "S2 AnkiDroid prerelease capability canary: OK ($version_name, $version_code)"
