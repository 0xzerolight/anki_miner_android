"""Initialize engine filesystem state before importing the engine."""

from __future__ import annotations

import os
import sys
import threading

from .protocol import BridgeProtocolError, encode_message

_LOCK = threading.Lock()
_initialized_home: str | None = None
_engine_modules_before_initialize: tuple[str, ...] | None = None


def _loaded_engine_modules() -> tuple[str, ...]:
    return tuple(sorted(name for name in sys.modules if name == "anki_miner" or name.startswith("anki_miner.")))


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

    global _engine_modules_before_initialize, _initialized_home
    with _LOCK:
        if _initialized_home is not None and _initialized_home != requested:
            raise BridgeProtocolError("already_initialized", "The Python engine home cannot change in-process")

        engine_modules_before = _loaded_engine_modules()
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
        _engine_modules_before_initialize = engine_modules_before
        _initialized_home = requested

    return encode_message("bootstrap.ready", {"home": requested})


def initialized_home() -> str | None:
    """Return the established engine home for diagnostics."""

    with _LOCK:
        return _initialized_home


def engine_modules_before_initialize() -> tuple[str, ...] | None:
    """Return the engine-module inventory captured before bootstrap imported paths.

    This is a diagnostic seam for the packaged startup acceptance test. ``None``
    means bootstrap has not completed in this process.
    """

    with _LOCK:
        return _engine_modules_before_initialize


def require_initialized(expected_home: str | os.PathLike[str] | None = None) -> str:
    """Require bootstrap without importing any engine module.

    ``expected_home`` is compared canonically, so an equivalent symlinked path
    is accepted while a different Android files directory fails closed. The
    environment check detects mutation after the engine froze its paths.
    """

    with _LOCK:
        home = _initialized_home
        if home is None:
            raise BridgeProtocolError(
                "bootstrap_required",
                "android_bridge.bootstrap.initialize must run before engine work",
            )

        environment_home = os.environ.get("ANKI_MINER_HOME")
        if not environment_home or os.path.realpath(environment_home) != home:
            raise BridgeProtocolError(
                "home_mismatch",
                "ANKI_MINER_HOME changed after the Python engine was initialized",
            )

        if expected_home is not None:
            candidate = os.fspath(expected_home)
            if not candidate or not os.path.isabs(candidate):
                raise BridgeProtocolError("invalid_files_dir", "expected_home must be absolute")
            if os.path.realpath(candidate) != home:
                raise BridgeProtocolError(
                    "home_mismatch",
                    "The requested Android files directory differs from the initialized engine home",
                )
        return home
