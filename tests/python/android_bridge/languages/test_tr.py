"""Turkish: zeyrek over nltk and regex, shipped in the APK; tokens equal desktop's own import path."""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from catalog_completeness import PENDING_PIN, assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("zeyrek", reason="runtime dependency lane: the Turkish engine ships in the APK")

FIXTURE = Path(__file__).parent / "fixtures" / "tr" / "tokens.jsonl"


@pytest.fixture(scope="module")
def tagger(initialized_bridge_home: Path) -> Any:
    del initialized_bridge_home
    from anki_miner.languages.tagger_provider import get_tagger

    return get_tagger("tr")


def _tokens(tagger: Any, text: str) -> list[list[object]]:
    """The exporter's token shape: punctuation and unknown words carry no ``lemma_pos``."""
    return [
        [
            token.surface,
            token.feature.pos1,
            token.feature.pos2,
            token.feature.lemma,
            list(getattr(token.feature, "lemma_pos", ())),
        ]
        for token in tagger(text)
    ]


def test_profile_loads_and_is_available(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge import languages
    from anki_miner.languages.registry import available_languages, get_profile

    profile = get_profile("tr")
    assert "tr" in available_languages()
    assert profile.english_name == "Turkish"
    assert languages.unavailable_reason_code(profile) is None
    assert languages.validated_language("tr") == "tr"


def test_tagger_tokenises_the_smoke_sentence(tagger: Any) -> None:
    from anki_miner.languages.registry import get_profile

    smoke = get_profile("tr").smoke_sentence
    tokens = tagger(smoke)
    assert [token.surface for token in tokens] == ["Öğrenci", "dün", "ilginç", "bir", "kitap", "okudu", "."]
    assert tokens[5].feature.lemma == "okumak"


def test_tokens_equal_the_desktop_import_path(tagger: Any) -> None:
    header, *records = (json.loads(line) for line in FIXTURE.read_text(encoding="utf-8").splitlines())
    engine_lock = Path(__file__).resolve().parents[4] / "tools/engine-sync/engine.lock"
    assert engine_lock.read_text(encoding="utf-8").strip() in header["provenance"]
    assert len(records) == 277

    mismatches = [record["text"] for record in records if _tokens(tagger, record["text"]) != record["tokens"]]
    assert not mismatches


def test_switch_language_applies_the_turkish_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.switching import switch_language
    from anki_miner.languages.tr.morphology import TR_SUBTITLE_REGEX

    config = switch_language(AnkiMinerConfig(), "tr")
    defaults = get_profile("tr").scoped_defaults
    assert config.language == "tr"
    assert config.use_subtitle_regex_filter is True
    assert config.subtitle_regex_filter == TR_SUBTITLE_REGEX == defaults["subtitle_regex_filter"]
    assert config.allowed_pos == defaults["allowed_pos"]
    assert config.anki_fields["pos"] == ""


def test_the_part_of_speech_card_field_survives_to_the_note(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.anki_adapter import _note_builder_kwargs
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.switching import switch_language
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = get_profile("tr")
    assert [field.key for field in profile.extra_card_fields] == ["pos"]
    config = switch_language(AnkiMinerConfig(), "tr")
    config = replace(
        config,
        language_stash={},
        anki_note_type="Basic",
        anki_fields={**dict(config.anki_fields), "pos": "PartOfSpeech"},
    )
    word = TokenizedWord(
        surface="kitap",
        lemma="kitap",
        reading="",
        sentence="Öğrenci dün ilginç bir kitap okudu.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="NOUN",
    )
    extra_fields: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra_fields.update(hook.render(word, config=config))
    payload = CardPayload(word=word, media=MediaData(), definition="book", extra_fields=extra_fields)

    note = build_note(payload, config, set(), **_note_builder_kwargs(config)).note

    assert note["fields"]["PartOfSpeech"] == "noun"


def test_every_pack_component_ships_in_the_apk_and_nothing_downloads(initialized_bridge_home: Path) -> None:
    """Decision 2: the Turkish pack is code only (zeyrek's lexicon is package data in its wheel)."""
    del initialized_bridge_home
    import importlib.util

    from android_bridge.languages import DOWNLOADABLE_DATA_COMPONENTS
    from android_bridge.resource_catalog import CATALOG_LANGUAGES, load_resource_catalog
    from anki_miner.languages.tr.pack import PACK

    names = {component.import_name for component in PACK.components}
    assert names == {"cloudpickle", "colorama", "defusedxml", "joblib", "nltk", "regex", "zeyrek"}
    assert all(importlib.util.find_spec(name) is not None for name in names)
    assert not {("tr", name) for name in names} & DOWNLOADABLE_DATA_COMPONENTS
    if "tr" in CATALOG_LANGUAGES:
        assert all(resource.kind != "language-data" for resource in load_resource_catalog("tr").resources)


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "tr",
        pinned={},
        excluded={
            "wty-tr-en": PENDING_PIN,
            "opensubtitles-tr": PENDING_PIN,
        },
    )
