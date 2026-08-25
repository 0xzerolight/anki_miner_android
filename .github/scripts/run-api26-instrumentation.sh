#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/instrumentation-result.sh
source "$REPO_ROOT/scripts/instrumentation-result.sh"

app_apk="$REPO_ROOT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
test_apk="$REPO_ROOT/app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk"
# Hosted runners do not provision external UniDic archives under /data/local/tmp.
# Those fixture-dependent methods are explicitly UNEXECUTED; fresh-process lanes own their runs.
readonly s1a_fixture_test="com.ankiminer.android.TokenizerS1aInstrumentedTest#externalUniDicMatchesDesktopGoldens"
readonly s1b_fixture_test="com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest#externalUniDicMatchesAllGoldensThroughPythonKotlinAndJni"
readonly engine_golden_v2_test="com.ankiminer.android.EngineGoldenV2InstrumentedTest#allCompleteSectionsReplayThroughPackagedEngine"
readonly reading_golden_test="com.ankiminer.android.ReadingGoldenInstrumentedTest#desktopReadingSourcesAndMokuroCardReplayThroughPackagedBridge"
readonly s4_test="com.ankiminer.android.S4EngineSmokeInstrumentedTest#pinnedDesktopChainRunsThroughPackagedEngine"
readonly s2_test="com.ankiminer.android.anki.s2.AnkiDroidS2CapabilityInstrumentedTest#provider_and_android_adapter_complete_the_raw_round_trip"
readonly s5_test="com.ankiminer.android.mining.S5VideoMiningAcceptanceInstrumentedTest#production_repository_mines_real_media_and_cancels_an_active_ffmpeg_child"
readonly s5_definition_lookup_test="com.ankiminer.android.mining.S5VideoMiningAcceptanceInstrumentedTest#definitionLookupRunsBesideAParkedRun"
readonly ui_audit_tests=(
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#curationList200CandidatesScrollsBottomThenTop"
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#settingsFullScrollsDownThenUp"
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#readingResultsLongListScrollsDownThenUp"
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#wizardStepsThroughEveryScreen"
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureVideoStatesAcrossThemeAndFontScaleMatrix"
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureReadingStatesAcrossThemeAndFontScaleMatrix"
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureSettingsStatesAcrossThemeAndFontScaleMatrix"
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureReadinessActionsAcrossThemeAndFontScaleMatrix"
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureEveryWizardStepAcrossThemeAndFontScaleMatrix"
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureAttributionAndNoticesAcrossThemeAndFontScaleMatrix"
)
readonly unexecuted_tests=(
    "$s1a_fixture_test"
    "$s1b_fixture_test"
    "$engine_golden_v2_test"
    "$reading_golden_test"
    "$s4_test"
    "$s2_test"
    "$s5_test"
    "$s5_definition_lookup_test"
    "${ui_audit_tests[@]}"
)
readonly expected_executed_test_count=303
excluded_tests="$(IFS=,; echo "${unexecuted_tests[*]}")"
readonly excluded_tests
# The lane runs everything the runner discovers except the allowlist above. The result contract is
# pinned at 303 executed tests: 321 source @Test methods minus the 18 explicit UNEXECUTED identities
# above. The host script test re-derives that count from source, so additions, removals, and renamed
# annotations require an intentional count update. The terminal contract also rejects failures,
# crashes, skips, assumption violations, and duplicate or missing terminal codes.

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
for test_name in "${unexecuted_tests[@]}"; do
    echo "instrumentation: UNEXECUTED: $test_name"
done
set +e
adb shell am instrument -w -r \
    -e notClass "$excluded_tests" \
    com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$result_file"
instrumentation_status="${PIPESTATUS[0]}"
set -e

if ((instrumentation_status != 0)); then
    echo "instrumentation: runner exited $instrumentation_status" >&2
    exit "$instrumentation_status"
fi
instrumentation_output="$(<"$result_file")"
if ! android_instrumentation_output_passed \
    "$instrumentation_output" "$expected_executed_test_count"; then
    echo "instrumentation: incomplete execution contract" >&2
    exit 1
fi

executed_summary="$(
    grep -Eo '^OK \([1-9][0-9]* tests?\)$' <<<"${instrumentation_output//$'\r'/}" \
        | head -1 \
        | tr -d '()' \
        | cut -d' ' -f2-
)"
echo "instrumentation: API 26 executed: PASS ($executed_summary)"
