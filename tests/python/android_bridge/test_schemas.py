from __future__ import annotations

import json
import threading
from copy import deepcopy
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from android_bridge.anki_limits import (
    ANKI_ENVELOPE_LIMITS_V1,
    ANKI_LIMITS_V1,
)
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
SOURCE_PATH_CORPUS = (
    Path(__file__).resolve().parents[3]
    / "app/src/test/resources/contracts/anki_media_source_path_v1.json"
)


def _load_schema(name: str) -> dict[str, Any]:
    return json.loads((SCHEMA_ROOT / name).read_text(encoding="utf-8"))


def _source_path_cases() -> list[dict[str, Any]]:
    corpus = json.loads(SOURCE_PATH_CORPUS.read_text(encoding="utf-8"))
    assert corpus["version"] == 1
    cases: list[dict[str, Any]] = []
    for raw_case in corpus["cases"]:
        case = dict(raw_case)
        recipe = case.pop("pathRecipe", None)
        if recipe is not None:
            case["path"] = recipe["prefix"] + recipe["unit"] * recipe["repeat"]
        cases.append(case)
    return cases


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
        "screenshot_animated": False,
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


def test_anki_limits_v1_manifest_freezes_exact_units_and_values() -> None:
    assert ANKI_LIMITS_V1 == {
        "schemaVersion": 1,
        "units": {
            "codePoints": "Unicode scalar values counted by JSON Schema maxLength",
            "items": "array entries",
            "utf8Bytes": (
                "bytes after strict UTF-8 encoding of the decoded string or "
                "complete JSON envelope"
            ),
        },
        "wire": {"numericTokenMaxChars": 1000},
        "names": {
            "deck": {"maxCodePoints": 1024, "maxUtf8Bytes": 1024},
            "model": {"maxCodePoints": 1024, "maxUtf8Bytes": 1024},
            "field": {"maxCodePoints": 256, "maxUtf8Bytes": 256},
            "targetFields": {"maxItems": 64, "maxTotalUtf8Bytes": 16384},
            "excludedDecks": {"maxItems": 256, "maxTotalUtf8Bytes": 65536},
        },
        "targetModel": {
            "allowedType": 0,
            "maxTemplates": 64,
            "cssMaxUtf8Bytes": 262144,
            "latexPreMaxUtf8Bytes": 262144,
            "latexPostMaxUtf8Bytes": 262144,
            "templateQuestionFormatMaxUtf8Bytes": 262144,
            "templateAnswerFormatMaxUtf8Bytes": 262144,
            "templateBrowserQuestionFormatMaxUtf8Bytes": 262144,
            "templateBrowserAnswerFormatMaxUtf8Bytes": 262144,
            "providerTextTotalMaxUtf8Bytes": 4194304,
        },
        "verifyTarget": {
            "requestEnvelopeMaxUtf8Bytes": 65536,
            "resultEnvelopeMaxUtf8Bytes": 65536,
        },
        "scanFirstFields": {
            "requestEnvelopeMaxUtf8Bytes": 16777216,
            "resultEnvelopeMaxUtf8Bytes": 2097152,
            "duplicateKeyMaxCodePoints": 4096,
            "duplicateFirstFieldMaxCodePoints": 16384,
            "duplicateCandidatesMaxItems": 100,
            "duplicateHitsPerCandidateMaxItems": 100,
            "duplicateHitsTotalMaxItems": 1000,
            "firstFieldMaxCodePoints": 65536,
            "firstFieldMaxUtf8Bytes": 65536,
            "duplicateHitsTotalMaxUtf8Bytes": 1048576,
            "knownPageMaxItems": 256,
            "knownPageMaxUtf8Bytes": 262144,
            "knownTotalScannedNotes": 100000,
            "knownCursorMaxCodePoints": 1024,
            "knownCursorMaxUtf8Bytes": 1024,
        },
        "storeMedia": {
            "requestEnvelopeMaxUtf8Bytes": 2097152,
            "resultEnvelopeMaxUtf8Bytes": 524288,
            "maxAssets": 50,
            "maxAssetBytes": 67108864,
            "maxTotalBytes": 67108864,
            "filenameMaxCodePoints": 1024,
            "filenameMaxUtf8Bytes": 1024,
            "sourcePathMaxCodePoints": 4096,
            "sourcePathMaxUtf8Bytes": 4096,
        },
        "createNotes": {
            "requestEnvelopeMaxUtf8Bytes": 524288,
            "resultEnvelopeMaxUtf8Bytes": 524288,
            "maxNotes": 100,
            "maxFieldsPerNote": 64,
            "maxCardsPerNote": 64,
            "fieldNameMaxUtf8Bytes": 256,
            "fieldValueMaxCodePoints": 98304,
            "fieldValueMaxUtf8Bytes": 98304,
            "maxTagsPerNote": 64,
            "tagMaxCodePoints": 256,
            "tagMaxUtf8Bytes": 256,
            "tagsPerNoteMaxUtf8Bytes": 8192,
            "noteContentMaxUtf8Bytes": 131072,
            "callbackContentMaxUtf8Bytes": 393216,
            "maxMediaBindingsPerNote": 8000,
            "maxMediaBindingsTotal": 8000,
        },
        "releaseRunState": {
            "requestEnvelopeMaxUtf8Bytes": 16384,
            "resultEnvelopeMaxUtf8Bytes": 16384,
        },
        "createCall": {
            "maxSourceItems": 2000,
            "sourceMaxUtf8Bytes": 16777216,
            "builtNotesMaxUtf8Bytes": 16777216,
            "maxMediaReferences": 8000,
            "mediaWorkMaxBytes": 536870912,
        },
    }
    assert ANKI_ENVELOPE_LIMITS_V1 == {
        "verifyTarget": (65536, 65536),
        "scanFirstFields": (16777216, 2097152),
        "storeMedia": (2097152, 524288),
        "createNotes": (524288, 524288),
        "releaseRunState": (16384, 16384),
    }
    assert (
        ANKI_LIMITS_V1["names"]["targetFields"]["maxItems"]
        == (ANKI_LIMITS_V1["createNotes"]["maxFieldsPerNote"])
    )
    assert (
        ANKI_LIMITS_V1["names"]["field"]["maxUtf8Bytes"]
        == (ANKI_LIMITS_V1["createNotes"]["fieldNameMaxUtf8Bytes"])
    )
    assert ANKI_LIMITS_V1["targetModel"]["allowedType"] == 0
    assert (
        ANKI_LIMITS_V1["targetModel"]["maxTemplates"]
        == ANKI_LIMITS_V1["createNotes"]["maxCardsPerNote"]
    )


