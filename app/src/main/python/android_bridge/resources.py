"""Android-private installation and lookup operations for external resources.

The module imports the vendored engine only inside operation functions.  This
keeps ``ANKI_MINER_HOME`` bootstrap ordering intact while reusing the desktop
Yomitan importer, registry, storage and renderer without modifying vendored
output.
"""

from __future__ import annotations

import codecs
import contextlib
import errno
import hashlib
import json
import logging
import os
import re
import shutil
import sqlite3
import stat
import struct
import tarfile
import threading
import unicodedata
import zipfile
from collections import OrderedDict
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

logger = logging.getLogger(__name__)

_COPY_CHUNK_BYTES = 1024 * 1024
_MANIFEST_NAME = "install.manifest.json"
_COMPATIBILITY_MARKER_NAME = "install.complete"
_MAX_MANIFEST_BYTES = 16 * 1024
_MAX_CUSTOM_DICTIONARY_ARCHIVE_BYTES = 1024 * 1024 * 1024
_MAX_YOMITAN_INDEX_BYTES = 8 * 1024 * 1024
_MAX_DICTIONARY_METADATA_BYTES = 4096
# The _SLOT_ID_RE / _bounded_text contract for a slot id. Derived slots that
# overflow it (non-ASCII titles slug to ``uXXXX`` runs) are truncated and
# suffixed with a digest of the full slug instead of being rejected.
_MAX_DERIVED_SLOT_CHARS = 64
_DERIVED_SLOT_DIGEST_CHARS = 8
# Anti-DoS backstop for archives whose catalog entry declares no member limit,
# NOT a product limit: it exists only so a hostile central directory cannot make
# zipfile materialise an unbounded ZipInfo list. Real Yomitan dictionaries are
# orders of magnitude below this, including media-bearing ones, so no archive a
# user can import today is refused by it. A catalog-declared limit still wins.
_MAX_CUSTOM_ZIP_MEMBERS = 65_536
# Bound ZipFile's eager central-directory allocation independently of entry count.
_MAX_CUSTOM_ZIP_CENTRAL_DIRECTORY_BYTES = 32 * 1024 * 1024
_MAX_LOOKUP_HTML_BYTES = 2 * 1024 * 1024
_MAX_DICTIONARY_SLOTS = 128
_FREE_SPACE_RESERVE_BYTES = 32 * 1024 * 1024
_MAX_PENDING_RESOURCE_CANCELLATIONS = 256
# Every engine registry compares an on-disk index against its own
# SCHEMA_VERSION with exact equality, so these are equalities too, never
# ranges. A contract test binds each one to the vendored constant; a re-pin
# that moves a schema is otherwise silent, because the source keeps listing
# itself while the engine quietly refuses to load it.
_DICTIONARY_SCHEMA_VERSION = 6
_FREQUENCY_SCHEMA_VERSION = 3
_PITCH_SCHEMA_VERSION = 3
_AUDIO_PACK_SCHEMA_VERSION = 2
_OPERATION_ID_RE = re.compile(r"[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?")
_SLOT_ID_RE = re.compile(r"(?!.*(?:\.\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
_SHA256_RE = re.compile(r"[0-9a-f]{64}")
_YOMITAN_BANK_RE = re.compile(r"^(term_bank|term_meta_bank|tag_bank)_[^/]*\.json$")
_PROMOTION_LOCK = threading.Lock()
_YOMITAN_BANK_CHUNK_BYTES = 4 * 1024 * 1024
# Characters that can extend a JSON number literal. A number is the only token
# ``raw_decode`` returns truncated rather than raising, so the streaming reader
# has to recognise a literal cut by a refill boundary itself.
_JSON_NUMBER_CONTINUATION = frozenset("0123456789.eE+-")
# Largest single Yomitan bank the desktop importer may read whole. Its bank path
# is ``read_text`` + ``json.loads``, so an unbounded bank would materialise as
# one Python object; ``_rewrite_yomitan_banks`` exists to split those. Real
# dictionaries sit far below this — the catalog imposes the same 16 MiB as its
# per-file ``fileBytesLimit`` — so gating the rewrite on it skips a full expand,
# re-encode and re-compress of every archive a user actually imports.
_YOMITAN_BANK_INLINE_LIMIT_BYTES = 16 * 1024 * 1024
_STORAGE_EXHAUSTION_ERRNOS = frozenset(
    {
        errno.ENOSPC,
        getattr(errno, "EDQUOT", errno.ENOSPC),
    }
)


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
            raise _fail("resource_operation_cancelled", "Resource operation was cancelled")


class _OperationRegistry:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active: dict[str, _Operation] = {}
        self._pending_cancellations: OrderedDict[str, None] = OrderedDict()
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
            if operation_id in self._pending_cancellations:
                del self._pending_cancellations[operation_id]
                operation.cancelled.set()
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
            if operation is not None:
                operation.cancelled.set()
                return True
            # Cancellation is sticky across the control/worker registration race.
            # Opaque IDs are single-use; the bounded FIFO prevents hostile callers
            # from growing process memory with never-started operations.
            self._pending_cancellations[operation_id] = None
            self._pending_cancellations.move_to_end(operation_id)
            while len(self._pending_cancellations) > _MAX_PENDING_RESOURCE_CANCELLATIONS:
                self._pending_cancellations.popitem(last=False)
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
        logger.debug("Failed to fsync resource directory", exc_info=True)
    finally:
        os.close(descriptor)


def _safe_rmtree(path: Path) -> None:
    try:
        path_stat = path.lstat()
    except FileNotFoundError:
        return
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot inspect resource staging") from exc
    if stat.S_ISLNK(path_stat.st_mode):
        try:
            path.unlink()
        except OSError as exc:
            raise _fail("resource_cleanup_failed", "Cannot remove resource staging link") from exc
        return
    if not stat.S_ISDIR(path_stat.st_mode):
        raise _fail("resource_cleanup_failed", "Resource staging path is not a directory")
    try:
        shutil.rmtree(path)
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot remove resource staging") from exc


def _safe_remove_dictionary_entry(path: Path) -> None:
    """Remove one resolved dictionary-slot entry without following links."""

    try:
        value = path.lstat()
    except FileNotFoundError:
        return
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot inspect dictionary slot") from exc
    try:
        if stat.S_ISLNK(value.st_mode) or stat.S_ISREG(value.st_mode):
            path.unlink()
        elif stat.S_ISDIR(value.st_mode):
            shutil.rmtree(path)
        else:
            raise _fail("resource_cleanup_failed", "Dictionary slot has an unsafe type")
    except BridgeProtocolError:
        raise
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot remove dictionary slot") from exc


def _canonical_json_bytes(value: Mapping[str, object]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def _write_all(stream: BinaryIO, content: bytes) -> None:
    view = memoryview(content)
    while view:
        written = stream.write(view)
        if written is None or written <= 0:
            raise OSError("short write")
        view = view[written:]


def _is_storage_exhaustion(error: BaseException) -> bool:
    """Recognize ENOSPC/EDQUOT and SQLite's equivalent through wrapper causes."""

    pending: list[BaseException] = [error]
    seen: set[int] = set()
    while pending:
        current = pending.pop()
        if id(current) in seen:
            continue
        seen.add(id(current))
        if isinstance(current, OSError) and current.errno in _STORAGE_EXHAUSTION_ERRNOS:
            return True
        if isinstance(current, sqlite3.Error) and (
            getattr(current, "sqlite_errorcode", None) == sqlite3.SQLITE_FULL
            or "database or disk is full" in str(current).lower()
        ):
            return True
        if current.__cause__ is not None:
            pending.append(current.__cause__)
        if current.__context__ is not None:
            pending.append(current.__context__)
    return False


def _raise_if_storage_exhausted(error: BaseException) -> None:
    """Map capacity races to stable Python reason ``insufficient_storage``."""

    if _is_storage_exhaustion(error):
        raise _fail(
            "insufficient_storage",
            "Not enough free space for this resource operation",
        ) from error


def _write_file(path: Path, content: bytes) -> None:
    try:
        with path.open("xb", buffering=0) as stream:
            _write_all(stream, content)
            os.fsync(stream.fileno())
    except OSError as exc:
        _raise_if_storage_exhausted(exc)
        raise _fail("resource_install_failed", "Cannot write resource completion metadata") from exc


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
        f"anki-miner-tokenizer-v1\nresourceId={resource.resource_id}\ntreeSha256={resource.install.tree_sha256}\n"
    ).encode()


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
        if parsed != _unidic_manifest(resource) or marker.read_bytes() != _compatibility_marker(resource):
            return False

        entries = list(dicdir.iterdir())
        if (
            len(entries) != resource.install.file_count
            or any(entry.is_symlink() or not entry.is_file() for entry in entries)
            or sum(entry.stat().st_size for entry in entries) != resource.install.size_bytes
        ):
            return False

        # Completion metadata proves publication ordering, not that the tree has
        # remained intact. Hash the private install before treating it as
        # idempotently complete so setup can repair truncated or changed files.
        from .unidic_resource import calculate_unidic_tree_sha256

        return calculate_unidic_tree_sha256(dicdir) == resource.install.tree_sha256
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
        raise _fail("resource_space_unknown", "Cannot determine available storage") from exc
    if available < required_bytes + _FREE_SPACE_RESERVE_BYTES:
        raise _fail("insufficient_storage", "Not enough free space for this resource operation")


@contextlib.contextmanager
def _open_source(path: Path) -> Iterator[tuple[BinaryIO, os.stat_result]]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        scanned = path.lstat()
        if stat.S_ISLNK(scanned.st_mode) or not stat.S_ISREG(scanned.st_mode):
            raise _fail("invalid_resource_path", "Resource source must be a regular file")
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
            raise _fail("resource_source_changed", "Resource source changed while opening")
        yield stream, opened
        final = os.fstat(stream.fileno())
        if (
            final.st_dev != opened.st_dev
            or final.st_ino != opened.st_ino
            or final.st_size != opened.st_size
            or final.st_mtime_ns != opened.st_mtime_ns
            or final.st_ctime_ns != opened.st_ctime_ns
        ):
            raise _fail("resource_source_changed", "Resource source changed while reading")
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
    except Exception as exc:
        destination.unlink(missing_ok=True)
        _raise_if_storage_exhausted(exc)
        raise


def _hash_archive(
    source: Path,
    operation: _Operation,
    *,
    maximum_bytes: int,
    expected_size: int | None = None,
    expected_sha256: str | None = None,
) -> _ArchiveCopy:
    """Measure *source* in place, with the guarantees :func:`_copy_archive` gives.

    Used when the caller already owns *source* as a private staged file and only
    needs its digest and size. Copying it again would double the peak footprint
    of a multi-gigabyte import for no added safety: ``_open_source`` refuses
    symlinks and non-regular files and re-stats the descriptor afterwards, so a
    source swapped mid-read is still rejected.

    ``expected_size``/``expected_sha256`` pin the archive to its catalog entry,
    mirroring :func:`_copy_archive`. Verifying in place is equivalent to
    verifying a copy here: both hash the same bytes through ``_open_source``,
    whose closing re-stat rejects a source that changed underneath the read.
    """
    digest = hashlib.sha256()
    read = 0
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
        while True:
            operation.check()
            chunk = input_stream.read(_COPY_CHUNK_BYTES)
            if not chunk:
                break
            read += len(chunk)
            if read > maximum_bytes:
                raise _fail(
                    "resource_archive_too_large",
                    "Resource archive exceeds its limit",
                )
            digest.update(chunk)
    if read != source_stat.st_size:
        raise _fail(
            "resource_archive_mismatch",
            "Resource archive length changed while reading",
        )
    actual_hash = digest.hexdigest()
    if expected_sha256 is not None and actual_hash != expected_sha256:
        raise _fail(
            "resource_archive_mismatch",
            "Resource archive hash does not match the catalog",
        )
    return _ArchiveCopy(source, actual_hash, read)


def _safe_archive_path(name: str, *, allow_directory_suffix: bool) -> tuple[str, ...]:
    try:
        name.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise _fail("unsafe_resource_archive", "Archive path is not valid Unicode") from exc
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
                if len(parts) <= len(prefix_parts) or parts[: len(prefix_parts)] != prefix_parts:
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
                        if written > member.size or selected_bytes - member.size + written > install.size_bytes:
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
    except OSError as exc:
        _raise_if_storage_exhausted(exc)
        raise _fail("resource_install_failed", "Cannot extract UniDic archive") from exc
    except (tarfile.TarError, EOFError) as exc:
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
    _write_file(staging / _MANIFEST_NAME, _canonical_json_bytes(_unidic_manifest(resource)))
    _fsync_directory(staging)


def _recover_unidic(parent: Path, final: Path, resource: UniDicResource) -> None:
    parent.mkdir(parents=True, exist_ok=True)
    backups = sorted(parent.glob(f".backup-{resource.resource_id}-*"))
    if _valid_unidic_install(final, resource):
        for backup in backups:
            _safe_rmtree(backup)
        return
    valid_backups = [backup for backup in backups if _valid_unidic_install(backup, resource)]
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
        operation.check()
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
                        "attribution": [item.payload() for item in resource.attribution],
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


def _engine_uncompressed_limit() -> int:
    """Return the engine's authoritative total-uncompressed zip cap.

    Single source of truth for the custom (non-catalog) import paths: the bridge
    defers to the vendored engine's ``MAX_UNCOMPRESSED_BYTES`` (2 GiB, local-user
    threat model) instead of a divergent bridge constant. Imported
    function-locally so ANKI_MINER_HOME bootstrap ordering and the eager-import
    gate (import must sit after ``install_unidic``) both hold.
    """
    from anki_miner.services.dictionary.zip_safety import MAX_UNCOMPRESSED_BYTES

    return MAX_UNCOMPRESSED_BYTES


@dataclass(frozen=True, slots=True)
class _ZipIdentity:
    member_count: int
    uncompressed_bytes: int
    # Largest declared uncompressed size among Yomitan bank members, or 0 when
    # the archive carries none. Drives the ``_rewrite_yomitan_banks`` gate.
    max_bank_bytes: int = 0


def _yomitan_import_peak_bytes(
    identity: _ZipIdentity,
    archive_size_bytes: int,
    *,
    intermediate_csv: bool,
    streamed_rewrite: bool = True,
) -> int:
    """Bound additional same-filesystem bytes needed after source copy.

    Bank streaming can produce a stored-size ZIP as large as expanded input.
    Import then holds that ZIP, its retained copy, extracted files, and a SQLite
    index whose table+indexes are budgeted at twice expanded input. Frequency
    and pitch also build one intermediate CSV. Existing installed slots already
    consume reported free space, so overwrite backups need no second addition.
    ``_check_free_space`` adds the shared 32 MiB reserve.

    ``streamed_rewrite`` is False when the caller has already established that
    every bank is small enough to skip ``_rewrite_yomitan_banks``. Reserving for
    a ZIP that is never written refused imports that fit: a 540 MiB dictionary
    demanded ~3.2 GiB free where ~1.9 GiB is enough.
    """

    expanded = identity.uncompressed_bytes
    streamed_zip_bound = (expanded + archive_size_bytes) if streamed_rewrite else 0
    extracted_tree = expanded
    sqlite_index_bound = expanded * 2
    csv_bound = expanded if intermediate_csv else 0
    return streamed_zip_bound * 2 + extracted_tree + sqlite_index_bound + csv_bound


def _zip_entry_is_safe_type(info: zipfile.ZipInfo) -> bool:
    if info.is_dir():
        return True
    mode = (info.external_attr >> 16) & 0xFFFF
    file_type = stat.S_IFMT(mode)
    return file_type in {0, stat.S_IFREG}


def _read_at(stream: BinaryIO, offset: int, size: int) -> bytes:
    stream.seek(offset)
    content = stream.read(size)
    if len(content) != size:
        raise _fail("invalid_resource_archive", "Dictionary archive metadata is truncated")
    return content


def _preflight_zip_member_count(path: Path, member_limit: int) -> int:
    """Read EOCD/ZIP64 counts before ``ZipFile`` allocates one object per entry."""

    try:
        with _open_source(path) as (stream, source_stat):
            tail_size = min(source_stat.st_size, 22 + 65_535)
            tail_offset = source_stat.st_size - tail_size
            tail = _read_at(stream, tail_offset, tail_size)
            search_end = len(tail)
            relative_eocd = -1
            while search_end:
                candidate = tail.rfind(b"PK\x05\x06", 0, search_end)
                if candidate < 0:
                    break
                if candidate + 22 <= len(tail):
                    candidate_comment_size = struct.unpack_from("<H", tail, candidate + 20)[0]
                    if candidate + 22 + candidate_comment_size == len(tail):
                        relative_eocd = candidate
                        break
                search_end = candidate
            if relative_eocd < 0:
                raise _fail(
                    "invalid_resource_archive",
                    "Dictionary archive is corrupt (BadZipFile)",
                )
            (
                _signature,
                disk_number,
                central_disk,
                disk_entries,
                total_entries,
                central_size,
                central_offset,
                comment_size,
            ) = struct.unpack_from("<4s4H2LH", tail, relative_eocd)
            if relative_eocd + 22 + comment_size != len(tail):
                raise _fail("invalid_resource_archive", "Dictionary archive end record is inconsistent")
            if disk_number != 0 or central_disk != 0 or disk_entries != total_entries:
                raise _fail("invalid_resource_archive", "Multi-disk dictionary archives are unsupported")

            eocd_offset = tail_offset + relative_eocd
            central_end = eocd_offset
            if total_entries == 0xFFFF or central_size == 0xFFFFFFFF or central_offset == 0xFFFFFFFF:
                if eocd_offset < 20:
                    raise _fail("invalid_resource_archive", "Dictionary ZIP64 locator is missing")
                locator = _read_at(stream, eocd_offset - 20, 20)
                locator_signature, zip64_disk, zip64_offset, zip64_disks = struct.unpack("<4sLQL", locator)
                if locator_signature != b"PK\x06\x07" or zip64_disk != 0 or zip64_disks != 1:
                    raise _fail("invalid_resource_archive", "Dictionary ZIP64 locator is invalid")
                zip64_location = zip64_offset
                zip64 = _read_at(stream, zip64_location, 56)
                if not zip64.startswith(b"PK\x06\x06"):
                    # A self-extracting prefix shifts the physical record while
                    # the locator retains ZIP-relative offsets.
                    zip64_location = eocd_offset - 20 - 56
                    zip64 = _read_at(stream, zip64_location, 56)
                (
                    zip64_signature,
                    record_size,
                    _created_version,
                    _required_version,
                    zip64_disk_number,
                    zip64_central_disk,
                    zip64_disk_entries,
                    zip64_total_entries,
                    zip64_central_size,
                    zip64_central_offset,
                ) = struct.unpack("<4sQ2H2L4Q", zip64)
                if (
                    zip64_signature != b"PK\x06\x06"
                    or record_size != 44
                    or zip64_disk_number != 0
                    or zip64_central_disk != 0
                    or zip64_disk_entries != zip64_total_entries
                ):
                    raise _fail("invalid_resource_archive", "Dictionary ZIP64 end record is invalid")
                total_entries = zip64_total_entries
                central_size = zip64_central_size
                central_offset = zip64_central_offset
                central_end = zip64_location

            if central_size > _MAX_CUSTOM_ZIP_CENTRAL_DIRECTORY_BYTES:
                raise _fail(
                    "resource_archive_too_large",
                    "Dictionary central directory exceeds its size limit",
                )
            if central_size > central_end or central_offset > central_end:
                raise _fail("invalid_resource_archive", "Dictionary central directory offset is invalid")
            central_start = central_end - central_size
            # ``central_offset`` is ZIP-relative for self-extracting archives.
            if central_start < central_offset:
                raise _fail("invalid_resource_archive", "Dictionary central directory is inconsistent")
            position = central_start
            counted_entries = 0
            while position < central_end:
                counted_entries += 1
                if counted_entries > member_limit:
                    raise _fail(
                        "resource_archive_too_large",
                        "Dictionary archive member count is outside its limit",
                    )
                header = _read_at(stream, position, 46)
                if not header.startswith(b"PK\x01\x02"):
                    raise _fail("invalid_resource_archive", "Dictionary central directory is corrupt")
                filename_size, extra_size, entry_comment_size = struct.unpack_from("<3H", header, 28)
                record_size = 46 + filename_size + extra_size + entry_comment_size
                position += record_size
                if position > central_end:
                    raise _fail("invalid_resource_archive", "Dictionary central directory is truncated")
            if position != central_end or counted_entries != total_entries:
                raise _fail("invalid_resource_archive", "Dictionary central directory count is inconsistent")
            total_entries = counted_entries
    except BridgeProtocolError:
        raise
    except (OSError, struct.error) as exc:
        raise _fail("invalid_resource_archive", "Dictionary archive metadata is corrupt") from exc

    if total_entries <= 0 or total_entries > member_limit:
        raise _fail(
            "resource_archive_too_large",
            "Dictionary archive member count is outside its limit",
        )
    return total_entries


def _unsupported_zip_compression(info: zipfile.ZipInfo) -> BridgeProtocolError:
    method = zipfile.compressor_names.get(info.compress_type, "unknown")
    return _fail(
        "resource_archive_unsupported_compression",
        "Dictionary archive uses an unsupported compression method "
        f"(zip method {info.compress_type}: {method}); re-create the "
        ".zip with standard Deflate compression and no encryption",
    )


def _validate_zip_streamed(
    path: Path,
    operation: _Operation,
    *,
    member_limit: int | None,
    total_limit: int,
    file_limit: int | None,
    require_root_index: bool,
) -> _ZipIdentity:
    effective_member_limit = member_limit if member_limit is not None else _MAX_CUSTOM_ZIP_MEMBERS
    declared_member_count = _preflight_zip_member_count(path, effective_member_limit)
    try:
        with zipfile.ZipFile(path, "r") as archive:
            infos = archive.infolist()
            if not infos or len(infos) != declared_member_count or len(infos) > effective_member_limit:
                raise _fail(
                    "resource_archive_too_large",
                    "Dictionary archive member count is outside its limit",
                )
            seen: set[tuple[str, ...]] = set()
            total = 0
            max_bank_bytes = 0
            has_root_index = False
            for info in infos:
                operation.check()
                parts = _safe_archive_path(info.filename, allow_directory_suffix=info.is_dir())
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
                if info.file_size < 0 or (file_limit is not None and info.file_size > file_limit):
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
                if len(parts) == 1 and _YOMITAN_BANK_RE.fullmatch(parts[0]):
                    max_bank_bytes = max(max_bank_bytes, info.file_size)
                actual = 0
                # Building the decompressor is eager in ``ZipExtFile.__init__``,
                # so an unsupported method (Deflate64, or a bz2/lzma module absent
                # under Chaquopy) raises here at ``open`` — never lazily on read.
                # Split it out of the generic "corrupt" catch-all with an
                # actionable, method-named diagnostic. ``operation.check`` and the
                # read loop stay OUTSIDE this narrow try so a cancellation
                # (``BridgeProtocolError``) is never mislabeled.
                try:
                    stream = archive.open(info, "r")
                except (NotImplementedError, RuntimeError) as exc:
                    raise _unsupported_zip_compression(info) from exc
                with stream:
                    while True:
                        operation.check()
                        chunk = stream.read(_COPY_CHUNK_BYTES)
                        if not chunk:
                            break
                        actual += len(chunk)
                        if actual > info.file_size or (file_limit is not None and actual > file_limit):
                            raise _fail(
                                "resource_archive_too_large",
                                "Dictionary file exceeds its declared limit",
                            )
                if actual != info.file_size:
                    raise _fail(
                        "invalid_resource_archive",
                        "Dictionary member length is inconsistent",
                    )
            if require_root_index and not has_root_index:
                raise _fail(
                    "invalid_resource_archive",
                    "Dictionary archive has no root index.json",
                )
            return _ZipIdentity(len(infos), total, max_bank_bytes)
    except BridgeProtocolError:
        raise
    except (zipfile.BadZipFile, RuntimeError, OSError, EOFError) as exc:
        # Unsupported-compression errors are split off above; only genuine
        # structural corruption reaches here. Name the exception class (PII-safe)
        # so a screenshot-only report stays diagnosable.
        raise _fail("invalid_resource_archive", f"Dictionary archive is corrupt ({type(exc).__name__})") from exc


def _iter_json_array_stream(
    stream: BinaryIO,
    operation: _Operation,
    *,
    item_byte_limit: int,
) -> Iterator[Any]:
    """Yield one top-level JSON-array item without retaining the whole bank."""

    json_decoder = json.JSONDecoder()
    utf8_decoder = codecs.getincrementaldecoder("utf-8")()
    buffer = ""
    position = 0
    eof = False

    def fill(maximum_bytes: int = _COPY_CHUNK_BYTES) -> None:
        nonlocal buffer, eof
        if eof:
            return
        operation.check()
        chunk = stream.read(maximum_bytes)
        try:
            if chunk:
                buffer += utf8_decoder.decode(chunk)
            else:
                buffer += utf8_decoder.decode(b"", final=True)
                eof = True
        except UnicodeDecodeError as exc:
            raise _fail("invalid_resource_archive", "Yomitan bank is not valid UTF-8") from exc

    def skip_whitespace() -> None:
        nonlocal position
        while True:
            while position < len(buffer) and buffer[position].isspace():
                position += 1
            if position < len(buffer) or eof:
                return
            fill()

    def compact() -> None:
        """Drop the consumed prefix, but only once it dominates the buffer.

        Compacting on every item made each yield copy the whole remaining
        buffer. That is quadratic within a refill window, and since the window
        is capped at ``_COPY_CHUNK_BYTES`` it comes out as linear with a copy
        of most of a chunk per item - measured at ~18x the cost of this
        version, stable across bank sizes. Halving keeps the buffer within 2x
        the pending region and makes compaction amortised O(1) per item.
        """
        nonlocal buffer, position
        if position and position * 2 >= len(buffer):
            buffer = buffer[position:]
            position = 0

    def pending_bytes() -> int:
        """UTF-8 size of the undecoded region, for the item-size cap.

        Only reached on a refill, i.e. once per chunk rather than once per
        item, so walking the pending region here stays linear overall.
        """
        return len(buffer[position:].encode("utf-8"))

    fill()
    skip_whitespace()
    if position >= len(buffer) or buffer[position] != "[":
        raise _fail("invalid_resource_archive", "Yomitan bank must be a JSON array")
    position += 1

    skip_whitespace()
    if position < len(buffer) and buffer[position] == "]":
        position += 1
    else:
        while True:
            compact()
            while True:
                try:
                    # Decode from ``position`` rather than slicing to it: the
                    # slice was the second half of the quadratic copy.
                    item, item_end = json_decoder.raw_decode(buffer, position)
                except json.JSONDecodeError as exc:
                    if eof:
                        raise _fail("invalid_resource_archive", "Yomitan bank contains invalid JSON") from exc
                    buffered_bytes = pending_bytes()
                    if buffered_bytes >= item_byte_limit + 1:
                        raise _fail(
                            "resource_archive_too_large",
                            "Yomitan bank contains an oversized item",
                        ) from exc
                    fill(min(_COPY_CHUNK_BYTES, item_byte_limit + 1 - buffered_bytes))
                    continue

                item_bytes = len(buffer[position:item_end].encode("utf-8"))
                if item_bytes > item_byte_limit:
                    raise _fail(
                        "resource_archive_too_large",
                        "Yomitan bank contains an oversized item",
                    )
                probe = item_end
                while probe < len(buffer) and buffer[probe].isspace():
                    probe += 1
                # A number is the one token the decoder will happily return
                # truncated: JSON's grammar stops before a trailing ``.`` or
                # ``e``, so ``raw_decode("1.")`` yields ``(1, 1)`` and the rest
                # of the literal looks like trailing garbage. Refill whenever
                # the next character could still extend the number, not only
                # when it ended flush against the buffer.
                if (
                    not eof
                    and isinstance(item, (int, float))
                    and not isinstance(item, bool)
                    and (item_end == len(buffer) or buffer[item_end] in _JSON_NUMBER_CONTINUATION)
                ):
                    buffered_bytes = pending_bytes()
                    if buffered_bytes >= item_byte_limit + 1:
                        raise _fail(
                            "resource_archive_too_large",
                            "Yomitan bank contains an oversized item",
                        )
                    fill(min(_COPY_CHUNK_BYTES, item_byte_limit + 1 - buffered_bytes))
                    continue
                while probe == len(buffer) and not eof:
                    # A whole chunk, not a byte at a time: ``buffer`` is a
                    # closure cell, so ``buffer += chunk`` cannot use CPython's
                    # in-place resize and every single-byte fill re-copied it.
                    # The item is already decoded and size-checked here, so
                    # reading ahead past it costs nothing.
                    fill()
                    while probe < len(buffer) and buffer[probe].isspace():
                        probe += 1
                if probe >= len(buffer) or buffer[probe] not in {",", "]"}:
                    raise _fail("invalid_resource_archive", "Yomitan bank contains invalid JSON")
                delimiter = buffer[probe]
                position = probe + 1
                break

            operation.check()
            yield item
            if delimiter == "]":
                break
            skip_whitespace()
            if position < len(buffer) and buffer[position] == "]":
                raise _fail("invalid_resource_archive", "Yomitan bank has a trailing comma")

    while True:
        while position < len(buffer) and buffer[position].isspace():
            position += 1
        if position < len(buffer):
            raise _fail("invalid_resource_archive", "Yomitan bank has trailing content")
        if eof:
            return
        buffer = ""
        position = 0
        fill()


def _rewrite_yomitan_banks(
    source_path: Path,
    destination_path: Path,
    operation: _Operation,
) -> Path:
    """Split Yomitan JSON arrays into bounded banks before desktop import.

    Desktop semantics stay intact: every decoded entry is re-emitted in order;
    only bank partitioning changes. This prevents its ``read_text`` +
    ``json.loads`` path from materializing an entire near-2-GiB bank.
    """

    destination_path.unlink(missing_ok=True)
    encoded = json.JSONEncoder(ensure_ascii=False, separators=(",", ":"))
    try:
        with (
            zipfile.ZipFile(source_path, "r") as source,
            zipfile.ZipFile(
                destination_path,
                "x",
                # Stored, not deflated: this archive is a private staging file
                # the importer reads back immediately and never retains, so
                # re-compressing hundreds of megabytes of bank JSON buys
                # nothing. ``_yomitan_import_peak_bytes`` already budgets it at
                # stored size.
                compression=zipfile.ZIP_STORED,
                allowZip64=True,
            ) as destination,
        ):
            infos = source.infolist()
            banks: dict[str, list[zipfile.ZipInfo]] = {
                "term_bank": [],
                "term_meta_bank": [],
                "tag_bank": [],
            }
            written_members = 0

            def count_member() -> None:
                nonlocal written_members
                written_members += 1
                if written_members > _MAX_CUSTOM_ZIP_MEMBERS:
                    raise _fail(
                        "resource_archive_too_large",
                        "Streamed dictionary archive has too many bank chunks",
                    )

            for info in infos:
                operation.check()
                match = _YOMITAN_BANK_RE.fullmatch(info.filename)
                if match and not info.is_dir():
                    banks[match.group(1)].append(info)
                    continue
                count_member()
                if info.is_dir():
                    destination.writestr(info.filename, b"")
                    continue
                with (
                    source.open(info, "r") as input_stream,
                    destination.open(
                        info.filename,
                        "w",
                        force_zip64=True,
                    ) as output_stream,
                ):
                    while True:
                        operation.check()
                        chunk = input_stream.read(_COPY_CHUNK_BYTES)
                        if not chunk:
                            break
                        _write_all(output_stream, chunk)

            for bank_prefix, bank_infos in banks.items():
                bank_number = 0
                chunk_entries: list[bytes] = []
                chunk_size = 2

                def flush_bank(prefix: str = bank_prefix) -> None:
                    nonlocal bank_number, chunk_entries, chunk_size
                    if not chunk_entries:
                        return
                    bank_number += 1
                    count_member()
                    destination.writestr(
                        f"{prefix}_{bank_number:06d}.json",
                        b"[" + b",".join(chunk_entries) + b"]",
                    )
                    chunk_entries = []
                    chunk_size = 2

                for info in sorted(bank_infos, key=lambda item: item.filename):
                    with source.open(info, "r") as bank_stream:
                        for item in _iter_json_array_stream(
                            bank_stream,
                            operation,
                            item_byte_limit=_YOMITAN_BANK_CHUNK_BYTES - 2,
                        ):
                            item_bytes = "".join(encoded.iterencode(item)).encode("utf-8")
                            if len(item_bytes) + 2 > _YOMITAN_BANK_CHUNK_BYTES:
                                raise _fail(
                                    "resource_archive_too_large",
                                    "Yomitan bank contains an oversized item",
                                )
                            added_size = len(item_bytes) + (1 if chunk_entries else 0)
                            if chunk_entries and chunk_size + added_size > _YOMITAN_BANK_CHUNK_BYTES:
                                flush_bank()
                                added_size = len(item_bytes)
                            chunk_entries.append(item_bytes)
                            chunk_size += added_size
                flush_bank()
                if bank_infos and bank_number == 0:
                    count_member()
                    destination.writestr(f"{bank_prefix}_000001.json", b"[]")
            operation.check()
        destination_path.chmod(0o400)
        _fsync_directory(destination_path.parent)
        return destination_path
    except BridgeProtocolError:
        destination_path.unlink(missing_ok=True)
        raise
    except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
        destination_path.unlink(missing_ok=True)
        _raise_if_storage_exhausted(exc)
        raise _fail("invalid_resource_archive", "Cannot stream Yomitan banks") from exc


def _restore_original_yomitan_zip(candidate: Path, archive: _ArchiveCopy) -> None:
    """Replace importer's rewritten retained ZIP with original verified bytes."""

    retained = candidate / "source.zip"
    retained.unlink()
    archive.path.replace(retained)


def _seal_retained_archive(candidate: Path) -> None:
    """Make the slot's retained ``source.zip`` read-only.

    The importer copies (or this module renames) it in with whatever mode the
    staged source carried. Every other archive this module lands is 0o400, and
    nothing reimports from a writable copy on purpose.
    """

    retained = candidate / "source.zip"
    if retained.is_file():
        retained.chmod(0o400)


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
        "attribution": ([item.payload() for item in catalog_resource.attribution] if catalog_resource else []),
    }


