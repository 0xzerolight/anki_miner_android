"""Curation on large runs: one candidate's sentence budget (AU-003), planning cost and lock scope (AU-034)."""

from __future__ import annotations

import json
import queue
import threading
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from pathlib import Path

import pytest
from android_bridge import jobs
from android_bridge.jobs import CURATION_PAGE_MAX_CANDIDATES, CURATION_PAGE_MAX_UTF8_BYTES, JobRegistry
from android_bridge.protocol import BridgeProtocolError, encode_message

RUN_ID = "run_" + "1" * 32
REQUEST_ID = "curation_" + "2" * 32
UNSENT_SENTENCE_ID = "sentence_" + "0" * 32


@dataclass
class Word:
    surface: str
    lemma: str
    sentence: str
    start_time: float
    end_time: float
    duration: float
    reading: str = "よみ"
    expression_reading: str = "よみ"
    sentence_furigana: str = ""
    sentence_reading: str = ""
    pos: str | None = "名詞"
    frequency_rank: int | None = None
    occurrence_count: int = 1
    sentence_candidates: list[Word] = field(default_factory=list)
    line_expansion: tuple[int, int] = (0, 0)

    @property
    def mined_form(self) -> str:
        return self.surface


def _word(index: int) -> Word:
    return Word(f"word-{index}", f"lemma-{index}", f"sentence-{index}。", float(index), index + 1.0, 1.0)


def _start_curation(
    registry: JobRegistry,
    words: list[Word],
    sentence_preview: Callable[[object], jobs.SentencePreview] | None = None,
) -> tuple[str, queue.Queue[tuple[str, dict[str, object]]], list[object], threading.Thread]:
    handle = registry.begin()
    emitted: queue.Queue[tuple[str, dict[str, object]]] = queue.Queue()
    returned: list[object] = []

    def emit(raw: str) -> None:
        emitted.put((raw, json.loads(raw)))

    thread = threading.Thread(
        target=lambda: returned.append(
            registry.await_curation(handle.run_id, words, emit, sentence_preview=sentence_preview)
        ),
        daemon=True,
    )
    thread.start()
    return handle.run_id, emitted, returned, thread


def test_running_page_total_is_the_exact_encoded_upper_bound() -> None:
    payloads = [
        {"candidateId": "candidate_" + "a" * 32, "surface": "猫", "rank": None, "time": 1.5, "count": 3},
        {"candidateId": "candidate_" + "b" * 32, "surface": "naïve “quote”", "nested": [{"x": 0.1}]},
        {"candidateId": "candidate_" + "c" * 32, "surface": "𠮷野家", "empty": []},
    ]

    empty_page = jobs._encoded_page_upper_bound(
        run_id=RUN_ID, request_id=REQUEST_ID, total_candidates=12_345, candidates=[]
    )
    running = empty_page + sum(jobs._encoded_size(payload) for payload in payloads) + len(payloads) - 1

    assert running == jobs._encoded_page_upper_bound(
        run_id=RUN_ID, request_id=REQUEST_ID, total_candidates=12_345, candidates=payloads
    )


def test_pages_fill_to_the_exact_byte_and_count_limits() -> None:
    empty_page = jobs._encoded_page_upper_bound(run_id=RUN_ID, request_id=REQUEST_ID, total_candidates=3, candidates=[])
    # Two candidates that fill a page to the byte: the second one's comma included.
    first = 1_000
    second = CURATION_PAGE_MAX_UTF8_BYTES - empty_page - first - 1

    exact = jobs._partition_pages(
        run_id=RUN_ID,
        request_id=REQUEST_ID,
        total_candidates=3,
        entries=[("a", first), ("b", second), ("c", 10)],
    )
    one_byte_over = jobs._partition_pages(
        run_id=RUN_ID,
        request_id=REQUEST_ID,
        total_candidates=3,
        entries=[("a", first), ("b", second + 1), ("c", 10)],
    )
    by_count = jobs._partition_pages(
        run_id=RUN_ID,
        request_id=REQUEST_ID,
        total_candidates=CURATION_PAGE_MAX_CANDIDATES + 1,
        entries=[(str(index), 10) for index in range(CURATION_PAGE_MAX_CANDIDATES + 1)],
    )

    assert [(page.candidate_ids, page.candidate_start) for page in exact] == [(("a", "b"), 0), (("c",), 2)]
    assert [(page.candidate_ids, page.candidate_start) for page in one_byte_over] == [
        (("a",), 0),
        (("b", "c"), 1),
    ]
    assert [len(page.candidate_ids) for page in by_count] == [CURATION_PAGE_MAX_CANDIDATES, 1]


