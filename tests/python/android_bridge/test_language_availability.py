"""Language availability on Android, answered without ``find_spec`` on a bundled engine (AU-009).

Chaquopy's importer answers ``find_spec`` on a top-level package by extracting every data file of
it into ``filesDir``. The bridge lists what the APK bundles instead and checks downloaded data on
disk, so ``language.profiles`` writes nothing. Tests that build a profile need the runtime lane.
"""

from __future__ import annotations

import importlib.util
import json
import logging
import sys
from collections.abc import Iterator
from pathlib import Path

import pytest
from android_bridge import languages


def _runtime_lane() -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane")


@pytest.fixture(autouse=True)
def _fresh_listing(initialized_bridge_home: Path) -> Iterator[None]:
    languages.bundled_modules.cache_clear()
    yield
    languages.bundled_modules.cache_clear()


class _ExtractingFinder:
    """Chaquopy's ``AssetFinder`` in miniature: listing reads its index, ``find_spec`` extracts."""

    def __init__(self) -> None:
        self.extracted: list[str] = []

    def iter_modules(self, prefix: str = "") -> Iterator[tuple[str, bool]]:
        yield f"{prefix}bundled_engine", True

    def find_spec(self, name: str, target: object = None) -> None:
        self.extracted.append(name)
        return None


def test_the_bundled_listing_reads_the_importer_index_without_locating_anything(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    finder = _ExtractingFinder()
    entry = str(tmp_path / "requirements")
    monkeypatch.syspath_prepend(entry)
    monkeypatch.setitem(sys.path_importer_cache, entry, finder)

    assert "bundled_engine" in languages.bundled_modules()
    assert finder.extracted == []


def test_listing_profiles_never_locates_a_top_level_module(monkeypatch: pytest.MonkeyPatch) -> None:
    """The vendored probes reach ``find_spec`` through module-level bindings: spy on every one."""
    _runtime_lane()
    real = importlib.util.find_spec
    located: list[str] = []

    def spy(name: str, package: str | None = None):
        located.append(name)
        return real(name, package)

    for module in list(sys.modules.values()):
        if module is not importlib.util and getattr(module, "find_spec", None) is real:
            monkeypatch.setattr(module, "find_spec", spy)
    monkeypatch.setattr(importlib.util, "find_spec", spy)

    languages.language_profiles({})

    # The manifest lookups (anki_miner.languages.<code>[.pack]) remain; a dotted name never extracts.
    assert [name for name in located if "." not in name] == []


def test_a_storage_error_reading_one_language_drops_only_that_language(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    _runtime_lane()
    from anki_miner.services import language_pack_installer

    real = language_pack_installer.component_path

    def component_path(code: str, import_name: str) -> Path | None:
        if code == "ar":
            raise OSError(28, "No space left on device")
        return real(code, import_name)

    monkeypatch.setattr(language_pack_installer, "component_path", component_path)
    with caplog.at_level(logging.WARNING, logger=languages.logger.name):
        response = json.loads(languages.language_profiles({}))

    assert response["type"] == "language.profiles.result"
    codes = {entry["code"] for entry in response["payload"]["profiles"]}
    assert "ar" not in codes
    assert {"ja", "fa", "ko", "th"} <= codes
    assert "language_profile_unavailable outcome=skip language=ar" in caplog.text


def test_the_bundled_ladder_agrees_with_every_engine_probe(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The ladder stands in for each profile's probe, so the two agree while nothing is downloaded.

    Split models (vi, yue) are outside both: the bridge checks them on top of the ladder.
    """
    _runtime_lane()
    from anki_miner.config import paths
    from anki_miner.languages.registry import available_languages

    monkeypatch.setattr(paths, "ANKI_MINER_HOME", tmp_path)
    for code in available_languages():
        profile = languages.get_profile(code)
        engine_ok = profile.unavailable_reason is None or profile.unavailable_reason() is None
        missing = languages._missing_components(code)
        assert engine_ok == (not missing), (code, missing)
