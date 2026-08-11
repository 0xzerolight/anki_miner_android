#!/usr/bin/env bash
set -euo pipefail

# Host/build health gate: toolchain presence, host Python suites, JVM tests,
# lint, APK assembly, and native/runtime inspection. This script builds the
# AndroidTest APK but does not boot an emulator or execute instrumentation.
#
# Invariant: this script is a SUPERSET of the CI "Secretless host checks" job.
# health.sh green => that job green. Anything added there must be added here.

# Mirrors the job-wide env block in .github/workflows/pull-request.yml so
# locale- and hash-order-sensitive suites behave identically to CI.
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export PYTHONDONTWRITEBYTECODE=1
export PYTHONHASHSEED=0
export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1
export TZ=UTC

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
available_sdk_packages="$(mktemp)"
trap 'rm -f "$available_sdk_packages"' EXIT
"$ANDROID_CMDLINE_TOOLS_HOME/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --channel=0 --list \
    >"$available_sdk_packages"
python3.13 "$SCRIPT_DIR/preflight_android_packages.py" \
    --lock "$SCRIPT_DIR/android-sdk-packages.lock" \
    --sdkmanager-list "$available_sdk_packages" \
    || fail "Android SDK stable-channel revision differs from the package lock"
"$SCRIPT_DIR/verify-android-toolchain.sh" || fail "Android SDK package or AVD lock mismatch"

python3.13 "$SCRIPT_DIR/verify_chaquopy_build_python.py" verify \
    --toolchain-root "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT" \
    --python "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" >/dev/null \
    || fail "pinned Chaquopy build Python is missing or stale; run scripts/provision-chaquopy-build-python.sh"

