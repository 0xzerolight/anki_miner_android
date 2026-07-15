#!/usr/bin/env python3
"""Reproducible source staging and offline cross-build driver for tokenizer S1a."""

from __future__ import annotations

import argparse
from email.parser import BytesParser
from email.policy import compat32
import hashlib
import importlib.util
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
import zlib

ROOT = Path(__file__).resolve().parents[2]
TOOL_ROOT = Path(__file__).resolve().parent
SOURCE_LOCK = TOOL_ROOT / "sources.lock"
HOST_LOCK = TOOL_ROOT / "host-wheels.lock"
ABIS = ("arm64-v8a", "x86_64")
PACKAGES = ("chaquopy-libcxx", "chaquopy-libmecab", "fugashi")
NDK_VERSION = "28.2.13676358"
PYTHON_TARGET = "3.13.9-0"
API_LEVEL = 26
MANIFEST_SCHEMA = 2
SOURCE_DATE_EPOCH = "1704067200"
REPRODUCIBLE_ENV = {
    "SOURCE_DATE_EPOCH": SOURCE_DATE_EPOCH,
    "PYTHONHASHSEED": "0",
    "TZ": "UTC",
    "LC_ALL": "C",
    "LANG": "C",
}
RECIPE_FILES = (
    "build-s1a-wheels.sh",
    "host-wheels.lock",
    "s1a_wheels.py",
    "sources.lock",
)
RECIPE_TREES = ("patches", "recipes")
RECIPE_REPO_FILES = (
    "scripts/android-env.sh",
    "scripts/android-licenses.sh",
    "scripts/check_native_artifacts.py",
)
KEY_PATTERN = re.compile(r"[0-9a-f]{64}")
STAGE_ID_PATTERN = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?")
WHEEL_SPECS = {
    "chaquopy_libcxx": ("190000", "py3", "none"),
    "chaquopy_libmecab": ("0.996", "py3", "none"),
    "fugashi": ("1.5.2", "cp313", "cp313"),
}
S1A_NATIVE_PATHS = {
    "chaquopy_libcxx": "chaquopy/lib/libc++_shared.so",
    "chaquopy_libmecab": "chaquopy/lib/libmecab.so.2",
    "fugashi": "fugashi/fugashi.so",
}
WHEEL_NAME = re.compile(
    r"^(?P<package>[a-zA-Z0-9_.]+)-(?P<version>[^-]+)-0-"
    r"(?P<python>[^-]+)-(?P<abi_tag>[^-]+)-android_26_"
    r"(?P<platform>arm64_v8a|x86_64)\.whl$",
)
PLATFORM_ABI = {"arm64_v8a": "arm64-v8a", "x86_64": "x86_64"}
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


def load_json(path: Path, expected_schema: int = 1) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != expected_schema:
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