@dataclass(frozen=True, slots=True)
class _DictionarySidecar:
    source_name: str
    source_revision: str
    catalog_resource: YomitanResource | None

    @property
    def catalog_resource_id(self) -> str | None:
        return self.catalog_resource.resource_id if self.catalog_resource else None

    @property
    def attribution(self) -> list[dict[str, object]]:
        if self.catalog_resource is None:
            return []
        return [item.payload() for item in self.catalog_resource.attribution]


def _path_occupied(path: Path) -> bool:
    """Return whether a path entry exists without following its final symlink."""

    try:
        path.lstat()
        return True
    except FileNotFoundError:
        return False
    except OSError as exc:
        raise _fail("resource_inventory_failed", "Cannot inspect dictionary storage") from exc


def _ensure_real_directory(path: Path, *, code: str, message: str) -> None:
    try:
        value = path.lstat()
    except FileNotFoundError:
        try:
            path.mkdir(parents=True)
            value = path.lstat()
        except OSError as exc:
            raise _fail(code, message) from exc
    except OSError as exc:
        raise _fail(code, message) from exc
    if stat.S_ISLNK(value.st_mode) or not stat.S_ISDIR(value.st_mode):
        raise _fail(code, message)


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
    if not _path_occupied(backup_root):
        return
    _ensure_real_directory(
        backup_root,
        code="resource_cleanup_failed",
        message="Dictionary backup root is unsafe",
    )
    dicts_root = _dictionary_root(home)
    _ensure_real_directory(
        dicts_root,
        code="resource_cleanup_failed",
        message="Dictionary storage root is unsafe",
    )
    try:
        backups = []
        for backup in backup_root.iterdir():
            if len(backups) >= _MAX_DICTIONARY_SLOTS:
                raise _fail("resource_cleanup_failed", "Dictionary backup set is unbounded")
            backups.append(backup)
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot inspect dictionary backups") from exc
    for backup in sorted(backups):
        if not backup.name.startswith("backup-"):
            raise _fail("resource_cleanup_failed", "Dictionary backup entry is unsafe")
        remainder = backup.name.removeprefix("backup-")
        slot_id, separator, operation_id = remainder.partition("--")
        if separator != "--" or not _SLOT_ID_RE.fullmatch(slot_id) or not _OPERATION_ID_RE.fullmatch(operation_id):
            raise _fail("resource_cleanup_failed", "Dictionary backup slot is invalid")
        try:
            backup_stat = backup.lstat()
        except OSError as exc:
            raise _fail("resource_cleanup_failed", "Cannot inspect dictionary backup") from exc
        if stat.S_ISLNK(backup_stat.st_mode) or not stat.S_ISDIR(backup_stat.st_mode):
            _safe_remove_dictionary_entry(backup)
            continue
        final = dicts_root / slot_id
        if _valid_dictionary_slot(final):
            _safe_remove_dictionary_entry(backup)
        elif _valid_dictionary_slot(backup):
            if _path_occupied(final):
                _safe_remove_dictionary_entry(final)
            backup.rename(final)
            _fsync_directory(dicts_root)
        else:
            _safe_remove_dictionary_entry(backup)


