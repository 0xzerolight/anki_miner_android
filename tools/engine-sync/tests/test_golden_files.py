from __future__ import annotations

import json
import unittest
from pathlib import Path

from engine_sync.golden_contract import UNIDIC_FEATURE_FIELDS


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
        self.assertEqual(ku_case["expect"]["orthBase"], "良し")


if __name__ == "__main__":
    unittest.main()
