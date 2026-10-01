"""Hebrew: the worked example of a per-language catalog, both desktop rows pinned."""

from __future__ import annotations

from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "he",
        pinned={
            "wty-he-en": "wty-he-en-2026.09.20",
            "opensubtitles-he": "opensubtitles-he-2018",
        },
        excluded={},
    )
