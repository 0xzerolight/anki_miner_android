#!/usr/bin/env python3
"""Resolve one exact enabled/clickable UIAutomator node to its center coordinates."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


BOUNDS = re.compile(r"^\[(\d+),(\d+)]\[(\d+),(\d+)]$")


class TargetError(RuntimeError):
    pass


def center(path: Path, text: str) -> tuple[int, int]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise TargetError(f"cannot read UI hierarchy: {error}") from error
    matches = [
        node
        for node in root.iter("node")
        if node.get("text") == text
        and node.get("enabled") == "true"
        and node.get("clickable") == "true"
    ]
    if len(matches) != 1:
        raise TargetError(f"expected one enabled clickable {text!r} node; found {len(matches)}")
    match = BOUNDS.fullmatch(matches[0].get("bounds", ""))
    if match is None:
        raise TargetError("target node has malformed bounds")
    left, top, right, bottom = map(int, match.groups())
    if left < 0 or top < 0 or right <= left or bottom <= top:
        raise TargetError("target node has empty or inverted bounds")
    return ((left + right) // 2, (top + bottom) // 2)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("hierarchy", type=Path)
    parser.add_argument("--text", required=True)
    args = parser.parse_args()
    try:
        x, y = center(args.hierarchy, args.text)
    except TargetError as error:
        print(f"UI target: {error}", file=sys.stderr)
        return 1
    print(x, y)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
