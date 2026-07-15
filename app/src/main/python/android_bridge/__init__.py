"""Host-testable boundary between Kotlin and the vendored Python engine.

Bridge modules deliberately avoid importing :mod:`anki_miner` at module import
time.  ``android_bridge.bootstrap.initialize`` must establish the engine home
before any engine module is loaded.
"""

from .boundary import dispatch
from .protocol import BRIDGE_SCHEMA_VERSION

__all__ = ["BRIDGE_SCHEMA_VERSION", "dispatch"]
