"""Deck and note-type names accept ZWNJ and ZWJ, and nothing else in category C (AU-020)."""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import pytest
from android_bridge.anki_adapter import _expect_bounded_canonical_name
from android_bridge.config_map import AndroidPaths, map_config_settings, refuses_target_name_code_point
from android_bridge.protocol import BridgeProtocolError
from test_anki_adapter import (
    FakeKotlinAnki,
    _adapter,
    _config,
    _known_scopes,
    isolated_services_namespace,  # noqa: F401  (autouse: host lane lacks desktop services)
)

PERSIAN_DECK = "واژه‌ها"
EMOJI_NAME = "\U0001f9d1‍\U0001f393 Vocab"
REFUSED_NAMES = [
    "Mining‮",
    "‏Mining",
    "﻿Mining",
    "Mi​ning",
    "Wort­teil",
    "Mining⁦",
    "Mining\u0007",
    "Mining",
]


def _paths(home: Path, tmp_path: Path) -> AndroidPaths:
    return AndroidPaths(home, tmp_path / "cache", tmp_path / "native")


def test_only_the_two_joiners_are_relaxed() -> None:
    assert not refuses_target_name_code_point(0x200C)
    assert not refuses_target_name_code_point(0x200D)
    for code_point in (0x200B, 0x200E, 0x200F, 0x202A, 0x202E, 0x2066, 0xFEFF, 0x00AD, 0x061C, 0x0007, 0xE000, 0x0378):
        assert refuses_target_name_code_point(code_point), hex(code_point)


def test_config_accepts_joiners_in_deck_note_type_and_excluded_decks(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    config = map_config_settings(
        {"anki_deck_name": PERSIAN_DECK, "anki_note_type": EMOJI_NAME, "excluded_decks": [EMOJI_NAME]},
        _paths(initialized_bridge_home, tmp_path),
    ).engine_config

    assert config.anki_deck_name == PERSIAN_DECK
    assert config.anki_note_type == EMOJI_NAME
    assert tuple(config.excluded_decks) == (EMOJI_NAME,)


@pytest.mark.parametrize("name", REFUSED_NAMES)
@pytest.mark.parametrize("field", ["anki_deck_name", "anki_note_type", "excluded_decks"])
def test_config_still_refuses_other_category_c_in_names(
    field: str, name: str, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    value: object = [name] if field == "excluded_decks" else name
    # A valid note type, so only the field under test can fail (the note-type case overrides it).
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"anki_note_type": "Lapis", field: value}, _paths(initialized_bridge_home, tmp_path))
    assert error.value.code == "invalid_config_field"
    assert str(error.value).startswith(field), str(error.value)


@pytest.mark.parametrize(
    "settings",
    [{"anki_fields": {"word": "Ex‌pression"}}, {"card_type_marker_fields": {"click": "Mark‍er"}}],
    ids=["field-name", "marker-field"],
)
def test_field_and_marker_names_keep_the_full_category_c_rule(
    settings: dict, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"anki_note_type": "Lapis", **settings}, _paths(initialized_bridge_home, tmp_path))
    assert error.value.code == "invalid_config_field"
    ((field, _value),) = settings.items()
    assert str(error.value).startswith(field), str(error.value)


def test_adapter_name_check_relaxes_only_when_asked() -> None:
    assert (
        _expect_bounded_canonical_name(
            PERSIAN_DECK, context="deck", max_bytes=1024, code="invalid_anki_request", target_name=True
        )
        == PERSIAN_DECK
    )
    with pytest.raises(BridgeProtocolError):
        _expect_bounded_canonical_name(PERSIAN_DECK, context="field", max_bytes=256, code="invalid_anki_request")
    with pytest.raises(BridgeProtocolError):
        _expect_bounded_canonical_name(
            "Mining‮", context="deck", max_bytes=1024, code="invalid_anki_request", target_name=True
        )


def test_verify_target_sends_joiner_deck_and_note_type_names(initialized_bridge_home: Path) -> None:
    config = replace(_config(initialized_bridge_home), anki_deck_name=PERSIAN_DECK, anki_note_type=EMOJI_NAME)
    kotlin = FakeKotlinAnki()

    _adapter(config, kotlin, target_verified=False).verify_card_target()

    # FakeKotlinAnki validates every request against anki.schema.json first.
    payload = kotlin.requests_for("ankiVerifyTarget")[0]["payload"]
    assert payload["deckName"] == PERSIAN_DECK
    assert payload["modelName"] == EMOJI_NAME


def test_known_vocabulary_scan_accepts_joiner_note_types_and_excluded_decks(initialized_bridge_home: Path) -> None:
    config = replace(_config(initialized_bridge_home), excluded_decks=(PERSIAN_DECK,))
    kotlin = FakeKotlinAnki()
    kotlin.known_note_types = [{"modelId": 7, "name": "لغت‌ها", "fieldNames": ["Expression", "Meaning"]}]

    _adapter(config, kotlin).get_existing_vocabulary()

    assert _known_scopes(kotlin)[0]["excludedDecks"] == [PERSIAN_DECK]
