from __future__ import annotations

import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools/wheels/s1a_wheels.py"
SPEC = importlib.util.spec_from_file_location("s1a_wheels", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
s1a_wheels = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(s1a_wheels)


class S1aWheelToolTests(unittest.TestCase):
    def test_locks_are_exact_and_complete(self) -> None:
        sources = s1a_wheels.source_entries()
        self.assertEqual(
            {
                "chaquopy",
                "fugashi",
                "mecab",
                "patchelf",
                "python-arm64-v8a",
                "python-x86_64",
            },
            set(sources),
        )
        requirements = s1a_wheels.host_entries()
        self.assertIn("pip==25.1.1", {requirement for requirement, _, _ in requirements})
        self.assertIn("Cython==3.1.5", {requirement for requirement, _, _ in requirements})
        self.assertEqual(len(requirements), len({filename for _, filename, _ in requirements}))

    def test_recipe_key_covers_patches_and_locks(self) -> None:
        key = s1a_wheels.recipe_key()
        self.assertRegex(key, r"^[0-9a-f]{64}$")
        self.assertEqual(key, s1a_wheels.recipe_key())

    def test_safe_extractor_rejects_traversal_and_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            traversal = root / "traversal.tar.gz"
            with tarfile.open(traversal, "w:gz") as archive:
                info = tarfile.TarInfo("../escape")
                info.size = 1
                archive.addfile(info, io.BytesIO(b"x"))
            with self.assertRaises(s1a_wheels.WheelError):
                s1a_wheels.safe_extract(traversal, root / "out-traversal")

            linked = root / "linked.tar.gz"
            with tarfile.open(linked, "w:gz") as archive:
                info = tarfile.TarInfo("root/link")
                info.type = tarfile.SYMTYPE
                info.linkname = "/tmp/target"
                archive.addfile(info)
            with self.assertRaises(s1a_wheels.WheelError):
                s1a_wheels.safe_extract(linked, root / "out-linked")

    def test_recipes_keep_dictionary_out_and_pin_cross_build(self) -> None:
        recipe_root = ROOT / "tools/wheels/recipes"
        combined = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(recipe_root.rglob("*"))
            if path.is_file()
        )
        self.assertIn("make -C src", combined)
        self.assertIn("--enable-utf8-only", combined)
        self.assertIn("chaquopy-libmecab 0.996", combined)
        self.assertNotIn("unidic-lite", combined.casefold())
        self.assertNotIn("mecab-ipadic", combined.casefold())

    def test_builder_patch_is_network_closed(self) -> None:
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn("network source discovery is disabled", source)
        self.assertIn("--no-index --only-binary=:all:", source)
        self.assertIn("locked NDK is missing", source)
        self.assertIn("yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager", source)


if __name__ == "__main__":
    unittest.main()