def _purge_dictionary_backups(home: Path, slot_id: str) -> None:
    """Drop every backup ``_recover_dictionary_backups`` would promote back into place.

    That function renames a surviving ``backup-<slot_id>--*`` onto ``dicts/<slot_id>``
    whenever the slot is missing, which is exactly the state a delete leaves behind.
    """

    backup_root = _backup_root(home)
    if not _path_occupied(backup_root):
        return
    _ensure_real_directory(
        backup_root,
        code="resource_cleanup_failed",
        message="Dictionary backup root is unsafe",
    )
    try:
        backups = sorted(backup_root.iterdir())
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot inspect dictionary backups") from exc
    purged = False
    for backup in backups:
        # Same parse and same strictness as _recover_dictionary_backups, so exactly
        # the entries it would promote onto this slot are the entries removed here.
        if not backup.name.startswith("backup-"):
            raise _fail("resource_cleanup_failed", "Dictionary backup entry is unsafe")
        remainder = backup.name.removeprefix("backup-")
        candidate, separator, operation_id = remainder.partition("--")
        if separator != "--" or not _SLOT_ID_RE.fullmatch(candidate) or not _OPERATION_ID_RE.fullmatch(operation_id):
            raise _fail("resource_cleanup_failed", "Dictionary backup slot is invalid")
        if candidate != slot_id:
            continue
        _safe_remove_dictionary_entry(backup)
        purged = True
    if purged:
        _fsync_directory(backup_root)


