"""Versioned JSON messages used across the Chaquopy boundary."""

from __future__ import annotations

import dataclasses
import json
import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any

BRIDGE_SCHEMA_VERSION = 1

# Kotlin decodes integer-valued fields as ``Long`` and floating-point fields as
# ``Double``. Keep Python on that same deliberately narrow wire domain instead
# of accepting its arbitrary-precision ints or non-finite IEEE values.
JSON_INTEGER_MIN = -(1 << 63)
JSON_INTEGER_MAX = (1 << 63) - 1

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

    if value is None or isinstance(value, (str, bool)):
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
            raise BridgeProtocolError(
                "non_finite_number", "JSON messages cannot contain NaN or infinity"
            )
        return value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, Enum):
        return to_json_value(value.value, _seen=_seen)

    seen = _seen if _seen is not None else set()
    identity = id(value)
    if identity in seen:
        raise BridgeProtocolError(
            "recursive_value", "Recursive values cannot cross the bridge"
        )

    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        seen.add(identity)
        try:
            return {
                _camel_case(field.name): to_json_value(
                    getattr(value, field.name), _seen=seen
                )
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
                    raise BridgeProtocolError(
                        "non_string_key", "JSON object keys must be strings"
                    )
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

    raise BridgeProtocolError(
        "unsupported_value", f"Unsupported bridge value: {type(value).__name__}"
    )


def encode_message(message_type: str, payload: Mapping[str, Any]) -> str:
    """Encode a canonical v1 bridge envelope."""

    if not isinstance(message_type, str) or not _MESSAGE_TYPE_RE.fullmatch(
        message_type
    ):
        raise BridgeProtocolError(
            "invalid_message_type", f"Invalid message type: {message_type!r}"
        )
    if not isinstance(payload, Mapping):
        raise BridgeProtocolError(
            "invalid_payload", "Bridge payload must be a JSON object"
        )

    envelope = {
        "schemaVersion": BRIDGE_SCHEMA_VERSION,
        "type": message_type,
        "payload": to_json_value(payload),
    }
    try:
        return json.dumps(
            envelope, ensure_ascii=False, separators=(",", ":"), allow_nan=False
        )
    except (TypeError, ValueError) as exc:
        raise BridgeProtocolError("invalid_payload", str(exc)) from exc


def _reject_non_finite(literal: str) -> None:
    raise BridgeProtocolError(
        "non_finite_number", f"Non-finite JSON number is forbidden: {literal}"
    )


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
        raise BridgeProtocolError(
            "invalid_json_number", "Malformed JSON floating-point number"
        ) from exc
    if not math.isfinite(value):
        raise BridgeProtocolError(
            "non_finite_number",
            "JSON floating-point numbers must be finite IEEE-754 doubles",
        )
    return value


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BridgeProtocolError(
                "duplicate_json_key", f"Duplicate JSON object key: {key}"
            )
        result[key] = value
    return result


def decode_envelope(raw: str, *, expected_type: str | None = None) -> DecodedMessage:
    """Decode a strict RFC JSON bridge envelope.

    The numeric wire domain is Kotlin-compatible: signed 64-bit integer tokens
    and finite IEEE-754 double tokens. Python otherwise accepts arbitrary-size
    integers, exponent overflow, JavaScript ``NaN``/``Infinity`` tokens, and
    duplicate object keys; none are allowed here.
    """

    if not isinstance(raw, str):
        raise BridgeProtocolError(
            "invalid_json", "Bridge message must be a JSON string"
        )
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

    if not isinstance(envelope, dict):
        raise BridgeProtocolError(
            "invalid_envelope", "Bridge envelope must be a JSON object"
        )
    expected_keys = {"schemaVersion", "type", "payload"}
    if set(envelope) != expected_keys:
        raise BridgeProtocolError(
            "invalid_envelope", "Bridge envelope has missing or unknown fields"
        )

    raw_version = envelope["schemaVersion"]
    version = normalize_integral_json_number(raw_version)
    if version != BRIDGE_SCHEMA_VERSION:
        raise BridgeProtocolError(
            "unsupported_schema_version",
            f"Expected bridge schema {BRIDGE_SCHEMA_VERSION}, received {raw_version!r}",
        )

    message_type = envelope["type"]
    if not isinstance(message_type, str) or not _MESSAGE_TYPE_RE.fullmatch(
        message_type
    ):
        raise BridgeProtocolError(
            "invalid_message_type", "Bridge message type is invalid"
        )
    if expected_type is not None and message_type != expected_type:
        raise BridgeProtocolError(
            "unexpected_message_type",
            f"Expected {expected_type!r}, received {message_type!r}",
        )

    payload = envelope["payload"]
    if not isinstance(payload, dict):
        raise BridgeProtocolError(
            "invalid_payload", "Bridge payload must be a JSON object"
        )
    return DecodedMessage(message_type=message_type, payload=payload)


def decode_message(raw: str, *, expected_type: str | None = None) -> dict[str, Any]:
    """Decode and validate a bridge envelope, returning its payload."""

    return decode_envelope(raw, expected_type=expected_type).payload


def encode_protocol_error(
    error: BridgeProtocolError, *, request_type: str | None = None
) -> str:
    """Encode a protocol failure without exposing Python exception details."""

    payload: dict[str, Any] = {"code": error.code, "message": str(error)}
    if request_type is not None:
        payload["requestType"] = request_type
    return encode_message("bridge.error", payload)
