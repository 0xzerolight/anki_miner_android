from __future__ import annotations

import base64
import copy
import hashlib
import importlib.util
import io
import json
import os
import shlex
import subprocess
import sys
import tarfile
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
TOOL_ROOT = ROOT / "tools/runtime-wheels"
SPEC = importlib.util.spec_from_file_location(
    "anki_miner_runtime_wheels_under_test",
    TOOL_ROOT / "runtime_wheels.py",
)
assert SPEC is not None and SPEC.loader is not None
runtime_wheels = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runtime_wheels
SPEC.loader.exec_module(runtime_wheels)


def wheel_name(package: str, abi: str) -> str:
    spec = runtime_wheels.NATIVE_SPECS[package]
    return (
        f"{package.replace('-', '_')}-{spec['version']}-{spec['build']}-"
        f"{spec['python']}-{spec['abi']}-android_26_{abi.replace('-', '_')}.whl"
    )


def make_wheel(
    path: Path,
    *,
    package: str,
    version: str,
    tag: str,
    requirements: tuple[str, ...] = (),
    license_data: bytes = b"locked test license",
    license_name: str = "LICENSE",
    extra_files: dict[str, bytes] | None = None,
    corrupt_record: bool = False,
) -> str:
    dist_info = f"{package.replace('-', '_')}-{version}.dist-info"
    metadata = [
        "Metadata-Version: 2.1",
        f"Name: {package}",
        f"Version: {version}",
    ]
    metadata.extend(f"Requires-Dist: {value}" for value in requirements)
    files = {
        f"{dist_info}/METADATA": ("\n".join(metadata) + "\n\n").encode(),
        f"{dist_info}/WHEEL": (
            "Wheel-Version: 1.0\n" "Generator: runtime-wheel-test\n" "Root-Is-Purelib: false\n" f"Tag: {tag}\n\n"
        ).encode(),
        f"{dist_info}/{license_name}": license_data,
        **(extra_files or {}),
    }
    rows: list[str] = []
    for name, data in files.items():
        encoded = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
        rows.append(f"{name},sha256={encoded},{len(data)}")
    record_name = f"{dist_info}/RECORD"
    rows.append(f"{record_name},,")
    record = ("\n".join(rows) + "\n").encode()
    if corrupt_record:
        record = record.replace(b"sha256=", b"sha256=bad", 1)
    files[record_name] = record
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo(name, (2024, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return hashlib.sha256(license_data).hexdigest()


def builder_identity() -> dict[str, object]:
    tool_names = {
        "bash",
        "coreutils",
        "findutils",
        "git",
        "grep",
        "make",
        "patch",
        "pkg-config",
        "sed",
        "tar",
        "unzip",
    }
    return {
        "schema": 2,
        "interpreters": {
            "outer": {
                "implementation": "cpython",
                "version": "3.13.5",
                "executable_sha256": "1" * 64,
            },
            "target": {
                "implementation": "cpython",
                "version": "3.12.13",
                "executable_sha256": "2" * 64,
            },
        },
        "host": {
            "os": "linux",
            "machine": "x86_64",
            "libc": {"name": "glibc", "version": "2.39"},
            "zlib": {"compiled": "1.3.1", "runtime": "1.3.1"},
        },
        "android": {
            "version": runtime_wheels.NDK_VERSION,
            "source_properties_sha256": "3" * 64,
            "clang_sha256": "4" * 64,
            "clang_version": "Android clang version 19.0.2",
        },
        "tools": {name: f"{name} 1" for name in tool_names},
    }


def driver_command(mode: str = "success") -> str:
    if mode == "success":
        build_body = r"""
    printf 'build\n' >>"$TEST_ROOT/builds.log"
    sleep "${TEST_BUILD_DELAY:-0}"
    mkdir -p "$target"
    : >"$target/VALID"
    : >"$target/manifest.json"
    run_root="$(mktemp -d "$build_root/runtime-$build_key-run-XXXXXXXX")"
    built_manifest="$target/manifest.json"
"""
    elif mode == "fail":
        build_body = r"""
    printf 'build\n' >>"$TEST_ROOT/builds.log"
    run_root="$(mktemp -d "$build_root/runtime-$build_key-run-XXXXXXXX")"
    return 23
"""
    else:
        raise AssertionError(mode)
    driver = shlex.quote(str(TOOL_ROOT / "build-runtime-wheels.sh"))
    return f"""
source {driver}
runtime_configure_paths() {{
    runtime_root="$TEST_ROOT/runtime-root"
    downloads="$runtime_root/downloads"
    wheelhouse="$runtime_root/host-wheels"
    build_root="$TEST_ROOT/build"
    publication_root="$TEST_ROOT/publications"
    current_pointer="$TEST_ROOT/out/current"
}}
runtime_provision_build_python() {{
    printf 'provision\n' >>"$TEST_ROOT/events.log"
}}
runtime_verify_build_python() {{
    printf 'verify-python\n' >>"$TEST_ROOT/events.log"
}}
runtime_check_prerequisites() {{ :; }}
runtime_compute_keys() {{
    recipe_key="{'a' * 64}"
    build_key="{'b' * 64}"
}}
runtime_verify_target() {{
    [[ -d "$1" && ! -L "$1" && -f "$1/VALID" ]]
}}
runtime_activate_target() {{
    local temporary
    mkdir -p "$(dirname "$current_pointer")"
    temporary="$current_pointer.tmp.$$"
    ln -s "$1" "$temporary"
    mv -Tf "$temporary" "$current_pointer"
}}
runtime_build_target() {{{build_body}
}}
runtime_main
"""


class LockContractTests(unittest.TestCase):
    def test_source_lock_has_exact_runtime_inventory_and_licenses(self) -> None:
        entries = runtime_wheels.source_entries()
        self.assertEqual(set(runtime_wheels.SOURCE_SPECS), set(entries))
        for name, entry in entries.items():
            self.assertRegex(str(entry["sha256"]), r"^[0-9a-f]{64}$")
            self.assertTrue(str(entry["url"]).startswith("https://"))
            if name not in {"patchelf", "python-arm64-v8a", "python-x86_64"}:
                self.assertIn("license", entry)
        for package, spec in runtime_wheels.NATIVE_SPECS.items():
            self.assertEqual(package, entries[str(spec["source"])]["package"])

    def test_common_runtime_closure_is_exact_and_network_closed(self) -> None:
        self.assertEqual(
            {
                "requests": "2.34.2",
                "pysubs2": "1.8.1",
                "charset-normalizer": "3.4.7",
                "urllib3": "2.7.0",
                "idna": "3.18",
                "certifi": "2026.6.17",
            },
            {name: version for name, (version, _) in runtime_wheels.COMMON_SPECS.items()},
        )
        locked_text = runtime_wheels.SOURCE_LOCK.read_text(encoding="utf-8").casefold()
        for forbidden in ("unidic", "pyqt6", "gtts", "yt-dlp", "yt_dlp"):
            self.assertNotIn(forbidden, locked_text)

    def test_host_lock_is_exact_and_target_wheels_are_cp312(self) -> None:
        entries = runtime_wheels.host_entries()
        self.assertEqual(
            set(runtime_wheels.HOST_REQUIREMENTS),
            {str(entry["requirement"]) for entry in entries},
        )
        target = [entry for entry in entries if "target" in entry["roles"]]
        self.assertTrue(any("cp312-cp312" in str(entry["filename"]) for entry in target))
        self.assertFalse(any("cp313" in str(entry["filename"]) for entry in target))

    def test_source_lock_rejects_extra_fields_and_wrong_schema(self) -> None:
        document = json.loads(runtime_wheels.SOURCE_LOCK.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "sources.lock"
            changed = copy.deepcopy(document)
            changed["extra"] = True
            path.write_text(json.dumps(changed), encoding="utf-8")
            with mock.patch.object(runtime_wheels, "SOURCE_LOCK", path):
                with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "exactly"):
                    runtime_wheels.source_entries()
            changed = copy.deepcopy(document)
            changed["schema"] = 2
            path.write_text(json.dumps(changed), encoding="utf-8")
            with mock.patch.object(runtime_wheels, "SOURCE_LOCK", path):
                with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "schema"):
                    runtime_wheels.source_entries()

    def test_host_lock_rejects_short_hash_and_unlisted_requirement(self) -> None:
        document = json.loads(runtime_wheels.HOST_LOCK.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "host.lock"
            changed = copy.deepcopy(document)
            changed["wheels"][0]["sha256"] = "deadbeef"
            path.write_text(json.dumps(changed), encoding="utf-8")
            with mock.patch.object(runtime_wheels, "HOST_LOCK", path):
                with self.assertRaises(runtime_wheels.RuntimeWheelError):
                    runtime_wheels.host_entries()
            changed = copy.deepcopy(document)
            changed["wheels"][0]["requirement"] = "mystery==1"
            path.write_text(json.dumps(changed), encoding="utf-8")
            with mock.patch.object(runtime_wheels, "HOST_LOCK", path):
                with self.assertRaises(runtime_wheels.RuntimeWheelError):
                    runtime_wheels.host_entries()

    def test_recipe_provenance_includes_build_python_and_license_inputs(self) -> None:
        self.assertEqual(
            (
                "scripts/android-env.sh",
                "scripts/android-licenses.sh",
                "scripts/chaquopy-build-python.lock.json",
                "scripts/check_native_artifacts.py",
                "scripts/provision-chaquopy-build-python.sh",
                "scripts/verify_chaquopy_build_python.py",
            ),
            runtime_wheels.RECIPE_REPO_FILES,
        )


class ArchiveSafetyTests(unittest.TestCase):
    def test_safe_extract_accepts_dot_root_tool_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive_path = root / "tool.tar.gz"
            with tarfile.open(archive_path, "w:gz") as archive:
                directory = tarfile.TarInfo("./bin/")
                directory.type = tarfile.DIRTYPE
                archive.addfile(directory)
                payload = b"tool"
                member = tarfile.TarInfo("./bin/tool")
                member.mode = 0o755
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
            extracted = runtime_wheels.safe_extract(archive_path, root / "out")
            self.assertEqual(root / "out", extracted)
            self.assertEqual(payload, (extracted / "bin/tool").read_bytes())

    def test_safe_extract_rejects_tar_traversal_and_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, configure in (
                ("traversal", lambda member: None),
                ("symlink", lambda member: setattr(member, "type", tarfile.SYMTYPE)),
            ):
                archive_path = root / f"{name}.tar"
                with tarfile.open(archive_path, "w") as archive:
                    member = tarfile.TarInfo("../escape" if name == "traversal" else "root/link")
                    configure(member)
                    if name == "symlink":
                        member.linkname = "/etc/passwd"
                    else:
                        member.size = 1
                    archive.addfile(member, None if name == "symlink" else io.BytesIO(b"x"))
                with self.assertRaises(runtime_wheels.RuntimeWheelError):
                    runtime_wheels.safe_extract(archive_path, root / f"out-{name}")

    def test_zip_validation_rejects_duplicate_and_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            duplicate = root / "duplicate.zip"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(duplicate, "w") as archive:
                    archive.writestr("root/file", b"a")
                    archive.writestr("root/file", b"b")
            with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "duplicate"):
                runtime_wheels.safe_extract(duplicate, root / "dup-out")

            symlink = root / "symlink.zip"
            with zipfile.ZipFile(symlink, "w") as archive:
                info = zipfile.ZipInfo("root/link")
                info.create_system = 3
                info.external_attr = 0o120777 << 16
                archive.writestr(info, b"target")
            with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "special"):
                runtime_wheels.safe_extract(symlink, root / "link-out")


