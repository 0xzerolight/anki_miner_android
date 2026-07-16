#!/usr/bin/env python3
"""Validate the two bounded responses used by the Ankiconnect fallback probe."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any


class ResponseError(RuntimeError):
    pass


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


def validate(version_path: Path, decks_path: Path) -> None:
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", type=Path, required=True)
    parser.add_argument("--decks", type=Path, required=True)
    args = parser.parse_args()
    try:
        validate(args.version, args.decks)
    except ResponseError as error:
        print(f"Ankiconnect probe response: {error}", file=sys.stderr)
        return 1
    print("AnkiconnectAndroid fallback API responses: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
