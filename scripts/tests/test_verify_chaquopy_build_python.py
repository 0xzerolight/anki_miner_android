from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import py_compile
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_chaquopy_build_python import (  # noqa: E402
    BuildPythonVerificationError,
    installation_payload_sha256,
    load_lock,
    validate_generated_bytecode,
    verify,
)


class ChaquopyBuildPythonVerificationTest(unittest.TestCase):
    def fixture(
        self,
        root: Path,
        *,
        reported_version: str = "3.12.13",
    ) -> tuple[Path, Path, Path]:
        install_root = root / "chaquopy-build-python"
        executable = install_root / "bin/python3.12"
        executable.parent.mkdir(parents=True)
        identity = json.dumps(
            {
                "implementation": "CPython",
                "version": reported_version,
                "executable": str(executable),
                "prefix": str(install_root),
            },
            sort_keys=True,
        )
        executable.write_text(
            "#!/bin/sh\n" "printf '%s\\n' " + repr(identity) + "\n",
            encoding="utf-8",
        )
        executable.chmod(executable.stat().st_mode | stat.S_IXUSR)
        stdlib_file = install_root / "lib/python3.12/json.py"
        stdlib_file.parent.mkdir(parents=True)
        stdlib_file.write_text("VALUE = 'locked'\n", encoding="utf-8")
        executable_sha256 = hashlib.sha256(executable.read_bytes()).hexdigest()
        payload_sha256 = installation_payload_sha256(install_root, ".lock.json")
        lock = root / "lock.json"
        lock.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "implementation": "CPython",
                    "version": "3.12.13",
                    "archive": {
                        "filename": "fixture.tar.gz",
                        "sha256": "a" * 64,
                        "url": (
                            "https://github.com/astral-sh/python-build-standalone/"
                            "releases/download/fixture/fixture.tar.gz"
                        ),
                    },
                    "installation": {
                        "directory": "chaquopy-build-python",
                        "executable": "bin/python3.12",
                        "executable_sha256": executable_sha256,
                        "marker": ".lock.json",
                        "payload_sha256": payload_sha256,
                    },
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        (install_root / ".lock.json").write_bytes(lock.read_bytes())
        return lock, executable, install_root

    def test_accepts_exact_locked_interpreter(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, executable, _ = self.fixture(root)
            identity = verify(
                lock_path=lock,
                python_command=executable,
                toolchain_root=root,
            )
            self.assertEqual("CPython", identity["implementation"])
            self.assertEqual("3.12.13", identity["version"])

    def test_rejects_missing_archive_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, executable, install_root = self.fixture(root)
            (install_root / ".lock.json").unlink()
            with self.assertRaisesRegex(BuildPythonVerificationError, "marker is missing"):
                verify(lock_path=lock, python_command=executable, toolchain_root=root)

    def test_rejects_symlinked_archive_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, executable, install_root = self.fixture(root)
            marker = install_root / ".lock.json"
            marker.unlink()
            marker.symlink_to(lock)
            with self.assertRaisesRegex(BuildPythonVerificationError, "regular file"):
                verify(lock_path=lock, python_command=executable, toolchain_root=root)

    def test_rejects_tampered_executable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, executable, _ = self.fixture(root)
            executable.write_text(executable.read_text(encoding="utf-8") + "# tampered\n")
            with self.assertRaisesRegex(BuildPythonVerificationError, "executable hash"):
                verify(lock_path=lock, python_command=executable, toolchain_root=root)

    def test_rejects_tampered_stdlib_payload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, executable, install_root = self.fixture(root)
            stdlib_file = install_root / "lib/python3.12/json.py"
            stdlib_file.write_text("VALUE = 'tampered'\n", encoding="utf-8")
            with self.assertRaisesRegex(BuildPythonVerificationError, "payload hash"):
                verify(lock_path=lock, python_command=executable, toolchain_root=root)

    def test_generated_pycache_bytecode_does_not_change_payload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, _, install_root = self.fixture(root)
            pycache = install_root / "lib/python3.12/__pycache__"
            pycache.mkdir()
            (pycache / "json.cpython-312.pyc").write_bytes(b"generated bytecode")
            expected = load_lock(lock)["installation"]["payload_sha256"]
            self.assertEqual(
                expected,
                installation_payload_sha256(install_root, ".lock.json"),
            )

    def test_generated_bytecode_must_match_its_attested_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "package/module.py"
            source.parent.mkdir(parents=True)
            source.write_text("VALUE = 'trusted'\n", encoding="utf-8")
            py_compile.compile(str(source), doraise=True)
            validate_generated_bytecode(Path(sys.executable), root)

            pyc = Path(importlib.util.cache_from_source(str(source)))
            payload = bytearray(pyc.read_bytes())
            payload[-1] ^= 1
            pyc.write_bytes(payload)
            with self.assertRaisesRegex(
                BuildPythonVerificationError,
                "failed source verification",
            ):
                validate_generated_bytecode(Path(sys.executable), root)

    def test_non_bytecode_file_inside_pycache_is_attested(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, executable, install_root = self.fixture(root)
            pycache = install_root / "lib/python3.12/__pycache__"
            pycache.mkdir()
            (pycache / "injected.py").write_text("VALUE = 1\n", encoding="utf-8")
            with self.assertRaisesRegex(BuildPythonVerificationError, "payload hash"):
                verify(lock_path=lock, python_command=executable, toolchain_root=root)

    def test_rejects_wrong_reported_patch_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, executable, _ = self.fixture(root, reported_version="3.12.12")
            with self.assertRaisesRegex(BuildPythonVerificationError, "version differs"):
                verify(lock_path=lock, python_command=executable, toolchain_root=root)

    def test_rejects_unexpected_python_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, _, _ = self.fixture(root)
            with self.assertRaisesRegex(BuildPythonVerificationError, "expected"):
                verify(
                    lock_path=lock,
                    python_command=Path("/usr/bin/python3.12"),
                    toolchain_root=root,
                )

    def test_rejects_unknown_lock_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock, _, _ = self.fixture(root)
            document = json.loads(lock.read_text(encoding="utf-8"))
            document["unexpected"] = True
            lock.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(BuildPythonVerificationError, "lock keys differ"):
                load_lock(lock)

    def test_android_environment_exports_only_the_dedicated_build_python(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            environment = os.environ.copy()
            environment["ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"] = directory
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    (
                        f"source {SCRIPTS_DIR / 'android-env.sh'}; "
                        "printf '%s\\n%s\\n' "
                        '"$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" "$PATH"'
                    ),
                ],
                check=True,
                stdout=subprocess.PIPE,
                text=True,
                env=environment,
            )
            build_python, path = result.stdout.splitlines()
            self.assertEqual(
                str(Path(directory) / "chaquopy-build-python/bin/python3.12"),
                build_python,
            )
            self.assertNotIn(str(Path(directory) / "chaquopy-build-python/bin"), path.split(":"))

    def test_committed_lock_pins_cpython_31213(self) -> None:
        lock = load_lock(SCRIPTS_DIR / "chaquopy-build-python.lock.json")
        self.assertEqual("3.12.13", lock["version"])
        self.assertEqual("bin/python3.12", lock["installation"]["executable"])
        self.assertRegex(lock["installation"]["payload_sha256"], r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
