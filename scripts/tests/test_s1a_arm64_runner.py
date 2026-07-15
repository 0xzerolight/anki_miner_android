from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
RUNNER = SCRIPTS_DIR / "run-s1a-arm64-tests.sh"
SERIAL = "arm64-test-4321"
FINGERPRINT = "example/arm64/image:16/AP4A.260101.001/123:userdebug/test-keys"
RECIPE_KEY = "a" * 64
BUILD_KEY = "b" * 64


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


class _RunnerFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.state = root / "state"
        self.state.mkdir()
        self.dicdir = root / "unidic"
        self.dicdir.mkdir()
        self.manifest = root / "manifest.json"
        self.manifest.write_text("{}", encoding="utf-8")
        self.app_apk = root / "app-device-debug.apk"
        self.test_apk = root / "app-device-debug-androidTest.apk"
        self.app_apk.write_bytes(b"app image")
        self.test_apk.write_bytes(b"test image")

        self.adb = root / "adb"
        _write_executable(
            self.adb,
            r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_STATE/adb.log"
[[ "${1:-}" == -s && "${2:-}" == "$FAKE_SERIAL" ]] || exit 91
shift 2
if [[ "${1:-}" == get-state ]]; then
    printf '%s\n' "$FAKE_DEVICE_STATE"
elif [[ "${1:-}" == shell && "${2:-}" == getprop ]]; then
    case "${3:-}" in
        sys.boot_completed) printf '%s\n' "$FAKE_BOOT_COMPLETE" ;;
        ro.product.cpu.abi) printf '%s\n' "$FAKE_ABI" ;;
        ro.build.version.sdk) printf '%s\n' "$FAKE_API" ;;
        ro.build.fingerprint) printf '%s\n' "$FAKE_FINGERPRINT" ;;
        *) exit 92 ;;
    esac
elif [[ "${1:-}" == shell && "${2:-}" == getconf && "${3:-}" == PAGE_SIZE ]]; then
    printf '%s\n' "$FAKE_PAGE_SIZE"
elif [[ "${1:-}" == install ]]; then
    :
elif [[ "${1:-}" == shell && "${2:-}" == am && "${3:-}" == force-stop ]]; then
    :
elif [[ "${1:-}" == shell && "${2:-}" == am && "${3:-}" == instrument ]]; then
    case "$FAKE_INSTRUMENT_RESULT" in
        pass) printf 'Time: 0.1\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n' ;;
        mixed) printf 'OK (1 test)\nINSTRUMENTATION_FAILED: crash\nINSTRUMENTATION_CODE: -1\n' ;;
        wrong-count) printf 'OK (2 tests)\nINSTRUMENTATION_CODE: -1\n' ;;
        *) printf 'FAILURES!!!\nTests run: 1, Failures: 1\n' ;;
    esac
elif [[ "${1:-}" == shell && "${2:-}" == rm ]]; then
    :
else
    exit 93
