"""Russian: the profile, its spaCy tagger, scoped defaults, card fields and catalog, offline.

The tagger runs on the pinned ``ru_core_news_sm`` and ``pymorphy3_dicts_ru``
installed from the local language-data cache (``fetch_language_data.py ru``); its
tests skip when an archive is not cached. ``fixtures/ru/tokens.jsonl`` is desktop's
own tagger output (``export_spacy_tokens.py``; its header names the desktop SHA
and command).
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

CODE = "ru"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / CODE / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile(CODE)


@pytest.fixture(scope="module")
def ru_home(initialized_bridge_home: Path, tmp_path_factory: pytest.TempPathFactory) -> Iterator[Path]:
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("ru-home")) as home:
        install_language_data(CODE, home)
        yield home


def test_the_profile_loads_and_is_registered(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert CODE in available_languages()
    assert profile.code == CODE
    assert profile.english_name == "Russian"


def test_the_tagger_tokenises_the_smoke_sentence(ru_home: Path) -> None:
    del ru_home
    from anki_miner.languages.tagger_provider import get_tagger

    sentence = _profile().smoke_sentence
    tokens = get_tagger(CODE)(sentence)
    assert "".join(token.surface for token in tokens) == sentence.replace(" ", "")
    tags = {(t.surface, t.feature.pos1, t.feature.lemma) for t in tokens}
    # pymorphy3's lemmas: a perfective past, an adjective and a noun all front as dictionary forms.
    assert {
        ("прочитал", "VERB", "прочитать"),
        ("интересную", "ADJ", "интересный"),
        ("книгу", "NOUN", "книга"),
    } <= tags


def test_tokens_match_desktop(ru_home: Path) -> None:
    del ru_home
    assert assert_tagger_matches(CODE, FIXTURE) > 20


def test_switching_to_russian_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.ru.morphology import RU_ALLOWED_POS, RU_EXCLUDED_SUBTYPES, RU_SUBTITLE_REGEX
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    ru = switch_language(ja, CODE)

    assert ru.language == CODE
    assert ru.use_subtitle_regex_filter is True
    # R11: ИВАН:/МАША: speaker labels the shared Latin capital class cannot match.
    assert ru.subtitle_regex_filter == RU_SUBTITLE_REGEX
    assert ru.allowed_pos == RU_ALLOWED_POS
    assert ru.excluded_subtypes == RU_EXCLUDED_SUBTYPES
    assert ru.downloader_subtitle_langs == "ru"
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(ru, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert ru.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert "aspect_pair" in {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {"language": CODE, "anki_note_type": "Basic", "anki_fields": {"aspect_pair": "AspectPair"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-ru-en Grammar head line: the romanisation after the headword is folded off before the rules read it.
    definition = '<div data-sc-content="Grammar-content">прочитать • (pročitátʹ) pf (imperfective читать)</div>'
    word = TokenizedWord(
        surface="прочитал",
        lemma="прочитать",
        reading="",
        sentence="Студент вчера прочитал интересную книгу.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="VERB",
        definition_html=definition,
    )
    # What EpisodeProcessor._apply_render_hooks merges into extra_fields in phase 5.
    extra: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra.update(hook.render(word, config=config))
    assert extra == {"pos": "verb", "aspect_pair": "perfective (imperfective: читать)"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["AspectPair"] == "perfective (imperfective: читать)"
    # pos is unmapped by default, so it reaches no field of its own.
    assert "verb" not in fields.values()


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        CODE,
        pinned={
            "wty-ru-en": "wty-ru-en-2026.09.20",
            "opr-ru-en": "opr-ru-en-2026.03.01",
            "opensubtitles-ru": "opensubtitles-ru-2018",
        },
        excluded={},
    )


def test_the_pinned_language_data_is_found_once_installed(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries(CODE)
    assert [entry.import_name for entry in entries] == ["ru_core_news_sm", "pymorphy3_dicts_ru"]
    with language_data_home(tmp_path / "home") as home:
        assert all(component_path(CODE, entry.import_name) is None for entry in entries)
        install_sentinels(CODE, home)
        for entry in entries:
            assert component_path(CODE, entry.import_name) == home / "language_packs" / CODE / entry.import_name
