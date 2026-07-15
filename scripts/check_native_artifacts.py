#!/usr/bin/env python3
"""Recursively verify native payloads in Android archives and Chaquopy IMYs."""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import hashlib
from io import BytesIO
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import zipfile

PAGE_SIZE = 16 * 1024
MAX_ARCHIVE_DEPTH = 12
MAX_NESTED_ARCHIVE_SIZE = 1024 * 1024 * 1024
MAX_ATTRIBUTION_SIZE = 1024 * 1024
ELF_MAGIC = b"\x7fELF"
ZIP_MAGICS = (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")
MACHINE_ABIS = {
    3: "x86",
    40: "armeabi-v7a",
    62: "x86_64",
    183: "arm64-v8a",
}
ABI_ELF_CLASSES = {
    "x86": 1,
    "armeabi-v7a": 1,
    "x86_64": 2,
    "arm64-v8a": 2,
}
EXECUTABLE_NATIVE_NAMES = {"libffmpeg.so", "libffprobe.so"}
UNIDIC_PAYLOAD_NAMES = {
    "char.bin",
    "dicrc",
    "matrix.bin",
    "mecabrc",
    "sys.dic",
    "unk.dic",
}
UNIDIC_ARCHIVE_SUFFIXES = (
    ".7z",
    ".bz2",
    ".gz",
    ".imy",
    ".tar",
    ".tar.gz",
    ".tar.zst",
    ".tar.zstd",
    ".tgz",
    ".whl",
    ".xz",
    ".zip",
    ".zst",
    ".zstd",
)
ET_DYN = 3
PT_INTERP = 3
PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
DT_SONAME = 14
DT_RPATH = 15
DT_TEXTREL = 22
DT_RUNPATH = 29
DT_FLAGS = 30
DF_TEXTREL = 0x4
ANDROID_SYSTEM_LIBS = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
    "libz.so",
}
S1A_PACKAGE_VERSIONS = {
    "chaquopy_libcxx": "190000",
    "chaquopy_libmecab": "0.996",
    "fugashi": "1.5.2",
}
S1A_NATIVE_PATHS = {
    "chaquopy_libcxx": "chaquopy/lib/libc++_shared.so",
    "chaquopy_libmecab": "chaquopy/lib/libmecab.so.2",
    "fugashi": "fugashi/fugashi.so",
}
S1A_LICENSE_MARKERS = {
    "chaquopy_libcxx": (b"apache license", b"llvm exceptions"),
    "chaquopy_libmecab": (b"taku kudo", b"redistribution"),
    "fugashi": (b"permission is hereby granted",),
}


class ArtifactError(RuntimeError):
    pass


@dataclass(frozen=True)
class NativeMetadata:
    abi: str
    soname: str | None
    needed: tuple[str, ...]
    has_dynamic: bool


@dataclass(frozen=True)
class S1aAttribution:
    package: str
    sha256: str


@dataclass(frozen=True)
class S1aNativePayload:
    package: str
    abi: str
    path: str
    sha256: str


@dataclass
class Inspection:
    allowed_abis: set[str]
    forbidden: tuple[str, ...]
    required_direct_entries: set[str] = field(default_factory=set)
    reject_base_unidic: bool = False
    elf_count: int = 0
    found_abis: set[str] = field(default_factory=set)
    app_imy_count: int = 0
    found_required_entries: set[str] = field(default_factory=set)
    requirement_imys: set[str] = field(default_factory=set)
    requirement_member_counts: dict[str, int] = field(default_factory=dict)
    requirement_owners: dict[str, set[str]] = field(default_factory=dict)
    s1a_payloads: set[str] = field(default_factory=set)
    require_s1a: bool = False
    expected_attributions: dict[str, S1aAttribution] = field(default_factory=dict)
    found_attributions: set[str] = field(default_factory=set)
    attribution_text: dict[str, list[bytes]] = field(default_factory=dict)
    expected_natives: dict[tuple[str, str], S1aNativePayload] = field(
        default_factory=dict
    )
    found_natives: dict[tuple[str, str], int] = field(default_factory=dict)


def _s1a_package_from_wheel(filename: str) -> str:
    matches = [
        package
        for package, version in S1A_PACKAGE_VERSIONS.items()
        if filename.startswith(f"{package}-{version}-0-") and filename.endswith(".whl")
    ]
    if len(matches) != 1:
        raise ArtifactError(f"S1a manifest has an unexpected wheel filename: {filename!r}")
    return matches[0]


