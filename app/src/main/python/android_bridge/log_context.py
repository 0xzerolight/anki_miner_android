"""Stamp the active mining run id onto every Python log record.

Stdlib only. Do not add a module-scope ``anki_miner`` import here:
``ANKI_MINER_HOME`` freezes at engine import time (see ``bootstrap.py``), and
every bridge module keeps engine imports function-local so bootstrap can run
first.
"""

from __future__ import annotations

import logging
from contextlib import suppress
from contextvars import ContextVar

_RUN_ID: ContextVar[str | None] = ContextVar("anki_miner_run_id", default=None)

# The wire vocabulary of ``diagnostics.loglevel.set``. INFO is always on; DEBUG
# is the tester switch.
LOG_LEVELS: dict[str, int] = {
    "info": logging.INFO,
    "debug": logging.DEBUG,
}

# Only the trees this app owns. Anything outside them keeps whatever ceiling
# bootstrap gave it.
_FIRST_PARTY_LOG_TREES = ("anki_miner", "android_bridge")
_VENDORED_LOG_TREE = "anki_miner"

# JobRegistry admits at most one active job, so a single module global is an
# exact (not approximate) fallback: the engine fans work out to plain
# threading.Thread workers for parallel media extraction, and a ContextVar set
# on the bridge thread that started a run is invisible on those workers, which
# would otherwise log run=- for the busiest part of a mining run.
_ACTIVE_RUN_ID: str | None = None


def set_active_run(run_id: str | None) -> None:
    """Mirror ``JobRegistry``'s current run id for cross-thread log attribution.

    Every caller already holds ``JobRegistry._lock`` when the active job
    changes, so this plain module-attribute write needs no lock of its own.
    """

    global _ACTIVE_RUN_ID
    _ACTIVE_RUN_ID = run_id


def current_run_id() -> str | None:
    """Return the run id that should be attributed to a log record right now."""

    return _RUN_ID.get() or _ACTIVE_RUN_ID


def set_first_party_log_level(level: int) -> None:
    """Set ``level`` on the app's own logger trees, never on the root logger.

    Root must stay where ``bootstrap._install_file_logging`` left it. Lifting
    root to DEBUG would lift every third-party logger that has no explicit
    ceiling, and the ceiling covers five named libraries only -- one of them
    because urllib3 logs a Jisho retry URL whose query string carries the mined
    term percent-encoded, which the redaction pass (it matches literal CJK)
    cannot see. A bundle the user is about to send is the worst place to
    discover that.

    Records still reach the root file handler: propagation walks ancestor
    *handlers* and filters on the handler's own level, and never re-checks an
    ancestor *logger*'s level.
    """

    for name in _FIRST_PARTY_LOG_TREES:
        logging.getLogger(name).setLevel(level)


class RunContextFilter(logging.Filter):
    """Attach ``record.run_id`` to every record that reaches the handler.

    Attached to the handler, not to any logger, so it stamps records from all
    47 vendored ``anki_miner`` modules and every android_bridge logger without
    editing a single one of them.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        # logging.Handler.handle() calls filter() with no exception guard of
        # its own -- unlike emit(), whose formatter failures are contained by
        # handleError(). A throw here propagates straight into the original
        # logger.warning(...) call site and crashes the caller this feature
        # exists to diagnose, so both failure modes must be guarded. They are
        # guarded separately, not together, because they have different
        # correct fallbacks: a failed lookup still has a usable one ("-"),
        # so the record survives with a degraded field; a failed assignment
        # (a LogRecord subclass that rejects the attribute) has none, and
        # collapsing the two would let a lookup failure fall through with no
        # run_id at all, which drops the whole record at the formatter later.
        try:
            run_id = current_run_id() or "-"
        except Exception:
            run_id = "-"
        # instrumentation: intentionally silent — logging here would recurse through this Filter
        with suppress(Exception):
            record.run_id = run_id
        return True


class DefaultLogPrivacyFilter(logging.Filter):
    """Hide vendored message payloads unless the tester enabled DEBUG.

    The synchronized engine can add new user-data-bearing log calls on every
    re-pin, so this boundary treats every ``anki_miner`` message as private
    instead of trying to maintain a call-site blocklist. Logger name, severity,
    and exception class remain available at default verbosity.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            vendored = record.name == _VENDORED_LOG_TREE or record.name.startswith(f"{_VENDORED_LOG_TREE}.")
            verbose = logging.getLogger(_VENDORED_LOG_TREE).isEnabledFor(logging.DEBUG)
            if vendored and not verbose:
                failure = _record_failure_name(record)
                record.msg = "vendored record redacted failure=%s"
                record.args = (failure,)
                # Traceback messages and locals can carry the same vocabulary
                # or paths as the formatted message, so only the class survives.
                record.exc_info = None
                record.exc_text = None
                record.stack_info = None
        except Exception:
            # A filter exception escapes Handler.handle() into the code which
            # tried to log. Preserve a safe marker even if a hostile custom
            # LogRecord defeats the richer path above.
            with suppress(Exception):
                record.msg = "vendored record redacted failure=unknown"
                record.args = ()
                record.exc_info = None
                record.exc_text = None
                record.stack_info = None
        return True


def _record_failure_name(record: logging.LogRecord) -> str:
    exc_info = record.exc_info
    if exc_info and exc_info[0] is not None:
        return exc_info[0].__name__
    values = record.args.values() if isinstance(record.args, dict) else record.args
    for value in values:
        if isinstance(value, BaseException):
            return type(value).__name__
    return "unspecified"
