from __future__ import annotations

import json
import queue
import re
import threading
from dataclasses import dataclass, field
from pathlib import Path

import pytest
from android_bridge.anki_limits import ANKI_LIMITS_V1
from android_bridge.jobs import (
    CURATION_PAGE_MAX_CANDIDATES,
    CURATION_PAGE_MAX_UTF8_BYTES,
    JobRegistry,
)
from android_bridge.protocol import BridgeProtocolError, encode_message

UNKNOWN_REQUEST_ID = "curation_" + "0" * 32
UNKNOWN_CANDIDATE_ID = "candidate_" + "0" * 32
UNKNOWN_SENTENCE_ID = "sentence_" + "0" * 32


@dataclass
class FakeWord:
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
    sentence_candidates: list[FakeWord] = field(default_factory=list)

    @property
    def mined_form(self) -> str:
        return self.surface


def _start_wait(
    registry: JobRegistry,
    candidates: list[object],
) -> tuple[str, dict[str, object], list[object], threading.Thread]:
    handle = registry.begin()
    request, returned, thread = _start_wait_for_run(registry, handle.run_id, candidates)
    return handle.run_id, request, returned, thread


def _start_wait_for_run(
    registry: JobRegistry,
    run_id: str,
    candidates: list[object],
) -> tuple[dict[str, object], list[object], threading.Thread]:
    emitted = threading.Event()
    request: dict[str, object] = {}
    returned: list[object] = []

    def emit(raw: str) -> None:
        request.update(json.loads(raw))
        emitted.set()

    def wait() -> None:
        returned.append(registry.await_curation(run_id, candidates, emit))

    thread = threading.Thread(target=wait, daemon=True)
    thread.start()
    assert emitted.wait(1), "curation request was not emitted"
    return request, returned, thread


def _response(run_id: str, request_id: str, selection: object) -> str:
    return encode_message(
        "curation.response",
        {"runId": run_id, "requestId": request_id, "selection": selection},
    )


def _page_response(
    run_id: str,
    request_id: str,
    page_index: int,
    selection: object,
) -> str:
    return encode_message(
        "curation.page.response",
        {
            "runId": run_id,
            "requestId": request_id,
            "pageIndex": page_index,
            "selection": selection,
        },
    )


def _start_paged_wait(
    registry: JobRegistry,
    candidates: list[object],
) -> tuple[
    str,
    queue.Queue[tuple[str, dict[str, object]]],
    list[object],
    threading.Thread,
]:
    handle = registry.begin()
    emitted: queue.Queue[tuple[str, dict[str, object]]] = queue.Queue()
    returned: list[object] = []

    def emit(raw: str) -> None:
        emitted.put((raw, json.loads(raw)))

    def wait() -> None:
        returned.append(registry.await_curation(handle.run_id, candidates, emit))

    thread = threading.Thread(target=wait, daemon=True)
    thread.start()
    return handle.run_id, emitted, returned, thread


def test_one_active_job_and_fresh_cancel_event_per_run() -> None:
    registry = JobRegistry()
    first = registry.begin()

    with pytest.raises(BridgeProtocolError) as active:
        registry.begin()
    assert active.value.code == "job_already_active"

    registry.cancel(first.run_id)
    registry.finish(first.run_id)
    second = registry.begin()
    assert second.run_id != first.run_id
    assert second.cancel_event is not first.cancel_event
    assert not second.cancel_event.is_set()


def test_selection_returns_original_objects_and_chosen_sentence_variant() -> None:
    registry = JobRegistry()
    original = FakeWord("食べた", "食べる", "最初の文", 1, 2, 1)
    alternative = FakeWord("食べた", "食べる", "別の文", 4, 5, 1)
    original.sentence_candidates = [original, alternative]
    second = FakeWord("猫", "猫", "猫だ", 6, 7, 1)
    run_id, request, returned, thread = _start_wait(registry, [original, second])
    payload = request["payload"]
    assert isinstance(payload, dict)
    candidates = payload["candidates"]
    assert isinstance(candidates, list)
    first_payload = candidates[0]
    second_payload = candidates[1]
    alternative_id = first_payload["sentences"][1]["sentenceId"]

    registry.resolve_curation(
        _response(
            run_id,
            payload["requestId"],
            [
                {
                    "candidateId": first_payload["candidateId"],
                    "sentenceId": alternative_id,
                },
                {"candidateId": second_payload["candidateId"]},
            ],
        )
    )
    thread.join(1)

    assert not thread.is_alive()
    assert returned == [[alternative, second]]
    assert returned[0][0] is alternative
    assert returned[0][1] is second
    assert original.surface not in first_payload["candidateId"]


