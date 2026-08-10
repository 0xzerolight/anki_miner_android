from __future__ import annotations

import ast
import json
import logging
import os
from dataclasses import fields, replace
from pathlib import Path

import android_bridge.config_map as config_map
import pytest
from android_bridge.config_map import (
    _LOCALAUDIO_URL,
    AndroidPaths,
    exposed_config_fields,
    map_config_json,
    map_config_settings,
)
from android_bridge.protocol import BridgeProtocolError, encode_message


def _paths(tmp_path: Path) -> AndroidPaths:
    return AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native")


def test_localaudio_remote_origin_allowlist_is_fail_closed() -> None:
    assert frozenset() == config_map._LOCALAUDIO_APPROVED_AUDIO_ORIGINS


@pytest.fixture(autouse=True)
def _bootstrap_mapper(initialized_bridge_home: Path) -> None:
    assert Path(os.environ["ANKI_MINER_HOME"]).resolve() == initialized_bridge_home.resolve()


def _path_overrides(paths: AndroidPaths) -> dict[str, Path]:
    home = paths.files_dir
    return {
        "media_temp_folder": paths.cache_dir / "anki_miner_temp",
        "jmdict_path": home / "JMdict_e",
        "dicts_root": home / "dicts",
        "audio_packs_root": home / "audio_packs",
        "pitch_accent_path": home / "pitch_accent.csv",
        "pitch_root": home / "pitch",
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


def test_empty_snapshot_preserves_all_102_desktop_defaults_except_targeted_android_overrides(
    tmp_path: Path,
) -> None:
    from anki_miner.config import AnkiMinerConfig, AudioSourceEntry

    paths = _paths(tmp_path)
    mapped = map_config_settings({}, paths)
    base = AnkiMinerConfig()
    expected = replace(
        base,
        **_path_overrides(paths),
        # The localaudio (localhost) source is injected as the default PRIMARY;
        # with no imported pack it is the sole expression-audio entry.
        expression_audio_chain=(AudioSourceEntry(kind="custom_json", url=_LOCALAUDIO_URL, enabled=True),),
        reading_tts_enabled=False,
        reading_tts_google_enabled=False,
        reading_tts_papago_enabled=False,
    )

    desktop_fields = fields(AnkiMinerConfig)
    assert len(desktop_fields) == 102
    assert {field.name: getattr(mapped.engine_config, field.name) for field in desktop_fields} == {
        field.name: getattr(expected, field.name) for field in desktop_fields
    }
    assert mapped.android_tts_enabled is False


def test_ffmpeg_binary_verification_logs_non_executable_and_missing_paths(
    caplog: pytest.LogCaptureFixture,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    paths = _paths(tmp_path)
    paths.native_library_dir.mkdir()
    ffmpeg = paths.native_library_dir / "libffmpeg.so"
    ffmpeg.write_bytes(b"not executable")
    ffmpeg.chmod(0o644)
    caplog.set_level(logging.ERROR, logger="android_bridge.config_map")

    map_config_settings({}, paths)

    failures = [record for record in caplog.records if "ffmpeg_binary_verification_failed" in record.msg]
    assert len(failures) == 2
    assert all(record.exc_info for record in failures)
    assert all("outcome=fail" in record.getMessage() for record in failures)
    assert any(
        "tool=ffmpeg" in failure.getMessage() and "reason=not_executable" in failure.getMessage()
        for failure in failures
    )
    assert any(
        "tool=ffprobe" in failure.getMessage() and "reason=stat_failed" in failure.getMessage() for failure in failures
    )

    caplog.clear()
    original_resolve = Path.resolve

    def fail_native_binary_resolution(path: Path, strict: bool = False) -> Path:
        if path.name == "libffmpeg.so":
            raise RuntimeError("simulated symlink loop")
        if path.name == "libffprobe.so":
            raise OSError("simulated resolution failure")
        return original_resolve(path, strict=strict)

    monkeypatch.setattr(Path, "resolve", fail_native_binary_resolution)

    mapped = map_config_settings({}, paths)

    assert mapped.engine_config.ffmpeg_location == ffmpeg
    assert mapped.engine_config.ffprobe_location == paths.native_library_dir / "libffprobe.so"
    resolution_failures = [record for record in caplog.records if "ffmpeg_binary_verification_failed" in record.msg]
    assert len(resolution_failures) == 2
    assert {record.exc_info[0] for record in resolution_failures if record.exc_info} == {
        OSError,
        RuntimeError,
    }
    messages = [record.getMessage() for record in resolution_failures]
    assert any(f"tool=ffmpeg path={ffmpeg} reason=stat_failed" in message for message in messages)
    assert any(
        f"tool=ffprobe path={paths.native_library_dir / 'libffprobe.so'} reason=stat_failed" in message
        for message in messages
    )


def test_typed_fields_and_entries_are_reconstructed(tmp_path: Path) -> None:
    from anki_miner.config import AudioSourceEntry, ChainEntry, FreqEntry

    snapshot = map_config_settings(
        {
            "anki_fields": {"expression_audio": "WordAudio"},
            "allowed_pos": ["名詞", "動詞"],
            "excluded_wordsets": ["given-names", "place-names"],
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
    assert config.excluded_wordsets == ("given-names", "place-names")
    assert isinstance(config.blacklist_path, Path)
    assert config.dictionary_chain == (
        ChainEntry(kind="indexed", dict_id="jmdict-english"),
        ChainEntry(kind="jisho", dict_id=None, enabled=False),
    )
    assert config.frequency_chain == (FreqEntry(source_id="bccwj"),)
    # localaudio (localhost) is prepended as the default PRIMARY source, ahead of
    # the imported pack, which becomes the ordered fallback.
    assert config.expression_audio_chain == (
        AudioSourceEntry(kind="custom_json", url=_LOCALAUDIO_URL, enabled=True),
        AudioSourceEntry(kind="pack", pack_id="my-pack"),
    )
    assert config.anki_fields["expression_audio"] == "WordAudio"
    assert config.anki_fields["word"] == "Expression"


def test_subtitle_annotation_strip_can_be_turned_off(tmp_path: Path) -> None:
    inherited = map_config_settings({}, _paths(tmp_path)).engine_config
    overridden = map_config_settings(
        {"strip_subtitle_annotations": False},
        _paths(tmp_path),
    ).engine_config

    assert inherited.strip_subtitle_annotations is True
    assert overridden.strip_subtitle_annotations is False


@pytest.mark.parametrize("kind", ["jpod101", "googletts", "custom", "custom_json"])
def test_network_expression_audio_kinds_are_rejected(kind: str, tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings(
            {"expression_audio_chain": [{"kind": kind, "pack_id": None}]},
            _paths(tmp_path),
        )
    assert error.value.code == "unsupported_audio_source"


def test_animated_screenshots_are_accepted_with_pinned_tuning(tmp_path: Path) -> None:
    mapped = map_config_settings(
        {
            "screenshot_animated": True,
            "screenshot_animated_format": "webp",
            "screenshot_animated_clip_duration": 2.0,
            "screenshot_animated_quality": 30,
        },
        _paths(tmp_path),
    ).engine_config

    assert mapped.screenshot_animated is True
    assert mapped.screenshot_animated_format == "webp"
    assert mapped.screenshot_animated_clip_duration == 2.0
    assert mapped.screenshot_animated_quality == 30
    # Pinned, not exposed: the engine must never see a desktop default that
    # Android has not validated on a phone.  match_audio is off because it
    # silently overrides clip_duration, which the user can see.
    assert mapped.screenshot_animated_fps == 20
    assert mapped.screenshot_animated_height == 720
    assert mapped.screenshot_animated_match_audio is False


def test_animated_screenshots_default_to_off(tmp_path: Path) -> None:
    mapped = map_config_settings({}, _paths(tmp_path)).engine_config

    assert mapped.screenshot_animated is False


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("screenshot_animated_clip_duration", 12.0),
        ("screenshot_animated_clip_duration", 0.1),
        ("screenshot_animated_quality", 101),
        ("screenshot_animated_quality", -1),
        ("screenshot_animated_format", "gif"),
    ],
)
def test_animated_screenshot_tuning_outside_the_supported_range_is_rejected(
    tmp_path: Path,
    field: str,
    value: object,
) -> None:
    with pytest.raises(BridgeProtocolError):
        map_config_settings({field: value}, _paths(tmp_path))


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("screenshot_animated_fps", 30),
        ("screenshot_animated_height", 480),
    ],
)
def test_pinned_animated_screenshot_fields_are_not_settable(
    tmp_path: Path,
    field: str,
    value: object,
) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({field: value}, _paths(tmp_path))

    assert error.value.code == "unknown_config_field"


def test_match_audio_is_settable_and_still_pins_fps_and_height(tmp_path: Path) -> None:
    """Desktop offers this control, so Android forwards it instead of pinning it off.

    fps and height stay pinned either way: they exist so a card mined on the phone matches one
    mined on the desktop, which is unrelated to the clip's time range.
    """
    mapped = map_config_settings(
        {"screenshot_animated": True, "screenshot_animated_match_audio": True},
        _paths(tmp_path),
    )

    assert mapped.engine_config.screenshot_animated_match_audio is True
    assert mapped.engine_config.screenshot_animated_fps == 20
    assert mapped.engine_config.screenshot_animated_height == 720


def test_match_audio_defaults_off_when_the_snapshot_omits_it(tmp_path: Path) -> None:
    mapped = map_config_settings({"screenshot_animated": True}, _paths(tmp_path))

    assert mapped.engine_config.screenshot_animated_match_audio is False


def test_android_tts_flag_satisfies_vendored_gate_without_enabling_papago(
    tmp_path: Path,
) -> None:
    mapped = map_config_settings({"reading_tts_enabled": True}, _paths(tmp_path))

    assert mapped.android_tts_enabled is True
    assert mapped.engine_config.reading_tts_enabled is True
    # Compatibility-only provider bit for AudioStage.reading_tts_active. The
    # Android reading bridge directly injects its Kotlin callback adapter.
    assert mapped.engine_config.reading_tts_google_enabled is True
    assert mapped.engine_config.reading_tts_papago_enabled is False


def test_android_tts_composition_has_no_desktop_network_fetcher_imports() -> None:
    source_root = Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge"
    imported_modules: set[str] = set()
    for filename in ("config_map.py", "mining.py", "reading_mining.py", "sentence_audio.py"):
        tree = ast.parse((source_root / filename).read_text(encoding="utf-8"))
        imported_modules.update(
            alias.name for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names
        )
        imported_modules.update(node.module or "" for node in ast.walk(tree) if isinstance(node, ast.ImportFrom))

    forbidden = ("gtts", "google_translate", "papago", "sentence_tts_fetcher")
    assert all(not any(part in module for part in forbidden) for module in imported_modules)


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
        ({"anki_deck_name": ""}, "invalid_config_field"),
        ({"anki_note_type": " Lapis"}, "invalid_config_field"),
        ({"anki_note_type": "Lapis\n"}, "invalid_config_field"),
        ({"anki_note_type": "Lapis\u200e"}, "invalid_config_field"),
        ({"anki_note_type": "Cafe\u0301"}, "invalid_config_field"),
        ({"anki_fields": {"glossary": " Glossary"}}, "invalid_config_field"),
        ({"excluded_decks": [""]}, "invalid_config_field"),
        ({"excluded_decks": ["Known", "Known"]}, "invalid_config_field"),
        ({"excluded_decks": ["Known "]}, "invalid_config_field"),
        ({"excluded_wordsets": ["../escape"]}, "invalid_config_field"),
        ({"excluded_wordsets": ["surnames", "surnames"]}, "invalid_config_field"),
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
    raw = '{"schemaVersion":1.0,"type":"config.snapshot","payload":{"settings":{"audio_bitrate":128.0}}}'

    mapped = map_config_json(
        raw,
        str(paths.files_dir),
        str(paths.cache_dir),
        str(paths.native_library_dir),
    )

    assert mapped.engine_config.audio_bitrate == 128
    assert type(mapped.engine_config.audio_bitrate) is int


def test_blank_field_and_active_marker_mappings_are_preserved(tmp_path: Path) -> None:
    mapped = map_config_settings(
        {
            "anki_fields": {"word": "", "sentence": ""},
            "card_type": "click",
            "card_type_marker_fields": {"click": ""},
        },
        _paths(tmp_path),
    )
    config = mapped.engine_config

    assert config.anki_fields["word"] == ""
    assert config.anki_fields["sentence"] == ""
    assert config.card_type_marker_fields["click"] == ""


def test_android_marker_map_shuts_out_the_engine_jpmn_defaults(tmp_path: Path) -> None:
    # What Kotlin emits once the user picks a mode: every mode named, only the active one filled.
    # An omitted key would let the overlay reinstate IsClickCard and friends on a note type that
    # may not have them.
    mapped = map_config_settings(
        {
            "card_type": "click",
            "card_type_marker_fields": {
                "word_and_sentence": "",
                "click": "MyClickMarker",
                "sentence": "",
                "audio": "",
            },
        },
        _paths(tmp_path),
    )
    config = mapped.engine_config

    assert config.card_type == "click"
    assert config.card_type_marker_fields["click"] == "MyClickMarker"
    assert config.card_type_marker_fields["audio"] == ""


def test_duplicate_anki_destinations_are_rejected_at_snapshot_boundary(tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings(
            {"anki_fields": {"word": "Sentence", "sentence": "Sentence"}},
            _paths(tmp_path),
        )

    assert error.value.code == "invalid_config_field"
    assert "Sentence" in str(error.value)
    assert "word" in str(error.value)
    assert "sentence" in str(error.value)


def test_active_card_marker_cannot_overwrite_word_at_snapshot_boundary(tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings(
            {
                "card_type": "click",
                "card_type_marker_fields": {"click": "Expression"},
            },
            _paths(tmp_path),
        )

    assert error.value.code == "invalid_config_field"
    assert "Expression" in str(error.value)
    assert "word" in str(error.value)
    assert "card_type_marker_fields.click" in str(error.value)


def test_nonintegral_float_is_not_an_integer_config_field(tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"audio_bitrate": 1.5}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"


def test_checked_in_schema_allowlist_matches_mapper() -> None:
    schema_path = (
        Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/config-snapshot.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    schema_fields = set(schema["$defs"]["settings"]["properties"])

    assert schema_fields == set(exposed_config_fields()) | {"reading_tts_enabled"}


def test_checked_in_schema_has_exact_mapping_keys_chain_shapes_and_absolute_paths() -> None:
    from anki_miner.config import AnkiMinerConfig

    schema_path = (
        Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/config-snapshot.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    definitions = schema["$defs"]
    defaults = AnkiMinerConfig()

    assert definitions["ankiFields"]["additionalProperties"] is False
    assert set(definitions["ankiFields"]["properties"]) == set(defaults.anki_fields)
    assert set(definitions["cardTypeMarkerFields"]["properties"]) == set(defaults.card_type_marker_fields)
    assert definitions["indexedDictionary"]["required"] == ["kind", "dict_id"]
    assert definitions["frequencySource"]["required"] == ["source_id"]
    assert definitions["audioPack"]["properties"]["kind"] == {"const": "pack"}
    assert definitions["absolutePathOrNull"]["oneOf"][1]["pattern"] == "^/"
    assert definitions["settings"]["properties"]["excluded_decks"]["uniqueItems"] is True
    assert definitions["settings"]["properties"]["anki_deck_name"] == {"$ref": "#/$defs/canonicalNonEmptyString"}
    assert definitions["ankiFields"]["properties"]["word"] == {"$ref": "#/$defs/optionalMappedField"}
    assert definitions["ankiFields"]["properties"]["glossary"] == {"$ref": "#/$defs/optionalMappedField"}


@pytest.mark.parametrize(
    "value",
    [
        "\u001cMining",
        "Mining\u001c",
        "\u1e0a\u0323",
    ],
    ids=["python-whitespace-leading", "python-whitespace-trailing", "nfc-trap"],
)
def test_canonical_names_follow_pinned_unicode_contract(value: str, tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"anki_deck_name": value}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"


def test_contract_validators_do_not_use_host_unicode_or_strip_tables() -> None:
    source_root = Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge"
    for filename in ("config_map.py", "anki_adapter.py"):
        source = (source_root / filename).read_text(encoding="utf-8")
        tree = ast.parse(source)
        imported_roots = {
            alias.name.split(".", 1)[0]
            for node in ast.walk(tree)
            if isinstance(node, ast.Import)
            for alias in node.names
        } | {(node.module or "").split(".", 1)[0] for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)}
        forbidden_calls = [
            node.func.attr
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr in {"strip", "lstrip", "rstrip"}
        ]

        assert "unicodedata" not in imported_roots, filename
        assert forbidden_calls == [], filename
