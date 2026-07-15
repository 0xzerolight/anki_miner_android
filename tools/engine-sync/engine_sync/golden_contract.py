"""Isolated desktop-golden execution and provenance validation."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

from .core import EngineSyncError, load_lock


SCHEMA_VERSION = 1
TOOL_NAME = "anki-miner-engine-golden-dumper"
TOOL_VERSION = "1"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
UNIDIC_FEATURE_FIELDS = (
    "pos1",
    "pos2",
    "pos3",
    "pos4",
    "cType",
    "cForm",
    "lForm",
    "lemma",
    "orth",
    "pron",
    "orthBase",
    "pronBase",
    "goshu",
    "iType",
    "iForm",
    "fType",
    "fForm",
    "kana",
    "kanaBase",
    "form",
    "formBase",
    "iConType",
    "fConType",
    "aType",
    "aConType",
    "aModeType",
)
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


class GoldenContractError(EngineSyncError):
    """A golden fixture or its derivation environment violates the contract."""


@dataclass(frozen=True)
class GoldenAsset:
    name: str
    path: Path


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise GoldenContractError(f"cannot hash {path}: {exc}") from exc
    return digest.hexdigest()


def _tree_files(root: Path) -> list[Path]:
    if not root.is_dir():
        raise GoldenContractError(f"tree does not exist: {root}")
    files: list[Path] = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise GoldenContractError(f"golden inputs may not contain symlinks: {path}")
        if (
            path.is_file()
            and "__pycache__" not in path.parts
            and not path.name.endswith((".pyc", ".pyo"))
        ):
            files.append(path)
    return files


def sha256_tree(root: Path) -> str:
    """Hash a tree using the desktop exporter's path/length/content framing."""

    digest = hashlib.sha256()
    for path in _tree_files(root):
        relative = path.relative_to(root).as_posix().encode("utf-8")
        content = path.read_bytes()
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def sha256_path(path: Path) -> str:
    if path.is_file():
        return sha256_file(path)
    return sha256_tree(path)


def _git(root: Path, *args: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", os.fspath(root), *args],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        detail = (
            exc.stderr.strip()
            if isinstance(exc, subprocess.CalledProcessError) and exc.stderr
            else str(exc)
        )
        raise GoldenContractError(f"git {' '.join(args)} failed: {detail}") from exc
    return result.stdout.strip()


def verify_engine_root(engine_root: Path, expected_revision: str) -> str:
    package = engine_root / "anki_miner"
    if not (package / "__init__.py").is_file():
        raise GoldenContractError(
            f"--engine-root does not contain anki_miner: {engine_root}"
        )
    revision = _git(engine_root, "rev-parse", "HEAD")
    if revision != expected_revision:
        raise GoldenContractError(
            f"engine checkout is {revision}, expected pinned {expected_revision}"
        )
    dirty = _git(engine_root, "status", "--porcelain", "--", "anki_miner")
    if dirty:
        raise GoldenContractError("engine checkout has changes under anki_miner")
    return sha256_tree(package)


