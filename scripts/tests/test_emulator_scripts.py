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
            command = [
                str(SCRIPTS_DIR / "verify-emulator-runtime.sh"),
                "--lane",
                lane,
            ]
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
        resources = (SCRIPTS_DIR / "android-test-resources.sh").read_text(
            encoding="utf-8",
        )

        self.assertIn("anki_miner_require_no_gradle", launcher)
        self.assertIn("anki_miner_require_emulator_capacity", launcher)
        self.assertIn("GradleWrapperMain", resources)
        self.assertIn("GradleDaemon", resources)
        self.assertIn("MemAvailable:", resources)
        self.assertIn("6 * 1024 * 1024", resources)
        self.assertIn("SwapFree:", resources)
        self.assertIn("less than 1 GiB of free swap", resources)

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

    def test_connected_gate_requires_fresh_engine_selector_paths_after_combined_tests(
        self,
    ) -> None:
        connected = (SCRIPTS_DIR / "run-connected-emulator-tests.sh").read_text(
            encoding="utf-8",
        )

        self.assertIn("run_instrumentation_any combined", connected)
        self.assertIn(
            "-e ankiMinerExpectedTokenizerPath engine_shared_tagger",
            connected,
        )
        self.assertIn(
            "com.ankiminer.android.tokenizer.MecabNativeTokenizerInstrumentedTest",
            connected,
        )
        self.assertIn(
            "com.ankiminer.android.TokenizerS1aInstrumentedTest",
            connected,
        )
        self.assertNotIn("gradlew", connected)
        self.assertLess(
            connected.index("run_instrumentation_any combined"),
            connected.index("run_instrumentation_exact S1b 2"),
        )

    def test_connected_gate_runs_s4_in_a_fresh_process(self) -> None:
        connected = (SCRIPTS_DIR / "run-connected-emulator-tests.sh").read_text(
            encoding="utf-8",
        )

        self.assertIn("run_instrumentation_exact S4 1", connected)
        self.assertIn("-e ankiMinerRunS4 true", connected)
        self.assertIn("-e ankiMinerExpectedFreshProcess true", connected)
        self.assertIn(
            "-e class com.ankiminer.android.S4EngineSmokeInstrumentedTest",
            connected,
        )
        self.assertIn("S4_EMULATOR_METRICS ", connected)

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

    def test_runner_maps_all_lanes_to_connected_gate_and_own_process(self) -> None:
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

            connected = root / "fake-connected.sh"
            connected.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$@" >"$FAKE_STATE/connected.args"
