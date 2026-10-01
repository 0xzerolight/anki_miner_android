"""Vietnamese: catalog completeness; the dictionary and frequency pins land with the D4 fan-out."""

from __future__ import annotations

from pathlib import Path

import pytest
from catalog_completeness import PENDING_PIN, assert_catalog_complete
from split_models import split_models_home

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "vi",
        pinned={},
        excluded={
            "wty-vi-en": PENDING_PIN,
            "opensubtitles-vi-word": PENDING_PIN,
        },
    )


def test_the_tagger_reads_the_downloaded_models(
    initialized_bridge_home: Path, monkeypatch: pytest.MonkeyPatch, tmp_path_factory: pytest.TempPathFactory
) -> None:
    del initialized_bridge_home
    split_models_home("vi", monkeypatch, tmp_path_factory)
    from android_bridge.languages import get_profile, unavailable_reason_code
    from anki_miner.languages.tagger_provider import get_tagger

    profile = get_profile("vi")
    assert unavailable_reason_code(profile) is None
    tokens = get_tagger("vi").parse(profile.smoke_sentence)

    # Desktop's tokenizer at the engine.lock SHA (export_desktop_tokens.py) on the same sentence.
    assert [(token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma) for token in tokens] == [
        ("Hôm nay", "N", "", "hôm nay"),
        ("trời", "N", "", "trời"),
        ("đẹp", "A", "", "đẹp"),
        ("quá", "R", "", "quá"),
        (".", "CH", "", "."),
    ]
