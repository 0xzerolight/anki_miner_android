from __future__ import annotations

import ast
import base64
import hashlib
import importlib.util
import io
import sys
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TOOL_ROOT = ROOT / "tools/runtime-wheels"
SPEC = importlib.util.spec_from_file_location("anki_miner_repack_wheels_under_test", TOOL_ROOT / "repack_wheels.py")
assert SPEC is not None and SPEC.loader is not None
repack_wheels = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = repack_wheels
SPEC.loader.exec_module(repack_wheels)

VENDORED_LANGUAGES = ROOT / "app/src/main/python/anki_miner/languages"


def _record_hash(data: bytes) -> str:
    return "sha256=" + base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode("ascii")


def make_wheel(files: dict[str, bytes]) -> bytes:
    record_name = "demo-1.0.dist-info/RECORD"
    record = "".join(f"{name},{_record_hash(data)},{len(data)}\n" for name, data in files.items())
    record += f"{record_name},,\n"
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in {**files, record_name: record.encode()}.items():
            archive.writestr(zipfile.ZipInfo(name, date_time=(2020, 2, 2, 0, 0, 0)), data)
    return output.getvalue()


def entry_for(source: bytes, exclude: list[str]) -> dict[str, object]:
    return {
        "exclude": exclude,
        "filename": "demo-1.0-py3-none-any.whl",
        "license": {"expression": "MIT", "members": {"demo/LICENSE": hashlib.sha256(b"license").hexdigest()}},
        "member_prefix": "demo/",
        "repacked_sha256": "",
        "sha256": hashlib.sha256(source).hexdigest(),
    }


FILES = {
    "demo/__init__.py": b"",
    "demo/LICENSE": b"license",
    "demo/data/big.bin": b"x" * 100,
    "demo/data/big.bin.py": b"KEEP = True\n",
    "demo/models/a.onnx": b"a",
    "demo/models/b.onnx": b"b",
    "demo-1.0.dist-info/METADATA": b"Metadata-Version: 2.1\nName: demo\nVersion: 1.0\n",
}


class RepackTests(unittest.TestCase):
    def test_exact_entries_and_subtrees_follow_desktop_matching(self) -> None:
        source = make_wheel(FILES)
        entry = entry_for(source, ["data/big.bin", "models/"])
        repacked = repack_wheels.repack(source, entry)
        with zipfile.ZipFile(io.BytesIO(repacked)) as archive:
            names = set(archive.namelist())
            record = archive.read("demo-1.0.dist-info/RECORD").decode()
            self.assertTrue(all(info.compress_type == zipfile.ZIP_STORED for info in archive.infolist()))
        self.assertIn("demo/data/big.bin.py", names)
        self.assertNotIn("demo/data/big.bin", names)
        self.assertFalse(any(name.startswith("demo/models/") for name in names))
        self.assertNotIn("demo/models/", record)
        self.assertNotIn("demo/data/big.bin,", record)

        entry["repacked_sha256"] = hashlib.sha256(repacked).hexdigest()
        repack_wheels.verify_wheel(repacked, entry)
        self.assertEqual(repacked, repack_wheels.repack(source, entry))

    def test_exclude_that_matches_nothing_is_rejected(self) -> None:
        source = make_wheel(FILES)
        with self.assertRaisesRegex(repack_wheels.RepackError, "match nothing"):
            repack_wheels.repack(source, entry_for(source, ["data/absent.txt"]))

    def test_empty_exclude_keeps_the_upstream_bytes(self) -> None:
        source = make_wheel(FILES)
        self.assertIs(source, repack_wheels.repack(source, entry_for(source, [])))

    def test_verify_rejects_an_excluded_member(self) -> None:
        source = make_wheel(FILES)
        entry = entry_for(source, ["models/"])
        entry["repacked_sha256"] = entry["sha256"]
        with self.assertRaisesRegex(repack_wheels.RepackError, "excluded members are present"):
            repack_wheels.verify_wheel(source, entry)

    def test_committed_wheels_match_the_lock(self) -> None:
        self.assertEqual(0, repack_wheels.check(repack_wheels.DEFAULT_OUTPUT))

    def test_lock_matches_the_vendored_desktop_pack_manifests(self) -> None:
        for name, entry in repack_wheels.load_lock().items():
            specs = _artifact_specs(VENDORED_LANGUAGES / entry["pack"] / "pack.py")
            spec = specs.get(entry["url"])
            self.assertIsNotNone(spec, f"{name}: no vendored ArtifactSpec pins {entry['url']}")
            self.assertEqual("wheel", spec["kind"], name)
            self.assertEqual(entry["sha256"], spec["sha256"], name)
            self.assertEqual(entry["member_prefix"], spec["member_prefix"], name)
            self.assertEqual(tuple(entry["exclude"]), spec.get("exclude", ()), name)


def _artifact_specs(path: Path) -> dict[str, dict[str, object]]:
    specs: dict[str, dict[str, object]] = {}
    for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
        if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "ArtifactSpec":
            wanted = {"url", "sha256", "kind", "member_prefix", "exclude"}
            values = {kw.arg: ast.literal_eval(kw.value) for kw in node.keywords if kw.arg in wanted}
            specs[str(values["url"])] = values
    return specs


if __name__ == "__main__":
    unittest.main()
