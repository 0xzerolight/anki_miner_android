#!/usr/bin/env python3
"""Reproducible source staging and offline cross-build driver for tokenizer S1a."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
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
        'os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"\n'
        '        os.environ["PIP_CONFIG_FILE"] = os.devnull\n'
        '        for name in ["PIP_INDEX_URL", "PIP_EXTRA_INDEX_URL", "HTTP_PROXY", '\
        '"HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"]:\n'
        '            os.environ.pop(name, None)',
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
    subprocess.run(["patch", f"-p{strip}", "--forward", "--batch", "-i", str(patch)], cwd=source, check=True)


def recipe_key() -> str:
    value = hashlib.sha256()
    for path in sorted(TOOL_ROOT.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts:
            value.update(path.relative_to(TOOL_ROOT).as_posix().encode())
            value.update(path.read_bytes())
    value.update(NDK_VERSION.encode())
    value.update(PYTHON_TARGET.encode())
    return value.hexdigest()


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
    except (WheelError, OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"S1a wheel tooling failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
