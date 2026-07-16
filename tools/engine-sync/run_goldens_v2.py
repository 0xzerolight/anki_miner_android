#!/usr/bin/env python3
"""Re-derive the complete desktop golden contract and reject any byte drift."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from engine_sync.golden_contract_v2 import GoldenV2Error, derive_and_compare


def main(argv: list[str] | None = None) -> int:
    script_root = Path(__file__).resolve().parent
    project_root = script_root.parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", required=True, type=Path)
    parser.add_argument("--exporter", required=True, type=Path)
    parser.add_argument("--engine-root", required=True, type=Path)
    parser.add_argument("--dicdir", required=True, type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    args = parser.parse_args(argv)
    try:
        derive_and_compare(
            project_root=project_root,
            # A venv interpreter is commonly a symlink. Resolving it would silently
            # discard pyvenv.cfg and run the system interpreter instead.
            python=args.python.expanduser().absolute(),
            exporter=args.exporter.resolve(strict=True),
            engine_root=args.engine_root.resolve(strict=True),
            dicdir=args.dicdir.resolve(strict=True),
            timeout_seconds=args.timeout_seconds,
        )
    except (GoldenV2Error, OSError) as exc:
        print(f"golden v2 contract error: {exc}", file=sys.stderr)
        return 1
    print("golden v2 fixture matches the pinned desktop derivation")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