class BuilderContractTests(unittest.TestCase):
    def test_target_python_must_be_absolute_and_executable(self) -> None:
        with mock.patch.dict(os.environ, {runtime_wheels.TARGET_BUILD_PYTHON_ENV: "python3"}):
            with self.assertRaises(runtime_wheels.RuntimeWheelError):
                runtime_wheels.target_python_executable()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "python"
            path.write_text("#!/bin/sh\n", encoding="utf-8")
            with mock.patch.dict(
                os.environ,
                {runtime_wheels.TARGET_BUILD_PYTHON_ENV: str(path)},
            ):
                with self.assertRaises(runtime_wheels.RuntimeWheelError):
                    runtime_wheels.target_python_executable()

    def test_builder_identity_requires_exact_target_python_and_ndk(self) -> None:
        identity = builder_identity()
        self.assertEqual(identity, runtime_wheels.validate_builder_identity(identity))
        wrong_python = copy.deepcopy(identity)
        wrong_python["interpreters"]["target"]["version"] = "3.13.5"
        with self.assertRaises(runtime_wheels.RuntimeWheelError):
            runtime_wheels.validate_builder_identity(wrong_python)
        wrong_ndk = copy.deepcopy(identity)
        wrong_ndk["android"]["version"] = "27.0"
        with self.assertRaises(runtime_wheels.RuntimeWheelError):
            runtime_wheels.validate_builder_identity(wrong_ndk)
        extra = copy.deepcopy(identity)
        extra["host"]["extra"] = True
        with self.assertRaises(runtime_wheels.RuntimeWheelError):
            runtime_wheels.validate_builder_identity(extra)

    def test_patch_builder_closes_network_and_selects_exact_target_python(self) -> None:
        builder_text = """import pypi_simple
class Builder:
    def setup(self):
        os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
        pip_version = "23.2.1"
        run(f"{bootstrap_env}/bin/pip install pip=={pip_version}")
        run(f"install " + " ".join(shlex.quote(req) for req in requirements))
        run(f"python{python_ver} -m venv --without-pip {self.build_env}")
        run(f"python{python_ver} -m venv {bootstrap_env}")
    def download_git(self, source):
        return source
    def download_pypi(self):
        return None
    def download_url(self, url):
        return url
    def apply_patches(self):
        return None
"""
        android_text = """ndk_version=27.3.13750724
if ! [ -e $ndk ]; then
    log "Installing NDK - this may take several minutes"
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$ndk_version"
fi
export CFLAGS="-D__BIONIC_NO_PAGE_SIZE_MACRO"
"""
        with tempfile.TemporaryDirectory() as temporary:
            chaquopy = Path(temporary)
            builder = chaquopy / "server/pypi/build-wheel.py"
            android = chaquopy / "target/android-env.sh"
            builder.parent.mkdir(parents=True)
            android.parent.mkdir(parents=True)
            builder.write_text(builder_text, encoding="utf-8")
            android.write_text(android_text, encoding="utf-8")
            runtime_wheels.patch_builder(chaquopy)
            patched_builder = builder.read_text(encoding="utf-8")
            patched_android = android.read_text(encoding="utf-8")
            self.assertNotIn("import pypi_simple", patched_builder)
            self.assertEqual(3, patched_builder.count("network source discovery is disabled"))
            self.assertIn("ANKI_MINER_CHAQUOPY_BUILD_PYTHON", patched_builder)
            self.assertIn("--no-index --only-binary=:all:", patched_builder)
            self.assertIn(f"ndk_version={runtime_wheels.NDK_VERSION}", patched_android)
            self.assertNotIn("sdkmanager", patched_android)
            self.assertIn("-ffile-prefix-map", patched_android)

    def test_staged_tree_timestamps_are_fixed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            nested = root / "dir/file"
            nested.parent.mkdir()
            nested.write_text("content", encoding="utf-8")
            runtime_wheels.normalize_tree_timestamps(root)
            expected = int(runtime_wheels.SOURCE_DATE_EPOCH)
            self.assertEqual(expected, int(root.stat().st_mtime))
            self.assertEqual(expected, int(nested.parent.stat().st_mtime))
            self.assertEqual(expected, int(nested.stat().st_mtime))

    def test_recipe_files_pin_required_native_features(self) -> None:
        pillow_patch = (TOOL_ROOT / "recipes/pillow/patches/android-required-codecs.patch").read_text()
        self.assertIn('{"jpeg", "zlib", "freetype", "webp"}', pillow_patch)
        self.assertIn("disable_platform_guessing = True", pillow_patch)
        for package in ("chaquopy-libwebp", "chaquopy-libxml2"):
            build_script = (TOOL_ROOT / "recipes" / package / "build.sh").read_text()
            self.assertIn("hardcode_into_libs=no", build_script)
            self.assertIn('hardcode_libdir_flag_spec=""', build_script)
        libxml2_script = (TOOL_ROOT / "recipes/chaquopy-libxml2/build.sh").read_text()
        self.assertIn("--sysconfdir=/etc", libxml2_script)
        self.assertIn('#define XML_SYSCONFDIR "/etc"', libxml2_script)
        for package in runtime_wheels.NATIVE_SPECS:
            recipe = TOOL_ROOT / "recipes" / package / "meta.yaml"
            self.assertTrue(recipe.is_file(), package)
            text = recipe.read_text(encoding="utf-8")
            self.assertIn("source:\n  path: ../../sources/", text)
            self.assertNotIn("http://", text)
            self.assertNotIn("https://", text)

    def test_build_driver_has_valid_shell_syntax(self) -> None:
        subprocess.run(
            ["bash", "-n", str(TOOL_ROOT / "build-runtime-wheels.sh")],
            check=True,
        )


