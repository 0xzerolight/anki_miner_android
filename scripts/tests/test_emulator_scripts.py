from __future__ import annotations

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]


class EmulatorScriptTest(unittest.TestCase):
    API26_FINGERPRINT = (
        "Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/"
        "OSR1.180418.026/6741039:userdebug/dev-keys"
    )

    def print_command(self, root: Path, *arguments: str) -> list[str]:
        environment = os.environ.copy()
        environment["ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"] = str(root)
        result = subprocess.run(
            [
                str(SCRIPTS_DIR / "emulator.sh"),
                "--print-command",
                "--headless",
                "--software",
                *arguments,
            ],
            check=False,
            capture_output=True,
            env=environment,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return shlex.split(result.stdout)

    def verify_runtime(
        self,
        lane: str,
        *,
        devices: str | None = None,
        avd: str | None = None,
        api: str | None = None,
        page_size: str | None = None,
        fingerprint: str | None = None,
        through_health: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        identities = {
            "api26": ("emulator-5558", "anki_miner_api26", "26", "4096"),
            "4k": ("emulator-5554", "anki_miner_api36", "36", "4096"),
            "16k": (
                "emulator-5556",
                "anki_miner_api36_ps16k",
                "36",
                "16384",
            ),
        }
        serial, expected_avd, expected_api, expected_page_size = identities[lane]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            platform_tools = root / "sdk" / "platform-tools"
            platform_tools.mkdir(parents=True)
            adb = platform_tools / "adb"
            adb.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "devices" ]]; then
    printf 'List of devices attached\n%b' "$FAKE_DEVICES"
    exit 0
fi
if [[ "${1:-}" == "-s" ]]; then
    shift 2
fi
if [[ "${1:-}" == "emu" && "${2:-}" == "avd" ]]; then
    printf '%s\nOK\n' "$FAKE_AVD"
elif [[ "${1:-}" == "shell" && "${2:-}" == "getprop" ]]; then
    case "${3:-}" in
        ro.build.version.sdk) echo "$FAKE_API" ;;
        ro.build.fingerprint) echo "$FAKE_FINGERPRINT" ;;
        *) exit 1 ;;
    esac
elif [[ "${1:-}" == "shell" && "${2:-}" == "getconf" && "${3:-}" == "PAGE_SIZE" ]]; then
    [[ "$FAKE_API" != 26 ]] || exit 127
    echo "$FAKE_PAGE_SIZE"
elif [[ "${1:-}" == "shell" && "${2:-}" == "cat" && "${3:-}" == "/proc/self/smaps" ]]; then
    printf 'KernelPageSize: %s kB\n' "$((FAKE_PAGE_SIZE / 1024))"
else
    exit 1
