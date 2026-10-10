"""anki.notetype.fill: desktop's "Fill in automatically" proposal for one note type.

The vendored ``anki_miner.services`` package imports pysubs2, which the host
lane lacks: every answered fill skips there and runs on the runtime lane. Only
request refusals, decided before any engine import, run on both.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from android_bridge import boundary
from android_bridge.protocol import encode_message
from jsonschema import Draft202012Validator

SCHEMA_PATH = (
    Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/note-type-fill.schema.json"
)

# Byte-exact upstream field lists, in the order each note type ships them. The
# vendored presets own detection; these pin what the bridge answers for them.
LAPIS_FIELDS = [
    "Expression",
    "ExpressionFurigana",
    "ExpressionReading",
    "ExpressionAudio",
    "SelectionText",
    "MainDefinition",
    "DefinitionPicture",
    "Sentence",
    "SentenceFurigana",
    "SentenceAudio",
    "Picture",
    "Glossary",
    "Hint",
    "IsWordAndSentenceCard",
    "IsClickCard",
    "IsSentenceCard",
    "IsAudioCard",
    "PitchPosition",
    "PitchCategories",
    "Frequency",
    "FreqSort",
    "MiscInfo",
]
KIKU_FIELDS = [*LAPIS_FIELDS, "RelatedExpression", "SentenceTranslation"]
SENREN_FIELDS = [
    "word",
    "reading",
    "sentence",
    "sentenceFurigana",
    "sentenceTranslation",
    "sentenceCard",
    "audioCard",
    "notes",
    "selectionText",
    "definition",
    "wordAudio",
    "sentenceAudio",
    "picture",
    "glossary",
    "hint",
    "pitchAccents",
    "pitchPositions",
    "pitchCategories",
    "frequencies",
    "freqSort",
    "miscInfo",
    "dictionaryPreference",
]
AMN_FIELDS = [
    *LAPIS_FIELDS,
    "SentenceTranslation",
    "Language",
    "Pinyin",
    "Jyutping",
    "Traditional",
    "MeasureWord",
    "Hanja",
    "HanViet",
    "Gender",
    "Article",
    "Plural",
    "PartOfSpeech",
    "AspectPair",
    "Root",
    "Binyan",
    "Transliteration",
    "Romanization",
    "Colloquial",
    "PresentStem",
    "Classifier",
    "Affixes",
    "Formal",
    "Grammar",
    "Segmentation",
]


def _runtime_lane() -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane")


@pytest.fixture(autouse=True)
def _bootstrap(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home


@pytest.fixture(scope="module")
def validator() -> Draft202012Validator:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def _fill(field_names: list[str], language: str = "ja", **extra: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {"fieldNames": field_names, "language": language, **extra}
    return json.loads(boundary.dispatch(encode_message("anki.notetype.fill", payload)))


def _result(field_names: list[str], language: str = "ja") -> dict[str, Any]:
    _runtime_lane()
    message = _fill(field_names, language)
    assert message["type"] == "anki.notetype.fill.result", message
    return message["payload"]


@pytest.mark.parametrize(
    ("fields", "preset_id", "name"),
    [
        (LAPIS_FIELDS, "lapis", "Lapis"),
        (KIKU_FIELDS, "kiku", "Kiku"),
        (SENREN_FIELDS, "senren", "Senren"),
        (AMN_FIELDS, "anki_miner_note", "Anki Miner Note"),
    ],
)
def test_every_published_note_type_is_recognised_in_japanese(
    fields: list[str], preset_id: str, name: str, validator: Draft202012Validator
) -> None:
    payload = _result(fields)
    validator.validate(payload)
    assert payload["preset"]["id"] == preset_id
    assert payload["preset"]["name"] == name
    assert payload["preset"]["pitchCategoryFormat"] == "romaji"
    # Every value the preset maps is a field this note type ships.
    assert {value for value in payload["fields"].values() if value} <= set(fields)


def test_lapis_answer_carries_its_whole_map_and_card_settings() -> None:
    payload = _result(LAPIS_FIELDS)
    assert payload["preset"] == {
        "id": "lapis",
        "name": "Lapis",
        "pitchCategoryFormat": "romaji",
        "cardTypeMarkerFields": {
            "word_and_sentence": "IsWordAndSentenceCard",
            "click": "IsClickCard",
            "sentence": "IsSentenceCard",
            "audio": "IsAudioCard",
        },
        "supportedCardTypes": ["", "word_and_sentence", "click", "sentence", "audio"],
        "boldTargetInSentence": False,
    }
    assert payload["fields"]["word"] == "Expression"
    assert payload["fields"]["pitch_category"] == "PitchCategories"
    assert payload["fields"]["source"] == "MiscInfo"
    # "" is the preset's answer, not a gap: Lapis has no sentence-reading field.
    assert payload["fields"]["sentence_reading"] == ""
    assert payload["extraFields"] == {}


def test_senren_has_no_click_card_and_its_own_marker_names() -> None:
    preset = _result(SENREN_FIELDS)["preset"]
    assert preset["supportedCardTypes"] == ["", "sentence", "audio"]
    assert preset["cardTypeMarkerFields"] == {
        "word_and_sentence": "",
        "click": "",
        "sentence": "sentenceCard",
        "audio": "audioCard",
    }


def test_anki_miner_note_turns_bold_target_words_on() -> None:
    assert _result(AMN_FIELDS)["preset"]["boldTargetInSentence"] is True


def test_an_unknown_note_type_gets_the_keyword_pass(validator: Draft202012Validator) -> None:
    payload = _result(["Front", "Back", "Sentence", "Picture"])
    validator.validate(payload)
    assert payload["preset"] is None
    assert payload["fields"]["sentence"] == "Sentence"
    assert payload["fields"]["picture"] == "Picture"
    assert payload["fields"]["definition"] == ""


def test_lapis_under_another_language_is_refused() -> None:
    payload = _result(LAPIS_FIELDS, "de")
    assert payload["preset"] is None
    assert payload["fields"]["word"] == "Expression"


def test_anki_miner_note_applies_in_every_language(validator: Draft202012Validator) -> None:
    payload = _result(AMN_FIELDS, "de")
    validator.validate(payload)
    assert payload["preset"]["id"] == "anki_miner_note"
    assert payload["fields"]["language"] == "Language"
    assert payload["extraFields"]["pos"] == "PartOfSpeech"
    assert payload["extraFields"]["noun_gender"] == "Gender"


@pytest.mark.parametrize("language", ["de", "he"])
def test_a_field_named_pos_maps_to_part_of_speech_in_a_spaced_language(language: str) -> None:
    payload = _result(["Expression", "Sentence", "POS"], language)
    assert payload["preset"] is None
    assert payload["extraFields"]["pos"] == "POS"


@pytest.mark.parametrize(
    "payload",
    [
        {"language": "ja"},
        {"fieldNames": ["Front"]},
        {"fieldNames": ["Front"], "language": "ja", "noteTypeName": "Lapis"},
        {"fieldNames": [], "language": "ja"},
        {"fieldNames": "Front", "language": "ja"},
        {"fieldNames": ["Front", ""], "language": "ja"},
        {"fieldNames": ["Front", "Front"], "language": "ja"},
        {"fieldNames": ["Front", 1], "language": "ja"},
        {"fieldNames": [f"F{index}" for index in range(65)], "language": "ja"},
        {"fieldNames": ["x" * 257], "language": "ja"},
    ],
)
def test_a_malformed_request_is_refused(payload: dict[str, Any], validator: Draft202012Validator) -> None:
    message = json.loads(boundary.dispatch(encode_message("anki.notetype.fill", payload)))
    assert message["type"] == "bridge.error"
    assert message["payload"]["code"] == "invalid_note_type_fill_request"
    assert list(validator.iter_errors(payload))


@pytest.mark.parametrize("language", ["JA", "xx"])
def test_an_unknown_language_is_refused(language: str) -> None:
    if language == "xx":
        _runtime_lane()  # a well-formed code is looked up in the registry
    message = _fill(["Front"], language)
    assert message["type"] == "bridge.error"
    assert message["payload"]["code"] == "unsupported_language"


def test_the_request_shape_validates(validator: Draft202012Validator) -> None:
    validator.validate({"fieldNames": LAPIS_FIELDS, "language": "ja"})
