"""Versioned JSON messages used across the Chaquopy boundary."""

from __future__ import annotations

import dataclasses
import json
import math
import re
from collections.abc import Mapping, Sequence
from enum import Enum
from pathlib import Path
from typing import Any

BRIDGE_SCHEMA_VERSION = 1

_MESSAGE_TYPE_RE = re.compile(r"^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+$")


class BridgeProtocolError(ValueError):
    """A stable, machine-readable protocol failure.

    ``code`` is safe for Kotlin to branch on.  ``message`` remains suitable for
    logs and diagnostics but is not part of the compatibility contract.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _camel_case(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def to_json_value(value: Any, *, _seen: set[int] | None = None) -> Any:
    """Convert common engine values into JSON-compatible data.

    This is intentionally structural: importing concrete engine result classes
    here would violate the bootstrap ordering constraint.  Recursive object
    graphs are rejected instead of being silently truncated.
    """

    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise BridgeProtocolError("non_finite_number", "JSON messages cannot contain NaN or infinity")
        return value
    if isinstance(value, Path):
        return str(value)
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


def encode_message(message_type: str, payload: Mapping[str, Any]) -> str:
    """Encode a canonical v1 bridge envelope."""

    if not isinstance(message_type, str) or not _MESSAGE_TYPE_RE.fullmatch(message_type):
        raise BridgeProtocolError("invalid_message_type", f"Invalid message type: {message_type!r}")
    if not isinstance(payload, Mapping):
        raise BridgeProtocolError("invalid_payload", "Bridge payload must be a JSON object")

    envelope = {
        "schemaVersion": BRIDGE_SCHEMA_VERSION,
        "type": message_type,
        "payload": to_json_value(payload),
    }
    try:
        return json.dumps(envelope, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    except (TypeError, ValueError) as exc:
        raise BridgeProtocolError("invalid_payload", str(exc)) from exc


def decode_message(raw: str, *, expected_type: str | None = None) -> dict[str, Any]:
    """Decode and validate a bridge envelope, returning its payload."""

    if not isinstance(raw, str):
        raise BridgeProtocolError("invalid_json", "Bridge message must be a JSON string")
    try:
        envelope = json.loads(raw)
    except (json.JSONDecodeError, RecursionError) as exc:
        raise BridgeProtocolError("invalid_json", "Malformed bridge JSON") from exc

    if not isinstance(envelope, dict):
        raise BridgeProtocolError("invalid_envelope", "Bridge envelope must be a JSON object")
    expected_keys = {"schemaVersion", "type", "payload"}
    if set(envelope) != expected_keys:
        raise BridgeProtocolError("invalid_envelope", "Bridge envelope has missing or unknown fields")

    version = envelope["schemaVersion"]
    if type(version) is not int or version != BRIDGE_SCHEMA_VERSION:
        raise BridgeProtocolError(
            "unsupported_schema_version",
            f"Expected bridge schema {BRIDGE_SCHEMA_VERSION}, received {version!r}",
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
    return payload


def encode_protocol_error(error: BridgeProtocolError, *, request_type: str | None = None) -> str:
    """Encode a protocol failure without exposing Python exception details."""

    payload: dict[str, Any] = {"code": error.code, "message": str(error)}
    if request_type is not None:
        payload["requestType"] = request_type
    return encode_message("bridge.error", payload)