[[ "${FAKE_CONNECTED_FAIL:-}" != true ]] || exit 23
""",
                encoding="utf-8",
            )
            connected.chmod(0o755)
            receipt = root / "receipt.json"
            receipt.write_text("{}\n", encoding="utf-8")
            receipt_command = root / "fake-receipt.sh"
            receipt_command.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            receipt_command.chmod(0o755)
            meminfo = root / "meminfo"
            meminfo.write_text(
                "MemAvailable: 8388608 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n",
                encoding="utf-8",
            )

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
                            "ANKI_MINER_CONNECTED_EMULATOR_RUNNER": str(connected),
                            "ANKI_MINER_RECEIPT_COMMAND": str(receipt_command),
                            "ANKI_MINER_MEMINFO_PATH": str(meminfo),
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
                            "--receipt",
                            str(receipt),
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
                        [
                            "--receipt",
                            str(receipt),
                            "--unidic-dir",
                            str(root),
                            "--lane",
                            lane,
                        ],
                        (state / "connected.args").read_text(
                            encoding="utf-8",
                        ).splitlines(),
                    )
                    self.assertIn(
                        f"-s {serial} emu kill",
                        (state / "adb.log").read_text(encoding="utf-8"),
                    )
                    self.assertTrue((root / f"emulator-{lane}.log").is_file())
                    self.assertFalse((state / "running").exists())
                    if lane == "4k":
                        environment["FAKE_CONNECTED_FAIL"] = "true"
                        failed = subprocess.run(
                            [
                                str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                                "--receipt",
                                str(receipt),
                                "--lane",
                                lane,
                            ],
                            check=False,
                            capture_output=True,
                            env=environment,
                            text=True,
                            timeout=10,
                        )
                        self.assertEqual(23, failed.returncode, failed.stderr)
                        self.assertFalse((state / "running").exists())

            s2 = root / "fake-s2-connected.sh"
            s2.write_text(
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" >\"$FAKE_STATE/s2.args\"\n",
                encoding="utf-8",
            )
            s2.chmod(0o755)
            ankidroid_apk = root / "AnkiDroid.apk"
            ankidroid_apk.write_bytes(b"fake")
            s2_environment = os.environ.copy()
            s2_environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                    "ANKI_MINER_S2_CONNECTED_RUNNER": str(s2),
                    "ANKI_MINER_RECEIPT_COMMAND": str(receipt_command),
                    "ANKI_MINER_MEMINFO_PATH": str(meminfo),
                    "ANKI_MINER_ANKIDROID_APK": str(ankidroid_apk),
                    "ANKI_MINER_S2_ALLOW_COLLECTION_RESET": "true",
                    "EMULATOR_BOOT_TIMEOUT_SECONDS": "5",
                    "FAKE_STATE": str(state),
                    "FAKE_SERIAL": "emulator-5554",
                    "FAKE_AVD": "anki_miner_api36",
                    "FAKE_API": "36",
                    "FAKE_PAGE_SIZE": "4096",
                    "FAKE_FINGERPRINT": self.API26_FINGERPRINT,
                },
            )
            s2_result = subprocess.run(
                [
                    str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                    "--s2",
                    "--receipt",
                    str(receipt),
                ],
                check=False,
                capture_output=True,
                env=s2_environment,
                text=True,
                timeout=10,
            )
            self.assertEqual(0, s2_result.returncode, s2_result.stderr)
            self.assertEqual(
                ["--receipt", str(receipt)],
                (state / "s2.args").read_text(encoding="utf-8").splitlines(),
            )
            self.assertFalse((state / "running").exists())

            fallback = root / "fake-s2-fallback-connected.sh"
            fallback.write_text(
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" >\"$FAKE_STATE/fallback.args\"\n",
                encoding="utf-8",
            )
            fallback.chmod(0o755)
            s2_environment["ANKI_MINER_S2_FALLBACK_CONNECTED_RUNNER"] = str(fallback)
            fallback_result = subprocess.run(
                [
                    str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                    "--s2-fallback",
                    "--receipt",
                    str(receipt),
                ],
                check=False,
                capture_output=True,
                env=s2_environment,
                text=True,
                timeout=10,
            )
            self.assertEqual(0, fallback_result.returncode, fallback_result.stderr)
            self.assertEqual(
                ["--receipt", str(receipt)],
                (state / "fallback.args").read_text(encoding="utf-8").splitlines(),
            )
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
            connected = root / "must-not-test.sh"
            connected.write_text(
                """#!/usr/bin/env bash
