"""The bridge's mining-language layer: validation, per-language paths and config bases.

Japanese never touches the profile registry, so those tests run on both lanes.
Every other language needs a built profile, which the host lane cannot import
(the registry pulls in ``subtitle_parser`` and with it pysubs2): those tests skip
there and run on the runtime lane.
"""

from __future__ import annotations

import os
from dataclasses import fields
from pathlib import Path

import pytest
from android_bridge import languages
from android_bridge.config_map import AndroidPaths, exposed_config_fields, map_config_settings
from android_bridge.protocol import BridgeProtocolError

# A non-ja profile defaults ``anki_note_type`` to "", which config_map refuses:
# until the user picks a note type no run can start. Tests that need a mapped
# config supply one.
_NOTE_TYPE = {"anki_note_type": "Basic"}


def _runtime_lane() -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane")


def _paths(tmp_path: Path) -> AndroidPaths:
    return AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native")


@pytest.fixture(autouse=True)
def _bootstrap(initialized_bridge_home: Path) -> None:
    assert Path(os.environ["ANKI_MINER_HOME"]).resolve() == initialized_bridge_home.resolve()


def _available() -> tuple[str, ...]:
    from anki_miner.languages.registry import available_languages

    return available_languages()


# ---------------------------------------------------------------- validation


def test_japanese_is_valid_without_the_registry() -> None:
    assert languages.validated_language("ja") == "ja"
    assert languages.payload_language({}) == "ja"
    assert languages.payload_language({"language": "ja"}) == "ja"


@pytest.mark.parametrize("value", [None, 5, "", "JA", "ja-JP", " ja", "japanese"])
def test_malformed_language_values_are_refused(value: object) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        languages.validated_language(value)
    assert error.value.code == "unsupported_language"


@pytest.mark.parametrize("code", ["xx", "eo"])
def test_a_code_without_a_vendored_profile_is_refused(code: str) -> None:
    # Every desktop language is vendored now; xx and eo are no desktop language
    # at all: the engine would silently mine them as Japanese, the bridge must not.
    _runtime_lane()
    with pytest.raises(BridgeProtocolError) as error:
        languages.validated_language(code)
    assert error.value.code == "unsupported_language"


def test_every_vendored_language_validates() -> None:
    _runtime_lane()
    assert _available() == (
        "ja",
        "ko",
        "zh",
        "en",
        "ca",
        "de",
        "pt",
        "fr",
        "es",
        "it",
        "nl",
        "nb",
        "ro",
        "el",
        "fi",
        "hu",
        "hr",
        "sv",
        "pl",
        "lt",
        "da",
        "tr",
        "id",
        "ru",
        "ar",
        "th",
        "fa",
        "sl",
        "uk",
        "vi",
        "yue",
        "he",
    )
    for code in _available():
        assert languages.validated_language(code) == code


def test_without_language_strips_only_the_language_key() -> None:
    assert languages.without_language({"language": "he", "operationId": "x"}) == {"operationId": "x"}


# ---------------------------------------------------------------- known-words path


def test_known_words_path_mirrors_desktop_resolve_known_words_db_path(tmp_path: Path) -> None:
    base = tmp_path / "known_words.db"
    assert languages.known_words_db_path(base, "ja") == base
    assert languages.known_words_db_path(base, "he") == tmp_path / "known_words.he.db"
    assert languages.known_words_db_path(base, "th") == tmp_path / "known_words.th.db"


# ---------------------------------------------------------------- config base per language


def test_explicit_japanese_maps_exactly_like_an_absent_language(tmp_path: Path) -> None:
    implicit = map_config_settings(dict(_NOTE_TYPE), _paths(tmp_path)).engine_config
    explicit = map_config_settings({"language": "ja", **_NOTE_TYPE}, _paths(tmp_path)).engine_config
    assert explicit == implicit
    assert explicit.language == "ja"


def test_config_map_refuses_an_unavailable_language(tmp_path: Path) -> None:
    _runtime_lane()
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"language": "eo", **_NOTE_TYPE}, _paths(tmp_path))
    assert error.value.code == "unsupported_language"


