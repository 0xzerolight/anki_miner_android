"""Cantonese: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

pycantonese and rustling ship in the APK; the segmenter and tagger models are downloaded
language data (``yue-pycantonese-models``), so every tagger test runs inside
``split_models_home``, which copies the catalog's model selection out of the runtime
lane's installed wheel. Jyutping needs no download: its tables stay in the APK.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete
from split_models import split_models_home, tokens_without_packaged_models

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "yue"
TOKEN_ROWS = [
    json.loads(line)
    for line in (FIXTURES / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
    if line.strip() and not line.startswith("#")
]


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("yue")


def _models(monkeypatch: pytest.MonkeyPatch, tmp_path_factory: pytest.TempPathFactory) -> Path:
    pytest.importorskip("pycantonese", reason="runtime dependency lane: the Cantonese engine ships in the APK")
    return split_models_home("yue", monkeypatch, tmp_path_factory)


def test_the_profile_loads_and_waits_for_its_models(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "yue" in available_languages()
    assert profile.code == "yue"
    assert profile.import_encodings == ("utf-8-sig", "gb18030", "big5hkscs")
    assert profile.capabilities == frozenset({"jyutping", "measure_word", "tone_color"})
    # The bootstrap home has no models: the bridge, not the engine, says what is missing.
    assert unavailable_reason_code(profile) == "language_data_required"


def test_the_models_install_where_the_bridge_looks_for_them(
    initialized_bridge_home: Path, monkeypatch: pytest.MonkeyPatch, tmp_path_factory: pytest.TempPathFactory
) -> None:
    del initialized_bridge_home
    home = _models(monkeypatch, tmp_path_factory)
    from android_bridge.language_data import data_component_path
    from android_bridge.languages import unavailable_reason_code

    assert data_component_path("yue", "pycantonese_models") == home / "language_packs" / "yue" / "pycantonese_models"
    assert unavailable_reason_code(_profile()) is None


def test_the_tagger_reads_the_downloaded_models(
    initialized_bridge_home: Path, monkeypatch: pytest.MonkeyPatch, tmp_path_factory: pytest.TempPathFactory
) -> None:
    del initialized_bridge_home
    _models(monkeypatch, tmp_path_factory)
    from anki_miner.languages.tagger_provider import get_tagger

    profile = _profile()
    tokens = get_tagger("yue").parse(profile.smoke_sentence)

    # Desktop's tokenizer at the engine.lock SHA (export_desktop_tokens.py) on the same sentence.
    assert [(token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma) for token in tokens] == [
        ("我", "PRON", "stopword", "我"),
        ("今日", "ADV", "", "今日"),
        ("睇", "VERB", "stopword", "睇"),
        ("咗", "PART", "stopword", "咗"),
        ("一", "NUM", "", "一"),
        ("套", "NOUN", "", "套"),
        ("好", "ADV", "stopword", "好"),
        ("好睇", "ADJ", "", "好睇"),
        ("嘅", "PART", "stopword", "嘅"),
        ("戲", "NOUN", "", "戲"),
        ("。", "PUNCT", "", "。"),
    ]


def test_pycantonese_without_its_models_tags_through_the_override(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path_factory: pytest.TempPathFactory,
    tmp_path: Path,
) -> None:
    """The APK's pycantonese has no models: only the override's ``_MODEL_PATH`` redirect finds them."""
    del initialized_bridge_home
    _models(monkeypatch, tmp_path_factory)
    from anki_miner.languages.tagger_provider import get_tagger

    sentence = _profile().smoke_sentence
    expected = [[t.surface, t.feature.pos1, t.feature.pos2, t.feature.lemma] for t in get_tagger("yue").parse(sentence)]

    assert tokens_without_packaged_models("yue", sentence, tmp_path) == expected


@pytest.mark.parametrize("row", TOKEN_ROWS, ids=[row["line"][:8] for row in TOKEN_ROWS])
def test_tokens_match_the_desktop_fixture(
    initialized_bridge_home: Path, monkeypatch: pytest.MonkeyPatch, tmp_path_factory: pytest.TempPathFactory, row: dict
) -> None:
    del initialized_bridge_home
    _models(monkeypatch, tmp_path_factory)
    from anki_miner.languages.tagger_provider import get_tagger
    from anki_miner.services.morphology import iter_token_spans

    line = row["line"]
    got = [
        {
            "surface": token.surface,
            "lemma": token.feature.lemma,
            "pos1": token.feature.pos1,
            "pos2": token.feature.pos2,
            "start": start,
            "end": end,
        }
        for token, start, end in iter_token_spans(line, get_tagger("yue").parse(line))
    ]
    assert got == row["tokens"]


def test_switching_to_cantonese_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language
    from anki_miner.languages.yue.pos import YUE_ALLOWED_POS, YUE_EXCLUDED_SUBTYPES

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=True, subtitle_regex_filter="ja-only")
    cantonese = switch_language(ja, "yue")

    assert cantonese.language == "yue"
    assert cantonese.use_subtitle_regex_filter is False
    assert cantonese.allowed_pos == YUE_ALLOWED_POS == ("NOUN", "VERB", "ADJ", "ADV")
    assert cantonese.excluded_subtypes == YUE_EXCLUDED_SUBTYPES
    assert cantonese.reading_tone_color is True
    assert cantonese.anki_deck_name == "Anki Miner"
    assert cantonese.anki_note_type == ""
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(cantonese, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert cantonese.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_the_extra_card_fields_survive_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    pytest.importorskip("pycantonese", reason="runtime dependency lane: jyutping reads pycantonese's tables")
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert {spec.key for spec in profile.extra_card_fields} == {"measure_word", "expression_jyutping"}
    config = map_config_settings(
        {
            "language": "yue",
            "anki_note_type": "Basic",
            "anki_fields": {"measure_word": "MeasureWord", "expression_jyutping": "Jyutping"},
        },
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    assert config.reading_tone_color is True
    # A CC-CEDICT Canto gloss: the inline classifier is Mandarin data, which yue swaps (家 -> 間).
    definition = "bank CL:家[jia1]"
    word = TokenizedWord(
        surface="銀行",
        lemma="銀行",
        reading="",
        sentence="我去銀行。",
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
    # Tone colours are note-type-overridable CSS variables with a hex fallback (desktop 16b9d0dd2).
    blue = "color:var(--amn-tone-blue, #4286e5)"
    assert extra == {
        "measure_word": "間",
        "expression_jyutping": f'<span style="{blue}">ngan4</span> <span style="{blue}">hong4</span>',
    }

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["MeasureWord"] == "間"
    assert fields["Jyutping"] == extra["expression_jyutping"]


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "yue",
        pinned={
            "cc-canto": "cc-canto-2026-10-01",
            "cc-cedict-canto": "cc-cedict-canto-2026-09-30",
            "hkcancor-yue": "hkcancor-yue-2026-09-20",
            "wty-yue-en": "wty-yue-en-2026.09.20",
        },
        excluded={},
    )
