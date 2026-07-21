from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "tools/wheels/vendored_wheel_manifest.py"
WHEELS_ROOT = REPO_ROOT / "app/wheels"
MANIFEST = WHEELS_ROOT / "manifest.json"


class VendoredWheelManifestTests(unittest.TestCase):
    def _run_tool(
        self,
        command: str,
        *,
        wheels_root: Path = WHEELS_ROOT,
        manifest: Path = MANIFEST,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                command,
                "--wheels-root",
                str(wheels_root),
                "--manifest",
                str(manifest),
            ],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

    def _copy_wheels(self, root: Path) -> Path:
        wheels_root = root / "wheels"
        shutil.copytree(WHEELS_ROOT, wheels_root)
        return wheels_root

    def test_committed_manifest_matches_every_vendored_wheel(self) -> None:
        result = self._run_tool("check")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("26 wheels verified", result.stdout)

    def test_generate_records_required_provenance_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheels_root = self._copy_wheels(root)
            manifest = root / "manifest.json"

            result = self._run_tool(
                "generate",
                wheels_root=wheels_root,
                manifest=manifest,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            document = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(1, document["schema"])
            self.assertEqual(26, len(document["wheels"]))
            for entry in document["wheels"]:
                self.assertEqual(
                    {
                        "abi",
                        "filename",
                        "license",
                        "package",
                        "path",
                        "sha256",
                        "source",
                        "version",
                    },
                    set(entry),
                )
                self.assertTrue(entry["source"]["url"].startswith("https://"))

    def test_check_rejects_unmanifested_wheel(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheels_root = self._copy_wheels(root)
            manifest = root / "manifest.json"
            generated = self._run_tool(
                "generate",
                wheels_root=wheels_root,
                manifest=manifest,
            )
            self.assertEqual(0, generated.returncode, generated.stderr)
            (wheels_root / "common/untracked-1-py3-none-any.whl").write_bytes(b"not a wheel")

            result = self._run_tool(
                "check",
                wheels_root=wheels_root,
                manifest=manifest,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("untracked-1-py3-none-any.whl", result.stderr)

    def test_check_rejects_wheel_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheels_root = self._copy_wheels(root)
            manifest = root / "manifest.json"
            generated = self._run_tool(
                "generate",
                wheels_root=wheels_root,
                manifest=manifest,
            )
            self.assertEqual(0, generated.returncode, generated.stderr)
            wheel = next((wheels_root / "common").glob("*.whl"))
            with wheel.open("ab") as stream:
                stream.write(b"tampered")

            result = self._run_tool(
                "check",
                wheels_root=wheels_root,
                manifest=manifest,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn(f"SHA-256 mismatch: common/{wheel.name}", result.stderr)

    def test_generate_rejects_wheel_version_not_bound_to_source_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheels_root = self._copy_wheels(root)
            wheel = next((wheels_root / "common").glob("certifi-*.whl"))
            rewritten = wheel.with_suffix(".rewritten")
            with zipfile.ZipFile(wheel) as source, zipfile.ZipFile(rewritten, "w") as target:
                for member in source.infolist():
                    payload = source.read(member)
                    if member.filename.endswith(".dist-info/METADATA"):
                        payload = payload.replace(b"Version: 2026.6.17", b"Version: 9999")
                    target.writestr(member, payload)
            rewritten.replace(wheel)

            result = self._run_tool(
                "generate",
                wheels_root=wheels_root,
                manifest=root / "manifest.json",
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("source version 2026.6.17 does not match wheel version 9999", result.stderr)


if __name__ == "__main__":
    unittest.main()
