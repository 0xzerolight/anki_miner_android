from __future__ import annotations

import json
from dataclasses import dataclass

import android_bridge.callbacks as callbacks_module
import pytest
from android_bridge.anki_limits import ANKI_ENVELOPE_LIMITS_V1
from android_bridge.callbacks import (
    AndroidAnkiCallbacks,
    AnkiCallbackError,
    CallbackAdapters,
)
from android_bridge.jobs import JobRegistry
from android_bridge.protocol import BridgeProtocolError, encode_message

_RUN_ID = "run_" + "a" * 32
_ANKI_OPERATION_CASES = [
    (
        "verifyTarget",
        "ankiVerifyTarget",
        "anki.verifytarget.request",
        "anki.verifytarget.result",
    ),
    (
        "scanFirstFields",
        "ankiScanFirstFields",
        "anki.scanfirstfields.request",
        "anki.scanfirstfields.result",
    ),
    (
        "storeMedia",
        "ankiStoreMedia",
        "anki.storemedia.request",
        "anki.storemedia.result",
    ),
    (
        "createNotes",
        "ankiCreateNotes",
        "anki.createnotes.request",
        "anki.createnotes.result",
    ),
    (
        "releaseRunState",
        "ankiReleaseRunState",
        "anki.releaserunstate.request",
        "anki.releaserunstate.result",
    ),
]


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


def test_job_registration_is_synchronous_strict_and_correlated() -> None:
    registry = JobRegistry()
    handle = registry.begin()

    class RegistrationCallbacks(RecordingCallbacks):
        def registerJob(self, raw: str) -> str:
            self._record("registerJob", raw)
            run_id = json.loads(raw)["payload"]["runId"]
            return encode_message("job.registration.accepted", {"runId": run_id})

    callbacks = RegistrationCallbacks()
    adapters = CallbackAdapters(callbacks, registry, handle)

    adapters.register_job()

    assert callbacks.calls == [
        (
            "registerJob",
            {
                "schemaVersion": 1,
                "type": "job.registration.request",
                "payload": {"runId": handle.run_id},
            },
        )
    ]


@pytest.mark.parametrize(
    "response",
    [
        encode_message("job.registration.accepted", {"runId": "run_" + "0" * 32}),
        encode_message(
            "job.registration.accepted",
            {"runId": _RUN_ID, "unknown": True},
        ),
        encode_message("job.registration.rejected", {"runId": _RUN_ID}),
    ],
)
def test_job_registration_rejects_uncorrelated_or_malformed_acceptance(
    response: str,
) -> None:
    registry = JobRegistry()
    handle = registry.begin()

    class BadRegistrationCallbacks:
        def registerJob(self, raw: str) -> str:
            return response

    adapters = CallbackAdapters(BadRegistrationCallbacks(), registry, handle)
    with pytest.raises(BridgeProtocolError):
        adapters.register_job()


def test_canonical_terminal_notification_uses_requested_channel_unchanged() -> None:
    registry = JobRegistry()
    handle = registry.begin()
    callbacks = RecordingCallbacks()
    adapters = CallbackAdapters(callbacks, registry, handle)
    terminal = encode_message(
        "mining.terminal",
        {
            "runId": handle.run_id,
            "outcome": "failed",
            "result": None,
            "error": {"code": "engine_error", "message": "failed"},
        },
    )

    adapters.notify_terminal(terminal, failed=True)

    assert callbacks.calls == [("onError", json.loads(terminal))]


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
                    "maxTotalScannedNotes": 100000,
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
                        "maxTotalScannedNotes": 100000,
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
                        "maxTotalScannedNotes": 100000,
                        "maxItems": 256,
                        "maxItemUtf8Bytes": 65536,
                        "maxTotalUtf8Bytes": 262144,
                    },
                }
            }
        )
    assert exc_info.value.code == "mismatched_callback_response"


