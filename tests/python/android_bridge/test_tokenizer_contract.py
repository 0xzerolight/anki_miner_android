from __future__ import annotations

import ast
import json
import struct
from pathlib import Path

import pytest

import android_bridge.tokenizer_contract as tokenizer_contract
from android_bridge.tokenizer_contract import (
    MECAB_NORMAL_NODE,
    MECAB_UNKNOWN_NODE,
    TOKEN_WIRE_HEADER_FORMAT,
    TOKEN_WIRE_MAGIC,
    TOKEN_WIRE_RECORD_FORMAT,
    TOKEN_WIRE_VERSION,
    UNIDIC_FEATURE_FIELDS,
    TaggerAdapter,
    TokenRecord,
    TokenizerContractError,
    Utf8OffsetMap,
    adapt_tokens,
    decode_mecab_feature_csv,
    decode_token_wire,
    validate_token_records,
)

PROJECT_ROOT = Path(__file__).resolve().parents[3]


def _features(prefix: str = "field") -> tuple[str | None, ...]:
    return tuple(f"{prefix}-{index}" for index in range(26))


def _record(
    byte_start: int,
    byte_end: int,
    *,
    raw_length: int | None = None,
    status: int = MECAB_NORMAL_NODE,
    features: tuple[str | None, ...] | None = None,
) -> TokenRecord:
    return TokenRecord(
        byte_start=byte_start,
        byte_end=byte_end,
        raw_length=byte_end - byte_start if raw_length is None else raw_length,
        pos_id=3,
        char_type=4,
        status=status,
        features=features or _features(),
    )


def _wire(
    text: str,
    records: list[tuple[int, int, int, int, str]],
    *,
    magic: bytes = TOKEN_WIRE_MAGIC,
    version: int = TOKEN_WIRE_VERSION,
    flags: int = 0,
    input_length: int | None = None,
    token_count: int | None = None,
    reserved: bytes = b"\x00\x00\x00",
) -> bytes:
    encoded = text.encode("utf-8")
    output = bytearray(
        struct.pack(
            TOKEN_WIRE_HEADER_FORMAT,
            magic,
            version,
            flags,
            len(encoded) if input_length is None else input_length,
            len(records) if token_count is None else token_count,
        )
    )
    for byte_start, byte_end, raw_length, status, feature_csv in records:
        feature_bytes = feature_csv.encode("utf-8")
        output.extend(
            struct.pack(
                TOKEN_WIRE_RECORD_FORMAT,
                byte_start,
                byte_end,
                raw_length,
                3,
                4,
                status,
                reserved,
                len(feature_bytes),
            )
        )
        output.extend(feature_bytes)
    return bytes(output)


def test_feature_schema_is_the_checked_in_golden_schema() -> None:
    schema = json.loads(
        (PROJECT_ROOT / "golden/schema/engine-goldens-v1.schema.json").read_text(
            encoding="utf-8"
        )
    )
    prefix = schema["properties"]["unidic_feature_fields"]["prefixItems"]

    assert len(UNIDIC_FEATURE_FIELDS) == 26
    assert tuple(item["const"] for item in prefix) == UNIDIC_FEATURE_FIELDS


def test_feature_csv_preserves_sentinels_and_only_pads_absent_fields() -> None:
    values = decode_mecab_feature_csv('名詞,"副詞,可能",*,,語彙')

    assert values[:5] == ("名詞", "副詞,可能", "*", "", "語彙")
    assert values[5:] == (None,) * 21


def test_feature_csv_with_26_fields_is_not_modified() -> None:
    raw = ",".join(f"value-{index}" for index in range(26))

    assert decode_mecab_feature_csv(raw) == tuple(raw.split(","))


@pytest.mark.parametrize(
    "raw",
    [
        '名詞,"unterminated',
        "名詞\n動詞",
        "名詞\x00動詞",
        ",".join(str(index) for index in range(27)),
    ],
)
def test_feature_csv_rejects_malformed_or_unknown_schemas(raw: str) -> None:
    with pytest.raises(TokenizerContractError):
        decode_mecab_feature_csv(raw)


