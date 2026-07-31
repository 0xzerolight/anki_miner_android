from __future__ import annotations

import hashlib
import json
import os
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from contextlib import contextmanager
from io import BytesIO
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_runtime_artifact as checker  # noqa: E402

RUNTIME_BUILD_KEY = "a" * 64
S1A_BUILD_KEY = "b" * 64


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _metadata(name: str, version: str, *, extra: str = "") -> bytes:
    return ("Metadata-Version: 2.1\n" f"Name: {name}\n" f"Version: {version}\n" f"{extra}\n").encode()


def _elf(abi: str, marker: bytes = b"") -> bytes:
    data = bytearray(64)
    data[:4] = b"\x7fELF"
    data[4] = 2
    data[5] = 1
    struct.pack_into("<H", data, 18, checker.SUPPORTED_ABIS[abi])
    return bytes(data) + marker


def _zip(entries: dict[str, bytes] | list[tuple[str, bytes]]) -> bytes:
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        values = entries.items() if isinstance(entries, dict) else entries
        for name, data in values:
            archive.writestr(name, data)
    return output.getvalue()


def _runtime_entry(
    package: str,
    version: str,
    license_path: str,
    license_data: bytes,
    *,
    abi: str | None = None,
    native_path: str | None = None,
    native_data: bytes | None = None,
) -> dict[str, object]:
    native: list[dict[str, object]] = []
    if native_path is not None:
        assert abi is not None and native_data is not None
        native.append(
            {
                "path": native_path,
                "sha256": _sha256(native_data),
                "abi": abi,
                "soname": None,
                "needed": ["libc.so"],
            }
        )
    return {
        "package": package,
        "version": version,
        "filename": f"{package}-{version}-fixture.whl",
        "sha256": "c" * 64,
        "size": 100,
        "tag": "py3-none-any" if abi is None else f"android-{abi}",
        "requires": [],
        "license_expression": "MIT",
        "licenses": [{"path": license_path, "sha256": _sha256(license_data)}],
        "native": native,
    }


