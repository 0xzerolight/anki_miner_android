"""Install the split models a language downloads (vi, yue) into a test home, offline.

On a device the models arrive as ``language-data`` (``languages.SPLIT_DATA_COMPONENTS``):
the bridge extracts the catalog entry's selection from the package's pinned PyPI
wheel into ``language_packs/<code>/<import name>/``, where the engine overrides
load them by path. The runtime lane installs those same upstream wheels
(``requirements-runtime-host-test.lock``), models included, so this copies the
catalog's selection out of the installed package instead of downloading it, and
checks every file against the catalog's inner SHA-256 (the yue lane wheel is the
x86_64 build of the pinned aarch64 one: the model bytes are the same).

Usage, from a test module in this directory::

    from split_models import split_models_home

    @pytest.fixture
    def models_home(initialized_bridge_home, monkeypatch, tmp_path_factory):
        del initialized_bridge_home
        return split_models_home("vi", monkeypatch, tmp_path_factory)

The models are copied once per session; each test points ``ANKI_MINER_HOME`` at
that home for its own duration only.
"""

from __future__ import annotations

import hashlib
import importlib.util
import shutil
from pathlib import Path

import pytest

_HOMES: dict[str, Path] = {}


def install_split_models(code: str, home: Path) -> Path:
    """Copy *code*'s split models from the installed upstream package into *home*; return their directory."""

    from android_bridge.languages import SPLIT_DATA_COMPONENTS
    from android_bridge.resource_catalog import load_resource_catalog

    (entry,) = [
        item for item in load_resource_catalog(code).language_data if (code, item.import_name) in SPLIT_DATA_COMPONENTS
    ]
    source = SPLIT_DATA_COMPONENTS[(code, entry.import_name)]
    spec = importlib.util.find_spec(source)
    if spec is None or not spec.submodule_search_locations:
        pytest.skip(f"runtime dependency lane: {source} is not installed")
    site = Path(next(iter(spec.submodule_search_locations))).parent
    digests = {digest.path: digest.sha256 for digest in entry.install.inner_sha256}
    target = home / "language_packs" / code / entry.import_name
    for relative in sorted({*entry.install.sentinels, *digests}):
        origin = site / (entry.install.member_prefix + relative)
        expected = digests.get(relative)
        if expected is not None:
            actual = hashlib.sha256(origin.read_bytes()).hexdigest()
            assert actual == expected, f"{origin} is not the pinned model ({actual})"
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, destination)
    return target


def split_models_home(code: str, monkeypatch: pytest.MonkeyPatch, tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A home holding *code*'s split models, set as ``ANKI_MINER_HOME`` for the calling test."""

    from anki_miner.config import paths

    home = _HOMES.get(code)
    if home is None:
        home = tmp_path_factory.mktemp(f"split-models-{code}")
        install_split_models(code, home)
        _HOMES[code] = home
    monkeypatch.setattr(paths, "ANKI_MINER_HOME", home)
    return home