def test_utf8_offsets_map_astral_codepoints_to_python_and_jvm_offsets() -> None:
    offsets = Utf8OffsetMap("猫𠮟𠮟𠮟犬")

    assert offsets.byte_length == 18
    assert offsets.resolve(3) == (1, 1)
    assert offsets.resolve(15) == (4, 7)
    assert offsets.decode_slice(3, 15) == "𠮟𠮟𠮟"

    with pytest.raises(TokenizerContractError, match="inside a UTF-8 code point"):
        offsets.resolve(4)


def test_utf8_offsets_reject_lone_surrogates_and_non_integer_offsets() -> None:
    with pytest.raises(TokenizerContractError) as surrogate:
        Utf8OffsetMap("\ud800")
    assert surrogate.value.code == "invalid_text_utf8"

    with pytest.raises(TokenizerContractError) as boolean:
        Utf8OffsetMap("猫").resolve(True)
    assert boolean.value.code == "invalid_token_offset"


def test_wire_decoder_and_adapter_cover_astral_unknown_node() -> None:
    text = "猫𠮟𠮟𠮟犬"
    payload = _wire(
        text,
        [
            (0, 3, 3, MECAB_NORMAL_NODE, "名詞,普通名詞,一般,*,*,*,ネコ"),
            (3, 15, 12, MECAB_UNKNOWN_NODE, "補助記号,一般,*,*"),
            (15, 18, 3, MECAB_NORMAL_NODE, "名詞,普通名詞,一般,*,*,*,イヌ"),
        ],
    )

    records = decode_token_wire(memoryview(payload), text)
    tokens = adapt_tokens(text, records)

    assert len(records) == 3
    assert tokens[1].surface == "𠮟𠮟𠮟"
    assert tokens[1].is_unk is True
    assert (tokens[1].byte_start, tokens[1].byte_end) == (3, 15)
    assert (tokens[1].codepoint_start, tokens[1].codepoint_end) == (1, 4)
    assert (tokens[1].utf16_start, tokens[1].utf16_end) == (1, 7)
    assert records[1].features[2] == "*"
    assert tokens[1].feature.pos3 == "*"
    assert tokens[1].feature.lForm is None


def test_adapter_distinguishes_explicit_stars_from_absent_trailing_fields() -> None:
    features = ("名詞", "", "*", *(None for _ in range(23)))
    records = (_record(0, 3, features=features),)

    token = adapt_tokens("猫", records)[0]

    assert records[0].features[:3] == ("名詞", "", "*")
    assert token.feature.pos1 == "名詞"
    assert token.feature.pos2 == ""
    assert token.feature.pos3 == "*"
    assert token.feature.pos4 is None


def test_adapter_recreates_fugashi_whitespace_and_mutable_feature_shape() -> None:
    text = " 猫  犬 "
    records = (
        _record(1, 4, raw_length=4, features=_features("cat")),
        _record(6, 9, raw_length=5, features=_features("dog")),
    )

    tokens = adapt_tokens(text, records)

    assert [token.surface for token in tokens] == ["猫", "犬"]
    assert [token.white_space for token in tokens] == [" ", "  "]
    assert [(token.length, token.rlength) for token in tokens] == [(3, 4), (3, 5)]
    assert tokens[0].posid == 3
    assert tokens[0].char_type == 4
    tokens[0].feature.kana = "ネコ"
    assert tokens[0].feature.kana == "ネコ"


def test_empty_and_whitespace_only_inputs_may_have_no_nodes() -> None:
    assert adapt_tokens("", ()) == []
    assert adapt_tokens(" \t\n", ()) == []


