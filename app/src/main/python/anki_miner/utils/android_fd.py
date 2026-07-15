"""Keep Android SAF descriptors alive across ffmpeg/ffprobe ``exec``.

Kotlin transfers a seekable ``ParcelFileDescriptor`` into the parked Python
job and represents it as ``/proc/self/fd/N``. Python child processes close all
non-standard descriptors by default, so each spawn must inherit a fresh dup and
must reference that dup's procfs path. Keeping this in one helper makes that
load-bearing rule identical for ffmpeg and ffprobe.
"""

from __future__ import annotations

import os
import re
from collections.abc import Iterator, Sequence
from contextlib import contextmanager

_PROC_SELF_FD_RE = re.compile(r"/proc/self/fd/([0-9]+)\Z")


@contextmanager
def inherited_fd_command(
    command: Sequence[str],
) -> Iterator[tuple[list[str], tuple[int, ...]]]:
    """Yield a rewritten command and the exact descriptors for ``pass_fds``.

    Every distinct ``/proc/self/fd/N`` argument is duplicated once per child.
    The duplicates stay open until ``Popen`` has completed its fork/exec setup
    and are then closed in the parent on every path. Repeated references to the
    same source descriptor use the same per-child duplicate.
    """

    rewritten = list(command)
    duplicates: dict[int, int] = {}
    try:
        for index, argument in enumerate(rewritten):
            if not isinstance(argument, str):
                raise TypeError("subprocess command arguments must be strings")
            match = _PROC_SELF_FD_RE.fullmatch(argument)
            if match is None:
                continue
            source_fd = int(match.group(1))
            duplicate = duplicates.get(source_fd)
            if duplicate is None:
                duplicate = os.dup(source_fd)
                duplicates[source_fd] = duplicate
            rewritten[index] = f"/proc/self/fd/{duplicate}"
        yield rewritten, tuple(duplicates.values())
    finally:
        for duplicate in duplicates.values():
            try:
                os.close(duplicate)
            except OSError:
                # A caller must not close these borrowed descriptors, but a
                # defensive close here must never mask its primary exception.
                pass
