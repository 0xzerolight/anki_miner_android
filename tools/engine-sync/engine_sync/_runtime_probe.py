"""Measure the selected exporter's interpreter and dependency bytes independently."""

from __future__ import annotations

import argparse
import hashlib
import importlib
import importlib.metadata
import json
import os
import platform
import stat
import sys
from collections.abc import Mapping
from pathlib import Path


DISTRIBUTION_IMPORTS: Mapping[str, tuple[str, ...]] = {
    "fugashi": ("fugashi",),
    "unidic-lite": ("unidic_lite",),
    "pysubs2": ("pysubs2",),
    "requests": ("requests",),
    "Pillow": ("PIL",),
    "lxml": ("lxml",),
    "charset-normalizer": ("charset_normalizer",),
    "certifi": ("certifi",),
    "idna": ("idna",),
    "urllib3": ("urllib3",),
    "PyQt6": ("PyQt6.QtCore",),
    "PyQt6-Qt6": (),
    "PyQt6-sip": ("PyQt6.sip",),
}
MUTABLE_DISTRIBUTION_METADATA = frozenset(
    {"INSTALLER", "RECORD", "REQUESTED", "direct_url.json"}
)


def _fail(message: str) -> int:
    print(f"golden runtime probe error: {message}", file=sys.stderr)
    return 96


def _sha256_file(path: Path) -> str:
    file_stat = path.lstat()
    if stat.S_ISLNK(file_stat.st_mode) or not stat.S_ISREG(file_stat.st_mode):
        raise ValueError(f"runtime distribution path is not a regular file: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _distribution_file_map(
    distribution: importlib.metadata.Distribution,
) -> dict[str, Path]:
    files = distribution.files
    if files is None:
        raise ValueError(
            f"runtime distribution has no file manifest: {distribution.metadata['Name']}"
        )
    resolved: dict[str, Path] = {}
    for entry in files:
        logical_path = Path(str(entry))
        if ".." in logical_path.parts:
            continue
        if "__pycache__" in logical_path.parts or logical_path.name.endswith(
            (".pyc", ".pyo")
        ):
            continue
        if (
            logical_path.name in MUTABLE_DISTRIBUTION_METADATA
            and logical_path.parent.name.endswith(".dist-info")
        ):
            continue
        resolved[logical_path.as_posix()] = Path(
            str(distribution.locate_file(entry))
        ).resolve()
    if not resolved:
        raise ValueError(
            f"runtime distribution has no stable content files: {distribution.metadata['Name']}"
        )
    return resolved


def _assert_import_origin(import_name: str, distribution_files: Mapping[str, Path]) -> None:
    module = importlib.import_module(import_name)
    raw_file = getattr(module, "__file__", None)
    if raw_file is None:
        raise ValueError(f"runtime import has no verifiable file origin: {import_name}")
    if Path(raw_file).resolve() not in distribution_files.values():
        raise ValueError(
            f"runtime import is not owned by its declared distribution: {import_name}"
        )


def _sha256_named_files(files: Mapping[str, Path]) -> str:
    digest = hashlib.sha256()
    for logical_name, path in sorted(files.items()):
        encoded_name = logical_name.encode("utf-8")
        content_sha256 = bytes.fromhex(_sha256_file(path))
        digest.update(len(encoded_name).to_bytes(8, "big"))
        digest.update(encoded_name)
        digest.update(content_sha256)
    return digest.hexdigest()


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

    dependencies: dict[str, dict[str, str]] = {}
    try:
        for distribution_name in args.distribution:
            import_names = DISTRIBUTION_IMPORTS.get(distribution_name)
            if import_names is None:
                return _fail(
                    f"runtime distribution has no frozen import mapping: {distribution_name}"
                )
            distribution = importlib.metadata.distribution(distribution_name)
            distribution_files = _distribution_file_map(distribution)
            for import_name in import_names:
                _assert_import_origin(import_name, distribution_files)
            dependencies[distribution_name] = {
                "version": distribution.version,
                "content_sha256": _sha256_named_files(distribution_files),
            }
    except (ImportError, importlib.metadata.PackageNotFoundError, OSError, ValueError) as exc:
        return _fail(str(exc))

    if args.dicdir is None:
        try:
            import unidic_lite  # type: ignore[import-untyped]
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