def _expect_dict(value: Any, label: str, keys: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise GoldenContractError(f"{label} must contain exactly {sorted(keys)}")
    return value


def _expect_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        raise GoldenContractError(f"{label} must be a lowercase SHA-256")
    return value


def _utf16_offset(text: str, codepoint_offset: int) -> int:
    return len(text[:codepoint_offset].encode("utf-16-le")) // 2


def _validate_tokenization(cases: Any) -> None:
    if not isinstance(cases, list):
        raise GoldenContractError("cases.tokenization must be an array")
    seen: set[str] = set()
    for case_index, raw_case in enumerate(cases):
        label = f"cases.tokenization[{case_index}]"
        case = _expect_dict(raw_case, label, {"id", "text", "tokens"})
        case_id, text, tokens = case["id"], case["text"], case["tokens"]
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise GoldenContractError(f"{label}.id must be a unique non-empty string")
        seen.add(case_id)
        if not isinstance(text, str) or not isinstance(tokens, list):
            raise GoldenContractError(f"{label} text/tokens have invalid types")
        previous_end = 0
        for token_index, raw_token in enumerate(tokens):
            token_label = f"{label}.tokens[{token_index}]"
            token = _expect_dict(
                raw_token,
                token_label,
                {"surface", "is_unknown", "offsets", "features"},
            )
            surface = token["surface"]
            if not isinstance(surface, str) or not isinstance(
                token["is_unknown"], bool
            ):
                raise GoldenContractError(
                    f"{token_label} surface/is_unknown have invalid types"
                )
            offsets = _expect_dict(
                token["offsets"],
                f"{token_label}.offsets",
                {"codepoint_start", "codepoint_end", "utf16_start", "utf16_end"},
            )
            if not all(
                isinstance(value, int) and value >= 0 for value in offsets.values()
            ):
                raise GoldenContractError(
                    f"{token_label}.offsets must be non-negative integers"
                )
            start, end = offsets["codepoint_start"], offsets["codepoint_end"]
            if start < previous_end or end < start or text[start:end] != surface:
                raise GoldenContractError(
                    f"{token_label} has invalid code-point offsets"
                )
            if offsets["utf16_start"] != _utf16_offset(text, start) or offsets[
                "utf16_end"
            ] != _utf16_offset(text, end):
                raise GoldenContractError(f"{token_label} has invalid UTF-16 offsets")
            previous_end = end
            features = _expect_dict(
                token["features"],
                f"{token_label}.features",
                set(UNIDIC_FEATURE_FIELDS),
            )
            if any(
                value == "*" or (value is not None and not isinstance(value, str))
                for value in features.values()
            ):
                raise GoldenContractError(
                    f"{token_label}.features must normalize '*' to null"
                )


def validate_fixture(
    payload: Any,
    *,
    engine_root: Path,
    expected_revision: str,
    corpus_path: Path,
    exporter_path: Path,
    assets: Sequence[GoldenAsset] = (),
) -> None:
    root = _expect_dict(
        payload,
        "fixture",
        {"schema_version", "provenance", "unidic_feature_fields", "cases"},
    )
    if root["schema_version"] != SCHEMA_VERSION:
        raise GoldenContractError(f"schema_version must be {SCHEMA_VERSION}")
    if root["unidic_feature_fields"] != list(UNIDIC_FEATURE_FIELDS):
        raise GoldenContractError(
            "unidic_feature_fields is not the frozen 26-field order"
        )

    provenance = _expect_dict(
        root["provenance"], "provenance", {"engine", "tool", "runtime", "data"}
    )
    engine = _expect_dict(
        provenance["engine"], "provenance.engine", {"revision", "tree_sha256"}
    )
    actual_tree_hash = verify_engine_root(engine_root, expected_revision)
    if engine["revision"] != expected_revision:
        raise GoldenContractError("fixture engine revision does not match engine.lock")
    if _expect_sha256(engine["tree_sha256"], "engine.tree_sha256") != actual_tree_hash:
        raise GoldenContractError(
            "fixture engine tree hash does not match --engine-root"
        )

    tool = _expect_dict(
        provenance["tool"], "provenance.tool", {"name", "version", "sha256"}
    )
    if tool["name"] != TOOL_NAME or tool["version"] != TOOL_VERSION:
        raise GoldenContractError(
            "fixture exporter name/version is not the v1 contract"
        )
    if _expect_sha256(tool["sha256"], "tool.sha256") != sha256_file(exporter_path):
        raise GoldenContractError("fixture tool hash does not match --exporter")

    runtime = _expect_dict(
        provenance["runtime"],
        "provenance.runtime",
        {
            "python_implementation",
            "python_version",
            "platform",
            "dependencies",
            "sha256",
        },
    )
    if not all(
        isinstance(runtime[key], str) and runtime[key]
        for key in ("python_implementation", "python_version", "platform")
    ) or not isinstance(runtime["dependencies"], dict):
        raise GoldenContractError("runtime identity fields have invalid types")
    if not all(
        isinstance(key, str) and isinstance(value, str)
        for key, value in runtime["dependencies"].items()
    ):
        raise GoldenContractError("runtime dependencies must map names to versions")
    runtime_without_hash = {
        key: value for key, value in runtime.items() if key != "sha256"
    }
    if _expect_sha256(runtime["sha256"], "runtime.sha256") != sha256_bytes(
        canonical_json_bytes(runtime_without_hash)
    ):
        raise GoldenContractError("runtime canonical hash is invalid")

    data = _expect_dict(
        provenance["data"],
        "provenance.data",
        {"corpus_sha256", "assets_sha256", "sha256"},
    )
    expected_assets = {asset.name: sha256_path(asset.path) for asset in assets}
    if _expect_sha256(data["corpus_sha256"], "data.corpus_sha256") != sha256_file(
        corpus_path
    ):
        raise GoldenContractError("fixture corpus hash does not match --corpus")
    if data["assets_sha256"] != expected_assets:
        raise GoldenContractError("fixture asset hashes do not match --asset inputs")
    data_without_hash = {key: value for key, value in data.items() if key != "sha256"}
    if _expect_sha256(data["sha256"], "data.sha256") != sha256_bytes(
        canonical_json_bytes(data_without_hash)
    ):
        raise GoldenContractError("data canonical hash is invalid")

    cases = _expect_dict(root["cases"], "cases", set(CASE_SECTIONS))
    _validate_tokenization(cases["tokenization"])
    for section in CASE_SECTIONS[1:]:
        records = cases[section]
        if not isinstance(records, list):
            raise GoldenContractError(f"cases.{section} must be an array")
        for index, record in enumerate(records):
            parsed = _expect_dict(
                record, f"cases.{section}[{index}]", {"id", "input", "output"}
            )
            if (
                not isinstance(parsed["id"], str)
                or not isinstance(parsed["input"], dict)
                or not isinstance(parsed["output"], dict)
            ):
                raise GoldenContractError(f"cases.{section}[{index}] has invalid types")


def parse_assets(values: Sequence[str]) -> tuple[GoldenAsset, ...]:
    assets: list[GoldenAsset] = []
    seen: set[str] = set()
    for value in values:
        name, separator, raw_path = value.partition("=")
        if not separator or not name or not raw_path:
            raise GoldenContractError("--asset must have the form NAME=PATH")
        if name in seen:
            raise GoldenContractError(f"duplicate asset name: {name}")
        path = Path(raw_path).expanduser().resolve()
        if not path.exists():
            raise GoldenContractError(f"asset does not exist: {path}")
        seen.add(name)
        assets.append(GoldenAsset(name, path))
    return tuple(sorted(assets, key=lambda asset: asset.name))


def run_exporter(
    *,
    python: Path,
    exporter_path: Path,
    engine_root: Path,
    expected_revision: str,
    corpus_path: Path,
    output_path: Path,
    assets: Sequence[GoldenAsset] = (),
    dicdir: Path | None = None,
    check: bool = False,
    timeout_seconds: int = 600,
) -> bool:
    python = python.expanduser().absolute()
    engine_root = engine_root.expanduser().resolve()
    exporter_path = exporter_path.expanduser().resolve()
    corpus_path = corpus_path.expanduser().resolve()
    output_path = output_path.expanduser().resolve()
    verify_engine_root(engine_root, expected_revision)
    if not exporter_path.is_file() or not corpus_path.is_file():
        raise GoldenContractError("--exporter and --corpus must be files")
    if dicdir is not None and not dicdir.is_dir():
        raise GoldenContractError("--dicdir must be a directory")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    bootstrap_path = Path(__file__).with_name("_golden_bootstrap.py")
    with tempfile.TemporaryDirectory(prefix="anki-miner-golden-run-") as temp_name:
        temp_root = Path(temp_name)
        home = temp_root / "home"
        home.mkdir()
        temporary_output = temp_root / "fixture.json"
        command = [
            os.fspath(python),
            "-I",
            os.fspath(bootstrap_path),
            os.fspath(exporter_path),
            os.fspath(engine_root),
            "--engine-root",
            os.fspath(engine_root),
            "--corpus",
            os.fspath(corpus_path),
            "--output",
            os.fspath(temporary_output),
            "--compact",
        ]
        if dicdir is not None:
            command.extend(("--dicdir", os.fspath(dicdir.resolve())))
        for asset in assets:
            command.extend(("--asset", f"{asset.name}={asset.path.resolve()}"))
        executable_dir = os.fspath(python.parent)
        environment = {
            "ANKI_MINER_HOME": os.fspath(home),
            "HOME": os.fspath(home),
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "PATH": executable_dir + os.pathsep + os.defpath,
            "PYTHONHASHSEED": "0",
            "TZ": "UTC",
        }
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
            raise GoldenContractError(f"golden exporter could not run: {exc}") from exc
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip()
            raise GoldenContractError(
                f"golden exporter exited {result.returncode}: {detail}"
            )
        try:
            payload = json.loads(temporary_output.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise GoldenContractError(
                f"exporter did not produce valid JSON: {exc}"
            ) from exc
        validate_fixture(
            payload,
            engine_root=engine_root,
            expected_revision=expected_revision,
            corpus_path=corpus_path,
            exporter_path=exporter_path,
            assets=assets,
        )
        rendered = canonical_json_bytes(payload) + b"\n"

    if check:
        try:
            actual = output_path.read_bytes()
        except OSError:
            return False
        return actual == rendered
    temporary_fd, temporary_name = tempfile.mkstemp(
        prefix=f".{output_path.name}.", dir=output_path.parent
    )
    try:
        with os.fdopen(temporary_fd, "wb") as stream:
            stream.write(rendered)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, output_path)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise
    return True


def default_python() -> Path:
    return Path(sys.executable)


def locked_revision(lock_path: Path) -> str:
    return load_lock(lock_path)
