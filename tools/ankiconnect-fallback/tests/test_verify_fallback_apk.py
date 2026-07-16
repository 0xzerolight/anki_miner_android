from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "verify_fallback_apk.py"
SPEC = importlib.util.spec_from_file_location("verify_fallback_apk", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FallbackManifestTest(unittest.TestCase):
    def test_repository_manifest_is_strict_and_immutable(self) -> None:
        payload = MODULE._load_manifest(MODULE.DEFAULT_MANIFEST)

        self.assertEqual("ankidroid-content-provider", payload["decision"]["productionPath"])
        self.assertEqual("s2-capability-probe-only", payload["decision"]["fallbackRole"])
        self.assertNotIn("latest", payload["artifact"]["assetUrl"].casefold())
        self.assertEqual([], payload["artifact"]["nativeAbis"])

    def test_manifest_rejects_mutable_asset_url(self) -> None:
        payload = json.loads(MODULE.DEFAULT_MANIFEST.read_text(encoding="utf-8"))
        payload["artifact"]["assetUrl"] = (
            "https://github.com/KamWithK/AnkiconnectAndroid/releases/latest/download/app.apk"
        )
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.json"
            manifest.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(MODULE.VerificationError, "immutable"):
                MODULE._load_manifest(manifest)

    def test_apk_abi_inventory_is_derived_from_zip_members(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "sample.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("lib/x86_64/libsample.so", b"binary")
            with zipfile.ZipFile(apk) as archive:
                abis = sorted(
                    {
                        name.split("/", 2)[1]
                        for name in archive.namelist()
                        if name.startswith("lib/") and name.count("/") >= 2
                    }
                )
            self.assertEqual(["x86_64"], abis)


if __name__ == "__main__":
    unittest.main()
