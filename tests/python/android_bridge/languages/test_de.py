"""German: the profile, its tokenizer, scoped defaults, card fields and catalog, offline.

spaCy ships in the APK; the de_core_news_sm model is ``language-data`` the user downloads.
The tagger tests install the pinned model from the local cache
(``tools/language-data/fetch_language_data.py de``) and skip when it is not cached. The
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

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "de" / "tokens.jsonl"


def _profile():
    from anki_miner.languages.registry import get_profile

    return get_profile("de")


@pytest.fixture(scope="module")
def de_home(tmp_path_factory: pytest.TempPathFactory, initialized_bridge_home: Path):
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp("de-home")) as home:
        install_language_data("de", home)
        yield home


def test_the_profile_loads_and_needs_its_model(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.registry import available_languages

    profile = _profile()
    assert "de" in available_languages()
    assert profile.code == "de"
    assert profile.content_style.direction == "ltr"
    with language_data_home(tmp_path / "home"):
        assert unavailable_reason_code(profile) == "language_data_required"


def test_the_tagger_tokenises_the_smoke_sentence(de_home: Path) -> None:
    from android_bridge.languages import unavailable_reason_code
    from anki_miner.languages.tagger_provider import get_tagger

    del de_home
    assert unavailable_reason_code(_profile()) is None
    tokens = get_tagger("de")(_profile().smoke_sentence)

    # Desktop's tagger at the engine.lock SHA: the separable particle stays a token of its own.
    assert [(token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma) for token in tokens] == [
        ("Er", "PRON", "PPER", "er"),
        ("sieht", "VERB", "VVFIN", "sehen"),
        ("sich", "PRON", "PRF", "sich"),
        ("den", "DET", "ART", "der"),
        ("Film", "NOUN", "NN", "Film"),
        ("an", "ADP", "PTKVZ", "an"),
        (".", "PUNCT", "$.", "--"),
    ]


def test_tokens_match_desktop(de_home: Path) -> None:
    del de_home
    assert assert_tagger_matches("de", FIXTURE) > 40


def test_switching_to_german_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages.de.morphology import DE_ALLOWED_POS, DE_EXCLUDED_SUBTYPES
    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS, switch_language

    ja = replace(AnkiMinerConfig(), use_subtitle_regex_filter=False, subtitle_regex_filter="ja-only")
    german = switch_language(ja, "de")

    defaults = _profile().scoped_defaults
    assert german.language == "de"
    assert german.use_subtitle_regex_filter is True
    assert german.subtitle_regex_filter == defaults["subtitle_regex_filter"] != "ja-only"
    assert german.allowed_pos == DE_ALLOWED_POS
    assert german.excluded_subtypes == DE_EXCLUDED_SUBTYPES
    assert german.downloader_subtitle_langs == "de"
    for name in LANGUAGE_SCOPED_FIELDS:
        assert getattr(german, name) == defaults[name], name
    # The user's Japanese filter is parked, not lost.
    assert german.language_stash["ja"]["subtitle_regex_filter"] == "ja-only"


def test_an_extra_card_field_survives_from_the_dictionary_to_the_note(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    profile = _profile()
    assert {"pos", "noun_gender", "noun_plural"} <= {spec.key for spec in profile.extra_card_fields}
    config = map_config_settings(
        {
            "language": "de",
            "anki_note_type": "Basic",
            "anki_fields": {"noun_gender": "Gender", "noun_plural": "Plural"},
        },
        AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A wty-de-en head line (desktop tests/fixtures/de/wty_row.json), the shape GrammarTagHook reads.
    definition = '<div data-sc-content="Grammar-content">Fuchs m (strong, genitive Fuchses, plural Füchse)</div>'
    word = TokenizedWord(
        surface="Fuchs",
        lemma="Fuchs",
        reading="",
        sentence="Der Fuchs schläft.",
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
    assert extra == {"pos": "noun", "noun_gender": "der", "noun_plural": "Füchse"}

    payload = CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra)
    fields = build_note(payload, config, set(), **_note_builder_kwargs(config)).note["fields"]

    assert fields["Gender"] == "der"
    assert fields["Plural"] == "Füchse"
    # pos is unmapped by default, so it reaches no field of its own.
    assert "noun" not in fields.values()


def test_every_language_data_component_is_found_once_installed(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from anki_miner.services.language_pack_installer import component_path

    entries = language_data_entries("de")
    assert [entry.import_name for entry in entries] == ["de_core_news_sm"]
    with language_data_home(tmp_path / "home") as home:
        assert component_path("de", "de_core_news_sm") is None
        install_sentinels("de", home)
        for entry in entries:
            assert component_path("de", entry.import_name) is not None, entry.import_name


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "de",
        pinned={
            "wty-de-en": "wty-de-en-2026.09.20",
            "opensubtitles-de": "opensubtitles-de-2018",
        },
        excluded={},
    )


# ---------------------------------------------------------------- manga


class _Definitions:
    """Every term defines; the run's only other lookup seams answer nothing."""

    def get_definitions_batch(
        self, pairs: list[tuple[str, str | None]], *_args: object, **_kwargs: object
    ) -> list[str]:
        return [f"<div>{term}</div>" for term, _lemma in pairs]

    def offline_term_identities(self, _pairs: object) -> dict[object, object]:
        return {}

    def css_entries(self) -> list[tuple[str, str, str]]:
        return []

    def clear_run_cache(self) -> None:
        pass

    def close(self) -> None:
        pass


