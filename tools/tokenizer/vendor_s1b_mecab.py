#!/usr/bin/env python3
"""Verify or reproduce the byte-pinned S1b MeCab runtime source subset."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
VENDOR_ROOT = PROJECT_ROOT / "third_party/mecab"
MANIFEST_PATH = VENDOR_ROOT / "source-manifest.json"


class VendorError(RuntimeError):
    pass


_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def _display(path: Path) -> str:
    try:
        return str(path.relative_to(PROJECT_ROOT))
    except ValueError:
        return str(path)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_manifest() -> dict[str, object]:
    value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    if value.get("schema_version") != 1:
        raise VendorError("unsupported source manifest schema")
    return value


def _files(manifest: dict[str, object]) -> dict[str, str]:
    value = manifest.get("files")
    if not isinstance(value, dict) or not value:
        raise VendorError("source manifest has no files")
    for name, digest in value.items():
        if (
            not isinstance(name, str)
            or Path(name).name != name
            or not isinstance(digest, str)
            or _SHA256_RE.fullmatch(digest) is None
        ):
            raise VendorError("source manifest files are malformed")
    return dict(sorted(value.items()))


def _verify_file(path: Path, expected: str) -> None:
    if not path.is_file() or path.is_symlink():
        raise VendorError(f"missing regular file: {_display(path)}")
    actual = _sha256(path)
    if actual != expected:
        raise VendorError(
            f"hash mismatch for {_display(path)}: {actual} != {expected}"
        )


def check_committed(manifest: dict[str, object]) -> None:
    files = _files(manifest)
    source_dir = VENDOR_ROOT / "src"
    actual_names = sorted(path.name for path in source_dir.iterdir() if path.is_file())
    if actual_names != list(files):
        raise VendorError("committed MeCab source set differs from the strict manifest")
    for name, digest in files.items():
        _verify_file(source_dir / name, digest)

    license_value = manifest.get("license")
    if not isinstance(license_value, dict):
        raise VendorError("source manifest license is malformed")
    path = license_value.get("path")
    digest = license_value.get("sha256")
    if (
        not isinstance(path, str)
        or Path(path).name != path
        or not isinstance(digest, str)
        or _SHA256_RE.fullmatch(digest) is None
    ):
        raise VendorError("source manifest license fields are malformed")
    _verify_file(VENDOR_ROOT / path, digest)


def _git_value(source: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(source), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.stdout.strip()


def reproduce(source: Path, manifest: dict[str, object]) -> None:
    upstream = manifest.get("upstream")
    if not isinstance(upstream, dict):
        raise VendorError("source manifest upstream is malformed")
    revision = upstream.get("revision")
    tag = upstream.get("tag")
    source_root = upstream.get("source_root")
    if not all(isinstance(value, str) for value in (revision, tag, source_root)):
        raise VendorError("source manifest upstream fields are malformed")
    if _git_value(source, "rev-parse", "HEAD") != revision:
        raise VendorError(f"source checkout is not pinned revision {revision}")
    tags = set(_git_value(source, "tag", "--points-at", "HEAD").splitlines())
    if tag not in tags:
        raise VendorError(f"source checkout is not tagged {tag}")

    source_dir = source / source_root
    for name, digest in _files(manifest).items():
        candidate = source_dir / name
        _verify_file(candidate, digest)
        shutil.copyfile(candidate, VENDOR_ROOT / "src" / name)

    license_value = manifest["license"]
    assert isinstance(license_value, dict)
    expected_license = license_value["sha256"]
    assert isinstance(expected_license, str)
    _verify_file(source / "LICENSE", expected_license)
    shutil.copyfile(source / "LICENSE", VENDOR_ROOT / "LICENSE")
    check_committed(manifest)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--source", type=Path)
    args = parser.parse_args(argv)
    if args.check == (args.source is not None):
        parser.error("choose exactly one of --check or --source")

    try:
        manifest = _load_manifest()
        if args.check:
            check_committed(manifest)
        else:
            reproduce(args.source.resolve(strict=True), manifest)
    except (OSError, subprocess.CalledProcessError, VendorError, json.JSONDecodeError) as exc:
        print(f"vendor_s1b_mecab: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
