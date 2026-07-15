from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

import pytest

import android_bridge.protocol as protocol

from android_bridge.protocol import (
    BRIDGE_SCHEMA_VERSION,
    JSON_INTEGER_MAX,
    JSON_INTEGER_MIN,
    BridgeProtocolError,
    decode_message,
    encode_message,
    to_json_value,
)


class Verdict(Enum):
    OK = "ok"


@dataclass
class Result:
    card_ids: list[int]
    output_path: Path
    verdict: Verdict


def test_round_trip_is_versioned_canonical_and_unicode_safe() -> None:
    raw = encode_message("mining.result", {"text": "日本語", "count": 2})

    assert raw == (
        '{"schemaVersion":1,"type":"mining.result",'
        '"payload":{"text":"日本語","count":2}}'
    )
    assert decode_message(raw, expected_type="mining.result") == {
        "text": "日本語",
        "count": 2,
    }


def test_structural_conversion_needs_no_engine_import() -> None:
    value = Result(card_ids=[3, 5], output_path=Path("/tmp/out"), verdict=Verdict.OK)

    assert to_json_value(value) == {
        "cardIds": [3, 5],
        "outputPath": "/tmp/out",
        "verdict": "ok",
    }


@pytest.mark.parametrize(
    ("raw", "code"),
    [
        ("[]", "invalid_envelope"),
        (
            '{"schemaVersion":2,"type":"job.cancel","payload":{}}',
            "unsupported_schema_version",
        ),
        (
            '{"schemaVersion":1,"type":"job.cancel","payload":{},"extra":1}',
            "invalid_envelope",
        ),
        ('{"schemaVersion":1,"type":"JobCancel","payload":{}}', "invalid_message_type"),
        ('{"schemaVersion":1,"type":"job.cancel","payload":[]}', "invalid_payload"),
    ],
)
def test_malformed_envelopes_have_stable_error_codes(raw: str, code: str) -> None:
    with pytest.raises(BridgeProtocolError) as caught:
        decode_message(raw)
    assert caught.value.code == code


def test_expected_type_is_enforced() -> None:
    raw = encode_message("job.cancel", {"runId": "opaque"})

    with pytest.raises(BridgeProtocolError, match="Expected") as caught:
        decode_message(raw, expected_type="curation.response")
    assert caught.value.code == "unexpected_message_type"


def test_non_finite_and_recursive_values_are_rejected() -> None:
    recursive: list[object] = []
    recursive.append(recursive)

    with pytest.raises(BridgeProtocolError) as non_finite:
        encode_message("progress.update", {"value": float("nan")})
    assert non_finite.value.code == "non_finite_number"

    with pytest.raises(BridgeProtocolError) as cycle:
        encode_message("progress.update", {"value": recursive})
    assert cycle.value.code == "recursive_value"


@pytest.mark.parametrize(
    "payload",
    [
        r'{"sourcePath":"/cache/\ud800.opus"}',
        r'{"firstFields":["\udfff"]}',
        r'{"\ud800":"value"}',
    ],
    ids=["source-path-value", "other-callback-value", "object-key"],
)
def test_decoder_rejects_ascii_escaped_surrogates_in_keys_and_values(
    payload: str,
) -> None:
    raw = (
        '{"schemaVersion":1,"type":"anki.storemedia.result","payload":' + payload + "}"
    )

    assert raw.isascii()
    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "invalid_utf8"
    assert str(error.value) == "Bridge JSON string contains an invalid Unicode scalar"


def test_decoder_accepts_an_ascii_escaped_valid_surrogate_pair() -> None:
    raw = (
        r'{"schemaVersion":1,"type":"progress.update",'
        r'"payload":{"message":"\ud83d\ude00"}}'
    )

    assert raw.isascii()
    assert decode_message(raw) == {"message": "😀"}


@pytest.mark.parametrize(
    "value",
    ["\ud800", {"\udfff": "value"}, Path("/cache/\ud800.opus")],
    ids=["value", "object-key", "path"],
)
def test_encoder_rejects_non_scalar_unicode(value: object) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        encode_message("progress.update", {"value": value})
    assert error.value.code == "invalid_utf8"


def test_decoded_json_depth_limit_has_a_stable_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(protocol, "_MAX_DECODED_JSON_DEPTH", 2)
    raw = '{"schemaVersion":1,"type":"progress.update","payload":{"nested":[[]]}}'

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "invalid_json"
    assert str(error.value) == "Bridge JSON exceeds its decoded depth limit"


def test_decoded_json_node_limit_has_a_stable_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # The empty envelope is seven decoded nodes when object keys are counted.
    monkeypatch.setattr(protocol, "_MAX_DECODED_JSON_NODES", 7)
    raw = '{"schemaVersion":1,"type":"progress.update","payload":{"extra":0}}'

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "invalid_json"
    assert str(error.value) == "Bridge JSON exceeds its decoded node limit"


@pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity"])
def test_decoder_rejects_every_non_rfc_numeric_literal(literal: str) -> None:
    raw = f'{{"schemaVersion":1,"type":"progress.update","payload":{{"nested":[{literal}]}}}}'

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "non_finite_number"


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_encoder_rejects_every_non_finite_float_at_any_depth(value: float) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        encode_message("progress.update", {"nested": [{"value": value}]})
    assert error.value.code == "non_finite_number"


def test_decoder_rejects_duplicate_object_keys() -> None:
    raw = '{"schemaVersion":1,"type":"progress.update","payload":{"value":1,"value":2}}'

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "duplicate_json_key"


@pytest.mark.parametrize("value", [JSON_INTEGER_MIN, JSON_INTEGER_MAX])
def test_signed_64_bit_integer_boundaries_round_trip(value: int) -> None:
    raw = encode_message("progress.update", {"value": value})

    assert decode_message(raw)["value"] == value


@pytest.mark.parametrize("value", [JSON_INTEGER_MIN - 1, JSON_INTEGER_MAX + 1])
def test_encoder_rejects_integer_overflow(value: int) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        encode_message("progress.update", {"nested": [{"value": value}]})
    assert error.value.code == "integer_out_of_range"


@pytest.mark.parametrize(
    "literal",
    [
        str(JSON_INTEGER_MIN - 1),
        str(JSON_INTEGER_MAX + 1),
    ],
)
def test_decoder_rejects_integer_overflow_without_raw_value_error(literal: str) -> None:
    raw = (
        '{"schemaVersion":1,"type":"progress.update",'
        '"payload":{"nested":[{"value":' + literal + "}]}}"
    )

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "integer_out_of_range"


@pytest.mark.parametrize(
    "literal",
    [
        "0." + "0" * 997 + "1",
        "-0." + "0" * 996 + "1",
    ],
)
def test_decoder_accepts_exact_lexical_number_token_limit(literal: str) -> None:
    assert len(literal) == 1000
    raw = (
        '{"schemaVersion":1,"type":"progress.update","payload":{"value":'
        + literal
        + "}}"
    )

    assert decode_message(raw)["value"] == 0.0


@pytest.mark.parametrize(
    "literal",
    [
        "0." + "0" * 998 + "1",
        "-0." + "0" * 997 + "1",
    ],
)
def test_decoder_rejects_number_token_one_character_over_limit(literal: str) -> None:
    assert len(literal) == 1001
    raw = (
        '{"schemaVersion":1,"type":"progress.update","payload":{"value":'
        + literal
        + "}}"
    )

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "json_number_too_long"


def test_number_guard_ignores_digit_text_and_escaped_quotes_inside_strings() -> None:
    digit_text = "9" * 5000
    raw = encode_message(
        "progress.update",
        {"quoted": f'escaped quote: "{digit_text}"', "number": 1},
    )

    assert decode_message(raw) == {
        "quoted": f'escaped quote: "{digit_text}"',
        "number": 1,
    }


def test_decoder_rejects_leading_unicode_bom_before_json_parse() -> None:
    raw = "\ufeff" + encode_message("progress.update", {})

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "invalid_json"
    assert str(error.value) == "A leading Unicode BOM is not JSON whitespace"


@pytest.mark.parametrize("literal", ["1e309", "-1e309"])
def test_decoder_rejects_exponent_overflow_at_depth(literal: str) -> None:
    nested = "[" * 64 + literal + "]" * 64
    raw = (
        '{"schemaVersion":1,"type":"progress.update","payload":{"nested":'
        + nested
        + "}}"
    )

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "non_finite_number"


def test_largest_finite_ieee_double_is_accepted() -> None:
    raw = (
        '{"schemaVersion":1,"type":"progress.update",'
        '"payload":{"value":1.7976931348623157e308}}'
    )

    assert decode_message(raw)["value"] == float("1.7976931348623157e308")


def test_integral_float_schema_version_is_normalized() -> None:
    raw = '{"schemaVersion":1.0,"type":"progress.update","payload":{}}'

    assert decode_message(raw) == {}


def test_nonintegral_float_schema_version_is_rejected() -> None:
    raw = '{"schemaVersion":1.5,"type":"progress.update","payload":{}}'

    with pytest.raises(BridgeProtocolError) as error:
        decode_message(raw)
    assert error.value.code == "unsupported_schema_version"


def test_checked_in_schema_matches_codec_version() -> None:
    schema_path = (
        Path(__file__).resolve().parents[3]
        / "app/src/main/python/android_bridge/schemas/bridge-envelope.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    assert schema["properties"]["schemaVersion"]["const"] == BRIDGE_SCHEMA_VERSION
