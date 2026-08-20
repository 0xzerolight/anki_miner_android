"""Run-scoped expression-audio cache lifetime.

Stdlib-only on purpose: mining.py constructs the cache before any engine
service import, and the requests-free host test lane imports it directly.
"""

from __future__ import annotations

import contextlib
import os
import threading
from pathlib import Path


class _RunAudioCache:
    """Pins source copies for one run and removes only unreferenced cache files.

    Pruning happens at construction (prior-run leftovers), via ``discard()``
    (rejected files), and at ``close()`` (run end) — never on ``pin()``, which
    would be an O(n²) full walk per hit and would bump the cache directory's
    mtime, invalidating the ``find_cached_by_stem`` signature index.
    """

    def __init__(self, root: Path) -> None:
        self._root = root.resolve(strict=False)
        self._pinned: set[Path] = set()
        self._closed = False
        self._lock = threading.RLock()
        self.prune_unreferenced()

    def pin(self, path: Path) -> bool:
        with self._lock:
            if self._closed:
                return False
            try:
                resolved = path.resolve(strict=True)
                resolved.relative_to(self._root)
            except (OSError, RuntimeError, ValueError):
                return False
            if not resolved.is_file():
                return False
            self._pinned.add(resolved)
            return True

    def discard(self, path: Path) -> None:
        with self._lock:
            try:
                resolved = path.resolve(strict=False)
                resolved.relative_to(self._root)
            except (OSError, RuntimeError, ValueError):
                return
            self._pinned.discard(resolved)
            with contextlib.suppress(OSError):
                path.unlink()

    def prune_unreferenced(self) -> None:
        with self._lock:
            self._prune_locked()

    def _prune_locked(self) -> None:
        if not self._root.is_dir():
            return
        try:
            entries = list(os.walk(self._root, topdown=False, followlinks=False))
        except OSError:
            return
        for directory, child_dirs, filenames in entries:
            parent = Path(directory)
            for filename in filenames:
                candidate = parent / filename
                if candidate.resolve(strict=False) in self._pinned:
                    continue
                with contextlib.suppress(OSError):
                    candidate.unlink()
            for child_dir in child_dirs:
                candidate = parent / child_dir
                try:
                    if candidate.is_symlink():
                        candidate.unlink()
                    else:
                        candidate.rmdir()
                except OSError:
                    continue

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._pinned.clear()
            self._prune_locked()
            self._closed = True
