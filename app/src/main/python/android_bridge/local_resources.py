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
import hashlib
import json
import os
import shutil
import sqlite3
import stat
import zipfile
from collections.abc import Mapping
from pathlib import Path, PurePosixPath

from . import resources as core
from .bootstrap import require_initialized
from .protocol import BridgeProtocolError, encode_message

_FREQUENCY_FORMATS = frozenset({"zip", "csv", "tsv", "txt"})
_PITCH_FORMATS = frozenset({"zip", "csv", "tsv"})
_KNOWN_WORD_FORMATS = frozenset({"json", "csv", "tsv", "txt"})

_FREQUENCY_ARCHIVE_LIMIT = 512 * 1024 * 1024
_FREQUENCY_TEXT_LIMIT = 64 * 1024 * 1024
_PITCH_ARCHIVE_LIMIT = 512 * 1024 * 1024
_PITCH_TEXT_LIMIT = 64 * 1024 * 1024
_KNOWN_WORD_FILE_LIMIT = 32 * 1024 * 1024
_AUDIO_ARCHIVE_LIMIT = 2 * 1024 * 1024 * 1024
_AUDIO_MEMBER_LIMIT = 200_000
_AUDIO_TOTAL_LIMIT = 8 * 1024 * 1024 * 1024
_AUDIO_FILE_LIMIT = 512 * 1024 * 1024
_AUDIO_JSON_LIMIT = 32 * 1024 * 1024
_MAX_KNOWN_WORDS = 500_000
_MAX_WORD_BYTES = 1024
_MAX_KNOWN_WORD_PAGE = 200
_MAX_KNOWN_WORD_MUTATION = 256
_KNOWN_WORD_EXPORT_LIMIT = 512 * 1024 * 1024
_KNOWN_WORD_LINE_SEPARATORS = frozenset("\n\r\v\f\x1c\x1d\x1e\x85\u2028\u2029")
_PITCH_SIDECAR = "pitch_accent.android-resource.json"
_ANDROID_SIDECAR = "android-resource.json"


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
        raise _fail("resource_install_failed", "Cannot open imported resource") from exc
    try:
        os.fsync(descriptor)
    except OSError as exc:
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
            if source_format == "zip":
                core._validate_zip_streamed(
                    copied.path,
                    operation,
                    member_limit=None,
                    total_limit=core._engine_uncompressed_limit(),
                    file_limit=None,
                    require_root_index=False,
                )
            operation.check()
            from anki_miner.exceptions import SetupError
            from anki_miner.services.frequency.source_importer import (
                import_frequency_source,
            )

            import_root = operation_root / "publication"
            try:
                result = import_frequency_source(
                    copied.path,
                    import_root,
                    source_id=source_id,
                    source_name=source_name,
                    cancel_check=operation.cancelled.is_set,
                )
            except (SetupError, UnicodeError, csv.Error, OSError) as exc:
                operation.check()
                raise _fail(
                    "frequency_import_failed",
                    "The selected file is not a supported frequency source",
                ) from exc
            operation.check()
            candidate = import_root / source_id
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
            return encode_message(
                "resource.frequency.imported",
                {
                    "sourceId": result.source_id,
                    "sourceName": result.source_name,
                    "sourceRevision": result.source_revision,
                    "format": result.format,
                    "entryCount": result.entry_count,
                    "skippedDisplayOnly": result.skipped_display_only,
                    "skippedMalformed": result.skipped_malformed,
                    "convertedToRanks": result.converted_to_ranks,
                    "isCategorical": result.is_categorical,
                    "archiveSha256": copied.sha256,
                },
            )
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


def _pitch_sidecar_payload(
    *,
    source_name: str,
    source_revision: str,
    source_format: str,
    entry_count: int,
    file_path: Path,
) -> dict[str, object]:
    digest = hashlib.sha256()
    size = 0
    with file_path.open("rb") as stream:
        while True:
            chunk = stream.read(core._COPY_CHUNK_BYTES)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return {
        "schemaVersion": 1,
        "sourceName": source_name,
        "sourceRevision": source_revision,
        "sourceFormat": source_format,
        "entryCount": entry_count,
        "fileSizeBytes": size,
        "fileSha256": digest.hexdigest(),
    }


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(core._COPY_CHUNK_BYTES)
            if not chunk:
                return digest.hexdigest()
            digest.update(chunk)


