from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RESOURCE_SCRIPT = Path(__file__).resolve().parents[1] / "android-test-resources.sh"


class AndroidTestResourceTest(unittest.TestCase):
    def _fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path, dict[str, str]]:
        temporary: tempfile.TemporaryDirectory[str] = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        environment = os.environ.copy()
        environment.update(
            {
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

    def test_emulator_capacity_rejects_low_memory_and_low_swap(self) -> None:
        cases = (
            (
                "MemAvailable: 1024 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n",
                "less than 6 GiB",
            ),
            (
                "MemAvailable: 8388608 kB\nSwapTotal: 8388608 kB\nSwapFree: 512 kB\n",
                "less than 1 GiB of free swap",
            ),
        )
        for contents, message in cases:
            with self.subTest(message=message):
                with tempfile.TemporaryDirectory() as directory:
                    meminfo = Path(directory) / "meminfo"
                    meminfo.write_text(contents, encoding="utf-8")
                    environment = os.environ.copy()
                    environment["ANKI_MINER_MEMINFO_PATH"] = str(meminfo)
                    result = subprocess.run(
                        [
                            "bash",
                            "-c",
                            'source "$1"; anki_miner_require_emulator_capacity',
                            "resource-test",
                            str(RESOURCE_SCRIPT),
                        ],
                        check=False,
                        capture_output=True,
                        env=environment,
                        text=True,
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(message, result.stderr)

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
