"""Danish: the profile, its spaCy tagger, scoped defaults, card fields and catalog, offline.

spaCy ships in the APK; the da_core_news_sm model is ``language-data``, installed here from
the local cache (``tools/language-data/fetch_language_data.py da``) through the real
``resource.languagedata.install`` op. The parity fixture comes from desktop's own tagger at the
``engine.lock`` SHA (``export_spacy_tokens.py``; its header holds the provenance).
"""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete
from language_data_fixtures import (
    assert_tagger_matches,
    install_language_data,
    install_sentinels,
    language_data_entries,
    language_data_home,
)

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("spacy", reason="runtime dependency lane: the spaCy family ships in the APK")

CODE = "da"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / CODE / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile(CODE)


@pytest.fixture(scope="module")
def da_home(initialized_bridge_home: Path, tmp_path_factory: pytest.TempPathFactory):
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("da-home")) as home:
        install_language_data(CODE, home)
        yield home


def test_the_profile_loads_and_waits_for_its_model(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert CODE in available_languages()
    assert profile.code == CODE
    with language_data_home(tmp_path / "home"):
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(da_home: Path) -> None:
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.tagger_provider import get_tagger

    profile = _profile()
    assert unavailable_reason_code(profile) is None
    tokens = get_tagger(CODE)(profile.smoke_sentence)
    assert [(token.surface, token.feature.pos1, token.feature.lemma) for token in tokens] == [
        ("Den", "DET", "den"),
        ("studerende", "VERB", "studerende"),
        ("læste", "VERB", "læse"),
        ("en", "DET", "en"),
        ("interessant", "ADJ", "interessant"),
        ("bog", "NOUN", "bog"),
        ("i", "ADP", "i"),
        ("går", "NOUN", "går"),
        (".", "PUNCT", "."),
    ]


def test_tokens_match_the_desktop_tagger(da_home: Path) -> None:
    assert assert_tagger_matches(CODE, FIXTURE) == 19


def test_switching_to_danish_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    switched = switch_language(ja, CODE)

    assert switched.language == CODE
    assert switched.script_variant == ""
    assert switched.use_subtitle_regex_filter is True
    assert switched.allowed_pos == ("ADJ", "ADV", "NOUN", "VERB")
    assert switched.downloader_subtitle_langs == "da"
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(switched, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert switched.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert "noun_article" in {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {"language": CODE, "anki_note_type": "Basic", "anki_fields": {"noun_article": "Article"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-da-en Grammar head line, the shape GrammarTagHook reads (desktop's fixtures/da/wty_row.json).
    definition = (
        '<div data-sc-content="Grammar-content">jakke c (singular definite jakken, plural indefinite jakker)</div>'
    )
    word = TokenizedWord(
        surface="jakke",
        lemma="jakke",
        reading="",
        sentence="Jeg har en ny jakke.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="NOUN",
        definition_html=definition,
        morph="Definite=Ind|Gender=Neut|Number=Sing",
    )
    # What EpisodeProcessor._apply_render_hooks merges into extra_fields in phase 5.
    extra: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra.update(hook.render(word, config=config))
    assert extra == {"pos": "noun", "noun_gender": "common", "noun_article": "en"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["Article"] == "en"


def test_every_language_data_component_is_found_once_installed(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries(CODE)
    assert [entry.import_name for entry in entries] == ["da_core_news_sm"]
    with language_data_home(tmp_path / "home") as home:
        assert all(component_path(CODE, entry.import_name) is None for entry in entries)
        install_sentinels(CODE, home)
        for entry in entries:
            assert component_path(CODE, entry.import_name) == home / "language_packs" / CODE / entry.import_name


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        CODE,
        pinned={
            "wty-da-en": "wty-da-en-2026.09.20",
            "opensubtitles-da": "opensubtitles-da-2018",
        },
        excluded={},
    )
