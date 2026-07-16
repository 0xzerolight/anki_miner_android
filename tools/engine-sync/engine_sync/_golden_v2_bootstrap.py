"""Minimal isolated launcher for the desktop v2 exporter's sibling modules."""

from __future__ import annotations

import os
from pathlib import Path
import runpy
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print("golden v2 bootstrap: exporter argument is missing", file=sys.stderr)
        return 97
    if os.environ.get("PYTHONHASHSEED") != "0" or sys.flags.hash_randomization != 0:
        print("golden v2 bootstrap: hash seed is not frozen", file=sys.stderr)
        return 97
    if not sys.flags.no_user_site or not sys.flags.safe_path or not sys.dont_write_bytecode:
        print("golden v2 bootstrap: interpreter isolation flags are incomplete", file=sys.stderr)
        return 97
    exporter = Path(sys.argv[1]).resolve()
    if not exporter.is_file():
        print("golden v2 bootstrap: exporter is missing", file=sys.stderr)
        return 97
    sys.path.insert(0, str(exporter.parent))
    sys.argv = [str(exporter), *sys.argv[2:]]
    try:
        runpy.run_path(str(exporter), run_name="__main__")
    except SystemExit as exc:
        if exc.code is None:
            return 0
        if isinstance(exc.code, int):
            return exc.code
        print(exc.code, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
