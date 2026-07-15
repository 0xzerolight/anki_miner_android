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
        echo "Usage: scripts/health.sh [--connected api26|4k|16k]" >&2
        exit 2
    }
    CONNECTED_LANE="$2"
    shift 2
fi
if (($#)); then
    echo "Usage: scripts/health.sh [--connected api26|4k|16k]" >&2
    exit 2
fi

fail() {
    echo "health: $*" >&2
    exit 1
}

run_isolated_tokenizer_instrumentation() {
    local test_selector="$1"
    local expected_count="$2"
    local label="$3"
    local output
    adb -s "$ANDROID_SERIAL" shell am force-stop com.ankiminer.android \
        || fail "cannot stop the app before isolated $label instrumentation"
    output="$(
        adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
            -e ankiMinerExpectedTokenizerPath engine_shared_tagger \
            -e class "$test_selector" \
            com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    )" || {
        printf '%s\n' "$output" >&2
        fail "isolated $label instrumentation command failed"
    }
    if ! android_instrumentation_output_passed "$output" "$expected_count"; then
        printf '%s\n' "$output" >&2
        fail "isolated $label instrumentation did not pass exactly $expected_count test"
    fi
}

install_isolated_instrumentation_artifacts() {
    local app_apk="$1"
    local test_apk="$2"
    local instrumentation

    adb -s "$ANDROID_SERIAL" install -r "$app_apk" >/dev/null \
        || fail "cannot reinstall the app for isolated tokenizer instrumentation"
    adb -s "$ANDROID_SERIAL" install -r -t "$test_apk" >/dev/null \
        || fail "cannot reinstall the test APK for isolated tokenizer instrumentation"
    instrumentation="$(adb -s "$ANDROID_SERIAL" shell pm list instrumentation)" \
        || fail "cannot list installed instrumentation"
    grep -Fqx \
        'instrumentation:com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner (target=com.ankiminer.android)' \
        <<<"$(tr -d '\r' <<<"$instrumentation")" \
        || fail "isolated tokenizer instrumentation is not registered"
}

if [[ -n "$CONNECTED_LANE" ]]; then
    resolve_android_emulator_lane "$CONNECTED_LANE" \
        || fail "connected lane must be api26, 4k, or 16k"
    export ANDROID_SERIAL="$ANDROID_LANE_EMULATOR_SERIAL"
    "$SCRIPT_DIR/verify-emulator-runtime.sh" --lane "$CONNECTED_LANE" \
        || fail "connected emulator identity mismatch"
fi

[[ -x "$JAVA_HOME/bin/java" ]] || fail "JDK is missing; run scripts/provision-android.sh"
[[ -x "$ANDROID_CMDLINE_TOOLS_HOME/bin/sdkmanager" ]] || fail "Android command-line tools are missing"
"$SCRIPT_DIR/android-licenses.sh" check || fail "Android SDK license state is incomplete"
[[ -d "$ANDROID_HOME/platforms/android-$ANDROID_API_LEVEL" ]] || fail "Android API $ANDROID_API_LEVEL is missing"
[[ -d "$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION" ]] || fail "Build Tools $ANDROID_BUILD_TOOLS_VERSION are missing"
[[ -x "$ANDROID_CMAKE_HOME/bin/cmake" ]] || fail "CMake $ANDROID_CMAKE_VERSION is missing"
[[ -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]] || fail "NDK $ANDROID_NDK_VERSION is missing"
[[ "$(java -version 2>&1 | head -n 1)" == *'17.0.19'* ]] || fail "expected pinned JDK 17.0.19"
command -v python3.13 >/dev/null || fail "host Python 3.13 is required by repository tooling"
[[ "$(python3.13 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')" == "3.13" ]] \
    || fail "host Python 3.13 is required by repository tooling"
"$SCRIPT_DIR/verify-android-toolchain.sh" || fail "Android SDK package or AVD lock mismatch"

python3.13 "$SCRIPT_DIR/verify_chaquopy_build_python.py" verify \
    --toolchain-root "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT" \
    --python "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" >/dev/null \
    || fail "pinned Chaquopy build Python is missing or stale; run scripts/provision-chaquopy-build-python.sh"
runtime_manifest="${ORG_GRADLE_PROJECT_ankiMinerRuntimeManifest:-$REPO_ROOT/tools/runtime-wheels/out/current/manifest.json}"
[[ -f "$runtime_manifest" ]] \
    || fail "runtime wheel publication is missing; run tools/runtime-wheels/build-runtime-wheels.sh"
runtime_manifest="$(realpath "$runtime_manifest")"
python3.13 "$REPO_ROOT/tools/runtime-wheels/runtime_wheels.py" verify-publication \
    --manifest "$runtime_manifest" >/dev/null \
    || fail "runtime wheel publication is invalid or stale; rebuild it"
ORG_GRADLE_PROJECT_ankiMinerRuntimeManifest="$runtime_manifest"
export ORG_GRADLE_PROJECT_ankiMinerRuntimeManifest
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

