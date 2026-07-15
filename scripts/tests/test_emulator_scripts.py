from __future__ import annotations

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


SCRIPTS_DIR = Path(__file__).resolve().parents[1]


class EmulatorScriptTest(unittest.TestCase):
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

    def test_interactive_command_keeps_userdata_and_snapshots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            command = self.print_command(Path(directory))
        self.assertNotIn("-wipe-data", command)
        self.assertNotIn("-no-snapshot-load", command)
        self.assertNotIn("-no-snapshot-save", command)

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

    def test_runner_uses_test_session_and_stops_only_its_process(self) -> None:
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
        printf 'emulator-5554\\tdevice\\n'
    fi
    exit 0
fi
if [[ "${1:-}" == "-s" ]]; then
    shift 2
fi
if [[ "${1:-}" == "shell" && "${2:-}" == "getprop" ]]; then
    [[ -f "$FAKE_STATE/running" ]] && echo 1
elif [[ "${1:-}" == "emu" && "${2:-}" == "avd" ]]; then
    printf 'anki_miner_api36\\nOK\\n'
elif [[ "${1:-}" == "shell" && "${2:-}" == "getconf" ]]; then
    echo 4096
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

            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "ANKI_MINER_EMULATOR_LAUNCHER": str(launcher),
                    "ANKI_MINER_HEALTH_SCRIPT": str(health),
                    "EMULATOR_BOOT_TIMEOUT_SECONDS": "5",
                    "FAKE_STATE": str(state),
                },
            )
            result = subprocess.run(
                [str(SCRIPTS_DIR / "run-emulator-tests.sh"), "--page-size", "4k"],
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
                ["--connected", "4k"],
                (state / "health.args").read_text(encoding="utf-8").splitlines(),
            )
            self.assertIn(
                "-s emulator-5554 emu kill",
                (state / "adb.log").read_text(encoding="utf-8"),
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
            result = subprocess.run(
                [str(SCRIPTS_DIR / "run-emulator-tests.sh"), "--page-size", "4k"],
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
                (state / "adb.log").read_text(encoding="utf-8").splitlines(),
            )

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
