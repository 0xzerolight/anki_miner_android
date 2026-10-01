"""Dutch: catalog completeness; the per-language fan-out replaces this module with the full set."""

from __future__ import annotations

from pathlib import Path

import pytest
from catalog_completeness import PENDING_PIN, assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "nl",
        pinned={},
        excluded={
            "wty-nl-en": PENDING_PIN,
            "opensubtitles-nl": PENDING_PIN,
        },
    )
