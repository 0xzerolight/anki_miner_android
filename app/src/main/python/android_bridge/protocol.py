"""Versioned JSON messages used across the Chaquopy boundary."""

from __future__ import annotations

import dataclasses
import json
import math
import re
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from itertools import chain
from pathlib import Path
from typing import Any

from .anki_limits import ANKI_LIMITS_V1

BRIDGE_SCHEMA_VERSION = 1
_MAX_JSON_NUMBER_TOKEN_CHARS = ANKI_LIMITS_V1["wire"]["numericTokenMaxChars"]

# Kotlin decodes integer-valued fields as ``Long`` and floating-point fields as
# ``Double``. Keep Python on that same deliberately narrow wire domain instead
# of accepting its arbitrary-precision ints or non-finite IEEE values.
JSON_INTEGER_MIN = -(1 << 63)
JSON_INTEGER_MAX = (1 << 63) - 1

_DICTIONARY_TERM_MAX_UTF8_BYTES = 256
_SENTENCE_AUDIO_MAX_UTF16_UNITS = 4_000

# ``json.loads`` accepts escaped lone UTF-16 surrogates and returns them in a
# Python ``str``. They cannot be encoded as strict UTF-8, so validate every
# decoded key and value before any operation-specific parser sees the payload.
# Explicit structural ceilings keep this generic boundary independent of the
# tighter per-operation byte and item limits enforced by its callers.
_MAX_DECODED_JSON_DEPTH = 128
_MAX_DECODED_JSON_NODES = 1_000_000

_MESSAGE_TYPE_RE = re.compile(r"^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+$")


