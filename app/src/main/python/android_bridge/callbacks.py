"""Adapters from desktop presenter/progress protocols to Kotlin callbacks."""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass, field
from typing import Any
from uuid import uuid4

from .anki_limits import ANKI_ENVELOPE_LIMITS_V1
from .jobs import JobHandle, JobRegistry
from .protocol import (
    BridgeProtocolError,
    DecodedMessage,
    decode_envelope,
    encode_message,
    to_json_value,
)

logger = logging.getLogger(__name__)


def _invoke(callbacks: object, method_name: str, message: str) -> None:
    method = getattr(callbacks, method_name, None)
    if not callable(method):
        raise BridgeProtocolError("missing_callback", f"EngineCallbacks.{method_name} is required")
    method(message)


def _invoke_result(callbacks: object, method_name: str, message: str) -> str:
    """Invoke a synchronous Kotlin callback and require a JSON string result."""

    method = getattr(callbacks, method_name, None)
    if not callable(method):
        raise BridgeProtocolError("missing_callback", f"EngineCallbacks.{method_name} is required")
    try:
        result = method(message)
    except Exception as exc:
        raise BridgeProtocolError("callback_failed", f"EngineCallbacks.{method_name} raised an exception") from exc
    if not isinstance(result, str):
        raise BridgeProtocolError(
            "invalid_callback_result",
            f"EngineCallbacks.{method_name} must return a JSON string",
        )
    return result


