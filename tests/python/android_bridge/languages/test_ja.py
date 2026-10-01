"""Japanese: the catalog Android has pinned since v0.1, against desktop's recommended set."""

from __future__ import annotations

from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "ja",
        pinned={
            "jmdict-english": "jmdict-en-2026-07-17",
            "jpdb-freq": "jpdb-v2.2-kana-2024-10-13",
            "kanjium-pitch": "kanjium-pitch-8a0cdaa1",
        },
        excluded={
            "jiten": "served from an always-latest API endpoint; no immutable URL exists to SHA-pin",
        },
        android_only={
            "jitendex-2026.07.09.0": "desktop's legacy catalog dictionary (LEGACY_DICT_SLOT_IDS); kept pinned",
        },
    )