def import_pitch(payload: Mapping[str, object]) -> str:
    core._exact(
        payload,
        {"operationId", "sourcePath", "sourceName", "sourceFormat", "overwrite"},
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    requested_name = _display_name(payload["sourceName"], label="sourceName")
    source_format = _format(payload["sourceFormat"], _PITCH_FORMATS, label="sourceFormat")
    overwrite = _boolean(payload["overwrite"], label="overwrite")
    home = Path(require_initialized())
    final = home / "pitch_accent.csv"
    sidecar = home / _PITCH_SIDECAR
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
            candidate = operation_root / "pitch_accent.csv"
            source_name = requested_name
            source_revision = ""
            skipped_display_only = 0
            skipped_malformed = 0
            from anki_miner.exceptions import SetupError
            from anki_miner.services.pitch_accent_service import PitchAccentService

            try:
                if source_format == "zip":
                    core._validate_zip_streamed(
                        copied.path,
                        operation,
                        member_limit=None,
                        total_limit=core._engine_uncompressed_limit(),
                        file_limit=None,
                        require_root_index=False,
                    )
                    from anki_miner.services.pitch_accent.yomitan_pitch_importer import (
                        import_yomitan_pitch_zip,
                    )

                    result = import_yomitan_pitch_zip(
                        copied.path,
                        candidate,
                        cancel_check=operation.cancelled.is_set,
                    )
                    source_name = result.source_name
                    source_revision = result.source_revision
                    entry_count = result.entry_count
                    skipped_display_only = result.skipped_display_only
                    skipped_malformed = result.skipped_malformed
                else:
                    shutil.copyfile(copied.path, candidate)
                    service = PitchAccentService(candidate)
                    service.load()
                    entry_count = service.entry_count
                    if entry_count <= 0:
                        raise SetupError("Pitch source yielded no usable entries")
            except (SetupError, UnicodeError, csv.Error, OSError) as exc:
                operation.check()
                raise _fail(
                    "pitch_import_failed",
                    "The selected file is not supported pitch-accent data",
                ) from exc
            operation.check()
            _fsync_file(candidate)
            metadata = _pitch_sidecar_payload(
                source_name=source_name,
                source_revision=source_revision,
                source_format=source_format,
                entry_count=entry_count,
                file_path=candidate,
            )
            staged_sidecar = operation_root / _PITCH_SIDECAR
            _write_sidecar(staged_sidecar, metadata)
            with core._PROMOTION_LOCK:
                if (final.exists() or final.is_symlink()) and not overwrite:
                    raise _fail(
                        "resource_already_installed",
                        "Pitch-accent data is already installed",
                    )
                os.replace(candidate, final)
                core._fsync_directory(home)
                os.replace(staged_sidecar, sidecar)
                core._fsync_directory(home)
            return encode_message(
                "resource.pitch.imported",
                {
                    "sourceName": source_name,
                    "sourceRevision": source_revision,
                    "sourceFormat": source_format,
                    "entryCount": entry_count,
                    "skippedDisplayOnly": skipped_display_only,
                    "skippedMalformed": skipped_malformed,
                    "fileSha256": metadata["fileSha256"],
                },
            )
        finally:
            if operation_root.exists():
                core._safe_rmtree(operation_root)


def _audio_member_limit(info: zipfile.ZipInfo) -> int:
    return _AUDIO_JSON_LIMIT if info.filename.lower().endswith(".json") else _AUDIO_FILE_LIMIT


def _extract_audio_zip(path: Path, destination: Path, operation: core._Operation) -> None:
    try:
        with zipfile.ZipFile(path, "r") as archive:
            infos = archive.infolist()
            if not infos or len(infos) > _AUDIO_MEMBER_LIMIT:
                raise _fail(
                    "resource_archive_too_large",
                    "Audio pack member count is outside its limit",
                )
            declared_total = sum(info.file_size for info in infos if not info.is_dir())
            if declared_total <= 0 or declared_total > _AUDIO_TOTAL_LIMIT:
                raise _fail("resource_archive_too_large", "Audio pack expands beyond its limit")
            core._check_free_space(destination.parent, declared_total)
            destination.mkdir(parents=True)
            seen: set[tuple[str, ...]] = set()
            actual_total = 0
            for info in infos:
                operation.check()
                parts = core._safe_archive_path(info.filename, allow_directory_suffix=info.is_dir())
                if parts in seen:
                    raise _fail(
                        "unsafe_resource_archive",
                        "Audio pack contains a duplicate path",
                    )
                seen.add(parts)
                if info.flag_bits & 0x1 or not core._zip_entry_is_safe_type(info):
                    raise _fail(
                        "unsafe_resource_archive",
                        "Audio pack contains an encrypted, linked, or special file",
                    )
                target = destination.joinpath(*parts)
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
                per_file_limit = _audio_member_limit(info)
                if info.file_size < 0 or info.file_size > per_file_limit:
                    raise _fail(
                        "resource_archive_too_large",
                        "Audio pack contains an oversized file",
                    )
                target.parent.mkdir(parents=True, exist_ok=True)
                written = 0
                with archive.open(info, "r") as source, target.open("xb", buffering=0) as output:
                    while True:
                        operation.check()
                        chunk = source.read(core._COPY_CHUNK_BYTES)
                        if not chunk:
                            break
                        written += len(chunk)
                        actual_total += len(chunk)
                        if (
                            written > info.file_size
                            or written > per_file_limit
                            or actual_total > declared_total
                            or actual_total > _AUDIO_TOTAL_LIMIT
                        ):
                            raise _fail(
                                "resource_archive_too_large",
                                "Audio pack expands beyond its limit",
                            )
                        core._write_all(output, chunk)
                    os.fsync(output.fileno())
                if written != info.file_size:
                    raise _fail(
                        "invalid_resource_archive",
                        "Audio pack member length is inconsistent",
                    )
            if actual_total != declared_total:
                raise _fail("invalid_resource_archive", "Audio pack length is inconsistent")
    except BridgeProtocolError:
        raise
    except (zipfile.BadZipFile, RuntimeError, OSError, EOFError) as exc:
        raise _fail("invalid_resource_archive", "Audio pack archive is corrupt") from exc


def _detect_audio_pack_root(extracted: Path) -> Path:
    from anki_miner.services.audio_packs.formats import detect_pack_format

    if detect_pack_format(extracted) is not None:
        return extracted
    candidates = [
        child for child in extracted.iterdir() if child.is_dir() and not child.is_symlink() and child.name != "__MACOSX"
    ]
    matches = [child for child in candidates if detect_pack_format(child) is not None]
    if len(matches) != 1:
        raise _fail(
            "audio_pack_import_failed",
            "The ZIP must contain exactly one supported local audio pack",
        )
    return matches[0]


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
                candidate = root.joinpath(*relative.parts).resolve()
                try:
                    candidate.relative_to(root)
                except ValueError:
                    raise _fail(
                        "audio_pack_import_failed",
                        "Audio pack media escapes its private root",
                    ) from None
                if (
                    candidate.suffix.lower() not in AUDIO_EXTENSIONS
                    or not candidate.is_file()
                    or candidate.is_symlink()
                ):
                    raise _fail(
                        "audio_pack_import_failed",
                        "Audio pack index references missing media",
                    )
        finally:
            connection.close()
    except sqlite3.Error as exc:
        raise _fail("audio_pack_import_failed", "Audio pack index is invalid") from exc


def import_audio_pack(payload: Mapping[str, object]) -> str:
    core._exact(
        payload,
        {"operationId", "sourcePath", "packId", "overwrite"},
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    pack_id = core._slot_id(payload["packId"])
    if pack_id == "jpod101":
        raise _fail("invalid_resource_request", "packId is reserved")
    overwrite = _boolean(payload["overwrite"], label="overwrite")
    home = Path(require_initialized())
    operation_root = _work_root(home, operation_id)
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._safe_rmtree(operation_root)
        operation_root.mkdir(parents=True)
        try:
            copied = core._copy_archive(
                source,
                operation_root / "source.zip",
                operation,
                maximum_bytes=_AUDIO_ARCHIVE_LIMIT,
            )
            extracted = operation_root / "extracted"
            _extract_audio_zip(copied.path, extracted, operation)
            pack_root = _detect_audio_pack_root(extracted)
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
            except (SetupError, ValueError, UnicodeError, json.JSONDecodeError) as exc:
                operation.check()
                raise _fail(
                    "audio_pack_import_failed",
                    "The ZIP does not contain a supported local audio pack",
                ) from exc
            operation.check()
            built = index_root / pack_id
            db_path = built / "index.sqlite"
            metadata = storage.read_meta(db_path)
            metadata["pack_dir"] = str(_audio_root(home) / pack_id / "content")
            storage.write_meta(db_path, metadata)
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
        parsed = parse_known_words_file(copied.path)
    except KnownWordsImportError as exc:
        raise _fail(
            "known_words_import_failed",
            "The selected file contains no supported known-word export",
        ) from exc
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
        escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        where = "source = 'user'"
        parameters: list[object] = []
        if query:
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
        database, _db_path = _known_words_database(home)
        words = sorted(database.get_words_by_source("user"))
        export_path = operation_root / "known_words.txt"
        size_bytes = 0
        try:
            with export_path.open("xb", buffering=0) as stream:
                for index, word in enumerate(words):
                    if index % 1024 == 0:
                        operation.check()
                    encoded = f"{word}\n".encode()
                    size_bytes += len(encoded)
                    if size_bytes > _KNOWN_WORD_EXPORT_LIMIT:
                        raise _fail("known_words_export_failed", "Known-word export exceeds its limit")
                    core._write_all(stream, encoded)
                os.fsync(stream.fileno())
            core._fsync_directory(operation_root)
            return encode_message(
                "resource.knownwords.exported",
                {
                    "exportPath": str(export_path),
                    "exportedCount": len(words),
                    "sizeBytes": size_bytes,
                },
            )
        except Exception:
            if operation_root.exists():
                core._safe_rmtree(operation_root)
            raise


def _read_pitch_inventory(home: Path) -> dict[str, object] | None:
    pitch = home / "pitch_accent.csv"
    if not pitch.exists():
        return None
    if pitch.is_symlink() or not pitch.is_file():
        return {
            "sourceName": "Pitch accent data",
            "sourceRevision": "",
            "sourceFormat": "unknown",
            "entryCount": 0,
            "fileSizeBytes": 0,
            "schemaOk": False,
        }
    try:
        size = pitch.stat().st_size
        if size <= 0 or size > _PITCH_TEXT_LIMIT:
            raise ValueError("invalid pitch size")
        sidecar_path = home / _PITCH_SIDECAR
        if (
            not sidecar_path.is_file()
            or sidecar_path.is_symlink()
            or sidecar_path.stat().st_size > core._MAX_MANIFEST_BYTES
        ):
            raise ValueError("invalid sidecar")
        parsed = json.loads(sidecar_path.read_text(encoding="utf-8"))
        if (
            not isinstance(parsed, dict)
            or parsed.get("schemaVersion") != 1
            or parsed.get("fileSizeBytes") != size
            or not isinstance(parsed.get("entryCount"), int)
            or parsed["entryCount"] <= 0
            or not isinstance(parsed.get("sourceName"), str)
            or not parsed["sourceName"]
            or len(parsed["sourceName"].encode("utf-8")) > 4096
            or not isinstance(parsed.get("sourceRevision"), str)
            or len(parsed["sourceRevision"].encode("utf-8")) > 4096
            or parsed.get("sourceFormat") not in _PITCH_FORMATS
            or not isinstance(parsed.get("fileSha256"), str)
            or len(parsed["fileSha256"]) != 64
            or _file_sha256(pitch) != parsed["fileSha256"]
        ):
            raise ValueError("invalid sidecar")
        return {
            "sourceName": str(parsed.get("sourceName", "Pitch accent data")),
            "sourceRevision": str(parsed.get("sourceRevision", "")),
            "sourceFormat": str(parsed.get("sourceFormat", "unknown")),
            "entryCount": parsed["entryCount"],
            "fileSizeBytes": size,
            "schemaOk": True,
        }
    except (OSError, UnicodeError, json.JSONDecodeError, TypeError, ValueError):
        try:
            fallback_size = pitch.stat().st_size
        except OSError:
            fallback_size = 0
        if 0 < fallback_size <= _PITCH_TEXT_LIMIT:
            try:
                # A crash can atomically publish the CSV immediately before its
                # informational sidecar. Re-validate the actual runtime format
                # so that harmless metadata loss does not permanently brick the
                # resource; malformed data still fails closed below.
                from anki_miner.services.pitch_accent_service import (
                    PitchAccentService,
                )

                service = PitchAccentService(pitch)
                service.load()
                if service.entry_count > 0:
                    return {
                        "sourceName": "Recovered local pitch accent",
                        "sourceRevision": "",
                        "sourceFormat": "unknown",
                        "entryCount": service.entry_count,
                        "fileSizeBytes": fallback_size,
                        "schemaOk": True,
                    }
            except Exception:
                pass
        return {
            "sourceName": "Pitch accent data",
            "sourceRevision": "",
            "sourceFormat": "unknown",
            "entryCount": 0,
            "fileSizeBytes": fallback_size,
            "schemaOk": False,
        }


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
        pass
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
        pass
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
                # Desktop frequency schema migrations are additive: v1 and v2
                # remain readable, while future versions fail closed.
                "schemaOk": 1 <= version <= 2,
                "schemaVersion": max(version, 0),
                "isCategorical": meta.get("is_categorical") == "1",
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
        with contextlib.suppress(OSError):
            content_available = (
                version == 1
                and count > 0
                and expected_content.is_dir()
                and not expected_content.is_symlink()
                and configured_content == expected_content
            )
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
            "pitchAccent": _read_pitch_inventory(home),
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
        kind="audio-pack",
        final_root=_audio_root(home),
        require_content=True,
    )
