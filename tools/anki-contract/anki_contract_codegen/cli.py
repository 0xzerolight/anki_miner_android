"""Command-line interface for deterministic Anki limits generation."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .core import ContractError, GENERATED_KOTLIN_PATH, check, refresh


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="generate Kotlin constants from the strict Anki limits manifest"
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[3],
        help=argparse.SUPPRESS,
    )
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument(
        "--check",
        action="store_true",
        help="detect manifest or generated Kotlin drift without writing",
    )
    actions.add_argument(
        "--refresh",
        action="store_true",
        help="atomically regenerate the Kotlin constants",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.check:
            check(args.repo_root)
            print(f"Anki limits Kotlin check OK: {GENERATED_KOTLIN_PATH}")
        else:
            refresh(args.repo_root)
            print(f"Anki limits Kotlin refreshed: {GENERATED_KOTLIN_PATH}")
    except ContractError as exc:
        print(f"Anki limits generation failed: {exc}", file=sys.stderr)
        return 1
    return 0