def delete_dictionary(payload: Mapping[str, object]) -> str:
    _exact(payload, {"operationId", "slotId"}, code="invalid_resource_request")
    operation_id = _operation_id(payload["operationId"])
    slot_id = _slot_id(payload["slotId"])
    home = Path(require_initialized())
    root = _dictionary_root(home)
    final = root / slot_id
    grave: Path | None = None
    with _OPERATIONS.begin(operation_id) as operation:
        operation.check()
        with _PROMOTION_LOCK:
            _purge_dictionary_backups(home, slot_id)
            if _path_occupied(final):
                operation_root = _resource_work_root(home) / "operations" / operation_id
                _safe_rmtree(operation_root)
                operation_root.mkdir(parents=True)
                grave = operation_root / slot_id
                # Rename, not rmtree: the slot leaves the published root atomically, so
                # an interrupted delete cannot leave a half-tree that inventory reports
                # as an invalid slot. cleanup_resources sweeps the staging left behind.
                final.rename(grave)
                _fsync_directory(root)
        # Outside _PROMOTION_LOCK: unlinking a large dictionary's media must not block
        # every other publication and inventory for the duration.
        if grave is not None:
            _safe_remove_dictionary_entry(grave)
        _fsync_directory(home)
        return encode_message(
            "resource.dictionary.deleted",
            {"slotId": slot_id, "removed": grave is not None},
        )


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
    _ensure_real_directory(
        final_root,
        code="resource_install_failed",
        message="Dictionary storage root is unsafe",
    )
    _ensure_real_directory(
        backup_root,
        code="resource_install_failed",
        message="Dictionary backup root is unsafe",
    )
    final = final_root / slot_id
    backup = backup_root / f"backup-{slot_id}--{operation_id}"
    with _PROMOTION_LOCK:
        _recover_dictionary_backups(home)
        if _path_occupied(final) and not overwrite:
            raise _fail(
                "resource_already_installed",
                f"Dictionary slot {slot_id!r} already exists",
            )
        if _path_occupied(backup):
            _safe_remove_dictionary_entry(backup)
        if _path_occupied(final):
            final.rename(backup)
            _fsync_directory(final_root)
            _fsync_directory(backup_root)
        try:
            candidate.rename(final)
            _fsync_directory(final_root)
        except Exception:
            if _path_occupied(final):
                _safe_remove_dictionary_entry(final)
            if _path_occupied(backup):
                backup.rename(final)
                _fsync_directory(final_root)
            raise
        if _path_occupied(backup):
            _safe_remove_dictionary_entry(backup)


