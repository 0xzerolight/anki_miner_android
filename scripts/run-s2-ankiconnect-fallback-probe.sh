#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=android-test-resources.sh
source "$SCRIPT_DIR/android-test-resources.sh"

SERIAL="${ANKI_MINER_S2_SERIAL:-emulator-5554}"
ANKIDROID_APK="${ANKI_MINER_ANKIDROID_APK:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/ankidroid/v2.24.0/variant-abi-AnkiDroid-2.24.0-x86_64.apk}"
FALLBACK_APK="${ANKI_MINER_ANKICONNECT_FALLBACK_APK:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/ankiconnect/v1.15/ankiconnect_android_1_15.apk}"
RECEIPT="${ANKI_MINER_ANDROID_TEST_RECEIPT:-}"
RECEIPT_COMMAND="${ANKI_MINER_RECEIPT_COMMAND:-$SCRIPT_DIR/android_test_receipt.py}"
FALLBACK_VERIFIER="${ANKI_MINER_ANKICONNECT_FALLBACK_VERIFIER:-$REPO_ROOT/tools/ankiconnect-fallback/verify_fallback_apk.py}"
HOST_PORT="${ANKI_MINER_ANKICONNECT_FALLBACK_HOST_PORT:-18765}"
ADB_TIMEOUT_SECONDS="${ANKI_MINER_ADB_TIMEOUT_SECONDS:-15}"
HTTP_TIMEOUT_SECONDS="${ANKI_MINER_FALLBACK_HTTP_TIMEOUT_SECONDS:-10}"

usage() {
    cat <<'EOF' >&2
Usage: scripts/run-s2-ankiconnect-fallback-probe.sh --receipt FILE

Runs the pinned capability-only HTTP fallback probe against the already-running
owned API 36 4K emulator. The disposable AnkiDroid collection reset must be
explicitly enabled with ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true.
EOF
}

