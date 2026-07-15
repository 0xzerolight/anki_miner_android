from __future__ import annotations

from io import BytesIO
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from check_native_artifacts import (  # noqa: E402
    ArtifactError,
    Inspection,
    inspect_zip,
)


def elf64(
    machine: int = 62,
    alignment: int = 16 * 1024,
    *,
    elf_type: int = 3,
    entry_point: int = 0,
    interpreter: bool = False,
) -> bytes:
    interpreter_data = b"/system/bin/linker64\0" if interpreter else b""
    program_header_count = 2 if interpreter else 1
    headers_size = 64 + 56 * program_header_count
    data = bytearray(headers_size + len(interpreter_data))
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into(
        "<HHIQQQIHHHHHH",
        data,
        16,
        elf_type,
        machine,
        1,
        entry_point,
        64,
        0,
        0,
        64,
        56,
        program_header_count,
        0,
        0,
        0,
    )
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    if interpreter:
        struct.pack_into(
            "<IIQQQQQQ",
            data,
            120,
            3,
            4,
            headers_size,
            0,
            0,
            len(interpreter_data),
            len(interpreter_data),
            1,
        )
        data[headers_size:] = interpreter_data
    return bytes(data)


def pie_cli() -> bytes:
    return elf64(entry_point=0x4000, interpreter=True)


def dynamic_elf(
    *,
    machine: int = 62,
    soname: str | None,
    needed: tuple[str, ...],
) -> bytes:
    strings = bytearray(b"\0")
    string_offsets: dict[str, int] = {}
    for value in (*needed, *((soname,) if soname else ())):
        if value not in string_offsets:
            string_offsets[value] = len(strings)
            strings.extend(value.encode("ascii") + b"\0")
    dynamic_count = 3 + len(needed) + (1 if soname else 0)
    dynamic_offset = 64 + 56 * 2
    string_offset = dynamic_offset + dynamic_count * 16
    base_address = 0x4000
    data = bytearray(string_offset + len(strings))
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into(
        "<HHIQQQIHHHHHH",
        data,
        16,
        3,
        machine,
        1,
        0,
        64,
        0,
        0,
        64,
        56,
        2,
        0,
        0,
        0,
    )
    struct.pack_into(
        "<IIQQQQQQ",
        data,
        64,
        1,
        5,
        0,
        base_address,
        0,
        len(data),
        len(data),
        16 * 1024,
    )
    struct.pack_into(
        "<IIQQQQQQ",
        data,
        120,
        2,
        4,
        dynamic_offset,
        base_address + dynamic_offset,
        0,
        dynamic_count * 16,
        dynamic_count * 16,
        8,
    )
    entries = [
        (5, base_address + string_offset),
        (10, len(strings)),
        *((1, string_offsets[value]) for value in needed),
    ]
    if soname:
        entries.append((14, string_offsets[soname]))
    entries.append((0, 0))
    for index, entry in enumerate(entries):
        struct.pack_into("<qQ", data, dynamic_offset + index * 16, *entry)
    data[string_offset:] = strings
    return bytes(data)


def archive(entries: dict[str, bytes]) -> bytes:
    output = BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
        for name, payload in entries.items():
            target.writestr(name, payload)
    return output.getvalue()


