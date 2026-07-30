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
def _reset_active_run_id():
    """Reset ``log_context``'s process-wide global around every test in this directory.

    A fixture defined inside a single test module only autouses within that
    module; ``_ACTIVE_RUN_ID`` is a module-global read across the whole
    process, and ``test_jobs.py`` alone calls ``JobRegistry.begin()`` far more
    often than ``finish()``/``shutdown()``, so it leaks the global into
    whatever test runs next in the same session unless every module resets
    it. This must stay function-scoped (the default) rather than
    session-scoped: a session-scoped reset only runs once total and would not
    stop leakage between individual tests, which is the actual bug.
    """

    from android_bridge import log_context

    log_context.set_active_run(None)
    yield
    log_context.set_active_run(None)