if (($# == 2)) && [[ "$1" == --receipt ]]; then
    RECEIPT="$2"
elif (($#)); then
    usage
    exit 2
fi

fail() {
    echo "S2 Ankiconnect fallback: $*" >&2
    exit 1
}

[[ "$SERIAL" == emulator-5554 ]] || fail "probe is pinned to the API 36 4K lane"
if [[ ! "$HOST_PORT" =~ ^[1-9][0-9]{0,4}$ ]] || ((HOST_PORT > 65535)); then
    fail "host port must be in 1..65535"
fi
for timeout_value in "$ADB_TIMEOUT_SECONDS" "$HTTP_TIMEOUT_SECONDS"; do
    [[ "$timeout_value" =~ ^[1-9][0-9]*$ ]] || fail "timeouts must be positive seconds"
done
if [[ "${ANKI_MINER_S2_ALLOW_COLLECTION_RESET:-}" != true ]]; then
    echo "The fallback probe resets exactly /storage/emulated/0/AnkiDroid on emulator-5554." >&2
    echo "Set ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true only for that disposable lane." >&2
    exit 2
fi
[[ -n "$RECEIPT" && -f "$RECEIPT" ]] || { usage; exit 2; }
[[ -f "$ANKIDROID_APK" ]] || fail "pinned AnkiDroid APK is missing: $ANKIDROID_APK"
[[ -f "$FALLBACK_APK" ]] || fail "pinned AnkiconnectAndroid APK is missing: $FALLBACK_APK"

ANKIDROID_APK="$(realpath "$ANKIDROID_APK")"
FALLBACK_APK="$(realpath "$FALLBACK_APK")"
RECEIPT="$(realpath "$RECEIPT")"
anki_miner_require_no_gradle || fail "Gradle must exit before connected work"
"$RECEIPT_COMMAND" validate \
    --repo-root "$REPO_ROOT" \
    --receipt "$RECEIPT" \
    --require-s2 \
    --ankidroid-apk "$ANKIDROID_APK" \
    --s2-reset-opt-in \
    || fail "host receipt or AnkiDroid identity is stale"
"$FALLBACK_VERIFIER" "$FALLBACK_APK" || fail "fallback APK identity is invalid"
"$SCRIPT_DIR/verify-emulator-runtime.sh" --lane 4k \
    || fail "connected emulator identity changed"

if command -v ss >/dev/null && ss -H -ltn | awk -v port=":$HOST_PORT" '$4 ~ port "$" { found = 1 } END { exit !found }'; then
    fail "host port $HOST_PORT is already in use"
fi

scratch="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/ankiconnect-fallback-probe"
mkdir -p "$scratch"
hierarchy="$scratch/window.xml"
version_response="$scratch/version.json"
decks_response="$scratch/decks.json"
device_hierarchy=/sdcard/anki-miner-ankiconnect-window.xml
forwarded=false
cleanup() {
    local status="$1"
    if [[ "$forwarded" == true ]]; then
        adb -s "$SERIAL" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true
    fi
    adb -s "$SERIAL" shell rm -f "$device_hierarchy" >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell am force-stop com.kamwithk.ankiconnectandroid >/dev/null 2>&1 || true
    if ((status != 0)); then
        adb -s "$SERIAL" logcat -d >"$scratch/failure-logcat.txt" 2>&1 || true
        echo "Fallback failure evidence retained in $scratch" >&2
    else
        rm -f "$hierarchy" "$version_response" "$decks_response"
    fi
}
trap 'status=$?; cleanup "$status"; exit "$status"' EXIT

anki_miner_require_no_gradle || fail "Gradle started before fallback installation"
adb -s "$SERIAL" uninstall com.kamwithk.ankiconnectandroid >/dev/null 2>&1 || true
adb -s "$SERIAL" uninstall com.ichi2.anki >/dev/null 2>&1 || true
adb -s "$SERIAL" shell rm -rf -- /storage/emulated/0/AnkiDroid
adb -s "$SERIAL" shell test ! -e /storage/emulated/0/AnkiDroid \
    || fail "disposable AnkiDroid collection could not be reset"
adb -s "$SERIAL" install --no-streaming "$ANKIDROID_APK" >/dev/null \
    || fail "cannot install pinned AnkiDroid"
adb -s "$SERIAL" shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow
adb -s "$SERIAL" install --no-streaming "$FALLBACK_APK" >/dev/null \
    || fail "cannot install pinned AnkiconnectAndroid"
adb -s "$SERIAL" shell pm grant \
    com.kamwithk.ankiconnectandroid com.ichi2.anki.permission.READ_WRITE_DATABASE \
    || fail "cannot grant the AnkiDroid provider permission to the fallback"
adb -s "$SERIAL" shell pm grant \
    com.kamwithk.ankiconnectandroid android.permission.POST_NOTIFICATIONS \
    || fail "cannot grant notification permission to the fallback"

adb -s "$SERIAL" shell am start -W \
    -n com.kamwithk.ankiconnectandroid/.MainActivity >/dev/null \
    || fail "cannot launch the fallback activity"
target_found=false
for _ in {1..10}; do
    timeout --kill-after=2s "${ADB_TIMEOUT_SECONDS}s" \
        adb -s "$SERIAL" shell uiautomator dump "$device_hierarchy" >/dev/null 2>&1 || true
    if adb -s "$SERIAL" pull "$device_hierarchy" "$hierarchy" >/dev/null 2>&1; then
        if coordinates="$("$SCRIPT_DIR/uiautomator_click_target.py" "$hierarchy" --text 'Start Service')"; then
            target_found=true
            break
        fi
    fi
    sleep 1
done
[[ "$target_found" == true ]] || fail "Start Service button did not become uniquely actionable"
read -r target_x target_y <<<"$coordinates"
adb -s "$SERIAL" shell input tap "$target_x" "$target_y" \
    || fail "cannot activate the fallback service"

adb -s "$SERIAL" forward "tcp:$HOST_PORT" tcp:8765 >/dev/null \
    || fail "cannot forward the fallback HTTP port"
forwarded=true
server_ready=false
for _ in {1..20}; do
    if curl --noproxy '*' --fail --silent --show-error \
        --max-time "$HTTP_TIMEOUT_SECONDS" \
        --header 'Content-Type: application/json' \
        --data '{"action":"version","version":6}' \
        --output "$version_response" \
        "http://127.0.0.1:$HOST_PORT/"; then
        server_ready=true
        break
    fi
    sleep 1
done
[[ "$server_ready" == true ]] || fail "fallback HTTP server did not become ready"
curl --noproxy '*' --fail --silent --show-error \
    --max-time "$HTTP_TIMEOUT_SECONDS" \
    --header 'Content-Type: application/json' \
    --data '{"action":"deckNames","version":6}' \
    --output "$decks_response" \
    "http://127.0.0.1:$HOST_PORT/" \
    || fail "fallback deckNames request failed"
"$SCRIPT_DIR/verify_ankiconnect_probe_response.py" \
    --version "$version_response" --decks "$decks_response" \
    || fail "fallback response contract changed"

echo "S2 AnkiconnectAndroid 1.15 fallback capability probe: OK"
