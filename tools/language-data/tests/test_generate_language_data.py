from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
sys.path.insert(0, str(TOOL_ROOT))

import generate_language_data as generator  # noqa: E402

_PACK_TEMPLATE = """
from anki_miner.languages.pack_spec import ArtifactSpec, LanguagePack, PackComponent

_DATA = PackComponent(
    import_name="xx_data",
    required=True,
    sentinels=("table.dat",),
    universal=ArtifactSpec(
        url=(
            "https://example.invalid/"
            "xx-data.zip"
        ),
        sha256="{sha}",
        kind="{kind}",
        member_prefix="",
        {extra}
    ),
)

PACK = LanguagePack(code="xx", approx_download_mb=1, components=(_DATA,))

__all__ = ["PACK"]
"""


class GenerateLanguageDataTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        (self.repo / generator.COMPOSITION_PATH).parent.mkdir(parents=True)
        (self.repo / generator.COMPOSITION_PATH).write_text('languages = ["ja", "xx"]\n', encoding="utf-8")
        (self.repo / generator.CATALOG_DIR).mkdir(parents=True)
        shutil.copy(REPO_ROOT / generator.CATALOG_DIR / "ja.json", self.repo / generator.CATALOG_DIR / "ja.json")
        (self.repo / generator.LANGUAGES_PATH / "xx").mkdir(parents=True)
        self.sha = "a" * 64
        self._write_pack()
        self._write_pins({"xx/xx_data": {"data": self._data_pin()}})

    def _data_pin(self, sha: str | None = None) -> dict:
        return {
            "sha256": sha or self.sha,
            "sizeBytes": 1234,
            "displayName": "XX data",
            "attribution": [
                {"name": "XX", "copyright": "XX authors", "license": "MIT", "url": "https://example.invalid/"}
            ],
        }

    def _write_pack(self, *, kind: str = "zip", extra: str = "") -> None:
        text = _PACK_TEMPLATE.format(sha=self.sha, kind=kind, extra=extra)
        (self.repo / generator.LANGUAGES_PATH / "xx" / "pack.py").write_text(text, encoding="utf-8")

    def _write_pins(self, components: dict) -> None:
        path = self.repo / generator.PINS_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"components": components}), encoding="utf-8")

    def _catalog(self, code: str) -> dict:
        return json.loads((self.repo / generator.CATALOG_DIR / f"{code}.json").read_text(encoding="utf-8"))

    def test_the_committed_catalogs_match_the_vendored_manifests(self) -> None:
        self.assertEqual([], generator.drift(REPO_ROOT))

    def test_every_committed_pin_matches_a_vendored_component(self) -> None:
        # generate_entries raises on an unclassified component or a stale pin.
        entries = generator.generate_entries(REPO_ROOT)
        self.assertEqual(
            {
                "ar": ["calima_msa"],
                "fa": ["hazm_data"],
                "en": ["en_core_web_sm"],
                "ca": ["ca_core_news_sm"],
                "de": ["de_core_news_sm"],
                "pt": ["pt_core_news_sm"],
                "fr": ["fr_core_news_sm"],
                "es": ["es_core_news_sm"],
                "it": ["it_core_news_sm"],
                "nl": ["nl_core_news_sm"],
                "nb": ["nb_core_news_sm"],
                "ro": ["ro_core_news_sm"],
                "el": ["el_core_news_sm"],
                "fi": ["fi_core_news_sm"],
                "hu": ["hu_core_news_md"],
                "hr": ["hr_core_news_sm"],
                "sv": ["sv_core_news_sm"],
                "pl": ["pl_core_news_sm"],
                "lt": ["lt_core_news_sm"],
                "da": ["da_core_news_sm"],
                "sl": ["sl_core_news_sm"],
                "ru": ["ru_core_news_sm", "pymorphy3_dicts_ru"],
                "uk": ["uk_core_news_sm", "pymorphy3_dicts_uk"],
            },
            {code: [entry["importName"] for entry in items] for code, items in entries.items()},
        )

    def test_a_data_component_becomes_a_catalog_entry_copied_from_its_manifest(self) -> None:
        self.assertEqual(
            ["app/src/main/python/android_bridge/resource_catalog/xx.json is stale"], generator.drift(self.repo)
        )

        generator.refresh(self.repo)

        self.assertEqual([], generator.drift(self.repo))
        document = self._catalog("xx")
        self.assertEqual((3, "xx", []), (document["schemaVersion"], document["language"], document["recommended"]))
        (entry,) = document["resources"]
        self.assertEqual("xx-xx-data", entry["resourceId"])
        self.assertEqual("language-data", entry["kind"])
        self.assertEqual("xx_data", entry["importName"])
        self.assertEqual(
            {"url": "https://example.invalid/xx-data.zip", "sha256": self.sha, "sizeBytes": 1234, "format": "zip"},
            entry["archive"],
        )
        self.assertEqual(
            {"memberPrefix": "", "exclude": [], "sentinels": ["table.dat"], "innerSha256": []},
            entry["install"],
        )
        # The Japanese catalog carries no pack and stays byte-identical.
        self.assertEqual(
            (REPO_ROOT / generator.CATALOG_DIR / "ja.json").read_bytes(),
            (self.repo / generator.CATALOG_DIR / "ja.json").read_bytes(),
        )

    def test_refresh_keeps_hand_pinned_entries_after_the_generated_ones(self) -> None:
        hand = {"resourceId": "xx-dict", "kind": "yomitan-dictionary"}
        stale = {"resourceId": "xx-old-data", "kind": "language-data"}
        (self.repo / generator.CATALOG_DIR / "xx.json").write_text(
            json.dumps({"schemaVersion": 3, "language": "xx", "resources": [hand, stale], "recommended": ["xx-dict"]}),
            encoding="utf-8",
        )

        generator.refresh(self.repo)

        document = self._catalog("xx")
        self.assertEqual(["xx-xx-data", "xx-dict"], [entry["resourceId"] for entry in document["resources"]])
        self.assertEqual(["xx-dict"], document["recommended"])

    def test_an_unclassified_component_fails(self) -> None:
        self._write_pins({})
        with self.assertRaisesRegex(generator.GenerationError, "classify it"):
            generator.drift(self.repo)

    def test_a_pin_no_vendored_pack_declares_fails(self) -> None:
        self._write_pins({"xx/xx_data": {"data": self._data_pin()}, "xx/gone": {"apk": "removed upstream"}})
        with self.assertRaisesRegex(generator.GenerationError, "no vendored pack declares"):
            generator.drift(self.repo)

    def test_a_moved_artifact_needs_its_length_measured_again(self) -> None:
        self._write_pins({"xx/xx_data": {"data": self._data_pin(sha="b" * 64)}})
        with self.assertRaisesRegex(generator.GenerationError, "re-measure sizeBytes"):
            generator.drift(self.repo)

    def test_an_apk_classification_needs_a_reason_and_emits_nothing(self) -> None:
        self._write_pins({"xx/xx_data": {"apk": " "}})
        with self.assertRaisesRegex(generator.GenerationError, "reason"):
            generator.drift(self.repo)

        self._write_pins({"xx/xx_data": {"apk": "engine code"}})
        self.assertEqual([], generator.drift(self.repo))

    def test_a_component_shaped_like_code_cannot_be_data(self) -> None:
        for kind, extra in (("sdist", ""), ("zip", 'root_members=("_native.",),')):
            with self.subTest(kind=kind, extra=extra):
                self._write_pack(kind=kind, extra=extra)
                with self.assertRaises(generator.GenerationError):
                    generator.drift(self.repo)

    def test_the_vendored_thai_manifest_reads_as_syntax(self) -> None:
        pack = generator.read_pack(REPO_ROOT / generator.LANGUAGES_PATH / "th" / "pack.py")

        names = [component["import_name"] for component in pack["components"]]
        self.assertEqual(["pythainlp", "tzdata"], names)
        self.assertIn("corpus/wordnet_th.db", pack["components"][0]["universal"]["exclude"])

    def test_check_exits_non_zero_on_drift(self) -> None:
        self.assertEqual(1, generator.main(["--repo-root", str(self.repo), "--check"]))
        self.assertEqual(0, generator.main(["--repo-root", str(self.repo), "--refresh"]))
        self.assertEqual(0, generator.main(["--repo-root", str(self.repo), "--check"]))


if __name__ == "__main__":
    unittest.main()
