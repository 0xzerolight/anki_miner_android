#!/usr/bin/env bash
# Gradle integration coverage for release provenance. This builds APK/AAB artifacts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../android-env.sh
source "$REPO_ROOT/scripts/android-env.sh"
# shellcheck source=../android-test-resources.sh
source "$REPO_ROOT/scripts/android-test-resources.sh"
cd "$REPO_ROOT"

valid_sha="$(git rev-parse HEAD)"
manifest="$REPO_ROOT/app/wheels/manifest.json"
manifest_backup="$(mktemp)"
failure_log="$(mktemp)"
wiring_log="$(mktemp)"
manifest_modified=false
cp -- "$manifest" "$manifest_backup"

cleanup() {
    if [[ "$manifest_modified" == true ]]; then
        cp -- "$manifest_backup" "$manifest"
    fi
    rm -f -- "$manifest_backup" "$failure_log" "$wiring_log"
}
trap cleanup EXIT

run_gradle_without_source_commit() (
    unset ANKI_MINER_SOURCE_COMMIT
    anki_miner_run_gradle ./gradlew "$@"
)

run_gradle_with_source_commit() {
    local source_commit="$1"
    shift
    anki_miner_run_gradle ./gradlew \
        -PankiMinerSourceCommit="$source_commit" \
        "$@"
}

expect_gradle_failure() {
    local label="$1"
    local expected="$2"
    shift 2
    if "$@" >"$failure_log" 2>&1; then
        echo "$label: Gradle unexpectedly succeeded" >&2
        return 1
    fi
    if ! grep -Eq "$expected" "$failure_log"; then
        echo "$label: expected error not found: $expected" >&2
        sed -n '1,240p' "$failure_log" >&2
        return 1
    fi
    echo "$label: expected failure"
}

# Debug default remains development and can produce an APK without a source SHA.
run_gradle_without_source_commit \
    :app:generateEmulatorDebugBuildConfig \
    :app:assembleEmulatorDebug
debug_build_config="$(
    find app/build/generated/source/buildConfig \
        -name BuildConfig.java \
        -exec grep -lF 'SOURCE_COMMIT = "development"' {} \; \
        -quit
)"
[[ -n "$debug_build_config" ]]
grep -Fq 'SOURCE_COMMIT = "development"' "$debug_build_config"
[[ -n "$(find app/build/outputs/apk/emulator/debug -name '*.apk' -print -quit)" ]]

# Lifecycle wiring still gives early, named feedback in ordinary release builds.
run_gradle_with_source_commit "$valid_sha" \
    :app:assembleDeviceRelease --dry-run >"$wiring_log"
grep -Eq '^:app:validateReleaseSourceCommit[[:space:]]+SKIPPED$' "$wiring_log"
grep -Eq '^:app:verifyVendoredWheelManifest[[:space:]]+SKIPPED$' "$wiring_log"

# The variant provider must fail closed even when both named checks are excluded.
expect_gradle_failure \
    "missing release SHA" \
    "full lowercase Git SHA" \
    run_gradle_without_source_commit \
    -x validateReleaseSourceCommit \
    -x verifyVendoredWheelManifest \
    :app:assembleDeviceRelease
expect_gradle_failure \
    "invalid release SHA" \
    "full lowercase Git SHA" \
    run_gradle_with_source_commit development \
    -x validateReleaseSourceCommit \
    -x verifyVendoredWheelManifest \
    :app:bundleDeviceRelease

# A valid SHA and current wheel manifest can produce both release artifact types.
run_gradle_with_source_commit "$valid_sha" \
    -x validateReleaseSourceCommit \
    -x verifyVendoredWheelManifest \
    :app:assembleEmulatorRelease \
    :app:bundleDeviceRelease
[[ -n "$(find app/build/outputs/apk/emulator/release -name '*.apk' -print -quit)" ]]
[[ -n "$(find app/build/outputs/bundle/deviceRelease -name '*.aab' -print -quit)" ]]

# Re-run an up-to-date release with manifest drift: provider evaluation must still fail.
manifest_modified=true
"$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/host-tests/bin/python" - "$manifest" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["wheels"][0]["sha256"] = "0" * 64
path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
expect_gradle_failure \
    "manifest drift" \
    "SHA-256 mismatch" \
    run_gradle_with_source_commit "$valid_sha" \
    -x validateReleaseSourceCommit \
    -x verifyVendoredWheelManifest \
    :app:assembleEmulatorRelease
cp -- "$manifest_backup" "$manifest"
manifest_modified=false

echo "release build integrity Gradle integration: OK"