@pytest.mark.parametrize(("selection", "expected"), [(None, None), ([], [])])
def test_null_and_empty_selection_remain_distinct(selection: object, expected: object) -> None:
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, request, returned, thread = _start_wait(registry, [word])
    payload = request["payload"]
    assert isinstance(payload, dict)

    registry.resolve_curation(_response(run_id, payload["requestId"], selection))
    thread.join(1)

    assert returned == [expected]


def test_duplicate_and_stale_responses_are_rejected() -> None:
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, request, _, thread = _start_wait(registry, [word])
    payload = request["payload"]
    assert isinstance(payload, dict)
    response = _response(run_id, payload["requestId"], [])
    registry.resolve_curation(response)

    with pytest.raises(BridgeProtocolError) as duplicate:
        registry.resolve_curation(response)
    assert duplicate.value.code == "duplicate_curation_response"
    thread.join(1)

    stale = _response(run_id, UNKNOWN_REQUEST_ID, [])
    with pytest.raises(BridgeProtocolError) as stale_error:
        registry.resolve_curation(stale)
    assert stale_error.value.code == "stale_curation_request"


@pytest.mark.parametrize("action", ["cancel", "finish", "shutdown"])
def test_every_terminal_action_unblocks_curation(action: str) -> None:
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, _, returned, thread = _start_wait(registry, [word])

    if action == "cancel":
        registry.cancel(run_id)
    elif action == "finish":
        registry.finish(run_id)
    else:
        registry.shutdown()
    thread.join(1)

    assert not thread.is_alive()
    assert returned == [None]


def test_cancel_before_curation_does_not_emit() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    registry.cancel(handle.run_id)
    emitted: list[str] = []

    result = registry.await_curation(handle.run_id, [], emitted.append)

    assert result is None
    assert emitted == []


@pytest.mark.parametrize(
    ("selection", "code"),
    [
        ([{"candidateId": UNKNOWN_CANDIDATE_ID}], "unknown_candidate"),
        (
            [{"candidateId": "USE_REAL", "sentenceId": UNKNOWN_SENTENCE_ID}],
            "unknown_sentence",
        ),
        (
            [{"candidateId": "USE_REAL"}, {"candidateId": "USE_REAL"}],
            "duplicate_candidate",
        ),
    ],
)
def test_invalid_object_ids_never_reconstruct_engine_values(selection: list[dict[str, str]], code: str) -> None:
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, request, _, thread = _start_wait(registry, [word])
    payload = request["payload"]
    assert isinstance(payload, dict)
    candidate_id = payload["candidates"][0]["candidateId"]
    patched = [
        {key: candidate_id if value == "USE_REAL" else value for key, value in item.items()} for item in selection
    ]

    with pytest.raises(BridgeProtocolError) as error:
        registry.resolve_curation(_response(run_id, payload["requestId"], patched))
    assert error.value.code == code

    registry.cancel(run_id)
    thread.join(1)


def test_shutdown_rejects_future_jobs() -> None:
    registry = JobRegistry()
    registry.shutdown()

    with pytest.raises(BridgeProtocolError) as error:
        registry.begin()
    assert error.value.code == "registry_shutdown"


@pytest.mark.parametrize("invalid_sentence_id", [None, ""])
def test_sentence_id_may_be_omitted_but_not_null_or_empty(
    invalid_sentence_id: object,
) -> None:
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, request, _, thread = _start_wait(registry, [word])
    payload = request["payload"]
    assert isinstance(payload, dict)
    candidate_id = payload["candidates"][0]["candidateId"]
    response = _response(
        run_id,
        payload["requestId"],
        [{"candidateId": candidate_id, "sentenceId": invalid_sentence_id}],
    )

    with pytest.raises(BridgeProtocolError) as error:
        registry.resolve_curation(response)
    assert error.value.code == "invalid_curation_response"
    assert thread.is_alive()
    registry.cancel(run_id)
    thread.join(1)


