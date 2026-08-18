"""Throttled resource-operation progress reporter.

Adapts engine ``ProgressFn`` callbacks and bridge-owned byte loops into
``resource.progress`` envelopes on ``EngineCallbacks.onProgress``. Never
raises into the import path: a failing callback disables the reporter for
the rest of the operation. Engine progress *messages* are dropped —
Android localizes phase labels itself.
"""

from __future__ import annotations

import time
from typing import Any, Callable, Mapping

from .protocol import encode_message

_PHASES = ("importing", "installing", "finalizing")
_MIN_INTERVAL = 0.2


class ResourceProgressReporter:
    def __init__(
        self,
        callbacks,
        operation_id: str,
        phase: str,
        *,
        min_interval: float = _MIN_INTERVAL,
        clock: Callable[[], float] = time.monotonic,
    ):
        if phase not in _PHASES:
            raise ValueError(f"unknown phase: {phase}")
        self._callbacks = callbacks
        self._operation_id = operation_id
        self._phase = phase
        self._min_interval = min_interval
        self._clock = clock
        self._last_emit = None
        self._disabled = False

    def report(self, current: int | float, total: int | float, *, kind: str = "items", force: bool = False) -> None:
        if self._disabled:
            return
        now = self._clock()
        terminal = total > 0 and current == total
        if not force and not terminal and self._last_emit is not None and now - self._last_emit < self._min_interval:
            return
        payload: Mapping[str, Any] = {
            "operationId": self._operation_id,
            "phase": self._phase,
            "kind": kind,
            "current": int(current),
            "total": int(total),
        }
        envelope = encode_message("resource.progress", payload)
        try:
            self._callbacks.onProgress(envelope)
        except Exception:
            self._disabled = True
            return
        self._last_emit = now

    def set_phase(self, phase: str) -> None:
        if phase not in _PHASES:
            raise ValueError(f"unknown phase: {phase}")
        self._phase = phase
        self.report(0, 0, force=True)

    def items_fn(self) -> Callable[[int, int, str], None]:
        def _fn(current: int, total: int, message: str) -> None:
            if current == 0 and total == 0:
                return  # stage marker, message-only: dropped by design
            self.report(current, total, kind="items")

        return _fn

    def bytes_fn(self) -> Callable[[int, int], None]:
        def _fn(current: int, total: int) -> None:
            self.report(current, total, kind="bytes")

        return _fn


class _NullReporter:
    def report(self, current, total, *, kind="items", force=False) -> None:
        pass

    def set_phase(self, phase: str) -> None:
        pass

    def items_fn(self):
        return lambda current, total, message: None

    def bytes_fn(self):
        return lambda current, total: None


def make_reporter(callbacks, operation_id: str, phase: str) -> ResourceProgressReporter | _NullReporter:
    if callbacks is None:
        return _NullReporter()
    return ResourceProgressReporter(callbacks, operation_id, phase)
