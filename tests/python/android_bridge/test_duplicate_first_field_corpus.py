"""Re-derive the duplicate-key parity corpus against the vendored ``_strip_for_dedup`` (AU-001).

The committed corpus is the oracle for the Kotlin ``DuplicateFirstFieldParityTest``. Compared only
against itself it would prove nothing, so every recorded key is rebuilt here from the live engine,
and the every-format-character case must hold exactly this interpreter's Cf set.
"""

from __future__ import annotations

import json
import sys
import unicodedata
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS = REPO_ROOT / "app/src/test/resources/contracts/duplicate_first_field_v1.json"


def _load() -> dict:
    return json.loads(CORPUS.read_text(encoding="utf-8"))


def test_corpus_is_well_formed() -> None:
    document = _load()
    assert document["schema_version"] == 1
    cases = document["cases"]
    names = [case["name"] for case in cases]
    assert len(names) == len(set(names)), "case names must be unique"
    assert len(cases) >= 25
    assert any(case["value"] != case["key"] for case in cases)


@pytest.mark.parametrize("case", _load()["cases"], ids=lambda case: case["name"])
def test_recorded_key_matches_the_vendored_engine(case: dict, initialized_bridge_home: Path) -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane: anki_miner.services imports the subtitle parser")
    from anki_miner.services.anki_note_builder import _strip_for_dedup

    assert _strip_for_dedup(case["value"]) == case["key"]


def test_every_format_character_case_holds_this_interpreters_cf_set() -> None:
    case = next(case for case in _load()["cases"] if case["name"] == "every-format-character")
    recorded = {ord(character) for character in case["value"][1:-1]}
    live = {code_point for code_point in range(sys.maxunicode + 1) if unicodedata.category(chr(code_point)) == "Cf"}
    assert recorded == live
    assert case["key"] == "ab"