def _canonical_json(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _recipe_path_entries(
    tool_root: Path = TOOL_ROOT,
    repo_root: Path = ROOT,
) -> list[tuple[str, Path]]:
    entries = [(f"tools/wheels/{name}", tool_root / name) for name in RECIPE_FILES]
    for tree_name in RECIPE_TREES:
        tree = tool_root / tree_name
        if not tree.is_dir():
            raise WheelError(f"recipe input tree is missing: {tree}")
        entries.extend(
            (
                f"tools/wheels/{path.relative_to(tool_root).as_posix()}",
                path,
            )
            for path in tree.rglob("*")
            if path.is_file()
        )
    entries.extend((name, repo_root / name) for name in RECIPE_REPO_FILES)
    logical_paths = [logical for logical, _ in entries]
    if len(logical_paths) != len(set(logical_paths)):
        raise WheelError("recipe input inventory contains duplicate paths")
    for _, path in entries:
        if path.is_symlink() or not path.is_file():
            raise WheelError(f"recipe input must be a regular file: {path}")
    return sorted(entries)


def recipe_inventory(
    tool_root: Path = TOOL_ROOT,
    repo_root: Path = ROOT,
) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for logical_path, path in _recipe_path_entries(tool_root, repo_root):
        data = path.read_bytes()
        result.append(
            {
                "path": logical_path,
                "mode": stat.S_IMODE(path.stat().st_mode),
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            },
        )
    parameters = _canonical_json(
        {
            "abis": ABIS,
            "api_level": API_LEVEL,
            "ndk": NDK_VERSION,
            "packages": PACKAGES,
            "python_target": PYTHON_TARGET,
            "reproducible_env": REPRODUCIBLE_ENV,
            "wheel_specs": WHEEL_SPECS,
        },
    )
    result.append(
        {
            "path": "@parameters.json",
            "mode": 0o644,
            "size": len(parameters),
            "sha256": hashlib.sha256(parameters).hexdigest(),
        },
    )
    return result


def source_recipe_key(
    tool_root: Path = TOOL_ROOT,
    repo_root: Path = ROOT,
) -> str:
    value = hashlib.sha256()
    for entry, (_, path) in zip(
        recipe_inventory(tool_root, repo_root)[:-1],
        _recipe_path_entries(tool_root, repo_root),
    ):
        data = path.read_bytes()
        header = _canonical_json(
            {
                "path": entry["path"],
                "mode": entry["mode"],
                "size": entry["size"],
            },
        )
        value.update(len(header).to_bytes(8, "big"))
        value.update(header)
        value.update(len(data).to_bytes(8, "big"))
        value.update(data)
    parameters = _canonical_json(
        {
            "abis": ABIS,
            "api_level": API_LEVEL,
            "ndk": NDK_VERSION,
            "packages": PACKAGES,
            "python_target": PYTHON_TARGET,
            "reproducible_env": REPRODUCIBLE_ENV,
            "wheel_specs": WHEEL_SPECS,
        },
    )
    header = _canonical_json(
        {"path": "@parameters.json", "mode": 0o644, "size": len(parameters)},
    )
    value.update(len(header).to_bytes(8, "big"))
    value.update(header)
    value.update(len(parameters).to_bytes(8, "big"))
    value.update(parameters)
    return value.hexdigest()


def _normalized_machine(value: str) -> str:
    machine = value.strip().casefold().replace("-", "_")
    aliases = {"amd64": "x86_64", "x64": "x86_64"}
    return aliases.get(machine, machine)


def _tool_version(name: str, command: list[str], marker: str) -> str:
    executable = shutil.which(command[0])
    if executable is None:
        raise WheelError(f"required builder tool is missing: {command[0]}")
    result = subprocess.run(
        [executable, *command[1:]],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env={**os.environ, "LC_ALL": "C", "LANG": "C", "TZ": "UTC"},
    )
    lines = [" ".join(line.split()) for line in result.stdout.splitlines() if line.strip()]
    if not lines or marker not in lines[0].casefold():
        raise WheelError(f"cannot identify {name} version from {command[0]}")
    return lines[0]


def _validate_builder_identity(identity: object) -> dict[str, object]:
    if not isinstance(identity, dict) or identity.get("schema") != 1:
        raise WheelError("invalid builder identity schema")
    python = identity.get("python")
    host = identity.get("host")
    tools = identity.get("tools")
    if not isinstance(python, dict) or not isinstance(host, dict) or not isinstance(tools, dict):
        raise WheelError("incomplete builder identity")
    if python.get("implementation") != "cpython":
        raise WheelError("S1a wheels require CPython")
    version = python.get("version")
    executable_sha256 = python.get("executable_sha256")
    if not isinstance(version, str) or not version.startswith("3.13."):
        raise WheelError("S1a wheel builder requires CPython 3.13")
    if not isinstance(executable_sha256, str) or KEY_PATTERN.fullmatch(executable_sha256) is None:
        raise WheelError("invalid builder interpreter hash")
    if host.get("os") != "linux" or host.get("machine") != "x86_64":
        raise WheelError("S1a wheel builder requires Linux x86_64")
    libc = host.get("libc")
    zlib_identity = host.get("zlib")
    if (
        not isinstance(libc, dict)
        or libc.get("name") != "glibc"
        or not isinstance(libc.get("version"), str)
        or not libc.get("version")
    ):
        raise WheelError("S1a wheel builder requires an identified glibc")
    if (
        not isinstance(zlib_identity, dict)
        or not isinstance(zlib_identity.get("compiled"), str)
        or not isinstance(zlib_identity.get("runtime"), str)
        or zlib_identity.get("compiled") != zlib_identity.get("runtime")
    ):
        raise WheelError("S1a wheel builder requires matching identified zlib versions")
    expected_tools = {
        "bash",
        "coreutils",
        "findutils",
        "git",
        "grep",
        "make",
        "patch",
        "sed",
        "unzip",
    }
    if set(tools) != expected_tools or not all(
        isinstance(value, str) and value for value in tools.values()
    ):
        raise WheelError("incomplete builder tool-version identity")
    return identity


def builder_identity() -> dict[str, object]:
    executable = Path(sys.executable).resolve(strict=True)
    libc_name, libc_version = platform.libc_ver()
    identity: dict[str, object] = {
        "schema": 1,
        "python": {
            "implementation": platform.python_implementation().casefold(),
            "version": platform.python_version(),
            "executable_sha256": digest(executable),
        },
        "host": {
            "os": platform.system().strip().casefold(),
            "machine": _normalized_machine(platform.machine()),
            "libc": {
                "name": libc_name.strip().casefold(),
                "version": libc_version.strip(),
            },
            "zlib": {
                "compiled": zlib.ZLIB_VERSION,
                "runtime": zlib.ZLIB_RUNTIME_VERSION,
            },
        },
        "tools": {
            "bash": _tool_version("bash", ["bash", "--version"], "bash"),
            "coreutils": _tool_version("coreutils", ["cp", "--version"], "coreutils"),
            "findutils": _tool_version("findutils", ["find", "--version"], "find"),
            "git": _tool_version("git", ["git", "--version"], "git version"),
            "grep": _tool_version("grep", ["grep", "--version"], "grep"),
            "make": _tool_version("make", ["make", "--version"], "make"),
            "patch": _tool_version("patch", ["patch", "--version"], "patch"),
            "sed": _tool_version("sed", ["sed", "--version"], "sed"),
            "unzip": _tool_version("unzip", ["unzip", "-v"], "unzip"),
        },
    }
    return _validate_builder_identity(identity)


def build_key(recipe: str | None = None, identity: object | None = None) -> str:
    recipe = recipe or source_recipe_key()
    if KEY_PATTERN.fullmatch(recipe) is None:
        raise WheelError("invalid source recipe key")
    checked_identity = _validate_builder_identity(identity or builder_identity())
    value = hashlib.sha256()
    value.update(recipe.encode("ascii"))
    value.update(b"\n")
    value.update(_canonical_json(checked_identity))
    return value.hexdigest()


def enforce_reproducible_environment() -> None:
    mismatches = {
        name: (os.environ.get(name), expected)
        for name, expected in REPRODUCIBLE_ENV.items()
        if os.environ.get(name) != expected
    }
    current_umask = os.umask(0o022)
    os.umask(current_umask)
    if current_umask != 0o022:
        mismatches["umask"] = (oct(current_umask), oct(0o022))
    if mismatches:
        details = ", ".join(
            f"{name}={actual!r} (expected {expected!r})"
            for name, (actual, expected) in sorted(mismatches.items())
        )
        raise WheelError(f"non-reproducible builder environment: {details}")


def _validate_expected_keys(expected_recipe: str, expected_build: str) -> tuple[str, str, dict[str, object]]:
    if KEY_PATTERN.fullmatch(expected_recipe) is None or KEY_PATTERN.fullmatch(expected_build) is None:
        raise WheelError("expected recipe/build keys must be lowercase SHA-256 values")
    actual_recipe = source_recipe_key()
    identity = builder_identity()
    actual_build = build_key(actual_recipe, identity)
    if expected_recipe != actual_recipe:
        raise WheelError(
            f"stale source recipe key: expected {expected_recipe}, current {actual_recipe}",
        )
    if expected_build != actual_build:
        raise WheelError(
            f"stale build key: expected {expected_build}, current {actual_build}",
        )
    return actual_recipe, actual_build, identity


def validate_recipes(chaquopy_root: Path) -> list[str]:
    try:
        from copy import deepcopy
        from jinja2 import StrictUndefined, Template, TemplateError
        import jsonschema
        import yaml
    except ImportError as error:
        raise WheelError(
            "Jinja2, jsonschema and PyYAML are required for staged recipe validation",
        ) from error

    chaquopy_root = chaquopy_root.resolve(strict=True)
    pypi_root = chaquopy_root / "server/pypi"
    schema_path = pypi_root / "meta-schema.yaml"
    if not schema_path.is_file():
        raise WheelError(f"staged Chaquopy schema is missing: {schema_path}")

    def with_defaults(validator_cls):
        def set_defaults(validator, properties, instance, schema):
            for name, subschema in properties.items():
                if "default" in subschema:
                    instance.setdefault(name, deepcopy(subschema["default"]))
            yield from validator_cls.VALIDATORS["properties"](
                validator,
                properties,
                instance,
                schema,
            )

        return jsonschema.validators.extend(validator_cls, {"properties": set_defaults})

    try:
        schema = yaml.safe_load(schema_path.read_text(encoding="utf-8"))
        validator_cls = jsonschema.Draft4Validator
        validator_cls.check_schema(schema)
    except (jsonschema.SchemaError, yaml.YAMLError, OSError) as error:
        raise WheelError(f"invalid staged Chaquopy recipe schema: {schema_path}") from error

    validated: list[str] = []
    for recipe_name in sorted(
        path.name for path in (TOOL_ROOT / "recipes").iterdir() if path.is_dir()
    ):
        meta_path = pypi_root / "packages" / recipe_name / "meta.yaml"
        if not meta_path.is_file():
            raise WheelError(f"staged custom recipe is missing: {meta_path}")
        try:
            rendered = Template(
                meta_path.read_text(encoding="utf-8"),
                undefined=StrictUndefined,
            ).render(PY_VER="3.13")
            meta = yaml.safe_load(rendered)
            with_defaults(validator_cls)(schema).validate(meta)
        except (
            TemplateError,
            jsonschema.SchemaError,
            jsonschema.ValidationError,
            yaml.YAMLError,
            OSError,
        ) as error:
            raise WheelError(f"invalid staged custom recipe: {meta_path}") from error
        validated.append(recipe_name)
    return validated


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
    dist_info = f"{package}-{WHEEL_SPECS[package][0]}.dist-info"
    expected = f"{dist_info}/{filename}"
    names = [name for name in archive.namelist() if name == expected]
    if len(names) != 1:
        raise WheelError(f"{package}: expected one {filename} file")
    return BytesParser(policy=compat32).parsebytes(archive.read(names[0]))


def _native_artifact_checker():
    module_name = "_anki_miner_native_artifact_checker"
    module = sys.modules.get(module_name)
    if module is not None:
        return module
    checker_path = ROOT / "scripts/check_native_artifacts.py"
    spec = importlib.util.spec_from_file_location(module_name, checker_path)
    if spec is None or spec.loader is None:
        raise WheelError(f"cannot load native artifact checker: {checker_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(module_name, None)
        raise
    return module


def _inspect_elf(data: bytes, logical_name: str, abi: str) -> dict[str, object]:
    for marker in (b"/home/", b"/tmp/", b"/Users/", b"C:\\"):
        if marker in data:
            raise WheelError(f"{logical_name}: absolute build path leaked into ELF")
    checker = _native_artifact_checker()
    try:
        inspection = checker.Inspection({abi}, ())
        metadata = checker.parse_elf(
            data,
            logical_name,
            inspection,
            require_et_dyn=True,
        )
    except checker.ArtifactError as error:
        raise WheelError(str(error)) from error
    except Exception as error:
        raise WheelError(f"{logical_name}: invalid ELF: {error}") from error
    return {
        "path": logical_name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "abi": abi,
        "soname": metadata.soname,
        "needed": list(metadata.needed),
    }


def _validated_wheel_members(
    archive: zipfile.ZipFile,
    wheel_name: str,
) -> list[zipfile.ZipInfo]:
    members: list[zipfile.ZipInfo] = []
    seen: dict[str, bool] = {}
    for info in archive.infolist():
        name = info.filename
        if not name or "\x00" in name or "\\" in name:
            raise WheelError(f"{wheel_name}: unsafe wheel entry {name!r}")
        normalized = name[:-1] if name.endswith("/") else name
        components = normalized.split("/")
        if (
            not normalized
            or normalized.startswith("/")
            or any(component in {"", ".", ".."} for component in components)
        ):
            raise WheelError(f"{wheel_name}: unsafe wheel entry {name!r}")
        is_directory = info.is_dir()
        if normalized in seen:
            kind = "directory/file ambiguity" if seen[normalized] != is_directory else "duplicate"
            raise WheelError(f"{wheel_name}: {kind} wheel entry {normalized!r}")
        ancestors = [
            "/".join(components[:index])
            for index in range(1, len(components))
        ]
        if any(seen.get(ancestor) is False for ancestor in ancestors):
            raise WheelError(
                f"{wheel_name}: file/descendant ambiguity at {normalized!r}"
            )
        if not is_directory and any(
            existing.startswith(f"{normalized}/") for existing in seen
        ):
            raise WheelError(
                f"{wheel_name}: file/descendant ambiguity at {normalized!r}"
            )
        seen[normalized] = is_directory
        members.append(info)
    return members


def verify_s1a_wheel(path: Path) -> tuple[str, str, dict[str, object]]:
    package, abi = _wheel_identity(path)
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as error:
        raise WheelError(f"invalid wheel: {path}") from error
    with archive:
        members = _validated_wheel_members(archive, path.name)
        names = [info.filename for info in members if not info.is_dir()]
        folded = "\n".join(names).casefold()
        for forbidden in ("sys.dic", "matrix.bin", "unidic_lite", "unidic-lite", "dicdir"):
            if forbidden in folded:
                raise WheelError(f"{path.name}: bundled dictionary payload {forbidden}")
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
            if f"{package}-{expected_version}.dist-info" in Path(name).parts
            and (
                Path(name).name.upper().startswith(
                    ("LICENSE", "COPYING", "COPYRIGHT", "NOTICE")
                )
                or (package == "chaquopy_libmecab" and Path(name).name == "BSD")
            )
        ]
        if not license_names:
            raise WheelError(f"{path.name}: license attribution is missing")
        license_entries = [
            {"path": name, "sha256": hashlib.sha256(archive.read(name)).hexdigest()}
            for name in sorted(license_names)
        ]
        license_text = b"\n".join(archive.read(name) for name in license_names).lower()
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
        expected_native_path = S1A_NATIVE_PATHS[package]
        if elf_names[0] != expected_native_path:
            raise WheelError(
                f"{path.name}: native payload path is {elf_names[0]!r}, "
                f"expected {expected_native_path!r}"
            )
        elf_entry = _inspect_elf(archive.read(elf_names[0]), elf_names[0], abi)
        native_name = Path(elf_names[0]).name
        expected_native = {
            "chaquopy_libcxx": "libc++_shared.so",
            "chaquopy_libmecab": "libmecab.so.2",
        }.get(package)
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


def _stage_manifest_path(stage_root: Path) -> Path:
    return stage_root / "manifest.json"


def validate_stage(
    stage_root: Path,
    expected_recipe: str,
    expected_build: str,
    *,
    require_dist: bool,
) -> tuple[dict[str, object], Path]:
    enforce_reproducible_environment()
    actual_recipe, actual_build, identity = _validate_expected_keys(
        expected_recipe,
        expected_build,
    )
    stage_root = stage_root.resolve(strict=True)
    document = load_json(_stage_manifest_path(stage_root), MANIFEST_SCHEMA)
    stage_id = document.get("stage_id")
    if not isinstance(stage_id, str) or STAGE_ID_PATTERN.fullmatch(stage_id) is None:
        raise WheelError("invalid S1a stage identifier")
    if stage_root.name != f"s1a-{actual_build}-{stage_id}":
        raise WheelError("S1a stage directory does not match its build key and stage identifier")
    if document.get("recipe_key") != actual_recipe or document.get("build_key") != actual_build:
        raise WheelError("stale S1a stage recipe/build keys")
    if document.get("builder_identity") != identity:
        raise WheelError("S1a stage builder identity differs from the active builder")
    if document.get("ndk") != NDK_VERSION or document.get("python_target") != PYTHON_TARGET:
        raise WheelError("S1a stage NDK/Python target differs from current inputs")
    if document.get("recipe_inventory") != recipe_inventory():
        raise WheelError("S1a stage recipe inventory differs from current inputs")
    if document.get("source_hashes") != {
        name: entry["sha256"] for name, entry in source_entries().items()
    }:
        raise WheelError("S1a stage source lock differs from current inputs")
    if document.get("host_wheels") != {
        filename: sha256 for _, filename, sha256 in host_entries()
    }:
        raise WheelError("S1a stage host-wheel lock differs from current inputs")
    chaquopy_root = stage_root / "chaquopy"
    roots = sorted(path for path in chaquopy_root.iterdir() if path.is_dir())
    if len(roots) != 1:
        raise WheelError(f"expected one staged Chaquopy source root under {chaquopy_root}")
    dist = roots[0] / "server/pypi/dist"
    if require_dist and not dist.is_dir():
        raise WheelError(f"S1a stage has no built wheel directory: {dist}")
    return document, dist


def _wheel_set(dist: Path) -> dict[str, Path]:
    wheels = sorted(dist.rglob("*.whl"))
    if len(wheels) != len(ABIS) * len(PACKAGES):
        raise WheelError(f"expected six S1a wheels, found {len(wheels)} under {dist}")
    result: dict[str, Path] = {}
    identities: set[tuple[str, str]] = set()
    for wheel in wheels:
        package, abi = _wheel_identity(wheel)
        if (package, abi) in identities or wheel.name in result:
            raise WheelError(f"duplicate S1a wheel identity: {package}/{abi}")
        identities.add((package, abi))
        result[wheel.name] = wheel
    expected_identities = {
        (package.replace("-", "_"), abi) for package in PACKAGES for abi in ABIS
    }
    if identities != expected_identities:
        raise WheelError(f"incomplete S1a wheel identities: {sorted(identities)}")
    return result


def _validate_publication_document(
    manifest: Path,
    document: dict[str, object],
    *,
    require_current_recipe: bool,
) -> tuple[str, str]:
    recipe = document.get("recipe_key")
    build = document.get("build_key")
    if (
        not isinstance(recipe, str)
        or not isinstance(build, str)
        or KEY_PATTERN.fullmatch(recipe) is None
        or KEY_PATTERN.fullmatch(build) is None
    ):
        raise WheelError("publication recipe/build keys are invalid")
    identity = _validate_builder_identity(document.get("builder_identity"))
    identity_sha256 = hashlib.sha256(_canonical_json(identity)).hexdigest()
    if document.get("builder_identity_sha256") != identity_sha256:
        raise WheelError("publication builder identity hash mismatch")
    if build_key(recipe, identity) != build:
        raise WheelError("publication build key does not match its canonical builder identity")
    if require_current_recipe:
        current_identity = builder_identity()
        if identity != current_identity:
            raise WheelError("publication builder identity differs from the active builder")
        current_recipe = source_recipe_key()
        if recipe != current_recipe:
            raise WheelError(f"obsolete S1a publication recipe key: {recipe}")
        current_build = build_key(current_recipe, current_identity)
        if build != current_build:
            raise WheelError(f"obsolete S1a publication build key: {build}")
        if document.get("recipe_inventory") != recipe_inventory():
            raise WheelError("publication recipe inventory differs from current inputs")
    if manifest.name != "manifest.json" or manifest.parent.name != f"s1a-wheels-{build}":
        raise WheelError("publication parent directory does not match its build key")
    if document.get("api_level") != API_LEVEL:
        raise WheelError("publication Android API level mismatch")
    if document.get("ndk") != NDK_VERSION or document.get("python_target") != PYTHON_TARGET:
        raise WheelError("publication NDK/Python target mismatch")
    wheels = document.get("wheels")
    if not isinstance(wheels, dict) or set(wheels) != set(ABIS):
        raise WheelError("publication ABI set mismatch")
    expected_names: set[str] = set()
    for abi in ABIS:
        entries = wheels.get(abi)
        if not isinstance(entries, list) or len(entries) != len(PACKAGES):
            raise WheelError(f"publication {abi} wheel set is incomplete")
        for raw_entry in entries:
            if not isinstance(raw_entry, dict):
                raise WheelError("invalid publication wheel entry")
            filename = raw_entry.get("filename")
            expected_hash = raw_entry.get("sha256")
            if (
                not isinstance(filename, str)
                or not isinstance(expected_hash, str)
                or KEY_PATTERN.fullmatch(expected_hash) is None
            ):
                raise WheelError("invalid publication wheel name/hash")
            package, filename_abi = _wheel_identity(Path(filename))
            if filename_abi != abi or filename in expected_names:
                raise WheelError(f"publication wheel identity mismatch: {filename}")
            expected_names.add(filename)
            wheel = (manifest.parent / filename).resolve()
            if wheel.parent != manifest.parent.resolve() or not wheel.is_file():
                raise WheelError(f"publication wheel path is invalid: {filename}")
            verify_file(wheel, expected_hash)
            inspected_package, inspected_abi, inspected_entry = verify_s1a_wheel(wheel)
            if inspected_package != package or inspected_abi != abi:
                raise WheelError(f"publication wheel identity mismatch: {filename}")
            if inspected_entry != raw_entry:
                raise WheelError(f"publication wheel inventory mismatch: {filename}")
            if package not in {name.replace("-", "_") for name in PACKAGES}:
                raise WheelError(f"unexpected publication package: {package}")
    actual_names = {path.name for path in manifest.parent.glob("*.whl")}
    if actual_names != expected_names:
        raise WheelError("publication directory contains unmanifested or missing wheels")
    return recipe, build


def verify_publication(manifest: Path) -> dict[str, str]:
    manifest = manifest.resolve(strict=True)
    document = load_json(manifest, MANIFEST_SCHEMA)
    recipe, build = _validate_publication_document(
        manifest,
        document,
        require_current_recipe=True,
    )
    return {"schema": MANIFEST_SCHEMA, "recipe_key": recipe, "build_key": build}


def publish(
    stage_a: Path,
    stage_b: Path,
    output_root: Path,
    expected_recipe: str,
    expected_build: str,
) -> Path:
    enforce_reproducible_environment()
    recipe, build, identity = _validate_expected_keys(expected_recipe, expected_build)
    stage_a = stage_a.resolve(strict=True)
    stage_b = stage_b.resolve(strict=True)
    if stage_a == stage_b:
        raise WheelError("reproducibility proof requires two distinct clean stages")
    document_a, dist_a = validate_stage(
        stage_a,
        recipe,
        build,
        require_dist=True,
    )
    document_b, dist_b = validate_stage(
        stage_b,
        recipe,
        build,
        require_dist=True,
    )
    if document_a["stage_id"] == document_b["stage_id"]:
        raise WheelError("reproducibility proof requires distinct stage identifiers")
    wheels_a = _wheel_set(dist_a)
    wheels_b = _wheel_set(dist_b)
    if set(wheels_a) != set(wheels_b):
        raise WheelError("clean S1a builds produced different wheel filename sets")
    mismatched = [
        filename
        for filename in sorted(wheels_a)
        if digest(wheels_a[filename]) != digest(wheels_b[filename])
    ]
    if mismatched:
        raise WheelError(
            "clean S1a builds are not byte-for-byte reproducible: " + ", ".join(mismatched),
        )

    entries: dict[str, list[dict[str, object]]] = {abi: [] for abi in ABIS}
    identities: set[tuple[str, str]] = set()
    verified: list[tuple[Path, str, dict[str, object]]] = []
    for wheel in wheels_a.values():
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

    output_root.mkdir(parents=True, exist_ok=True)
    target = output_root / f"s1a-wheels-{build}"
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
            "schema": MANIFEST_SCHEMA,
            "recipe_key": recipe,
            "build_key": build,
            "builder_identity": identity,
            "builder_identity_sha256": hashlib.sha256(
                _canonical_json(identity),
            ).hexdigest(),
            "api_level": API_LEVEL,
            "ndk": NDK_VERSION,
            "python_target": PYTHON_TARGET,
            "recipe_inventory": recipe_inventory(),
            "source_hashes": {
                name: entry["sha256"] for name, entry in source_entries().items()
            },
            "sources": source_entries(),
            "patch_hashes": {
                path.relative_to(TOOL_ROOT).as_posix(): digest(path)
                for path in sorted(TOOL_ROOT.rglob("*.patch"))
            },
            "reproducibility": {
                "stage_manifests": [
                    {
                        "stage_id": document["stage_id"],
                        "sha256": digest(_stage_manifest_path(stage)),
                    }
                    for stage, document in (
                        (stage_a, document_a),
                        (stage_b, document_b),
                    )
                ],
                "wheel_sets_byte_identical": True,
            },
            "wheels": entries,
        }
        staging_manifest = staging / "manifest.json"
        staging_manifest.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(staging, target)
        verify_publication(target / "manifest.json")
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        if target.exists():
            shutil.rmtree(target)
        raise
    return target / "manifest.json"


