"""Deterministic desktop-engine vendoring support."""

from .core import EngineSyncError, build_snapshot, check_destination, sync_destination

__all__ = ["EngineSyncError", "build_snapshot", "check_destination", "sync_destination"]
