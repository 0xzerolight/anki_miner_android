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
    request_type: str, payload: dict[str, object], raw_request: str
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
    }
    if request_type not in supported_after_bootstrap:
        raise BridgeProtocolError(
            "unsupported_operation", f"Unsupported bridge operation: {request_type}"
        )

    from .bootstrap import require_initialized

    require_initialized()

    if request_type == "job.cancel":
        from .jobs import cancel_job

        return cancel_job(raw_request)
    if request_type == "curation.response":
        from .jobs import submit_curation

        return submit_curation(raw_request)

    _exact_payload(payload, set(), error_code="invalid_shutdown_request")
    from .jobs import shutdown

    return shutdown()


def dispatch(raw_request: str) -> str:
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
        return _dispatch_validated(request_type, decoded.payload, raw_request)
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