class ArtifactFixture:
    def __init__(
        self,
        root: Path,
        *,
        abi: str = "x86_64",
        artifact_type: str = "apk",
        include_s1a: bool = False,
    ) -> None:
        self.root = root
        self.abi = abi
        self.artifact_type = artifact_type
        self.common: dict[str, bytes] = {}

        requests_license = b"requests fixture license"
        pillow_license = b"pillow fixture license"
        pillow_native = _elf(abi, b"pillow")
        self.common.update(
            {
                "requests-1.0.dist-info/METADATA": _metadata("requests", "1.0"),
                "requests-1.0.dist-info/LICENSE": requests_license,
                "requests/__init__.py": b"VALUE = 1\n",
                "pillow-2.0.dist-info/METADATA": _metadata("Pillow", "2.0"),
                "pillow-2.0.dist-info/LICENSE": pillow_license,
                "PIL/_imaging.so": pillow_native,
            }
        )
        common_entry = _runtime_entry(
            "requests",
            "1.0",
            "requests-1.0.dist-info/LICENSE",
            requests_license,
        )
        native_entry = _runtime_entry(
            "pillow",
            "2.0",
            "pillow-2.0.dist-info/LICENSE",
            pillow_license,
            abi=abi,
            native_path="PIL/_imaging.so",
            native_data=pillow_native,
        )
        other_abi = next(value for value in checker.SUPPORTED_ABIS if value != abi)
        self.runtime_document: dict[str, object] = {
            "schema": checker.RUNTIME_SCHEMA,
            "build_key": RUNTIME_BUILD_KEY,
            "wheels": {
                "common": [common_entry],
                abi: [native_entry],
                other_abi: [native_entry],
            },
        }
        runtime_dir = root / f"runtime-wheels-{RUNTIME_BUILD_KEY}"
        runtime_dir.mkdir()
        self.runtime_manifest = runtime_dir / "manifest.json"
        self.write_runtime_manifest()

        self.s1a_manifest: Path | None = None
        if include_s1a:
            self._add_s1a()
        self.artifact = root / f"app.{artifact_type}"
        self.write_artifact()

    def write_runtime_manifest(self) -> None:
        self.runtime_manifest.write_text(json.dumps(self.runtime_document), encoding="utf-8")

    def _s1a_entries(self, abi: str, *, install: bool) -> list[dict[str, object]]:
        result: list[dict[str, object]] = []
        for package, (wheel_name, version, tags, native_path) in checker.S1A_SPECS.items():
            native = _elf(abi, package.encode())
            license_name = "BSD" if package == "chaquopy-libmecab" else "LICENSE"
            dist_name = wheel_name
            license_path = f"{dist_name}-{version}.dist-info/{license_name}"
            license_data = f"{package} fixture license".encode()
            if install:
                self.common[f"{dist_name}-{version}.dist-info/METADATA"] = _metadata(package, version)
                self.common[license_path] = license_data
                self.common[native_path] = native
            result.append(
                {
                    "filename": (f"{wheel_name}-{version}-0-{tags}-android_26_" f"{abi.replace('-', '_')}.whl"),
                    "sha256": "d" * 64,
                    "size": 100,
                    "licenses": [{"path": license_path, "sha256": _sha256(license_data)}],
                    "elf": {
                        "abi": abi,
                        "needed": ["libc.so"],
                        "path": native_path,
                        "sha256": _sha256(native),
                        "soname": None,
                    },
                }
            )
        return result

    def _add_s1a(self) -> None:
        other_abi = next(value for value in checker.SUPPORTED_ABIS if value != self.abi)
        document = {
            "schema": checker.S1A_SCHEMA,
            "build_key": S1A_BUILD_KEY,
            "wheels": {
                self.abi: self._s1a_entries(self.abi, install=True),
                other_abi: self._s1a_entries(other_abi, install=False),
            },
        }
        directory = self.root / f"s1a-wheels-{S1A_BUILD_KEY}"
        directory.mkdir()
        self.s1a_manifest = directory / "manifest.json"
        self.s1a_manifest.write_text(json.dumps(document), encoding="utf-8")

    def _prefix(self) -> str:
        return "assets/chaquopy" if self.artifact_type == "apk" else "base/assets/chaquopy"

    def write_artifact(
        self,
        *,
        common_payload: bytes | None = None,
        abi_payload: bytes = checker.EMPTY_ZIP,
        extra_outer: dict[str, bytes] | None = None,
        prefix: str | None = None,
    ) -> None:
        prefix = prefix or self._prefix()
        entries = {
            f"{prefix}/requirements-common.imy": (_zip(self.common) if common_payload is None else common_payload),
            f"{prefix}/requirements-{self.abi}.imy": abi_payload,
            # The auditor deliberately never opens app.imy. This required shim is allowed.
            f"{prefix}/app.imy": _zip({"PyQt6/QtCore.py": b"class QCoreApplication: pass\n"}),
        }
        entries.update(extra_outer or {})
        with zipfile.ZipFile(self.artifact, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def audit(self) -> checker.AuditResult:
        return checker.audit_artifact(
            self.artifact,
            self.runtime_manifest,
            self.abi,
            self.s1a_manifest,
        )

    def write_vendored_manifest(self) -> Path:
        wheels_root = self.root / "wheels"
        entries = (
            (
                "common",
                "requests-1.0-py3-none-any.whl",
                "requests",
                "1.0",
                {
                    "requests-1.0.dist-info/METADATA": self.common["requests-1.0.dist-info/METADATA"],
                    "requests-1.0.dist-info/licenses/LICENSE": self.common["requests-1.0.dist-info/licenses/LICENSE"],
                    "requests/__init__.py": self.common["requests/__init__.py"],
                },
            ),
            (
                self.abi,
                f"pillow-2.0-{self.abi}.whl",
                "pillow",
                "2.0",
                {
                    "pillow-2.0.dist-info/METADATA": self.common["pillow-2.0.dist-info/METADATA"],
                    "pillow-2.0.dist-info/LICENSE": self.common["pillow-2.0.dist-info/LICENSE"],
                    "PIL/_imaging.so": self.common["PIL/_imaging.so"],
                },
            ),
        )
        manifest_entries: list[dict[str, object]] = []
        for abi, filename, package, version, wheel_entries in entries:
            payload = _zip(wheel_entries)
            path = wheels_root / abi / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
            manifest_entries.append(
                {
                    "abi": abi,
                    "filename": filename,
                    "license": "MIT",
                    "package": package,
                    "path": path.relative_to(wheels_root).as_posix(),
                    "sha256": _sha256(payload),
                    "source": {"kind": "fixture"},
                    "version": version,
                }
            )
        manifest = wheels_root / "manifest.json"
        manifest.write_text(json.dumps({"schema": 1, "wheels": manifest_entries}), encoding="utf-8")
        return manifest


@contextmanager
def fixture(**kwargs: object):
    with tempfile.TemporaryDirectory() as temporary:
        yield ArtifactFixture(Path(temporary), **kwargs)


class RuntimeArtifactPositiveTests(unittest.TestCase):
    def test_freetype_ftl_attribution_is_accepted(self) -> None:
        checker._validate_license_owner(
            "chaquopy_freetype-2.14.1.dist-info/FTL.TXT",
            "chaquopy-freetype",
            "2.14.1",
            "freetype fixture",
        )

    def test_exact_runtime_inventory_passes_for_apk_and_aab(self) -> None:
        for artifact_type, abi in (("apk", "x86_64"), ("aab", "arm64-v8a")):
            with (
                self.subTest(artifact_type=artifact_type),
                fixture(
                    artifact_type=artifact_type,
                    abi=abi,
                ) as value,
            ):
                result = value.audit()

                self.assertEqual(artifact_type, result.artifact_type)
                self.assertEqual(abi, result.abi)
                self.assertEqual(2, result.distribution_count)
                self.assertEqual(2, result.license_count)
                self.assertEqual(1, result.native_count)
                self.assertFalse(result.s1a_included)

    def test_exact_runtime_and_s1a_inventory_passes(self) -> None:
        with fixture(include_s1a=True) as value:
            result = value.audit()

            self.assertTrue(result.s1a_included)
            self.assertEqual(5, result.distribution_count)
            self.assertEqual(5, result.license_count)
            self.assertEqual(4, result.native_count)


class RuntimeArtifactInventoryTests(unittest.TestCase):
    def test_vendored_manifest_audit_rejects_mutated_packaged_native(self) -> None:
        with fixture() as value:
            value.common["requests-1.0.dist-info/licenses/LICENSE"] = value.common.pop("requests-1.0.dist-info/LICENSE")
            value.write_artifact()
            manifest = value.write_vendored_manifest()
            result = checker.audit_vendored_artifact(value.artifact, manifest, value.abi)
            self.assertEqual(2, result.distribution_count)

            value.common["PIL/_imaging.so"] = _elf(value.abi, b"changed after wheel verification")
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "native inventory"):
                checker.audit_vendored_artifact(value.artifact, manifest, value.abi)

    def test_distribution_identity_must_be_exact(self) -> None:
        cases = {
            "extra": lambda value: value.common.update(
                {
                    "extra-1.0.dist-info/METADATA": _metadata("extra", "1.0"),
                    "extra-1.0.dist-info/LICENSE": b"extra license",
                }
            ),
            "wrong version": lambda value: value.common.__setitem__(
                "requests-1.0.dist-info/METADATA",
                _metadata("requests", "2.0"),
            ),
            "missing metadata": lambda value: value.common.pop("requests-1.0.dist-info/METADATA"),
        }
        for label, mutate in cases.items():
            with self.subTest(label=label), fixture() as value:
                mutate(value)
                value.write_artifact()
                with self.assertRaisesRegex(
                    checker.RuntimeArtifactError,
                    "distribution|METADATA|dist-info",
                ):
                    value.audit()

    def test_native_and_license_path_and_hash_inventory_must_be_exact(self) -> None:
        cases = {
            "native hash": lambda value: value.common.__setitem__("PIL/_imaging.so", _elf(value.abi, b"changed")),
            "extra native": lambda value: value.common.__setitem__("PIL/_extra.so", _elf(value.abi, b"extra")),
            "license hash": lambda value: value.common.__setitem__("requests-1.0.dist-info/LICENSE", b"changed"),
            "extra license": lambda value: value.common.__setitem__("requests-1.0.dist-info/NOTICE", b"extra"),
        }
        for label, mutate in cases.items():
            with self.subTest(label=label), fixture() as value:
                mutate(value)
                value.write_artifact()
                with self.assertRaisesRegex(
                    checker.RuntimeArtifactError,
                    "native inventory|license inventory",
                ):
                    value.audit()

    def test_native_payload_must_match_selected_abi(self) -> None:
        with fixture() as value:
            value.common["PIL/_imaging.so"] = _elf("arm64-v8a")
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "expected x86_64"):
                value.audit()

    def test_forbidden_requirement_modules_are_rejected_but_app_imy_is_ignored(self) -> None:
        forbidden = (
            "gtts/__init__.py",
            "yt_dlp/__init__.py",
            "yt_dlp_plugins/__init__.py",
            "unidic/__init__.py",
            "unidic_lite/__init__.py",
            "PyQt6/QtCore.py",
            "PyQt6_sip/__init__.py",
        )
        for path in forbidden:
            with self.subTest(path=path), fixture() as value:
                value.common[path] = b"forbidden = True\n"
                value.write_artifact()
                with self.assertRaisesRegex(checker.RuntimeArtifactError, "forbidden package payload"):
                    value.audit()

    def test_s1a_payload_requires_manifest_and_then_must_match_it(self) -> None:
        with fixture() as value:
            value.common["fugashi/fugashi.so"] = _elf(value.abi)
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "S1a-only payload"):
                value.audit()

        with fixture(include_s1a=True) as value:
            value.common["fugashi/fugashi.so"] = _elf(value.abi, b"mismatch")
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "native inventory"):
                value.audit()