def test_synchronous_curation_response_cannot_miss_the_gate() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)

    def respond_synchronously(raw: str) -> None:
        payload = json.loads(raw)["payload"]
        registry.resolve_curation(
            _response(
                handle.run_id,
                payload["requestId"],
                [{"candidateId": payload["candidates"][0]["candidateId"]}],
            )
        )

    returned = registry.await_curation(handle.run_id, [word], respond_synchronously)

    assert returned == [word]
    assert returned[0] is word


def test_callback_failure_cleans_gate_for_retry() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)

    with pytest.raises(RuntimeError, match="callback failed"):
        registry.await_curation(
            handle.run_id,
            [word],
            lambda _: (_ for _ in ()).throw(RuntimeError("callback failed")),
        )

    request, returned, thread = _start_wait_for_run(registry, handle.run_id, [word])
    payload = request["payload"]
    assert isinstance(payload, dict)
    registry.resolve_curation(_response(handle.run_id, payload["requestId"], []))
    thread.join(1)
    assert returned == [[]]


def test_resolve_versus_cancel_is_race_safe_and_never_hangs() -> None:
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, request, returned, waiter = _start_wait(registry, [word])
    payload = request["payload"]
    assert isinstance(payload, dict)
    response = _response(
        run_id,
        payload["requestId"],
        [{"candidateId": payload["candidates"][0]["candidateId"]}],
    )
    start = threading.Barrier(3)
    resolver_errors: list[BridgeProtocolError] = []

    def resolve() -> None:
        start.wait()
        try:
            registry.resolve_curation(response)
        except BridgeProtocolError as error:
            resolver_errors.append(error)

    def cancel() -> None:
        start.wait()
        registry.cancel(run_id)

    resolver = threading.Thread(target=resolve)
    canceller = threading.Thread(target=cancel)
    resolver.start()
    canceller.start()
    start.wait()
    resolver.join(1)
    canceller.join(1)
    waiter.join(1)

    assert not resolver.is_alive() and not canceller.is_alive() and not waiter.is_alive()
    assert returned in ([[word]], [None])
    assert not resolver_errors or resolver_errors[0].code == "duplicate_curation_response"


def test_repeated_cancellation_is_idempotent() -> None:
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, _, returned, thread = _start_wait(registry, [word])

    assert registry.cancel(run_id) is True
    assert registry.cancel(run_id) is False
    thread.join(1)

    assert returned == [None]


def test_correlated_cancellation_after_finish_is_idempotent() -> None:
    registry = JobRegistry()
    handle = registry.begin()

    registry.finish(handle.run_id)

    assert registry.cancel(handle.run_id) is False


def test_unrelated_cancellation_after_finish_remains_rejected() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    registry.finish(handle.run_id)

    with pytest.raises(BridgeProtocolError) as failure:
        registry.cancel("run_ffffffffffffffffffffffffffffffff")

    assert failure.value.code == "no_active_job"


def test_sequential_curation_rejects_prior_response_without_poisoning_current_gate() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    first_word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    first_request, first_returned, first_thread = _start_wait_for_run(
        registry,
        handle.run_id,
        [first_word],
    )
    first_payload = first_request["payload"]
    assert isinstance(first_payload, dict)
    first_response = _response(handle.run_id, first_payload["requestId"], [])
    registry.resolve_curation(first_response)
    first_thread.join(1)
    assert first_returned == [[]]

    second_word = FakeWord("犬", "犬", "犬だ", 3, 4, 1)
    second_request, second_returned, second_thread = _start_wait_for_run(
        registry,
        handle.run_id,
        [second_word],
    )
    second_payload = second_request["payload"]
    assert isinstance(second_payload, dict)

    with pytest.raises(BridgeProtocolError) as stale:
        registry.resolve_curation(first_response)
    assert stale.value.code == "stale_curation_request"
    assert second_thread.is_alive()

    registry.resolve_curation(
        _response(
            handle.run_id,
            second_payload["requestId"],
            [{"candidateId": second_payload["candidates"][0]["candidateId"]}],
        )
    )
    second_thread.join(1)
    assert second_returned == [[second_word]]


