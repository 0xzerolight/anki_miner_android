#!/usr/bin/env bash
set -euo pipefail

# Host health gate: toolchain presence, host Python suites, and the emulator
# Gradle build + unit tests. Release/receipt/acceptance ceremony was removed;
# real release verification is a local emulator-release smoke test + apksigner.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=android-test-resources.sh
source "$SCRIPT_DIR/android-test-resources.sh"

fail() {
    echo "health: $*" >&2
    exit 1
}

anki_miner_require_no_emulator || fail "host health requires every emulator to be stopped"

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
PYTHONDONTWRITEBYTECODE=1 "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" \
    "$REPO_ROOT/tools/anki-contract/generate_html5_entities.py" --check
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
anki_miner_run_gradle ./gradlew \
    :app:testEmulatorDebugUnitTest \
    :app:lintEmulatorDebug \
    :app:assembleEmulatorDebug \
    :app:assembleEmulatorDebugAndroidTest

emulator_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
emulator_test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
[[ -f "$emulator_apk" ]] || fail "emulator debug APK was not produced"
[[ -f "$emulator_test_apk" ]] || fail "emulator debug AndroidTest APK was not produced"

"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$emulator_apk" \
    --allow-abi x86_64 \
    --require-app-imy \
    --reject-base-unidic \
    --require-entry lib/x86_64/libanki_miner_mecab.so \
    --require-entry lib/x86_64/libffmpeg.so \
    --require-entry lib/x86_64/libffprobe.so

echo "health: OK"
