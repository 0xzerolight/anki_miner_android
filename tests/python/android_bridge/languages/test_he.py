"""Hebrew: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

Hebrew ships no engine and no pack: the regex tokenizer is vendored code, and the only downloads
are the dictionary and frequency list the catalog pins. So there is no language-data component
for ``component_path`` to find, and the completeness check is that none is expected.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "he"
TOKEN_ROWS = [
    json.loads(line)
    for line in (FIXTURES / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
    if not line.startswith("#")
]


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("he")


def test_the_profile_loads_and_can_mine(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "he" in available_languages()
    assert profile.code == "he"
    assert profile.content_style.direction == "rtl"
    assert unavailable_reason_code(profile) is None


def test_the_tagger_tokenises_the_smoke_sentence(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.languages.tagger_provider import get_tagger

    sentence = _profile().smoke_sentence
    tokens = get_tagger("he")(sentence)
    assert [token.feature.pos1 for token in tokens] == ["WORD"] * 5 + ["PUNCT"]
    assert "".join(token.surface for token in tokens) == sentence.replace(" ", "")


@pytest.mark.parametrize("row", TOKEN_ROWS, ids=[row["id"] for row in TOKEN_ROWS])
def test_tokens_match_the_desktop_fixture(initialized_bridge_home: Path, row: dict) -> None:
    del initialized_bridge_home
    from anki_miner.languages.tagger_provider import get_tagger

    tokens = get_tagger("he")(row["sentence"])
    actual = [[t.surface, t.feature.pos1, t.feature.pos2, t.feature.lemma] for t in tokens]
    assert actual == row["tokens"], row["note"]


def test_switching_to_hebrew_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.he.pos import HE_ALLOWED_POS
    from anki_miner.languages.he.script import HE_SUBTITLE_REGEX
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    he = switch_language(ja, "he")

    assert he.language == "he"
    assert he.use_subtitle_regex_filter is True
    assert he.subtitle_regex_filter == HE_SUBTITLE_REGEX
    assert he.allowed_pos == HE_ALLOWED_POS
    assert he.anki_fields["expression_reading"] == "Reading"
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(he, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert he.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_snapshot_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    assert "binyan" in {spec.key for spec in _profile().extra_card_fields}
    config = map_config_settings(
        {"language": "he", "anki_note_type": "Basic", "anki_fields": {"binyan": "Binyan"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    word = TokenizedWord(
        surface="כתבתי",
        lemma="כתב",
        reading="כָּתַב",
        sentence="כתבתי מכתב.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
    )
    card = CardPayload(word=word, media=MediaData(), definition="to write", extra_fields={"binyan": "pa'al"})

    note = build_note(card, config, set(), **_note_builder_kwargs(config)).note

    assert note["fields"]["Binyan"] == "pa&#x27;al"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "he",
        pinned={
            "wty-he-en": "wty-he-en-2026.09.20",
            "opensubtitles-he": "opensubtitles-he-2018",
        },
        excluded={},
    )


def test_hebrew_needs_no_language_data(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import DOWNLOADABLE_DATA_COMPONENTS
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.services.language_pack_installer import load_pack

    assert load_pack("he") is None
    assert not {name for code, name in DOWNLOADABLE_DATA_COMPONENTS if code == "he"}
    assert all(resource.kind != "language-data" for resource in load_resource_catalog("he").resources)
