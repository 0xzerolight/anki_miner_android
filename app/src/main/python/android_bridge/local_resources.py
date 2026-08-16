"""Android-private imports for optional local mining resources.

The vendored desktop importers remain the format authority.  This module adds
the Android boundary around them: bounded private copies, streamed archive
extraction, operation-scoped candidates, crash-recoverable publication, and a
strict inventory payload.  Engine imports stay function-local because
``bootstrap.initialize`` must establish ``ANKI_MINER_HOME`` first.
"""

from __future__ import annotations

import contextlib
import csv
import json
import logging
import lzma
import os
import sqlite3
import stat
import tarfile
import unicodedata
import zipfile
from collections.abc import Callable, Mapping
from dataclasses import replace
from pathlib import Path, PurePosixPath

from . import resources as core
from .bootstrap import require_initialized
from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)

_FREQUENCY_FORMATS = frozenset({"zip", "csv", "tsv", "txt"})
_PITCH_FORMATS = frozenset({"zip", "csv", "tsv"})
_KNOWN_WORD_FORMATS = frozenset({"json", "csv", "tsv", "txt"})

_FREQUENCY_ARCHIVE_LIMIT = 512 * 1024 * 1024
_FREQUENCY_TEXT_LIMIT = 64 * 1024 * 1024
_PITCH_ARCHIVE_LIMIT = 512 * 1024 * 1024
_PITCH_TEXT_LIMIT = 64 * 1024 * 1024
_KNOWN_WORD_FILE_LIMIT = 32 * 1024 * 1024
# Absolute ceilings only. Local audio packs are stored (uncompressed) media in
# the multi-gigabyte range, so what a device can actually take is decided by
# core._check_free_space during extraction, not by a fixed number here. Keep
# these in step with AUDIO_ARCHIVE_CEILING_BYTES on the Kotlin side.
_AUDIO_ARCHIVE_LIMIT = 16 * 1024 * 1024 * 1024
# The upstream local-audio-yomichan collection is one archive holding four packs
# and over 250,000 expressions, so a ceiling near that count rejects the file
# users actually download. Sized to clear the whole collection with room spare;
# the real bound on an import is free space, checked during extraction.
_AUDIO_MEMBER_LIMIT = 600_000
_AUDIO_TOTAL_LIMIT = 16 * 1024 * 1024 * 1024
_AUDIO_FILE_LIMIT = 512 * 1024 * 1024
# The same collection's nhk16 pack ships entries.json at ~42 MiB (43,944,140
# bytes in the 2023-06-11 release), so a JSON ceiling under that rejects every
# pack in the archive at preflight. Sized at 3x that real maximum.
_AUDIO_JSON_LIMIT = 128 * 1024 * 1024
# Deepest an index file can sit and still describe a pack: wrapper directory,
# user_files, the pack folder, the file. Also how far pack detection descends
# through single-child wrapper directories.
_AUDIO_PACK_WRAPPER_DEPTH = 4
_AUDIO_PACK_METADATA_LIMIT = 64
_AUDIO_PACK_CANDIDATE_LIMIT = 64
_MAX_KNOWN_WORDS = 500_000
_MAX_WORD_BYTES = 1024
_MAX_KNOWN_WORD_PAGE = 200
_MAX_KNOWN_WORD_MUTATION = 256
_KNOWN_WORD_EXPORT_LIMIT = 512 * 1024 * 1024
_KNOWN_WORD_LINE_SEPARATORS = frozenset("\n\r\v\f\x1c\x1d\x1e\x85\u2028\u2029")
_ANDROID_SIDECAR = "android-resource.json"
_LEGACY_PITCH_SOURCE_ID = "legacy-pitch"
_LEGACY_PITCH_SOURCE_NAME = "Pitch Accent"


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def _format(value: object, allowed: frozenset[str], *, label: str) -> str:
    selected = core._bounded_text(value, name=label, max_bytes=16)
    if selected not in allowed:
        raise _fail("invalid_resource_request", f"{label} is unsupported")
    return selected


def _boolean(value: object, *, label: str) -> bool:
    if type(value) is not bool:
        raise _fail("invalid_resource_request", f"{label} must be a boolean")
    return value


def _display_name(value: object, *, label: str) -> str:
    text = core._bounded_text(value, name=label, max_bytes=512)
    if text != text.strip() or any(ord(character) < 0x20 for character in text):
        raise _fail("invalid_resource_request", f"{label} is invalid")
    return text


def _work_root(home: Path, operation_id: str) -> Path:
    return core._resource_work_root(home) / "operations" / operation_id


def _frequency_root(home: Path) -> Path:
    return home / "freqs"


def _pitch_root(home: Path) -> Path:
    return home / "pitch"


def _audio_root(home: Path) -> Path:
    return home / "audio_packs"


def _backup_root(home: Path, kind: str) -> Path:
    return core._resource_work_root(home) / f"{kind}-backups"


def _valid_indexed_dir(path: Path, *, require_content: bool) -> bool:
    try:
        scanned = path.lstat()
        index = path / "index.sqlite"
        if (
            stat.S_ISLNK(scanned.st_mode)
            or not stat.S_ISDIR(scanned.st_mode)
            or not index.is_file()
            or index.is_symlink()
            or index.stat().st_size <= 0
        ):
            return False
        if not require_content:
            return True
        content = path / "content"
        return content.is_dir() and not content.is_symlink()
    except OSError:
        return False


def _remove_exact_path(path: Path) -> None:
    """Remove one already-resolved app-private resource path, never its target."""

    try:
        scanned = path.lstat()
    except FileNotFoundError:
        return
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot inspect local resource") from exc
    if stat.S_ISDIR(scanned.st_mode) and not stat.S_ISLNK(scanned.st_mode):
        core._safe_rmtree(path)
        return
    try:
        path.unlink()
    except OSError as exc:
        raise _fail("resource_cleanup_failed", "Cannot remove local resource") from exc


def _recover_indexed_backups(
    home: Path,
    *,
    kind: str,
    final_root: Path,
    require_content: bool,
) -> None:
    backups_root = _backup_root(home, kind)
    if not backups_root.exists():
        return
    if backups_root.is_symlink() or not backups_root.is_dir():
        raise _fail("resource_cleanup_failed", f"{kind} backup root is unsafe")
    final_root.mkdir(parents=True, exist_ok=True)
    for backup in sorted(backups_root.iterdir()):
        if not backup.name.startswith("backup-"):
            raise _fail("resource_cleanup_failed", f"{kind} backup entry is unsafe")
        identity = backup.name.removeprefix("backup-").split("--", 1)[0]
        if not core._SLOT_ID_RE.fullmatch(identity):
            raise _fail("resource_cleanup_failed", f"{kind} backup id is invalid")
        if backup.is_symlink() or not backup.is_dir():
            _remove_exact_path(backup)
            continue
        final = final_root / identity
        if _valid_indexed_dir(final, require_content=require_content):
            core._safe_rmtree(backup)
        elif _valid_indexed_dir(backup, require_content=require_content):
            if final.exists() or final.is_symlink():
                _remove_exact_path(final)
            backup.rename(final)
            core._fsync_directory(final_root)
        else:
            core._safe_rmtree(backup)


_LOCAL_DELETE_ROOTS: dict[str, Callable[[Path], Path]] = {
    "pitch": _pitch_root,
    "frequency": _frequency_root,
    "audio-pack": _audio_root,
}


def _purge_indexed_backups(home: Path, *, kind: str, identity: str) -> None:
    """Drop every backup ``_recover_indexed_backups`` would promote back into place.

    Without this a delete is undone by the next inventory: that function renames
    ``backup-<identity>--*`` onto ``final_root/<identity>`` whenever the final slot
    is absent, which is exactly the state a delete leaves behind.
    """

    backups_root = _backup_root(home, kind)
    if not backups_root.exists():
        return
    if backups_root.is_symlink() or not backups_root.is_dir():
        raise _fail("resource_cleanup_failed", f"{kind} backup root is unsafe")
    purged = False
    for backup in sorted(backups_root.iterdir()):
        if not backup.name.startswith("backup-"):
            raise _fail("resource_cleanup_failed", f"{kind} backup entry is unsafe")
        # Same parse as _recover_indexed_backups, so exactly the entries it would
        # promote onto this identity are the entries removed here.
        if backup.name.removeprefix("backup-").split("--", 1)[0] != identity:
            continue
        _remove_exact_path(backup)
        purged = True
    if purged:
        core._fsync_directory(backups_root)


