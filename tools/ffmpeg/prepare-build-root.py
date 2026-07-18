#!/usr/bin/env python3
"""Safely recreate an FFmpeg build directory below one trusted parent."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import stat
import sys


class UnsafeBuildRoot(ValueError):
    pass


def _absolute_lexical(path: Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def prepare_build_root(allowed_parent: Path, requested_root: Path) -> Path:
    parent = _absolute_lexical(allowed_parent)
    if parent.is_symlink():
        raise UnsafeBuildRoot(f"allowed parent must not be a symlink: {parent}")
    parent.mkdir(parents=True, exist_ok=True)
    if not parent.is_dir():
        raise UnsafeBuildRoot(f"allowed parent is not a directory: {parent}")
    canonical_parent = parent.resolve(strict=True)

    requested = _absolute_lexical(requested_root)
    try:
        relative = requested.relative_to(parent)
    except ValueError as error:
        raise UnsafeBuildRoot(f"build root is outside {parent}: {requested}") from error
    if not relative.parts:
        raise UnsafeBuildRoot("build root must not be the allowed parent itself")

    current = parent
    for index, component in enumerate(relative.parts):
        current /= component
        try:
            mode = current.lstat().st_mode
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(mode):
            raise UnsafeBuildRoot(f"build root contains a symlink: {current}")
        if not stat.S_ISDIR(mode):
            location = "root" if index == len(relative.parts) - 1 else "parent"
            raise UnsafeBuildRoot(f"build {location} is not a directory: {current}")

    canonical_requested = requested.resolve(strict=False)
    try:
        canonical_relative = canonical_requested.relative_to(canonical_parent)
    except ValueError as error:
        raise UnsafeBuildRoot(f"canonical build root escapes {canonical_parent}: {canonical_requested}") from error
    if not canonical_relative.parts:
        raise UnsafeBuildRoot("canonical build root must not equal the allowed parent")

    if requested.exists():
        shutil.rmtree(requested)
    requested.mkdir(parents=True, exist_ok=False)
    return requested.resolve(strict=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--allowed-parent", type=Path, required=True)
    parser.add_argument("--build-root", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        prepared = prepare_build_root(args.allowed_parent, args.build_root)
    except (OSError, UnsafeBuildRoot) as error:
        print(f"Refusing unsafe ffmpeg build root: {error}", file=sys.stderr)
        return 2
    print(prepared)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
