#!/usr/bin/env python3
"""Generate and verify provenance for wheels committed under app/wheels."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from email.parser import BytesParser
from email.policy import compat32
from pathlib import Path
from typing import Any

TOOL_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOL_ROOT.parents[1]
DEFAULT_WHEELS_ROOT = REPO_ROOT / "app/wheels"
DEFAULT_MANIFEST = DEFAULT_WHEELS_ROOT / "manifest.json"
RUNTIME_SOURCE_LOCK = REPO_ROOT / "tools/runtime-wheels/sources.lock"
S1A_SOURCE_LOCK = TOOL_ROOT / "sources.lock"
ABIS = ("arm64-v8a", "common", "x86_64")
ENTRY_KEYS = {
    "abi",
    "filename",
    "license",
    "package",
    "path",
    "sha256",
    "source",
    "version",
}
NDK_REVISION = "28.2.13676358"
NDK_RELEASE_URL = "https://github.com/android/ndk/releases/tag/r28c"
NDK_LIBCXX_PATHS = {
    "arm64-v8a": "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
    "x86_64": "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so",
}
S1A_SOURCE_RULES = {
    "chaquopy-libmecab": ("0.996", "mecab", "BSD-3-Clause"),
    "fugashi": ("1.5.2", "fugashi", "MIT AND BSD-3-Clause"),
}


class ManifestError(RuntimeError):
    """Raised when vendored wheel provenance is incomplete or stale."""


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ManifestError(f"cannot read JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise ManifestError(f"JSON root must be an object: {path}")
    return value


def _source_entries(path: Path) -> dict[str, dict[str, Any]]:
    entries = _load_json(path).get("sources")
    if not isinstance(entries, dict):
        raise ManifestError(f"source lock has no sources object: {path}")
    if not all(isinstance(name, str) and isinstance(entry, dict) for name, entry in entries.items()):
        raise ManifestError(f"source lock contains an invalid entry: {path}")
    return entries


def _normalize_package(value: str) -> str:
    return value.casefold().replace("_", "-")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _wheel_identity(path: Path) -> tuple[str, str]:
    try:
        with zipfile.ZipFile(path) as archive:
            metadata_names = [name for name in archive.namelist() if name.endswith(".dist-info/METADATA")]
            if len(metadata_names) != 1:
                raise ManifestError(
                    f"{path.name}: expected one .dist-info/METADATA member, found {len(metadata_names)}",
                )
            metadata = BytesParser(policy=compat32).parsebytes(archive.read(metadata_names[0]))
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        raise ManifestError(f"invalid wheel {path.name}: {error}") from error
    package = metadata.get("Name")
    version = metadata.get("Version")
    if not package or not version:
        raise ManifestError(f"{path.name}: wheel metadata lacks Name or Version")
    return _normalize_package(package), version


def _locked_source(entry: dict[str, Any], *, kind: str | None = None) -> dict[str, str]:
    required = ("filename", "sha256", "url")
    if not all(isinstance(entry.get(key), str) and entry[key] for key in required):
        raise ManifestError("locked source lacks filename, sha256, or URL")
    source_kind = kind or entry.get("kind")
    if not isinstance(source_kind, str) or not source_kind:
        raise ManifestError(f"locked source lacks kind: {entry['filename']}")
    return {
        "filename": entry["filename"],
        "kind": source_kind,
        "sha256": entry["sha256"],
        "url": entry["url"],
    }


def _provenance(
    package: str,
    version: str,
    abi: str,
    runtime_sources: dict[str, dict[str, Any]],
    s1a_sources: dict[str, dict[str, Any]],
) -> tuple[str, dict[str, str]]:
    runtime_matches = [
        entry for entry in runtime_sources.values() if _normalize_package(str(entry.get("package", ""))) == package
    ]
    if len(runtime_matches) == 1:
        entry = runtime_matches[0]
        source_version = entry.get("version")
        if source_version != version:
            raise ManifestError(
                f"{package}: source version {source_version} does not match wheel version {version}",
            )
        license_value = entry.get("license")
        expression = license_value.get("expression") if isinstance(license_value, dict) else None
        if not isinstance(expression, str) or not expression:
            raise ManifestError(f"runtime source lacks license expression: {package}")
        return expression, _locked_source(entry)
    if package == "chaquopy-libcxx":
        if version != "190000":
            raise ManifestError(
                f"{package}: source version 190000 does not match wheel version {version}",
            )
        if abi not in NDK_LIBCXX_PATHS:
            raise ManifestError(f"chaquopy-libcxx cannot use ABI {abi}")
        return (
            "Apache-2.0 WITH LLVM-exception",
            {
                "kind": "android-ndk",
                "path": NDK_LIBCXX_PATHS[abi],
                "revision": NDK_REVISION,
                "url": NDK_RELEASE_URL,
            },
        )
    rule = S1A_SOURCE_RULES.get(package)
    if rule is None:
        raise ManifestError(f"no authoritative source mapping for package {package}")
    source_version, source_name, license_expression = rule
    if source_version != version:
        raise ManifestError(
            f"{package}: source version {source_version} does not match wheel version {version}",
        )
    source_entry = s1a_sources.get(source_name)
    if not isinstance(source_entry, dict):
        raise ManifestError(f"S1a source lock lacks {source_name}")
    return license_expression, _locked_source(source_entry, kind="source-build")


def _wheel_paths(wheels_root: Path) -> list[Path]:
    if not wheels_root.is_dir():
        raise ManifestError(f"wheel root is not a directory: {wheels_root}")
    paths = sorted(wheels_root.rglob("*.whl"), key=lambda path: path.relative_to(wheels_root).as_posix())
    if not paths:
        raise ManifestError(f"no vendored wheels found under {wheels_root}")
    for path in paths:
        relative = path.relative_to(wheels_root)
        if path.is_symlink() or not path.is_file():
            raise ManifestError(f"vendored wheel must be a regular file: {relative.as_posix()}")
        if len(relative.parts) != 2 or relative.parts[0] not in ABIS:
            raise ManifestError(f"vendored wheel has unsupported path: {relative.as_posix()}")
    return paths


def build_manifest(wheels_root: Path) -> dict[str, Any]:
    runtime_sources = _source_entries(RUNTIME_SOURCE_LOCK)
    s1a_sources = _source_entries(S1A_SOURCE_LOCK)
    entries: list[dict[str, Any]] = []
    for path in _wheel_paths(wheels_root):
        relative = path.relative_to(wheels_root)
        abi = relative.parts[0]
        package, version = _wheel_identity(path)
        license_expression, source = _provenance(package, version, abi, runtime_sources, s1a_sources)
        entries.append(
            {
                "abi": abi,
                "filename": path.name,
                "license": license_expression,
                "package": package,
                "path": relative.as_posix(),
                "sha256": _sha256(path),
                "source": source,
                "version": version,
            },
        )
    return {"schema": 1, "wheels": entries}


def _render(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, indent=2, sort_keys=True) + "\n").encode()


def generate(wheels_root: Path, manifest: Path) -> int:
    document = build_manifest(wheels_root)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_bytes(_render(document))
    print(f"vendored-wheel-manifest: generated {len(document['wheels'])} wheels at {manifest}")
    return 0


def _manifest_entries(document: dict[str, Any]) -> list[dict[str, Any]]:
    if document.get("schema") != 1 or set(document) != {"schema", "wheels"}:
        raise ManifestError("vendored wheel manifest must use schema 1 with only wheels")
    entries = document.get("wheels")
    if not isinstance(entries, list) or not entries:
        raise ManifestError("vendored wheel manifest has no wheel entries")
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != ENTRY_KEYS:
            raise ManifestError("vendored wheel manifest contains an invalid entry")
    return entries


def check(wheels_root: Path, manifest: Path) -> int:
    committed = _load_json(manifest)
    entries = _manifest_entries(committed)
    actual_paths = {path.relative_to(wheels_root).as_posix(): path for path in _wheel_paths(wheels_root)}
    manifest_paths = {entry["path"] for entry in entries}
    if set(actual_paths) != manifest_paths:
        added = sorted(set(actual_paths) - manifest_paths)
        missing = sorted(manifest_paths - set(actual_paths))
        raise ManifestError(f"wheel set differs: unmanifested={added}, missing={missing}")
    for entry in entries:
        path = actual_paths[entry["path"]]
        actual_hash = _sha256(path)
        if actual_hash != entry["sha256"]:
            raise ManifestError(f"SHA-256 mismatch: {entry['path']}")
    expected = build_manifest(wheels_root)
    if _render(committed) != _render(expected):
        raise ManifestError(
            "vendored wheel manifest provenance differs; run "
            "python3.13 tools/wheels/vendored_wheel_manifest.py generate",
        )
    if manifest.read_bytes() != _render(committed):
        raise ManifestError("vendored wheel manifest is not in canonical generated form")
    print(f"vendored-wheel-manifest: {len(entries)} wheels verified")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "generate"))
    parser.add_argument("--wheels-root", type=Path, default=DEFAULT_WHEELS_ROOT)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            return generate(args.wheels_root, args.manifest)
        return check(args.wheels_root, args.manifest)
    except ManifestError as error:
        print(f"vendored-wheel-manifest: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
