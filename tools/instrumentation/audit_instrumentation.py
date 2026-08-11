#!/usr/bin/env python3
"""Audit source instrumentation, log boundaries, and shared fault contracts."""

from __future__ import annotations

import argparse
import ast
import csv
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

REPO_ROOT = Path(__file__).resolve().parents[2]
KOTLIN_ROOT = Path("app/src/main/kotlin")
KOTLIN_SOURCE_ROOTS = (
    KOTLIN_ROOT,
    Path("app/src/main/ankidroidApi/kotlin"),
    Path("app/src/release/kotlin"),
)
LOGCAT_SINK_PATH = Path("app/src/main/kotlin/com/ankiminer/android/diagnostics/log/LogcatSink.kt")
PYTHON_BRIDGE_ROOT = Path("app/src/main/python/android_bridge")
ALLOWLIST_PATH = Path("tools/instrumentation/bare_catch_allowlist.tsv")
CONTRACT_PATH = Path("app/src/test/resources/contracts/mining_protocol_v1.json")
FAULTS_PATH = Path("app/src/main/python/android_bridge/faults.py")
SCHEMA_ROOT = Path("app/src/main/python/android_bridge/schemas")
PINNED_BARE_CATCH_COUNT = 176
EXPECTED_FAULT_PATTERN = r"^f[0-9a-f]{8}$"
EXPECTED_FAULT_CODECS = {
    "app/src/main/kotlin/com/ankiminer/android/data/resources/ResourceBridgeCodec.kt",
    "app/src/main/kotlin/com/ankiminer/android/engine/BridgeJsonCodec.kt",
}
TRACKED_TEXT_SUFFIXES = {".kt", ".py", ".xml", ".md", ".pro", ".json", ".toml"}
KOTLIN_SILENCE_ANNOTATION = re.compile(r"^\s*// instrumentation: silent — \S.*$")
PYTHON_SILENCE_ANNOTATION = re.compile(r"^\s*# instrumentation: intentionally silent — \S.*$")
CATCH_PATTERN = re.compile(r"\bcatch\s*\(\s*(?P<name>_|[A-Za-z][A-Za-z0-9_]*)\s*:\s*[^)]*\)\s*\{")
LOG_METHODS = {"debug", "info", "warning", "error", "exception", "critical"}
DIRECT_LOG_CONSOLE_PATTERNS = (
    re.compile(r"\bandroid\s*\.\s*util\s*\.\s*Log\b"),
    re.compile(r"\bimport\s+android\s*\.\s*util\s*\.\s*\*"),
    re.compile(r"\bkotlin\s*\.\s*io\s*\.\s*println\s*\("),
    re.compile(r"(?<![A-Za-z0-9_.])println\s*\("),
    re.compile(r"\bkotlin\s*\.\s*io\s*\.\s*print\s*\("),
    re.compile(r"(?<![A-Za-z0-9_.])print\s*\("),
    re.compile(r"\bSystem\s*\.\s*(?:out|err)\b"),
    re.compile(r"\bprintStackTrace\s*\("),
    re.compile(r"::\s*printStackTrace\b"),
)


class InstrumentationError(ValueError):
    """Source instrumentation cannot be audited safely."""


@dataclass(frozen=True)
class AuditSummary:
    bare_catches: int
    kotlin_files: int
    python_files: int
    tracked_text_files: int
    fault_codecs: int