class RuntimeArtifactArchiveSafetyTests(unittest.TestCase):
    def test_requires_exact_canonical_single_abi_layout(self) -> None:
        with fixture() as value:
            value.write_artifact(abi_payload=_zip({"unexpected": b"payload"}))
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "must be empty"):
                value.audit()

        with fixture() as value:
            value.write_artifact(extra_outer={"assets/chaquopy/requirements-arm64-v8a.imy": checker.EMPTY_ZIP})
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "canonical requirements archives differ"):
                value.audit()

        with fixture(artifact_type="aab") as value:
            value.write_artifact(prefix="assets/chaquopy")
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "canonical requirements archives differ"):
                value.audit()

    def test_duplicate_unsafe_symlink_and_nested_archive_entries_are_rejected(self) -> None:
        with fixture() as value:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                duplicate = _zip(
                    [
                        ("requests-1.0.dist-info/METADATA", _metadata("requests", "1.0")),
                        ("requests-1.0.dist-info/METADATA", _metadata("requests", "1.0")),
                    ]
                )
            value.write_artifact(common_payload=duplicate)
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "duplicate"):
                value.audit()

        with fixture() as value:
            value.common["../escape"] = b"escape"
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "unsafe"):
                value.audit()

        with fixture() as value:
            output = BytesIO()
            with zipfile.ZipFile(output, "w") as archive:
                for path, data in value.common.items():
                    archive.writestr(path, data)
                link = zipfile.ZipInfo("linked-module")
                link.create_system = 3
                link.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(link, b"target")
            value.write_artifact(common_payload=output.getvalue())
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "symlink"):
                value.audit()

        with fixture() as value:
            value.common["payload.zip"] = _zip({"nested": b"archive"})
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "nested archive"):
                value.audit()

    def test_entry_count_and_size_bombs_are_rejected_without_large_fixtures(self) -> None:
        with fixture() as value, mock.patch.object(checker, "MAX_REQUIREMENT_ENTRIES", 2):
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "too many entries"):
                value.audit()

        with fixture() as value, mock.patch.object(checker, "MAX_REQUIREMENT_ENTRY_SIZE", 8):
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "oversized"):
                value.audit()

    def test_malformed_metadata_and_native_looking_non_elf_are_rejected(self) -> None:
        with fixture() as value:
            value.common["requests-1.0.dist-info/METADATA"] = _metadata("requests", "1.0", extra="Name: duplicate\n")
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "headers differ"):
                value.audit()

        with fixture() as value:
            value.common["PIL/_imaging.so"] = b"not an ELF"
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "malformed native"):
                value.audit()

        with fixture() as value:
            value.common["requests-1.0.dist-info/METADATA"] = _metadata("requests", "1.0").replace(
                b"Metadata-Version: 2.1", b"Metadata-Version: invalid"
            )
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "invalid METADATA"):
                value.audit()

        with fixture() as value:
            value.common["hidden-1.0.DIST-INFO/METADATA"] = _metadata("hidden", "1.0")
            value.write_artifact()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "dist-info"):
                value.audit()