def _validate_dictionary_metadata(title: str, revision: str) -> None:
    for name, value in (("title", title), ("revision", revision)):
        try:
            size = len(value.encode("utf-8"))
        except UnicodeEncodeError as exc:
            raise _fail(
                "dictionary_import_failed",
                f"Dictionary {name} is not valid Unicode",
            ) from exc
        if size > _MAX_DICTIONARY_METADATA_BYTES:
            raise _fail(
                "dictionary_import_failed",
                f"Dictionary {name} exceeds its size limit",
            )


def _derive_dictionary_slot(source: Path) -> str:
    """Derive the slot from title+revision, bounded to the bridge slot contract.

    The composition follows the desktop importer's title+revision rule, but the
    result is app-local: the engine receives it as an explicit ``dict_id`` and
    replace-targeting matches on the occupied slot, so desktop parity of the
    exact string does not matter. Non-ASCII titles slug to ``uXXXX`` runs, so a
    Japanese title of eleven or more chars overflows ``_SLOT_ID_RE``'s 64-char
    bound; those collapse to a truncated prefix plus a digest of the full slug.
    """

    _preflight_zip_member_count(source, _MAX_CUSTOM_ZIP_MEMBERS)
    try:
        with zipfile.ZipFile(source, "r") as archive:
            try:
                info = archive.getinfo("index.json")
            except KeyError as exc:
                raise _fail("dictionary_import_failed", "Dictionary ZIP has no root index.json") from exc
            if info.file_size > _MAX_YOMITAN_INDEX_BYTES:
                raise _fail("dictionary_import_failed", "Dictionary index.json exceeds its size limit")
            try:
                stream = archive.open(info, "r")
            except (NotImplementedError, RuntimeError) as exc:
                raise _unsupported_zip_compression(info) from exc
            with stream:
                raw = stream.read(_MAX_YOMITAN_INDEX_BYTES + 1)
            if len(raw) > _MAX_YOMITAN_INDEX_BYTES:
                raise _fail("dictionary_import_failed", "Dictionary index.json exceeds its size limit")
    except (OSError, zipfile.BadZipFile) as exc:
        raise _fail("dictionary_import_failed", "The selected dictionary is not a readable ZIP") from exc
    try:
        index = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise _fail("dictionary_import_failed", "Dictionary index.json is invalid") from exc
    if not isinstance(index, dict):
        raise _fail("dictionary_import_failed", "Dictionary index.json must be an object")
    title = str(index.get("title", "")).strip()
    revision = str(index.get("revision", "")).strip()
    if not title:
        raise _fail("dictionary_import_failed", "Dictionary index.json has no title")
    _validate_dictionary_metadata(title, revision)

    def slug(text: str) -> str:
        parts: list[str] = []
        buffer: list[str] = []
        for character in text.strip().lower():
            if ord(character) < 128:
                buffer.append(character)
            else:
                if buffer:
                    parts.append("".join(buffer))
                    buffer.clear()
                parts.append(f"u{ord(character):x}")
        if buffer:
            parts.append("".join(buffer))
        return re.sub(r"[^a-z0-9]+", "-", "-".join(parts)).strip("-") or "dict"

    derived = slug(title) + ("-" + slug(revision) if revision else "")
    if len(derived) <= _MAX_DERIVED_SLOT_CHARS:
        return derived
    # Digest the full unbounded slug so titles that only diverge past the
    # truncation point still land in distinct slots.
    digest = hashlib.sha256(derived.encode("utf-8")).hexdigest()[:_DERIVED_SLOT_DIGEST_CHARS]
    prefix = derived[: _MAX_DERIVED_SLOT_CHARS - _DERIVED_SLOT_DIGEST_CHARS - 1].rstrip("-") or "dict"
    return f"{prefix}-{digest}"


