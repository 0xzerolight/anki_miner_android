"""The ``{series}``, ``{episode}`` and ``{volume}`` tag tokens, rendered inside real mining runs.

Each run crosses the real vendored ``EpisodeProcessor``, whose phase 5 renders the tag string
per source, and the real ``AndroidAnkiAdapter``, whose note reaches the in-memory Kotlin Anki
double. Video runs use ``test_secondary_subtitle._run``; reading runs load a real document
through ``reading_mining._load_document`` and mine it through ``reading_mining._process_reading``.
Tokenizing and dictionary lookups stay deterministic doubles.
"""

from __future__ import annotations

import collections
import threading
from collections.abc import Callable
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import android_bridge.reading_mining as reading_mining
import pytest
from android_bridge.callbacks import AndroidAnkiCallbacks
from test_anki_adapter import _MAX_TAG_UTF8_BYTES, RUN_ID, FakeKotlinAnki
from test_reading_engine_smoke import (
    _DefinitionService,
    _reading_request,
    _write_epub_fixture,
    _write_mokuro_fixture,
    _write_subtitle_fixture,
)
from test_secondary_subtitle import _presenter, _run

_TEMPLATE = "mined::{series}::{episode}"


def _note_tags(kotlin: FakeKotlinAnki) -> list[str]:
    (create,) = kotlin.requests_for("ankiCreateNotes")
    (note,) = create["payload"]["notes"]
    return note["tags"]


def _mine_video(tmp_path: Path, home: Path, monkeypatch: pytest.MonkeyPatch, episode_name: str) -> list[str]:
    result, kotlin, presenter = _run(
        tmp_path,
        home,
        monkeypatch,
        translation=None,
        offset_ms=0,
        config_changes={"anki_tags": _TEMPLATE},
        episode_name=episode_name,
    )
    assert (result.errors, presenter.errors) == ([], [])
    assert result.cards_created == 1
    return _note_tags(kotlin)


