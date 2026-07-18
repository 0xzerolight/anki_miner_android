#!/usr/bin/env python3
"""Fail-closed verification for the pinned Chaquopy build interpreter."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from typing import Any
from urllib.parse import unquote, urlparse

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_LOCK = SCRIPT_DIR / "chaquopy-build-python.lock.json"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
VERSION_PATTERN = re.compile(r"3\.12\.\d+")


class BuildPythonVerificationError(RuntimeError):
    """The build interpreter lock or installation is invalid."""


def _exact_keys(value: object, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        actual = sorted(value) if isinstance(value, dict) else type(value).__name__
        raise BuildPythonVerificationError(
            f"{label} keys differ: expected={sorted(expected)}, actual={actual}",
        )
    return value


def _nonempty_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise BuildPythonVerificationError(f"{label} must be a non-empty string")
    if "\n" in value or "\r" in value or "\0" in value:
        raise BuildPythonVerificationError(f"{label} contains a forbidden character")
    return value


def _sha256_string(value: object, label: str) -> str:
    text = _nonempty_string(value, label)
    if SHA256_PATTERN.fullmatch(text) is None:
        raise BuildPythonVerificationError(f"{label} must be a lowercase SHA-256 value")
    return text


def _safe_relative_path(value: object, label: str, *, single_component: bool = False) -> str:
    text = _nonempty_string(value, label)
    path = Path(text)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise BuildPythonVerificationError(f"{label} must be a safe relative path")
    if single_component and len(path.parts) != 1:
        raise BuildPythonVerificationError(f"{label} must contain exactly one path component")
    return text


def load_lock(path: Path = DEFAULT_LOCK) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BuildPythonVerificationError(f"cannot read build Python lock: {path}") from error

    root = _exact_keys(
        document,
        {"schema", "implementation", "version", "archive", "installation"},
        "lock",
    )
    if root["schema"] != 1:
        raise BuildPythonVerificationError("unsupported build Python lock schema")
    if root["implementation"] != "CPython":
        raise BuildPythonVerificationError("build Python implementation must be CPython")
    version = _nonempty_string(root["version"], "version")
    if VERSION_PATTERN.fullmatch(version) is None:
        raise BuildPythonVerificationError(
            "build Python version must be an exact CPython 3.12 patch",
        )

    archive = _exact_keys(root["archive"], {"filename", "sha256", "url"}, "archive")
    filename = _safe_relative_path(archive["filename"], "archive.filename", single_component=True)
    _sha256_string(archive["sha256"], "archive.sha256")
    url = _nonempty_string(archive["url"], "archive.url")
    parsed = urlparse(url)
    if (
        parsed.scheme != "https"
        or parsed.netloc != "github.com"
        or not parsed.path.startswith(
            "/astral-sh/python-build-standalone/releases/download/",
        )
        or unquote(Path(parsed.path).name) != filename
        or parsed.params
        or parsed.query
        or parsed.fragment
    ):
        raise BuildPythonVerificationError("archive.url is not the locked upstream release asset")

    installation = _exact_keys(
        root["installation"],
        {
            "directory",
            "executable",
            "executable_sha256",
            "marker",
            "payload_sha256",
        },
        "installation",
    )
    _safe_relative_path(
        installation["directory"],
        "installation.directory",
        single_component=True,
    )
    _safe_relative_path(installation["executable"], "installation.executable")
    _sha256_string(installation["executable_sha256"], "installation.executable_sha256")
    _sha256_string(installation["payload_sha256"], "installation.payload_sha256")
    _safe_relative_path(
        installation["marker"],
        "installation.marker",
        single_component=True,
    )
    return root


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _digest_field(digest: Any, value: bytes) -> None:
    digest.update(len(value).to_bytes(8, "big"))
    digest.update(value)


def _payload_path_bytes(path: Path) -> bytes:
    try:
        return path.as_posix().encode("utf-8")
    except UnicodeEncodeError as error:
        raise BuildPythonVerificationError(
            f"build Python payload path is not canonical UTF-8: {path!s}",
        ) from error


def _stat_identity(value: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def _payload_inventory(
    root: Path,
    *,
    marker: Path,
    digest: Any | None,
) -> list[tuple[object, ...]]:
    inventory: list[tuple[object, ...]] = []

    def scan(directory: Path, relative_directory: Path, in_pycache: bool) -> None:
        try:
            entries = sorted(os.scandir(directory), key=lambda entry: entry.name)
        except OSError as error:
            raise BuildPythonVerificationError(
                f"cannot inspect build Python payload directory: {directory}",
            ) from error

        for entry in entries:
            relative = relative_directory / entry.name
            if relative == marker:
                continue
            try:
                entry_stat = entry.stat(follow_symlinks=False)
            except OSError as error:
                raise BuildPythonVerificationError(
                    f"cannot inspect build Python payload entry: {relative}",
                ) from error

            mode = entry_stat.st_mode
            if stat.S_ISREG(mode) and in_pycache and relative.suffix == ".pyc":
                # CPython and pip may generate or refresh these after extraction.
                # Only regular .pyc files directly inside __pycache__ are ignored.
                continue

            relative_bytes = _payload_path_bytes(relative)
            if stat.S_ISDIR(mode):
                child_is_pycache = entry.name == "__pycache__"
                if not child_is_pycache:
                    inventory.append(("directory", relative.as_posix()))
                    if digest is not None:
                        _digest_field(digest, b"directory")
                        _digest_field(digest, relative_bytes)
                scan(Path(entry.path), relative, child_is_pycache)
                continue

            if stat.S_ISLNK(mode):
                try:
                    target = os.readlink(entry.path)
                    final_stat = entry.stat(follow_symlinks=False)
                except OSError as error:
                    raise BuildPythonVerificationError(
                        f"cannot inspect build Python payload symlink: {relative}",
                    ) from error
                identity = (
                    "symlink",
                    relative.as_posix(),
                    target,
                    entry_stat.st_dev,
                    entry_stat.st_ino,
                    entry_stat.st_mtime_ns,
                    entry_stat.st_ctime_ns,
                )
                if _stat_identity(final_stat) != _stat_identity(entry_stat):
                    raise BuildPythonVerificationError(
                        f"build Python payload changed while hashing: {relative}",
                    )
                inventory.append(identity)
                if digest is not None:
                    try:
                        target_bytes = target.encode("utf-8")
                    except UnicodeEncodeError as error:
                        raise BuildPythonVerificationError(
                            f"build Python symlink target is not UTF-8: {relative}",
                        ) from error
                    _digest_field(digest, b"symlink")
                    _digest_field(digest, relative_bytes)
                    _digest_field(digest, target_bytes)
                continue

            if not stat.S_ISREG(mode):
                raise BuildPythonVerificationError(
                    f"build Python payload contains a special file: {relative}",
                )

            file_digest = hashlib.sha256()
            try:
                with open(entry.path, "rb", buffering=0) as stream:
                    opened_stat = os.fstat(stream.fileno())
                    if _stat_identity(opened_stat) != _stat_identity(entry_stat):
                        raise BuildPythonVerificationError(
                            f"build Python payload changed before hashing: {relative}",
                        )
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        file_digest.update(chunk)
                    final_stat = os.fstat(stream.fileno())
            except BuildPythonVerificationError:
                raise
            except OSError as error:
                raise BuildPythonVerificationError(
                    f"cannot hash build Python payload file: {relative}",
                ) from error
            if _stat_identity(final_stat) != _stat_identity(opened_stat):
                raise BuildPythonVerificationError(
                    f"build Python payload changed while hashing: {relative}",
                )

            executable = bool(mode & 0o111)
            inventory.append(
                (
                    "file",
                    relative.as_posix(),
                    executable,
                    entry_stat.st_size,
                    entry_stat.st_dev,
                    entry_stat.st_ino,
                    entry_stat.st_mtime_ns,
                    entry_stat.st_ctime_ns,
                    file_digest.digest(),
                )
            )
            if digest is not None:
                _digest_field(digest, b"file")
                _digest_field(digest, relative_bytes)
                _digest_field(digest, b"executable" if executable else b"regular")
                _digest_field(digest, entry_stat.st_size.to_bytes(8, "big"))
                _digest_field(digest, file_digest.digest())

    scan(root, Path(), False)
    return inventory


def installation_payload_sha256(install_root: Path, marker_name: str) -> str:
    """Hash the immutable installation while tolerating generated bytecode."""

    try:
        root_stat = install_root.stat(follow_symlinks=False)
    except OSError as error:
        raise BuildPythonVerificationError(
            "build Python payload root is missing",
        ) from error
    if not stat.S_ISDIR(root_stat.st_mode):
        raise BuildPythonVerificationError("build Python payload root is not a directory")

    marker = Path(_safe_relative_path(marker_name, "installation.marker"))
    digest = hashlib.sha256()
    first_inventory = _payload_inventory(install_root, marker=marker, digest=digest)
    second_inventory = _payload_inventory(install_root, marker=marker, digest=None)
    if first_inventory != second_inventory:
        raise BuildPythonVerificationError(
            "build Python payload changed while its inventory was verified",
        )
    return digest.hexdigest()


def validate_generated_bytecode(python_command: Path, install_root: Path) -> None:
    """Prove ignored ``__pycache__`` files compile from attested sources."""

    if not any(install_root.glob("**/__pycache__/*.pyc")):
        return
    probe = r"""
