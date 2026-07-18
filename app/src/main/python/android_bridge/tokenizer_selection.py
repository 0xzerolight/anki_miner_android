"""One-time selection of the tokenizer used by the vendored engine."""

from __future__ import annotations

import threading
from typing import Literal

from .protocol import BridgeProtocolError

TokenizerBackendName = Literal["s1a", "s1b"]

_LOCK = threading.Lock()
_selected_backend: TokenizerBackendName | None = None


def configure_tokenizer_backend(backend: str) -> TokenizerBackendName:
    """Bind the engine shared-tagger seam to one registered backend.

    Configuration is process-immutable because both backends memory-map the
    registered UniDic files. Construction stays lazy until the engine first
    asks for its shared tagger.
    """

    if backend not in {"s1a", "s1b"}:
        raise BridgeProtocolError("invalid_tokenizer_backend", "Tokenizer backend must be 's1a' or 's1b'")

    from .bootstrap import require_initialized

    require_initialized()

    global _selected_backend
    with _LOCK:
        if _selected_backend is not None:
            if _selected_backend != backend:
                raise BridgeProtocolError(
                    "tokenizer_already_configured",
                    "The tokenizer backend cannot change in-process",
                )
            return _selected_backend

        from .unidic_resource import require_registered_unidic

        registration = require_registered_unidic()

        if backend == "s1a":

            def factory() -> object:
                from .tokenizer_s1a import create_s1a_tagger

                return create_s1a_tagger(registration)

        else:

            def factory() -> object:
                from .tokenizer_s1b import create_s1b_tagger

                return create_s1b_tagger(registration)

        # Load-bearing ordering: this is the first engine import in this module,
        # after bootstrap and dictionary registration have both succeeded.
        from anki_miner.services.tagger import configure_tagger_factory

        configure_tagger_factory(factory)
        _selected_backend = backend
        return backend


def selected_tokenizer_backend() -> TokenizerBackendName | None:
    """Return the configured backend without importing the engine."""

    with _LOCK:
        return _selected_backend
