from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

import pytest

from android_bridge.protocol import (
    BRIDGE_SCHEMA_VERSION,
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
    assert decode_message(raw, expected_type="mining.result") == {"text": "日本語", "count": 2}


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
        ('{"schemaVersion":2,"type":"job.cancel","payload":{}}', "unsupported_schema_version"),
        ('{"schemaVersion":1,"type":"job.cancel","payload":{},"extra":1}', "invalid_envelope"),
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


def test_checked_in_schema_matches_codec_version() -> None:
    schema_path = Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/bridge-envelope.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    assert schema["properties"]["schemaVersion"]["const"] == BRIDGE_SCHEMA_VERSION
