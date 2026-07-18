"""Sync the pinned, source-only AnkiDroid API surface into the app module."""

from __future__ import annotations

import errno
import hashlib
import json
import os
import secrets
import stat
import subprocess
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, NoReturn

MANIFEST_VERSION = 1
MAX_MANIFEST_BYTES = 128 * 1024
_READ_CHUNK_BYTES = 64 * 1024
MANIFEST_PATH = PurePosixPath("third_party/ankidroid-api/manifest.json")
GENERATED_SOURCE_ROOT = PurePosixPath("app/src/main/ankidroidApi/kotlin")
GENERATED_LICENSE_ROOT = PurePosixPath("third_party/ankidroid-api/upstream")


class SyncError(RuntimeError):
    """Raised when provenance or generated output cannot be proven."""


@dataclass(frozen=True)
class UpstreamPin:
    repository: str
    tag: str
    commit: str


@dataclass(frozen=True)
class VendoredFile:
    source_path: PurePosixPath
    destination_path: PurePosixPath
    kind: str
    spdx_license: str


PINNED_UPSTREAM = UpstreamPin(
    repository="https://github.com/ankidroid/Anki-Android.git",
    tag="v2.24.0",
    commit="ebcf8e0e34921628b9b8a496c66ffd4adbb3705f",
)

_API_SOURCE_ROOT = PurePosixPath("api/src/main/java")
_SOURCE_DESTINATION_ROOT = GENERATED_SOURCE_ROOT
_API_FILES = (
    (
        "com/ichi2/anki/FlashCardsContract.kt",
        "LicenseRef-AnkiDroid-FlashCardsContract-Permissive",
    ),
    ("com/ichi2/anki/api/AddContentApi.kt", "LGPL-3.0-or-later"),
    ("com/ichi2/anki/api/Basic2Model.kt", "LGPL-3.0-only"),
    ("com/ichi2/anki/api/BasicModel.kt", "LGPL-3.0-only"),
    ("com/ichi2/anki/api/Ease.kt", "LGPL-3.0-or-later"),
    ("com/ichi2/anki/api/NoteInfo.kt", "LGPL-3.0-or-later"),
    ("com/ichi2/anki/api/Utils.kt", "LGPL-3.0-or-later"),
)

VENDORED_FILES = tuple(
    VendoredFile(
        source_path=_API_SOURCE_ROOT / relative_path,
        destination_path=_SOURCE_DESTINATION_ROOT / relative_path,
        kind="kotlin-source",
        spdx_license=license_expression,
    )
    for relative_path, license_expression in _API_FILES
) + (
    VendoredFile(
        source_path=PurePosixPath("api/COPYING.LESSER"),
        destination_path=GENERATED_LICENSE_ROOT / "api/COPYING.LESSER",
        kind="license-text",
        spdx_license="LGPL-3.0-only",
    ),
    VendoredFile(
        source_path=PurePosixPath("COPYING"),
        destination_path=GENERATED_LICENSE_ROOT / "COPYING",
        kind="license-text",
        spdx_license="GPL-3.0-only",
    ),
)

_MANIFEST_TOP_LEVEL_KEYS = {"component", "files", "formatVersion"}
_MANIFEST_COMPONENT_KEYS = {"commit", "name", "repository", "tag"}
_MANIFEST_FILE_KEYS = {
    "byteCount",
    "destinationPath",
    "kind",
    "sha256",
    "sourcePath",
    "spdxLicense",
}


def _fail(message: str) -> NoReturn:
    raise SyncError(message)


