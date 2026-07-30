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
        # exists to diagnose. The assignment itself can fail too (a LogRecord
        # subclass that rejects the attribute), not just the lookup, so both
        # must be inside the same guard, with no retry on failure.
        with suppress(Exception):
            record.run_id = current_run_id() or "-"
        return True