class WheelVerificationTests(unittest.TestCase):
    def test_optional_imageqt_source_text_is_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / runtime_wheels.COMMON_SPECS["pysubs2"][1]
            license_hash = make_wheel(
                path,
                package="pysubs2",
                version="1.8.1",
                tag="py3-none-any",
                extra_files={
                    "pysubs2/ImageQt.py": b"from PyQt6.QtGui import QImage\n",
                },
            )
            with mock.patch.object(
                runtime_wheels,
                "_expected_license_hashes",
                return_value=("MIT", {license_hash}),
            ):
                package, abi, _ = runtime_wheels.verify_runtime_wheel(path)
            self.assertEqual("pysubs2", package)
            self.assertIsNone(abi)

    def test_forbidden_member_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / runtime_wheels.COMMON_SPECS["pysubs2"][1]
            license_hash = make_wheel(
                path,
                package="pysubs2",
                version="1.8.1",
                tag="py3-none-any",
                extra_files={"pysubs2/unidic_lite/sys.dic": b"fixture"},
            )
            with mock.patch.object(
                runtime_wheels,
                "_expected_license_hashes",
                return_value=("MIT", {license_hash}),
            ):
                with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "payload member"):
                    runtime_wheels.verify_runtime_wheel(path)

    def test_nonstandard_locked_license_name_is_attributed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / runtime_wheels.COMMON_SPECS["pysubs2"][1]
            license_hash = make_wheel(
                path,
                package="pysubs2",
                version="1.8.1",
                tag="py3-none-any",
                license_name="FTL.TXT",
            )
            with mock.patch.object(
                runtime_wheels,
                "_expected_license_hashes",
                return_value=("FTL", {license_hash}),
            ):
                _, _, entry = runtime_wheels.verify_runtime_wheel(path)
            self.assertEqual("FTL.TXT", Path(str(entry["licenses"][0]["path"])).name)

    def test_valid_common_wheel_and_optional_unlisted_extra(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / runtime_wheels.COMMON_SPECS["pysubs2"][1]
            license_hash = make_wheel(
                path,
                package="pysubs2",
                version="1.8.1",
                tag="py3-none-any",
                requirements=('PySocks ; extra == "socks"',),
            )
            with mock.patch.object(
                runtime_wheels,
                "_expected_license_hashes",
                return_value=("MIT", {license_hash}),
            ):
                package, abi, entry = runtime_wheels.verify_runtime_wheel(path)
            self.assertEqual("pysubs2", package)
            self.assertIsNone(abi)
            self.assertEqual([], entry["native"])

    def test_common_wheel_rejects_dependency_record_and_payload_tampering(self) -> None:
        cases = (
            (("mystery>=1",), {}, False, "mandatory dependencies"),
            ((), {}, True, "RECORD"),
            ((), {"pysubs2/sys.dic": b"dictionary"}, False, "forbidden payload"),
        )
        for index, (requirements, files, corrupt, message) in enumerate(cases):
            with self.subTest(case=index), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / runtime_wheels.COMMON_SPECS["pysubs2"][1]
                license_hash = make_wheel(
                    path,
                    package="pysubs2",
                    version="1.8.1",
                    tag="py3-none-any",
                    requirements=requirements,
                    extra_files=files,
                    corrupt_record=corrupt,
                )
                with mock.patch.object(
                    runtime_wheels,
                    "_expected_license_hashes",
                    return_value=("MIT", {license_hash}),
                ):
                    with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, message):
                        runtime_wheels.verify_runtime_wheel(path)

    def test_forbidden_dependency_is_rejected_even_when_optional(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / runtime_wheels.COMMON_SPECS["pysubs2"][1]
            license_hash = make_wheel(
                path,
                package="pysubs2",
                version="1.8.1",
                tag="py3-none-any",
                requirements=('PyQt6 ; extra == "gui"',),
            )
            with mock.patch.object(
                runtime_wheels,
                "_expected_license_hashes",
                return_value=("MIT", {license_hash}),
            ):
                with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "forbidden dependency"):
                    runtime_wheels.verify_runtime_wheel(path)

    def test_native_wheel_checks_tag_soname_and_dependencies(self) -> None:
        abi = "x86_64"
        filename = wheel_name("chaquopy-libjpeg", abi)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / filename
            license_hash = make_wheel(
                path,
                package="chaquopy-libjpeg",
                version="1.5.3",
                tag="py3-none-android_26_x86_64",
                extra_files={"chaquopy/lib/libjpeg_chaquopy.so": b"\x7fELFfixture"},
            )

            def inspected(data: bytes, logical: str, native_abi: str) -> dict[str, object]:
                self.assertEqual(abi, native_abi)
                return {
                    "path": logical,
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "abi": native_abi,
                    "soname": "libjpeg_chaquopy.so",
                    "needed": ["libc.so", "libm.so"],
                }

            with (
                mock.patch.object(
                    runtime_wheels,
                    "_expected_license_hashes",
                    return_value=("BSD-3-Clause", {license_hash}),
                ),
                mock.patch.object(runtime_wheels, "inspect_elf", side_effect=inspected),
            ):
                package, checked_abi, entry = runtime_wheels.verify_runtime_wheel(path)
            self.assertEqual("chaquopy-libjpeg", package)
            self.assertEqual(abi, checked_abi)
            self.assertEqual(1, len(entry["native"]))

    def test_native_wheel_rejects_unlisted_needed_library(self) -> None:
        abi = "arm64-v8a"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / wheel_name("chaquopy-libjpeg", abi)
            license_hash = make_wheel(
                path,
                package="chaquopy-libjpeg",
                version="1.5.3",
                tag="py3-none-android_26_arm64_v8a",
                extra_files={"chaquopy/lib/libjpeg_chaquopy.so": b"\x7fELFfixture"},
            )

            def inspected(data: bytes, logical: str, native_abi: str) -> dict[str, object]:
                return {
                    "path": logical,
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "abi": native_abi,
                    "soname": "libjpeg_chaquopy.so",
                    "needed": ["libevil.so"],
                }

            with (
                mock.patch.object(
                    runtime_wheels,
                    "_expected_license_hashes",
                    return_value=("BSD-3-Clause", {license_hash}),
                ),
                mock.patch.object(runtime_wheels, "inspect_elf", side_effect=inspected),
            ):
                with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "unlisted native"):
                    runtime_wheels.verify_runtime_wheel(path)

    def test_native_inspection_rejects_absolute_build_paths_before_parsing(self) -> None:
        with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "build path"):
            runtime_wheels.inspect_elf(
                b"\x7fELF debug /home/builder/runtime",
                "module.so",
                "x86_64",
            )