runtime_host_python="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests/bin/python"
[[ -x "$runtime_host_python" ]] \
    || fail "runtime host test environment is missing; run scripts/provision-runtime-host-tests.sh"
expected_runtime_lock="$(sha256sum "$REPO_ROOT/requirements-runtime-host-test.lock" | awk '{ print $1 }')"
runtime_lock_marker="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests/.anki-miner-runtime-lock-sha256"
[[ -f "$runtime_lock_marker" && "$(<"$runtime_lock_marker")" == "$expected_runtime_lock" ]] \
    || fail "runtime host test environment is stale; run scripts/provision-runtime-host-tests.sh"
PIP_NO_CACHE_DIR=1 "$runtime_host_python" -m pip check
PYTHONDONTWRITEBYTECODE=1 "$runtime_host_python" -m pytest \
    -q "$REPO_ROOT/tests/python/android_bridge"
PYTHONDONTWRITEBYTECODE=1 "$runtime_host_python" -m compileall \
    -q "$REPO_ROOT/app/src/main/python"
PYTHONDONTWRITEBYTECODE=1 "$runtime_host_python" \
    "$SCRIPT_DIR/check-python-runtime.py" \
    --python-root "$REPO_ROOT/app/src/main/python" \
    --expected-version 3.12.13
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$REPO_ROOT/app/src/debug/python" \
    "$runtime_host_python" -c \
    'import runtime_dependencies_probe; runtime_dependencies_probe.snapshot()'

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

if [[ -n "$CONNECTED_LANE" ]]; then
    # connectedAndroidTest removes its installed APKs when it completes. Reinstall
    # the exact artifacts before the selector-isolation reruns below.
    install_isolated_instrumentation_artifacts "$emulator_apk" "$emulator_test_apk"
    run_isolated_tokenizer_instrumentation \
        'com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest' \
        2 \
        S1b
    if [[ -n "${ORG_GRADLE_PROJECT_ankiMinerS1aManifest:-}" ]]; then
        run_isolated_tokenizer_instrumentation \
            'com.ankiminer.android.TokenizerS1aInstrumentedTest' \
            1 \
            S1a
    fi
fi

s1a_artifact_args=()
runtime_s1a_artifact_args=()
if [[ -n "${ORG_GRADLE_PROJECT_ankiMinerS1aManifest:-}" ]]; then
    s1a_artifact_args=(
        --require-s1a
        --s1a-manifest "$s1a_manifest"
    )
    runtime_s1a_artifact_args=(--s1a-manifest "$s1a_manifest")
fi
"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$emulator_apk" \
    --allow-abi x86_64 \
    --require-app-imy \
    --reject-base-unidic \
    --require-entry lib/x86_64/libanki_miner_mecab.so \
    "${s1a_artifact_args[@]}"
"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$release_apk" \
    --allow-abi arm64-v8a \
    --require-app-imy \
    --reject-base-unidic \
    --require-entry lib/arm64-v8a/libanki_miner_mecab.so \
    --forbid-entry scaffold_probe \
    --forbid-entry runtime_dependencies_probe \
    --forbid-entry tokenizer_s1a_instrumented \
    --forbid-entry TokenizerS1aInstrumentedTest \
    --forbid-entry tokenizer_s1b_instrumented \
    --forbid-entry engine-v1.json \
    "${s1a_artifact_args[@]}"
"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$release_aab" \
    --allow-abi arm64-v8a \
    --require-app-imy \
    --reject-base-unidic \
    --require-entry base/lib/arm64-v8a/libanki_miner_mecab.so \
    --forbid-entry scaffold_probe \
    --forbid-entry runtime_dependencies_probe \
    --forbid-entry tokenizer_s1a_instrumented \
    --forbid-entry TokenizerS1aInstrumentedTest \
    --forbid-entry tokenizer_s1b_instrumented \
    --forbid-entry engine-v1.json \
    "${s1a_artifact_args[@]}"

python3.13 "$SCRIPT_DIR/check_runtime_artifact.py" \
    --artifact "$emulator_apk" \
    --runtime-manifest "$runtime_manifest" \
    --allow-abi x86_64 \
    "${runtime_s1a_artifact_args[@]}"
python3.13 "$SCRIPT_DIR/check_runtime_artifact.py" \
    --artifact "$release_apk" \
    --runtime-manifest "$runtime_manifest" \
    --allow-abi arm64-v8a \
    "${runtime_s1a_artifact_args[@]}"
python3.13 "$SCRIPT_DIR/check_runtime_artifact.py" \
    --artifact "$release_aab" \
    --runtime-manifest "$runtime_manifest" \
    --allow-abi arm64-v8a \
    "${runtime_s1a_artifact_args[@]}"

release_manifest="$(apkanalyzer manifest print "$release_apk")"
if grep -Eq 'ScaffoldProbeActivity|scaffold_probe' <<<"$release_manifest"; then
    fail "debug probe component leaked into the release manifest"
fi

echo "health: OK"