class NativeArtifactTest(unittest.TestCase):
    def inspect(
        self,
        payload: bytes,
        *,
        allowed: set[str] | None = None,
        forbidden: tuple[str, ...] = (),
        require_s1a: bool = False,
    ) -> Inspection:
        inspection = Inspection(
            allowed or {"x86_64"},
            forbidden,
            require_s1a=require_s1a,
        )
        with tempfile.NamedTemporaryFile(suffix=".apk") as artifact:
            artifact.write(payload)
            artifact.flush()
            inspect_zip(Path(artifact.name), "fixture.apk", inspection)
        return inspection

    def test_recurses_into_app_imy_and_accepts_aligned_elf(self) -> None:
        app_imy = archive({"chaquopy/lib/x86_64/libpython.so": elf64()})
        result = self.inspect(archive({"assets/chaquopy/app.imy": app_imy}))
        self.assertEqual(1, result.elf_count)
        self.assertEqual({"x86_64"}, result.found_abis)
        self.assertEqual(1, result.app_imy_count)

    def test_rejects_4k_load_alignment(self) -> None:
        payload = archive({"lib/x86_64/libbad.so": elf64(alignment=4096)})
        with self.assertRaisesRegex(ArtifactError, "requires at least 16384"):
            self.inspect(payload)

    def test_rejects_wrong_abi(self) -> None:
        payload = archive({"lib/arm64-v8a/libbad.so": elf64(machine=183)})
        with self.assertRaisesRegex(ArtifactError, "contains ABI arm64-v8a"):
            self.inspect(payload)

    def test_rejects_path_abi_disagreeing_with_elf_header(self) -> None:
        payload = archive({"lib/x86/libbad.so": elf64(machine=62)})
        with self.assertRaisesRegex(ArtifactError, "path ABI.*disagrees"):
            self.inspect(payload)

    def test_finds_forbidden_probe_inside_app_imy(self) -> None:
        app_imy = archive(
            {
                "scaffold_probe.pyc": b"debug-only",
                "chaquopy/lib/x86_64/libpython.so": elf64(),
            },
        )
        with self.assertRaisesRegex(ArtifactError, "forbidden release entry"):
            self.inspect(
                archive({"assets/chaquopy/app.imy": app_imy}),
                forbidden=("scaffold_probe",),
            )

    def test_executable_must_be_direct_native_entry(self) -> None:
        app_imy = archive({"lib/x86_64/libffmpeg.so": elf64()})
        with self.assertRaisesRegex(ArtifactError, "executable must be a direct"):
            self.inspect(archive({"assets/chaquopy/app.imy": app_imy}))

    def test_direct_native_executable_is_accepted(self) -> None:
        result = self.inspect(archive({"lib/x86_64/libffprobe.so": pie_cli()}))
        self.assertEqual(1, result.elf_count)

    def test_rejects_et_exec_native_tool(self) -> None:
        executable = elf64(elf_type=2, entry_point=0x4000, interpreter=True)
        with self.assertRaisesRegex(ArtifactError, "must be PIE"):
            self.inspect(archive({"lib/x86_64/libffmpeg.so": executable}))

    def test_rejects_shared_library_renamed_as_native_tool(self) -> None:
        with self.assertRaisesRegex(ArtifactError, "zero entry point"):
            self.inspect(archive({"lib/x86_64/libffmpeg.so": elf64()}))

    def test_rejects_pie_native_tool_without_interpreter(self) -> None:
        executable = elf64(entry_point=0x4000)
        with self.assertRaisesRegex(ArtifactError, "no PT_INTERP"):
            self.inspect(archive({"lib/x86_64/libffprobe.so": executable}))

    def test_s1a_payloads_revalidate_sonames_and_dependencies_in_imy(self) -> None:
        common = archive(
            {
                "chaquopy/lib/libc++_shared.so": dynamic_elf(
                    soname="libc++_shared.so",
                    needed=("libc.so", "libdl.so"),
                ),
                "chaquopy/lib/libmecab.so.2": dynamic_elf(
                    soname="libmecab.so.2",
                    needed=("libc++_shared.so", "libc.so"),
                ),
                "fugashi/fugashi.so": dynamic_elf(
                    soname=None,
                    needed=(
                        "libc++_shared.so",
                        "libmecab.so.2",
                        "libpython3.13.so",
                        "libc.so",
                    ),
                ),
            },
        )
        payload = archive(
            {
                "assets/chaquopy/requirements-common.imy": common,
                "assets/chaquopy/requirements-x86_64.imy": archive({}),
            },
        )
        result = self.inspect(payload, require_s1a=True)
        self.assertEqual(
            {"fugashi-extension", "libc++_shared.so", "libmecab.so.2"},
            result.s1a_payloads,
        )

    def test_s1a_payload_rejects_wrong_soname_after_packaging(self) -> None:
        common = archive(
            {
                "chaquopy/lib/libmecab.so.2": dynamic_elf(
                    soname="libmecab-wrong.so",
                    needed=("libc++_shared.so",),
                ),
            },
        )
        with self.assertRaisesRegex(ArtifactError, "SONAME"):
            self.inspect(
                archive({"assets/chaquopy/requirements-common.imy": common}),
                require_s1a=True,
            )


if __name__ == "__main__":
    unittest.main()
