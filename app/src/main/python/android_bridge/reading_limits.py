"""Android-owned safety limits for staged reading sources.

The desktop loaders are parity-sensitive and intentionally remain vendored
verbatim.  This module surrounds detector/load with mobile-specific preflight
and post-load checks sized for a 384 MiB process target.  Compressed staging
limits alone are insufficient: ZIP central directories, decompressed members,
JSON object graphs, retained reading units and image decodes all have distinct
amplification factors.
"""

from __future__ import annotations

import json
import posixpath
import struct
import warnings
import zipfile
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from .protocol import BridgeProtocolError

# Staged-byte gates. Kotlin enforces the same values while copying SAF input;
# the Python boundary repeats them because paths and files are untrusted at
# every language seam.
MAX_TXT_SOURCE_BYTES = 8 * 1024 * 1024
MAX_SUBTITLE_SOURCE_BYTES = 8 * 1024 * 1024
MAX_EPUB_SOURCE_BYTES = 256 * 1024 * 1024
MAX_MOKURO_JSON_BYTES = 16 * 1024 * 1024
MAX_MOKURO_ARCHIVE_BYTES = 1024 * 1024 * 1024

# Retained document graph. Eight MiB of UTF-8 Japanese text expands into many
# Python strings/token objects; 50k units leaves ample room for long novels and
# manga while preventing the unit graph itself from exhausting a 384 MiB app.
MAX_DOCUMENT_TEXT_UTF8_BYTES = 8 * 1024 * 1024
MAX_DOCUMENT_UNITS = 50_000
MAX_UNIT_TEXT_UTF8_BYTES = 64 * 1024
MAX_LOCATION_LABEL_UTF8_BYTES = 4 * 1024
MAX_DOCUMENT_LOCATION_UTF8_BYTES = 4 * 1024 * 1024
MAX_UNIQUE_IMAGE_REFS = 4_096
MAX_IMAGE_PIXELS = 16_000_000

# Mokuro's JSON is parsed by the desktop detector and loader. Bound its
# collection fan-out before either one sees it.
MAX_MOKURO_PAGES = 4_096
MAX_MOKURO_BLOCKS = 50_000
MAX_MOKURO_LINES = 250_000

_EOCD_SIGNATURE = b"PK\x05\x06"
_EOCD = struct.Struct("<4s4H2LH")
_MAX_ZIP_COMMENT_BYTES = 65_535
_ZIP64_U16 = 0xFFFF
_ZIP64_U32 = 0xFFFFFFFF
_MAX_ZIP_MEMBER_NAME_UTF8_BYTES = 1_024
_MAX_COMPRESSION_RATIO = 250
_CANCEL_CHECK_INTERVAL = 64

_EPUB_BINARY_EXTENSIONS = frozenset(
    {
        ".avif",
        ".bmp",
        ".eot",
        ".gif",
        ".jpeg",
        ".jpg",
        ".m4a",
        ".mp3",
        ".mp4",
        ".ogg",
        ".otf",
        ".png",
        ".ttc",
        ".ttf",
        ".wav",
        ".webm",
        ".webp",
        ".woff",
        ".woff2",
    }
)


@dataclass(frozen=True, slots=True)
class ZipArchiveLimits:
    label: str
    max_members: int
    max_central_directory_bytes: int
    max_member_uncompressed_bytes: int
    max_total_uncompressed_bytes: int
    max_text_member_uncompressed_bytes: int | None = None
    max_total_text_uncompressed_bytes: int | None = None


EPUB_ARCHIVE_LIMITS = ZipArchiveLimits(
    label="EPUB",
    max_members=8_192,
    max_central_directory_bytes=8 * 1024 * 1024,
    max_member_uncompressed_bytes=64 * 1024 * 1024,
    max_total_uncompressed_bytes=1024 * 1024 * 1024,
    max_text_member_uncompressed_bytes=8 * 1024 * 1024,
    max_total_text_uncompressed_bytes=32 * 1024 * 1024,
)

MOKURO_ARCHIVE_LIMITS = ZipArchiveLimits(
    label="Mokuro image archive",
    max_members=4_096,
    max_central_directory_bytes=4 * 1024 * 1024,
    max_member_uncompressed_bytes=32 * 1024 * 1024,
    max_total_uncompressed_bytes=1024 * 1024 * 1024,
)


