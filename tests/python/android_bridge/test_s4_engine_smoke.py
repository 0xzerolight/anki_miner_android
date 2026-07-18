from __future__ import annotations

import ast
import json
import os
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app/src/main/python"
DEBUG_PYTHON_ROOT = PROJECT_ROOT / "app/src/debug/python"
PROBE_PATH = DEBUG_PYTHON_ROOT / "s4_engine_smoke.py"


def test_debug_probe_does_not_import_engine_at_module_scope() -> None:
    tree = ast.parse(PROBE_PATH.read_text(encoding="utf-8"), filename=str(PROBE_PATH))
    offenders: list[int] = []
    for node in tree.body:
        if isinstance(node, ast.Import) and any(
            alias.name == "anki_miner" or alias.name.startswith("anki_miner.") for alias in node.names
        ):
            offenders.append(node.lineno)
        if isinstance(node, ast.ImportFrom) and (
            node.module == "anki_miner" or (node.module or "").startswith("anki_miner.")
        ):
            offenders.append(node.lineno)

    assert offenders == []


def test_preflight_proves_bootstrap_is_required_without_loading_engine(tmp_path: Path) -> None:
    environment = dict(os.environ)
    environment.pop("ANKI_MINER_HOME", None)
    environment["PYTHONPATH"] = os.pathsep.join((str(DEBUG_PYTHON_ROOT), str(PYTHON_ROOT)))
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            "import s4_engine_smoke; print(s4_engine_smoke.preflight())",
        ],
        cwd=tmp_path,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {
        "bootstrap_engine_modules_before": [],
        "engine_modules_after": [],
        "engine_modules_before": [],
        "require_initialized_failure": "bootstrap_required",
    }


def test_probe_replays_one_unbroken_production_chain() -> None:
    source = PROBE_PATH.read_text(encoding="utf-8")
    configure_index = source.index('configure_tokenizer_backend("s1a")')
    shared_index = source.index("get_shared_tagger()")
    orchestration_index = source.index("import anki_miner.orchestration.episode_processor")
    parse_index = source.index(".parse_subtitle_file(subtitle_path)")
    filter_index = source.index(".filter_unknown(")
    render_index = source.index("rendered_content = render_glossary_entry")
    row_index = source.index("content=rendered_content")
    lookup_index = source.index("lookup_html = provider.lookup")

    assert configure_index < shared_index < orchestration_index
    assert orchestration_index < parse_index < filter_index < render_index < row_index < lookup_index
    assert '"episode_processor_import_ms"' in source
    assert '"engine_import_ms"' not in source
    assert 'QCoreApplication.translate("S4", "%n cards", "", 2)' in source
    assert 'tr_format("Step %1 of %2", 1, 5)' in source
    assert "actual_output != expected_output" in source
