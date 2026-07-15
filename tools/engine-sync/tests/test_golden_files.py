from __future__ import annotations

import hashlib
import json
from copy import deepcopy
import unittest
from pathlib import Path

import jsonschema

from engine_sync.golden_contract import (
    ASSET_NAME_RE,
    CASE_ID_RE,
    UNIDIC_FEATURE_FIELDS,
)


class GoldenFilesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.project_root = Path(__file__).resolve().parents[3]

    def test_schema_freezes_all_unidic_fields_and_provenance_domains(self) -> None:
        schema = json.loads(
            (
                self.project_root / "golden/schema/engine-goldens-v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        prefix = schema["properties"]["unidic_feature_fields"]["prefixItems"]
        self.assertEqual(
            [entry["const"] for entry in prefix], list(UNIDIC_FEATURE_FIELDS)
        )
        self.assertEqual(
            set(schema["$defs"]["provenance"]["properties"]),
            {"engine", "tool", "runtime", "data"},
        )
        self.assertEqual(
            schema["properties"]["section_status"]["$ref"],
            "#/$defs/section_status",
        )
        self.assertEqual(
            set(schema["$defs"]["runtime_dependency"]["properties"]),
            {"version", "content_sha256"},
        )
        case_properties = schema["properties"]["cases"]["properties"]
        self.assertEqual(case_properties["tokenization"]["minItems"], 1)
        self.assertEqual(case_properties["morphology"]["minItems"], 1)
        self.assertEqual(case_properties["compounds"]["minItems"], 1)
        for section in (
            "filtering",
            "deinflection",
            "dictionaries",
            "frequency",
            "pitch",
            "cards",
        ):
            self.assertEqual(case_properties[section]["$ref"], "#/$defs/inactive_cases")
        self.assertEqual(schema["$defs"]["inactive_cases"]["maxItems"], 0)
        for definition in ("tokenization_case", "morphology_case", "compound_case"):
            self.assertEqual(
                schema["$defs"][definition]["properties"]["id"]["pattern"],
                CASE_ID_RE.pattern,
            )
        self.assertEqual(
            schema["$defs"]["provenance"]["properties"]["data"]["properties"][
                "assets_sha256"
            ]["propertyNames"]["pattern"],
            ASSET_NAME_RE.pattern,
        )
        self.assertEqual(
            set(schema["$defs"]["word"]["properties"]),
            {
                "surface",
                "lemma",
                "orth_base",
                "mined_form",
                "reading",
                "pos",
                "surface_start",
                "surface_end",
                "highlight_end",
                "sentence",
                "expression_furigana",
                "expression_reading",
                "sentence_furigana",
                "sentence_reading",
            },
        )

    def test_seed_corpus_contains_every_required_regression_class(self) -> None:
        corpus = json.loads(
            (self.project_root / "golden/corpus/tokenizer-v1.json").read_text(
                encoding="utf-8"
            )
        )
        coverage = {
            marker for case in corpus["cases"] for marker in case.get("coverage", [])
        }
        self.assertTrue(
            {
                "homograph",
                "orthbase-variant",
                "compound-merge",
                "potential-fold",
                "ra-nuki-fold",
                "adjective-ku-form-fold",
                "jiru-zuru-canonicalization-guard",
                "astral-codepoint",
                "utf16-offset",
                "oov-empty-orthbase",
            }
            <= coverage
        )
        ku_case = next(
            case for case in corpus["cases"] if case["id"] == "ku-form-yoshi"
        )
        self.assertEqual(ku_case["text"], "この状態で良かれ。")
        self.assertEqual(ku_case["expect"]["token"]["orthBase"], "良し")

    def test_committed_fixture_validates_and_is_the_reviewed_derivation(self) -> None:
        schema = json.loads(
            (
                self.project_root / "golden/schema/engine-goldens-v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        fixture_path = self.project_root / "golden/engine-v1.json"
        fixture_bytes = fixture_path.read_bytes()
        fixture = json.loads(fixture_bytes)

        jsonschema.Draft202012Validator.check_schema(schema)
        jsonschema.Draft202012Validator(schema).validate(fixture)
        self.assertEqual(
            hashlib.sha256(fixture_bytes).hexdigest(),
            "8c3051100e2f2d7702c8e3ba84e4dc3cb9ab197331f7aed7af96cd41dd44f9c9",
        )
        self.assertEqual(
            fixture["provenance"]["data"]["corpus_sha256"],
            hashlib.sha256(
                (self.project_root / "golden/corpus/tokenizer-v1.json").read_bytes()
            ).hexdigest(),
        )

    def test_schema_rejects_empty_surfaces_and_raw_unidic_stars(self) -> None:
        schema = json.loads(
            (
                self.project_root / "golden/schema/engine-goldens-v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        fixture = json.loads(
            (self.project_root / "golden/engine-v1.json").read_text(encoding="utf-8")
        )
        validator = jsonschema.Draft202012Validator(schema)

        empty_surface = deepcopy(fixture)
        empty_surface["cases"]["tokenization"][0]["tokens"][0]["surface"] = ""
        with self.assertRaises(jsonschema.ValidationError):
            validator.validate(empty_surface)

        raw_star = deepcopy(fixture)
        raw_star["cases"]["tokenization"][0]["tokens"][0]["features"]["orthBase"] = "*"
        with self.assertRaises(jsonschema.ValidationError):
            validator.validate(raw_star)


if __name__ == "__main__":
    unittest.main()
