"""Measure the selected exporter's interpreter and dependencies independently."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
import platform
import sys
from pathlib import Path


def _fail(message: str) -> int:
    print(f"golden runtime probe error: {message}", file=sys.stderr)
    return 96


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", action="append", default=[])
    parser.add_argument("--dicdir", type=Path)
    args = parser.parse_args()
    if os.environ.get("PYTHONHASHSEED") != "0" or sys.flags.hash_randomization != 0:
        return _fail("PYTHONHASHSEED=0 was not applied at interpreter startup")
    if (
        sys.flags.ignore_environment
        or not sys.flags.no_user_site
        or not sys.flags.safe_path
        or not sys.dont_write_bytecode
    ):
        return _fail("interpreter isolation flags are incomplete")

    dependencies: dict[str, str] = {}
    for distribution in args.distribution:
        try:
            dependencies[distribution] = importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            return _fail(f"runtime distribution is missing: {distribution}")
    if args.dicdir is None:
        try:
            import unidic_lite
        except ImportError:
            return _fail("unidic-lite is required when --dicdir is omitted")
        dicdir = Path(unidic_lite.DICDIR).resolve()
    else:
        dicdir = args.dicdir.expanduser().resolve()
    if not (dicdir / "sys.dic").is_file():
        return _fail(f"UniDic directory has no sys.dic: {dicdir}")

    payload = {
        "runtime": {
            "python_implementation": platform.python_implementation(),
            "python_version": platform.python_version(),
            "platform": f"{sys.platform}-{platform.machine().lower()}",
            "dependencies": dependencies,
        },
        "unidic_dicdir": str(dicdir),
        "hash_probe": hash("anki-miner-golden-seed"),
    }
    print(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