fi
""",
                encoding="utf-8",
            )
            adb.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "FAKE_DEVICES": devices or f"{serial}\\tdevice\\n",
                    "FAKE_AVD": avd or expected_avd,
                    "FAKE_API": api or expected_api,
                    "FAKE_PAGE_SIZE": page_size or expected_page_size,
                    "FAKE_FINGERPRINT": fingerprint or self.API26_FINGERPRINT,
                },
            )
            command = (
                [str(SCRIPTS_DIR / "health.sh"), "--connected", lane]
                if through_health
                else [
                    str(SCRIPTS_DIR / "verify-emulator-runtime.sh"),
                    "--lane",
                    lane,
                ]
            )
            return subprocess.run(
                command,
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

    def test_interactive_command_keeps_userdata_and_snapshots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            command = self.print_command(Path(directory))
        self.assertNotIn("-wipe-data", command)
        self.assertNotIn("-no-snapshot-load", command)
        self.assertNotIn("-no-snapshot-save", command)
        self.assertEqual("anki_miner_api36", command[command.index("-avd") + 1])
        self.assertEqual("5554", command[command.index("-port") + 1])
        gpu_index = command.index("-gpu")
        self.assertEqual("swiftshader", command[gpu_index + 1])

    def test_test_session_enforces_clean_snapshot_free_boot_last(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            command = self.print_command(
                Path(directory),
                "--test-session",
                "--",
                "-snapshot",
                "stale-snapshot",
            )
        self.assertEqual(
            ["-wipe-data", "-no-snapshot-load", "-no-snapshot-save"],
            command[-3:],
        )

    def test_launcher_maps_all_canonical_lanes_and_legacy_aliases(self) -> None:
        cases = (
            (("--lane", "api26"), "anki_miner_api26", "5558"),
            (("--lane", "4k"), "anki_miner_api36", "5554"),
            (("--lane", "16k"), "anki_miner_api36_ps16k", "5556"),
            (("--page-size", "4k"), "anki_miner_api36", "5554"),
            (("--page-size", "16k"), "anki_miner_api36_ps16k", "5556"),
        )
        with tempfile.TemporaryDirectory() as directory:
            for arguments, expected_avd, expected_port in cases:
                with self.subTest(arguments=arguments):
                    command = self.print_command(Path(directory), *arguments)
                    self.assertEqual(expected_avd, command[command.index("-avd") + 1])
                    self.assertEqual(expected_port, command[command.index("-port") + 1])

    def test_launcher_rejects_selector_conflicts_duplicates_and_invalid_values(
        self,
    ) -> None:
        cases = (
            ("--lane", "api26", "--lane", "4k"),
            ("--page-size", "4k", "--page-size", "16k"),
            ("--lane", "4k", "--page-size", "4k"),
            ("--lane", "invalid"),
            ("--page-size", "api26"),
            ("--page-size", "invalid"),
            ("--lane=4k",),
            ("--page-size=4k",),
        )
        with tempfile.TemporaryDirectory() as directory:
            environment = os.environ.copy()
            environment["ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"] = directory
            for arguments in cases:
                with self.subTest(arguments=arguments):
                    result = subprocess.run(
                        [
                            str(SCRIPTS_DIR / "emulator.sh"),
                            "--print-command",
                            *arguments,
                        ],
                        check=False,
                        capture_output=True,
                        env=environment,
                        text=True,
                    )
                    self.assertEqual(2, result.returncode, result.stderr)

    def test_launcher_rejects_passthrough_avd_and_port_overrides(self) -> None:
        cases = (
            ("--", "-avd", "other"),
            ("--", "-avd=other"),
            ("--", "@other"),
            ("--", "-port", "5560"),
            ("--", "-port=5560"),
            ("--", "-ports", "5560,5561"),
            ("--", "-ports=5560,5561"),
        )
        with tempfile.TemporaryDirectory() as directory:
            environment = os.environ.copy()
            environment["ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"] = directory
            for arguments in cases:
                with self.subTest(arguments=arguments):
                    result = subprocess.run(
                        [
                            str(SCRIPTS_DIR / "emulator.sh"),
                            "--print-command",
                            *arguments,
                        ],
                        check=False,
                        capture_output=True,
                        env=environment,
                        text=True,
                    )
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertIn("fixed by --lane", result.stderr)

    def test_launcher_guards_gradle_overlap_and_low_host_memory(self) -> None:
        launcher = (SCRIPTS_DIR / "emulator.sh").read_text(encoding="utf-8")

        self.assertIn("GradleWrapperMain", launcher)
        self.assertIn("GradleDaemon", launcher)
        self.assertIn("MemAvailable:", launcher)
        self.assertIn("6 * 1024 * 1024", launcher)
        self.assertIn("less than 6 GiB", launcher)

    def test_runtime_identity_accepts_all_three_lanes(self) -> None:
        for lane in ("api26", "4k", "16k"):
            with self.subTest(lane=lane):
                result = self.verify_runtime(lane)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_runtime_identity_uses_getconf_with_the_api26_fallback(self) -> None:
        verifier = (SCRIPTS_DIR / "verify-emulator-runtime.sh").read_text(
            encoding="utf-8",
        )

        self.assertIn("shell getconf PAGE_SIZE", verifier)
        self.assertIn('[[ "$LANE" == api26 ]]', verifier)
        self.assertIn("/proc/self/smaps", verifier)

    def test_runtime_identity_rejects_each_pinned_field_mismatch(self) -> None:
        cases = (
            ({"avd": "wrong_avd"}, "running AVD"),
            ({"api": "27"}, "API level"),
            ({"page_size": "16384"}, "page size"),
            ({"fingerprint": "wrong/fingerprint"}, "build fingerprint"),
        )
        for changes, expected_message in cases:
            with self.subTest(changes=changes):
                result = self.verify_runtime("api26", **changes)
                self.assertEqual(1, result.returncode)
                self.assertIn(expected_message, result.stderr)

    def test_runtime_identity_rejects_extra_online_or_offline_targets(self) -> None:
        for state in ("device", "offline"):
            with self.subTest(state=state):
                result = self.verify_runtime(
                    "api26",
                    devices=(
                        "emulator-5558\\tdevice\\n"
                        f"emulator-5560\\t{state}\\n"
                    ),
                )
                self.assertEqual(1, result.returncode)
                self.assertIn("only emulator-5558 may be present", result.stderr)
                self.assertIn(f"emulator-5560 ({state})", result.stderr)

    def test_runtime_identity_diagnoses_intended_offline_target(self) -> None:
        result = self.verify_runtime(
            "api26",
            devices="emulator-5558\\toffline\\n",
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("emulator-5558 is in ADB state 'offline'", result.stderr)

    def test_health_connected_lane_enforces_api26_runtime_identity_first(self) -> None:
        result = self.verify_runtime(
            "api26",
            fingerprint="wrong/fingerprint",
            through_health=True,
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("build fingerprint", result.stderr)
        self.assertIn("connected emulator identity mismatch", result.stderr)

    def test_health_requires_fresh_engine_selector_paths_after_combined_tests(
        self,
    ) -> None:
        health = (SCRIPTS_DIR / "health.sh").read_text(encoding="utf-8")

        self.assertIn("run_isolated_tokenizer_instrumentation", health)
        self.assertIn(
            "-e ankiMinerExpectedTokenizerPath engine_shared_tagger",
            health,
        )
        self.assertIn(
            "com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest",
            health,
        )
        self.assertIn(
            "com.ankiminer.android.TokenizerS1aInstrumentedTest",
            health,
        )
        self.assertNotIn("InstrumentedTest#", health)
        self.assertIn(
            'install_isolated_instrumentation_artifacts "$emulator_apk" '
            '"$emulator_test_apk"',
            health,
        )
        gradle_index = health.index("./gradlew --no-daemon")
        reinstall_index = health.index(
            'install_isolated_instrumentation_artifacts "$emulator_apk" '
            '"$emulator_test_apk"',
        )
        isolated_index = health.index("run_isolated_tokenizer_instrumentation \\\n")
        self.assertLess(
            gradle_index,
            reinstall_index,
        )
        self.assertLess(reinstall_index, isolated_index)

    def test_health_runs_s4_in_a_fresh_process_from_reinstalled_artifacts(self) -> None:
        health = (SCRIPTS_DIR / "health.sh").read_text(encoding="utf-8")

        self.assertIn("run_isolated_s4_instrumentation", health)
        self.assertIn("-e ankiMinerRunS4 true", health)
        self.assertIn("-e ankiMinerExpectedFreshProcess true", health)
        self.assertIn(
            "-e class com.ankiminer.android.S4EngineSmokeInstrumentedTest",
            health,
        )
        self.assertIn("S4_EMULATOR_METRICS ", health)
        reinstall_index = health.index(
            'install_isolated_instrumentation_artifacts "$emulator_apk" '
            '"$emulator_test_apk"',
        )
        s4_index = health.index("        run_isolated_s4_instrumentation")
        self.assertLess(reinstall_index, s4_index)

    def test_runner_rejects_selector_conflicts_duplicates_and_invalid_values(
        self,
    ) -> None:
        cases = (
            ("--lane", "api26", "--lane", "4k"),
            ("--page-size", "4k", "--page-size", "16k"),
            ("--lane", "4k", "--page-size", "4k"),
            ("--lane", "invalid"),
            ("--page-size", "api26"),
            ("--page-size", "invalid"),
            ("--lane=4k",),
            ("--page-size=4k",),
        )
        with tempfile.TemporaryDirectory() as directory:
            environment = os.environ.copy()
            environment["ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"] = directory
            for arguments in cases:
                with self.subTest(arguments=arguments):
                    result = subprocess.run(
                        [
                            str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                            *arguments,
                        ],
                        check=False,
                        capture_output=True,
                        env=environment,
                        text=True,
                    )
                    self.assertEqual(2, result.returncode, result.stderr)

    def test_runner_maps_all_lanes_to_health_log_serial_and_own_process(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state"
            state.mkdir()
            platform_tools = root / "sdk" / "platform-tools"
            platform_tools.mkdir(parents=True)

            adb = platform_tools / "adb"
            adb.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_STATE/adb.log"
if [[ "${1:-}" == "devices" ]]; then
    echo 'List of devices attached'
    if [[ -f "$FAKE_STATE/running" ]]; then
        printf '%s\\tdevice\\n' "$FAKE_SERIAL"
    fi
    exit 0
fi
if [[ "${1:-}" == "-s" ]]; then
    shift 2
fi
if [[ "${1:-}" == "shell" && "${2:-}" == "getprop" ]]; then
    case "${3:-}" in
        sys.boot_completed) [[ -f "$FAKE_STATE/running" ]] && echo 1 ;;
        ro.build.version.sdk) echo "$FAKE_API" ;;
        ro.build.fingerprint) printf '%s\\n' "$FAKE_FINGERPRINT" ;;
        *) exit 1 ;;
    esac
elif [[ "${1:-}" == "emu" && "${2:-}" == "avd" ]]; then
    printf '%s\\nOK\\n' "$FAKE_AVD"
elif [[ "${1:-}" == "shell" && "${2:-}" == "getconf" && "${3:-}" == "PAGE_SIZE" ]]; then
    [[ "$FAKE_API" != 26 ]] || exit 127
    echo "$FAKE_PAGE_SIZE"
elif [[ "${1:-}" == "shell" && "${2:-}" == "cat" && "${3:-}" == "/proc/self/smaps" ]]; then
    printf 'KernelPageSize: %s kB\n' "$((FAKE_PAGE_SIZE / 1024))"
elif [[ "${1:-}" == "emu" && "${2:-}" == "kill" ]]; then
    kill -TERM "$(cat "$FAKE_STATE/emulator.pid")"
elif [[ "${1:-}" == "shell" && "${2:-}" == "input" ]]; then
    :
else
    exit 1
fi
""",
                encoding="utf-8",
            )
            adb.chmod(0o755)

            launcher = root / "fake-emulator.sh"
            launcher.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$@" >"$FAKE_STATE/launcher.args"