def delete_local_resource(payload: Mapping[str, object]) -> str:
    core._exact(payload, {"operationId", "kind", "slotId"}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    kind = core._bounded_text(payload["kind"], name="kind", max_bytes=16)
    if kind not in _LOCAL_DELETE_ROOTS:
        raise _fail("invalid_resource_request", "kind is invalid")
    slot_id = core._slot_id(payload["slotId"])
    home = Path(require_initialized())
    final_root = _LOCAL_DELETE_ROOTS[kind](home)
    final = final_root / slot_id
    grave: Path | None = None
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        with core._PROMOTION_LOCK:
            _purge_indexed_backups(home, kind=kind, identity=slot_id)
            if kind == "pitch" and slot_id == _LEGACY_PITCH_SOURCE_ID:
                # _migrate_legacy_pitch_csv re-imports this file on every
                # _pitch_inventory call, so the slot returns unless it goes too.
                _remove_exact_path(home / "pitch_accent.csv")
                core._fsync_directory(home)
            if final.exists() or final.is_symlink():
                operation_root = _work_root(home, operation_id)
                core._safe_rmtree(operation_root)
                operation_root.mkdir(parents=True)
                grave = operation_root / slot_id
                # Rename, not rmtree: the slot leaves the published root atomically
                # even for a 600k-file audio pack, and a crash mid-delete leaves only
                # staging that cleanup_resources already sweeps.
                final.rename(grave)
                core._fsync_directory(final_root)
        # Outside _PROMOTION_LOCK on purpose: unlinking a large pack must not block
        # every other publication and inventory for the duration.
        if grave is not None:
            _remove_exact_path(grave)
        core._fsync_directory(home)
        return encode_message(
            "resource.local.deleted",
            {"kind": kind, "slotId": slot_id, "removed": grave is not None},
        )


def _publish_indexed_dir(
    candidate: Path,
    *,
    home: Path,
    kind: str,
    identity: str,
    operation_id: str,
    overwrite: bool,
    final_root: Path,
    require_content: bool,
) -> None:
    backups_root = _backup_root(home, kind)
    final_root.mkdir(parents=True, exist_ok=True)
    backups_root.mkdir(parents=True, exist_ok=True)
    final = final_root / identity
    backup = backups_root / f"backup-{identity}--{operation_id}"
    with core._PROMOTION_LOCK:
        _recover_indexed_backups(
            home,
            kind=kind,
            final_root=final_root,
            require_content=require_content,
        )
        if (final.exists() or final.is_symlink()) and not overwrite:
            raise _fail(
                "resource_already_installed",
                f"Local resource {identity!r} already exists",
            )
        if backup.exists() or backup.is_symlink():
            _remove_exact_path(backup)
        if final.exists() or final.is_symlink():
            if _valid_indexed_dir(final, require_content=require_content):
                final.rename(backup)
                core._fsync_directory(final_root)
                core._fsync_directory(backups_root)
            else:
                _remove_exact_path(final)
                core._fsync_directory(final_root)
        try:
            candidate.rename(final)
            core._fsync_directory(final_root)
        except Exception:
            if final.exists() or final.is_symlink():
                _remove_exact_path(final)
            if backup.exists():
                backup.rename(final)
                core._fsync_directory(final_root)
            raise
        if not _valid_indexed_dir(final, require_content=require_content):
            if final.exists():
                _remove_exact_path(final)
            if backup.exists():
                backup.rename(final)
                core._fsync_directory(final_root)
            raise _fail("resource_install_failed", "Published resource is incomplete")
        if backup.exists():
            _remove_exact_path(backup)


def _write_sidecar(path: Path, value: Mapping[str, object]) -> None:
    core._write_file(path, core._canonical_json_bytes(value))


def _fsync_file(path: Path) -> None:
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0))
    except OSError as exc:
        core._raise_if_storage_exhausted(exc)
        raise _fail("resource_install_failed", "Cannot open imported resource") from exc
    try:
        os.fsync(descriptor)
    except OSError as exc:
        core._raise_if_storage_exhausted(exc)
        raise _fail("resource_install_failed", "Cannot persist imported resource") from exc
    finally:
        os.close(descriptor)


def _fsync_tree_directories(root: Path) -> None:
    directories: list[Path] = []
    for current, children, _files in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        directories.append(current_path)
        for name in children:
            child = current_path / name
            if child.is_symlink():
                raise _fail("resource_install_failed", "Imported resource contains a link")
    for directory in reversed(directories):
        core._fsync_directory(directory)


def _fsync_small_tree(root: Path) -> None:
    for current, children, files in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        for name in children:
            if (current_path / name).is_symlink():
                raise _fail("resource_install_failed", "Imported resource contains a link")
        for name in files:
            child = current_path / name
            if child.is_symlink() or not child.is_file():
                raise _fail(
                    "resource_install_failed",
                    "Imported resource contains a special file",
                )
            _fsync_file(child)
    _fsync_tree_directories(root)


def _frequency_import_payload(result: object, source_id: str, archive_sha256: str) -> dict[str, object]:
    def text(value: object, *, label: str, max_bytes: int, allow_empty: bool = False) -> str:
        if not isinstance(value, str) or (not allow_empty and not value):
            raise _fail(
                "frequency_import_failed",
                f"Imported frequency {label} exceeds the bridge contract",
            )
        try:
            encoded_size = len(value.encode("utf-8"))
        except UnicodeEncodeError as exc:
            raise _fail(
                "frequency_import_failed",
                f"Imported frequency {label} exceeds the bridge contract",
            ) from exc
        if encoded_size > max_bytes:
            raise _fail(
                "frequency_import_failed",
                f"Imported frequency {label} exceeds the bridge contract",
            )
        return value

    def count(value: object, *, label: str) -> int:
        if type(value) is not int or value < 0 or value > (1 << 63) - 1:
            raise _fail(
                "frequency_import_failed",
                f"Imported frequency {label} exceeds the bridge contract",
            )
        return value

    result_source_id = text(result.source_id, label="source ID", max_bytes=64)
    if result_source_id != source_id or not core._SLOT_ID_RE.fullmatch(result_source_id):
        raise _fail("frequency_import_failed", "Imported frequency identity is invalid")
    if type(result.converted_to_ranks) is not bool or type(result.is_categorical) is not bool:
        raise _fail("frequency_import_failed", "Imported frequency flags are invalid")
    return {
        "sourceId": result_source_id,
        "sourceName": text(result.source_name, label="source name", max_bytes=4096),
        "sourceRevision": text(
            result.source_revision,
            label="source revision",
            max_bytes=4096,
            allow_empty=True,
        ),
        "format": text(result.format, label="format", max_bytes=64),
        "entryCount": count(result.entry_count, label="entry count"),
        "skippedDisplayOnly": count(result.skipped_display_only, label="display-only count"),
        "skippedMalformed": count(result.skipped_malformed, label="malformed count"),
        "convertedToRanks": result.converted_to_ranks,
        "isCategorical": result.is_categorical,
        "archiveSha256": archive_sha256,
    }


