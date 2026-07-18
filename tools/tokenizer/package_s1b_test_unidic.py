#!/usr/bin/env python3
"""Create a deterministic external UniDic ZIP for Android instrumentation."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import zipfile

from verify_s1b_host_parity import verify_dictionary_provenance

_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def _source_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for current, directory_names, file_names in os.walk(root, followlinks=False):
        current_path = Path(current)
        kept_directories: list[str] = []
        for name in sorted(directory_names):
            path = current_path / name
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
                raise RuntimeError(f"invalid UniDic directory entry: {path}")
            if name != "__pycache__":
                kept_directories.append(name)
        directory_names[:] = kept_directories
        for name in sorted(file_names):
            path = current_path / name
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
                raise RuntimeError(f"invalid UniDic file entry: {path}")
            if not name.endswith((".pyc", ".pyo")):
                files.append(path)
    return files


def _archive_tree_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if names != sorted(names) or len(names) != len(set(names)):
            raise RuntimeError("external UniDic ZIP entries are not canonical")
        for entry in entries:
            if entry.is_dir():
                raise RuntimeError("external UniDic ZIP must contain files only")
            relative = entry.filename.encode("utf-8")
            digest.update(len(relative).to_bytes(8, "big"))
            digest.update(relative)
            digest.update(entry.file_size.to_bytes(8, "big"))
            with archive.open(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
    return digest.hexdigest()


def package_dictionary(dicdir: Path, golden: Path, output: Path) -> str:
    document = json.loads(golden.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise RuntimeError("golden root is not an object")
    provenance_hash = verify_dictionary_provenance(dicdir, document)
    try:
        output.resolve().relative_to(dicdir.resolve())
    except ValueError:
        pass
    else:
        raise RuntimeError("test archive must be created outside the dictionary")

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.tmp")
    temporary.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(
            temporary,
            mode="w",
            compression=zipfile.ZIP_STORED,
            allowZip64=True,
        ) as archive:
            archive.comment = f"anki-miner-unidic-sha256={provenance_hash}".encode()
            for path in _source_files(dicdir):
                relative = path.relative_to(dicdir).as_posix()
                info = zipfile.ZipInfo(relative, date_time=_ZIP_TIMESTAMP)
                info.compress_type = zipfile.ZIP_STORED
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o444) << 16
                with path.open("rb") as source, archive.open(info, "w") as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
        packaged_hash = _archive_tree_sha256(temporary)
        if packaged_hash != provenance_hash:
            raise RuntimeError("dictionary changed while the external test ZIP was created")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return provenance_hash


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dicdir", type=Path, required=True)
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        digest = package_dictionary(
            args.dicdir.resolve(strict=True),
            args.golden.resolve(strict=True),
            args.output.resolve(),
        )
    except (OSError, RuntimeError, ValueError, zipfile.BadZipFile) as exc:
        print(f"package_s1b_test_unidic: {exc}", file=sys.stderr)
        return 1
    print(f"S1b external test UniDic: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
