from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"


def _run(script: str, home: Path) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(PYTHON_ROOT)
    env.pop("ANKI_MINER_HOME", None)
    return subprocess.run(
        [sys.executable, "-c", script, str(home)],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )


def test_initialize_sets_home_before_engine_import(tmp_path: Path) -> None:
    result = _run(
        """
import json, os, sys
from android_bridge.bootstrap import initialize
raw = initialize(sys.argv[1])
from anki_miner.config.paths import ANKI_MINER_HOME
print(json.dumps({"message": json.loads(raw), "env": os.environ["ANKI_MINER_HOME"], "home": str(ANKI_MINER_HOME)}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["message"]["type"] == "bootstrap.ready"
    assert data["env"] == data["home"] == str(tmp_path)


def test_initialize_detects_engine_imported_with_the_wrong_home(tmp_path: Path) -> None:
    wrong_home = tmp_path / "wrong"
    result = _run(
        f"""
import os, sys
os.environ["ANKI_MINER_HOME"] = {str(wrong_home)!r}
from anki_miner.config.paths import ANKI_MINER_HOME
from android_bridge.bootstrap import initialize
try:
    initialize(sys.argv[1])
except Exception as exc:
    print(getattr(exc, "code", ""))
    raise
""",
        tmp_path / "right",
    )

    assert result.returncode != 0
    assert "engine_imported_before_bootstrap" in result.stdout


def test_initialize_rejects_relative_home_without_mutating_environment() -> None:
    result = _run(
        """
import os
from android_bridge.bootstrap import initialize
try:
    initialize("relative/files")
except Exception as exc:
    print(getattr(exc, "code", ""), os.environ.get("ANKI_MINER_HOME"))
    raise
""",
        Path("/unused"),
    )

    assert result.returncode != 0
    assert "invalid_files_dir None" in result.stdout


def test_require_initialized_fails_without_importing_engine(tmp_path: Path) -> None:
    result = _run(
        """
import sys
from android_bridge.bootstrap import require_initialized
try:
    require_initialized()
except Exception as exc:
    print(getattr(exc, "code", ""), any(name.startswith("anki_miner") for name in sys.modules))
    raise
""",
        tmp_path,
    )

    assert result.returncode != 0
    assert "bootstrap_required False" in result.stdout


def test_require_initialized_uses_canonical_same_home_check(tmp_path: Path) -> None:
    real_home = tmp_path / "real"
    real_home.mkdir()
    alias_home = tmp_path / "alias"
    alias_home.symlink_to(real_home, target_is_directory=True)
    other_home = tmp_path / "other"
    result = _run(
        f"""
import sys
from android_bridge.bootstrap import initialize, require_initialized
initialize(sys.argv[1])
print(require_initialized({str(alias_home)!r}))
try:
    require_initialized({str(other_home)!r})
except Exception as exc:
    print(getattr(exc, "code", ""))
""",
        real_home,
    )

    assert result.returncode == 0, result.stderr
    assert str(real_home) in result.stdout
    assert "home_mismatch" in result.stdout


def test_require_initialized_detects_environment_mutation(tmp_path: Path) -> None:
    result = _run(
        """
import os, sys
from android_bridge.bootstrap import initialize, require_initialized
initialize(sys.argv[1])
os.environ["ANKI_MINER_HOME"] = "/different"
try:
    require_initialized()
except Exception as exc:
    print(getattr(exc, "code", ""))
    raise
""",
        tmp_path,
    )

    assert result.returncode != 0
    assert "home_mismatch" in result.stdout


def test_bridge_modules_have_no_top_level_engine_imports() -> None:
    import ast

    bridge_root = PYTHON_ROOT / "android_bridge"
    offenders: list[str] = []
    for path in bridge_root.glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in tree.body:
            if isinstance(node, ast.Import) and any(alias.name.startswith("anki_miner") for alias in node.names):
                offenders.append(f"{path.name}:{node.lineno}")
            elif isinstance(node, ast.ImportFrom) and (node.module or "").startswith("anki_miner"):
                offenders.append(f"{path.name}:{node.lineno}")

    assert offenders == []
