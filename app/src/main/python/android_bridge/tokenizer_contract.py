"""Backend-neutral tokenizer records and the Android native wire contract.

The Android port has two tokenizer candidates, but the vendored engine must see
one fugashi-shaped object model.  Backends copy their native data into
``TokenRecord`` instances; this module validates those copies and creates the
mutable ``SimpleNamespace`` feature objects used by the engine.

No Java, fugashi, or engine module is imported here.  Bootstrap and backend
selection remain separate concerns.
"""

from __future__ import annotations

import csv
import struct
from collections.abc import Sequence
from dataclasses import dataclass
from types import SimpleNamespace
from typing import Protocol

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

TOKEN_WIRE_MAGIC = b"AMTK"
TOKEN_WIRE_VERSION = 1
TOKEN_WIRE_HEADER_FORMAT = "<4sHHII"
TOKEN_WIRE_RECORD_FORMAT = "<IIIIIB3sI"
TOKEN_WIRE_HEADER_SIZE = struct.calcsize(TOKEN_WIRE_HEADER_FORMAT)
TOKEN_WIRE_RECORD_SIZE = struct.calcsize(TOKEN_WIRE_RECORD_FORMAT)

MECAB_NORMAL_NODE = 0
MECAB_UNKNOWN_NODE = 1

_UINT8_MAX = (1 << 8) - 1
_UINT16_MAX = (1 << 16) - 1
_UINT32_MAX = (1 << 32) - 1
_MAX_FEATURE_BYTES = 1 << 20
_HEADER = struct.Struct(TOKEN_WIRE_HEADER_FORMAT)
_RECORD = struct.Struct(TOKEN_WIRE_RECORD_FORMAT)


