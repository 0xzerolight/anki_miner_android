#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

RUN_CONNECTED=false
if [[ "${1:-}" == "--connected" ]]; then
    RUN_CONNECTED=true
    shift
fi
if (($#)); then
    echo "Usage: scripts/health.sh [--connected]" >&2
    exit 2
fi

fail() {
    echo "health: $*" >&2
    exit 1
}

for script in "$SCRIPT_DIR"/*.sh; do
    bash -n "$script" || fail "shell syntax check failed: $script"
done
if command -v shellcheck >/dev/null; then
    shellcheck -x -P "$SCRIPT_DIR" "$SCRIPT_DIR"/*.sh \
        || fail "ShellCheck failed"
fi

[[ -x "$JAVA_HOME/bin/java" ]] || fail "JDK is missing; run scripts/provision-android.sh"
[[ -x "$ANDROID_CMDLINE_TOOLS_HOME/bin/sdkmanager" ]] || fail "Android command-line tools are missing"
[[ -d "$ANDROID_HOME/platforms/android-$ANDROID_API_LEVEL" ]] || fail "Android API $ANDROID_API_LEVEL is missing"
[[ -d "$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION" ]] || fail "Build Tools $ANDROID_BUILD_TOOLS_VERSION are missing"
[[ -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]] || fail "NDK $ANDROID_NDK_VERSION is missing"
[[ "$(java -version 2>&1 | head -n 1)" == *'17.0.19'* ]] || fail "expected pinned JDK 17.0.19"
[[ "$(python3.13 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')" == "3.13" ]] \
    || fail "host Python 3.13 is required by Chaquopy"

wrapper_jar="$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar"
wrapper_checksum="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
[[ -f "$wrapper_jar" ]] || fail "Gradle wrapper JAR is missing"
echo "$wrapper_checksum  $wrapper_jar" | sha256sum --check --status \
    || fail "Gradle wrapper JAR checksum mismatch"

cd "$REPO_ROOT"
tasks=(
    :app:testDebugUnitTest
    :app:lintDebug
    :app:assembleDebug
    :app:assembleRelease
)
if [[ "$RUN_CONNECTED" == true ]]; then
    adb -e get-state >/dev/null 2>&1 || fail "no running emulator"
    tasks+=(:app:connectedDebugAndroidTest)
fi

./gradlew --no-daemon --stacktrace "${tasks[@]}"

release_apk="$REPO_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$release_apk" ]] || fail "release APK was not produced"
release_manifest="$(apkanalyzer manifest print "$release_apk")"
if grep -Eq 'ScaffoldProbeActivity|scaffold_probe' <<<"$release_manifest"; then
    fail "debug probe component leaked into the release manifest"
fi
if unzip -l "$release_apk" | grep 'scaffold_probe' >/dev/null; then
    fail "debug Python probe leaked into the release APK"
fi

echo "health: OK"
