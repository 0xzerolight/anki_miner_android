from __future__ import annotations

import os
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RUN_APP_SCRIPT = REPO_ROOT / "scripts/run-app.sh"
RESOURCE_SCRIPT = REPO_ROOT / "scripts/android-test-resources.sh"


class RunAppBootSafetyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.checkout = self.root / "checkout"
        self.scripts = self.checkout / "scripts"
        self.fake_bin = self.root / "bin"
        self.toolchain = self.root / "toolchain"
        self.emulator_log = self.root / "emulator.log"
        self.emulator_pid_file = self.root / "emulator.pid"
        self.scripts.mkdir(parents=True)
        self.fake_bin.mkdir()
        shutil.copy2(RUN_APP_SCRIPT, self.scripts / "run-app.sh")
        shutil.copy2(RESOURCE_SCRIPT, self.scripts / "android-test-resources.sh")
        self._write_script(
            self.scripts / "android-env.sh",
            """
export ANKI_MINER_ANDROID_TOOLCHAIN_ROOT="$FAKE_TOOLCHAIN"
export ANDROID_HOME="$FAKE_TOOLCHAIN/sdk"
export ANDROID_AVD_API26_NAME="anki_miner_api26"
export ANDROID_EMULATOR_API26_PORT="5558"
export ANDROID_EMULATOR_API26_SERIAL="emulator-5558"
export PATH="$FAKE_BIN:$PATH"
""",
        )
        self._write_script(
            self.checkout / "gradlew",
            """
mkdir -p "$FAKE_CHECKOUT/app/build/outputs/apk/emulator/debug"
touch "$FAKE_CHECKOUT/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk"
""",
        )
        self._write_script(self.fake_bin / "pgrep", "exit 1\n")
        meminfo = self.root / "meminfo"
        meminfo.write_text(
            "MemAvailable: 8388608 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n",
            encoding="utf-8",
        )
        self.environment = os.environ.copy()
        self.environment.update(
            {
                "ANKI_MINER_EMULATOR_ADB_TIMEOUT_SECONDS": "1",
                "ANKI_MINER_EMULATOR_BOOT_POLL_SECONDS": "0.05",
                "ANKI_MINER_EMULATOR_BOOT_TIMEOUT_SECONDS": "1",
                "ANKI_MINER_EMULATOR_LOG": str(self.emulator_log),
                "ANKI_MINER_MEMINFO_PATH": str(meminfo),
                "FAKE_BIN": str(self.fake_bin),
                "FAKE_CHECKOUT": str(self.checkout),
                "FAKE_ROOT": str(self.root),
                "FAKE_TOOLCHAIN": str(self.toolchain),
                "PATH": f"{self.fake_bin}:{self.environment['PATH']}",
            },
        )
        self.addCleanup(self._stop_recorded_emulator)

    @staticmethod
    def _write_script(path: Path, body: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"#!/usr/bin/env bash\n{body.lstrip()}", encoding="utf-8")
        path.chmod(0o755)

    def _set_emulator(self, body: str) -> None:
        self._write_script(self.toolchain / "sdk/emulator/emulator", body)

    def _run_launcher(self) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                ["bash", str(self.scripts / "run-app.sh")],
                cwd=self.checkout,
                check=False,
                capture_output=True,
                env=self.environment,
                text=True,
                timeout=4,
            )
        except subprocess.TimeoutExpired:
            self._stop_recorded_emulator()
            self.fail("run-app.sh exceeded the boot-liveness bound")

    def _stop_recorded_emulator(self) -> None:
        if not self.emulator_pid_file.is_file():
            return
        pid = int(self.emulator_pid_file.read_text(encoding="utf-8"))
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.01)
        os.kill(pid, signal.SIGKILL)

    def test_run_app_stops_when_owned_emulator_exits_before_boot(self) -> None:
        self._set_emulator(
            """
printf '%s\n' "$$" >"$FAKE_ROOT/emulator.pid"
echo "early emulator failure"
exit 23
""",
        )
        self._write_script(
            self.fake_bin / "adb",
            """
if [[ "${1:-}" == "devices" ]]; then
    echo "List of devices attached"
elif [[ "${3:-}" == "wait-for-device" ]]; then
    sleep 60
else
    exit 1
fi
""",
        )

        # This test races liveness detection against the boot timeout, and the
        # shared 1 s budget lets a loaded machine time out first and report the
        # wrong failure. Only the timeout test needs that budget to be short.
        self.environment["ANKI_MINER_EMULATOR_BOOT_TIMEOUT_SECONDS"] = "10"

        result = self._run_launcher()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Emulator process exited before boot completed", result.stderr)
        self.assertIn(str(self.emulator_log), result.stderr)
        self.assertIn("early emulator failure", self.emulator_log.read_text(encoding="utf-8"))

    def test_run_app_times_out_and_stops_only_its_owned_emulator(self) -> None:
        self._set_emulator(
            """
printf '%s\n' "$$" >"$FAKE_ROOT/emulator.pid"
trap 'touch "$FAKE_ROOT/emulator-stopped"; exit 0' TERM
while true; do
    sleep 0.05
done
""",
        )
        self._write_script(
            self.fake_bin / "adb",
            """
if [[ "${1:-}" == "devices" ]]; then
    echo "List of devices attached"
elif [[ "${3:-}" == "wait-for-device" ]]; then
    exit 0
elif [[ "${3:-}" == "get-state" ]]; then
    echo "device"
elif [[ "${3:-}" == "shell" && "${4:-}" == "getprop" ]]; then
    echo "0"
else
    exit 1
fi
""",
        )

        result = self._run_launcher()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not boot within 1 seconds", result.stderr)
        self.assertIn(str(self.emulator_log), result.stderr)
        self.assertTrue((self.root / "emulator-stopped").is_file())


if __name__ == "__main__":
    unittest.main()
