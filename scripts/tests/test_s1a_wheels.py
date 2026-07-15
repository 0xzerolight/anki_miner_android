from __future__ import annotations

import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools/wheels/s1a_wheels.py"
SPEC = importlib.util.spec_from_file_location("s1a_wheels", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
s1a_wheels = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(s1a_wheels)


class S1aWheelToolTests(unittest.TestCase):
    def _fake_wheel_set(self, dist: Path) -> list[Path]:
        filenames = []
        for platform in ("arm64_v8a", "x86_64"):
            filenames.extend(
                [
                    f"chaquopy_libcxx-190000-0-py3-none-android_26_{platform}.whl",
                    f"chaquopy_libmecab-0.996-0-py3-none-android_26_{platform}.whl",
                    f"fugashi-1.5.2-0-cp313-cp313-android_26_{platform}.whl",
                ],
            )
        paths = []
        for filename in filenames:
            path = dist / filename.split("-", 1)[0] / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(filename.encode())
            paths.append(path)
        return paths

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

    def test_wheel_identity_requires_exact_version_api_and_tags(self) -> None:
        package, abi = s1a_wheels._wheel_identity(
            Path("fugashi-1.5.2-0-cp313-cp313-android_26_arm64_v8a.whl"),
        )
        self.assertEqual(("fugashi", "arm64-v8a"), (package, abi))
        for invalid in (
            "fugashi-1.5.1-0-cp313-cp313-android_26_arm64_v8a.whl",
            "fugashi-1.5.2-0-cp313-cp313-android_25_arm64_v8a.whl",
            "fugashi-1.5.2-0-cp312-cp312-android_26_arm64_v8a.whl",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(s1a_wheels.WheelError):
                s1a_wheels._wheel_identity(Path(invalid))

    def test_wheel_verifier_rejects_dictionary_before_native_inspection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            wheel = (
                Path(temporary)
                / "fugashi-1.5.2-0-cp313-cp313-android_26_x86_64.whl"
            )
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("unidic_lite/dicdir/sys.dic", b"forbidden")
            with self.assertRaisesRegex(s1a_wheels.WheelError, "dictionary"):
                s1a_wheels.verify_s1a_wheel(wheel)

    def test_publication_is_complete_hashed_and_immutable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheels = self._fake_wheel_set(root / "dist")

            def fake_verify(path: Path):
                package, abi = s1a_wheels._wheel_identity(path)
                return package, abi, {
                    "filename": path.name,
                    "sha256": s1a_wheels.digest(path),
                    "size": path.stat().st_size,
                    "licenses": [],
                    "elf": {"abi": abi},
                }

            with mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=fake_verify):
                manifest_path = s1a_wheels.publish(root / "dist", root / "published")
            document = s1a_wheels.load_json(manifest_path)
            self.assertEqual(s1a_wheels.recipe_key(), document["recipe_key"])
            self.assertEqual({"arm64-v8a", "x86_64"}, set(document["wheels"]))
            self.assertEqual(3, len(document["wheels"]["arm64-v8a"]))
            self.assertEqual(3, len(document["wheels"]["x86_64"]))
            self.assertEqual(
                {path.name for path in wheels},
                {path.name for path in manifest_path.parent.glob("*.whl")},
            )
            with mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=fake_verify):
                with self.assertRaisesRegex(s1a_wheels.WheelError, "immutable"):
                    s1a_wheels.publish(root / "dist", root / "published")

    def test_failed_publication_leaves_no_partial_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._fake_wheel_set(root / "dist")
            with mock.patch.object(
                s1a_wheels,
                "verify_s1a_wheel",
                side_effect=s1a_wheels.WheelError("inspection failed"),
            ):
                with self.assertRaisesRegex(s1a_wheels.WheelError, "inspection failed"):
                    s1a_wheels.publish(root / "dist", root / "published")
            self.assertFalse((root / "published").exists())


if __name__ == "__main__":
    unittest.main()
