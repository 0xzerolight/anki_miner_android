#!/usr/bin/env python3
"""Recursively verify native payloads in Android archives and Chaquopy IMYs."""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from io import BytesIO
from pathlib import Path, PurePosixPath
import struct
import sys
import zipfile


PAGE_SIZE = 16 * 1024
MAX_ARCHIVE_DEPTH = 12
MAX_NESTED_ARCHIVE_SIZE = 1024 * 1024 * 1024
ELF_MAGIC = b"\x7fELF"
ZIP_MAGICS = (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")
MACHINE_ABIS = {
    3: "x86",
    40: "armeabi-v7a",
    62: "x86_64",
    183: "arm64-v8a",
}
EXECUTABLE_NATIVE_NAMES = {"libffmpeg.so", "libffprobe.so"}
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


class ArtifactError(RuntimeError):
    pass


@dataclass(frozen=True)
class NativeMetadata:
    abi: str
    soname: str | None
    needed: tuple[str, ...]
    has_dynamic: bool


@dataclass
class Inspection:
    allowed_abis: set[str]
    forbidden: tuple[str, ...]
    elf_count: int = 0
    found_abis: set[str] = field(default_factory=set)
    app_imy_count: int = 0
    requirement_imys: set[str] = field(default_factory=set)
    requirement_owners: dict[str, str] = field(default_factory=dict)
    s1a_payloads: set[str] = field(default_factory=set)
    require_s1a: bool = False


def safe_entry_name(name: str, archive_name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise ArtifactError(f"{archive_name}: unsafe archive entry {name!r}")
    return path


def parse_elf(
    data: bytes,
    logical_name: str,
    inspection: Inspection,
    *,
    require_pie_cli: bool = False,
) -> NativeMetadata:
    if len(data) < 52 or data[:4] != ELF_MAGIC:
        raise ArtifactError(f"{logical_name}: truncated ELF header")
    elf_class = data[4]
    data_encoding = data[5]
    if data_encoding == 1:
        endian = "<"
    elif data_encoding == 2:
        endian = ">"
    else:
        raise ArtifactError(f"{logical_name}: unsupported ELF byte order {data_encoding}")

    elf_type = struct.unpack_from(f"{endian}H", data, 16)[0]
    machine = struct.unpack_from(f"{endian}H", data, 18)[0]
    abi = MACHINE_ABIS.get(machine)
    if abi is None:
        raise ArtifactError(f"{logical_name}: unsupported ELF machine {machine}")
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
            if interpreter_size == 0 or interpreter_offset + interpreter_size > len(data):
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
    if dynamic_segment is not None:
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
        required = {"libc++_shared.so", "libmecab.so.2", "libpython3.13.so"}
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


def inspect_zip(
    source: Path | BytesIO,
    logical_name: str,
    inspection: Inspection,
    depth: int = 0,
) -> None:
    if depth > MAX_ARCHIVE_DEPTH:
        raise ArtifactError(f"{logical_name}: archive nesting exceeds {MAX_ARCHIVE_DEPTH}")
    try:
        archive = zipfile.ZipFile(source)
    except zipfile.BadZipFile as error:
        raise ArtifactError(f"{logical_name}: invalid ZIP/IMY archive") from error

    with archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            entry_path = safe_entry_name(info.filename, logical_name)
            entry_name = f"{logical_name}!/{entry_path.as_posix()}"
            folded_name = entry_name.casefold()
            for forbidden in inspection.forbidden:
                if forbidden.casefold() in folded_name:
                    raise ArtifactError(f"{entry_name}: forbidden release entry {forbidden!r}")

            basename = entry_path.name
            if basename == "app.imy":
                inspection.app_imy_count += 1
            if basename.startswith("requirements-") and basename.endswith(".imy"):
                inspection.requirement_imys.add(basename)
            requirement_owner = next(
                (
                    component
                    for component in logical_name.replace("!", "/").split("/")
                    if component.startswith("requirements-") and component.endswith(".imy")
                ),
                None,
            )
            if requirement_owner:
                payload_name = entry_path.as_posix()
                previous_owner = inspection.requirement_owners.setdefault(
                    payload_name,
                    requirement_owner,
                )
                if previous_owner != requirement_owner:
                    raise ArtifactError(
                        f"{entry_name}: requirement payload is duplicated in "
                        f"{previous_owner} and {requirement_owner}",
                    )
                if basename in {"libmecab.so.2", "libc++_shared.so"}:
                    inspection.s1a_payloads.add(basename)
                if "fugashi" in entry_path.parts and basename.endswith(".so"):
                    inspection.s1a_payloads.add("fugashi-extension")
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
                if not (is_elf or is_archive):
                    continue
                if info.file_size > MAX_NESTED_ARCHIVE_SIZE:
                    raise ArtifactError(
                        f"{entry_name}: native/archive entry exceeds {MAX_NESTED_ARCHIVE_SIZE} bytes",
                    )
                payload = prefix + stream.read()

            if is_elf:
                metadata = parse_elf(
                    payload,
                    entry_name,
                    inspection,
                    require_pie_cli=basename in EXECUTABLE_NATIVE_NAMES,
                )
                if inspection.require_s1a and requirement_owner:
                    is_fugashi = "fugashi" in entry_path.parts and basename.endswith(".so")
                    if basename in {"libmecab.so.2", "libc++_shared.so"} or is_fugashi:
                        validate_s1a_native(basename, entry_name, metadata)
            else:
                inspect_zip(BytesIO(payload), entry_name, inspection, depth + 1)


def inspect_artifact(args: argparse.Namespace) -> Inspection:
    if not args.artifact.is_file():
        raise ArtifactError(f"artifact not found: {args.artifact}")
    inspection = Inspection(
        set(args.allow_abi),
        tuple(args.forbid_entry),
        require_s1a=args.require_s1a,
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
        raise ArtifactError(f"{args.artifact}: Chaquopy app.imy was not recursively inspected")
    if args.require_s1a:
        expected_imys = {"requirements-common.imy"} | {
            f"requirements-{abi}.imy" for abi in inspection.allowed_abis
        }
        if not expected_imys.issubset(inspection.requirement_imys):
            raise ArtifactError(
                f"{args.artifact}: missing S1a requirement IMYs "
                f"{sorted(expected_imys - inspection.requirement_imys)}",
            )
        expected_payloads = {"fugashi-extension", "libmecab.so.2", "libc++_shared.so"}
        if inspection.s1a_payloads != expected_payloads:
            raise ArtifactError(
                f"{args.artifact}: S1a payload set is {sorted(inspection.s1a_payloads)}, "
                f"expected {sorted(expected_payloads)}",
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
    parser.add_argument("--require-app-imy", action="store_true")
    parser.add_argument("--require-s1a", action="store_true")
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
