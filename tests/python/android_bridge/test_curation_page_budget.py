"""Curation on large runs: one candidate's sentence budget (AU-003), planning cost and lock scope (AU-034)."""

from __future__ import annotations

import json
import queue
import threading
from collections.abc import Callable
from dataclasses import dataclass, field

import pytest
from android_bridge import jobs
from android_bridge.jobs import CURATION_PAGE_MAX_CANDIDATES, CURATION_PAGE_MAX_UTF8_BYTES, JobRegistry
from android_bridge.protocol import BridgeProtocolError

RUN_ID = "run_" + "1" * 32
REQUEST_ID = "curation_" + "2" * 32


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