def _too_large(message: str) -> BridgeProtocolError:
    return BridgeProtocolError("reading_source_too_large", message)


def _invalid_source(message: str) -> BridgeProtocolError:
    return BridgeProtocolError("invalid_reading_source", message)


def _invalid_archive(label: str) -> BridgeProtocolError:
    return BridgeProtocolError(
        "invalid_reading_source_archive",
        f"The selected {label} is not a valid, single-volume ZIP archive",
    )


def _check_cancelled(cancellation_check: Callable[[], bool] | None) -> None:
    if cancellation_check is None or not cancellation_check():
        return
    # Local import avoids a module cycle and keeps this safety helper independent
    # of the engine until an admitted run actually requests cancellation.
    from .anki_adapter import AnkiOperationCancelled

    raise AnkiOperationCancelled(
        "runReading",
        "Mining was cancelled",
        False,
    )


def _stat_bounded(path: Path, *, label: str, maximum: int) -> int:
    try:
        size = path.stat().st_size
    except OSError as error:
        raise _invalid_source(f"The selected {label} cannot be read") from error
    if size <= 0:
        raise _invalid_source(f"The selected {label} is empty")
    if size > maximum:
        raise _too_large(
            f"The selected {label} exceeds the mobile safety limit "
            f"({size:,} > {maximum:,} bytes); choose a smaller source"
        )
    return size


def _read_eocd(path: Path, limits: ZipArchiveLimits) -> tuple[int, int, int]:
    """Read count/central-size/offset without allocating the central directory."""

    try:
        size = path.stat().st_size
    except OSError as error:
        raise _invalid_archive(limits.label) from error
    tail_size = min(size, _EOCD.size + _MAX_ZIP_COMMENT_BYTES)
    try:
        with path.open("rb") as stream:
            stream.seek(size - tail_size)
            tail = stream.read(tail_size)
    except OSError as error:
        raise _invalid_archive(limits.label) from error

    offset = tail.rfind(_EOCD_SIGNATURE)
    if offset < 0 or len(tail) - offset < _EOCD.size:
        raise _invalid_archive(limits.label)
    (
        signature,
        disk_number,
        central_disk,
        disk_entries,
        total_entries,
        central_size,
        central_offset,
        comment_length,
    ) = _EOCD.unpack_from(tail, offset)
    if signature != _EOCD_SIGNATURE or offset + _EOCD.size + comment_length != len(tail):
        raise _invalid_archive(limits.label)
    if disk_number != 0 or central_disk != 0 or disk_entries != total_entries:
        raise _invalid_archive(limits.label)
    if total_entries == _ZIP64_U16 or central_size == _ZIP64_U32 or central_offset == _ZIP64_U32:
        raise _too_large(
            f"The selected {limits.label} uses a ZIP64 central directory, " "which is outside the mobile safety limits"
        )
    if total_entries > limits.max_members:
        raise _too_large(
            f"The selected {limits.label} contains too many archive members "
            f"({total_entries:,} > {limits.max_members:,})"
        )
    if central_size > limits.max_central_directory_bytes:
        raise _too_large(
            f"The selected {limits.label} central directory is too large "
            f"({central_size:,} > {limits.max_central_directory_bytes:,} bytes)"
        )
    if central_offset + central_size > size:
        raise _invalid_archive(limits.label)
    return total_entries, central_size, central_offset


def _safe_member_name(name: str, limits: ZipArchiveLimits) -> None:
    try:
        encoded_size = len(name.encode("utf-8"))
    except UnicodeEncodeError as error:
        raise _invalid_archive(limits.label) from error
    parts = PurePosixPath(name).parts
    if (
        not name
        or encoded_size > _MAX_ZIP_MEMBER_NAME_UTF8_BYTES
        or "\\" in name
        or name.startswith("/")
        or (len(name) > 1 and name[1] == ":")
        or ".." in parts
        or "\x00" in name
    ):
        raise _invalid_archive(limits.label)


def _is_epub_text_member(name: str) -> bool:
    normalized = name.lower().split("?", 1)[0]
    extension = posixpath.splitext(normalized)[1]
    # Treat unknown extensions as text: an OPF manifest can name XHTML without
    # a conventional suffix, while known binary media never enters the loader's
    # retained prose graph.
    return extension not in _EPUB_BINARY_EXTENSIONS


