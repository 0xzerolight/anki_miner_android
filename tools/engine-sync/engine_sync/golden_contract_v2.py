"""Strict Android consumer for the complete desktop engine golden contract."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from typing import Any

import jsonschema

from .core import EngineSyncError, load_lock
from .golden_exporter_overlay import materialize_golden_exporter


SCHEMA_VERSION = 2
CASE_SECTIONS = (
    "tokenization",
    "morphology",
    "filtering",
    "deinflection",
    "compounds",
    "dictionaries",
    "frequency",
    "pitch",
    "cards",
)
FIXTURE_SHA256 = "883657e9710656046f8900216ce6918135549956b135b07155357048a41ef764"
INPUT_SHA256 = "68e3ce3bd9a9073534817a83ce6978e12758d85ab98887c014a0c2db932fbc79"
SCHEMA_SHA256 = "05e611d5e2c10168a8dfd93d318fd007c67d2eecd7d67adbe72d1de49ee52115"
TOKENIZER_CORPUS_SHA256 = (
    "12105dd9e223a1f3851f544074db93b0ce3b13b9e14f921f4a605e1504da405a"
)
ENGINE_TREE_SHA256 = "1ee6e67fa6981046bb56c977f0c488ffce04eb8b0f439840a9d8a6ee31bc8e23"
UNIDIC_TREE_SHA256 = "bd942f1b395aa7c56fe20321dc7f021930e29107f6b2949a49f5c56caab55ea7"
UNIDIC_FILE_COUNT = 19
UNIDIC_SIZE_BYTES = 260_467_176
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class GoldenV2Error(EngineSyncError):
    """The complete golden fixture or its derivation environment is invalid."""


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256_file(path: Path) -> str:
    try:
        value = path.lstat()
    except OSError as exc:
        raise GoldenV2Error(f"cannot inspect {path}: {exc}") from exc
    if stat.S_ISLNK(value.st_mode) or not stat.S_ISREG(value.st_mode):
        raise GoldenV2Error(f"golden input must be a regular non-symlink file: {path}")
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as exc:
        raise GoldenV2Error(f"cannot hash {path}: {exc}") from exc
    return digest.hexdigest()


def _exact_file(path: Path, expected: str, label: str) -> None:
    actual = sha256_file(path)
    if actual != expected:
        raise GoldenV2Error(f"{label} SHA-256 mismatch: expected {expected}, got {actual}")


def _aggregate_hash(value: dict[str, Any], label: str) -> None:
    unhashed = dict(value)
    recorded = unhashed.pop("sha256", None)
    actual = hashlib.sha256(canonical_json_bytes(unhashed)).hexdigest()
    if recorded != actual:
        raise GoldenV2Error(f"{label} aggregate provenance hash is invalid")


def _paths(project_root: Path) -> tuple[Path, Path, Path, Path, Path]:
    return (
        project_root / "golden/engine-v2.json",
        project_root / "golden/schema/engine-goldens-v2.schema.json",
        project_root / "golden/corpus/engine-v2-input.json",
        project_root / "golden/corpus/tokenizer-v1.json",
        project_root / "tools/engine-sync/engine.lock",
    )


def validate_committed_fixture(project_root: Path) -> dict[str, Any]:
    """Validate schema, canonical bytes, complete coverage, and all provenance."""

    fixture_path, schema_path, input_path, corpus_path, lock_path = _paths(project_root)
    _exact_file(fixture_path, FIXTURE_SHA256, "golden v2 fixture")
    _exact_file(schema_path, SCHEMA_SHA256, "golden v2 schema")
    _exact_file(input_path, INPUT_SHA256, "golden v2 contract input")
    _exact_file(corpus_path, TOKENIZER_CORPUS_SHA256, "tokenizer corpus")
    try:
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        raise GoldenV2Error(f"cannot read golden v2 JSON: {exc}") from exc
    expected_bytes = canonical_json_bytes(fixture) + b"\n"
    if fixture_path.read_bytes() != expected_bytes:
        raise GoldenV2Error("golden v2 fixture is not canonical compact JSON")
    try:
        jsonschema.Draft202012Validator.check_schema(schema)
        jsonschema.Draft202012Validator(schema).validate(fixture)
    except jsonschema.SchemaError as exc:
        raise GoldenV2Error(f"golden v2 schema is invalid: {exc.message}") from exc
    except jsonschema.ValidationError as exc:
        location = ".".join(str(part) for part in exc.absolute_path) or "root"
        raise GoldenV2Error(f"golden v2 fixture violates schema at {location}: {exc.message}") from exc

    if fixture.get("schema_version") != SCHEMA_VERSION:
        raise GoldenV2Error("golden fixture is not schema v2")
    cases = fixture.get("cases")
    statuses = fixture.get("section_status")
    if not isinstance(cases, dict) or set(cases) != set(CASE_SECTIONS):
        raise GoldenV2Error("golden v2 case section names changed")
    if not isinstance(statuses, dict) or set(statuses) != set(CASE_SECTIONS):
        raise GoldenV2Error("golden v2 section status set changed")
    for section in CASE_SECTIONS:
        if statuses[section] != {"state": "implemented"}:
            raise GoldenV2Error(f"golden v2 section is not implemented: {section}")
        if not isinstance(cases[section], list) or not cases[section]:
            raise GoldenV2Error(f"golden v2 section has no replay cases: {section}")

    provenance = fixture["provenance"]
    if set(provenance) != {"engine", "tool", "runtime", "data"}:
        raise GoldenV2Error("golden v2 provenance domains changed")
    expected_revision = load_lock(lock_path)
    engine = provenance["engine"]
    if engine != {"revision": expected_revision, "tree_sha256": ENGINE_TREE_SHA256}:
        raise GoldenV2Error("golden v2 engine provenance differs from engine.lock")
    tool = provenance["tool"]
    if tool.get("name") != "anki-miner-engine-golden-dumper" or tool.get("version") != "2":
        raise GoldenV2Error("golden v2 exporter identity changed")
    if not SHA256_RE.fullmatch(str(tool.get("sha256", ""))):
        raise GoldenV2Error("golden v2 exporter aggregate hash is invalid")
    files = tool.get("files_sha256")
    expected_tool_files = {
        "dump_engine_goldens.py",
        "engine_golden_contract_v2.py",
        "prepare_golden_unidic.py",
    }
    if not isinstance(files, dict) or set(files) != expected_tool_files:
        raise GoldenV2Error("golden v2 exporter file set changed")
    if any(not isinstance(value, str) or not SHA256_RE.fullmatch(value) for value in files.values()):
        raise GoldenV2Error("golden v2 exporter file hash is invalid")

    runtime = provenance["runtime"]
    data = provenance["data"]
    _aggregate_hash(runtime, "runtime")
    _aggregate_hash(data, "data")
    if data.get("tokenizer_corpus_sha256") != TOKENIZER_CORPUS_SHA256:
        raise GoldenV2Error("golden v2 tokenizer corpus provenance changed")
    if data.get("contract_input_sha256") != INPUT_SHA256:
        raise GoldenV2Error("golden v2 contract input provenance changed")
    if data.get("schema_sha256") != SCHEMA_SHA256:
        raise GoldenV2Error("golden v2 schema provenance changed")
    if data.get("assets_sha256") != {}:
        raise GoldenV2Error("golden v2 contains undeclared derivation assets")
    unidic = data.get("unidic")
    if not isinstance(unidic, dict) or unidic.get("tree") != {
        "file_count": UNIDIC_FILE_COUNT,
        "sha256": UNIDIC_TREE_SHA256,
        "size_bytes": UNIDIC_SIZE_BYTES,
    }:
        raise GoldenV2Error("golden v2 UniDic tree provenance changed")
    return fixture


def _tree_identity(root: Path) -> tuple[str, int, int]:
    try:
        root_stat = root.lstat()
    except OSError as exc:
        raise GoldenV2Error(f"cannot inspect UniDic: {exc}") from exc
    if stat.S_ISLNK(root_stat.st_mode) or not stat.S_ISDIR(root_stat.st_mode):
        raise GoldenV2Error("UniDic must be a real directory")
    digest = hashlib.sha256()
    file_count = 0
    size_bytes = 0
    for current, directories, filenames in os.walk(root, followlinks=False):
        current_path = Path(current)
        directories.sort()
        filenames.sort()
        for name in directories:
            entry = current_path / name
            value = entry.lstat()
            if stat.S_ISLNK(value.st_mode) or not stat.S_ISDIR(value.st_mode):
                raise GoldenV2Error(f"invalid UniDic directory entry: {entry}")
        for name in filenames:
            path = current_path / name
            value = path.lstat()
            if stat.S_ISLNK(value.st_mode) or not stat.S_ISREG(value.st_mode):
                raise GoldenV2Error(f"invalid UniDic file entry: {path}")
            relative = path.relative_to(root).as_posix().encode("utf-8")
            content = path.read_bytes()
            digest.update(len(relative).to_bytes(8, "big"))
            digest.update(relative)
            digest.update(len(content).to_bytes(8, "big"))
            digest.update(content)
            file_count += 1
            size_bytes += len(content)
    return digest.hexdigest(), file_count, size_bytes


def verify_unidic(dicdir: Path) -> None:
    actual = _tree_identity(dicdir)
    expected = (UNIDIC_TREE_SHA256, UNIDIC_FILE_COUNT, UNIDIC_SIZE_BYTES)
    if actual != expected:
        raise GoldenV2Error(f"UniDic identity mismatch: expected {expected}, got {actual}")


def _git(engine_root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", os.fspath(engine_root), *arguments],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise GoldenV2Error(f"git {' '.join(arguments)} failed: {detail}")
    return result.stdout.strip()


def verify_engine_root(engine_root: Path, expected_revision: str) -> None:
    if _git(engine_root, "rev-parse", "HEAD") != expected_revision:
        raise GoldenV2Error("desktop engine checkout does not match engine.lock")
    if _git(engine_root, "status", "--porcelain=v2", "--untracked-files=all"):
        raise GoldenV2Error("desktop engine checkout must be clean for golden derivation")


def verify_exporter_sources(exporter: Path, fixture: dict[str, Any]) -> None:
    files = fixture["provenance"]["tool"]["files_sha256"]
    for name, expected in files.items():
        path = exporter.parent / name
        _exact_file(path, expected, f"desktop exporter source {name}")


def derive_and_compare(
    *,
    project_root: Path,
    python: Path,
    exporter: Path,
    engine_root: Path,
    dicdir: Path,
    timeout_seconds: int = 900,
) -> None:
    """Execute the desktop exporter and byte-compare it with the committed fixture."""

    fixture = validate_committed_fixture(project_root)
    expected_revision = load_lock(project_root / "tools/engine-sync/engine.lock")
    verify_engine_root(engine_root, expected_revision)
    verify_unidic(dicdir)
    if timeout_seconds <= 0:
        raise GoldenV2Error("golden derivation timeout must be positive")
    if not python.is_file() or not os.access(python, os.X_OK):
        raise GoldenV2Error(f"desktop golden Python is not executable: {python}")
    fixture_path, _schema_path, input_path, corpus_path, _lock_path = _paths(project_root)
    with tempfile.TemporaryDirectory(prefix="anki-miner-android-golden-v2-") as raw_temp:
        temp = Path(raw_temp)
        output = temp / "derived.json"
        materialized_exporter = materialize_golden_exporter(
            exporter, temp / "exporter/scripts"
        )
        verify_exporter_sources(materialized_exporter, fixture)
        environment = {
            "HOME": os.fspath(temp / "home"),
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "PATH": os.environ.get("PATH", ""),
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONHASHSEED": "0",
            "PYTHONIOENCODING": "utf-8",
            "PYTHONNOUSERSITE": "1",
            "PYTHONUTF8": "1",
            "SOURCE_DATE_EPOCH": "315532800",
            "TZ": "UTC",
        }
        (temp / "home").mkdir()
        bootstrap = Path(__file__).with_name("_golden_v2_bootstrap.py")
        command = [
            os.fspath(python),
            "-s",
            "-P",
            "-B",
            os.fspath(bootstrap),
            os.fspath(materialized_exporter),
            "--schema-version",
            "2",
            "--engine-root",
            os.fspath(engine_root),
            "--corpus",
            os.fspath(corpus_path),
            "--v2-input",
            os.fspath(input_path),
            "--dicdir",
            os.fspath(dicdir),
            "--compact",
            "--output",
            os.fspath(output),
        ]
        try:
            result = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                env=environment,
                timeout=timeout_seconds,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise GoldenV2Error(f"desktop golden v2 derivation failed: {exc}") from exc
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip()
            raise GoldenV2Error(f"desktop golden v2 exporter failed: {detail}")
        try:
            derived = output.read_bytes()
        except OSError as exc:
            raise GoldenV2Error(f"desktop exporter did not produce its fixture: {exc}") from exc
        committed = fixture_path.read_bytes()
        if derived != committed:
            actual_sha = hashlib.sha256(derived).hexdigest()
            raise GoldenV2Error(
                f"desktop golden v2 drift: expected {FIXTURE_SHA256}, got {actual_sha}"
            )
