from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
PROJECT_ROOT = SCRIPTS_DIR.parent
RUNNER = SCRIPTS_DIR / "run-s1b-arm64-tests.sh"
SERIAL = "arm64-test-4321"
FINGERPRINT = "example/arm64/image:16/AP4A.260101.001/123:userdebug/test-keys"


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
    if [[ "$FAKE_INSTRUMENT_RESULT" == pass ]]; then
        printf 'Time: 0.1\n\nOK (2 tests)\n\nINSTRUMENTATION_CODE: -1\n'
    else
        printf 'FAILURES!!!\nTests run: 2, Failures: 1\n'
    fi
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
exit "$FAKE_PROVISION_EXIT"
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
                "ANKI_MINER_S1B_PROVISIONER": str(self.provisioner),
                "ANKI_MINER_S1B_APP_APK": str(self.app_apk),
                "ANKI_MINER_S1B_TEST_APK": str(self.test_apk),
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


class S1bArm64RunnerTest(unittest.TestCase):
    def test_gradle_variant_is_opt_in_without_weakening_normal_policy(self) -> None:
        gradle = (PROJECT_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

        self.assertIn('gradleProperty("ankiMinerS1bArm64Tests")', gradle)
        self.assertIn('require(value == "true")', gradle)
        self.assertIn(
            '(runtimeAbi == "emulator" && variant.buildType == "debug")',
            gradle,
        )
        self.assertIn(
            '(s1aArm64Accepted && runtimeAbi == "device" && variant.buildType == "release")',
            gradle,
        )
        self.assertIn(
            "s1bArm64TestsEnabled &&",
            gradle,
        )
        self.assertIn('runtimeAbi == "device" &&', gradle)
        self.assertIn('variant.buildType == "debug"', gradle)

    def test_runner_builds_checks_provisions_and_runs_only_s1b_class(self) -> None:
        with _fixture() as fixture:
            result = fixture.run()

            self.assertEqual(0, result.returncode, result.stderr)
            gradle = (fixture.state / "gradle.log").read_text(encoding="utf-8")
            self.assertIn("-PankiMinerS1bArm64Tests=true", gradle)
            self.assertIn(":app:assembleDeviceDebug", gradle)
            self.assertIn(":app:assembleDeviceDebugAndroidTest", gradle)
            checker = (fixture.state / "checker.log").read_text(encoding="utf-8")
            self.assertIn("--allow-abi arm64-v8a", checker)
            self.assertIn(
                "--require-entry lib/arm64-v8a/libanki_miner_mecab.so",
                checker,
            )
            self.assertEqual(
                f"{SERIAL}|--dicdir {fixture.dicdir}\n",
                (fixture.state / "provision.log").read_text(encoding="utf-8"),
            )
            self.assertEqual(
                f"{fixture.adb}\n",
                (fixture.state / "provision-adb.log").read_text(encoding="utf-8"),
            )
            adb_log = (fixture.state / "adb.log").read_text(encoding="utf-8")
            self.assertIn(f"-s {SERIAL} shell getprop ro.product.cpu.abi", adb_log)
            self.assertIn(f"-s {SERIAL} shell getconf PAGE_SIZE", adb_log)
            self.assertIn(
                "-e class com.ankiminer.android.tokenizer."
                "MecabNativeTokenizerInstrumentedTest",
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
            self.assertIn(
                "shell rm -f /data/local/tmp/anki-miner-s1b-unidic.zip",
                adb_log,
            )

    def test_invalid_commands_and_serials_fail_before_external_commands(self) -> None:
        cases = {
            "missing": ["--serial", SERIAL],
            "unknown": ["--unknown", "value"],
            "duplicate": [
                "--serial",
                SERIAL,
                "--serial",
                SERIAL,
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
            "ANKI_MINER_S1B_PROVISIONER",
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

    def test_instrumentation_output_must_prove_both_tests_passed(self) -> None:
        with _fixture() as fixture:
            environment = fixture.environment.copy()
            environment["FAKE_INSTRUMENT_RESULT"] = "fail"
            result = fixture.run(environment=environment)

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn("did not pass both production-JNI tests", result.stderr)


if __name__ == "__main__":
    unittest.main()
