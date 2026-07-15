#!/usr/bin/env python3
"""Reproducible source staging and offline cross-build driver for tokenizer S1a."""

from __future__ import annotations

import argparse
from email.parser import BytesParser
from email.policy import compat32
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TOOL_ROOT = Path(__file__).resolve().parent
SOURCE_LOCK = TOOL_ROOT / "sources.lock"
HOST_LOCK = TOOL_ROOT / "host-wheels.lock"
ABIS = ("arm64-v8a", "x86_64")
PACKAGES = ("chaquopy-libcxx", "chaquopy-libmecab", "fugashi")
NDK_VERSION = "28.2.13676358"
PYTHON_TARGET = "3.13.9-0"
API_LEVEL = 26
WHEEL_SPECS = {
    "chaquopy_libcxx": ("190000", "py3", "none"),
    "chaquopy_libmecab": ("0.996", "py3", "none"),
    "fugashi": ("1.5.2", "cp313", "cp313"),
}
WHEEL_NAME = re.compile(
    r"^(?P<package>[a-zA-Z0-9_.]+)-(?P<version>[^-]+)-0-"
    r"(?P<python>[^-]+)-(?P<abi_tag>[^-]+)-android_26_"
    r"(?P<platform>arm64_v8a|x86_64)\.whl$",
)
PLATFORM_ABI = {"arm64_v8a": "arm64-v8a", "x86_64": "x86_64"}
ELF_MACHINE_ABI = {"EM_AARCH64": "arm64-v8a", "EM_X86_64": "x86_64"}
ANDROID_SYSTEM_LIBS = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
    "libz.so",
}


