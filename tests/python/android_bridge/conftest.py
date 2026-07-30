from __future__ import annotations

import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_ROOT))


@pytest.fixture(scope="session")
def initialized_bridge_home(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Bootstrap once before tests which construct an engine config."""

    from android_bridge.bootstrap import initialize

    home = tmp_path_factory.mktemp("android-files")
    initialize(str(home))
    return home


@pytest.fixture(autouse=True)
def _reset_log_context():
    """Reset ``log_context``'s process-wide state around every test in this directory.

    Two globals now: the active run id, and the first-party logger levels that
    ``diagnostics.loglevel.set`` moves. Both leak the same way -- a test that
    raises the ``anki_miner`` tree to DEBUG and does not put it back makes the
    next test's assertion about that level depend on ordering.

    A fixture defined inside a single test module only autouses within that
    module; ``_ACTIVE_RUN_ID`` is a module-global read across the whole
    process, and ``test_jobs.py`` alone calls ``JobRegistry.begin()`` far more
    often than ``finish()``/``shutdown()``, so it leaks the global into
    whatever test runs next in the same session unless every module resets
    it. This must stay function-scoped (the default) rather than
    session-scoped: a session-scoped reset only runs once total and would not
    stop leakage between individual tests, which is the actual bug.
    """

    import logging

    from android_bridge import log_context

    log_context.set_active_run(None)
    log_context.set_first_party_log_level(logging.INFO)
    yield
    log_context.set_active_run(None)
    log_context.set_first_party_log_level(logging.INFO)