def _run_git_bytes(checkout: Path, *args: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", "--no-replace-objects", "-C", os.fspath(checkout), *args],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as exc:
        _fail(f"cannot execute git to verify upstream checkout: {exc}")
    if result.returncode != 0:
        raw_detail = result.stderr.strip() or result.stdout.strip()
        detail = raw_detail.decode("utf-8", errors="replace") if raw_detail else "git command failed"
        _fail(f"cannot verify upstream checkout: {detail}")
    return result.stdout


def _run_git(checkout: Path, *args: str) -> str:
    return _run_git_bytes(checkout, *args).decode("utf-8", errors="strict").strip()


def _read_pinned_source_bytes(
    checkout: Path,
    pin: UpstreamPin,
) -> dict[PurePosixPath, bytes]:
    """Read only regular non-executable blobs from the immutable pinned tree."""

    paths = [entry.source_path.as_posix() for entry in VENDORED_FILES]
    tree_output = _run_git_bytes(
        checkout,
        "ls-tree",
        "-z",
        pin.commit,
        "--",
        *paths,
    )
    tree_entries: dict[PurePosixPath, tuple[str, str, str]] = {}
    for raw_entry in tree_output.split(b"\0"):
        if not raw_entry:
            continue
        try:
            raw_metadata, raw_path = raw_entry.split(b"\t", 1)
            mode, object_type, object_id = raw_metadata.decode("ascii").split(" ")
            source_path = PurePosixPath(raw_path.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as exc:
            _fail(f"cannot parse pinned Git tree entry: {exc}")
        if source_path in tree_entries:
            _fail(f"pinned Git tree contains duplicate path: {source_path}")
        tree_entries[source_path] = (mode, object_type, object_id)

    expected_paths = {entry.source_path for entry in VENDORED_FILES}
    if set(tree_entries) != expected_paths:
        missing = sorted(expected_paths - set(tree_entries))
        extra = sorted(set(tree_entries) - expected_paths)
        _fail(f"pinned Git tree path set differs: missing={missing}, extra={extra}")

    source_bytes: dict[PurePosixPath, bytes] = {}
    for entry in VENDORED_FILES:
        mode, object_type, object_id = tree_entries[entry.source_path]
        if mode != "100644" or object_type != "blob":
            _fail("pinned Git tree entry must be a 100644 blob: " f"{entry.source_path} is {mode} {object_type}")
        source_bytes[entry.source_path] = _run_git_bytes(
            checkout,
            "cat-file",
            "blob",
            object_id,
        )
    return source_bytes


def _require_visible_index_entries(checkout: Path) -> None:
    """Reject assume-unchanged, skip-worktree, unmerged, or missing pinned paths."""

    expected_paths = {entry.source_path for entry in VENDORED_FILES}
    output = _run_git_bytes(
        checkout,
        "ls-files",
        "-v",
        "-z",
        "--",
        *(path.as_posix() for path in expected_paths),
    )
    found: set[PurePosixPath] = set()
    for raw_entry in output.split(b"\0"):
        if not raw_entry:
            continue
        if len(raw_entry) < 3 or raw_entry[1:2] != b" ":
            _fail("cannot parse upstream Git index flags")
        try:
            source_path = PurePosixPath(raw_entry[2:].decode("utf-8"))
        except UnicodeDecodeError as exc:
            _fail(f"upstream Git index path is not UTF-8: {exc}")
        if raw_entry[:1] != b"H":
            marker = raw_entry[:1].decode("ascii", errors="replace")
            _fail(f"upstream Git index hides or alters {source_path}: flag {marker}")
        if source_path in found:
            _fail(f"upstream Git index contains duplicate path: {source_path}")
        found.add(source_path)
    if found != expected_paths:
        _fail("upstream Git index does not contain the complete pinned file set")


def verify_checkout(checkout: Path, pin: UpstreamPin = PINNED_UPSTREAM) -> Path:
    """Prove that checkout is the clean worktree for the exact pinned tag and commit."""

    checkout = checkout.resolve()
    if not checkout.is_dir():
        _fail(f"upstream checkout is not a directory: {checkout}")

    top_level = Path(_run_git(checkout, "rev-parse", "--show-toplevel")).resolve()
    if top_level != checkout:
        _fail(f"source must name the checkout root exactly: {top_level}")

    actual_commit = _run_git(checkout, "rev-parse", "HEAD")
    if actual_commit != pin.commit:
        _fail(f"wrong upstream commit: expected {pin.commit}, found {actual_commit}")

    tag_commit = _run_git(checkout, "rev-parse", f"refs/tags/{pin.tag}^{{commit}}")
    if tag_commit != pin.commit:
        _fail(f"tag {pin.tag} does not resolve to pinned commit {pin.commit}")

    pinned_source_bytes = _read_pinned_source_bytes(checkout, pin)
    _require_visible_index_entries(checkout)

    status = _run_git(
        checkout,
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
        "--ignore-submodules=none",
    )
    if status:
        _fail("upstream checkout is dirty; refresh and upstream proof require a clean checkout")

    for entry in VENDORED_FILES:
        source = checkout / entry.source_path
        try:
            source.relative_to(checkout)
        except ValueError:
            _fail(f"source path escapes checkout: {entry.source_path}")
        if source.is_symlink() or not source.is_file():
            _fail(f"upstream source is not a regular file: {entry.source_path}")
        if source.read_bytes() != pinned_source_bytes[entry.source_path]:
            _fail(f"upstream worktree bytes differ from pinned Git blob: {entry.source_path}")

    return checkout


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _fail(f"manifest contains duplicate key: {key}")
        result[key] = value
    return result


def _manifest_bytes(manifest: dict[str, Any]) -> bytes:
    rendered = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True)
    return f"{rendered}\n".encode("utf-8")


def _build_manifest(source_bytes: dict[PurePosixPath, bytes], pin: UpstreamPin) -> dict[str, Any]:
    files = []
    for entry in VENDORED_FILES:
        data = source_bytes[entry.source_path]
        files.append(
            {
                "byteCount": len(data),
                "destinationPath": entry.destination_path.as_posix(),
                "kind": entry.kind,
                "sha256": _sha256(data),
                "sourcePath": entry.source_path.as_posix(),
                "spdxLicense": entry.spdx_license,
            }
        )
    return {
        "component": {
            "commit": pin.commit,
            "name": "AnkiDroid API",
            "repository": pin.repository,
            "tag": pin.tag,
        },
        "files": files,
        "formatVersion": MANIFEST_VERSION,
    }


def _open_absolute_directory_no_follow(path: Path, *, label: str) -> int:
    """Open one absolute directory through no-follow component descriptors."""

    if not path.is_absolute():
        _fail(f"{label} is not absolute: {path}")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW
    current_fd: int | None = None
    try:
        current_fd = os.open(path.anchor, flags)
        for part in path.parts[1:]:
            next_fd = os.open(part, flags, dir_fd=current_fd)
            os.close(current_fd)
            current_fd = next_fd
        return current_fd
    except OSError as exc:
        if current_fd is not None:
            os.close(current_fd)
        _fail(f"cannot open {label} safely: {exc}")


class _ManagedRepository:
    """Descriptor-rooted access to the generated boundary.

    Construction pins the resolved root directory identity. Context entry
    reopens that path component-by-component and rejects any root or ancestor
    replacement before exposing the descriptor.

    Every child component is opened relative to the held repository descriptor
    with ``O_NOFOLLOW``. Renaming or replacing an ancestor pathname therefore
    cannot redirect a managed read, write, or deletion outside this root.
    """

    def __init__(self, repo_root: Path) -> None:
        try:
            self.root_path = repo_root.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            _fail(f"cannot resolve repository root: {exc}")
        descriptor = _open_absolute_directory_no_follow(
            self.root_path,
            label="repository root",
        )
        try:
            opened = os.fstat(descriptor)
            if not stat.S_ISDIR(opened.st_mode):
                _fail(f"repository root is not a directory: {self.root_path}")
            self._root_identity = (opened.st_dev, opened.st_ino)
        finally:
            os.close(descriptor)
        self._root_fd: int | None = None

    def __enter__(self) -> _ManagedRepository:
        if self._root_fd is not None:
            _fail("managed repository is already open")
        descriptor = _open_absolute_directory_no_follow(
            self.root_path,
            label="repository root",
        )
        try:
            opened = os.fstat(descriptor)
            if not stat.S_ISDIR(opened.st_mode) or (opened.st_dev, opened.st_ino) != self._root_identity:
                _fail("repository root changed between construction and opening")
        except BaseException:
            os.close(descriptor)
            raise
        self._root_fd = descriptor
        return self

    def __exit__(self, _exc_type: object, _exc: object, _traceback: object) -> None:
        if self._root_fd is not None:
            os.close(self._root_fd)
            self._root_fd = None

    @property
    def root_fd(self) -> int:
        if self._root_fd is None:
            raise RuntimeError("managed repository is not open")
        return self._root_fd

    @staticmethod
    def _parts(relative: PurePosixPath) -> tuple[str, ...]:
        if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
            _fail(f"managed path is not a safe repository-relative path: {relative}")
        return relative.parts

    def _open_directory(
        self,
        relative: PurePosixPath,
        *,
        create: bool,
        missing_ok: bool,
        description: str,
    ) -> int | None:
        parts = self._parts(relative)
        current_fd = os.dup(self.root_fd)
        traversed: list[str] = []
        try:
            for part in parts:
                traversed.append(part)
                if create:
                    try:
                        os.mkdir(part, mode=0o755, dir_fd=current_fd)
                    except FileExistsError:
                        pass
                    except OSError as exc:
                        _fail(f"cannot create {description} {relative}: {exc}")
                try:
                    next_fd = os.open(
                        part,
                        os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW,
                        dir_fd=current_fd,
                    )
                except FileNotFoundError:
                    if missing_ok:
                        os.close(current_fd)
                        return None
                    _fail(f"{description} is missing: {relative}")
                except OSError as exc:
                    try:
                        component_stat = os.stat(part, dir_fd=current_fd, follow_symlinks=False)
                    except OSError:
                        component_stat = None
                    component = PurePosixPath(*traversed)
                    if component_stat is not None and stat.S_ISLNK(component_stat.st_mode):
                        _fail(f"{description} traverses a symlink: {component}")
                    _fail(f"{description} is not a regular directory: {component}: {exc}")
                os.close(current_fd)
                current_fd = next_fd
            return current_fd
        except BaseException:
            try:
                os.close(current_fd)
            except OSError:
                pass
            raise

    def read_regular(
        self,
        relative: PurePosixPath,
        *,
        label: str,
        max_bytes: int | None = None,
    ) -> bytes:
        parts = self._parts(relative)
        parent = PurePosixPath(*parts[:-1])
        parent_fd = self._open_directory(
            parent,
            create=False,
            missing_ok=True,
            description=f"{label} parent",
        )
        if parent_fd is None:
            _fail(f"{label} is missing or not a regular file: {relative}")
        leaf = parts[-1]
        descriptor: int | None = None
        try:
            try:
                before = os.stat(leaf, dir_fd=parent_fd, follow_symlinks=False)
                if not stat.S_ISREG(before.st_mode):
                    _fail(f"{label} is missing or not a regular file: {relative}")
                descriptor = os.open(
                    leaf,
                    os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK,
                    dir_fd=parent_fd,
                )
                opened = os.fstat(descriptor)
            except OSError:
                _fail(f"{label} is missing or not a regular file: {relative}")
            if not stat.S_ISREG(opened.st_mode):
                _fail(f"{label} is missing or not a regular file: {relative}")
            if (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
                _fail(f"{label} changed while being opened: {relative}")
            if max_bytes is not None and opened.st_size > max_bytes:
                _fail(f"{label} exceeds the {max_bytes}-byte host-tool limit")

            chunks: list[bytes] = []
            total = 0
            while True:
                chunk = os.read(descriptor, _READ_CHUNK_BYTES)
                if not chunk:
                    break
                chunks.append(chunk)
                total += len(chunk)
                if max_bytes is not None and total > max_bytes:
                    _fail(f"{label} exceeds the {max_bytes}-byte host-tool limit")
            after = os.fstat(descriptor)
            if (
                opened.st_size,
                opened.st_mtime_ns,
                opened.st_ctime_ns,
            ) != (
                after.st_size,
                after.st_mtime_ns,
                after.st_ctime_ns,
            ):
                _fail(f"{label} changed while being read: {relative}")
            data = b"".join(chunks)
            if len(data) != opened.st_size:
                _fail(f"{label} changed while being read: {relative}")
            return data
        finally:
            if descriptor is not None:
                os.close(descriptor)
            os.close(parent_fd)

    def scan_tree(
        self,
        root: PurePosixPath,
    ) -> tuple[set[PurePosixPath], set[PurePosixPath]]:
        root_fd = self._open_directory(
            root,
            create=False,
            missing_ok=True,
            description="managed output root",
        )
        if root_fd is None:
            return set(), set()

        files: set[PurePosixPath] = set()
        directories: set[PurePosixPath] = set()

        def visit(directory_fd: int, relative_directory: PurePosixPath) -> None:
            try:
                with os.scandir(directory_fd) as iterator:
                    entries = []
                    for entry in iterator:
                        entries.append((entry.name, entry.stat(follow_symlinks=False)))
            except OSError as exc:
                _fail(f"cannot scan managed output {relative_directory}: {exc}")
            for name, entry_stat in sorted(entries, key=lambda item: item[0]):
                relative = relative_directory / name
                if stat.S_ISLNK(entry_stat.st_mode):
                    _fail(f"managed output contains a symlink: {relative}")
                if stat.S_ISREG(entry_stat.st_mode):
                    files.add(relative)
                    continue
                if not stat.S_ISDIR(entry_stat.st_mode):
                    _fail(f"managed output contains a non-regular entry: {relative}")
                try:
                    child_fd = os.open(
                        name,
                        os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW,
                        dir_fd=directory_fd,
                    )
                except OSError as exc:
                    _fail(f"managed output changed while scanning {relative}: {exc}")
                try:
                    opened = os.fstat(child_fd)
                    if (entry_stat.st_dev, entry_stat.st_ino) != (
                        opened.st_dev,
                        opened.st_ino,
                    ):
                        _fail(f"managed output changed while scanning {relative}")
                    directories.add(relative)
                    visit(child_fd, relative)
                finally:
                    os.close(child_fd)

        try:
            visit(root_fd, root)
        finally:
            os.close(root_fd)
        return files, directories

    def atomic_write(self, destination: PurePosixPath, data: bytes) -> None:
        parts = self._parts(destination)
        parent = PurePosixPath(*parts[:-1])
        parent_fd = self._open_directory(
            parent,
            create=True,
            missing_ok=False,
            description="managed destination parent",
        )
        if parent_fd is None:
            raise AssertionError("created destination parent cannot be missing")
        leaf = parts[-1]
        temporary_name: str | None = None
        descriptor: int | None = None
        try:
            for _attempt in range(32):
                candidate = f".{leaf}.{secrets.token_hex(12)}"
                try:
                    descriptor = os.open(
                        candidate,
                        os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW,
                        0o600,
                        dir_fd=parent_fd,
                    )
                except FileExistsError:
                    continue
                temporary_name = candidate
                break
            if descriptor is None or temporary_name is None:
                _fail(f"cannot allocate temporary file for managed destination: {destination}")

            offset = 0
            while offset < len(data):
                try:
                    written = os.write(descriptor, data[offset:])
                except InterruptedError:
                    continue
                if written <= 0:
                    _fail(f"short write for managed destination: {destination}")
                offset += written
            os.fchmod(descriptor, 0o644)
            os.fsync(descriptor)
            os.close(descriptor)
            descriptor = None
            os.replace(
                temporary_name,
                leaf,
                src_dir_fd=parent_fd,
                dst_dir_fd=parent_fd,
            )
            temporary_name = None
            os.fsync(parent_fd)
        except OSError as exc:
            _fail(f"cannot write managed destination {destination}: {exc}")
        finally:
            if descriptor is not None:
                os.close(descriptor)
            if temporary_name is not None:
                try:
                    os.unlink(temporary_name, dir_fd=parent_fd)
                except FileNotFoundError:
                    pass
            os.close(parent_fd)

    def unlink_regular(self, relative: PurePosixPath) -> None:
        parts = self._parts(relative)
        parent_fd = self._open_directory(
            PurePosixPath(*parts[:-1]),
            create=False,
            missing_ok=False,
            description="managed deletion parent",
        )
        if parent_fd is None:
            raise AssertionError("managed deletion parent cannot be missing")
        try:
            entry_stat = os.stat(parts[-1], dir_fd=parent_fd, follow_symlinks=False)
            if not stat.S_ISREG(entry_stat.st_mode):
                _fail(f"refusing to delete non-regular managed output: {relative}")
            os.unlink(parts[-1], dir_fd=parent_fd)
        except FileNotFoundError:
            return
        except OSError as exc:
            _fail(f"cannot delete extraneous managed output {relative}: {exc}")
        finally:
            os.close(parent_fd)

    def rmdir_if_empty(self, relative: PurePosixPath) -> None:
        parts = self._parts(relative)
        parent_fd = self._open_directory(
            PurePosixPath(*parts[:-1]),
            create=False,
            missing_ok=True,
            description="managed directory parent",
        )
        if parent_fd is None:
            return
        try:
            entry_stat = os.stat(parts[-1], dir_fd=parent_fd, follow_symlinks=False)
            if stat.S_ISLNK(entry_stat.st_mode):
                _fail(f"managed output contains a symlink: {relative}")
            if not stat.S_ISDIR(entry_stat.st_mode):
                return
            try:
                os.rmdir(parts[-1], dir_fd=parent_fd)
            except OSError as exc:
                if exc.errno not in {errno.ENOTEMPTY, errno.EEXIST}:
                    raise
        except FileNotFoundError:
            return
        except OSError as exc:
            _fail(f"cannot remove empty managed directory {relative}: {exc}")
        finally:
            os.close(parent_fd)


def _expected_under(root: PurePosixPath) -> set[PurePosixPath]:
    return {entry.destination_path for entry in VENDORED_FILES if entry.destination_path.is_relative_to(root)}


def _remove_extraneous_outputs(repository: _ManagedRepository) -> None:
    for root in (GENERATED_SOURCE_ROOT, GENERATED_LICENSE_ROOT):
        expected = _expected_under(root)
        actual, directories = repository.scan_tree(root)
        for relative in sorted(actual - expected):
            repository.unlink_regular(relative)
        for directory in sorted(directories, key=lambda path: len(path.parts), reverse=True):
            repository.rmdir_if_empty(directory)


def _paths_intersect(first: Path, second: Path) -> bool:
    return first == second or first.is_relative_to(second) or second.is_relative_to(first)


def _reject_checkout_output_overlap(repo_root: Path, checkout: Path) -> None:
    managed_paths = {
        repo_root / GENERATED_SOURCE_ROOT,
        repo_root / GENERATED_LICENSE_ROOT,
        repo_root / MANIFEST_PATH,
        *(repo_root / entry.destination_path for entry in VENDORED_FILES),
    }
    for managed_path in managed_paths:
        if _paths_intersect(checkout, managed_path):
            _fail(
                "upstream checkout intersects managed output and refresh would be destructive: "
                f"{checkout} <-> {managed_path}"
            )


def refresh(repo_root: Path, checkout: Path, pin: UpstreamPin = PINNED_UPSTREAM) -> None:
    """Regenerate all managed files from a proven, clean pinned checkout."""

    repository = _ManagedRepository(repo_root)
    checkout = verify_checkout(checkout, pin)
    _reject_checkout_output_overlap(repository.root_path, checkout)
    source_bytes = _read_pinned_source_bytes(checkout, pin)
    manifest = _build_manifest(source_bytes, pin)

    with repository:
        _remove_extraneous_outputs(repository)
        for entry in VENDORED_FILES:
            repository.atomic_write(entry.destination_path, source_bytes[entry.source_path])
        repository.atomic_write(MANIFEST_PATH, _manifest_bytes(manifest))
        _check_managed(repository, pin)


def _load_manifest(raw: bytes) -> dict[str, Any]:
    try:
        text = raw.decode("utf-8")
        manifest = json.loads(text, object_pairs_hook=_strict_object)
    except UnicodeDecodeError as exc:
        _fail(f"manifest is not UTF-8: {exc}")
    except json.JSONDecodeError as exc:
        _fail(f"manifest is not valid JSON: {exc}")
    if not isinstance(manifest, dict):
        _fail("manifest root must be an object")
    return manifest


def _require_keys(value: dict[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    if actual != expected:
        _fail(f"{context} keys differ: expected {sorted(expected)}, found {sorted(actual)}")


def _validate_manifest(manifest: dict[str, Any], pin: UpstreamPin) -> list[dict[str, Any]]:
    _require_keys(manifest, _MANIFEST_TOP_LEVEL_KEYS, "manifest")
    if type(manifest["formatVersion"]) is not int or manifest["formatVersion"] != MANIFEST_VERSION:
        _fail(f"unsupported manifest formatVersion: {manifest['formatVersion']!r}")

    component = manifest["component"]
    if not isinstance(component, dict):
        _fail("manifest component must be an object")
    _require_keys(component, _MANIFEST_COMPONENT_KEYS, "manifest component")
    expected_component = {
        "commit": pin.commit,
        "name": "AnkiDroid API",
        "repository": pin.repository,
        "tag": pin.tag,
    }
    if component != expected_component:
        _fail("manifest component does not match the pinned AnkiDroid API source")

    files = manifest["files"]
    if not isinstance(files, list):
        _fail("manifest files must be an array")
    if len(files) != len(VENDORED_FILES):
        _fail(f"manifest must contain exactly {len(VENDORED_FILES)} files")

    validated: list[dict[str, Any]] = []
    for index, (raw_entry, expected) in enumerate(zip(files, VENDORED_FILES, strict=True)):
        if not isinstance(raw_entry, dict):
            _fail(f"manifest files[{index}] must be an object")
        _require_keys(raw_entry, _MANIFEST_FILE_KEYS, f"manifest files[{index}]")
        expected_metadata = {
            "destinationPath": expected.destination_path.as_posix(),
            "kind": expected.kind,
            "sourcePath": expected.source_path.as_posix(),
            "spdxLicense": expected.spdx_license,
        }
        for key, expected_value in expected_metadata.items():
            if raw_entry[key] != expected_value:
                _fail(f"manifest files[{index}].{key} does not match the pinned file set")
        byte_count = raw_entry["byteCount"]
        sha256 = raw_entry["sha256"]
        if type(byte_count) is not int or byte_count < 0:
            _fail(f"manifest files[{index}].byteCount must be a non-negative integer")
        if (
            not isinstance(sha256, str)
            or len(sha256) != 64
            or any(character not in "0123456789abcdef" for character in sha256)
        ):
            _fail(f"manifest files[{index}].sha256 must be lowercase SHA-256")
        validated.append(raw_entry)
    return validated


def _check_managed(
    repository: _ManagedRepository,
    pin: UpstreamPin,
) -> dict[PurePosixPath, bytes]:
    raw_manifest = repository.read_regular(
        MANIFEST_PATH,
        label="manifest",
        max_bytes=MAX_MANIFEST_BYTES,
    )
    manifest = _load_manifest(raw_manifest)
    entries = _validate_manifest(manifest, pin)
    if raw_manifest != _manifest_bytes(manifest):
        _fail("manifest is not in canonical deterministic JSON form")

    for root in (GENERATED_SOURCE_ROOT, GENERATED_LICENSE_ROOT):
        actual, _directories = repository.scan_tree(root)
        expected = _expected_under(root)
        missing = expected - actual
        extraneous = actual - expected
        if missing:
            _fail(f"managed output is missing files: {', '.join(map(str, sorted(missing)))}")
        if extraneous:
            paths = ", ".join(map(str, sorted(extraneous)))
            _fail(f"managed output contains extraneous files: {paths}")

    generated_bytes: dict[PurePosixPath, bytes] = {}
    for entry, manifest_entry in zip(VENDORED_FILES, entries, strict=True):
        data = repository.read_regular(entry.destination_path, label="generated file")
        if len(data) != manifest_entry["byteCount"]:
            _fail(f"generated file byte count drifted: {entry.destination_path}")
        if _sha256(data) != manifest_entry["sha256"]:
            _fail(f"generated file hash drifted: {entry.destination_path}")
        generated_bytes[entry.destination_path] = data
    return generated_bytes


def check(repo_root: Path, pin: UpstreamPin = PINNED_UPSTREAM) -> None:
    """Check committed generated files only against their committed manifest."""

    with _ManagedRepository(repo_root) as repository:
        _check_managed(repository, pin)


def check_upstream(
    repo_root: Path,
    checkout: Path,
    pin: UpstreamPin = PINNED_UPSTREAM,
) -> None:
    """Prove byte equality against an independently verified pinned checkout."""

    repository = _ManagedRepository(repo_root)
    checkout = verify_checkout(checkout, pin)
    source_bytes = _read_pinned_source_bytes(checkout, pin)
    with repository:
        generated_bytes = _check_managed(repository, pin)
        for entry in VENDORED_FILES:
            if generated_bytes[entry.destination_path] != source_bytes[entry.source_path]:
                _fail(f"verified upstream bytes differ: {entry.source_path}")
