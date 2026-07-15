"""Isolated desktop-golden execution and provenance validation."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from collections.abc import Mapping
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
RUNTIME_DISTRIBUTIONS = (
    "fugashi",
    "unidic-lite",
    "pysubs2",
    "requests",
    "Pillow",
    "lxml",
    "charset-normalizer",
)
RESERVED_UNIDIC_ASSET = "unidic_dicdir"
CASE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
CORPUS_EXPECTATION_FIELDS = {
    "surface",
    "lemma",
    "orthBase",
    "mining_base",
    "is_unknown",
}
ACTIVE_WORD_FIELDS = {
    "surface",
    "lemma",
    "orth_base",
    "mined_form",
    "reading",
    "pos",
    "surface_start",
    "surface_end",
    "highlight_end",
    "sentence",
    "expression_furigana",
    "expression_reading",
    "sentence_furigana",
    "sentence_reading",
}
INACTIVE_CASE_SECTIONS = (
    "filtering",
    "deinflection",
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


def _git_bytes(root: Path, *args: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", "-C", os.fspath(root), *args],
            check=True,
            capture_output=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        detail = (
            exc.stderr.decode("utf-8", "replace").strip()
            if isinstance(exc, subprocess.CalledProcessError) and exc.stderr
            else str(exc)
        )
        raise GoldenContractError(f"git {' '.join(args)} failed: {detail}") from exc
    return result.stdout


def _pinned_package_files(
    engine_root: Path, expected_revision: str
) -> dict[str, bytes]:
    raw = _git_bytes(
        engine_root,
        "ls-tree",
        "-r",
        "-z",
        expected_revision,
        "--",
        "anki_miner",
    )
    files: dict[str, bytes] = {}
    for record in raw.split(b"\0"):
        if not record:
            continue
        try:
            metadata, raw_path = record.split(b"\t", 1)
            mode, object_type, object_id = metadata.split(b" ")
            full_path = raw_path.decode("utf-8")
        except (UnicodeDecodeError, ValueError) as exc:
            raise GoldenContractError("pinned anki_miner tree is malformed") from exc
        if object_type != b"blob":
            raise GoldenContractError(
                f"pinned anki_miner path is not a file: {full_path}"
            )
        if mode == b"120000":
            raise GoldenContractError(
                f"pinned anki_miner tree contains a symlink: {full_path}"
            )
        relative = full_path.removeprefix("anki_miner/")
        if not relative or relative == full_path:
            raise GoldenContractError(
                f"pinned package path is outside anki_miner: {full_path}"
            )
        files[relative] = _git_bytes(
            engine_root, "cat-file", "blob", object_id.decode("ascii")
        )
    if "__init__.py" not in files:
        raise GoldenContractError("pinned commit has no anki_miner package")
    return files


def _working_package_entries(
    package: Path,
) -> tuple[dict[str, bytes], set[str]]:
    if package.is_symlink() or not package.is_dir():
        raise GoldenContractError(
            f"--engine-root has no real anki_miner directory: {package}"
        )
    files: dict[str, bytes] = {}
    directories: set[str] = set()

    def visit(directory: Path, relative_directory: str) -> None:
        try:
            entries = sorted(os.scandir(directory), key=lambda entry: entry.name)
        except OSError as exc:
            raise GoldenContractError(
                f"cannot inspect engine path {directory}: {exc}"
            ) from exc
        for entry in entries:
            relative = (
                f"{relative_directory}/{entry.name}"
                if relative_directory
                else entry.name
            )
            try:
                mode = entry.stat(follow_symlinks=False).st_mode
            except OSError as exc:
                raise GoldenContractError(
                    f"cannot inspect engine path {entry.path}: {exc}"
                ) from exc
            if stat.S_ISLNK(mode):
                raise GoldenContractError(
                    f"engine package may not contain symlinks: anki_miner/{relative}"
                )
            if stat.S_ISDIR(mode):
                directories.add(relative)
                visit(Path(entry.path), relative)
                continue
            if not stat.S_ISREG(mode):
                raise GoldenContractError(
                    f"engine package path has unsupported type: anki_miner/{relative}"
                )
            try:
                files[relative] = Path(entry.path).read_bytes()
            except OSError as exc:
                raise GoldenContractError(
                    f"cannot read engine path {entry.path}: {exc}"
                ) from exc

    visit(package, "")
    return files, directories


def _canonical_tree_hash(files: Mapping[str, bytes]) -> str:
    digest = hashlib.sha256()
    for relative, content in sorted(files.items()):
        encoded = relative.encode("utf-8")
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def verify_engine_root(engine_root: Path, expected_revision: str) -> str:
    package = engine_root / "anki_miner"
    revision = _git(engine_root, "rev-parse", "HEAD")
    if revision != expected_revision:
        raise GoldenContractError(
            f"engine checkout is {revision}, expected pinned {expected_revision}"
        )
    expected_files = _pinned_package_files(engine_root, expected_revision)
    actual_files, actual_directories = _working_package_entries(package)
    expected_directories = {
        parent.as_posix()
        for relative in expected_files
        for parent in Path(relative).parents
        if parent != Path(".")
    }
    missing_files = set(expected_files) - set(actual_files)
    unexpected_files = set(actual_files) - set(expected_files)
    missing_directories = expected_directories - actual_directories
    unexpected_directories = actual_directories - expected_directories
    if any(
        (missing_files, unexpected_files, missing_directories, unexpected_directories)
    ):
        changes = []
        for label, paths in (
            ("missing file", missing_files),
            ("unexpected file", unexpected_files),
            ("missing directory", missing_directories),
            ("unexpected directory", unexpected_directories),
        ):
            changes.extend(f"{label} anki_miner/{path}" for path in sorted(paths))
        raise GoldenContractError(
            "engine checkout path/type set differs from pinned Git tree: "
            + "; ".join(changes)
        )
    modified = [
        relative
        for relative, expected in expected_files.items()
        if actual_files[relative] != expected
    ]
    if modified:
        raise GoldenContractError(
            "engine checkout content differs from pinned Git tree: "
            + ", ".join(f"anki_miner/{path}" for path in sorted(modified))
        )
    return _canonical_tree_hash(actual_files)


def _expect_dict(value: Any, label: str, keys: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise GoldenContractError(f"{label} must contain exactly {sorted(keys)}")
    return value


def _expect_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        raise GoldenContractError(f"{label} must be a lowercase SHA-256")
    return value


def _expect_non_empty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise GoldenContractError(f"{label} must be a non-empty string")
    return value


def _load_corpus(path: Path) -> list[dict[str, Any]]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GoldenContractError(f"invalid corpus {path}: {exc}") from exc
    root = _expect_dict(payload, "corpus", {"schema_version", "cases"})
    if root["schema_version"] != SCHEMA_VERSION:
        raise GoldenContractError(f"corpus.schema_version must be {SCHEMA_VERSION}")
    raw_cases = root["cases"]
    if not isinstance(raw_cases, list) or not raw_cases:
        raise GoldenContractError("corpus.cases must be a non-empty array")

    cases: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw_case in enumerate(raw_cases):
        label = f"corpus.cases[{index}]"
        if not isinstance(raw_case, dict):
            raise GoldenContractError(f"{label} must be an object")
        required = {"id", "text", "coverage"}
        allowed = required | {"expect", "dictionary_terms"}
        if not required <= set(raw_case) or not set(raw_case) <= allowed:
            raise GoldenContractError(
                f"{label} must contain {sorted(required)} and only optional "
                "expect/dictionary_terms"
            )
        case_id = _expect_non_empty_string(raw_case["id"], f"{label}.id")
        if CASE_ID_RE.fullmatch(case_id) is None or case_id in seen:
            raise GoldenContractError(
                f"{label}.id must be a unique lowercase case identifier"
            )
        seen.add(case_id)
        _expect_non_empty_string(raw_case["text"], f"{label}.text")

        coverage = raw_case["coverage"]
        if (
            not isinstance(coverage, list)
            or not coverage
            or any(not isinstance(value, str) or not value for value in coverage)
            or len(set(coverage)) != len(coverage)
        ):
            raise GoldenContractError(
                f"{label}.coverage must contain unique non-empty strings"
            )

        expectation = raw_case.get("expect")
        if expectation is not None:
            if (
                not isinstance(expectation, dict)
                or "surface" not in expectation
                or not set(expectation) <= CORPUS_EXPECTATION_FIELDS
            ):
                raise GoldenContractError(
                    f"{label}.expect requires surface and only frozen expectation fields"
                )
            _expect_non_empty_string(expectation["surface"], f"{label}.expect.surface")
            for field in ("lemma", "orthBase", "mining_base"):
                value = expectation.get(field)
                if (
                    field in expectation
                    and value is not None
                    and not isinstance(value, str)
                ):
                    raise GoldenContractError(
                        f"{label}.expect.{field} must be a string or null"
                    )
            if "is_unknown" in expectation and not isinstance(
                expectation["is_unknown"], bool
            ):
                raise GoldenContractError(
                    f"{label}.expect.is_unknown must be a boolean"
                )

        terms = raw_case.get("dictionary_terms")
        if terms is not None and (
            not isinstance(terms, list)
            or not terms
            or any(not isinstance(value, str) or not value for value in terms)
            or len(set(terms)) != len(terms)
        ):
            raise GoldenContractError(
                f"{label}.dictionary_terms must contain unique non-empty strings"
            )
        cases.append(raw_case)
    return cases


def _utf16_offset(text: str, codepoint_offset: int) -> int:
    return len(text[:codepoint_offset].encode("utf-16-le")) // 2


def _validate_tokenization(cases: Any, corpus: Sequence[Mapping[str, Any]]) -> None:
    if not isinstance(cases, list) or not cases:
        raise GoldenContractError("cases.tokenization must be a non-empty array")
    if len(cases) != len(corpus):
        raise GoldenContractError(
            "cases.tokenization must contain exactly one record per corpus case"
        )
    seen: set[str] = set()
    for case_index, (raw_case, corpus_case) in enumerate(
        zip(cases, corpus, strict=True)
    ):
        label = f"cases.tokenization[{case_index}]"
        case = _expect_dict(raw_case, label, {"id", "text", "tokens"})
        case_id, text, tokens = case["id"], case["text"], case["tokens"]
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise GoldenContractError(f"{label}.id must be a unique non-empty string")
        seen.add(case_id)
        if case_id != corpus_case["id"] or text != corpus_case["text"]:
            raise GoldenContractError(
                f"{label} id/text does not match the corpus record at this position"
            )
        if not isinstance(text, str) or not isinstance(tokens, list) or not tokens:
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

        expectation = corpus_case.get("expect")
        if isinstance(expectation, Mapping):
            matching = [
                token for token in tokens if token["surface"] == expectation["surface"]
            ]
            if len(matching) != 1:
                raise GoldenContractError(
                    f"{label} must contain exactly one expected surface token"
                )
            token = matching[0]
            comparisons = {
                "lemma": token["features"]["lemma"],
                "orthBase": token["features"]["orthBase"],
                "is_unknown": token["is_unknown"],
            }
            for field, actual in comparisons.items():
                if field in expectation and expectation[field] != actual:
                    raise GoldenContractError(
                        f"{label} expected {field} does not match the token"
                    )


def _validate_word(raw_word: Any, label: str) -> dict[str, Any]:
    word = _expect_dict(raw_word, label, ACTIVE_WORD_FIELDS)
    string_fields = ACTIVE_WORD_FIELDS - {
        "surface_start",
        "surface_end",
        "highlight_end",
    }
    if any(not isinstance(word[field], str) for field in string_fields):
        raise GoldenContractError(f"{label} word text fields must be strings")
    word_sentence = word["sentence"]
    if not word_sentence:
        raise GoldenContractError(f"{label}.sentence must be non-empty")
    positions = (
        word["surface_start"],
        word["surface_end"],
        word["highlight_end"],
    )
    if (
        any(
            not isinstance(value, int) or isinstance(value, bool) for value in positions
        )
        or not 0 <= positions[0] <= positions[1] <= positions[2] <= len(word_sentence)
        or word_sentence[positions[0] : positions[1]] != word["surface"]
    ):
        raise GoldenContractError(f"{label} has invalid surface/highlight offsets")
    return word


def _validate_active_case(
    raw_case: Any,
    label: str,
    *,
    expected_id: str,
    expected_text: str,
    dictionary_terms: Sequence[str] | None = None,
) -> list[dict[str, Any]]:
    case = _expect_dict(raw_case, label, {"id", "input", "output"})
    if case["id"] != expected_id:
        raise GoldenContractError(f"{label}.id does not match the corpus")
    input_keys = {"text"} if dictionary_terms is None else {"text", "dictionary_terms"}
    case_input = _expect_dict(case["input"], f"{label}.input", input_keys)
    if case_input["text"] != expected_text:
        raise GoldenContractError(f"{label}.input.text does not match the corpus")
    if dictionary_terms is not None and case_input["dictionary_terms"] != sorted(
        dictionary_terms
    ):
        raise GoldenContractError(
            f"{label}.input.dictionary_terms does not match the corpus"
        )
    output = _expect_dict(case["output"], f"{label}.output", {"words"})
    raw_words = output["words"]
    if not isinstance(raw_words, list) or not raw_words:
        raise GoldenContractError(f"{label}.output.words must be a non-empty array")
    return [
        _validate_word(word, f"{label}.output.words[{index}]")
        for index, word in enumerate(raw_words)
    ]


def _validate_morphology(cases: Any, corpus: Sequence[Mapping[str, Any]]) -> None:
    if not isinstance(cases, list) or not cases:
        raise GoldenContractError("cases.morphology must be a non-empty array")
    if len(cases) != len(corpus):
        raise GoldenContractError(
            "cases.morphology must contain exactly one record per corpus case"
        )
    for index, (raw_case, corpus_case) in enumerate(zip(cases, corpus, strict=True)):
        label = f"cases.morphology[{index}]"
        words = _validate_active_case(
            raw_case,
            label,
            expected_id=corpus_case["id"],
            expected_text=corpus_case["text"],
        )
        expectation = corpus_case.get("expect")
        if not isinstance(expectation, Mapping):
            continue
        matching = [word for word in words if word["surface"] == expectation["surface"]]
        if expectation.get("is_unknown") is True and not matching:
            continue
        if len(matching) != 1:
            raise GoldenContractError(
                f"{label} must contain exactly one expected surface word"
            )
        word = matching[0]
        comparisons = {"lemma": word["lemma"], "mining_base": word["mined_form"]}
        for field, actual in comparisons.items():
            if field in expectation and expectation[field] != actual:
                raise GoldenContractError(
                    f"{label} expected {field} does not match the mined word"
                )


def _validate_compounds(cases: Any, corpus: Sequence[Mapping[str, Any]]) -> None:
    compound_corpus = [case for case in corpus if "dictionary_terms" in case]
    if not isinstance(cases, list) or not cases:
        raise GoldenContractError("cases.compounds must be a non-empty array")
    if len(cases) != len(compound_corpus):
        raise GoldenContractError(
            "cases.compounds must contain exactly the dictionary-term corpus cases"
        )
    for index, (raw_case, corpus_case) in enumerate(
        zip(cases, compound_corpus, strict=True)
    ):
        label = f"cases.compounds[{index}]"
        terms = corpus_case["dictionary_terms"]
        words = _validate_active_case(
            raw_case,
            label,
            expected_id=corpus_case["id"],
            expected_text=corpus_case["text"],
            dictionary_terms=terms,
        )
        for term in terms:
            if not any(term in (word["lemma"], word["mined_form"]) for word in words):
                raise GoldenContractError(
                    f"{label} does not contain merged dictionary term {term!r}"
                )


def validate_fixture(
    payload: Any,
    *,
    engine_root: Path,
    expected_revision: str,
    corpus_path: Path,
    exporter_path: Path,
    expected_runtime: Mapping[str, Any],
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
    if runtime_without_hash != expected_runtime:
        raise GoldenContractError(
            "fixture runtime identity does not match the selected interpreter"
        )

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

    corpus = _load_corpus(corpus_path)
    cases = _expect_dict(root["cases"], "cases", set(CASE_SECTIONS))
    _validate_tokenization(cases["tokenization"], corpus)
    _validate_morphology(cases["morphology"], corpus)
    _validate_compounds(cases["compounds"], corpus)
    for section in INACTIVE_CASE_SECTIONS:
        if cases[section] != []:
            raise GoldenContractError(
                f"cases.{section} is staged but inactive in contract v1"
            )


def parse_assets(values: Sequence[str]) -> tuple[GoldenAsset, ...]:
    assets: list[GoldenAsset] = []
    seen: set[str] = set()
    for value in values:
        name, separator, raw_path = value.partition("=")
        if not separator or not name or not raw_path:
            raise GoldenContractError("--asset must have the form NAME=PATH")
        if name == RESERVED_UNIDIC_ASSET:
            raise GoldenContractError(
                f"asset name {RESERVED_UNIDIC_ASSET!r} is reserved for --dicdir"
            )
        if name in seen:
            raise GoldenContractError(f"duplicate asset name: {name}")
        path = Path(raw_path).expanduser().resolve()
        if not path.exists():
            raise GoldenContractError(f"asset does not exist: {path}")
        seen.add(name)
        assets.append(GoldenAsset(name, path))
    return tuple(sorted(assets, key=lambda asset: asset.name))


def _isolated_environment(*, python: Path, home: Path) -> dict[str, str]:
    return {
        "ANKI_MINER_HOME": os.fspath(home),
        "HOME": os.fspath(home),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PATH": os.fspath(python.parent) + os.pathsep + os.defpath,
        "PYTHONHASHSEED": "0",
        "TZ": "UTC",
    }


def _run_checked_process(
    command: Sequence[str],
    *,
    cwd: Path,
    environment: Mapping[str, str],
    timeout_seconds: int,
    label: str,
) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GoldenContractError(f"{label} could not run: {exc}") from exc
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise GoldenContractError(f"{label} exited {result.returncode}: {detail}")
    return result


def _probe_runtime(
    *,
    python: Path,
    dicdir: Path | None,
    runtime_distributions: Sequence[str],
    cwd: Path,
    environment: Mapping[str, str],
    timeout_seconds: int,
) -> tuple[dict[str, Any], Path]:
    probe_path = Path(__file__).with_name("_runtime_probe.py")
    command = [
        os.fspath(python),
        "-s",
        "-P",
        "-B",
        os.fspath(probe_path),
    ]
    for distribution in runtime_distributions:
        command.extend(("--distribution", distribution))
    if dicdir is not None:
        command.extend(("--dicdir", os.fspath(dicdir)))
    result = _run_checked_process(
        command,
        cwd=cwd,
        environment=environment,
        timeout_seconds=timeout_seconds,
        label="golden runtime probe",
    )
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise GoldenContractError(
            f"golden runtime probe did not produce valid JSON: {exc}"
        ) from exc
    root = _expect_dict(
        payload, "runtime probe", {"runtime", "unidic_dicdir", "hash_probe"}
    )
    runtime = _expect_dict(
        root["runtime"],
        "runtime probe identity",
        {"python_implementation", "python_version", "platform", "dependencies"},
    )
    if not all(
        isinstance(runtime[key], str) and runtime[key]
        for key in ("python_implementation", "python_version", "platform")
    ):
        raise GoldenContractError("runtime probe identity fields have invalid types")
    dependencies = runtime["dependencies"]
    if (
        not isinstance(dependencies, dict)
        or set(dependencies) != set(runtime_distributions)
        or any(
            not isinstance(name, str) or not isinstance(version, str) or not version
            for name, version in dependencies.items()
        )
    ):
        raise GoldenContractError(
            "runtime probe dependencies do not match the required distributions"
        )
    if not isinstance(root["hash_probe"], int) or isinstance(root["hash_probe"], bool):
        raise GoldenContractError("runtime probe hash result has an invalid type")
    probed_dicdir = Path(
        _expect_non_empty_string(root["unidic_dicdir"], "runtime probe dicdir")
    )
    if not probed_dicdir.is_absolute() or not (probed_dicdir / "sys.dic").is_file():
        raise GoldenContractError("runtime probe returned an invalid UniDic directory")
    if dicdir is not None and probed_dicdir != dicdir.resolve():
        raise GoldenContractError(
            "runtime probe did not use the requested UniDic directory"
        )
    return runtime, probed_dicdir


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
    runtime_distributions: Sequence[str] = RUNTIME_DISTRIBUTIONS,
) -> bool:
    python = python.expanduser().absolute()
    engine_root = engine_root.expanduser().resolve()
    exporter_path = exporter_path.expanduser().resolve()
    corpus_path = corpus_path.expanduser().resolve()
    output_path = output_path.expanduser().resolve()
    verify_engine_root(engine_root, expected_revision)
    if not exporter_path.is_file() or not corpus_path.is_file():
        raise GoldenContractError("--exporter and --corpus must be files")
    if any(asset.name == RESERVED_UNIDIC_ASSET for asset in assets):
        raise GoldenContractError(
            f"asset name {RESERVED_UNIDIC_ASSET!r} is reserved for --dicdir"
        )
    if len({asset.name for asset in assets}) != len(assets):
        raise GoldenContractError("duplicate asset names")
    for asset in assets:
        if not asset.path.exists():
            raise GoldenContractError(f"asset does not exist: {asset.path}")
    if dicdir is not None:
        dicdir = dicdir.expanduser().resolve()
        if not dicdir.is_dir():
            raise GoldenContractError("--dicdir must be a directory")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    bootstrap_path = Path(__file__).with_name("_golden_bootstrap.py")
    with tempfile.TemporaryDirectory(prefix="anki-miner-golden-run-") as temp_name:
        temp_root = Path(temp_name)
        home = temp_root / "home"
        home.mkdir()
        temporary_output = temp_root / "fixture.json"
        environment = _isolated_environment(python=python, home=home)
        expected_runtime, effective_dicdir = _probe_runtime(
            python=python,
            dicdir=dicdir,
            runtime_distributions=runtime_distributions,
            cwd=temp_root,
            environment=environment,
            timeout_seconds=timeout_seconds,
        )
        effective_assets = tuple(
            sorted(
                (*assets, GoldenAsset(RESERVED_UNIDIC_ASSET, effective_dicdir)),
                key=lambda asset: asset.name,
            )
        )
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
            os.fspath(temporary_output),
            "--compact",
            "--dicdir",
            os.fspath(effective_dicdir),
        ]
        for asset in assets:
            command.extend(("--asset", f"{asset.name}={asset.path.resolve()}"))
        _run_checked_process(
            command,
            cwd=temp_root,
            environment=environment,
            timeout_seconds=timeout_seconds,
            label="golden exporter",
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
            expected_runtime=expected_runtime,
            assets=effective_assets,
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
