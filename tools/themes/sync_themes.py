#!/usr/bin/env python3
"""Synchronize vendored Android palettes from a local desktop Anki Miner checkout."""

from __future__ import annotations

import argparse
import filecmp
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PALETTES_DIRECTORY = REPO_ROOT / "tools/themes/palettes"
THEMES_LOCK_PATH = REPO_ROOT / "tools/themes/themes.lock"
DESKTOP_REPOSITORY_ENVIRONMENT_VARIABLE = "ANKI_MINER_DESKTOP_REPO"
DESKTOP_THEMES_RELATIVE_PATH = Path("anki_miner/gui/resources/styles/themes")


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


def _desktop_head(repository: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or "git rev-parse HEAD failed"
        raise ThemeSyncError(f"cannot read desktop repository HEAD: {detail}")
    return result.stdout.strip()


def sync(repository: Path) -> None:
    source = repository / DESKTOP_THEMES_RELATIVE_PATH
    source_paths = _palette_paths(source)
    destination_paths = _palette_paths(PALETTES_DIRECTORY)

    PALETTES_DIRECTORY.mkdir(parents=True, exist_ok=True)
    for name in sorted(destination_paths.keys() - source_paths.keys()):
        destination_paths[name].unlink()
    for name, source_path in source_paths.items():
        shutil.copy2(source_path, PALETTES_DIRECTORY / name)
    THEMES_LOCK_PATH.write_text(f"{_desktop_head(repository)}\n", encoding="utf-8")


def check(repository: Path) -> list[str]:
    source_paths = _palette_paths(repository / DESKTOP_THEMES_RELATIVE_PATH)
    destination_paths = _palette_paths(PALETTES_DIRECTORY)
    drifted = []

    for name in sorted(source_paths.keys() - destination_paths.keys()):
        drifted.append(f"missing vendored palette: {name}")
    for name in sorted(destination_paths.keys() - source_paths.keys()):
        drifted.append(f"unexpected vendored palette: {name}")
    for name in sorted(source_paths.keys() & destination_paths.keys()):
        if not filecmp.cmp(source_paths[name], destination_paths[name], shallow=False):
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
