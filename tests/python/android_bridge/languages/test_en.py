"""English: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

The spaCy engine ships in the APK; the en_core_web_sm model is language data the
catalog pins and the app downloads. The tagger tests install that pinned archive
from the local cache (``tools/language-data/fetch_language_data.py en``) and skip
when it is not cached. The other downloads are the wty-en-en dictionary and the
OpenSubtitles frequency list.
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
    read_token_fixture,
    token_row,
)

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("spacy", reason="runtime dependency lane: the spaCy family ships in the APK")

CODE = "en"
FIXTURES = Path(__file__).resolve().parent / "fixtures" / CODE


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile(CODE)


@pytest.fixture(scope="module")
def en_home(tmp_path_factory: pytest.TempPathFactory, initialized_bridge_home: Path):
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("en-home")) as home:
        install_language_data(CODE, home)
        yield home


def test_the_profile_loads_and_needs_its_model(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert CODE in available_languages()
    assert profile.code == CODE
    assert profile.content_style.direction == "ltr"
    with language_data_home(tmp_path / "home"):
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(en_home: Path) -> None:
    del en_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.tagger_provider import get_tagger

    profile = _profile()
    assert unavailable_reason_code(profile) is None
    rows = [token_row(token) for token in get_tagger(CODE)(profile.smoke_sentence)]

    (smoke,) = [row for row in read_token_fixture(FIXTURES / "tokens.jsonl") if row["id"] == "smoke"]
    assert smoke["sentence"] == profile.smoke_sentence
    assert rows == smoke["tokens"]
    assert rows[4][:4] == ["jumps", "VERB", "VBZ", "jump"]
    assert [row[0] for row in rows if row[1] == "NOUN"] == ["fox", "dog"]


def test_tokens_match_desktop(en_home: Path) -> None:
    del en_home
    assert assert_tagger_matches(CODE, FIXTURES / "tokens.jsonl") > 20


def test_switching_to_english_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    english = switch_language(ja, CODE)

    defaults = _profile().scoped_defaults
    assert english.language == CODE
    # The spaCy family turns its bracket and speaker-label filter on.
    assert english.use_subtitle_regex_filter is True
    assert english.subtitle_regex_filter == defaults["subtitle_regex_filter"] != "ja-only"
    assert english.allowed_pos == ("ADJ", "ADV", "NOUN", "VERB")
    assert english.downloader_subtitle_langs == CODE
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(english, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert english.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_the_part_of_speech_field_survives_to_the_note(initialized_bridge_home: Path, tmp_path: Path) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert "pos" in {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {"language": CODE, "anki_note_type": "Basic", "anki_fields": {"pos": "PartOfSpeech"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    definition = "<div>a domesticated canine</div>"
    # "dog" as the desktop tagger reads it in the smoke sentence (fixtures/en/tokens.jsonl).
    word = TokenizedWord(
        surface="dog",
        lemma="dog",
        reading="",
        sentence=profile.smoke_sentence,
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="NOUN",
        definition_html=definition,
        morph="Number=Sing",
    )
    # What EpisodeProcessor._apply_render_hooks merges into extra_fields in phase 5.
    extra: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra.update(hook.render(word, config=config))
    assert extra == {"pos": "noun"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["PartOfSpeech"] == "noun"


def test_the_pinned_model_is_found_once_installed(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries(CODE)
    assert [entry.import_name for entry in entries] == ["en_core_web_sm"]
    with language_data_home(tmp_path / "home") as home:
        assert all(component_path(CODE, entry.import_name) is None for entry in entries)
        install_sentinels(CODE, home)
        for entry in entries:
            assert component_path(CODE, entry.import_name) is not None, entry.import_name
        assert unavailable_reason_code(_profile()) is None


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.resource_catalog import load_resource_catalog

    assert_catalog_complete(
        CODE,
        pinned={
            "wty-en-en": "wty-en-en-2026.09.20",
            "opensubtitles-en": "opensubtitles-en-2018",
        },
        excluded={},
    )
    assert load_resource_catalog(CODE).recommended == ("wty-en-en-2026.09.20", "opensubtitles-en-2018")