touch "$FAKE_STATE/connected-ran"
""",
                encoding="utf-8",
            )
            connected.chmod(0o755)
            receipt = root / "receipt.json"
            receipt.write_text("{}\n", encoding="utf-8")
            receipt_command = root / "fake-receipt.sh"
            receipt_command.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            receipt_command.chmod(0o755)
            meminfo = root / "meminfo"
            meminfo.write_text(
                "MemAvailable: 8388608 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n",
                encoding="utf-8",
            )

            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                    "ANKI_MINER_CONNECTED_EMULATOR_RUNNER": str(connected),
                    "ANKI_MINER_RECEIPT_COMMAND": str(receipt_command),
                    "ANKI_MINER_MEMINFO_PATH": str(meminfo),
                    "FAKE_STATE": str(state),
                },
            )
            for arguments in ((), ("--page-size", "4k")):
                with self.subTest(arguments=arguments):
                    (state / "adb.log").unlink(missing_ok=True)
                    result = subprocess.run(
                        [
                            str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                            "--receipt",
                            str(receipt),
                            *arguments,
                        ],
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
                    self.assertFalse((state / "connected-ran").exists())
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
                    receipt = root / "receipt.json"
                    receipt.write_text("{}\n", encoding="utf-8")
                    receipt_command = root / "fake-receipt.sh"
                    receipt_command.write_text(
                        "#!/usr/bin/env bash\nexit 0\n",
                        encoding="utf-8",
                    )
                    receipt_command.chmod(0o755)
                    meminfo = root / "meminfo"
                    meminfo.write_text(
                        "MemAvailable: 8388608 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n",
                        encoding="utf-8",
                    )
                    environment = os.environ.copy()
                    environment.update(
                        {
                            "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                            "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                            "ANKI_MINER_RECEIPT_COMMAND": str(receipt_command),
                            "ANKI_MINER_MEMINFO_PATH": str(meminfo),
                            "FAKE_DEVICE_LINE": device_line,
                            "FAKE_STATE": str(state),
                        },
                    )
                    result = subprocess.run(
                        [
                            str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                            "--receipt",
                            str(receipt),
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

    def test_runner_bounds_a_wedged_adb_before_emulator_start(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            platform_tools = root / "sdk" / "platform-tools"
            platform_tools.mkdir(parents=True)
            adb = platform_tools / "adb"
            adb.write_text("#!/usr/bin/env bash\nsleep 30\n", encoding="utf-8")
            adb.chmod(0o755)
            launcher = root / "launcher.sh"
            launcher.write_text(
                "#!/usr/bin/env bash\ntouch \"$WEDGED_ROOT/launched\"\n",
                encoding="utf-8",
            )
            launcher.chmod(0o755)
            receipt = root / "receipt.json"
            receipt.write_text("{}\n", encoding="utf-8")
            receipt_command = root / "receipt.sh"
            receipt_command.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            receipt_command.chmod(0o755)
            meminfo = root / "meminfo"
            meminfo.write_text(
                "MemAvailable: 8388608 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n",
                encoding="utf-8",
            )
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                    "ANKI_MINER_RECEIPT_COMMAND": str(receipt_command),
                    "ANKI_MINER_MEMINFO_PATH": str(meminfo),
                    "ANKI_MINER_ADB_TIMEOUT_SECONDS": "1",
                    "WEDGED_ROOT": str(root),
                },
            )
            result = subprocess.run(
                [
                    str(SCRIPTS_DIR / "run-emulator-tests.sh"),
                    "--receipt",
                    str(receipt),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
                timeout=5,
            )

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn("Could not list ADB devices", result.stderr)
            self.assertFalse((root / "launched").exists())

    def test_s1a_runner_executes_both_page_size_lanes_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text("{}", encoding="utf-8")
            dicdir = root / "dicdir"
            dicdir.mkdir()
            log = root / "runner.log"
            wheel_log = root / "wheel.log"
            prepare_log = root / "prepare.log"
            receipt = root / "receipt.json"
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
            preparer = root / "preparer.sh"
            preparer.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$S1A_PREPARE_LOG"
[[ "$1" == --receipt ]]
touch "$2"
""",
                encoding="utf-8",
            )
            preparer.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_S1A_EMULATOR_RUNNER": str(runner),
                    "ANKI_MINER_EMULATOR_PREPARER": str(preparer),
                    "ANKI_MINER_S1A_WHEEL_TOOL": str(wheel_tool),
                    "ANKI_MINER_ANDROID_TEST_RECEIPT": str(receipt),
                    "S1A_RUNNER_LOG": str(log),
                    "S1A_PREPARE_LOG": str(prepare_log),
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
                    f"--receipt {receipt} --unidic-dir {dicdir} --page-size 4k|{manifest}|{dicdir}",
                    f"--receipt {receipt} --unidic-dir {dicdir} --page-size 16k|{manifest}|{dicdir}",
                ],
                log.read_text(encoding="utf-8").splitlines(),
            )
            self.assertEqual(
                f"verify-publication --manifest {manifest}\n",
                wheel_log.read_text(encoding="utf-8"),
            )
            self.assertEqual(
                f"--receipt {receipt}\n",
                prepare_log.read_text(encoding="utf-8"),
            )

    def test_s1a_prepare_failure_never_starts_an_emulator_lane(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text("{}\n", encoding="utf-8")
            dicdir = root / "dicdir"
            dicdir.mkdir()
            wheel = root / "wheel.sh"
            wheel.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            wheel.chmod(0o755)
            preparer = root / "prepare.sh"
            preparer.write_text("#!/usr/bin/env bash\nexit 19\n", encoding="utf-8")
            preparer.chmod(0o755)
            runner = root / "runner.sh"
            runner.write_text(
                "#!/usr/bin/env bash\ntouch \"$FAILURE_ROOT/runner-ran\"\n",
                encoding="utf-8",
            )
            runner.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_S1A_WHEEL_TOOL": str(wheel),
                    "ANKI_MINER_EMULATOR_PREPARER": str(preparer),
                    "ANKI_MINER_S1A_EMULATOR_RUNNER": str(runner),
                    "ANKI_MINER_ANDROID_TEST_RECEIPT": str(root / "receipt.json"),
                    "FAILURE_ROOT": str(root),
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

            self.assertEqual(19, result.returncode, result.stderr)
            self.assertFalse((root / "runner-ran").exists())


if __name__ == "__main__":
    unittest.main()
