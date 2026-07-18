from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import struct
import sys
import tarfile
import tempfile
import types
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools/wheels/s1a_wheels.py"
SPEC = importlib.util.spec_from_file_location("s1a_wheels", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
s1a_wheels = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(s1a_wheels)


def dynamic_elf(
    *,
    soname: str | None,
    needed: tuple[str, ...],
    machine: int = 62,
    alignment: int = 16 * 1024,
) -> bytes:
    strings = bytearray(b"\0")
    offsets: dict[str, int] = {}
    for value in (*needed, *((soname,) if soname else ())):
        if value not in offsets:
            offsets[value] = len(strings)
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
        alignment,
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
        *((1, offsets[value]) for value in needed),
    ]
    if soname:
        entries.append((14, offsets[soname]))
    entries.append((0, 0))
    for index, entry in enumerate(entries):
        struct.pack_into("<qQ", data, dynamic_offset + index * 16, *entry)
    data[string_offset:] = strings
    return bytes(data)


S1A_VALID_WHEEL_CASES = {
    "chaquopy_libcxx": {
        "filename": "chaquopy_libcxx-190000-0-py3-none-android_26_x86_64.whl",
        "version": "190000",
        "tag": "py3-none-android_26_x86_64",
        "requirements": (),
        "extras": (),
        "license": ("LICENSE.TXT", b"Apache License with LLVM Exceptions\n"),
        "native": (
            "chaquopy/lib/libc++_shared.so",
            "libc++_shared.so",
            ("libc.so",),
        ),
    },
    "chaquopy_libmecab": {
        "filename": "chaquopy_libmecab-0.996-0-py3-none-android_26_x86_64.whl",
        "version": "0.996",
        "tag": "py3-none-android_26_x86_64",
        "requirements": ("chaquopy-libcxx (>=190000)",),
        "extras": (),
        "license": ("BSD", b"Taku Kudo redistribution terms\n"),
        "native": (
            "chaquopy/lib/libmecab.so.2",
            "libmecab.so.2",
            ("libc++_shared.so", "libc.so"),
        ),
    },
    "fugashi": {
        "filename": "fugashi-1.5.2-0-cp312-cp312-android_26_x86_64.whl",
        "version": "1.5.2",
        "tag": "cp312-cp312-android_26_x86_64",
        "requirements": (
            'unidic; extra == "unidic"',
            'unidic-lite; extra == "unidic-lite"',
            "chaquopy-libmecab (>=0.996)",
        ),
        "extras": ("unidic", "unidic-lite"),
        "license": ("LICENSE", b"Permission is hereby granted to use Fugashi\n"),
        "native": (
            "fugashi/fugashi.so",
            None,
            ("libmecab.so.2", "libpython3.12.so", "libc.so"),
        ),
    },
}


