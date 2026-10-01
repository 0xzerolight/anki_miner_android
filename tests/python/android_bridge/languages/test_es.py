"""Spanish: the profile, its spaCy tagger, scoped defaults, card fields and catalog, offline.

The tagger runs on the pinned ``es_core_news_sm`` installed from the local
language-data cache (``fetch_language_data.py es``); its tests skip when the
archive is not cached. ``fixtures/es/tokens.jsonl`` is desktop's own tagger
output (``export_spacy_tokens.py``; its header names the desktop SHA and command).
"""

from __future__ import annotations

from collections.abc import Iterator
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

CODE = "es"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / CODE / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile(CODE)


@pytest.fixture(scope="module")
def es_home(initialized_bridge_home: Path, tmp_path_factory: pytest.TempPathFactory) -> Iterator[Path]:
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("es-home")) as home:
        install_language_data(CODE, home)
        yield home


def test_the_profile_loads_and_is_registered(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert CODE in available_languages()
    assert profile.code == CODE
    assert profile.english_name == "Spanish"


def test_the_tagger_tokenises_the_smoke_sentence(es_home: Path) -> None:
    del es_home
    from anki_miner.languages.tagger_provider import get_tagger

    sentence = _profile().smoke_sentence
    tokens = get_tagger(CODE)(sentence)
    assert "".join(token.surface for token in tokens) == sentence.replace(" ", "")
    tags = {(t.surface, t.feature.pos1, t.feature.lemma) for t in tokens}
    assert {("corre", "VERB", "correr"), ("parque", "NOUN", "parque")} <= tags


def test_tokens_match_desktop(es_home: Path) -> None:
    del es_home
    assert assert_tagger_matches(CODE, FIXTURE) > 20


def test_switching_to_spanish_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages._spaced.script import LATIN_UNSPACED_DASH_SUBTITLE_REGEX
    from anki_miner.languages.es.morphology import ES_ALLOWED_POS, ES_EXCLUDED_SUBTYPES
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    es = switch_language(ja, CODE)

    assert es.language == CODE
    assert es.use_subtitle_regex_filter is True
    # Spanish subtitles write the dialogue dash unspaced (``-¿Vienes?``).
    assert es.subtitle_regex_filter == LATIN_UNSPACED_DASH_SUBTITLE_REGEX
    assert es.allowed_pos == ES_ALLOWED_POS
    assert es.excluded_subtypes == ES_EXCLUDED_SUBTYPES
    assert es.downloader_subtitle_langs == "es"
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(es, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert es.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert "noun_gender" in {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {"language": CODE, "anki_note_type": "Basic", "anki_fields": {"noun_gender": "Gender", "pos": "PartOfSpeech"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-es-en Grammar head line, the shape the gender hook reads (desktop's es wty_row.json ``nieve``).
    definition = '<div data-sc-content="Grammar-content">nieve f (plural nieves)</div>'
    word = TokenizedWord(
        surface="nieve",
        lemma="nieve",
        reading="",
        sentence="La nieve cae.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="NOUN",
        definition_html=definition,
    )
    # What EpisodeProcessor._apply_render_hooks merges into extra_fields in phase 5.
    extra: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra.update(hook.render(word, config=config))
    # ES_GENDER_LABELS puts the article in the gender field.
    assert extra == {"pos": "noun", "noun_gender": "la"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["Gender"] == "la"
    assert fields["PartOfSpeech"] == "noun"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        CODE,
        pinned={
            "wty-es-en": "wty-es-en-2026.09.20",
            "opensubtitles-es": "opensubtitles-es-2018",
        },
        excluded={},
    )


def test_the_pinned_language_data_is_found_once_installed(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries(CODE)
    assert [entry.import_name for entry in entries] == ["es_core_news_sm"]
    with language_data_home(tmp_path / "home") as home:
        assert all(component_path(CODE, entry.import_name) is None for entry in entries)
        install_sentinels(CODE, home)
        for entry in entries:
            assert component_path(CODE, entry.import_name) == home / "language_packs" / CODE / entry.import_name
