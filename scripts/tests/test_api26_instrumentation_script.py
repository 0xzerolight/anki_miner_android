from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / ".github" / "scripts" / "run-api26-instrumentation.sh"
RESULT_HELPER = REPO_ROOT / "scripts" / "instrumentation-result.sh"
ANDROID_TEST_ROOT = REPO_ROOT / "app" / "src" / "androidTest"
EXTERNAL_UNIDIC_TESTS = (
    "com.ankiminer.android.TokenizerS1aInstrumentedTest#externalUniDicMatchesDesktopGoldens",
    "com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest"
    "#externalUniDicMatchesAllGoldensThroughPythonKotlinAndJni",
)
SELECTOR_GATED_TESTS = (
    "com.ankiminer.android.EngineGoldenV2InstrumentedTest#allCompleteSectionsReplayThroughPackagedEngine",
    "com.ankiminer.android.ReadingGoldenInstrumentedTest#desktopReadingSourcesAndMokuroCardReplayThroughPackagedBridge",
    "com.ankiminer.android.S4EngineSmokeInstrumentedTest#pinnedDesktopChainRunsThroughPackagedEngine",
    "com.ankiminer.android.anki.s2.AnkiDroidS2CapabilityInstrumentedTest"
    "#provider_and_android_adapter_complete_the_raw_round_trip",
    "com.ankiminer.android.mining.S5VideoMiningAcceptanceInstrumentedTest"
    "#production_repository_mines_real_media_and_cancels_an_active_ffmpeg_child",
    "com.ankiminer.android.mining.S5VideoMiningAcceptanceInstrumentedTest#definitionLookupRunsBesideAParkedRun",
)
UI_AUDIT_TESTS = (
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#curationList200CandidatesScrollsBottomThenTop",
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#settingsFullScrollsDownThenUp",
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#readingResultsLongListScrollsDownThenUp",
    "com.ankiminer.android.uiaudit.UiAuditJankFlowTest#wizardStepsThroughEveryScreen",
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureVideoStatesAcrossThemeAndFontScaleMatrix",
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureReadingStatesAcrossThemeAndFontScaleMatrix",
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureSettingsStatesAcrossThemeAndFontScaleMatrix",
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureReadinessActionsAcrossThemeAndFontScaleMatrix",
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureEveryWizardStepAcrossThemeAndFontScaleMatrix",
    "com.ankiminer.android.uiaudit.UiAuditScreenshotTest#captureAttributionAndNoticesAcrossThemeAndFontScaleMatrix",
)
UNEXECUTED_TESTS = EXTERNAL_UNIDIC_TESTS + SELECTOR_GATED_TESTS + UI_AUDIT_TESTS
ASSUMPTION_GATED_TESTS = (
    EXTERNAL_UNIDIC_TESTS[0],
    *SELECTOR_GATED_TESTS,
    *UI_AUDIT_TESTS,
)
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.MULTILINE)
SOURCE_DECLARED_TEST_COUNT = sum(
    len(TEST_ANNOTATION.findall(source.read_text(encoding="utf-8"))) for source in ANDROID_TEST_ROOT.rglob("*.kt")
)
EXPECTED_EXECUTED_COUNT = SOURCE_DECLARED_TEST_COUNT - len(UNEXECUTED_TESTS)
PINNED_EXECUTED_COUNT = 304


class Api26InstrumentationScriptTest(unittest.TestCase):
    def _run_script(self, reported_count: int) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / ".github" / "scripts" / SCRIPT.name
            helper = root / "scripts" / RESULT_HELPER.name
            script.parent.mkdir(parents=True)
            helper.parent.mkdir(parents=True)
            shutil.copy2(SCRIPT, script)
            shutil.copy2(RESULT_HELPER, helper)

            for apk in (
                root / "app/build/outputs/apk/emulator/debug/app-emulator-debug.apk",
                root / "app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk",
            ):
                apk.parent.mkdir(parents=True, exist_ok=True)
                apk.touch()

            adb_log = root / "adb.log"
            fake_bin = root / "bin"
            fake_bin.mkdir()
            fake_adb = fake_bin / "adb"
            fake_adb.write_text(
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                'printf \'%s\\n\' "$*" >> "$ADB_LOG"\n'
                'if [[ "$*" == "shell am instrument"* ]]; then\n'
                f"    printf 'OK ({reported_count} tests)\\n"
                "INSTRUMENTATION_CODE: -1\\n'\n"
                "fi\n",
                encoding="utf-8",
            )
            fake_adb.chmod(0o755)

            env = os.environ.copy()
            env["ADB_LOG"] = str(adb_log)
            env["PATH"] = f"{fake_bin}{os.pathsep}{env['PATH']}"
            result = subprocess.run(
                ["bash", str(script)],
                text=True,
                capture_output=True,
                check=False,
                env=env,
            )

            instrumentation_command = adb_log.read_text(encoding="utf-8").splitlines()[-1]
            return result, instrumentation_command

    def test_excludes_all_assumption_gated_tests_and_requires_pinned_results(self) -> None:
        assumption_gated_test_count = sum(
            len(TEST_ANNOTATION.findall(source.read_text(encoding="utf-8")))
            for source in ANDROID_TEST_ROOT.rglob("*.kt")
            if "assumeTrue" in source.read_text(encoding="utf-8")
        )
        self.assertEqual(
            len(ASSUMPTION_GATED_TESTS),
            assumption_gated_test_count,
            "update the explicit unexecuted allowlist whenever an instrumentation class gains assumeTrue",
        )
        self.assertEqual(PINNED_EXECUTED_COUNT, EXPECTED_EXECUTED_COUNT)

        result, instrumentation_command = self._run_script(PINNED_EXECUTED_COUNT)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(
            f"instrumentation: API 26 executed: PASS ({PINNED_EXECUTED_COUNT} tests)",
            result.stdout,
        )
        self.assertEqual(
            "shell am instrument -w -r -e notClass " + ",".join(UNEXECUTED_TESTS) + " com.ankiminer.android.test/"
            "androidx.test.runner.AndroidJUnitRunner",
            instrumentation_command,
        )
        for test_name in SELECTOR_GATED_TESTS:
            self.assertIn(f"instrumentation: UNEXECUTED: {test_name}", result.stdout)

    def test_rejects_loss_of_one_discovered_test(self) -> None:
        result, _ = self._run_script(PINNED_EXECUTED_COUNT - 1)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("instrumentation: incomplete execution contract", result.stderr)


if __name__ == "__main__":
    unittest.main()
