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

``tokens_without_packaged_models`` runs the tagger in a fresh interpreter whose
package lacks those models, as the APK ships it, to prove the engine override
still redirects the package to the downloaded ones.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

_HOMES: dict[str, Path] = {}
_PYTHON_ROOT = Path(__file__).resolve().parents[4] / "app" / "src" / "main" / "python"


def _split_selection(code: str) -> tuple[Any, str, Path, dict[str, str]]:
    """*code*'s split catalog entry, the package it comes from, that package's site directory and the model digests."""

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
    return entry, source, site, digests


def install_split_models(code: str, home: Path) -> Path:
    """Copy *code*'s split models from the installed upstream package into *home*; return their directory."""

    entry, _, site, digests = _split_selection(code)
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


#: Run in a fresh interpreter: argv is the model-less packages directory, the bridge's
#: Python root, the home, the language code, the source package and the sentence.
_RUN_WITHOUT_PACKAGED_MODELS = """
import importlib
import json
import sys
from pathlib import Path

packages, python_root, home, code, source, sentence = sys.argv[1:]
sys.path[:0] = [packages, python_root]
from android_bridge.bootstrap import initialize

initialize(home)
module = importlib.import_module(source)
assert Path(module.__file__).parent == Path(packages, source), module.__file__
from anki_miner.languages.tagger_provider import get_tagger

tokens = get_tagger(code).parse(sentence)
print(json.dumps([[t.surface, t.feature.pos1, t.feature.pos2, t.feature.lemma] for t in tokens]))
"""


def tokens_without_packaged_models(code: str, sentence: str, root: Path) -> list[list[str]]:
    """Tokenise *sentence* where *code*'s package lacks its split models, as the APK ships it.

    The installed package is mirrored into *root* by symlinks, leaving out every model
    file the language data provides, and those files exist only under the home's
    ``language_packs/``. If the engine override stops redirecting the package to them
    (upstream renames the attribute it sets, or the override is dropped), the package
    reaches for its own model files, which are not there, and the run fails.
    """

    entry, source, site, digests = _split_selection(code)
    removed = {entry.install.member_prefix + relative for relative in {*entry.install.sentinels, *digests}}
    packages = root / "packages"
    for path in sorted((site / source).rglob("*")):
        relative = path.relative_to(site).as_posix()
        if path.is_dir() or "__pycache__" in path.parts or relative in removed:
            continue
        link = packages / relative
        link.parent.mkdir(parents=True, exist_ok=True)
        link.symlink_to(path)
    for relative in removed:
        assert (site / relative).is_file(), f"{relative} is not in the installed package"
        assert not (packages / relative).exists(), relative
    home = root / "home"
    install_split_models(code, home)
    environment = {key: value for key, value in os.environ.items() if key != "ANKI_MINER_HOME"}
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            _RUN_WITHOUT_PACKAGED_MODELS,
            str(packages),
            str(_PYTHON_ROOT),
            str(home),
            code,
            source,
            sentence,
        ],
        capture_output=True,
        text=True,
        env={**environment, "PYTHONDONTWRITEBYTECODE": "1"},
        check=False,
        timeout=300,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout.splitlines()[-1])