# .github/scripts is included because CI syntax-checks it too; without it that
# directory is checked by CI and by nothing local.
for script in "$SCRIPT_DIR"/*.sh "$REPO_ROOT"/.github/scripts/*.sh; do
    bash -n "$script" || fail "shell syntax check failed: $script"
done
if command -v shellcheck >/dev/null; then
    shellcheck -x -P "$SCRIPT_DIR" "$SCRIPT_DIR"/*.sh "$REPO_ROOT"/.github/scripts/*.sh \
        || fail "ShellCheck failed"
fi
python3.13 -m unittest discover -s "$SCRIPT_DIR/tests" -v
PYTHONDONTWRITEBYTECODE=1 python3.13 \
    "$REPO_ROOT/tools/instrumentation/audit_instrumentation.py"
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
PYTHONDONTWRITEBYTECODE=1 python3.13 -m unittest discover \
    -s "$REPO_ROOT/tools/themes/tests" -v
PYTHONDONTWRITEBYTECODE=1 python3.13 \
    "$REPO_ROOT/tools/anki-contract/generate_anki_limits.py" --check
PYTHONDONTWRITEBYTECODE=1 python3.13 \
    "$REPO_ROOT/tools/anki-contract/generate_unicode_contract.py" --check
PYTHONDONTWRITEBYTECODE=1 python3.13 \
    "$REPO_ROOT/tools/themes/generate_theme_palettes.py" --check
PYTHONDONTWRITEBYTECODE=1 "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" \
    "$REPO_ROOT/tools/anki-contract/generate_html5_entities.py" --check
PYTHONDONTWRITEBYTECODE=1 python3.13 -m unittest discover \
    -s "$REPO_ROOT/tools/dependencies/tests" -v
PYTHONDONTWRITEBYTECODE=1 python3.13 -m unittest discover \
    -s "$REPO_ROOT/tools/runtime-wheels/tests" -v
PYTHONDONTWRITEBYTECODE=1 python3.13 -m unittest discover \
    -s "$REPO_ROOT/tools/ankiconnect-fallback/tests" -v

host_test_python="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/host-tests/bin/python"
[[ -x "$host_test_python" ]] \
    || fail "host test environment is missing; run scripts/provision-host-tests.sh"
# Must stay byte-identical to the expression in provision-host-tests.sh: hash the
# file CONTENT, not `sha256sum FILE1 FILE2` output (which embeds absolute paths
# and would false-report "stale" whenever a worktree and the main checkout share
# this marker, as they do -- the toolchain root comes from git-common-dir).
expected_host_lock="$(cat \
    "$REPO_ROOT/requirements-host-test.lock" \
    "$REPO_ROOT/requirements-lint.lock" | sha256sum | awk '{ print $1 }')"
host_lock_marker="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/host-tests/.anki-miner-lock-sha256"
[[ -f "$host_lock_marker" && "$(<"$host_lock_marker")" == "$expected_host_lock" ]] \
    || fail "host test environment is stale; run scripts/provision-host-tests.sh"
PIP_NO_CACHE_DIR=1 "$host_test_python" -m pip check

# Same lint CI runs, from the same pinned venv. Pass "$REPO_ROOT" explicitly:
# health.sh does not cd until the Gradle leg, and `black --check .` on an
# unrelated cwd exits 0 ("no Python files"), which would be a false green.
"$host_test_python" -m ruff check "$REPO_ROOT" || fail "ruff check failed"
"$host_test_python" -m black --check "$REPO_ROOT" || fail "black --check failed"

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$REPO_ROOT/tools/engine-sync" \
    "$host_test_python" -m unittest discover -s "$REPO_ROOT/tools/engine-sync/tests" -v
PYTHONPATH="$REPO_ROOT/tools/engine-sync" ANKI_MINER_REPO_ROOT="$REPO_ROOT" \
    "$host_test_python" - <<'PY' || fail "immutable engine v2 fixture validation failed"
import os
from pathlib import Path

from engine_sync.golden_contract_v2 import validate_committed_fixture

validate_committed_fixture(Path(os.environ["ANKI_MINER_REPO_ROOT"]))
print("immutable engine v2 fixture: OK")
PY
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
# The shipped ARM64 release artifact is built and audited by the CI Android job,
# not here. A release variant runs validate_release_build.py, which fails closed
# unless HEAD matches the built source and the checkout is completely clean —
# correct for a release, but it would make every local health run require a
# committed tree. The packaged-runtime auditor below still runs on a real
# artifact, which is what this gate was missing.
anki_miner_run_gradle ./gradlew \
    :app:testEmulatorDebugUnitTest \
    :app:lintEmulatorDebug \
    :app:assembleEmulatorDebug \
    :app:assembleEmulatorDebugAndroidTest

emulator_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
emulator_test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
[[ -f "$emulator_apk" ]] || fail "emulator debug APK was not produced"
[[ -f "$emulator_test_apk" ]] || fail "emulator debug AndroidTest APK was not produced"
echo "health: instrumentation APK built: $emulator_test_apk"
echo "health: instrumentation executed: NO (build-only host gate)"

"$host_test_python" "$REPO_ROOT/tools/wheels/vendored_wheel_manifest.py" check \
    --wheels-root "$REPO_ROOT/app/wheels" \
    --manifest "$REPO_ROOT/app/wheels/manifest.json"

"$SCRIPT_DIR/check-native-artifact.sh" \
    --artifact "$emulator_apk" \
    --allow-abi x86_64 \
    --require-app-imy \
    --reject-base-unidic \
    --require-entry lib/x86_64/libanki_miner_mecab.so \
    --require-entry lib/x86_64/libffmpeg.so \
    --require-entry lib/x86_64/libffprobe.so \
    --require-entry lib/x86_64/libmedia3ext.so
python3.13 "$SCRIPT_DIR/check_runtime_artifact.py" \
    --artifact "$emulator_apk" \
    --vendored-manifest "$REPO_ROOT/app/wheels/manifest.json" \
    --allow-abi x86_64

echo "health: host/build checks OK; instrumentation execution NOT RUN"