def test_config_map_refuses_a_malformed_language(tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"language": 1}, _paths(tmp_path))
    assert error.value.code == "unsupported_language"


def test_a_non_ja_snapshot_starts_from_its_profile_not_from_ja_defaults(tmp_path: Path) -> None:
    """Every vendored profile, so a newly vendored language's defaults drift here first."""
    _runtime_lane()
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS

    for code in _available():
        if code == "ja":
            continue
        config = map_config_settings({"language": code, **_NOTE_TYPE}, _paths(tmp_path)).engine_config
        profile = get_profile(code)

        assert config.language == code
        assert dict(config.language_stash) == {}, code
        for name in LANGUAGE_SCOPED_FIELDS:
            actual = getattr(config, name)
            expected = profile.scoped_defaults[name]
            if name == "anki_note_type":
                assert actual == "Basic", code
            elif name == "expression_audio_chain":
                # The profile's default network voice is a cut kind: the device voice stands in.
                assert [entry.kind for entry in actual] == ["android_tts"], code
            elif name == "anki_fields":
                assert dict(actual) == dict(expected), code
            else:
                assert actual == expected, (code, name)
        # Nothing Japanese-shaped leaks in: no jmdict/Jisho chain, no Lapis, no ja POS gate.
        assert config.dictionary_chain == (), code
        assert "名詞" not in config.allowed_pos, code


def test_a_non_ja_snapshot_without_a_note_type_is_refused(tmp_path: Path) -> None:
    _runtime_lane()
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"language": "he"}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"
    assert "anki_note_type" in str(error.value)


