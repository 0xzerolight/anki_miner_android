from __future__ import annotations

from argparse import Namespace
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
    inspect_artifact,
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
    struct.pack_into(
        "<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment
    )
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
    ) -> Inspection:
        inspection = Inspection(allowed or {"x86_64"}, forbidden)
        with tempfile.NamedTemporaryFile(suffix=".apk") as artifact:
            artifact.write(payload)
            artifact.flush()
            inspect_zip(Path(artifact.name), "fixture.apk", inspection)
        return inspection

    def inspect_complete(
        self,
        payload: bytes,
        *,
        required: list[str],
        reject_base_unidic: bool = False,
        suffix: str = ".apk",
    ) -> Inspection:
        with tempfile.NamedTemporaryFile(suffix=suffix) as artifact:
            artifact.write(payload)
            artifact.flush()
            return inspect_artifact(
                Namespace(
                    artifact=Path(artifact.name),
                    allow_abi=["x86_64"],
                    forbid_entry=[],
                    require_entry=required,
                    require_app_imy=False,
                    reject_base_unidic=reject_base_unidic,
                )
            )

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

    def test_required_direct_entry_cannot_be_satisfied_by_unrelated_elf(self) -> None:
        payload = archive({"lib/x86_64/libchaquopy.so": elf64()})
        with self.assertRaisesRegex(ArtifactError, "missing required direct"):
            self.inspect_complete(
                payload,
                required=["lib/x86_64/libanki_miner_mecab.so"],
            )

    def test_required_entry_placeholder_cannot_be_satisfied_by_unrelated_elf(
        self,
    ) -> None:
        expected = "lib/x86_64/libanki_miner_mecab.so"
        payload = archive(
            {
                expected: b"not an ELF",
                "lib/x86_64/libchaquopy.so": elf64(),
            }
        )
        with self.assertRaisesRegex(ArtifactError, "missing required direct"):
            self.inspect_complete(payload, required=[expected])

    def test_required_shared_library_must_be_et_dyn(self) -> None:
        expected = "lib/x86_64/libanki_miner_mecab.so"
        payload = archive({expected: elf64(elf_type=2)})
        with self.assertRaisesRegex(ArtifactError, "must be ET_DYN"):
            self.inspect_complete(payload, required=[expected])

    def test_required_direct_entry_is_exact_and_case_sensitive(self) -> None:
        expected = "lib/x86_64/libanki_miner_mecab.so"
        result = self.inspect_complete(
            archive({expected: elf64()}),
            required=[expected],
        )
        self.assertEqual({expected}, result.found_required_entries)

    def test_rejects_unidic_payload_anywhere_in_apk_base(self) -> None:
        payload = archive(
            {
                "lib/x86_64/libchaquopy.so": elf64(),
                "assets/unrelated/matrix.bin": b"dictionary payload",
            }
        )
        with self.assertRaisesRegex(ArtifactError, "UniDic payload"):
            self.inspect_complete(
                payload,
                required=[],
                reject_base_unidic=True,
            )

    def test_rejects_empty_unidic_dicdir_layout_in_apk_base(self) -> None:
        payload = archive(
            {
                "lib/x86_64/libchaquopy.so": elf64(),
                "assets/unidic_lite/dicdir/": b"",
            }
        )
        with self.assertRaisesRegex(ArtifactError, "UniDic dicdir layout"):
            self.inspect_complete(
                payload,
                required=[],
                reject_base_unidic=True,
            )

    def test_rejects_unidic_layout_inside_nested_imy_archive(self) -> None:
        nested_zip = archive(
            {"python/unidic_lite/dicdir/sys.dic": b"dictionary payload"}
        )
        app_imy = archive({"chaquopy/assets/runtime.zip": nested_zip})
        payload = archive(
            {
                "lib/x86_64/libchaquopy.so": elf64(),
                "assets/chaquopy/app.imy": app_imy,
            }
        )
        with self.assertRaisesRegex(ArtifactError, "UniDic"):
            self.inspect_complete(
                payload,
                required=[],
                reject_base_unidic=True,
            )

    def test_rejects_named_unidic_archive_without_opening_it(self) -> None:
        payload = archive(
            {
                "lib/x86_64/libchaquopy.so": elf64(),
                "assets/data/unidic-lite.tar.gz": b"opaque archive",
            }
        )
        with self.assertRaisesRegex(ArtifactError, "UniDic archive"):
            self.inspect_complete(
                payload,
                required=[],
                reject_base_unidic=True,
            )

    def test_allows_unidic_in_separate_aab_asset_pack(self) -> None:
        expected = "base/lib/x86_64/libanki_miner_mecab.so"
        payload = archive(
            {
                expected: elf64(),
                "unidic_pack/assets/unidic_lite/dicdir/sys.dic": b"asset pack",
            }
        )
        result = self.inspect_complete(
            payload,
            required=[expected],
            reject_base_unidic=True,
            suffix=".aab",
        )
        self.assertEqual({expected}, result.found_required_entries)

    def test_rejects_unidic_inside_aab_base_module(self) -> None:
        expected = "base/lib/x86_64/libanki_miner_mecab.so"
        payload = archive(
            {
                expected: elf64(),
                "base/assets/unidic_lite/dicdir/matrix.bin": b"dictionary payload",
            }
        )
        with self.assertRaisesRegex(ArtifactError, "UniDic"):
            self.inspect_complete(
                payload,
                required=[expected],
                reject_base_unidic=True,
                suffix=".aab",
            )


if __name__ == "__main__":
    unittest.main()
