#!/usr/bin/env python3
"""Re-derive and verify the canonical desktop S4 engine-smoke fixture."""

from __future__ import annotations

import argparse
import hashlib
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
    sha256_path,
    verify_engine_root,
)

SCHEMA_VERSION = 1
TOOL_NAME = "anki-miner-s4-engine-smoke"
TOOL_VERSION = "1"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SUPPORT_TOOL_PATHS = (
    "engine_sync/__init__.py",
    "engine_sync/_golden_bootstrap.py",
    "engine_sync/core.py",
    "engine_sync/golden_contract.py",
)


class S4ContractError(RuntimeError):
    """The S4 fixture or derivation environment violates its contract."""


def _support_tool_sha256(contract_path: Path) -> str:
    """Hash the complete local support path used to derive the fixture."""

    script_root = contract_path.parent
    sources = {"run_s4_engine_smoke.py": sha256_file(contract_path)}
    for relative in SUPPORT_TOOL_PATHS:
        sources[relative] = sha256_file(script_root / relative)
    return sha256_bytes(canonical_json_bytes(sources))


def _expect_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        raise S4ContractError(f"{label} must be a lowercase SHA-256")
    return value


def _load_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise S4ContractError(f"invalid {label}: {exc}") from exc


def validate_fixture(
    payload: Any,
    *,
    lock_path: Path,
    corpus_path: Path,
    exporter_path: Path,
    contract_path: Path,
) -> None:
    if not isinstance(payload, dict) or set(payload) != {
        "schema_version",
        "provenance",
        "case",
    }:
        raise S4ContractError("S4 fixture root has an unexpected shape")
    if payload["schema_version"] != SCHEMA_VERSION:
        raise S4ContractError("S4 fixture schema_version must be 1")
    provenance = payload["provenance"]
    if not isinstance(provenance, dict) or set(provenance) != {
        "engine",
        "tool",
        "input",
        "output_sha256",
    }:
        raise S4ContractError("S4 fixture provenance has an unexpected shape")
    engine = provenance["engine"]
    if not isinstance(engine, dict) or set(engine) != {"revision", "tree_sha256"}:
        raise S4ContractError("S4 fixture engine provenance is malformed")
    try:
        expected_revision = locked_revision(lock_path)
    except EngineSyncError as exc:
        raise S4ContractError(f"cannot read the engine lock: {exc}") from exc
    if engine["revision"] != expected_revision:
        raise S4ContractError("S4 fixture engine revision differs from engine.lock")
    _expect_sha256(engine["tree_sha256"], "engine.tree_sha256")

    tool = provenance["tool"]
    if not isinstance(tool, dict) or set(tool) != {
        "name",
        "version",
        "exporter_sha256",
        "contract_sha256",
        "support_sha256",
    }:
        raise S4ContractError("S4 fixture tool provenance is malformed")
    if tool["name"] != TOOL_NAME or tool["version"] != TOOL_VERSION:
        raise S4ContractError("S4 fixture tool identity is unsupported")
    if _expect_sha256(tool["exporter_sha256"], "tool.exporter_sha256") != sha256_file(
        exporter_path
    ):
        raise S4ContractError("S4 fixture exporter hash is stale")
    if _expect_sha256(tool["contract_sha256"], "tool.contract_sha256") != sha256_file(
        contract_path
    ):
        raise S4ContractError("S4 fixture contract hash is stale")
    if _expect_sha256(tool["support_sha256"], "tool.support_sha256") != (
        _support_tool_sha256(contract_path)
    ):
        raise S4ContractError("S4 fixture support-tool hash is stale")

    fixture_input = provenance["input"]
    if not isinstance(fixture_input, dict) or set(fixture_input) != {
        "corpus_sha256",
        "unidic_dicdir_sha256",
        "sha256",
    }:
        raise S4ContractError("S4 fixture input provenance is malformed")
    if _expect_sha256(
        fixture_input["corpus_sha256"], "input.corpus_sha256"
    ) != sha256_file(corpus_path):
        raise S4ContractError("S4 fixture corpus hash is stale")
    _expect_sha256(
        fixture_input["unidic_dicdir_sha256"], "input.unidic_dicdir_sha256"
    )
    input_without_hash = {
        key: value for key, value in fixture_input.items() if key != "sha256"
    }
    if _expect_sha256(fixture_input["sha256"], "input.sha256") != sha256_bytes(
        canonical_json_bytes(input_without_hash)
    ):
        raise S4ContractError("S4 fixture canonical input hash is invalid")

    case = payload["case"]
    if not isinstance(case, dict) or set(case) != {"input", "output"}:
        raise S4ContractError("S4 fixture case has an unexpected shape")
    if case["input"] != _load_json(corpus_path, "S4 corpus"):
        raise S4ContractError("S4 fixture input differs from the corpus")
    output = case["output"]
    if not isinstance(output, dict) or set(output) != {
        "parsed_words",
        "filtered_words",
        "selected_mined_form",
        "rendered_content",
        "lookup_html",
    }:
        raise S4ContractError("S4 fixture output has an unexpected shape")
    if not isinstance(output["parsed_words"], list) or not output["parsed_words"]:
        raise S4ContractError("S4 fixture parsed_words must be non-empty")
    if len(output["filtered_words"]) != 1:
        raise S4ContractError("S4 fixture must contain one filtered word")
    selected = output["selected_mined_form"]
    if (
        not isinstance(selected, str)
        or not selected
        or output["filtered_words"][0].get("mined_form") != selected
        or case["input"]["dictionary"]["term"] != selected
    ):
        raise S4ContractError("S4 fixture filtered target is inconsistent")
    for field in ("rendered_content", "lookup_html"):
        if not isinstance(output[field], str) or not output[field]:
            raise S4ContractError(f"S4 fixture {field} must be non-empty")
    expected_output_hash = sha256_bytes(canonical_json_bytes(output))
    if _expect_sha256(
        provenance["output_sha256"], "provenance.output_sha256"
    ) != expected_output_hash:
        raise S4ContractError("S4 fixture canonical output hash is invalid")


