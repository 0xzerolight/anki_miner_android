"""Arabic: the profile, its tagger, scoped defaults, card fields and catalog, offline.

Arabic mines only once its language data (the calima-msa-r13 morphology database) is installed.
As on desktop (``tests/_pack_seeds.py``), the repository commits none of that GPL-2.0 database:
the real pinned archive is installed from the local cache
(``tools/language-data/fetch_language_data.py ar``) through the real
``resource.languagedata.install`` op, once per module, and a missing archive FAILS the tests
rather than skipping them.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import pytest
from catalog_completeness import assert_catalog_complete
from language_data_fixtures import install_language_data, language_data_home

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("requests", reason="runtime dependency lane: the pack installer imports the downloader")

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "ar"
TOKEN_ROWS = [
    json.loads(line)
    for line in (FIXTURES / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
    if not line.startswith("#")
]


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("ar")


@pytest.fixture(scope="module")
def ar_home(initialized_bridge_home: Path, tmp_path_factory: pytest.TempPathFactory) -> Iterator[Path]:
    """A home with the pinned ``ar-calima-msa`` archive installed; the tagger loads it once."""
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("ar-home")) as home:
        install_language_data("ar", home, required=True)
        yield home


def test_the_profile_loads_and_waits_for_its_language_data(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "ar" in available_languages()
    assert profile.code == "ar"
    assert profile.content_style.direction == "rtl"
    with language_data_home(tmp_path / "home") as home:
        assert not (home / "language_packs" / "ar").exists()
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(ar_home: Path) -> None:
    del ar_home
    from anki_miner.languages.tagger_provider import get_tagger

    sentence = _profile().smoke_sentence
    tokens = get_tagger("ar")(sentence)
    assert [(t.feature.pos1, t.feature.lemma) for t in tokens] == [
        ("verb", "ذهب"),
        ("noun", "طالب"),
        ("prep", "إلى"),
        ("noun", "مدرسة"),
        ("noun", "صباح"),
        ("punc", "."),
    ]
    assert "".join(token.surface for token in tokens) == sentence.replace(" ", "")


@pytest.mark.parametrize("row", TOKEN_ROWS, ids=[f"{i:02d}-{row['pos1']}" for i, row in enumerate(TOKEN_ROWS)])
def test_tokens_match_the_desktop_fixture(ar_home: Path, row: dict) -> None:
    del ar_home
    from anki_miner.languages.ar.morphology import ArabicMinedForm
    from anki_miner.languages.tagger_provider import get_tagger

    (token,) = get_tagger("ar")(row["surface"])
    actual = {
        "surface": token.surface,
        "pos1": token.feature.pos1,
        "pos2": token.feature.pos2,
        "lemma": token.feature.lemma,
        "orth_base": token.feature.orthBase,
        "reading": token.feature.reading,
        "morph": token.morph,
    }
    assert actual == {key: row[key] for key in actual}, row["note"]
    mined = ArabicMinedForm().mined_form(token.feature.pos1, token.feature.orthBase, token.feature.lemma, token.surface)
    assert mined == row["mined_form"]


def test_switching_to_arabic_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import base_config
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.ar.morphology import AR_ALLOWED_POS
    from anki_miner.languages.ar.script import AR_SUBTITLE_REGEX
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    ar = switch_language(ja, "ar")
    first_visit = base_config("ar")

    assert ar.language == "ar"
    assert ar.use_subtitle_regex_filter is True
    assert ar.subtitle_regex_filter == AR_SUBTITLE_REGEX
    assert ar.allowed_pos == AR_ALLOWED_POS
    assert ar.anki_fields["expression_reading"] == "Reading"
    defaults = _profile().scoped_defaults
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(ar, name) == defaults[name], name
        assert getattr(first_visit, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert ar.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_snapshot_to_the_note(
    ar_home: Path, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    del ar_home
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData
    from anki_miner.models.reading import ReadingUnit
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert {"root", "clitic_segmentation"} <= {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {
            "language": "ar",
            "anki_note_type": "Basic",
            "anki_fields": {"root": "Root", "clitic_segmentation": "Segmentation"},
        },
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    words, _index, _counts = profile.create_parser(config).parse_text_units(
        [ReadingUnit(text=profile.smoke_sentence, index=0, location_label="t")], False
    )
    word = next(word for word in words if word.mined_form == "طالب")
    extra_fields: dict[str, str] = {}
    # Phase 5's hook pass itself, on the parser's own word (its ``morph`` carries the analysis).
    EpisodeProcessor._apply_render_hooks(SimpleNamespace(config=config, profile=profile), word, "", extra_fields)
    card = CardPayload(word=word, media=MediaData(), definition="student", extra_fields=extra_fields)

    note = build_note(card, config, set(), **_note_builder_kwargs(config)).note

    assert note["fields"]["Root"] == "ط ل ب"
    assert note["fields"]["Segmentation"] == "ال+ طالِب"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "ar",
        pinned={
            "wty-ar-en": "wty-ar-en-2026.09.20",
            "opensubtitles-ar": "opensubtitles-ar-2018",
        },
        excluded={},
    )


def test_the_installed_catalog_language_data_is_what_the_tagger_reads(ar_home: Path) -> None:
    from android_bridge.language_data import installed_language_data
    from android_bridge.languages import DOWNLOADABLE_DATA_COMPONENTS, unavailable_reason_code
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.languages.ar.availability import AR_DB_COMPONENT, AR_DB_FILE
    from anki_miner.services.language_pack_installer import component_path

    (data,) = [r for r in load_resource_catalog("ar").resources if r.kind == "language-data"]
    assert data.resource_id == "ar-calima-msa"
    assert ("ar", AR_DB_COMPONENT) in DOWNLOADABLE_DATA_COMPONENTS
    path = component_path("ar", AR_DB_COMPONENT)
    assert path is not None
    assert (path / AR_DB_FILE).is_file()
    assert installed_language_data(ar_home) == ["ar-calima-msa"]
    assert unavailable_reason_code(_profile()) is None
