"""Host guard for the debug golden dumper's pitch section.

``EngineGoldenV2InstrumentedTest`` owns ``engine_golden_v2_instrumented`` and is
on the API 26 lane's UNEXECUTED allowlist, so nothing in CI executes that
module. It went on importing ``PitchAccentService`` for a whole release cycle
after the name was removed upstream -- an ImportError no lane could reach. This
re-derives the pitch section on the host, from the committed input, and compares
it with the committed expectation.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEBUG_PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "debug" / "python"
GOLDEN_ROOT = PROJECT_ROOT / "golden"


def _dumper():
    pytest.importorskip("requests", reason="runtime dependency lane")
    pytest.importorskip("pysubs2", reason="runtime dependency lane")
    if str(DEBUG_PYTHON_ROOT) not in sys.path:
        sys.path.insert(0, str(DEBUG_PYTHON_ROOT))
    import engine_golden_v2_instrumented

    return engine_golden_v2_instrumented


def test_pitch_section_rederives_the_committed_golden(tmp_path: Path) -> None:
    dumper = _dumper()
    contract_input = json.loads((GOLDEN_ROOT / "corpus" / "engine-v2-input.json").read_text("utf-8"))
    expected = json.loads((GOLDEN_ROOT / "engine-v2.json").read_text("utf-8"))["cases"]["pitch"]

    derived, _service = dumper._pitch(tmp_path / "pitch", contract_input["pitch"])

    assert derived == expected


def test_pitch_section_renders_a_graph_for_every_pitched_query(tmp_path: Path) -> None:
    # The committed expectation is only worth as much as the fields it pins: a
    # section that returned "" everywhere would still round-trip. Both fixture
    # queries carry a pattern, so both owe a graph.
    dumper = _dumper()
    contract_input = json.loads((GOLDEN_ROOT / "corpus" / "engine-v2-input.json").read_text("utf-8"))

    derived, _service = dumper._pitch(tmp_path / "pitch", contract_input["pitch"])

    assert derived, "the pitch fixture has no queries"
    for case in derived:
        assert case["output"]["pattern"], f"{case['id']} has no pattern"
        assert case["output"]["graph_html"].startswith("<svg"), f"{case['id']} rendered no graph"
        assert case["output"]["text_html"], f"{case['id']} rendered no overline text"
