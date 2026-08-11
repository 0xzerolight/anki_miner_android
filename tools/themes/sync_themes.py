#!/usr/bin/env python3
"""Synchronize vendored Android palettes from a local desktop Anki Miner checkout."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

REPO_ROOT = Path(__file__).resolve().parents[2]
PALETTES_DIRECTORY = REPO_ROOT / "tools/themes/palettes"
THEMES_LOCK_PATH = REPO_ROOT / "tools/themes/themes.lock"
DESKTOP_REPOSITORY_ENVIRONMENT_VARIABLE = "ANKI_MINER_DESKTOP_REPO"
DESKTOP_THEMES_RELATIVE_PATH = Path("anki_miner/gui/resources/styles/themes")
REVISION_PATTERN = re.compile(r"[0-9a-f]{40}")


class ThemeSyncError(ValueError):
    """The local desktop repository cannot provide palette provenance."""


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="synchronize vendored desktop theme palettes")
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument(
        "--sync",
        action="store_true",
        help="copy desktop palettes and record the desktop revision",
    )
    action.add_argument(
        "--check",
        action="store_true",
        help="report vendored palette drift without writing",
    )
    return parser


def _desktop_repository() -> Path:
    value = os.environ.get(DESKTOP_REPOSITORY_ENVIRONMENT_VARIABLE)
    if not value:
        raise ThemeSyncError(f"{DESKTOP_REPOSITORY_ENVIRONMENT_VARIABLE} is not set")
    repository = Path(value)
    themes_directory = repository / DESKTOP_THEMES_RELATIVE_PATH
    if not themes_directory.is_dir():
        raise ThemeSyncError(f"desktop themes directory does not exist: {themes_directory}")
    return repository


def _palette_paths(directory: Path) -> dict[str, Path]:
    return {path.name: path for path in sorted(directory.glob("*.json"))}


def _git(repository: Path, *arguments: str) -> bytes:
    result = subprocess.run(
        ["git", "--no-replace-objects", "-C", str(repository), *arguments],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise ThemeSyncError(detail or f"git {' '.join(arguments)} failed")
    return result.stdout


def _desktop_head(repository: Path) -> str:
    revision = _git(repository, "rev-parse", "HEAD").decode("ascii").strip()
    if not REVISION_PATTERN.fullmatch(revision):
        raise ThemeSyncError(f"invalid desktop repository HEAD: {revision!r}")
    return revision


def _require_clean(repository: Path) -> None:
    status = _git(
        repository,
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
    )
    if status:
        raise ThemeSyncError("desktop repository must be clean before theme sync")


def _locked_revision() -> str:
    revision = THEMES_LOCK_PATH.read_text(encoding="ascii").strip()
    if not REVISION_PATTERN.fullmatch(revision):
        raise ThemeSyncError(f"invalid theme revision lock: {revision!r}")
    return revision


def _palette_blobs(repository: Path, revision: str) -> dict[str, bytes]:
    prefix = PurePosixPath(DESKTOP_THEMES_RELATIVE_PATH.as_posix())
    output = _git(
        repository,
        "ls-tree",
        "-r",
        "--name-only",
        "-z",
        revision,
        "--",
        prefix.as_posix(),
    )
    paths = []
    for value in output.decode("utf-8").split("\0"):
        if not value:
            continue
        path = PurePosixPath(value)
        if path.parent == prefix and path.suffix == ".json":
            paths.append(path)
    if not paths:
        raise ThemeSyncError(f"no desktop palettes found at revision {revision}")

    return {path.name: _git(repository, "show", f"{revision}:{path.as_posix()}") for path in paths}


def sync(repository: Path) -> None:
    revision = _desktop_head(repository)
    _require_clean(repository)
    source_blobs = _palette_blobs(repository, revision)
    destination_paths = _palette_paths(PALETTES_DIRECTORY)

    PALETTES_DIRECTORY.mkdir(parents=True, exist_ok=True)
    for name in sorted(destination_paths.keys() - source_blobs.keys()):
        destination_paths[name].unlink()
    for name, content in source_blobs.items():
        (PALETTES_DIRECTORY / name).write_bytes(content)
    THEMES_LOCK_PATH.write_text(f"{revision}\n", encoding="ascii")


def check(repository: Path) -> list[str]:
    source_blobs = _palette_blobs(repository, _locked_revision())
    destination_paths = _palette_paths(PALETTES_DIRECTORY)
    drifted = []

    for name in sorted(source_blobs.keys() - destination_paths.keys()):
        drifted.append(f"missing vendored palette: {name}")
    for name in sorted(destination_paths.keys() - source_blobs.keys()):
        drifted.append(f"unexpected vendored palette: {name}")
    for name in sorted(source_blobs.keys() & destination_paths.keys()):
        if destination_paths[name].read_bytes() != source_blobs[name]:
            drifted.append(f"drifted vendored palette: {name}")
    return drifted


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        repository = _desktop_repository()
        if args.sync:
            sync(repository)
            print(f"Theme palettes synchronized from {repository}")
        else:
            drifted = check(repository)
            if drifted:
                print("Theme palette provenance check failed:", file=sys.stderr)
                for message in drifted:
                    print(message, file=sys.stderr)
                return 1
            print(f"Theme palette provenance check OK: {repository}")
    except (OSError, ThemeSyncError) as exc:
        print(f"Theme palette synchronization failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
