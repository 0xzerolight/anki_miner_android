#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

CONNECTED_LANE=""
staged_s1a_dictionary=false
staged_s1b_dictionary=false
cleanup_tokenizer_dictionaries() {
    if [[ "$staged_s1a_dictionary" == true ]]; then
        adb -s "$ANDROID_SERIAL" shell rm -f /data/local/tmp/anki-miner-tokenizer-unidic.zip \
            >/dev/null 2>&1 || true
    fi
    if [[ "$staged_s1b_dictionary" == true ]]; then
        adb -s "$ANDROID_SERIAL" shell rm -f "$ANDROID_S1B_TEST_UNIDIC_ARCHIVE" \
            >/dev/null 2>&1 || true
    fi
}
trap cleanup_tokenizer_dictionaries EXIT
if [[ "${1:-}" == "--connected" ]]; then
    (($# >= 2)) || {
        echo "Usage: scripts/health.sh [--connected 4k|16k]" >&2
        exit 2
    }
    CONNECTED_LANE="$2"
    shift 2
fi
if (($#)); then
    echo "Usage: scripts/health.sh [--connected 4k|16k]" >&2
    exit 2
fi

fail() {
    echo "health: $*" >&2
    exit 1
}

command -v python3.13 >/dev/null || fail "host Python 3.13 is required by Chaquopy"
if [[ -n "${ORG_GRADLE_PROJECT_ankiMinerS1aManifest:-}" ]]; then
    s1a_manifest="$(realpath "$ORG_GRADLE_PROJECT_ankiMinerS1aManifest")"
    s1a_wheel_tool="${ANKI_MINER_S1A_WHEEL_TOOL:-$REPO_ROOT/tools/wheels/s1a_wheels.py}"
    [[ -x "$s1a_wheel_tool" ]] || fail "S1a wheel tool is unavailable: $s1a_wheel_tool"
    "$s1a_wheel_tool" verify-publication --manifest "$s1a_manifest" >/dev/null \
        || fail "S1a wheel publication is invalid or stale for the active builder"
    ORG_GRADLE_PROJECT_ankiMinerS1aManifest="$s1a_manifest"
    export ORG_GRADLE_PROJECT_ankiMinerS1aManifest
fi
for script in "$SCRIPT_DIR"/*.sh; do
    bash -n "$script" || fail "shell syntax check failed: $script"
done
if command -v shellcheck >/dev/null; then
    shellcheck -x -P "$SCRIPT_DIR" "$SCRIPT_DIR"/*.sh \
        || fail "ShellCheck failed"
fi
python3.13 -m unittest discover -s "$SCRIPT_DIR/tests" -v
PYTHONDONTWRITEBYTECODE=1 python3.13 -m unittest discover \
    -s "$REPO_ROOT/tools/ankidroid-api/tests" -v
PYTHONDONTWRITEBYTECODE=1 python3.13 \
    "$REPO_ROOT/tools/ankidroid-api/sync_ankidroid_api.py" --check
if [[ -n "${ANKIDROID_API_UPSTREAM_CHECKOUT:-}" ]]; then
    PYTHONDONTWRITEBYTECODE=1 python3.13 \
        "$REPO_ROOT/tools/ankidroid-api/sync_ankidroid_api.py" \
        --check-upstream --source "$ANKIDROID_API_UPSTREAM_CHECKOUT"
fi
PYTHONDONTWRITEBYTECODE=1 python3.13 -m unittest discover \
    -s "$REPO_ROOT/tools/anki-contract/tests" -v
PYTHONDONTWRITEBYTECODE=1 python3.13 \
    "$REPO_ROOT/tools/anki-contract/generate_anki_limits.py" --check
PYTHONDONTWRITEBYTECODE=1 python3.13 \
    "$REPO_ROOT/tools/anki-contract/generate_unicode_contract.py" --check
PYTHONDONTWRITEBYTECODE=1 python3.13 -m unittest discover \
    -s "$REPO_ROOT/tools/dependencies/tests" -v

host_test_python="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/host-tests/bin/python"
[[ -x "$host_test_python" ]] \
    || fail "host test environment is missing; run scripts/provision-host-tests.sh"
expected_host_lock="$(sha256sum "$REPO_ROOT/requirements-host-test.lock" | awk '{ print $1 }')"
host_lock_marker="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/host-tests/.anki-miner-lock-sha256"
[[ -f "$host_lock_marker" && "$(<"$host_lock_marker")" == "$expected_host_lock" ]] \
    || fail "host test environment is stale; run scripts/provision-host-tests.sh"
PIP_NO_CACHE_DIR=1 "$host_test_python" -m pip check
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$REPO_ROOT/tools/engine-sync" \
    "$host_test_python" -m unittest discover -s "$REPO_ROOT/tools/engine-sync/tests" -v
"$host_test_python" "$REPO_ROOT/tools/engine-sync/sync_engine.py" --check
PYTHONDONTWRITEBYTECODE=1 "$host_test_python" -m pytest \
    -q "$REPO_ROOT/tests/python/android_bridge"
PYTHONDONTWRITEBYTECODE=1 "$host_test_python" -m compileall \
    -q "$REPO_ROOT/app/src/main/python/android_bridge"
PYTHONDONTWRITEBYTECODE=1 "$host_test_python" -m compileall \
    -q "$REPO_ROOT/app/src/debug/python"
"$host_test_python" "$REPO_ROOT/tools/tokenizer/vendor_s1b_mecab.py" --check

[[ -x "$JAVA_HOME/bin/java" ]] || fail "JDK is missing; run scripts/provision-android.sh"
[[ -x "$ANDROID_CMDLINE_TOOLS_HOME/bin/sdkmanager" ]] || fail "Android command-line tools are missing"
"$SCRIPT_DIR/android-licenses.sh" check || fail "Android SDK license state is incomplete"
[[ -d "$ANDROID_HOME/platforms/android-$ANDROID_API_LEVEL" ]] || fail "Android API $ANDROID_API_LEVEL is missing"
[[ -d "$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION" ]] || fail "Build Tools $ANDROID_BUILD_TOOLS_VERSION are missing"
[[ -x "$ANDROID_CMAKE_HOME/bin/cmake" ]] || fail "CMake $ANDROID_CMAKE_VERSION is missing"
[[ -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]] || fail "NDK $ANDROID_NDK_VERSION is missing"
[[ "$(java -version 2>&1 | head -n 1)" == *'17.0.19'* ]] || fail "expected pinned JDK 17.0.19"
[[ "$(python3.13 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')" == "3.13" ]] \
    || fail "host Python 3.13 is required by Chaquopy"
"$SCRIPT_DIR/verify-android-toolchain.sh" || fail "Android SDK package or AVD lock mismatch"

wrapper_jar="$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar"
wrapper_checksum="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
[[ -f "$wrapper_jar" ]] || fail "Gradle wrapper JAR is missing"
echo "$wrapper_checksum  $wrapper_jar" | sha256sum --check --status \
    || fail "Gradle wrapper JAR checksum mismatch"

cd "$REPO_ROOT"
tasks=(
    :app:testEmulatorDebugUnitTest
    :app:lintEmulatorDebug
    :app:lintDeviceRelease
    :app:assembleEmulatorDebug
    :app:assembleEmulatorDebugAndroidTest
    :app:assembleDeviceRelease
    :app:bundleDeviceRelease
)
if [[ -n "$CONNECTED_LANE" ]]; then
    case "$CONNECTED_LANE" in
        4k)
            expected_avd="$ANDROID_AVD_4K_NAME"
            emulator_serial="$ANDROID_EMULATOR_4K_SERIAL"
            expected_page_size=4096
            ;;
        16k)
            expected_avd="$ANDROID_AVD_16K_NAME"
            emulator_serial="$ANDROID_EMULATOR_16K_SERIAL"
            expected_page_size=16384
            ;;
        *)
            fail "connected lane must be 4k or 16k"
            ;;
    esac
    mapfile -t connected_devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [[ "${#connected_devices[@]}" -eq 1 && "${connected_devices[0]}" == "$emulator_serial" ]] \
        || fail "connected tests require only $emulator_serial to be online"
    actual_avd="$(adb -s "$emulator_serial" emu avd name 2>/dev/null | tr -d '\r' | sed '/^OK$/d' | sed -n '1p')"
    [[ "$actual_avd" == "$expected_avd" ]] \
        || fail "$emulator_serial is ${actual_avd:-unknown}, expected $expected_avd"
    actual_page_size="$(adb -s "$emulator_serial" shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')"
    [[ "$actual_page_size" == "$expected_page_size" ]] \
        || fail "$expected_avd page size is ${actual_page_size:-unknown}, expected $expected_page_size"
    export ANDROID_SERIAL="$emulator_serial"
    test_unidic="${ANKI_MINER_TEST_UNIDIC_DIR:-}"
    [[ -n "$test_unidic" && -d "$test_unidic" ]] \
        || fail "connected tokenizer tests require ANKI_MINER_TEST_UNIDIC_DIR"
    "$SCRIPT_DIR/provision-s1b-test-unidic.sh" --dicdir "$test_unidic"
    staged_s1b_dictionary=true
    if [[ -n "${ORG_GRADLE_PROJECT_ankiMinerS1aManifest:-}" ]]; then
        "$SCRIPT_DIR/provision-tokenizer-test-unidic.sh" --dicdir "$test_unidic"
        staged_s1a_dictionary=true
    fi
    tasks+=(:app:connectedEmulatorDebugAndroidTest)
fi

./gradlew --no-daemon --stacktrace --dependency-verification strict "${tasks[@]}"

emulator_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
emulator_test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
release_apk="$REPO_ROOT/app/build/outputs/apk/device/release/app-device-release-unsigned.apk"
release_aab="$REPO_ROOT/app/build/outputs/bundle/deviceRelease/app-device-release.aab"
[[ -f "$emulator_apk" ]] || fail "emulator debug APK was not produced"
[[ -f "$emulator_test_apk" ]] || fail "emulator debug AndroidTest APK was not produced"
[[ -f "$release_apk" ]] || fail "device release APK was not produced"
[[ -f "$release_aab" ]] || fail "device release AAB was not produced"

s1a_artifact_args=()
if [[ -n "${ORG_GRADLE_PROJECT_ankiMinerS1aManifest:-}" ]]; then
    s1a_artifact_args=(
        --require-app-imy
        --require-s1a
        --s1a-manifest "$s1a_manifest"
    )
fi
"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$emulator_apk" \
    --allow-abi x86_64 \
    --require-entry lib/x86_64/libanki_miner_mecab.so \
    "${s1a_artifact_args[@]}"
"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$release_apk" \
    --allow-abi arm64-v8a \
    --require-entry lib/arm64-v8a/libanki_miner_mecab.so \
    --forbid-entry scaffold_probe \
    --forbid-entry tokenizer_s1a_instrumented \
    --forbid-entry TokenizerS1aInstrumentedTest \
    --forbid-entry tokenizer_s1b_instrumented \
    --forbid-entry engine-v1.json \
    "${s1a_artifact_args[@]}"
"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$release_aab" \
    --allow-abi arm64-v8a \
    --require-entry base/lib/arm64-v8a/libanki_miner_mecab.so \
    --forbid-entry scaffold_probe \
    --forbid-entry tokenizer_s1a_instrumented \
    --forbid-entry TokenizerS1aInstrumentedTest \
    --forbid-entry tokenizer_s1b_instrumented \
    --forbid-entry engine-v1.json \
    "${s1a_artifact_args[@]}"

release_manifest="$(apkanalyzer manifest print "$release_apk")"
if grep -Eq 'ScaffoldProbeActivity|scaffold_probe' <<<"$release_manifest"; then
    fail "debug probe component leaked into the release manifest"
fi

echo "health: OK"
