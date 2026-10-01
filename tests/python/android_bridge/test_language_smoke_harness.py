"""The emulator language smoke's harness, replayed on the host engine.

``LanguageSmokeInstrumentedTest`` compares the packaged engine's rows against
``EXPECTED``; this proves those rows are what the vendored engine produces here,
so an emulator mismatch points at packaging, not at a stale expectation.
"""

from __future__ import annotations

import ast
import json
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEBUG_PYTHON_ROOT = PROJECT_ROOT / "app/src/debug/python"
HARNESS_PATH = DEBUG_PYTHON_ROOT / "language_smoke_instrumented.py"
sys.path.insert(0, str(DEBUG_PYTHON_ROOT))

import language_smoke_instrumented as harness  # noqa: E402


def test_harness_does_not_import_engine_at_module_scope() -> None:
    tree = ast.parse(HARNESS_PATH.read_text(encoding="utf-8"), filename=str(HARNESS_PATH))
    offenders = [
        node.lineno
        for node in tree.body
        if isinstance(node, (ast.Import, ast.ImportFrom))
        and any(
            name.split(".")[0] in {"anki_miner", "android_bridge"}
            for name in ([alias.name for alias in node.names] if isinstance(node, ast.Import) else [node.module or ""])
        )
    ]
    assert offenders == []


def test_every_smoked_code_has_expected_rows() -> None:
    assert set(harness.EXPECTED) == set(harness.CI_CODES) | set(harness.LOCAL_RESOURCES)
    assert set(harness.CI_CODES).isdisjoint(harness.LOCAL_RESOURCES)


def test_local_codes_name_their_pinned_language_data() -> None:
    from android_bridge.resource_catalog import LanguageDataResource, find_catalog_resource

    for code, resource_id in harness.LOCAL_RESOURCES.items():
        language, resource = find_catalog_resource(resource_id)
        assert language == code
        assert isinstance(resource, LanguageDataResource)
    assert harness.archive_name("ar") == "morphology_db_calima-msa-r13-0.4.0.zip"
    assert harness.archive_name("fa") == "hazm-0.12.1-py3-none-any.whl"


#: The package each CI code's tagger needs beyond the engine (runtime dependency lane only).
_TAGGER_PACKAGES = {"th": "pythainlp", "tr": "zeyrek", "zh": "jieba"}


@pytest.mark.parametrize("code", ["he", "id", "th", "tr", "zh"])
def test_ci_code_tokenises_its_smoke_sentence_as_expected(code: str, initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    if code in _TAGGER_PACKAGES:
        pytest.importorskip(_TAGGER_PACKAGES[code], reason=f"runtime dependency lane: {code} needs its tagger")
    assert code in harness.CI_CODES
    result = json.loads(harness.smoke(code))
    assert result["unavailable_reason"] is None
    assert result["tokens"] == harness.EXPECTED[code]
    assert result["expected"] == harness.EXPECTED[code]


def test_thai_footprint_reports_the_read_only_package(initialized_bridge_home: Path) -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    pytest.importorskip("pythainlp", reason="runtime dependency lane: Thai tokenises with pythainlp")
    harness.smoke("th")
    footprint = json.loads(harness.thai_footprint(str(initialized_bridge_home)))
    assert footprint["env_read_only"] == "1"
    assert footprint["env_offline"] == "1"
    assert footprint["file_count"] > 0
    assert str(initialized_bridge_home / "pythainlp-data") not in footprint["pythainlp_data_dirs"]