import importlib.util
import io
import marshal
from pathlib import Path
import py_compile
import tempfile
import types

root = Path(__import__("sys").argv[1]).resolve(strict=True)

def normalize(value):
    if not isinstance(value, types.CodeType):
        return value
    return value.replace(
        co_filename="<attested-source>",
        co_consts=tuple(normalize(item) for item in value.co_consts),
    )

count = 0
for pyc in sorted(root.glob("**/__pycache__/*.pyc")):
    if not pyc.is_file() or pyc.is_symlink():
        raise RuntimeError(f"invalid generated bytecode entry: {pyc}")
    source = Path(importlib.util.source_from_cache(str(pyc))).resolve(strict=True)
    if root not in source.parents or not source.is_file():
        raise RuntimeError(f"generated bytecode has no attested source: {pyc}")
    optimization = 0
    if ".opt-1." in pyc.name:
        optimization = 1
    elif ".opt-2." in pyc.name:
        optimization = 2
    elif ".opt-" in pyc.name:
        raise RuntimeError(f"unsupported bytecode optimization tag: {pyc}")

    payload = pyc.read_bytes()
    if len(payload) < 16 or payload[:4] != importlib.util.MAGIC_NUMBER:
        raise RuntimeError(f"invalid generated bytecode header: {pyc}")
    stream = io.BytesIO(payload[16:])
    cached_code = marshal.load(stream)
    if stream.read():
        raise RuntimeError(f"generated bytecode has trailing data: {pyc}")

    with tempfile.NamedTemporaryFile() as compiled:
        py_compile.compile(
            str(source),
            cfile=compiled.name,
            dfile=str(source),
            doraise=True,
            optimize=optimization,
        )
        fresh_stream = io.BytesIO(Path(compiled.name).read_bytes()[16:])
        fresh_code = marshal.load(fresh_stream)
        if fresh_stream.read():
            raise RuntimeError(f"fresh bytecode has trailing data: {source}")
    if normalize(cached_code) != normalize(fresh_code):
        raise RuntimeError(f"generated bytecode differs from its source: {pyc}")
    count += 1

