"""Command-line interface for the pinned Unicode contract generator."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .core import ContractError
from .unicode_core import KOTLIN_OUTPUT_PATH, PYTHON_OUTPUT_PATH, check, refresh


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="generate pinned Unicode 15.1 Python and Kotlin validators"
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[3],
        help=argparse.SUPPRESS,
    )
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--check", action="store_true")
    actions.add_argument("--refresh", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.check:
            check(args.repo_root)
            print(f"Unicode contract check OK: {PYTHON_OUTPUT_PATH}, {KOTLIN_OUTPUT_PATH}")
        else:
            refresh(args.repo_root)
            print(f"Unicode contract refreshed: {PYTHON_OUTPUT_PATH}, {KOTLIN_OUTPUT_PATH}")
    except ContractError as exc:
        print(f"Unicode contract generation failed: {exc}", file=sys.stderr)
        return 1
    return 0