def preflight_dictionary(payload: Mapping[str, object]) -> str:
    _exact(
        payload,
        {"operationId", "sourcePath"},
        code="invalid_resource_request",
    )
    operation_id = _operation_id(payload["operationId"])
    source = _absolute_path(payload["sourcePath"], name="sourcePath")
    with _OPERATIONS.begin(operation_id) as operation:
        operation.check()
        try:
            slot_id = _slot_id(_derive_dictionary_slot(source))
        except BridgeProtocolError as exc:
            if exc.code in {
                "dictionary_import_failed",
                "resource_archive_unsupported_compression",
            }:
                raise
            raise _fail(
                "dictionary_import_failed",
                "The selected dictionary could not be inspected",
            ) from exc
        operation.check()
        return encode_message(
            "resource.dictionary.preflighted",
            {"slotId": slot_id},
        )


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
        catalog_id = _bounded_text(raw_catalog_id, name="catalogResourceId", max_bytes=64)
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
    if _path_occupied(final) and not overwrite:
        raise _fail("resource_already_installed", f"Dictionary slot {slot_id!r} already exists")
    operation_root = _resource_work_root(home) / "operations" / operation_id
    with _OPERATIONS.begin(operation_id) as operation:
        operation.check()
        _safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            maximum_archive = (
                catalog_resource.archive.size_bytes if catalog_resource else _MAX_CUSTOM_DICTIONARY_ARCHIVE_BYTES
            )
            # Kotlin hands us an app-private staged file (SAF copy for a custom
            # pick, download for a catalog one), so copying it here only doubled
            # the peak footprint of a multi-hundred-megabyte import.
            copied = _hash_archive(
                source,
                operation,
                maximum_bytes=maximum_archive,
                expected_size=(catalog_resource.archive.size_bytes if catalog_resource else None),
                expected_sha256=(catalog_resource.archive.sha256 if catalog_resource else None),
            )
            identity = _validate_zip_streamed(
                copied.path,
                operation,
                member_limit=(catalog_resource.dictionary.archive_member_limit if catalog_resource else None),
                total_limit=(
                    catalog_resource.dictionary.uncompressed_bytes_limit
                    if catalog_resource
                    else _engine_uncompressed_limit()
                ),
                file_limit=(catalog_resource.dictionary.file_bytes_limit if catalog_resource else None),
                require_root_index=catalog_resource is not None,
            )
            if catalog_resource and (
                identity.member_count != catalog_resource.dictionary.member_count
                or identity.uncompressed_bytes != catalog_resource.dictionary.uncompressed_bytes
            ):
                raise _fail(
                    "resource_archive_mismatch",
                    "Pinned dictionary layout differs from the catalog",
                )
            # A catalog archive's banks are pinned under the catalog's own
            # file_bytes_limit, so it has never needed the rewrite. A custom one
            # needs it only when some bank is too large for the engine importer
            # to read whole.
            streamed_rewrite = catalog_resource is None and identity.max_bank_bytes > _YOMITAN_BANK_INLINE_LIMIT_BYTES
            _check_free_space(
                operation_root,
                _yomitan_import_peak_bytes(
                    identity,
                    copied.size_bytes,
                    intermediate_csv=False,
                    streamed_rewrite=streamed_rewrite,
                ),
            )
            import_source = copied.path
            if streamed_rewrite:
                import_source = _rewrite_yomitan_banks(
                    copied.path,
                    operation_root / "streamed-dictionary.zip",
                    operation,
                )
            import_root = operation_root / "publication"
            import_root.mkdir()

            # Function-local engine import preserves ANKI_MINER_HOME bootstrap ordering.
            from anki_miner.services.dictionary.importers.yomitan_importer import (
                import_yomitan_zip,
            )

            try:
                result = import_yomitan_zip(
                    import_source,
                    import_root,
                    overwrite=False,
                    cancel_check=operation.cancelled.is_set,
                    dict_id=slot_id,
                )
            except Exception as exc:
                operation.check()
                _raise_if_storage_exhausted(exc)
                from anki_miner.exceptions import SetupError

                if isinstance(exc, SetupError):
                    raise _fail(
                        "dictionary_import_failed",
                        "The selected dictionary could not be imported",
                    ) from exc
                raise
            operation.check()
            _validate_dictionary_metadata(result.source_name, result.source_revision)
            if catalog_resource and (
                result.source_name != catalog_resource.dictionary.title
                or result.source_revision != catalog_resource.dictionary.revision
            ):
                raise _fail(
                    "resource_archive_mismatch",
                    "Pinned dictionary metadata differs from the catalog",
                )
            candidate = import_root / slot_id
            # Only a rewritten archive needs replacing: without the rewrite the
            # importer already retained the original verified bytes itself.
            if streamed_rewrite:
                _restore_original_yomitan_zip(candidate, copied)
            _seal_retained_archive(candidate)
            sidecar = _dictionary_sidecar(
                slot_id=slot_id,
                archive=copied,
                catalog_resource=catalog_resource,
                source_name=result.source_name,
                source_revision=result.source_revision,
            )
            _write_file(candidate / "android-resource.json", _canonical_json_bytes(sidecar))
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
                    "catalogResourceId": (catalog_resource.resource_id if catalog_resource else None),
                    "sourceName": result.source_name,
                    "sourceRevision": result.source_revision,
                    "entryCount": result.entry_count,
                    "skippedMalformed": result.skipped_malformed,
                    "mediaWarnings": list(result.media_warnings),
                    "archiveSha256": copied.sha256,
                    "attribution": (
                        [item.payload() for item in catalog_resource.attribution] if catalog_resource else []
                    ),
                },
            )
        finally:
            if operation_root.exists():
                _safe_rmtree(operation_root)