echo $$ >"$FAKE_STATE/emulator.pid"
touch "$FAKE_STATE/running"
trap 'rm -f "$FAKE_STATE/running"; exit 0' TERM INT
while :; do sleep 0.1; done
""",
                encoding="utf-8",
            )
            launcher.chmod(0o755)

            health = root / "fake-health.sh"
            health.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$@" >"$FAKE_STATE/health.args"
""",
                encoding="utf-8",
            )
            health.chmod(0o755)

            lanes = (
                ("api26", "emulator-5558", "anki_miner_api26", "26", "4096"),
                ("4k", "emulator-5554", "anki_miner_api36", "36", "4096"),
                ("16k", "emulator-5556", "anki_miner_api36_ps16k", "36", "16384"),
            )
            for lane, serial, avd, api, page_size in lanes:
                with self.subTest(lane=lane):
                    environment = os.environ.copy()
                    environment.update(
                        {
                            "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                            "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                            "ANKI_MINER_HEALTH_SCRIPT": str(health),
                            "EMULATOR_BOOT_TIMEOUT_SECONDS": "5",
                            "FAKE_STATE": str(state),
                            "FAKE_SERIAL": serial,
                            "FAKE_AVD": avd,
                            "FAKE_API": api,
                            "FAKE_PAGE_SIZE": page_size,
                            "FAKE_FINGERPRINT": self.API26_FINGERPRINT,
                            "ANKI_MINER_TEST_UNIDIC_DIR": str(root),
                        },
                    )
                    result = subprocess.run(
                        [
                            str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                            "--lane",
                            lane,
                        ],
                        check=False,
                        capture_output=True,
                        env=environment,
                        text=True,
                        timeout=10,
                    )

                    self.assertEqual(0, result.returncode, result.stderr)
                    launcher_arguments = (state / "launcher.args").read_text(
                        encoding="utf-8",
                    ).splitlines()
                    self.assertIn("--test-session", launcher_arguments)
                    self.assertEqual(
                        lane,
                        launcher_arguments[launcher_arguments.index("--lane") + 1],
                    )
                    self.assertEqual(
                        ["--connected", lane],
                        (state / "health.args").read_text(
                            encoding="utf-8",
                        ).splitlines(),
                    )
                    self.assertIn(
                        f"-s {serial} emu kill",
                        (state / "adb.log").read_text(encoding="utf-8"),
                    )
                    self.assertTrue((root / f"emulator-{lane}.log").is_file())
                    self.assertFalse((state / "running").exists())

    def test_runner_refuses_an_already_online_target_without_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state"
            state.mkdir()
            platform_tools = root / "sdk" / "platform-tools"
            platform_tools.mkdir(parents=True)

            adb = platform_tools / "adb"
            adb.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_STATE/adb.log"
