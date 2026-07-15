from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
LICENSE_HASH = "efa68a6b3c661d18699d5c026771d5911cdc2f83"


class AndroidLicenseTest(unittest.TestCase):
    def run_check(self, root: Path) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"] = str(root)
        return subprocess.run(
            [str(SCRIPTS_DIR / "android-licenses.sh"), "check"],
            check=False,
            capture_output=True,
            env=environment,
            text=True,
        )

    def test_check_accepts_exact_recorded_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            license_dir = root / "sdk" / "licenses"
            license_dir.mkdir(parents=True)
            (license_dir / "android-sdk-license").write_text(
                f"older-hash\n{LICENSE_HASH}\n",
                encoding="utf-8",
            )
            result = self.run_check(root)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_check_rejects_missing_acceptance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_check(Path(directory))
            self.assertEqual(2, result.returncode)
            self.assertIn("has not been accepted", result.stderr)


if __name__ == "__main__":
    unittest.main()
