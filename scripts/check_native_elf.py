#!/usr/bin/env python3
"""Verify a raw native ELF before Android packaging."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from check_native_artifacts import ArtifactError, Inspection, MACHINE_ABIS, parse_elf


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elf", type=Path, required=True)
    parser.add_argument("--allow-abi", required=True, choices=sorted(MACHINE_ABIS.values()))
    parser.add_argument("--require-pie-cli", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inspection = Inspection({args.allow_abi}, ())
    try:
        if not args.elf.is_file():
            raise ArtifactError(f"ELF not found: {args.elf}")
        parse_elf(
            args.elf.read_bytes(),
            str(args.elf),
            inspection,
            require_pie_cli=args.require_pie_cli,
        )
        if inspection.found_abis != {args.allow_abi}:
            raise ArtifactError(f"found ABIs {sorted(inspection.found_abis)}, expected {args.allow_abi}")
    except (ArtifactError, OSError) as error:
        print(f"native ELF verification failed: {error}", file=sys.stderr)
        return 1
    print(f"native ELF OK: {args.elf} ({args.allow_abi})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
