"""Vietnamese: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

underthesea ships in the APK but its CRF models are downloaded language data
(``vi-underthesea-models``, a split component), so every tagger test runs inside
``split_models_home``, which installs the catalog's model selection from the
upstream wheel the runtime lane already has. The downloads the catalog pins
besides the models are desktop's two rows: wty-vi-en and the OpenSubtitles word
frequency list.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete
from split_models import split_models_home

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "vi"
TOKEN_ROWS = [
    json.loads(line)
    for line in (FIXTURES / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
    if line.strip() and not line.startswith("#")
]


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("vi")


def _tokens(text: str) -> list[dict[str, object]]:
    """The Android tagger's tokens in the desktop fixture's shape (offsets by a running ``str.find``)."""
    from anki_miner.languages.tagger_provider import get_tagger

    rows: list[dict[str, object]] = []
    cursor = 0
    for token in get_tagger("vi").parse(text):
        start = text.find(token.surface, cursor)
        end = start + len(token.surface) if start >= 0 else -1
        if start >= 0:
            cursor = end
        rows.append(
            {
                "surface": token.surface,
                "lemma": token.feature.lemma,
                "pos1": token.feature.pos1,
                "pos2": token.feature.pos2,
                "start": start,
                "end": end,
            }
        )
    return rows


@pytest.fixture
def models_home(
    initialized_bridge_home: Path, monkeypatch: pytest.MonkeyPatch, tmp_path_factory: pytest.TempPathFactory
) -> Path:
    del initialized_bridge_home
    return split_models_home("vi", monkeypatch, tmp_path_factory)


def test_the_profile_needs_its_models_before_it_can_mine(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "vi" in available_languages()
    assert profile.code == "vi"
    assert profile.import_encodings == ("utf-8-sig", "cp1258")
    assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_reads_the_downloaded_models(models_home: Path) -> None:
    from android_bridge.language_data import data_component_path
    from android_bridge.languages import unavailable_reason_code

    profile = _profile()
    assert data_component_path("vi", "underthesea_models") is not None
    assert unavailable_reason_code(profile) is None

    # Desktop's tokenizer at the engine.lock SHA (export_desktop_tokens.py) on the same sentence.
    assert [(row["surface"], row["pos1"], row["pos2"], row["lemma"]) for row in _tokens(profile.smoke_sentence)] == [
        ("Hôm nay", "N", "", "hôm nay"),
        ("trời", "N", "", "trời"),
        ("đẹp", "A", "", "đẹp"),
        ("quá", "R", "", "quá"),
        (".", "CH", "", "."),
    ]


@pytest.mark.parametrize("row", TOKEN_ROWS, ids=[row["line"] for row in TOKEN_ROWS])
def test_tokens_match_desktop(models_home: Path, row: dict) -> None:
    assert _tokens(row["line"]) == row["tokens"]


def test_switching_to_vietnamese_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language
    from anki_miner.languages.vi.pos import VI_ALLOWED_POS, VI_EXCLUDED_SUBTYPES
    from anki_miner.languages.vi.script import VI_SUBTITLE_REGEX

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    vietnamese = switch_language(ja, "vi")

    assert vietnamese.language == "vi"
    assert vietnamese.use_subtitle_regex_filter is True
    assert vietnamese.subtitle_regex_filter == VI_SUBTITLE_REGEX
    assert vietnamese.allowed_pos == VI_ALLOWED_POS == ("N", "V", "A", "Nc", "Nu")
    assert vietnamese.excluded_subtypes == VI_EXCLUDED_SUBTYPES == ("stopword", "name")
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(vietnamese, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert vietnamese.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_the_hanviet_field_survives_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert "hanviet" in {spec.key for spec in profile.extra_card_fields}
    assert "hanviet" in profile.capabilities
    config = map_config_settings(
        {"language": "vi", "anki_note_type": "Basic", "anki_fields": {"hanviet": "HanViet"}},
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-vi-en definition as yomitan_renderer lays it out: the etymology names the hanzi.
    definition = (
        '<ul><li class="gloss-item"><i>(n)</i> doctor. Sino-Vietnamese word from 博士.</li>'
        '<li class="gloss-item"><i>(n)</i> uncle. Sino-Vietnamese word from 伯.</li></ul>'
    )
    word = TokenizedWord(
        surface="bác sĩ",
        lemma="bác sĩ",
        reading="",
        sentence="Bác sĩ bây giờ có thể thản nhiên báo tin bệnh nhân bị ung thư.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        pos="N",
        definition_html=definition,
    )
    # What EpisodeProcessor._apply_render_hooks merges into extra_fields in phase 5.
    extra: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra.update(hook.render(word, config=config))
    # Only the first row counts: the second row's 伯 never reaches the card.
    assert extra == {"hanviet": "博士"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["HanViet"] == "博士"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "vi",
        pinned={
            "wty-vi-en": "wty-vi-en-2026.09.20",
            "opensubtitles-vi-word": "opensubtitles-vi-word-2026.09.19",
        },
        excluded={},
    )
