"""Indonesian: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

Indonesian ships no engine and no pack: the regex tokenizer and deinflection ladder are vendored
code, and the only downloads are the dictionary and frequency list the catalog pins. So there is no
language-data component for ``component_path`` to find, and the completeness check is that none is
expected.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "id"
TOKEN_ROWS = [
    json.loads(line)
    for line in (FIXTURES / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
    if not line.startswith("#")
]


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("id")


def _rows(text: str) -> list[list[str]]:
    """Desktop ``test_id_tokenizer._rows``: the fixture leaves punctuation out."""
    from anki_miner.languages.tagger_provider import get_tagger

    return [
        [token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma]
        for token in get_tagger("id")(text)
        if token.feature.pos1 != "PUNCT"
    ]


def test_the_profile_loads_and_can_mine(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "id" in available_languages()
    assert profile.code == "id"
    assert profile.content_style.direction == "ltr"
    assert unavailable_reason_code(profile) is None


def test_the_tagger_tokenises_the_smoke_sentence(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert _rows(_profile().smoke_sentence) == [
        ["Saya", "WORD", "stopword", "saya"],
        ["sedang", "WORD", "stopword", "sedang"],
        ["membaca", "WORD", "", "membaca"],
        ["buku", "WORD", "", "buku"],
        ["di", "WORD", "stopword", "di"],
        ["rumah", "WORD", "", "rumah"],
    ]


@pytest.mark.parametrize("row", TOKEN_ROWS, ids=[row["id"] for row in TOKEN_ROWS])
def test_tokens_match_the_desktop_fixture(initialized_bridge_home: Path, row: dict) -> None:
    del initialized_bridge_home
    assert _rows(row["sentence"]) == row["tokens"], row["note"]


def test_switching_to_indonesian_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages._spaced.script import LATIN_SUBTITLE_REGEX
    from anki_miner.languages.id.morphology import ID_ALLOWED_POS, ID_EXCLUDED_SUBTYPES
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    indonesian = switch_language(ja, "id")

    assert indonesian.language == "id"
    assert indonesian.use_subtitle_regex_filter is True
    assert indonesian.subtitle_regex_filter == LATIN_SUBTITLE_REGEX
    assert indonesian.downloader_subtitle_langs == "id"
    assert indonesian.allowed_pos == ID_ALLOWED_POS
    assert indonesian.excluded_subtypes == ID_EXCLUDED_SUBTYPES
    # Furigana is unmapped and each extra field ships off: its mapped name is the switch.
    assert indonesian.anki_fields["expression_furigana"] == ""
    assert {key: indonesian.anki_fields[key] for key in ("root", "affixes", "formal_form")} == dict.fromkeys(
        ("root", "affixes", "formal_form"), ""
    )
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(indonesian, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert indonesian.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_snapshot_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    assert "affixes" in {spec.key for spec in _profile().extra_card_fields}
    config = map_config_settings(
        {"language": "id", "anki_note_type": "Basic", "anki_fields": {"affixes": "Imbuhan"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    word = TokenizedWord(
        surface="dibeli",
        lemma="membeli",
        reading="",
        sentence="Buku itu dibeli di Jakarta.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
    )
    card = CardPayload(word=word, media=MediaData(), definition="to buy", extra_fields={"affixes": "di- + beli"})

    note = build_note(card, config, set(), **_note_builder_kwargs(config)).note

    assert note["fields"]["Imbuhan"] == "di- + beli"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "id",
        pinned={
            "wty-id-en": "wty-id-en-2026.09.20",
            "opensubtitles-id": "opensubtitles-id-2018",
        },
        excluded={},
    )


def test_indonesian_needs_no_language_data(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import DOWNLOADABLE_DATA_COMPONENTS
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.services.language_pack_installer import load_pack

    assert load_pack("id") is None
    assert not {name for code, name in DOWNLOADABLE_DATA_COMPONENTS if code == "id"}
    assert all(resource.kind != "language-data" for resource in load_resource_catalog("id").resources)
