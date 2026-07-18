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
_GIT_REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
_LICENSE_NAMES = frozenset({"mecab", "mecab_for_dart"})


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


def _licenses(manifest: dict[str, object]) -> dict[str, dict[str, object]]:
    value = manifest.get("licenses")
    if not isinstance(value, dict) or set(value) != _LICENSE_NAMES:
        raise VendorError("source manifest must pin both license domains")
    output: dict[str, dict[str, object]] = {}
    for name in sorted(value):
        record = value[name]
        if not isinstance(record, dict):
            raise VendorError(f"source manifest license {name!r} is malformed")
        path = record.get("path")
        digest = record.get("sha256")
        source = record.get("source")
        if (
            not isinstance(path, str)
            or Path(path).name != path
            or not isinstance(digest, str)
            or _SHA256_RE.fullmatch(digest) is None
            or record.get("spdx") != "BSD-3-Clause"
            or not isinstance(record.get("copyright"), str)
            or not isinstance(source, dict)
            or set(source) != {"path", "repository", "revision"}
            or not all(isinstance(source.get(key), str) for key in source)
            or _GIT_REVISION_RE.fullmatch(str(source.get("revision"))) is None
        ):
            raise VendorError(f"source manifest license {name!r} is malformed")
        output[name] = record
    return output


def _verify_file(path: Path, expected: str) -> None:
    if not path.is_file() or path.is_symlink():
        raise VendorError(f"missing regular file: {_display(path)}")
    actual = _sha256(path)
    if actual != expected:
        raise VendorError(f"hash mismatch for {_display(path)}: {actual} != {expected}")


def check_committed(manifest: dict[str, object]) -> None:
    files = _files(manifest)
    source_dir = VENDOR_ROOT / "src"
    actual_names = sorted(path.name for path in source_dir.iterdir() if path.is_file())
    if actual_names != list(files):
        raise VendorError("committed MeCab source set differs from the strict manifest")
    for name, digest in files.items():
        _verify_file(source_dir / name, digest)

    for record in _licenses(manifest).values():
        path = record["path"]
        license_digest = record["sha256"]
        assert isinstance(path, str) and isinstance(license_digest, str)
        _verify_file(VENDOR_ROOT / path, license_digest)


def _git_value(source: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(source), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.stdout.strip()


def _verify_license_source(
    checkout: Path,
    record: dict[str, object],
) -> Path:
    source = record["source"]
    digest = record["sha256"]
    assert isinstance(source, dict) and isinstance(digest, str)
    revision = source["revision"]
    relative = source["path"]
    assert isinstance(revision, str) and isinstance(relative, str)
    if _git_value(checkout, "rev-parse", "HEAD") != revision:
        raise VendorError(f"license source checkout is not pinned revision {revision}")
    path = checkout / relative
    _verify_file(path, digest)
    return path


def reproduce(
    source: Path,
    mecab_source: Path,
    manifest: dict[str, object],
) -> None:
    upstream = manifest.get("upstream")
    if not isinstance(upstream, dict):
        raise VendorError("source manifest upstream is malformed")
    revision = upstream.get("revision")
    tag = upstream.get("tag")
    source_root = upstream.get("source_root")
    if not isinstance(revision, str) or not isinstance(tag, str) or not isinstance(source_root, str):
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

    licenses = _licenses(manifest)
    wrapper_license = _verify_license_source(
        source,
        licenses["mecab_for_dart"],
    )
    original_license = _verify_license_source(mecab_source, licenses["mecab"])
    for source_path, record in (
        (wrapper_license, licenses["mecab_for_dart"]),
        (original_license, licenses["mecab"]),
    ):
        destination = record["path"]
        assert isinstance(destination, str)
        shutil.copyfile(source_path, VENDOR_ROOT / destination)
    check_committed(manifest)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--mecab-source", type=Path)
    args = parser.parse_args(argv)
    reproducing = args.source is not None or args.mecab_source is not None
    if args.check == reproducing or (reproducing and (args.source is None or args.mecab_source is None)):
        parser.error("choose --check, or both --source and --mecab-source")

    try:
        manifest = _load_manifest()
        if args.check:
            check_committed(manifest)
        else:
            reproduce(
                args.source.resolve(strict=True),
                args.mecab_source.resolve(strict=True),
                manifest,
            )
    except (
        OSError,
        subprocess.CalledProcessError,
        VendorError,
        json.JSONDecodeError,
    ) as exc:
        print(f"vendor_s1b_mecab: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
