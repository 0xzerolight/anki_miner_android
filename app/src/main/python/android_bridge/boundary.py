"""The sole exception-safe entry point exposed to Kotlin."""

from __future__ import annotations

import logging
from collections.abc import Mapping

from .faults import record_fault
from .protocol import (
    BridgeProtocolError,
    decode_envelope,
    encode_message,
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
        raise BridgeProtocolError(error_code, f"Expected payload fields: {sorted(keys)!r}")


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
            raise BridgeProtocolError("invalid_bootstrap_request", "filesDir must be a string")
        from .bootstrap import initialize

        return initialize(files_dir)

    supported_after_bootstrap = {
        "job.cancel",
        "curation.page.response",
        "curation.response",
        "bridge.shutdown.request",
        "diagnostics.loglevel.set",
        "mining.reading.run",
        "mining.video.run",
        "resource.catalog.get",
        "resource.cleanup",
        "resource.audiopack.import",
        "resource.audiopack.preflight",
        "resource.dictionary.delete",
        "resource.dictionary.import",
        "resource.dictionary.list",
        "resource.dictionary.lookup",
        "resource.dictionary.preflight",
        "resource.frequency.import",
        "resource.knownwords.import",
        "resource.knownwords.preview",
        "resource.knownwords.list",
        "resource.knownwords.remove",
        "resource.knownwords.reset",
        "resource.knownwords.export",
        "resource.local.delete",
        "resource.local.list",
        "resource.operation.cancel",
        "resource.pitch.import",
        "resource.unidic.install",
        "tokenizer.configure",
        "dictionary.define",
        "subtitle.cues",
    }
    if request_type not in supported_after_bootstrap:
        raise BridgeProtocolError("unsupported_operation", f"Unsupported bridge operation: {request_type}")

    from .bootstrap import require_initialized

    require_initialized()

    if request_type.startswith("resource."):
        from . import resources

        if request_type in {
            "resource.audiopack.import",
            "resource.audiopack.preflight",
            "resource.frequency.import",
            "resource.knownwords.import",
            "resource.knownwords.preview",
            "resource.knownwords.list",
            "resource.knownwords.remove",
            "resource.knownwords.reset",
            "resource.knownwords.export",
            "resource.local.delete",
            "resource.local.list",
            "resource.pitch.import",
        }:
            from . import local_resources

            local_handlers = {
                "resource.audiopack.import": local_resources.import_audio_pack,
                "resource.audiopack.preflight": local_resources.preflight_audio_pack,
                "resource.frequency.import": local_resources.import_frequency,
                "resource.knownwords.import": local_resources.import_known_words,
                "resource.knownwords.preview": local_resources.preview_known_words,
                "resource.knownwords.list": local_resources.list_known_words,
                "resource.knownwords.remove": local_resources.remove_known_words,
                "resource.knownwords.reset": local_resources.reset_known_words,
                "resource.knownwords.export": local_resources.export_known_words,
                "resource.local.delete": local_resources.delete_local_resource,
                "resource.local.list": local_resources.list_local_resources,
                "resource.pitch.import": local_resources.import_pitch,
            }
            return local_handlers[request_type](payload)

        handlers = {
            "resource.catalog.get": resources.catalog_response,
            "resource.cleanup": resources.cleanup_resources,
            "resource.dictionary.delete": resources.delete_dictionary,
            "resource.dictionary.import": resources.import_dictionary,
            "resource.dictionary.list": resources.list_dictionaries,
            "resource.dictionary.lookup": resources.lookup_dictionary,
            "resource.dictionary.preflight": resources.preflight_dictionary,
            "resource.operation.cancel": resources.cancel_operation,
            "resource.unidic.install": resources.install_unidic,
        }
        return handlers[request_type](payload)

    if request_type == "tokenizer.configure":
        _exact_payload(
            payload,
            {"dicDir", "resourceId", "treeSha256", "backend"},
            error_code="invalid_tokenizer_request",
        )
        from .tokenizer_runtime import configure_tokenizer

        return configure_tokenizer(payload)

    if request_type == "dictionary.define":
        from .definitions import define_word

        return define_word(payload)

    if request_type == "subtitle.cues":
        from .subtitles import get_cues

        return get_cues(payload)

    if request_type == "mining.video.run":
        if callbacks is None:
            raise BridgeProtocolError(
                "missing_callbacks",
                "mining.video.run requires an EngineCallbacks object",
            )
        from .mining import run_video

        return run_video(raw_request, callbacks)

    if request_type == "mining.reading.run":
        if callbacks is None:
            raise BridgeProtocolError(
                "missing_callbacks",
                "mining.reading.run requires an EngineCallbacks object",
            )
        from .reading_mining import run_reading

        return run_reading(raw_request, callbacks)

    # Needs its own branch, not just membership in the set above: the tail of
    # this function is an unguarded fall-through to shutdown(), so a type that
    # is declared supported and never routed would tear the job registry down
    # instead of failing.
    if request_type == "diagnostics.loglevel.set":
        _exact_payload(payload, {"level"}, error_code="invalid_log_level_request")
        from . import log_context

        requested = payload["level"]
        if not isinstance(requested, str) or requested not in log_context.LOG_LEVELS:
            raise BridgeProtocolError(
                "invalid_log_level_request",
                f"Expected level in {sorted(log_context.LOG_LEVELS)!r}",
            )
        log_context.set_first_party_log_level(log_context.LOG_LEVELS[requested])
        return encode_message("diagnostics.loglevel.applied", {"level": requested})

    if request_type == "job.cancel":
        from .jobs import cancel_job

        return cancel_job(raw_request)
    if request_type in {"curation.response", "curation.page.response"}:
        from .jobs import submit_curation

        return submit_curation(raw_request)

    _exact_payload(payload, set(), error_code="invalid_shutdown_request")
    from .jobs import shutdown

    return shutdown()


def dispatch(raw_request: str, callbacks: object | None = None) -> str:
    """Dispatch one Kotlin request and always return a versioned envelope.

    ``BridgeProtocolError`` becomes a ``bridge.error`` carrying its stable
    machine code. Any other ordinary Python exception is logged locally under an
    opaque fault id and becomes a generic ``internal_error``; the id is the only
    part of it that crosses, so its type and text still never reach Kotlin.
    Process-control exceptions derived directly from ``BaseException`` are
    logged, then re-raised rather than swallowed.
    """

    request_type: str | None = None
    try:
        decoded = decode_envelope(raw_request)
        request_type = decoded.message_type
        if callbacks is None:
            # Preserve the historical three-argument internal seam for callers
            # and tests which do not use a callback-bearing operation.
            return _dispatch_validated(request_type, decoded.payload, raw_request)
        return _dispatch_validated(request_type, decoded.payload, raw_request, callbacks)
    except BridgeProtocolError as error:
        logger.error("Bridge protocol error code=%s", error.code, exc_info=error)
        return encode_protocol_error(error, request_type=request_type)
    except Exception as error:
        fault_id = record_fault(
            logger,
            "Unexpected failure in Android bridge operation",
            error,
            request=request_type,
        )
        return encode_protocol_error(
            BridgeProtocolError("internal_error", "Internal bridge failure"),
            request_type=request_type,
            fault_id=fault_id,
        )
    except BaseException:
        logger.exception("Process-control exception escaping Android bridge operation %r", request_type)
        raise