@pytest.mark.parametrize(
    ("text", "records", "code"),
    [
        ("猫犬", (_record(0, 3),), "invalid_token_coverage"),
        ("猫犬", (_record(0, 3), _record(2, 6)), "invalid_token_offset"),
        ("𠮟", (_record(1, 4),), "invalid_token_offset"),
        (" 猫", (_record(1, 4, raw_length=3),), "invalid_token_record"),
        ("猫", (_record(0, 3, status=2),), "invalid_token_record"),
        ("猫", (_record(0, 3, status=False),), "invalid_token_record"),
    ],
)
def test_record_validation_rejects_bad_coverage_and_metadata(
    text: str, records: tuple[TokenRecord, ...], code: str
) -> None:
    with pytest.raises(TokenizerContractError) as error:
        validate_token_records(text, records)
    assert error.value.code == code


@pytest.mark.parametrize(
    ("payload", "text", "code"),
    [
        (b"short", "猫", "invalid_token_wire"),
        (_wire("猫", [], magic=b"NOPE"), "猫", "invalid_token_wire"),
        (
            _wire("猫", [], version=TOKEN_WIRE_VERSION + 1),
            "猫",
            "unsupported_token_wire_version",
        ),
        (_wire("猫", [], flags=1), "猫", "invalid_token_wire"),
        (_wire("猫", [], input_length=2), "猫", "invalid_token_wire"),
        (_wire("猫", [], token_count=(1 << 32) - 1), "猫", "invalid_token_wire"),
        (_wire("猫", []) + b"extra", "猫", "invalid_token_wire"),
        (
            _wire("猫", [(0, 3, 3, 0, "名詞")], reserved=b"\x00\x01\x00"),
            "猫",
            "invalid_token_wire",
        ),
    ],
)
def test_wire_decoder_rejects_bad_framing(
    payload: bytes, text: str, code: str
) -> None:
    with pytest.raises(TokenizerContractError) as error:
        decode_token_wire(payload, text)
    assert error.value.code == code


def test_wire_decoder_rejects_feature_length_overflow_before_slicing() -> None:
    payload = bytearray(
        struct.pack(TOKEN_WIRE_HEADER_FORMAT, TOKEN_WIRE_MAGIC, 1, 0, 3, 1)
    )
    payload.extend(
        struct.pack(
            TOKEN_WIRE_RECORD_FORMAT,
            0,
            3,
            3,
            1,
            1,
            0,
            b"\x00\x00\x00",
            (1 << 20) + 1,
        )
    )

    with pytest.raises(TokenizerContractError, match="too large"):
        decode_token_wire(payload, "猫")


def test_wire_decoder_rejects_truncated_and_non_utf8_features() -> None:
    header = struct.pack(TOKEN_WIRE_HEADER_FORMAT, TOKEN_WIRE_MAGIC, 1, 0, 3, 1)
    truncated_record = struct.pack(
        TOKEN_WIRE_RECORD_FORMAT,
        0,
        3,
        3,
        1,
        1,
        0,
        b"\x00\x00\x00",
        4,
    )

    with pytest.raises(TokenizerContractError, match="truncated"):
        decode_token_wire(header + truncated_record + b"x", "猫")

    invalid_utf8_record = truncated_record[:-4] + struct.pack("<I", 1)
    with pytest.raises(TokenizerContractError, match="not UTF-8"):
        decode_token_wire(header + invalid_utf8_record + b"\xff", "猫")


def test_tagger_adapter_materializes_backend_records_before_adapting() -> None:
    class FakeBackend:
        def __init__(self) -> None:
            self.calls: list[str] = []

        def tokenize(self, text: str) -> list[TokenRecord]:
            self.calls.append(text)
            return [_record(0, len(text.encode("utf-8")))]

    backend = FakeBackend()
    tagger = TaggerAdapter(backend)

    assert [token.surface for token in tagger("日本")] == ["日本"]
    assert backend.calls == ["日本"]


def test_contract_module_has_no_backend_or_engine_imports() -> None:
    tree = ast.parse(Path(tokenizer_contract.__file__).read_text(encoding="utf-8"))
    imported_roots = {
        alias.name.split(".", 1)[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.Import)
        for alias in node.names
    }
    imported_roots.update(
        node.module.split(".", 1)[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom) and node.module
    )

    assert imported_roots.isdisjoint({"anki_miner", "fugashi", "java", "com"})