_SchemaPath = tuple[str | int, ...]
_ManifestPath = tuple[str, ...]
_LimitBinding = tuple[_ManifestPath, _SchemaPath, int]


# Every numeric upper bound or numeric wire constant in anki.schema.json is
# listed exactly once. The final integer is an explicit derivation offset;
# occurrence indexes are the only derived values (maxItems - 1).
_ANKI_SCHEMA_LIMIT_BINDINGS: tuple[_LimitBinding, ...] = (
    (
        ("names", "deck", "maxCodePoints"),
        ("$defs", "deckName", "allOf", 1, "maxLength"),
        0,
    ),
    (
        ("names", "model", "maxCodePoints"),
        ("$defs", "modelName", "allOf", 1, "maxLength"),
        0,
    ),
    (
        ("names", "field", "maxCodePoints"),
        ("$defs", "fieldName", "allOf", 1, "maxLength"),
        0,
    ),
    (("storeMedia", "filenameMaxCodePoints"), ("$defs", "filename", "maxLength"), 0),
    (
        ("storeMedia", "filenameMaxCodePoints"),
        ("$defs", "mediaBasename", "maxLength"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateKeyMaxCodePoints"),
        ("$defs", "duplicateKey", "maxLength"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateFirstFieldMaxCodePoints"),
        ("$defs", "duplicateCandidate", "properties", "firstField", "maxLength"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateFirstFieldMaxCodePoints"),
        ("$defs", "createDuplicateCandidate", "properties", "firstField", "maxLength"),
        0,
    ),
    (
        ("createNotes", "maxNotes"),
        ("$defs", "createDuplicateCandidate", "properties", "occurrence", "maximum"),
        -1,
    ),
    (
        ("names", "targetFields", "maxItems"),
        ("$defs", "verifyTargetRequest", "properties", "requiredFields", "maxItems"),
        0,
    ),
    (
        ("names", "targetFields", "maxItems"),
        ("$defs", "verifyTargetResult", "properties", "fieldNames", "maxItems"),
        0,
    ),
    (
        ("names", "excludedDecks", "maxItems"),
        ("$defs", "knownVocabularyScope", "properties", "excludedDecks", "maxItems"),
        0,
    ),
    (
        ("scanFirstFields", "knownPageMaxItems"),
        (
            "$defs",
            "knownVocabularyScope",
            "properties",
            "limits",
            "properties",
            "maxScannedNotes",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "knownTotalScannedNotes"),
        (
            "$defs",
            "knownVocabularyScope",
            "properties",
            "limits",
            "properties",
            "maxTotalScannedNotes",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "knownPageMaxItems"),
        (
            "$defs",
            "knownVocabularyScope",
            "properties",
            "limits",
            "properties",
            "maxItems",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "firstFieldMaxUtf8Bytes"),
        (
            "$defs",
            "knownVocabularyScope",
            "properties",
            "limits",
            "properties",
            "maxItemUtf8Bytes",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "knownPageMaxUtf8Bytes"),
        (
            "$defs",
            "knownVocabularyScope",
            "properties",
            "limits",
            "properties",
            "maxTotalUtf8Bytes",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "knownCursorMaxCodePoints"),
        ("$defs", "knownVocabularyCursor", "properties", "token", "maxLength"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateCandidatesMaxItems"),
        ("$defs", "duplicateScope", "properties", "candidates", "maxItems"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateCandidatesMaxItems"),
        ("$defs", "duplicateScope", "properties", "occurrences", "maxItems"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateCandidatesMaxItems"),
        ("$defs", "duplicateScope", "properties", "occurrences", "items", "maximum"),
        -1,
    ),
    (
        ("scanFirstFields", "duplicateHitsPerCandidateMaxItems"),
        (
            "$defs",
            "duplicateScope",
            "properties",
            "limits",
            "properties",
            "maxHitsPerCandidate",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "duplicateHitsTotalMaxItems"),
        (
            "$defs",
            "duplicateScope",
            "properties",
            "limits",
            "properties",
            "maxTotalHits",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "firstFieldMaxUtf8Bytes"),
        (
            "$defs",
            "duplicateScope",
            "properties",
            "limits",
            "properties",
            "maxItemUtf8Bytes",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "duplicateHitsTotalMaxUtf8Bytes"),
        (
            "$defs",
            "duplicateScope",
            "properties",
            "limits",
            "properties",
            "maxTotalUtf8Bytes",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "knownPageMaxItems"),
        ("$defs", "scanFirstFieldsResult", "properties", "firstFields", "maxItems"),
        0,
    ),
    (
        ("scanFirstFields", "firstFieldMaxCodePoints"),
        (
            "$defs",
            "scanFirstFieldsResult",
            "properties",
            "firstFields",
            "items",
            "maxLength",
        ),
        0,
    ),
    (
        ("scanFirstFields", "knownPageMaxItems"),
        ("$defs", "scanFirstFieldsResult", "properties", "scannedNotes", "maximum"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateCandidatesMaxItems"),
        (
            "$defs",
            "duplicateLookupResult",
            "properties",
            "rawFirstFieldHits",
            "maxItems",
        ),
        0,
    ),
    (
        ("scanFirstFields", "duplicateHitsPerCandidateMaxItems"),
        (
            "$defs",
            "duplicateLookupResult",
            "properties",
            "rawFirstFieldHits",
            "items",
            "maxItems",
        ),
        0,
    ),
    (
        ("scanFirstFields", "firstFieldMaxCodePoints"),
        (
            "$defs",
            "duplicateLookupResult",
            "properties",
            "rawFirstFieldHits",
            "items",
            "items",
            "properties",
            "firstField",
            "maxLength",
        ),
        0,
    ),
    (
        ("storeMedia", "sourcePathMaxCodePoints"),
        ("$defs", "mediaAsset", "properties", "sourcePath", "maxLength"),
        0,
    ),
    (
        ("storeMedia", "maxAssetBytes"),
        ("$defs", "mediaAsset", "properties", "expectedSizeBytes", "maximum"),
        0,
    ),
    (
        ("storeMedia", "maxAssets"),
        ("$defs", "storeMediaLimits", "properties", "maxAssets", "const"),
        0,
    ),
    (
        ("storeMedia", "maxAssetBytes"),
        ("$defs", "storeMediaLimits", "properties", "maxAssetBytes", "const"),
        0,
    ),
    (
        ("storeMedia", "maxTotalBytes"),
        ("$defs", "storeMediaLimits", "properties", "maxTotalBytes", "const"),
        0,
    ),
    (
        ("storeMedia", "maxAssets"),
        ("$defs", "storeMediaRequest", "properties", "assets", "maxItems"),
        0,
    ),
    (
        ("storeMedia", "maxAssets"),
        ("$defs", "storeMediaResult", "properties", "results", "maxItems"),
        0,
    ),
    (
        ("createNotes", "maxFieldsPerNote"),
        ("$defs", "note", "properties", "fields", "maxProperties"),
        0,
    ),
    (
        ("createNotes", "fieldValueMaxCodePoints"),
        ("$defs", "note", "properties", "fields", "additionalProperties", "maxLength"),
        0,
    ),
    (
        ("createNotes", "maxTagsPerNote"),
        ("$defs", "note", "properties", "tags", "maxItems"),
        0,
    ),
    (
        ("createNotes", "maxMediaBindingsPerNote"),
        ("$defs", "note", "properties", "mediaBindings", "maxItems"),
        0,
    ),
    (
        ("createNotes", "tagMaxCodePoints"),
        ("$defs", "note", "properties", "tags", "items", "allOf", 1, "maxLength"),
        0,
    ),
    (
        ("createNotes", "maxNotes"),
        ("$defs", "createNotesLimits", "properties", "maxNotes", "const"),
        0,
    ),
    (
        ("createNotes", "maxFieldsPerNote"),
        ("$defs", "createNotesLimits", "properties", "maxFieldsPerNote", "const"),
        0,
    ),
    (
        ("createNotes", "maxCardsPerNote"),
        ("$defs", "createNotesLimits", "properties", "maxCardsPerNote", "const"),
        0,
    ),
    (
        ("createNotes", "fieldNameMaxUtf8Bytes"),
        ("$defs", "createNotesLimits", "properties", "maxFieldNameUtf8Bytes", "const"),
        0,
    ),
    (
        ("createNotes", "fieldValueMaxUtf8Bytes"),
        ("$defs", "createNotesLimits", "properties", "maxFieldValueUtf8Bytes", "const"),
        0,
    ),
    (
        ("createNotes", "maxTagsPerNote"),
        ("$defs", "createNotesLimits", "properties", "maxTagsPerNote", "const"),
        0,
    ),
    (
        ("createNotes", "tagMaxUtf8Bytes"),
        ("$defs", "createNotesLimits", "properties", "maxTagUtf8Bytes", "const"),
        0,
    ),
    (
        ("createNotes", "tagsPerNoteMaxUtf8Bytes"),
        (
            "$defs",
            "createNotesLimits",
            "properties",
            "maxTagsUtf8BytesPerNote",
            "const",
        ),
        0,
    ),
    (
        ("createNotes", "noteContentMaxUtf8Bytes"),
        (
            "$defs",
            "createNotesLimits",
            "properties",
            "maxNoteContentUtf8Bytes",
            "const",
        ),
        0,
    ),
    (
        ("createNotes", "callbackContentMaxUtf8Bytes"),
        (
            "$defs",
            "createNotesLimits",
            "properties",
            "maxTotalContentUtf8Bytes",
            "const",
        ),
        0,
    ),
    (
        ("createNotes", "maxMediaBindingsPerNote"),
        (
            "$defs",
            "createNotesLimits",
            "properties",
            "maxMediaBindingsPerNote",
            "const",
        ),
        0,
    ),
    (
        ("createNotes", "maxMediaBindingsTotal"),
        (
            "$defs",
            "createNotesLimits",
            "properties",
            "maxMediaBindingsTotal",
            "const",
        ),
        0,
    ),
    (
        ("createNotes", "requestEnvelopeMaxUtf8Bytes"),
        ("$defs", "createNotesLimits", "properties", "maxEnvelopeUtf8Bytes", "const"),
        0,
    ),
    (
        ("scanFirstFields", "duplicateHitsPerCandidateMaxItems"),
        (
            "$defs",
            "createSnapshotLimits",
            "properties",
            "maxNoteIdsPerCandidate",
            "const",
        ),
        0,
    ),
    (
        ("scanFirstFields", "duplicateHitsTotalMaxItems"),
        ("$defs", "createSnapshotLimits", "properties", "maxTotalNoteIds", "const"),
        0,
    ),
    (
        ("createNotes", "maxNotes"),
        ("$defs", "createNotesRequest", "properties", "notes", "maxItems"),
        0,
    ),
    (
        ("createNotes", "maxNotes"),
        ("$defs", "createNotesResult", "properties", "results", "maxItems"),
        0,
    ),
)


def _path_value(root: Any, path: tuple[str | int, ...]) -> Any:
    value = root
    for component in path:
        value = value[component]
    return value


def _numeric_schema_bound_paths(value: Any, path: _SchemaPath = ()) -> set[_SchemaPath]:
    result: set[_SchemaPath] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = (*path, key)
            if (
                key in {"maxLength", "maxItems", "maxProperties", "maximum", "const"}
                and type(child) is int
            ):
                result.add(child_path)
            result.update(_numeric_schema_bound_paths(child, child_path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            result.update(_numeric_schema_bound_paths(child, (*path, index)))
    return result


def _schema_limit_mismatches(schema: dict[str, Any]) -> list[str]:
    mismatches: list[str] = []
    for manifest_path, schema_path, offset in _ANKI_SCHEMA_LIMIT_BINDINGS:
        expected = _path_value(ANKI_LIMITS_V1, manifest_path) + offset
        actual = _path_value(schema, schema_path)
        if actual != expected:
            mismatches.append(
                f"{'/'.join(map(str, schema_path))}: {actual} != {expected} "
                f"from {'/'.join(manifest_path)}"
            )
    return mismatches


def test_every_anki_schema_numeric_bound_is_mapped_to_limits_manifest(
    schemas: dict[str, dict[str, Any]],
) -> None:
    schema_paths = [binding[1] for binding in _ANKI_SCHEMA_LIMIT_BINDINGS]
    assert len(schema_paths) == len(set(schema_paths))
    assert set(schema_paths) == _numeric_schema_bound_paths(schemas["anki"])
    assert _schema_limit_mismatches(schemas["anki"]) == []


def test_anki_schema_limit_drift_is_reported_at_the_exact_path(
    schemas: dict[str, dict[str, Any]],
) -> None:
    drifted = deepcopy(schemas["anki"])
    drifted["$defs"]["knownVocabularyCursor"]["properties"]["token"]["maxLength"] += 1

    assert _schema_limit_mismatches(drifted) == [
        "$defs/knownVocabularyCursor/properties/token/maxLength: 1025 != 1024 "
        "from scanFirstFields/knownCursorMaxCodePoints"
    ]


def test_create_notes_card_limit_is_required_and_exact(
    schemas: dict[str, dict[str, Any]],
) -> None:
    limits_schema = schemas["anki"]["$defs"]["createNotesLimits"]
    limits = {
        "maxNotes": 100,
        "maxFieldsPerNote": 64,
        "maxCardsPerNote": 64,
        "maxFieldNameUtf8Bytes": 256,
        "maxFieldValueUtf8Bytes": 98304,
        "maxTagsPerNote": 64,
        "maxTagUtf8Bytes": 256,
        "maxTagsUtf8BytesPerNote": 8192,
        "maxNoteContentUtf8Bytes": 131072,
        "maxTotalContentUtf8Bytes": 393216,
        "maxMediaBindingsPerNote": 8000,
        "maxMediaBindingsTotal": 8000,
        "maxEnvelopeUtf8Bytes": 524288,
    }
    validator = Draft202012Validator(limits_schema)
    validator.validate(limits)

    for invalid in (63, 65, None):
        candidate = dict(limits)
        if invalid is None:
            candidate.pop("maxCardsPerNote")
        else:
            candidate["maxCardsPerNote"] = invalid
        with pytest.raises(ValidationError):
            validator.validate(candidate)


def test_provider_model_text_limits_are_explicit_local_constants() -> None:
    target = ANKI_LIMITS_V1["targetModel"]
    per_value_keys = {
        "cssMaxUtf8Bytes",
        "latexPreMaxUtf8Bytes",
        "latexPostMaxUtf8Bytes",
        "templateQuestionFormatMaxUtf8Bytes",
        "templateAnswerFormatMaxUtf8Bytes",
        "templateBrowserQuestionFormatMaxUtf8Bytes",
        "templateBrowserAnswerFormatMaxUtf8Bytes",
    }

    assert {target[key] for key in per_value_keys} == {262144}
    assert target["providerTextTotalMaxUtf8Bytes"] == 4194304


def test_note_media_bindings_and_release_acknowledgement_are_strict(
    schemas: dict[str, dict[str, Any]],
) -> None:
    note_validator = Draft202012Validator(
        {
            "$schema": schemas["anki"]["$schema"],
            "$ref": "#/$defs/note",
            "$defs": schemas["anki"]["$defs"],
        }
    )
    note = {
        "clientNoteId": "note_" + "d" * 32,
        "fields": {"Expression": "猫"},
        "tags": [],
        "mediaBindings": [
            {
                "assetId": "asset_" + "c" * 32,
                "actualFilename": "voice_provider.opus",
            }
        ],
        "duplicateCandidate": {
            "key": "猫",
            "firstField": "猫",
            "occurrence": 0,
        },
    }
    note_validator.validate(note)

    for mutation in ("missing", "unknown", "markup", "mixedCaseMarkup"):
        invalid = deepcopy(note)
        if mutation == "missing":
            invalid.pop("mediaBindings")
        elif mutation == "unknown":
            invalid["mediaBindings"][0]["unexpected"] = True
        elif mutation == "markup":
            invalid["mediaBindings"][0]["actualFilename"] = "[sound:x.opus]"
        else:
            invalid["mediaBindings"][0]["actualFilename"] = "<ImG src=x>"
        with pytest.raises(ValidationError):
            note_validator.validate(invalid)

    release_validator = Draft202012Validator(
        {
            "$schema": schemas["anki"]["$schema"],
            "$ref": "#/$defs/releaseRunStateRequest",
            "$defs": schemas["anki"]["$defs"],
        }
    )
    release = {
        "runId": "run_" + "a" * 32,
        "requestId": "anki_" + "b" * 32,
        "acknowledgeTerminalResponses": False,
    }
    release_validator.validate(release)
    for invalid_value in (None, 0, "false"):
        invalid = dict(release)
        if invalid_value is None:
            invalid.pop("acknowledgeTerminalResponses")
        else:
            invalid["acknowledgeTerminalResponses"] = invalid_value
        with pytest.raises(ValidationError):
            release_validator.validate(invalid)


def test_schema_code_point_bound_is_distinct_from_runtime_utf8_bound(
    schemas: dict[str, dict[str, Any]],
) -> None:
    path_limit = ANKI_LIMITS_V1["storeMedia"]["sourcePathMaxUtf8Bytes"]
    multibyte_path = "/" + "界" * (path_limit // 3 + 1)
    payload = {
        "runId": "run_" + "a" * 32,
        "requestId": "anki_" + "b" * 32,
        "assets": [
            {
                "assetId": "asset_" + "c" * 32,
                "sourcePath": multibyte_path,
                "preferredName": "media",
                "requestedFilename": "media.opus",
                "purpose": "card",
                "mediaKind": "audio",
                "expectedSizeBytes": 0,
                "expectedSha256": "0" * 64,
            }
        ],
        "limits": {
            "maxAssets": 50,
            "maxAssetBytes": 67108864,
            "maxTotalBytes": 67108864,
        },
    }

    assert len(multibyte_path) < path_limit
    assert len(multibyte_path.encode("utf-8")) > path_limit
    Draft202012Validator(schemas["anki"]).validate(payload)

    payload["assets"][0]["sourcePath"] = "/" + "x" * path_limit
    with pytest.raises(ValidationError):
        Draft202012Validator(schemas["anki"]).validate(payload)


@pytest.mark.parametrize(
    "case",
    _source_path_cases(),
    ids=lambda case: case["id"],
)
def test_media_source_path_schema_matches_shared_contract_corpus(
    case: dict[str, Any], schemas: dict[str, dict[str, Any]]
) -> None:
    source_path_schema = schemas["anki"]["$defs"]["mediaAsset"]["properties"][
        "sourcePath"
    ]
    errors = list(Draft202012Validator(source_path_schema).iter_errors(case["path"]))

    assert (not errors) is case["schemaValid"]


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


def test_config_schema_accepts_blank_desktop_field_mappings(
    schemas: dict[str, dict[str, Any]],
) -> None:
    Draft202012Validator(schemas["config"]).validate(
        {
            "settings": {
                "anki_fields": {"word": "", "sentence": ""},
                "card_type": "click",
                "card_type_marker_fields": {"click": ""},
            }
        }
    )


def test_config_schema_rejects_animated_screenshots(
    schemas: dict[str, dict[str, Any]],
    initialized_bridge_home: Path,
) -> None:
    payload = _full_config_payload(initialized_bridge_home)
    payload["settings"]["screenshot_animated"] = True

    with pytest.raises(ValidationError):
        Draft202012Validator(schemas["config"]).validate(payload)


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
        {"settings": {"anki_deck_name": ""}},
        {"settings": {"anki_note_type": " Lapis"}},
        {"settings": {"anki_note_type": "Lapis\u200e"}},
        {"settings": {"anki_fields": {"glossary": " Glossary"}}},
        {"settings": {"excluded_decks": [""]}},
        {"settings": {"excluded_decks": ["Known", "Known"]}},
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
            "deckName": "Japanese::Mining",
            "modelName": "Lapis",
            "requiredFields": [],
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
                "cursor": None,
                "limits": {
                    "maxScannedNotes": 256,
                    "maxTotalScannedNotes": 100000,
                    "maxItems": 256,
                    "maxItemUtf8Bytes": 65536,
                    "maxTotalUtf8Bytes": 262144,
                },
            },
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "scope": {
                "kind": "duplicates",
                "modelName": "Lapis",
                "firstFieldName": "Expression",
                "deckName": None,
                "candidates": [
                    {"key": "猫", "firstField": "猫"},
                    {"key": "犬", "firstField": "<b>犬</b>"},
                ],
                "occurrences": [0, 1],
                "invalidateBaselineToken": None,
                "limits": {
                    "maxHitsPerCandidate": 100,
                    "maxTotalHits": 1000,
                    "maxItemUtf8Bytes": 65536,
                    "maxTotalUtf8Bytes": 1048576,
                },
            },
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "firstFields": ["<b>猫</b>", "[sound:dog.mp3]犬"],
            "scannedNotes": 2,
            "nextCursor": {"ordinal": 1, "token": "opaque-page-token"},
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "rawFirstFieldHits": [
                [{"noteId": 10, "firstField": "<b>猫</b>"}],
                [],
            ],
            "baselineToken": "baseline_" + "e" * 32,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "assets": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "sourcePath": "/data/user/0/app/cache/audio.opus",
                    "preferredName": "猫_ab12cd34ef56",
                    "requestedFilename": "猫_ab12cd34ef56.opus",
                    "purpose": "card",
                    "mediaKind": "audio",
                    "expectedSizeBytes": 5,
                    "expectedSha256": "0" * 64,
                }
            ],
            "limits": {
                "maxAssets": 50,
                "maxAssetBytes": 67108864,
                "maxTotalBytes": 67108864,
            },
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "results": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "status": "stored",
                    "actualFilename": "猫_ab12cd34ef56_provider.opus",
                },
                {
                    "assetId": "asset_" + "d" * 32,
                    "status": "failed",
                    "error": {
                        "code": "media_store_failed",
                        "message": "media insert failed",
                        "retryable": True,
                    },
                },
            ],
            "error": None,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "results": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "status": "uncertain",
                }
            ],
            "error": {
                "code": "post_commit_uncertain",
                "message": "media insert outcome is unknown",
                "retryable": False,
            },
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "deckName": "Japanese::Mining",
            "modelName": "Lapis",
            "firstFieldName": "Expression",
            "baselineToken": "baseline_" + "e" * 32,
            "duplicateScope": {
                "kind": "collection",
                "limits": {
                    "maxNoteIdsPerCandidate": 100,
                    "maxTotalNoteIds": 1000,
                },
            },
            "limits": {
                "maxNotes": 100,
                "maxFieldsPerNote": 64,
                "maxCardsPerNote": 64,
                "maxFieldNameUtf8Bytes": 256,
                "maxFieldValueUtf8Bytes": 98304,
                "maxTagsPerNote": 64,
                "maxTagUtf8Bytes": 256,
                "maxTagsUtf8BytesPerNote": 8192,
                "maxNoteContentUtf8Bytes": 131072,
                "maxTotalContentUtf8Bytes": 393216,
                "maxMediaBindingsPerNote": 8000,
                "maxMediaBindingsTotal": 8000,
                "maxEnvelopeUtf8Bytes": 524288,
            },
            "notes": [
                {
                    "clientNoteId": "note_" + "d" * 32,
                    "fields": {"Expression": "猫", "Sentence": "猫だ"},
                    "tags": ["auto-mined"],
                    "mediaBindings": [],
                    "duplicateCandidate": {
                        "key": "猫",
                        "firstField": "猫",
                        "occurrence": 0,
                    },
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
            "acknowledgeTerminalResponses": True,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "state": "released",
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "state": "deferred",
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "state": "absent",
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "operation": "releaseRunState",
            "code": "internal_error",
            "message": "registry cleanup callback failed",
            "retryable": False,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "operation": "verifyTarget",
            "code": "post_commit_uncertain",
            "message": "deck creation outcome could not be proven",
            "retryable": False,
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
            "scope": {
                "kind": "duplicates",
                "modelName": "Lapis",
                "deckName": None,
            },
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "assets": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "sourcePath": "relative/file.mp3",
                    "preferredName": "../file.mp3",
                    "requestedFilename": "file.mp3",
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
                    "status": "uncertain",
                    "actualFilename": "must-not-be-known.opus",
                }
            ],
            "error": {
                "code": "post_commit_uncertain",
                "message": "unknown outcome",
                "retryable": False,
            },
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
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "deckName": " Mining",
            "modelName": "Lapis",
            "requiredFields": ["Expression"],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "deckName": "Mining",
            "modelName": "Lapis\u200e",
            "requiredFields": ["Expression"],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "results": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "status": "stored",
                    "actualFilename": "[sound:clip.opus]",
                }
            ],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "results": [
                {
                    "assetId": "asset_" + "c" * 32,
                    "status": "failed",
                    "error": {
                        "code": "permission_required",
                        "message": "grant permission",
                        "retryable": False,
                    },
                }
            ],
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "released": True,
        },
        {
            "runId": "run_" + "a" * 32,
            "requestId": "anki_" + "b" * 32,
            "state": "pending",
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
