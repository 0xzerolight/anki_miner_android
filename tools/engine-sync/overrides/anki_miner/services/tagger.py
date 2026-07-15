"""Process-wide shared tokenizer with lazy, lock-guarded construction.

Android selects one verified tokenizer backend before any parsing starts. The
engine keeps the desktop shared-tagger contract: construction happens once and
every call is serialized through one re-entrant lock.
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from typing import Any

TaggerFactory = Callable[[], Any]

_tagger_lock = threading.Lock()
_tagger_factory: TaggerFactory | None = None
_tagger: Any | None = None
_locked_tagger: "LockedTagger | None" = None


class LockedTagger:
    """Thread-safe wrapper over the selected tokenizer backend."""

    _parse_lock: threading.RLock = threading.RLock()

    def __init__(self, inner: Any) -> None:
        object.__setattr__(self, "_inner", inner)

    def __call__(self, text: str, *args: Any, **kwargs: Any) -> Any:
        with self._parse_lock:
            return self._inner(text, *args, **kwargs)

    def parse(self, *args: Any, **kwargs: Any) -> Any:
        with self._parse_lock:
            return self._inner.parse(*args, **kwargs)

    def __getattr__(self, name: str) -> Any:
        return getattr(object.__getattribute__(self, "_inner"), name)

    def __repr__(self) -> str:
        inner = object.__getattribute__(self, "_inner")
        return f"LockedTagger({inner!r})"


def configure_tagger_factory(factory: TaggerFactory) -> None:
    """Install the immutable Android tokenizer factory before first use."""

    if not callable(factory):
        raise TypeError("Tokenizer factory must be callable")

    global _tagger_factory
    with _tagger_lock:
        if _tagger is not None or _locked_tagger is not None:
            raise RuntimeError("Tokenizer backend was configured after first use")
        if _tagger_factory is None:
            _tagger_factory = factory
        elif _tagger_factory is not factory:
            raise RuntimeError("Tokenizer backend is already configured")


def get_shared_tagger() -> LockedTagger:
    """Return the one lock-guarded tagger built by the selected backend."""

    global _tagger, _locked_tagger
    if _locked_tagger is None:
        with _tagger_lock:
            if _locked_tagger is None:
                factory = _tagger_factory
                if factory is None:
                    raise RuntimeError(
                        "Android tokenizer backend has not been configured"
                    )
                if _tagger is None:
                    _tagger = factory()
                _locked_tagger = LockedTagger(_tagger)
    return _locked_tagger
