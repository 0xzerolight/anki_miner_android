"""Re-derive the subtitle replacement-template parity corpus against live CPython.

The committed corpus is the oracle for the Kotlin `SubtitleRegexReplacementParityTest`.
If it were only ever compared against itself it would prove nothing, so every recorded
verdict is reconstructed here from the real interpreter. A drift in either direction —
a case Python now accepts but the corpus calls rejected, or vice versa — fails.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS = REPO_ROOT / "app/src/test/resources/contracts/subtitle_regex_replacement_v1.json"


def _load() -> dict:
    return json.loads(CORPUS.read_text(encoding="utf-8"))


def _python_rejects(pattern: str, replacement: str) -> bool:
    compiled = re.compile(pattern)
    try:
        compiled.sub(replacement, "")
    except re.error:
        return True
    except IndexError:
        # CPython raises IndexError, not re.error, for an unknown group name.
        return True
    return False


def test_corpus_is_well_formed() -> None:
    document = _load()
    assert document["schema_version"] == 1
    cases = document["cases"]
    assert len(cases) >= 60
    names = [case["name"] for case in cases]
    assert len(names) == len(set(names)), "case names must be unique"
    assert any(case["rejected"] for case in cases)
    assert any(not case["rejected"] for case in cases)


@pytest.mark.parametrize("case", _load()["cases"], ids=lambda case: case["name"])
def test_recorded_verdict_matches_live_python(case: dict) -> None:
    assert _python_rejects(case["pattern"], case["replacement"]) is case["rejected"]


def test_every_pattern_compiles() -> None:
    # A pattern Python cannot compile would make its replacement verdict meaningless.
    for case in _load()["cases"]:
        re.compile(case["pattern"])
