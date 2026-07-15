from __future__ import annotations

import json
import os
from dataclasses import fields, replace
from pathlib import Path

import pytest

from android_bridge.config_map import (
    AndroidPaths,
    exposed_config_fields,
    map_config_json,
    map_config_settings,
)
from android_bridge.protocol import BridgeProtocolError, encode_message


def _paths(tmp_path: Path) -> AndroidPaths:
    return AndroidPaths(
        Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native"
    )


@pytest.fixture(autouse=True)
def _bootstrap_mapper(initialized_bridge_home: Path) -> None:
    assert (
        Path(os.environ["ANKI_MINER_HOME"]).resolve()
        == initialized_bridge_home.resolve()
    )


def _path_overrides(paths: AndroidPaths) -> dict[str, Path]:
    home = paths.files_dir
    return {
        "media_temp_folder": paths.cache_dir / "anki_miner_temp",
        "jmdict_path": home / "JMdict_e",
        "dicts_root": home / "dicts",
        "audio_packs_root": home / "audio_packs",
        "pitch_accent_path": home / "pitch_accent.csv",
        "freqs_root": home / "freqs",
        "known_words_db_path": home / "known_words.db",
        "stats_db_path": home / "stats.db",
        "log_path": home / "anki_miner.log",
        "ffmpeg_location": paths.native_library_dir / "libffmpeg.so",
        "ffprobe_location": paths.native_library_dir / "libffprobe.so",
        "asr_models_root": home / "asr_models",
        "cuda_libs_root": home / "cuda_libs",
        "onnx_pack_root": home / "onnx_pack",
        "bin_root": home / "bin",
        "themes_root": home / "themes",
    }


def test_empty_snapshot_preserves_all_97_desktop_defaults_except_targeted_android_overrides(
    tmp_path: Path,
) -> None:
    from anki_miner.config import AnkiMinerConfig

    paths = _paths(tmp_path)
    mapped = map_config_settings({}, paths)
    base = AnkiMinerConfig()
    expected = replace(
        base,
        **_path_overrides(paths),
        expression_audio_chain=(),
        reading_tts_enabled=False,
        reading_tts_google_enabled=False,
        reading_tts_papago_enabled=False,
    )

    desktop_fields = fields(AnkiMinerConfig)
    assert len(desktop_fields) == 97
    assert {
        field.name: getattr(mapped.engine_config, field.name)
        for field in desktop_fields
    } == {field.name: getattr(expected, field.name) for field in desktop_fields}
    assert mapped.android_tts_enabled is False


def test_typed_fields_and_entries_are_reconstructed(tmp_path: Path) -> None:
    from anki_miner.config import AudioSourceEntry, ChainEntry, FreqEntry

    snapshot = map_config_settings(
        {
            "anki_fields": {"expression_audio": "WordAudio"},
            "allowed_pos": ["名詞", "動詞"],
            "blacklist_path": str(tmp_path / "files" / "blacklist.txt"),
            "dictionary_chain": [
                {"kind": "indexed", "dict_id": "jmdict-english"},
                {"kind": "jisho", "dict_id": None, "enabled": False},
            ],
            "frequency_chain": [{"source_id": "bccwj", "enabled": True}],
            "expression_audio_chain": [{"kind": "pack", "pack_id": "my-pack"}],
        },
        _paths(tmp_path),
    )
    config = snapshot.engine_config

    assert config.allowed_pos == ("名詞", "動詞")
    assert isinstance(config.blacklist_path, Path)
    assert config.dictionary_chain == (
        ChainEntry(kind="indexed", dict_id="jmdict-english"),
        ChainEntry(kind="jisho", dict_id=None, enabled=False),
    )
    assert config.frequency_chain == (FreqEntry(source_id="bccwj"),)
    assert config.expression_audio_chain == (
        AudioSourceEntry(kind="pack", pack_id="my-pack"),
    )
    assert config.anki_fields["expression_audio"] == "WordAudio"
    assert config.anki_fields["word"] == "Expression"