def validate_zip_archive(
    path: Path,
    limits: ZipArchiveLimits,
    *,
    cancellation_check: Callable[[], bool] | None = None,
) -> None:
    """Preflight one ZIP using declared sizes and bounded central metadata."""

    _check_cancelled(cancellation_check)
    declared_entries, _, _ = _read_eocd(path, limits)
    _check_cancelled(cancellation_check)
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
    except (OSError, zipfile.BadZipFile, NotImplementedError) as error:
        raise _invalid_archive(limits.label) from error

    if len(infos) != declared_entries or len(infos) > limits.max_members:
        raise _invalid_archive(limits.label)

    seen: set[str] = set()
    total = 0
    text_total = 0
    for index, info in enumerate(infos):
        if index % _CANCEL_CHECK_INTERVAL == 0:
            _check_cancelled(cancellation_check)
        _safe_member_name(info.filename, limits)
        if info.filename in seen:
            raise _invalid_archive(limits.label)
        seen.add(info.filename)
        if info.flag_bits & 0x1:
            raise _invalid_archive(limits.label)
        if info.is_dir():
            continue

        member_size = info.file_size
        compressed_size = info.compress_size
        if member_size < 0 or compressed_size < 0:
            raise _invalid_archive(limits.label)
        if member_size > limits.max_member_uncompressed_bytes:
            raise _too_large(
                f"The selected {limits.label} contains an oversized member "
                f"({member_size:,} > {limits.max_member_uncompressed_bytes:,} bytes)"
            )
        if member_size and (compressed_size == 0 or member_size > compressed_size * _MAX_COMPRESSION_RATIO):
            raise _too_large(f"The selected {limits.label} contains a suspiciously compressed member")

        total += member_size
        if total > limits.max_total_uncompressed_bytes:
            raise _too_large(
                f"The selected {limits.label} expands beyond the cumulative "
                f"mobile safety limit ({total:,} > "
                f"{limits.max_total_uncompressed_bytes:,} bytes)"
            )

        if limits.max_text_member_uncompressed_bytes is not None and _is_epub_text_member(info.filename):
            if member_size > limits.max_text_member_uncompressed_bytes:
                raise _too_large(
                    f"The selected {limits.label} contains an oversized text member "
                    f"({member_size:,} > "
                    f"{limits.max_text_member_uncompressed_bytes:,} bytes)"
                )
            text_total += member_size
            if (
                limits.max_total_text_uncompressed_bytes is not None
                and text_total > limits.max_total_text_uncompressed_bytes
            ):
                raise _too_large(
                    f"The selected {limits.label} expands beyond the cumulative "
                    f"text limit ({text_total:,} > "
                    f"{limits.max_total_text_uncompressed_bytes:,} bytes)"
                )
    _check_cancelled(cancellation_check)