class RuntimeArtifactManifestAndCliTests(unittest.TestCase):
    def test_manifest_payload_paths_and_s1a_group_are_fail_closed(self) -> None:
        with fixture() as value:
            entry = value.runtime_document["wheels"]["common"][0]  # type: ignore[index]
            entry["licenses"][0]["path"] = "../LICENSE"  # type: ignore[index]
            value.write_runtime_manifest()
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "unsafe"):
                value.audit()

        with fixture(include_s1a=True) as value:
            assert value.s1a_manifest is not None
            document = json.loads(value.s1a_manifest.read_text(encoding="utf-8"))
            document["wheels"][value.abi].pop()
            value.s1a_manifest.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(checker.RuntimeArtifactError, "incomplete"):
                value.audit()

    def test_cli_success_and_exactly_one_abi_contract(self) -> None:
        with fixture() as value:
            command = [
                sys.executable,
                str(SCRIPTS_DIR / "check_runtime_artifact.py"),
                "--artifact",
                str(value.artifact),
                "--runtime-manifest",
                str(value.runtime_manifest),
                "--allow-abi",
                value.abi,
            ]
            result = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(value.abi, json.loads(result.stdout)["abi"])

            duplicate = subprocess.run(
                [*command, "--allow-abi", "arm64-v8a"],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(2, duplicate.returncode)
            self.assertIn("exactly once", duplicate.stderr)

    def test_cli_reports_audit_failure_without_traceback(self) -> None:
        with fixture() as value:
            value.common["gtts/__init__.py"] = b"forbidden = True\n"
            value.write_artifact()
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "check_runtime_artifact.py"),
                    "--artifact",
                    str(value.artifact),
                    "--runtime-manifest",
                    str(value.runtime_manifest),
                    "--allow-abi",
                    value.abi,
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("runtime-artifact:", result.stderr)
            self.assertNotIn("Traceback", result.stderr)

    def test_checker_is_executable_and_has_valid_cli_help(self) -> None:
        path = SCRIPTS_DIR / "check_runtime_artifact.py"
        self.assertTrue(os.access(path, os.X_OK))
        result = subprocess.run(
            [str(path), "--help"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--runtime-manifest", result.stdout)
        self.assertIn("--vendored-manifest", result.stdout)
        self.assertIn("--s1a-manifest", result.stdout)


if __name__ == "__main__":
    unittest.main()
