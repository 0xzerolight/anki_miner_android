from __future__ import annotations

from argparse import Namespace
from io import BytesIO
import os
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import unittest
import warnings
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
    data_encoding: int = 1,
) -> bytes:
    interpreter_data = b"/system/bin/linker64\0" if interpreter else b""
    program_header_count = 2 if interpreter else 1
    headers_size = 64 + 56 * program_header_count
    data = bytearray(headers_size + len(interpreter_data))
    data[:16] = b"\x7fELF\x02" + bytes((data_encoding, 1)) + bytes(9)
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


def elf32(
    machine: int = 3,
    alignment: int = 16 * 1024,
    *,
    elf_type: int = 3,
) -> bytes:
    data = bytearray(52 + 32)
    data[:16] = b"\x7fELF\x01\x01\x01" + bytes(9)
    struct.pack_into(
        "<HHIIIIIHHHHHH",
        data,
        16,
        elf_type,
        machine,
        1,
        0,
        52,
        0,
        0,
        52,
        32,
        1,
        0,
        0,
        0,
    )
    struct.pack_into(
        "<IIIIIIII", data, 52, 1, 0, 0, 0, len(data), len(data), 5, alignment
    )
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


def archive_entries(entries: list[tuple[str, bytes]]) -> bytes:
    output = BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            for name, payload in entries:
                target.writestr(name, payload)
    return output.getvalue()


def archive(entries: dict[str, bytes]) -> bytes:
    return archive_entries(list(entries.items()))


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

    def inspect_complete(
        self,
        payload: bytes,
        *,
        required: list[str],
        reject_base_unidic: bool = False,
        require_s1a: bool = False,
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
                    require_s1a=require_s1a,
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

    def test_required_shared_library_must_match_abi_elf_class(self) -> None:
        expected = "lib/x86_64/libanki_miner_mecab.so"
        payload = archive({expected: elf32(machine=62)})
        with self.assertRaisesRegex(
            ArtifactError, "ABI x86_64 requires ELF class 2"
        ):
            self.inspect_complete(payload, required=[expected])

    def test_android_elf_must_be_little_endian(self) -> None:
        expected = "lib/x86_64/libanki_miner_mecab.so"
        payload = archive({expected: elf64(data_encoding=2)})
        with self.assertRaisesRegex(ArtifactError, "must be little-endian"):
            self.inspect_complete(payload, required=[expected])

    def test_duplicate_required_entry_is_rejected_before_credit(self) -> None:
        expected = "lib/x86_64/libanki_miner_mecab.so"
        payload = archive_entries(
            [
                (expected, elf64()),
                (expected, b"not an ELF"),
            ]
        )
        with self.assertRaisesRegex(ArtifactError, "duplicate archive entry"):
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

    def test_rejects_zstandard_unidic_archives_in_apk_base(self) -> None:
        for name in (
            "assets/unidic-lite.tar.zst",
            "assets/unidic_lite.tar.zstd",
            "assets/unidic-lite.zst",
            "assets/unidic_lite.zstd",
        ):
            with self.subTest(name=name):
                payload = archive(
                    {
                        "lib/x86_64/libchaquopy.so": elf64(),
                        name: b"opaque Zstandard payload",
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

    def test_rejects_ambiguous_aab_archive_paths_before_base_detection(self) -> None:
        expected = "base/lib/x86_64/libanki_miner_mecab.so"
        for ambiguous in (
            "base\\assets\\sys.dic",
            "base//assets/sys.dic",
            "base/./assets/sys.dic",
        ):
            with self.subTest(ambiguous=ambiguous):
                payload = archive(
                    {
                        expected: elf64(),
                        ambiguous: b"dictionary payload",
                    }
                )
                with self.assertRaisesRegex(ArtifactError, "unsafe archive entry"):
                    self.inspect_complete(
                        payload,
                        required=[expected],
                        reject_base_unidic=True,
                        suffix=".aab",
                    )

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

    def test_shell_wrapper_forwards_value_and_boolean_policy_flags(self) -> None:
        wrapper = SCRIPTS_DIR / "check-native-artifact.sh"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fake_python = root / "python3.13"
            fake_python.write_text(
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > \"$ARG_LOG\"\n",
                encoding="utf-8",
            )
            fake_python.chmod(0o755)
            artifact = root / "fixture.aab"
            artifact.touch()
            argument_log = root / "arguments.txt"
            environment = os.environ.copy()
            environment["ARG_LOG"] = str(argument_log)
            environment["PATH"] = f"{root}:{environment['PATH']}"

            completed = subprocess.run(
                [
                    "bash",
                    str(wrapper),
                    "--artifact",
                    str(artifact),
                    "--allow-abi",
                    "arm64-v8a",
                    "--forbid-entry",
                    "debug-only",
                    "--require-entry",
                    "base/lib/arm64-v8a/libanki_miner_mecab.so",
                    "--require-app-imy",
                    "--reject-base-unidic",
                    "--require-s1a",
                ],
                cwd=SCRIPTS_DIR.parent,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            forwarded = argument_log.read_text(encoding="utf-8").splitlines()[1:]
            self.assertIn("--allow-abi", forwarded)
            self.assertIn("arm64-v8a", forwarded)
            self.assertIn("--forbid-entry", forwarded)
            self.assertIn("--require-entry", forwarded)
            self.assertIn("--require-app-imy", forwarded)
            self.assertIn("--reject-base-unidic", forwarded)
            self.assertIn("--require-s1a", forwarded)


if __name__ == "__main__":
    unittest.main()