class ReproducibilityTests(unittest.TestCase):
    def test_publish_rejects_nonidentical_clean_stage_wheels(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage_a = root / "stage-a"
            stage_b = root / "stage-b"
            dist_a = stage_a / "dist"
            dist_b = stage_b / "dist"
            dist_a.mkdir(parents=True)
            dist_b.mkdir(parents=True)
            for package in runtime_wheels.NATIVE_SPECS:
                for abi in runtime_wheels.ABIS:
                    name = wheel_name(package, abi)
                    (dist_a / name).write_bytes(b"same")
                    (dist_b / name).write_bytes(
                        b"different" if package == "lxml" and abi == "x86_64" else b"same",
                    )

            def validated(stage: Path, *args, **kwargs):
                if stage == stage_a:
                    return {"stage_id": "clean-a"}, dist_a
                return {"stage_id": "clean-b"}, dist_b

            identity = builder_identity()
            with (
                mock.patch.object(runtime_wheels, "enforce_reproducible_environment"),
                mock.patch.object(
                    runtime_wheels,
                    "validate_expected_keys",
                    return_value=("1" * 64, "2" * 64, identity),
                ),
                mock.patch.object(runtime_wheels, "validate_stage", side_effect=validated),
            ):
                with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "not byte-for-byte"):
                    runtime_wheels.publish(
                        stage_a,
                        stage_b,
                        root / "downloads",
                        root / "out",
                        "1" * 64,
                        "2" * 64,
                    )

    def test_attributions_require_every_locked_runtime_package(self) -> None:
        entry = {
            "package": "requests",
            "version": "2.34.2",
            "license_expression": "Apache-2.0",
            "licenses": [{"path": "LICENSE", "sha256": "a" * 64}],
        }
        with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "incomplete"):
            runtime_wheels._attributions([entry])


