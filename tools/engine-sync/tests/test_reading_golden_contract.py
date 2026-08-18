from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = PROJECT_ROOT / "tools/engine-sync/run_reading_goldens.py"
SPEC = importlib.util.spec_from_file_location("run_reading_goldens", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("could not load reading golden contract module")
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)


class ReadingGoldenContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture_path = PROJECT_ROOT / "golden/reading-v1.json"
        self.corpus_path = PROJECT_ROOT / "golden/corpus/reading-v1-input.json"
        self.exporter_path = PROJECT_ROOT / "tools/engine-sync/export_reading_goldens.py"
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

    def test_committed_fixture_is_complete_and_current(self) -> None:
        self.validate(self.fixture)
        self.assertEqual(
            "cdc66bf175aa2c4e1ca6eaaf6440d824b461bc0c8e7a36b549e9bdc153f429ee",
            hashlib.sha256(self.fixture_path.read_bytes()).hexdigest(),
        )
        documents = self.fixture["case"]["output"]["documents"]
        self.assertEqual({"aozora", "subtitle", "epub", "mokuro"}, set(documents))
        self.assertEqual("吾輩は猫である。", documents["aozora"]["units"][0]["text"])
        self.assertEqual("Imported subtitles", documents["subtitle"]["series"])
        self.assertEqual("OEBPS/cover.png", documents["epub"]["units"][0]["image"]["entry"])
        self.assertEqual([1, 2, 30, 40], documents["mokuro"]["units"][0]["block_box"])

        process = self.fixture["case"]["output"]["mokuro_process_reading"]
        self.assertEqual(1, process["result"]["cards_created"])
        self.assertEqual([4242], process["result"]["card_ids"])
        self.assertEqual("猫を見る。", process["card"]["word"]["sentence"])
        self.assertEqual("JPEG", process["card"]["media"]["format"])

    def test_output_tampering_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.fixture)
        tampered["case"]["output"]["documents"]["mokuro"]["units"][0]["text"] += "改"
        with self.assertRaisesRegex(CONTRACT.ReadingContractError, "output hash"):
            self.validate(tampered)

    def test_tool_and_corpus_provenance_are_independent(self) -> None:
        provenance = self.fixture["provenance"]
        self.assertEqual({"revision", "tree_sha256"}, set(provenance["engine"]))
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
        self.assertEqual({"corpus_sha256", "sha256"}, set(provenance["input"]))
        self.assertRegex(provenance["output_sha256"], r"^[0-9a-f]{64}$")

    def test_missing_lock_is_reported_as_contract_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(CONTRACT.ReadingContractError, "engine.lock"):
                CONTRACT.validate_fixture(
                    self.fixture,
                    lock_path=Path(temporary) / "missing.lock",
                    corpus_path=self.corpus_path,
                    exporter_path=self.exporter_path,
                    contract_path=SCRIPT_PATH,
                )

    def test_exporter_uses_real_loader_and_process_reading(self) -> None:
        source = self.exporter_path.read_text(encoding="utf-8")
        self.assertIn("refs = detector.detect(source)", source)
        self.assertIn("document = detector.load(refs[0])", source)
        self.assertIn("result = processor.process_reading(document)", source)
        self.assertIn("WordFilterService(config)", source)
        self.assertNotIn("expected_output", source)


if __name__ == "__main__":
    unittest.main()