class WheelError(RuntimeError):
    pass


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def load_json(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != 1:
        raise WheelError(f"unsupported lock schema: {path}")
    return value


def source_entries() -> dict[str, dict[str, str]]:
    raw = load_json(SOURCE_LOCK).get("sources")
    if not isinstance(raw, dict) or not raw:
        raise WheelError("source lock has no sources")
    entries: dict[str, dict[str, str]] = {}
    for name, value in raw.items():
        if not isinstance(name, str) or not isinstance(value, dict):
            raise WheelError("invalid source lock entry")
        entry = {key: value.get(key) for key in ("filename", "sha256", "url")}
        if not all(isinstance(item, str) and item for item in entry.values()):
            raise WheelError(f"incomplete source lock entry: {name}")
        if len(entry["sha256"]) != 64 or not entry["url"].startswith("https://"):
            raise WheelError(f"unsafe source lock entry: {name}")
        entries[name] = entry  # type: ignore[assignment]
    return entries


def host_entries() -> list[tuple[str, str, str]]:
    raw = load_json(HOST_LOCK).get("requirements")
    if not isinstance(raw, list) or not raw:
        raise WheelError("host wheel lock has no requirements")
    result: list[tuple[str, str, str]] = []
    for value in raw:
        if (
            not isinstance(value, list)
            or len(value) != 3
            or not all(isinstance(item, str) and item for item in value)
        ):
            raise WheelError("invalid host wheel lock entry")
        requirement, filename, sha256 = value
        if "==" not in requirement or not filename.endswith(".whl") or len(sha256) != 64:
            raise WheelError(f"unsafe host wheel entry: {requirement}")
        result.append((requirement, filename, sha256))
    if len({filename for _, filename, _ in result}) != len(result):
        raise WheelError("duplicate host wheel filename")
    return result


def verify_file(path: Path, expected: str) -> None:
    if not path.is_file() or digest(path) != expected:
        raise WheelError(f"missing or hash-mismatched input: {path}")


def fetch_sources(downloads: Path) -> None:
    downloads.mkdir(parents=True, exist_ok=True)
    for entry in source_entries().values():
        target = downloads / entry["filename"]
        if target.is_file() and digest(target) == entry["sha256"]:
            continue
        temporary = target.with_suffix(target.suffix + ".partial")
        if temporary.exists():
            temporary.unlink()
        with urllib.request.urlopen(entry["url"], timeout=120) as response:
            with temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
        verify_file(temporary, entry["sha256"])
        os.replace(temporary, target)


def fetch_host_wheels(wheelhouse: Path) -> None:
    wheelhouse.mkdir(parents=True, exist_ok=True)
    requirements = [requirement for requirement, _, _ in host_entries()]
    with tempfile.TemporaryDirectory(dir=wheelhouse.parent) as temporary:
        subprocess.run(
            [
                sys.executable,
                "-m",
                "pip",
                "download",
                "--only-binary=:all:",
                "--no-deps",
                "--dest",
                temporary,
                *requirements,
            ],
            check=True,
        )
        staged = Path(temporary)
        for _, filename, sha256 in host_entries():
            verify_file(staged / filename, sha256)
            shutil.copy2(staged / filename, wheelhouse / filename)
    verify_host_wheels(wheelhouse)


def verify_host_wheels(wheelhouse: Path) -> None:
    expected = {filename: sha256 for _, filename, sha256 in host_entries()}
    actual = {path.name for path in wheelhouse.glob("*.whl")}
    if actual != set(expected):
        raise WheelError(f"host wheel set differs: expected={sorted(expected)}, actual={sorted(actual)}")
    for filename, sha256 in expected.items():
        verify_file(wheelhouse / filename, sha256)


def _safe_destination(root: Path, name: str) -> Path:
    path = Path(name)
    parts = tuple(part for part in path.parts if part not in {"", "."})
    if path.is_absolute() or ".." in parts:
        raise WheelError(f"unsafe archive entry: {name}")
    candidate = root.joinpath(*parts)
    if not parts:
        return root
    resolved_parent = candidate.parent.resolve()
    if resolved_parent != root.resolve() and root.resolve() not in resolved_parent.parents:
        raise WheelError(f"archive entry escapes staging: {name}")
    return candidate


def safe_extract(archive: Path, destination: Path) -> Path:
    destination.mkdir(parents=True, exist_ok=False)
    roots: set[str] = set()
    dot_root = False
    if tarfile.is_tarfile(archive):
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                target = _safe_destination(destination, member.name)
                parts = tuple(part for part in Path(member.name).parts if part not in {"", "."})
                dot_root = dot_root or member.name.startswith("./")
                if parts:
                    roots.add(parts[0])
                if member.issym() or member.islnk() or member.isdev():
                    raise WheelError(f"archive links/devices are forbidden: {member.name}")
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    stream = source.extractfile(member)
                    if stream is None:
                        raise WheelError(f"cannot read archive member: {member.name}")
                    with target.open("wb") as output:
                        shutil.copyfileobj(stream, output)
                    target.chmod(0o755 if member.mode & stat.S_IXUSR else 0o644)
                else:
                    raise WheelError(f"unsupported archive member: {member.name}")
    elif zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                target = _safe_destination(destination, info.filename)
                parts = tuple(part for part in Path(info.filename).parts if part not in {"", "."})
                dot_root = dot_root or info.filename.startswith("./")
                if parts:
                    roots.add(parts[0])
                mode = info.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise WheelError(f"archive links are forbidden: {info.filename}")
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(info) as stream, target.open("wb") as output:
                        shutil.copyfileobj(stream, output)
    else:
        raise WheelError(f"unsupported archive: {archive}")
    if dot_root:
        return destination
    if len(roots) != 1:
        raise WheelError(f"archive must have one root: {archive}")
    return destination / next(iter(roots))


def _replace(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise WheelError(f"staged source patch anchor mismatch: {path}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def patch_builder(chaquopy: Path, wheelhouse: Path) -> None:
    builder = chaquopy / "server/pypi/build-wheel.py"
    _replace(builder, "import pypi_simple\n", "")
    _replace(
        builder,
        'os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"',
        'for name in list(os.environ):\n'
        '            if name.startswith("PIP_") or name.casefold().endswith("_proxy"):\n'
        '                os.environ.pop(name, None)\n'
        '        os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"\n'
        '        os.environ["PIP_CONFIG_FILE"] = os.devnull\n',
    )
    _replace(
        builder,
        'run(f"{bootstrap_env}/bin/pip install pip=={pip_version}")',
        'run(f"{bootstrap_env}/bin/pip install --no-index "\n'
        '    f"--find-links={os.environ[\'ANKI_MINER_HOST_WHEELHOUSE\']} "\n'
        '    f"pip=={pip_version}")',
    )
    _replace(builder, 'pip_version = "23.2.1"', 'pip_version = "25.1.1"')
    _replace(
        builder,
        'f"install " + " ".join(shlex.quote(req) for req in requirements))',
        'f"install --no-index --only-binary=:all: "\n'
        '                f"--find-links={os.environ[\'ANKI_MINER_HOST_WHEELHOUSE\']} " +\n'
        '                " ".join(shlex.quote(req) for req in requirements))',
    )
    start = builder.read_text(encoding="utf-8")
    first = start.index("    def download_git(")
    last = start.index("    def create_host_env(", first)
    closed = (
        "    def download_git(self, source):\n"
        "        raise CommandError(\"network source discovery is disabled\")\n\n"
        "    def download_pypi(self):\n"
        "        raise CommandError(\"network source discovery is disabled\")\n\n"
        "    def download_url(self, url):\n"
        "        raise CommandError(\"network source discovery is disabled\")\n\n"
    )
    builder.write_text(start[:first] + closed + start[last:], encoding="utf-8")

    android_env = chaquopy / "target/android-env.sh"
    _replace(android_env, "ndk_version=27.3.13750724", f"ndk_version={NDK_VERSION}")
    _replace(
        android_env,
        'if ! [ -e $ndk ]; then\n    log "Installing NDK - this may take several minutes"\n    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$ndk_version"\nfi',
        'if ! [ -e "$ndk" ]; then\n    fail "locked NDK is missing: $ndk"\nfi',
    )
    libcxx = chaquopy / "server/pypi/packages/chaquopy-libcxx/meta.yaml"
    _replace(libcxx, 'version: "180000"', 'version: "190000"')


def apply_patch(source: Path, patch: Path, strip: int) -> None:
    if strip != 1:
        raise WheelError(f"unsupported patch strip level: {strip}")
    subprocess.run(["git", "apply", "--check", str(patch)], cwd=source, check=True)
    subprocess.run(["git", "apply", str(patch)], cwd=source, check=True)


def recipe_key() -> str:
    value = hashlib.sha256()
    for path in sorted(TOOL_ROOT.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts:
            value.update(path.relative_to(TOOL_ROOT).as_posix().encode())
            value.update(path.read_bytes())
    value.update(NDK_VERSION.encode())
    value.update(PYTHON_TARGET.encode())
    return value.hexdigest()


def _wheel_identity(path: Path) -> tuple[str, str]:
    match = WHEEL_NAME.fullmatch(path.name)
    if match is None:
        raise WheelError(f"unexpected S1a wheel filename: {path.name}")
    package = match["package"].casefold().replace("-", "_")
    spec = WHEEL_SPECS.get(package)
    if spec is None:
        raise WheelError(f"unexpected S1a package: {package}")
    version, python_tag, abi_tag = spec
    if (match["version"], match["python"], match["abi_tag"]) != (
        version,
        python_tag,
        abi_tag,
    ):
        raise WheelError(f"wrong version or tag for {path.name}")
    return package, PLATFORM_ABI[match["platform"]]


def _wheel_message(archive: zipfile.ZipFile, package: str, filename: str) -> object:
    names = [name for name in archive.namelist() if name.endswith(f".dist-info/{filename}")]
    if len(names) != 1:
        raise WheelError(f"{package}: expected one {filename} file")
    return BytesParser(policy=compat32).parsebytes(archive.read(names[0]))


def _inspect_elf(data: bytes, logical_name: str, abi: str) -> dict[str, object]:
    for marker in (b"/home/", b"/tmp/", b"/Users/", b"C:\\"):
        if marker in data:
            raise WheelError(f"{logical_name}: absolute build path leaked into ELF")
    try:
        from elftools.elf.elffile import ELFFile
    except ImportError as error:
        raise WheelError("pyelftools is required to publish S1a wheels") from error
    try:
        elf = ELFFile(BytesIO(data))
        header = elf.header
        actual_abi = ELF_MACHINE_ABI.get(header["e_machine"])
        if actual_abi != abi:
            raise WheelError(f"{logical_name}: ELF ABI is {actual_abi}, expected {abi}")
        if header["e_type"] != "ET_DYN":
            raise WheelError(f"{logical_name}: native payload is not ET_DYN")
        loads = [segment for segment in elf.iter_segments() if segment["p_type"] == "PT_LOAD"]
        if not loads:
            raise WheelError(f"{logical_name}: ELF has no LOAD segments")
        for index, segment in enumerate(loads):
            alignment = int(segment["p_align"])
            offset = int(segment["p_offset"])
            address = int(segment["p_vaddr"])
            if alignment < 16 * 1024 or (address - offset) % (16 * 1024):
                raise WheelError(f"{logical_name}: LOAD[{index}] is not 16 KiB compatible")
        dynamic = elf.get_section_by_name(".dynamic")
        if dynamic is None:
            raise WheelError(f"{logical_name}: ELF has no dynamic section")
        needed: list[str] = []
        soname = None
        for tag in dynamic.iter_tags():
            kind = tag.entry.d_tag
            if kind == "DT_NEEDED":
                needed.append(tag.needed)
            elif kind == "DT_SONAME":
                soname = tag.soname
            elif kind in {"DT_RPATH", "DT_RUNPATH", "DT_TEXTREL"}:
                raise WheelError(f"{logical_name}: forbidden {kind}")
    except WheelError:
        raise
    except Exception as error:
        raise WheelError(f"{logical_name}: invalid ELF: {error}") from error
    return {
        "path": logical_name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "abi": abi,
        "soname": soname,
        "needed": sorted(needed),
    }


def verify_s1a_wheel(path: Path) -> tuple[str, str, dict[str, object]]:
    package, abi = _wheel_identity(path)
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as error:
        raise WheelError(f"invalid wheel: {path}") from error
    with archive:
        names = [info.filename for info in archive.infolist() if not info.is_dir()]
        folded = "\n".join(names).casefold()
        for forbidden in ("sys.dic", "matrix.bin", "unidic_lite", "unidic-lite", "dicdir"):
            if forbidden in folded:
                raise WheelError(f"{path.name}: bundled dictionary payload {forbidden}")
        if len(names) != len(set(names)):
            raise WheelError(f"{path.name}: duplicate archive entries")

        message = _wheel_message(archive, package, "METADATA")
        expected_version = WHEEL_SPECS[package][0]
        metadata_name = str(message.get("Name", "")).casefold().replace("-", "_")
        if metadata_name != package or message.get("Version") != expected_version:
            raise WheelError(f"{path.name}: METADATA identity mismatch")
        requirements = [str(value) for value in (message.get_all("Requires-Dist") or [])]
        normalized_requirements = {
            re.split(r"[ ;(<>=]", value.casefold().replace("_", "-"), maxsplit=1)[0]
            for value in requirements
        }
        expected_requirements = {
            "chaquopy_libcxx": set(),
            "chaquopy_libmecab": {"chaquopy-libcxx"},
            "fugashi": {"chaquopy-libcxx", "chaquopy-libmecab"},
        }[package]
        if normalized_requirements != expected_requirements:
            raise WheelError(
                f"{path.name}: dependency set is {sorted(normalized_requirements)}, "
                f"expected {sorted(expected_requirements)}",
            )
        wheel_message = _wheel_message(archive, package, "WHEEL")
        python_tag, abi_tag = WHEEL_SPECS[package][1:]
        platform_tag = abi.replace("-", "_")
        expected_tag = f"{python_tag}-{abi_tag}-android_{API_LEVEL}_{platform_tag}"
        if wheel_message.get_all("Tag") != [expected_tag]:
            raise WheelError(f"{path.name}: WHEEL tag mismatch")

        license_names = [
            name
            for name in names
            if ".dist-info/" in name
            and Path(name).name.upper().startswith(("LICENSE", "COPYING", "COPYRIGHT"))
        ]
        if not license_names:
            raise WheelError(f"{path.name}: license attribution is missing")
        license_entries = [
            {"path": name, "sha256": hashlib.sha256(archive.read(name)).hexdigest()}
            for name in sorted(license_names)
        ]
        license_text = b"\n".join(archive.read(name) for name in license_names).casefold()
        markers = {
            "chaquopy_libcxx": (b"apache license", b"llvm exceptions"),
            "chaquopy_libmecab": (b"taku kudo", b"redistribution"),
            "fugashi": (b"permission is hereby granted",),
        }[package]
        if not all(marker in license_text for marker in markers):
            raise WheelError(f"{path.name}: expected license text is missing")

        elf_names = [name for name in names if ".so" in Path(name).name]
        if len(elf_names) != 1:
            raise WheelError(f"{path.name}: expected one native payload, found {len(elf_names)}")
        elf_entry = _inspect_elf(archive.read(elf_names[0]), elf_names[0], abi)
        native_name = Path(elf_names[0]).name
        expected_native = {
            "chaquopy_libcxx": "libc++_shared.so",
            "chaquopy_libmecab": "libmecab.so.2",
        }.get(package)
        if expected_native is not None and native_name != expected_native:
            raise WheelError(f"{path.name}: expected {expected_native}, found {native_name}")
        if package == "fugashi" and not (
            elf_names[0].startswith("fugashi/") and native_name.startswith("fugashi.")
        ):
            raise WheelError(f"{path.name}: Fugashi extension path is unexpected")
        expected_soname = expected_native if package != "fugashi" else None
        if elf_entry["soname"] != expected_soname:
            raise WheelError(f"{path.name}: SONAME is {elf_entry['soname']!r}")
        needed = set(elf_entry["needed"])
        required_needed = {
            "chaquopy_libcxx": set(),
            "chaquopy_libmecab": {"libc++_shared.so"},
            "fugashi": {"libmecab.so.2", "libc++_shared.so", "libpython3.13.so"},
        }[package]
        allowed_needed = required_needed | ANDROID_SYSTEM_LIBS
        if not required_needed.issubset(needed) or not needed.issubset(allowed_needed):
            raise WheelError(
                f"{path.name}: native dependencies are {sorted(needed)}, "
                f"required {sorted(required_needed)}",
            )
        return package, abi, {
            "filename": path.name,
            "sha256": digest(path),
            "size": path.stat().st_size,
            "licenses": license_entries,
            "elf": elf_entry,
        }


def publish(dist: Path, output_root: Path) -> Path:
    wheels = sorted(dist.rglob("*.whl"))
    if len(wheels) != len(ABIS) * len(PACKAGES):
        raise WheelError(f"expected six S1a wheels, found {len(wheels)} under {dist}")
    entries: dict[str, list[dict[str, object]]] = {abi: [] for abi in ABIS}
    identities: set[tuple[str, str]] = set()
    verified: list[tuple[Path, str, dict[str, object]]] = []
    for wheel in wheels:
        package, abi, entry = verify_s1a_wheel(wheel)
        if (package, abi) in identities:
            raise WheelError(f"duplicate S1a wheel for {package}/{abi}")
        identities.add((package, abi))
        verified.append((wheel, abi, entry))
    expected_identities = {
        (package.replace("-", "_"), abi) for package in PACKAGES for abi in ABIS
    }
    if identities != expected_identities:
        raise WheelError(f"incomplete S1a wheel identities: {sorted(identities)}")

    key = recipe_key()
    output_root.mkdir(parents=True, exist_ok=True)
    target = output_root / f"s1a-wheels-{key}"
    staging = output_root / f".{target.name}.staging"
    if target.exists() or staging.exists():
        raise WheelError(f"immutable S1a publication already exists: {target}")
    try:
        staging.mkdir()
        for wheel, abi, entry in verified:
            shutil.copy2(wheel, staging / wheel.name)
            entries[abi].append(entry)
        for abi in ABIS:
            entries[abi].sort(key=lambda entry: str(entry["filename"]))
        manifest = {
            "schema": 1,
            "recipe_key": key,
            "api_level": API_LEVEL,
            "ndk": NDK_VERSION,
            "python_target": PYTHON_TARGET,
            "tool_versions": {"python": platform.python_version()},
            "source_hashes": {
                name: entry["sha256"] for name, entry in source_entries().items()
            },
            "sources": source_entries(),
            "patch_hashes": {
                path.relative_to(TOOL_ROOT).as_posix(): digest(path)
                for path in sorted((TOOL_ROOT / "patches").glob("*.patch"))
            },
            "wheels": entries,
        }
        (staging / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(staging, target)
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        raise
    return target / "manifest.json"


def stage(downloads: Path, wheelhouse: Path, build_root: Path) -> Path:
    verify_host_wheels(wheelhouse)
    entries = source_entries()
    for entry in entries.values():
        verify_file(downloads / entry["filename"], entry["sha256"])
    target = build_root / f"s1a-{recipe_key()}"
    if target.exists():
        raise WheelError(f"immutable staging directory already exists: {target}")
    temporary = build_root / f".{target.name}.staging"
    if temporary.exists():
        shutil.rmtree(temporary)
    temporary.mkdir(parents=True)
    roots: dict[str, Path] = {}
    for name in ("chaquopy", "mecab", "fugashi", "patchelf"):
        roots[name] = safe_extract(downloads / entries[name]["filename"], temporary / name)
    chaquopy = roots["chaquopy"]
    source_dir = chaquopy / "server/pypi/sources"
    source_dir.mkdir()
    shutil.copytree(roots["mecab"] / "mecab", source_dir / "mecab")
    shutil.copytree(roots["fugashi"], source_dir / "fugashi")
    for patch in sorted((TOOL_ROOT / "patches").glob("mecab-*.patch")):
        apply_patch(source_dir / "mecab", patch, 1)
    apply_patch(source_dir / "fugashi", TOOL_ROOT / "patches/fugashi-android-link.patch", 1)
    for recipe in (TOOL_ROOT / "recipes").iterdir():
        shutil.copytree(recipe, chaquopy / "server/pypi/packages" / recipe.name)
    target_dir = chaquopy / f"maven/com/chaquo/python/target/{PYTHON_TARGET}"
    target_dir.mkdir(parents=True, exist_ok=True)
    for abi in ABIS:
        shutil.copy2(downloads / entries[f"python-{abi}"]["filename"], target_dir)
    patch_builder(chaquopy, wheelhouse)
    manifest = {
        "schema": 1,
        "recipe_key": recipe_key(),
        "ndk": NDK_VERSION,
        "python_target": PYTHON_TARGET,
        "source_hashes": {name: entry["sha256"] for name, entry in entries.items()},
        "host_wheels": {filename: sha256 for _, filename, sha256 in host_entries()},
    }
    (temporary / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, target)
    return target


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("key")
    verify = sub.add_parser("verify")
    verify.add_argument("--downloads", type=Path)
    verify.add_argument("--wheelhouse", type=Path)
    fetch = sub.add_parser("fetch")
    fetch.add_argument("--downloads", type=Path, required=True)
    fetch.add_argument("--wheelhouse", type=Path, required=True)
    staging = sub.add_parser("stage")
    staging.add_argument("--downloads", type=Path, required=True)
    staging.add_argument("--wheelhouse", type=Path, required=True)
    staging.add_argument("--build-root", type=Path, required=True)
    publishing = sub.add_parser("publish")
    publishing.add_argument("--dist", type=Path, required=True)
    publishing.add_argument("--output-root", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    try:
        args = parse_args()
        if args.command == "key":
            print(recipe_key())
        elif args.command == "verify":
            source_entries()
            host_entries()
            if args.downloads:
                for entry in source_entries().values():
                    verify_file(args.downloads / entry["filename"], entry["sha256"])
            if args.wheelhouse:
                verify_host_wheels(args.wheelhouse)
        elif args.command == "fetch":
            fetch_sources(args.downloads)
            fetch_host_wheels(args.wheelhouse)
        elif args.command == "stage":
            print(stage(args.downloads, args.wheelhouse, args.build_root))
        elif args.command == "publish":
            print(publish(args.dist, args.output_root))
    except (WheelError, OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"S1a wheel tooling failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
