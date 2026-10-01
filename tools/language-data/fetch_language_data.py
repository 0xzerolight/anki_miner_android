#!/usr/bin/env python3
"""Fetch the pinned language-data archives of some languages into a local cache.

The bridge tests and the token exporter never touch the network. They read the
archives a language's catalog pins (``resource_catalog/<code>.json``, kind
``language-data``) from this cache and skip when one is absent. A cached file is
named by its SHA-256, ``<sha256>.whl`` or ``<sha256>.zip``, and lands only after
its size and digest match the pin.

The cache is ``$ANKI_MINER_LANGUAGE_DATA_CACHE``, else
``$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/language-data``. ``--source-dir`` copies an
archive already on disk (found by its download file name) instead of fetching it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
import urllib.request
from collections.abc import Iterable
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
CATALOG_DIR = Path("app/src/main/python/android_bridge/resource_catalog")
CACHE_ENV = "ANKI_MINER_LANGUAGE_DATA_CACHE"
TOOLCHAIN_ENV = "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"
_EXTENSIONS = {"wheel": "whl", "zip": "zip"}
_CHUNK = 1024 * 1024


class FetchError(Exception):
    """An archive could not be fetched or does not match its pin."""


def cache_root(environ: dict[str, str] | None = None) -> Path:
    environ = dict(os.environ) if environ is None else environ
    if environ.get(CACHE_ENV):
        return Path(environ[CACHE_ENV])
    if environ.get(TOOLCHAIN_ENV):
        return Path(environ[TOOLCHAIN_ENV]) / "language-data"
    raise FetchError(f"set {CACHE_ENV}, or source scripts/android-env.sh for {TOOLCHAIN_ENV}")


def cache_name(archive: dict[str, Any]) -> str:
    """The cache file name of a catalog ``archive`` object."""

    return f"{archive['sha256']}.{_EXTENSIONS[archive['format']]}"


def language_data(repo: Path, code: str) -> list[dict[str, Any]]:
    """The ``language-data`` entries ``resource_catalog/<code>.json`` pins."""

    path = repo / CATALOG_DIR / f"{code}.json"
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise FetchError(f"cannot read {path}: {exc}") from exc
    return [entry for entry in catalog["resources"] if entry["kind"] == "language-data"]


def _verified_copy(source: Iterable[bytes], archive: dict[str, Any], target: Path) -> None:
    digest = hashlib.sha256()
    size = 0
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".partial-", delete=False) as stream:
        partial = Path(stream.name)
        try:
            for chunk in source:
                size += len(chunk)
                if size > archive["sizeBytes"]:
                    raise FetchError(f"{archive['url']} is larger than its pinned {archive['sizeBytes']} bytes")
                digest.update(chunk)
                stream.write(chunk)
        except BaseException:
            partial.unlink(missing_ok=True)
            raise
    if size != archive["sizeBytes"] or digest.hexdigest() != archive["sha256"]:
        partial.unlink(missing_ok=True)
        raise FetchError(f"{archive['url']} does not match its pinned size and SHA-256")
    partial.replace(target)


def _chunks(stream: Any) -> Iterable[bytes]:
    while chunk := stream.read(_CHUNK):
        yield chunk


def fetch(repo: Path, codes: Iterable[str], cache: Path, source_dir: Path | None = None) -> list[Path]:
    """Make every language-data archive of *codes* present in *cache*; return their paths."""

    paths = []
    for code in codes:
        for entry in language_data(repo, code):
            archive = entry["archive"]
            target = cache / cache_name(archive)
            if not target.is_file():
                local = None if source_dir is None else source_dir / archive["url"].rsplit("/", 1)[1]
                if local is not None and local.is_file():
                    with local.open("rb") as stream:
                        _verified_copy(_chunks(stream), archive, target)
                else:
                    with urllib.request.urlopen(
                        archive["url"], timeout=60
                    ) as response:  # noqa: S310 (pinned https URL)
                        _verified_copy(_chunks(response), archive, target)
            paths.append(target)
            print(f"{entry['resourceId']}: {target}")
    return paths


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("codes", nargs="+", metavar="CODE", help="mining language codes")
    parser.add_argument("--cache", type=Path, help="cache directory (default: see the module docstring)")
    parser.add_argument("--source-dir", type=Path, help="directory of already-downloaded archives to copy from")
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        cache = args.cache if args.cache is not None else cache_root()
        fetch(args.repo_root, args.codes, cache, args.source_dir)
    except (FetchError, OSError) as exc:
        print(f"fetch_language_data: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
