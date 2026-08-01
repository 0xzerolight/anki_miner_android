"""Private Python reference for Kotlin canonical Anki request identities.\n\nCallers must first pass the complete wire request through the independent shared\nJSON Schema and semantic validator in ``test_anki_protocol_corpus``.  This file\nis test-only and deliberately exposes no runtime bridge API.\n"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, NoReturn

from android_bridge.anki_limits import ANKI_LIMITS_V1
from android_bridge.protocol import BridgeProtocolError, normalize_integral_json_number

ANKI_REQUEST_DIGEST_VERSION = 1
ANKI_REQUEST_DIGEST_DOMAIN = "com.ankiminer.android.anki.request"

_OPERATIONS = frozenset(
    {
        "verifyTarget",
        "scanFirstFields",
        "storeMedia",
        "createNotes",
        "releaseRunState",
    }
)


@dataclass(frozen=True)
class _ReferenceDigest:
    """Immutable canonical bytes and lowercase SHA-256 replay identity."""

    digest_version: int
    canonical_bytes: bytes
    sha256: str

    def __post_init__(self) -> None:
        if (
            self.digest_version != ANKI_REQUEST_DIGEST_VERSION
            or type(self.canonical_bytes) is not bytes
            or not re.fullmatch(r"[0-9a-f]{64}", self.sha256)
            or hashlib.sha256(self.canonical_bytes).hexdigest() != self.sha256
        ):
            raise ValueError("invalid Anki request digest value")

    @classmethod
    def compute(
        cls,
        operation: str,
        validated_request: Mapping[str, Any],
    ) -> _ReferenceDigest:
        """Digest an operation-validated request object, never raw JSON.

        Fixed caller-supplied ``limits`` members are deliberately absent from
        the identity: the strict operation validator has already checked them
        against protocol v1 and the typed Kotlin request does not retain them.
        This function still checks the exact wire shape before extracting the
        corresponding typed semantic fields, so it cannot silently digest an
        arbitrary or partially decoded object.
        """

        if operation not in _OPERATIONS:
            _invalid("unknown Anki request digest operation")
        request = _canonical_request(operation, validated_request)
        root = _Object(
            (
                ("domain", ANKI_REQUEST_DIGEST_DOMAIN),
                ("digestVersion", ANKI_REQUEST_DIGEST_VERSION),
                ("operation", operation),
                ("request", request),
            )
        )
        canonical = _encode(root)
        return cls(
            digest_version=ANKI_REQUEST_DIGEST_VERSION,
            canonical_bytes=canonical,
            sha256=hashlib.sha256(canonical).hexdigest(),
        )


@dataclass(frozen=True)
class _Object:
    fields: tuple[tuple[str, object], ...]


@dataclass(frozen=True)
class _SemanticMap:
    values: Mapping[str, str]


def _invalid(message: str) -> NoReturn:
    raise BridgeProtocolError("invalid_value", message)


def _mapping(value: object, expected: set[str], context: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or set(value) != expected:
        _invalid(f"{context} has missing or unknown fields")
    if any(not isinstance(key, str) for key in value):
        _invalid(f"{context} contains a non-string key")
    return value


def _string(value: object, context: str) -> str:
    if not isinstance(value, str):
        _invalid(f"{context} must be a string")
    try:
        value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as error:
        raise BridgeProtocolError("invalid_utf8", f"{context} contains an invalid Unicode scalar") from error
    return value


def _nullable_string(value: object, context: str) -> str | None:
    return None if value is None else _string(value, context)


def _integer(value: object, context: str) -> int:
    normalized = normalize_integral_json_number(value)
    if normalized is None:
        _invalid(f"{context} must be a signed-64 mathematical integer")
    return normalized


def _boolean(value: object, context: str) -> bool:
    if type(value) is not bool:
        _invalid(f"{context} must be a boolean")
    return value


def _constant_limits(
    value: object,
    expected: Mapping[str, int],
    context: str,
) -> None:
    limits = _mapping(value, set(expected), context)
    for name, expected_value in expected.items():
        if _integer(limits[name], f"{context}.{name}") != expected_value:
            _invalid(f"{context}.{name} does not match protocol v1")


def _list(value: object, context: str) -> Sequence[Any]:
    if not isinstance(value, list):
        _invalid(f"{context} must be an array")
    return value


def _string_list(value: object, context: str) -> list[str]:
    return [_string(item, f"{context}[{index}]") for index, item in enumerate(_list(value, context))]


def _canonical_request(
    operation: str,
    value: Mapping[str, Any],
) -> _Object:
    if operation == "verifyTarget":
        request = _mapping(
            value,
            {"runId", "requestId", "deckName", "modelName", "requiredFields"},
            "verifyTarget request",
        )
        return _Object(
            (
                ("runId", _string(request["runId"], "runId")),
                ("requestId", _string(request["requestId"], "requestId")),
                ("deckName", _string(request["deckName"], "deckName")),
                ("modelName", _string(request["modelName"], "modelName")),
                (
                    "requiredFields",
                    _string_list(request["requiredFields"], "requiredFields"),
                ),
            )
        )

    if operation == "scanFirstFields":
        request = _mapping(
            value,
            {"runId", "requestId", "scope"},
            "scanFirstFields request",
        )
        return _Object(
            (
                ("runId", _string(request["runId"], "runId")),
                ("requestId", _string(request["requestId"], "requestId")),
                ("scope", _scan_scope(request["scope"])),
            )
        )

    if operation == "storeMedia":
        request = _mapping(
            value,
            {"runId", "requestId", "assets", "limits"},
            "storeMedia request",
        )
        assets = [_media_asset(asset, index) for index, asset in enumerate(_list(request["assets"], "assets"))]
        media_limits = ANKI_LIMITS_V1["storeMedia"]
        _constant_limits(
            request["limits"],
            {
                "maxAssets": media_limits["maxAssets"],
                "maxAssetBytes": media_limits["maxAssetBytes"],
                "maxTotalBytes": media_limits["maxTotalBytes"],
            },
            "storeMedia limits",
        )
        return _Object(
            (
                ("runId", _string(request["runId"], "runId")),
                ("requestId", _string(request["requestId"], "requestId")),
                ("assets", assets),
            )
        )

    if operation == "createNotes":
        request = _mapping(
            value,
            {
                "runId",
                "requestId",
                "deckName",
                "modelName",
                "firstFieldName",
                "baselineToken",
                "duplicateScope",
                "limits",
                "notes",
            },
            "createNotes request",
        )
        notes = [_create_note(note, index) for index, note in enumerate(_list(request["notes"], "notes"))]
        note_limits = ANKI_LIMITS_V1["createNotes"]
        _constant_limits(
            request["limits"],
            {
                "maxNotes": note_limits["maxNotes"],
                "maxFieldsPerNote": note_limits["maxFieldsPerNote"],
                "maxCardsPerNote": note_limits["maxCardsPerNote"],
                "maxFieldNameUtf8Bytes": note_limits["fieldNameMaxUtf8Bytes"],
                "maxFieldValueUtf8Bytes": note_limits["fieldValueMaxUtf8Bytes"],
                "maxTagsPerNote": note_limits["maxTagsPerNote"],
                "maxTagUtf8Bytes": note_limits["tagMaxUtf8Bytes"],
                "maxTagsUtf8BytesPerNote": note_limits["tagsPerNoteMaxUtf8Bytes"],
                "maxNoteContentUtf8Bytes": note_limits["noteContentMaxUtf8Bytes"],
                "maxTotalContentUtf8Bytes": note_limits["callbackContentMaxUtf8Bytes"],
                "maxMediaBindingsPerNote": note_limits["maxMediaBindingsPerNote"],
                "maxMediaBindingsTotal": note_limits["maxMediaBindingsTotal"],
                "maxEnvelopeUtf8Bytes": note_limits["requestEnvelopeMaxUtf8Bytes"],
            },
            "createNotes limits",
        )
        return _Object(
            (
                ("runId", _string(request["runId"], "runId")),
                ("requestId", _string(request["requestId"], "requestId")),
                ("deckName", _string(request["deckName"], "deckName")),
                ("modelName", _string(request["modelName"], "modelName")),
                (
                    "firstFieldName",
                    _string(request["firstFieldName"], "firstFieldName"),
                ),
                (
                    "baselineToken",
                    _string(request["baselineToken"], "baselineToken"),
                ),
                ("duplicateScope", _create_duplicate_scope(request["duplicateScope"])),
                ("notes", notes),
            )
        )

    request = _mapping(
        value,
        {"runId", "requestId", "acknowledgeTerminalResponses"},
        "releaseRunState request",
    )
    return _Object(
        (
            ("runId", _string(request["runId"], "runId")),
            ("requestId", _string(request["requestId"], "requestId")),
            (
                "acknowledgeTerminalResponses",
                _boolean(
                    request["acknowledgeTerminalResponses"],
                    "acknowledgeTerminalResponses",
                ),
            ),
        )
    )


def _scan_scope(value: object) -> _Object:
    if not isinstance(value, Mapping):
        _invalid("scan scope must be an object")
    kind = value.get("kind")
    if kind == "knownVocabulary":
        scope = _mapping(
            value,
            {"kind", "excludedDecks", "cursor", "limits"},
            "known-vocabulary scope",
        )
        cursor_value = scope["cursor"]
        scan_limits = ANKI_LIMITS_V1["scanFirstFields"]
        _constant_limits(
            scope["limits"],
            {
                "maxScannedNotes": scan_limits["knownPageMaxItems"],
                "maxTotalScannedNotes": scan_limits["knownTotalScannedNotes"],
                "maxItems": scan_limits["knownPageMaxItems"],
                "maxItemUtf8Bytes": scan_limits["firstFieldMaxUtf8Bytes"],
                "maxTotalUtf8Bytes": scan_limits["knownPageMaxUtf8Bytes"],
            },
            "known-vocabulary limits",
        )
        cursor: _Object | None
        if cursor_value is None:
            cursor = None
        else:
            raw_cursor = _mapping(
                cursor_value,
                {"ordinal", "token"},
                "known-vocabulary cursor",
            )
            cursor = _Object(
                (
                    ("ordinal", _integer(raw_cursor["ordinal"], "cursor ordinal")),
                    ("token", _string(raw_cursor["token"], "cursor token")),
                )
            )
        return _Object(
            (
                ("kind", "knownVocabulary"),
                ("excludedDecks", _string_list(scope["excludedDecks"], "excludedDecks")),
                ("cursor", cursor),
            )
        )
    if kind == "duplicates":
        scope = _mapping(
            value,
            {
                "kind",
                "modelName",
                "firstFieldName",
                "candidates",
                "occurrences",
                "invalidateBaselineToken",
                "limits",
            },
            "duplicate scan scope",
        )
        candidates = [
            _duplicate_candidate(candidate, index)
            for index, candidate in enumerate(_list(scope["candidates"], "duplicate candidates"))
        ]
        scan_limits = ANKI_LIMITS_V1["scanFirstFields"]
        _constant_limits(
            scope["limits"],
            {
                "maxHitsPerCandidate": scan_limits["duplicateHitsPerCandidateMaxItems"],
                "maxTotalHits": scan_limits["duplicateHitsTotalMaxItems"],
                "maxItemUtf8Bytes": scan_limits["firstFieldMaxUtf8Bytes"],
                "maxTotalUtf8Bytes": scan_limits["duplicateHitsTotalMaxUtf8Bytes"],
            },
            "duplicate scan limits",
        )
        occurrences = [
            _integer(item, f"occurrences[{index}]")
            for index, item in enumerate(_list(scope["occurrences"], "occurrences"))
        ]
        return _Object(
            (
                ("kind", "duplicates"),
                ("modelName", _string(scope["modelName"], "modelName")),
                (
                    "firstFieldName",
                    _string(scope["firstFieldName"], "firstFieldName"),
                ),
                ("candidates", candidates),
                ("occurrences", occurrences),
                (
                    "invalidateBaselineToken",
                    _nullable_string(
                        scope["invalidateBaselineToken"],
                        "invalidateBaselineToken",
                    ),
                ),
            )
        )
    _invalid("scan scope kind is invalid")


def _duplicate_candidate(value: object, index: int) -> _Object:
    candidate = _mapping(
        value,
        {"key", "firstField"},
        f"candidates[{index}]",
    )
    return _Object(
        (
            ("key", _string(candidate["key"], "duplicate key")),
            (
                "firstField",
                _string(candidate["firstField"], "duplicate firstField"),
            ),
        )
    )


def _media_asset(value: object, index: int) -> _Object:
    asset = _mapping(
        value,
        {
            "assetId",
            "sourcePath",
            "preferredName",
            "requestedFilename",
            "purpose",
            "mediaKind",
            "expectedSizeBytes",
            "expectedSha256",
        },
        f"assets[{index}]",
    )
    purpose = _string(asset["purpose"], "purpose")
    media_kind = _string(asset["mediaKind"], "mediaKind")
    if purpose not in {"card", "dictionary"}:
        _invalid("media purpose is invalid")
    if media_kind not in {"audio", "image"}:
        _invalid("media kind is invalid")
    return _Object(
        (
            ("assetId", _string(asset["assetId"], "assetId")),
            ("sourcePath", _string(asset["sourcePath"], "sourcePath")),
            (
                "preferredName",
                _string(asset["preferredName"], "preferredName"),
            ),
            (
                "requestedFilename",
                _string(asset["requestedFilename"], "requestedFilename"),
            ),
            ("purpose", purpose),
            ("mediaKind", media_kind),
            (
                "expectedSizeBytes",
                _integer(asset["expectedSizeBytes"], "expectedSizeBytes"),
            ),
            (
                "expectedSha256",
                _string(asset["expectedSha256"], "expectedSha256"),
            ),
        )
    )


def _create_duplicate_scope(value: object) -> _Object:
    if not isinstance(value, Mapping):
        _invalid("create duplicate scope must be an object")
    kind = value.get("kind")
    if kind == "collection":
        scope = _mapping(value, {"kind", "limits"}, "collection duplicate scope")
        _validate_create_duplicate_limits(scope["limits"])
        return _Object((("kind", "collection"),))
    _invalid("create duplicate scope kind is invalid")


def _validate_create_duplicate_limits(value: object) -> None:
    scan_limits = ANKI_LIMITS_V1["scanFirstFields"]
    _constant_limits(
        value,
        {
            "maxNoteIdsPerCandidate": scan_limits["duplicateHitsPerCandidateMaxItems"],
            "maxTotalNoteIds": scan_limits["duplicateHitsTotalMaxItems"],
        },
        "create duplicate limits",
    )


def _create_note(value: object, index: int) -> _Object:
    note = _mapping(
        value,
        {"clientNoteId", "fields", "tags", "duplicateCandidate", "mediaBindings"},
        f"notes[{index}]",
    )
    fields = note["fields"]
    if not isinstance(fields, Mapping):
        _invalid(f"notes[{index}].fields must be an object")
    normalized_fields: dict[str, str] = {}
    for key, field_value in fields.items():
        normalized_key = _string(key, f"notes[{index}].fields key")
        normalized_fields[normalized_key] = _string(field_value, f"notes[{index}].fields[{normalized_key!r}]")
    candidate = _mapping(
        note["duplicateCandidate"],
        {"key", "firstField", "occurrence"},
        f"notes[{index}].duplicateCandidate",
    )
    bindings = [
        _media_binding(binding, binding_index)
        for binding_index, binding in enumerate(_list(note["mediaBindings"], f"notes[{index}].mediaBindings"))
    ]
    return _Object(
        (
            ("clientNoteId", _string(note["clientNoteId"], "clientNoteId")),
            ("fields", _SemanticMap(normalized_fields)),
            ("tags", _string_list(note["tags"], f"notes[{index}].tags")),
            (
                "duplicateCandidate",
                _Object(
                    (
                        ("key", _string(candidate["key"], "duplicate key")),
                        (
                            "firstField",
                            _string(candidate["firstField"], "duplicate firstField"),
                        ),
                        (
                            "occurrence",
                            _integer(candidate["occurrence"], "duplicate occurrence"),
                        ),
                    )
                ),
            ),
            ("mediaBindings", bindings),
        )
    )


def _media_binding(value: object, index: int) -> _Object:
    binding = _mapping(
        value,
        {"assetId", "actualFilename"},
        f"mediaBindings[{index}]",
    )
    return _Object(
        (
            ("assetId", _string(binding["assetId"], "binding assetId")),
            (
                "actualFilename",
                _string(binding["actualFilename"], "binding actualFilename"),
            ),
        )
    )


def _encode(value: object) -> bytes:
    output = bytearray()
    _write(value, output)
    return bytes(output)


def _write(value: object, output: bytearray) -> None:
    if isinstance(value, _Object):
        output.extend(b"{")
        for index, (name, field_value) in enumerate(value.fields):
            if index:
                output.extend(b",")
            _write_string(name, output)
            output.extend(b":")
            _write(field_value, output)
        output.extend(b"}")
        return
    if isinstance(value, _SemanticMap):
        output.extend(b"{")
        for index, key in enumerate(sorted(value.values, key=lambda item: item.encode("utf-8", errors="strict"))):
            if index:
                output.extend(b",")
            _write_string(key, output)
            output.extend(b":")
            _write_string(value.values[key], output)
        output.extend(b"}")
        return
    if isinstance(value, str):
        _write_string(value, output)
        return
    if value is None:
        output.extend(b"null")
        return
    if type(value) is bool:
        output.extend(b"true" if value else b"false")
        return
    if type(value) is int:
        normalized = normalize_integral_json_number(value)
        if normalized is None:
            _invalid("canonical integer is outside the signed-64 domain")
        output.extend(str(normalized).encode("ascii"))
        return
    if isinstance(value, list):
        output.extend(b"[")
        for index, item in enumerate(value):
            if index:
                output.extend(b",")
            _write(item, output)
        output.extend(b"]")
        return
    _invalid(f"unsupported canonical request value: {type(value).__name__}")


def _write_string(value: str, output: bytearray) -> None:
    raw = _string(value, "canonical request string").encode("utf-8")
    output.extend(b'"')
    for byte in raw:
        if byte == 0x22:
            output.extend(b'\\"')
        elif byte == 0x5C:
            output.extend(b"\\\\")
        elif byte <= 0x1F:
            output.extend(f"\\u00{byte:02x}".encode("ascii"))
        else:
            output.append(byte)
    output.extend(b'"')


def _compute_prevalidated_request_digest(
    operation: str,
    payload: Mapping[str, Any],
) -> tuple[int, bytes, str]:
    """Return version, canonical bytes, and hash for a prevalidated request."""

    digest = _ReferenceDigest.compute(operation, payload)
    return digest.digest_version, digest.canonical_bytes, digest.sha256
