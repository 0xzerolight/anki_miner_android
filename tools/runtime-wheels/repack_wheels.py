#!/usr/bin/env python3
"""Repack pure-Python language-pack wheels with the desktop pack.py excludes.

Each entry in ``repacked-wheels.lock`` names a PyPI wheel that a desktop
``anki_miner/languages/<code>/pack.py`` component pins, together with that
component's ``member_prefix`` and ``exclude`` tuple. ``build`` downloads the
hash-locked wheel, drops the excluded members (and their RECORD rows), and
writes the result to ``app/wheels/common``. Every kept member is copied
uncompressed, so the output bytes depend on neither zlib nor the host. A
component with no excludes is copied verbatim. ``check`` verifies the
committed wheels offline.
"""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path
from typing import Any

TOOL_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOL_ROOT.parents[1]
LOCK = TOOL_ROOT / "repacked-wheels.lock"
DEFAULT_OUTPUT = REPO_ROOT / "app/wheels/common"
ENTRY_KEYS = {
    "exclude",
    "filename",
    "license",
    "member_prefix",
    "pack",
    "package",
    "repacked_sha256",
    "sha256",
    "url",
    "version",
}


class RepackError(RuntimeError):
    """A repacked wheel or its lock entry is inconsistent."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_lock(path: Path = LOCK) -> dict[str, dict[str, Any]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schema") != 1 or set(document) != {"schema", "wheels"}:
        raise RepackError(f"{path.name}: expected schema 1 with only wheels")
    wheels = document["wheels"]
    if not isinstance(wheels, dict) or not wheels:
        raise RepackError(f"{path.name}: no wheels")
    for name, entry in wheels.items():
        if not isinstance(entry, dict) or set(entry) != ENTRY_KEYS:
            raise RepackError(f"{path.name}: entry {name} keys differ from the schema")
        if entry["package"] != name or not entry["url"].startswith("https://"):
            raise RepackError(f"{path.name}: entry {name} has an unsafe identity")
        if not entry["url"].endswith("/" + entry["filename"]):
            raise RepackError(f"{path.name}: entry {name} URL does not name its filename")
        if not entry["exclude"] and entry["repacked_sha256"] != entry["sha256"]:
            raise RepackError(f"{path.name}: entry {name} has no excludes, so it must stay verbatim")
    return wheels


def is_excluded(name: str, member_prefix: str, exclude: list[str]) -> bool:
    """Mirror desktop ``pack_installer._wanted``: a ``/`` entry is a subtree, others are exact."""
    if not name.startswith(member_prefix):
        return False
    relative = name[len(member_prefix) :]
    return any(relative.startswith(item) if item.endswith("/") else relative == item for item in exclude)


def _record_name(names: list[str]) -> str:
    records = [name for name in names if name.endswith(".dist-info/RECORD") and name.count("/") == 1]
    if len(records) != 1:
        raise RepackError(f"expected one top-level RECORD, found {len(records)}")
    return records[0]


def _record_path(line: str) -> str:
    return next(csv.reader([line]))[0]


def repack(source: bytes, entry: dict[str, Any]) -> bytes:
    """Return *source* without the members *entry* excludes."""
    exclude = list(entry["exclude"])
    if not exclude:
        return source
    prefix = entry["member_prefix"]
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        dropped = {name for name in names if is_excluded(name, prefix, exclude)}
        unmatched = [item for item in exclude if not any(is_excluded(name, prefix, [item]) for name in names)]
        if unmatched:
            raise RepackError(f"{entry['filename']}: exclude entries match nothing: {unmatched}")
        record_name = _record_name(names)
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as target:
            for info in infos:
                if info.filename in dropped:
                    continue
                data = archive.read(info)
                if info.filename == record_name:
                    lines = data.decode("utf-8").splitlines(keepends=True)
                    kept = [line for line in lines if _record_path(line) not in dropped]
                    if len(lines) - len(kept) != len(dropped):
                        raise RepackError(f"{entry['filename']}: RECORD does not list every excluded member")
                    data = "".join(kept).encode("utf-8")
                clone = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                clone.create_system = info.create_system
                clone.external_attr = info.external_attr
                clone.compress_type = zipfile.ZIP_STORED
                target.writestr(clone, data)
    return output.getvalue()


def _record_hash(data: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode("ascii")
    return f"sha256={digest}"


def verify_wheel(data: bytes, entry: dict[str, Any]) -> None:
    """Offline checks on a repacked (or verbatim) wheel."""
    if sha256_bytes(data) != entry["repacked_sha256"]:
        raise RepackError(f"{entry['filename']}: SHA-256 {sha256_bytes(data)} differs from the lock")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = [info.filename for info in archive.infolist() if not info.is_dir()]
        excluded = [name for name in names if is_excluded(name, entry["member_prefix"], entry["exclude"])]
        if excluded:
            raise RepackError(f"{entry['filename']}: excluded members are present: {excluded}")
        for member, expected in entry["license"]["members"].items():
            if member not in names or sha256_bytes(archive.read(member)) != expected:
                raise RepackError(f"{entry['filename']}: license member {member} is missing or changed")
        record_name = _record_name(names)
        rows = {}
        for line in archive.read(record_name).decode("utf-8").splitlines():
            path, digest, _size = next(csv.reader([line]))
            rows[path] = digest
        if set(rows) != set(names):
            raise RepackError(f"{entry['filename']}: RECORD and archive members differ")
        for name, digest in rows.items():
            if name != record_name and digest != _record_hash(archive.read(name)):
                raise RepackError(f"{entry['filename']}: RECORD hash differs for {name}")


def _download(entry: dict[str, Any], downloads: Path) -> bytes:
    path = downloads / entry["filename"]
    if not path.is_file():
        downloads.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(entry["url"], timeout=120) as response:  # noqa: S310 - https only, hash-checked
            path.write_bytes(response.read())
    data = path.read_bytes()
    if sha256_bytes(data) != entry["sha256"]:
        raise RepackError(f"{entry['filename']}: downloaded SHA-256 differs from the lock")
    return data


def build(downloads: Path, output: Path) -> int:
    for entry in load_lock().values():
        data = repack(_download(entry, downloads), entry)
        verify_wheel(data, entry)
        output.mkdir(parents=True, exist_ok=True)
        (output / entry["filename"]).write_bytes(data)
        print(f"repack-wheels: {entry['filename']} {entry['repacked_sha256']}")
    return 0


def check(wheels_dir: Path) -> int:
    lock = load_lock()
    for entry in lock.values():
        path = wheels_dir / entry["filename"]
        if not path.is_file():
            raise RepackError(f"missing repacked wheel: {path}")
        verify_wheel(path.read_bytes(), entry)
    print(f"repack-wheels: {len(lock)} wheels verified")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build")
    build_parser.add_argument("--downloads", type=Path, required=True)
    build_parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    check_parser = commands.add_parser("check")
    check_parser.add_argument("--wheels-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            return build(args.downloads, args.output)
        return check(args.wheels_dir)
    except (OSError, RepackError, zipfile.BadZipFile) as error:
        print(f"repack-wheels: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