def s1a_native_package(path: PurePosixPath) -> str | None:
    basename = path.name
    if basename == "libc++_shared.so":
        return "chaquopy_libcxx"
    if basename == "libmecab.so.2":
        return "chaquopy_libmecab"
    if (
        bool(path.parts)
        and path.parts[0] == "fugashi"
        and basename.startswith("fugashi.")
        and basename.endswith(".so")
    ):
        return "fugashi"
    return None


def s1a_requirement_imys(allowed_abis: set[str]) -> set[str]:
    return {"requirements-common.imy"} | {
        f"requirements-{abi}.imy" for abi in allowed_abis
    }


def s1a_native_owner(abi: str, allowed_abis: set[str]) -> str:
    if len(allowed_abis) == 1:
        return "requirements-common.imy"
    return f"requirements-{abi}.imy"


def load_s1a_inventory(
    manifest: Path,
    allowed_abis: set[str],
) -> tuple[
    dict[str, S1aAttribution],
    dict[tuple[str, str], S1aNativePayload],
]:
    if not manifest.is_file():
        raise ArtifactError(f"S1a publication manifest not found: {manifest}")
    try:
        document = json.loads(manifest.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ArtifactError(f"invalid S1a publication manifest: {manifest}") from error
    if not isinstance(document, dict) or document.get("schema") != 2:
        raise ArtifactError("unsupported S1a publication manifest schema")
    wheels = document.get("wheels")
    expected_abis = {"arm64-v8a", "x86_64"}
    if not isinstance(wheels, dict) or set(wheels) != expected_abis:
        raise ArtifactError("S1a publication manifest ABI set is invalid")
    if not allowed_abis or not allowed_abis.issubset(expected_abis):
        raise ArtifactError(
            f"S1a packaging supports only {sorted(expected_abis)}, "
            f"found {sorted(allowed_abis)}"
        )

    by_abi: dict[str, dict[str, dict[str, str]]] = {}
    expected_natives: dict[tuple[str, str], S1aNativePayload] = {}
    for abi in sorted(expected_abis):
        entries = wheels[abi]
        if not isinstance(entries, list) or len(entries) != len(S1A_PACKAGE_VERSIONS):
            raise ArtifactError(f"S1a publication {abi} wheel set is incomplete")
        package_entries: dict[str, dict[str, str]] = {}
        for raw_entry in entries:
            if not isinstance(raw_entry, dict):
                raise ArtifactError("S1a publication wheel entry is invalid")
            filename = raw_entry.get("filename")
            licenses = raw_entry.get("licenses")
            elf = raw_entry.get("elf")
            if (
                not isinstance(filename, str)
                or not isinstance(licenses, list)
                or not licenses
                or not isinstance(elf, dict)
            ):
                raise ArtifactError("S1a publication license inventory is incomplete")
            if not filename.endswith(f"android_26_{abi.replace('-', '_')}.whl"):
                raise ArtifactError(f"S1a publication wheel ABI mismatch: {filename!r}")
            package = _s1a_package_from_wheel(filename)
            if package in package_entries:
                raise ArtifactError(f"duplicate S1a publication wheel for {package}/{abi}")
            native_path = elf.get("path")
            native_hash = elf.get("sha256")
            native_abi = elf.get("abi")
            if not isinstance(native_path, str) or not isinstance(native_hash, str):
                raise ArtifactError("S1a publication native path/hash is invalid")
            normalized_native = safe_entry_name(native_path, str(manifest)).as_posix()
            if (
                normalized_native != native_path
                or not re.fullmatch(r"[0-9a-f]{64}", native_hash)
                or native_abi != abi
            ):
                raise ArtifactError("S1a publication native path/hash/ABI is invalid")
            if native_path != S1A_NATIVE_PATHS[package]:
                raise ArtifactError(
                    f"S1a {package} manifest native path is unexpected: {native_path}"
                )
            if abi in allowed_abis:
                native_key = (s1a_native_owner(abi, allowed_abis), native_path)
                if native_key in expected_natives:
                    raise ArtifactError(
                        f"duplicate S1a publication native path for "
                        f"{native_key[0]}: {native_path}"
                    )
                expected_natives[native_key] = S1aNativePayload(
                    package,
                    abi,
                    native_path,
                    native_hash,
                )
            expected_dist_info = (
                f"{package}-{S1A_PACKAGE_VERSIONS[package]}.dist-info"
            ).casefold()
            package_licenses: dict[str, str] = {}
            for raw_license in licenses:
                if not isinstance(raw_license, dict):
                    raise ArtifactError("S1a publication license entry is invalid")
                path = raw_license.get("path")
                sha256 = raw_license.get("sha256")
                if not isinstance(path, str) or not isinstance(sha256, str):
                    raise ArtifactError("S1a publication license path/hash is invalid")
                normalized = safe_entry_name(path, str(manifest)).as_posix()
                if normalized != path or not re.fullmatch(r"[0-9a-f]{64}", sha256):
                    raise ArtifactError("S1a publication license path/hash is invalid")
                parts = tuple(part.casefold() for part in PurePosixPath(path).parts)
                if parts.count(expected_dist_info) != 1:
                    raise ArtifactError(
                        f"S1a {package} attribution is outside its dist-info directory: {path}"
                    )
                basename = PurePosixPath(path).name.upper()
                expected_name = basename.startswith(
                    ("LICENSE", "COPYING", "COPYRIGHT", "NOTICE")
                ) or (package == "chaquopy_libmecab" and basename == "BSD")
                if not expected_name:
                    raise ArtifactError(f"S1a attribution has an unexpected name: {path}")
                if path in package_licenses:
                    raise ArtifactError(f"duplicate S1a attribution path: {path}")
                package_licenses[path] = sha256
            package_entries[package] = package_licenses
        if set(package_entries) != set(S1A_PACKAGE_VERSIONS):
            raise ArtifactError(f"S1a publication {abi} package set is incomplete")
        by_abi[abi] = package_entries

    reference = by_abi["arm64-v8a"]
    if by_abi["x86_64"] != reference:
        raise ArtifactError("S1a attribution inventory differs across ABI wheels")
    result: dict[str, S1aAttribution] = {}
    for package, licenses in reference.items():
        for path, sha256 in licenses.items():
            if path in result:
                raise ArtifactError(f"S1a attribution path has duplicate package owners: {path}")
            result[path] = S1aAttribution(package, sha256)
    expected_native_count = len(allowed_abis) * len(S1A_PACKAGE_VERSIONS)
    if len(expected_natives) != expected_native_count:
        raise ArtifactError("S1a publication native inventory is incomplete")
    return result, expected_natives


def safe_entry_name(name: str, archive_name: str) -> PurePosixPath:
    if not name or "\x00" in name or "\\" in name:
        raise ArtifactError(f"{archive_name}: unsafe archive entry {name!r}")
    without_directory_marker = name[:-1] if name.endswith("/") else name
    components = without_directory_marker.split("/")
    if not without_directory_marker or any(
        component in {"", ".", ".."} for component in components
    ):
        raise ArtifactError(f"{archive_name}: unsafe archive entry {name!r}")
    path = PurePosixPath(without_directory_marker)
    if path.is_absolute():
        raise ArtifactError(f"{archive_name}: unsafe archive entry {name!r}")
    return path


def parse_elf(
    data: bytes,
    logical_name: str,
    inspection: Inspection,
    *,
    require_pie_cli: bool = False,
    require_et_dyn: bool = False,
    inspect_dynamic: bool = False,
) -> NativeMetadata:
    if len(data) < 52 or data[:4] != ELF_MAGIC:
        raise ArtifactError(f"{logical_name}: truncated ELF header")
    elf_class = data[4]
    data_encoding = data[5]
    if data_encoding != 1:
        raise ArtifactError(
            f"{logical_name}: Android ELF must be little-endian, found encoding "
            f"{data_encoding}"
        )
    endian = "<"

    elf_type = struct.unpack_from(f"{endian}H", data, 16)[0]
    machine = struct.unpack_from(f"{endian}H", data, 18)[0]
    abi = MACHINE_ABIS.get(machine)
    if abi is None:
        raise ArtifactError(f"{logical_name}: unsupported ELF machine {machine}")
    expected_class = ABI_ELF_CLASSES[abi]
    if elf_class != expected_class:
        raise ArtifactError(
            f"{logical_name}: ABI {abi} requires ELF class {expected_class}, "
            f"found {elf_class}"
        )
    if abi not in inspection.allowed_abis:
        raise ArtifactError(
            f"{logical_name}: contains ABI {abi}, allowed: {sorted(inspection.allowed_abis)}",
        )

    if elf_class == 1:
        entry_point = struct.unpack_from(f"{endian}I", data, 24)[0]
        phoff = struct.unpack_from(f"{endian}I", data, 28)[0]
        phentsize = struct.unpack_from(f"{endian}H", data, 42)[0]
        phnum = struct.unpack_from(f"{endian}H", data, 44)[0]
        minimum_phentsize = 32
        ph_format = f"{endian}IIIIIIII"
    elif elf_class == 2:
        if len(data) < 64:
            raise ArtifactError(f"{logical_name}: truncated ELF64 header")
        entry_point = struct.unpack_from(f"{endian}Q", data, 24)[0]
        phoff = struct.unpack_from(f"{endian}Q", data, 32)[0]
        phentsize = struct.unpack_from(f"{endian}H", data, 54)[0]
        phnum = struct.unpack_from(f"{endian}H", data, 56)[0]
        minimum_phentsize = 56
        ph_format = f"{endian}IIQQQQQQ"
    else:
        raise ArtifactError(f"{logical_name}: unsupported ELF class {elf_class}")

    if phnum in {0, 0xFFFF}:
        raise ArtifactError(f"{logical_name}: unsupported program-header count {phnum}")
    if phentsize < minimum_phentsize:
        raise ArtifactError(f"{logical_name}: invalid program-header size {phentsize}")
    if phoff + phentsize * phnum > len(data):
        raise ArtifactError(f"{logical_name}: truncated program-header table")

    loads: list[tuple[int, int, int]] = []
    dynamic_segment: tuple[int, int] | None = None
    has_interpreter = False
    for index in range(phnum):
        fields = struct.unpack_from(ph_format, data, phoff + index * phentsize)
        if fields[0] == PT_INTERP:
            if elf_class == 1:
                interpreter_offset, interpreter_size = fields[1], fields[4]
            else:
                interpreter_offset, interpreter_size = fields[2], fields[5]
            if interpreter_size == 0 or interpreter_offset + interpreter_size > len(
                data
            ):
                raise ArtifactError(f"{logical_name}: invalid PT_INTERP segment")
            has_interpreter = True
        if fields[0] != PT_LOAD:
            if fields[0] == PT_DYNAMIC:
                if elf_class == 1:
                    dynamic_offset, dynamic_size = fields[1], fields[4]
                else:
                    dynamic_offset, dynamic_size = fields[2], fields[5]
                if dynamic_segment is not None:
                    raise ArtifactError(f"{logical_name}: multiple PT_DYNAMIC segments")
                dynamic_segment = (dynamic_offset, dynamic_size)
            continue
        if elf_class == 1:
            offset, virtual_address, file_size, alignment = (
                fields[1],
                fields[2],
                fields[4],
                fields[7],
            )
        else:
            offset, virtual_address, file_size, alignment = (
                fields[2],
                fields[3],
                fields[5],
                fields[7],
            )
        loads.append((offset, virtual_address, file_size))
        if alignment < PAGE_SIZE:
            raise ArtifactError(
                f"{logical_name}: PT_LOAD[{index}] alignment is {alignment}, "
                f"requires at least {PAGE_SIZE}",
            )
        if (virtual_address - offset) % PAGE_SIZE != 0:
            raise ArtifactError(
                f"{logical_name}: PT_LOAD[{index}] offset and address are not 16 KiB congruent",
            )
    if not loads:
        raise ArtifactError(f"{logical_name}: ELF has no PT_LOAD segment")
    if require_et_dyn and elf_type != ET_DYN:
        raise ArtifactError(
            f"{logical_name}: required shared library must be ET_DYN, found {elf_type}"
        )
    if require_pie_cli:
        if elf_type != ET_DYN:
            raise ArtifactError(
                f"{logical_name}: executable must be PIE (ELF type ET_DYN), found {elf_type}",
            )
        if entry_point == 0:
            raise ArtifactError(f"{logical_name}: executable has a zero entry point")
        if not has_interpreter:
            raise ArtifactError(f"{logical_name}: executable has no PT_INTERP segment")

    mentioned_abis: set[str] = set()
    for component in logical_name.replace("!", "/").split("/"):
        if component in MACHINE_ABIS.values():
            mentioned_abis.add(component)
    if mentioned_abis and mentioned_abis != {abi}:
        raise ArtifactError(
            f"{logical_name}: path ABI {sorted(mentioned_abis)} disagrees with ELF ABI {abi}",
        )

    inspection.elf_count += 1
    inspection.found_abis.add(abi)
    soname = None
    needed: list[str] = []
    if dynamic_segment is not None and inspect_dynamic:
        dynamic_offset, dynamic_size = dynamic_segment
        entry_size = 8 if elf_class == 1 else 16
        dynamic_format = f"{endian}iI" if elf_class == 1 else f"{endian}qQ"
        if (
            dynamic_size < entry_size
            or dynamic_size % entry_size
            or dynamic_offset + dynamic_size > len(data)
        ):
            raise ArtifactError(f"{logical_name}: invalid PT_DYNAMIC segment")
        dynamic: list[tuple[int, int]] = []
        terminated = False
        for offset in range(dynamic_offset, dynamic_offset + dynamic_size, entry_size):
            tag, value = struct.unpack_from(dynamic_format, data, offset)
            if tag == DT_NULL:
                terminated = True
                break
            dynamic.append((tag, value))
        if not terminated:
            raise ArtifactError(f"{logical_name}: unterminated PT_DYNAMIC segment")
        if any(tag in {DT_RPATH, DT_RUNPATH, DT_TEXTREL} for tag, _ in dynamic):
            raise ArtifactError(f"{logical_name}: forbidden RPATH or text relocation")
        if any(tag == DT_FLAGS and value & DF_TEXTREL for tag, value in dynamic):
            raise ArtifactError(f"{logical_name}: forbidden text relocation flag")
        string_tables = [value for tag, value in dynamic if tag == DT_STRTAB]
        string_sizes = [value for tag, value in dynamic if tag == DT_STRSZ]
        string_offsets = [
            value for tag, value in dynamic if tag in {DT_NEEDED, DT_SONAME}
        ]
        if string_offsets:
            if len(string_tables) != 1 or len(string_sizes) != 1:
                raise ArtifactError(f"{logical_name}: invalid dynamic string table")
            table_address = string_tables[0]
            table_size = string_sizes[0]
            table_offset = None
            for load_offset, load_address, load_size in loads:
                if load_address <= table_address < load_address + load_size:
                    table_offset = load_offset + table_address - load_address
                    break
            if (
                table_offset is None
                or table_size == 0
                or table_offset + table_size > len(data)
            ):
                raise ArtifactError(f"{logical_name}: dynamic string table is out of bounds")

            def dynamic_string(offset: int) -> str:
                if offset >= table_size:
                    raise ArtifactError(f"{logical_name}: dynamic string offset is invalid")
                start = table_offset + offset
                end = data.find(b"\0", start, table_offset + table_size)
                if end < 0:
                    raise ArtifactError(f"{logical_name}: unterminated dynamic string")
                try:
                    return data[start:end].decode("ascii")
                except UnicodeDecodeError as error:
                    raise ArtifactError(
                        f"{logical_name}: non-ASCII dynamic dependency",
                    ) from error

            sonames = [dynamic_string(value) for tag, value in dynamic if tag == DT_SONAME]
            if len(sonames) > 1:
                raise ArtifactError(f"{logical_name}: multiple SONAME values")
            soname = sonames[0] if sonames else None
            needed = [dynamic_string(value) for tag, value in dynamic if tag == DT_NEEDED]
    return NativeMetadata(abi, soname, tuple(sorted(needed)), dynamic_segment is not None)


def validate_s1a_native(
    basename: str,
    logical_name: str,
    metadata: NativeMetadata,
) -> None:
    if basename == "libc++_shared.so":
        expected_soname = basename
        required = set()
    elif basename == "libmecab.so.2":
        expected_soname = basename
        required = {"libc++_shared.so"}
    else:
        expected_soname = None
        required = {"libmecab.so.2", "libpython3.12.so"}
    if not metadata.has_dynamic:
        raise ArtifactError(f"{logical_name}: S1a native payload has no PT_DYNAMIC")
    if metadata.soname != expected_soname:
        raise ArtifactError(
            f"{logical_name}: SONAME is {metadata.soname!r}, expected {expected_soname!r}",
        )
    needed = set(metadata.needed)
    allowed = required | ANDROID_SYSTEM_LIBS
    if not required.issubset(needed) or not needed.issubset(allowed):
        raise ArtifactError(
            f"{logical_name}: native dependencies are {sorted(needed)}, "
            f"required {sorted(required)}",
        )


def reject_base_unidic_entry(path: PurePosixPath, logical_name: str) -> None:
    normalized = path.as_posix().replace("\\", "/").casefold()
    components = tuple(part for part in normalized.split("/") if part)
    basename = components[-1] if components else ""
    if basename in UNIDIC_PAYLOAD_NAMES:
        raise ArtifactError(f"{logical_name}: UniDic payload is forbidden in base")
    for index in range(len(components) - 1):
        if (
            components[index] in {"unidic_lite", "unidic-lite"}
            and components[index + 1] == "dicdir"
        ):
            raise ArtifactError(
                f"{logical_name}: UniDic dicdir layout is forbidden in base"
            )
    if "unidic" in normalized and basename.endswith(UNIDIC_ARCHIVE_SUFFIXES):
        raise ArtifactError(f"{logical_name}: UniDic archive is forbidden in base")


def inspect_zip(
    source: Path | BytesIO,
    logical_name: str,
    inspection: Inspection,
    depth: int = 0,
    inside_base_module: bool | None = None,
    requirement_owner: str | None = None,
    requirement_depth: int = 0,
) -> None:
    if depth > MAX_ARCHIVE_DEPTH:
        raise ArtifactError(
            f"{logical_name}: archive nesting exceeds {MAX_ARCHIVE_DEPTH}"
        )
    try:
        archive = zipfile.ZipFile(source)
    except zipfile.BadZipFile as error:
        raise ArtifactError(f"{logical_name}: invalid ZIP/IMY archive") from error

    with archive:
        members = archive.infolist()
        if requirement_owner is not None and requirement_depth == 0:
            if requirement_owner in inspection.requirement_member_counts:
                raise ArtifactError(
                    f"{logical_name}: requirement IMY owner was inspected more than once"
                )
            inspection.requirement_member_counts[requirement_owner] = len(members)
        seen_entries: set[str] = set()
        for info in members:
            entry_path = safe_entry_name(info.filename, logical_name)
            entry_name = f"{logical_name}!/{entry_path.as_posix()}"
            direct_path = entry_path.as_posix()
            if direct_path in seen_entries:
                raise ArtifactError(
                    f"{logical_name}: duplicate archive entry {direct_path!r}"
                )
            seen_entries.add(direct_path)
            required_direct = (
                depth == 0 and direct_path in inspection.required_direct_entries
            )
            if depth == 0:
                is_aab = logical_name.casefold().endswith(".aab")
                entry_in_base = not is_aab or (
                    bool(entry_path.parts) and entry_path.parts[0].casefold() == "base"
                )
            else:
                entry_in_base = bool(inside_base_module)
            if inspection.reject_base_unidic and entry_in_base:
                reject_base_unidic_entry(entry_path, entry_name)
            if info.is_dir():
                continue
            folded_name = entry_name.casefold()
            for forbidden in inspection.forbidden:
                if forbidden.casefold() in folded_name:
                    raise ArtifactError(
                        f"{entry_name}: forbidden release entry {forbidden!r}"
                    )

            basename = entry_path.name
            is_app_imy = basename == "app.imy"
            is_requirement_imy = (
                basename.startswith("requirements-") and basename.endswith(".imy")
            )
            if is_app_imy or is_requirement_imy:
                if depth != 0:
                    raise ArtifactError(
                        f"{entry_name}: Chaquopy IMY must be a direct outer artifact entry"
                    )
                is_aab = logical_name.casefold().endswith(".aab")
                prefix = "base/assets/chaquopy" if is_aab else "assets/chaquopy"
                canonical_path = f"{prefix}/{basename}"
                if direct_path != canonical_path:
                    raise ArtifactError(
                        f"{entry_name}: Chaquopy IMY path is not canonical; "
                        f"expected {canonical_path!r}"
                    )
                if is_requirement_imy:
                    expected_requirement_imys = {"requirements-common.imy"} | {
                            f"requirements-{abi}.imy"
                            for abi in inspection.allowed_abis
                    }
                    if inspection.require_s1a:
                        expected_requirement_imys = s1a_requirement_imys(
                            inspection.allowed_abis
                        )
                    if basename not in expected_requirement_imys:
                        raise ArtifactError(
                            f"{entry_name}: unexpected requirement IMY for artifact ABI set"
                        )
            if is_app_imy:
                inspection.app_imy_count += 1
            if is_requirement_imy:
                inspection.requirement_imys.add(basename)
            if requirement_owner:
                payload_name = entry_path.as_posix()
                previous_owners = inspection.requirement_owners.setdefault(
                    payload_name, set()
                )
                candidate_owners = previous_owners | {requirement_owner}
                distinct_owner = bool(previous_owners) and (
                    requirement_owner not in previous_owners
                )
                owners_are_manifest_natives = all(
                    (owner, payload_name) in inspection.expected_natives
                    for owner in candidate_owners
                )
                if distinct_owner and not owners_are_manifest_natives:
                    raise ArtifactError(
                        f"{entry_name}: requirement payload is duplicated in "
                        f"{sorted(previous_owners)} and {requirement_owner}",
                    )
                previous_owners.add(requirement_owner)
            if basename in EXECUTABLE_NATIVE_NAMES:
                parts = entry_path.parts
                direct_apk = len(parts) == 3 and parts[0] == "lib"
                direct_aab = len(parts) == 4 and parts[0:2] == ("base", "lib")
                if depth != 0 or not (direct_apk or direct_aab):
                    raise ArtifactError(
                        f"{entry_name}: executable must be a direct Android native-library entry",
                    )

            with archive.open(info) as stream:
                prefix = stream.read(4)
                is_elf = prefix == ELF_MAGIC
                is_archive = prefix in ZIP_MAGICS or basename.endswith(".imy")
                payload_name = entry_path.as_posix()
                attribution = inspection.expected_attributions.get(payload_name)
                expected_native = inspection.expected_natives.get(
                    (requirement_owner or "", payload_name)
                )
                manifest_native_path = any(
                    path == payload_name
                    for _, path in inspection.expected_natives
                )
                if attribution is not None and (
                    requirement_owner != "requirements-common.imy"
                    or requirement_depth != 0
                ):
                    raise ArtifactError(
                        f"{entry_name}: S1a attribution must be a direct member of "
                        "requirements-common.imy"
                    )
                if manifest_native_path and (
                    expected_native is None or requirement_depth != 0
                ):
                    raise ArtifactError(
                        f"{entry_name}: S1a native payload must be a direct member of its "
                        "manifest-selected requirements IMY"
                    )
                if expected_native is not None and not is_elf:
                    raise ArtifactError(
                        f"{entry_name}: manifest S1a native payload is not an ELF"
                    )
                if not (is_elf or is_archive or attribution is not None):
                    continue
                size_limit = (
                    MAX_ATTRIBUTION_SIZE
                    if attribution is not None
                    else MAX_NESTED_ARCHIVE_SIZE
                )
                if info.file_size > size_limit:
                    raise ArtifactError(
                        f"{entry_name}: inspected entry exceeds {size_limit} bytes",
                    )
                payload = prefix + stream.read()

            if attribution is not None:
                actual_hash = hashlib.sha256(payload).hexdigest()
                if actual_hash != attribution.sha256:
                    raise ArtifactError(
                        f"{entry_name}: S1a attribution hash differs from the wheel manifest"
                    )
                inspection.found_attributions.add(entry_path.as_posix())
                inspection.attribution_text.setdefault(attribution.package, []).append(
                    payload.lower()
                )
                continue

            if is_elf:
                native_package = s1a_native_package(entry_path)
                metadata = parse_elf(
                    payload,
                    entry_name,
                    inspection,
                    require_pie_cli=basename in EXECUTABLE_NATIVE_NAMES,
                    require_et_dyn=required_direct,
                    inspect_dynamic=(
                        inspection.require_s1a and native_package is not None
                    ),
                )
                if required_direct:
                    inspection.found_required_entries.add(direct_path)
                if inspection.require_s1a and native_package is not None:
                    if expected_native is None or requirement_depth != 0:
                        raise ArtifactError(
                            f"{entry_name}: unexpected S1a native payload location"
                        )
                    if native_package != expected_native.package:
                        raise ArtifactError(
                            f"{entry_name}: S1a native payload package mismatch"
                        )
                    if metadata.abi != expected_native.abi:
                        raise ArtifactError(
                            f"{entry_name}: S1a native ABI is {metadata.abi}, "
                            f"expected {expected_native.abi}"
                        )
                    validate_s1a_native(basename, entry_name, metadata)
                    if hashlib.sha256(payload).hexdigest() != expected_native.sha256:
                        raise ArtifactError(
                            f"{entry_name}: S1a native hash differs from the wheel manifest"
                        )
                    native_key = (requirement_owner or "", expected_native.path)
                    inspection.found_natives[native_key] = (
                        inspection.found_natives.get(native_key, 0) + 1
                    )
                    if inspection.found_natives[native_key] != 1:
                        raise ArtifactError(
                            f"{entry_name}: S1a native payload appears more than once"
                        )
                    inspection.s1a_payloads.add(
                        "fugashi-extension"
                        if native_package == "fugashi"
                        else basename
                    )
            else:
                child_owner = requirement_owner
                child_requirement_depth = requirement_depth + 1 if child_owner else 0
                if basename.startswith("requirements-") and basename.endswith(".imy"):
                    if requirement_owner is not None and inspection.require_s1a:
                        raise ArtifactError(f"{entry_name}: nested requirement IMY is forbidden")
                    child_owner = basename
                    child_requirement_depth = 0
                inspect_zip(
                    BytesIO(payload),
                    entry_name,
                    inspection,
                    depth + 1,
                    entry_in_base,
                    child_owner,
                    child_requirement_depth,
                )


def inspect_artifact(args: argparse.Namespace) -> Inspection:
    if not args.artifact.is_file():
        raise ArtifactError(f"artifact not found: {args.artifact}")
    required_entries: set[str] = set()
    for name in args.require_entry:
        normalized = safe_entry_name(name, str(args.artifact)).as_posix()
        if normalized != name or name.endswith("/"):
            raise ArtifactError(f"invalid required direct archive entry: {name!r}")
        required_entries.add(name)
    if args.require_s1a and args.s1a_manifest is None:
        raise ArtifactError("--require-s1a requires --s1a-manifest")
    if args.s1a_manifest is not None and not args.require_s1a:
        raise ArtifactError("--s1a-manifest requires --require-s1a")
    allowed_abis = set(args.allow_abi)
    if args.s1a_manifest is not None:
        expected_attributions, expected_natives = load_s1a_inventory(
            args.s1a_manifest,
            allowed_abis,
        )
    else:
        expected_attributions, expected_natives = {}, {}
    inspection = Inspection(
        allowed_abis=allowed_abis,
        forbidden=tuple(args.forbid_entry),
        required_direct_entries=required_entries,
        reject_base_unidic=args.reject_base_unidic,
        require_s1a=args.require_s1a,
        expected_attributions=expected_attributions,
        expected_natives=expected_natives,
    )
    inspect_zip(args.artifact, args.artifact.name, inspection)
    if inspection.elf_count == 0:
        raise ArtifactError(f"{args.artifact}: no ELF payloads found")
    if inspection.found_abis != inspection.allowed_abis:
        raise ArtifactError(
            f"{args.artifact}: found ABIs {sorted(inspection.found_abis)}, "
            f"expected exactly {sorted(inspection.allowed_abis)}",
        )
    if args.require_app_imy and inspection.app_imy_count == 0:
        raise ArtifactError(
            f"{args.artifact}: Chaquopy app.imy was not recursively inspected"
        )
    missing_entries = (
        inspection.required_direct_entries - inspection.found_required_entries
    )
    if missing_entries:
        raise ArtifactError(
            f"{args.artifact}: missing required direct archive entries "
            f"{sorted(missing_entries)!r}"
        )
    if args.require_s1a:
        expected_imys = s1a_requirement_imys(inspection.allowed_abis)
        if expected_imys != inspection.requirement_imys:
            missing_imys = sorted(expected_imys - inspection.requirement_imys)
            unexpected_imys = sorted(inspection.requirement_imys - expected_imys)
            raise ArtifactError(
                f"{args.artifact}: S1a requirement IMY layout differs; "
                f"missing={missing_imys}, unexpected={unexpected_imys}",
            )
        if len(inspection.allowed_abis) == 1:
            abi = next(iter(inspection.allowed_abis))
            abi_imy = f"requirements-{abi}.imy"
            abi_member_count = inspection.requirement_member_counts.get(abi_imy)
            if abi_member_count != 0:
                raise ArtifactError(
                    f"{args.artifact}: single-ABI {abi_imy} must be empty, "
                    f"found {abi_member_count} members"
                )
        expected_native_keys = set(inspection.expected_natives)
        if (
            set(inspection.found_natives) != expected_native_keys
            or any(count != 1 for count in inspection.found_natives.values())
        ):
            missing = sorted(expected_native_keys - set(inspection.found_natives))
            raise ArtifactError(
                f"{args.artifact}: missing exact S1a native payloads {missing}"
            )
        expected_payloads = {"fugashi-extension", "libmecab.so.2", "libc++_shared.so"}
        if inspection.s1a_payloads != expected_payloads:
            raise ArtifactError(
                f"{args.artifact}: S1a payload set is {sorted(inspection.s1a_payloads)}, "
                f"expected {sorted(expected_payloads)}",
            )
        expected_paths = set(inspection.expected_attributions)
        if inspection.found_attributions != expected_paths:
            raise ArtifactError(
                f"{args.artifact}: missing S1a package attributions "
                f"{sorted(expected_paths - inspection.found_attributions)}"
            )
        for package, markers in S1A_LICENSE_MARKERS.items():
            text = b"\n".join(inspection.attribution_text.get(package, []))
            if not all(marker in text for marker in markers):
                raise ArtifactError(
                    f"{args.artifact}: {package} attribution markers are missing"
                )
    return inspection


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument(
        "--allow-abi",
        action="append",
        required=True,
        choices=sorted(MACHINE_ABIS.values()),
    )
    parser.add_argument("--forbid-entry", action="append", default=[])
    parser.add_argument("--require-entry", action="append", default=[])
    parser.add_argument("--require-app-imy", action="store_true")
    parser.add_argument("--reject-base-unidic", action="store_true")
    parser.add_argument("--require-s1a", action="store_true")
    parser.add_argument("--s1a-manifest", type=Path)
    return parser.parse_args()


def main() -> int:
    try:
        inspection = inspect_artifact(parse_args())
    except (ArtifactError, OSError, struct.error, zipfile.BadZipFile) as error:
        print(f"native artifact verification failed: {error}", file=sys.stderr)
        return 1
    print(
        f"native artifact OK: {inspection.elf_count} ELF files, "
        f"ABIs={','.join(sorted(inspection.found_abis))}, app.imy={inspection.app_imy_count}",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
