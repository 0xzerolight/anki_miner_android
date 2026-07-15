from __future__ import annotations

import json
import threading
from dataclasses import dataclass, field

import pytest

from android_bridge.jobs import JobRegistry
from android_bridge.protocol import BridgeProtocolError, encode_message


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
    emitted = threading.Event()
    request: dict[str, object] = {}
    returned: list[object] = []

    def emit(raw: str) -> None:
        request.update(json.loads(raw))
        emitted.set()

    def wait() -> None:
        returned.append(registry.await_curation(handle.run_id, candidates, emit))

    thread = threading.Thread(target=wait, daemon=True)
    thread.start()
    assert emitted.wait(1), "curation request was not emitted"
    return handle.run_id, request, returned, thread


def _response(run_id: str, request_id: str, selection: object) -> str:
    return encode_message(
        "curation.response",
        {"runId": run_id, "requestId": request_id, "selection": selection},
    )


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
                {"candidateId": first_payload["candidateId"], "sentenceId": alternative_id},
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

    stale = _response(run_id, "curation_stale", [])
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
        ([{"candidateId": "candidate_unknown"}], "unknown_candidate"),
        (
            [{"candidateId": "USE_REAL", "sentenceId": "sentence_unknown"}],
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
        {key: candidate_id if value == "USE_REAL" else value for key, value in item.items()}
        for item in selection
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
