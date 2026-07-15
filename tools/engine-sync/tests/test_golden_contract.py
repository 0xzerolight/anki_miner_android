from __future__ import annotations

import json
import platform
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from engine_sync import _runtime_probe
from engine_sync.golden_contract import (
    CASE_SECTIONS,
    RUNTIME_DISTRIBUTIONS,
    RESERVED_UNIDIC_ASSET,
    TOOL_NAME,
    TOOL_VERSION,
    UNIDIC_FEATURE_FIELDS,
    GoldenAsset,
    GoldenContractError,
    canonical_json_bytes,
    parse_assets,
    run_exporter,
    sha256_bytes,
    sha256_file,
    sha256_path,
    sha256_tree,
    validate_fixture,
    verify_engine_root,
)


REAL_RUN = subprocess.run

FAKE_EXPORTER = r"""#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path


def canonical(value):
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tree_hash(root):
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if not path.is_file() or "__pycache__" in path.parts:
            continue
        relative = path.relative_to(root).as_posix().encode("utf-8")
        content = path.read_bytes()
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def path_hash(path):
    return file_hash(path) if path.is_file() else tree_hash(path)


parser = argparse.ArgumentParser()
parser.add_argument("--engine-root", type=Path, required=True)
parser.add_argument("--corpus", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--dicdir", type=Path, required=True)
parser.add_argument("--asset", action="append", default=[])
parser.add_argument("--compact", action="store_true")
args = parser.parse_args()
sys.path.insert(0, str(args.engine_root))
import anki_miner
payload = json.loads(Path(__file__).with_suffix(".template.json").read_text())
engine_package = args.engine_root / "anki_miner"
payload["provenance"]["engine"] = {
    "revision": subprocess.run(
        ["git", "-C", str(args.engine_root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip(),
    "tree_sha256": tree_hash(engine_package),
}
payload["provenance"]["tool"]["sha256"] = file_hash(Path(__file__).resolve())
runtime = {
    "python_implementation": platform.python_implementation(),
    "python_version": platform.python_version(),
    "platform": f"{sys.platform}-{platform.machine().lower()}",
    "dependencies": {},
}
runtime["sha256"] = hashlib.sha256(canonical(runtime)).hexdigest()
payload["provenance"]["runtime"] = runtime
assets = {
    value.partition("=")[0]: Path(value.partition("=")[2]).resolve()
    for value in args.asset
}
assets["unidic_dicdir"] = args.dicdir.resolve()
data = {
    "corpus_sha256": file_hash(args.corpus),
    "assets_sha256": {
        name: path_hash(path) for name, path in sorted(assets.items())
    },
}
data["sha256"] = hashlib.sha256(canonical(data)).hexdigest()
payload["provenance"]["data"] = data
args.output.write_bytes(canonical(payload) + b"\n")
for name in tuple(sys.modules):
    if name == "anki_miner" or name.startswith("anki_miner."):
        del sys.modules[name]
"""


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
        self.corpus.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "cases": [
                        {
                            "id": "astral",
                            "text": "猫𠮟犬",
                            "coverage": ["astral-codepoint", "oov-empty-orthbase"],
                            "dictionary_terms": ["猫"],
                            "expect": {
                                "token": {
                                    "surface": "𠮟",
                                    "lemma": None,
                                    "orthBase": None,
                                    "is_unknown": True,
                                }
                            },
                        }
                    ],
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        self.exporter = self.root / "dump.py"
        self.exporter.write_text("# exporter fixture\n", encoding="utf-8")
        self.asset_path = self.root / "dicdir"
        self.asset_path.mkdir()
        (self.asset_path / "sys.dic").write_bytes(b"dictionary")
        self.assets = (GoldenAsset(RESERVED_UNIDIC_ASSET, self.asset_path),)
        self.runtime_identity = {
            "python_implementation": "CPython",
            "python_version": "3.11.0",
            "platform": "linux-x86_64",
            "dependencies": {
                "fugashi": {
                    "version": "1.5.0",
                    "content_sha256": "1" * 64,
                },
                "unidic-lite": {
                    "version": "1.0.8",
                    "content_sha256": "2" * 64,
                },
            },
        }

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def _payload(self) -> dict:
        runtime = {
            **self.runtime_identity,
            "dependencies": {
                name: dict(record)
                for name, record in self.runtime_identity["dependencies"].items()
            },
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
        word = {
            "surface": "猫",
            "lemma": "猫",
            "orth_base": "猫",
            "mined_form": "猫",
            "reading": "ネコ",
            "pos": "名詞",
            "surface_start": 0,
            "surface_end": 1,
            "highlight_end": 1,
            "sentence": "猫𠮟犬",
            "expression_furigana": "猫[ねこ]",
            "expression_reading": "ねこ",
            "sentence_furigana": "猫[ねこ]𠮟犬[いぬ]",
            "sentence_reading": "ねこ𠮟いぬ",
        }
        cases["morphology"] = [
            {
                "id": "astral",
                "input": {"text": "猫𠮟犬"},
                "output": {"words": [dict(word)]},
            }
        ]
        cases["compounds"] = [
            {
                "id": "astral",
                "input": {"text": "猫𠮟犬", "dictionary_terms": ["猫"]},
                "output": {"words": [dict(word)]},
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
            "section_status": {
                section: (
                    {"state": "implemented"}
                    if section in {"tokenization", "morphology", "compounds"}
                    else {"state": "pending", "reason": "Staged test section."}
                )
                for section in CASE_SECTIONS
            },
            "cases": cases,
        }

    def _validate(self, payload: dict) -> None:
        validate_fixture(
            payload,
            engine_root=self.engine,
            expected_revision=self.revision,
            corpus_path=self.corpus,
            exporter_path=self.exporter,
            expected_runtime=self.runtime_identity,
            assets=self.assets,
        )

    def _prepare_fake_exporter(self) -> None:
        self.exporter.write_text(FAKE_EXPORTER, encoding="utf-8")
        template = self._payload()
        self.exporter.with_suffix(".template.json").write_text(
            json.dumps(template, ensure_ascii=False), encoding="utf-8"
        )

    def test_valid_fixture_has_separate_verified_provenance_hashes(self) -> None:
        self._validate(self._payload())

    def test_clean_engine_root_matches_pinned_tree(self) -> None:
        self.assertEqual(
            verify_engine_root(self.engine, self.revision),
            sha256_tree(self.engine / "anki_miner"),
        )

    def test_skip_worktree_modified_engine_file_is_rejected(self) -> None:
        tracked = self.engine / "anki_miner/__init__.py"
        _run(
            "git",
            "update-index",
            "--skip-worktree",
            "anki_miner/__init__.py",
            cwd=self.engine,
        )
        tracked.write_text("HIDDEN = 'modified'\n", encoding="utf-8")
        self.assertEqual(
            _run("git", "status", "--porcelain", "--", "anki_miner", cwd=self.engine),
            "",
        )
        with self.assertRaisesRegex(GoldenContractError, "content differs"):
            verify_engine_root(self.engine, self.revision)

    def test_runtime_hash_cannot_be_relabelled(self) -> None:
        payload = self._payload()
        payload["provenance"]["runtime"]["python_version"] = "9.9"
        with self.assertRaisesRegex(GoldenContractError, "runtime canonical hash"):
            self._validate(payload)

    def test_runtime_dependency_content_hash_is_structural(self) -> None:
        payload = self._payload()
        payload["provenance"]["runtime"]["dependencies"]["fugashi"][
            "content_sha256"
        ] = "not-a-hash"
        with self.assertRaisesRegex(GoldenContractError, "content_sha256"):
            self._validate(payload)

    def test_runtime_probe_mapping_and_named_file_hash_are_frozen(self) -> None:
        self.assertEqual(
            set(_runtime_probe.DISTRIBUTION_IMPORTS), set(RUNTIME_DISTRIBUTIONS)
        )
        package = self.root / "runtime/package.py"
        native = self.root / "runtime.libs/library.so"
        package.parent.mkdir()
        native.parent.mkdir()
        package.write_bytes(b"python")
        native.write_bytes(b"native-one")
        files = {"runtime/package.py": package, "runtime.libs/library.so": native}
        original = _runtime_probe._sha256_named_files(files)
        native.write_bytes(b"native-two")
        self.assertNotEqual(_runtime_probe._sha256_named_files(files), original)

    def test_section_status_cannot_hide_staged_coverage(self) -> None:
        payload = self._payload()
        payload["section_status"]["filtering"] = {
            "state": "pending",
            "reason": "   ",
        }
        with self.assertRaisesRegex(GoldenContractError, "pending with a reason"):
            self._validate(payload)

        payload = self._payload()
        payload["section_status"]["morphology"] = {
            "state": "pending",
            "reason": "hidden",
        }
        with self.assertRaisesRegex(GoldenContractError, "morphology"):
            self._validate(payload)

    def test_runtime_is_compared_to_selected_interpreter(self) -> None:
        payload = self._payload()
        runtime = payload["provenance"]["runtime"]
        runtime["python_version"] = "9.9"
        runtime_without_hash = {
            key: value for key, value in runtime.items() if key != "sha256"
        }
        runtime["sha256"] = sha256_bytes(canonical_json_bytes(runtime_without_hash))
        with self.assertRaisesRegex(GoldenContractError, "runtime identity"):
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

    def test_runner_is_repeatable_in_real_isolated_subprocesses(self) -> None:
        self._prepare_fake_exporter()
        python_link = self.root / "venv/bin/python"
        python_link.parent.mkdir(parents=True)
        python_link.symlink_to(Path(sys.executable))
        first = self.root / "golden-first.json"
        second = self.root / "golden-second.json"
        for output in (first, second):
            self.assertTrue(
                run_exporter(
                    python=python_link,
                    exporter_path=self.exporter,
                    engine_root=self.engine,
                    expected_revision=self.revision,
                    corpus_path=self.corpus,
                    output_path=output,
                    dicdir=self.asset_path,
                    runtime_distributions=(),
                )
            )
        self.assertEqual(first.read_bytes(), second.read_bytes())
        payload = json.loads(first.read_text(encoding="utf-8"))
        self.assertEqual(
            payload["provenance"]["data"]["assets_sha256"],
            {RESERVED_UNIDIC_ASSET: sha256_path(self.asset_path)},
        )
        self.assertEqual(
            payload["provenance"]["runtime"]["python_version"],
            platform.python_version(),
        )

    def test_check_mode_reports_drift_without_writing(self) -> None:
        self._prepare_fake_exporter()
        output = self.root / "golden.json"
        output.write_text("stale\n", encoding="utf-8")
        self.assertFalse(
            run_exporter(
                python=Path(sys.executable),
                exporter_path=self.exporter,
                engine_root=self.engine,
                expected_revision=self.revision,
                corpus_path=self.corpus,
                output_path=output,
                dicdir=self.asset_path,
                check=True,
                runtime_distributions=(),
            )
        )
        self.assertEqual(output.read_text(encoding="utf-8"), "stale\n")

    def test_reserved_unidic_asset_name_is_rejected(self) -> None:
        with self.assertRaisesRegex(GoldenContractError, "reserved"):
            parse_assets([f"{RESERVED_UNIDIC_ASSET}={self.asset_path}"])

    def test_empty_active_section_is_rejected(self) -> None:
        payload = self._payload()
        payload["cases"]["morphology"] = []
        with self.assertRaisesRegex(GoldenContractError, "morphology"):
            self._validate(payload)

    def test_fixture_case_must_match_corpus_identity(self) -> None:
        payload = self._payload()
        payload["cases"]["tokenization"][0]["text"] = "different"
        with self.assertRaisesRegex(GoldenContractError, "does not match the corpus"):
            self._validate(payload)

    def test_unidic_provenance_hash_is_recomputed(self) -> None:
        payload = self._payload()
        (self.asset_path / "sys.dic").write_bytes(b"changed")
        with self.assertRaisesRegex(GoldenContractError, "asset hashes"):
            self._validate(payload)


if __name__ == "__main__":
    unittest.main()