def _normalize(path: Path, *, kind: str, label: str) -> Path:
    unresolved = path.expanduser().absolute()
    if unresolved.is_symlink():
        raise S4ContractError(f"{label} must not be a symlink: {unresolved}")
    try:
        resolved = unresolved.resolve(strict=True)
    except OSError as exc:
        raise S4ContractError(f"{label} does not exist: {unresolved}") from exc
    if kind == "file" and not resolved.is_file():
        raise S4ContractError(f"{label} must be a regular file: {resolved}")
    if kind == "directory" and not resolved.is_dir():
        raise S4ContractError(f"{label} must be a directory: {resolved}")
    return resolved


def _normalize_python(path: Path) -> Path:
    """Keep the venv entry path so Python discovers its pyvenv.cfg."""

    candidate = path.expanduser().absolute()
    if not candidate.is_file() or not os.access(candidate, os.X_OK):
        raise S4ContractError(f"--python must be an executable file: {candidate}")
    return candidate


def derive_fixture(
    *,
    python: Path,
    engine_root: Path,
    dicdir: Path,
    lock_path: Path,
    corpus_path: Path,
    exporter_path: Path,
    contract_path: Path,
    timeout_seconds: int,
) -> dict[str, Any]:
    python = _normalize_python(python)
    engine_root = _normalize(engine_root, kind="directory", label="--engine-root")
    dicdir = _normalize(dicdir, kind="directory", label="--dicdir")
    corpus_path = _normalize(corpus_path, kind="file", label="--corpus")
    exporter_path = _normalize(exporter_path, kind="file", label="--exporter")
    try:
        revision = locked_revision(lock_path)
    except EngineSyncError as exc:
        raise S4ContractError(f"cannot read the engine lock: {exc}") from exc
    try:
        engine_tree_sha256 = verify_engine_root(engine_root, revision)
        unidic_sha256 = sha256_path(dicdir)
    except GoldenContractError as exc:
        raise S4ContractError(f"golden contract support failed: {exc}") from exc
    corpus_sha256 = sha256_file(corpus_path)

    bootstrap_path = contract_path.parent / "engine_sync" / "_golden_bootstrap.py"
    bootstrap_path = _normalize(
        bootstrap_path, kind="file", label="isolated golden bootstrap"
    )
    with tempfile.TemporaryDirectory(prefix="anki-miner-s4-contract-") as temp_name:
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
            "--dicdir",
            os.fspath(dicdir),
            "--output",
            os.fspath(derived_path),
        ]
        try:
            result = subprocess.run(
                command,
                cwd=temp_root,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise S4ContractError(f"S4 exporter could not run: {exc}") from exc
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip()
            raise S4ContractError(
                f"S4 exporter exited {result.returncode}: {detail}"
            )
        output = _load_json(derived_path, "derived S4 output")

    input_provenance = {
        "corpus_sha256": corpus_sha256,
        "unidic_dicdir_sha256": unidic_sha256,
    }
    input_provenance["sha256"] = sha256_bytes(
        canonical_json_bytes(input_provenance)
    )
    payload = {
        "schema_version": SCHEMA_VERSION,
        "provenance": {
            "engine": {
                "revision": revision,
                "tree_sha256": engine_tree_sha256,
            },
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
        "case": {
            "input": _load_json(corpus_path, "S4 corpus"),
            "output": output,
        },
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
    parser.add_argument("--dicdir", type=Path, required=True)
    parser.add_argument("--python", type=Path, default=Path(sys.executable))
    parser.add_argument(
        "--lock", type=Path, default=script_root / "engine.lock"
    )
    parser.add_argument(
        "--corpus",
        type=Path,
        default=project_root / "golden/corpus/s4-engine-smoke-v1.json",
    )
    parser.add_argument(
        "--exporter",
        type=Path,
        default=script_root / "export_s4_engine_smoke.py",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=project_root / "golden/s4-engine-smoke-v1.json",
    )
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=600)
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
            dicdir=args.dicdir,
            lock_path=args.lock,
            corpus_path=args.corpus,
            exporter_path=args.exporter,
            contract_path=contract_path,
            timeout_seconds=args.timeout_seconds,
        )
        rendered = canonical_json_bytes(payload) + b"\n"
        if args.check:
            matched = args.output.is_file() and args.output.read_bytes() == rendered
            if not matched:
                print(f"S4 fixture drift detected: {args.output}", file=sys.stderr)
                return 1
            print(f"S4 fixture matches {args.output}")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_name(f".{args.output.name}.{os.getpid()}.tmp")
        temporary.write_bytes(rendered)
        os.replace(temporary, args.output)
        print(f"S4 fixture wrote {args.output}")
        return 0
    except (OSError, S4ContractError, GoldenContractError) as exc:
        print(f"S4 contract error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