class S1aWheelToolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.identity = s1a_wheels.builder_identity()

    def setUp(self) -> None:
        self.environment = mock.patch.dict(
            os.environ,
            s1a_wheels.REPRODUCIBLE_ENV,
            clear=False,
        )
        self.environment.start()
        self.previous_umask = os.umask(0o022)
        self.recipe = s1a_wheels.source_recipe_key()
        self.build = s1a_wheels.build_key(self.recipe, self.identity)

    def tearDown(self) -> None:
        os.umask(self.previous_umask)
        self.environment.stop()

    @staticmethod
    def _write_valid_s1a_wheel(
        root: Path,
        package: str,
        *,
        native_path: str | None = None,
        requirements: tuple[str, ...] | None = None,
        extras: tuple[str, ...] | None = None,
    ) -> Path:
        case = S1A_VALID_WHEEL_CASES[package]
        requirements = case["requirements"] if requirements is None else requirements
        extras = case["extras"] if extras is None else extras
        wheel = root / case["filename"]
        dist_info = f"{package}-{case['version']}.dist-info"
        metadata = [
            "Metadata-Version: 2.1",
            f"Name: {package.replace('_', '-')}",
            f"Version: {case['version']}",
            *(f"Provides-Extra: {value}" for value in extras),
            *(f"Requires-Dist: {value}" for value in requirements),
            "",
        ]
        canonical_native_path, soname, needed = case["native"]
        license_name, license_text = case["license"]
        with zipfile.ZipFile(wheel, "w") as archive:
            archive.writestr(f"{dist_info}/METADATA", "\n".join(metadata))
            archive.writestr(
                f"{dist_info}/WHEEL",
                f"Wheel-Version: 1.0\nTag: {case['tag']}\n",
            )
            archive.writestr(f"{dist_info}/{license_name}", license_text)
            archive.writestr(
                native_path or canonical_native_path,
                dynamic_elf(soname=soname, needed=needed),
            )
        return wheel

    def _fake_wheel_set(self, dist: Path, payload_suffix: bytes = b"") -> list[Path]:
        filenames = []
        for platform in ("arm64_v8a", "x86_64"):
            filenames.extend(
                [
                    f"chaquopy_libcxx-190000-0-py3-none-android_26_{platform}.whl",
                    f"chaquopy_libmecab-0.996-0-py3-none-android_26_{platform}.whl",
                    f"fugashi-1.5.2-0-cp312-cp312-android_26_{platform}.whl",
                ],
            )
        paths = []
        for filename in filenames:
            path = dist / filename.split("-", 1)[0] / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(filename.encode() + payload_suffix)
            paths.append(path)
        return paths

    def _fake_stage(self, root: Path, stage_id: str) -> tuple[Path, list[Path]]:
        stage = root / f"s1a-{self.build}-{stage_id}"
        dist = stage / "chaquopy/source/server/pypi/dist"
        wheels = self._fake_wheel_set(dist)
        (stage / "patchelf").mkdir(parents=True)
        manifest = {
            "schema": s1a_wheels.MANIFEST_SCHEMA,
            "stage_id": stage_id,
            "recipe_key": self.recipe,
            "build_key": self.build,
            "builder_identity": self.identity,
            "recipe_inventory": s1a_wheels.recipe_inventory(),
            "ndk": s1a_wheels.NDK_VERSION,
            "python_target": s1a_wheels.PYTHON_TARGET,
            "source_hashes": {name: entry["sha256"] for name, entry in s1a_wheels.source_entries().items()},
            "host_wheels": {filename: sha256 for _, _, filename, sha256 in s1a_wheels.host_entries()},
        }
        (stage / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return stage, wheels

    @staticmethod
    def _fake_verify(path: Path):
        package, abi = s1a_wheels._wheel_identity(path)
        return (
            package,
            abi,
            {
                "filename": path.name,
                "sha256": s1a_wheels.digest(path),
                "size": path.stat().st_size,
                "licenses": [],
                "elf": {"abi": abi},
            },
        )

    @staticmethod
    def _write_target_archive(
        root: Path,
        abi: str,
        *,
        needed: tuple[str, ...] = ("libc.so", "libdl.so", "libm.so"),
        alignment: int = 16 * 1024,
        suffix: bytes = b"",
        libpython_version: str = "3.12",
        machine_override: int | None = None,
    ) -> Path:
        machine = machine_override if machine_override is not None else (183 if abi == "arm64-v8a" else 62)
        archive_path = root / f"target-3.12.12-0-{abi}.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr(
                f"jniLibs/{abi}/libpython{libpython_version}.so",
                dynamic_elf(
                    soname=None,
                    needed=needed,
                    machine=machine,
                    alignment=alignment,
                )
                + suffix,
            )
            archive.writestr(
                f"lib-dynload/{abi}/_json.cpython-312.so",
                dynamic_elf(
                    soname=None,
                    needed=("libc.so",),
                    machine=machine,
                    alignment=alignment,
                ),
            )
        return archive_path

    def test_locks_are_exact_and_complete(self) -> None:
        sources = s1a_wheels.source_entries()
        self.assertEqual(
            {
                "chaquopy",
                "fugashi",
                "mecab",
                "patchelf",
                "python-arm64-v8a",
                "python-x86_64",
            },
            set(sources),
        )
        self.assertEqual("3.12.12-0", s1a_wheels.PYTHON_TARGET)
        for abi in s1a_wheels.ABIS:
            target = sources[f"python-{abi}"]
            self.assertEqual(
                f"target-3.12.12-0-{abi}.zip",
                target["filename"],
            )
            self.assertIn("/3.12.12-0/", target["url"])
        requirements = s1a_wheels.host_entries()
        self.assertIn(
            ("outer", "pip==25.1.1"),
            {(role, requirement) for role, requirement, _, _ in requirements},
        )
        target = [entry for entry in requirements if entry[0] == "target"]
        self.assertEqual(1, len(target))
        self.assertEqual("Cython==3.1.5", target[0][1])
        self.assertIn("cp312-cp312", target[0][2])
        self.assertNotIn("cp313", target[0][2])
        self.assertEqual(
            len(requirements),
            len({filename for _, _, filename, _ in requirements}),
        )

    def test_host_lock_rejects_schema_one_extra_keys_and_cp313_target(self) -> None:
        valid = json.loads(s1a_wheels.HOST_LOCK.read_text(encoding="utf-8"))
        invalid_documents = []
        schema_one = json.loads(json.dumps(valid))
        schema_one["schema"] = 1
        invalid_documents.append(schema_one)
        extra_key = json.loads(json.dumps(valid))
        extra_key["unexpected"] = True
        invalid_documents.append(extra_key)
        extra_entry_key = json.loads(json.dumps(valid))
        extra_entry_key["requirements"][0]["unexpected"] = True
        invalid_documents.append(extra_entry_key)
        cp313_target = json.loads(json.dumps(valid))
        target = next(entry for entry in cp313_target["requirements"] if entry["interpreter"] == "target")
        target["filename"] = target["filename"].replace("cp312", "cp313")
        invalid_documents.append(cp313_target)

        with tempfile.TemporaryDirectory() as temporary:
            lock = Path(temporary) / "host-wheels.lock"
            for document in invalid_documents:
                with self.subTest(document=document):
                    lock.write_text(json.dumps(document), encoding="utf-8")
                    with (
                        mock.patch.object(s1a_wheels, "HOST_LOCK", lock),
                        self.assertRaises(s1a_wheels.WheelError),
                    ):
                        s1a_wheels.host_entries()

    def test_host_wheel_fetch_uses_exact_interpreter_for_target_cython(self) -> None:
        payloads = {
            "outer.whl": b"outer",
            "target.whl": b"target",
        }
        entries = [
            (
                "outer",
                "outer-package==1",
                "outer.whl",
                hashlib.sha256(payloads["outer.whl"]).hexdigest(),
            ),
            (
                "target",
                "Cython==3.1.5",
                "target.whl",
                hashlib.sha256(payloads["target.whl"]).hexdigest(),
            ),
        ]
        commands: list[list[str]] = []

        def fake_run(command, **kwargs):
            commands.append(command)
            destination = Path(command[command.index("--dest") + 1])
            filename = "outer.whl" if command[0] == sys.executable else "target.whl"
            (destination / filename).write_bytes(payloads[filename])
            return subprocess.CompletedProcess(command, 0)

        with tempfile.TemporaryDirectory() as temporary:
            wheelhouse = Path(temporary) / "wheelhouse"
            with (
                mock.patch.object(s1a_wheels, "host_entries", return_value=entries),
                mock.patch.object(
                    s1a_wheels,
                    "target_python_executable",
                    return_value="/locked/python3.12",
                ),
                mock.patch.object(s1a_wheels.subprocess, "run", side_effect=fake_run),
            ):
                s1a_wheels.fetch_host_wheels(wheelhouse)
        self.assertEqual(sys.executable, commands[0][0])
        self.assertEqual("/locked/python3.12", commands[1][0])
        self.assertIn("Cython==3.1.5", commands[1])

    def test_recipe_key_uses_explicit_paths_modes_and_bytes_but_not_outputs(self) -> None:
        self.assertRegex(self.recipe, r"^[0-9a-f]{64}$")
        inventory = s1a_wheels.recipe_inventory()
        self.assertEqual(len(inventory), len({entry["path"] for entry in inventory}))
        self.assertIn(
            "tools/wheels/recipes/fugashi/patches/fugashi-android-link.patch", {entry["path"] for entry in inventory}
        )
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"
            copied = repo / "tools/wheels"
            copied.parent.mkdir(parents=True)
            shutil.copytree(
                s1a_wheels.TOOL_ROOT,
                copied,
                ignore=shutil.ignore_patterns("__pycache__", "out"),
            )
            (repo / "scripts").mkdir()
            for name in s1a_wheels.RECIPE_REPO_FILES:
                source = s1a_wheels.ROOT / name
                destination = repo / name
                shutil.copy2(source, destination)
            original = s1a_wheels.source_recipe_key(copied, repo)
            output = copied / "out/cache.whl"
            output.parent.mkdir()
            output.write_bytes(b"ignored output")
            self.assertEqual(original, s1a_wheels.source_recipe_key(copied, repo))
            lock = copied / "sources.lock"
            lock.write_bytes(lock.read_bytes() + b"\n")
            changed_bytes = s1a_wheels.source_recipe_key(copied, repo)
            self.assertNotEqual(original, changed_bytes)
            script = copied / "build-s1a-wheels.sh"
            script.chmod(script.stat().st_mode & ~0o111)
            changed_mode = s1a_wheels.source_recipe_key(copied, repo)
            self.assertNotEqual(changed_bytes, changed_mode)
            android_env = repo / "scripts/android-env.sh"
            android_env.write_bytes(android_env.read_bytes() + b"\n")
            self.assertNotEqual(changed_mode, s1a_wheels.source_recipe_key(copied, repo))

    def test_recipe_key_command_is_network_free_and_stable(self) -> None:
        result = subprocess.run(
            [str(MODULE_PATH), "recipe-key"],
            check=True,
            stdout=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(self.recipe, result.stdout.strip())

    def test_locked_pip_wheel_bootstraps_a_clean_pipless_venv(self) -> None:
        build_script = (ROOT / "tools/wheels/build-s1a-wheels.sh").read_text(encoding="utf-8")
        self.assertIn(
            'PYTHONPATH="$pip_wheel" "$builder_env/bin/python" -m pip install',
            build_script,
        )
        self.assertIn("pip==25.1.1", build_script)
        self.assertIn('PATH="$builder_env/bin:$patchelf_dir:$PATH"', build_script)
        self.assertIn('ANKI_MINER_S1A_STAGE_ROOT="$stage"', build_script)
        self.assertIn("build-wheel.py --python 3.12", build_script)
        self.assertIn("export ANKI_MINER_CHAQUOPY_BUILD_PYTHON", build_script)
        self.assertNotIn("build-wheel.py --python 3.13", build_script)
        self.assertNotIn('"$pip_wheel/pip"', build_script)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            environment = root / "builder-env"
            subprocess.run(
                ["python3.13", "-m", "venv", "--without-pip", str(environment)],
                check=True,
            )
            python = environment / "bin/python"
            missing = subprocess.run(
                [str(python), "-m", "pip", "--version"],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, missing.returncode)
            pip_wheel = root / "pip-25.1.1-py3-none-any.whl"
            with zipfile.ZipFile(pip_wheel, "w") as archive:
                archive.writestr("pip/__init__.py", '__version__ = "25.1.1"\n')
                archive.writestr(
                    "pip/__main__.py",
                    "from pip import __version__\nprint(__version__)\n",
                )
            bootstrapped = subprocess.run(
                [str(python), "-m", "pip", "--version"],
                env={**os.environ, "PYTHONPATH": str(pip_wheel)},
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, bootstrapped.returncode, bootstrapped.stderr)
            self.assertEqual("25.1.1", bootstrapped.stdout.strip())

    def test_builder_identity_and_build_key_cover_required_host_tools(self) -> None:
        self.assertEqual(2, self.identity["schema"])
        outer = self.identity["interpreters"]["outer"]
        target = self.identity["interpreters"]["target"]
        self.assertEqual("cpython", outer["implementation"])
        self.assertRegex(outer["version"], r"^3\.13\.\d+$")
        self.assertRegex(outer["executable_sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual("cpython", target["implementation"])
        self.assertEqual("3.12.13", target["version"])
        self.assertRegex(target["executable_sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual("linux", self.identity["host"]["os"])
        self.assertEqual("x86_64", self.identity["host"]["machine"])
        self.assertEqual(
            {
                "bash",
                "coreutils",
                "findutils",
                "git",
                "grep",
                "make",
                "patch",
                "sed",
                "unzip",
            },
            set(self.identity["tools"]),
        )
        expected = hashlib.sha256(
            self.recipe.encode("ascii") + b"\n" + s1a_wheels._canonical_json(self.identity),
        ).hexdigest()
        self.assertEqual(expected, self.build)
        for interpreter in ("outer", "target"):
            changed = json.loads(json.dumps(self.identity))
            changed["interpreters"][interpreter]["executable_sha256"] = "f" * 64
            self.assertNotEqual(
                self.build,
                s1a_wheels.build_key(self.recipe, changed),
            )

    def test_builder_identity_rejects_schema_one_extra_keys_and_cp313_target(self) -> None:
        invalid = json.loads(json.dumps(self.identity))
        invalid["schema"] = 1
        with self.assertRaisesRegex(s1a_wheels.WheelError, "schema"):
            s1a_wheels._validate_builder_identity(invalid)

        invalid = json.loads(json.dumps(self.identity))
        invalid["unexpected"] = True
        with self.assertRaisesRegex(s1a_wheels.WheelError, "exactly"):
            s1a_wheels._validate_builder_identity(invalid)

        invalid = json.loads(json.dumps(self.identity))
        invalid["interpreters"]["target"]["version"] = "3.13.7"
        with self.assertRaisesRegex(s1a_wheels.WheelError, "wrong version"):
            s1a_wheels._validate_builder_identity(invalid)

    def test_reproducible_environment_and_expected_keys_are_enforced(self) -> None:
        s1a_wheels.enforce_reproducible_environment()
        with mock.patch.dict(os.environ, {"SOURCE_DATE_EPOCH": "1"}):
            with self.assertRaisesRegex(s1a_wheels.WheelError, "SOURCE_DATE_EPOCH"):
                s1a_wheels.enforce_reproducible_environment()
        with mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity):
            with self.assertRaisesRegex(s1a_wheels.WheelError, "stale source recipe key"):
                s1a_wheels._validate_expected_keys("0" * 64, self.build)
            with self.assertRaisesRegex(s1a_wheels.WheelError, "stale build key"):
                s1a_wheels._validate_expected_keys(self.recipe, "0" * 64)

    def test_safe_extractor_rejects_traversal_and_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            traversal = root / "traversal.tar.gz"
            with tarfile.open(traversal, "w:gz") as archive:
                info = tarfile.TarInfo("../escape")
                info.size = 1
                archive.addfile(info, io.BytesIO(b"x"))
            with self.assertRaises(s1a_wheels.WheelError):
                s1a_wheels.safe_extract(traversal, root / "out-traversal")

            linked = root / "linked.tar.gz"
            with tarfile.open(linked, "w:gz") as archive:
                info = tarfile.TarInfo("root/link")
                info.type = tarfile.SYMTYPE
                info.linkname = "/tmp/target"
                archive.addfile(info)
            with self.assertRaises(s1a_wheels.WheelError):
                s1a_wheels.safe_extract(linked, root / "out-linked")

    def test_python_target_archive_audit_covers_both_abis(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for abi in s1a_wheels.ABIS:
                with self.subTest(abi=abi):
                    archive = self._write_target_archive(root, abi)
                    result = s1a_wheels.verify_python_target_archive(
                        archive,
                        abi,
                        s1a_wheels.digest(archive),
                    )
                    self.assertEqual(abi, result["abi"])
                    self.assertEqual(2, result["native_count"])
                    self.assertEqual(
                        ["libc.so", "libdl.so", "libm.so"],
                        result["libpython"]["needed"],
                    )

    def test_python_target_archive_audit_rejects_hash_abi_deps_alignment_and_signatures(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self._write_target_archive(root, "x86_64")
            with self.assertRaisesRegex(s1a_wheels.WheelError, "hash-mismatched"):
                s1a_wheels.verify_python_target_archive(
                    archive,
                    "x86_64",
                    "0" * 64,
                )

            wrong_abi = self._write_target_archive(
                root,
                "arm64-v8a",
                machine_override=62,
            )
            with self.assertRaisesRegex(s1a_wheels.WheelError, "contains ABI"):
                s1a_wheels.verify_python_target_archive(
                    wrong_abi,
                    "arm64-v8a",
                    s1a_wheels.digest(wrong_abi),
                )

            wrong_deps = self._write_target_archive(
                root,
                "x86_64",
                needed=("libc.so", "libdl.so"),
            )
            with self.assertRaisesRegex(s1a_wheels.WheelError, "dependencies"):
                s1a_wheels.verify_python_target_archive(
                    wrong_deps,
                    "x86_64",
                    s1a_wheels.digest(wrong_deps),
                )

            misaligned = self._write_target_archive(
                root,
                "x86_64",
                alignment=4096,
            )
            with self.assertRaisesRegex(s1a_wheels.WheelError, "alignment"):
                s1a_wheels.verify_python_target_archive(
                    misaligned,
                    "x86_64",
                    s1a_wheels.digest(misaligned),
                )

            for signature in (b"mimalloc", b"/proc/sys/vm/overcommit_memory"):
                signed = self._write_target_archive(root, "x86_64", suffix=signature)
                with (
                    self.subTest(signature=signature),
                    self.assertRaisesRegex(s1a_wheels.WheelError, "forbidden"),
                ):
                    s1a_wheels.verify_python_target_archive(
                        signed,
                        "x86_64",
                        s1a_wheels.digest(signed),
                    )

            cp313 = self._write_target_archive(
                root,
                "x86_64",
                libpython_version="3.13",
            )
            with self.assertRaisesRegex(s1a_wheels.WheelError, "libpython3.12"):
                s1a_wheels.verify_python_target_archive(
                    cp313,
                    "x86_64",
                    s1a_wheels.digest(cp313),
                )

    def test_recipes_match_pinned_chaquopy_schema_layout(self) -> None:
        recipe_root = ROOT / "tools/wheels/recipes"
        mecab = (recipe_root / "chaquopy-libmecab/meta.yaml").read_text(encoding="utf-8")
        fugashi = (recipe_root / "fugashi/meta.yaml").read_text(encoding="utf-8")
        self.assertIn("  license_file: BSD", mecab)
        self.assertIn("  license_file: LICENSE", fugashi)
        self.assertNotIn("  license:", mecab + fugashi)
        self.assertNotIn("\npatches:", fugashi)
        self.assertTrue((recipe_root / "fugashi/patches/fugashi-android-link.patch").is_file())
        combined = "\n".join(
            path.read_text(encoding="utf-8") for path in sorted(recipe_root.rglob("*")) if path.is_file()
        )
        self.assertIn("make -C src", combined)
        self.assertIn("--enable-utf8-only", combined)
        self.assertIn("-std=gnu++14", combined)
        self.assertIn("-print-libgcc-file-name", combined)
        self.assertIn("libclang_rt.builtins-aarch64-android.a", combined)
        self.assertIn("libclang_rt.builtins-x86_64-android.a", combined)
        self.assertIn("ac_cv_lib_stdcpp_main=no", combined)
        self.assertIn("src/Makefile", combined)
        self.assertIn("chaquopy-libmecab 0.996", combined)
        self.assertNotIn("unidic-lite", combined.casefold())
        self.assertNotIn("mecab-ipadic", combined.casefold())

    def test_staged_recipe_validation_rejects_forbidden_property_before_build(self) -> None:
        class FakeSchemaError(Exception):
            pass

        class FakeValidationError(Exception):
            pass

        class FakeValidator:
            VALIDATORS = {"properties": lambda *args: iter(())}

            @classmethod
            def check_schema(cls, schema):
                if not isinstance(schema, dict):
                    raise FakeSchemaError()

            def __init__(self, schema):
                self.schema = schema

            def validate(self, instance):
                def validate_object(schema, value):
                    if not isinstance(schema, dict) or schema.get("type") != "object":
                        return
                    if not isinstance(value, dict):
                        raise FakeValidationError()
                    properties = schema.get("properties", {})
                    if schema.get("additionalProperties") is False:
                        if set(value) - set(properties):
                            raise FakeValidationError()
                    for name, nested in properties.items():
                        if name in value:
                            validate_object(nested, value[name])

                validate_object(self.schema, instance)

        class FakeTemplateError(Exception):
            pass

        class FakeTemplate:
            def __init__(self, value, undefined):
                self.value = value

            def render(self, **kwargs):
                return self.value

        jsonschema = types.ModuleType("jsonschema")
        jsonschema.Draft4Validator = FakeValidator
        jsonschema.SchemaError = FakeSchemaError
        jsonschema.ValidationError = FakeValidationError
        jsonschema.validators = types.SimpleNamespace(
            extend=lambda validator, additions: validator,
        )
        yaml = types.ModuleType("yaml")
        yaml.safe_load = json.loads
        yaml.YAMLError = ValueError
        jinja2 = types.ModuleType("jinja2")
        jinja2.StrictUndefined = object()
        jinja2.Template = FakeTemplate
        jinja2.TemplateError = FakeTemplateError

        with tempfile.TemporaryDirectory() as temporary:
            chaquopy = Path(temporary) / "chaquopy"
            pypi = chaquopy / "server/pypi"
            packages = pypi / "packages"
            packages.mkdir(parents=True)
            schema = {
                "type": "object",
                "properties": {
                    "package": {"type": "object"},
                    "source": {},
                    "build": {},
                    "requirements": {},
                    "about": {
                        "type": "object",
                        "properties": {"license_file": {}},
                        "additionalProperties": False,
                    },
                },
                "additionalProperties": False,
            }
            (pypi / "meta-schema.yaml").write_text(json.dumps(schema), encoding="utf-8")
            recipe_names = sorted(
                path.name for path in s1a_wheels.TOOL_ROOT.joinpath("recipes").iterdir() if path.is_dir()
            )
            for name in recipe_names:
                recipe = packages / name
                recipe.mkdir()
                (recipe / "meta.yaml").write_text(
                    json.dumps(
                        {
                            "package": {"name": name, "version": "1"},
                            "about": {"license_file": "LICENSE"},
                        },
                    ),
                    encoding="utf-8",
                )
            modules = {"jsonschema": jsonschema, "yaml": yaml, "jinja2": jinja2}
            with mock.patch.dict(sys.modules, modules):
                self.assertEqual(recipe_names, s1a_wheels.validate_recipes(chaquopy))
                invalid = packages / recipe_names[0] / "meta.yaml"
                document = json.loads(invalid.read_text(encoding="utf-8"))
                document["about"]["license"] = "forbidden"
                invalid.write_text(json.dumps(document), encoding="utf-8")
                with self.assertRaisesRegex(s1a_wheels.WheelError, "invalid staged custom recipe"):
                    s1a_wheels.validate_recipes(chaquopy)

        build_script = (ROOT / "tools/wheels/build-s1a-wheels.sh").read_text(encoding="utf-8")
        self.assertLess(
            build_script.index("validate-recipes"),
            build_script.index("build-wheel.py --abi"),
        )

    def test_builder_patch_is_network_closed(self) -> None:
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn("network source discovery is disabled", source)
        self.assertIn("--no-index --only-binary=:all:", source)
        self.assertIn("locked NDK is missing", source)
        self.assertIn("yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager", source)

    def test_builder_patch_preserves_non_network_build_methods(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            chaquopy = Path(temporary) / "chaquopy"
            builder = chaquopy / "server/pypi/build-wheel.py"
            builder.parent.mkdir(parents=True)
            builder.write_text(
                """import os
import pypi_simple

class BuildWheel:
    def configure(self):
        os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
        pip_version = "23.2.1"
        run(f"{bootstrap_env}/bin/pip install pip=={pip_version}")
        command = (f"install " + " ".join(shlex.quote(req) for req in requirements))

    def create_build_env(self):
        run(f"python{python_ver} -m venv --without-pip {self.build_env}")

    def get_bootstrap_env(self):
        run(f"python{python_ver} -m venv {bootstrap_env}")

    def download_git(self, source):
        return "GIT_NETWORK_SENTINEL"

    def download_pypi(self):
        return "PYPI_NETWORK_SENTINEL"

    def download_url(self, url):
        return "URL_NETWORK_SENTINEL"

    def apply_patches(self):
        return "PATCH_SENTINEL"

    def build_wheel(self):
        return "BUILD_SENTINEL"

    def create_host_env(self):
        return "HOST_SENTINEL"
""",
                encoding="utf-8",
            )
            android_env = chaquopy / "target/android-env.sh"
            android_env.parent.mkdir(parents=True)
            android_env.write_text(
                """ndk_version=27.3.13750724
if ! [ -e $ndk ]; then
    log "Installing NDK - this may take several minutes"
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$ndk_version"
fi
export CFLAGS="-D__BIONIC_NO_PAGE_SIZE_MACRO"
""",
                encoding="utf-8",
            )
            libcxx = chaquopy / "server/pypi/packages/chaquopy-libcxx/meta.yaml"
            libcxx.parent.mkdir(parents=True)
            libcxx.write_text('version: "180000"\n', encoding="utf-8")

            s1a_wheels.patch_builder(chaquopy, Path(temporary) / "wheelhouse")

            patched = builder.read_text(encoding="utf-8")
            compile(patched, str(builder), "exec")
            self.assertEqual(3, patched.count("network source discovery is disabled"))
            self.assertNotIn("GIT_NETWORK_SENTINEL", patched)
            self.assertNotIn("PYPI_NETWORK_SENTINEL", patched)
            self.assertNotIn("URL_NETWORK_SENTINEL", patched)
            self.assertIn("PATCH_SENTINEL", patched)
            self.assertIn("BUILD_SENTINEL", patched)
            self.assertIn("HOST_SENTINEL", patched)
            self.assertEqual(2, patched.count("ANKI_MINER_CHAQUOPY_BUILD_PYTHON"))
            self.assertNotIn('run(f"python{python_ver} -m venv', patched)
            patched_android_env = android_env.read_text(encoding="utf-8")
            self.assertIn("-ffile-prefix-map=", patched_android_env)
            self.assertIn("-fdebug-prefix-map=", patched_android_env)
            self.assertIn("-fmacro-prefix-map=", patched_android_env)

    def test_wheel_identity_requires_exact_version_api_and_tags(self) -> None:
        package, abi = s1a_wheels._wheel_identity(
            Path("fugashi-1.5.2-0-cp312-cp312-android_26_arm64_v8a.whl"),
        )
        self.assertEqual(("fugashi", "arm64-v8a"), (package, abi))
        for invalid in (
            "fugashi-1.5.1-0-cp312-cp312-android_26_arm64_v8a.whl",
            "fugashi-1.5.2-0-cp312-cp312-android_25_arm64_v8a.whl",
            "fugashi-1.5.2-0-cp313-cp313-android_26_arm64_v8a.whl",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(s1a_wheels.WheelError):
                s1a_wheels._wheel_identity(Path(invalid))

    def test_wheel_verifier_rejects_dictionary_before_native_inspection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            wheel = Path(temporary) / "fugashi-1.5.2-0-cp312-cp312-android_26_x86_64.whl"
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("unidic_lite/dicdir/sys.dic", b"forbidden")
            with self.assertRaisesRegex(s1a_wheels.WheelError, "dictionary"):
                s1a_wheels.verify_s1a_wheel(wheel)

    def test_wheel_verifier_accepts_exact_package_attributions_and_native_payloads(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for package, case in S1A_VALID_WHEEL_CASES.items():
                with self.subTest(package=package):
                    wheel = self._write_valid_s1a_wheel(root, package)
                    dist_info = f"{package}-{case['version']}.dist-info"
                    license_name, _ = case["license"]
                    verified_package, abi, entry = s1a_wheels.verify_s1a_wheel(wheel)
                    self.assertEqual((package, "x86_64"), (verified_package, abi))
                    self.assertEqual(f"{dist_info}/{license_name}", entry["licenses"][0]["path"])

    def test_wheel_verifier_rejects_unconditional_or_unknown_optional_dependencies(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            unconditional = self._write_valid_s1a_wheel(
                root,
                "fugashi",
                requirements=(
                    "unidic",
                    'unidic-lite; extra == "unidic-lite"',
                    "chaquopy-libmecab (>=0.996)",
                ),
            )
            with self.assertRaisesRegex(s1a_wheels.WheelError, "dependency set"):
                s1a_wheels.verify_s1a_wheel(unconditional)

            unknown_marker = self._write_valid_s1a_wheel(
                root,
                "fugashi",
                requirements=(
                    'unidic; python_version >= "3.13"',
                    'unidic-lite; extra == "unidic-lite"',
                    "chaquopy-libmecab (>=0.996)",
                ),
            )
            with self.assertRaisesRegex(s1a_wheels.WheelError, "unsupported S1a requirement"):
                s1a_wheels.verify_s1a_wheel(unknown_marker)

    def test_wheel_verifier_rejects_native_payloads_outside_exact_paths(self) -> None:
        wrong_paths = {
            "chaquopy_libcxx": "deeper/chaquopy/lib/libc++_shared.so",
            "chaquopy_libmecab": "chaquopy/lib/deeper/libmecab.so.2",
            "fugashi": "fugashi/deeper/fugashi.so",
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for package, native_path in wrong_paths.items():
                with self.subTest(package=package, native_path=native_path):
                    wheel = self._write_valid_s1a_wheel(
                        root,
                        package,
                        native_path=native_path,
                    )
                    with self.assertRaisesRegex(
                        s1a_wheels.WheelError,
                        "native payload path",
                    ):
                        s1a_wheels.verify_s1a_wheel(wheel)

    def test_wheel_members_reject_unsafe_and_ambiguous_paths(self) -> None:
        class FakeInfo:
            def __init__(self, filename: str, directory: bool = False):
                self.filename = filename
                self._directory = directory

            def is_dir(self) -> bool:
                return self._directory

        class FakeArchive:
            def __init__(self, entries):
                self.entries = entries

            def infolist(self):
                return self.entries

        for unsafe in ("", "../escape", "a/../escape", "a\\b", "/absolute", "a//b", "a/./b"):
            with self.subTest(unsafe=unsafe), self.assertRaisesRegex(s1a_wheels.WheelError, "unsafe wheel entry"):
                s1a_wheels._validated_wheel_members(
                    FakeArchive([FakeInfo(unsafe)]),
                    "fixture.whl",
                )
        with self.assertRaisesRegex(s1a_wheels.WheelError, "ambiguity"):
            s1a_wheels._validated_wheel_members(
                FakeArchive([FakeInfo("same"), FakeInfo("same/", True)]),
                "fixture.whl",
            )
        for entries in (
            [FakeInfo("fugashi"), FakeInfo("fugashi/fugashi.so")],
            [FakeInfo("fugashi/fugashi.so"), FakeInfo("fugashi")],
        ):
            with self.subTest(order=[entry.filename for entry in entries]):
                with self.assertRaisesRegex(
                    s1a_wheels.WheelError,
                    "file/descendant ambiguity",
                ):
                    s1a_wheels._validated_wheel_members(
                        FakeArchive(entries),
                        "fixture.whl",
                    )

    def test_wheel_elf_inspection_rejects_wrong_class_and_endianness(self) -> None:
        def elf64(*, elf_class: int = 2, data_encoding: int = 1) -> bytes:
            data = bytearray(64 + 56)
            data[:16] = b"\x7fELF" + bytes((elf_class, data_encoding, 1)) + bytes(9)
            struct.pack_into(
                "<HHIQQQIHHHHHH",
                data,
                16,
                3,
                62,
                1,
                0,
                64,
                0,
                0,
                64,
                56,
                1,
                0,
                0,
                0,
            )
            struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), 16384)
            return bytes(data)

        with self.assertRaisesRegex(s1a_wheels.WheelError, "requires ELF class"):
            s1a_wheels._inspect_elf(elf64(elf_class=1), "wrong-class.so", "x86_64")
        with self.assertRaisesRegex(s1a_wheels.WheelError, "little-endian"):
            s1a_wheels._inspect_elf(
                elf64(data_encoding=2),
                "big-endian.so",
                "x86_64",
            )

    def test_publication_requires_two_identical_validated_stages(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage_a, wheels = self._fake_stage(root, "clean-a")
            stage_b, _ = self._fake_stage(root, "clean-b")
            with (
                mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity),
                mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=self._fake_verify),
            ):
                manifest_path = s1a_wheels.publish(
                    stage_a,
                    stage_b,
                    root / "published",
                    self.recipe,
                    self.build,
                )
            document = s1a_wheels.load_json(manifest_path, s1a_wheels.MANIFEST_SCHEMA)
            self.assertEqual(self.recipe, document["recipe_key"])
            self.assertEqual(self.build, document["build_key"])
            self.assertEqual(f"s1a-wheels-{self.build}", manifest_path.parent.name)
            self.assertTrue(document["reproducibility"]["wheel_sets_byte_identical"])
            self.assertEqual(
                {path.name for path in wheels},
                {path.name for path in manifest_path.parent.glob("*.whl")},
            )
            with mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=self._fake_verify):
                keys = s1a_wheels.verify_publication(manifest_path)
            self.assertEqual(
                {"schema": 2, "recipe_key": self.recipe, "build_key": self.build},
                keys,
            )
            with self.assertRaisesRegex(s1a_wheels.WheelError, "invalid wheel"):
                s1a_wheels.verify_publication(manifest_path)
            document["wheels"]["x86_64"][0]["size"] += 1
            manifest_path.write_text(json.dumps(document), encoding="utf-8")
            with (
                mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=self._fake_verify),
                self.assertRaisesRegex(s1a_wheels.WheelError, "inventory mismatch"),
            ):
                s1a_wheels.verify_publication(manifest_path)
            with (
                mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity),
                mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=self._fake_verify),
                self.assertRaisesRegex(s1a_wheels.WheelError, "immutable"),
            ):
                s1a_wheels.publish(
                    stage_a,
                    stage_b,
                    root / "published",
                    self.recipe,
                    self.build,
                )

    def test_publication_rejects_nonreproducible_or_stale_stage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage_a, _ = self._fake_stage(root, "clean-a")
            stage_b, wheels_b = self._fake_stage(root, "clean-b")
            wheels_b[0].write_bytes(wheels_b[0].read_bytes() + b"different")
            with mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity):
                with self.assertRaisesRegex(s1a_wheels.WheelError, "byte-for-byte"):
                    s1a_wheels.publish(
                        stage_a,
                        stage_b,
                        root / "published",
                        self.recipe,
                        self.build,
                    )
            wheels_b[0].write_bytes(wheels_b[0].read_bytes().removesuffix(b"different"))
            stage_document = json.loads((stage_b / "manifest.json").read_text(encoding="utf-8"))
            stage_document["recipe_key"] = "0" * 64
            (stage_b / "manifest.json").write_text(json.dumps(stage_document), encoding="utf-8")
            with mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity):
                with self.assertRaisesRegex(s1a_wheels.WheelError, "stale S1a stage"):
                    s1a_wheels.publish(
                        stage_a,
                        stage_b,
                        root / "published",
                        self.recipe,
                        self.build,
                    )

    def test_obsolete_publication_and_parent_mismatch_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage_a, _ = self._fake_stage(root, "clean-a")
            stage_b, _ = self._fake_stage(root, "clean-b")
            with (
                mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity),
                mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=self._fake_verify),
            ):
                manifest = s1a_wheels.publish(
                    stage_a,
                    stage_b,
                    root / "published",
                    self.recipe,
                    self.build,
                )
            with mock.patch.object(s1a_wheels, "source_recipe_key", return_value="f" * 64):
                with self.assertRaisesRegex(s1a_wheels.WheelError, "obsolete"):
                    s1a_wheels.verify_publication(manifest)
            moved = root / "published/stale-parent"
            manifest.parent.rename(moved)
            with self.assertRaisesRegex(s1a_wheels.WheelError, "parent directory"):
                s1a_wheels.verify_publication(moved / "manifest.json")

    def test_self_consistent_foreign_builder_publication_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage_a, _ = self._fake_stage(root, "clean-a")
            stage_b, _ = self._fake_stage(root, "clean-b")
            with (
                mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity),
                mock.patch.object(s1a_wheels, "verify_s1a_wheel", side_effect=self._fake_verify),
            ):
                manifest = s1a_wheels.publish(
                    stage_a,
                    stage_b,
                    root / "published",
                    self.recipe,
                    self.build,
                )
            document = json.loads(manifest.read_text(encoding="utf-8"))
            foreign = json.loads(json.dumps(self.identity))
            foreign["tools"]["bash"] += " foreign"
            foreign_build = s1a_wheels.build_key(self.recipe, foreign)
            document["builder_identity"] = foreign
            document["builder_identity_sha256"] = hashlib.sha256(s1a_wheels._canonical_json(foreign)).hexdigest()
            document["build_key"] = foreign_build
            foreign_parent = manifest.parent.with_name(f"s1a-wheels-{foreign_build}")
            manifest.parent.rename(foreign_parent)
            foreign_manifest = foreign_parent / "manifest.json"
            foreign_manifest.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(s1a_wheels.WheelError, "active builder"):
                s1a_wheels.verify_publication(foreign_manifest)

    def test_failed_publication_leaves_no_partial_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage_a, _ = self._fake_stage(root, "clean-a")
            stage_b, _ = self._fake_stage(root, "clean-b")
            with (
                mock.patch.object(s1a_wheels, "builder_identity", return_value=self.identity),
                mock.patch.object(
                    s1a_wheels,
                    "verify_s1a_wheel",
                    side_effect=s1a_wheels.WheelError("inspection failed"),
                ),
                self.assertRaisesRegex(s1a_wheels.WheelError, "inspection failed"),
            ):
                s1a_wheels.publish(
                    stage_a,
                    stage_b,
                    root / "published",
                    self.recipe,
                    self.build,
                )
            self.assertFalse((root / "published").exists())


if __name__ == "__main__":
    unittest.main()
