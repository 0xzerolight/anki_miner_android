"""The curator opens on the merged sentence and its translation, and ± extends it.

With ``merge_incomplete_cues`` on, the engine stamps the automatic cue merge
before curation. One wire request runs the real vendored ``process_episode``
over real SRTs; the curation callback is the real ``JobRegistry`` handshake fed
by the bridge's ``_SentencePreview``, answered the way Kotlin's draft answers
(seeded from each sentence's own counts). The card fields then show what each
answer mines. Tokenizing, media, and dictionary lookups stay deterministic
doubles.
"""

from __future__ import annotations

import collections
import json
import threading
from collections.abc import Callable
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import android_bridge.mining as mining
import pytest
from android_bridge.callbacks import AndroidAnkiCallbacks
from android_bridge.jobs import JobRegistry
from android_bridge.protocol import encode_message
from jsonschema import Draft202012Validator
from test_anki_adapter import RUN_ID, FakeKotlinAnki
from test_secondary_subtitle import _DefinitionService, _MediaExtractor, _presenter

# Cue 1 is a fragment that cue 2 finishes; cue 3 is the next sentence. Cues 4-5
# are a second fragment-and-finish pair, where the word's other occurrence sits.
_PRIMARY_SRT = """\
1
00:00:01,000 --> 00:00:02,000
猫を

2
00:00:02,200 --> 00:00:03,000
見た。

3
00:00:03,200 --> 00:00:04,000
次の文。

4
00:00:10,000 --> 00:00:11,000
猫が

5
00:00:11,100 --> 00:00:12,000
好き。
"""

_TRANSLATION_SRT = """\
1
00:00:01,000 --> 00:00:02,000
I saw

2
00:00:02,200 --> 00:00:03,000
the cat.

3
00:00:03,200 --> 00:00:04,000
Next line.

4
00:00:10,000 --> 00:00:12,000
I like cats.
"""

_SCHEMA = json.loads(
    (Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/curation.schema.json").read_text(
        encoding="utf-8"
    )
)

Respond = Callable[[dict[str, Any]], list[dict[str, Any]]]


def _run(
    tmp_path: Path,
    home: Path,
    monkeypatch: pytest.MonkeyPatch,
    respond: Respond,
) -> tuple[dict[str, Any], dict[str, str]]:
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
    secondary = job / "translation.srt"
    secondary.write_text(_TRANSLATION_SRT, encoding="utf-8")

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
            "secondarySubtitlePath": str(secondary),
            "secondarySubtitleOffsetMs": 0,
            "cacheDir": str(tmp_path),
            "nativeLibraryDir": str(tmp_path / "native"),
            "configSnapshot": {
                "settings": {
                    "anki_note_type": "Lapis",
                    "anki_fields": anki_fields,
                    "merge_incomplete_cues": True,
                },
                "androidTtsEnabled": False,
            },
        },
    )
    request = mining._parse_request(raw)
    config = replace(
        mining._map_config(request, home),
        include_known_words=True,
        bypass_optional_filters=True,
    )
    assert config.merge_incomplete_cues is True

    def occurrence(sentence: str, start: float) -> Any:
        return TokenizedWord(
            surface="猫",
            lemma="猫",
            reading="ネコ",
            sentence=sentence,
            start_time=start,
            end_time=start + 1.0,
            duration=1.0,
            expression_furigana="猫[ねこ]",
            expression_reading="ねこ",
            lemma_reading="ねこ",
            pos="名詞",
        )

    word = occurrence("猫を", 1.0)
    word.sentence_candidates = [occurrence("猫を", 1.0), occurrence("猫が", 10.0)]

    class _Parser(parser_module.SubtitleParserService):
        """The real parser with tokenizing stubbed; raw cue parsing stays real."""

        def parse_subtitle_file_with_index(self, subtitle_file: Path, subtitle_offset: float | None = None) -> Any:
            assert subtitle_file == primary
            return [word], []

        def count_lemmas(self, subtitle_file: Path) -> collections.Counter[str]:
            return collections.Counter({"猫": 2})

        def count_fronts(self, subtitle_file: Path) -> collections.Counter[str]:
            # The curator's occurrence count, keyed by card front (mined_form).
            return collections.Counter({"猫": 2})

    monkeypatch.setattr(parser_module, "get_shared_tagger", lambda: object())
    presenter = _presenter()

    def build_processor(received_config: object, adapters: object, anki_adapter: object) -> object:
        return EpisodeProcessor(
            config=config,
            subtitle_parser=_Parser(config),
            word_filter=WordFilterService(config),
            media_extractor=_MediaExtractor(),
            definition_service=_DefinitionService(),
            anki_service=anki_adapter,
            presenter=presenter,
        )

    monkeypatch.setattr(mining, "_build_processor", build_processor)
    registry = JobRegistry()
    handle = registry.begin()
    emitted: list[dict[str, Any]] = []

    def emit(raw_request: str) -> None:
        payload = json.loads(raw_request)["payload"]
        Draft202012Validator(_SCHEMA).validate(payload)
        emitted.append(payload)
        registry.resolve_curation(
            encode_message(
                "curation.response",
                {"runId": handle.run_id, "requestId": payload["requestId"], "selection": respond(payload)},
            )
        )

    kotlin = FakeKotlinAnki()
    kotlin.verify_fields = ["Expression", "Sentence", "Translation"]
    adapters = SimpleNamespace(
        anki=AndroidAnkiCallbacks(kotlin, RUN_ID),
        run_id=RUN_ID,
        cancel_event=threading.Event(),
        progress=None,
        sentence_preview=None,
    )
    adapters.curate = lambda words: registry.await_curation(
        handle.run_id,
        words,
        emit,
        allow_line_expansion=True,
        sentence_preview=adapters.sentence_preview,
    )
    result = mining._process_episode(request, config, adapters)
    registry.finish(handle.run_id)

    assert (result.errors, presenter.errors) == ([], [])
    assert result.cards_created == 1
    (create,) = kotlin.requests_for("ankiCreateNotes")
    (note,) = create["payload"]["notes"]
    (curation,) = emitted
    return curation, note["fields"]