print(f"anki-miner-bytecode-ok:{count}")
"""
    environment = {
        key: value
        for key, value in os.environ.items()
        if key not in {"PYTHONHOME", "PYTHONPATH", "PYTHONPYCACHEPREFIX"}
    }
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    try:
        with tempfile.TemporaryDirectory(prefix="anki-miner-pycache-") as cache:
            result = subprocess.run(
                [
                    str(python_command),
                    "-I",
                    "-S",
                    "-B",
                    "-X",
                    f"pycache_prefix={cache}",
                    "-c",
                    probe,
                    str(install_root),
                ],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=120,
                env=environment,
            )
    except (OSError, subprocess.SubprocessError) as error:
        raise BuildPythonVerificationError(
            "build Python generated bytecode failed source verification",
        ) from error
    output = result.stdout.strip()
    if not re.fullmatch(r"anki-miner-bytecode-ok:[1-9][0-9]*", output):
        raise BuildPythonVerificationError(
            "build Python generated bytecode returned an invalid verification result",
        )


def _resolve_install_root(
    lock: dict[str, Any],
    toolchain_root: Path | None,
    install_root: Path | None,
) -> Path:
    if toolchain_root is not None and install_root is not None:
        raise BuildPythonVerificationError("use only one of --toolchain-root and --install-root")
    if install_root is not None:
        if not install_root.is_absolute():
            raise BuildPythonVerificationError("--install-root must be absolute")
        return install_root
    if toolchain_root is None:
        raw_root = os.environ.get("ANKI_MINER_ANDROID_TOOLCHAIN_ROOT")
        if not raw_root:
            raise BuildPythonVerificationError(
                "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT is unset; source scripts/android-env.sh",
            )
        toolchain_root = Path(raw_root)
    if not toolchain_root.is_absolute():
        raise BuildPythonVerificationError("--toolchain-root must be absolute")
    return toolchain_root / lock["installation"]["directory"]


def verify(
    *,
    lock_path: Path,
    python_command: Path,
    toolchain_root: Path | None = None,
    install_root: Path | None = None,
) -> dict[str, object]:
    lock = load_lock(lock_path)
    if not python_command.is_absolute():
        raise BuildPythonVerificationError("build Python command must be absolute")

    expected_install_root = _resolve_install_root(lock, toolchain_root, install_root)
    expected_python = expected_install_root / lock["installation"]["executable"]
    if python_command != expected_python:
        raise BuildPythonVerificationError(
            f"build Python command is {python_command}, expected {expected_python}",
        )
    try:
        resolved_install_root = expected_install_root.resolve(strict=True)
        resolved_python = python_command.resolve(strict=True)
    except OSError as error:
        raise BuildPythonVerificationError("pinned build Python installation is missing") from error
    if resolved_python != resolved_install_root / lock["installation"]["executable"]:
        raise BuildPythonVerificationError(
            "build Python executable resolves outside its installation",
        )
    if not resolved_python.is_file() or not os.access(resolved_python, os.X_OK):
        raise BuildPythonVerificationError("build Python executable is not an executable file")

    marker = resolved_install_root / lock["installation"]["marker"]
    try:
        marker_stat = marker.stat(follow_symlinks=False)
        marker_bytes = marker.read_bytes()
        lock_bytes = lock_path.read_bytes()
    except OSError as error:
        raise BuildPythonVerificationError("build Python archive marker is missing") from error
    if not stat.S_ISREG(marker_stat.st_mode):
        raise BuildPythonVerificationError(
            "build Python archive marker must be a regular file",
        )
    if marker_bytes != lock_bytes:
        raise BuildPythonVerificationError(
            "build Python archive marker differs from the committed lock",
        )

    actual_sha256 = sha256(resolved_python)
    expected_sha256 = lock["installation"]["executable_sha256"]
    if actual_sha256 != expected_sha256:
        raise BuildPythonVerificationError(
            "build Python executable hash differs from the committed lock",
        )

    actual_payload_sha256 = installation_payload_sha256(
        resolved_install_root,
        lock["installation"]["marker"],
    )
    if actual_payload_sha256 != lock["installation"]["payload_sha256"]:
        raise BuildPythonVerificationError(
            "build Python payload hash differs from the committed lock",
        )
    validate_generated_bytecode(resolved_python, resolved_install_root)
    try:
        final_marker_stat = marker.stat(follow_symlinks=False)
        final_marker_bytes = marker.read_bytes()
        final_lock_bytes = lock_path.read_bytes()
    except OSError as error:
        raise BuildPythonVerificationError(
            "build Python archive marker changed during verification",
        ) from error
    if (
        _stat_identity(final_marker_stat) != _stat_identity(marker_stat)
        or final_marker_bytes != lock_bytes
        or final_lock_bytes != lock_bytes
    ):
        raise BuildPythonVerificationError(
            "build Python archive marker changed during verification",
        )

    probe = (
        "import json,platform,sys; "
        "print(json.dumps({"
        "'implementation':platform.python_implementation(),"
        "'version':platform.python_version(),"
        "'executable':sys.executable,"
        "'prefix':sys.prefix},sort_keys=True))"
    )
    environment = {key: value for key, value in os.environ.items() if key not in {"PYTHONHOME", "PYTHONPATH"}}
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    try:
        result = subprocess.run(
            [str(resolved_python), "-I", "-S", "-B", "-c", probe],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
            env=environment,
        )
        identity = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        raise BuildPythonVerificationError("build Python identity probe failed") from error
    identity = _exact_keys(
        identity,
        {"implementation", "version", "executable", "prefix"},
        "build Python identity",
    )
    if identity["implementation"] != lock["implementation"]:
        raise BuildPythonVerificationError("build Python implementation differs from the lock")
    if identity["version"] != lock["version"]:
        raise BuildPythonVerificationError("build Python version differs from the lock")
    try:
        probed_executable = Path(
            _nonempty_string(identity["executable"], "identity.executable"),
        ).resolve(strict=True)
        probed_prefix = Path(
            _nonempty_string(identity["prefix"], "identity.prefix"),
        ).resolve(strict=True)
    except OSError as error:
        raise BuildPythonVerificationError(
            "build Python reported an invalid filesystem identity",
        ) from error
    if probed_executable != resolved_python or probed_prefix != resolved_install_root:
        raise BuildPythonVerificationError(
            "build Python reported an unexpected filesystem identity",
        )

    return {
        "schema": 1,
        "implementation": identity["implementation"],
        "version": identity["version"],
        "executable": str(resolved_python),
        "executable_sha256": actual_sha256,
        "archive_sha256": lock["archive"]["sha256"],
    }


def describe(lock_path: Path) -> list[str]:
    lock = load_lock(lock_path)
    archive = lock["archive"]
    installation = lock["installation"]
    return [
        archive["filename"],
        archive["url"],
        archive["sha256"],
        installation["directory"],
        installation["executable"],
        installation["executable_sha256"],
        installation["marker"],
        lock["version"],
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("describe", help="print locked provisioning fields")
    verify_parser = subparsers.add_parser("verify", help="verify the installed interpreter")
    verify_parser.add_argument("--python", type=Path, required=True)
    root_group = verify_parser.add_mutually_exclusive_group()
    root_group.add_argument("--toolchain-root", type=Path)
    root_group.add_argument("--install-root", type=Path)
    arguments = parser.parse_args()

    try:
        if arguments.command == "describe":
            for value in describe(arguments.lock):
                print(value)
        else:
            result = verify(
                lock_path=arguments.lock,
                python_command=arguments.python,
                toolchain_root=arguments.toolchain_root,
                install_root=arguments.install_root,
            )
            print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    except BuildPythonVerificationError as error:
        print(f"build Python verification error: {error}", file=sys.stderr)
        return 96
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