def _sidecar_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate dictionary sidecar key")
        result[key] = value
    return result


def _sidecar_text(
    value: object,
    *,
    maximum_bytes: int,
    allow_empty: bool = False,
) -> str | None:
    if not isinstance(value, str) or (not allow_empty and not value):
        return None
    try:
        if len(value.encode("utf-8")) > maximum_bytes:
            return None
    except UnicodeEncodeError:
        return None
    return value


def _read_dictionary_sidecar(
    slot: Path,
    *,
    slot_id: str,
) -> _DictionarySidecar | None:
    """Read only a fully validated Android identity sidecar.

    A sidecar is advisory inventory metadata, never proof that the index is
    usable. Catalog identity is exposed only when every immutable field matches
    the frozen catalog, so corrupt files cannot forge catalog attribution.
    """

    sidecar = slot / "android-resource.json"
    try:
        sidecar_stat = sidecar.lstat()
        if (
            stat.S_ISLNK(sidecar_stat.st_mode)
            or not stat.S_ISREG(sidecar_stat.st_mode)
            or sidecar_stat.st_size <= 0
            or sidecar_stat.st_size > _MAX_MANIFEST_BYTES
        ):
            return None
        with sidecar.open("rb") as stream:
            content = stream.read(_MAX_MANIFEST_BYTES + 1)
        if len(content) != sidecar_stat.st_size or len(content) > _MAX_MANIFEST_BYTES:
            return None
        value = json.loads(content.decode("utf-8"), object_pairs_hook=_sidecar_object_pairs)
    except FileNotFoundError:
        return None
    except (OSError, UnicodeError, json.JSONDecodeError, TypeError, ValueError):
        return None

    expected_keys = {
        "schemaVersion",
        "slotId",
        "archiveSha256",
        "archiveSizeBytes",
        "catalogResourceId",
        "sourceName",
        "sourceRevision",
        "attribution",
    }
    if not isinstance(value, dict) or set(value) != expected_keys:
        return None
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        return None
    if value["slotId"] != slot_id:
        return None
    archive_hash = _sidecar_text(value["archiveSha256"], maximum_bytes=64)
    if archive_hash is None or not _SHA256_RE.fullmatch(archive_hash):
        return None
    archive_size = value["archiveSizeBytes"]
    if type(archive_size) is not int or archive_size <= 0 or archive_size > _MAX_CUSTOM_DICTIONARY_ARCHIVE_BYTES:
        return None
    source_name = _sidecar_text(value["sourceName"], maximum_bytes=4096)
    source_revision = _sidecar_text(value["sourceRevision"], maximum_bytes=4096, allow_empty=True)
    if source_name is None or source_revision is None:
        return None

    catalog_id = value["catalogResourceId"]
    catalog_resource: YomitanResource | None = None
    if catalog_id is None:
        if value["attribution"] != []:
            return None
    else:
        catalog_text = _sidecar_text(catalog_id, maximum_bytes=64)
        if catalog_text is None:
            return None
        try:
            selected = load_resource_catalog().get(catalog_text)
        except BridgeProtocolError:
            return None
        if not isinstance(selected, YomitanResource):
            return None
        if (
            selected.slot_id != slot_id
            or selected.archive.sha256 != archive_hash
            or selected.archive.size_bytes != archive_size
            or selected.dictionary.title != source_name
            or selected.dictionary.revision != source_revision
            or value["attribution"] != [item.payload() for item in selected.attribution]
        ):
            return None
        catalog_resource = selected
    return _DictionarySidecar(
        source_name=source_name,
        source_revision=source_revision,
        catalog_resource=catalog_resource,
    )