class BridgeProtocolError(ValueError):
    """A stable, machine-readable protocol failure.

    ``code`` is safe for Kotlin to branch on.  ``message`` remains suitable for
    logs and diagnostics but is not part of the compatibility contract.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class DecodedMessage:
    """A fully validated bridge envelope."""

    message_type: str
    payload: dict[str, Any]


def _camel_case(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def _require_unicode_scalar_string(value: str, *, context: str) -> None:
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise BridgeProtocolError("invalid_utf8", f"{context} contains an invalid Unicode scalar") from exc


def _validate_decoded_json(value: Any) -> None:
    """Validate decoded strings with bounded, non-recursive traversal."""

    frames: list[tuple[Iterator[Any], int]] = [(iter((value,)), 0)]
    visited_nodes = 0
    while frames:
        iterator, depth = frames[-1]
        try:
            current = next(iterator)
        except StopIteration:
            frames.pop()
            continue

        visited_nodes += 1
        if visited_nodes > _MAX_DECODED_JSON_NODES:
            raise BridgeProtocolError("invalid_json", "Bridge JSON exceeds its decoded node limit")

        if isinstance(current, str):
            _require_unicode_scalar_string(current, context="Bridge JSON string")
            continue

        if isinstance(current, dict):
            children: Iterator[Any] = chain.from_iterable(current.items())
        elif isinstance(current, list):
            children = iter(current)
        else:
            continue

        if not current:
            continue
        if depth >= _MAX_DECODED_JSON_DEPTH:
            raise BridgeProtocolError("invalid_json", "Bridge JSON exceeds its decoded depth limit")
        frames.append((children, depth + 1))


def normalize_integral_json_number(value: object) -> int | None:
    """Normalize a mathematical JSON integer into the signed-64 wire domain.

    Draft 2020-12 considers ``1`` and ``1.0`` integers. Booleans, non-integral
    doubles, non-finite doubles, and values outside Kotlin ``Long`` are not.
    """

    if type(value) is int:
        converted = value
    elif type(value) is float and math.isfinite(value) and value.is_integer():
        if value < JSON_INTEGER_MIN or value > JSON_INTEGER_MAX:
            return None
        converted = int(value)
    else:
        return None
    if converted < JSON_INTEGER_MIN or converted > JSON_INTEGER_MAX:
        return None
    return converted


def to_json_value(value: Any, *, _seen: set[int] | None = None) -> Any:
    """Convert common engine values into JSON-compatible data.

    This is intentionally structural: importing concrete engine result classes
    here would violate the bootstrap ordering constraint.  Recursive object
    graphs are rejected instead of being silently truncated.
    """

    if isinstance(value, str):
        _require_unicode_scalar_string(value, context="Bridge string")
        return value
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, int):
        if normalize_integral_json_number(value) is None:
            raise BridgeProtocolError(
                "integer_out_of_range",
                "JSON integers must fit a signed 64-bit Kotlin Long",
            )
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise BridgeProtocolError("non_finite_number", "JSON messages cannot contain NaN or infinity")
        return value
    if isinstance(value, Path):
        return to_json_value(str(value), _seen=_seen)
    if isinstance(value, Enum):
        return to_json_value(value.value, _seen=_seen)

    seen = _seen if _seen is not None else set()
    identity = id(value)
    if identity in seen:
        raise BridgeProtocolError("recursive_value", "Recursive values cannot cross the bridge")

    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        seen.add(identity)
        try:
            return {
                _camel_case(field.name): to_json_value(getattr(value, field.name), _seen=seen)
                for field in dataclasses.fields(value)
            }
        finally:
            seen.remove(identity)

    if isinstance(value, Mapping):
        seen.add(identity)
        try:
            converted: dict[str, Any] = {}
            for key, item in value.items():
                if not isinstance(key, str):
                    raise BridgeProtocolError("non_string_key", "JSON object keys must be strings")
                _require_unicode_scalar_string(key, context="Bridge object key")
                converted[key] = to_json_value(item, _seen=seen)
            return converted
        finally:
            seen.remove(identity)

    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        seen.add(identity)
        try:
            return [to_json_value(item, _seen=seen) for item in value]
        finally:
            seen.remove(identity)

    raise BridgeProtocolError("unsupported_value", f"Unsupported bridge value: {type(value).__name__}")


def _utf8_size(value: str) -> int:
    return len(value.encode("utf-8"))


def _utf16_units(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def validate_message_semantics(message_type: str, payload: Mapping[str, Any]) -> None:
    """Enforce bridge invariants Draft 2020-12 cannot express."""

    if message_type == "progress.stage":
        index = normalize_integral_json_number(payload.get("index"))
        total = normalize_integral_json_number(payload.get("total"))
        if index is None or total is None or not 1 <= index <= total <= 32:
            raise BridgeProtocolError(
                "invalid_progress_stage",
                "progress.stage must satisfy 1 <= index <= total <= 32",
            )
        return

    if message_type == "dictionary.define":
        term = payload.get("term")
        fallback = payload.get("fallbackTerm")
        if not isinstance(term, str) or not term.strip() or _utf8_size(term) > _DICTIONARY_TERM_MAX_UTF8_BYTES:
            raise BridgeProtocolError(
                "invalid_definition_request",
                "term must be non-blank and at most 256 UTF-8 bytes",
            )
        if fallback is not None and (
            not isinstance(fallback, str) or _utf8_size(fallback) > _DICTIONARY_TERM_MAX_UTF8_BYTES
        ):
            raise BridgeProtocolError(
                "invalid_definition_request",
                "fallbackTerm must be null or at most 256 UTF-8 bytes",
            )
        return

    if message_type == "tts.sentence.request":
        sentence = payload.get("sentence")
        if (
            not isinstance(sentence, str)
            or not sentence
            or "\x00" in sentence
            or _utf16_units(sentence) > _SENTENCE_AUDIO_MAX_UTF16_UNITS
        ):
            raise BridgeProtocolError(
                "invalid_tts_request",
                "sentence must contain at most 4000 UTF-16 code units",
            )
        return

    if message_type == "subtitle.cues.result":
        cues = payload.get("cues")
        if not isinstance(cues, list):
            raise BridgeProtocolError("invalid_subtitle_cues_result", "cues must be an array")
        for cue in cues:
            if not isinstance(cue, Mapping):
                raise BridgeProtocolError("invalid_subtitle_cues_result", "subtitle cue must be an object")
            start = cue.get("start")
            end = cue.get("end")
            if (
                type(start) not in (int, float)
                or type(end) not in (int, float)
                or not math.isfinite(start)
                or not math.isfinite(end)
                or start < 0
                or end < start
            ):
                raise BridgeProtocolError(
                    "invalid_subtitle_cues_result",
                    "subtitle cue times must satisfy end >= start >= 0",
                )


def encode_message(message_type: str, payload: Mapping[str, Any]) -> str:
    """Encode a canonical v1 bridge envelope."""

    if not isinstance(message_type, str) or not _MESSAGE_TYPE_RE.fullmatch(message_type):
        raise BridgeProtocolError("invalid_message_type", f"Invalid message type: {message_type!r}")
    if not isinstance(payload, Mapping):
        raise BridgeProtocolError("invalid_payload", "Bridge payload must be a JSON object")

    converted_payload = to_json_value(payload)
    validate_message_semantics(message_type, converted_payload)
    envelope = {
        "schemaVersion": BRIDGE_SCHEMA_VERSION,
        "type": message_type,
        "payload": converted_payload,
    }
    try:
        return json.dumps(envelope, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    except (TypeError, ValueError) as exc:
        raise BridgeProtocolError("invalid_payload", str(exc)) from exc


def _reject_non_finite(literal: str) -> None:
    raise BridgeProtocolError("non_finite_number", f"Non-finite JSON number is forbidden: {literal}")


def _parse_json_integer(literal: str) -> int:
    try:
        value = int(literal)
    except (ValueError, OverflowError) as exc:
        raise BridgeProtocolError(
            "integer_out_of_range",
            "JSON integers must fit a signed 64-bit Kotlin Long",
        ) from exc
    if normalize_integral_json_number(value) is None:
        raise BridgeProtocolError(
            "integer_out_of_range",
            "JSON integers must fit a signed 64-bit Kotlin Long",
        )
    return value


def _parse_json_float(literal: str) -> float:
    try:
        value = float(literal)
    except (ValueError, OverflowError) as exc:
        raise BridgeProtocolError("invalid_json_number", "Malformed JSON floating-point number") from exc
    if not math.isfinite(value):
        raise BridgeProtocolError(
            "non_finite_number",
            "JSON floating-point numbers must be finite IEEE-754 doubles",
        )
    return value


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require_unicode_scalar_string(key, context="Bridge JSON string")
        if key in result:
            raise BridgeProtocolError("duplicate_json_key", f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def _json_number_token_end(raw: str, start: int) -> int | None:
    """Return the end of one valid JSON number token, or ``None``.

    The caller invokes this only outside JSON strings. Keeping this lexical
    pass separate from ``json.loads`` prevents Python's arbitrary-precision
    integer parser from allocating for a token which Kotlin rejects while
    streaming.
    """

    length = len(raw)
    cursor = start
    if raw[cursor] == "-":
        cursor += 1
        if cursor == length:
            return None

    if raw[cursor] == "0":
        cursor += 1
    elif "1" <= raw[cursor] <= "9":
        cursor += 1
        while cursor < length and "0" <= raw[cursor] <= "9":
            cursor += 1
    else:
        return None

    if cursor < length and raw[cursor] == ".":
        fraction_start = cursor
        cursor += 1
        if cursor == length or not "0" <= raw[cursor] <= "9":
            return fraction_start
        while cursor < length and "0" <= raw[cursor] <= "9":
            cursor += 1

    if cursor < length and raw[cursor] in "eE":
        exponent_start = cursor
        cursor += 1
        if cursor < length and raw[cursor] in "+-":
            cursor += 1
        if cursor == length or not "0" <= raw[cursor] <= "9":
            return exponent_start
        while cursor < length and "0" <= raw[cursor] <= "9":
            cursor += 1
    return cursor


def _validate_json_number_token_lengths(raw: str) -> None:
    """Reject overlong number tokens without inspecting quoted content."""

    cursor = 0
    in_string = False
    while cursor < len(raw):
        character = raw[cursor]
        if in_string:
            if character == "\\":
                # The JSON decoder owns escape validity. Skipping the escaped
                # code unit is enough to keep quotes and digit text opaque.
                cursor += 2
                continue
            if character == '"':
                in_string = False
            cursor += 1
            continue
        if character == '"':
            in_string = True
            cursor += 1
            continue
        if character == "-" or "0" <= character <= "9":
            token_end = _json_number_token_end(raw, cursor)
            if token_end is not None:
                if token_end - cursor > _MAX_JSON_NUMBER_TOKEN_CHARS:
                    raise BridgeProtocolError(
                        "json_number_too_long",
                        "Bridge JSON number exceeds its lexical character limit",
                    )
                cursor = token_end
                continue
        cursor += 1


def decode_envelope(raw: str, *, expected_type: str | None = None) -> DecodedMessage:
    """Decode a strict RFC JSON bridge envelope.

    The numeric wire domain is Kotlin-compatible: signed 64-bit integer tokens
    and finite IEEE-754 double tokens. Python otherwise accepts arbitrary-size
    integers, exponent overflow, JavaScript ``NaN``/``Infinity`` tokens, and
    duplicate object keys; none are allowed here.
    """

    if not isinstance(raw, str):
        raise BridgeProtocolError("invalid_json", "Bridge message must be a JSON string")
    if raw.startswith("\ufeff"):
        raise BridgeProtocolError("invalid_json", "A leading Unicode BOM is not JSON whitespace")
    _validate_json_number_token_lengths(raw)
    try:
        envelope = json.loads(
            raw,
            parse_constant=_reject_non_finite,
            parse_float=_parse_json_float,
            parse_int=_parse_json_integer,
            object_pairs_hook=_reject_duplicate_keys,
        )
    except BridgeProtocolError:
        raise
    except (json.JSONDecodeError, RecursionError, ValueError, OverflowError) as exc:
        raise BridgeProtocolError("invalid_json", "Malformed bridge JSON") from exc

    _validate_decoded_json(envelope)

    if not isinstance(envelope, dict):
        raise BridgeProtocolError("invalid_envelope", "Bridge envelope must be a JSON object")
    expected_keys = {"schemaVersion", "type", "payload"}
    if set(envelope) != expected_keys:
        raise BridgeProtocolError("invalid_envelope", "Bridge envelope has missing or unknown fields")

    raw_version = envelope["schemaVersion"]
    version = normalize_integral_json_number(raw_version)
    if version != BRIDGE_SCHEMA_VERSION:
        raise BridgeProtocolError(
            "unsupported_schema_version",
            f"Expected bridge schema {BRIDGE_SCHEMA_VERSION}, received {raw_version!r}",
        )

    message_type = envelope["type"]
    if not isinstance(message_type, str) or not _MESSAGE_TYPE_RE.fullmatch(message_type):
        raise BridgeProtocolError("invalid_message_type", "Bridge message type is invalid")
    if expected_type is not None and message_type != expected_type:
        raise BridgeProtocolError(
            "unexpected_message_type",
            f"Expected {expected_type!r}, received {message_type!r}",
        )

    payload = envelope["payload"]
    if not isinstance(payload, dict):
        raise BridgeProtocolError("invalid_payload", "Bridge payload must be a JSON object")
    validate_message_semantics(message_type, payload)
    return DecodedMessage(message_type=message_type, payload=payload)


def decode_message(raw: str, *, expected_type: str | None = None) -> dict[str, Any]:
    """Decode and validate a bridge envelope, returning its payload."""

    return decode_envelope(raw, expected_type=expected_type).payload


def encode_protocol_error(
    error: BridgeProtocolError,
    *,
    request_type: str | None = None,
    fault_id: str | None = None,
) -> str:
    """Encode a protocol failure without exposing Python exception details.

    ``fault_id`` is emitted only when the caller logged a traceback under it, so
    a payload without one is still a complete, valid ``bridge.error``.
    """

    payload: dict[str, Any] = {"code": error.code, "message": str(error)}
    if request_type is not None:
        payload["requestType"] = request_type
    if fault_id is not None:
        payload["faultId"] = fault_id
    return encode_message("bridge.error", payload)
