"""Android-private installation and lookup operations for external resources.

The module imports the vendored engine only inside operation functions.  This
keeps ``ANKI_MINER_HOME`` bootstrap ordering intact while reusing the desktop
Yomitan importer, registry, storage and renderer without modifying vendored
output.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
import re
import shutil
import stat
import tarfile
import threading
import zipfile
from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO

from .bootstrap import require_initialized
from .protocol import BridgeProtocolError, encode_message
from .resource_catalog import (
    UniDicResource,
    YomitanResource,
    load_resource_catalog,
)
from .tokenizer_contract import TokenizerContractError

_COPY_CHUNK_BYTES = 1024 * 1024
_MANIFEST_NAME = "install.manifest.json"
_COMPATIBILITY_MARKER_NAME = "install.complete"
_MAX_MANIFEST_BYTES = 16 * 1024
_MAX_CUSTOM_DICTIONARY_ARCHIVE_BYTES = 1024 * 1024 * 1024
_CUSTOM_ZIP_MEMBER_LIMIT = 10_000
_CUSTOM_ZIP_TOTAL_LIMIT = 1024 * 1024 * 1024
# The desktop importer materializes each JSON bank at once. Keep a hostile
# custom bank comfortably below the heap-risk range on a 3 GiB Android device.
_CUSTOM_ZIP_FILE_LIMIT = 16 * 1024 * 1024
_MAX_LOOKUP_HTML_BYTES = 2 * 1024 * 1024
_FREE_SPACE_RESERVE_BYTES = 32 * 1024 * 1024
_OPERATION_ID_RE = re.compile(r"[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?")
_SLOT_ID_RE = re.compile(
    r"(?!.*(?:\.\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?"
)
_PROMOTION_LOCK = threading.Lock()


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def _exact(payload: Mapping[str, object], keys: set[str], *, code: str) -> None:
    if set(payload) != keys:
        raise _fail(code, f"Expected payload fields: {sorted(keys)!r}")


def _bounded_text(value: object, *, name: str, max_bytes: int = 4096) -> str:
    if not isinstance(value, str) or not value:
        raise _fail("invalid_resource_request", f"{name} must be a non-empty string")
    try:
        encoded = value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise _fail("invalid_resource_request", f"{name} is not valid Unicode") from exc
    if len(encoded) > max_bytes:
        raise _fail("invalid_resource_request", f"{name} exceeds its size limit")
    return value


def _operation_id(value: object) -> str:
    candidate = _bounded_text(value, name="operationId", max_bytes=64)
    if not _OPERATION_ID_RE.fullmatch(candidate):
        raise _fail("invalid_resource_request", "operationId is invalid")
    return candidate


def _slot_id(value: object) -> str:
    candidate = _bounded_text(value, name="slotId", max_bytes=64)
    if not _SLOT_ID_RE.fullmatch(candidate):
        raise _fail("invalid_resource_request", "slotId is invalid")
    return candidate


def _absolute_path(value: object, *, name: str) -> Path:
    candidate = Path(_bounded_text(value, name=name))
    if not candidate.is_absolute():
        raise _fail("invalid_resource_path", f"{name} must be absolute")
    return candidate


@dataclass(frozen=True, slots=True)
class _ArchiveCopy:
    path: Path
    sha256: str
    size_bytes: int


class _Operation:
    def __init__(self, operation_id: str) -> None:
        self.operation_id = operation_id
        self.cancelled = threading.Event()

    def check(self) -> None:
        if self.cancelled.is_set():
            raise _fail(
                "resource_operation_cancelled", "Resource operation was cancelled"
            )


class _OperationRegistry:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active: dict[str, _Operation] = {}
        self._cleanup_active = False

    @contextlib.contextmanager
    def begin(self, operation_id: str) -> Iterator[_Operation]:
        operation = _Operation(operation_id)
        with self._lock:
            if self._cleanup_active:
                raise _fail(
                    "resource_cleanup_active",
                    "Resource cleanup is currently active",
                )
            if operation_id in self._active:
                raise _fail(
                    "resource_operation_exists",
                    "A resource operation with this id is already active",
                )
            self._active[operation_id] = operation
        try:
            yield operation
        finally:
            with self._lock:
                if self._active.get(operation_id) is operation:
                    del self._active[operation_id]

    @contextlib.contextmanager
    def exclusive_cleanup(self) -> Iterator[None]:
        with self._lock:
            if self._cleanup_active:
                raise _fail(
                    "resource_cleanup_active",
                    "Resource cleanup is already active",
                )
            if self._active:
                raise _fail(
                    "resource_operation_active",
                    "Finish or cancel active resource work before cleanup",
                )
            self._cleanup_active = True
        try:
            yield
        finally:
            with self._lock:
                self._cleanup_active = False

    def cancel(self, operation_id: str) -> bool:
        with self._lock:
            operation = self._active.get(operation_id)
            if operation is None:
                return False
            operation.cancelled.set()
            return True


_OPERATIONS = _OperationRegistry()


def _resource_work_root(home: Path) -> Path:
    return home / "resource-work"


def _dictionary_root(home: Path) -> Path:
    return home / "dicts"


def _unidic_root(home: Path, resource: UniDicResource) -> Path:
    return home / "resources" / "tokenizer" / resource.resource_id


def _fsync_directory(path: Path) -> None:
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        os.close(descriptor)


def _safe_rmtree(path: Path) -> None:
    try:
        path_stat = path.lstat()
    except FileNotFoundError:
        return
    except OSError as exc:
        raise _fail(
            "resource_cleanup_failed", "Cannot inspect resource staging"
        ) from exc
    if stat.S_ISLNK(path_stat.st_mode):
        try:
            path.unlink()
        except OSError as exc:
            raise _fail(
                "resource_cleanup_failed", "Cannot remove resource staging link"
            ) from exc
        return
    if not stat.S_ISDIR(path_stat.st_mode):
        raise _fail(
            "resource_cleanup_failed", "Resource staging path is not a directory"
        )
    try:
        shutil.rmtree(path)
    except OSError as exc:
        raise _fail(
            "resource_cleanup_failed", "Cannot remove resource staging"
        ) from exc


def _canonical_json_bytes(value: Mapping[str, object]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _write_all(stream: BinaryIO, content: bytes) -> None:
    view = memoryview(content)
    while view:
        written = stream.write(view)
        if written is None or written <= 0:
            raise OSError("short write")
        view = view[written:]


def _write_file(path: Path, content: bytes) -> None:
    try:
        with path.open("xb", buffering=0) as stream:
            _write_all(stream, content)
            os.fsync(stream.fileno())
    except OSError as exc:
        raise _fail(
            "resource_install_failed", "Cannot write resource completion metadata"
        ) from exc


def _unidic_manifest(resource: UniDicResource) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "resourceId": resource.resource_id,
        "archiveSha256": resource.archive.sha256,
        "archiveSizeBytes": resource.archive.size_bytes,
        "treeSha256": resource.install.tree_sha256,
        "treeSizeBytes": resource.install.size_bytes,
        "fileCount": resource.install.file_count,
    }


def _compatibility_marker(resource: UniDicResource) -> bytes:
    return (
        "anki-miner-tokenizer-v1\n"
        f"resourceId={resource.resource_id}\n"
        f"treeSha256={resource.install.tree_sha256}\n"
    ).encode("utf-8")


def _valid_unidic_install(root: Path, resource: UniDicResource) -> bool:
    try:
        root_stat = root.lstat()
        dicdir = root / "dicdir"
        manifest = root / _MANIFEST_NAME
        marker = root / _COMPATIBILITY_MARKER_NAME
        if (
            stat.S_ISLNK(root_stat.st_mode)
            or not stat.S_ISDIR(root_stat.st_mode)
            or not dicdir.is_dir()
            or dicdir.is_symlink()
            or not manifest.is_file()
            or manifest.is_symlink()
            or manifest.stat().st_size > _MAX_MANIFEST_BYTES
            or not marker.is_file()
            or marker.is_symlink()
            or marker.stat().st_size > 512
        ):
            return False
        parsed = json.loads(manifest.read_text(encoding="utf-8"))
        if parsed != _unidic_manifest(
            resource
        ) or marker.read_bytes() != _compatibility_marker(resource):
            return False

        entries = list(dicdir.iterdir())
        if (
            len(entries) != resource.install.file_count
            or any(entry.is_symlink() or not entry.is_file() for entry in entries)
            or sum(entry.stat().st_size for entry in entries)
            != resource.install.size_bytes
        ):
            return False

        # Completion metadata proves publication ordering, not that the tree has
        # remained intact. Hash the private install before treating it as
        # idempotently complete so setup can repair truncated or changed files.
        from .unidic_resource import calculate_unidic_tree_sha256

        return (
            calculate_unidic_tree_sha256(dicdir)
            == resource.install.tree_sha256
        )
    except (
        OSError,
        UnicodeError,
        json.JSONDecodeError,
        TokenizerContractError,
        TypeError,
        ValueError,
    ):
        return False


def _check_free_space(parent: Path, required_bytes: int) -> None:
    parent.mkdir(parents=True, exist_ok=True)
    try:
        available = shutil.disk_usage(parent).free
    except OSError as exc:
        raise _fail(
            "resource_space_unknown", "Cannot determine available storage"
        ) from exc
    if available < required_bytes + _FREE_SPACE_RESERVE_BYTES:
        raise _fail(
            "insufficient_storage", "Not enough free space for this resource operation"
        )


@contextlib.contextmanager
def _open_source(path: Path) -> Iterator[tuple[BinaryIO, os.stat_result]]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        scanned = path.lstat()
        if stat.S_ISLNK(scanned.st_mode) or not stat.S_ISREG(scanned.st_mode):
            raise _fail(
                "invalid_resource_path", "Resource source must be a regular file"
            )
        descriptor = os.open(path, flags)
    except BridgeProtocolError:
        raise
    except OSError as exc:
        raise _fail("invalid_resource_path", "Cannot open resource source") from exc
    stream = os.fdopen(descriptor, "rb", buffering=0)
    try:
        opened = os.fstat(stream.fileno())
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != scanned.st_dev
            or opened.st_ino != scanned.st_ino
            or opened.st_size != scanned.st_size
        ):
            raise _fail(
                "resource_source_changed", "Resource source changed while opening"
            )
        yield stream, opened
        final = os.fstat(stream.fileno())
        if (
            final.st_dev != opened.st_dev
            or final.st_ino != opened.st_ino
            or final.st_size != opened.st_size
            or final.st_mtime_ns != opened.st_mtime_ns
            or final.st_ctime_ns != opened.st_ctime_ns
        ):
            raise _fail(
                "resource_source_changed", "Resource source changed while reading"
            )
    finally:
        stream.close()


def _copy_archive(
    source: Path,
    destination: Path,
    operation: _Operation,
    *,
    maximum_bytes: int,
    expected_size: int | None = None,
    expected_sha256: str | None = None,
) -> _ArchiveCopy:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)
    digest = hashlib.sha256()
    copied = 0
    try:
        with _open_source(source) as (input_stream, source_stat):
            if source_stat.st_size <= 0 or source_stat.st_size > maximum_bytes:
                raise _fail(
                    "resource_archive_too_large",
                    "Resource archive size is outside its limit",
                )
            if expected_size is not None and source_stat.st_size != expected_size:
                raise _fail(
                    "resource_archive_mismatch",
                    "Resource archive size does not match the catalog",
                )
            _check_free_space(destination.parent, source_stat.st_size)
            with destination.open("xb", buffering=0) as output_stream:
                while True:
                    operation.check()
                    chunk = input_stream.read(_COPY_CHUNK_BYTES)
                    if not chunk:
                        break
                    copied += len(chunk)
                    if copied > maximum_bytes:
                        raise _fail(
                            "resource_archive_too_large",
                            "Resource archive exceeds its limit",
                        )
                    _write_all(output_stream, chunk)
                    digest.update(chunk)
                os.fsync(output_stream.fileno())
        actual_hash = digest.hexdigest()
        if copied <= 0 or (expected_size is not None and copied != expected_size):
            raise _fail(
                "resource_archive_mismatch",
                "Resource archive length changed while copying",
            )
        if expected_sha256 is not None and actual_hash != expected_sha256:
            raise _fail(
                "resource_archive_mismatch",
                "Resource archive hash does not match the catalog",
            )
        destination.chmod(0o400)
        _fsync_directory(destination.parent)
        return _ArchiveCopy(destination, actual_hash, copied)
    except Exception:
        destination.unlink(missing_ok=True)
        raise


def _safe_archive_path(name: str, *, allow_directory_suffix: bool) -> tuple[str, ...]:
    try:
        name.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise _fail(
            "unsafe_resource_archive", "Archive path is not valid Unicode"
        ) from exc
    if (
        not name
        or "\\" in name
        or "\x00" in name
        or name.startswith("/")
        or name.startswith("./")
        or len(name.encode("utf-8")) > 1024
    ):
        raise _fail("unsafe_resource_archive", "Archive contains an unsafe path")
    normalized = name[:-1] if allow_directory_suffix and name.endswith("/") else name
    path = PurePosixPath(normalized)
    if (
        not normalized
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
        or (path.parts and ":" in path.parts[0])
        or any(len(part.encode("utf-8")) > 255 for part in path.parts)
    ):
        raise _fail("unsafe_resource_archive", "Archive contains an unsafe path")
    return path.parts


def _extract_unidic(
    archive: Path,
    staging: Path,
    resource: UniDicResource,
    operation: _Operation,
) -> None:
    install = resource.install
    dicdir = staging / "dicdir"
    dicdir.mkdir(parents=True)
    prefix_parts = PurePosixPath(install.member_prefix).parts
    archive_root = prefix_parts[0]
    seen: set[tuple[str, ...]] = set()
    selected: set[tuple[str, ...]] = set()
    archive_members = 0
    selected_bytes = 0
    all_regular_bytes = 0
    try:
        with tarfile.open(archive, mode="r|gz") as source:
            for member in source:
                operation.check()
                archive_members += 1
                if archive_members > install.archive_member_limit:
                    raise _fail(
                        "resource_archive_too_large",
                        "UniDic archive has too many members",
                    )
                parts = _safe_archive_path(member.name, allow_directory_suffix=False)
                if parts in seen:
                    raise _fail(
                        "unsafe_resource_archive",
                        "UniDic archive contains a duplicate path",
                    )
                seen.add(parts)
                if parts[0] != archive_root:
                    raise _fail(
                        "unsafe_resource_archive",
                        "UniDic archive contains an unexpected root",
                    )
                if member.isdir():
                    continue
                if not member.isreg():
                    raise _fail(
                        "unsafe_resource_archive",
                        "UniDic archive contains a link or special file",
                    )
                if member.size < 0:
                    raise _fail(
                        "unsafe_resource_archive",
                        "UniDic archive contains an invalid file size",
                    )
                all_regular_bytes += member.size
                if all_regular_bytes > install.size_bytes + 32 * 1024 * 1024:
                    raise _fail(
                        "resource_archive_too_large",
                        "UniDic archive expands beyond its limit",
                    )
                if (
                    len(parts) <= len(prefix_parts)
                    or parts[: len(prefix_parts)] != prefix_parts
                ):
                    continue
                relative = parts[len(prefix_parts) :]
                if len(relative) != 1:
                    raise _fail(
                        "unsafe_resource_archive",
                        "UniDic dictionary has an unexpected layout",
                    )
                if relative in selected:
                    raise _fail(
                        "unsafe_resource_archive",
                        "UniDic archive contains a duplicate dictionary file",
                    )
                selected.add(relative)
                if len(selected) > install.file_count:
                    raise _fail(
                        "resource_archive_too_large",
                        "UniDic archive has too many dictionary files",
                    )
                selected_bytes += member.size
                if selected_bytes > install.size_bytes:
                    raise _fail(
                        "resource_archive_too_large",
                        "UniDic dictionary exceeds its size limit",
                    )
                extracted = source.extractfile(member)
                if extracted is None:
                    raise _fail("unsafe_resource_archive", "UniDic file cannot be read")
                destination = dicdir / relative[0]
                written = 0
                with extracted, destination.open("xb", buffering=0) as output:
                    while True:
                        operation.check()
                        chunk = extracted.read(_COPY_CHUNK_BYTES)
                        if not chunk:
                            break
                        written += len(chunk)
                        if (
                            written > member.size
                            or selected_bytes - member.size + written
                            > install.size_bytes
                        ):
                            raise _fail(
                                "resource_archive_too_large",
                                "UniDic file exceeds its declared limit",
                            )
                        _write_all(output, chunk)
                    os.fsync(output.fileno())
                if written != member.size:
                    raise _fail(
                        "resource_archive_mismatch",
                        "UniDic member length is inconsistent",
                    )
    except (tarfile.TarError, EOFError, OSError) as exc:
        raise _fail("invalid_resource_archive", "UniDic archive is corrupt") from exc

    if len(selected) != install.file_count or selected_bytes != install.size_bytes:
        raise _fail(
            "resource_archive_mismatch",
            "UniDic extracted tree shape does not match the catalog",
        )

    # Function-local import is load-bearing: bridge bootstrap must establish HOME first.
    from .unidic_resource import calculate_unidic_tree_sha256

    if calculate_unidic_tree_sha256(dicdir) != install.tree_sha256:
        raise _fail(
            "resource_archive_mismatch",
            "UniDic extracted tree hash does not match the catalog",
        )
    _fsync_directory(dicdir)
    _write_file(staging / _COMPATIBILITY_MARKER_NAME, _compatibility_marker(resource))
    # The strict completion manifest is written last while the staging directory
    # is still unpublished. The final rename makes the whole valid tree visible.
    _write_file(
        staging / _MANIFEST_NAME, _canonical_json_bytes(_unidic_manifest(resource))
    )
    _fsync_directory(staging)


def _recover_unidic(parent: Path, final: Path, resource: UniDicResource) -> None:
    parent.mkdir(parents=True, exist_ok=True)
    backups = sorted(parent.glob(f".backup-{resource.resource_id}-*"))
    if _valid_unidic_install(final, resource):
        for backup in backups:
            _safe_rmtree(backup)
        return
    valid_backups = [
        backup for backup in backups if _valid_unidic_install(backup, resource)
    ]
    if final.exists() and valid_backups:
        _safe_rmtree(final)
    if not final.exists() and valid_backups:
        chosen = valid_backups[-1]
        chosen.rename(final)
        _fsync_directory(parent)
    for backup in backups:
        if backup.exists():
            _safe_rmtree(backup)


def _publish_unidic(
    staging: Path,
    final: Path,
    resource: UniDicResource,
    operation_id: str,
) -> None:
    parent = final.parent
    backup = parent / f".backup-{resource.resource_id}-{operation_id}"
    with _PROMOTION_LOCK:
        _recover_unidic(parent, final, resource)
        if _valid_unidic_install(final, resource):
            _safe_rmtree(staging)
            return
        if final.exists():
            backup.unlink(missing_ok=True) if backup.is_symlink() else None
            if backup.exists():
                _safe_rmtree(backup)
            final.rename(backup)
            _fsync_directory(parent)
        try:
            staging.rename(final)
            _fsync_directory(parent)
        except Exception:
            if final.exists():
                _safe_rmtree(final)
            if backup.exists():
                backup.rename(final)
                _fsync_directory(parent)
            raise
        if backup.exists():
            _safe_rmtree(backup)


def install_unidic(payload: Mapping[str, object]) -> str:
    _exact(
        payload,
        {"operationId", "resourceId", "archivePath"},
        code="invalid_resource_request",
    )
    operation_id = _operation_id(payload["operationId"])
    resource_id = _bounded_text(payload["resourceId"], name="resourceId", max_bytes=64)
    resource = load_resource_catalog().get(resource_id)
    if not isinstance(resource, UniDicResource):
        raise _fail("invalid_resource_kind", "Requested resource is not UniDic")
    source = _absolute_path(payload["archivePath"], name="archivePath")
    home = Path(require_initialized())
    final = _unidic_root(home, resource)
    parent = final.parent
    with _OPERATIONS.begin(operation_id) as operation:
        with _PROMOTION_LOCK:
            _recover_unidic(parent, final, resource)
            if _valid_unidic_install(final, resource):
                return encode_message(
                    "resource.unidic.installed",
                    {
                        "resourceId": resource.resource_id,
                        "dicDir": str(final / "dicdir"),
                        "treeSha256": resource.install.tree_sha256,
                        "fileCount": resource.install.file_count,
                        "sizeBytes": resource.install.size_bytes,
                        "alreadyInstalled": True,
                        "attribution": [
                            item.payload() for item in resource.attribution
                        ],
                    },
                )
        operation_root = _resource_work_root(home) / "operations" / operation_id
        _safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        staging = parent / f".installing-{resource.resource_id}-{operation_id}"
        try:
            _safe_rmtree(staging)
            # The verified archive and extracted tree coexist until publication.
            _check_free_space(
                parent,
                resource.archive.size_bytes + resource.install.size_bytes,
            )
            copied = _copy_archive(
                source,
                operation_root / "unidic.tar.gz",
                operation,
                maximum_bytes=resource.archive.size_bytes,
                expected_size=resource.archive.size_bytes,
                expected_sha256=resource.archive.sha256,
            )
            staging.mkdir(parents=True)
            _extract_unidic(copied.path, staging, resource, operation)
            operation.check()
            _publish_unidic(staging, final, resource, operation_id)
        finally:
            if staging.exists():
                _safe_rmtree(staging)
            if operation_root.exists():
                _safe_rmtree(operation_root)
        return encode_message(
            "resource.unidic.installed",
            {
                "resourceId": resource.resource_id,
                "dicDir": str(final / "dicdir"),
                "treeSha256": resource.install.tree_sha256,
                "fileCount": resource.install.file_count,
                "sizeBytes": resource.install.size_bytes,
                "alreadyInstalled": False,
                "attribution": [item.payload() for item in resource.attribution],
            },
        )


@dataclass(frozen=True, slots=True)
class _ZipIdentity:
    member_count: int
    uncompressed_bytes: int


def _zip_entry_is_safe_type(info: zipfile.ZipInfo) -> bool:
    if info.is_dir():
        return True
    mode = (info.external_attr >> 16) & 0xFFFF
    file_type = stat.S_IFMT(mode)
    return file_type in {0, stat.S_IFREG}


def _validate_zip_streamed(
    path: Path,
    operation: _Operation,
    *,
    member_limit: int,
    total_limit: int,
    file_limit: int,
) -> _ZipIdentity:
    try:
        with zipfile.ZipFile(path, "r") as archive:
            infos = archive.infolist()
            if not infos or len(infos) > member_limit:
                raise _fail(
                    "resource_archive_too_large",
                    "Dictionary archive member count is outside its limit",
                )
            seen: set[tuple[str, ...]] = set()
            total = 0
            has_root_index = False
            for info in infos:
                operation.check()
                parts = _safe_archive_path(
                    info.filename, allow_directory_suffix=info.is_dir()
                )
                if parts in seen:
                    raise _fail(
                        "unsafe_resource_archive",
                        "Dictionary archive contains a duplicate path",
                    )
                seen.add(parts)
                if info.flag_bits & 0x1:
                    raise _fail(
                        "unsafe_resource_archive",
                        "Encrypted dictionary archives are unsupported",
                    )
                if not _zip_entry_is_safe_type(info):
                    raise _fail(
                        "unsafe_resource_archive",
                        "Dictionary archive contains a link or special file",
                    )
                if info.is_dir():
                    continue
                if info.file_size < 0 or info.file_size > file_limit:
                    raise _fail(
                        "resource_archive_too_large",
                        "Dictionary archive contains an oversized file",
                    )
                total += info.file_size
                if total > total_limit:
                    raise _fail(
                        "resource_archive_too_large",
                        "Dictionary archive expands beyond its limit",
                    )
                if parts == ("index.json",):
                    has_root_index = True
                actual = 0
                with archive.open(info, "r") as stream:
                    while True:
                        operation.check()
                        chunk = stream.read(_COPY_CHUNK_BYTES)
                        if not chunk:
                            break
                        actual += len(chunk)
                        if actual > info.file_size or actual > file_limit:
                            raise _fail(
                                "resource_archive_too_large",
                                "Dictionary file exceeds its declared limit",
                            )
                if actual != info.file_size:
                    raise _fail(
                        "invalid_resource_archive",
                        "Dictionary member length is inconsistent",
                    )
            if not has_root_index:
                raise _fail(
                    "invalid_resource_archive",
                    "Dictionary archive has no root index.json",
                )
            return _ZipIdentity(len(infos), total)
    except BridgeProtocolError:
        raise
    except (zipfile.BadZipFile, RuntimeError, OSError, EOFError) as exc:
        raise _fail(
            "invalid_resource_archive", "Dictionary archive is corrupt"
        ) from exc


def _dictionary_sidecar(
    *,
    slot_id: str,
    archive: _ArchiveCopy,
    catalog_resource: YomitanResource | None,
    source_name: str,
    source_revision: str,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "slotId": slot_id,
        "archiveSha256": archive.sha256,
        "archiveSizeBytes": archive.size_bytes,
        "catalogResourceId": catalog_resource.resource_id if catalog_resource else None,
        "sourceName": source_name,
        "sourceRevision": source_revision,
        "attribution": (
            [item.payload() for item in catalog_resource.attribution]
            if catalog_resource
            else []
        ),
    }


def _valid_dictionary_slot(path: Path) -> bool:
    try:
        value = path.lstat()
        index = path / "index.sqlite"
        return (
            stat.S_ISDIR(value.st_mode)
            and not stat.S_ISLNK(value.st_mode)
            and index.is_file()
            and not index.is_symlink()
            and index.stat().st_size > 0
        )
    except OSError:
        return False


def _backup_root(home: Path) -> Path:
    return _resource_work_root(home) / "dictionary-backups"


def _recover_dictionary_backups(home: Path) -> None:
    backup_root = _backup_root(home)
    if not backup_root.exists():
        return
    if backup_root.is_symlink() or not backup_root.is_dir():
        raise _fail("resource_cleanup_failed", "Dictionary backup root is unsafe")
    dicts_root = _dictionary_root(home)
    dicts_root.mkdir(parents=True, exist_ok=True)
    for backup in sorted(backup_root.iterdir()):
        if (
            not backup.name.startswith("backup-")
            or not backup.is_dir()
            or backup.is_symlink()
        ):
            raise _fail("resource_cleanup_failed", "Dictionary backup entry is unsafe")
        remainder = backup.name.removeprefix("backup-")
        slot_id = remainder.split("--", 1)[0]
        if not _SLOT_ID_RE.fullmatch(slot_id):
            raise _fail("resource_cleanup_failed", "Dictionary backup slot is invalid")
        final = dicts_root / slot_id
        if _valid_dictionary_slot(final):
            _safe_rmtree(backup)
        elif _valid_dictionary_slot(backup):
            if final.exists():
                _safe_rmtree(final)
            backup.rename(final)
            _fsync_directory(dicts_root)
        else:
            _safe_rmtree(backup)


def _publish_dictionary(
    candidate: Path,
    *,
    home: Path,
    slot_id: str,
    operation_id: str,
    overwrite: bool,
) -> None:
    final_root = _dictionary_root(home)
    backup_root = _backup_root(home)
    final_root.mkdir(parents=True, exist_ok=True)
    backup_root.mkdir(parents=True, exist_ok=True)
    final = final_root / slot_id
    backup = backup_root / f"backup-{slot_id}--{operation_id}"
    with _PROMOTION_LOCK:
        _recover_dictionary_backups(home)
        if final.exists() and not overwrite:
            raise _fail(
                "resource_already_installed",
                f"Dictionary slot {slot_id!r} already exists",
            )
        if backup.exists():
            _safe_rmtree(backup)
        if final.exists():
            final.rename(backup)
            _fsync_directory(final_root)
            _fsync_directory(backup_root)
        try:
            candidate.rename(final)
            _fsync_directory(final_root)
        except Exception:
            if final.exists():
                _safe_rmtree(final)
            if backup.exists():
                backup.rename(final)
                _fsync_directory(final_root)
            raise
        if backup.exists():
            _safe_rmtree(backup)


def import_dictionary(payload: Mapping[str, object]) -> str:
    _exact(
        payload,
        {"operationId", "sourcePath", "slotId", "overwrite", "catalogResourceId"},
        code="invalid_resource_request",
    )
    operation_id = _operation_id(payload["operationId"])
    source = _absolute_path(payload["sourcePath"], name="sourcePath")
    slot_id = _slot_id(payload["slotId"])
    overwrite = payload["overwrite"]
    if not isinstance(overwrite, bool):
        raise _fail("invalid_resource_request", "overwrite must be a boolean")
    raw_catalog_id = payload["catalogResourceId"]
    catalog_resource: YomitanResource | None
    if raw_catalog_id is None:
        catalog_resource = None
    else:
        catalog_id = _bounded_text(
            raw_catalog_id, name="catalogResourceId", max_bytes=64
        )
        selected = load_resource_catalog().get(catalog_id)
        if not isinstance(selected, YomitanResource):
            raise _fail(
                "invalid_resource_kind",
                "Requested resource is not a Yomitan dictionary",
            )
        if slot_id != selected.slot_id:
            raise _fail(
                "invalid_resource_request",
                "Pinned dictionary must use its stable catalog slot",
            )
        catalog_resource = selected

    home = Path(require_initialized())
    final = _dictionary_root(home) / slot_id
    if final.exists() and not overwrite:
        raise _fail(
            "resource_already_installed", f"Dictionary slot {slot_id!r} already exists"
        )
    operation_root = _resource_work_root(home) / "operations" / operation_id
    with _OPERATIONS.begin(operation_id) as operation:
        _safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            maximum_archive = (
                catalog_resource.archive.size_bytes
                if catalog_resource
                else _MAX_CUSTOM_DICTIONARY_ARCHIVE_BYTES
            )
            copied = _copy_archive(
                source,
                operation_root / "dictionary.zip",
                operation,
                maximum_bytes=maximum_archive,
                expected_size=(
                    catalog_resource.archive.size_bytes if catalog_resource else None
                ),
                expected_sha256=(
                    catalog_resource.archive.sha256 if catalog_resource else None
                ),
            )
            identity = _validate_zip_streamed(
                copied.path,
                operation,
                member_limit=(
                    catalog_resource.dictionary.archive_member_limit
                    if catalog_resource
                    else _CUSTOM_ZIP_MEMBER_LIMIT
                ),
                total_limit=(
                    catalog_resource.dictionary.uncompressed_bytes_limit
                    if catalog_resource
                    else _CUSTOM_ZIP_TOTAL_LIMIT
                ),
                file_limit=(
                    catalog_resource.dictionary.file_bytes_limit
                    if catalog_resource
                    else _CUSTOM_ZIP_FILE_LIMIT
                ),
            )
            if catalog_resource and (
                identity.member_count != catalog_resource.dictionary.member_count
                or identity.uncompressed_bytes
                != catalog_resource.dictionary.uncompressed_bytes
            ):
                raise _fail(
                    "resource_archive_mismatch",
                    "Pinned dictionary layout differs from the catalog",
                )
            _check_free_space(
                operation_root,
                identity.uncompressed_bytes + copied.size_bytes,
            )
            import_root = operation_root / "publication"
            import_root.mkdir()

            # Function-local engine import preserves ANKI_MINER_HOME bootstrap ordering.
            from anki_miner.services.dictionary.importers.yomitan_importer import (
                import_yomitan_zip,
            )

            try:
                result = import_yomitan_zip(
                    copied.path,
                    import_root,
                    overwrite=False,
                    cancel_check=operation.cancelled.is_set,
                    dict_id=slot_id,
                )
            except Exception as exc:
                operation.check()
                from anki_miner.exceptions import SetupError

                if isinstance(exc, SetupError):
                    raise _fail(
                        "dictionary_import_failed",
                        "The selected dictionary could not be imported",
                    ) from exc
                raise
            operation.check()
            if catalog_resource and (
                result.source_name != catalog_resource.dictionary.title
                or result.source_revision != catalog_resource.dictionary.revision
            ):
                raise _fail(
                    "resource_archive_mismatch",
                    "Pinned dictionary metadata differs from the catalog",
                )
            candidate = import_root / slot_id
            sidecar = _dictionary_sidecar(
                slot_id=slot_id,
                archive=copied,
                catalog_resource=catalog_resource,
                source_name=result.source_name,
                source_revision=result.source_revision,
            )
            _write_file(
                candidate / "android-resource.json", _canonical_json_bytes(sidecar)
            )
            _fsync_directory(candidate)
            _publish_dictionary(
                candidate,
                home=home,
                slot_id=slot_id,
                operation_id=operation_id,
                overwrite=overwrite,
            )
            return encode_message(
                "resource.dictionary.imported",
                {
                    "slotId": slot_id,
                    "catalogResourceId": (
                        catalog_resource.resource_id if catalog_resource else None
                    ),
                    "sourceName": result.source_name,
                    "sourceRevision": result.source_revision,
                    "entryCount": result.entry_count,
                    "skippedMalformed": result.skipped_malformed,
                    "mediaWarnings": list(result.media_warnings),
                    "archiveSha256": copied.sha256,
                    "attribution": (
                        [item.payload() for item in catalog_resource.attribution]
                        if catalog_resource
                        else []
                    ),
                },
            )
        finally:
            if operation_root.exists():
                _safe_rmtree(operation_root)


def _read_dictionary_sidecar(slot: Path) -> dict[str, Any] | None:
    sidecar = slot / "android-resource.json"
    try:
        if (
            not sidecar.is_file()
            or sidecar.is_symlink()
            or sidecar.stat().st_size > _MAX_MANIFEST_BYTES
        ):
            return None
        value = json.loads(sidecar.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else None
    except (OSError, UnicodeError, json.JSONDecodeError, TypeError, ValueError):
        return None


def _dictionary_payload(meta: object, slot: Path) -> dict[str, object]:
    # Function-local storage import preserves bootstrap ordering.
    from anki_miner.services.dictionary.storage import read_meta_cached

    values = read_meta_cached(meta.db_path)
    sidecar = _read_dictionary_sidecar(slot)
    catalog_attribution = sidecar.get("attribution", []) if sidecar else []
    if not isinstance(catalog_attribution, list):
        catalog_attribution = []
    embedded = {
        key: values[key]
        for key in ("author", "attribution", "description")
        if isinstance(values.get(key), str) and values[key]
    }
    return {
        "slotId": meta.dict_id,
        "sourceName": meta.source_name,
        "sourceRevision": values.get("source_revision", ""),
        "format": meta.format,
        "entryCount": meta.entry_count,
        "schemaOk": meta.schema_ok,
        "embeddedAttribution": embedded,
        "catalogResourceId": sidecar.get("catalogResourceId") if sidecar else None,
        "attribution": catalog_attribution,
    }


def list_dictionaries(payload: Mapping[str, object]) -> str:
    _exact(payload, set(), code="invalid_resource_request")
    home = Path(require_initialized())
    with _PROMOTION_LOCK:
        _recover_dictionary_backups(home)
    root = _dictionary_root(home)
    if not root.exists():
        return encode_message("resource.dictionary.listed", {"dictionaries": []})

    # Function-local registry import preserves bootstrap ordering.
    from anki_miner.services.dictionary.registry import DictionaryRegistry

    registry = DictionaryRegistry(root)
    registry.load()
    dictionaries: list[dict[str, object]] = []
    for slot in sorted(root.iterdir(), key=lambda item: item.name):
        if (
            not _SLOT_ID_RE.fullmatch(slot.name)
            or not slot.is_dir()
            or slot.is_symlink()
        ):
            continue
        meta = registry.get(slot.name)
        if meta is not None:
            dictionaries.append(_dictionary_payload(meta, slot))
    return encode_message("resource.dictionary.listed", {"dictionaries": dictionaries})


def _validate_lookup_html(html: str) -> None:
    try:
        html_size = len(html.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise _fail(
            "dictionary_result_invalid", "Dictionary lookup returned invalid text"
        ) from exc
    if html_size > _MAX_LOOKUP_HTML_BYTES:
        raise _fail(
            "dictionary_result_too_large",
            "Dictionary lookup result exceeds the Android display limit",
        )


def lookup_dictionary(payload: Mapping[str, object]) -> str:
    _exact(payload, {"slotId", "term"}, code="invalid_resource_request")
    slot_id = _slot_id(payload["slotId"])
    term = _bounded_text(payload["term"], name="term", max_bytes=1024)
    if not term.strip():
        raise _fail("invalid_resource_request", "term must not be blank")
    home = Path(require_initialized())
    root = _dictionary_root(home)

    # Function-local imports preserve bootstrap ordering.
    from anki_miner.services.dictionary.providers.indexed_provider import (
        IndexedDictProvider,
    )
    from anki_miner.services.dictionary.registry import DictionaryRegistry

    registry = DictionaryRegistry(root)
    registry.load()
    meta = registry.get(slot_id)
    if meta is None:
        raise _fail(
            "dictionary_not_found", f"Dictionary slot {slot_id!r} is not installed"
        )
    if not meta.schema_ok:
        raise _fail("dictionary_schema_mismatch", "Dictionary must be reimported")
    provider = IndexedDictProvider(meta.dict_id, meta.db_path, meta.source_name)
    if not provider.load():
        raise _fail("dictionary_unavailable", "Dictionary index cannot be opened")
    try:
        html = provider.lookup(term)
    finally:
        provider.close()
    _validate_lookup_html(html)
    return encode_message(
        "resource.dictionary.lookup.result",
        {"slotId": slot_id, "term": term, "html": html},
    )


def catalog_response(payload: Mapping[str, object]) -> str:
    _exact(payload, set(), code="invalid_resource_request")
    return encode_message("resource.catalog", load_resource_catalog().payload())


def cancel_operation(payload: Mapping[str, object]) -> str:
    _exact(payload, {"operationId"}, code="invalid_resource_request")
    operation_id = _operation_id(payload["operationId"])
    return encode_message(
        "resource.operation.cancel.result",
        {"operationId": operation_id, "accepted": _OPERATIONS.cancel(operation_id)},
    )


def cleanup_resources(payload: Mapping[str, object]) -> str:
    _exact(payload, set(), code="invalid_resource_request")
    home = Path(require_initialized())
    # Excluding new operations for the full cleanup window prevents the
    # control executor from deleting a live copy, extraction, or import tree.
    with _OPERATIONS.exclusive_cleanup(), _PROMOTION_LOCK:
        _recover_dictionary_backups(home)
        operations = _resource_work_root(home) / "operations"
        if operations.exists():
            if operations.is_symlink() or not operations.is_dir():
                raise _fail(
                    "resource_cleanup_failed", "Resource operation root is unsafe"
                )
            for child in list(operations.iterdir()):
                _safe_rmtree(child)
        catalog = load_resource_catalog()
        for item in catalog.resources:
            if not isinstance(item, UniDicResource):
                continue
            final = _unidic_root(home, item)
            _recover_unidic(final.parent, final, item)
            for staging in list(final.parent.glob(f".installing-{item.resource_id}-*")):
                _safe_rmtree(staging)
    return encode_message("resource.cleanup.result", {"clean": True})
