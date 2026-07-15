"""Adapters from desktop presenter/progress protocols to Kotlin callbacks."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from .jobs import JobHandle, JobRegistry
from .protocol import (
    BridgeProtocolError,
    DecodedMessage,
    decode_envelope,
    encode_message,
    to_json_value,
)


def _invoke(callbacks: object, method_name: str, message: str) -> None:
    method = getattr(callbacks, method_name, None)
    if not callable(method):
        raise BridgeProtocolError(
            "missing_callback", f"EngineCallbacks.{method_name} is required"
        )
    method(message)


def _invoke_result(callbacks: object, method_name: str, message: str) -> str:
    """Invoke a synchronous Kotlin callback and require a JSON string result."""

    method = getattr(callbacks, method_name, None)
    if not callable(method):
        raise BridgeProtocolError(
            "missing_callback", f"EngineCallbacks.{method_name} is required"
        )
    try:
        result = method(message)
    except Exception as exc:
        raise BridgeProtocolError(
            "callback_failed", f"EngineCallbacks.{method_name} raised an exception"
        ) from exc
    if not isinstance(result, str):
        raise BridgeProtocolError(
            "invalid_callback_result",
            f"EngineCallbacks.{method_name} must return a JSON string",
        )
    return result


@dataclass(frozen=True)
class AnkiCallbackError(Exception):
    """A well-formed ``anki.error`` returned by the Kotlin provider seam."""

    operation: str
    code: str
    message: str
    retryable: bool

    def __str__(self) -> str:
        return self.message


def _parse_anki_error(
    message: DecodedMessage, *, run_id: str, request_id: str, operation: str
) -> AnkiCallbackError:
    payload = message.payload
    required = {"runId", "requestId", "operation", "code", "message", "retryable"}
    if set(payload) != required:
        raise BridgeProtocolError(
            "invalid_anki_error", "anki.error has missing or unknown fields"
        )
    if payload["runId"] != run_id or payload["requestId"] != request_id:
        raise BridgeProtocolError(
            "mismatched_callback_response",
            "Anki callback response does not match its request",
        )
    if payload["operation"] != operation:
        raise BridgeProtocolError(
            "mismatched_callback_response",
            "Anki callback error names the wrong operation",
        )
    if not isinstance(payload["code"], str) or not payload["code"]:
        raise BridgeProtocolError("invalid_anki_error", "anki.error code is invalid")
    if not isinstance(payload["message"], str):
        raise BridgeProtocolError("invalid_anki_error", "anki.error message is invalid")
    if not isinstance(payload["retryable"], bool):
        raise BridgeProtocolError(
            "invalid_anki_error", "anki.error retryable flag is invalid"
        )
    return AnkiCallbackError(
        operation=operation,
        code=payload["code"],
        message=payload["message"],
        retryable=payload["retryable"],
    )


@dataclass(frozen=True)
class AndroidAnkiCallbacks:
    """Synchronous JSON client for the four Kotlin AnkiDroid operations."""

    callbacks: object
    run_id: str

    def _request(
        self,
        *,
        method_name: str,
        operation: str,
        request_type: str,
        result_type: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        request_id = f"anki_{uuid4().hex}"
        request_payload = {
            **payload,
            "runId": self.run_id,
            "requestId": request_id,
        }
        raw_result = _invoke_result(
            self.callbacks,
            method_name,
            encode_message(request_type, request_payload),
        )
        message = decode_envelope(raw_result)
        if message.message_type == "anki.error":
            raise _parse_anki_error(
                message,
                run_id=self.run_id,
                request_id=request_id,
                operation=operation,
            )
        if message.message_type != result_type:
            raise BridgeProtocolError(
                "unexpected_message_type",
                f"Expected {result_type!r}, received {message.message_type!r}",
            )
        if (
            message.payload.get("runId") != self.run_id
            or message.payload.get("requestId") != request_id
        ):
            raise BridgeProtocolError(
                "mismatched_callback_response",
                "Anki callback response does not match its request",
            )
        return message.payload

    def verify_target(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            method_name="ankiVerifyTarget",
            operation="verifyTarget",
            request_type="anki.verifytarget.request",
            result_type="anki.verifytarget.result",
            payload=payload,
        )

    def scan_first_fields(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            method_name="ankiScanFirstFields",
            operation="scanFirstFields",
            request_type="anki.scanfirstfields.request",
            result_type="anki.scanfirstfields.result",
            payload=payload,
        )

    def store_media(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            method_name="ankiStoreMedia",
            operation="storeMedia",
            request_type="anki.storemedia.request",
            result_type="anki.storemedia.result",
            payload=payload,
        )

    def create_notes(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            method_name="ankiCreateNotes",
            operation="createNotes",
            request_type="anki.createnotes.request",
            result_type="anki.createnotes.result",
            payload=payload,
        )


@dataclass(frozen=True)
class AndroidProgressCallback:
    """Structurally implements the desktop ``ProgressCallback`` protocol."""

    callbacks: object
    run_id: str

    def on_start(self, total: int, description: str) -> None:
        _invoke(
            self.callbacks,
            "onStart",
            encode_message(
                "progress.start",
                {"runId": self.run_id, "total": total, "description": description},
            ),
        )

    def on_progress(self, current: int, item_description: str) -> None:
        _invoke(
            self.callbacks,
            "onProgress",
            encode_message(
                "progress.update",
                {
                    "runId": self.run_id,
                    "current": current,
                    "description": item_description,
                },
            ),
        )

    def on_complete(self) -> None:
        _invoke(
            self.callbacks,
            "onComplete",
            encode_message("progress.complete", {"runId": self.run_id}),
        )

    def on_error(self, item_description: str, error_message: str) -> None:
        _invoke(
            self.callbacks,
            "onError",
            encode_message(
                "progress.error",
                {
                    "runId": self.run_id,
                    "description": item_description,
                    "message": error_message,
                },
            ),
        )


@dataclass(frozen=True)
class AndroidPresenter:
    """Structurally implements the desktop ``PresenterProtocol``."""

    callbacks: object
    run_id: str

    def _event(self, kind: str, **payload: Any) -> None:
        _invoke(
            self.callbacks,
            "onPresenterEvent",
            encode_message(
                "presenter.event", {"runId": self.run_id, "kind": kind, **payload}
            ),
        )

    def show_info(self, message: str) -> None:
        self._event("info", message=message)

    def show_success(self, message: str) -> None:
        self._event("success", message=message)

    def show_warning(self, message: str) -> None:
        self._event("warning", message=message)

    def show_error(self, message: str) -> None:
        self._event("error", message=message)

    def show_validation_result(self, result: object) -> None:
        self._event("validation", result=to_json_value(result))

    def show_processing_result(self, result: object) -> None:
        self._event("processingResult", result=to_json_value(result))


class CallbackAdapters:
    """One coherent adapter set bound to a single live job."""

    def __init__(
        self, callbacks: object, registry: JobRegistry, handle: JobHandle
    ) -> None:
        self._callbacks = callbacks
        self._registry = registry
        self._handle = handle
        self.progress = AndroidProgressCallback(callbacks, handle.run_id)
        self.presenter = AndroidPresenter(callbacks, handle.run_id)
        self.anki = AndroidAnkiCallbacks(callbacks, handle.run_id)

    @property
    def run_id(self) -> str:
        return self._handle.run_id

    def curate(self, candidates: list[object]) -> list[object] | None:
        """Blocking curation callback passed unchanged into ``process_*``."""

        return self._registry.await_curation(
            self._handle.run_id,
            candidates,
            lambda message: _invoke(self._callbacks, "onCurationNeeded", message),
        )

    def notify_job_complete(self, result: object) -> None:
        _invoke(
            self._callbacks,
            "onComplete",
            encode_message(
                "mining.complete",
                {"runId": self._handle.run_id, "result": to_json_value(result)},
            ),
        )

    def notify_job_error(self, error: BaseException) -> None:
        _invoke(
            self._callbacks,
            "onError",
            encode_message(
                "mining.error",
                {
                    "runId": self._handle.run_id,
                    "errorType": type(error).__name__,
                    "message": str(error),
                },
            ),
        )
