from __future__ import annotations

import json
from dataclasses import dataclass

import pytest

from android_bridge.callbacks import AnkiCallbackError, CallbackAdapters
from android_bridge.jobs import JobRegistry
from android_bridge.protocol import BridgeProtocolError, encode_message


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
    assert callbacks.calls[-1][1]["payload"]["result"] == {
        "cardsCreated": 2,
        "errors": [],
    }
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


class AnkiCallbacks(RecordingCallbacks):
    def _reply(self, method: str, raw: str, result_type: str) -> str:
        self._record(method, raw)
        request = json.loads(raw)["payload"]
        return encode_message(
            result_type,
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "firstFields": ["<b>猫</b>"],
                "scannedNotes": 1,
                "nextCursor": None,
            },
        )

    def ankiScanFirstFields(self, raw: str) -> str:
        return self._reply("ankiScanFirstFields", raw, "anki.scanfirstfields.result")


def test_synchronous_anki_callback_is_correlated_and_versioned() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    callbacks = AnkiCallbacks()
    adapters = CallbackAdapters(callbacks, registry, handle)

    result = adapters.anki.scan_first_fields(
        {
            "scope": {
                "kind": "knownVocabulary",
                "excludedDecks": [],
                "cursor": None,
                "limits": {
                    "maxScannedNotes": 256,
                    "maxItems": 256,
                    "maxItemUtf8Bytes": 65536,
                    "maxTotalUtf8Bytes": 262144,
                },
            }
        }
    )

    method, request = callbacks.calls[-1]
    assert method == "ankiScanFirstFields"
    assert request["type"] == "anki.scanfirstfields.request"
    assert request["payload"]["runId"] == handle.run_id
    assert request["payload"]["requestId"].startswith("anki_")
    assert result["firstFields"] == ["<b>猫</b>"]


def test_synchronous_anki_callback_maps_strict_error_envelope() -> None:
    registry = JobRegistry()
    handle = registry.begin()

    class ErrorCallbacks:
        def ankiVerifyTarget(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.error",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "operation": "verifyTarget",
                    "code": "note_type_not_found",
                    "message": "missing",
                    "retryable": False,
                },
            )

    adapters = CallbackAdapters(ErrorCallbacks(), registry, handle)

    with pytest.raises(AnkiCallbackError) as exc_info:
        adapters.anki.verify_target(
            {
                "deckName": "Mining",
                "modelName": "Missing",
                "requiredFields": ["Expression"],
            }
        )

    assert exc_info.value.code == "note_type_not_found"
    assert exc_info.value.retryable is False


@pytest.mark.parametrize("result", [None, 3, {}])
def test_synchronous_anki_callback_requires_json_string(result: object) -> None:
    registry = JobRegistry()
    handle = registry.begin()

    class BadCallbacks:
        def ankiScanFirstFields(self, raw: str) -> object:
            return result

    adapters = CallbackAdapters(BadCallbacks(), registry, handle)
    with pytest.raises(BridgeProtocolError) as exc_info:
        adapters.anki.scan_first_fields(
            {
                "scope": {
                    "kind": "knownVocabulary",
                    "excludedDecks": [],
                    "cursor": None,
                    "limits": {
                        "maxScannedNotes": 256,
                        "maxItems": 256,
                        "maxItemUtf8Bytes": 65536,
                        "maxTotalUtf8Bytes": 262144,
                    },
                }
            }
        )
    assert exc_info.value.code == "invalid_callback_result"


def test_synchronous_anki_callback_rejects_mismatched_request_id() -> None:
    registry = JobRegistry()
    handle = registry.begin()

    class BadCallbacks:
        def ankiScanFirstFields(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": "anki_" + "0" * 32,
                    "firstFields": [],
                    "scannedNotes": 0,
                    "nextCursor": None,
                },
            )

    adapters = CallbackAdapters(BadCallbacks(), registry, handle)
    with pytest.raises(BridgeProtocolError) as exc_info:
        adapters.anki.scan_first_fields(
            {
                "scope": {
                    "kind": "knownVocabulary",
                    "excludedDecks": [],
                    "cursor": None,
                    "limits": {
                        "maxScannedNotes": 256,
                        "maxItems": 256,
                        "maxItemUtf8Bytes": 65536,
                        "maxTotalUtf8Bytes": 262144,
                    },
                }
            }
        )
    assert exc_info.value.code == "mismatched_callback_response"
