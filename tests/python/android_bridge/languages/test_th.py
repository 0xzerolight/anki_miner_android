"""Thai: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

PyThaiNLP and tzdata ship as repacked wheels in the APK (``tools/language-data/pins.json``
classifies both ``apk``), so Thai has no data-only component to download and nothing for
``component_path`` to find on disk: the completeness check is that the catalog expects none and
the profile reports Thai mineable without one. The downloads are the dictionary and the two
frequency lists the catalog pins.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("pythainlp", reason="runtime dependency lane: the Thai engine ships in the APK")

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "th"
TOKEN_ROWS = [
    json.loads(line)
    for line in (FIXTURES / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
    if line.strip() and not line.startswith("#")
]
_PINS = Path(__file__).resolve().parents[4] / "tools" / "language-data" / "pins.json"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("th")


def _tags(text: str) -> list[tuple[str, str, str, str]]:
    from anki_miner.languages.tagger_provider import get_tagger

    return [
        (token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma)
        for token in get_tagger("th").parse(text)
    ]


def test_the_profile_loads_and_can_mine(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "th" in available_languages()
    assert profile.code == "th"
    assert profile.content_style.direction == "ltr"
    assert unavailable_reason_code(profile) is None


def test_the_tagger_tokenises_the_smoke_sentence(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    sentence = _profile().smoke_sentence
    tags = _tags(sentence)

    # Desktop's tagger at the engine.lock SHA, same pythainlp 5.3.7: newmm keeps ดีมาก whole.
    assert [(surface, pos1, pos2) for surface, pos1, pos2, _ in tags] == [
        ("วันนี้", "NOUN", ""),
        ("อากาศ", "NOUN", ""),
        ("ดีมาก", "ADV", ""),
    ]
    assert "".join(surface for surface, *_ in tags) == sentence


@pytest.mark.parametrize("row", TOKEN_ROWS, ids=[f"{row['sentence']}|{row['surface']}" for row in TOKEN_ROWS])
def test_tokens_match_the_desktop_fixture(initialized_bridge_home: Path, row: dict) -> None:
    del initialized_bridge_home
    got = {surface: (pos1, pos2, lemma) for surface, pos1, pos2, lemma in _tags(row["sentence"])}
    assert got[row["surface"]] == (row["pos1"], row["pos2"], row["lemma"]), row["note"]
    mined = _profile().mined_form.mined_form(row["pos1"], row["lemma"], row["lemma"], row["surface"])
    assert mined == row["mined_form"]


def test_switching_to_thai_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language
    from anki_miner.languages.th import TH_SUBTITLE_REGEX
    from anki_miner.languages.th.pos import TH_ALLOWED_POS, TH_EXCLUDED_SUBTYPES

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    thai = switch_language(ja, "th")

    assert thai.language == "th"
    assert thai.use_subtitle_regex_filter is True
    assert thai.subtitle_regex_filter == TH_SUBTITLE_REGEX
    assert thai.allowed_pos == TH_ALLOWED_POS
    assert thai.excluded_subtypes == TH_EXCLUDED_SUBTYPES
    assert thai.anki_note_type == ""
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(thai, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert thai.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert "reading_paiboon" in {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {"language": "th", "anki_note_type": "Basic", "anki_fields": {"reading_paiboon": "Reading"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-th-en Grammar line, the shape the reading hook reads (desktop's wty_grammar_rows.json).
    definition = '<div data-sc-content="Grammar-content">หมา • (mǎa) (classifier ตัว)</div>'
    word = TokenizedWord(
        surface="หมา",
        lemma="หมา",
        reading="",
        sentence="หมาน่ารัก",
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
    assert extra == {"reading_paiboon": "mǎa", "classifier": "ตัว"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["Reading"] == "mǎa"
    # The classifier is unmapped by default, so it reaches no field of its own.
    assert "ตัว" not in fields.values()


def test_no_language_data_is_expected_because_the_engine_ships_in_the_apk(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.services.language_pack_installer import load_pack

    pack = load_pack("th")
    classes = json.loads(_PINS.read_text(encoding="utf-8"))["components"]
    assert {component.import_name for component in pack.components} == {"pythainlp", "tzdata"}
    for component in pack.components:
        assert set(classes[f"th/{component.import_name}"]) == {"apk"}, component.import_name
    assert load_resource_catalog("th").language_data == ()


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "th",
        pinned={
            "wty-th-en": "wty-th-en-2026.09.20",
            "tnc-th": "tnc-th-2026-09-20",
            "ttc-th": "ttc-th-2026-09-20",
        },
        excluded={},
    )