def test_a_candidate_over_a_whole_page_is_still_refused() -> None:
    empty_page = jobs._encoded_page_upper_bound(run_id=RUN_ID, request_id=REQUEST_ID, total_candidates=2, candidates=[])

    with pytest.raises(BridgeProtocolError) as failure:
        jobs._partition_pages(
            run_id=RUN_ID,
            request_id=REQUEST_ID,
            total_candidates=2,
            entries=[("a", 10), ("b", CURATION_PAGE_MAX_UTF8_BYTES - empty_page + 1)],
        )
    assert failure.value.code == "curation_candidate_too_large"


def test_page_planning_serializes_each_candidate_a_bounded_number_of_times(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # A candidate is encoded once to measure it and once more on its page.
    # Re-encoding every growing page prefix serialized a 100-candidate page
    # 5,050 candidate-times before its first emit (AU-034).
    serialized: list[int] = []
    real_encode = jobs.encode_message
    real_size = jobs._encoded_size

    def counting_encode(message_type: str, payload: dict[str, object]) -> str:
        serialized.append(len(payload.get("candidates", ())))
        return real_encode(message_type, payload)

    def counting_size(value: dict[str, object]) -> int:
        serialized.append(1)
        return real_size(value)

    monkeypatch.setattr(jobs, "encode_message", counting_encode)
    monkeypatch.setattr(jobs, "_encoded_size", counting_size)
    registry = JobRegistry()
    words = [_word(index) for index in range(450)]
    run_id, emitted, returned, thread = _start_curation(registry, words)

    _, first_page = emitted.get(timeout=5)
    serialized_before_first_emit = sum(serialized)
    registry.cancel(run_id)
    thread.join(5)

    assert first_page["type"] == "curation.page.request"
    assert serialized_before_first_emit <= 2 * len(words) + CURATION_PAGE_MAX_CANDIDATES
    assert returned == [None]


def test_cancel_while_candidates_are_built_is_prompt_and_emits_nothing() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    words = [_word(index) for index in range(150)]
    entered = threading.Event()
    release = threading.Event()
    previewed: list[object] = []

    def slow_preview(word: object) -> jobs.SentencePreview:
        previewed.append(word)
        if len(previewed) == 1:
            entered.set()
            release.wait(5)
        return jobs.SentencePreview((0, 0), "")

    emitted: list[str] = []
    returned: list[object] = []
    waiter = threading.Thread(
        target=lambda: returned.append(
            registry.await_curation(handle.run_id, words, emitted.append, sentence_preview=slow_preview)
        ),
        daemon=True,
    )
    waiter.start()
    assert entered.wait(5)

    canceller = threading.Thread(target=registry.cancel, args=(handle.run_id,), daemon=True)
    canceller.start()
    canceller.join(1)
    cancel_was_prompt = not canceller.is_alive()
    release.set()
    waiter.join(5)
    canceller.join(5)

    assert cancel_was_prompt, "cancel() waited for curation planning to finish"
    assert returned == [None]
    assert emitted == []
    # Planning stops at the next candidate once the run is cancelled.
    assert previewed == [words[0]]


def _ja_line(index: int) -> Word:
    # A light-novel line carrying 言う, with furigana and reading as the engine annotates them.
    return Word(
        "言っ",
        "言う",
        f"彼女は少し困ったような顔をして、それでも笑いながら「大丈夫だよ」と言った。{index}",
        float(index),
        index + 1.0,
        1.0,
        reading="いっ",
        expression_reading="いう",
        sentence_furigana=(
            "彼女[かのじょ]は少[すこ]し困[こま]ったような顔[かお]をして、それでも"
            f"笑[わら]いながら「大丈夫[だいじょうぶ]だよ」と言[い]った。{index}"
        ),
        sentence_reading=f"かのじょはすこしこまったようなかおをして、それでもわらいながら「だいじょうぶだよ」といった。{index}",
        pos="動詞",
    )


def _latin_line(index: int) -> Word:
    return Word(
        "no",
        "no",
        "—No, no pienso volver a esa casa nunca más —dijo ella en voz baja, mirando por la ventana "
        f"mientras la lluvia caía sobre el patio. ({index})",
        float(index),
        index + 1.0,
        1.0,
        reading="",
        expression_reading="",
        pos="ADV",
    )


def _frequent_word(
    line: Callable[[int], Word],
    lines: int = 2_000,
    default_line: int = 1_234,
) -> tuple[Word, list[Word]]:
    """A word on ``lines`` lines, attached as the engine does: one variant per line, its own included."""

    variants = [line(index) for index in range(lines)]
    word = replace(variants[default_line], occurrence_count=lines, sentence_candidates=variants)
    return word, variants


def _json_size(value: object) -> int:
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def _response(run_id: str, request_id: str, selection: object) -> str:
    return encode_message("curation.response", {"runId": run_id, "requestId": request_id, "selection": selection})


def _page_response(run_id: str, request_id: str, page_index: int, selection: object) -> str:
    return encode_message(
        "curation.page.response",
        {"runId": run_id, "requestId": request_id, "pageIndex": page_index, "selection": selection},
    )


def test_one_budgeted_candidate_always_fits_a_page_with_the_largest_metadata() -> None:
    empty_page = jobs._encoded_page_upper_bound(
        run_id=RUN_ID, request_id=REQUEST_ID, total_candidates=10**18, candidates=[]
    )

    assert empty_page + jobs._CANDIDATE_MAX_UTF8_BYTES <= CURATION_PAGE_MAX_UTF8_BYTES


@pytest.mark.parametrize("line", [_ja_line, _latin_line], ids=["ja", "latin"])
def test_a_word_on_2000_lines_curates_with_its_default_and_earliest_variants(
    line: Callable[[int], Word],
) -> None:
    word, variants = _frequent_word(line)
    registry = JobRegistry()
    run_id, emitted, returned, thread = _start_curation(registry, [word])

    raw, request = emitted.get(timeout=5)
    assert request["type"] == "curation.request"
    assert len(raw.encode("utf-8")) <= CURATION_PAGE_MAX_UTF8_BYTES
    payload = request["payload"]
    assert isinstance(payload, dict)
    (candidate,) = payload["candidates"]
    assert candidate["occurrenceCount"] == len(variants)
    default, *sent = candidate["sentences"]
    assert default["sentenceId"] == candidate["defaultSentenceId"]
    assert (default["sentence"], default["startTime"]) == (word.sentence, word.start_time)
    # The earliest variants, in the engine's order, as many as the budget holds.
    sent_variants = variants[: len(sent)]
    assert [(entry["sentence"], entry["startTime"]) for entry in sent] == [
        (variant.sentence, variant.start_time) for variant in sent_variants
    ]
    assert 100 < len(sent) < len(variants)
    candidate_size = _json_size(candidate)
    assert candidate_size <= jobs._CANDIDATE_MAX_UTF8_BYTES
    unsent = jobs._sentence_payload(UNSENT_SENTENCE_ID, variants[len(sent)])
    assert candidate_size + 1 + _json_size(unsent) > jobs._CANDIDATE_MAX_UTF8_BYTES
    # Only what was sent can be selected.
    gate = registry._active.curation  # type: ignore[union-attr]
    assert gate is not None
    assert set(gate.candidates[candidate["candidateId"]].sentences) == {
        entry["sentenceId"] for entry in candidate["sentences"]
    }

    registry.resolve_curation(
        _response(
            run_id,
            payload["requestId"],
            [{"candidateId": candidate["candidateId"], "sentenceId": sent[-1]["sentenceId"]}],
        )
    )
    thread.join(5)

    assert not thread.is_alive()
    assert returned == [[sent_variants[-1]]]
    assert returned[0][0] is sent_variants[-1]  # type: ignore[index]


def test_the_default_line_is_not_repeated_among_the_variants() -> None:
    word, _ = _frequent_word(_ja_line, default_line=3)

    _, payload, size = jobs._bounded_candidate("candidate_" + "0" * 32, word)

    starts = [entry["startTime"] for entry in payload["sentences"]]
    assert starts[:5] == [3.0, 0.0, 1.0, 2.0, 4.0]
    assert len(set(starts)) == len(starts)
    assert size == _json_size(payload)


def test_the_budget_counts_each_sentences_preview_bytes() -> None:
    word, _ = _frequent_word(_latin_line)
    translation = "No, I am never going back to that house, she said quietly, watching the rain." * 2
    _, plain, _ = jobs._bounded_candidate("candidate_" + "0" * 32, word)
    registry = JobRegistry()
    run_id, emitted, returned, thread = _start_curation(
        registry,
        [word],
        sentence_preview=lambda _word: jobs.SentencePreview((1, 0), translation),
    )

    _, request = emitted.get(timeout=5)
    payload = request["payload"]
    assert isinstance(payload, dict)
    (candidate,) = payload["candidates"]
    assert all(entry["translation"] == translation for entry in candidate["sentences"])
    assert _json_size(candidate) <= jobs._CANDIDATE_MAX_UTF8_BYTES
    assert len(candidate["sentences"]) < len(plain["sentences"])

    registry.cancel(run_id)
    thread.join(5)
    assert returned == [None]


def test_a_default_sentence_over_the_budget_is_sent_alone() -> None:
    # Under a page but over one candidate's share: the default still goes, its variants do not.
    word = Word("猫", "猫", "猫" * 60_000, 0.0, 1.0, 1.0)
    word.sentence_candidates = [word, *(_ja_line(index) for index in range(1, 4))]
    registry = JobRegistry()
    run_id, emitted, returned, thread = _start_curation(registry, [word])

    raw, request = emitted.get(timeout=5)
    assert len(raw.encode("utf-8")) <= CURATION_PAGE_MAX_UTF8_BYTES
    payload = request["payload"]
    assert isinstance(payload, dict)
    (candidate,) = payload["candidates"]
    assert [entry["sentenceId"] for entry in candidate["sentences"]] == [candidate["defaultSentenceId"]]
    assert candidate["sentences"][0]["sentence"] == word.sentence

    registry.resolve_curation(_response(run_id, payload["requestId"], [{"candidateId": candidate["candidateId"]}]))
    thread.join(5)

    assert returned == [[word]]
    assert returned[0][0] is word  # type: ignore[index]


def test_several_frequent_words_among_many_page_without_refusal() -> None:
    frequent = [_frequent_word(_latin_line)[0] for _ in range(6)]
    words = [*frequent, *(_word(index) for index in range(200))]
    registry = JobRegistry()
    run_id, emitted, returned, thread = _start_curation(registry, words)

    delivered = 0
    while True:
        raw, request = emitted.get(timeout=5)
        assert request["type"] == "curation.page.request"
        assert len(raw.encode("utf-8")) <= CURATION_PAGE_MAX_UTF8_BYTES
        payload = request["payload"]
        assert isinstance(payload, dict)
        delivered += len(payload["candidates"])
        resolution = registry.resolve_curation(_page_response(run_id, payload["requestId"], payload["pageIndex"], []))
        if resolution.final_page:
            break
    thread.join(5)

    assert delivered == len(words)
    assert returned == [[]]


# ---------------------------------------------------------------- reading lane, end to end

_BOOK_LINES = 2_000
_DEFAULT_UNIT = 1_234


def _book_line(index: int) -> str:
    return f"{index}日目、先生がそう言っていたのを思い出して、僕は窓の外をぼんやりと眺めていた。"


class _LongBookParser:
    """Tokenizer double: 先生 on every unit, with the line index curation asks for."""

    def __init__(self, tokenized_word: type, line_lemmas: type) -> None:
        self._tokenized_word = tokenized_word
        self._line_lemmas = line_lemmas

    def parse_text_units(
        self,
        units: list[object],
        want_line_index: bool,
        *,
        subtitle_cleanup: bool = False,
    ) -> tuple[list[object], list[object], dict[str, int]]:
        assert want_line_index is True  # curation is always on for reading runs
        lines = []
        for unit in units:
            start = unit.text.index("先生")
            lines.append(
                self._line_lemmas(
                    line_text=unit.text,
                    lemmas=frozenset({"先生"}),
                    start_time=float(unit.index),
                    end_time=float(unit.index),
                    duration=0.0,
                    sentence_furigana=unit.text.replace("先生", "先生[せんせい]"),
                    sentence_reading=unit.text.replace("先生", "せんせい"),
                    lemma_spans=(("先生", "先生", start, start + 2, start + 2),),
                )
            )
        default = lines[_DEFAULT_UNIT]
        start = default.line_text.index("先生")
        word = self._tokenized_word(
            surface="先生",
            lemma="先生",
            reading="センセイ",
            sentence=default.line_text,
            start_time=default.start_time,
            end_time=default.end_time,
            duration=0.0,
            expression_furigana="先生[せんせい]",
            expression_reading="せんせい",
            lemma_reading="せんせい",
            sentence_furigana=default.sentence_furigana,
            sentence_reading=default.sentence_reading,
            pos="名詞",
            surface_start=start,
            surface_end=start + 2,
            highlight_end=start + 2,
        )
        return [word], lines, {"先生": len(units)}


class _Definitions:
    def get_definitions_batch(
        self, pairs: list[object], progress_callback: object, fallback_context: object, **_: object
    ) -> list[str]:
        return ['<div class="definition">teacher</div>'] * len(pairs)

    def offline_term_identities(self, pairs: list[object]) -> dict[object, object]:
        return {}

    def css_entries(self) -> list[object]:
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

    def set_cancelled_check(self, cancelled: object) -> None:
        pass

    def verify_card_target(self) -> None:
        pass

    def create_cards_batch(self, card_data: list[object], progress_callback: object = None) -> list[int]:
        self.card_data = list(card_data)
        self.last_created_note_ids = [4242]
        self.last_created_mined_forms = [payload.word.mined_form for payload in card_data]
        self.last_created_lemmas = [payload.word.lemma for payload in card_data]
        return [4242]


class _KotlinPicksLastSentence:
    """Kotlin double: answers the curation request with its last sentence variant."""

    def __init__(self, registry: JobRegistry) -> None:
        self._registry = registry
        self.requests: list[str] = []

    def onCurationNeeded(self, raw: str) -> None:
        self.requests.append(raw)
        payload = json.loads(raw)["payload"]
        (candidate,) = payload["candidates"]
        self._registry.resolve_curation(
            _response(
                payload["runId"],
                payload["requestId"],
                [{"candidateId": candidate["candidateId"], "sentenceId": candidate["sentences"][-1]["sentenceId"]}],
            )
        )


def test_a_long_book_mines_its_most_frequent_word_from_a_picked_variant(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane")
    pytest.importorskip("requests", reason="runtime dependency lane")
    import android_bridge.reading_mining as reading_mining
    from android_bridge.callbacks import CallbackAdapters
    from anki_miner.models import TokenizedWord
    from anki_miner.models.word import LineLemmas
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.presenters import NullPresenter
    from anki_miner.services.word_filter import WordFilterService

    job = tmp_path / "reading-job-v1-long-book"
    job.mkdir()
    source = job / "novel.txt"
    source.write_text("\n".join(_book_line(index) for index in range(_BOOK_LINES)), encoding="utf-8")
    request = reading_mining._parse_request(
        encode_message(
            "mining.reading.run",
            {
                "sourceKind": "txt",
                "sourcePath": str(source),
                "imageArchivePath": None,
                "seriesName": None,
                "stagingRoot": str(tmp_path),
                "cacheDir": str(tmp_path),
                "nativeLibraryDir": str(tmp_path / "native"),
                "configSnapshot": {"settings": {"anki_note_type": "Lapis"}, "androidTtsEnabled": False},
            },
        )
    )
    document = reading_mining._load_document(request)
    assert len(document.units) == _BOOK_LINES
    config = replace(
        reading_mining._map_config(request, initialized_bridge_home),
        include_known_words=True,
        bypass_optional_filters=True,
        reading_min_occurrence=1,
        use_i_plus_one_filter=False,
    )
    anki = _Anki()
    processor = EpisodeProcessor(
        config=config,
        subtitle_parser=_LongBookParser(TokenizedWord, LineLemmas),
        word_filter=WordFilterService(config),
        media_extractor=object(),
        definition_service=_Definitions(),
        anki_service=anki,
        presenter=NullPresenter(),
    )
    registry = JobRegistry()
    handle = registry.begin()
    kotlin = _KotlinPicksLastSentence(registry)
    adapters = CallbackAdapters(kotlin, registry, handle)
    curated: list[tuple[list[object], list[object] | None]] = []

    def curate(words: list[object]) -> list[object] | None:
        selection = adapters.curate(words)
        curated.append((list(words[0].sentence_candidates), selection))
        return selection

    try:
        result = processor.process_reading(document, curation_callback=curate, cancel_event=handle.cancel_event)
    finally:
        processor.close()
        registry.finish(handle.run_id)

    assert len(kotlin.requests) == 1, result.errors
    raw = kotlin.requests[0]
    assert len(raw.encode("utf-8")) <= CURATION_PAGE_MAX_UTF8_BYTES
    (candidate,) = json.loads(raw)["payload"]["candidates"]
    sent_variants = len(candidate["sentences"]) - 1
    # Earliest first, and the budget runs out before the default's own unit.
    assert 100 < sent_variants < _DEFAULT_UNIT
    ((engine_variants, selection),) = curated
    assert len(engine_variants) == _BOOK_LINES  # the engine attached every line, unbounded
    picked = engine_variants[sent_variants - 1]
    assert selection is not None and len(selection) == 1
    assert selection[0] is picked
    assert (result.cards_created, result.errors) == (1, [])
    assert anki.card_data[0].word.sentence == _book_line(sent_variants - 1)
