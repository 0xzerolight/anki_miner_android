"""Deterministic AnkiDroid API source vendoring."""

from .core import PINNED_UPSTREAM, SyncError, check, check_upstream, refresh

__all__ = ["PINNED_UPSTREAM", "SyncError", "check", "check_upstream", "refresh"]
