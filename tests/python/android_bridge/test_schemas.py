from __future__ import annotations

import json
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from android_bridge.config_map import (
    AndroidPaths,
    exposed_config_fields,
    map_config_json,
    map_config_settings,
)
from android_bridge.jobs import JobRegistry
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message

SCHEMA_ROOT = (
    Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas"
)


def _load_schema(name: str) -> dict[str, Any]:
    return json.loads((SCHEMA_ROOT / name).read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def schemas() -> dict[str, dict[str, Any]]:
    return {
        "envelope": _load_schema("bridge-envelope.schema.json"),
        "config": _load_schema("config-snapshot.schema.json"),
        "curation": _load_schema("curation.schema.json"),
        "anki": _load_schema("anki.schema.json"),
    }


def _validated_payload(
    raw: str,
    expected_type: str,
    *,
    envelope_validator: Draft202012Validator,
    payload_validator: Draft202012Validator,
) -> dict[str, Any]:
    decoded = decode_envelope(raw, expected_type=expected_type)
    envelope_validator.validate(json.loads(raw))
    payload_validator.validate(decoded.payload)
    return decoded.payload


def _full_config_payload(home: Path) -> dict[str, Any]:
    settings: dict[str, Any] = {
        "anki_deck_name": "Japanese::Mining",
        "anki_note_type": "Lapis",
        "anki_fields": {
            "word": "Expression",
            "sentence": "Sentence",
            "definition": "MainDefinition",
            "glossary": "Glossary",
            "picture": "Picture",
            "audio": "SentenceAudio",
            "expression_furigana": "ExpressionFurigana",
            "expression_reading": "ExpressionReading",
            "sentence_furigana": "SentenceFurigana",
            "sentence_reading": "SentenceReading",
            "pitch_position": "PitchPosition",
            "pitch_category": "PitchCategory",
            "pitch_graph": "PitchGraph",
            "pitch_text": "PitchText",
            "frequency": "Frequency",
            "frequency_sort": "FrequencySort",
            "source": "Source",
            "expression_audio": "ExpressionAudio",
        },
        "card_type": "word_and_sentence",
        "card_type_marker_fields": {
            "word_and_sentence": "IsWordAndSentenceCard",
            "click": "IsClickCard",
            "sentence": "IsSentenceCard",
            "audio": "IsAudioCard",
        },
        "anki_tags": "auto-mined mobile",
        "excluded_decks": ["Japanese::Known"],
        "audio_padding": 0.4,
        "screenshot_offset": 1.2,
        "audio_format": "opus",
        "audio_bitrate": 128,
        "screenshot_animated": True,
        "screenshot_animated_format": "webp",
        "screenshot_animated_clip_duration": 1.5,
        "screenshot_animated_match_audio": False,
        "screenshot_animated_fps": 24,
        "screenshot_animated_height": 720,
        "screenshot_animated_quality": 80,
        "subtitle_offset": -0.2,
        "allowed_pos": ["名詞", "動詞"],
        "excluded_subtypes": ["数詞"],
        "excluded_wordsets": ["given-names"],
        "dictionary_chain": [
            {"kind": "indexed", "dict_id": "jmdict-english", "enabled": True},
            {"kind": "jisho", "dict_id": None, "enabled": False},
        ],
        "jisho_delay": 0.5,
        "expression_audio_chain": [
            {"kind": "pack", "pack_id": "local-audio", "enabled": True}
        ],
        "reading_tts_enabled": True,
        "pitch_category_format": "romaji",
        "max_frequency_rank": 20000,
        "frequency_chain": [{"source_id": "bccwj", "enabled": True}],
        "use_known_words_db": True,
        "exclude_hiragana_only_words": True,
        "exclude_katakana_only_words": False,
        "blacklist_path": str(home / "blacklist.txt"),
        "whitelist_path": str(home / "whitelist.txt"),
        "use_blacklist": True,
        "use_whitelist": True,
        "subtitle_regex_filter": "^♪+$",
        "subtitle_regex_replacement": "",
        "use_subtitle_regex_filter": True,
        "bold_target_in_sentence": True,
        "deduplicate_sentences": True,
        "use_i_plus_one_filter": False,
        "use_sentence_length_filter": True,
        "max_sentence_duration_seconds": 12.0,
        "max_sentence_chars": 80,
        "reading_min_occurrence": 2,
        "max_parallel_workers": 4,
    }
    assert set(settings) == set(exposed_config_fields()) | {"reading_tts_enabled"}
    return {"settings": settings, "androidTtsEnabled": True}


def test_all_checked_in_schemas_self_validate_as_draft_2020_12(
    schemas: dict[str, dict[str, Any]],
) -> None:
    for schema in schemas.values():
        assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
        Draft202012Validator.check_schema(schema)


def test_representative_full_config_message_validates_and_maps(
    schemas: dict[str, dict[str, Any]],
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    payload = _full_config_payload(initialized_bridge_home)
    raw = encode_message("config.snapshot", payload)
    validated = _validated_payload(
        raw,
        "config.snapshot",
        envelope_validator=Draft202012Validator(schemas["envelope"]),
        payload_validator=Draft202012Validator(schemas["config"]),
    )
    mapped = map_config_json(
        raw,
        str(initialized_bridge_home),
        str(tmp_path / "cache"),
        str(tmp_path / "native"),
    )

    assert validated == payload
    assert mapped.engine_config.audio_format == "opus"
    assert mapped.engine_config.expression_audio_chain[0].pack_id == "local-audio"
    assert mapped.android_tts_enabled is True


@dataclass
class _FakeWord:
    surface: str = "猫"
    lemma: str = "猫"
    sentence: str = "猫だ"
    start_time: float = 1.0
    end_time: float = 2.0
    duration: float = 1.0
    reading: str = "ねこ"
    expression_reading: str = "ねこ"
    sentence_furigana: str = ""
    sentence_reading: str = "ねこだ"
    pos: str | None = "名詞"
    frequency_rank: int | None = 100
    occurrence_count: int = 1
    sentence_candidates: list[_FakeWord] = field(default_factory=list)

    @property
    def mined_form(self) -> str:
        return self.surface


def test_generated_curation_request_and_omitted_sentence_id_validate(
    schemas: dict[str, dict[str, Any]],
) -> None:
    registry = JobRegistry()
    handle = registry.begin()
    word = _FakeWord()
    emitted = threading.Event()
    request: list[str] = []
    returned: list[object] = []

    def wait_for_curation() -> None:
        returned.append(
            registry.await_curation(
                handle.run_id,
                [word],
                lambda raw: (request.append(raw), emitted.set()),
            )
        )

    thread = threading.Thread(target=wait_for_curation, daemon=True)
    thread.start()
    try:
        assert emitted.wait(1)
        envelope_validator = Draft202012Validator(schemas["envelope"])
        curation_validator = Draft202012Validator(schemas["curation"])
        request_payload = _validated_payload(
            request[0],
            "curation.request",
            envelope_validator=envelope_validator,
            payload_validator=curation_validator,
        )
        response = encode_message(
            "curation.response",
            {
                "runId": handle.run_id,
                "requestId": request_payload["requestId"],
                "selection": [
                    {"candidateId": request_payload["candidates"][0]["candidateId"]}
                ],
            },
        )
        _validated_payload(
            response,
            "curation.response",
            envelope_validator=envelope_validator,
            payload_validator=curation_validator,
        )
        registry.resolve_curation(response)
        thread.join(1)
    finally:
        try:
            if thread.is_alive():
                registry.cancel(handle.run_id)
        finally:
            thread.join(1)

    assert not thread.is_alive()
    assert returned == [[word]]
    assert returned[0][0] is word


@pytest.mark.parametrize("sentence_id", [None, ""])
def test_null_or_empty_sentence_id_is_schema_invalid(
    schemas: dict[str, dict[str, Any]],
    sentence_id: object,
) -> None:
    payload = {
        "runId": "run_" + "a" * 32,
        "requestId": "curation_" + "b" * 32,
        "selection": [
            {
                "candidateId": "candidate_" + "c" * 32,
                "sentenceId": sentence_id,
            }
        ],
    }

    with pytest.raises(ValidationError):
        Draft202012Validator(schemas["curation"]).validate(payload)


@pytest.mark.parametrize(
    "payload",
    [
        {"settings": {"unknown_field": True}},
        {"settings": {"dictionary_chain": [{"kind": "indexed", "enabled": True}]}},
        {"settings": {"frequency_chain": [{"source_id": "freq", "unexpected": True}]}},
        {
            "settings": {
                "expression_audio_chain": [{"kind": "jpod101", "pack_id": "not-local"}]
            }
        },
        {"settings": {"blacklist_path": "relative/blacklist.txt"}},
        {"settings": {"whitelist_path": ""}},
    ],
)
def test_invalid_config_shapes_are_rejected_by_schema(
    schemas: dict[str, dict[str, Any]],
    payload: dict[str, Any],
) -> None:
    with pytest.raises(ValidationError):
        Draft202012Validator(schemas["config"]).validate(payload)


def test_unknown_curation_map_key_is_schema_invalid(
    schemas: dict[str, dict[str, Any]],
) -> None:
    payload = {
        "runId": "run_" + "a" * 32,
        "requestId": "curation_" + "b" * 32,
        "selection": [
            {
                "candidateId": "candidate_" + "c" * 32,
                "unexpected": True,
            }
        ],
    }

    with pytest.raises(ValidationError):
        Draft202012Validator(schemas["curation"]).validate(payload)


@pytest.mark.parametrize(
    "payload",
    [
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "deckName": "Japanese::Mining",
            "modelName": "Lapis",
            "requiredFields": ["Expression", "Sentence"],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "deckId": 1,
            "modelId": 2,
            "fieldNames": ["Expression", "Sentence"],
            "deckCreated": True,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "scope": {
                "kind": "knownVocabulary",
                "excludedDecks": ["Japanese::Known"],
            },
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "scope": {
                "kind": "duplicates",
                "modelName": "Lapis",
                "deckName": None,
            },
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "firstFields": ["<b>猫</b>", "[sound:dog.mp3]犬"],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "assets": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "sourcePath": "/data/user/0/app/cache/audio.opus",
                    "preferredName": "猫_ab12cd34ef56.opus",
                    "purpose": "card",
                    "mediaKind": "audio",
                }
            ],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "results": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "status": "stored",
                    "actualFilename": "猫_ab12cd34ef56.opus",
                },
                {
                    "assetId": "asset_" + "d" * 32,
                    "status": "failed",
                    "errorCode": "media_store_failed",
                },
            ],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "deckName": "Japanese::Mining",
            "modelName": "Lapis",
            "notes": [
                {
                    "clientNoteId": "note_" + "d" * 32,
                    "fields": {"Expression": "猫", "Sentence": "猫だ"},
                    "tags": ["auto-mined"],
                }
            ],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "results": [
                {
                    "clientNoteId": "note_" + "d" * 32,
                    "status": "created",
                    "noteId": 123,
                }
            ],
            "error": None,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "operation": "createNotes",
            "code": "write_failed",
            "message": "provider write failed",
            "retryable": True,
        },
    ],
)
def test_representative_anki_callback_payloads_validate(
    schemas: dict[str, dict[str, Any]], payload: dict[str, Any]
) -> None:
    Draft202012Validator(schemas["anki"]).validate(payload)


