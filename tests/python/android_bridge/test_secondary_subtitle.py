"""A staged translation subtitle reaches the card's Translation field.

One wire request crosses ``mining._parse_request`` and ``mining._process_episode``
into the real vendored ``EpisodeProcessor.process_episode``, which parses the
secondary track with the real ``SubtitleParserService.parse_raw_entries`` and
matches it in ``attach_translations``; the real ``AndroidAnkiAdapter`` then
builds the note and hands it to the in-memory Kotlin Anki double. Tokenizing,
media, and dictionary lookups stay deterministic doubles.
"""

from __future__ import annotations

import collections
import threading
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import android_bridge.mining as mining
import pytest
from android_bridge.callbacks import AndroidAnkiCallbacks
from android_bridge.protocol import encode_message
from test_anki_adapter import RUN_ID, FakeKotlinAnki

_PRIMARY_SRT = """\
1
00:00:01,000 --> 00:00:03,000
猫を見る。
"""

# The matching cue sits five seconds late, so it only lands on the word's
# 1.0-3.0 s window once the request's -5000 ms offset is applied.
_TRANSLATION_SRT = """\
1
00:00:06,200 --> 00:00:07,800
I see a cat.

2
00:00:20,000 --> 00:00:22,000
Unrelated line.
"""


def _presenter() -> Any:
    from anki_miner.presenters import NullPresenter

    class _Presenter(NullPresenter):
        def __init__(self) -> None:
            self.errors: list[str] = []

        def show_error(self, message: str) -> None:
            self.errors.append(message)

    return _Presenter()


class _MediaExtractor:
    """Picture and sentence audio are unmapped, so phase 3 never extracts."""

    def invalidate_audio_stream_cache(self, video_file: Path) -> None:
        del video_file


class _DefinitionService:
    def get_definitions_batch(
        self,
        pairs: list[tuple[str, str | None]],
        progress_callback: object | None,
        fallback_context: object,
        **_kwargs: object,
    ) -> list[str]:
        del progress_callback, fallback_context
        return ['<div class="definition">cat</div>' for _pair in pairs]

    def offline_term_identities(self, pairs: list[tuple[str, str]]) -> dict[Any, Any]:
        del pairs
        return {}

    def clear_run_cache(self) -> None:
        pass

    def close(self) -> None:
        pass


def _run(
    tmp_path: Path,
    home: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    translation: str | None,
    offset_ms: int,
    kotlin: FakeKotlinAnki | None = None,
    cancel_event: threading.Event | None = None,
    words: list[Any] | None = None,
    known_word_db: object | None = None,
    curate: Any = None,
) -> tuple[Any, FakeKotlinAnki, Any]:
    """Run the fixture; the optional doubles default to one 猫 card and a fresh Kotlin."""

    for module in ("pysubs2", "charset_normalizer", "requests"):
        pytest.importorskip(module, reason="runtime dependency lane")
    import anki_miner.services.subtitle_parser as parser_module
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.models import TokenizedWord
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.services.word_filter import WordFilterService

    job = tmp_path / "video-job"
    job.mkdir()
    primary = job / "subtitle.srt"
    primary.write_text(_PRIMARY_SRT, encoding="utf-8")
    secondary: Path | None = None
    if translation is not None:
        secondary = job / "translation.srt"
        secondary.write_text(translation, encoding="utf-8")

    anki_fields = dict.fromkeys(AnkiMinerConfig().anki_fields, "")
    anki_fields.update(word="Expression", sentence="Sentence", sentence_translation="Translation")
    raw = encode_message(
        "mining.video.run",
        {
            "videoPath": str(job / "input.mkv"),
            "subtitlePath": str(primary),
            "episodeName": "Episode 1",
            "seriesName": "Local video",
            "sourceLabel": None,
            "audioTrackOverride": None,
            "audioOnly": False,
            "secondarySubtitlePath": None if secondary is None else str(secondary),
            "secondarySubtitleOffsetMs": offset_ms,
            "cacheDir": str(tmp_path),
            "nativeLibraryDir": str(tmp_path / "native"),
            "configSnapshot": {
                "settings": {"anki_note_type": "Lapis", "anki_fields": anki_fields},
                "androidTtsEnabled": False,
            },
        },
    )
    request = mining._parse_request(raw)
    config = replace(
        mining._map_config(request, home),
        # Desktop-only switches, set here to keep the run off the known-words
        # scan and the offline-dictionary filters this fixture has no data for.
        include_known_words=True,
        bypass_optional_filters=True,
    )

    word = TokenizedWord(
        surface="猫",
        lemma="猫",
        reading="ネコ",
        sentence="猫を見る。",
        start_time=1.0,
        end_time=3.0,
        duration=2.0,
        expression_furigana="猫[ねこ]",
        expression_reading="ねこ",
        lemma_reading="ねこ",
        sentence_furigana="猫[ねこ]を見[み]る。",
        sentence_reading="ねこをみる。",
        pos="名詞",
    )
    tokens = [word] if words is None else list(words)

    class _Parser(parser_module.SubtitleParserService):
        """The real parser with tokenizing stubbed; raw cue parsing stays real."""

        def parse_subtitle_file_with_index(self, subtitle_file: Path, subtitle_offset: float | None = None) -> Any:
            assert subtitle_file == primary
            return list(tokens), []

        def count_lemmas(self, subtitle_file: Path) -> collections.Counter[str]:
            assert subtitle_file == primary
            return collections.Counter(token.lemma for token in tokens)

        def count_fronts(self, subtitle_file: Path) -> collections.Counter[str]:
            assert subtitle_file == primary
            return collections.Counter(token.mined_form for token in tokens)

    # Constructing the parser asks for the shared MeCab tagger; nothing here
    # tokenizes, so it never needs a real one.
    monkeypatch.setattr(parser_module, "get_shared_tagger", lambda: object())
    presenter = _presenter()

    def build_processor(received_config: object, adapters: object, anki_adapter: object) -> object:
        assert received_config is config
        return EpisodeProcessor(
            config=config,
            subtitle_parser=_Parser(config),
            word_filter=WordFilterService(config),
            media_extractor=_MediaExtractor(),
            definition_service=_DefinitionService(),
            anki_service=anki_adapter,
            presenter=presenter,
            known_word_db=known_word_db,
        )

    monkeypatch.setattr(mining, "_build_processor", build_processor)
    kotlin = FakeKotlinAnki() if kotlin is None else kotlin
    kotlin.verify_fields = ["Expression", "Sentence", "Translation"]
    adapters = SimpleNamespace(
        anki=AndroidAnkiCallbacks(kotlin, RUN_ID),
        run_id=RUN_ID,
        cancel_event=threading.Event() if cancel_event is None else cancel_event,
        progress=None,
        curate=(lambda words: list(words)) if curate is None else curate,
    )
    result = mining._process_episode(request, config, adapters)
    return result, kotlin, presenter