class PublicationStateTests(unittest.TestCase):
    def test_publication_rejects_symlink_and_directory_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "publication"
            target.mkdir()
            regular = target / "manifest.json"
            regular.write_text("{}\n", encoding="utf-8")
            extra = target / "extra"
            extra.mkdir()
            with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "non-regular"):
                runtime_wheels._publication_regular_files(target)
            extra.rmdir()
            regular.unlink()
            regular.symlink_to(root / "outside-manifest.json")
            with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "non-regular"):
                runtime_wheels._publication_regular_files(target)

    def test_verification_summary_has_exact_grouped_filenames(self) -> None:
        wheels = {
            "common": [{"filename": "z.whl"}, {"filename": "a.whl"}],
            "arm64-v8a": [{"filename": "arm.whl"}],
            "x86_64": [{"filename": "x86.whl"}],
        }
        result = runtime_wheels._publication_summary("a" * 64, "b" * 64, wheels)
        self.assertEqual(
            {
                "schema",
                "recipe_key",
                "build_key",
                "api_level",
                "ndk",
                "python_target",
                "groups",
            },
            set(result),
        )
        self.assertEqual(
            {
                "common": ["a.whl", "z.whl"],
                "arm64-v8a": ["arm.whl"],
                "x86_64": ["x86.whl"],
            },
            result["groups"],
        )

    def test_activation_is_atomic_and_target_survives_pointer_removal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / f"runtime-wheels-{'b' * 64}"
            target.mkdir()
            manifest = target / "manifest.json"
            manifest.write_text("{}\n", encoding="utf-8")
            pointer = root / "checkout/out/current"
            verification = {"schema": 1, "build_key": "b" * 64}
            with mock.patch.object(
                runtime_wheels,
                "verify_publication",
                return_value=verification,
            ):
                self.assertEqual(
                    verification,
                    runtime_wheels.activate_publication(manifest, pointer),
                )
            self.assertTrue(pointer.is_symlink())
            self.assertEqual(target.resolve(), pointer.resolve())
            pointer.unlink()
            self.assertTrue(target.is_dir())
            self.assertTrue(manifest.is_file())

    def test_failed_verification_preserves_prior_pointer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_target = root / "old-publication"
            old_target.mkdir()
            pointer = root / "checkout/out/current"
            pointer.parent.mkdir(parents=True)
            pointer.symlink_to(old_target, target_is_directory=True)
            new_target = root / f"runtime-wheels-{'b' * 64}"
            new_target.mkdir()
            manifest = new_target / "manifest.json"
            manifest.write_text("{}\n", encoding="utf-8")
            with mock.patch.object(
                runtime_wheels,
                "verify_publication",
                side_effect=runtime_wheels.RuntimeWheelError("invalid publication"),
            ):
                with self.assertRaisesRegex(runtime_wheels.RuntimeWheelError, "invalid"):
                    runtime_wheels.activate_publication(manifest, pointer)
            self.assertTrue(pointer.is_symlink())
            self.assertEqual(old_target.resolve(), pointer.resolve())


