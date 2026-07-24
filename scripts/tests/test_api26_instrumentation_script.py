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
    "com.ankiminer.android.TokenizerS1aInstrumentedTest" "#externalUniDicMatchesDesktopGoldens",
    "com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest"
    "#externalUniDicMatchesAllGoldensThroughPythonKotlinAndJni",
)
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.MULTILINE)
EXPECTED_COUNT = re.compile(r"readonly expected_test_count=(\d+)")
DISCOVERY_COMMENT = re.compile(r"# Full discovery is (\d+) tests\. (\d+) UI-audit tests are discovered")


class Api26InstrumentationScriptTest(unittest.TestCase):
    def test_excludes_only_external_unidic_tests_and_requires_discovered_results(self) -> None:
        discovered_test_count = sum(
            len(TEST_ANNOTATION.findall(source.read_text(encoding="utf-8")))
            for source in ANDROID_TEST_ROOT.rglob("*.kt")
        )
        ui_audit_root = ANDROID_TEST_ROOT / "kotlin/com/ankiminer/android/uiaudit"
        ui_audit_test_count = sum(
            len(TEST_ANNOTATION.findall(source.read_text(encoding="utf-8")))
            for source in ui_audit_root.glob("*Test.kt")
        )
        expected_test_count = discovered_test_count - len(EXTERNAL_UNIDIC_TESTS)
        script_source = SCRIPT.read_text(encoding="utf-8")
        count_match = EXPECTED_COUNT.search(script_source)
        self.assertIsNotNone(count_match)
        declared_count = int(count_match.group(1))
        self.assertEqual(expected_test_count, declared_count)

        discovery_match = DISCOVERY_COMMENT.search(script_source)
        self.assertIsNotNone(discovery_match)
        documented_discovery, documented_ui_audit = (int(value) for value in discovery_match.groups())
        self.assertEqual(discovered_test_count, documented_discovery)
        self.assertEqual(ui_audit_test_count, documented_ui_audit)

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
                root / "app/build/outputs/apk/androidTest/emulator/debug/" "app-emulator-debug-androidTest.apk",
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
                f"    printf 'OK ({expected_test_count} tests)\\n"
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

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(
                f"instrumentation: API 26 executed: PASS ({expected_test_count} tests)",
                result.stdout,
            )
            instrumentation_command = adb_log.read_text(encoding="utf-8").splitlines()[-1]
            self.assertEqual(
                "shell am instrument -w -r -e notClass "
                + ",".join(EXTERNAL_UNIDIC_TESTS)
                + " com.ankiminer.android.test/"
                "androidx.test.runner.AndroidJUnitRunner",
                instrumentation_command,
            )


if __name__ == "__main__":
    unittest.main()
