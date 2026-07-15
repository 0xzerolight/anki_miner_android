"""Initialize engine filesystem state before importing the engine."""

from __future__ import annotations

import os
import sys
import threading

from .protocol import BridgeProtocolError, encode_message

_LOCK = threading.Lock()
_initialized_home: str | None = None


def initialize(files_dir: str) -> str:
    """Set ``ANKI_MINER_HOME`` and prove the engine observed the same path.

    Kotlin must call this immediately after ``Python.start()``.  The only
    engine import is function-local and happens after the environment variable
    is set.  Repeating the call for the same directory is idempotent; attempting
    to move an already-imported engine is rejected.
    """

    if not isinstance(files_dir, str) or not files_dir.strip():
        raise BridgeProtocolError("invalid_files_dir", "files_dir must be a non-empty string")
    if not os.path.isabs(files_dir):
        raise BridgeProtocolError("invalid_files_dir", "files_dir must be absolute")
    requested = os.path.realpath(files_dir)

    global _initialized_home
    with _LOCK:
        if _initialized_home is not None and _initialized_home != requested:
            raise BridgeProtocolError("already_initialized", "The Python engine home cannot change in-process")

        already_loaded = sys.modules.get("anki_miner.config.paths")
        if already_loaded is not None:
            frozen_home = os.path.realpath(os.fspath(already_loaded.ANKI_MINER_HOME))
            if frozen_home != requested:
                raise BridgeProtocolError(
                    "engine_imported_before_bootstrap",
                    "anki_miner.config.paths was imported before ANKI_MINER_HOME was established",
                )

        # Load-bearing ordering: do not place an anki_miner import above this.
        os.environ["ANKI_MINER_HOME"] = requested

        from anki_miner.config.paths import ANKI_MINER_HOME

        observed = os.path.realpath(os.fspath(ANKI_MINER_HOME))
        if observed != requested:
            raise BridgeProtocolError(
                "home_mismatch",
                f"Engine home mismatch: requested {requested!r}, observed {observed!r}",
            )
        _initialized_home = requested

    return encode_message("bootstrap.ready", {"home": requested})


def initialized_home() -> str | None:
    """Return the established engine home for diagnostics."""

    with _LOCK:
        return _initialized_home