class DriverBehaviorTests(unittest.TestCase):
    @staticmethod
    def _environment(root: Path, *, delay: str = "0") -> dict[str, str]:
        return {
            **os.environ,
            "TEST_ROOT": str(root),
            "TEST_BUILD_DELAY": delay,
            "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root / "toolchain"),
        }

    def _run(self, root: Path, mode: str = "success") -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", "-c", driver_command(mode)],
            env=self._environment(root),
            text=True,
            capture_output=True,
            timeout=20,
            check=False,
        )

    def test_concurrent_invocations_serialize_and_second_reuses(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            environment = self._environment(root, delay="0.4")
            first = subprocess.Popen(
                ["bash", "-c", driver_command()],
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            second = subprocess.Popen(
                ["bash", "-c", driver_command()],
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            first_output = first.communicate(timeout=20)
            second_output = second.communicate(timeout=20)
            self.assertEqual(0, first.returncode, first_output)
            self.assertEqual(0, second.returncode, second_output)
            self.assertEqual(["build"], (root / "builds.log").read_text().splitlines())

            target = root / "publications" / f"runtime-wheels-{'b' * 64}"
            pointer = root / "out/current"
            self.assertEqual(target.resolve(), pointer.resolve())
            self.assertEqual([], list((root / "build").glob("runtime-*-run-*")))

            reused = self._run(root)
            self.assertEqual(0, reused.returncode, (reused.stdout, reused.stderr))
            self.assertEqual(["build"], (root / "builds.log").read_text().splitlines())

    def test_invalid_existing_target_is_rejected_without_rebuild(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "publications" / f"runtime-wheels-{'b' * 64}"
            target.mkdir(parents=True)
            old_target = root / "old-publication"
            old_target.mkdir()
            pointer = root / "out/current"
            pointer.parent.mkdir(parents=True)
            pointer.symlink_to(old_target, target_is_directory=True)

            result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "builds.log").exists())
            self.assertEqual(old_target.resolve(), pointer.resolve())

    def test_build_failure_preserves_prior_pointer_and_private_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_target = root / "old-publication"
            old_target.mkdir()
            pointer = root / "out/current"
            pointer.parent.mkdir(parents=True)
            pointer.symlink_to(old_target, target_is_directory=True)

            result = self._run(root, mode="fail")
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(old_target.resolve(), pointer.resolve())
            self.assertEqual(1, len(list((root / "build").glob("runtime-*-run-*"))))


if __name__ == "__main__":
    unittest.main()