fi
""",
        )

        self.gradle = root / "gradlew"
        _write_executable(
            self.gradle,
            r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"$FAKE_STATE/gradle.log"
exit "$FAKE_GRADLE_EXIT"
""",
        )

        self.apkanalyzer = root / "apkanalyzer"
        _write_executable(
            self.apkanalyzer,
            r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_STATE/apkanalyzer.log"
if [[ "${1:-}" == manifest && "${2:-}" == application-id ]]; then
    if [[ "${3:-}" == "$FAKE_APP_APK" ]]; then
        printf '%s\n' "$FAKE_APP_ID"
    elif [[ "${3:-}" == "$FAKE_TEST_APK" ]]; then
        printf '%s\n' "$FAKE_TEST_ID"
    else
        exit 81
    fi
elif [[ "${1:-}" == manifest && "${2:-}" == print && "${3:-}" == "$FAKE_TEST_APK" ]]; then
    printf '<instrumentation android:targetPackage="%s" android:name="%s" />\n' \
        "$FAKE_TARGET_PACKAGE" "$FAKE_TEST_RUNNER"
else
    exit 82
fi
""",
        )

        self.checker = root / "native-checker"
        _write_executable(
            self.checker,
            r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"$FAKE_STATE/checker.log"
exit "$FAKE_CHECKER_EXIT"
""",
        )

        self.provisioner = root / "provisioner"
        _write_executable(
            self.provisioner,
            r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s|%s\n' "${ANDROID_SERIAL:-}" "$*" >"$FAKE_STATE/provision.log"
printf '%s\n' "${ANKI_MINER_ADB_COMMAND:-}" >"$FAKE_STATE/provision-adb.log"
printf '%s\n' "${ANKI_MINER_PYTHON_COMMAND:-}" >"$FAKE_STATE/provision-python.log"
exit "$FAKE_PROVISION_EXIT"
""",
        )

        self.wheel_tool = root / "wheel-tool"
        _write_executable(
            self.wheel_tool,
            r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"$FAKE_STATE/wheel-tool.log"
[[ "${1:-}" == "verify-publication" && "${2:-}" == "--manifest" \
    && "${3:-}" == "$FAKE_MANIFEST" ]] || exit 71
[[ "$FAKE_WHEEL_EXIT" == 0 ]] || exit "$FAKE_WHEEL_EXIT"
printf '{"schema":2,"recipe_key":"%s","build_key":"%s"}\n' \
    "$FAKE_RECIPE_KEY" "$FAKE_BUILD_KEY"
""",
        )

        self.environment = os.environ.copy()
        self.environment.update(
            {
                "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root / "toolchain"),
                "ANKI_MINER_ADB_COMMAND": str(self.adb),
                "ANKI_MINER_GRADLEW_COMMAND": str(self.gradle),
                "ANKI_MINER_APKANALYZER_COMMAND": str(self.apkanalyzer),
                "ANKI_MINER_NATIVE_CHECKER": str(self.checker),
                "ANKI_MINER_PYTHON_COMMAND": sys.executable,
                "ANKI_MINER_S1A_PROVISIONER": str(self.provisioner),
                "ANKI_MINER_S1A_WHEEL_TOOL": str(self.wheel_tool),
                "ANKI_MINER_S1A_APP_APK": str(self.app_apk),
                "ANKI_MINER_S1A_TEST_APK": str(self.test_apk),
                "FAKE_STATE": str(self.state),
                "FAKE_SERIAL": SERIAL,
                "FAKE_DEVICE_STATE": "device",
                "FAKE_BOOT_COMPLETE": "1",
                "FAKE_ABI": "arm64-v8a",
                "FAKE_API": "36",
                "FAKE_PAGE_SIZE": "16384",
                "FAKE_FINGERPRINT": FINGERPRINT,
                "FAKE_INSTRUMENT_RESULT": "pass",
                "FAKE_GRADLE_EXIT": "0",
                "FAKE_CHECKER_EXIT": "0",
                "FAKE_PROVISION_EXIT": "0",
                "FAKE_RECIPE_KEY": RECIPE_KEY,
                "FAKE_BUILD_KEY": BUILD_KEY,
                "FAKE_WHEEL_EXIT": "0",
                "FAKE_MANIFEST": str(self.manifest),
                "FAKE_APP_APK": str(self.app_apk),
                "FAKE_TEST_APK": str(self.test_apk),
                "FAKE_APP_ID": "com.ankiminer.android",
                "FAKE_TEST_ID": "com.ankiminer.android.test",
                "FAKE_TARGET_PACKAGE": "com.ankiminer.android",
                "FAKE_TEST_RUNNER": "androidx.test.runner.AndroidJUnitRunner",
            }
        )

    @property
    def arguments(self) -> list[str]:
        return [
            "--serial",
            SERIAL,
            "--manifest",
            str(self.manifest),
            "--unidic-dir",
            str(self.dicdir),
            "--page-size",
            "16k",
            "--image-fingerprint",
            FINGERPRINT,
        ]

    def run(
        self,
        *,
        arguments: list[str] | None = None,
        environment: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(RUNNER), *(self.arguments if arguments is None else arguments)],
            check=False,
            capture_output=True,
            env=self.environment if environment is None else environment,
            text=True,
            timeout=10,
        )


@contextmanager
def _fixture() -> _RunnerFixture:
    with tempfile.TemporaryDirectory() as directory:
        yield _RunnerFixture(Path(directory))