def _note_fields(kotlin: FakeKotlinAnki) -> dict[str, str]:
    (create,) = kotlin.requests_for("ankiCreateNotes")
    (note,) = create["payload"]["notes"]
    return note["fields"]


def test_staged_translation_track_fills_the_mapped_translation_field(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, kotlin, presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=_TRANSLATION_SRT,
        offset_ms=-5000,
    )

    assert (result.errors, presenter.errors) == ([], [])
    assert result.cards_created == 1
    assert _note_fields(kotlin) == {
        "Expression": "猫",
        "Sentence": "猫を見る。",
        "Translation": "I see a cat.",
    }


def test_the_curator_counts_occurrences_by_card_front(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """v3.8.0 stamps the curator's occurrence count from ``count_fronts`` (keyed by mined_form)."""
    pytest.importorskip("requests", reason="runtime dependency lane")
    from anki_miner.models import TokenizedWord

    def cat(sentence: str, start: float) -> Any:
        return TokenizedWord(
            surface="猫",
            lemma="猫",
            reading="ネコ",
            sentence=sentence,
            start_time=start,
            end_time=start + 2.0,
            duration=2.0,
            expression_furigana="猫[ねこ]",
            expression_reading="ねこ",
            lemma_reading="ねこ",
            pos="名詞",
        )

    curated: list[Any] = []

    def curate(words: list[Any]) -> list[Any]:
        curated.extend(words)
        return list(words)

    result, _kotlin, presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=None,
        offset_ms=0,
        words=[cat("猫を見る。", 1.0), cat("猫を見る。", 1.0)],
        curate=curate,
    )

    assert (result.errors, presenter.errors) == ([], [])
    # One card front, seen twice: the curator gets one candidate counted twice (Kotlin's occurrenceCount).
    assert [word.mined_form for word in curated] == ["猫"]
    assert curated[0].occurrence_count == 2
    assert result.cards_created == 1


def test_the_translation_offset_is_what_lines_the_track_up(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, kotlin, _presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=_TRANSLATION_SRT,
        offset_ms=0,
    )

    assert result.cards_created == 1
    assert _note_fields(kotlin)["Translation"] == ""


def test_no_translation_track_writes_the_mapped_field_empty_like_desktop(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result, kotlin, presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=None,
        offset_ms=0,
    )

    assert (result.errors, presenter.errors) == ([], [])
    assert result.cards_created == 1
    # build_note writes sentence_translation like sentence_reading: a mapped
    # field is always sent, empty when the word has no translation.
    assert _note_fields(kotlin) == {
        "Expression": "猫",
        "Sentence": "猫を見る。",
        "Translation": "",
    }
