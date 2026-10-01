from __future__ import annotations

import importlib.util
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

# PID_MAX_LIMIT on 64-bit Linux is 2**22, so /proc can never list this PID.
_IMPOSSIBLE_PID = 2**22 + 1


def _load_psutil_shim():
    path = Path(__file__).parents[1] / "overrides/psutil/__init__.py"
    spec = importlib.util.spec_from_file_location("android_psutil_shim", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _kernel_create_time(pid: int) -> float:
    """What real psutil reports on Linux: /proc/stat btime + starttime ticks."""
    with open("/proc/stat", "rb") as handle:
        btime = next(
            float(line.split()[1]) for line in handle if line.startswith(b"btime")
        )
    with open(f"/proc/{pid}/stat", "rb") as handle:
        data = handle.read()
    ticks = int(data[data.rfind(b")") + 2 :].split()[19])
    return btime + ticks / os.sysconf("SC_CLK_TCK")


@unittest.skipUnless(Path("/proc/self/stat").is_file(), "needs Linux procfs")
class PsutilShimTests(unittest.TestCase):
    def setUp(self) -> None:
        self.psutil = _load_psutil_shim()

    def _spawn_sleeper(self, executable: str = "sleep") -> subprocess.Popen[bytes]:
        child = subprocess.Popen([executable, "30"])
        self.addCleanup(child.wait)
        self.addCleanup(child.kill)
        return child

    def test_exports_only_what_the_engine_uses(self) -> None:
        self.assertEqual(["NoSuchProcess", "Process"], sorted(self.psutil.__all__))
        self.assertTrue(issubclass(self.psutil.NoSuchProcess, Exception))

    def test_default_process_is_the_current_one(self) -> None:
        self.assertEqual(os.getpid(), self.psutil.Process().pid)
        self.assertEqual(
            f"{self.psutil.Process().create_time():.6f}",
            f"{self.psutil.Process(os.getpid()).create_time():.6f}",
        )

    @unittest.skipUnless(os.access("/proc/stat", os.R_OK), "host hides /proc/stat")
    def test_create_time_matches_the_kernel_boot_time_source(self) -> None:
        # The shim floors its boot time the way the kernel prints btime, so the
        # two agree except when the boot instant sits within microseconds of a
        # whole second.
        pid = os.getpid()
        self.assertLessEqual(
            abs(self.psutil.Process(pid).create_time() - _kernel_create_time(pid)),
            1.0,
        )

    def test_create_time_is_stable_at_the_lockfile_precision(self) -> None:
        child = self._spawn_sleeper()
        rendered = {
            f"{self.psutil.Process(child.pid).create_time():.6f}" for _ in range(200)
        }
        self.assertEqual(1, len(rendered))

    def test_child_started_after_parent_and_before_now(self) -> None:
        own = self.psutil.Process().create_time()
        child = self._spawn_sleeper()
        created = self.psutil.Process(child.pid).create_time()
        self.assertLessEqual(own, created)
        self.assertLessEqual(created, time.time() + 1.0)

    def test_process_name_with_parentheses_and_spaces(self) -> None:
        sleep = subprocess.run(
            ["sh", "-c", "command -v sleep"], capture_output=True, check=True, text=True
        ).stdout.strip()
        with tempfile.TemporaryDirectory() as directory:
            tricky = Path(directory) / "a) b (c"
            tricky.symlink_to(sleep)
            child = self._spawn_sleeper(str(tricky))
            with open(f"/proc/{child.pid}/stat", "rb") as handle:
                self.assertIn(b"(a) b (c)", handle.read())
            created = self.psutil.Process(child.pid).create_time()
        self.assertLessEqual(self.psutil.Process().create_time(), created)
        self.assertLessEqual(created, time.time() + 1.0)

    def test_missing_process_raises_no_such_process(self) -> None:
        with self.assertRaises(self.psutil.NoSuchProcess) as caught:
            self.psutil.Process(_IMPOSSIBLE_PID).create_time()
        self.assertEqual(_IMPOSSIBLE_PID, caught.exception.pid)

        child = subprocess.Popen(["true"])
        child.wait()
        with self.assertRaises(self.psutil.NoSuchProcess):
            self.psutil.Process(child.pid).create_time()

    def test_negative_pid_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            self.psutil.Process(-1)


if __name__ == "__main__":
    unittest.main()
