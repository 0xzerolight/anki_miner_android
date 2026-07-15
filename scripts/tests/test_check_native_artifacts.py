from __future__ import annotations

from argparse import Namespace
import hashlib
from io import BytesIO
import json
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


S1A_ATTRIBUTIONS = {
    "chaquopy_libcxx-190000.dist-info/LICENSE.TXT": (
        "chaquopy_libcxx",
        b"Apache License version 2.0 with LLVM Exceptions\n",
    ),
    "chaquopy_libmecab-0.996.dist-info/BSD": (
        "chaquopy_libmecab",
        b"Copyright Taku Kudo. Redistribution permitted.\n",
    ),
    "fugashi-1.5.2.dist-info/LICENSE": (
        "fugashi",
        b"Permission is hereby granted to use this software.\n",
    ),
}
S1A_FILENAMES = {
    "arm64-v8a": {
        "chaquopy_libcxx": "chaquopy_libcxx-190000-0-py3-none-android_26_arm64_v8a.whl",
        "chaquopy_libmecab": "chaquopy_libmecab-0.996-0-py3-none-android_26_arm64_v8a.whl",
        "fugashi": "fugashi-1.5.2-0-cp313-cp313-android_26_arm64_v8a.whl",
    },
    "x86_64": {
        "chaquopy_libcxx": "chaquopy_libcxx-190000-0-py3-none-android_26_x86_64.whl",
        "chaquopy_libmecab": "chaquopy_libmecab-0.996-0-py3-none-android_26_x86_64.whl",
        "fugashi": "fugashi-1.5.2-0-cp313-cp313-android_26_x86_64.whl",
    },
}
S1A_NATIVE_PATHS = {
    "chaquopy_libcxx": "chaquopy/lib/libc++_shared.so",
    "chaquopy_libmecab": "chaquopy/lib/libmecab.so.2",
    "fugashi": "fugashi/fugashi.so",
}