@pytest.mark.parametrize("kind", ["jpod101", "googletts", "custom", "custom_json"])
def test_network_expression_audio_kinds_are_rejected(kind: str, tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings(
            {"expression_audio_chain": [{"kind": kind, "pack_id": None}]},
            _paths(tmp_path),
        )
    assert error.value.code == "unsupported_audio_source"


def test_legacy_android_tts_flag_is_ephemeral_and_cannot_compose_fetchers(
    tmp_path: Path,
) -> None:
    mapped = map_config_settings({"reading_tts_enabled": True}, _paths(tmp_path))

    assert mapped.android_tts_enabled is True
    assert mapped.engine_config.reading_tts_enabled is False
    assert mapped.engine_config.reading_tts_google_enabled is False
    assert mapped.engine_config.reading_tts_papago_enabled is False


def test_conflicting_tts_aliases_are_rejected(tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings(
            {"reading_tts_enabled": True},
            _paths(tmp_path),
            android_tts_enabled=False,
        )
    assert error.value.code == "conflicting_android_tts_flags"


@pytest.mark.parametrize(
    ("settings", "code"),
    [
        ({"youtube_max_duration_s": 10}, "unknown_config_field"),
        ({"audio_bitrate": True}, "invalid_config_field"),
        ({"jisho_delay": 0.1}, "invalid_config_field"),
        ({"blacklist_path": "relative.txt"}, "invalid_config_field"),
        ({"blacklist_path": ""}, "invalid_config_field"),
        ({"anki_fields": {"invented": "Field"}}, "invalid_config_field"),
        ({"frequency_chain": [{"source_id": "../escape"}]}, "invalid_config_field"),
    ],
)
def test_unknown_or_wrongly_typed_settings_fail_closed(
    settings: dict[str, object],
    code: str,
    tmp_path: Path,
) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings(settings, _paths(tmp_path))
    assert error.value.code == code


def test_public_json_entry_point_requires_versioned_snapshot(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    raw = encode_message(
        "config.snapshot",
        {"settings": {"audio_format": "opus"}, "androidTtsEnabled": True},
    )

    mapped = map_config_json(
        raw,
        str(paths.files_dir),
        str(paths.cache_dir),
        str(paths.native_library_dir),
    )

    assert mapped.engine_config.audio_format == "opus"
    assert mapped.android_tts_enabled is True


def test_draft_integer_floats_normalize_for_schema_and_config_fields(
    tmp_path: Path,
) -> None:
    paths = _paths(tmp_path)
    raw = (
        '{"schemaVersion":1.0,"type":"config.snapshot",'
        '"payload":{"settings":{"audio_bitrate":128.0}}}'
    )

    mapped = map_config_json(
        raw,
        str(paths.files_dir),
        str(paths.cache_dir),
        str(paths.native_library_dir),
    )

    assert mapped.engine_config.audio_bitrate == 128
    assert type(mapped.engine_config.audio_bitrate) is int


def test_nonintegral_float_is_not_an_integer_config_field(tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"audio_bitrate": 1.5}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"


def test_checked_in_schema_allowlist_matches_mapper() -> None:
    schema_path = (
        Path(__file__).resolve().parents[3]
        / "app/src/main/python/android_bridge/schemas/config-snapshot.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    schema_fields = set(schema["$defs"]["settings"]["properties"])

    assert schema_fields == set(exposed_config_fields()) | {"reading_tts_enabled"}


def test_checked_in_schema_has_exact_mapping_keys_chain_shapes_and_absolute_paths() -> (
    None
):
    from anki_miner.config import AnkiMinerConfig

    schema_path = (
        Path(__file__).resolve().parents[3]
        / "app/src/main/python/android_bridge/schemas/config-snapshot.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    definitions = schema["$defs"]
    defaults = AnkiMinerConfig()

    assert definitions["ankiFields"]["additionalProperties"] is False
    assert set(definitions["ankiFields"]["properties"]) == set(defaults.anki_fields)
    assert set(definitions["cardTypeMarkerFields"]["properties"]) == set(
        defaults.card_type_marker_fields
    )
    assert definitions["indexedDictionary"]["required"] == ["kind", "dict_id"]
    assert definitions["frequencySource"]["required"] == ["source_id"]
    assert definitions["audioPack"]["properties"]["kind"] == {"const": "pack"}
    assert definitions["absolutePathOrNull"]["oneOf"][1]["pattern"] == "^/"
