"""Shared helpers for the tests of languages whose engine reads downloaded data.

Nothing here touches the network. The archives a catalog pins come from the
local cache ``tools/language-data/fetch_language_data.py`` fills
(``$ANKI_MINER_LANGUAGE_DATA_CACHE``, else
``$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/language-data``); a test that needs one which
is not cached skips and names the command that caches it.

- :func:`language_data_home` points the engine and the bridge at a scratch
  ``ANKI_MINER_HOME``, so an install never leaks into the session's home.
- :func:`install_language_data` installs a language's pinned data through the
  real ``resource.languagedata.install`` op (digest, code-member filter,
  vendored extractor).
- :func:`install_sentinels` writes small stand-ins at each sentinel, for checks
  that only need the component to look installed.
- :func:`assert_tagger_matches` compares the Android tagger with an
  ``export_spacy_tokens.py`` fixture, sentence by sentence.
"""

from __future__ import annotations

import json
import os
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import pytest

CACHE_ENV = "ANKI_MINER_LANGUAGE_DATA_CACHE"
#: Cache file names, as ``fetch_language_data.cache_name`` writes them.
_EXTENSIONS = {"wheel": "whl", "zip": "zip"}


def cache_dir() -> Path | None:
    if os.environ.get(CACHE_ENV):
        return Path(os.environ[CACHE_ENV])
    toolchain = os.environ.get("ANKI_MINER_ANDROID_TOOLCHAIN_ROOT")
    return Path(toolchain) / "language-data" if toolchain else None


def language_data_entries(code: str) -> list[Any]:
    from android_bridge.resource_catalog import load_resource_catalog

    return list(load_resource_catalog(code).language_data)


def cached_archive(entry: Any) -> Path | None:
    """The cached archive of a ``LanguageDataResource``, or None."""

    root = cache_dir()
    if root is None:
        return None
    path = root / f"{entry.archive.sha256}.{_EXTENSIONS[entry.archive.format]}"
    return path if path.is_file() else None


@contextmanager
def language_data_home(home: Path) -> Iterator[Path]:
    """Use *home* as ``ANKI_MINER_HOME`` for the engine and the bridge inside the block."""

    import android_bridge.language_data as language_data
    from anki_miner.config import paths
    from anki_miner.languages import tagger_provider

    home.mkdir(parents=True, exist_ok=True)
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(paths, "ANKI_MINER_HOME", home)
        patch.setattr(language_data, "require_initialized", lambda: str(home))
        try:
            yield home
        finally:
            # A tagger built from this home must not outlive it.
            for code in list(tagger_provider._TAGGERS):
                tagger_provider.evict(code)


def install_language_data(code: str, home: Path) -> list[Path]:
    """Install every pinned ``language-data`` entry of *code* into *home*; skip when one is not cached."""

    from android_bridge import boundary
    from android_bridge.protocol import decode_envelope, encode_message

    installed = []
    for entry in language_data_entries(code):
        archive = cached_archive(entry)
        if archive is None:
            pytest.skip(f"{entry.resource_id} is not cached: run tools/language-data/fetch_language_data.py {code}")
        message = decode_envelope(
            boundary.dispatch(
                encode_message(
                    "resource.languagedata.install",
                    {
                        "operationId": f"test-{entry.resource_id}",
                        "resourceId": entry.resource_id,
                        "archivePath": str(archive),
                    },
                )
            )
        )
        assert message.message_type == "resource.languagedata.installed", message.payload
        installed.append(home / "language_packs" / code / entry.import_name)
    return installed


def install_sentinels(code: str, home: Path) -> None:
    """Write a small file at every sentinel of *code*'s pinned data, as an extraction would leave them."""

    for entry in language_data_entries(code):
        component = home / "language_packs" / code / entry.import_name
        for name in entry.install.sentinels:
            path = component / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"fixture {name}\n", encoding="utf-8")


def token_row(token: Any) -> list[str]:
    """A tagger token as ``export_spacy_tokens.py`` records it."""

    feature = token.feature
    return [token.surface, feature.pos1, feature.pos2, feature.lemma, feature.kana, getattr(token, "morph", "")]


def read_token_fixture(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip() and not line.startswith("#")]


def assert_tagger_matches(code: str, fixture: Path) -> int:
    """Tag every fixture sentence with the Android tagger; each must equal desktop's tokens. Returns the count."""

    from anki_miner.languages.tagger_provider import get_tagger

    rows = read_token_fixture(fixture)
    assert rows, f"{fixture} holds no sentences"
    tagger = get_tagger(code)
    mismatched = [row["id"] for row in rows if [token_row(token) for token in tagger(row["sentence"])] != row["tokens"]]
    assert not mismatched, f"{code}: {len(mismatched)} of {len(rows)} sentences differ from desktop: {mismatched[:5]}"
    return len(rows)
