from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = PROJECT_ROOT / "tools/engine-sync/run_s4_engine_smoke.py"
SPEC = importlib.util.spec_from_file_location("run_s4_engine_smoke", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("could not load S4 contract module")
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)


class S4EngineSmokeContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture_path = PROJECT_ROOT / "golden/s4-engine-smoke-v1.json"
        self.corpus_path = PROJECT_ROOT / "golden/corpus/s4-engine-smoke-v1.json"
        self.exporter_path = PROJECT_ROOT / "tools/engine-sync/export_s4_engine_smoke.py"
        self.lock_path = PROJECT_ROOT / "tools/engine-sync/engine.lock"
        self.fixture = json.loads(self.fixture_path.read_text(encoding="utf-8"))

    def validate(self, payload: object) -> None:
        CONTRACT.validate_fixture(
            payload,
            lock_path=self.lock_path,
            corpus_path=self.corpus_path,
            exporter_path=self.exporter_path,
            contract_path=SCRIPT_PATH,
        )

    def test_committed_fixture_validates_against_current_contract_inputs(self) -> None:
        self.validate(self.fixture)
        self.assertEqual(
            "94b281dac035de719af33850e34da9750da0ca2f476e85d7dda49b03343635e0",
            hashlib.sha256(self.fixture_path.read_bytes()).hexdigest(),
        )

        output = self.fixture["case"]["output"]
        self.assertEqual("殺る", output["selected_mined_form"])
        self.assertEqual(["奴", "殺る"], [word["mined_form"] for word in output["parsed_words"]])
        self.assertEqual(["殺る"], [word["mined_form"] for word in output["filtered_words"]])
        self.assertIn("to do &amp; kill &lt;colloquial&gt;", output["rendered_content"])
        self.assertIn('data-dictionary="S4 Smoke Dictionary"', output["lookup_html"])

    def test_output_tampering_is_rejected_by_canonical_hash(self) -> None:
        tampered = copy.deepcopy(self.fixture)
        tampered["case"]["output"]["lookup_html"] += "tampered"

        with self.assertRaisesRegex(CONTRACT.S4ContractError, "output hash"):
            self.validate(tampered)

    def test_fixture_keeps_every_derivation_identity_separate(self) -> None:
        provenance = self.fixture["provenance"]
        self.assertEqual(
            {"revision", "tree_sha256"}, set(provenance["engine"])
        )
        self.assertEqual(
            {
                "name",
                "version",
                "exporter_sha256",
                "contract_sha256",
                "support_sha256",
            },
            set(provenance["tool"]),
        )
        self.assertRegex(provenance["tool"]["support_sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(
            {"corpus_sha256", "unidic_dicdir_sha256", "sha256"},
            set(provenance["input"]),
        )
        self.assertRegex(provenance["output_sha256"], r"^[0-9a-f]{64}$")

    def test_support_tool_tampering_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.fixture)
        tampered["provenance"]["tool"]["support_sha256"] = "0" * 64

        with self.assertRaisesRegex(CONTRACT.S4ContractError, "support-tool hash"):
            self.validate(tampered)

    def test_engine_sync_errors_are_translated_to_s4_contract_errors(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            missing_lock = Path(temporary) / "missing-engine.lock"
            with self.assertRaisesRegex(
                CONTRACT.S4ContractError,
                "cannot read the engine lock",
            ):
                CONTRACT.validate_fixture(
                    self.fixture,
                    lock_path=missing_lock,
                    corpus_path=self.corpus_path,
                    exporter_path=self.exporter_path,
                    contract_path=SCRIPT_PATH,
                )

    def test_exporter_uses_production_render_and_index_inputs(self) -> None:
        source = self.exporter_path.read_text(encoding="utf-8")
        rendered_index = source.index("rendered_content = render_glossary_entry")
        row_index = source.index("content=rendered_content")
        lookup_index = source.index("lookup_html = provider.lookup")

        self.assertLess(rendered_index, row_index)
        self.assertLess(row_index, lookup_index)
        self.assertIn("create_index(db_path)", source)
        self.assertIn("bulk_insert(", source)
        self.assertIn("write_meta(", source)
        self.assertNotIn('content=expected_output["rendered_content"]', source)


if __name__ == "__main__":
    unittest.main()
