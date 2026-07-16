from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name: str):
    path = SCRIPTS / name
    spec = importlib.util.spec_from_file_location(path.stem, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


UI_TARGET = load_script("uiautomator_click_target.py")
RESPONSES = load_script("verify_ankiconnect_probe_response.py")


class UiAutomatorTargetTest(unittest.TestCase):
    def test_resolves_one_exact_clickable_target(self) -> None:
        xml = (
            '<hierarchy><node text="Start Service" enabled="true" clickable="true" '
            'bounds="[10,20][110,80]" /></hierarchy>'
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "window.xml"
            path.write_text(xml, encoding="utf-8")
            self.assertEqual((60, 50), UI_TARGET.center(path, "Start Service"))

    def test_rejects_ambiguous_target(self) -> None:
        node = '<node text="Start Service" enabled="true" clickable="true" bounds="[0,0][2,2]" />'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "window.xml"
            path.write_text(f"<hierarchy>{node}{node}</hierarchy>", encoding="utf-8")
            with self.assertRaisesRegex(UI_TARGET.TargetError, "found 2"):
                UI_TARGET.center(path, "Start Service")


class ProbeResponseTest(unittest.TestCase):
    def write(self, root: Path, name: str, value: object) -> Path:
        path = root / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_accepts_exact_version_decks_and_media_readback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            version = self.write(root, "version.json", {"result": 6, "error": None})
            decks = self.write(root, "decks.json", {"result": ["Default"], "error": None})
            stored = self.write(
                root,
                "store.json",
                {"result": "anki_miner_fallback_probe_random-A9.png", "error": None},
            )
            readback = root / "readback.png"
            readback.write_bytes(
                bytes.fromhex(
                    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
                    "0000000d49444154789c63000100000005000159c8e1740000000049454e44ae426082"
                )
            )
            self.assertEqual(
                "anki_miner_fallback_probe_random-A9.png",
                RESPONSES.validate(version, decks, stored, readback),
            )
            self.assertEqual(
                "anki_miner_fallback_probe_random-A9.png",
                RESPONSES.stored_filename(stored),
            )

    def test_rejects_boolean_version_and_duplicate_decks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            version = self.write(root, "version.json", {"result": True, "error": None})
            decks = self.write(root, "decks.json", {"result": ["Default", "Default"], "error": None})
            stored = self.write(
                root,
                "store.json",
                {"result": "../escape.png", "error": None},
            )
            readback = root / "readback.png"
            readback.write_bytes(b"wrong")
            with self.assertRaises(RESPONSES.ResponseError):
                RESPONSES.validate(version, decks, stored, readback)

    def test_rejects_untrusted_media_name_and_changed_readback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            version = self.write(root, "version.json", {"result": 6, "error": None})
            decks = self.write(root, "decks.json", {"result": ["Default"], "error": None})
            readback = root / "readback.png"
            readback.write_bytes(b"changed")
            for name in ("anki_miner_fallback_probe.png", "../probe_1.png", "probe_1.png"):
                stored = self.write(root, "store.json", {"result": name, "error": None})
                with self.subTest(name=name), self.assertRaises(RESPONSES.ResponseError):
                    RESPONSES.validate(version, decks, stored, readback)


class ProbeRunnerSourceTest(unittest.TestCase):
    def test_runner_is_owned_resource_safe_and_capability_only(self) -> None:
        source = (SCRIPTS / "run-s2-ankiconnect-fallback-probe.sh").read_text(encoding="utf-8")

        self.assertIn("anki_miner_require_no_gradle", source)
        self.assertIn("verify-emulator-runtime.sh\" --lane 4k", source)
        self.assertIn("ANKI_MINER_S2_ALLOW_COLLECTION_RESET", source)
        self.assertIn("verify_fallback_apk.py", source)
        self.assertIn("com.kamwithk.ankiconnectandroid", source)
        self.assertIn("'Start Service'", source)
        self.assertIn("{\"action\":\"version\",\"version\":6}", source)
        self.assertIn("{\"action\":\"deckNames\",\"version\":6}", source)
        self.assertIn("{\"action\":\"storeMediaFile\",\"version\":6", source)
        self.assertIn("collection.media/$media_filename", source)
        self.assertNotIn("gradlew", source)
        self.assertNotIn("addNote", source)


if __name__ == "__main__":
    unittest.main()