def import_frequency(payload: Mapping[str, object]) -> str:
    core._exact(
        payload,
        {
            "operationId",
            "sourcePath",
            "sourceId",
            "sourceName",
            "sourceFormat",
            "overwrite",
        },
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    source_id = core._slot_id(payload["sourceId"])
    source_name = _display_name(payload["sourceName"], label="sourceName")
    source_format = _format(payload["sourceFormat"], _FREQUENCY_FORMATS, label="sourceFormat")
    overwrite = _boolean(payload["overwrite"], label="overwrite")
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            maximum = _FREQUENCY_ARCHIVE_LIMIT if source_format == "zip" else _FREQUENCY_TEXT_LIMIT
            copied = core._copy_archive(
                source,
                operation_root / f"source.{source_format}",
                operation,
                maximum_bytes=maximum,
            )
            import_source = copied.path
            if source_format == "zip":
                identity = core._validate_zip_streamed(
                    copied.path,
                    operation,
                    member_limit=None,
                    total_limit=core._engine_uncompressed_limit(),
                    file_limit=None,
                    require_root_index=False,
                )
                core._check_free_space(
                    operation_root,
                    core._yomitan_import_peak_bytes(
                        identity,
                        copied.size_bytes,
                        intermediate_csv=True,
                    ),
                )
                import_source = core._rewrite_yomitan_banks(
                    copied.path,
                    operation_root / "streamed-frequency.zip",
                    operation,
                )
            operation.check()
            from anki_miner.exceptions import SetupError
            from anki_miner.services.frequency.source_importer import (
                import_frequency_source,
            )

            import_root = operation_root / "publication"
            try:
                result = import_frequency_source(
                    import_source,
                    import_root,
                    source_id=source_id,
                    source_name=source_name,
                    cancel_check=operation.cancelled.is_set,
                )
            except (SetupError, UnicodeError, csv.Error, OSError, sqlite3.Error) as exc:
                operation.check()
                core._raise_if_storage_exhausted(exc)
                raise _fail(
                    "frequency_import_failed",
                    "The selected file is not a supported frequency source",
                ) from exc
            operation.check()
            candidate = import_root / source_id
            response = encode_message(
                "resource.frequency.imported",
                _frequency_import_payload(result, source_id, copied.sha256),
            )
            if source_format == "zip":
                core._restore_original_yomitan_zip(candidate, copied)
            _write_sidecar(
                candidate / _ANDROID_SIDECAR,
                {
                    "schemaVersion": 1,
                    "kind": "frequency",
                    "sourceId": source_id,
                    "archiveSha256": copied.sha256,
                    "archiveSizeBytes": copied.size_bytes,
                },
            )
            _fsync_small_tree(candidate)
            _publish_indexed_dir(
                candidate,
                home=home,
                kind="frequency",
                identity=source_id,
                operation_id=operation_id,
                overwrite=overwrite,
                final_root=_frequency_root(home),
                require_content=False,
            )
            return response
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


def import_pitch(payload: Mapping[str, object]) -> str:
    """Import one pitch source into its own slot under the pitch root.

    Mirrors :func:`import_frequency`: the engine builds a per-source
    ``index.sqlite`` inside a private operation root, and publication into the
    canonical root stays here. The engine's own ``repair_pitch_source`` is
    deliberately unused — it takes the canonical root and quarantines any slot
    without an ownership marker, which is every slot an existing install has.
    """

    core._exact(
        payload,
        {
            "operationId",
            "sourcePath",
            "sourceId",
            "sourceName",
            "sourceFormat",
            "overwrite",
        },
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    source_id = core._slot_id(payload["sourceId"])
    requested_name = _display_name(payload["sourceName"], label="sourceName")
    source_format = _format(payload["sourceFormat"], _PITCH_FORMATS, label="sourceFormat")
    overwrite = _boolean(payload["overwrite"], label="overwrite")
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            maximum = _PITCH_ARCHIVE_LIMIT if source_format == "zip" else _PITCH_TEXT_LIMIT
            copied = core._copy_archive(
                source,
                operation_root / f"source.{source_format}",
                operation,
                maximum_bytes=maximum,
            )
            import_source = copied.path
            if source_format == "zip":
                identity = core._validate_zip_streamed(
                    copied.path,
                    operation,
                    member_limit=None,
                    total_limit=core._engine_uncompressed_limit(),
                    file_limit=None,
                    require_root_index=False,
                )
                core._check_free_space(
                    operation_root,
                    core._yomitan_import_peak_bytes(
                        identity,
                        copied.size_bytes,
                        intermediate_csv=True,
                    ),
                )
                import_source = core._rewrite_yomitan_banks(
                    copied.path,
                    operation_root / "streamed-pitch.zip",
                    operation,
                )
            operation.check()
            from anki_miner.exceptions import SetupError
            from anki_miner.services.pitch_accent.source_importer import (
                import_pitch_source,
            )

            import_root = operation_root / "publication"
            try:
                result = import_pitch_source(
                    import_source,
                    import_root,
                    source_id=source_id,
                    source_name=requested_name,
                    cancel_check=operation.cancelled.is_set,
                )
            except (SetupError, UnicodeError, csv.Error, OSError, sqlite3.Error) as exc:
                operation.check()
                core._raise_if_storage_exhausted(exc)
                raise _fail(
                    "pitch_import_failed",
                    "The selected file is not supported pitch-accent data",
                ) from exc
            operation.check()
            candidate = import_root / source_id
            if source_format == "zip":
                core._restore_original_yomitan_zip(candidate, copied)
            _write_sidecar(
                candidate / _ANDROID_SIDECAR,
                {
                    "schemaVersion": 1,
                    "kind": "pitch",
                    "sourceId": source_id,
                    "archiveSha256": copied.sha256,
                    "archiveSizeBytes": copied.size_bytes,
                },
            )
            _fsync_small_tree(candidate)
            _publish_indexed_dir(
                candidate,
                home=home,
                kind="pitch",
                identity=source_id,
                operation_id=operation_id,
                overwrite=overwrite,
                final_root=_pitch_root(home),
                require_content=False,
            )
            return encode_message(
                "resource.pitch.imported",
                {
                    "sourceId": result.source_id,
                    "sourceName": result.source_name,
                    "sourceRevision": result.source_revision,
                    "sourceFormat": result.format,
                    "entryCount": result.entry_count,
                    "skippedDisplayOnly": result.skipped_display_only,
                    "skippedMalformed": result.skipped_malformed,
                    "archiveSha256": copied.sha256,
                },
            )
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


_ZIP_MAGIC = b"PK\x03\x04"
_XZ_MAGIC = b"\xfd7zXZ\x00"
_GZIP_MAGIC = b"\x1f\x8b"
_TAR_USTAR_OFFSET = 257


def _audio_archive_kind(path: Path) -> str:
    """Return ``"zip"`` or ``"tar"`` for *path*, decided by content not by name.

    The upstream local-audio-yomichan collection ships as ``.tar.xz`` while
    single packs are usually rezipped, and a document provider's reported type
    is not evidence of either, so the archive family comes from the bytes.
    """
    with core._open_source(path) as (stream, _):
        head = stream.read(_TAR_USTAR_OFFSET + 8)
    if head.startswith(_ZIP_MAGIC):
        return "zip"
    if (
        head.startswith(_XZ_MAGIC)
        or head.startswith(_GZIP_MAGIC)
        or head[_TAR_USTAR_OFFSET : _TAR_USTAR_OFFSET + 5] == b"ustar"
    ):
        return "tar"
    raise _fail(
        "resource_archive_unrecognized",
        "Selected audio-pack file has no supported archive signature",
    )


def _audio_member_limit(name: str) -> int:
    return _AUDIO_JSON_LIMIT if name.lower().endswith(".json") else _AUDIO_FILE_LIMIT


def _accept_audio_member(
    parts: tuple[str, ...],
    size: int,
    seen: set[tuple[str, ...]],
) -> int:
    """Record *parts* as seen and return the byte limit that applies to it."""
    if parts in seen:
        raise _fail("unsafe_resource_archive", "Audio pack contains a duplicate path")
    seen.add(parts)
    limit = _audio_member_limit(parts[-1])
    if size < 0 or size > limit:
        raise _fail("resource_archive_member_oversized", "Audio pack contains an oversized file")
    return limit


def _audio_pack_prefix(value: object) -> tuple[str, ...]:
    """Validate the pack subtree the caller chose during preflight.

    An empty string means the archive root is the pack. Anything else runs
    through the same path guard archive members do, so a caller cannot widen
    the extraction beyond a subtree the preflight actually reported.
    """
    if type(value) is not str:
        raise _fail("invalid_resource_request", "packPath must be a string")
    if not value:
        return ()
    text = core._bounded_text(value, name="packPath", max_bytes=1024)
    try:
        return core._safe_archive_path(text, allow_directory_suffix=True)
    except BridgeProtocolError as exc:
        raise _fail("invalid_resource_request", "packPath is invalid") from exc


def _under_prefix(parts: tuple[str, ...], prefix: tuple[str, ...]) -> tuple[str, ...] | None:
    """Return *parts* relative to *prefix*, or None when it lies outside it."""
    if not prefix:
        return parts
    if len(parts) <= len(prefix) or parts[: len(prefix)] != prefix:
        return None
    return parts[len(prefix) :]


def _copy_audio_member(
    source,
    target: Path,
    size: int,
    limit: int,
    operation: core._Operation,
) -> int:
    """Write *source* to *target*, bounded by the member's own declared length.

    The completed file is synced before the candidate tree can be published,
    so a durable directory rename never exposes an index whose media data is
    still dirty.
    """
    written = 0
    with target.open("xb", buffering=0) as output:
        while True:
            operation.check()
            chunk = source.read(core._COPY_CHUNK_BYTES)
            if not chunk:
                break
            written += len(chunk)
            if written > size or written > limit:
                raise _fail(
                    "resource_archive_expands_too_large",
                    "Audio pack expands beyond its limit",
                )
            core._write_all(output, chunk)
        if written != size:
            raise _fail(
                "invalid_resource_archive",
                "Audio pack member length is inconsistent",
            )
        os.fsync(output.fileno())
    return written


def _extract_audio_zip(
    path: Path,
    destination: Path,
    operation: core._Operation,
    prefix: tuple[str, ...],
) -> None:
    try:
        declared_member_count = core._preflight_zip_member_count(path, _AUDIO_MEMBER_LIMIT)
    except BridgeProtocolError as exc:
        if exc.code == "resource_archive_too_large":
            raise _fail(
                "resource_archive_member_count",
                "Audio pack member count is outside its limit",
            ) from exc
        raise
    # Open through _open_source so the staged ZIP keeps the no-symlink and
    # changed-underneath-us guards it had when it was copied first.
    with core._open_source(path) as (stream, _), zipfile.ZipFile(stream, "r") as archive:
        infos = archive.infolist()
        if len(infos) != declared_member_count:
            raise _fail(
                "resource_archive_member_count",
                "Audio pack member count is outside its limit",
            )
        # Two passes over the central directory: the first validates every
        # member and totals only the selected subtree, so a single pack out of
        # the four-pack collection reserves and writes its own size rather than
        # the whole archive's.
        seen: set[tuple[str, ...]] = set()
        selected: dict[int, tuple[str, ...]] = {}
        declared_total = 0
        for index, info in enumerate(infos):
            operation.check()
            parts = core._safe_archive_path(info.filename, allow_directory_suffix=info.is_dir())
            if info.flag_bits & 0x1 or not core._zip_entry_is_safe_type(info):
                raise _fail(
                    "unsafe_resource_archive",
                    "Audio pack contains an encrypted, linked, or special file",
                )
            _accept_audio_member(parts, 0 if info.is_dir() else info.file_size, seen)
            relative = _under_prefix(parts, prefix)
            if relative is None:
                continue
            selected[index] = relative
            if not info.is_dir():
                declared_total += info.file_size
        if declared_total <= 0 or declared_total > _AUDIO_TOTAL_LIMIT:
            raise _fail("resource_archive_expands_too_large", "Audio pack expands beyond its limit")
        core._check_free_space(destination.parent, declared_total)
        destination.mkdir(parents=True)
        actual_total = 0
        for index, info in enumerate(infos):
            relative = selected.get(index)
            if relative is None:
                continue
            operation.check()
            target = destination.joinpath(*relative)
            if info.is_dir():
                if target.exists():
                    if target.is_symlink() or not target.is_dir():
                        raise _fail(
                            "unsafe_resource_archive",
                            "Audio pack directory conflicts with a file",
                        )
                else:
                    target.mkdir(parents=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info, "r") as source:
                actual_total += _copy_audio_member(
                    source,
                    target,
                    info.file_size,
                    _audio_member_limit(relative[-1]),
                    operation,
                )
            if actual_total > declared_total:
                raise _fail("resource_archive_expands_too_large", "Audio pack expands beyond its limit")
        if actual_total != declared_total:
            raise _fail("invalid_resource_archive", "Audio pack length is inconsistent")


def _extract_audio_tar(
    path: Path,
    destination: Path,
    operation: core._Operation,
    prefix: tuple[str, ...],
) -> None:
    """Extract the *prefix* subtree of a streamed tar archive.

    A tar has no central directory, so its selected declared total is checked
    incrementally before each file is materialised. A device that fills up
    surfaces through ``_raise_if_storage_exhausted``.
    """
    destination.mkdir(parents=True)
    seen: set[tuple[str, ...]] = set()
    members = 0
    declared_total = 0
    with core._open_source(path) as (stream, _), tarfile.open(fileobj=stream, mode="r|*") as archive:
        for member in archive:
            operation.check()
            members += 1
            if members > _AUDIO_MEMBER_LIMIT:
                raise _fail(
                    "resource_archive_member_count",
                    "Audio pack member count is outside its limit",
                )
            parts = core._safe_archive_path(member.name, allow_directory_suffix=False)
            if not member.isdir() and not member.isreg():
                raise _fail(
                    "unsafe_resource_archive",
                    "Audio pack contains an encrypted, linked, or special file",
                )
            limit = _accept_audio_member(parts, 0 if member.isdir() else member.size, seen)
            relative = _under_prefix(parts, prefix)
            if relative is None:
                continue
            if member.isreg():
                if member.size > _AUDIO_TOTAL_LIMIT - declared_total:
                    raise _fail(
                        "resource_archive_expands_too_large",
                        "Audio pack expands beyond its limit",
                    )
                declared_total += member.size
            target = destination.joinpath(*relative)
            if member.isdir():
                if target.exists():
                    if target.is_symlink() or not target.is_dir():
                        raise _fail(
                            "unsafe_resource_archive",
                            "Audio pack directory conflicts with a file",
                        )
                else:
                    target.mkdir(parents=True)
                continue
            source = archive.extractfile(member)
            if source is None:
                raise _fail("unsafe_resource_archive", "Audio pack member cannot be read")
            target.parent.mkdir(parents=True, exist_ok=True)
            with source:
                _copy_audio_member(source, target, member.size, limit, operation)
    if declared_total <= 0:
        raise _fail("resource_archive_expands_too_large", "Audio pack expands beyond its limit")


def _extract_audio_archive(
    path: Path,
    destination: Path,
    operation: core._Operation,
    *,
    prefix: tuple[str, ...] = (),
) -> None:
    """Extract the *prefix* subtree of a staged audio archive, ZIP or tar."""
    kind = _audio_archive_kind(path)
    try:
        if kind == "zip":
            _extract_audio_zip(path, destination, operation, prefix)
        else:
            _extract_audio_tar(path, destination, operation, prefix)
    except BridgeProtocolError:
        raise
    except OSError as exc:
        core._raise_if_storage_exhausted(exc)
        raise _fail("resource_install_failed", "Cannot extract audio pack") from exc
    except (zipfile.BadZipFile, tarfile.TarError, lzma.LZMAError, RuntimeError, EOFError) as exc:
        raise _fail("invalid_resource_archive", "Audio pack archive is corrupt") from exc


def _detect_audio_pack_roots(projected: Path) -> list[Path]:
    """Every supported pack in the projected tree, at the outermost level that matches.

    Archives arrive shaped three ways: the pack folder alone, one ``user_files``
    holding all four upstream packs, or either of those inside a wrapper
    directory the archiver added. Children are tested before the directory
    itself because the forvo and jpod_legacy detectors are content sniffs a
    parent of real packs can satisfy by accident.
    """
    from anki_miner.services.audio_packs.formats import detect_pack_format

    current = projected
    for _ in range(_AUDIO_PACK_WRAPPER_DEPTH):
        child_count = 0
        only_child: Path | None = None
        matches: list[Path] = []
        for child in current.iterdir():
            if not child.is_dir() or child.is_symlink() or child.name == "__MACOSX":
                continue
            child_count += 1
            only_child = child
            if detect_pack_format(child) is None:
                continue
            if len(matches) >= _AUDIO_PACK_CANDIDATE_LIMIT:
                raise _fail(
                    "resource_archive_member_count",
                    "Audio archive holds too many packs",
                )
            matches.append(child)
        if matches:
            return sorted(matches)
        if detect_pack_format(current) is not None:
            return [current]
        if child_count != 1:
            return []
        assert only_child is not None
        current = only_child
    return []


def _projected_member_kind(parts: tuple[str, ...], audio_extensions: set[str]) -> str | None:
    """Whether a member matters to detection: its metadata, its shape, or neither.

    Only ``index.json`` and ``entries.json`` decide a format, and they sit at
    most four levels deep — wrapper, ``user_files``, the pack folder, the file.
    Audio members are materialised empty because the detectors only ever ask
    where audio files are, never what is in them.
    """
    name = parts[-1].lower()
    if name in {"index.json", "entries.json"} and len(parts) <= _AUDIO_PACK_WRAPPER_DEPTH:
        return "json"
    if PurePosixPath(name).suffix in audio_extensions:
        return "audio"
    return None


def _project_audio_archive(path: Path, destination: Path, operation: core._Operation) -> None:
    """Reproduce the archive's shape without copying its media.

    The result is a tree of empty audio files plus the real index metadata,
    which is everything ``detect_pack_format`` reads. It costs one pass and a
    few megabytes rather than the multi-gigabyte extraction the import itself
    performs, so the user picks a pack before committing to that.
    """
    from anki_miner.services.audio_packs.formats import AUDIO_EXTENSIONS

    kind = _audio_archive_kind(path)
    try:
        if kind == "zip":
            _project_audio_zip(path, destination, operation, AUDIO_EXTENSIONS)
        else:
            _project_audio_tar(path, destination, operation, AUDIO_EXTENSIONS)
    except BridgeProtocolError:
        raise
    except OSError as exc:
        core._raise_if_storage_exhausted(exc)
        raise _fail("resource_install_failed", "Cannot inspect audio pack") from exc
    except (zipfile.BadZipFile, tarfile.TarError, lzma.LZMAError, RuntimeError, EOFError) as exc:
        raise _fail("invalid_resource_archive", "Audio pack archive is corrupt") from exc


def _project_audio_zip(
    path: Path,
    destination: Path,
    operation: core._Operation,
    audio_extensions: set[str],
) -> None:
    try:
        declared_member_count = core._preflight_zip_member_count(path, _AUDIO_MEMBER_LIMIT)
    except BridgeProtocolError as exc:
        if exc.code == "resource_archive_too_large":
            raise _fail(
                "resource_archive_member_count",
                "Audio pack member count is outside its limit",
            ) from exc
        raise
    with core._open_source(path) as (stream, _), zipfile.ZipFile(stream, "r") as archive:
        infos = archive.infolist()
        if len(infos) != declared_member_count:
            raise _fail(
                "resource_archive_member_count",
                "Audio pack member count is outside its limit",
            )
        declared_total = sum(info.file_size for info in infos if not info.is_dir())
        if declared_total <= 0 or declared_total > _AUDIO_TOTAL_LIMIT:
            raise _fail("resource_archive_expands_too_large", "Audio pack expands beyond its limit")
        destination.mkdir(parents=True)
        seen: set[tuple[str, ...]] = set()
        metadata_files = 0
        for info in infos:
            operation.check()
            parts = core._safe_archive_path(info.filename, allow_directory_suffix=info.is_dir())
            if info.flag_bits & 0x1 or not core._zip_entry_is_safe_type(info):
                raise _fail(
                    "unsafe_resource_archive",
                    "Audio pack contains an encrypted, linked, or special file",
                )
            _accept_audio_member(parts, 0 if info.is_dir() else info.file_size, seen)
            if info.is_dir():
                continue
            member_kind = _projected_member_kind(parts, audio_extensions)
            if member_kind is None:
                continue
            target = destination.joinpath(*parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if member_kind == "audio":
                target.touch(exist_ok=False)
                continue
            metadata_files = _accept_projected_metadata(metadata_files)
            with archive.open(info, "r") as source:
                _copy_audio_member(source, target, info.file_size, _AUDIO_JSON_LIMIT, operation)


def _project_audio_tar(
    path: Path,
    destination: Path,
    operation: core._Operation,
    audio_extensions: set[str],
) -> None:
    destination.mkdir(parents=True)
    seen: set[tuple[str, ...]] = set()
    members = 0
    metadata_files = 0
    declared_total = 0
    with core._open_source(path) as (stream, _), tarfile.open(fileobj=stream, mode="r|*") as archive:
        for member in archive:
            operation.check()
            members += 1
            if members > _AUDIO_MEMBER_LIMIT:
                raise _fail(
                    "resource_archive_member_count",
                    "Audio pack member count is outside its limit",
                )
            parts = core._safe_archive_path(member.name, allow_directory_suffix=False)
            if not member.isdir() and not member.isreg():
                raise _fail(
                    "unsafe_resource_archive",
                    "Audio pack contains an encrypted, linked, or special file",
                )
            _accept_audio_member(parts, 0 if member.isdir() else member.size, seen)
            if member.isdir():
                continue
            if member.size > _AUDIO_TOTAL_LIMIT - declared_total:
                raise _fail(
                    "resource_archive_expands_too_large",
                    "Audio pack expands beyond its limit",
                )
            declared_total += member.size
            member_kind = _projected_member_kind(parts, audio_extensions)
            if member_kind is None:
                continue
            target = destination.joinpath(*parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if member_kind == "audio":
                target.touch(exist_ok=False)
                continue
            metadata_files = _accept_projected_metadata(metadata_files)
            source = archive.extractfile(member)
            if source is None:
                raise _fail("unsafe_resource_archive", "Audio pack member cannot be read")
            with source:
                _copy_audio_member(source, target, member.size, _AUDIO_JSON_LIMIT, operation)
    if declared_total <= 0:
        raise _fail("resource_archive_expands_too_large", "Audio pack expands beyond its limit")


def _accept_projected_metadata(count: int) -> int:
    """Bound how much index metadata a preflight will read.

    Every pack contributes one index file, so an archive claiming dozens is not
    a collection — it is an attempt to make detection do the copying that the
    projection exists to avoid.
    """
    if count >= _AUDIO_PACK_METADATA_LIMIT:
        raise _fail(
            "resource_archive_member_count",
            "Audio archive declares too many pack indexes",
        )
    return count + 1


def preflight_audio_pack(payload: Mapping[str, object]) -> str:
    core._exact(
        payload,
        {"operationId", "sourcePath", "displayName"},
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    display_name = _display_name(payload["displayName"], label="displayName")
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            projected = operation_root / "projected"
            _project_audio_archive(source, projected, operation)
            pack_roots = _detect_audio_pack_roots(projected)
            if not pack_roots:
                raise _fail(
                    "audio_pack_none_detected",
                    "The archive holds no supported local audio pack",
                )

            from anki_miner.services.audio_packs.formats import detect_pack_format
            from anki_miner.services.audio_packs.importer import derive_pack_id

            packs: list[dict[str, object]] = []
            for pack_root in pack_roots:
                operation.check()
                # A pack that is itself the archive root has no folder name to
                # derive from, so the document name stands in for it.
                folder_name = PurePosixPath(display_name).stem if pack_root == projected else pack_root.name
                pack_id = derive_pack_id(folder_name)
                if pack_id == "jpod101":
                    raise _fail(
                        "audio_pack_id_reserved",
                        "Derived audio-pack ID 'jpod101' is reserved",
                    )
                try:
                    core._slot_id(pack_id)
                except BridgeProtocolError as exc:
                    raise _fail(
                        "audio_pack_import_failed",
                        "Derived audio-pack ID is invalid",
                    ) from exc
                packs.append(
                    {
                        "packId": pack_id,
                        # Joined from parts rather than as_posix(): a root that
                        # is the archive itself relativises to ".", which the
                        # prefix guard rejects. Its parts are empty.
                        "packPath": "/".join(pack_root.relative_to(projected).parts),
                        "format": detect_pack_format(pack_root) or "",
                    },
                )
            if len({pack["packId"] for pack in packs}) != len(packs):
                raise _fail(
                    "audio_pack_import_failed",
                    "The archive holds two packs that derive the same ID",
                )
            return encode_message("resource.audiopack.preflighted", {"packs": packs})
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


def _validate_audio_index(db_path: Path, content: Path, operation: core._Operation) -> None:
    from anki_miner.services.audio_packs.formats import AUDIO_EXTENSIONS

    root = content.resolve()
    try:
        connection = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
        try:
            cursor = connection.execute("SELECT file FROM entries ORDER BY id")
            for index, row in enumerate(cursor, 1):
                if index % 5000 == 0:
                    operation.check()
                value = row[0]
                if not isinstance(value, str) or not value or "\\" in value:
                    raise _fail(
                        "audio_pack_import_failed",
                        "Audio pack index contains an unsafe media path",
                    )
                relative = PurePosixPath(value)
                if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
                    raise _fail(
                        "audio_pack_import_failed",
                        "Audio pack index contains an unsafe media path",
                    )
                # Containment is already proved by the component check above —
                # no absolute path, no "..", no empty part — so one lstat per row
                # is enough. Resolving every row instead costs a full symlink
                # walk per entry across hundreds of thousands of rows.
                candidate = root.joinpath(*relative.parts)
                if candidate.suffix.lower() not in AUDIO_EXTENSIONS:
                    raise _fail(
                        "audio_pack_import_failed",
                        "Audio pack index references missing media",
                    )
                try:
                    scanned = candidate.lstat()
                except OSError:
                    raise _fail(
                        "audio_pack_import_failed",
                        "Audio pack index references missing media",
                    ) from None
                if not stat.S_ISREG(scanned.st_mode):
                    raise _fail(
                        "audio_pack_import_failed",
                        "Audio pack index references missing media",
                    )
        finally:
            connection.close()
    except sqlite3.Error as exc:
        core._raise_if_storage_exhausted(exc)
        raise _fail("audio_pack_import_failed", "Audio pack index is invalid") from exc


def import_audio_pack(payload: Mapping[str, object]) -> str:
    core._exact(
        payload,
        {"operationId", "sourcePath", "packId", "packPath", "overwrite"},
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    pack_id = core._slot_id(payload["packId"])
    if pack_id == "jpod101":
        raise _fail("invalid_resource_request", "packId is reserved")
    pack_prefix = _audio_pack_prefix(payload["packPath"])
    overwrite = _boolean(payload["overwrite"], label="overwrite")
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            # The caller already staged this ZIP into private storage, so read it
            # where it lies. A second copy would put three multi-gigabyte trees on
            # disk at once (staged, copy, extracted) for a digest we can take in
            # one pass.
            copied = core._hash_archive(
                source,
                operation,
                maximum_bytes=_AUDIO_ARCHIVE_LIMIT,
            )
            extracted = operation_root / "extracted"
            # Only the chosen subtree is extracted, so importing one pack out of
            # the four-pack collection costs that pack's bytes, not the whole
            # archive's.
            _extract_audio_archive(copied.path, extracted, operation, prefix=pack_prefix)
            pack_roots = _detect_audio_pack_roots(extracted)
            if len(pack_roots) != 1:
                raise _fail(
                    "audio_pack_none_detected",
                    "The chosen path does not hold one supported local audio pack",
                )
            pack_root = pack_roots[0]
            publication_parent = operation_root / "publication"
            candidate = publication_parent / pack_id
            candidate.mkdir(parents=True)
            content = candidate / "content"
            pack_root.rename(content)
            if extracted.exists():
                core._safe_rmtree(extracted)
            operation.check()

            from anki_miner.exceptions import SetupError
            from anki_miner.services.audio_packs import storage
            from anki_miner.services.audio_packs.importer import (
                import_audio_pack as desktop_import,
            )

            index_root = operation_root / "index"
            try:
                result = desktop_import(
                    content,
                    index_root,
                    pack_id=pack_id,
                    cancel_check=operation.cancelled.is_set,
                    overwrite=False,
                )
            except (
                SetupError,
                ValueError,
                UnicodeError,
                json.JSONDecodeError,
                OSError,
                sqlite3.Error,
            ) as exc:
                operation.check()
                core._raise_if_storage_exhausted(exc)
                raise _fail(
                    "audio_pack_index_malformed",
                    "The chosen pack's index could not be read",
                ) from exc
            operation.check()
            built = index_root / pack_id
            db_path = built / "index.sqlite"
            try:
                metadata = storage.read_meta(db_path)
                metadata["pack_dir"] = str(_audio_root(home) / pack_id / "content")
                storage.write_meta(db_path, metadata)
            except (OSError, sqlite3.Error) as exc:
                operation.check()
                core._raise_if_storage_exhausted(exc)
                raise _fail(
                    "audio_pack_import_failed",
                    "Audio pack index could not be persisted",
                ) from exc
            _validate_audio_index(db_path, content, operation)
            for name in ("index.sqlite", "meta.json"):
                (built / name).rename(candidate / name)
                _fsync_file(candidate / name)
            _write_sidecar(
                candidate / _ANDROID_SIDECAR,
                {
                    "schemaVersion": 1,
                    "kind": "audio-pack",
                    "packId": pack_id,
                    "archiveSha256": copied.sha256,
                    "archiveSizeBytes": copied.size_bytes,
                },
            )
            _fsync_tree_directories(content)
            core._fsync_directory(candidate)
            _publish_indexed_dir(
                candidate,
                home=home,
                kind="audio-pack",
                identity=pack_id,
                operation_id=operation_id,
                overwrite=overwrite,
                final_root=_audio_root(home),
                require_content=True,
            )
            return encode_message(
                "resource.audiopack.imported",
                {
                    "packId": result.pack_id,
                    "sourceName": result.source_name,
                    "format": result.format,
                    "entryCount": result.entry_count,
                    "archiveSha256": copied.sha256,
                },
            )
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


def _parse_known_words_copy(source: Path, source_format: str, operation: object, operation_root: Path):
    copied = core._copy_archive(
        source,
        operation_root / f"known-words.{source_format}",
        operation,
        maximum_bytes=_KNOWN_WORD_FILE_LIMIT,
    )
    from anki_miner.services.known_words_import import (
        KnownWordsImportError,
        parse_known_words_file,
    )

    try:
        parsed = parse_known_words_file(
            copied.path,
            max_words=_MAX_KNOWN_WORDS,
            max_word_bytes=_MAX_WORD_BYTES,
            cancel_check=operation.cancelled.is_set,
        )
    except KnownWordsImportError as exc:
        operation.check()
        raise _fail(
            "known_words_import_failed",
            "The selected file contains no supported known-word export",
        ) from exc
    from anki_miner.utils.ja_normalize import (
        normalize_for_tokenization,
        standardize_kanji_variants,
    )

    parsed = replace(
        parsed,
        words=frozenset(standardize_kanji_variants(normalize_for_tokenization(word)) for word in parsed.words),
    )
    if len(parsed.words) > _MAX_KNOWN_WORDS or any(
        not word
        or len(word.encode("utf-8")) > _MAX_WORD_BYTES
        or "\x00" in word
        or not _KNOWN_WORD_LINE_SEPARATORS.isdisjoint(word)
        for word in parsed.words
    ):
        raise _fail("known_words_import_failed", "Known-word import exceeds its limits")
    return parsed


def preview_known_words(payload: Mapping[str, object]) -> str:
    core._exact(
        payload,
        {"operationId", "sourcePath", "sourceFormat"},
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    source_format = _format(payload["sourceFormat"], _KNOWN_WORD_FORMATS, label="sourceFormat")
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            parsed = _parse_known_words_copy(source, source_format, operation, operation_root)
            operation.check()
            return encode_message(
                "resource.knownwords.previewed",
                {
                    "format": parsed.format_key,
                    "importedCount": len(parsed.words),
                    "totalEntries": parsed.total_entries,
                    "isGeneric": parsed.format_key == "generic",
                    "sampleWords": sorted(parsed.words)[:20],
                },
            )
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


def import_known_words(payload: Mapping[str, object]) -> str:
    core._exact(
        payload,
        {"operationId", "sourcePath", "sourceFormat"},
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    source_format = _format(payload["sourceFormat"], _KNOWN_WORD_FORMATS, label="sourceFormat")
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            from anki_miner.services.known_word_db import KnownWordDB

            parsed = _parse_known_words_copy(source, source_format, operation, operation_root)
            operation.check()
            db_path = home / "known_words.db"
            if db_path.exists() and (db_path.is_symlink() or not db_path.is_file()):
                raise _fail("known_words_database_unsafe", "Known-word database path is unsafe")
            database = KnownWordDB(db_path)
            database.initialize()
            # Last point before the write becomes durable. Parsing and schema setup
            # both run after the previous check, so without this a Cancel delivered
            # in that window still committed the import.
            operation.check()
            added_count = database.add_words(set(parsed.words), source="user")
            _fsync_file(db_path)
            core._fsync_directory(home)
            return encode_message(
                "resource.knownwords.imported",
                {
                    "format": parsed.format_key,
                    "importedCount": len(parsed.words),
                    "newRowCount": added_count,
                    "totalEntries": parsed.total_entries,
                    "isGeneric": parsed.format_key == "generic",
                },
            )
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


def _known_words_database(home: Path):
    from anki_miner.services.known_word_db import KnownWordDB

    db_path = home / "known_words.db"
    if db_path.exists() and (db_path.is_symlink() or not db_path.is_file()):
        raise _fail("known_words_database_unsafe", "Known-word database path is unsafe")
    database = KnownWordDB(db_path)
    database.initialize()
    if not _known_words_inventory(home)["schemaOk"]:
        raise _fail("known_words_database_unsafe", "Known-word database schema is invalid")
    return database, db_path


def _bounded_non_negative_int(value: object, *, label: str, maximum: int) -> int:
    if type(value) is not int or value < 0 or value > maximum:
        raise _fail("invalid_resource_request", f"{label} is invalid")
    return value


def _known_word_query(value: object) -> str:
    if not isinstance(value, str) or "\x00" in value:
        raise _fail("invalid_resource_request", "query is invalid")
    try:
        encoded = value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise _fail("invalid_resource_request", "query is invalid") from exc
    if len(encoded) > _MAX_WORD_BYTES:
        raise _fail("invalid_resource_request", "query exceeds its size limit")
    return value


def list_known_words(payload: Mapping[str, object]) -> str:
    core._exact(payload, {"operationId", "query", "offset", "limit"}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    query = _known_word_query(payload["query"])
    offset = _bounded_non_negative_int(payload["offset"], label="offset", maximum=_MAX_KNOWN_WORDS)
    limit = _bounded_non_negative_int(payload["limit"], label="limit", maximum=_MAX_KNOWN_WORD_PAGE)
    if limit == 0:
        raise _fail("invalid_resource_request", "limit is invalid")
    home = Path(require_initialized())
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        database, db_path = _known_words_database(home)
        del database
        search_query = unicodedata.normalize("NFC", query)
        escaped = search_query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        where = "source = 'user'"
        parameters: list[object] = []
        if search_query:
            where += " AND lemma LIKE ? ESCAPE '\\' COLLATE NOCASE"
            parameters.append(f"%{escaped}%")
        connection = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
        try:
            total = int(connection.execute(f"SELECT COUNT(*) FROM known_words WHERE {where}", parameters).fetchone()[0])
            rows = connection.execute(
                f"SELECT lemma FROM known_words WHERE {where} ORDER BY lemma LIMIT ? OFFSET ?",
                [*parameters, limit, offset],
            ).fetchall()
        finally:
            connection.close()
        operation.check()
        words = [str(row[0]) for row in rows]
        return encode_message(
            "resource.knownwords.listed",
            {
                "query": query,
                "offset": offset,
                "totalCount": total,
                "words": words,
                "hasMore": offset + len(words) < total,
            },
        )


def _known_word_list(value: object) -> list[str]:
    if not isinstance(value, list) or not value or len(value) > _MAX_KNOWN_WORD_MUTATION:
        raise _fail("invalid_resource_request", "words are invalid")
    words = [_known_word_query(item) for item in value]
    if any(not word for word in words) or len(set(words)) != len(words):
        raise _fail("invalid_resource_request", "words are invalid")
    return words


def remove_known_words(payload: Mapping[str, object]) -> str:
    core._exact(payload, {"operationId", "words"}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    words = _known_word_list(payload["words"])
    home = Path(require_initialized())
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        database, db_path = _known_words_database(home)
        operation.check()
        removed = database.remove_words(set(words), source="user")
        _fsync_file(db_path)
        core._fsync_directory(home)
        return encode_message("resource.knownwords.removed", {"removedCount": removed})


def reset_known_words(payload: Mapping[str, object]) -> str:
    core._exact(payload, {"operationId", "scope"}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    scope = core._bounded_text(payload["scope"], name="scope", max_bytes=8)
    if scope not in {"user", "cache"}:
        raise _fail("invalid_resource_request", "scope is invalid")
    home = Path(require_initialized())
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        database, db_path = _known_words_database(home)
        operation.check()
        removed = database.clear_user() if scope == "user" else database.clear(preserve_user=True)
        _fsync_file(db_path)
        core._fsync_directory(home)
        return encode_message(
            "resource.knownwords.reset",
            {"scope": scope, "removedCount": removed},
        )


def export_known_words(payload: Mapping[str, object]) -> str:
    core._exact(payload, {"operationId"}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        _database, db_path = _known_words_database(home)
        export_path = operation_root / "known_words.txt"
        size_bytes = 0
        exported_count = 0
        try:
            connection = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
            try:
                cursor = connection.execute(
                    "SELECT lemma FROM known_words WHERE source = ? ORDER BY lemma",
                    ("user",),
                )
                with export_path.open("xb", buffering=0) as stream:
                    for row in cursor:
                        if exported_count % 1024 == 0:
                            operation.check()
                        word = row[0]
                        encoded = f"{word}\n".encode()
                        size_bytes += len(encoded)
                        if size_bytes > _KNOWN_WORD_EXPORT_LIMIT:
                            raise _fail(
                                "known_words_export_failed",
                                "Known-word export exceeds its limit",
                            )
                        core._write_all(stream, encoded)
                        exported_count += 1
                    os.fsync(stream.fileno())
            finally:
                connection.close()
            core._fsync_directory(operation_root)
            return encode_message(
                "resource.knownwords.exported",
                {
                    "exportPath": str(export_path),
                    "exportedCount": exported_count,
                    "sizeBytes": size_bytes,
                },
            )
        except Exception:
            if operation_root.exists():
                core._safe_rmtree(operation_root)
            raise


def _invalid_pitch_inventory_entry(source_id: str) -> dict[str, object]:
    return {
        "sourceId": source_id,
        "sourceName": source_id,
        "sourceRevision": "",
        "format": "unknown",
        "entryCount": 0,
        "schemaOk": False,
        "schemaVersion": 0,
        # An entry only reaches here when its index could not be read at all,
        # which is the "missing" case, not the rebuildable "stale" one.
        "rebuildSourcePath": None,
    }


def _migrate_legacy_pitch_csv(home: Path) -> None:
    """Publish v0.1.8's single pitch CSV as desktop's legacy source.

    Desktop ``legacy_migration.py`` leaves the source in place for downgrade
    safety and uses the stable ``legacy-pitch`` identity. Android mirrors that
    contract while routing publication through its crash-recoverable slot swap.
    Invalid legacy data remains untouched and is surfaced by inventory.
    """

    legacy = home / "pitch_accent.csv"
    final = _pitch_root(home) / _LEGACY_PITCH_SOURCE_ID
    if _valid_indexed_dir(final, require_content=False):
        return
    try:
        if legacy.is_symlink() or not legacy.is_file():
            return
    except OSError:
        return

    operation_id = "legacy-pitch-migration"
    operation_root = _work_root(home, operation_id)
    operation = core._Operation(operation_id)
    try:
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        archive = core._hash_archive(
            legacy,
            operation,
            maximum_bytes=_PITCH_TEXT_LIMIT,
        )
        from anki_miner.services.pitch_accent.source_importer import (
            import_pitch_source,
        )

        import_root = operation_root / "publication"
        import_pitch_source(
            legacy,
            import_root,
            source_id=_LEGACY_PITCH_SOURCE_ID,
            source_name=_LEGACY_PITCH_SOURCE_NAME,
        )
        candidate = import_root / _LEGACY_PITCH_SOURCE_ID
        _write_sidecar(
            candidate / _ANDROID_SIDECAR,
            {
                "schemaVersion": 1,
                "kind": "pitch",
                "sourceId": _LEGACY_PITCH_SOURCE_ID,
                "archiveSha256": archive.sha256,
                "archiveSizeBytes": archive.size_bytes,
            },
        )
        _fsync_small_tree(candidate)
        _publish_indexed_dir(
            candidate,
            home=home,
            kind="pitch",
            identity=_LEGACY_PITCH_SOURCE_ID,
            operation_id=operation_id,
            overwrite=True,
            final_root=_pitch_root(home),
            require_content=False,
        )
    except Exception:
        # Desktop intentionally treats a malformed legacy file as a non-fatal
        # startup condition. Inventory below makes the failed migration visible.
        # instrumentation: intentionally silent — inventory owns malformed legacy visibility
        pass
    finally:
        if operation_root.exists():
            with contextlib.suppress(Exception):
                core._safe_rmtree(operation_root)


def _rebuild_source_path(slot: Path) -> str | None:
    """The persisted source copy as a wire string, or None when unrecoverable."""
    copy = _persisted_source_copy(slot)
    return str(copy) if copy is not None else None


def _persisted_source_copy(slot: Path) -> Path | None:
    """The ``source.<ext>`` the importer kept beside ``index.sqlite``.

    Every engine source importer copies its input in next to the index so a
    later reimport does not need the user to re-pick the original file. That
    copy is what makes a schema-stale slot *rebuildable* rather than lost, and
    it is the whole reason a re-pin does not force a manual re-import.
    """
    for suffix in (".zip", ".csv", ".tsv", ".txt"):
        candidate = slot / f"source{suffix}"
        try:
            if candidate.is_file() and not candidate.is_symlink():
                return candidate
        except OSError:
            logger.debug("Failed to probe a persisted source copy", exc_info=True)
    return None


def _pitch_inventory(home: Path) -> list[dict[str, object]]:
    _migrate_legacy_pitch_csv(home)
    legacy = home / "pitch_accent.csv"
    legacy_occupied = legacy.exists() or legacy.is_symlink()
    root = _pitch_root(home)
    if not root.exists():
        return [_invalid_pitch_inventory_entry(_LEGACY_PITCH_SOURCE_ID)] if legacy_occupied else []
    if root.is_symlink() or not root.is_dir():
        raise _fail("resource_inventory_failed", "Pitch resource root is unsafe")
    result: list[dict[str, object]] = []
    for child in sorted(root.iterdir(), key=lambda item: item.name):
        if (
            child.name.startswith(".")
            or not core._SLOT_ID_RE.fullmatch(child.name)
            or child.is_symlink()
            or not child.is_dir()
        ):
            continue
        meta = _read_index_meta(child)
        if meta is None:
            result.append(_invalid_pitch_inventory_entry(child.name))
            continue
        try:
            version = int(meta.get("schema_version", "0"))
            count = int(meta.get("entry_count", "0"))
        except ValueError:
            version = 0
            count = 0
        result.append(
            {
                "sourceId": child.name,
                "sourceName": meta.get("source_name", child.name),
                "sourceRevision": meta.get("source_revision", ""),
                "format": meta.get("format", "unknown"),
                "entryCount": max(count, 0),
                # The pitch registry compares on exact equality, so anything
                # older or newer fails closed rather than being read with the
                # wrong row shape.
                "schemaOk": version == core._PITCH_SCHEMA_VERSION,
                "schemaVersion": max(version, 0),
                "rebuildSourcePath": _rebuild_source_path(child),
            }
        )
    if legacy_occupied and not any(item["sourceId"] == _LEGACY_PITCH_SOURCE_ID for item in result):
        result.append(_invalid_pitch_inventory_entry(_LEGACY_PITCH_SOURCE_ID))
    result.sort(key=lambda item: str(item["sourceId"]))
    return result


def _known_words_inventory(home: Path) -> dict[str, object]:
    database = home / "known_words.db"
    counts = {"user": 0, "anki": 0, "mined": 0}
    total = 0
    if not database.exists():
        return {
            "totalCount": 0,
            "userCount": 0,
            "ankiCount": 0,
            "minedCount": 0,
            "schemaOk": True,
        }
    if database.is_symlink() or not database.is_file():
        return {
            "totalCount": 0,
            "userCount": 0,
            "ankiCount": 0,
            "minedCount": 0,
            "schemaOk": False,
        }
    try:
        connection = sqlite3.connect(f"file:{database.as_posix()}?mode=ro", uri=True)
        try:
            columns = {row[1] for row in connection.execute("PRAGMA table_info(known_words)")}
            if not {"lemma", "source"}.issubset(columns):
                raise sqlite3.DatabaseError("missing known_words schema")
            for source, count in connection.execute("SELECT source, COUNT(*) FROM known_words GROUP BY source"):
                count = int(count)
                total += count
                if source in counts:
                    counts[source] = count
        finally:
            connection.close()
        return {
            "totalCount": total,
            "userCount": counts["user"],
            "ankiCount": counts["anki"],
            "minedCount": counts["mined"],
            "schemaOk": True,
        }
    except (sqlite3.Error, OSError, TypeError, ValueError):
        return {
            "totalCount": 0,
            "userCount": 0,
            "ankiCount": 0,
            "minedCount": 0,
            "schemaOk": False,
        }


def _read_index_meta(index_dir: Path) -> dict[str, str] | None:
    """Read one index's bounded sidecar, with a read-only SQLite fallback."""

    db_path = index_dir / "index.sqlite"
    try:
        if not db_path.is_file() or db_path.is_symlink() or db_path.stat().st_size <= 0:
            return None
    except OSError:
        return None
    sidecar = index_dir / "meta.json"
    try:
        if sidecar.is_file() and not sidecar.is_symlink() and sidecar.stat().st_size <= core._MAX_MANIFEST_BYTES:
            raw = json.loads(sidecar.read_text(encoding="utf-8"))
            if (
                isinstance(raw, dict)
                and len(raw) <= 64
                and all(
                    isinstance(key, str)
                    and isinstance(value, str)
                    and len(key.encode("utf-8")) <= 128
                    and len(value.encode("utf-8")) <= 4096
                    for key, value in raw.items()
                )
            ):
                return raw
    except (OSError, UnicodeError, json.JSONDecodeError):
        logger.debug("Failed to read resource index sidecar metadata", exc_info=True)
    try:
        connection = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
        try:
            rows = connection.execute("SELECT key, value FROM meta LIMIT 65").fetchall()
        finally:
            connection.close()
        if len(rows) <= 64 and all(
            isinstance(key, str)
            and isinstance(value, str)
            and len(key.encode("utf-8")) <= 128
            and len(value.encode("utf-8")) <= 4096
            for key, value in rows
        ):
            return dict(rows)
    except (sqlite3.Error, OSError):
        logger.debug("Failed to read resource index SQLite metadata", exc_info=True)
    return None


def _frequency_inventory(home: Path) -> list[dict[str, object]]:
    root = _frequency_root(home)
    if not root.exists():
        return []
    if root.is_symlink() or not root.is_dir():
        raise _fail("resource_inventory_failed", "Frequency resource root is unsafe")
    result: list[dict[str, object]] = []
    for child in sorted(root.iterdir(), key=lambda item: item.name):
        if (
            child.name.startswith(".")
            or not core._SLOT_ID_RE.fullmatch(child.name)
            or child.is_symlink()
            or not child.is_dir()
        ):
            continue
        meta = _read_index_meta(child)
        if meta is None:
            continue
        try:
            version = int(meta.get("schema_version", "0"))
            count = int(meta.get("entry_count", "0"))
        except ValueError:
            version = 0
            count = 0
        result.append(
            {
                "sourceId": child.name,
                "sourceName": meta.get("source_name", child.name),
                "format": meta.get("format", "unknown"),
                "entryCount": max(count, 0),
                # Not a range: the frequency registry compares on exact
                # equality, so an older index is stale, not merely readable.
                "schemaOk": version == core._FREQUENCY_SCHEMA_VERSION,
                "schemaVersion": max(version, 0),
                "isCategorical": meta.get("is_categorical") == "1",
                "rebuildSourcePath": _rebuild_source_path(child),
            }
        )
    return result


def _audio_inventory(home: Path) -> list[dict[str, object]]:
    root = _audio_root(home)
    if not root.exists():
        return []
    if root.is_symlink() or not root.is_dir():
        raise _fail("resource_inventory_failed", "Audio-pack resource root is unsafe")
    result: list[dict[str, object]] = []
    for child in sorted(root.iterdir(), key=lambda item: item.name):
        if (
            child.name.startswith(".")
            or ".bak-" in child.name
            or not core._SLOT_ID_RE.fullmatch(child.name)
            or child.is_symlink()
            or not child.is_dir()
        ):
            continue
        meta = _read_index_meta(child)
        if meta is None or meta.get("pack_id", child.name) != child.name:
            result.append(
                {
                    "packId": child.name,
                    "sourceName": child.name,
                    "format": "unknown",
                    "entryCount": 0,
                    "contentAvailable": False,
                }
            )
            continue
        try:
            version = int(meta.get("schema_version", "0"))
            count = int(meta.get("entry_count", "0"))
        except ValueError:
            version = 0
            count = 0
        expected_content = child / "content"
        configured_content = Path(meta.get("pack_dir", ""))
        content_available = False
        try:
            # Folder packs only. The engine also knows an ``android_db`` format
            # whose audio lives in an external database rather than a content
            # directory, but Android has no way to register one.
            content_available = (
                version == core._AUDIO_PACK_SCHEMA_VERSION
                and count > 0
                and expected_content.is_dir()
                and not expected_content.is_symlink()
                and configured_content == expected_content
            )
        except OSError:
            logger.debug("Failed to inspect audio-pack content availability", exc_info=True)
        result.append(
            {
                "packId": child.name,
                "sourceName": meta.get("source", child.name),
                "format": meta.get("format", "unknown"),
                "entryCount": max(count, 0),
                "contentAvailable": content_available,
            }
        )
    return result


def _wordset_inventory() -> list[dict[str, object]]:
    from importlib.resources import files

    root = files("anki_miner.resources.wordsets")
    fallbacks = {
        "surnames": "Surnames",
        "given-names": "Given names",
        "place-names": "Place names",
        "org-product": "Company / Product / Org",
    }
    result: list[dict[str, object]] = []
    for wordset_id, fallback in fallbacks.items():
        label = fallback
        count = 0
        try:
            with root.joinpath(f"{wordset_id}.txt").open("r", encoding="utf-8") as stream:
                for line in stream:
                    stripped = line.strip()
                    if not stripped:
                        continue
                    if not stripped.startswith("#"):
                        break
                    body = stripped.removeprefix("#").strip()
                    key, separator, value = body.partition(":")
                    if not separator:
                        continue
                    if key.strip().lower() == "label":
                        label = value.strip() or fallback
                    elif key.strip().lower() == "count":
                        try:
                            count = max(int(value.strip()), 0)
                        except ValueError:
                            count = 0
        except (FileNotFoundError, OSError, UnicodeError):
            continue
        result.append(
            {
                "wordsetId": wordset_id,
                "displayName": label,
                "entryCount": count,
            }
        )
    return result


def list_local_resources(payload: Mapping[str, object]) -> str:
    core._exact(payload, set(), code="invalid_resource_request")
    home = Path(require_initialized())
    with core._PROMOTION_LOCK:
        _recover_indexed_backups(
            home,
            kind="frequency",
            final_root=_frequency_root(home),
            require_content=False,
        )
        _recover_indexed_backups(
            home,
            kind="audio-pack",
            final_root=_audio_root(home),
            require_content=True,
        )

    return encode_message(
        "resource.local.listed",
        {
            "frequencies": _frequency_inventory(home),
            "pitchSources": _pitch_inventory(home),
            "audioPacks": _audio_inventory(home),
            "knownWords": _known_words_inventory(home),
            "wordsets": _wordset_inventory(),
        },
    )


def recover_local_resources(home: Path) -> None:
    """Recover indexed-resource swaps while the caller owns the core lock."""

    _recover_indexed_backups(
        home,
        kind="frequency",
        final_root=_frequency_root(home),
        require_content=False,
    )
    _recover_indexed_backups(
        home,
        kind="pitch",
        final_root=_pitch_root(home),
        require_content=False,
    )
    _recover_indexed_backups(
        home,
        kind="audio-pack",
        final_root=_audio_root(home),
        require_content=True,
    )
