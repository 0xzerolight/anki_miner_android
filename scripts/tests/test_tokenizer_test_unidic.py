from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools/tokenizer/package_test_unidic.py"
SPEC = importlib.util.spec_from_file_location("package_test_unidic", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
package_test_unidic = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(package_test_unidic)


class TokenizerTestUniDicTests(unittest.TestCase):
    def make_fixture(self, root: Path) -> tuple[Path, Path]:
        dicdir = root / "dicdir"
        dicdir.mkdir()
        for index, name in enumerate(package_test_unidic.REQUIRED):
            (dicdir / name).write_bytes(f"{index}:{name}".encode())
        nested = dicdir / "nested"
        nested.mkdir()
        (nested / "notice.txt").write_text("notice", encoding="utf-8")
        tree_hash = package_test_unidic.calculate_unidic_tree_sha256(dicdir)
        golden = root / "golden.json"
        golden.write_text(
            json.dumps(
                {
                    "provenance": {
                        "data": {"assets_sha256": {"unidic_dicdir": tree_hash}},
                    },
                },
            ),
            encoding="utf-8",
        )
        return dicdir, golden

    def run_packager(
        self,
        dicdir: Path,
        golden: Path,
        output: Path,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(MODULE_PATH),
                "--dicdir",
                str(dicdir),
                "--golden",
                str(golden),
                "--output",
                str(output),
            ],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_packaging_is_golden_bound_recursive_and_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dicdir, golden = self.make_fixture(root)
            first = root / "first.zip"
            second = root / "second.zip"
            self.assertEqual(0, self.run_packager(dicdir, golden, first).returncode)
            self.assertEqual(0, self.run_packager(dicdir, golden, second).returncode)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertIn("nested/notice.txt", archive.namelist())
                self.assertTrue(
                    all(info.date_time == (1980, 1, 1, 0, 0, 0) for info in archive.infolist()),
                )

    def test_hash_mismatch_fails_before_writing_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dicdir, golden = self.make_fixture(root)
            document = json.loads(golden.read_text(encoding="utf-8"))
            document["provenance"]["data"]["assets_sha256"]["unidic_dicdir"] = "0" * 64
            golden.write_text(json.dumps(document), encoding="utf-8")
            output = root / "must-not-exist.zip"
            result = self.run_packager(dicdir, golden, output)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not match golden hash", result.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