def _inventory_text(
    value: object,
    *,
    maximum_bytes: int,
    allow_empty: bool = False,
) -> str | None:
    return _sidecar_text(
        value,
        maximum_bytes=maximum_bytes,
        allow_empty=allow_empty,
    )


def _invalid_dictionary_payload(
    slot_id: str,
    sidecar: _DictionarySidecar | None,
) -> dict[str, object]:
    return {
        "slotId": slot_id,
        "occupied": True,
        "valid": False,
        "sourceName": sidecar.source_name if sidecar else slot_id,
        "sourceRevision": sidecar.source_revision if sidecar else "",
        "format": "unknown",
        "entryCount": 0,
        "schemaOk": False,
        "embeddedAttribution": {},
        "catalogResourceId": sidecar.catalog_resource_id if sidecar else None,
        "attribution": sidecar.attribution if sidecar else [],
    }


def _read_dictionary_meta(index: Path) -> dict[object, object]:
    """Read dictionary metadata without importing the engine service package.

    Importing ``anki_miner.services.dictionary.storage`` executes the eager
    ``anki_miner.services`` package initializer, which pulls network and media
    dependencies into this otherwise offline inventory path. Keep inventory
    available in recovery/minimal-runtime contexts by using the same SQLite
    query directly. A contract test binds the local schema constant to the
    vendored engine's ``SCHEMA_VERSION``.
    """

    connection = sqlite3.connect(index.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        return dict(connection.execute("SELECT key, value FROM meta"))
    finally:
        connection.close()


def _dictionary_payload(slot: Path) -> dict[str, object]:
    slot_id = slot.name
    try:
        slot_stat = slot.lstat()
        if stat.S_ISLNK(slot_stat.st_mode) or not stat.S_ISDIR(slot_stat.st_mode):
            return _invalid_dictionary_payload(slot_id, None)
    except (FileNotFoundError, OSError):
        return _invalid_dictionary_payload(slot_id, None)
    sidecar = _read_dictionary_sidecar(slot, slot_id=slot_id)
    try:
        index = slot / "index.sqlite"
        index_stat = index.lstat()
        if stat.S_ISLNK(index_stat.st_mode) or not stat.S_ISREG(index_stat.st_mode) or index_stat.st_size <= 0:
            return _invalid_dictionary_payload(slot_id, sidecar)
    except (FileNotFoundError, OSError):
        return _invalid_dictionary_payload(slot_id, sidecar)

    # Read SQLite directly instead of trusting its performance-only meta.json
    # cache. This confirms that the occupied index can at least be opened and
    # that its metadata table is readable before marking the slot valid.
    try:
        values = _read_dictionary_meta(index)
    except (sqlite3.Error, OSError, UnicodeError, ValueError, TypeError):
        return _invalid_dictionary_payload(slot_id, sidecar)

    source_name = _inventory_text(values.get("source_name"), maximum_bytes=4096)
    source_revision = _inventory_text(values.get("source_revision", ""), maximum_bytes=4096, allow_empty=True)
    resource_format = _inventory_text(values.get("format"), maximum_bytes=64)
    try:
        entry_count = int(values.get("entry_count", ""))
        schema_version = int(values.get("schema_version", ""))
    except (TypeError, ValueError, OverflowError):
        return _invalid_dictionary_payload(slot_id, sidecar)
    if (
        source_name is None
        or source_revision is None
        or resource_format is None
        or entry_count < 0
        or entry_count > 2**63 - 1
    ):
        return _invalid_dictionary_payload(slot_id, sidecar)
    if sidecar is not None and (sidecar.source_name != source_name or sidecar.source_revision != source_revision):
        sidecar = None

    embedded = {
        key: validated
        for key in ("author", "attribution", "description")
        if (validated := _inventory_text(values.get(key), maximum_bytes=64 * 1024))
    }
    schema_ok = schema_version == _DICTIONARY_SCHEMA_VERSION
    return {
        "slotId": slot_id,
        "occupied": True,
        "valid": schema_ok,
        "sourceName": source_name,
        "sourceRevision": source_revision,
        "format": resource_format,
        "entryCount": entry_count,
        "schemaOk": schema_ok,
        "embeddedAttribution": embedded,
        "catalogResourceId": sidecar.catalog_resource_id if sidecar else None,
        "attribution": sidecar.attribution if sidecar else [],
    }


def list_dictionaries(payload: Mapping[str, object]) -> str:
    _exact(payload, set(), code="invalid_resource_request")
    home = Path(require_initialized())
    with _PROMOTION_LOCK:
        _recover_dictionary_backups(home)
    root = _dictionary_root(home)
    if not _path_occupied(root):
        return encode_message("resource.dictionary.listed", {"dictionaries": []})
    _ensure_real_directory(
        root,
        code="resource_inventory_failed",
        message="Dictionary storage root is unsafe",
    )
    try:
        slots = []
        for slot in root.iterdir():
            if len(slots) >= _MAX_DICTIONARY_SLOTS:
                raise _fail("resource_inventory_failed", "Dictionary storage has too many slots")
            slots.append(slot)
    except OSError as exc:
        raise _fail("resource_inventory_failed", "Cannot inspect dictionary storage") from exc
    dictionaries: list[dict[str, object]] = []
    for slot in sorted(slots, key=lambda item: item.name):
        if not _SLOT_ID_RE.fullmatch(slot.name):
            raise _fail(
                "resource_inventory_failed",
                "Dictionary storage contains an unsafe slot name",
            )
        dictionaries.append(_dictionary_payload(slot))
    return encode_message("resource.dictionary.listed", {"dictionaries": dictionaries})


def _validate_lookup_html(html: str) -> None:
    try:
        html_size = len(html.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise _fail("dictionary_result_invalid", "Dictionary lookup returned invalid text") from exc
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
    if not _path_occupied(root):
        raise _fail("dictionary_not_found", f"Dictionary slot {slot_id!r} is not installed")
    _ensure_real_directory(
        root,
        code="dictionary_unavailable",
        message="Dictionary storage root is unsafe",
    )
    slot = root / slot_id
    if not _path_occupied(slot):
        raise _fail("dictionary_not_found", f"Dictionary slot {slot_id!r} is not installed")
    inventory = _dictionary_payload(slot)
    if not inventory["valid"]:
        raise _fail("dictionary_schema_mismatch", "Dictionary must be reimported")

    # Function-local imports preserve bootstrap ordering.
    from anki_miner.services.dictionary.providers.indexed_provider import (
        IndexedDictProvider,
    )
    from anki_miner.services.dictionary.registry import DictionaryRegistry

    registry = DictionaryRegistry(root)
    registry.load()
    meta = registry.get(slot_id)
    if meta is None:
        raise _fail("dictionary_not_found", f"Dictionary slot {slot_id!r} is not installed")
    if not meta.schema_ok:
        raise _fail("dictionary_schema_mismatch", "Dictionary must be reimported")
    provider = IndexedDictProvider(meta.dict_id, meta.db_path, meta.source_name)
    if not provider.load():
        raise _fail("dictionary_unavailable", "Dictionary index cannot be opened")
    lookup_key = unicodedata.normalize("NFC", term)
    try:
        html = provider.lookup(lookup_key)
    finally:
        provider.close()
    if html is None:
        html = ""
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
        # Function-local import preserves bootstrap ordering and avoids a module
        # cycle: local_resources intentionally reuses this module's guarded
        # copy/publication primitives.
        from .local_resources import recover_local_resources

        recover_local_resources(home)
        operations = _resource_work_root(home) / "operations"
        if operations.exists():
            if operations.is_symlink() or not operations.is_dir():
                raise _fail("resource_cleanup_failed", "Resource operation root is unsafe")
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
