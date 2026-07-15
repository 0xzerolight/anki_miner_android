"""Adapters from desktop presenter/progress protocols to Kotlin callbacks."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .jobs import JobHandle, JobRegistry
from .protocol import BridgeProtocolError, encode_message, to_json_value


def _invoke(callbacks: object, method_name: str, message: str) -> None:
    method = getattr(callbacks, method_name, None)
    if not callable(method):
        raise BridgeProtocolError(
            "missing_callback", f"EngineCallbacks.{method_name} is required"
        )
    method(message)


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
