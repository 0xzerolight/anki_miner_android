"""Initialize engine filesystem state before importing the engine."""

from __future__ import annotations

import logging
import logging.handlers
import os
import sys
import threading
import time
import traceback

from . import log_context
from .protocol import BridgeProtocolError, encode_message

_LOCK = threading.Lock()
_initialized_home: str | None = None
_engine_modules_before_initialize: tuple[str, ...] | None = None

_LOG_FILE_NAME = "anki_miner.log"
_LOG_MAX_BYTES = 4_194_304
_LOG_BACKUP_COUNT = 1
_log_handler_installed = False
# Set on install failure, cleared on a later success; read back through
# log_handler_install_error() by a future diagnostics-bundle task.
_log_handler_install_error: str | None = None

# ``composition.toml``'s allowed_external includes ``requests`` for Jisho
# egress, and a mined vocabulary term reaches urllib3 percent-encoded (e.g.
# ``keyword=%E6%AE%BA%E3%81%99``), where the later redaction pass -- which
# matches literal CJK -- cannot see it. A flat WARNING ceiling is not enough:
# urllib3 2.7's connectionpool logs the retry URL, query string included, at
# WARNING itself (connectionpool.py:869, hit on any flaky mobile network
# retry), so that one child logger needs its own ceiling above WARNING. The
# rest of urllib3 stays at WARNING because its other warnings (TLS, header
# parsing) carry no URL and are worth keeping.
_THIRD_PARTY_LOG_CEILING = {
    "urllib3": logging.WARNING,
    "urllib3.connectionpool": logging.ERROR,
    "requests": logging.WARNING,
    "charset_normalizer": logging.WARNING,
    "PIL": logging.WARNING,
}


def _install_file_logging(home: str) -> None:
    """Attach one capped file handler so engine warnings become retrievable.

    ffmpeg/ffprobe failures are logged by the engine at WARNING on propagating
    module loggers; without a handler they die in logging's last-resort stderr.
    The file path matches the engine config's ``log_path`` (both derive from
    HOME). A logging failure must never break the load-bearing bootstrap.
    """

    global _log_handler_installed, _log_handler_install_error
    if _log_handler_installed:
        return
    try:
        log_path = os.path.join(home, _LOG_FILE_NAME)
        handler = logging.handlers.RotatingFileHandler(
            log_path,
            maxBytes=_LOG_MAX_BYTES,
            backupCount=_LOG_BACKUP_COUNT,
            encoding="utf-8",
        )
        # The timestamp must be byte-compatible with Kotlin's LogRecord.kt
        # (UTC, millisecond precision, trailing Z) so a maintainer can
        # interleave anki_miner_app.log and this file with a plain `sort`.
        formatter = logging.Formatter(
            "%(asctime)s.%(msecs)03dZ %(levelname)s run=%(run_id)s %(name)s: %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S",
        )
        formatter.converter = time.gmtime
        handler.setFormatter(formatter)
        # On the handler, not on any logger: this is what stamps all 47
        # vendored anki_miner modules and every bridge logger without
        # touching the sync-generated vendored tree.
        handler.addFilter(log_context.RunContextFilter())
        root = logging.getLogger()
        root.addHandler(handler)
        if root.level > logging.INFO:
            root.setLevel(logging.INFO)
        for name, level in _THIRD_PARTY_LOG_CEILING.items():
            logging.getLogger(name).setLevel(level)
        _log_handler_installed = True
        # A prior failed attempt (different home, or a transient install
        # error) must not leave a stale traceback behind a later success.
        _log_handler_install_error = None
        # Without this, "no log lines yet" and "no log file at all" look
        # identical from the diagnostics bundle.
        logging.getLogger(__name__).info(
            "file logging installed path=%s maxBytes=%d backupCount=%d",
            log_path,
            _LOG_MAX_BYTES,
            _LOG_BACKUP_COUNT,
        )
    except Exception:
        # The handler that would carry a message here is the one that just
        # failed to install, under a root logger clamped to INFO -- logging
        # this through `logging` goes nowhere. Chaquopy pipes Python stderr
        # to logcat, which a later diagnostics bundle captures.
        _log_handler_install_error = traceback.format_exc()
        print(_log_handler_install_error, file=sys.stderr)


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
        _install_file_logging(requested)

    return encode_message("bootstrap.ready", {"home": requested})


def initialized_home() -> str | None:
    """Return the established engine home for diagnostics."""

    with _LOCK:
        return _initialized_home


def log_handler_install_error() -> str | None:
    """Return the formatted traceback from the last failed handler install.

    ``None`` means either no attempt has failed, or a later attempt for the
    same home has since succeeded. For a future diagnostics-bundle task.
    """

    with _LOCK:
        return _log_handler_install_error


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
