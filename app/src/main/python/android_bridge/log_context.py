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
        with suppress(Exception):
            record.run_id = run_id
        return True
