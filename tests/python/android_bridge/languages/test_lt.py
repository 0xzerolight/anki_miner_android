"""Lithuanian: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

spaCy ships in the APK; the lt_core_news_sm model is ``language-data`` the user downloads.
The tagger tests install the pinned model from the local cache
(``tools/language-data/fetch_language_data.py lt``) and skip when it is not cached. The
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

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "lt" / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("lt")


@pytest.fixture(scope="module")
def lt_home(tmp_path_factory: pytest.TempPathFactory, initialized_bridge_home: Path):
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("lt-home")) as home:
        install_language_data("lt", home)
        yield home


def test_the_profile_loads_and_needs_its_model(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "lt" in available_languages()
    assert profile.code == "lt"
    assert profile.content_style.direction == "ltr"
    with language_data_home(tmp_path / "home"):
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(lt_home: Path) -> None:
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.tagger_provider import get_tagger

    del lt_home
    assert unavailable_reason_code(_profile()) is None
    tokens = get_tagger("lt")(_profile().smoke_sentence)

    # Desktop's tagger at the engine.lock SHA: the genitive stalo lemmatises to stalas.
    assert [(token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma) for token in tokens] == [
        ("Knyga", "NOUN", "dkt.mot.vns.V.", "knyga"),
        ("yra", "AUX", "vksm.asm.tiesiog.es.vns.3.", "būti"),
        ("ant", "ADP", "prl.K.", "ant"),
        ("stalo", "NOUN", "dkt.vyr.vns.K.", "stalas"),
        (".", "PUNCT", "skyr.", "."),
    ]


def test_tokens_match_desktop(lt_home: Path) -> None:
    del lt_home
    assert assert_tagger_matches("lt", FIXTURE) > 20


def test_switching_to_lithuanian_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.lt.morphology import LT_ALLOWED_POS, LT_EXCLUDED_SUBTYPES, LT_SUBTITLE_REGEX
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    lithuanian = switch_language(ja, "lt")

    assert lithuanian.language == "lt"
    assert lithuanian.use_subtitle_regex_filter is True
    # Lithuanian's own speaker-label class adds its accented capitals to the shared Latin ones.
    assert lithuanian.subtitle_regex_filter == LT_SUBTITLE_REGEX
    assert "ĄČĘĖĮŠŲŪŽ" in LT_SUBTITLE_REGEX
    assert lithuanian.allowed_pos == LT_ALLOWED_POS
    assert lithuanian.excluded_subtypes == LT_EXCLUDED_SUBTYPES
    assert lithuanian.downloader_subtitle_langs == "lt"
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(lithuanian, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert lithuanian.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


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
        {"language": "lt", "anki_note_type": "Basic", "anki_fields": {"noun_gender": "Gender"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-lt-en head line (desktop tests/fixtures/lt/wty_row.json): the stress mark on knygà is dropped
    # before the gender letter is read.
    definition = '<div data-sc-content="Grammar-content">knygà f (plural knỹgos)</div>'
    word = TokenizedWord(
        surface="Knyga",
        lemma="knyga",
        reading="",
        sentence="Knyga yra ant stalo.",
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
    assert extra == {"pos": "noun", "noun_gender": "feminine"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["Gender"] == "feminine"
    # pos is unmapped by default, so it reaches no field of its own.
    assert "noun" not in fields.values()


def test_every_language_data_component_is_found_once_installed(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries("lt")
    assert [entry.import_name for entry in entries] == ["lt_core_news_sm"]
    with language_data_home(tmp_path / "home") as home:
        assert component_path("lt", "lt_core_news_sm") is None
        install_sentinels("lt", home)
        for entry in entries:
            assert component_path("lt", entry.import_name) is not None, entry.import_name


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "lt",
        pinned={
            "wty-lt-en": "wty-lt-en-2026.09.20",
            "opensubtitles-lt": "opensubtitles-lt-2018",
        },
        excluded={},
    )
