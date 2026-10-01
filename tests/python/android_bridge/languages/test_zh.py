"""Chinese: the jieba/pypinyin/opencc engine shipped in the APK, and its catalog."""

from __future__ import annotations

import json
import os
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("jieba", reason="runtime dependency lane: zh engine packages")

FIXTURE = Path(__file__).parent / "fixtures" / "zh" / "tokens.json"


@pytest.fixture(autouse=True)
def _home(initialized_bridge_home: Path) -> Path:
    return initialized_bridge_home


def _paths(tmp_path: Path) -> object:
    from android_bridge.config_map import AndroidPaths

    return AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native")


def _zh_config(tmp_path: Path, **settings: object) -> object:
    from android_bridge.config_map import map_config_settings

    return map_config_settings(
        {"language": "zh", "anki_note_type": "Basic", **settings}, _paths(tmp_path)
    ).engine_config


def test_the_profile_loads_and_is_available() -> None:
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages, get_profile

    profile = get_profile("zh")

    assert "zh" in available_languages()
    assert profile.english_name == "Chinese"
    assert unavailable_reason_code(profile) is None


def test_every_pack_component_ships_in_the_apk() -> None:
    from anki_miner.services.language_pack_installer import component_path, component_satisfied, load_pack

    pack = load_pack("zh")

    assert {component.import_name for component in pack.components} == {"jieba", "pypinyin", "opencc"}
    for component in pack.components:
        # Engine code, never downloaded: nothing is on disk under language_packs/zh ...
        assert component_path("zh", component.import_name) is None
        # ... and every component is satisfied by the APK's own packages.
        assert component_satisfied("zh", component), component.import_name


def test_the_tagger_tokenises_the_smoke_sentence() -> None:
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.tagger_provider import get_tagger

    sentence = get_profile("zh").smoke_sentence
    tokens = get_tagger("zh").parse(sentence)

    assert "".join(token.surface for token in tokens) == sentence
    assert [token.surface for token in tokens][-2:] == ["苹果", "。"]


def test_tokens_and_readings_match_the_desktop_export() -> None:
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.tagger_provider import get_tagger

    document = json.loads(FIXTURE.read_text(encoding="utf-8"))
    tagger = get_tagger("zh")
    reading = get_profile("zh").reading

    assert len(document["cases"]) == 14
    for case in document["cases"]:
        actual = [
            [token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma, reading.word_reading(token)]
            for token in tagger.parse(case["sentence"])
        ]
        assert actual == case["tokens"], case["id"]


def test_switching_to_chinese_applies_its_scoped_defaults(tmp_path: Path) -> None:
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.switching import switch_language

    defaults = get_profile("zh").scoped_defaults
    switched = switch_language(AnkiMinerConfig(), "zh")
    mapped = _zh_config(tmp_path)

    for config in (switched, mapped):
        assert config.language == "zh"
        assert config.reading_tone_color is True
        assert config.script_variant == ""
        assert config.allowed_pos == defaults["allowed_pos"]
        assert config.excluded_subtypes == defaults["excluded_subtypes"]


def test_the_traditional_field_survives_build_note(tmp_path: Path) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from anki_miner.languages.registry import get_profile
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    config = _zh_config(tmp_path)
    config = replace(config, anki_fields={**dict(config.anki_fields), "expression_traditional": "Traditional"})
    (hook,) = [hook for hook in get_profile("zh").render_hooks if hook.field_names() == ("expression_traditional",)]
    extra = hook.render(SimpleNamespace(mined_form="苹果"), config=config)
    word = TokenizedWord(
        surface="苹果",
        lemma="苹果",
        reading="píng guǒ",
        sentence="我吃了苹果。",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="n",
    )
    card = CardPayload(word=word, media=MediaData(), definition="apple", extra_fields=extra)

    note = build_note(card, config, set(), **_note_builder_kwargs(config)).note

    assert extra == {"expression_traditional": "蘋果"}
    assert note["fields"]["Traditional"] == "蘋果"


def test_known_words_use_their_own_database_from_the_start(tmp_path: Path) -> None:
    from android_bridge.languages import known_words_db_path

    assert known_words_db_path(tmp_path / "known_words.db", "zh") == tmp_path / "known_words.zh.db"


def test_every_desktop_catalog_row_is_pinned_or_excluded() -> None:
    from android_bridge.resource_catalog import load_resource_catalog

    assert_catalog_complete(
        "zh",
        pinned={
            "cc-cedict": "cc-cedict-2026-09-30",
            "opensubtitles-zh-word": "opensubtitles-zh-word-2026.09.20",
        },
        excluded={},
    )
    # The engine ships in the APK: zh has nothing for the language-data installer.
    assert not [resource for resource in load_resource_catalog("zh").resources if resource.kind == "language-data"]
