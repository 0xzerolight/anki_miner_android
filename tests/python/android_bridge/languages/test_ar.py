"""Arabic: catalog completeness; the dictionary and frequency pins land with C.7."""

from __future__ import annotations

from pathlib import Path

import pytest
from catalog_completeness import PENDING_PIN, assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "ar",
        pinned={},
        excluded={
            "wty-ar-en": PENDING_PIN,
            "opensubtitles-ar": PENDING_PIN,
        },
    )
