#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

if (($# != 6)) || [[ "$1" != "--serial" || "$3" != "--manifest" || "$5" != "--unidic-dir" ]]; then
    echo "Usage: scripts/run-s1a-arm64-tests.sh --serial SERIAL --manifest FILE --unidic-dir DIR" >&2
    exit 2
fi
serial="$2"
manifest="$(realpath "$4")"
dicdir="$(realpath "$6")"
[[ "$(adb -s "$serial" get-state)" == device ]]
[[ "$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')" == arm64-v8a ]]
api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
((api >= 26))
page_size="$(adb -s "$serial" shell getconf PAGE_SIZE | tr -d '\r')"
[[ "$page_size" == 4096 || "$page_size" == 16384 ]]
export ANDROID_SERIAL="$serial"
cleanup_dictionary() {
    adb -s "$serial" shell rm -f /data/local/tmp/anki-miner-tokenizer-unidic.zip \
        >/dev/null 2>&1 || true
}
trap cleanup_dictionary EXIT
"$SCRIPT_DIR/provision-tokenizer-test-unidic.sh" --dicdir "$dicdir"
cd "$REPO_ROOT"
./gradlew --no-daemon --stacktrace --dependency-verification strict \
    -PankiMinerS1aManifest="$manifest" \
    :app:assembleDeviceDebug :app:assembleDeviceDebugAndroidTest
app_apk="app/build/outputs/apk/device/debug/app-device-debug.apk"
test_apk="app/build/outputs/apk/androidTest/device/debug/app-device-debug-androidTest.apk"
"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$app_apk" \
    --allow-abi arm64-v8a \
    --require-app-imy \
    --require-s1a
[[ "$(apkanalyzer manifest application-id "$app_apk")" == com.ankiminer.android ]]
adb -s "$serial" install -r "$app_apk"
adb -s "$serial" install -r "$test_apk"
instrumentation_output="$(adb -s "$serial" shell am instrument -w \
    -e class com.ankiminer.android.TokenizerS1aInstrumentedTest \
    com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner)"
printf '%s\n' "$instrumentation_output"
grep -Fq 'OK (1 test)' <<<"$instrumentation_output"