def test_large_curation_is_complete_bounded_and_aggregates_original_objects() -> None:
    registry = JobRegistry()
    words = [FakeWord(f"word-{index}", f"lemma-{index}", f"sentence-{index}", 1, 2, 1) for index in range(205)]
    run_id, emitted, returned, thread = _start_paged_wait(registry, words)
    seen_ids: set[str] = set()
    selected_words: list[FakeWord] = []
    request_id: str | None = None

    for page_index in range(3):
        raw, request = emitted.get(timeout=1)
        assert len(raw.encode("utf-8")) <= CURATION_PAGE_MAX_UTF8_BYTES
        assert request["type"] == "curation.page.request"
        payload = request["payload"]
        assert isinstance(payload, dict)
        assert payload["pageIndex"] == page_index
        assert payload["pageCount"] == 3
        assert payload["candidateStart"] == page_index * 100
        assert payload["totalCandidates"] == len(words)
        candidates = payload["candidates"]
        assert isinstance(candidates, list)
        assert 1 <= len(candidates) <= CURATION_PAGE_MAX_CANDIDATES
        assert len(candidates) == (5 if page_index == 2 else 100)
        if request_id is None:
            request_id = payload["requestId"]
        assert payload["requestId"] == request_id
        page_ids = {candidate["candidateId"] for candidate in candidates}
        assert seen_ids.isdisjoint(page_ids)
        seen_ids.update(page_ids)

        chosen = candidates[-1]
        selected_words.append(words[page_index * 100 + len(candidates) - 1])
        resolution = registry.resolve_curation(
            _page_response(
                run_id,
                request_id,
                page_index,
                [{"candidateId": chosen["candidateId"]}],
            )
        )
        assert resolution.paged is True
        assert resolution.page_index == page_index
        assert resolution.final_page is (page_index == 2)
        if page_index < 2:
            assert thread.is_alive()

    thread.join(1)
    assert not thread.is_alive()
    assert len(seen_ids) == len(words)
    assert returned == [selected_words]
    assert all(actual is expected for actual, expected in zip(returned[0], selected_words, strict=True))


def test_oversized_curation_fails_before_returning_selected_work() -> None:
    limit = ANKI_LIMITS_V1["createCall"]["maxSourceItems"]
    registry = JobRegistry()
    words = [FakeWord(str(index), str(index), str(index), 1, 2, 1) for index in range(limit + 1)]
    handle = registry.begin()
    emitted: queue.Queue[dict[str, object]] = queue.Queue()
    returned: list[object] = []
    failures: list[BridgeProtocolError] = []

    def wait() -> None:
        try:
            returned.append(
                registry.await_curation(
                    handle.run_id,
                    words,
                    lambda raw: emitted.put(json.loads(raw)),
                )
            )
        except BridgeProtocolError as error:
            failures.append(error)

    thread = threading.Thread(target=wait, daemon=True)
    thread.start()
    final_resolution = None
    while final_resolution is None or not final_resolution.final_page:
        request = emitted.get(timeout=5)
        payload = request["payload"]
        assert isinstance(payload, dict)
        candidates = payload["candidates"]
        assert isinstance(candidates, list)
        final_resolution = registry.resolve_curation(
            _page_response(
                handle.run_id,
                payload["requestId"],
                payload["pageIndex"],
                [{"candidateId": candidate["candidateId"]} for candidate in candidates],
            )
        )

    thread.join(1)
    assert not thread.is_alive()
    assert returned == []
    assert [failure.code for failure in failures] == ["create_call_too_large"]


def test_empty_selection_on_every_page_returns_empty_not_cancellation() -> None:
    registry = JobRegistry()
    words = [FakeWord(str(index), str(index), str(index), 1, 2, 1) for index in range(101)]
    run_id, emitted, returned, thread = _start_paged_wait(registry, words)

    for page_index in range(2):
        _, request = emitted.get(timeout=1)
        payload = request["payload"]
        assert isinstance(payload, dict)
        registry.resolve_curation(_page_response(run_id, payload["requestId"], page_index, []))

    thread.join(1)
    assert returned == [[]]


def test_null_page_selection_cancels_the_whole_curation() -> None:
    registry = JobRegistry()
    words = [FakeWord(str(index), str(index), str(index), 1, 2, 1) for index in range(101)]
    run_id, emitted, returned, thread = _start_paged_wait(registry, words)
    _, request = emitted.get(timeout=1)
    payload = request["payload"]
    assert isinstance(payload, dict)

    resolution = registry.resolve_curation(_page_response(run_id, payload["requestId"], 0, None))
    thread.join(1)

    assert resolution.final_page is True
    assert returned == [None]
    assert emitted.empty()