@pytest.mark.parametrize(
    ("operation", "method_name", "request_type", "result_type"),
    _ANKI_OPERATION_CASES,
)
def test_every_anki_request_envelope_accepts_exact_limit_and_rejects_next_byte(
    operation: str,
    method_name: str,
    request_type: str,
    result_type: str,
) -> None:
    class ExactCallbacks:
        calls = 0

    callbacks = ExactCallbacks()

    def reply(raw: str) -> str:
        callbacks.calls += 1
        request = json.loads(raw)["payload"]
        return encode_message(
            result_type,
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
            },
        )

    setattr(callbacks, method_name, reply)
    client = AndroidAnkiCallbacks(callbacks, _RUN_ID)
    request_limit = ANKI_ENVELOPE_LIMITS_V1[operation][0]
    placeholder = {
        "padding": "",
        "runId": _RUN_ID,
        "requestId": "anki_" + "0" * 32,
    }
    base_size = len(encode_message(request_type, placeholder).encode("utf-8"))
    exact_padding = "x" * (request_limit - base_size)

    result = client._request(
        method_name=method_name,
        operation=operation,
        request_type=request_type,
        result_type=result_type,
        payload={"padding": exact_padding},
    )
    assert result["runId"] == _RUN_ID
    assert callbacks.calls == 1

    with pytest.raises(BridgeProtocolError) as exc_info:
        client._request(
            method_name=method_name,
            operation=operation,
            request_type=request_type,
            result_type=result_type,
            payload={"padding": exact_padding + "x"},
        )
    assert exc_info.value.code == "anki_request_too_large"
    assert callbacks.calls == 1


@pytest.mark.parametrize(
    ("operation", "method_name", "request_type", "result_type"),
    _ANKI_OPERATION_CASES,
)
def test_every_anki_response_envelope_accepts_exact_limit_and_rejects_next_byte(
    operation: str,
    method_name: str,
    request_type: str,
    result_type: str,
) -> None:
    class SizedCallbacks:
        extra_byte = False

    callbacks = SizedCallbacks()

    def reply(raw: str) -> str:
        request = json.loads(raw)["payload"]
        base_payload = {
            "runId": request["runId"],
            "requestId": request["requestId"],
            "padding": "",
        }
        result_limit = ANKI_ENVELOPE_LIMITS_V1[operation][1]
        base_size = len(encode_message(result_type, base_payload).encode("utf-8"))
        padding_size = result_limit - base_size + int(callbacks.extra_byte)
        return encode_message(
            result_type,
            {**base_payload, "padding": "x" * padding_size},
        )

    setattr(callbacks, method_name, reply)
    client = AndroidAnkiCallbacks(callbacks, _RUN_ID)
    request_kwargs = {
        "method_name": method_name,
        "operation": operation,
        "request_type": request_type,
        "result_type": result_type,
        "payload": {},
    }

    result = client._request(**request_kwargs)
    assert result["padding"]

    callbacks.extra_byte = True
    with pytest.raises(BridgeProtocolError) as exc_info:
        client._request(**request_kwargs)
    assert exc_info.value.code == "anki_response_too_large"


def test_anki_response_envelope_rejects_non_scalar_unicode_before_json_decode() -> None:
    class InvalidUnicodeCallbacks:
        def ankiReleaseRunState(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return (
                '{"schemaVersion":1,"type":"anki.releaserunstate.result",'
                f'"payload":{{"runId":"{request["runId"]}",'
                f'"requestId":"{request["requestId"]}",'
                '"state":"\ud800"}}'
            )

    with pytest.raises(BridgeProtocolError) as exc_info:
        AndroidAnkiCallbacks(InvalidUnicodeCallbacks(), _RUN_ID).release_run_state()
    assert exc_info.value.code == "invalid_utf8"


def test_anki_response_rejects_ascii_escaped_surrogate_after_json_decode() -> None:
    class EscapedSurrogateCallbacks:
        def ankiScanFirstFields(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return (
                '{"schemaVersion":1,"type":"anki.scanfirstfields.result",'
                f'"payload":{{"runId":"{request["runId"]}",'
                f'"requestId":"{request["requestId"]}",'
                '"firstFields":["\\ud800"],"scannedNotes":1,'
                '"nextCursor":null}}'
            )

    callbacks = AndroidAnkiCallbacks(EscapedSurrogateCallbacks(), _RUN_ID)

    with pytest.raises(BridgeProtocolError) as exc_info:
        callbacks.scan_first_fields({})
    assert exc_info.value.code == "invalid_utf8"
    assert str(exc_info.value) == ("Bridge JSON string contains an invalid Unicode scalar")


def test_terminal_mutation_receipt_requires_explicit_semantic_acceptance() -> None:
    class MutationCallbacks:
        def ankiVerifyTarget(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.verifytarget.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "deckId": 1,
                    "modelId": 2,
                    "fieldNames": ["Expression"],
                    "deckCreated": False,
                },
            )

    client = AndroidAnkiCallbacks(MutationCallbacks(), _RUN_ID)
    payload = client.verify_target({})

    assert client.can_acknowledge_terminal_responses is False
    client.accept_terminal_response(payload["requestId"])
    assert client.can_acknowledge_terminal_responses is True


def test_terminal_receipt_double_accept_fails_sticky_closed() -> None:
    class MutationCallbacks:
        def ankiCreateNotes(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.createnotes.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [],
                    "error": None,
                },
            )

    client = AndroidAnkiCallbacks(MutationCallbacks(), _RUN_ID)
    payload = client.create_notes({})
    client.accept_terminal_response(payload["requestId"])

    with pytest.raises(BridgeProtocolError) as error:
        client.accept_terminal_response(payload["requestId"])
    assert error.value.code == "invalid_anki_response"
    assert client.can_acknowledge_terminal_responses is False


