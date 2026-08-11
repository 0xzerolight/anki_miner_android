from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

RESOURCE_SCRIPT = Path(__file__).resolve().parents[1] / "android-test-resources.sh"
EMULATOR_SCRIPT = Path(__file__).resolve().parents[1] / "emulator.sh"
EMULATOR_LANES_SCRIPT = Path(__file__).resolve().parents[1] / "emulator-lanes.sh"
RUN_APP_SCRIPT = Path(__file__).resolve().parents[1] / "run-app.sh"


class AndroidTestResourceTest(unittest.TestCase):
    def _fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path, dict[str, str]]:
        temporary: tempfile.TemporaryDirectory[str] = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        environment = os.environ.copy()
        environment.update(
            {
                "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root / "toolchain"),
                "PATH": f"{bin_dir}:{environment['PATH']}",
                "FAKE_ROOT": str(root),
            },
        )
        return temporary, bin_dir, environment

    @staticmethod
    def _script(path: Path, body: str) -> None:
        path.write_text(f"#!/usr/bin/env bash\n{body}", encoding="utf-8")
        path.chmod(0o755)

    def test_gradle_entry_rejects_an_online_emulator_before_build(self) -> None:
        temporary, bin_dir, environment = self._fixture()
        with temporary:
            self._script(
                bin_dir / "adb",
                "printf 'List of devices attached\\nemulator-5554\\tdevice\\n'\n",
            )
            self._script(bin_dir / "pgrep", "exit 1\n")
            gradle = bin_dir / "gradlew"
            self._script(gradle, 'touch "$FAKE_ROOT/gradle-ran"\n')
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; anki_miner_run_gradle "$2" :app:test',
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                    str(gradle),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("emulator is running", result.stderr)
            self.assertFalse((Path(temporary.name) / "gradle-ran").exists())

    def test_connected_boundary_rejects_a_running_gradle_process(self) -> None:
        temporary, bin_dir, environment = self._fixture()
        with temporary:
            self._script(bin_dir / "pgrep", "exit 0\n")
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; anki_miner_require_no_gradle',
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("while Gradle is running", result.stderr)

    def test_gradle_entry_rejects_a_second_gradle_process(self) -> None:
        temporary, bin_dir, environment = self._fixture()
        with temporary:
            self._script(bin_dir / "pgrep", "exit 0\n")
            gradle = bin_dir / "gradlew"
            self._script(gradle, 'touch "$FAKE_ROOT/gradle-ran"\n')
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; anki_miner_run_gradle "$2" :app:test',
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                    str(gradle),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("while Gradle is running", result.stderr)
            self.assertFalse((Path(temporary.name) / "gradle-ran").exists())

    def test_emulator_launchers_do_not_call_retired_capacity_gate(self) -> None:
        for launcher in (EMULATOR_SCRIPT, RUN_APP_SCRIPT):
            with self.subTest(launcher=launcher.name):
                self.assertNotIn(
                    "anki_miner_require_emulator_capacity",
                    launcher.read_text(encoding="utf-8"),
                )

    def test_emulator_lock_stays_held_after_launcher_exec(self) -> None:
        temporary, _, environment = self._fixture()
        with temporary:
            environment["ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"] = temporary.name
            ready = Path(temporary.name) / "ready"
            holder = subprocess.Popen(
                [
                    "bash",
                    "-c",
                    ('source "$1"; ' "anki_miner_acquire_emulator_lock; " 'touch "$2"; ' "exec sleep 60"),
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                    str(ready),
                ],
                env=environment,
                stderr=subprocess.PIPE,
                text=True,
            )

            def cleanup_holder() -> None:
                if holder.poll() is None:
                    holder.kill()
                    holder.wait(timeout=2)
                if holder.stderr is not None:
                    holder.stderr.close()

            self.addCleanup(cleanup_holder)
            deadline = time.monotonic() + 2
            while not ready.exists() and holder.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
            if not ready.exists():
                holder.terminate()
                _, stderr = holder.communicate(timeout=2)
                self.fail(stderr)

            contender = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; anki_miner_acquire_emulator_lock',
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertNotEqual(0, contender.returncode)
            self.assertIn("another emulator launcher", contender.stderr)
            holder.terminate()
            holder.wait(timeout=2)

    def test_gradle_holds_shared_workload_lock_for_child_lifetime(self) -> None:
        temporary, bin_dir, environment = self._fixture()
        with temporary:
            self._script(bin_dir / "adb", "echo 'List of devices attached'\n")
            self._script(bin_dir / "pgrep", "exit 1\n")
            gradle = bin_dir / "gradlew"
            self._script(
                gradle,
                """
touch "$FAKE_ROOT/gradle-started"
while [[ ! -f "$FAKE_ROOT/release-gradle" ]]; do
    sleep 0.01
done
""",
            )
            holder = subprocess.Popen(
                [
                    "bash",
                    "-c",
                    'source "$1"; anki_miner_run_gradle "$2" :app:test',
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                    str(gradle),
                ],
                env=environment,
                stderr=subprocess.PIPE,
                text=True,
            )

            def cleanup_holder() -> None:
                release = Path(temporary.name) / "release-gradle"
                if release.parent.exists():
                    release.touch()
                elif holder.poll() is None:
                    holder.terminate()
                if holder.poll() is None:
                    holder.wait(timeout=2)
                if holder.stderr is not None:
                    holder.stderr.close()

            self.addCleanup(cleanup_holder)
            ready = Path(temporary.name) / "gradle-started"
            deadline = time.monotonic() + 2
            while not ready.exists() and holder.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
            if not ready.exists():
                _, stderr = holder.communicate(timeout=2)
                self.fail(stderr)

            contender = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; anki_miner_acquire_workload_lock',
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertNotEqual(0, contender.returncode)
            self.assertIn("Gradle or emulator workload", contender.stderr)
            (Path(temporary.name) / "release-gradle").touch()
            holder.wait(timeout=2)

    def test_emulator_launchers_acquire_the_shared_workload_lock(self) -> None:
        for launcher in (EMULATOR_SCRIPT, RUN_APP_SCRIPT):
            with self.subTest(launcher=launcher.name):
                self.assertIn(
                    "anki_miner_acquire_workload_lock",
                    launcher.read_text(encoding="utf-8"),
                )

    def test_emulator_launcher_ignores_low_memory_and_swap_when_checking_process_exclusion(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkout = root / "checkout"
            scripts = checkout / "scripts"
            fake_bin = root / "bin"
            toolchain = root / "toolchain"
            scripts.mkdir(parents=True)
            fake_bin.mkdir()
            shutil.copy2(EMULATOR_SCRIPT, scripts / "emulator.sh")
            shutil.copy2(EMULATOR_LANES_SCRIPT, scripts / "emulator-lanes.sh")
            shutil.copy2(RESOURCE_SCRIPT, scripts / "android-test-resources.sh")
            self._script(
                scripts / "android-env.sh",
                """
export ANKI_MINER_ANDROID_TOOLCHAIN_ROOT="$FAKE_TOOLCHAIN"
export ANDROID_HOME="$FAKE_TOOLCHAIN/sdk"
export ANDROID_AVD_API26_NAME="anki_miner_api26"
export ANDROID_AVD_4K_NAME="anki_miner_api36"
export ANDROID_AVD_16K_NAME="anki_miner_api36_ps16k"
export ANDROID_EMULATOR_API26_PORT="5558"
export ANDROID_EMULATOR_4K_PORT="5554"
export ANDROID_EMULATOR_16K_PORT="5556"
export ANDROID_EMULATOR_API26_SERIAL="emulator-5558"
export ANDROID_EMULATOR_4K_SERIAL="emulator-5554"
export ANDROID_EMULATOR_16K_SERIAL="emulator-5556"
export ANDROID_EMULATOR_API26_FINGERPRINT="api26-fingerprint"
export PATH="$FAKE_BIN:$PATH"
""",
            )
            self._script(scripts / "verify-android-toolchain.sh", "exit 0\n")
            emulator = toolchain / "sdk/emulator/emulator"
            emulator.parent.mkdir(parents=True)
            self._script(
                emulator,
                """
if [[ "${1:-}" == "-list-avds" ]]; then
    printf '%s\n' anki_miner_api26 anki_miner_api36 anki_miner_api36_ps16k
else
    touch "$FAKE_ROOT/emulator-launched"
fi
""",
            )
            shutil.copy2(emulator, fake_bin / "emulator")
            self._script(
                fake_bin / "adb",
                "printf 'List of devices attached\\n'\n",
            )
            self._script(fake_bin / "pgrep", "exit 1\n")
            self._script(fake_bin / "ss", "exit 1\n")
            meminfo = root / "meminfo"
            meminfo.write_text(
                "MemAvailable: 1024 kB\nSwapTotal: 8388608 kB\nSwapFree: 512 kB\n",
                encoding="utf-8",
            )
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_MEMINFO_PATH": str(meminfo),
                    "FAKE_BIN": str(fake_bin),
                    "FAKE_ROOT": str(root),
                    "FAKE_TOOLCHAIN": str(toolchain),
                    "PATH": f"{fake_bin}:{environment['PATH']}",
                },
            )

            result = subprocess.run(
                [
                    "bash",
                    str(scripts / "emulator.sh"),
                    "--lane",
                    "16k",
                    "--headless",
                    "--software",
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue((root / "emulator-launched").exists())

    def test_every_gradle_entry_gets_the_explicit_resource_flags(self) -> None:
        temporary, bin_dir, environment = self._fixture()
        with temporary:
            self._script(bin_dir / "adb", "echo 'List of devices attached'\n")
            self._script(bin_dir / "pgrep", "exit 1\n")
            gradle = bin_dir / "gradlew"
            self._script(gradle, 'printf \'%s\\n\' "$@" >"$FAKE_ROOT/args"\n')
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; anki_miner_run_gradle "$2" :app:test',
                    "resource-test",
                    str(RESOURCE_SCRIPT),
                    str(gradle),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            arguments = (
                (Path(temporary.name) / "args")
                .read_text(
                    encoding="utf-8",
                )
                .splitlines()
            )
            self.assertIn("--no-daemon", arguments)
            self.assertIn("--no-parallel", arguments)
            self.assertIn("--max-workers=1", arguments)
            self.assertIn("-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8", arguments)
            self.assertIn("--dependency-verification", arguments)
            self.assertEqual(":app:test", arguments[-1])


if __name__ == "__main__":
    unittest.main()
