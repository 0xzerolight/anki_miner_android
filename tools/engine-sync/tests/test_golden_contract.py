from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from engine_sync.golden_contract import (
    CASE_SECTIONS,
    TOOL_NAME,
    TOOL_VERSION,
    UNIDIC_FEATURE_FIELDS,
    GoldenAsset,
    GoldenContractError,
    canonical_json_bytes,
    run_exporter,
    sha256_bytes,
    sha256_file,
    sha256_path,
    sha256_tree,
    validate_fixture,
)


REAL_RUN = subprocess.run


def _run(*args: str, cwd: Path) -> str:
    result = REAL_RUN(args, cwd=cwd, check=True, capture_output=True, text=True)
    return result.stdout.strip()


class GoldenContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary.name)
        self.engine = self.root / "engine"
        package = self.engine / "anki_miner"
        package.mkdir(parents=True)
        (package / "__init__.py").write_text("VERSION = 1\n", encoding="utf-8")
        _run("git", "init", "-q", cwd=self.engine)
        _run("git", "config", "user.email", "tests@example.invalid", cwd=self.engine)
        _run("git", "config", "user.name", "Golden Tests", cwd=self.engine)
        _run("git", "add", ".", cwd=self.engine)
        _run("git", "commit", "-qm", "fixture", cwd=self.engine)
        self.revision = _run("git", "rev-parse", "HEAD", cwd=self.engine)
        self.corpus = self.root / "corpus.json"
        self.corpus.write_text('{"schema_version":1,"cases":[]}\n', encoding="utf-8")
        self.exporter = self.root / "dump.py"
        self.exporter.write_text("# exporter fixture\n", encoding="utf-8")
        self.asset_path = self.root / "dicdir"
        self.asset_path.mkdir()
        (self.asset_path / "sys.dic").write_bytes(b"dictionary")
        self.assets = (GoldenAsset("unidic", self.asset_path),)

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def _payload(self) -> dict:
        runtime = {
            "python_implementation": "CPython",
            "python_version": "3.11.0",
            "platform": "linux-x86_64",
            "dependencies": {"fugashi": "1.5.0", "unidic-lite": "1.0.8"},
        }
        runtime["sha256"] = sha256_bytes(canonical_json_bytes(runtime))
        data = {
            "corpus_sha256": sha256_file(self.corpus),
            "assets_sha256": {
                asset.name: sha256_path(asset.path) for asset in self.assets
            },
        }
        data["sha256"] = sha256_bytes(canonical_json_bytes(data))
        null_features = {name: None for name in UNIDIC_FEATURE_FIELDS}
        cases = {section: [] for section in CASE_SECTIONS}
        cases["tokenization"] = [
            {
                "id": "astral",
                "text": "猫𠮟犬",
                "tokens": [
                    {
                        "surface": "猫",
                        "is_unknown": False,
                        "offsets": {
                            "codepoint_start": 0,
                            "codepoint_end": 1,
                            "utf16_start": 0,
                            "utf16_end": 1,
                        },
                        "features": dict(null_features),
                    },
                    {
                        "surface": "𠮟",
                        "is_unknown": True,
                        "offsets": {
                            "codepoint_start": 1,
                            "codepoint_end": 2,
                            "utf16_start": 1,
                            "utf16_end": 3,
                        },
                        "features": dict(null_features),
                    },
                    {
                        "surface": "犬",
                        "is_unknown": False,
                        "offsets": {
                            "codepoint_start": 2,
                            "codepoint_end": 3,
                            "utf16_start": 3,
                            "utf16_end": 4,
                        },
                        "features": dict(null_features),
                    },
                ],
            }
        ]
        return {
            "schema_version": 1,
            "provenance": {
                "engine": {
                    "revision": self.revision,
                    "tree_sha256": sha256_tree(self.engine / "anki_miner"),
                },
                "tool": {
                    "name": TOOL_NAME,
                    "version": TOOL_VERSION,
                    "sha256": sha256_file(self.exporter),
                },
                "runtime": runtime,
                "data": data,
            },
            "unidic_feature_fields": list(UNIDIC_FEATURE_FIELDS),
            "cases": cases,
        }

    def _validate(self, payload: dict) -> None:
        validate_fixture(
            payload,
            engine_root=self.engine,
            expected_revision=self.revision,
            corpus_path=self.corpus,
            exporter_path=self.exporter,
            assets=self.assets,
        )

    def test_valid_fixture_has_separate_verified_provenance_hashes(self) -> None:
        self._validate(self._payload())

    def test_runtime_hash_cannot_be_relabelled(self) -> None:
        payload = self._payload()
        payload["provenance"]["runtime"]["python_version"] = "9.9"
        with self.assertRaisesRegex(GoldenContractError, "runtime canonical hash"):
            self._validate(payload)

    def test_unidic_star_must_be_normalized_to_null(self) -> None:
        payload = self._payload()
        payload["cases"]["tokenization"][0]["tokens"][1]["features"]["orthBase"] = "*"
        with self.assertRaisesRegex(GoldenContractError, "normalize '\\*' to null"):
            self._validate(payload)

    def test_utf16_offsets_are_checked_independently(self) -> None:
        payload = self._payload()
        payload["cases"]["tokenization"][0]["tokens"][1]["offsets"]["utf16_end"] = 2
        with self.assertRaisesRegex(GoldenContractError, "invalid UTF-16 offsets"):
            self._validate(payload)

    def test_runner_uses_isolated_environment_and_check_mode(self) -> None:
        payload = self._payload()
        observed: dict[str, object] = {}
        python_link = self.root / "venv/bin/python"
        python_link.parent.mkdir(parents=True)
        python_link.symlink_to(Path(sys.executable))

        def fake_run(command, **kwargs):
            if command[0] == "git":
                return REAL_RUN(command, **kwargs)
            observed["command"] = command
            observed["environment"] = kwargs["env"]
            observed["cwd"] = kwargs["cwd"]
            output_index = command.index("--output") + 1
            Path(command[output_index]).write_text(
                json.dumps(payload, ensure_ascii=False), encoding="utf-8"
            )
            return subprocess.CompletedProcess(command, 0, "", "")

        output = self.root / "golden.json"
        with mock.patch(
            "engine_sync.golden_contract.subprocess.run", side_effect=fake_run
        ):
            self.assertTrue(
                run_exporter(
                    python=python_link,
                    exporter_path=self.exporter,
                    engine_root=self.engine,
                    expected_revision=self.revision,
                    corpus_path=self.corpus,
                    output_path=output,
                    assets=self.assets,
                )
            )
            self.assertTrue(
                run_exporter(
                    python=python_link,
                    exporter_path=self.exporter,
                    engine_root=self.engine,
                    expected_revision=self.revision,
                    corpus_path=self.corpus,
                    output_path=output,
                    assets=self.assets,
                    check=True,
                )
            )

        command = observed["command"]
        environment = observed["environment"]
        self.assertEqual(command[1], "-I")
        self.assertEqual(Path(command[2]).name, "_golden_bootstrap.py")
        self.assertEqual(Path(command[0]), python_link)
        self.assertNotIn("PYTHONPATH", environment)
        self.assertEqual(environment["PYTHONHASHSEED"], "0")
        self.assertNotEqual(Path(observed["cwd"]), Path.cwd())
        self.assertEqual(output.read_bytes(), canonical_json_bytes(payload) + b"\n")

    def test_check_mode_reports_drift_without_writing(self) -> None:
        payload = self._payload()
        output = self.root / "golden.json"
        output.write_text("stale\n", encoding="utf-8")

        def fake_run(command, **kwargs):
            if command[0] == "git":
                return REAL_RUN(command, **kwargs)
            output_index = command.index("--output") + 1
            Path(command[output_index]).write_text(
                json.dumps(copy.deepcopy(payload)), encoding="utf-8"
            )
            return subprocess.CompletedProcess(command, 0, "", "")

        with mock.patch(
            "engine_sync.golden_contract.subprocess.run", side_effect=fake_run
        ):
            self.assertFalse(
                run_exporter(
                    python=Path(sys.executable),
                    exporter_path=self.exporter,
                    engine_root=self.engine,
                    expected_revision=self.revision,
                    corpus_path=self.corpus,
                    output_path=output,
                    assets=self.assets,
                    check=True,
                )
            )
        self.assertEqual(output.read_text(encoding="utf-8"), "stale\n")


if __name__ == "__main__":
    unittest.main()
