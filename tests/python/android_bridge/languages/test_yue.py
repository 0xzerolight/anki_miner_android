"""Cantonese: catalog completeness; the dictionary and frequency pins land with the D4 fan-out."""

from __future__ import annotations

from pathlib import Path

import pytest
from catalog_completeness import PENDING_PIN, assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "yue",
        pinned={},
        excluded={
            "cc-canto": PENDING_PIN,
            "cc-cedict-canto": PENDING_PIN,
            "hkcancor-yue": PENDING_PIN,
            "wty-yue-en": PENDING_PIN,
        },
    )
