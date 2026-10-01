"""Every vendored mining language carries its own test module here."""

from __future__ import annotations

from pathlib import Path

import pytest

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_every_vendored_language_has_a_test_module(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.languages.registry import available_languages

    here = Path(__file__).parent
    missing = [code for code in available_languages() if not (here / f"test_{code}.py").is_file()]

    assert not missing, f"no tests/python/android_bridge/languages/test_<code>.py for {missing}"