class TokenizerContractError(ValueError):
    """A stable tokenizer contract violation.

    ``code`` is intended for tests and boundary translation.  Human-readable
    text may become more specific without changing the contract.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True, slots=True)
class TokenRecord:
    """A complete copy of one non-BOS/EOS MeCab node."""

    byte_start: int
    byte_end: int
    raw_length: int
    pos_id: int
    char_type: int
    status: int
    features: tuple[str | None, ...]


class TokenizerBackend(Protocol):
    """Backend seam implemented by either the fugashi or JNI candidate."""

    def tokenize(self, text: str) -> Sequence[TokenRecord]:
        """Copy one parse into backend-independent records."""


class Utf8OffsetMap:
    """Map UTF-8 byte boundaries to Python and JVM string offsets."""

    __slots__ = ("_boundaries", "_encoded", "_text")

    def __init__(self, text: str) -> None:
        if not isinstance(text, str):
            raise TokenizerContractError("invalid_text", "Tokenizer text must be str")

        encoded = bytearray()
        boundaries: dict[int, tuple[int, int]] = {0: (0, 0)}
        utf16_offset = 0
        for codepoint_offset, character in enumerate(text, start=1):
            value = ord(character)
            if 0xD800 <= value <= 0xDFFF:
                raise TokenizerContractError(
                    "invalid_text_utf8", "Tokenizer text contains a lone surrogate"
                )
            encoded.extend(character.encode("utf-8"))
            utf16_offset += 2 if value > 0xFFFF else 1
            boundaries[len(encoded)] = (codepoint_offset, utf16_offset)

        self._text = text
        self._encoded = bytes(encoded)
        self._boundaries = boundaries

    @property
    def text(self) -> str:
        return self._text

    @property
    def encoded(self) -> bytes:
        return self._encoded

    @property
    def byte_length(self) -> int:
        return len(self._encoded)

    def resolve(self, byte_offset: int) -> tuple[int, int]:
        """Return ``(codepoint_offset, utf16_offset)`` for a byte boundary."""

        if type(byte_offset) is not int:
            raise TokenizerContractError(
                "invalid_token_offset", "UTF-8 byte offsets must be integers"
            )
        try:
            return self._boundaries[byte_offset]
        except KeyError as exc:
            if byte_offset < 0 or byte_offset > self.byte_length:
                detail = "outside the encoded input"
            else:
                detail = "inside a UTF-8 code point"
            raise TokenizerContractError(
                "invalid_token_offset", f"Byte offset {byte_offset} is {detail}"
            ) from exc

    def decode_slice(self, byte_start: int, byte_end: int) -> str:
        """Decode a slice whose endpoints must both be UTF-8 boundaries."""

        self.resolve(byte_start)
        self.resolve(byte_end)
        if byte_start > byte_end:
            raise TokenizerContractError(
                "invalid_token_offset", "Token byte range is reversed"
            )
        return self._encoded[byte_start:byte_end].decode("utf-8")


def decode_mecab_feature_csv(raw: str) -> tuple[str | None, ...]:
    """Decode one MeCab feature row using fugashi's 26-field semantics.

    Explicit empty strings and UniDic's literal ``*`` sentinel are data and are
    preserved.  Only fields absent from the end of a short row are padded with
    ``None``.
    """

    if not isinstance(raw, str):
        raise TokenizerContractError(
            "invalid_feature_csv", "MeCab features must be a UTF-8 string"
        )
    if "\x00" in raw or "\r" in raw or "\n" in raw:
        raise TokenizerContractError(
            "invalid_feature_csv", "MeCab features must be one NUL-free CSV row"
        )

    try:
        fields = next(csv.reader([raw], strict=True)) if '"' in raw else raw.split(",")
    except (csv.Error, StopIteration) as exc:
        raise TokenizerContractError(
            "invalid_feature_csv", "Malformed MeCab feature CSV"
        ) from exc

    expected = len(UNIDIC_FEATURE_FIELDS)
    if len(fields) > expected:
        raise TokenizerContractError(
            "unsupported_feature_schema",
            f"UniDic row has {len(fields)} fields; expected at most {expected}",
        )
    return tuple(fields) + (None,) * (expected - len(fields))


def _require_uint(name: str, value: object, maximum: int) -> int:
    if type(value) is not int or value < 0 or value > maximum:
        raise TokenizerContractError(
            "invalid_token_record", f"{name} is outside its unsigned wire domain"
        )
    return value


def _validate_features(features: object) -> tuple[str | None, ...]:
    if not isinstance(features, tuple) or len(features) != len(
        UNIDIC_FEATURE_FIELDS
    ):
        raise TokenizerContractError(
            "invalid_token_record", "Token features must be the frozen 26-field tuple"
        )
    if any(
        value is not None
        and (not isinstance(value, str) or "\x00" in value)
        for value in features
    ):
        raise TokenizerContractError(
            "invalid_token_record", "Token feature values must be NUL-free strings or None"
        )
    return features


def validate_token_records(
    text: str, records: Sequence[TokenRecord]
) -> Utf8OffsetMap:
    """Validate copied nodes against the original input and return its map."""

    offsets = Utf8OffsetMap(text)
    if not isinstance(records, Sequence) or isinstance(
        records, (str, bytes, bytearray)
    ):
        raise TokenizerContractError(
            "invalid_token_record", "Tokenizer records must be a sequence"
        )
    if len(records) > offsets.byte_length:
        raise TokenizerContractError(
            "invalid_token_record", "There cannot be more tokens than input bytes"
        )

    previous_end = 0
    for index, record in enumerate(records):
        if not isinstance(record, TokenRecord):
            raise TokenizerContractError(
                "invalid_token_record", f"Record {index} is not a TokenRecord"
            )

        byte_start = _require_uint("byte_start", record.byte_start, _UINT32_MAX)
        byte_end = _require_uint("byte_end", record.byte_end, _UINT32_MAX)
        raw_length = _require_uint("raw_length", record.raw_length, _UINT32_MAX)
        _require_uint("pos_id", record.pos_id, _UINT16_MAX)
        _require_uint("char_type", record.char_type, _UINT8_MAX)
        _require_uint("status", record.status, _UINT8_MAX)
        _validate_features(record.features)

        if record.status not in (MECAB_NORMAL_NODE, MECAB_UNKNOWN_NODE):
            raise TokenizerContractError(
                "invalid_token_record", "Only normal and unknown nodes may cross the wire"
            )
        if byte_start >= byte_end or byte_end > offsets.byte_length:
            raise TokenizerContractError(
                "invalid_token_offset", f"Record {index} has an invalid byte range"
            )
        if byte_start < previous_end:
            raise TokenizerContractError(
                "invalid_token_offset", f"Record {index} overlaps its predecessor"
            )

        offsets.resolve(byte_start)
        offsets.resolve(byte_end)
        whitespace = offsets.decode_slice(previous_end, byte_start)
        if whitespace and not whitespace.isspace():
            raise TokenizerContractError(
                "invalid_token_coverage",
                f"Record {index} skips non-whitespace input",
            )
        expected_raw_length = byte_end - previous_end
        if raw_length != expected_raw_length:
            raise TokenizerContractError(
                "invalid_token_record",
                f"Record {index} raw_length does not include exactly its leading whitespace",
            )
        previous_end = byte_end

    trailing = offsets.decode_slice(previous_end, offsets.byte_length)
    if trailing and not trailing.isspace():
        raise TokenizerContractError(
            "invalid_token_coverage", "Tokenizer omitted trailing non-whitespace input"
        )
    return offsets


def decode_token_wire(payload: object, text: str) -> tuple[TokenRecord, ...]:
    """Decode and validate a version-1 JNI token buffer.

    The decoder accepts bytes-like values only.  All arithmetic is bounded by
    the actual buffer before allocation or slicing, and trailing bytes are
    rejected so native and Python cannot silently disagree on framing.
    """

    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise TokenizerContractError(
            "invalid_token_wire", "Native tokenizer payload must be bytes-like"
        )
    data = bytes(payload)
    offsets = Utf8OffsetMap(text)
    if offsets.byte_length > _UINT32_MAX:
        raise TokenizerContractError(
            "invalid_text", "Tokenizer input exceeds the v1 wire size domain"
        )
    if len(data) < _HEADER.size:
        raise TokenizerContractError(
            "invalid_token_wire", "Native tokenizer payload has no complete header"
        )

    magic, version, flags, input_length, token_count = _HEADER.unpack_from(data)
    if magic != TOKEN_WIRE_MAGIC:
        raise TokenizerContractError(
            "invalid_token_wire", "Native tokenizer payload has the wrong magic"
        )
    if version != TOKEN_WIRE_VERSION:
        raise TokenizerContractError(
            "unsupported_token_wire_version",
            f"Expected token wire {TOKEN_WIRE_VERSION}, received {version}",
        )
    if flags != 0:
        raise TokenizerContractError(
            "invalid_token_wire", "Native tokenizer payload uses unknown flags"
        )
    if input_length != offsets.byte_length:
        raise TokenizerContractError(
            "invalid_token_wire", "Native tokenizer input length does not match Python"
        )

    remaining = len(data) - _HEADER.size
    if token_count > input_length or token_count > remaining // _RECORD.size:
        raise TokenizerContractError(
            "invalid_token_wire", "Native tokenizer token count exceeds its buffer"
        )

    cursor = _HEADER.size
    records: list[TokenRecord] = []
    for index in range(token_count):
        if len(data) - cursor < _RECORD.size:
            raise TokenizerContractError(
                "invalid_token_wire", f"Token record {index} is truncated"
            )
        (
            byte_start,
            byte_end,
            raw_length,
            pos_id,
            char_type,
            status,
            reserved,
            feature_length,
        ) = _RECORD.unpack_from(data, cursor)
        cursor += _RECORD.size

        if reserved != b"\x00\x00\x00":
            raise TokenizerContractError(
                "invalid_token_wire", f"Token record {index} has nonzero reserved bytes"
            )
        if feature_length > _MAX_FEATURE_BYTES:
            raise TokenizerContractError(
                "invalid_token_wire", f"Token record {index} feature row is too large"
            )
        if feature_length > len(data) - cursor:
            raise TokenizerContractError(
                "invalid_token_wire", f"Token record {index} feature row is truncated"
            )

        raw_feature = data[cursor : cursor + feature_length]
        cursor += feature_length
        try:
            feature_csv = raw_feature.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise TokenizerContractError(
                "invalid_token_wire", f"Token record {index} feature row is not UTF-8"
            ) from exc
        records.append(
            TokenRecord(
                byte_start=byte_start,
                byte_end=byte_end,
                raw_length=raw_length,
                pos_id=pos_id,
                char_type=char_type,
                status=status,
                features=decode_mecab_feature_csv(feature_csv),
            )
        )

    if cursor != len(data):
        raise TokenizerContractError(
            "invalid_token_wire", "Native tokenizer payload has trailing bytes"
        )
    validate_token_records(text, records)
    return tuple(records)


def adapt_tokens(text: str, records: Sequence[TokenRecord]) -> list[SimpleNamespace]:
    """Create copied fugashi-shaped tokens from validated backend records."""

    offsets = validate_token_records(text, records)
    output: list[SimpleNamespace] = []
    previous_end = 0
    for record in records:
        codepoint_start, utf16_start = offsets.resolve(record.byte_start)
        codepoint_end, utf16_end = offsets.resolve(record.byte_end)
        feature = SimpleNamespace(
            **dict(zip(UNIDIC_FEATURE_FIELDS, record.features, strict=True))
        )
        output.append(
            SimpleNamespace(
                surface=offsets.decode_slice(record.byte_start, record.byte_end),
                feature=feature,
                length=record.byte_end - record.byte_start,
                rlength=record.raw_length,
                posid=record.pos_id,
                char_type=record.char_type,
                stat=record.status,
                is_unk=record.status == MECAB_UNKNOWN_NODE,
                white_space=offsets.decode_slice(previous_end, record.byte_start),
                byte_start=record.byte_start,
                byte_end=record.byte_end,
                codepoint_start=codepoint_start,
                codepoint_end=codepoint_end,
                utf16_start=utf16_start,
                utf16_end=utf16_end,
            )
        )
        previous_end = record.byte_end
    return output


class TaggerAdapter:
    """Minimal callable tagger over a copied-record tokenizer backend."""

    __slots__ = ("_backend",)

    def __init__(self, backend: TokenizerBackend) -> None:
        self._backend = backend

    def __call__(self, text: str) -> list[SimpleNamespace]:
        if not isinstance(text, str):
            raise TokenizerContractError("invalid_text", "Tokenizer text must be str")
        records = tuple(self._backend.tokenize(text))
        return adapt_tokens(text, records)
