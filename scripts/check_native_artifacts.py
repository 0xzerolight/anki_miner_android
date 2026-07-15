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


class ArtifactError(RuntimeError):
    pass


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
) -> None:
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

    load_count = 0
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
            continue
        load_count += 1
        if elf_class == 1:
            offset, virtual_address, alignment = fields[1], fields[2], fields[7]
        else:
            offset, virtual_address, alignment = fields[2], fields[3], fields[7]
        if alignment < PAGE_SIZE:
            raise ArtifactError(
                f"{logical_name}: PT_LOAD[{index}] alignment is {alignment}, "
                f"requires at least {PAGE_SIZE}",
            )
        if (virtual_address - offset) % PAGE_SIZE != 0:
            raise ArtifactError(
                f"{logical_name}: PT_LOAD[{index}] offset and address are not 16 KiB congruent",
            )
    if load_count == 0:
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
                parse_elf(
                    payload,
                    entry_name,
                    inspection,
                    require_pie_cli=basename in EXECUTABLE_NATIVE_NAMES,
                )
            else:
                inspect_zip(BytesIO(payload), entry_name, inspection, depth + 1)


def inspect_artifact(args: argparse.Namespace) -> Inspection:
    if not args.artifact.is_file():
        raise ArtifactError(f"artifact not found: {args.artifact}")
    inspection = Inspection(set(args.allow_abi), tuple(args.forbid_entry))
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