def test_curation_pages_are_split_by_exact_utf8_envelope_size() -> None:
    registry = JobRegistry()
    large_sentence = "猫" * 90_000
    words = [FakeWord(str(index), str(index), large_sentence, 1, 2, 1) for index in range(2)]
    run_id, emitted, returned, thread = _start_paged_wait(registry, words)

    for page_index in range(2):
        raw, request = emitted.get(timeout=1)
        payload = request["payload"]
        assert isinstance(payload, dict)
        assert len(raw.encode("utf-8")) <= CURATION_PAGE_MAX_UTF8_BYTES
        assert len(payload["candidates"]) == 1
        registry.resolve_curation(_page_response(run_id, payload["requestId"], page_index, []))

    thread.join(1)
    assert returned == [[]]


def test_one_oversized_candidate_fails_without_truncation_and_allows_retry() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    oversized = FakeWord("猫", "猫", "猫" * 180_000, 1, 2, 1)
    emitted: list[str] = []

    with pytest.raises(BridgeProtocolError) as failure:
        registry.await_curation(handle.run_id, [oversized], emitted.append)
    assert failure.value.code == "curation_candidate_too_large"
    assert emitted == []

    retry_request, returned, thread = _start_wait_for_run(
        registry,
        handle.run_id,
        [FakeWord("犬", "犬", "犬だ", 1, 2, 1)],
    )
    payload = retry_request["payload"]
    assert isinstance(payload, dict)
    registry.resolve_curation(_response(handle.run_id, payload["requestId"], []))
    thread.join(1)
    assert returned == [[]]


def test_paged_response_rejects_wrong_type_page_and_future_candidate() -> None:
    registry = JobRegistry()
    words = [FakeWord(str(index), str(index), str(index), 1, 2, 1) for index in range(101)]
    run_id, emitted, _, thread = _start_paged_wait(registry, words)
    _, request = emitted.get(timeout=1)
    payload = request["payload"]
    assert isinstance(payload, dict)
    request_id = payload["requestId"]

    with pytest.raises(BridgeProtocolError) as wrong_type:
        registry.resolve_curation(_response(run_id, request_id, []))
    assert wrong_type.value.code == "invalid_curation_response"

    with pytest.raises(BridgeProtocolError) as wrong_page:
        registry.resolve_curation(_page_response(run_id, request_id, 1, []))
    assert wrong_page.value.code == "stale_curation_page"

    gate = registry._active.curation  # type: ignore[union-attr]
    assert gate is not None
    future_candidate_id = gate.pages[1].candidate_ids[0]
    with pytest.raises(BridgeProtocolError) as future_candidate:
        registry.resolve_curation(
            _page_response(
                run_id,
                request_id,
                0,
                [{"candidateId": future_candidate_id}],
            )
        )
    assert future_candidate.value.code == "unknown_candidate"

    registry.cancel(run_id)
    thread.join(1)


def test_cancel_between_pages_releases_wait_without_emitting_another_page() -> None:
    registry = JobRegistry()
    words = [FakeWord(str(index), str(index), str(index), 1, 2, 1) for index in range(101)]
    run_id, emitted, returned, thread = _start_paged_wait(registry, words)
    _, request = emitted.get(timeout=1)
    payload = request["payload"]
    assert isinstance(payload, dict)

    registry.resolve_curation(_page_response(run_id, payload["requestId"], 0, []))
    registry.cancel(run_id)
    thread.join(1)

    assert not thread.is_alive()
    assert returned == [None]


def test_curation_schema_matches_generated_ids_and_optional_sentence_selection() -> None:
    schema_path = (
        Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/curation.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    definitions = schema["$defs"]
    registry = JobRegistry()
    word = FakeWord("猫", "猫", "猫だ", 1, 2, 1)
    run_id, request, _, thread = _start_wait(registry, [word])
    payload = request["payload"]
    assert isinstance(payload, dict)
    candidate = payload["candidates"][0]

    assert re.fullmatch(definitions["runId"]["pattern"], run_id)
    assert re.fullmatch(definitions["requestId"]["pattern"], payload["requestId"])
    assert re.fullmatch(definitions["candidateId"]["pattern"], candidate["candidateId"])
    assert definitions["selection"]["required"] == ["candidateId"]
    assert "sentenceId" not in definitions["selection"]["required"]

    registry.cancel(run_id)
    thread.join(1)
