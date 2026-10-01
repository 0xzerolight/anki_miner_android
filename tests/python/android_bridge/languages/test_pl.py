"""Polish: the profile, its tagger, scoped defaults, card fields and catalog, offline.

Polish mines once its language data (the spaCy ``pl_core_news_sm`` model) is
installed. The module installs the pinned model archive from the local
language-data cache through the real ``resource.languagedata.install`` op, and
skips when it is not cached (``tools/language-data/fetch_language_data.py pl``).
``fixtures/pl/tokens.jsonl`` is desktop's own tagger output at the engine pin
(``tools/language-data/export_spacy_tokens.py``; its header names the command).
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
    language_data_home,
    read_token_fixture,
    token_row,
)

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("spacy", reason="runtime dependency lane: the spaCy family ships in the APK")

CODE = "pl"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / CODE / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile(CODE)


@pytest.fixture(scope="module")
def pl_home(initialized_bridge_home: Path, tmp_path_factory: pytest.TempPathFactory) -> Iterator[Path]:
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("pl-home")) as home:
        install_language_data(CODE, home)
        yield home


def test_the_profile_loads_and_waits_for_its_language_data(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert CODE in available_languages()
    assert profile.code == CODE
    assert profile.english_name == "Polish"
    with language_data_home(tmp_path / "home"):
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(pl_home: Path) -> None:
    del pl_home
    from anki_miner.languages.tagger_provider import get_tagger

    sentence = _profile().smoke_sentence
    tokens = get_tagger(CODE)(sentence)
    assert [(t.surface, t.feature.pos1, t.feature.lemma) for t in tokens] == [
        ("Student", "NOUN", "student"),
        ("przeczytał", "VERB", "przeczytać"),
        ("wczoraj", "ADV", "wczoraj"),
        ("ciekawą", "ADJ", "ciekawy"),
        ("książkę", "NOUN", "książka"),
        (".", "PUNCT", "."),
    ]
    (smoke,) = [row for row in read_token_fixture(FIXTURE) if row["id"] == "smoke"]
    assert smoke["sentence"] == sentence
    assert [token_row(token) for token in tokens] == smoke["tokens"]


def test_tokens_match_the_desktop_fixture(pl_home: Path) -> None:
    del pl_home
    assert assert_tagger_matches(CODE, FIXTURE) >= 20


def test_switching_to_polish_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import base_config
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.pl.morphology import PL_ALLOWED_POS, PL_SUBTITLE_REGEX
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    pl = switch_language(ja, CODE)
    first_visit = base_config(CODE)

    assert pl.language == CODE
    assert pl.use_subtitle_regex_filter is True
    assert pl.subtitle_regex_filter == PL_SUBTITLE_REGEX
    assert pl.allowed_pos == PL_ALLOWED_POS
    assert pl.downloader_subtitle_langs == "pl"
    assert {"pos", "noun_gender", "aspect_pair"} <= set(pl.anki_fields)
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(pl, name) == defaults[name], name
        assert getattr(first_visit, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert pl.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_snapshot_to_the_note(
    initialized_bridge_home: Path, pl_home: Path, tmp_path: Path
) -> None:
    del pl_home
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData
    from anki_miner.models.reading import ReadingUnit
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert {"pos", "noun_gender", "aspect_pair"} <= {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {
            "language": CODE,
            "anki_note_type": "Basic",
            "anki_fields": {"pos": "PartOfSpeech", "aspect_pair": "AspectPair"},
        },
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    words, _index, _counts = profile.create_parser(config).parse_text_units(
        [ReadingUnit(text=profile.smoke_sentence, index=0, location_label="t")], False
    )
    word = next(word for word in words if word.mined_form == "przeczytać")
    extra_fields: dict[str, str] = {}
    # Phase 5's hook pass itself, on the parser's own word (its ``morph`` carries Aspect=Perf).
    EpisodeProcessor._apply_render_hooks(SimpleNamespace(config=config, profile=profile), word, "", extra_fields)
    card = CardPayload(word=word, media=MediaData(), definition="to read", extra_fields=extra_fields)

    note = build_note(card, config, set(), **_note_builder_kwargs(config)).note

    assert note["fields"]["PartOfSpeech"] == "verb"
    assert note["fields"]["AspectPair"] == "perfective"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        CODE,
        pinned={
            "wty-pl-en": "wty-pl-en-2026.09.20",
            "opensubtitles-pl": "opensubtitles-pl-2018",
        },
        excluded={},
    )


def test_every_catalog_language_data_entry_resolves_once_installed(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    del initialized_bridge_home
    from android_bridge.languages import DOWNLOADABLE_DATA_COMPONENTS, unavailable_reason_code
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.services.language_pack_installer import component_path

    entries = list(load_resource_catalog(CODE).language_data)
    assert [entry.import_name for entry in entries] == ["pl_core_news_sm"]
    with language_data_home(tmp_path / "home") as home:
        assert component_path(CODE, "pl_core_news_sm") is None
        install_sentinels(CODE, home)
        for entry in entries:
            assert (CODE, entry.import_name) in DOWNLOADABLE_DATA_COMPONENTS
            assert component_path(CODE, entry.import_name) is not None, entry.import_name
        assert unavailable_reason_code(_profile()) is None
