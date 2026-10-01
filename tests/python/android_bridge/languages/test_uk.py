"""Ukrainian: the profile, its tagger, scoped defaults, card fields and catalog, offline.

Ukrainian mines only once its spaCy model (``uk_core_news_sm``) and the pymorphy3 dictionaries
(``pymorphy3_dicts_uk``) its lemmatizer reads, both language data, are installed. The module
installs the pinned wheels from the local language-data cache through the real
``resource.languagedata.install`` op (``language_data_fixtures``); the tests that need them
skip when one is not cached (``tools/language-data/fetch_language_data.py uk``).
``fixtures/uk/tokens.jsonl`` is desktop's own tagger output (``export_spacy_tokens.py``; its header
names the desktop SHA, the versions and the command line).
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

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

CODE = "uk"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / CODE / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile(CODE)


@pytest.fixture(scope="module")
def uk_home(tmp_path_factory: pytest.TempPathFactory, initialized_bridge_home: Path) -> Iterator[Path]:
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("uk-home")) as home:
        install_language_data(CODE, home)
        yield home


def test_the_profile_loads_and_waits_for_its_model(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert CODE in available_languages()
    assert profile.code == CODE
    with language_data_home(tmp_path / "empty-home"):
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(uk_home: Path) -> None:
    del uk_home
    from anki_miner.languages.tagger_provider import get_tagger

    tokens = get_tagger(CODE)(_profile().smoke_sentence)
    assert [(t.surface, t.feature.pos1, t.feature.lemma) for t in tokens] == [
        ("Студент", "NOUN", "студент"),
        ("учора", "ADV", "учора"),
        ("прочитав", "VERB", "прочитати"),
        ("цікаву", "ADJ", "цікавий"),
        ("книжку", "NOUN", "книжка"),
        (".", "PUNCT", "."),
    ]


def test_tokens_match_desktop(uk_home: Path) -> None:
    del uk_home
    assert assert_tagger_matches(CODE, FIXTURE) > 0


def test_switching_to_ukrainian_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import base_config
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language
    from anki_miner.languages.uk.morphology import UK_ALLOWED_POS, UK_SUBTITLE_REGEX

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    uk = switch_language(ja, CODE)
    first_visit = base_config(CODE)

    assert uk.language == CODE
    assert uk.use_subtitle_regex_filter is True
    assert uk.subtitle_regex_filter == UK_SUBTITLE_REGEX
    assert uk.allowed_pos == UK_ALLOWED_POS
    assert uk.downloader_subtitle_langs == "uk"
    assert {"pos", "noun_gender", "aspect_pair"} <= set(uk.anki_fields)
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(uk, name) == defaults[name], name
        assert getattr(first_visit, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert uk.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_snapshot_to_the_note(
    uk_home: Path, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    del uk_home  # the model the parser loads; the config itself keeps the bridge's home
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData
    from anki_miner.models.reading import ReadingUnit
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert "pos" in {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {"language": CODE, "anki_note_type": "Basic", "anki_fields": {"pos": "PartOfSpeech"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    words, _index, _counts = profile.create_parser(config).parse_text_units(
        [ReadingUnit(text=profile.smoke_sentence, index=0, location_label="t")], False
    )
    word = next(word for word in words if word.mined_form == "книжка")
    extra_fields: dict[str, str] = {}
    # Phase 5's hook pass itself, on the parser's own word.
    EpisodeProcessor._apply_render_hooks(SimpleNamespace(config=config, profile=profile), word, "", extra_fields)
    card = CardPayload(word=word, media=MediaData(), definition="book", extra_fields=extra_fields)

    note = build_note(card, config, set(), **_note_builder_kwargs(config)).note

    assert note["fields"]["PartOfSpeech"] == "noun"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        CODE,
        pinned={
            "wty-uk-en": "wty-uk-en-2026.09.20",
            "opensubtitles-uk": "opensubtitles-uk-2018",
        },
        excluded={},
    )


def test_the_catalog_language_data_is_what_the_profile_needs(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries(CODE)
    assert [entry.import_name for entry in entries] == ["uk_core_news_sm", "pymorphy3_dicts_uk"]
    with language_data_home(tmp_path / "home") as home:
        install_sentinels(CODE, home)
        for entry in entries:
            assert component_path(CODE, entry.import_name) is not None, entry.import_name
        assert unavailable_reason_code(_profile()) is None
