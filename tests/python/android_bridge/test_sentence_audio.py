from __future__ import annotations

import json
from pathlib import Path

import pytest
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message
from android_bridge.sentence_audio import AndroidSentenceAudioFetcher
from jsonschema import Draft202012Validator

RUN_ID = "run_00000000000000000000000000000000"
PROJECT_ROOT = Path(__file__).resolve().parents[3]


class ResultCallbacks:
    def __init__(
        self,
        cache_dir: Path,
        outcome: str = "ready",
        error_code: str = "tts_engine_unavailable",
    ) -> None:
        self.cache_dir = cache_dir
        self.outcome = outcome
        self.error_code = error_code
        self.requests: list[str] = []

    def synthesizeSentenceAudio(self, raw: str) -> str:
        self.requests.append(raw)
        request = decode_envelope(raw, expected_type="tts.sentence.request")
        output = self.cache_dir / "sentence-audio-v1" / ("android_tts_v1_" + "a" * 64 + ".wav")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"RIFFaudio")
        return encode_message(
            "tts.sentence.result",
            {
                "runId": request.payload["runId"],
                "requestId": request.payload["requestId"],
                "outcome": self.outcome,
                "path": str(output) if self.outcome == "ready" else None,
                "errorCode": None if self.outcome == "ready" else self.error_code,
            },
        )


def test_fetch_sends_strict_correlated_request_and_returns_verified_private_wav(
    tmp_path: Path,
) -> None:
    callbacks = ResultCallbacks(tmp_path)
    fetcher = AndroidSentenceAudioFetcher(callbacks, RUN_ID, tmp_path)

    result = fetcher.fetch("猫だ。", lambda: False)

    assert result is not None
    assert result.read_bytes() == b"RIFFaudio"
    request = decode_envelope(callbacks.requests[0], expected_type="tts.sentence.request")
    assert set(request.payload) == {"runId", "requestId", "sentence"}
    assert request.payload["runId"] == RUN_ID
    assert request.payload["requestId"].startswith("tts_")
    assert request.payload["sentence"] == "猫だ。"


@pytest.mark.parametrize("outcome", ["unavailable", "failed"])
def test_optional_tts_failure_never_raises(tmp_path: Path, outcome: str) -> None:
    warnings: list[str] = []
    fetcher = AndroidSentenceAudioFetcher(
        ResultCallbacks(tmp_path, outcome, "offline_japanese_voice_unavailable"),
        RUN_ID,
        tmp_path,
        warning_callback=warnings.append,
    )

    assert fetcher.fetch("猫だ。") is None
    assert fetcher.fetch("犬だ。") is None
    assert warnings == [
        "Offline Japanese sentence audio is unavailable. Install an offline Japanese "
        "voice in Android speech settings."
    ]


@pytest.mark.parametrize(
    "error_code",
    ["tts_engine_unavailable", "tts_initialization_timeout"],
)
def test_engine_or_initialization_failure_does_not_claim_voice_is_missing(
    tmp_path: Path,
    error_code: str,
) -> None:
    warnings: list[str] = []
    fetcher = AndroidSentenceAudioFetcher(
        ResultCallbacks(tmp_path, "unavailable", error_code),
        RUN_ID,
        tmp_path,
        warning_callback=warnings.append,
    )

    assert fetcher.fetch("猫だ。") is None
    assert warnings == ["Offline sentence audio could not be synthesized for one or more selected sentences."]


def test_missing_throwing_or_malformed_callback_never_raises(tmp_path: Path) -> None:
    class Throwing:
        def synthesizeSentenceAudio(self, raw: str) -> str:
            raise RuntimeError("TTS service died")

    class Malformed:
        def synthesizeSentenceAudio(self, raw: str) -> str:
            return "{}"

    for callbacks in (object(), Throwing(), Malformed()):
        fetcher = AndroidSentenceAudioFetcher(callbacks, RUN_ID, tmp_path)
        assert fetcher.fetch("猫だ。") is None


def test_result_path_must_be_exact_bounded_cache_file(tmp_path: Path) -> None:
    outside = tmp_path / "outside.wav"
    outside.write_bytes(b"RIFFaudio")

    class PathCallbacks:
        def synthesizeSentenceAudio(self, raw: str) -> str:
            request = decode_envelope(raw, expected_type="tts.sentence.request")
            return encode_message(
                "tts.sentence.result",
                {
                    "runId": request.payload["runId"],
                    "requestId": request.payload["requestId"],
                    "outcome": "ready",
                    "path": str(outside),
                    "errorCode": None,
                },
            )

    fetcher = AndroidSentenceAudioFetcher(PathCallbacks(), RUN_ID, tmp_path)
    assert fetcher.fetch("猫だ。") is None


def test_cancellation_before_callback_and_after_result_returns_none(tmp_path: Path) -> None:
    callbacks = ResultCallbacks(tmp_path)
    warnings: list[str] = []
    fetcher = AndroidSentenceAudioFetcher(
        callbacks,
        RUN_ID,
        tmp_path,
        warning_callback=warnings.append,
    )
    assert fetcher.fetch("猫だ。", lambda: True) is None
    assert callbacks.requests == []

    checks = iter((False, True))
    assert fetcher.fetch("猫だ。", lambda: next(checks)) is None
    assert len(callbacks.requests) == 1
    assert warnings == []


def test_invalid_or_oversized_sentences_do_not_cross_callback(tmp_path: Path) -> None:
    callbacks = ResultCallbacks(tmp_path)
    fetcher = AndroidSentenceAudioFetcher(callbacks, RUN_ID, tmp_path)

    assert fetcher.fetch("") is None
    assert fetcher.fetch("bad\x00sentence") is None
    assert fetcher.fetch("猫" * 6_000) is None
    assert callbacks.requests == []


def test_sentence_over_4000_utf16_units_does_not_cross_callback(tmp_path: Path) -> None:
    callbacks = ResultCallbacks(tmp_path)
    fetcher = AndroidSentenceAudioFetcher(callbacks, RUN_ID, tmp_path)

    assert fetcher.fetch("\U0001f600" * 2_001) is None
    assert callbacks.requests == []


def test_sentence_audio_schema_and_corpus_freeze_complete_envelopes() -> None:
    schema = json.loads(
        (PROJECT_ROOT / "app/src/main/python/android_bridge/schemas/sentence-audio.schema.json").read_text(
            encoding="utf-8"
        )
    )
    corpus = json.loads(
        (PROJECT_ROOT / "app/src/test/resources/contracts/sentence_audio_protocol_v1.json").read_text(encoding="utf-8")
    )
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    assert corpus["version"] == 1
    for message in corpus["valid"]:
        assert list(validator.iter_errors(message)) == []
    for message in corpus["invalid"]:
        assert list(validator.iter_errors(message))


def test_sentence_audio_contract_counts_utf16_units_beyond_schema_code_points() -> None:
    schema = json.loads(
        (PROJECT_ROOT / "app/src/main/python/android_bridge/schemas/sentence-audio.schema.json").read_text(
            encoding="utf-8"
        )
    )
    payload = {
        "runId": RUN_ID,
        "requestId": "tts_" + "a" * 32,
        "sentence": "\U0001f600" * 2_001,
    }
    message = {"schemaVersion": 1, "type": "tts.sentence.request", "payload": payload}

    Draft202012Validator(schema).validate(message)
    with pytest.raises(BridgeProtocolError, match="4000 UTF-16 code units"):
        encode_message("tts.sentence.request", payload)
