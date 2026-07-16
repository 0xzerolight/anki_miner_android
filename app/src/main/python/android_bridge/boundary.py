"""The sole exception-safe entry point exposed to Kotlin."""

from __future__ import annotations

import logging
from collections.abc import Mapping

from .protocol import (
    BridgeProtocolError,
    decode_envelope,
    encode_protocol_error,
)

logger = logging.getLogger(__name__)


def _exact_payload(
    payload: Mapping[str, object],
    keys: set[str],
    *,
    error_code: str,
) -> None:
    if set(payload) != keys:
        raise BridgeProtocolError(
            error_code, f"Expected payload fields: {sorted(keys)!r}"
        )


def _dispatch_validated(
    request_type: str,
    payload: dict[str, object],
    raw_request: str,
    callbacks: object | None = None,
) -> str:
    if request_type == "bootstrap.initialize":
        _exact_payload(payload, {"filesDir"}, error_code="invalid_bootstrap_request")
        files_dir = payload["filesDir"]
        if not isinstance(files_dir, str):
            raise BridgeProtocolError(
                "invalid_bootstrap_request", "filesDir must be a string"
            )
        from .bootstrap import initialize

        return initialize(files_dir)

    supported_after_bootstrap = {
        "job.cancel",
        "curation.response",
        "bridge.shutdown.request",
        "mining.video.run",
        "tokenizer.configure",
    }
    if request_type not in supported_after_bootstrap:
        raise BridgeProtocolError(
            "unsupported_operation", f"Unsupported bridge operation: {request_type}"
        )

    from .bootstrap import require_initialized

    require_initialized()

    if request_type == "tokenizer.configure":
        _exact_payload(
            payload,
            {"dicDir", "resourceId", "treeSha256", "backend"},
            error_code="invalid_tokenizer_request",
        )
        from .tokenizer_runtime import configure_tokenizer

        return configure_tokenizer(payload)

    if request_type == "mining.video.run":
        if callbacks is None:
            raise BridgeProtocolError(
                "missing_callbacks",
                "mining.video.run requires an EngineCallbacks object",
            )
        from .mining import run_video

        return run_video(raw_request, callbacks)

    if request_type == "job.cancel":
        from .jobs import cancel_job

        return cancel_job(raw_request)
    if request_type == "curation.response":
        from .jobs import submit_curation

        return submit_curation(raw_request)

    _exact_payload(payload, set(), error_code="invalid_shutdown_request")
    from .jobs import shutdown

    return shutdown()


def dispatch(raw_request: str, callbacks: object | None = None) -> str:
    """Dispatch one Kotlin request and always return a versioned envelope.

    ``BridgeProtocolError`` becomes a ``bridge.error`` carrying its stable
    machine code. Any other ordinary Python exception is logged locally and
    becomes a generic ``internal_error``; its type and text never cross into
    Kotlin. Process-control exceptions derived directly from ``BaseException``
    are intentionally not swallowed.
    """

    request_type: str | None = None
    try:
        decoded = decode_envelope(raw_request)
        request_type = decoded.message_type
        if callbacks is None:
            # Preserve the historical three-argument internal seam for callers
            # and tests which do not use a callback-bearing operation.
            return _dispatch_validated(request_type, decoded.payload, raw_request)
        return _dispatch_validated(
            request_type, decoded.payload, raw_request, callbacks
        )
    except BridgeProtocolError as error:
        return encode_protocol_error(error, request_type=request_type)
    except Exception:
        logger.exception(
            "Unexpected failure in Android bridge operation %r", request_type
        )
        return encode_protocol_error(
            BridgeProtocolError("internal_error", "Internal bridge failure"),
            request_type=request_type,
        )
