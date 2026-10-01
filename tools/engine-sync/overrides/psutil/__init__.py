"""Android-owned stand-in for the two psutil names the vendored engine uses.

``anki_miner/services/_staging.py`` needs only ``Process(pid).create_time()``
and ``NoSuchProcess`` to tell a live promotion-lock holder from a dead one.
The real wheel cannot answer that on Android. Probed on the API 26 emulator
as the app (``run-as``, SELinux domain ``untrusted_app``):

- ``/proc/stat`` (label ``proc_stat``) and ``/proc/uptime`` are EACCES, so
  psutil's boot time, and with it ``create_time()``, raises AccessDenied;
- ``/proc/self/stat`` is readable;
- ``/proc`` is mounted ``hidepid=2``: a PID this process may not ptrace is
  invisible and reads as ``NoSuchProcess``, as it does under real psutil.
  That covers other apps and can cover a second, non-dumpable process of
  this app, so sharing a UID is not what makes it safe. It is safe because
  the engine runs in this one process: any other PID in a lockfile is a
  dead holder.

The boot time therefore comes from clocks instead of files. ``starttime`` in
``/proc/<pid>/stat`` counts clock ticks on CLOCK_BOOTTIME (the same clock
``/proc/uptime`` reports), so wall-clock boot time is ``time.time()`` minus
CLOCK_BOOTTIME. The two reads are not atomic; flooring to whole seconds, as
the kernel does when it prints ``btime``, keeps the value identical across
calls and processes, which ``_staging`` needs because it compares the result
to a lockfile copy at microsecond precision.
"""

from __future__ import annotations

import os
import time

__all__ = ["NoSuchProcess", "Process"]


class NoSuchProcess(Exception):
    """The PID is not in ``/proc``: it exited, or it was never visible."""

    def __init__(self, pid: int) -> None:
        super().__init__(f"process PID not found (pid={pid})")
        self.pid = pid


def _boot_time() -> float:
    return float(int(time.time() - time.clock_gettime(time.CLOCK_BOOTTIME)))


class Process:
    def __init__(self, pid: int | None = None) -> None:
        if pid is None:
            pid = os.getpid()
        elif pid < 0:
            raise ValueError(f"pid must be a positive integer (got {pid})")
        self.pid = pid

    def create_time(self) -> float:
        """Process start in seconds since the epoch, as psutil computes it."""
        try:
            with open(f"/proc/{self.pid}/stat", "rb") as handle:
                data = handle.read()
        except (FileNotFoundError, ProcessLookupError):
            raise NoSuchProcess(self.pid) from None
        # The command name may hold spaces and parentheses; the fixed fields
        # resume after the last ")". starttime is field 22 of proc(5).
        start_ticks = int(data[data.rfind(b")") + 2 :].split()[19])
        return start_ticks / os.sysconf("SC_CLK_TCK") + _boot_time()