def test_profile_extra_card_fields_are_accepted_only_for_their_language(tmp_path: Path) -> None:
    _runtime_lane()
    mapped = map_config_settings(
        {"language": "he", **_NOTE_TYPE, "anki_fields": {"transliteration": "Translit"}},
        _paths(tmp_path),
    ).engine_config
    assert mapped.anki_fields["transliteration"] == "Translit"

    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({**_NOTE_TYPE, "anki_fields": {"transliteration": "Translit"}}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"
    assert str(error.value).startswith("anki_fields:")


def test_script_variant_is_limited_to_the_profile_offer(tmp_path: Path) -> None:
    assert (
        map_config_settings({**_NOTE_TYPE, "script_variant": ""}, _paths(tmp_path)).engine_config.script_variant == ""
    )
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({**_NOTE_TYPE, "script_variant": "simplified"}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"
    assert str(error.value).startswith("script_variant:")
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({**_NOTE_TYPE, "script_variant": 1}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"
    assert str(error.value).startswith("script_variant:")


def test_script_variant_is_refused_for_a_non_ja_language_without_variants(tmp_path: Path) -> None:
    _runtime_lane()
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"language": "he", **_NOTE_TYPE, "script_variant": "br"}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"


def test_reading_tone_color_is_a_boolean_setting(tmp_path: Path) -> None:
    assert map_config_settings(
        {**_NOTE_TYPE, "reading_tone_color": True}, _paths(tmp_path)
    ).engine_config.reading_tone_color
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({**_NOTE_TYPE, "reading_tone_color": "yes"}, _paths(tmp_path))
    assert error.value.code == "invalid_config_field"
    assert str(error.value).startswith("reading_tone_color:")


def test_language_stash_never_crosses_the_wire(tmp_path: Path) -> None:
    assert "language_stash" not in exposed_config_fields()
    with pytest.raises(BridgeProtocolError) as error:
        map_config_settings({"language_stash": {}}, _paths(tmp_path))
    assert error.value.code == "unknown_config_field"


# ---------------------------------------------------------------- scoped defaults wire


def test_scoped_defaults_cover_every_android_scoped_field() -> None:
    _runtime_lane()
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS

    for code in _available():
        wire = languages.scoped_defaults_wire(get_profile(code))
        assert set(wire) == set(LANGUAGE_SCOPED_FIELDS) & exposed_config_fields()
        # Only the YouTube downloader's two fields stay behind: Android has no downloader.
        assert set(LANGUAGE_SCOPED_FIELDS) - set(wire) == {"downloader_subtitle_langs", "downloader_audio_lang"}


def test_scoped_defaults_round_trip_through_config_map(tmp_path: Path) -> None:
    """Kotlin stores scopedDefaults and sends them back: config_map must rebuild the profile's values."""
    _runtime_lane()
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS

    for code in _available():
        profile = get_profile(code)
        wire = languages.scoped_defaults_wire(profile)
        settings = {**wire, "language": code}
        if not settings["anki_note_type"]:
            settings.update(_NOTE_TYPE)
        config = map_config_settings(settings, _paths(tmp_path)).engine_config

        for name in set(LANGUAGE_SCOPED_FIELDS) & set(wire):
            actual = getattr(config, name)
            expected = profile.scoped_defaults[name]
            if name == "anki_note_type" and not expected:
                continue
            if name == "expression_audio_chain":
                expected = tuple(entry for entry in expected if entry.kind == "pack")
            if name == "anki_fields":
                actual, expected = dict(actual), dict(expected)
            assert actual == expected, (code, name)


def test_ja_scoped_defaults_carry_the_desktop_ja_values() -> None:
    _runtime_lane()
    from anki_miner.languages.registry import get_profile

    wire = languages.scoped_defaults_wire(get_profile("ja"))
    # Desktop v3.8.0: no language picks a note type (the setup wizard does), and Jisho is gone.
    assert wire["anki_note_type"] == ""
    assert wire["dictionary_chain"] == [{"kind": "indexed", "dict_id": "jmdict-english", "enabled": True}]
    # Scoped since v3.8.0, at the config defaults every language shared while they were global.
    assert wire["pitch_category_format"] == "jp"
    assert wire["max_sentence_chars"] == 0
    assert wire["card_type_marker_fields"] == {
        "word_and_sentence": "IsWordAndSentenceCard",
        "click": "IsClickCard",
        "sentence": "IsSentenceCard",
        "audio": "IsAudioCard",
    }
    assert wire["anki_fields"]["language"] == ""
    # jpod101 + googletts: both cut kinds, so the Android default chain is empty.
    assert wire["expression_audio_chain"] == []
    assert wire["known_words_match_kana_variants"] is True


def test_base_config_fields_are_the_engine_fields() -> None:
    _runtime_lane()
    from anki_miner.config import AnkiMinerConfig

    config = languages.base_config("he")
    assert {field.name for field in fields(config)} == {field.name for field in fields(AnkiMinerConfig)}
    assert config.language == "he"
    assert dict(config.language_stash) == {}


def test_config_schema_anki_field_keys_are_ja_plus_every_vendored_profile_extra() -> None:
    """A newly vendored language's card fields fail here until the schema lists them."""
    _runtime_lane()
    import json

    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.registry import get_profile

    schema_path = (
        Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/config-snapshot.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    extras = {spec.key for code in _available() for spec in get_profile(code).extra_card_fields}
    expected = set(AnkiMinerConfig().anki_fields) | extras
    assert set(schema["$defs"]["ankiFields"]["properties"]) == expected
    for code in _available():
        assert set(get_profile(code).scoped_defaults["anki_fields"]) <= expected


# ---------------------------------------------------------------- language.profiles op


def _profiles() -> dict[str, dict[str, object]]:
    import json

    from android_bridge import boundary
    from android_bridge.protocol import encode_message

    response = json.loads(boundary.dispatch(encode_message("language.profiles", {})))
    assert response["type"] == "language.profiles.result", response
    return {entry["code"]: entry for entry in response["payload"]["profiles"]}


def _mining_validator():
    import json

    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource

    root = Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas"
    mining = json.loads((root / "mining.schema.json").read_text(encoding="utf-8"))
    config = json.loads((root / "config-snapshot.schema.json").read_text(encoding="utf-8"))
    registry = Registry().with_resources(
        [(schema["$id"], Resource.from_contents(schema)) for schema in (mining, config)]
    )
    return Draft202012Validator(mining, registry=registry)


def test_language_profiles_lists_every_vendored_language_in_registry_order() -> None:
    _runtime_lane()
    import json

    from android_bridge import boundary
    from android_bridge.protocol import encode_message

    raw = boundary.dispatch(encode_message("language.profiles", {}))
    message = json.loads(raw)
    assert [entry["code"] for entry in message["payload"]["profiles"]] == list(_available())
    assert list(_mining_validator().iter_errors(message)) == []
    assert list(_mining_validator().iter_errors(json.loads(encode_message("language.profiles", {})))) == []


def test_japanese_profile_entry_keeps_every_japanese_surface() -> None:
    _runtime_lane()
    ja = _profiles()["ja"]
    assert ja["displayName"] == "日本語"
    assert ja["englishName"] == "Japanese"
    assert ja["unavailableReason"] is None
    assert ja["requiresUnidic"] is True
    assert ja["scriptVariants"] == []
    assert ja["contentDirection"] == "ltr"
    assert ja["contentLanguage"] == "ja"
    assert ja["speechLanguage"] == "ja"
    assert ja["audioTrackCodes"] == ["ja", "japanese", "jp", "jpn"]
    assert "pitch" in ja["capabilities"]
    assert ja["extraCardFields"] == []


def test_hebrew_profile_entry_describes_an_rtl_language_with_its_own_card_fields() -> None:
    _runtime_lane()
    he = _profiles()["he"]
    assert he["englishName"] == "Hebrew"
    assert he["unavailableReason"] is None
    assert he["requiresUnidic"] is False
    assert he["contentDirection"] == "rtl"
    assert he["contentLanguage"] == "he"
    assert he["audioTrackCodes"] == ["he", "heb", "hebrew", "iw"]
    assert "pitch" not in he["capabilities"]
    assert "tone_color" not in he["capabilities"]
    assert {field["key"] for field in he["extraCardFields"]} == {
        "transliteration",
        "root",
        "binyan",
        "noun_gender",
        "noun_plural",
        "pos",
    }
    assert he["extraCardFields"][0] == {
        "key": "transliteration",
        "capability": "hebrew_transliteration",
        "placeholder": "Transliteration",
        "rawHtml": False,
    }
    defaults = he["scopedDefaults"]
    assert defaults["anki_note_type"] == ""
    assert defaults["dictionary_chain"] == []
    # Google's Hebrew voice is the desktop default; Android cannot build it.
    assert defaults["expression_audio_chain"] == []
    assert defaults["use_subtitle_regex_filter"] is True
    assert defaults["allowed_pos"] == ["WORD", "NOUN", "VERB", "ADJ", "ADV"]


def test_a_regional_varietys_run_speaks_with_that_regions_voice() -> None:
    """Desktop ``pt_gtts_lang``: European Portuguese speaks pt-PT, Brazilian the Brazilian voice."""
    _runtime_lane()
    from anki_miner.languages.registry import get_profile

    assert languages.speech_language_for("pt", "pt") == "pt-PT"
    assert languages.speech_language_for("pt", "br") == "pt-BR"
    assert languages.speech_language_for("pt") == "pt"
    assert languages.speech_language(get_profile("pt")) == "pt"
    assert languages.speech_language_for("zh", "traditional") == "zh"
    assert languages.speech_language_for("yue") == "yue"
    assert languages.speech_language_for("ja") == "ja"


@pytest.mark.parametrize(("code", "direction"), [("ar", "rtl"), ("fa", "rtl"), ("ko", "ltr")])
def test_a_language_waiting_for_its_data_pack_reports_a_download_reason(code: str, direction: str) -> None:
    _runtime_lane()
    entry = _profiles()[code]
    assert entry["unavailableReason"] == "language_data_required"
    assert entry["contentDirection"] == direction


def test_installed_data_clears_the_reason(initialized_bridge_home: Path) -> None:
    _runtime_lane()
    import shutil

    component = initialized_bridge_home / "language_packs" / "ar" / "calima_msa"
    component.mkdir(parents=True)
    try:
        (component / "morphology.db").write_bytes(b"")
        (component / "LICENSE").write_text("GPL-2.0", encoding="utf-8")
        assert _profiles()["ar"]["unavailableReason"] is None
    finally:
        shutil.rmtree(initialized_bridge_home / "language_packs")
    assert _profiles()["ar"]["unavailableReason"] == "language_data_required"


def test_a_missing_bundled_engine_is_unsupported_not_downloadable(monkeypatch: pytest.MonkeyPatch) -> None:
    """th's pack is wheels (pythainlp, tzdata): they ship in the APK, so no download can repair them."""
    _runtime_lane()
    from types import SimpleNamespace

    without_pythainlp = languages.bundled_modules() - {"pythainlp"}
    monkeypatch.setattr(languages, "bundled_modules", lambda: without_pythainlp)
    assert languages.unavailable_reason_code(languages.get_profile("th")) == "language_unsupported"
    no_pack = SimpleNamespace(code="he", unavailable_reason=lambda: "broken")
    assert languages.unavailable_reason_code(no_pack) == "language_unsupported"


def test_no_engine_sentence_crosses_in_a_profile() -> None:
    _runtime_lane()
    import json

    raw = json.dumps(list(_profiles().values()), ensure_ascii=False)
    assert "Settings" not in raw
    assert "Download it" not in raw


def test_language_profiles_takes_no_fields() -> None:
    import json

    from android_bridge import boundary
    from android_bridge.protocol import encode_message

    response = json.loads(boundary.dispatch(encode_message("language.profiles", {"language": "ja"})))
    assert response["type"] == "bridge.error"
    assert response["payload"]["code"] == "invalid_language_profiles_request"


def test_every_vendored_pack_component_is_downloadable_data_or_ships_in_the_apk() -> None:
    """Decision 2: data is downloaded, code is bundled. A new pack fails here until classified."""
    _runtime_lane()
    from importlib.util import find_spec

    from anki_miner.services.language_pack_installer import load_pack

    seen: set[tuple[str, str]] = set()
    for code in _available():
        pack = load_pack(code)
        for component in pack.components if pack is not None else ():
            key = (code, component.import_name)
            seen.add(key)
            if key in languages.DOWNLOADABLE_DATA_COMPONENTS:
                assert find_spec(component.import_name) is None, key
            else:
                assert find_spec(component.import_name) is not None, f"{key} is neither data nor bundled"
    split = set(languages.SPLIT_DATA_COMPONENTS)
    assert seen >= languages.DOWNLOADABLE_DATA_COMPONENTS - split
    # Split models are no pack component: the component they come from ships in the APK.
    assert {(code, source) for (code, _), source in languages.SPLIT_DATA_COMPONENTS.items()} <= seen - split
    assert not split & seen


@pytest.mark.parametrize("code", ["vi", "yue"])
def test_split_models_gate_the_language_until_they_are_installed(
    code: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The engine finds vi/yue code importable from the APK; only the bridge sees the missing models."""
    _runtime_lane()
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.config import paths

    monkeypatch.setattr(paths, "ANKI_MINER_HOME", tmp_path)
    profile = languages.get_profile(code)
    assert profile.unavailable_reason() is None
    assert languages.unavailable_reason_code(profile) == "language_data_required"

    (entry,) = load_resource_catalog(code).language_data
    directory = tmp_path / "language_packs" / code / entry.import_name
    for sentinel in entry.install.sentinels:
        (directory / sentinel).parent.mkdir(parents=True, exist_ok=True)
        (directory / sentinel).write_bytes(b"model")

    assert languages.unavailable_reason_code(profile) is None


def test_the_engine_overrides_read_the_split_models_the_catalog_installs() -> None:
    _runtime_lane()
    from anki_miner.languages.vi import tokenizer as vi_tokenizer
    from anki_miner.languages.yue import tokenizer as yue_tokenizer

    assert {("vi", vi_tokenizer.MODEL_DATA), ("yue", yue_tokenizer.MODEL_DATA)} == set(languages.SPLIT_DATA_COMPONENTS)


def test_the_bridge_never_puts_downloaded_packs_on_sys_path() -> None:
    """Decision 2: no downloaded code. Desktop imports pack code from ``filesDir``; Android never does."""
    root = Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge"
    for source in root.rglob("*.py"):
        assert "ensure_language_packs_on_syspath" not in source.read_text(encoding="utf-8"), source