def _relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def _line_number(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def _mask_kotlin(source: str) -> str:
    """Replace strings and comments with spaces while preserving offsets and lines."""

    masked = list(source)
    index = 0
    length = len(source)
    state = "code"
    block_depth = 0
    while index < length:
        if state == "code":
            if source.startswith("//", index):
                masked[index] = masked[index + 1] = " "
                index += 2
                state = "line_comment"
            elif source.startswith("/*", index):
                masked[index] = masked[index + 1] = " "
                index += 2
                state = "block_comment"
                block_depth = 1
            elif source.startswith('"""', index):
                masked[index : index + 3] = "   "
                index += 3
                state = "triple_string"
            elif source[index] == '"':
                masked[index] = " "
                index += 1
                state = "string"
            elif source[index] == "'":
                masked[index] = " "
                index += 1
                state = "character"
            else:
                index += 1
        elif state == "line_comment":
            if source[index] == "\n":
                state = "code"
            else:
                masked[index] = " "
            index += 1
        elif state == "block_comment":
            if source.startswith("/*", index):
                masked[index] = masked[index + 1] = " "
                block_depth += 1
                index += 2
            elif source.startswith("*/", index):
                masked[index] = masked[index + 1] = " "
                block_depth -= 1
                index += 2
                if block_depth == 0:
                    state = "code"
            else:
                if source[index] != "\n":
                    masked[index] = " "
                index += 1
        elif state == "triple_string":
            if source.startswith('"""', index):
                masked[index : index + 3] = "   "
                index += 3
                state = "code"
            else:
                if source[index] != "\n":
                    masked[index] = " "
                index += 1
        else:
            if source[index] == "\\" and index + 1 < length:
                masked[index] = " "
                if source[index + 1] != "\n":
                    masked[index + 1] = " "
                index += 2
            else:
                closing = '"' if state == "string" else "'"
                if source[index] == closing:
                    state = "code"
                if source[index] != "\n":
                    masked[index] = " "
                index += 1
    return "".join(masked)


def _matching_delimiter(source: str, opening_offset: int, opening: str, closing: str) -> int:
    depth = 0
    for offset in range(opening_offset, len(source)):
        character = source[offset]
        if character == opening:
            depth += 1
        elif character == closing:
            depth -= 1
            if depth == 0:
                return offset
    raise InstrumentationError(f"unclosed {opening!r} at character {opening_offset}")


def _read_allowlist(repo_root: Path) -> dict[tuple[str, int], str]:
    path = repo_root / ALLOWLIST_PATH
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as failure:
        raise InstrumentationError(f"{ALLOWLIST_PATH}: could not read allowlist: {failure}") from failure
    if not lines:
        raise InstrumentationError(f"{ALLOWLIST_PATH}: allowlist is empty")

    reader = csv.DictReader(lines, delimiter="\t")
    if reader.fieldnames != ["path", "lines", "reason"]:
        raise InstrumentationError(f"{ALLOWLIST_PATH}: expected path, lines, reason columns")
    entries: dict[tuple[str, int], str] = {}
    for row_number, row in enumerate(reader, start=2):
        relative = (row.get("path") or "").strip()
        line_values = (row.get("lines") or "").strip()
        reason = (row.get("reason") or "").strip()
        if not relative or not line_values:
            raise InstrumentationError(f"{ALLOWLIST_PATH}:{row_number}: allowlist path and lines are required")
        if not reason:
            raise InstrumentationError(f"{ALLOWLIST_PATH}:{row_number}: allowlist reason is required")
        try:
            line_numbers = [int(value) for value in line_values.split(",")]
        except ValueError as failure:
            raise InstrumentationError(f"{ALLOWLIST_PATH}:{row_number}: allowlist lines must be integers") from failure
        if any(line <= 0 for line in line_numbers):
            raise InstrumentationError(f"{ALLOWLIST_PATH}:{row_number}: allowlist lines must be positive")
        for line in line_numbers:
            key = (relative, line)
            if key in entries:
                raise InstrumentationError(f"{ALLOWLIST_PATH}:{row_number}: duplicate allowlist site {relative}:{line}")
            entries[key] = reason
    if len(entries) > PINNED_BARE_CATCH_COUNT:
        raise InstrumentationError(
            f"{ALLOWLIST_PATH}: {len(entries)} sites exceed pinned maximum {PINNED_BARE_CATCH_COUNT}"
        )
    return entries


def _is_kotlin_silence_annotated(source: str, catch_line: int) -> bool:
    lines = source.splitlines()
    return catch_line > 1 and bool(KOTLIN_SILENCE_ANNOTATION.fullmatch(lines[catch_line - 2]))


def _chain_methods(masked: str, cursor: int) -> list[str]:
    methods: list[str] = []
    while cursor < len(masked):
        while cursor < len(masked) and masked[cursor].isspace():
            cursor += 1
        if cursor >= len(masked) or masked[cursor] != ".":
            break
        cursor += 1
        while cursor < len(masked) and masked[cursor].isspace():
            cursor += 1
        match = re.match(r"[A-Za-z][A-Za-z0-9_]*", masked[cursor:])
        if match is None:
            break
        method = match.group(0)
        methods.append(method)
        cursor += len(method)
        while cursor < len(masked) and masked[cursor].isspace():
            cursor += 1
        if cursor < len(masked) and masked[cursor] == "(":
            cursor = _matching_delimiter(masked, cursor, "(", ")") + 1
        while cursor < len(masked) and masked[cursor].isspace():
            cursor += 1
        if cursor < len(masked) and masked[cursor] == "{":
            cursor = _matching_delimiter(masked, cursor, "{", "}") + 1
    return methods


def _has_unconditional_top_level_throw(body: str) -> bool:
    pattern = re.compile(r"\bthrow\b")
    for match in pattern.finditer(body):
        prefix = body[: match.start()]
        if prefix.count("{") != prefix.count("}"):
            continue
        statement_prefix = prefix[max(prefix.rfind("\n"), prefix.rfind(";")) + 1 :]
        if statement_prefix.strip():
            continue
        if re.search(r"\b(?:return|break|continue)\b", prefix):
            continue
        return True
    return False


def _audit_applog_boundary(path: Path, repo_root: Path) -> list[str]:
    relative = _relative(path, repo_root)
    if Path(relative) == LOGCAT_SINK_PATH:
        return []
    source = path.read_text(encoding="utf-8")
    masked = _mask_kotlin(source)
    failures: list[str] = []
    reported_lines: set[int] = set()
    for pattern in DIRECT_LOG_CONSOLE_PATTERNS:
        for direct_output in pattern.finditer(masked):
            line = _line_number(source, direct_output.start())
            if line not in reported_lines:
                failures.append(f"{relative}:{line}: direct Log/console usage outside AppLog")
                reported_lines.add(line)
    return failures


def _audit_kotlin_file(
    path: Path,
    repo_root: Path,
    allowlist: dict[tuple[str, int], str],
) -> tuple[list[str], set[tuple[str, int]], int]:
    source = path.read_text(encoding="utf-8")
    masked = _mask_kotlin(source)
    relative = _relative(path, repo_root)
    failures: list[str] = []
    found_allowlist_sites: set[tuple[str, int]] = set()
    bare_count = 0

    for match in CATCH_PATTERN.finditer(masked):
        name = match.group("name")
        line = _line_number(source, match.start())
        opening = match.end() - 1
        try:
            closing = _matching_delimiter(masked, opening, "{", "}")
        except InstrumentationError as failure:
            failures.append(f"{relative}:{line}: {failure}")
            continue
        if name == "_":
            bare_count += 1
            site = (relative, line)
            if site in allowlist:
                found_allowlist_sites.add(site)
            elif not _is_kotlin_silence_annotated(source, line):
                failures.append(f"{relative}:{line}: bare catch is not annotated or allowlisted")
            continue

        body = masked[opening + 1 : closing]
        if _has_unconditional_top_level_throw(body):
            continue
        significant = [line.strip() for line in body.splitlines() if line.strip()]
        if "AppLog." in body:
            if not any("AppLog." in line for line in significant[:3]):
                failures.append(f"{relative}:{line}: non-rethrowing bound catch must log among its first statements")
            continue
        # Forwarding the caught value to a typed error, aggregate, continuation, or
        # throwing helper gives the failure an owner. This gate targets a bound value
        # which is silently discarded; `_` catches have their separate pinned audit.
        body_without_direct_rethrows = re.sub(
            rf"\bthrow\s+{re.escape(name)}\b",
            "throw",
            body,
        )
        if re.search(rf"\b{re.escape(name)}\b", body_without_direct_rethrows):
            continue
        if re.search(r"\b(?:fail[A-Za-z0-9_]*|invalid|resumeWithException)\s*\(", body):
            continue
        failures.append(f"{relative}:{line}: non-rethrowing bound catch must log among its first statements")

    for method_reference in re.finditer(r"::\s*printStackTrace\b", masked):
        line = _line_number(source, method_reference.start())
        failures.append(f"{relative}:{line}: direct printStackTrace method reference")

    for run_catching in re.finditer(r"\brunCatching\s*\{", masked):
        opening = run_catching.end() - 1
        try:
            closing = _matching_delimiter(masked, opening, "{", "}")
        except InstrumentationError as failure:
            line = _line_number(source, run_catching.start())
            failures.append(f"{relative}:{line}: {failure}")
            continue
        methods = _chain_methods(masked, closing + 1)
        if "getOrNull" in methods and "onFailure" not in methods[: methods.index("getOrNull")]:
            line = _line_number(source, run_catching.start())
            failures.append(f"{relative}:{line}: runCatching getOrNull chain requires onFailure")
    return failures, found_allowlist_sites, bare_count


def _handler_annotation(source_lines: list[str], handler: ast.ExceptHandler) -> bool:
    end_line = handler.body[0].lineno if handler.body else handler.lineno
    return any(
        PYTHON_SILENCE_ANNOTATION.fullmatch(source_lines[index - 1])
        for index in range(handler.lineno + 1, end_line + 1)
    )


def _logger_method(call: ast.Call) -> str | None:
    function = call.func
    if not isinstance(function, ast.Attribute) or not isinstance(function.value, ast.Name):
        return None
    if function.value.id != "logger" or function.attr not in LOG_METHODS:
        return None
    return function.attr


def _is_type_name(value: ast.AST) -> bool:
    return (
        isinstance(value, ast.Attribute)
        and value.attr == "__name__"
        and isinstance(value.value, ast.Call)
        and isinstance(value.value.func, ast.Name)
        and value.value.func.id == "type"
        and len(value.value.args) == 1
        and not value.value.keywords
    )


def _audit_python_file(path: Path, repo_root: Path) -> list[str]:
    relative = _relative(path, repo_root)
    source = path.read_text(encoding="utf-8")
    try:
        tree = ast.parse(source, filename=relative)
    except SyntaxError as failure:
        return [f"{relative}:{failure.lineno or 1}: Python syntax error: {failure.msg}"]
    source_lines = source.splitlines()
    failures: list[str] = []

    for node in ast.walk(tree):
        if isinstance(node, ast.ExceptHandler):
            if (
                len(node.body) == 1
                and isinstance(node.body[0], ast.Pass)
                and not _handler_annotation(source_lines, node)
            ):
                failures.append(f"{relative}:{node.lineno}: silent except requires an intentional-silence annotation")
            for descendant in ast.walk(node):
                if not isinstance(descendant, ast.Call):
                    continue
                method = _logger_method(descendant)
                if method not in {"warning", "error"}:
                    continue
                exc_info = next(
                    (keyword.value for keyword in descendant.keywords if keyword.arg == "exc_info"),
                    None,
                )
                disabled = isinstance(exc_info, ast.Constant) and (exc_info.value is False or exc_info.value is None)
                if exc_info is None or disabled:
                    requirement = "traceback-preserving exc_info=" if disabled else "exc_info="
                    failures.append(
                        f"{relative}:{descendant.lineno}: logger.{method} inside except requires {requirement}"
                    )
        elif isinstance(node, ast.Call):
            if (
                _logger_method(node) is not None
                and len(node.args) == 1
                and not node.keywords
                and _is_type_name(node.args[0])
            ):
                failures.append(f"{relative}:{node.lineno}: type(x).__name__ cannot be a log call's sole argument")
    return failures


def _tracked_text_files(repo_root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "-C", str(repo_root), "ls-files", "-z"],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise InstrumentationError(f"{repo_root}: git ls-files failed: {detail}")
    relative_paths = result.stdout.decode("utf-8", errors="surrogateescape").split("\0")
    return [
        repo_root / relative
        for relative in relative_paths
        if relative and Path(relative).suffix in TRACKED_TEXT_SUFFIXES
    ]


def _audit_nul_bytes(repo_root: Path) -> tuple[list[str], int]:
    files = _tracked_text_files(repo_root)
    failures: list[str] = []
    for path in files:
        try:
            content = path.read_bytes()
        except OSError as failure:
            failures.append(f"{_relative(path, repo_root)}: could not read tracked source: {failure}")
            continue
        offset = content.find(b"\x00")
        if offset >= 0:
            failures.append(f"{_relative(path, repo_root)}: NUL byte at offset {offset}")
    return failures, len(files)


def _accepted_key_sets(source: str, path: Path, repo_root: Path) -> frozenset[frozenset[str]]:
    masked = _mask_kotlin(source)
    assignment = re.search(r"\bval\s+accepted\s*=\s*setOf\s*\(", masked)
    if assignment is None:
        raise InstrumentationError(f"{_relative(path, repo_root)}: bridge.error accepted key sets are missing")
    opening = assignment.end() - 1
    closing = _matching_delimiter(masked, opening, "(", ")")
    body = source[opening + 1 : closing]
    required = re.search(
        r"\bval\s+required\s*=\s*setOf\s*\(\s*\"code\"\s*,\s*\"message\"\s*\)",
        source,
    )
    if required is None:
        raise InstrumentationError(f"{_relative(path, repo_root)}: bridge.error required keys are not code,message")
    accepted: set[frozenset[str]] = set()
    for term in re.finditer(r"\brequired\b((?:\s*\+\s*\"[A-Za-z]+\")*)", body):
        keys = {"code", "message"}
        keys.update(re.findall(r"\"([A-Za-z]+)\"", term.group(1)))
        accepted.add(frozenset(keys))
    return frozenset(accepted)


def _walk_fault_patterns(value: object) -> Iterator[str]:
    if isinstance(value, dict):
        fault = value.get("faultId")
        if isinstance(fault, dict) and isinstance(fault.get("pattern"), str):
            yield fault["pattern"]
        for child in value.values():
            yield from _walk_fault_patterns(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_fault_patterns(child)


def _walk_fault_ids(value: object) -> Iterator[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "faultId" and isinstance(child, str):
                yield child
            else:
                yield from _walk_fault_ids(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_fault_ids(child)


def _read_json(path: Path, repo_root: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise InstrumentationError(f"{_relative(path, repo_root)}: could not parse JSON: {failure}") from failure


def _audit_fault_contract(repo_root: Path) -> tuple[list[str], int]:
    failures: list[str] = []
    kotlin_root = repo_root / KOTLIN_ROOT
    codecs: list[tuple[Path, str]] = []
    for path in sorted(kotlin_root.rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        if '"bridge.error"' in source and "faultId" in source:
            codecs.append((path, source))
    codec_paths = {_relative(path, repo_root) for path, _ in codecs}
    if codec_paths != EXPECTED_FAULT_CODECS:
        failures.append(
            f"{KOTLIN_ROOT}: bridge.error faultId codec inventory mismatch: "
            f"found={sorted(codec_paths)}; expected={sorted(EXPECTED_FAULT_CODECS)}"
        )
    if not codecs:
        return failures, 0

    expected_keys = frozenset(
        {
            frozenset({"code", "message"}),
            frozenset({"code", "message", "requestType"}),
            frozenset({"code", "message", "faultId"}),
            frozenset({"code", "message", "requestType", "faultId"}),
        }
    )
    for path, source in codecs:
        try:
            accepted = _accepted_key_sets(source, path, repo_root)
        except InstrumentationError as failure:
            failures.append(str(failure))
            continue
        if accepted != expected_keys:
            failures.append(f"{_relative(path, repo_root)}: bridge.error accepted key sets disagree")
        pattern_match = re.search(r"\b(?:faultIdPattern|faultId)\s*=\s*Regex\(\"([^\"]+)\"\)", source)
        if pattern_match is None or f"^{pattern_match.group(1)}$" != EXPECTED_FAULT_PATTERN:
            actual = pattern_match.group(1) if pattern_match else "missing"
            failures.append(
                f"{_relative(path, repo_root)}: faultId pattern mismatch: {actual!r}; expected {EXPECTED_FAULT_PATTERN!r}"
            )

    faults_path = repo_root / FAULTS_PATH
    try:
        faults_source = faults_path.read_text(encoding="utf-8")
    except OSError as failure:
        failures.append(f"{FAULTS_PATH}: could not read fault id producer: {failure}")
    else:
        pattern_match = re.search(r"^FAULT_ID_PATTERN\s*=\s*r?\"([^\"]+)\"", faults_source, re.MULTILINE)
        if pattern_match is None or pattern_match.group(1) != EXPECTED_FAULT_PATTERN:
            actual = pattern_match.group(1) if pattern_match else "missing"
            failures.append(f"{FAULTS_PATH}: faultId pattern mismatch: {actual!r}; expected {EXPECTED_FAULT_PATTERN!r}")

    schema_patterns: list[tuple[Path, str]] = []
    for path in sorted((repo_root / SCHEMA_ROOT).glob("*.json")):
        value = _read_json(path, repo_root)
        schema_patterns.extend((path, pattern) for pattern in _walk_fault_patterns(value))
    if not schema_patterns:
        failures.append(f"{SCHEMA_ROOT}: no faultId schema pattern found")
    for path, pattern in schema_patterns:
        if pattern != EXPECTED_FAULT_PATTERN:
            failures.append(
                f"{_relative(path, repo_root)}: faultId pattern mismatch: {pattern!r}; expected {EXPECTED_FAULT_PATTERN!r}"
            )

    contract = _read_json(repo_root / CONTRACT_PATH, repo_root)
    if not isinstance(contract, dict) or contract.get("version") != 1:
        failures.append(f"{CONTRACT_PATH}: expected version 1 contract corpus")
    else:
        valid_ids = list(_walk_fault_ids(contract.get("valid", [])))
        invalid_ids = list(_walk_fault_ids(contract.get("invalid", [])))
        if not valid_ids or not invalid_ids:
            failures.append(f"{CONTRACT_PATH}: valid and invalid faultId controls are required")
        pattern = re.compile(EXPECTED_FAULT_PATTERN)
        for fault_id in valid_ids:
            if pattern.fullmatch(fault_id) is None:
                failures.append(f"{CONTRACT_PATH}: valid contract faultId does not match: {fault_id}")
        for fault_id in invalid_ids:
            if pattern.fullmatch(fault_id) is not None:
                failures.append(f"{CONTRACT_PATH}: invalid contract faultId unexpectedly matches: {fault_id}")
    return failures, len(codecs)


def audit(repo_root: Path) -> AuditSummary:
    if not repo_root.is_dir():
        raise InstrumentationError(f"{repo_root}: repository root is not a directory")
    allowlist = _read_allowlist(repo_root)
    failures: list[str] = []
    found_allowlist_sites: set[tuple[str, int]] = set()
    bare_catches = 0
    production_kotlin_files = sorted(
        path for source_root in KOTLIN_SOURCE_ROOTS for path in (repo_root / source_root).rglob("*.kt")
    )
    for path in production_kotlin_files:
        failures.extend(_audit_applog_boundary(path, repo_root))

    kotlin_files = sorted((repo_root / KOTLIN_ROOT).rglob("*.kt"))
    for path in kotlin_files:
        file_failures, found, count = _audit_kotlin_file(path, repo_root, allowlist)
        failures.extend(file_failures)
        found_allowlist_sites.update(found)
        bare_catches += count
    stale = sorted(set(allowlist) - found_allowlist_sites)
    failures.extend(f"{path}:{line}: stale bare-catch allowlist entry" for path, line in stale)

    python_files = sorted((repo_root / PYTHON_BRIDGE_ROOT).rglob("*.py"))
    for path in python_files:
        failures.extend(_audit_python_file(path, repo_root))

    nul_failures, tracked_text_files = _audit_nul_bytes(repo_root)
    failures.extend(nul_failures)
    fault_failures, fault_codecs = _audit_fault_contract(repo_root)
    failures.extend(fault_failures)
    if failures:
        raise InstrumentationError("\n".join(failures))
    return AuditSummary(
        bare_catches=bare_catches,
        kotlin_files=len(kotlin_files),
        python_files=len(python_files),
        tracked_text_files=tracked_text_files,
        fault_codecs=fault_codecs,
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=REPO_ROOT,
        help="repository root containing app source and instrumentation allowlist",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        summary = audit(args.repo_root.resolve())
    except InstrumentationError as failure:
        print(f"instrumentation audit failed: {failure}", file=sys.stderr)
        return 1

    print(
        "Instrumentation audit passed: "
        f"{summary.bare_catches} bare catch site(s) verified; "
        f"{summary.kotlin_files} Kotlin file(s); {summary.python_files} bridge Python file(s); "
        f"{summary.tracked_text_files} tracked text file(s); {summary.fault_codecs} fault codec(s)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
