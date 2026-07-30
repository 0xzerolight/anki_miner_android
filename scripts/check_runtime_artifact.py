#!/usr/bin/env python3
"""Compare packaged Chaquopy requirements with verified wheel manifests."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from email.parser import BytesParser
from email.policy import compat32
from io import BytesIO
from pathlib import Path, PurePosixPath

SUPPORTED_ABIS = {"arm64-v8a": 183, "x86_64": 62}
RUNTIME_SCHEMA = 1
S1A_SCHEMA = 2
KEY_PATTERN = re.compile(r"[0-9a-f]{64}")
PACKAGE_PATTERN = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")

MAX_OUTER_ENTRIES = 100_000
MAX_OUTER_ENTRY_SIZE = 1024 * 1024 * 1024
MAX_OUTER_TOTAL_SIZE = 4 * 1024 * 1024 * 1024
MAX_REQUIREMENT_ENTRIES = 100_000
MAX_REQUIREMENT_ENTRY_SIZE = 512 * 1024 * 1024
MAX_REQUIREMENT_TOTAL_SIZE = 2 * 1024 * 1024 * 1024
MAX_METADATA_SIZE = 4 * 1024 * 1024
MAX_LICENSE_SIZE = 16 * 1024 * 1024
EMPTY_ZIP = b"PK\x05\x06" + (b"\x00" * 18)
ELF_MAGIC = b"\x7fELF"

ALLOWED_COMPRESSION = {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}
NESTED_ARCHIVE_SUFFIXES = (".aab", ".apk", ".imy", ".whl", ".zip")
ZIP_MAGICS = (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")
LICENSE_PREFIXES = ("LICENSE", "COPYING", "COPYRIGHT", "NOTICE", "FTL")

FORBIDDEN_PACKAGE_PREFIXES = ("gtts", "pyqt6", "unidic", "yt-dlp")
S1A_ONLY_PACKAGES = {
    "chaquopy-libcxx",
    "chaquopy-libmecab",
    "fugashi",
    "mecab",
    "mecab-python3",
}
S1A_ONLY_NATIVE_PATHS = {
    "chaquopy/lib/libc++_shared.so",
    "chaquopy/lib/libmecab.so.2",
    "fugashi/fugashi.so",
}
S1A_SPECS = {
    "chaquopy-libcxx": (
        "chaquopy_libcxx",
        "190000",
        "py3-none",
        "chaquopy/lib/libc++_shared.so",
    ),
    "chaquopy-libmecab": (
        "chaquopy_libmecab",
        "0.996",
        "py3-none",
        "chaquopy/lib/libmecab.so.2",
    ),
    "fugashi": (
        "fugashi",
        "1.5.2",
        "cp312-cp312",
        "fugashi/fugashi.so",
    ),
}
VENDORED_MANIFEST_ENTRY_KEYS = {
    "abi",
    "filename",
    "license",
    "package",
    "path",
    "sha256",
    "source",
    "version",
}


class RuntimeArtifactError(RuntimeError):
    """The artifact does not match its selected wheel publications."""


@dataclass(frozen=True)
class ExpectedFile:
    package: str
    sha256: str
    abi: str | None = None


@dataclass
class ExpectedInventory:
    distributions: dict[str, str] = field(default_factory=dict)
    natives: dict[str, ExpectedFile] = field(default_factory=dict)
    licenses: dict[str, ExpectedFile] = field(default_factory=dict)

    def add_distribution(self, package: str, version: str, label: str) -> None:
        normalized = normalize_package(package)
        if normalized in self.distributions:
            raise RuntimeArtifactError(f"{label}: duplicate distribution {normalized}")
        self.distributions[normalized] = version

    def add_file(
        self,
        target: dict[str, ExpectedFile],
        path: str,
        expected: ExpectedFile,
        label: str,
    ) -> None:
        if path in target:
            raise RuntimeArtifactError(f"{label}: duplicate payload path {path}")
        target[path] = expected

    def merge(self, other: ExpectedInventory, label: str) -> None:
        for package, version in other.distributions.items():
            self.add_distribution(package, version, label)
        for path, expected in other.natives.items():
            self.add_file(self.natives, path, expected, label)
        for path, expected in other.licenses.items():
            self.add_file(self.licenses, path, expected, label)


@dataclass(frozen=True)
class AuditResult:
    abi: str
    artifact_type: str
    distribution_count: int
    license_count: int
    native_count: int
    s1a_included: bool

    def as_json(self) -> str:
        return json.dumps(
            {
                "abi": self.abi,
                "artifact_type": self.artifact_type,
                "distribution_count": self.distribution_count,
                "license_count": self.license_count,
                "native_count": self.native_count,
                "s1a_included": self.s1a_included,
            },
            sort_keys=True,
        )


def normalize_package(value: str) -> str:
    return re.sub(r"[-_.]+", "-", value).casefold()


def _is_forbidden_package(value: str) -> bool:
    package = normalize_package(value)
    return any(package == prefix or package.startswith(f"{prefix}-") for prefix in FORBIDDEN_PACKAGE_PREFIXES)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _require_hash(value: object, label: str) -> str:
    if not isinstance(value, str) or KEY_PATTERN.fullmatch(value) is None:
        raise RuntimeArtifactError(f"{label}: invalid SHA-256")
    return value


def _safe_member_name(name: str, label: str) -> PurePosixPath:
    if not name or "\x00" in name or "\\" in name:
        raise RuntimeArtifactError(f"{label}: unsafe archive entry {name!r}")
    normalized = name[:-1] if name.endswith("/") else name
    components = normalized.split("/")
    if not normalized or normalized.startswith("/") or any(component in {"", ".", ".."} for component in components):
        raise RuntimeArtifactError(f"{label}: unsafe archive entry {name!r}")
    return PurePosixPath(normalized)


def _safe_manifest_path(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise RuntimeArtifactError(f"{label}: payload path is missing")
    path = _safe_member_name(value, label).as_posix()
    if path != value:
        raise RuntimeArtifactError(f"{label}: payload path is not canonical")
    return path


def _is_license_path(path: str) -> bool:
    basename = PurePosixPath(path).name.upper()
    return basename == "BSD" or basename.startswith(LICENSE_PREFIXES)


def _is_dist_info_license_path(path: str) -> bool:
    """Return whether a wheel member is an owned dist-info license payload."""
    parts = PurePosixPath(path).parts
    return (
        bool(parts)
        and parts[0].endswith(".dist-info")
        and not any(part.endswith(".dist-info") for part in parts[1:])
        and _is_license_path(path)
    )


def _dist_info_identity(path: str, label: str) -> tuple[str, str, str]:
    parts = PurePosixPath(path).parts
    if not parts or not parts[0].endswith(".dist-info"):
        raise RuntimeArtifactError(f"{label}: attribution is outside a top-level dist-info directory")
    if any(part.endswith(".dist-info") for part in parts[1:]):
        raise RuntimeArtifactError(f"{label}: nested dist-info directory")
    stem = parts[0][: -len(".dist-info")]
    raw_name, separator, version = stem.rpartition("-")
    if not separator or not raw_name or not version:
        raise RuntimeArtifactError(f"{label}: malformed dist-info directory")
    return parts[0], normalize_package(raw_name), version


def _validate_license_owner(
    path: str,
    package: str,
    version: str,
    label: str,
) -> None:
    _, owner, owner_version = _dist_info_identity(path, label)
    if owner != package or owner_version != version or not _is_license_path(path):
        raise RuntimeArtifactError(f"{label}: attribution path does not belong to {package} {version}")


def _read_manifest(path: Path, label: str) -> tuple[Path, dict[str, object]]:
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise RuntimeArtifactError(f"{label} not found: {path}") from error
    if not resolved.is_file() or resolved.name != "manifest.json":
        raise RuntimeArtifactError(f"{label} must be a manifest.json file")
    try:
        value = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise RuntimeArtifactError(f"invalid {label}: {path}") from error
    if not isinstance(value, dict):
        raise RuntimeArtifactError(f"invalid {label}: root must be an object")
    return resolved, value


def _runtime_entry_inventory(
    raw_entry: object,
    abi: str,
    group: str,
    inventory: ExpectedInventory,
) -> None:
    label = f"runtime manifest {group} entry"
    expected_keys = {
        "package",
        "version",
        "filename",
        "sha256",
        "size",
        "tag",
        "requires",
        "license_expression",
        "licenses",
        "native",
    }
    if not isinstance(raw_entry, dict) or set(raw_entry) != expected_keys:
        raise RuntimeArtifactError(f"{label}: keys differ from schema")
    package_value = raw_entry.get("package")
    version = raw_entry.get("version")
    filename = raw_entry.get("filename")
    if (
        not isinstance(package_value, str)
        or PACKAGE_PATTERN.fullmatch(package_value) is None
        or not isinstance(version, str)
        or not version
        or version != version.strip()
        or not isinstance(filename, str)
        or Path(filename).name != filename
    ):
        raise RuntimeArtifactError(f"{label}: invalid distribution identity")
    package = normalize_package(package_value)
    if _is_forbidden_package(package) or package in S1A_ONLY_PACKAGES:
        raise RuntimeArtifactError(f"{label}: forbidden tokenizer or cut distribution {package}")
    _require_hash(raw_entry.get("sha256"), label)
    if (
        not isinstance(raw_entry.get("size"), int)
        or raw_entry["size"] <= 0
        or not isinstance(raw_entry.get("tag"), str)
        or not isinstance(raw_entry.get("requires"), list)
        or not isinstance(raw_entry.get("license_expression"), str)
    ):
        raise RuntimeArtifactError(f"{label}: malformed wheel metadata")
    inventory.add_distribution(package, version, label)

    licenses = raw_entry.get("licenses")
    if not isinstance(licenses, list) or not licenses:
        raise RuntimeArtifactError(f"{label}: license inventory is empty")
    for raw_license in licenses:
        if not isinstance(raw_license, dict) or set(raw_license) != {
            "path",
            "sha256",
        }:
            raise RuntimeArtifactError(f"{label}: malformed license entry")
        path = _safe_manifest_path(raw_license.get("path"), label)
        sha256 = _require_hash(raw_license.get("sha256"), label)
        _validate_license_owner(path, package, version, label)
        inventory.add_file(
            inventory.licenses,
            path,
            ExpectedFile(package, sha256),
            label,
        )

    natives = raw_entry.get("native")
    if not isinstance(natives, list):
        raise RuntimeArtifactError(f"{label}: native inventory is malformed")
    for raw_native in natives:
        if not isinstance(raw_native, dict) or set(raw_native) != {
            "path",
            "sha256",
            "abi",
            "soname",
            "needed",
        }:
            raise RuntimeArtifactError(f"{label}: malformed native entry")
        path = _safe_manifest_path(raw_native.get("path"), label)
        sha256 = _require_hash(raw_native.get("sha256"), label)
        native_abi = raw_native.get("abi")
        if group == "common" or native_abi != abi:
            raise RuntimeArtifactError(f"{label}: native ABI differs from {abi}")
        if not isinstance(raw_native.get("needed"), list) or not all(
            isinstance(value, str) for value in raw_native["needed"]
        ):
            raise RuntimeArtifactError(f"{label}: native dependencies are malformed")
        soname = raw_native.get("soname")
        if soname is not None and not isinstance(soname, str):
            raise RuntimeArtifactError(f"{label}: native SONAME is malformed")
        inventory.add_file(
            inventory.natives,
            path,
            ExpectedFile(package, sha256, abi),
            label,
        )


def load_runtime_inventory(manifest: Path, abi: str) -> ExpectedInventory:
    resolved, document = _read_manifest(manifest, "runtime manifest")
    if document.get("schema") != RUNTIME_SCHEMA:
        raise RuntimeArtifactError("unsupported runtime manifest schema")
    build_key = _require_hash(document.get("build_key"), "runtime manifest")
    if resolved.parent.name != f"runtime-wheels-{build_key}":
        raise RuntimeArtifactError("runtime manifest is outside its immutable build-key directory")
    wheels = document.get("wheels")
    expected_groups = {"common", *SUPPORTED_ABIS}
    if not isinstance(wheels, dict) or set(wheels) != expected_groups:
        raise RuntimeArtifactError("runtime manifest wheel groups are invalid")

    inventory = ExpectedInventory()
    for group in ("common", abi):
        entries = wheels.get(group)
        if not isinstance(entries, list) or not entries:
            raise RuntimeArtifactError(f"runtime manifest {group} wheel group is empty")
        for raw_entry in entries:
            _runtime_entry_inventory(raw_entry, abi, group, inventory)
    return inventory


def _s1a_package_from_filename(filename: str, abi: str) -> tuple[str, str, str]:
    for package, (wheel_name, version, tags, native_path) in S1A_SPECS.items():
        expected = f"{wheel_name}-{version}-0-{tags}-android_26_" f"{abi.replace('-', '_')}.whl"
        if filename == expected:
            return package, version, native_path
    raise RuntimeArtifactError(f"S1a manifest has an unexpected {abi} wheel: {filename!r}")


def load_s1a_inventory(manifest: Path, abi: str) -> ExpectedInventory:
    resolved, document = _read_manifest(manifest, "S1a manifest")
    if document.get("schema") != S1A_SCHEMA:
        raise RuntimeArtifactError("unsupported S1a manifest schema")
    build_key = _require_hash(document.get("build_key"), "S1a manifest")
    if resolved.parent.name != f"s1a-wheels-{build_key}":
        raise RuntimeArtifactError("S1a manifest is outside its immutable build-key directory")
    wheels = document.get("wheels")
    if not isinstance(wheels, dict) or set(wheels) != set(SUPPORTED_ABIS):
        raise RuntimeArtifactError("S1a manifest ABI groups are invalid")
    entries = wheels.get(abi)
    if not isinstance(entries, list) or len(entries) != len(S1A_SPECS):
        raise RuntimeArtifactError(f"S1a manifest {abi} wheel group is incomplete")

    inventory = ExpectedInventory()
    for raw_entry in entries:
        label = f"S1a manifest {abi} entry"
        if not isinstance(raw_entry, dict) or set(raw_entry) != {
            "elf",
            "filename",
            "licenses",
            "sha256",
            "size",
        }:
            raise RuntimeArtifactError(f"{label}: keys differ from schema")
        filename = raw_entry.get("filename")
        if not isinstance(filename, str):
            raise RuntimeArtifactError(f"{label}: wheel filename is missing")
        package, version, expected_native_path = _s1a_package_from_filename(filename, abi)
        _require_hash(raw_entry.get("sha256"), label)
        if not isinstance(raw_entry.get("size"), int) or raw_entry["size"] <= 0:
            raise RuntimeArtifactError(f"{label}: wheel size is invalid")
        inventory.add_distribution(package, version, label)

        raw_elf = raw_entry.get("elf")
        if not isinstance(raw_elf, dict) or set(raw_elf) != {
            "abi",
            "needed",
            "path",
            "sha256",
            "soname",
        }:
            raise RuntimeArtifactError(f"{label}: native entry is malformed")
        native_path = _safe_manifest_path(raw_elf.get("path"), label)
        native_hash = _require_hash(raw_elf.get("sha256"), label)
        if native_path != expected_native_path or raw_elf.get("abi") != abi:
            raise RuntimeArtifactError(f"{label}: native path or ABI differs")
        if not isinstance(raw_elf.get("needed"), list) or not all(
            isinstance(value, str) for value in raw_elf["needed"]
        ):
            raise RuntimeArtifactError(f"{label}: native dependencies are malformed")
        soname = raw_elf.get("soname")
        if soname is not None and not isinstance(soname, str):
            raise RuntimeArtifactError(f"{label}: native SONAME is malformed")
        inventory.add_file(
            inventory.natives,
            native_path,
            ExpectedFile(package, native_hash, abi),
            label,
        )

        licenses = raw_entry.get("licenses")
        if not isinstance(licenses, list) or not licenses:
            raise RuntimeArtifactError(f"{label}: license inventory is empty")
        for raw_license in licenses:
            if not isinstance(raw_license, dict) or set(raw_license) != {
                "path",
                "sha256",
            }:
                raise RuntimeArtifactError(f"{label}: license entry is malformed")
            path = _safe_manifest_path(raw_license.get("path"), label)
            sha256 = _require_hash(raw_license.get("sha256"), label)
            _validate_license_owner(path, package, version, label)
            inventory.add_file(
                inventory.licenses,
                path,
                ExpectedFile(package, sha256),
                label,
            )
    if set(inventory.distributions) != set(S1A_SPECS):
        raise RuntimeArtifactError(f"S1a manifest {abi} package set differs")
    return inventory


def load_vendored_inventory(manifest: Path, abi: str) -> ExpectedInventory:
    """Load exact package inventory from Gradle-verified vendored wheels."""
    resolved, document = _read_manifest(manifest, "vendored wheel manifest")
    if document.get("schema") != 1 or set(document) != {"schema", "wheels"}:
        raise RuntimeArtifactError("unsupported vendored wheel manifest schema")
    entries = document.get("wheels")
    if not isinstance(entries, list) or not entries:
        raise RuntimeArtifactError("vendored wheel manifest has no wheel entries")

    inventory = ExpectedInventory()
    selected_groups = {"common", abi}
    seen_paths: set[str] = set()
    for raw_entry in entries:
        label = "vendored wheel manifest entry"
        if not isinstance(raw_entry, dict) or set(raw_entry) != VENDORED_MANIFEST_ENTRY_KEYS:
            raise RuntimeArtifactError(f"{label}: keys differ from schema")
        group = raw_entry.get("abi")
        filename = raw_entry.get("filename")
        package_value = raw_entry.get("package")
        version = raw_entry.get("version")
        path_value = raw_entry.get("path")
        sha256 = raw_entry.get("sha256")
        if (
            not isinstance(group, str)
            or group not in {"common", *SUPPORTED_ABIS}
            or not isinstance(filename, str)
            or Path(filename).name != filename
            or not isinstance(package_value, str)
            or PACKAGE_PATTERN.fullmatch(package_value) is None
            or not isinstance(version, str)
            or not version
            or not isinstance(raw_entry.get("license"), str)
            or not isinstance(raw_entry.get("source"), dict)
        ):
            raise RuntimeArtifactError(f"{label}: invalid wheel identity")
        path = _safe_manifest_path(path_value, label)
        if PurePosixPath(path).parts != (group, filename):
            raise RuntimeArtifactError(f"{label}: wheel path differs from group and filename")
        if path in seen_paths:
            raise RuntimeArtifactError(f"{label}: duplicate wheel path {path}")
        seen_paths.add(path)
        expected_hash = _require_hash(sha256, label)
        if group not in selected_groups:
            continue

        wheel = resolved.parent / path
        if wheel.is_symlink() or not wheel.is_file():
            raise RuntimeArtifactError(f"{label}: wheel is not a regular file: {path}")
        if _sha256(wheel.read_bytes()) != expected_hash:
            raise RuntimeArtifactError(f"{label}: wheel hash differs: {path}")
        try:
            archive = zipfile.ZipFile(wheel)
        except zipfile.BadZipFile as error:
            raise RuntimeArtifactError(f"{label}: invalid wheel archive: {path}") from error
        with archive:
            infos = _validated_infos(
                archive,
                f"{label}: {path}",
                max_entries=MAX_REQUIREMENT_ENTRIES,
                max_entry_size=MAX_REQUIREMENT_ENTRY_SIZE,
                max_total_size=MAX_REQUIREMENT_TOTAL_SIZE,
            )
            metadata_paths = [
                member
                for member, info in infos.items()
                if not info.is_dir() and member.endswith(".dist-info/METADATA")
            ]
            if len(metadata_paths) != 1:
                raise RuntimeArtifactError(f"{label}: expected one METADATA payload: {path}")
            metadata_path = metadata_paths[0]
            package, actual_version = _parse_metadata(
                _read_member(
                    archive,
                    infos[metadata_path],
                    f"{label}: {path}",
                    MAX_METADATA_SIZE,
                ),
                metadata_path.removesuffix("/METADATA"),
            )
            if package != normalize_package(package_value) or actual_version != version:
                raise RuntimeArtifactError(f"{label}: wheel METADATA differs from manifest: {path}")
            inventory.add_distribution(package, actual_version, label)

            for member, info in infos.items():
                if info.is_dir():
                    continue
                data = _read_member(
                    archive,
                    info,
                    f"{label}: {path}",
                    MAX_REQUIREMENT_ENTRY_SIZE,
                )
                if _is_dist_info_license_path(member):
                    _validate_license_owner(member, package, actual_version, f"{label}: {path}")
                    inventory.add_file(
                        inventory.licenses,
                        member,
                        ExpectedFile(package, _sha256(data)),
                        label,
                    )
                native_name = _is_native_name(member)
                native_magic = data.startswith(ELF_MAGIC)
                if native_name != native_magic:
                    raise RuntimeArtifactError(f"{label}: malformed native payload {member}")
                if native_magic:
                    if group == "common":
                        raise RuntimeArtifactError(f"{label}: common wheel contains native payload {member}")
                    native_abi = _native_abi(data, member)
                    if native_abi != abi:
                        raise RuntimeArtifactError(
                            f"{label}: native payload {member} is {native_abi}, expected {abi}",
                        )
                    inventory.add_file(
                        inventory.natives,
                        member,
                        ExpectedFile(package, _sha256(data), native_abi),
                        label,
                    )
    if not inventory.distributions:
        raise RuntimeArtifactError("vendored wheel manifest has no selected wheels")
    return inventory


def _validated_infos(
    archive: zipfile.ZipFile,
    label: str,
    *,
    max_entries: int,
    max_entry_size: int,
    max_total_size: int,
) -> dict[str, zipfile.ZipInfo]:
    infos = archive.infolist()
    if len(infos) > max_entries:
        raise RuntimeArtifactError(f"{label}: archive has too many entries")
    result: dict[str, zipfile.ZipInfo] = {}
    total_size = 0
    for info in infos:
        path = _safe_member_name(info.filename, label).as_posix()
        if path in result:
            raise RuntimeArtifactError(f"{label}: duplicate archive entry {path}")
        if info.flag_bits & 0x1:
            raise RuntimeArtifactError(f"{label}: encrypted archive entry {path}")
        if info.compress_type not in ALLOWED_COMPRESSION:
            raise RuntimeArtifactError(f"{label}: unsupported compression for {path}")
        mode = info.external_attr >> 16
        file_type = stat.S_IFMT(mode)
        if file_type == stat.S_IFLNK:
            raise RuntimeArtifactError(f"{label}: symlink entry {path}")
        if file_type not in {0, stat.S_IFREG, stat.S_IFDIR}:
            raise RuntimeArtifactError(f"{label}: special-file entry {path}")
        if info.file_size > max_entry_size or info.compress_size > max_entry_size:
            raise RuntimeArtifactError(f"{label}: oversized archive entry {path}")
        total_size += info.file_size
        if total_size > max_total_size:
            raise RuntimeArtifactError(f"{label}: archive expands beyond its limit")
        result[path] = info
    return result


def _read_member(
    archive: zipfile.ZipFile,
    info: zipfile.ZipInfo,
    label: str,
    limit: int,
) -> bytes:
    if info.file_size > limit:
        raise RuntimeArtifactError(f"{label}: oversized payload {info.filename}")
    try:
        with archive.open(info) as stream:
            data = stream.read(limit + 1)
            if stream.read(1):
                data += b"!"
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        raise RuntimeArtifactError(f"{label}: cannot read archive entry {info.filename}") from error
    if len(data) > limit or len(data) != info.file_size:
        raise RuntimeArtifactError(f"{label}: payload size differs for {info.filename}")
    return data


def _module_root(path: str) -> str:
    root = PurePosixPath(path).parts[0]
    for suffix in (".dist-info", ".egg-info", ".py", ".pyc"):
        if root.casefold().endswith(suffix):
            root = root[: -len(suffix)]
            if suffix in {".dist-info", ".egg-info"}:
                root = root.rpartition("-")[0]
            break
    return normalize_package(root)


def _check_module_boundary(path: str, s1a_enabled: bool) -> None:
    root = _module_root(path)
    if _is_forbidden_package(root):
        raise RuntimeArtifactError(f"requirements-common.imy: forbidden package payload {root}")
    if not s1a_enabled and (root in S1A_ONLY_PACKAGES or path in S1A_ONLY_NATIVE_PATHS):
        raise RuntimeArtifactError(f"requirements-common.imy: S1a-only payload without S1a manifest: {path}")


def _parse_metadata(data: bytes, root: str) -> tuple[str, str]:
    if len(data) > MAX_METADATA_SIZE:
        raise RuntimeArtifactError(f"{root}: METADATA is oversized")
    try:
        message = BytesParser(policy=compat32).parsebytes(data)
    except Exception as error:
        raise RuntimeArtifactError(f"{root}: malformed METADATA") from error
    names = message.get_all("Name") or []
    versions = message.get_all("Version") or []
    metadata_versions = message.get_all("Metadata-Version") or []
    if message.defects or len(names) != 1 or len(versions) != 1 or len(metadata_versions) != 1:
        raise RuntimeArtifactError(f"{root}: METADATA identity headers differ")
    name = str(names[0])
    version = str(versions[0])
    if (
        PACKAGE_PATTERN.fullmatch(name) is None
        or re.fullmatch(r"[0-9]+\.[0-9]+", str(metadata_versions[0])) is None
        or not version
        or version != version.strip()
        or any(ord(character) < 0x20 for character in version)
    ):
        raise RuntimeArtifactError(f"{root}: invalid METADATA identity")
    _, root_name, root_version = _dist_info_identity(f"{root}/METADATA", f"{root}/METADATA")
    normalized = normalize_package(name)
    if root_name != normalized or root_version != version:
        raise RuntimeArtifactError(f"{root}: dist-info name differs from METADATA")
    return normalized, version


def _native_abi(data: bytes, path: str) -> str:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise RuntimeArtifactError(f"{path}: truncated native payload")
    if data[4] != 2 or data[5] != 1:
        raise RuntimeArtifactError(f"{path}: Android runtime native must be ELF64 LE")
    machine = struct.unpack_from("<H", data, 18)[0]
    matches = [abi for abi, expected in SUPPORTED_ABIS.items() if machine == expected]
    if len(matches) != 1:
        raise RuntimeArtifactError(f"{path}: unsupported ELF machine {machine}")
    return matches[0]


def _is_native_name(path: str) -> bool:
    name = PurePosixPath(path).name
    return name.endswith(".so") or ".so." in name


def _audit_requirements(
    payload: bytes,
    expected: ExpectedInventory,
    abi: str,
    *,
    s1a_enabled: bool,
) -> None:
    label = "requirements-common.imy"
    try:
        archive = zipfile.ZipFile(BytesIO(payload))
    except zipfile.BadZipFile as error:
        raise RuntimeArtifactError(f"{label}: invalid ZIP archive") from error
    with archive:
        if archive.comment:
            raise RuntimeArtifactError(f"{label}: archive comment is forbidden")
        infos = _validated_infos(
            archive,
            label,
            max_entries=MAX_REQUIREMENT_ENTRIES,
            max_entry_size=MAX_REQUIREMENT_ENTRY_SIZE,
            max_total_size=MAX_REQUIREMENT_TOTAL_SIZE,
        )
        files: dict[str, bytes] = {}
        for path, info in infos.items():
            if info.is_dir():
                continue
            data = _read_member(
                archive,
                info,
                label,
                MAX_REQUIREMENT_ENTRY_SIZE,
            )
            lower = path.casefold()
            if lower.endswith(NESTED_ARCHIVE_SUFFIXES) or data.startswith(ZIP_MAGICS):
                raise RuntimeArtifactError(f"{label}: nested archive payload {path}")
            _check_module_boundary(path, s1a_enabled)
            files[path] = data

    dist_roots: set[str] = set()
    for path in files:
        parts = PurePosixPath(path).parts
        roots = [part for part in parts if part.casefold().endswith(".dist-info")]
        if roots:
            if len(roots) != 1 or parts[0] != roots[0] or not roots[0].endswith(".dist-info"):
                raise RuntimeArtifactError(f"{label}: nested dist-info path {path}")
            dist_roots.add(roots[0])
        if any(part.casefold().endswith(".egg-info") for part in parts):
            raise RuntimeArtifactError(f"{label}: legacy egg-info distribution {path}")

    actual_distributions: dict[str, str] = {}
    root_owners: dict[str, str] = {}
    for root in sorted(dist_roots):
        metadata_path = f"{root}/METADATA"
        metadata = files.get(metadata_path)
        if metadata is None:
            raise RuntimeArtifactError(f"{label}: {root} has no METADATA")
        package, version = _parse_metadata(metadata, root)
        if package in actual_distributions:
            raise RuntimeArtifactError(f"{label}: duplicate distribution {package}")
        if _is_forbidden_package(package):
            raise RuntimeArtifactError(f"{label}: forbidden distribution {package}")
        if not s1a_enabled and package in S1A_ONLY_PACKAGES:
            raise RuntimeArtifactError(f"{label}: S1a-only distribution without manifest: {package}")
        actual_distributions[package] = version
        root_owners[root] = package

    if actual_distributions != expected.distributions:
        raise RuntimeArtifactError(
            f"{label}: distribution inventory differs: "
            f"expected={sorted(expected.distributions.items())}, "
            f"actual={sorted(actual_distributions.items())}"
        )

    actual_licenses: dict[str, ExpectedFile] = {}
    actual_natives: dict[str, ExpectedFile] = {}
    for path, data in files.items():
        if _is_license_path(path):
            root, _, _ = _dist_info_identity(path, f"{label}:{path}")
            owner = root_owners.get(root)
            if owner is None:
                raise RuntimeArtifactError(f"{label}: unowned license payload {path}")
            if len(data) > MAX_LICENSE_SIZE:
                raise RuntimeArtifactError(f"{label}: oversized license payload {path}")
            actual_licenses[path] = ExpectedFile(owner, _sha256(data))

        native_name = _is_native_name(path)
        native_magic = data.startswith(b"\x7fELF")
        if native_name != native_magic:
            raise RuntimeArtifactError(f"{label}: malformed native payload {path}")
        if native_magic:
            native_abi = _native_abi(data, path)
            if native_abi != abi:
                raise RuntimeArtifactError(f"{label}: native payload {path} is {native_abi}, expected {abi}")
            owner = expected.natives.get(path)
            actual_natives[path] = ExpectedFile(
                owner.package if owner is not None else "<unowned>",
                _sha256(data),
                native_abi,
            )

    if actual_licenses != expected.licenses:
        raise RuntimeArtifactError(
            f"{label}: license inventory differs: "
            f"expected={sorted(expected.licenses)}, actual={sorted(actual_licenses)}"
        )
    if actual_natives != expected.natives:
        raise RuntimeArtifactError(
            f"{label}: native inventory differs: "
            f"expected={sorted(expected.natives)}, actual={sorted(actual_natives)}"
        )


def _artifact_layout(artifact: Path, abi: str) -> tuple[str, str, str]:
    suffix = artifact.suffix.casefold()
    if suffix == ".apk":
        artifact_type = "apk"
        prefix = "assets/chaquopy"
    elif suffix == ".aab":
        artifact_type = "aab"
        prefix = "base/assets/chaquopy"
    else:
        raise RuntimeArtifactError("artifact must have an .apk or .aab filename")
    return (
        artifact_type,
        f"{prefix}/requirements-common.imy",
        f"{prefix}/requirements-{abi}.imy",
    )


def _audit_artifact_inventory(
    artifact: Path,
    expected: ExpectedInventory,
    allowed_abi: str,
    *,
    s1a_enabled: bool,
) -> AuditResult:
    if allowed_abi not in SUPPORTED_ABIS:
        raise RuntimeArtifactError(f"unsupported ABI: {allowed_abi}")
    if artifact.is_symlink() or not artifact.is_file():
        raise RuntimeArtifactError(f"artifact is not a regular file: {artifact}")
    artifact_type, common_path, abi_path = _artifact_layout(artifact, allowed_abi)

    try:
        outer = zipfile.ZipFile(artifact)
    except zipfile.BadZipFile as error:
        raise RuntimeArtifactError(f"invalid Android archive: {artifact}") from error
    with outer:
        if outer.comment:
            raise RuntimeArtifactError(f"{artifact}: archive comment is forbidden")
        infos = _validated_infos(
            outer,
            str(artifact),
            max_entries=MAX_OUTER_ENTRIES,
            max_entry_size=MAX_OUTER_ENTRY_SIZE,
            max_total_size=MAX_OUTER_TOTAL_SIZE,
        )
        requirement_paths = {
            path
            for path, info in infos.items()
            if not info.is_dir()
            and PurePosixPath(path).name.startswith("requirements-")
            and PurePosixPath(path).suffix == ".imy"
        }
        expected_paths = {common_path, abi_path}
        if requirement_paths != expected_paths:
            raise RuntimeArtifactError(
                f"{artifact}: canonical requirements archives differ: "
                f"expected={sorted(expected_paths)}, actual={sorted(requirement_paths)}"
            )
        common_payload = _read_member(
            outer,
            infos[common_path],
            str(artifact),
            MAX_OUTER_ENTRY_SIZE,
        )
        abi_payload = _read_member(
            outer,
            infos[abi_path],
            str(artifact),
            MAX_OUTER_ENTRY_SIZE,
        )

    if abi_payload != EMPTY_ZIP:
        raise RuntimeArtifactError(f"{abi_path}: single-ABI Chaquopy requirements archive must be empty")
    _audit_requirements(
        common_payload,
        expected,
        allowed_abi,
        s1a_enabled=s1a_enabled,
    )
    return AuditResult(
        abi=allowed_abi,
        artifact_type=artifact_type,
        distribution_count=len(expected.distributions),
        license_count=len(expected.licenses),
        native_count=len(expected.natives),
        s1a_included=s1a_enabled,
    )


def audit_artifact(
    artifact: Path,
    runtime_manifest: Path,
    allowed_abi: str,
    s1a_manifest: Path | None = None,
) -> AuditResult:
    expected = load_runtime_inventory(runtime_manifest, allowed_abi)
    if s1a_manifest is not None:
        expected.merge(
            load_s1a_inventory(s1a_manifest, allowed_abi),
            "runtime and S1a manifests",
        )
    return _audit_artifact_inventory(
        artifact,
        expected,
        allowed_abi,
        s1a_enabled=s1a_manifest is not None,
    )


def audit_vendored_artifact(
    artifact: Path,
    vendored_manifest: Path,
    allowed_abi: str,
) -> AuditResult:
    return _audit_artifact_inventory(
        artifact,
        load_vendored_inventory(vendored_manifest, allowed_abi),
        allowed_abi,
        s1a_enabled=True,
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Audit packaged Chaquopy requirements against wheel manifests.",
    )
    parser.add_argument("--artifact", type=Path, required=True)
    manifest_source = parser.add_mutually_exclusive_group(required=True)
    manifest_source.add_argument("--runtime-manifest", type=Path)
    manifest_source.add_argument("--vendored-manifest", type=Path)
    parser.add_argument(
        "--allow-abi",
        action="append",
        choices=sorted(SUPPORTED_ABIS),
        required=True,
    )
    parser.add_argument("--s1a-manifest", type=Path)
    arguments = parser.parse_args(argv)
    if len(arguments.allow_abi) != 1:
        parser.error("--allow-abi must be supplied exactly once")
    if arguments.vendored_manifest is not None and arguments.s1a_manifest is not None:
        parser.error("--s1a-manifest cannot be combined with --vendored-manifest")
    arguments.allow_abi = arguments.allow_abi[0]
    return arguments


def main(argv: list[str] | None = None) -> int:
    arguments = parse_args(argv)
    try:
        if arguments.vendored_manifest is not None:
            result = audit_vendored_artifact(
                arguments.artifact,
                arguments.vendored_manifest,
                arguments.allow_abi,
            )
        else:
            result = audit_artifact(
                arguments.artifact,
                arguments.runtime_manifest,
                arguments.allow_abi,
                arguments.s1a_manifest,
            )
    except (OSError, RuntimeArtifactError) as error:
        print(f"runtime-artifact: {error}", file=sys.stderr)
        return 1
    print(result.as_json())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
