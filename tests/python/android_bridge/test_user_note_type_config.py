from __future__ import annotations

import os
from dataclasses import replace
from pathlib import Path

import pytest
from android_bridge.config_map import (
    AndroidPaths,
    map_config_settings,
    validate_anki_request_config,
)
from android_bridge.protocol import BridgeProtocolError


def _paths(tmp_path: Path) -> AndroidPaths:
    return AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native")


@pytest.fixture(autouse=True)
def _bootstrap_mapper(initialized_bridge_home: Path) -> None:
    assert Path(os.environ["ANKI_MINER_HOME"]).resolve() == initialized_bridge_home.resolve()


# The exact wire shape the Kotlin EngineSettingsSnapshotMapper emits: all 18 logical keys are
# always present, the seven required keys carry the user's field names, every optional key is
# present but blank rather than absent (so no key can inherit a desktop default via overlay).
_USER_FIELD_MAP = {
    "word": "Expression",
    "sentence": "Sentence",
    "definition": "Definition",
    "picture": "Picture",
    "audio": "SentenceAudio",
    "expression_furigana": "ExpressionFurigana",
    "sentence_furigana": "SentenceFurigana",
    "glossary": "",
    "expression_reading": "",
    "sentence_reading": "",
    "expression_audio": "",
    "pitch_position": "",
    "pitch_category": "",
    "pitch_graph": "",
    "pitch_text": "",
    "frequency": "",
    "frequency_sort": "",
    "source": "",
}


def test_user_field_map_covers_all_eighteen_logical_keys() -> None:
    # Pins the fixture to the 18-key contract shared with AnkiFieldKeys.ALL, so a drift in either
    # side surfaces here rather than silently narrowing the round-trip below.
    assert len(_USER_FIELD_MAP) == 18


def test_empty_note_type_snapshot_is_rejected_fail_closed(tmp_path: Path) -> None:
    # An unconfigured target arrives as anki_note_type="". The bridge must reject that blank at the
    # snapshot boundary rather than construct a config with it, so mining can never silently fall
    # back to a first-party "Anki Miner" model.
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings(
            {"anki_note_type": "", "anki_fields": _USER_FIELD_MAP},
            _paths(tmp_path),
        )
    assert error.value.code == "invalid_config_field"
    assert "anki_note_type" in str(error.value)


def test_empty_note_type_is_rejected_by_request_config_validation() -> None:
    # The Anki callback adapter re-validates the frozen engine config directly (anki_adapter calls
    # validate_anki_request_config); a blank note type must also fail closed on that path.
    from anki_miner.config import AnkiMinerConfig

    config = replace(AnkiMinerConfig(), anki_note_type="")
    with pytest.raises(BridgeProtocolError) as error:
        validate_anki_request_config(config)
    assert error.value.code == "invalid_config_field"
    assert "anki_note_type" in str(error.value)


def test_user_note_type_with_partial_field_map_round_trips(tmp_path: Path) -> None:
    snapshot = map_config_settings(
        {"anki_note_type": "Lapis", "anki_fields": _USER_FIELD_MAP},
        _paths(tmp_path),
    )
    config = snapshot.engine_config

    assert config.anki_note_type == "Lapis"
    # The mapped word is the note type's first/dedup field.
    assert config.anki_fields["word"] == "Expression"
    assert config.anki_fields["audio"] == "SentenceAudio"
    # Blank optional keys stay blank; the {**defaults, **value} overlay never reintroduces a
    # desktop default for a key the user left unmapped.
    assert config.anki_fields["glossary"] == ""
    assert config.anki_fields["pitch_text"] == ""
    # Nothing outside the supplied 18 keys leaks in, and every value round-trips verbatim.
    assert dict(config.anki_fields) == _USER_FIELD_MAP


def test_user_note_type_partial_field_map_passes_request_config_validation() -> None:
    # The same partial map (required keys mapped, optional keys blank) must satisfy the adapter's
    # direct validation once a real note type name is present.
    from anki_miner.config import AnkiMinerConfig

    config = replace(
        AnkiMinerConfig(),
        anki_note_type="Lapis",
        anki_fields=dict(_USER_FIELD_MAP),
    )

    validate_anki_request_config(config)
