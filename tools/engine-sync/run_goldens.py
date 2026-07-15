#!/usr/bin/env python3
"""Derive and validate desktop goldens in an isolated subprocess."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from engine_sync.golden_contract import (
    GoldenContractError,
    default_python,
    locked_revision,
    parse_assets,
    run_exporter,
)


def _parser(script_root: Path, project_root: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-root", type=Path, required=True)
    parser.add_argument("--exporter", type=Path, required=True)
    parser.add_argument("--python", type=Path, default=default_python())
    parser.add_argument(
        "--corpus",
        type=Path,
        default=project_root / "golden/corpus/tokenizer-v1.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=project_root / "golden/engine-v1.json",
    )
    parser.add_argument("--lock", type=Path, default=script_root / "engine.lock")
    parser.add_argument("--dicdir", type=Path)
    parser.add_argument("--asset", action="append", default=[], metavar="NAME=PATH")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=600)
    return parser


def main(argv: list[str] | None = None) -> int:
    script_root = Path(__file__).resolve().parent
    project_root = script_root.parents[1]
    args = _parser(script_root, project_root).parse_args(argv)
    try:
        assets = parse_assets(args.asset)
        matched = run_exporter(
            python=args.python,
            exporter_path=args.exporter,
            engine_root=args.engine_root,
            expected_revision=locked_revision(args.lock),
            corpus_path=args.corpus,
            output_path=args.output,
            assets=assets,
            dicdir=args.dicdir,
            check=args.check,
            timeout_seconds=args.timeout_seconds,
        )
    except GoldenContractError as exc:
        print(f"golden contract error: {exc}", file=sys.stderr)
        return 2
    if args.check and not matched:
        print(f"golden fixture drift detected: {args.output}", file=sys.stderr)
        return 1
    action = "matches" if args.check else "wrote"
    print(f"golden fixture {action} {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