def _validate_mokuro_json(
    path: Path,
    *,
    cancellation_check: Callable[[], bool] | None,
) -> None:
    try:
        with path.open("r", encoding="utf-8") as source:
            payload = json.load(source)
    except (OSError, UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise _invalid_source("The selected .mokuro sidecar is invalid") from error
    _check_cancelled(cancellation_check)
    if not isinstance(payload, dict) or not isinstance(payload.get("pages"), list):
        raise _invalid_source("The selected .mokuro sidecar has an invalid pages list")

    pages = payload["pages"]
    if len(pages) > MAX_MOKURO_PAGES:
        raise _too_large(
            f"The selected .mokuro sidecar contains too many pages " f"({len(pages):,} > {MAX_MOKURO_PAGES:,})"
        )

    blocks = 0
    lines = 0
    text_bytes = 0
    for page_index, page in enumerate(pages):
        if page_index % _CANCEL_CHECK_INTERVAL == 0:
            _check_cancelled(cancellation_check)
        if not isinstance(page, dict):
            raise _invalid_source("The selected .mokuro sidecar contains an invalid page")
        page_blocks = page.get("blocks", []) or []
        if not isinstance(page_blocks, list):
            raise _invalid_source("The selected .mokuro sidecar contains an invalid block list")
        blocks += len(page_blocks)
        if blocks > MAX_MOKURO_BLOCKS:
            raise _too_large(
                f"The selected .mokuro sidecar contains too many text blocks " f"({blocks:,} > {MAX_MOKURO_BLOCKS:,})"
            )
        for block in page_blocks:
            if not isinstance(block, dict):
                raise _invalid_source("The selected .mokuro sidecar contains an invalid block")
            block_lines = block.get("lines", []) or []
            if not isinstance(block_lines, list) or not all(isinstance(line, str) for line in block_lines):
                raise _invalid_source("The selected .mokuro sidecar contains invalid OCR text")
            lines += len(block_lines)
            if lines > MAX_MOKURO_LINES:
                raise _too_large(
                    f"The selected .mokuro sidecar contains too many OCR lines " f"({lines:,} > {MAX_MOKURO_LINES:,})"
                )
            for line in block_lines:
                try:
                    line_bytes = len(line.encode("utf-8"))
                except UnicodeEncodeError as error:
                    raise _invalid_source("The selected .mokuro sidecar contains invalid Unicode") from error
                if line_bytes > MAX_UNIT_TEXT_UTF8_BYTES:
                    raise _too_large("The selected .mokuro sidecar contains an oversized OCR line")
                text_bytes += line_bytes
                if text_bytes > MAX_DOCUMENT_TEXT_UTF8_BYTES:
                    raise _too_large("The selected .mokuro sidecar exceeds the mobile OCR text limit")
    _check_cancelled(cancellation_check)


def validate_source_before_load(
    *,
    source_kind: str,
    source_path: Path,
    image_archive_path: Path | None,
    cancellation_check: Callable[[], bool] | None = None,
) -> None:
    """Validate staged bytes and expansion metadata before desktop detection."""

    _check_cancelled(cancellation_check)
    if source_kind == "txt":
        _stat_bounded(source_path, label="text file", maximum=MAX_TXT_SOURCE_BYTES)
    elif source_kind == "subtitle":
        _stat_bounded(
            source_path,
            label="subtitle file",
            maximum=MAX_SUBTITLE_SOURCE_BYTES,
        )
    elif source_kind == "epub":
        _stat_bounded(source_path, label="EPUB", maximum=MAX_EPUB_SOURCE_BYTES)
        validate_zip_archive(
            source_path,
            EPUB_ARCHIVE_LIMITS,
            cancellation_check=cancellation_check,
        )
    elif source_kind == "mokuro":
        _stat_bounded(
            source_path,
            label=".mokuro sidecar",
            maximum=MAX_MOKURO_JSON_BYTES,
        )
        _validate_mokuro_json(
            source_path,
            cancellation_check=cancellation_check,
        )
        if image_archive_path is not None:
            _stat_bounded(
                image_archive_path,
                label="Mokuro image archive",
                maximum=MAX_MOKURO_ARCHIVE_BYTES,
            )
            validate_zip_archive(
                image_archive_path,
                MOKURO_ARCHIVE_LIMITS,
                cancellation_check=cancellation_check,
            )
    else:
        raise _invalid_source("The selected reading source kind is unsupported")
    _check_cancelled(cancellation_check)


def _utf8_size(value: str, *, label: str) -> int:
    try:
        return len(value.encode("utf-8"))
    except UnicodeEncodeError as error:
        raise _invalid_source(f"The loaded reading document contains invalid {label}") from error


def _iter_unique_image_refs(units: Iterable[Any]) -> list[Any]:
    unique: list[Any] = []
    seen: set[tuple[str, str | None]] = set()
    for unit in units:
        ref = getattr(unit, "image_ref", None)
        if ref is None:
            continue
        source = getattr(ref, "source", None)
        entry = getattr(ref, "entry", None)
        if not isinstance(source, Path) or (entry is not None and not isinstance(entry, str)):
            raise _invalid_source("The loaded reading document contains an invalid image reference")
        key = (str(source), entry)
        if key not in seen:
            seen.add(key)
            unique.append(ref)
    return unique


def _validate_image_headers(
    refs: list[Any],
    *,
    expected_source: Path,
    cancellation_check: Callable[[], bool] | None,
) -> None:
    if not refs:
        return
    # Function-local optional dependency import keeps the bridge importable in
    # host protocol tests which do not load reading media.
    from PIL import Image, UnidentifiedImageError

    expected = expected_source.resolve(strict=False)
    try:
        archive_context = zipfile.ZipFile(expected)
    except (OSError, zipfile.BadZipFile, NotImplementedError) as error:
        raise _invalid_source("The loaded reading document image archive changed during validation") from error

    with archive_context as archive:
        for index, ref in enumerate(refs):
            if index % _CANCEL_CHECK_INTERVAL == 0:
                _check_cancelled(cancellation_check)
            source = ref.source.resolve(strict=False)
            if source != expected or ref.entry is None:
                raise _invalid_source("The loaded reading document references an undeclared image source")
            try:
                member_context = archive.open(ref.entry)
                with member_context as member:
                    with warnings.catch_warnings():
                        warnings.simplefilter("error", Image.DecompressionBombWarning)
                        with Image.open(member) as image:
                            width, height = image.size
            except Image.DecompressionBombError as error:
                raise _too_large("A reading image exceeds the mobile pixel safety limit") from error
            except Image.DecompressionBombWarning as error:
                raise _too_large("A reading image exceeds the mobile pixel safety limit") from error
            except (KeyError, OSError, zipfile.BadZipFile, UnidentifiedImageError):
                # The desktop phase-3 path already turns corrupt/unreadable images
                # into per-image warnings. They carry no decode amplification here.
                continue
            if width <= 0 or height <= 0 or width * height > MAX_IMAGE_PIXELS:
                raise _too_large(
                    f"A reading image exceeds the mobile pixel safety limit "
                    f"({width}x{height} > {MAX_IMAGE_PIXELS:,} pixels)"
                )
    _check_cancelled(cancellation_check)


def validate_loaded_document(
    document: object,
    *,
    source_kind: str,
    source_path: Path,
    image_archive_path: Path | None,
    cancellation_check: Callable[[], bool] | None = None,
) -> None:
    """Bound the exact unit/text/image graph retained for engine processing."""

    units = getattr(document, "units", None)
    if not isinstance(units, list):
        raise _invalid_source("The reading loader returned an invalid unit list")
    if len(units) > MAX_DOCUMENT_UNITS:
        raise _too_large(f"The reading document contains too many units " f"({len(units):,} > {MAX_DOCUMENT_UNITS:,})")

    text_bytes = 0
    location_bytes = 0
    for index, unit in enumerate(units):
        if index % _CANCEL_CHECK_INTERVAL == 0:
            _check_cancelled(cancellation_check)
        text = getattr(unit, "text", None)
        location = getattr(unit, "location_label", None)
        if not isinstance(text, str) or not isinstance(location, str):
            raise _invalid_source("The reading loader returned an invalid unit")
        unit_bytes = _utf8_size(text, label="unit text")
        if unit_bytes > MAX_UNIT_TEXT_UTF8_BYTES:
            raise _too_large("A reading unit exceeds the mobile text safety limit")
        text_bytes += unit_bytes
        if text_bytes > MAX_DOCUMENT_TEXT_UTF8_BYTES:
            raise _too_large("The reading document exceeds the mobile retained-text safety limit")
        label_bytes = _utf8_size(location, label="location label")
        if label_bytes > MAX_LOCATION_LABEL_UTF8_BYTES:
            raise _too_large("A reading location label exceeds the mobile safety limit")
        location_bytes += label_bytes
        if location_bytes > MAX_DOCUMENT_LOCATION_UTF8_BYTES:
            raise _too_large("The reading document exceeds the mobile location-label safety limit")

    image_refs = _iter_unique_image_refs(units)
    if len(image_refs) > MAX_UNIQUE_IMAGE_REFS:
        raise _too_large(
            f"The reading document references too many images " f"({len(image_refs):,} > {MAX_UNIQUE_IMAGE_REFS:,})"
        )
    if image_refs:
        expected_image_source = source_path if source_kind == "epub" else image_archive_path
        if expected_image_source is None:
            raise _invalid_source("The reading document references images without a declared archive")
        _validate_image_headers(
            image_refs,
            expected_source=expected_image_source,
            cancellation_check=cancellation_check,
        )
    _check_cancelled(cancellation_check)