def stage(
    downloads: Path,
    wheelhouse: Path,
    build_root: Path,
    stage_id: str,
    expected_recipe: str,
    expected_build: str,
) -> Path:
    enforce_reproducible_environment()
    if STAGE_ID_PATTERN.fullmatch(stage_id) is None:
        raise WheelError(f"invalid S1a stage identifier: {stage_id}")
    recipe, build, identity = _validate_expected_keys(expected_recipe, expected_build)
    verify_host_wheels(wheelhouse)
    entries = source_entries()
    for entry in entries.values():
        verify_file(downloads / entry["filename"], entry["sha256"])
    target = build_root / f"s1a-{build}-{stage_id}"
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
    for recipe_dir in (TOOL_ROOT / "recipes").iterdir():
        shutil.copytree(recipe_dir, chaquopy / "server/pypi/packages" / recipe_dir.name)
    target_dir = chaquopy / f"maven/com/chaquo/python/target/{PYTHON_TARGET}"
    target_dir.mkdir(parents=True, exist_ok=True)
    for abi in ABIS:
        shutil.copy2(downloads / entries[f"python-{abi}"]["filename"], target_dir)
    patch_builder(chaquopy, wheelhouse)
    manifest = {
        "schema": MANIFEST_SCHEMA,
        "stage_id": stage_id,
        "recipe_key": recipe,
        "build_key": build,
        "builder_identity": identity,
        "recipe_inventory": recipe_inventory(),
        "ndk": NDK_VERSION,
        "python_target": PYTHON_TARGET,
        "source_hashes": {name: entry["sha256"] for name, entry in entries.items()},
        "host_wheels": {filename: sha256 for _, filename, sha256 in host_entries()},
    }
    (temporary / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, target)
    validate_stage(target, recipe, build, require_dist=False)
    return target


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("recipe-key")
    sub.add_parser("builder-identity")
    sub.add_parser("build-key")
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
    staging.add_argument("--stage-id", required=True)
    staging.add_argument("--expected-recipe-key", required=True)
    staging.add_argument("--expected-build-key", required=True)
    publishing = sub.add_parser("publish")
    publishing.add_argument("--stage-a", type=Path, required=True)
    publishing.add_argument("--stage-b", type=Path, required=True)
    publishing.add_argument("--output-root", type=Path, required=True)
    publishing.add_argument("--expected-recipe-key", required=True)
    publishing.add_argument("--expected-build-key", required=True)
    publication = sub.add_parser("verify-publication")
    publication.add_argument("--manifest", type=Path, required=True)
    recipes = sub.add_parser("validate-recipes")
    recipes.add_argument("--chaquopy-root", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    try:
        args = parse_args()
        if args.command == "recipe-key":
            print(source_recipe_key())
        elif args.command == "builder-identity":
            print(json.dumps(builder_identity(), sort_keys=True, separators=(",", ":")))
        elif args.command == "build-key":
            print(build_key())
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
            print(
                stage(
                    args.downloads,
                    args.wheelhouse,
                    args.build_root,
                    args.stage_id,
                    args.expected_recipe_key,
                    args.expected_build_key,
                ),
            )
        elif args.command == "publish":
            print(
                publish(
                    args.stage_a,
                    args.stage_b,
                    args.output_root,
                    args.expected_recipe_key,
                    args.expected_build_key,
                ),
            )
        elif args.command == "verify-publication":
            print(
                json.dumps(
                    verify_publication(args.manifest),
                    sort_keys=True,
                    separators=(",", ":"),
                ),
            )
        elif args.command == "validate-recipes":
            print(
                json.dumps(
                    {"schema": 1, "recipes": validate_recipes(args.chaquopy_root)},
                    sort_keys=True,
                    separators=(",", ":"),
                ),
            )
    except (WheelError, OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"S1a wheel tooling failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
