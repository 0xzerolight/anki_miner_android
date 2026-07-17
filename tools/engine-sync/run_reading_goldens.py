#!/usr/bin/env python3
"""Re-derive and verify the desktop reading parity fixture."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

from engine_sync.core import EngineSyncError
from engine_sync.golden_contract import (
    GoldenContractError,
    canonical_json_bytes,
    locked_revision,
    sha256_bytes,
    sha256_file,
    verify_engine_root,
)

SCHEMA_VERSION = 1
TOOL_NAME = "anki-miner-reading-goldens"
TOOL_VERSION = "1"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SUPPORT_TOOL_PATHS = (
    "engine_sync/__init__.py",
    "engine_sync/_golden_bootstrap.py",
    "engine_sync/core.py",
    "engine_sync/golden_contract.py",
)
SOURCE_NAMES = ("aozora", "subtitle", "epub", "mokuro")


class ReadingContractError(RuntimeError):
    """The fixture or derivation environment violates the reading contract."""


def _support_tool_sha256(contract_path: Path) -> str:
    script_root = contract_path.parent
    sources = {"run_reading_goldens.py": sha256_file(contract_path)}
    for relative in SUPPORT_TOOL_PATHS:
        sources[relative] = sha256_file(script_root / relative)
    return sha256_bytes(canonical_json_bytes(sources))


def _expect_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        raise ReadingContractError(f"{label} must be a lowercase SHA-256")
    return value


def _load_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReadingContractError(f"invalid {label}: {exc}") from exc


def _exact_dict(value: Any, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ReadingContractError(f"{label} has an unexpected shape")
    return value


def validate_fixture(
    payload: Any,
    *,
    lock_path: Path,
    corpus_path: Path,
    exporter_path: Path,
    contract_path: Path,
) -> None:
    payload = _exact_dict(payload, {"schema_version", "provenance", "case"}, "fixture root")
    if payload["schema_version"] != SCHEMA_VERSION:
        raise ReadingContractError("reading fixture schema_version must be 1")
    provenance = _exact_dict(
        payload["provenance"], {"engine", "tool", "input", "output_sha256"}, "provenance"
    )
    engine = _exact_dict(provenance["engine"], {"revision", "tree_sha256"}, "engine provenance")
    try:
        expected_revision = locked_revision(lock_path)
    except EngineSyncError as exc:
        raise ReadingContractError(f"cannot read engine.lock: {exc}") from exc
    if engine["revision"] != expected_revision:
        raise ReadingContractError("reading fixture engine revision differs from engine.lock")
    _expect_sha256(engine["tree_sha256"], "engine.tree_sha256")

    tool = _exact_dict(
        provenance["tool"],
        {"name", "version", "exporter_sha256", "contract_sha256", "support_sha256"},
        "tool provenance",
    )
    if tool["name"] != TOOL_NAME or tool["version"] != TOOL_VERSION:
        raise ReadingContractError("reading fixture tool identity is unsupported")
    if _expect_sha256(tool["exporter_sha256"], "tool.exporter_sha256") != sha256_file(
        exporter_path
    ):
        raise ReadingContractError("reading fixture exporter hash is stale")
    if _expect_sha256(tool["contract_sha256"], "tool.contract_sha256") != sha256_file(
        contract_path
    ):
        raise ReadingContractError("reading fixture contract hash is stale")
    if _expect_sha256(tool["support_sha256"], "tool.support_sha256") != _support_tool_sha256(
        contract_path
    ):
        raise ReadingContractError("reading fixture support-tool hash is stale")

    fixture_input = _exact_dict(
        provenance["input"], {"corpus_sha256", "sha256"}, "input provenance"
    )
    if _expect_sha256(fixture_input["corpus_sha256"], "input.corpus_sha256") != sha256_file(
        corpus_path
    ):
        raise ReadingContractError("reading fixture corpus hash is stale")
    if _expect_sha256(fixture_input["sha256"], "input.sha256") != sha256_bytes(
        canonical_json_bytes({"corpus_sha256": fixture_input["corpus_sha256"]})
    ):
        raise ReadingContractError("reading fixture canonical input hash is invalid")

    case = _exact_dict(payload["case"], {"input", "output"}, "case")
    if case["input"] != _load_json(corpus_path, "reading corpus"):
        raise ReadingContractError("reading fixture input differs from the corpus")
    output = _exact_dict(
        case["output"], {"documents", "mokuro_process_reading"}, "case output"
    )
    documents = output["documents"]
    if not isinstance(documents, dict) or tuple(sorted(documents)) != tuple(sorted(SOURCE_NAMES)):
        raise ReadingContractError("reading fixture document source set is invalid")
    for source_name in SOURCE_NAMES:
        document = _exact_dict(
            documents[source_name],
            {"title", "kind", "series", "episode", "warnings", "units"},
            f"{source_name} document",
        )
        if not isinstance(document["units"], list) or not document["units"]:
            raise ReadingContractError(f"{source_name} document must contain units")
    process = _exact_dict(
        output["mokuro_process_reading"],
        {
            "result",
            "parser_received_all_units",
            "definition_lookup_pairs",
            "definition_fallback_context",
            "definition_service_closed",
            "anki_target_verified",
            "card",
        },
        "Mokuro process_reading output",
    )
    result = process["result"]
    card = process["card"]
    if (
        not isinstance(result, dict)
        or result.get("cards_created") != 1
        or result.get("card_ids") != [4242]
        or result.get("mined_forms") != ["猫"]
        or process["parser_received_all_units"] is not True
        or process["definition_service_closed"] is not True
        or process["anki_target_verified"] is not True
        or not isinstance(card, dict)
        or card.get("media", {}).get("screenshot_filename_matches_contract") is not True
    ):
        raise ReadingContractError("Mokuro process_reading evidence is incomplete")
    if _expect_sha256(provenance["output_sha256"], "provenance.output_sha256") != sha256_bytes(
        canonical_json_bytes(output)
    ):
        raise ReadingContractError("reading fixture canonical output hash is invalid")


def _normalize(path: Path, *, kind: str, label: str) -> Path:
    unresolved = path.expanduser().absolute()
    if unresolved.is_symlink():
        raise ReadingContractError(f"{label} must not be a symlink: {unresolved}")
    try:
        resolved = unresolved.resolve(strict=True)
    except OSError as exc:
        raise ReadingContractError(f"{label} does not exist: {unresolved}") from exc
    if kind == "file" and not resolved.is_file():
        raise ReadingContractError(f"{label} must be a regular file: {resolved}")
    if kind == "directory" and not resolved.is_dir():
        raise ReadingContractError(f"{label} must be a directory: {resolved}")
    return resolved


def _normalize_python(path: Path) -> Path:
    candidate = path.expanduser().absolute()
    if not candidate.is_file() or not os.access(candidate, os.X_OK):
        raise ReadingContractError(f"--python must be executable: {candidate}")
    return candidate


def derive_fixture(
    *,
    python: Path,
    engine_root: Path,
    lock_path: Path,
    corpus_path: Path,
    exporter_path: Path,
    contract_path: Path,
    timeout_seconds: int,
) -> dict[str, Any]:
    python = _normalize_python(python)
    engine_root = _normalize(engine_root, kind="directory", label="--engine-root")
    corpus_path = _normalize(corpus_path, kind="file", label="--corpus")
    exporter_path = _normalize(exporter_path, kind="file", label="--exporter")
    try:
        revision = locked_revision(lock_path)
        engine_tree_sha256 = verify_engine_root(engine_root, revision)
    except (EngineSyncError, GoldenContractError) as exc:
        raise ReadingContractError(f"cannot verify pinned engine root: {exc}") from exc

    bootstrap_path = _normalize(
        contract_path.parent / "engine_sync/_golden_bootstrap.py",
        kind="file",
        label="isolated golden bootstrap",
    )
    with tempfile.TemporaryDirectory(prefix="anki-miner-reading-contract-") as temp_name:
        temp_root = Path(temp_name)
        home = temp_root / "home"
        home.mkdir()
        derived_path = temp_root / "derived.json"
        environment = {
            "ANKI_MINER_HOME": os.fspath(home),
            "HOME": os.fspath(home),
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "PATH": os.fspath(python.parent) + os.pathsep + os.defpath,
            "PYTHONHASHSEED": "0",
            "TZ": "UTC",
        }
        command = [
            os.fspath(python),
            "-s",
            "-P",
            "-B",
            os.fspath(bootstrap_path),
            os.fspath(exporter_path),
            os.fspath(engine_root),
            "--engine-root",
            os.fspath(engine_root),
            "--corpus",
            os.fspath(corpus_path),
            "--output",
            os.fspath(derived_path),
        ]
        try:
            completed = subprocess.run(
                command,
                cwd=temp_root,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise ReadingContractError(f"reading exporter could not run: {exc}") from exc
        if completed.returncode != 0:
            detail = completed.stderr.strip() or completed.stdout.strip()
            raise ReadingContractError(
                f"reading exporter exited {completed.returncode}: {detail}"
            )
        output = _load_json(derived_path, "derived reading output")

    input_provenance = {"corpus_sha256": sha256_file(corpus_path)}
    input_provenance["sha256"] = sha256_bytes(canonical_json_bytes(input_provenance))
    payload = {
        "schema_version": SCHEMA_VERSION,
        "provenance": {
            "engine": {"revision": revision, "tree_sha256": engine_tree_sha256},
            "tool": {
                "name": TOOL_NAME,
                "version": TOOL_VERSION,
                "exporter_sha256": sha256_file(exporter_path),
                "contract_sha256": sha256_file(contract_path),
                "support_sha256": _support_tool_sha256(contract_path),
            },
            "input": input_provenance,
            "output_sha256": sha256_bytes(canonical_json_bytes(output)),
        },
        "case": {"input": _load_json(corpus_path, "reading corpus"), "output": output},
    }
    validate_fixture(
        payload,
        lock_path=lock_path,
        corpus_path=corpus_path,
        exporter_path=exporter_path,
        contract_path=contract_path,
    )
    return payload


def _parser(script_root: Path, project_root: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-root", type=Path, required=True)
    parser.add_argument("--python", type=Path, default=Path(sys.executable))
    parser.add_argument("--lock", type=Path, default=script_root / "engine.lock")
    parser.add_argument(
        "--corpus", type=Path, default=project_root / "golden/corpus/reading-v1-input.json"
    )
    parser.add_argument(
        "--exporter", type=Path, default=script_root / "export_reading_goldens.py"
    )
    parser.add_argument(
        "--output", type=Path, default=project_root / "golden/reading-v1.json"
    )
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    contract_path = Path(__file__).resolve()
    script_root = contract_path.parent
    project_root = script_root.parents[1]
    args = _parser(script_root, project_root).parse_args(argv)
    try:
        payload = derive_fixture(
            python=args.python,
            engine_root=args.engine_root,
            lock_path=args.lock,
            corpus_path=args.corpus,
            exporter_path=args.exporter,
            contract_path=contract_path,
            timeout_seconds=args.timeout_seconds,
        )
        rendered = canonical_json_bytes(payload) + b"\n"
        if args.check:
            if not args.output.is_file() or args.output.read_bytes() != rendered:
                print(f"reading fixture drift detected: {args.output}", file=sys.stderr)
                return 1
            print(f"reading fixture matches {args.output}")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(rendered)
        print(f"wrote reading fixture to {args.output}")
        return 0
    except (OSError, ValueError, ReadingContractError) as exc:
        print(f"reading golden contract: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
