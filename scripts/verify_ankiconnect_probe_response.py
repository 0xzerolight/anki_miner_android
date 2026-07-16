#!/usr/bin/env python3
"""Validate the bounded API and media evidence from the fallback probe."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any


class ResponseError(RuntimeError):
    pass


MEDIA_FILENAME = "anki_miner_fallback_probe.png"
MEDIA_RESULT_RE = re.compile(r"^anki_miner_fallback_probe[A-Za-z0-9._-]*\.png$")
MEDIA_SHA256 = "255cdba9c8b77dd94f2dd21a0a6b3cc76f32d5611ae97e32ef4ee64ed02f0a3c"
MEDIA_SIZE_BYTES = 68


def _load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ResponseError(f"cannot read {path.name}: {error}") from error
    if not isinstance(value, dict) or set(value) != {"result", "error"}:
        raise ResponseError(f"{path.name} has the wrong envelope")
    if value["error"] is not None:
        raise ResponseError(f"{path.name} reported an API error")
    return value


def stored_filename(store_path: Path) -> str:
    stored = _load(store_path)
    actual_filename = stored["result"]
    if (
        not isinstance(actual_filename, str)
        or len(actual_filename.encode("utf-8")) > 255
        or MEDIA_RESULT_RE.fullmatch(actual_filename) is None
        or actual_filename == MEDIA_FILENAME
    ):
        raise ResponseError(
            "fallback storeMediaFile did not return a safe authoritative randomized PNG name"
        )
    return actual_filename


def validate(
    version_path: Path,
    decks_path: Path,
    store_path: Path,
    readback_path: Path,
) -> str:
    version = _load(version_path)
    decks = _load(decks_path)
    if version["result"] != 6 or isinstance(version["result"], bool):
        raise ResponseError("fallback API version is not exactly 6")
    names = decks["result"]
    if (
        not isinstance(names, list)
        or not names
        or any(not isinstance(name, str) or not name for name in names)
        or len(names) != len(set(names))
    ):
        raise ResponseError("fallback deckNames result is empty or malformed")
    actual_filename = stored_filename(store_path)
    try:
        readback = readback_path.read_bytes()
    except OSError as error:
        raise ResponseError(f"cannot read media readback: {error}") from error
    if len(readback) != MEDIA_SIZE_BYTES or hashlib.sha256(readback).hexdigest() != MEDIA_SHA256:
        raise ResponseError("stored fallback media differs from the deterministic payload")
    return actual_filename


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", type=Path)
    parser.add_argument("--decks", type=Path)
    parser.add_argument("--store-media", type=Path)
    parser.add_argument("--media-readback", type=Path)
    parser.add_argument("--print-stored-filename", action="store_true")
    args = parser.parse_args()
    try:
        if args.print_stored_filename:
            if args.store_media is None or any(
                value is not None for value in (args.version, args.decks, args.media_readback)
            ):
                parser.error("--print-stored-filename requires only --store-media")
            actual_filename = stored_filename(args.store_media)
        else:
            if any(
                value is None
                for value in (args.version, args.decks, args.store_media, args.media_readback)
            ):
                parser.error("full validation requires version, decks, store-media, and media-readback")
            actual_filename = validate(
                args.version,
                args.decks,
                args.store_media,
                args.media_readback,
            )
    except ResponseError as error:
        print(f"Ankiconnect probe response: {error}", file=sys.stderr)
        return 1
    print(actual_filename)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