class _Anki:
    def __init__(self) -> None:
        self.card_data: list[object] = []
        self.last_created_note_ids: list[int] = []
        self.last_created_mined_forms: list[str] = []
        self.last_created_lemmas: list[str] = []
        self.last_media_store_failures = 0
        self.last_skipped_duplicates = 0

    def set_cancelled_check(self, _cancelled: object) -> None:
        pass

    def verify_card_target(self) -> None:
        pass

    def create_cards_batch(self, card_data: list[object], progress_callback: object | None = None) -> list[int]:
        del progress_callback
        self.card_data = list(card_data)
        self.last_created_note_ids = list(range(1, len(card_data) + 1))
        self.last_created_mined_forms = [payload.word.mined_form for payload in self.card_data]
        self.last_created_lemmas = [payload.word.lemma for payload in self.card_data]
        return list(self.last_created_note_ids)


def test_a_german_mokuro_volume_yields_units_and_cards(de_home: Path, tmp_path: Path) -> None:
    """Manga OCR reads German through a recogniser, so the loader gates blocks on German's script.

    Desktop ``reading_queue_worker.load_reading_source`` keeps the loader's own
    Japanese gate only for manga-ocr's language (no ``ocr_recognizer``); with that
    gate a German volume kept nothing but its untouched Japanese SFX.
    """
    del de_home
    pytest.importorskip("requests", reason="runtime dependency lane: the processor imports the Anki service")
    import json
    import os

    import android_bridge.reading_mining as reading_mining
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from android_bridge.languages import profile_parser
    from android_bridge.mining import _word_filter_seams
    from android_bridge.protocol import encode_message
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.presenters import NullPresenter
    from anki_miner.services.word_filter import WordFilterService

    profile = _profile()
    assert "manga_ocr" in profile.capabilities and profile.ocr_recognizer
    job = tmp_path / "reading-job-v1-de"
    job.mkdir()
    source = job / "volume.mokuro"
    blocks = [
        {"box": [1, 2, 30, 40], "lines": ["Der Fuchs", "schläft."]},
        # A translated volume's sound effect, left in Japanese.
        {"box": [5, 6, 35, 46], "lines": ["ドドド"]},
    ]
    sidecar = {
        "version": "0.2.4",
        "title": "Comic",
        "title_uuid": "title-fixture",
        "volume": "Band 1",
        "volume_uuid": "volume-fixture",
        "pages": [{"version": "0.2.4", "img_width": 1, "img_height": 1, "img_path": "001.png", "blocks": blocks}],
    }
    source.write_text(json.dumps(sidecar, ensure_ascii=False), encoding="utf-8")
    settings = {"language": "de", "anki_note_type": "Basic"}
    request = reading_mining._parse_request(
        encode_message(
            "mining.reading.run",
            {
                "sourceKind": "mokuro",
                "sourcePath": str(source),
                "imageArchivePath": None,
                "seriesName": None,
                "stagingRoot": str(tmp_path),
                "cacheDir": str(tmp_path),
                "nativeLibraryDir": "/native",
                "configSnapshot": {"settings": settings},
            },
        )
    )
    paths = AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native")
    config = replace(
        map_config_settings(settings, paths).engine_config,
        include_known_words=True,
        bypass_optional_filters=True,
    )

    loader_kwargs = reading_mining._reading_loader_kwargs(config, "mokuro")
    assert loader_kwargs["has_target_script"] == profile.script.contains_target_script
    document = reading_mining._load_document(request, lambda: False, loader_kwargs=loader_kwargs)
    assert [unit.text for unit in document.units] == ["Der Fuchs schläft."]

    parser = profile_parser(config)
    anki = _Anki()
    processor = EpisodeProcessor(
        config=config,
        subtitle_parser=parser,
        word_filter=WordFilterService(config, tagger=parser.tagger, **_word_filter_seams(profile, config)),
        media_extractor=object(),
        definition_service=_Definitions(),
        anki_service=anki,
        presenter=NullPresenter(),
        profile=profile,
    )
    try:
        result = processor.process_reading(document)
    finally:
        processor.close()

    assert result.errors == []
    assert result.cards_created == len(anki.card_data) > 0
    assert {payload.word.sentence for payload in anki.card_data} == {"Der Fuchs schläft."}
    assert "Fuchs" in {payload.word.mined_form for payload in anki.card_data}
