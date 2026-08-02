"""On-demand offline dictionary lookup for the curation screen.

The mining run owns a ``DefinitionService`` inside its ``EpisodeProcessor``,
but it belongs to the parked run thread and its lifecycle is not concurrent-
safe: nothing locks ``ensure_loaded``/``close``.  A preview therefore builds
its OWN short-lived chain, uses it, and closes it — the same shape
``resources.lookup_dictionary`` already uses for the Settings lookup.

Only the run's own enabled chain is used, so the preview shows the
dictionaries that will actually build the card.  The snapshot is registered by
the run and dropped when the run ends; a request naming an unknown run is
rejected rather than silently falling back to on-disk order.

Offline providers only: ``lookup_all_offline`` excludes online providers by
construction, so a preview never performs network I/O.
"""

from __future__ import annotations

import logging
import re
import threading
from collections.abc import Mapping

from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)

#: Matches the opaque run IDs ``jobs.py`` mints.
_RUN_ID_RE = re.compile(r"^run_[0-9a-f]{32}$")

#: Longest term the UI may ask about. A mined form is a few characters.
MAX_TERM_BYTES = 256

#: Total rendered HTML one preview may return across all providers. The
#: WebView is the display bound; this is the transport bound.
MAX_DEFINITION_HTML_BYTES = 512 * 1024

_lock = threading.Lock()
_run_configs: dict[str, object] = {}


def register_run_dictionaries(run_id: str, config: object) -> None:
    """Publish a run's config so its curation can look words up."""
    with _lock:
        _run_configs[run_id] = config


def clear_run_dictionaries(run_id: str) -> None:
    """Drop a run's snapshot. Safe for a run that never registered."""
    with _lock:
        _run_configs.pop(run_id, None)


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def _text(value: object, *, name: str, max_bytes: int) -> str:
    if not isinstance(value, str):
        raise _fail("invalid_definition_request", f"{name} must be a string")
    if len(value.encode("utf-8")) > max_bytes:
        raise _fail("invalid_definition_request", f"{name} exceeds {max_bytes} bytes")
    return value


def _build_service(config: object) -> object:
    """Build a fresh offline-only provider chain for one request.

    Function-local imports preserve the ANKI_MINER_HOME bootstrap ordering.
    """
    from anki_miner.services.definition_service import DefinitionService
    from anki_miner.services.dictionary.registry import DictionaryRegistry

    registry = DictionaryRegistry(config.dicts_root)
    registry.load()
    providers = [
        provider for provider in registry.build_provider_chain(config) if not getattr(provider, "is_online", False)
    ]
    service = DefinitionService(config, providers=providers, registry=registry)
    service.ensure_loaded()
    return service


def _close(service: object) -> None:
    closer = getattr(service, "close", None)
    if not callable(closer):
        return
    try:
        closer()
    except Exception as error:  # pragma: no cover - defensive
        logger.warning("Definition provider close failed: %s", error, exc_info=error)


def define_word(payload: Mapping[str, object]) -> str:
    if set(payload) != {"runId", "term", "fallbackTerm"}:
        raise _fail(
            "invalid_definition_request",
            "Expected payload fields: ['fallbackTerm', 'runId', 'term']",
        )
    run_id = payload["runId"]
    if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
        raise _fail("invalid_definition_request", "runId is not a valid opaque run ID")
    term = _text(payload["term"], name="term", max_bytes=MAX_TERM_BYTES)
    if not term.strip():
        raise _fail("invalid_definition_request", "term must not be blank")
    raw_fallback = payload["fallbackTerm"]
    fallback = None if raw_fallback is None else _text(raw_fallback, name="fallbackTerm", max_bytes=MAX_TERM_BYTES)

    with _lock:
        config = _run_configs.get(run_id)
    if config is None:
        raise _fail("definition_run_unknown", "No active run owns this lookup")

    service = _build_service(config)
    try:
        matched = term
        hits = service.lookup_all_offline(term)
        # Miss-only. A hit on the mined form always wins: unidic's canonical
        # lemma collapses kanji variants (殺る → 遣る), so a lemma entry painted
        # over a word that has its own would be the wrong homograph.
        if not hits and fallback and fallback != term:
            fallback_hits = service.lookup_all_offline(fallback)
            if fallback_hits:
                matched = fallback
                hits = fallback_hits
    finally:
        _close(service)

    entries: list[dict[str, str]] = []
    total = 0
    for source, html in hits:
        if not isinstance(source, str) or not isinstance(html, str):
            continue
        total += len(html.encode("utf-8"))
        if total > MAX_DEFINITION_HTML_BYTES:
            raise _fail(
                "definition_result_too_large",
                "The definition preview exceeds the Android display limit",
            )
        entries.append({"source": source, "html": html})

    return encode_message(
        "dictionary.define.result",
        {"runId": run_id, "term": term, "matchedTerm": matched, "entries": entries},
    )