if [[ "${1:-}" == "devices" ]]; then
    printf 'List of devices attached\\nemulator-5554\\tdevice\\n'
    exit 0
fi
exit 99
""",
                encoding="utf-8",
            )
            adb.chmod(0o755)

            launcher = root / "must-not-launch.sh"
            launcher.write_text(
                """#!/usr/bin/env bash
touch "$FAKE_STATE/launcher-ran"
""",
                encoding="utf-8",
            )
            launcher.chmod(0o755)
            health = root / "must-not-test.sh"
            health.write_text(
                """#!/usr/bin/env bash
touch "$FAKE_STATE/health-ran"
""",
                encoding="utf-8",
            )
            health.chmod(0o755)

            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                    "ANKI_MINER_HEALTH_SCRIPT": str(health),
                    "FAKE_STATE": str(state),
                },
            )
            for arguments in ((), ("--page-size", "4k")):
                with self.subTest(arguments=arguments):
                    (state / "adb.log").unlink(missing_ok=True)
                    result = subprocess.run(
                        [str(SCRIPTS_DIR / "run-emulator-tests.sh"), *arguments],
                        check=False,
                        capture_output=True,
                        env=environment,
                        text=True,
                        timeout=5,
                    )

                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("already online", result.stderr)
                    self.assertIn("adb -s emulator-5554 emu kill", result.stderr)
                    self.assertFalse((state / "launcher-ran").exists())
                    self.assertFalse((state / "health-ran").exists())
                    self.assertEqual(
                        ["devices"],
                        (state / "adb.log").read_text(
                            encoding="utf-8",
                        ).splitlines(),
                    )

    def test_runner_preflight_rejects_unrelated_and_offline_adb_targets(self) -> None:
        cases = (
            ("emulator-5560\\tdevice\\n", "no pre-existing ADB targets"),
            ("emulator-5560\\toffline\\n", "no pre-existing ADB targets"),
            ("emulator-5558\\toffline\\n", "ADB state 'offline'"),
        )
        for device_line, expected_message in cases:
            with self.subTest(device_line=device_line):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    state = root / "state"
                    state.mkdir()
                    platform_tools = root / "sdk" / "platform-tools"
                    platform_tools.mkdir(parents=True)
                    adb = platform_tools / "adb"
                    adb.write_text(
                        """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "devices" ]]; then
    printf 'List of devices attached\n%b' "$FAKE_DEVICE_LINE"
    exit 0
