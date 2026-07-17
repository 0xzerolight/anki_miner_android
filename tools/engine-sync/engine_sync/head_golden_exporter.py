"""Materialize the desktop HEAD v2 exporter for a non-release drift warning."""

from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import stat
import subprocess


class HeadGoldenExporterError(RuntimeError):
    """Desktop HEAD cannot be isolated for semantic-drift reporting."""


_REVISION = re.compile(
    rb'(?m)^PINNED_ENGINE_REVISION = "[0-9a-f]{40}"$'
)
_SOURCE_FILES = (
    "dump_engine_goldens.py",
    "engine_golden_contract_v2.py",
    "prepare_golden_unidic.py",
)


def _git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", os.fspath(root), *arguments],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise HeadGoldenExporterError(
            f"git {' '.join(arguments)} failed: {detail}"
        )
    return result.stdout.strip()


def verify_desktop_root(root: Path) -> tuple[Path, str]:
    root = root.expanduser().absolute()
    if root.is_symlink():
        raise HeadGoldenExporterError("desktop root must not be a symlink")
    try:
        root = root.resolve(strict=True)
    except OSError as error:
        raise HeadGoldenExporterError(f"desktop root does not exist: {error}") from error
    if not root.is_dir():
        raise HeadGoldenExporterError("desktop root must be a directory")
    top = Path(_git(root, "rev-parse", "--show-toplevel")).resolve()
    if top != root:
        raise HeadGoldenExporterError("desktop root must be the Git top level")
    revision = _git(root, "rev-parse", "HEAD")
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        raise HeadGoldenExporterError("desktop HEAD is not an exact lowercase commit")
    if _git(root, "status", "--porcelain=v2", "--untracked-files=all"):
        raise HeadGoldenExporterError("desktop HEAD checkout must be clean")
    return root, revision


def _read_regular(path: Path) -> bytes:
    try:
        value = path.lstat()
        content = path.read_bytes()
    except OSError as error:
        raise HeadGoldenExporterError(f"cannot read desktop exporter input: {path}") from error
    if stat.S_ISLNK(value.st_mode) or not stat.S_ISREG(value.st_mode):
        raise HeadGoldenExporterError(f"desktop exporter input is not a regular file: {path}")
    return content


def materialize_desktop_head_exporter(
    desktop_root: Path,
    output_root: Path,
) -> tuple[Path, str]:
    """Copy HEAD's exporter and change only its explicit revision guard."""

    desktop_root, revision = verify_desktop_root(desktop_root)
    output_root = output_root.absolute()
    try:
        output_root.mkdir(parents=True, exist_ok=False)
    except OSError as error:
        raise HeadGoldenExporterError(f"cannot create exporter staging directory: {error}") from error
    scripts = output_root / "scripts"
    scripts.mkdir()
    for name in _SOURCE_FILES:
        content = _read_regular(desktop_root / "scripts" / name)
        if name == "engine_golden_contract_v2.py":
            replacement = f'PINNED_ENGINE_REVISION = "{revision}"'.encode("ascii")
            content, count = _REVISION.subn(replacement, content)
            if count != 1:
                raise HeadGoldenExporterError(
                    "desktop HEAD v2 exporter has no unique revision seam"
                )
        destination = scripts / name
        destination.write_bytes(content)
        os.chmod(destination, 0o644)
    schema_source = (
        desktop_root / "tests/fixtures/goldens/engine-v2.schema.json"
    )
    schema_destination = output_root / "tests/fixtures/goldens/engine-v2.schema.json"
    schema_destination.parent.mkdir(parents=True)
    schema_destination.write_bytes(_read_regular(schema_source))
    os.chmod(schema_destination, 0o644)
    return scripts / "dump_engine_goldens.py", revision
