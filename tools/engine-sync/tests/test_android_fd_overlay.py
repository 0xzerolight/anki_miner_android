from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


def _load_android_fd():
    path = (
        Path(__file__).parents[1]
        / "overrides/anki_miner/utils/android_fd.py"
    )
    spec = importlib.util.spec_from_file_location("android_fd_overlay", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AndroidFdOverlayTests(unittest.TestCase):
    def test_proc_fd_is_duplicated_inherited_and_closed(self) -> None:
        android_fd = _load_android_fd()
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "日本語.mkv"
            source.write_text("fd inherited", encoding="utf-8")
            original = os.open(source, os.O_RDONLY)
            try:
                command = [
                    sys.executable,
                    "-c",
                    "from pathlib import Path; import sys; print(Path(sys.argv[1]).read_text())",
                    f"/proc/self/fd/{original}",
                ]
                with android_fd.inherited_fd_command(command) as (
                    rewritten,
                    pass_fds,
                ):
                    self.assertEqual(len(pass_fds), 1)
                    inherited = pass_fds[0]
                    self.assertNotEqual(inherited, original)
                    self.assertEqual(rewritten[-1], f"/proc/self/fd/{inherited}")
                    completed = subprocess.run(
                        rewritten,
                        pass_fds=pass_fds,
                        check=True,
                        capture_output=True,
                        text=True,
                    )
                self.assertEqual(completed.stdout.strip(), "fd inherited")
                with self.assertRaises(OSError):
                    os.fstat(inherited)
                os.fstat(original)
            finally:
                os.close(original)

    def test_repeated_reference_reuses_one_per_child_duplicate(self) -> None:
        android_fd = _load_android_fd()
        read_fd, write_fd = os.pipe()
        try:
            command = [
                "ffmpeg",
                f"/proc/self/fd/{read_fd}",
                f"/proc/self/fd/{read_fd}",
            ]
            with android_fd.inherited_fd_command(command) as (rewritten, pass_fds):
                self.assertEqual(len(pass_fds), 1)
                self.assertEqual(rewritten[1], rewritten[2])
        finally:
            os.close(read_fd)
            os.close(write_fd)

    def test_normal_paths_do_not_request_descriptor_inheritance(self) -> None:
        android_fd = _load_android_fd()
        command = ["ffprobe", "/storage/emulated/0/video.mkv"]
        with android_fd.inherited_fd_command(command) as (rewritten, pass_fds):
            self.assertEqual(rewritten, command)
            self.assertEqual(pass_fds, ())

    def test_closed_source_descriptor_fails_before_spawn(self) -> None:
        android_fd = _load_android_fd()
        read_fd, write_fd = os.pipe()
        os.close(read_fd)
        try:
            with self.assertRaises(OSError):
                with android_fd.inherited_fd_command(
                    ["ffprobe", f"/proc/self/fd/{read_fd}"]
                ):
                    self.fail("invalid fd must not reach the spawn body")
        finally:
            os.close(write_fd)


if __name__ == "__main__":
    unittest.main()
