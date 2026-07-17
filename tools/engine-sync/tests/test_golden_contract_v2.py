from __future__ import annotations

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

from engine_sync.golden_contract_v2 import (
    CASE_SECTIONS,
    FIXTURE_SHA256,
    GoldenV2Error,
    canonical_json_bytes,
    derive_and_compare,
    validate_committed_fixture,
)


class GoldenV2ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.project_root = Path(__file__).resolve().parents[3]

    def test_committed_fixture_is_complete_canonical_and_pinned(self) -> None:
        fixture = validate_committed_fixture(self.project_root)
        self.assertEqual(2, fixture["schema_version"])
        self.assertEqual(set(fixture["cases"]), set(CASE_SECTIONS))
        self.assertTrue(all(fixture["cases"][section] for section in CASE_SECTIONS))
        self.assertEqual(
            {"state": "implemented"},
            fixture["section_status"]["cards"],
        )
        self.assertEqual(
            FIXTURE_SHA256,
            __import__("hashlib").sha256(
                (self.project_root / "golden/engine-v2.json").read_bytes()
            ).hexdigest(),
        )

    def test_packaged_replay_uses_the_contract_fixture_identity(self) -> None:
        replay_path = (
            self.project_root
            / "app/src/debug/python/engine_golden_v2_instrumented.py"
        )
        spec = importlib.util.spec_from_file_location(
            "engine_golden_v2_instrumented_contract_test",
            replay_path,
        )
        self.assertIsNotNone(spec)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(FIXTURE_SHA256, module.FIXTURE_SHA256)

    def test_fixture_validator_rejects_status_or_case_weakening(self) -> None:
        fixture_path = self.project_root / "golden/engine-v2.json"
        original = json.loads(fixture_path.read_text(encoding="utf-8"))
        for mutate in (
            lambda value: value["section_status"].__setitem__(
                "pitch", {"state": "pending", "reason": "later"}
            ),
            lambda value: value["cases"].__setitem__("cards", []),
        ):
            with self.subTest(mutate=mutate):
                changed = deepcopy(original)
                mutate(changed)
                with tempfile.TemporaryDirectory() as raw:
                    root = Path(raw)
                    for relative in (
                        "golden/schema/engine-goldens-v2.schema.json",
                        "golden/corpus/engine-v2-input.json",
                        "golden/corpus/tokenizer-v1.json",
                        "tools/engine-sync/engine.lock",
                    ):
                        destination = root / relative
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(self.project_root / relative, destination)
                    destination = root / "golden/engine-v2.json"
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(canonical_json_bytes(changed) + b"\n")
                    with self.assertRaises(GoldenV2Error):
                        validate_committed_fixture(root)

    def test_derivation_rejects_byte_drift(self) -> None:
        fixture = validate_committed_fixture(self.project_root)
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            python = root / "python"
            exporter = root / "dump_engine_goldens.py"
            engine = root / "engine"
            dicdir = root / "dicdir"
            python.write_text("", encoding="utf-8")
            python.chmod(0o755)
            exporter.write_text("", encoding="utf-8")
            engine.mkdir()
            dicdir.mkdir()
            with (
                mock.patch("engine_sync.golden_contract_v2.validate_committed_fixture", return_value=fixture),
                mock.patch("engine_sync.golden_contract_v2.verify_engine_root"),
                mock.patch("engine_sync.golden_contract_v2.verify_exporter_sources"),
                mock.patch(
                    "engine_sync.golden_contract_v2.materialize_golden_exporter",
                    return_value=exporter,
                ),
                mock.patch("engine_sync.golden_contract_v2.verify_unidic"),
                mock.patch("engine_sync.golden_contract_v2.load_lock", return_value="a" * 40),
                mock.patch("engine_sync.golden_contract_v2.subprocess.run") as run,
            ):
                def execute(command, **_kwargs):
                    output = Path(command[command.index("--output") + 1])
                    output.write_bytes(b"{}\n")
                    return mock.Mock(returncode=0, stdout="", stderr="")

                run.side_effect = execute
                with self.assertRaisesRegex(GoldenV2Error, "drift"):
                    derive_and_compare(
                        project_root=self.project_root,
                        python=python,
                        exporter=exporter,
                        engine_root=engine,
                        dicdir=dicdir,
                    )


if __name__ == "__main__":
    unittest.main()