def test_a_video_tags_its_cleaned_file_name_and_never_the_lane_label(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Kotlin sends the display name's stem as the episode and "Local video" as the series;
    # the bridge hands the engine no series, and the engine strips the release metadata.
    tags = _mine_video(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        "[SubsPlease] Sousou no Frieren - 01 (1080p) [ABCD1234]",
    )

    assert tags == ["mined::Sousou_no_Frieren_-_01"]


def test_a_title_past_the_tag_byte_limit_is_cut_and_the_run_still_mines(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # 300 UTF-8 bytes of Japanese: AnkiDroid takes at most 256 bytes per tag.
    title = "とある魔術の禁書目録" * 10
    assert len(title.encode("utf-8")) == 300

    tags = _mine_video(tmp_path, initialized_bridge_home, monkeypatch, title)

    # "mined::" is 7 bytes; 83 three-byte characters fill the remaining 249 exactly.
    assert tags == ["mined::" + title[:83]]
    assert len(tags[0].encode("utf-8")) == _MAX_TAG_UTF8_BYTES


class _OneWordParser:
    """Tokenizer double: one 猫 on the document's first unit, whatever the text says."""

    def __init__(self, tokenized_word_type: type) -> None:
        self._tokenized_word_type = tokenized_word_type

    def parse_text_units(
        self,
        units: list[Any],
        want_line_index: bool,
        *,
        subtitle_cleanup: bool = False,
    ) -> tuple[list[Any], None, collections.Counter[str]]:
        del want_line_index, subtitle_cleanup
        unit = units[0]
        word = self._tokenized_word_type(
            surface="猫",
            lemma="猫",
            reading="ネコ",
            sentence=unit.text,
            start_time=float(unit.index),
            end_time=float(unit.index),
            duration=0.0,
            pos="名詞",
        )
        return [word], None, collections.Counter({"猫": 1})


def _mine_reading(
    tmp_path: Path,
    home: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    template: str,
    source_kind: str,
    write_fixture: Callable[[Path], Any],
    series_name: str | None = None,
) -> list[str]:
    for module in ("PIL", "lxml", "pysubs2", "requests"):
        pytest.importorskip(module, reason="runtime dependency lane")
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.models import TokenizedWord
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.services.word_filter import WordFilterService

    written = write_fixture(tmp_path / "reading-job-v1-tags")
    source, image_archive = written if isinstance(written, tuple) else (written, None)
    request = reading_mining._parse_request(
        _reading_request(
            cache_dir=tmp_path,
            source_kind=source_kind,
            source_path=source,
            image_archive_path=image_archive,
            series_name=series_name,
        )
    )
    document = reading_mining._load_document(request)
    anki_fields = dict.fromkeys(AnkiMinerConfig().anki_fields, "")
    anki_fields["word"] = "Expression"
    config = replace(
        reading_mining._map_config(request, home),
        anki_fields=anki_fields,
        anki_tags=template,
        include_known_words=True,
        bypass_optional_filters=True,
        reading_min_occurrence=1,
        use_i_plus_one_filter=False,
        # The best-sentence pick reads the per-line index, which the parser double never builds.
        pick_best_sentence=False,
    )
    presenter = _presenter()

    def build(
        received_config: object,
        adapters: object,
        anki_adapter: object,
        *,
        sentence_audio_fetcher: object | None,
    ) -> object:
        assert received_config is config
        assert sentence_audio_fetcher is None
        return EpisodeProcessor(
            config=config,
            subtitle_parser=_OneWordParser(TokenizedWord),
            word_filter=WordFilterService(config),
            media_extractor=object(),
            definition_service=_DefinitionService(),
            anki_service=anki_adapter,
            presenter=presenter,
        )

    monkeypatch.setattr(reading_mining, "_build_processor", build)
    kotlin = FakeKotlinAnki()
    kotlin.verify_fields = ["Expression"]
    adapters = SimpleNamespace(
        anki=AndroidAnkiCallbacks(kotlin, RUN_ID),
        run_id=RUN_ID,
        cancel_event=threading.Event(),
        progress=None,
        curate=lambda words: list(words),
        presenter=presenter,
    )

    result = reading_mining._process_reading(document, config, adapters)

    assert (result.errors, presenter.errors) == ([], [])
    assert result.cards_created == 1
    return _note_tags(kotlin)


@pytest.mark.parametrize(
    ("template", "source_kind", "write_fixture", "series_name", "expected"),
    [
        # A manga's mokuro title is {series} and its volume {volume}; it has no {episode}.
        (_TEMPLATE, "mokuro", _write_mokuro_fixture, None, ["mined::漫画作品"]),
        ("mined::{series}::{volume}", "mokuro", _write_mokuro_fixture, None, ["mined::漫画作品::第一巻"]),
        # A book's series is the constant "Books", so its title stands in as {series}.
        (_TEMPLATE, "epub", _write_epub_fixture, None, ["mined::試験小説"]),
        # A subtitle file reads like a video: the user's series, then the file name.
        (_TEMPLATE, "subtitle", _write_subtitle_fixture, "Imported subtitles", ["mined::Imported_subtitles::episode"]),
        # Kotlin's "Subtitles" stand-in for no series is never a series of its own.
        (_TEMPLATE, "subtitle", _write_subtitle_fixture, "Subtitles", ["mined::episode"]),
    ],
    ids=["manga", "manga-volume", "book", "subtitle-series", "subtitle-default-series"],
)
def test_a_reading_source_fills_the_tag_tokens(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    template: str,
    source_kind: str,
    write_fixture: Callable[[Path], Any],
    series_name: str | None,
    expected: list[str],
) -> None:
    tags = _mine_reading(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        template=template,
        source_kind=source_kind,
        write_fixture=write_fixture,
        series_name=series_name,
    )

    assert tags == expected
