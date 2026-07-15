"""Debug-only tokenizer acquisition for combined instrumentation runs.

Production keeps one immutable engine tokenizer per process. Android's combined
instrumentation process exercises both parity candidates, so the candidate
which runs second must stay outside the already-configured engine seam.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Literal

if TYPE_CHECKING:
    from android_bridge.unidic_resource import RegisteredUniDic

TokenizerBackend = Literal["s1a", "s1b"]

ENGINE_SHARED_TAGGER = "engine_shared_tagger"


def acquire_tagger_for_instrumentation(
    backend: TokenizerBackend,
    registration: RegisteredUniDic,
) -> tuple[object, str, TokenizerBackend]:
    """Return a parity tagger without ever changing production selection.

    A fresh backend-specific instrumentation process takes the real engine
    path. Only a combined debug test process which already selected the other
    backend constructs the second candidate directly.
    """

    if backend not in {"s1a", "s1b"}:
        raise ValueError(f"unsupported instrumentation tokenizer: {backend!r}")

    from android_bridge.tokenizer_selection import (
        configure_tokenizer_backend,
        selected_tokenizer_backend,
    )

    selected = selected_tokenizer_backend()
    if selected is None or selected == backend:
        configured = configure_tokenizer_backend(backend)
        from anki_miner.services.tagger import get_shared_tagger

        return get_shared_tagger(), ENGINE_SHARED_TAGGER, configured

    if backend == "s1a":
        from android_bridge.tokenizer_s1a import create_s1a_tagger

        tagger = create_s1a_tagger(registration)
    else:
        from android_bridge.tokenizer_s1b import create_s1b_tagger

        tagger = create_s1b_tagger(registration)

    return tagger, f"debug_direct_fallback_after_{selected}", selected