def test_terminal_receipt_tracking_is_bounded_and_fails_sticky_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class MutationCallbacks:
        def ankiStoreMedia(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [],
                    "error": None,
                },
            )

    monkeypatch.setattr(callbacks_module, "_MAX_PENDING_TERMINAL_RECEIPTS", 1)
    client = AndroidAnkiCallbacks(MutationCallbacks(), _RUN_ID)
    client.store_media({})

    with pytest.raises(BridgeProtocolError) as error:
        client.store_media({})

    assert error.value.code == "anki_receipt_limit_exceeded"
    assert client.can_acknowledge_terminal_responses is False


def test_release_sends_false_after_callback_transport_failure() -> None:
    seen_release: list[dict[str, object]] = []

    class FailureCallbacks:
        def ankiStoreMedia(self, raw: str) -> object:
            del raw
            return None

        def ankiReleaseRunState(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            seen_release.append(request)
            return encode_message(
                "anki.releaserunstate.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "state": "absent",
                },
            )

    client = AndroidAnkiCallbacks(FailureCallbacks(), _RUN_ID)
    with pytest.raises(BridgeProtocolError):
        client.store_media({})

    client.release_run_state()
    assert seen_release[0]["acknowledgeTerminalResponses"] is False


@pytest.mark.parametrize(
    ("code", "message", "retryable"),
    [
        ("target_invalid", "", False),
        ("unknown_code", "unknown error", False),
        ("post_commit_uncertain", "uncertain", True),
        ("cancelled", "cancelled", True),
    ],
    ids=[
        "empty-message",
        "unknown-code",
        "retryable-post-commit",
        "retryable-cancellation",
    ],
)
def test_malformed_anki_error_makes_release_acknowledgement_false(
    code: str,
    message: str,
    retryable: bool,
) -> None:
    seen_release: list[dict[str, object]] = []

    class MalformedErrorCallbacks:
        def ankiVerifyTarget(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.error",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "operation": "verifyTarget",
                    "code": code,
                    "message": message,
                    "retryable": retryable,
                },
            )

        def ankiReleaseRunState(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            seen_release.append(request)
            return encode_message(
                "anki.releaserunstate.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "state": "absent",
                },
            )

    client = AndroidAnkiCallbacks(MalformedErrorCallbacks(), _RUN_ID)
    with pytest.raises(BridgeProtocolError) as error:
        client.verify_target({})
    assert error.value.code == "invalid_anki_error"

    client.release_run_state()
    assert seen_release[0]["acknowledgeTerminalResponses"] is False


def test_release_sends_true_only_after_all_terminal_receipts_are_accepted() -> None:
    seen_release: list[dict[str, object]] = []

    class ReceiptCallbacks:
        def ankiVerifyTarget(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.verifytarget.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                },
            )

        def ankiReleaseRunState(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            seen_release.append(request)
            return encode_message(
                "anki.releaserunstate.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "state": "released",
                },
            )

    client = AndroidAnkiCallbacks(ReceiptCallbacks(), _RUN_ID)
    payload = client.verify_target({})
    client.accept_terminal_response(payload["requestId"])
    client.release_run_state()

    assert seen_release[0]["acknowledgeTerminalResponses"] is True