class S1aArm64RunnerTest(unittest.TestCase):
    def test_runner_builds_checks_provisions_and_runs_only_s1a_class(self) -> None:
        with _fixture() as fixture:
            result = fixture.run()

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                f"verify-publication --manifest {fixture.manifest}\n",
                (fixture.state / "wheel-tool.log").read_text(encoding="utf-8"),
            )
            gradle = (fixture.state / "gradle.log").read_text(encoding="utf-8")
            self.assertIn(f"-PankiMinerS1aManifest={fixture.manifest}", gradle)
            self.assertNotIn("ankiMinerS1aRecipeKey", gradle)
            self.assertNotIn("ankiMinerS1aBuildKey", gradle)
            self.assertIn(":app:assembleDeviceDebug", gradle)
            self.assertIn(":app:assembleDeviceDebugAndroidTest", gradle)
            checker = (fixture.state / "checker.log").read_text(encoding="utf-8")
            self.assertIn("--allow-abi arm64-v8a", checker)
            self.assertIn("--require-app-imy", checker)
            self.assertIn("--require-s1a", checker)
            self.assertIn(f"--s1a-manifest {fixture.manifest}", checker)
            self.assertEqual(
                f"{SERIAL}|--dicdir {fixture.dicdir}\n",
                (fixture.state / "provision.log").read_text(encoding="utf-8"),
            )
            self.assertEqual(
                f"{fixture.adb}\n",
                (fixture.state / "provision-adb.log").read_text(encoding="utf-8"),
            )
            self.assertEqual(
                f"{Path(sys.executable)}\n",
                (fixture.state / "provision-python.log").read_text(encoding="utf-8"),
            )
            adb_log = (fixture.state / "adb.log").read_text(encoding="utf-8")
            self.assertIn(f"-s {SERIAL} shell getprop ro.product.cpu.abi", adb_log)
            self.assertIn(f"-s {SERIAL} shell getconf PAGE_SIZE", adb_log)
            self.assertIn(
                "-e class com.ankiminer.android.TokenizerS1aInstrumentedTest",
                adb_log,
            )
            self.assertIn(
                "-e ankiMinerExpectedTokenizerPath engine_shared_tagger",
                adb_log,
            )
            self.assertLess(
                adb_log.index("shell am force-stop com.ankiminer.android"),
                adb_log.index("shell am instrument"),
            )
            self.assertIn(
                "com.ankiminer.android.test/androidx.test.runner.AndroidJUnitRunner",
                adb_log,
            )
            self.assertNotIn("ScaffoldInstrumentedTest", adb_log)
            self.assertIn(" install -r -t ", f" {adb_log} ")
            self.assertIn(
                "shell rm -f /data/local/tmp/anki-miner-tokenizer-unidic.zip",
                adb_log,
            )

    def test_invalid_arguments_fail_before_external_commands(self) -> None:
        cases = {
            "missing": ["--serial", SERIAL],
            "unknown": ["--unknown", "value"],
            "duplicate": [
                "--serial",
                SERIAL,
                "--serial",
                SERIAL,
                "--manifest",
                "/tmp/manifest",
                "--unidic-dir",
                "/tmp",
                "--page-size",
                "16k",
                "--image-fingerprint",
                FINGERPRINT,
            ],
            "option-shaped serial": [
                "--serial",
                "--transport-id=1",
                "--manifest",
                "/tmp/manifest",
                "--unidic-dir",
                "/tmp",
                "--page-size",
                "16k",
                "--image-fingerprint",
                FINGERPRINT,
            ],
            "unsupported page": [
                "--serial",
                SERIAL,
                "--manifest",
                "/tmp/manifest",
                "--unidic-dir",
                "/tmp",
                "--page-size",
                "64k",
                "--image-fingerprint",
                FINGERPRINT,
            ],
            "ambiguous fingerprint": [
                "--serial",
                SERIAL,
                "--manifest",
                "/tmp/manifest",
                "--unidic-dir",
                "/tmp",
                "--page-size",
                "16k",
                "--image-fingerprint",
                "two words",
            ],
        }
        for label, arguments in cases.items():
            with self.subTest(label=label), _fixture() as fixture:
                result = fixture.run(arguments=arguments)

                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((fixture.state / "adb.log").exists())
                self.assertFalse((fixture.state / "gradle.log").exists())
                self.assertFalse((fixture.state / "provision.log").exists())

    def test_missing_external_commands_fail_before_target_queries(self) -> None:
        command_variables = (
            "ANKI_MINER_ADB_COMMAND",
            "ANKI_MINER_GRADLEW_COMMAND",
            "ANKI_MINER_APKANALYZER_COMMAND",
            "ANKI_MINER_NATIVE_CHECKER",
            "ANKI_MINER_S1A_PROVISIONER",
            "ANKI_MINER_S1A_WHEEL_TOOL",
            "ANKI_MINER_PYTHON_COMMAND",
        )
        for variable in command_variables:
            with self.subTest(variable=variable), _fixture() as fixture:
                environment = fixture.environment.copy()
                environment[variable] = str(fixture.root / "missing-command")
                result = fixture.run(environment=environment)

                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("unavailable", result.stderr)
                self.assertFalse((fixture.state / "adb.log").exists())
                self.assertFalse((fixture.state / "gradle.log").exists())
                self.assertFalse((fixture.state / "provision.log").exists())

    def test_stale_publication_fails_before_target_queries(self) -> None:
        with _fixture() as fixture:
            environment = fixture.environment.copy()
            environment["FAKE_WHEEL_EXIT"] = "8"

            result = fixture.run(environment=environment)

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn("invalid or stale", result.stderr)
            self.assertFalse((fixture.state / "adb.log").exists())
            self.assertFalse((fixture.state / "gradle.log").exists())

    def test_verifier_output_cannot_inject_gradle_identity(self) -> None:
        with _fixture() as fixture:
            environment = fixture.environment.copy()
            environment["FAKE_RECIPE_KEY"] = "not-a-hash"

            result = fixture.run(environment=environment)

            self.assertEqual(0, result.returncode, result.stderr)
            gradle = (fixture.state / "gradle.log").read_text(encoding="utf-8")
            self.assertNotIn("not-a-hash", gradle)
            self.assertNotIn("ankiMinerS1aRecipeKey", gradle)
            self.assertNotIn("ankiMinerS1aBuildKey", gradle)

    def test_target_identity_mismatches_fail_before_build_or_provision(self) -> None:
        cases = {
            "state": ("FAKE_DEVICE_STATE", "offline", "not an online device"),
            "boot": ("FAKE_BOOT_COMPLETE", "0", "not completed booting"),
            "abi": ("FAKE_ABI", "x86_64", "expected arm64-v8a"),
            "api": ("FAKE_API", "35", "expected 36"),
            "page": ("FAKE_PAGE_SIZE", "4096", "expected 16384"),
            "image": ("FAKE_FINGERPRINT", "different/image", "fingerprint"),
        }
        for label, (name, value, error_text) in cases.items():
            with self.subTest(label=label), _fixture() as fixture:
                environment = fixture.environment.copy()
                environment[name] = value
                result = fixture.run(environment=environment)

                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn(error_text, result.stderr)
                self.assertFalse((fixture.state / "gradle.log").exists())
                self.assertFalse((fixture.state / "provision.log").exists())

    def test_artifact_and_provisioning_failures_never_install_or_run(self) -> None:
        cases = {
            "build command": ("FAKE_GRADLE_EXIT", "6"),
            "native image": ("FAKE_CHECKER_EXIT", "7"),
            "package identity": ("FAKE_APP_ID", "wrong.application"),
            "test package identity": ("FAKE_TEST_ID", "wrong.test"),
            "target identity": ("FAKE_TARGET_PACKAGE", "wrong.application"),
            "runner identity": ("FAKE_TEST_RUNNER", "wrong.Runner"),
            "provisioning": ("FAKE_PROVISION_EXIT", "9"),
        }
        for label, (name, value) in cases.items():
            with self.subTest(label=label), _fixture() as fixture:
                environment = fixture.environment.copy()
                environment[name] = value
                result = fixture.run(environment=environment)

                self.assertNotEqual(0, result.returncode)
                adb_log = (fixture.state / "adb.log").read_text(encoding="utf-8")
                self.assertNotIn(" install ", f" {adb_log} ")
                self.assertNotIn(" shell am instrument ", f" {adb_log} ")
                if label == "provisioning":
                    self.assertTrue((fixture.state / "provision.log").is_file())
                else:
                    self.assertFalse((fixture.state / "provision.log").exists())

    def test_instrumentation_output_must_prove_the_single_test_passed(self) -> None:
        for mode in ("fail", "mixed", "wrong-count"):
            with self.subTest(mode=mode), _fixture() as fixture:
                environment = fixture.environment.copy()
                environment["FAKE_INSTRUMENT_RESULT"] = mode
                result = fixture.run(environment=environment)

                self.assertEqual(1, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
