"""Isolated-process bootstrap which proves engine imports came from --engine-root."""

from __future__ import annotations

import os
import runpy
import sys
from pathlib import Path


def _fail(message: str) -> int:
    print(f"golden bootstrap error: {message}", file=sys.stderr)
    return 97


def main() -> int:
    if len(sys.argv) < 3:
        return _fail("expected EXPORTER ENGINE_ROOT followed by exporter arguments")
    if os.environ.get("PYTHONHASHSEED") != "0" or sys.flags.hash_randomization != 0:
        return _fail("PYTHONHASHSEED=0 was not applied at interpreter startup")
    if (
        sys.flags.ignore_environment
        or not sys.flags.no_user_site
        or not sys.flags.safe_path
        or not sys.dont_write_bytecode
    ):
        return _fail("interpreter isolation flags are incomplete")
    exporter = Path(sys.argv[1]).resolve()
    engine_root = Path(sys.argv[2]).resolve()
    engine_package = engine_root / "anki_miner"
    if not exporter.is_file() or not (engine_package / "__init__.py").is_file():
        return _fail("exporter or engine package is missing")

    for name in tuple(sys.modules):
        if name == "anki_miner" or name.startswith("anki_miner."):
            del sys.modules[name]
    sys.path.insert(0, str(engine_root))
    sys.argv = [str(exporter), *sys.argv[3:]]
    exit_code = 0
    try:
        runpy.run_path(str(exporter), run_name="__main__")
    except SystemExit as exc:
        if exc.code is None:
            exit_code = 0
        elif isinstance(exc.code, int):
            exit_code = exc.code
        else:
            print(exc.code, file=sys.stderr)
            exit_code = 1
    if exit_code != 0:
        return exit_code

    imported = False
    for name, module in tuple(sys.modules.items()):
        if name != "anki_miner" and not name.startswith("anki_miner."):
            continue
        module_file = getattr(module, "__file__", None)
        if module_file is None:
            continue
        imported = True
        resolved = Path(module_file).resolve()
        if not resolved.is_relative_to(engine_package):
            return _fail(f"{name} loaded outside --engine-root: {resolved}")
    if not imported:
        return _fail("exporter succeeded without importing the engine")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
