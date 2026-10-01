"""Romanian: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

spaCy ships in the APK; the ro_core_news_sm model is ``language-data`` the user downloads.
The tagger tests install the pinned model from the local cache
(``tools/language-data/fetch_language_data.py ro``) and skip when it is not cached. The
parity fixture comes from ``export_spacy_tokens.py``: desktop's own tagger at ``engine.lock``.
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

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "ro" / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("ro")


@pytest.fixture(scope="module")
def ro_home(tmp_path_factory: pytest.TempPathFactory, initialized_bridge_home: Path):
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("ro-home")) as home:
        install_language_data("ro", home)
        yield home


def test_the_profile_loads_and_needs_its_model(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "ro" in available_languages()
    assert profile.code == "ro"
    assert profile.content_style.direction == "ltr"
    with language_data_home(tmp_path / "home"):
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(ro_home: Path) -> None:
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.tagger_provider import get_tagger

    del ro_home
    assert unavailable_reason_code(_profile()) is None
    tokens = get_tagger("ro")(_profile().smoke_sentence)

    # Desktop's tagger at the engine.lock SHA: the enclitic article folds into the lemma (Studentul -> student).
    assert [(token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma) for token in tokens] == [
        ("Studentul", "NOUN", "Ncmsry", "student"),
        ("a", "AUX", "Va--3s", "avea"),
        ("citit", "VERB", "Vmp--sm", "citi"),
        ("o", "DET", "Tifsr", "un"),
        ("carte", "NOUN", "Ncfsrn", "carte"),
        ("interesantă", "ADJ", "Afpfsrn", "interesant"),
        ("ieri", "ADV", "Rgp", "ieri"),
        (".", "PUNCT", "PERIOD", "."),
    ]


def test_tokens_match_desktop(ro_home: Path) -> None:
    del ro_home
    assert assert_tagger_matches("ro", FIXTURE) > 20


def test_switching_to_romanian_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.ro.morphology import RO_ALLOWED_POS, RO_EXCLUDED_SUBTYPES, RO_SUBTITLE_REGEX
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    romanian = switch_language(ja, "ro")

    assert romanian.language == "ro"
    assert romanian.use_subtitle_regex_filter is True
    # Romanian's own speaker-label class adds Ă, Ș and Ț to the shared Latin capitals.
    assert romanian.subtitle_regex_filter == RO_SUBTITLE_REGEX
    assert "ĂȘȚ" in RO_SUBTITLE_REGEX
    assert romanian.allowed_pos == RO_ALLOWED_POS
    assert romanian.excluded_subtypes == RO_EXCLUDED_SUBTYPES
    assert romanian.downloader_subtitle_langs == "ro"
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(romanian, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert romanian.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert {"pos", "noun_gender"} <= {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {"language": "ro", "anki_note_type": "Basic", "anki_fields": {"noun_gender": "Gender"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-ro-en head line (desktop tests/fixtures/ro/wty_row.json), the shape GrammarTagHook reads.
    definition = '<div data-sc-content="Grammar-content">scaun n (plural scaune)</div>'
    word = TokenizedWord(
        surface="scaun",
        lemma="scaun",
        reading="",
        sentence="Scaunul este nou.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="NOUN",
        definition_html=definition,
        # UD RRT has no neuter, so the model says Masc; Romanian reads the dictionary first.
        morph="Case=Acc,Nom|Definite=Ind|Gender=Masc|Number=Sing",
    )
    # What EpisodeProcessor._apply_render_hooks merges into extra_fields in phase 5.
    extra: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra.update(hook.render(word, config=config))
    assert extra == {"pos": "noun", "noun_gender": "neuter"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["Gender"] == "neuter"
    # pos is unmapped by default, so it reaches no field of its own.
    assert "noun" not in fields.values()


def test_every_language_data_component_is_found_once_installed(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries("ro")
    assert [entry.import_name for entry in entries] == ["ro_core_news_sm"]
    with language_data_home(tmp_path / "home") as home:
        assert component_path("ro", "ro_core_news_sm") is None
        install_sentinels("ro", home)
        for entry in entries:
            assert component_path("ro", entry.import_name) is not None, entry.import_name


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "ro",
        pinned={
            "wty-ro-en": "wty-ro-en-2026.09.20",
            "opensubtitles-ro": "opensubtitles-ro-2018",
        },
        excluded={},
    )