def _sentences(curation: dict[str, Any]) -> tuple[str, dict[str, Any], dict[str, Any]]:
    (candidate,) = curation["candidates"]
    default, alternative = candidate["sentences"]
    assert default["sentenceId"] == candidate["defaultSentenceId"]
    return candidate["candidateId"], default, alternative


def _selection(candidate_id: str, sentence: dict[str, Any], *, extra_after: int = 0) -> list[dict[str, Any]]:
    """Kotlin's draft: the sentence's own counts, plus any + next line taps."""

    chosen: dict[str, Any] = {"candidateId": candidate_id, "sentenceId": sentence["sentenceId"]}
    if sentence.get("linesBefore"):
        chosen["linesBefore"] = sentence["linesBefore"]
    lines_after = sentence.get("linesAfter", 0) + extra_after
    if lines_after:
        chosen["linesAfter"] = lines_after
    return [chosen]


def test_each_sentence_arrives_with_its_merge_and_translation(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def untouched(curation: dict[str, Any]) -> list[dict[str, Any]]:
        candidate_id, default, _alternative = _sentences(curation)
        return _selection(candidate_id, default)

    curation, fields = _run(tmp_path, initialized_bridge_home, monkeypatch, untouched)

    _candidate_id, default, alternative = _sentences(curation)
    assert curation["candidates"][0]["occurrenceCount"] == 2
    # The fragment still names the cue; the counts say how far the card reaches.
    assert (default["sentence"], default.get("linesBefore"), default["linesAfter"]) == ("猫を", None, 1)
    assert default["translation"] == "I saw the cat."
    # The other occurrence carries the merge a pick re-derives, as on desktop.
    assert (alternative["sentence"], alternative.get("linesBefore"), alternative["linesAfter"]) == ("猫が", None, 1)
    assert alternative["translation"] == "I like cats."
    assert fields == {"Expression": "猫", "Sentence": "猫を 見た。", "Translation": "I saw the cat."}


def test_reset_mines_the_fragment_alone(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def reset(curation: dict[str, Any]) -> list[dict[str, Any]]:
        candidate_id, default, _alternative = _sentences(curation)
        return [{"candidateId": candidate_id, "sentenceId": default["sentenceId"]}]

    _curation, fields = _run(tmp_path, initialized_bridge_home, monkeypatch, reset)

    assert fields == {"Expression": "猫", "Sentence": "猫を", "Translation": "I saw"}


def test_a_next_line_extends_the_merge_rather_than_replacing_it(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def extend(curation: dict[str, Any]) -> list[dict[str, Any]]:
        candidate_id, default, _alternative = _sentences(curation)
        return _selection(candidate_id, default, extra_after=1)

    _curation, fields = _run(tmp_path, initialized_bridge_home, monkeypatch, extend)

    assert fields == {
        "Expression": "猫",
        "Sentence": "猫を 見た。 次の文。",
        "Translation": "I saw the cat. Next line.",
    }


def test_a_picked_occurrence_mines_with_its_own_merge(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def pick(curation: dict[str, Any]) -> list[dict[str, Any]]:
        candidate_id, _default, alternative = _sentences(curation)
        return _selection(candidate_id, alternative)

    _curation, fields = _run(tmp_path, initialized_bridge_home, monkeypatch, pick)

    assert fields == {"Expression": "猫", "Sentence": "猫が 好き。", "Translation": "I like cats."}
