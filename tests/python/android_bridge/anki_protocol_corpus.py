"""Strict loader for the shared Anki bridge JSONL conformance corpus."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, NoReturn

_MAX_CORPUS_BYTES = 2 * 1024 * 1024
_MAX_LINE_BYTES = 512 * 1024
_MAX_EXPANDED_RAW_CHARS = 20 * 1024 * 1024
_MAX_CONCAT_SEGMENTS = 256
_MAX_REPEAT_COUNT = 20 * 1024 * 1024

_CASE_ID_RE = re.compile(r"^[a-z0-9]+(?:_[a-z0-9]+)*$")
_MESSAGE_TYPE_RE = re.compile(r"^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+$")
_CALLBACKS = frozenset(
    {
        "verifyTarget",
        "scanFirstFields",
        "storeMedia",
        "createNotes",
        "releaseRunState",
    }
)
_DIRECTIONS = frozenset({"request", "response"})
_REJECTION_CATEGORIES = frozenset(
    {
        "input_too_large",
        "output_too_large",
        "invalid_utf8",
        "invalid_json",
        "duplicate_json_key",
        "json_number_too_long",
        "invalid_envelope",
        "unsupported_schema_version",
        "unexpected_message_type",
        "invalid_payload",
        "invalid_value",
        "limit_mismatch",
    }
)


class CorpusFormatError(ValueError):
    """The committed corpus itself is malformed or unsafe to expand."""


@dataclass(frozen=True)
class CorpusExpectation:
    outcome: str
    message_type: str | None = None
    payload: dict[str, Any] | None = None
    canonical: str | None = None
    category: str | None = None


@dataclass(frozen=True)
class CorpusCase:
    case_id: str
    callback: str
    direction: str
    raw: str
    expectation: CorpusExpectation


def _fail(message: str) -> NoReturn:
    raise CorpusFormatError(message)


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _fail(f"duplicate corpus object key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> NoReturn:
    _fail(f"non-JSON corpus number: {value}")


def _require_scalar_strings(value: Any) -> None:
    pending = [value]
    while pending:
        current = pending.pop()
        if isinstance(current, str):
            try:
                current.encode("utf-8")
            except UnicodeEncodeError as error:
                raise CorpusFormatError(
                    "corpus data must use utf16CodeUnits for unpaired surrogates"
                ) from error
        elif isinstance(current, dict):
            pending.extend(current.keys())
            pending.extend(current.values())
        elif isinstance(current, list):
            pending.extend(current)


def _exact_keys(value: object, expected: set[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        _fail(f"{context} must contain exactly {sorted(expected)}")
    return value


def _utf16_string(units: object, context: str) -> str:
    if (
        not isinstance(units, list)
        or not units
        or len(units) > 32
        or any(type(unit) is not int or unit < 0 or unit > 0xFFFF for unit in units)
    ):
        _fail(f"{context}.utf16CodeUnits must contain 1..32 unsigned code units")

    result: list[str] = []
    index = 0
    while index < len(units):
        first = units[index]
        if (
            0xD800 <= first <= 0xDBFF
            and index + 1 < len(units)
            and 0xDC00 <= units[index + 1] <= 0xDFFF
        ):
            second = units[index + 1]
            result.append(chr(0x10000 + ((first - 0xD800) << 10) + second - 0xDC00))
            index += 2
        else:
            result.append(chr(first))
            index += 1
    return "".join(result)


def _expand_input(value: object, context: str) -> str:
    if not isinstance(value, dict) or len(value) != 1:
        _fail(f"{context} must contain exactly raw or concat")
    if "raw" in value:
        raw = value["raw"]
        if not isinstance(raw, str):
            _fail(f"{context}.raw must be a string")
        return raw
    if "concat" not in value:
        _fail(f"{context} has an unknown construction")

    segments = value["concat"]
    if (
        not isinstance(segments, list)
        or not segments
        or len(segments) > _MAX_CONCAT_SEGMENTS
    ):
        _fail(f"{context}.concat must contain 1..{_MAX_CONCAT_SEGMENTS} segments")

    expanded: list[str] = []
    expanded_chars = 0
    for index, segment in enumerate(segments):
        segment_context = f"{context}.concat[{index}]"
        if isinstance(segment, str):
            text = segment
        elif isinstance(segment, dict) and set(segment) == {"repeat"}:
            repeat = _exact_keys(
                segment["repeat"], {"text", "count"}, f"{segment_context}.repeat"
            )
            text_value = repeat["text"]
            count = repeat["count"]
            if not isinstance(text_value, str) or not text_value:
                _fail(f"{segment_context}.repeat.text must be a non-empty string")
            if type(count) is not int or count < 0 or count > _MAX_REPEAT_COUNT:
                _fail(f"{segment_context}.repeat.count is outside the safe bound")
            if len(text_value) * count > _MAX_EXPANDED_RAW_CHARS - expanded_chars:
                _fail(f"{context} expands beyond the safe character bound")
            text = text_value * count
        elif isinstance(segment, dict) and set(segment) == {"utf16CodeUnits"}:
            text = _utf16_string(segment["utf16CodeUnits"], segment_context)
        else:
            _fail(f"{segment_context} has an unknown construction")
        expanded_chars += len(text)
        if expanded_chars > _MAX_EXPANDED_RAW_CHARS:
            _fail(f"{context} expands beyond the safe character bound")
        expanded.append(text)
    return "".join(expanded)


def _parse_expectation(value: object, context: str) -> CorpusExpectation:
    if not isinstance(value, dict) or value.get("outcome") not in {"accept", "reject"}:
        _fail(f"{context} must declare outcome accept or reject")
    if value["outcome"] == "reject":
        expected = _exact_keys(value, {"outcome", "category"}, context)
        category = expected["category"]
        if category not in _REJECTION_CATEGORIES:
            _fail(f"{context}.category is unknown")
        return CorpusExpectation(outcome="reject", category=category)

    allowed = {"outcome", "messageType", "payload", "canonical"}
    if set(value) not in (
        {"outcome", "messageType", "payload"},
        allowed,
    ):
        _fail(f"{context} has missing or unknown accepted-result fields")
    message_type = value["messageType"]
    payload = value["payload"]
    canonical = value.get("canonical")
    if not isinstance(message_type, str) or not _MESSAGE_TYPE_RE.fullmatch(message_type):
        _fail(f"{context}.messageType is invalid")
    if not isinstance(payload, dict):
        _fail(f"{context}.payload must be an object")
    if canonical is not None and not isinstance(canonical, str):
        _fail(f"{context}.canonical must be a string")
    return CorpusExpectation(
        outcome="accept",
        message_type=message_type,
        payload=payload,
        canonical=canonical,
    )


def load_anki_protocol_corpus(path: Path) -> tuple[CorpusCase, ...]:
    """Load, validate, and safely expand the committed language-neutral vectors."""

    data = path.read_bytes()
    if len(data) > _MAX_CORPUS_BYTES:
        _fail("corpus exceeds its checked-in byte bound")
    if data.startswith(b"\xef\xbb\xbf"):
        _fail("corpus file must not start with a UTF-8 BOM")
    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise CorpusFormatError("corpus file is not strict UTF-8") from error
    if text and not text.endswith("\n"):
        _fail("corpus file must end with a newline")

    cases: list[CorpusCase] = []
    seen_ids: set[str] = set()
    for line_number, line in enumerate(text.splitlines(), 1):
        if not line:
            _fail(f"line {line_number} is empty")
        if len(line.encode("utf-8")) > _MAX_LINE_BYTES:
            _fail(f"line {line_number} exceeds the safe line bound")
        try:
            record = json.loads(
                line,
                object_pairs_hook=_reject_duplicate_keys,
                parse_constant=_reject_constant,
            )
        except CorpusFormatError:
            raise
        except (json.JSONDecodeError, RecursionError, ValueError) as error:
            raise CorpusFormatError(f"line {line_number} is not strict JSON") from error
        _require_scalar_strings(record)
        record = _exact_keys(
            record,
            {"id", "callback", "direction", "input", "expect"},
            f"line {line_number}",
        )
        case_id = record["id"]
        callback = record["callback"]
        direction = record["direction"]
        if not isinstance(case_id, str) or not _CASE_ID_RE.fullmatch(case_id):
            _fail(f"line {line_number} has an invalid case id")
        if case_id in seen_ids:
            _fail(f"duplicate corpus case id: {case_id}")
        if callback not in _CALLBACKS:
            _fail(f"{case_id} names an unknown callback")
        if direction not in _DIRECTIONS:
            _fail(f"{case_id} names an unknown direction")
        expectation = _parse_expectation(record["expect"], f"{case_id}.expect")
        if direction == "request" and expectation.canonical is not None:
            _fail(f"{case_id} supplies canonical output for an input request")
        raw = _expand_input(record["input"], f"{case_id}.input")
        cases.append(CorpusCase(case_id, callback, direction, raw, expectation))
        seen_ids.add(case_id)

    if not cases:
        _fail("corpus must contain at least one case")
    return tuple(cases)
