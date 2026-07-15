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