class NativeArtifactTest(unittest.TestCase):
    @staticmethod
    def write_s1a_manifest(
        root: Path,
        attributions: dict[str, tuple[str, bytes]] = S1A_ATTRIBUTIONS,
        native_paths: dict[str, str] = S1A_NATIVE_PATHS,
    ) -> Path:
        licenses_by_package: dict[str, list[dict[str, str]]] = {}
        for path, (package, payload) in attributions.items():
            licenses_by_package.setdefault(package, []).append(
                {"path": path, "sha256": hashlib.sha256(payload).hexdigest()}
            )
        wheels = {
            abi: [
                {
                    "filename": S1A_FILENAMES[abi][package],
                    "licenses": sorted(
                        licenses_by_package[package], key=lambda entry: entry["path"]
                    ),
                    "elf": {
                        "path": native_paths[package],
                        "sha256": hashlib.sha256(
                            NativeArtifactTest.valid_s1a_native_entries(abi)[
                                S1A_NATIVE_PATHS[package]
                            ]
                        ).hexdigest(),
                        "abi": abi,
                    },
                }
                for package in ("chaquopy_libcxx", "chaquopy_libmecab", "fugashi")
            ]
            for abi in ("arm64-v8a", "x86_64")
        }
        manifest = root / "manifest.json"
        manifest.write_text(
            json.dumps({"schema": 2, "wheels": wheels}),
            encoding="utf-8",
        )
        return manifest

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
        allowed: set[str] | None = None,
        reject_base_unidic: bool = False,
        require_s1a: bool = False,
        manifest_attributions: dict[str, tuple[str, bytes]] = S1A_ATTRIBUTIONS,
        manifest_native_paths: dict[str, str] = S1A_NATIVE_PATHS,
        suffix: str = ".apk",
    ) -> Inspection:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / f"fixture{suffix}"
            artifact.write_bytes(payload)
            s1a_manifest = (
                self.write_s1a_manifest(
                    root,
                    manifest_attributions,
                    manifest_native_paths,
                )
                if require_s1a
                else None
            )
            return inspect_artifact(
                Namespace(
                    artifact=artifact,
                    allow_abi=sorted(allowed or {"x86_64"}),
                    forbid_entry=[],
                    require_entry=required,
                    require_app_imy=False,
                    reject_base_unidic=reject_base_unidic,
                    require_s1a=require_s1a,
                    s1a_manifest=s1a_manifest,
                )
            )

    def test_recurses_into_app_imy_and_accepts_aligned_elf(self) -> None:
        app_imy = archive({"chaquopy/lib/x86_64/libpython.so": elf64()})
        result = self.inspect(archive({"assets/chaquopy/app.imy": app_imy}))
        self.assertEqual(1, result.elf_count)
        self.assertEqual({"x86_64"}, result.found_abis)
        self.assertEqual(1, result.app_imy_count)

    def test_apk_and_aab_accept_only_canonical_outer_imy_paths(self) -> None:
        app_imy = archive({"chaquopy/lib/x86_64/libpython.so": elf64()})
        for suffix, prefix in (
            (".apk", "assets/chaquopy"),
            (".aab", "base/assets/chaquopy"),
        ):
            with self.subTest(suffix=suffix):
                result = self.inspect_complete(
                    archive(
                        {
                            f"{prefix}/app.imy": app_imy,
                            f"{prefix}/requirements-common.imy": archive({}),
                            f"{prefix}/requirements-x86_64.imy": archive({}),
                        }
                    ),
                    required=[],
                    suffix=suffix,
                )
                self.assertEqual(1, result.app_imy_count)
                self.assertEqual(
                    {"requirements-common.imy", "requirements-x86_64.imy"},
                    result.requirement_imys,
                )

    def test_apk_and_aab_reject_decoy_and_nested_imy_paths(self) -> None:
        for suffix, canonical_prefix, decoy_prefix in (
            (".apk", "assets/chaquopy", "decoy/assets/chaquopy"),
            (".aab", "base/assets/chaquopy", "feature/assets/chaquopy"),
        ):
            with self.subTest(suffix=suffix, case="decoy"):
                payload = archive(
                    {
                        f"{canonical_prefix}/app.imy": archive(
                            {"chaquopy/lib/x86_64/libpython.so": elf64()}
                        ),
                        f"{decoy_prefix}/requirements-common.imy": archive({}),
                    }
                )
                with self.assertRaisesRegex(ArtifactError, "path is not canonical"):
                    self.inspect_complete(payload, required=[], suffix=suffix)

            with self.subTest(suffix=suffix, case="nested"):
                payload = archive(
                    {
                        f"{canonical_prefix}/app.imy": archive(
                            {"nested/requirements-common.imy": archive({})}
                        ),
                    }
                )
                with self.assertRaisesRegex(ArtifactError, "direct outer artifact"):
                    self.inspect_complete(payload, required=[], suffix=suffix)

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
        result = self.inspect_complete(
            self.valid_s1a_artifact(),
            required=[],
            require_s1a=True,
        )
        self.assertEqual(
            {"fugashi-extension", "libc++_shared.so", "libmecab.so.2"},
            result.s1a_payloads,
        )

    @staticmethod
    def valid_s1a_native_entries(abi: str = "x86_64") -> dict[str, bytes]:
        machine = 62 if abi == "x86_64" else 183
        return {
            "chaquopy/lib/libc++_shared.so": dynamic_elf(
                machine=machine,
                soname="libc++_shared.so",
                needed=("libc.so", "libdl.so"),
            ),
            "chaquopy/lib/libmecab.so.2": dynamic_elf(
                machine=machine,
                soname="libmecab.so.2",
                needed=("libc++_shared.so", "libc.so"),
            ),
            "fugashi/fugashi.so": dynamic_elf(
                machine=machine,
                soname=None,
                needed=(
                    "libc++_shared.so",
                    "libmecab.so.2",
                    "libpython3.13.so",
                    "libc.so",
                ),
            ),
        }

    @classmethod
    def valid_s1a_artifact(
        cls,
        *,
        abis: tuple[str, ...] = ("x86_64",),
        suffix: str = ".apk",
        native_entries: dict[str, bytes] | None = None,
        attribution_entries: dict[str, bytes] | None = None,
    ) -> bytes:
        if not abis or len(set(abis)) != len(abis):
            raise ValueError("S1a fixture ABIs must be non-empty and unique")
        prefix = "base/assets/chaquopy" if suffix == ".aab" else "assets/chaquopy"
        common = dict(
            attribution_entries
            if attribution_entries is not None
            else {
                path: payload
                for path, (_, payload) in S1A_ATTRIBUTIONS.items()
            }
        )
        artifact_entries: dict[str, bytes] = {}
        if len(abis) == 1:
            natives = (
                native_entries
                if native_entries is not None
                else cls.valid_s1a_native_entries(abis[0])
            )
            common.update(natives)
        elif native_entries is not None:
            raise ValueError("multi-ABI fixtures require per-ABI native entries")
        artifact_entries[f"{prefix}/requirements-common.imy"] = archive(common)
        for abi in sorted(abis):
            abi_entries = (
                cls.valid_s1a_native_entries(abi) if len(abis) > 1 else {}
            )
            artifact_entries[f"{prefix}/requirements-{abi}.imy"] = archive(
                abi_entries
            )
        return archive(artifact_entries)

    def test_packaged_s1a_requires_exact_manifest_attributions(self) -> None:
        result = self.inspect_complete(
            self.valid_s1a_artifact(),
            required=[],
            require_s1a=True,
        )
        self.assertEqual(set(S1A_ATTRIBUTIONS), result.found_attributions)
        self.assertEqual(3, len(result.found_natives))
        self.assertEqual({1}, set(result.found_natives.values()))

    def test_single_abi_apk_and_aab_use_chaquopy_common_native_layout(self) -> None:
        for suffix in (".apk", ".aab"):
            for abi in ("arm64-v8a", "x86_64"):
                with self.subTest(suffix=suffix, abi=abi):
                    result = self.inspect_complete(
                        self.valid_s1a_artifact(abis=(abi,), suffix=suffix),
                        required=[],
                        allowed={abi},
                        require_s1a=True,
                        suffix=suffix,
                    )
                    self.assertEqual(
                        {
                            "requirements-common.imy",
                            f"requirements-{abi}.imy",
                        },
                        result.requirement_imys,
                    )
                    self.assertEqual(
                        {
                            ("requirements-common.imy", path)
                            for path in S1A_NATIVE_PATHS.values()
                        },
                        set(result.found_natives),
                    )
                    self.assertEqual(
                        0,
                        result.requirement_member_counts[
                            f"requirements-{abi}.imy"
                        ],
                    )

    def test_multi_abi_apk_and_aab_use_per_abi_native_layout(self) -> None:
        abis = {"arm64-v8a", "x86_64"}
        for suffix in (".apk", ".aab"):
            with self.subTest(suffix=suffix):
                result = self.inspect_complete(
                    self.valid_s1a_artifact(
                        abis=("arm64-v8a", "x86_64"),
                        suffix=suffix,
                    ),
                    required=[],
                    allowed=abis,
                    require_s1a=True,
                    suffix=suffix,
                )
                self.assertEqual(
                    {
                        "requirements-common.imy",
                        "requirements-arm64-v8a.imy",
                        "requirements-x86_64.imy",
                    },
                    result.requirement_imys,
                )
                self.assertEqual(
                    {
                        (f"requirements-{abi}.imy", path)
                        for abi in abis
                        for path in S1A_NATIVE_PATHS.values()
                    },
                    set(result.found_natives),
                )

    def test_single_abi_requires_empty_abi_imy_in_apk_and_aab(self) -> None:
        common = {
            **{
                path: data
                for path, (_, data) in S1A_ATTRIBUTIONS.items()
            },
            **self.valid_s1a_native_entries(),
        }
        for suffix, prefix in (
            (".apk", "assets/chaquopy"),
            (".aab", "base/assets/chaquopy"),
        ):
            with self.subTest(suffix=suffix):
                payload = archive(
                    {f"{prefix}/requirements-common.imy": archive(common)}
                )
                with self.assertRaisesRegex(
                    ArtifactError,
                    "S1a requirement IMY layout differs",
                ):
                    self.inspect_complete(
                        payload,
                        required=[],
                        require_s1a=True,
                        suffix=suffix,
                    )

    def test_single_abi_rejects_any_abi_imy_member_in_apk_and_aab(self) -> None:
        common = {
            **{
                path: data
                for path, (_, data) in S1A_ATTRIBUTIONS.items()
            },
            **self.valid_s1a_native_entries(),
        }
        rogue_members = {
            "non-native": ("rogue.txt", b"rogue"),
            "directory": ("rogue/", b""),
            "attribution": (
                "rogue-1.0.dist-info/LICENSE",
                b"rogue attribution",
            ),
        }
        for suffix, prefix in (
            (".apk", "assets/chaquopy"),
            (".aab", "base/assets/chaquopy"),
        ):
            for case, member in rogue_members.items():
                with self.subTest(suffix=suffix, case=case):
                    payload = archive(
                        {
                            f"{prefix}/requirements-common.imy": archive(common),
                            f"{prefix}/requirements-x86_64.imy": archive_entries(
                                [member]
                            ),
                        }
                    )
                    with self.assertRaisesRegex(
                        ArtifactError,
                        "single-ABI requirements-x86_64.imy must be empty",
                    ):
                        self.inspect_complete(
                            payload,
                            required=[],
                            require_s1a=True,
                            suffix=suffix,
                        )

    def test_single_abi_rejects_split_native_layout(self) -> None:
        for suffix, prefix in (
            (".apk", "assets/chaquopy"),
            (".aab", "base/assets/chaquopy"),
        ):
            with self.subTest(suffix=suffix):
                payload = archive(
                    {
                        f"{prefix}/requirements-common.imy": archive(
                            {
                                path: data
                                for path, (_, data) in S1A_ATTRIBUTIONS.items()
                            }
                        ),
                        f"{prefix}/requirements-x86_64.imy": archive(
                            self.valid_s1a_native_entries()
                        ),
                    }
                )
                with self.assertRaisesRegex(
                    ArtifactError,
                    "manifest-selected requirements IMY",
                ):
                    self.inspect_complete(
                        payload,
                        required=[],
                        require_s1a=True,
                        suffix=suffix,
                    )

    def test_multi_abi_native_payload_must_match_its_owner_abi(self) -> None:
        payload = archive(
            {
                "assets/chaquopy/requirements-common.imy": archive(
                    {
                        path: data
                        for path, (_, data) in S1A_ATTRIBUTIONS.items()
                    }
                ),
                "assets/chaquopy/requirements-arm64-v8a.imy": archive(
                    self.valid_s1a_native_entries("x86_64")
                ),
                "assets/chaquopy/requirements-x86_64.imy": archive(
                    self.valid_s1a_native_entries("arm64-v8a")
                ),
            }
        )
        with self.assertRaisesRegex(ArtifactError, "S1a native ABI"):
            self.inspect_complete(
                payload,
                required=[],
                allowed={"arm64-v8a", "x86_64"},
                require_s1a=True,
            )

    def test_s1a_native_duplicate_within_one_owner_is_rejected(self) -> None:
        natives = self.valid_s1a_native_entries()
        duplicate_path = "chaquopy/lib/libc++_shared.so"
        common = archive_entries(
            [
                *(
                    (path, data)
                    for path, (_, data) in S1A_ATTRIBUTIONS.items()
                ),
                *natives.items(),
                (duplicate_path, natives[duplicate_path]),
            ]
        )
        payload = archive(
            {"assets/chaquopy/requirements-common.imy": common}
        )
        with self.assertRaisesRegex(ArtifactError, "duplicate archive entry"):
            self.inspect_complete(payload, required=[], require_s1a=True)

    def test_s1a_manifest_requires_exact_native_payload_paths(self) -> None:
        wrong_paths = {
            "chaquopy_libcxx": "wrong/chaquopy/lib/libc++_shared.so",
            "chaquopy_libmecab": "chaquopy/lib/deeper/libmecab.so.2",
            "fugashi": "fugashi/deeper/fugashi.so",
        }
        for package, native_path in wrong_paths.items():
            with self.subTest(package=package, native_path=native_path):
                manifest_paths = dict(S1A_NATIVE_PATHS)
                manifest_paths[package] = native_path
                with self.assertRaisesRegex(
                    ArtifactError,
                    "manifest native path is unexpected",
                ):
                    self.inspect_complete(
                        self.valid_s1a_artifact(),
                        required=[],
                        require_s1a=True,
                        manifest_native_paths=manifest_paths,
                    )

    def test_s1a_named_placeholders_are_not_credited_by_unrelated_elf(self) -> None:
        common = archive_entries(
            [
                *(
                    (path, data)
                    for path, (_, data) in S1A_ATTRIBUTIONS.items()
                ),
                ("unrelated/valid.so", elf64()),
                ("chaquopy/lib/libc++_shared.so", b"text placeholder"),
                ("chaquopy/lib/libmecab.so.2", b"text placeholder"),
                ("fugashi/fugashi.so", b"text placeholder"),
            ]
        )
        payload = archive(
            {
                "assets/chaquopy/requirements-common.imy": common,
            }
        )
        with self.assertRaisesRegex(ArtifactError, "native payload is not an ELF"):
            self.inspect_complete(payload, required=[], require_s1a=True)

    def test_s1a_valid_native_shape_elsewhere_is_rejected(self) -> None:
        valid = self.valid_s1a_native_entries()
        common = archive_entries(
            [
                *(
                    (path, data)
                    for path, (_, data) in S1A_ATTRIBUTIONS.items()
                ),
                ("elsewhere/libmecab.so.2", valid["chaquopy/lib/libmecab.so.2"]),
                *valid.items(),
            ]
        )
        payload = archive(
            {
                "assets/chaquopy/requirements-common.imy": common,
            }
        )
        with self.assertRaisesRegex(ArtifactError, "unexpected S1a native payload location"):
            self.inspect_complete(payload, required=[], require_s1a=True)

    def test_s1a_native_payloads_reject_wrong_owner_and_nested_archives(self) -> None:
        native_entries = self.valid_s1a_native_entries()
        attributions = {path: data for path, (_, data) in S1A_ATTRIBUTIONS.items()}
        wrong_owner = archive(
            {
                "assets/chaquopy/requirements-common.imy": archive(
                    {**native_entries, **attributions}
                ),
                "assets/chaquopy/requirements-arm64-v8a.imy": archive({}),
                "assets/chaquopy/requirements-x86_64.imy": archive({}),
            }
        )
        with self.assertRaisesRegex(ArtifactError, "manifest-selected requirements IMY"):
            self.inspect_complete(
                wrong_owner,
                required=[],
                allowed={"arm64-v8a", "x86_64"},
                require_s1a=True,
            )

        nested = archive(
            {
                "assets/chaquopy/requirements-common.imy": archive(
                    {**attributions, "nested.zip": archive(native_entries)}
                ),
            }
        )
        with self.assertRaisesRegex(ArtifactError, "direct member"):
            self.inspect_complete(nested, required=[], require_s1a=True)

    def test_s1a_native_payload_hash_must_match_manifest(self) -> None:
        natives = self.valid_s1a_native_entries()
        natives["chaquopy/lib/libc++_shared.so"] += b"post-build mutation"
        with self.assertRaisesRegex(ArtifactError, "native hash differs"):
            self.inspect_complete(
                self.valid_s1a_artifact(native_entries=natives),
                required=[],
                require_s1a=True,
            )

    def test_s1a_attributions_must_be_direct_common_members(self) -> None:
        native_entries = self.valid_s1a_native_entries()
        attributions = {path: data for path, (_, data) in S1A_ATTRIBUTIONS.items()}
        nested_common = archive(
            {
                "assets/chaquopy/requirements-common.imy": archive(
                    {
                        "nested.zip": archive(attributions),
                        **native_entries,
                    }
                ),
            }
        )
        with self.assertRaisesRegex(ArtifactError, "direct member of requirements-common"):
            self.inspect_complete(nested_common, required=[], require_s1a=True)

    def test_s1a_attribution_absence_and_arbitrary_license_do_not_pass(self) -> None:
        payload = self.valid_s1a_artifact(
            attribution_entries={
                "unrelated-1.0.dist-info/LICENSE": b"all expected marker words"
            },
        )
        with self.assertRaisesRegex(ArtifactError, "missing S1a package attributions"):
            self.inspect_complete(payload, required=[], require_s1a=True)

    def test_s1a_attribution_spoof_fails_hash_and_license_markers(self) -> None:
        spoofed = dict(S1A_ATTRIBUTIONS)
        fugashi_path = "fugashi-1.5.2.dist-info/LICENSE"
        spoofed[fugashi_path] = ("fugashi", b"not the Fugashi MIT attribution\n")
        payload = self.valid_s1a_artifact(
            attribution_entries={path: data for path, (_, data) in spoofed.items()},
        )
        with self.assertRaisesRegex(ArtifactError, "hash differs"):
            self.inspect_complete(payload, required=[], require_s1a=True)
        with self.assertRaisesRegex(ArtifactError, "fugashi attribution markers"):
            self.inspect_complete(
                payload,
                required=[],
                require_s1a=True,
                manifest_attributions=spoofed,
            )

    def test_s1a_attribution_cannot_have_duplicate_imy_owners(self) -> None:
        common_entries = {
            path: payload
            for path, (_, payload) in S1A_ATTRIBUTIONS.items()
        }
        duplicate_path = "fugashi-1.5.2.dist-info/LICENSE"
        x86_entries = self.valid_s1a_native_entries()
        x86_entries[duplicate_path] = S1A_ATTRIBUTIONS[duplicate_path][1]
        payload = archive(
            {
                "assets/chaquopy/requirements-common.imy": archive(common_entries),
                "assets/chaquopy/requirements-arm64-v8a.imy": archive(
                    self.valid_s1a_native_entries("arm64-v8a")
                ),
                "assets/chaquopy/requirements-x86_64.imy": archive(x86_entries),
            }
        )
        with self.assertRaisesRegex(ArtifactError, "requirement payload is duplicated"):
            self.inspect_complete(
                payload,
                required=[],
                allowed={"arm64-v8a", "x86_64"},
                require_s1a=True,
            )

    def test_s1a_payload_rejects_wrong_soname_after_packaging(self) -> None:
        natives = self.valid_s1a_native_entries()
        natives["chaquopy/lib/libmecab.so.2"] = dynamic_elf(
            soname="libmecab-wrong.so",
            needed=("libc++_shared.so",),
        )
        with self.assertRaisesRegex(ArtifactError, "SONAME"):
            self.inspect_complete(
                self.valid_s1a_artifact(native_entries=natives),
                required=[],
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
                    "--s1a-manifest",
                    str(root / "manifest.json"),
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
            self.assertIn("--s1a-manifest", forwarded)
            self.assertIn(str(root / "manifest.json"), forwarded)


if __name__ == "__main__":
    unittest.main()
