"""Command-line interface for deterministic engine synchronization."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .core import EngineSyncError, build_snapshot, check_destination, sync_destination


def _parser(script_root: Path, project_root: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-repo", type=Path, default=project_root.parent / "anki_miner"
    )
    parser.add_argument(
        "--destination", type=Path, default=project_root / "app/src/main/python"
    )
    parser.add_argument("--lock", type=Path, default=script_root / "engine.lock")
    parser.add_argument(
        "--composition", type=Path, default=script_root / "composition.toml"
    )
    parser.add_argument("--overrides", type=Path, default=script_root / "overrides")
    parser.add_argument(
        "--check",
        action="store_true",
        help="report drift without changing the destination",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="print the selected file paths and do not sync",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    script_root = Path(__file__).resolve().parents[1]
    project_root = script_root.parents[1]
    args = _parser(script_root, project_root).parse_args(argv)
    try:
        snapshot = build_snapshot(
            source_repo=args.source_repo.resolve(),
            lock_path=args.lock.resolve(),
            composition_path=args.composition.resolve(),
            overlays_path=args.overrides.resolve(),
        )
        if args.list:
            for path in snapshot.files:
                print(path)
            return 0
        if args.check:
            differences = check_destination(args.destination.resolve(), snapshot)
            if differences:
                print("engine vendor drift detected:", file=sys.stderr)
                for difference in differences:
                    print(f"  {difference}", file=sys.stderr)
                return 1
            print(f"engine vendor is synchronized at {snapshot.revision}")
            return 0
        sync_destination(args.destination.resolve(), snapshot)
        print(f"synchronized {len(snapshot.files)} files from {snapshot.revision}")
        return 0
    except EngineSyncError as exc:
        print(f"engine sync error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