@pytest.mark.parametrize(
    "payload",
    [
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "deckName": "Mining",
            "modelName": "Lapis",
            "requiredFields": ["Expression"],
            "unexpected": True,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "assets": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "sourcePath": "relative/file.mp3",
                    "preferredName": "../file.mp3",
                    "purpose": "card",
                    "mediaKind": "audio",
                }
            ],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "results": [
                {
                    "clientNoteId": "note_" + "d" * 32,
                    "status": "notAttempted",
                    "noteId": 1,
                }
            ],
            "error": None,
        },
    ],
)
def test_anki_callback_schema_is_closed(
    schemas: dict[str, dict[str, Any]], payload: dict[str, Any]
) -> None:
    with pytest.raises(ValidationError):
        Draft202012Validator(schemas["anki"]).validate(payload)


@pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity"])
def test_validation_pipeline_rejects_non_rfc_numeric_messages(literal: str) -> None:
    raw = (
        '{"schemaVersion":1,"type":"config.snapshot",'
        '"payload":{"settings":{"subtitle_offset":' + literal + "}}}"
    )

    with pytest.raises(BridgeProtocolError) as error:
        decode_envelope(raw, expected_type="config.snapshot")
    assert error.value.code == "non_finite_number"


def test_provider_identity_uniqueness_is_documented_runtime_refinement(
    schemas: dict[str, dict[str, Any]],
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    payload = {
        "settings": {
            "frequency_chain": [
                {"source_id": "same-source", "enabled": True},
                {"source_id": "same-source", "enabled": False},
            ]
        }
    }
    Draft202012Validator(schemas["config"]).validate(payload)
    assert "runtime semantic refinement" in schemas["config"]["$comment"]

    with pytest.raises(BridgeProtocolError, match="duplicate source"):
        map_config_settings(
            payload["settings"],
            paths=_android_paths(initialized_bridge_home, tmp_path),
        )


def _android_paths(home: Path, tmp_path: Path) -> AndroidPaths:
    return AndroidPaths(home, tmp_path / "cache", tmp_path / "native")