def _utf8_size(raw: str, *, context: str) -> int:
    try:
        return len(raw.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise BridgeProtocolError("invalid_utf8", f"{context} contains an invalid Unicode scalar") from exc


@dataclass(frozen=True)
class AnkiCallbackError(Exception):
    """A well-formed ``anki.error`` returned by the Kotlin provider seam."""

    operation: str
    code: str
    message: str
    retryable: bool
    request_id: str | None = None

    def __str__(self) -> str:
        return self.message


_ANKI_ERROR_CODES = frozenset(
    {
        "api_disabled",
        "permission_required",
        "note_type_not_found",
        "field_missing",
        "field_mapping_invalid",
        "target_invalid",
        "provider_unavailable",
        "query_failed",
        "write_failed",
        "timeout",
        "cancelled",
        "media_store_failed",
        "post_commit_uncertain",
        "invalid_request",
        "unsupported_operation",
        "internal_error",
    }
)


def _parse_anki_error(message: DecodedMessage, *, run_id: str, request_id: str, operation: str) -> AnkiCallbackError:
    payload = message.payload
    required = {"runId", "requestId", "operation", "code", "message", "retryable"}
    if set(payload) != required:
        raise BridgeProtocolError("invalid_anki_error", "anki.error has missing or unknown fields")
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
    if not isinstance(payload["code"], str) or payload["code"] not in _ANKI_ERROR_CODES:
        raise BridgeProtocolError("invalid_anki_error", "anki.error code is invalid")
    if not isinstance(payload["message"], str) or not payload["message"]:
        raise BridgeProtocolError("invalid_anki_error", "anki.error message is invalid")
    if not isinstance(payload["retryable"], bool):
        raise BridgeProtocolError("invalid_anki_error", "anki.error retryable flag is invalid")
    if payload["code"] == "post_commit_uncertain" and payload["retryable"]:
        raise BridgeProtocolError(
            "invalid_anki_error",
            "post-commit uncertainty cannot be retryable",
        )
    if payload["code"] == "cancelled" and payload["retryable"]:
        raise BridgeProtocolError(
            "invalid_anki_error",
            "cancellation cannot be retryable",
        )
    return AnkiCallbackError(
        operation=operation,
        code=payload["code"],
        message=payload["message"],
        retryable=payload["retryable"],
        request_id=request_id,
    )


_TERMINAL_MUTATION_OPERATIONS = frozenset({"verifyTarget", "storeMedia", "createNotes"})
_MAX_PENDING_TERMINAL_RECEIPTS = 8192


@dataclass
class AndroidAnkiCallbacks:
    """Synchronous JSON client for the Kotlin AnkiDroid operations."""

    callbacks: object
    run_id: str
    _pending_terminal_receipts: set[str] = field(default_factory=set, init=False, repr=False)
    _receipt_failure: bool = field(default=False, init=False, repr=False)

    def _record_terminal_receipt(self, request_id: str) -> None:
        if len(self._pending_terminal_receipts) >= _MAX_PENDING_TERMINAL_RECEIPTS:
            self._receipt_failure = True
            raise BridgeProtocolError(
                "anki_receipt_limit_exceeded",
                "Anki terminal-response receipt limit was exceeded",
            )
        self._pending_terminal_receipts.add(request_id)

    def accept_terminal_response(self, request_id: str) -> None:
        """Mark one operation response semantically accepted by the adapter."""

        if request_id not in self._pending_terminal_receipts:
            self._receipt_failure = True
            raise BridgeProtocolError(
                "invalid_anki_response",
                "Anki terminal-response receipt is missing or already accepted",
            )
        self._pending_terminal_receipts.remove(request_id)

    def reject_terminal_response(self, request_id: str) -> None:
        """Make semantic rejection sticky and release its bounded receipt slot."""

        self._receipt_failure = True
        self._pending_terminal_receipts.discard(request_id)

    def mark_response_failure(self) -> None:
        """Make any callback decoding or semantic failure sticky for release."""

        self._receipt_failure = True

    @property
    def can_acknowledge_terminal_responses(self) -> bool:
        return not self._receipt_failure and not self._pending_terminal_receipts

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
        request_limit, result_limit = ANKI_ENVELOPE_LIMITS_V1[operation]
        try:
            raw_request = encode_message(request_type, request_payload)
            if _utf8_size(raw_request, context=f"{operation} request") > request_limit:
                raise BridgeProtocolError(
                    "anki_request_too_large",
                    f"{operation} request exceeds its v1 UTF-8 envelope limit",
                )
            raw_result = _invoke_result(
                self.callbacks,
                method_name,
                raw_request,
            )
            if _utf8_size(raw_result, context=f"{operation} response") > result_limit:
                raise BridgeProtocolError(
                    "anki_response_too_large",
                    f"{operation} response exceeds its v1 UTF-8 envelope limit",
                )
            message = decode_envelope(raw_result)
        except Exception:
            self._receipt_failure = True
            raise
        if message.message_type == "anki.error":
            try:
                error = _parse_anki_error(
                    message,
                    run_id=self.run_id,
                    request_id=request_id,
                    operation=operation,
                )
            except Exception:
                self._receipt_failure = True
                raise
            if operation in _TERMINAL_MUTATION_OPERATIONS:
                self._record_terminal_receipt(request_id)
            raise error
        if message.message_type != result_type:
            self._receipt_failure = True
            raise BridgeProtocolError(
                "unexpected_message_type",
                f"Expected {result_type!r}, received {message.message_type!r}",
            )
        if message.payload.get("runId") != self.run_id or message.payload.get("requestId") != request_id:
            self._receipt_failure = True
            raise BridgeProtocolError(
                "mismatched_callback_response",
                "Anki callback response does not match its request",
            )
        if operation in _TERMINAL_MUTATION_OPERATIONS:
            self._record_terminal_receipt(request_id)
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

    def release_run_state(self) -> dict[str, Any]:
        """Release every Kotlin-side capability retained for this run."""

        return self._request(
            method_name="ankiReleaseRunState",
            operation="releaseRunState",
            request_type="anki.releaserunstate.request",
            result_type="anki.releaserunstate.result",
            payload={"acknowledgeTerminalResponses": (self.can_acknowledge_terminal_responses)},
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

    def on_stage(self, index: int, total: int, name: str) -> None:
        """Report which numbered pipeline stage the run reached.

        The engine calls this and ``PresenterProtocol.show_stage`` with the same
        arguments; only this run-scoped channel forwards them, so the UI gets one
        stage event per stage rather than two. It replaced the engine's blended
        stage-weight percentage, so the stage pair is now the only whole-run
        position available: Kotlin composes it as the outer denominator around
        the per-stage on_start/on_progress counts.
        """

        logger.info("engine_stage outcome=ok index=%d total=%d name=%s", index, total, name)
        _invoke(
            self.callbacks,
            "onStage",
            encode_message(
                "progress.stage",
                {"runId": self.run_id, "index": index, "total": total, "name": name},
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
            encode_message("presenter.event", {"runId": self.run_id, "kind": kind, **payload}),
        )

    def show_info(self, message: str) -> None:
        self._event("info", message=message)

    def show_success(self, message: str) -> None:
        self._event("success", message=message)

    def show_warning(self, message: str) -> None:
        self._event("warning", message=message)

    def show_error(self, message: str) -> None:
        self._event("error", message=message)

    def show_stage(self, index: int, total: int, name: str) -> None:
        """Deliberately silent: the run-scoped progress callback owns stages.

        ``EpisodeProcessor._announce_stage`` calls the presenter and the progress
        callback with identical arguments, because on desktop four Reading
        sub-tabs share one presenter while each run owns its callback. Android
        has one run per presenter, so forwarding here would duplicate every
        stage event. Kept because the protocol is duck-typed and the engine calls
        it unconditionally.
        """

    def show_run_details(self, result: object) -> None:
        """Deliberately silent: no Android surface opens a run-details view.

        Desktop raises this from **View details** on a run receipt. The engine
        never calls it during a run (only the GUI does, via getattr), but the
        protocol names it, so it exists to keep this class structurally complete.
        """

    def show_validation_result(self, result: object) -> None:
        self._event("validation", result=to_json_value(result))

    def show_processing_result(self, result: object) -> None:
        self._event("processingResult", result=to_json_value(result))


class CallbackAdapters:
    """One coherent adapter set bound to a single live job."""

    def __init__(self, callbacks: object, registry: JobRegistry, handle: JobHandle) -> None:
        self._callbacks = callbacks
        self._registry = registry
        self._handle = handle
        self.progress = AndroidProgressCallback(callbacks, handle.run_id)
        self.presenter = AndroidPresenter(callbacks, handle.run_id)
        self.anki = AndroidAnkiCallbacks(callbacks, handle.run_id)

    @property
    def run_id(self) -> str:
        return self._handle.run_id

    @property
    def callbacks(self) -> object:
        """Return the reflected Kotlin callback owner for optional adapters."""

        return self._callbacks

    @property
    def cancel_event(self) -> threading.Event:
        """Return the live per-run ``threading.Event`` cancellation token."""

        return self._handle.cancel_event

    def register_job(self) -> None:
        """Synchronously transfer the generated run ID to Kotlin ownership."""

        response = decode_envelope(
            _invoke_result(
                self._callbacks,
                "registerJob",
                encode_message("job.registration.request", {"runId": self._handle.run_id}),
            ),
            expected_type="job.registration.accepted",
        )
        if set(response.payload) != {"runId"}:
            raise BridgeProtocolError(
                "invalid_job_registration",
                "job.registration.accepted must contain exactly runId",
            )
        if response.payload["runId"] != self._handle.run_id:
            raise BridgeProtocolError(
                "mismatched_callback_response",
                "Job registration response belongs to a different run",
            )

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

    def notify_terminal(self, raw_terminal: str, *, failed: bool) -> None:
        """Deliver the same canonical terminal envelope returned by dispatch."""

        decode_envelope(raw_terminal, expected_type="mining.terminal")
        _invoke(self._callbacks, "onError" if failed else "onComplete", raw_terminal)