fi
exit 99
""",
                        encoding="utf-8",
                    )
                    adb.chmod(0o755)
                    launcher = root / "must-not-launch.sh"
                    launcher.write_text(
                        "#!/usr/bin/env bash\ntouch \"$FAKE_STATE/launcher-ran\"\n",
                        encoding="utf-8",
                    )
                    launcher.chmod(0o755)
                    environment = os.environ.copy()
                    environment.update(
                        {
                            "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                            "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                            "FAKE_DEVICE_LINE": device_line,
                            "FAKE_STATE": str(state),
                        },
                    )
                    result = subprocess.run(
                        [
                            str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                            "--lane",
                            "api26",
                        ],
                        check=False,
                        capture_output=True,
                        env=environment,
                        text=True,
                        timeout=5,
                    )

                    self.assertEqual(1, result.returncode, result.stderr)
                    self.assertIn(expected_message, result.stderr)
                    self.assertFalse((state / "launcher-ran").exists())

    def test_s1a_runner_executes_both_page_size_lanes_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text("{}", encoding="utf-8")
            dicdir = root / "dicdir"
            dicdir.mkdir()
            log = root / "runner.log"
            wheel_log = root / "wheel.log"
            recipe_key = "a" * 64
            build_key = "b" * 64
            wheel_tool = root / "wheel-tool.sh"
            wheel_tool.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >"$S1A_WHEEL_LOG"
printf '{"schema":2,"recipe_key":"%s","build_key":"%s"}\\n' \
    "$S1A_RECIPE_KEY" "$S1A_BUILD_KEY"
""",
                encoding="utf-8",
            )
            wheel_tool.chmod(0o755)
            runner = root / "runner.sh"
            runner.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s|%s|%s\n' \
    "$*" \
    "$ORG_GRADLE_PROJECT_ankiMinerS1aManifest" \
    "$ANKI_MINER_TEST_UNIDIC_DIR" >>"$S1A_RUNNER_LOG"
""",
                encoding="utf-8",
            )
            runner.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_S1A_EMULATOR_RUNNER": str(runner),
                    "ANKI_MINER_S1A_WHEEL_TOOL": str(wheel_tool),
                    "S1A_RUNNER_LOG": str(log),
                    "S1A_WHEEL_LOG": str(wheel_log),
                    "S1A_RECIPE_KEY": recipe_key,
                    "S1A_BUILD_KEY": build_key,
                },
            )
            result = subprocess.run(
                [
                    str(SCRIPTS_DIR / "run-s1a-emulator-tests.sh"),
                    "--manifest",
                    str(manifest),
                    "--unidic-dir",
                    str(dicdir),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                [
                    f"--page-size 4k|{manifest}|{dicdir}",
                    f"--page-size 16k|{manifest}|{dicdir}",
                ],
                log.read_text(encoding="utf-8").splitlines(),
            )
            self.assertEqual(
                f"verify-publication --manifest {manifest}\n",
                wheel_log.read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()
