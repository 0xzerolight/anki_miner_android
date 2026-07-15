"""Isolated-process bootstrap which proves engine imports came from --engine-root."""

from __future__ import annotations

import os
import importlib.abc
import importlib.machinery
import importlib.util
import runpy
import sys
from collections.abc import Sequence
from pathlib import Path
from types import ModuleType


def _fail(message: str) -> int:
    print(f"golden bootstrap error: {message}", file=sys.stderr)
    return 97


class _RecordingLoader(importlib.abc.Loader):
    def __init__(
        self,
        loader: importlib.abc.Loader,
        engine_package: Path,
        loaded_names: set[str],
    ) -> None:
        self._loader = loader
        self._engine_package = engine_package
        self._loaded_names = loaded_names

    def create_module(
        self, spec: importlib.machinery.ModuleSpec
    ) -> ModuleType | None:
        create_module = getattr(self._loader, "create_module", None)
        return None if create_module is None else create_module(spec)

    def exec_module(self, module: ModuleType) -> None:
        self._loader.exec_module(module)
        module_file = getattr(module, "__file__", None)
        if module_file is None:
            raise ImportError(f"{module.__name__} has no verifiable import origin")
        resolved = Path(module_file).resolve()
        if not resolved.is_relative_to(self._engine_package):
            raise ImportError(
                f"{module.__name__} loaded outside --engine-root: {resolved}"
            )
        self._loaded_names.add(module.__name__)


class _RecordingFinder(importlib.abc.MetaPathFinder):
    def __init__(self, engine_package: Path, loaded_names: set[str]) -> None:
        self._engine_package = engine_package
        self._loaded_names = loaded_names

    def find_spec(
        self,
        fullname: str,
        path: Sequence[str] | None = None,
        target: ModuleType | None = None,
    ) -> importlib.machinery.ModuleSpec | None:
        if fullname != "anki_miner" and not fullname.startswith("anki_miner."):
            return None
        spec = importlib.machinery.PathFinder.find_spec(fullname, path, target)
        if spec is None or spec.loader is None:
            return spec
        if not isinstance(spec.loader, importlib.abc.Loader):
            raise ImportError(f"{fullname} has an unsupported loader")
        spec.loader = _RecordingLoader(
            spec.loader, self._engine_package, self._loaded_names
        )
        return spec


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
    loaded_names: set[str] = set()
    recording_finder = _RecordingFinder(engine_package, loaded_names)
    sys.meta_path.insert(0, recording_finder)
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

    if not loaded_names:
        return _fail("exporter succeeded without importing the engine")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
