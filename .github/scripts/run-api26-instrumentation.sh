#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/instrumentation-result.sh
source "$REPO_ROOT/scripts/instrumentation-result.sh"

app_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
# Hosted runners do not provision external UniDic archives under /data/local/tmp.
# Exclude only the two fixture-dependent methods; a fixture-provisioned lane is the follow-on.
readonly s1a_fixture_test="com.ankiminer.android.TokenizerS1aInstrumentedTest#externalUniDicMatchesDesktopGoldens"
readonly s1b_fixture_test="com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest#externalUniDicMatchesAllGoldensThroughPythonKotlinAndJni"
readonly external_unidic_tests="$s1a_fixture_test,$s1b_fixture_test"
# Full discovery is 135 tests. Keep the remaining count exact so discovery regressions fail closed.
readonly expected_test_count=133

[[ -f "$app_apk" ]] || {
    echo "instrumentation: app APK was not built: $app_apk" >&2
    exit 1
}
[[ -f "$test_apk" ]] || {
    echo "instrumentation: test APK was not built: $test_apk" >&2
    exit 1
}

adb wait-for-device
adb install --no-streaming -r -t "$app_apk"
adb install --no-streaming -r -t "$test_apk"

result_file="$(mktemp)"
trap 'rm -f "$result_file"' EXIT
echo "instrumentation: excluding 2 external-UniDic fixture tests;" \
    "fixture-provisioned lane is the follow-on"
set +e
adb shell am instrument -w -r \
    -e notClass "$external_unidic_tests" \
    com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$result_file"
instrumentation_status="${PIPESTATUS[0]}"
set -e

if ((instrumentation_status != 0)); then
    echo "instrumentation: runner exited $instrumentation_status" >&2
    exit "$instrumentation_status"
fi
if ! android_instrumentation_output_passed \
    "$(<"$result_file")" \
    "$expected_test_count"; then
    echo "instrumentation: expected complete $expected_test_count-test pass contract" >&2
    exit 1
fi

echo "instrumentation: API 26 executed: PASS ($expected_test_count tests)"
