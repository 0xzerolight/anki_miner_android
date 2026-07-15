from __future__ import annotations

import json
from dataclasses import dataclass

from android_bridge.callbacks import CallbackAdapters
from android_bridge.jobs import JobRegistry


class RecordingCallbacks:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []

    def _record(self, method: str, raw: str) -> None:
        self.calls.append((method, json.loads(raw)))

    def onStart(self, raw: str) -> None:
        self._record("onStart", raw)

    def onProgress(self, raw: str) -> None:
        self._record("onProgress", raw)

    def onComplete(self, raw: str) -> None:
        self._record("onComplete", raw)

    def onError(self, raw: str) -> None:
        self._record("onError", raw)

    def onPresenterEvent(self, raw: str) -> None:
        self._record("onPresenterEvent", raw)

    def onCurationNeeded(self, raw: str) -> None:
        self._record("onCurationNeeded", raw)


@dataclass
class FakeResult:
    cards_created: int
    errors: list[str]


def test_progress_and_presenter_methods_emit_versioned_events() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    callbacks = RecordingCallbacks()
    adapters = CallbackAdapters(callbacks, registry, handle)

    adapters.progress.on_start(10, "Parsing")
    adapters.progress.on_progress(3, "字幕")
    adapters.progress.on_error("line 3", "bad input")
    adapters.progress.on_complete()
    adapters.presenter.show_info("Ready")
    adapters.presenter.show_processing_result(FakeResult(cards_created=2, errors=[]))

    assert [method for method, _ in callbacks.calls] == [
        "onStart",
        "onProgress",
        "onError",
        "onComplete",
        "onPresenterEvent",
        "onPresenterEvent",
    ]
    assert [message["type"] for _, message in callbacks.calls] == [
        "progress.start",
        "progress.update",
        "progress.error",
        "progress.complete",
        "presenter.event",
        "presenter.event",
    ]
    assert callbacks.calls[-1][1]["payload"]["result"] == {"cardsCreated": 2, "errors": []}
    assert all(call[1]["payload"]["runId"] == handle.run_id for call in callbacks.calls)


def test_terminal_notifications_are_distinct_from_stage_progress() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    callbacks = RecordingCallbacks()
    adapters = CallbackAdapters(callbacks, registry, handle)

    adapters.notify_job_complete(FakeResult(cards_created=1, errors=[]))
    adapters.notify_job_error(RuntimeError("boom"))

    assert callbacks.calls[0][1]["type"] == "mining.complete"
    assert callbacks.calls[1][1]["type"] == "mining.error"
    assert callbacks.calls[1][1]["payload"]["errorType"] == "RuntimeError"
