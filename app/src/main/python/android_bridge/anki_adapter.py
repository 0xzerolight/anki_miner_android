"""AnkiService-shaped adapter backed by synchronous Kotlin callbacks.

The Android side owns ContentProvider access.  Parity-sensitive behavior stays
in Python: desktop note construction, first-field normalization, duplicate
partitioning, content-addressed media names, batching, counters, and cache
updates.  Engine imports are deliberately function-local because bootstrap must
set ``ANKI_MINER_HOME`` before any ``anki_miner`` module is imported.
"""

from __future__ import annotations

import hashlib
import logging
import os
import re
import stat
import unicodedata
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from html import escape as html_escape
from html import unescape as html_unescape
from pathlib import Path
from typing import Any, NoReturn
from uuid import uuid4

from .callbacks import AndroidAnkiCallbacks, AnkiCallbackError
from .config_map import _REQUIRED_ANKI_FIELD_KEYS, validate_anki_request_config
from .protocol import (
    BridgeProtocolError,
    encode_message,
    normalize_integral_json_number,
)

logger = logging.getLogger(__name__)

_JAPANESE_RE = re.compile(r"[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\u3400-\u4DBF]")
_BATCH_SIZE = 100
_MEDIA_BATCH_SIZE = 50
_MAX_DUPLICATE_KEY_CHARS = 4096
_MAX_DUPLICATE_FIRST_FIELD_CHARS = 16_384
_MAX_DUPLICATE_HITS_PER_CANDIDATE = 100
_MAX_DUPLICATE_TOTAL_HITS = 1000
_MAX_RAW_FIRST_FIELD_UTF8_BYTES = 64 * 1024
_MAX_DUPLICATE_HITS_UTF8_BYTES = 1024 * 1024
_KNOWN_VOCABULARY_PAGE_ITEMS = 256
_KNOWN_VOCABULARY_PAGE_UTF8_BYTES = 256 * 1024
_MAX_KNOWN_VOCABULARY_SCANNED_NOTES = 100_000
_MAX_KNOWN_CURSOR_UTF8_BYTES = 1024
_MAX_NOTE_FIELDS = 64
_MAX_FIELD_NAME_UTF8_BYTES = 256
_MAX_FIELD_VALUE_UTF8_BYTES = 96 * 1024
_MAX_NOTE_TAGS = 64
_MAX_TAG_UTF8_BYTES = 256
_MAX_NOTE_TAGS_UTF8_BYTES = 8 * 1024
_MAX_NOTE_CONTENT_UTF8_BYTES = 128 * 1024
_MAX_CREATE_CONTENT_UTF8_BYTES = 384 * 1024
_MAX_CREATE_ENVELOPE_UTF8_BYTES = 512 * 1024
_MAX_MEDIA_ASSET_BYTES = 64 * 1024 * 1024
_MAX_MEDIA_CALLBACK_BYTES = 64 * 1024 * 1024
# Compatibility alias for callers/tests which imported the original card-only
# constant. The limit now applies identically to card and dictionary assets.
_MAX_CARD_MEDIA_BYTES = _MAX_MEDIA_ASSET_BYTES
_MEDIA_HASH_CHUNK_BYTES = 128 * 1024

_SETUP_ERROR_CODES = {
    "api_disabled",
    "permission_required",
    "note_type_not_found",
    "field_missing",
    "field_mapping_invalid",
    "target_invalid",
}
_PROTOCOL_ERROR_CODES = {"invalid_request", "unsupported_operation"}
_CONNECTION_ERROR_CODES = {
    "provider_unavailable",
    "query_failed",
    "write_failed",
    "timeout",
    "cancelled",
    "media_store_failed",
    "post_commit_uncertain",
    "internal_error",
}
_ALL_ERROR_CODES = _SETUP_ERROR_CODES | _PROTOCOL_ERROR_CODES | _CONNECTION_ERROR_CODES
_RECOVERABLE_MEDIA_ERROR_CODES = frozenset({"media_store_failed"})
_FORBIDDEN_FILENAME_CHARACTERS = frozenset('/\\<>[]:"')
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_BASELINE_TOKEN_RE = re.compile(r"^baseline_[0-9a-f]{32}$")
_PLACEHOLDER_REQUEST_ID = "anki_" + "0" * 32
_PLACEHOLDER_BASELINE_TOKEN = "baseline_" + "0" * 32


class AnkiOperationCancelled(BaseException):
    """A provider cancellation which must escape ``process_episode`` intact.

    The desktop engine represents user cancellation as a result rather than an
    exception and therefore has no reusable cancellation exception. This
    bridge-only ``BaseException`` deliberately bypasses the engine's broad
    ``except Exception`` failure conversion. The mining entrypoint translates
    it to the job-cancelled terminal state.
    """

    code = "cancelled"

    def __init__(self, operation: str, message: str, retryable: bool) -> None:
        super().__init__(message)
        self.operation = operation
        self.message = message
        self.retryable = retryable

    def __str__(self) -> str:
        return self.message


@dataclass(frozen=True)
class _MediaAsset:
    asset_id: str
    source_path: str
    # AnkiDroid's addMediaFromUri contract explicitly requires this prefix to
    # omit the file extension. The provider appends a random suffix and the
    # MIME-derived extension.
    preferred_name: str
    # Full filename we would use with a direct-name media API. Kept internal so
    # response validation can support both the ContentProvider and a fallback.
    requested_name: str
    # Filename currently referenced by CardPayload/marked dictionary HTML.
    original_name: str
    purpose: str
    media_kind: str
    expected_size_bytes: int
    expected_sha256: str

    def to_wire(self) -> dict[str, str]:
        return {
            "assetId": self.asset_id,
            "sourcePath": self.source_path,
            "preferredName": self.preferred_name,
            "requestedFilename": self.requested_name,
            "purpose": self.purpose,
            "mediaKind": self.media_kind,
            "expectedSizeBytes": self.expected_size_bytes,
            "expectedSha256": self.expected_sha256,
        }


@dataclass(frozen=True)
class _CardMediaRef:
    media: Any
    filename_attr: str
    source_path: Path


@dataclass(frozen=True)
class _DuplicateProbeResult:
    is_duplicate: bool
    occurrence: int


@dataclass(frozen=True)
class _PendingNote:
    payload: Any
    note: dict[str, Any]
    key: str
    first_field: str
    content_utf8_bytes: int
    source_index: int


@dataclass(frozen=True)
class _MediaDigest:
    size: int
    sha1_prefix: str
    sha256: str


@dataclass(frozen=True)
class _StoreAssetsOutcome:
    stored: dict[str, str]
    error: AnkiCallbackError | None = None


def _protocol_error(code: str, message: str) -> NoReturn:
    raise BridgeProtocolError(code, message)


def _expect_exact_keys(
    value: object, required: set[str], *, context: str
) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != required:
        _protocol_error(
            "invalid_anki_response", f"{context} has missing or unknown fields"
        )
    return value


def _expect_string(value: object, *, context: str, nonempty: bool = False) -> str:
    if not isinstance(value, str) or (nonempty and not value):
        _protocol_error("invalid_anki_response", f"{context} must be a string")
    return value


def _expect_string_list(
    value: object, *, context: str, unique: bool = False, nonempty: bool = False
) -> list[str]:
    if not isinstance(value, list):
        _protocol_error("invalid_anki_response", f"{context} must be an array")
    result = [
        _expect_string(item, context=f"{context}[{index}]", nonempty=nonempty)
        for index, item in enumerate(value)
    ]
    if unique and len(set(result)) != len(result):
        _protocol_error("invalid_anki_response", f"{context} must be unique")
    return result


def _expect_positive_int(value: object, *, context: str) -> int:
    converted = normalize_integral_json_number(value)
    if converted is None or converted <= 0:
        _protocol_error(
            "invalid_anki_response", f"{context} must be a positive integer"
        )
    return converted


def _expect_media_basename(
    value: object, *, context: str, code: str = "invalid_anki_response"
) -> str:
    if not isinstance(value, str) or not value:
        _protocol_error(code, f"{context} must be a non-empty string")
    filename = value
    if (
        filename in {".", ".."}
        or "/" in filename
        or "\\" in filename
        or any(
            unicodedata.category(character).startswith("C") for character in filename
        )
        or Path(filename).name != filename
    ):
        _protocol_error(code, f"{context} is not a media basename")
    return filename


def _expect_filename(
    value: object, *, context: str, code: str = "invalid_anki_response"
) -> str:
    filename = _expect_media_basename(value, context=context, code=code)
    if (
        filename != filename.strip()
        or filename != unicodedata.normalize("NFC", filename)
        or any(character in _FORBIDDEN_FILENAME_CHARACTERS for character in filename)
    ):
        _protocol_error(code, f"{context} is not a safe provider filename")
    return filename


def _expect_actual_media_basename(value: object, *, context: str) -> str:
    filename = _expect_media_basename(value, context=context)
    lowered = filename.lower()
    if lowered.startswith("[sound:") or lowered.startswith("<img"):
        _protocol_error(
            "invalid_anki_response", f"{context} must be a raw media basename"
        )
    return filename


def _validate_provider_filename(
    actual: str,
    preferred: str,
    *,
    requested: str,
    purpose: str,
    context: str,
) -> None:
    """Constrain AnkiDroid's returned name to its documented insertion shape.

    The v2.24 API requires an extensionless ``preferredName``. Its provider
    creates ``preferredName + '_' + random + MIME-extension`` before calling
    ``media.addFile``. A direct-name fallback may instead return the full
    requested filename unchanged. Kotlin must unwrap the provider URI/
    ``[sound:]``/``<img>`` response before returning this raw basename.
    """

    if actual == requested:
        if purpose == "dictionary":
            _expect_media_basename(actual, context=context)
        else:
            _expect_filename(actual, context=context)
        return
    _expect_filename(actual, context=context)
    actual_path = Path(actual)
    if not actual_path.suffix:
        _protocol_error(
            "unexpected_media_name", f"{context} must include a provider extension"
        )
    if not actual_path.stem.startswith(f"{preferred}_"):
        _protocol_error(
            "unexpected_media_name",
            f"{context} is unrelated to the requested media name",
        )


def _provider_preferred_name(filename: str) -> str:
    """Return the extensionless prefix required by ``addMediaFromUri``."""

    path = Path(filename)
    preferred = (path.stem if path.suffix else filename).replace(" ", "_")
    # CardContentProvider appends ``_`` and passes the result to Java's
    # createTempFile, whose prefix must be at least three characters.
    return preferred if len(preferred) >= 2 else f"{preferred}_"


def _dictionary_provider_preferred_name(logical_filename: str) -> str:
    """Return a safe deterministic prefix for an arbitrary Yomitan basename."""

    digest = hashlib.sha256(logical_filename.encode("utf-8")).hexdigest()
    return f"anki_miner_dict_{digest}"


def _content_addressed_name_from_digest(filename: str, sha1_prefix: str) -> str:
    """Match the desktop ``{stem}_{sha1[:12]}{suffix}`` media name."""

    path = Path(filename)
    return f"{path.stem}_{sha1_prefix}{path.suffix}"


def _chunk_media_assets(assets: Sequence[_MediaAsset]) -> list[list[_MediaAsset]]:
    """Split assets by the exact provider callback count and byte ceilings."""

    chunks: list[list[_MediaAsset]] = []
    chunk: list[_MediaAsset] = []
    chunk_bytes = 0
    for asset in assets:
        if not 0 <= asset.expected_size_bytes <= _MAX_MEDIA_ASSET_BYTES:
            _protocol_error(
                "media_too_large",
                f"Media exceeds the {_MAX_MEDIA_ASSET_BYTES}-byte limit",
            )
        if chunk and (
            len(chunk) >= _MEDIA_BATCH_SIZE
            or chunk_bytes + asset.expected_size_bytes > _MAX_MEDIA_CALLBACK_BYTES
        ):
            chunks.append(chunk)
            chunk = []
            chunk_bytes = 0
        chunk.append(asset)
        chunk_bytes += asset.expected_size_bytes
    if chunk:
        chunks.append(chunk)
    return chunks


def _expect_error_detail(value: object, *, operation: str) -> AnkiCallbackError:
    detail = _expect_exact_keys(
        value, {"code", "message", "retryable"}, context=f"{operation} error"
    )
    code = _expect_string(detail["code"], context=f"{operation} error code")
    if code not in _ALL_ERROR_CODES:
        _protocol_error("invalid_anki_response", f"Unknown Anki error code: {code}")
    message = _expect_string(
        detail["message"], context=f"{operation} error message", nonempty=True
    )
    retryable = detail["retryable"]
    if not isinstance(retryable, bool):
        _protocol_error(
            "invalid_anki_response", f"{operation} retryable must be boolean"
        )
    return AnkiCallbackError(operation, code, message, retryable)


def _raise_callback_error(error: AnkiCallbackError) -> NoReturn:
    if error.code not in _ALL_ERROR_CODES:
        raise BridgeProtocolError(
            "invalid_anki_response", f"Unknown Anki error code: {error.code}"
        ) from error
    if error.code in _PROTOCOL_ERROR_CODES:
        raise BridgeProtocolError(error.code, error.message) from error
    if error.code == "cancelled":
        raise AnkiOperationCancelled(
            error.operation, error.message, error.retryable
        ) from error

    # Function-local by design: bootstrap.py must set ANKI_MINER_HOME first.
    from anki_miner.exceptions import AnkiConnectionError, SetupError

    if error.code in _SETUP_ERROR_CODES:
        raise SetupError(error.message) from error
    raise AnkiConnectionError(error.message) from error


class AndroidAnkiAdapter:
    """Duck-typed replacement for the desktop ``AnkiService`` on Android."""

    def __init__(
        self,
        config: Any,
        callbacks: AndroidAnkiCallbacks,
        cancellation_check: Callable[[], bool] | None = None,
    ) -> None:
        # Function-local by design: importing the builder freezes config paths.
        from anki_miner.services.anki_note_builder import REQUIRED_FIELD_KEYS

        if frozenset(REQUIRED_FIELD_KEYS) != _REQUIRED_ANKI_FIELD_KEYS:
            raise BridgeProtocolError(
                "engine_contract_drift",
                "Desktop required Anki field keys changed; update the bridge contract",
            )
        validate_anki_request_config(config)
        self.config = config
        self._callbacks = callbacks
        # The M0 seam can run standalone; the mining composition layer supplies
        # JobHandle.cancel_event.is_set once it owns adapter construction.
        self._cancellation_check = cancellation_check or (lambda: False)
        self.last_created_note_ids: list[int] = []
        self.last_skipped_duplicates = 0
        self.last_media_store_failures = 0
        self._verified_field_names: tuple[str, ...] | None = None
        self._issued_baseline_tokens: set[str] = set()
        self._consumed_baseline_tokens: set[str] = set()
        self._existing_vocab_cache: set[str] | None = None
        self._dict_media_uploaded: set[str] = set()
        self._dict_media_actual_names: dict[str, str] = {}
        self._stored_media_name_owners: dict[str, str] = {}

    def _raise_if_cancelled(self, operation: str) -> None:
        if self._cancellation_check():
            raise AnkiOperationCancelled(
                operation,
                f"Anki {operation} preparation was cancelled",
                False,
            )

    def _stream_media_digest(self, source_path: Path) -> _MediaDigest:
        """Hash one bounded regular media file without retaining its contents."""

        self._raise_if_cancelled("storeMedia")
        path_stat = source_path.stat()
        if not stat.S_ISREG(path_stat.st_mode):
            raise FileNotFoundError(source_path)
        if path_stat.st_size > _MAX_MEDIA_ASSET_BYTES:
            _protocol_error(
                "media_too_large",
                f"Media exceeds the {_MAX_MEDIA_ASSET_BYTES}-byte limit",
            )

        # SHA-1 is the existing desktop filename contract, not a security
        # decision. SHA-256 independently guards same-name collision checks.
        sha1 = hashlib.sha1()  # noqa: S324
        sha256 = hashlib.sha256()
        bytes_read = 0
        self._raise_if_cancelled("storeMedia")
        with source_path.open("rb") as source:
            opened_stat = os.fstat(source.fileno())
            if not stat.S_ISREG(opened_stat.st_mode):
                raise FileNotFoundError(source_path)
            if (
                opened_stat.st_dev,
                opened_stat.st_ino,
                opened_stat.st_size,
                opened_stat.st_mtime_ns,
            ) != (
                path_stat.st_dev,
                path_stat.st_ino,
                path_stat.st_size,
                path_stat.st_mtime_ns,
            ):
                _protocol_error(
                    "media_changed",
                    "Card media changed before it could be hashed",
                )
            if opened_stat.st_size > _MAX_MEDIA_ASSET_BYTES:
                _protocol_error(
                    "media_too_large",
                    f"Media exceeds the {_MAX_MEDIA_ASSET_BYTES}-byte limit",
                )
            while True:
                self._raise_if_cancelled("storeMedia")
                chunk = source.read(_MEDIA_HASH_CHUNK_BYTES)
                if not chunk:
                    break
                bytes_read += len(chunk)
                if bytes_read > _MAX_MEDIA_ASSET_BYTES:
                    _protocol_error(
                        "media_too_large",
                        f"Media exceeds the {_MAX_MEDIA_ASSET_BYTES}-byte limit",
                    )
                sha1.update(chunk)
                sha256.update(chunk)
            final_stat = os.fstat(source.fileno())
        self._raise_if_cancelled("storeMedia")
        current_path_stat = source_path.stat()
        if (
            bytes_read != opened_stat.st_size
            or final_stat.st_size != opened_stat.st_size
            or final_stat.st_mtime_ns != opened_stat.st_mtime_ns
            or (
                current_path_stat.st_dev,
                current_path_stat.st_ino,
                current_path_stat.st_size,
                current_path_stat.st_mtime_ns,
            )
            != (
                opened_stat.st_dev,
                opened_stat.st_ino,
                opened_stat.st_size,
                opened_stat.st_mtime_ns,
            )
        ):
            _protocol_error(
                "media_changed",
                "Card media changed while it was being hashed",
            )
        return _MediaDigest(
            size=bytes_read,
            sha1_prefix=sha1.hexdigest()[:12],
            sha256=sha256.hexdigest(),
        )

    def verify_card_target(self) -> None:
        """Validate model/fields and create the target deck only after checks."""

        if self._verified_field_names is not None:
            return

        required = {value for value in self.config.anki_fields.values() if value}
        if self.config.card_type:
            marker = self.config.card_type_marker_fields.get(self.config.card_type, "")
            if marker:
                required.add(marker)
        try:
            payload = self._callbacks.verify_target(
                {
                    "deckName": self.config.anki_deck_name,
                    "modelName": self.config.anki_note_type,
                    "requiredFields": sorted(required),
                }
            )
        except AnkiCallbackError as error:
            _raise_callback_error(error)

        result = _expect_exact_keys(
            payload,
            {"runId", "requestId", "deckId", "modelId", "fieldNames", "deckCreated"},
            context="verifyTarget result",
        )
        _expect_positive_int(result["deckId"], context="deckId")
        _expect_positive_int(result["modelId"], context="modelId")
        fields = _expect_string_list(
            result["fieldNames"],
            context="fieldNames",
            unique=True,
            nonempty=True,
        )
        if not fields:
            _protocol_error("invalid_anki_response", "fieldNames must not be empty")
        if not isinstance(result["deckCreated"], bool):
            _protocol_error("invalid_anki_response", "deckCreated must be boolean")

        missing = required - set(fields)
        if missing:
            from anki_miner.exceptions import SetupError

            available = ", ".join(sorted(fields)[:5])
            more = "..." if len(fields) > 5 else ""
            raise SetupError(
                f"Field(s) {', '.join(sorted(missing))} not found on note type "
                f"'{self.config.anki_note_type}'. Available: {available}{more}. "
                "Check Settings → Anki field mapping."
            )
        self._verified_field_names = tuple(fields)

    def _scan_known_vocabulary_page(
        self, cursor: dict[str, Any] | None
    ) -> tuple[list[str], int, dict[str, Any] | None]:
        limits = {
            "maxScannedNotes": _KNOWN_VOCABULARY_PAGE_ITEMS,
            "maxTotalScannedNotes": _MAX_KNOWN_VOCABULARY_SCANNED_NOTES,
            "maxItems": _KNOWN_VOCABULARY_PAGE_ITEMS,
            "maxItemUtf8Bytes": _MAX_RAW_FIRST_FIELD_UTF8_BYTES,
            "maxTotalUtf8Bytes": _KNOWN_VOCABULARY_PAGE_UTF8_BYTES,
        }
        try:
            payload = self._callbacks.scan_first_fields(
                {
                    "scope": {
                        "kind": "knownVocabulary",
                        "excludedDecks": list(self.config.excluded_decks),
                        "cursor": cursor,
                        "limits": limits,
                    }
                }
            )
        except AnkiCallbackError:
            raise
        result = _expect_exact_keys(
            payload,
            {"runId", "requestId", "firstFields", "scannedNotes", "nextCursor"},
            context="known-vocabulary page result",
        )
        raw_fields = _expect_string_list(
            result["firstFields"], context="firstFields"
        )
        if len(raw_fields) > _KNOWN_VOCABULARY_PAGE_ITEMS:
            _protocol_error(
                "invalid_anki_response",
                "Known-vocabulary page contains too many fields",
            )
        total_utf8_bytes = 0
        for index, raw_field in enumerate(raw_fields):
            raw_bytes = len(raw_field.encode("utf-8"))
            if raw_bytes > _MAX_RAW_FIRST_FIELD_UTF8_BYTES:
                _protocol_error(
                    "invalid_anki_response",
                    f"Known-vocabulary first field {index} is too large",
                )
            total_utf8_bytes += raw_bytes
            if total_utf8_bytes > _KNOWN_VOCABULARY_PAGE_UTF8_BYTES:
                _protocol_error(
                    "invalid_anki_response",
                    "Known-vocabulary page exceeds the UTF-8 budget",
                )

        scanned_notes = normalize_integral_json_number(result["scannedNotes"])
        if (
            scanned_notes is None
            or scanned_notes < len(raw_fields)
            or scanned_notes > _KNOWN_VOCABULARY_PAGE_ITEMS
        ):
            _protocol_error(
                "invalid_anki_response",
                "Known-vocabulary scanned-note count is invalid",
            )

        raw_next_cursor = result["nextCursor"]
        if raw_next_cursor is None:
            next_cursor = None
        else:
            next_cursor = _expect_exact_keys(
                raw_next_cursor,
                {"ordinal", "token"},
                context="known-vocabulary nextCursor",
            )
            token = _expect_string(
                next_cursor["token"],
                context="known-vocabulary cursor token",
                nonempty=True,
            )
            if len(token.encode("utf-8")) > _MAX_KNOWN_CURSOR_UTF8_BYTES:
                _protocol_error(
                    "invalid_anki_response",
                    "Known-vocabulary cursor token is too large",
                )
            ordinal = normalize_integral_json_number(next_cursor["ordinal"])
            expected_ordinal = 1 if cursor is None else cursor["ordinal"] + 1
            if ordinal != expected_ordinal or scanned_notes == 0:
                _protocol_error(
                    "invalid_anki_response",
                    "Known-vocabulary cursor did not advance monotonically",
                )
            next_cursor = {"ordinal": ordinal, "token": token}
        return raw_fields, scanned_notes, next_cursor

    def get_existing_vocabulary(self) -> set[str]:
        """Return cached, desktop-normalized Japanese first fields."""

        if self._existing_vocab_cache is not None:
            return self._existing_vocab_cache

        from anki_miner.services.anki_note_builder import _strip_for_dedup

        existing: set[str] = set()
        cursor: dict[str, Any] | None = None
        seen_cursor_tokens: set[str] = set()
        total_scanned_notes = 0
        try:
            while True:
                raw_fields, scanned_notes, next_cursor = (
                    self._scan_known_vocabulary_page(cursor)
                )
                total_scanned_notes += scanned_notes
                if total_scanned_notes > _MAX_KNOWN_VOCABULARY_SCANNED_NOTES:
                    _protocol_error(
                        "invalid_anki_response",
                        "Known-vocabulary scan exceeds its total note ceiling",
                    )
                if (
                    total_scanned_notes == _MAX_KNOWN_VOCABULARY_SCANNED_NOTES
                    and next_cursor is not None
                ):
                    _protocol_error(
                        "invalid_anki_response",
                        "Known-vocabulary scan continued past its total note ceiling",
                    )
                for raw in raw_fields:
                    normalized = _strip_for_dedup(raw)
                    if normalized and _JAPANESE_RE.search(normalized):
                        existing.add(normalized)
                if next_cursor is None:
                    break
                token = next_cursor["token"]
                if token in seen_cursor_tokens:
                    _protocol_error(
                        "invalid_anki_response",
                        "Known-vocabulary cursor token was reused",
                    )
                seen_cursor_tokens.add(token)
                cursor = next_cursor
        except AnkiCallbackError as error:
            if error.code == "timeout" and error.retryable:
                logger.warning(
                    "Failed to fetch existing vocabulary (filtering disabled): %s",
                    error,
                )
                return set()
            _raise_callback_error(error)

        self._existing_vocab_cache = existing
        return existing

    def invalidate_existing_vocabulary_cache(self) -> None:
        self._existing_vocab_cache = None

    def _parse_store_media_result(
        self, payload: dict[str, Any], assets: Sequence[_MediaAsset]
    ) -> _StoreAssetsOutcome:
        result = _expect_exact_keys(
            payload,
            {"runId", "requestId", "results", "error"},
            context="storeMedia result",
        )
        rows = result["results"]
        if not isinstance(rows, list) or len(rows) != len(assets):
            _protocol_error(
                "invalid_anki_response",
                "storeMedia results must align with requested assets",
            )

        claims: dict[str, set[str]] = {}
        for asset in assets:
            for claimed_name in (asset.requested_name, asset.original_name):
                claims.setdefault(claimed_name, set()).add(asset.asset_id)

        stored: dict[str, str] = {}
        actual_names: set[str] = set()
        pending_name_owners: dict[str, str] = {}
        saw_not_attempted = False
        for index, (row_value, asset) in enumerate(zip(rows, assets, strict=True)):
            if not isinstance(row_value, dict):
                _protocol_error(
                    "invalid_anki_response", f"storeMedia result {index} is invalid"
                )
            status = row_value.get("status")
            if saw_not_attempted and status != "notAttempted":
                _protocol_error(
                    "invalid_anki_response",
                    "notAttempted storeMedia rows must form a strict suffix",
                )
            if status == "stored":
                row = _expect_exact_keys(
                    row_value,
                    {"assetId", "status", "actualFilename"},
                    context=f"storeMedia result {index}",
                )
                actual = _expect_actual_media_basename(
                    row["actualFilename"], context=f"storeMedia result {index} filename"
                )
                _validate_provider_filename(
                    actual,
                    asset.preferred_name,
                    requested=asset.requested_name,
                    purpose=asset.purpose,
                    context=f"storeMedia result {index} filename",
                )
                if actual in actual_names:
                    _protocol_error(
                        "media_name_collision",
                        "storeMedia returned the same filename for multiple assets",
                    )
                other_claimants = claims.get(actual, set()) - {asset.asset_id}
                if other_claimants:
                    _protocol_error(
                        "media_name_collision",
                        "storeMedia filename collides with another requested media name",
                    )
                prior_owner = self._stored_media_name_owners.get(actual)
                if prior_owner is not None and prior_owner != asset.original_name:
                    _protocol_error(
                        "media_name_collision",
                        "storeMedia filename was already assigned to different media",
                    )
                actual_names.add(actual)
                pending_name_owners[actual] = asset.original_name
                stored[asset.asset_id] = actual
            elif status == "failed":
                row = _expect_exact_keys(
                    row_value,
                    {"assetId", "status", "error"},
                    context=f"storeMedia result {index}",
                )
                error = _expect_error_detail(row["error"], operation="storeMedia")
                if error.code not in _RECOVERABLE_MEDIA_ERROR_CODES:
                    _raise_callback_error(error)
                logger.warning(
                    "Failed to store media asset %s: %s", asset.original_name, error
                )
            elif status == "notAttempted":
                row = _expect_exact_keys(
                    row_value,
                    {"assetId", "status"},
                    context=f"storeMedia result {index}",
                )
                saw_not_attempted = True
            else:
                _protocol_error(
                    "invalid_anki_response",
                    f"storeMedia result {index} status is invalid",
                )
            if row.get("assetId") != asset.asset_id:
                _protocol_error(
                    "mismatched_callback_response",
                    "storeMedia results are not request-aligned",
                )

        raw_error = result["error"]
        error = (
            None
            if raw_error is None
            else _expect_error_detail(raw_error, operation="storeMedia")
        )
        if saw_not_attempted and error is None:
            _protocol_error(
                "invalid_anki_response",
                "notAttempted storeMedia rows require a top-level error",
            )
        if error is not None and not saw_not_attempted:
            _protocol_error(
                "invalid_anki_response",
                "storeMedia top-level errors require a notAttempted suffix",
            )
        self._stored_media_name_owners.update(pending_name_owners)
        return _StoreAssetsOutcome(stored, error)

    def _store_assets(self, assets: list[_MediaAsset]) -> _StoreAssetsOutcome:
        if not assets:
            return _StoreAssetsOutcome({})
        asset_ids: set[str] = set()
        requested_name_owners: dict[str, str] = {}
        for index, asset in enumerate(assets):
            if asset.asset_id in asset_ids:
                _protocol_error(
                    "invalid_anki_request", "Media asset IDs must be unique"
                )
            asset_ids.add(asset.asset_id)
            if asset.purpose not in {"card", "dictionary"} or asset.media_kind not in {
                "audio",
                "image",
            }:
                _protocol_error(
                    "invalid_anki_request", "Media asset metadata is invalid"
                )
            _expect_filename(
                asset.preferred_name,
                context=f"storeMedia asset {index} preferredName",
                code="invalid_anki_request",
            )
            name_validator = (
                _expect_media_basename
                if asset.purpose == "dictionary"
                else _expect_filename
            )
            name_validator(
                asset.requested_name,
                context=f"storeMedia asset {index} requestedFilename",
                code="invalid_anki_request",
            )
            name_validator(
                asset.original_name,
                context=f"storeMedia asset {index} originalName",
                code="invalid_anki_request",
            )
            expected_preferred = (
                _dictionary_provider_preferred_name(asset.original_name)
                if asset.purpose == "dictionary"
                else _provider_preferred_name(asset.requested_name)
            )
            if asset.preferred_name != expected_preferred:
                _protocol_error(
                    "invalid_anki_request",
                    "Media preferredName is inconsistent with the requested media",
                )
            for claimed_name in {asset.requested_name, asset.original_name}:
                prior_owner = requested_name_owners.setdefault(
                    claimed_name, asset.asset_id
                )
                if prior_owner != asset.asset_id:
                    _protocol_error(
                        "media_name_collision",
                        "Requested media names collide before provider insertion",
                    )
            if not Path(asset.source_path).is_absolute():
                _protocol_error(
                    "invalid_anki_request", "Media source paths must be absolute"
                )
            if (
                type(asset.expected_size_bytes) is not int
                or not 0 <= asset.expected_size_bytes <= _MAX_MEDIA_ASSET_BYTES
                or not _SHA256_RE.fullmatch(asset.expected_sha256)
            ):
                _protocol_error(
                    "invalid_anki_request", "Media integrity metadata is invalid"
                )

        stored: dict[str, str] = {}
        limits = {
            "maxAssets": _MEDIA_BATCH_SIZE,
            "maxAssetBytes": _MAX_MEDIA_ASSET_BYTES,
            "maxTotalBytes": _MAX_MEDIA_CALLBACK_BYTES,
        }
        for chunk in _chunk_media_assets(assets):
            try:
                self._raise_if_cancelled("storeMedia")
                payload = self._callbacks.store_media(
                    {
                        "assets": [asset.to_wire() for asset in chunk],
                        "limits": limits,
                    }
                )
            except AnkiOperationCancelled as error:
                return _StoreAssetsOutcome(
                    stored,
                    AnkiCallbackError(
                        "storeMedia", "cancelled", str(error), error.retryable
                    ),
                )
            except AnkiCallbackError as error:
                return _StoreAssetsOutcome(stored, error)
            chunk_outcome = self._parse_store_media_result(payload, chunk)
            if stored.keys() & chunk_outcome.stored.keys():
                _protocol_error(
                    "mismatched_callback_response",
                    "storeMedia returned an asset more than once",
                )
            stored.update(chunk_outcome.stored)
            if chunk_outcome.error is not None:
                return _StoreAssetsOutcome(stored, chunk_outcome.error)
        return _StoreAssetsOutcome(stored)

    def _store_card_media(self, word_data_list: Sequence[Any]) -> set[str]:
        from anki_miner.services.anki_media_store import (
            _MEDIA_FIELD_ATTRS,
        )

        paths_by_filename: dict[str, list[Path]] = {}
        refs: dict[str, list[_CardMediaRef]] = {}
        kinds: dict[str, set[str]] = {}

        for item in word_data_list:
            media = item.media
            for filename_attr, path_attr in _MEDIA_FIELD_ATTRS:
                filename = getattr(media, filename_attr)
                source_path = getattr(media, path_attr)
                if not filename or not source_path:
                    continue
                path = Path(source_path)
                try:
                    resolved_path = path.resolve()
                except OSError:
                    resolved_path = path.absolute()
                refs.setdefault(filename, []).append(
                    _CardMediaRef(media, filename_attr, resolved_path)
                )
                kinds.setdefault(filename, set()).add(
                    "image" if filename_attr == "screenshot_filename" else "audio"
                )
                paths = paths_by_filename.setdefault(filename, [])
                if resolved_path not in paths:
                    paths.append(resolved_path)

        assets: list[_MediaAsset] = []
        originals_by_id: dict[str, str] = {}
        for filename, referenced_paths in paths_by_filename.items():
            if len(kinds[filename]) != 1:
                _protocol_error(
                    "media_content_collision",
                    f"Media filename {filename!r} is used as both audio and image",
                )

            readable: list[tuple[Path, _MediaDigest]] = []
            unreadable: list[tuple[Path, OSError]] = []
            for source_path in referenced_paths:
                try:
                    digest = self._stream_media_digest(source_path)
                    readable.append((source_path, digest))
                except OSError as error:
                    unreadable.append((source_path, error))
            if len(referenced_paths) > 1 and unreadable:
                _protocol_error(
                    "media_content_collision",
                    f"Cannot verify colliding media filename {filename!r} across all paths",
                )
            if not readable:
                if unreadable:
                    logger.warning(
                        "Failed to read media file %s: %s", filename, unreadable[0][1]
                    )
                continue
            source_path, digest = readable[0]
            if any(
                (candidate.size, candidate.sha256) != (digest.size, digest.sha256)
                for _, candidate in readable[1:]
            ):
                _protocol_error(
                    "media_content_collision",
                    f"Media filename {filename!r} refers to different file contents",
                )

            requested_name = _content_addressed_name_from_digest(
                filename, digest.sha1_prefix
            )
            asset_id = f"asset_{uuid4().hex}"
            originals_by_id[asset_id] = filename
            assets.append(
                _MediaAsset(
                    asset_id=asset_id,
                    source_path=str(source_path),
                    preferred_name=_provider_preferred_name(requested_name),
                    requested_name=requested_name,
                    original_name=filename,
                    purpose="card",
                    media_kind=next(iter(kinds[filename])),
                    expected_size_bytes=digest.size,
                    expected_sha256=digest.sha256,
                )
            )

        outcome = self._store_assets(assets)
        stored_names: set[str] = set()
        renamed_originals: set[str] = set()
        for asset_id, actual in outcome.stored.items():
            original = originals_by_id[asset_id]
            renamed_originals.add(original)
            stored_names.add(actual)
            for ref in refs.get(original, []):
                setattr(ref.media, ref.filename_attr, actual)

        self.last_media_store_failures = len(refs) - len(renamed_originals)
        if outcome.error is not None:
            _raise_callback_error(outcome.error)
        return stored_names

    @staticmethod
    def _rewrite_dictionary_html(value: str, actual_names: Mapping[str, str]) -> str:
        """Rewrite only renderer-marked dictionary image ``src`` attributes."""

        from anki_miner.services.anki_media_store import (
            _DICT_MEDIA_IMG_RE,
            _IMG_SRC_RE,
        )

        def rewrite_tag(match: re.Match[str]) -> str:
            tag = match.group(0)
            src_match = _IMG_SRC_RE.search(tag)
            if src_match is None:
                return tag
            original = html_unescape(src_match.group(1))
            actual = actual_names.get(original)
            if actual is None or actual == original:
                return tag
            escaped_actual = html_escape(actual, quote=True)
            return tag[: src_match.start(1)] + escaped_actual + tag[src_match.end(1) :]

        return _DICT_MEDIA_IMG_RE.sub(rewrite_tag, value)

    def _rewrite_dictionary_payloads(self, word_data_list: Sequence[Any]) -> list[Any]:
        from dataclasses import replace

        rewrite_names = {
            **self._dict_media_actual_names,
            **{actual: actual for actual in self._dict_media_actual_names.values()},
        }
        if not rewrite_names:
            return list(word_data_list)

        rewritten_payloads: list[Any] = []
        for item in word_data_list:
            definition = item.definition
            rewritten_definition = (
                self._rewrite_dictionary_html(definition, rewrite_names)
                if isinstance(definition, str)
                else definition
            )
            extra_fields = item.extra_fields
            rewritten_extra = extra_fields
            if extra_fields and isinstance(extra_fields.get("glossary"), str):
                glossary = extra_fields["glossary"]
                rewritten_glossary = self._rewrite_dictionary_html(
                    glossary, rewrite_names
                )
                if rewritten_glossary != glossary:
                    rewritten_extra = {**extra_fields, "glossary": rewritten_glossary}
            if (
                rewritten_definition != definition
                or rewritten_extra is not extra_fields
            ):
                item = replace(
                    item,
                    definition=rewritten_definition,
                    extra_fields=rewritten_extra,
                )
            rewritten_payloads.append(item)
        return rewritten_payloads

    def _store_dictionary_media(self, word_data_list: Sequence[Any]) -> list[Any]:
        from anki_miner.services.anki_media_store import (
            _extract_dict_media_srcs,
            _resolve_dict_media_path,
        )

        seen: set[str] = set()
        sources: list[str] = []
        for item in word_data_list:
            for html_field in (
                item.definition,
                item.extra_fields.get("glossary") if item.extra_fields else None,
            ):
                if not isinstance(html_field, str):
                    continue
                for source in _extract_dict_media_srcs(html_field):
                    if source not in self._dict_media_uploaded and source not in seen:
                        seen.add(source)
                        sources.append(source)

        assets: list[_MediaAsset] = []
        sources_by_id: dict[str, str] = {}
        for source in sources:
            path = _resolve_dict_media_path(source, self.config.dicts_root)
            if path is None:
                logger.warning("Dict media file missing on disk: %s", source)
                self._dict_media_uploaded.add(source)
                continue
            try:
                digest = self._stream_media_digest(path.resolve())
            except OSError as error:
                logger.warning("Failed to read dict media file %s: %s", source, error)
                continue
            asset_id = f"asset_{uuid4().hex}"
            sources_by_id[asset_id] = source
            assets.append(
                _MediaAsset(
                    asset_id=asset_id,
                    source_path=str(path.resolve()),
                    preferred_name=_dictionary_provider_preferred_name(source),
                    requested_name=source,
                    original_name=source,
                    purpose="dictionary",
                    media_kind="image",
                    expected_size_bytes=digest.size,
                    expected_sha256=digest.sha256,
                )
            )

        outcome = self._store_assets(assets)
        for asset_id, actual in outcome.stored.items():
            source = sources_by_id[asset_id]
            self._dict_media_uploaded.add(source)
            self._dict_media_uploaded.add(actual)
            self._dict_media_actual_names[source] = actual
        rewritten = self._rewrite_dictionary_payloads(word_data_list)
        if outcome.error is not None:
            _raise_callback_error(outcome.error)
        return rewritten

    def _prepare_note(
        self, payload: Any, note: Mapping[str, Any], source_index: int
    ) -> _PendingNote:
        """Validate one built note before any duplicate/provider side effect."""

        if self._verified_field_names is None:
            _protocol_error("invalid_note", "The Anki target must be verified first")
        first_field_name = self._verified_field_names[0]
        fields = note.get("fields")
        tags = note.get("tags")
        if not isinstance(fields, dict) or not fields:
            _protocol_error("invalid_note", "A note must contain at least one field")
        if len(fields) > _MAX_NOTE_FIELDS:
            _protocol_error("note_too_large", "A note contains too many fields")

        content_utf8_bytes = 0
        for field_name, value in fields.items():
            if not isinstance(field_name, str) or not field_name:
                _protocol_error(
                    "invalid_note", "Anki field names must be non-empty strings"
                )
            if not isinstance(value, str):
                _protocol_error("invalid_note", "Anki field values must be strings")
            name_bytes = len(field_name.encode("utf-8"))
            value_bytes = len(value.encode("utf-8"))
            if name_bytes > _MAX_FIELD_NAME_UTF8_BYTES:
                _protocol_error("note_too_large", "An Anki field name is too large")
            if value_bytes > _MAX_FIELD_VALUE_UTF8_BYTES:
                _protocol_error("note_too_large", "An Anki field value is too large")
            content_utf8_bytes += name_bytes + value_bytes

        if not isinstance(tags, list) or not all(
            isinstance(tag, str) and tag for tag in tags
        ):
            _protocol_error("invalid_note", "Anki tags must be non-empty strings")
        if len(tags) > _MAX_NOTE_TAGS:
            _protocol_error("note_too_large", "A note contains too many Anki tags")
        tags_utf8_bytes = 0
        for tag in tags:
            tag_bytes = len(tag.encode("utf-8"))
            if tag_bytes > _MAX_TAG_UTF8_BYTES:
                _protocol_error("note_too_large", "An Anki tag is too large")
            tags_utf8_bytes += tag_bytes
        if tags_utf8_bytes > _MAX_NOTE_TAGS_UTF8_BYTES:
            _protocol_error("note_too_large", "Anki tags exceed the note byte limit")
        content_utf8_bytes += tags_utf8_bytes
        if content_utf8_bytes > _MAX_NOTE_CONTENT_UTF8_BYTES:
            _protocol_error("note_too_large", "An Anki note exceeds the byte limit")

        first_field = fields.get(first_field_name)
        if not isinstance(first_field, str):
            _protocol_error(
                "invalid_note",
                f"The verified first field {first_field_name!r} is missing",
            )
        first_field_bytes = len(first_field.encode("utf-8"))
        from anki_miner.services.anki_note_builder import _strip_for_dedup

        key = _strip_for_dedup(first_field)
        if (
            not key
            or len(key) > _MAX_DUPLICATE_KEY_CHARS
            or len(first_field) > _MAX_DUPLICATE_FIRST_FIELD_CHARS
            or first_field_bytes > _MAX_RAW_FIRST_FIELD_UTF8_BYTES
        ):
            _protocol_error(
                "invalid_note",
                "The verified Anki first field has an invalid duplicate identity",
            )
        return _PendingNote(
            payload=payload,
            note={"fields": dict(fields), "tags": list(tags)},
            key=key,
            first_field=first_field,
            content_utf8_bytes=content_utf8_bytes,
            source_index=source_index,
        )

    @staticmethod
    def _create_limits() -> dict[str, int]:
        return {
            "maxNotes": _BATCH_SIZE,
            "maxFieldsPerNote": _MAX_NOTE_FIELDS,
            "maxFieldNameUtf8Bytes": _MAX_FIELD_NAME_UTF8_BYTES,
            "maxFieldValueUtf8Bytes": _MAX_FIELD_VALUE_UTF8_BYTES,
            "maxTagsPerNote": _MAX_NOTE_TAGS,
            "maxTagUtf8Bytes": _MAX_TAG_UTF8_BYTES,
            "maxTagsUtf8BytesPerNote": _MAX_NOTE_TAGS_UTF8_BYTES,
            "maxNoteContentUtf8Bytes": _MAX_NOTE_CONTENT_UTF8_BYTES,
            "maxTotalContentUtf8Bytes": _MAX_CREATE_CONTENT_UTF8_BYTES,
            "maxEnvelopeUtf8Bytes": _MAX_CREATE_ENVELOPE_UTF8_BYTES,
        }

    def _create_duplicate_scope(self) -> dict[str, Any]:
        snapshot_limits = {
            "maxNoteIdsPerCandidate": _MAX_DUPLICATE_HITS_PER_CANDIDATE,
            "maxTotalNoteIds": _MAX_DUPLICATE_TOTAL_HITS,
        }
        if self.config.allow_duplicate_cards:
            return {
                "kind": "exactDeck",
                "deckName": self.config.anki_deck_name,
                "includeChildren": False,
                "limits": snapshot_limits,
            }
        return {"kind": "collection", "limits": snapshot_limits}

    @staticmethod
    def _wire_note(
        pending: _PendingNote, client_id: str, occurrence: int
    ) -> dict[str, Any]:
        return {
            "clientNoteId": client_id,
            "fields": dict(pending.note["fields"]),
            "tags": list(pending.note["tags"]),
            "duplicateCandidate": {
                "key": pending.key,
                "firstField": pending.first_field,
                "occurrence": occurrence,
            },
        }

    def _create_request_payload(
        self,
        notes: Sequence[_PendingNote],
        baseline_token: str,
        client_ids: Sequence[str],
        occurrences: Sequence[int],
    ) -> dict[str, Any]:
        if self._verified_field_names is None:
            _protocol_error("invalid_note", "The Anki target must be verified first")
        return {
            "deckName": self.config.anki_deck_name,
            "modelName": self.config.anki_note_type,
            "firstFieldName": self._verified_field_names[0],
            "baselineToken": baseline_token,
            "duplicateScope": self._create_duplicate_scope(),
            "limits": self._create_limits(),
            "notes": [
                self._wire_note(note, client_id, occurrence)
                for note, client_id, occurrence in zip(
                    notes, client_ids, occurrences, strict=True
                )
            ],
        }

    def _create_envelope_utf8_bytes(self, notes: Sequence[_PendingNote]) -> int:
        client_ids = [f"note_{index:032x}" for index in range(len(notes))]
        payload = self._create_request_payload(
            notes,
            _PLACEHOLDER_BASELINE_TOKEN,
            client_ids,
            list(range(len(notes))),
        )
        envelope = encode_message(
            "anki.createnotes.request",
            {
                **payload,
                "runId": self._callbacks.run_id,
                "requestId": _PLACEHOLDER_REQUEST_ID,
            },
        )
        return len(envelope.encode("utf-8"))

    def _create_batch_fits(self, notes: Sequence[_PendingNote]) -> bool:
        return bool(notes) and (
            len(notes) <= _BATCH_SIZE
            and sum(note.content_utf8_bytes for note in notes)
            <= _MAX_CREATE_CONTENT_UTF8_BYTES
            and self._create_envelope_utf8_bytes(notes)
            <= _MAX_CREATE_ENVELOPE_UTF8_BYTES
        )

    def _chunk_pending_notes(
        self, notes: Sequence[_PendingNote]
    ) -> list[list[_PendingNote]]:
        """Pack exact-size callbacks without splitting repeated identities."""

        if not notes:
            return []
        last_occurrence = {
            (note.key, note.first_field): index for index, note in enumerate(notes)
        }
        blocks: list[list[_PendingNote]] = []
        start = 0
        while start < len(notes):
            end = last_occurrence[(notes[start].key, notes[start].first_field)]
            cursor = start
            while cursor <= end:
                identity = (notes[cursor].key, notes[cursor].first_field)
                end = max(end, last_occurrence[identity])
                cursor += 1
            blocks.append(list(notes[start : end + 1]))
            start = end + 1

        chunks: list[list[_PendingNote]] = []
        current: list[_PendingNote] = []
        for block in blocks:
            if not self._create_batch_fits(block):
                _protocol_error(
                    "note_batch_too_large",
                    "Repeated duplicate candidates cannot fit one safe callback",
                )
            candidate = [*current, *block]
            if current and not self._create_batch_fits(candidate):
                chunks.append(current)
                current = block
            else:
                current = candidate
        if current:
            chunks.append(current)
        return chunks

    def _duplicate_first_fields(
        self, candidates: Sequence[tuple[str, str]]
    ) -> tuple[list[_DuplicateProbeResult], str]:
        if not candidates:
            _protocol_error("invalid_note", "Duplicate candidates must not be empty")
        if self._verified_field_names is None:
            _protocol_error("invalid_note", "The Anki target must be verified first")
        from anki_miner.services.anki_note_builder import _strip_for_dedup

        candidate_list = list(candidates)
        if (
            len(candidate_list) > _BATCH_SIZE
            or any(
                not isinstance(key, str)
                or not key
                or len(key) > _MAX_DUPLICATE_KEY_CHARS
                for key, _ in candidate_list
            )
            or any(
                not isinstance(first_field, str)
                or len(first_field) > _MAX_DUPLICATE_FIRST_FIELD_CHARS
                or _strip_for_dedup(first_field) != key
                for key, first_field in candidate_list
            )
        ):
            _protocol_error(
                "invalid_note",
                "Duplicate candidates must be normalized, non-empty, and bounded",
            )
        # Provider identity includes the exact first-field checksum. Collapse only
        # identical wire probes, then restore their position-aligned decisions.
        unique_candidates = list(dict.fromkeys(candidate_list))
        candidate_indexes = {
            candidate: index for index, candidate in enumerate(unique_candidates)
        }
        scope = {
            "kind": "duplicates",
            "modelName": self.config.anki_note_type,
            "firstFieldName": self._verified_field_names[0],
            "deckName": (
                self.config.anki_deck_name
                if self.config.allow_duplicate_cards
                else None
            ),
            "candidates": [
                {"key": key, "firstField": first_field}
                for key, first_field in unique_candidates
            ],
            "occurrences": [
                candidate_indexes[candidate] for candidate in candidate_list
            ],
            "limits": {
                "maxHitsPerCandidate": _MAX_DUPLICATE_HITS_PER_CANDIDATE,
                "maxTotalHits": _MAX_DUPLICATE_TOTAL_HITS,
                "maxItemUtf8Bytes": _MAX_RAW_FIRST_FIELD_UTF8_BYTES,
                "maxTotalUtf8Bytes": _MAX_DUPLICATE_HITS_UTF8_BYTES,
            },
        }
        try:
            payload = self._callbacks.scan_first_fields({"scope": scope})
        except AnkiCallbackError as error:
            _raise_callback_error(error)
        result = _expect_exact_keys(
            payload,
            {"runId", "requestId", "rawFirstFieldHits", "baselineToken"},
            context="duplicate lookup result",
        )
        baseline_token = _expect_string(
            result["baselineToken"],
            context="duplicate lookup baselineToken",
            nonempty=True,
        )
        if (
            _BASELINE_TOKEN_RE.fullmatch(baseline_token) is None
            or baseline_token in self._issued_baseline_tokens
            or baseline_token in self._consumed_baseline_tokens
        ):
            _protocol_error(
                "invalid_anki_response",
                "Duplicate lookup baselineToken is malformed or reused",
            )
        self._issued_baseline_tokens.add(baseline_token)
        raw_buckets = result["rawFirstFieldHits"]
        if not isinstance(raw_buckets, list) or len(raw_buckets) != len(
            unique_candidates
        ):
            _protocol_error(
                "invalid_anki_response",
                "Duplicate lookup raw fields must align with candidates",
            )
        total_hits = 0
        total_utf8_bytes = 0
        probe_results: list[_DuplicateProbeResult] = []
        for index, ((key, _), bucket) in enumerate(
            zip(unique_candidates, raw_buckets, strict=True)
        ):
            if not isinstance(bucket, list) or len(bucket) > (
                _MAX_DUPLICATE_HITS_PER_CANDIDATE
            ):
                _protocol_error(
                    "invalid_anki_response",
                    f"Duplicate lookup raw field bucket {index} is invalid",
                )
            total_hits += len(bucket)
            if total_hits > _MAX_DUPLICATE_TOTAL_HITS:
                _protocol_error(
                    "invalid_anki_response",
                    "Duplicate lookup returned too many raw fields",
                )
            normalized_match = False
            note_ids: set[int] = set()
            for raw_index, raw_hit in enumerate(bucket):
                row = _expect_exact_keys(
                    raw_hit,
                    {"noteId", "firstField"},
                    context=f"duplicate raw field {index}:{raw_index}",
                )
                note_id = _expect_positive_int(
                    row["noteId"], context=f"duplicate noteId {index}:{raw_index}"
                )
                if note_id in note_ids:
                    _protocol_error(
                        "invalid_anki_response",
                        f"Duplicate raw field bucket {index} repeats a note ID",
                    )
                note_ids.add(note_id)
                raw_value = row["firstField"]
                if not isinstance(raw_value, str):
                    _protocol_error(
                        "invalid_anki_response",
                        f"Duplicate raw field {index}:{raw_index} must be a string",
                    )
                raw_bytes = len(raw_value.encode("utf-8"))
                if raw_bytes > _MAX_RAW_FIRST_FIELD_UTF8_BYTES:
                    _protocol_error(
                        "invalid_anki_response",
                        f"Duplicate raw field {index}:{raw_index} is too large",
                    )
                total_utf8_bytes += raw_bytes
                if total_utf8_bytes > _MAX_DUPLICATE_HITS_UTF8_BYTES:
                    _protocol_error(
                        "invalid_anki_response",
                        "Duplicate lookup raw fields exceed the UTF-8 budget",
                    )
                if _strip_for_dedup(raw_value) == key:
                    normalized_match = True
            probe_results.append(
                _DuplicateProbeResult(is_duplicate=normalized_match, occurrence=-1)
            )
        results_by_candidate = dict(
            zip(unique_candidates, probe_results, strict=True)
        )
        return (
            [
                _DuplicateProbeResult(
                    is_duplicate=results_by_candidate[candidate].is_duplicate,
                    occurrence=occurrence,
                )
                for occurrence, candidate in enumerate(candidate_list)
            ],
            baseline_token,
        )

    def _parse_create_notes_result(
        self, payload: dict[str, Any], client_ids: Sequence[str]
    ) -> tuple[list[int | None], list[bool], int, AnkiCallbackError | None]:
        result = _expect_exact_keys(
            payload,
            {"runId", "requestId", "results", "error"},
            context="createNotes result",
        )
        rows = result["results"]
        if not isinstance(rows, list) or len(rows) != len(client_ids):
            _protocol_error(
                "invalid_anki_response",
                "createNotes results must align with requested notes",
            )

        note_ids: list[int | None] = []
        successful: list[bool] = []
        duplicates = 0
        saw_terminal_failure = False
        saw_not_attempted = False
        saw_uncertain = False
        saw_committed_failure = False
        created_ids: set[int] = set()
        for index, (row_value, expected_id) in enumerate(
            zip(rows, client_ids, strict=True)
        ):
            if not isinstance(row_value, dict):
                _protocol_error(
                    "invalid_anki_response", f"createNotes result {index} is invalid"
                )
            status = row_value.get("status")
            if saw_not_attempted and status != "notAttempted":
                _protocol_error(
                    "invalid_anki_response",
                    "notAttempted createNotes rows must form a strict suffix",
                )
            if saw_terminal_failure and status != "notAttempted":
                _protocol_error(
                    "invalid_anki_response",
                    "A failed createNotes row must end provider attempts",
                )
            if status == "created":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status", "noteId"},
                    context=f"createNotes result {index}",
                )
                note_id = _expect_positive_int(row["noteId"], context=f"noteId {index}")
                if note_id in created_ids:
                    _protocol_error(
                        "invalid_anki_response",
                        "createNotes returned duplicate note IDs",
                    )
                created_ids.add(note_id)
                note_ids.append(note_id)
                successful.append(True)
            elif status == "duplicate":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status"},
                    context=f"createNotes result {index}",
                )
                note_ids.append(None)
                successful.append(False)
                duplicates += 1
            elif status == "failed":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status"},
                    context=f"createNotes result {index}",
                )
                note_ids.append(None)
                successful.append(False)
                saw_terminal_failure = True
            elif status == "committedFailed":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status", "noteId"},
                    context=f"createNotes result {index}",
                )
                note_id = _expect_positive_int(row["noteId"], context=f"noteId {index}")
                if note_id in created_ids:
                    _protocol_error(
                        "invalid_anki_response",
                        "createNotes returned duplicate note IDs",
                    )
                created_ids.add(note_id)
                note_ids.append(note_id)
                successful.append(False)
                saw_terminal_failure = True
                saw_committed_failure = True
            elif status == "uncertain":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status"},
                    context=f"createNotes result {index}",
                )
                note_ids.append(None)
                successful.append(False)
                saw_terminal_failure = True
                saw_uncertain = True
            elif status == "notAttempted":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status"},
                    context=f"createNotes result {index}",
                )
                note_ids.append(None)
                successful.append(False)
                saw_not_attempted = True
            else:
                _protocol_error(
                    "invalid_anki_response",
                    f"createNotes result {index} status is invalid",
                )
            if row.get("clientNoteId") != expected_id:
                _protocol_error(
                    "mismatched_callback_response",
                    "createNotes results are not request-aligned",
                )

        raw_error = result["error"]
        error = (
            None
            if raw_error is None
            else _expect_error_detail(raw_error, operation="createNotes")
        )
        if (saw_terminal_failure or saw_not_attempted) and error is None:
            _protocol_error(
                "invalid_anki_response",
                "failed/notAttempted createNotes rows require a top-level error",
            )
        if error is not None and not (saw_terminal_failure or saw_not_attempted):
            _protocol_error(
                "invalid_anki_response",
                "createNotes top-level error requires a failed or notAttempted row",
            )
        if error is not None:
            if saw_uncertain and (
                error.code != "post_commit_uncertain" or error.retryable
            ):
                _protocol_error(
                    "invalid_anki_response",
                    "An uncertain write requires a non-retryable post-commit error",
                )
            if saw_committed_failure and (
                error.code == "cancelled" or error.retryable
            ):
                _protocol_error(
                    "invalid_anki_response",
                    "A known post-commit failure must be non-retryable",
                )
            if created_ids and error.retryable:
                _protocol_error(
                    "invalid_anki_response",
                    "A partial write cannot be declared safely retryable",
                )
            if created_ids and error.code == "cancelled":
                error = AnkiCallbackError(
                    "createNotes", "post_commit_uncertain", error.message, False
                )
        return note_ids, successful, duplicates, error

    def _create_note_batch(
        self,
        notes: Sequence[_PendingNote],
        baseline_token: str,
        occurrences: Sequence[int],
    ) -> tuple[list[int | None], list[bool], int, AnkiCallbackError | None]:
        if not notes or len(notes) > _BATCH_SIZE:
            _protocol_error(
                "invalid_note",
                "Create-note batches must contain between 1 and 100 notes",
            )
        if (
            _BASELINE_TOKEN_RE.fullmatch(baseline_token) is None
            or baseline_token not in self._issued_baseline_tokens
            or baseline_token in self._consumed_baseline_tokens
        ):
            _protocol_error(
                "invalid_note", "Create-note duplicate baseline token is invalid"
            )
        if (
            len(occurrences) != len(notes)
            or any(type(value) is not int or value < 0 for value in occurrences)
            or list(occurrences) != sorted(set(occurrences))
        ):
            _protocol_error(
                "invalid_note", "Create-note duplicate occurrences are invalid"
            )
        if not self._create_batch_fits(notes):
            _protocol_error(
                "note_batch_too_large", "Create-note callback exceeds its byte limit"
            )
        client_ids = [f"note_{uuid4().hex}" for _ in notes]
        request_payload = self._create_request_payload(
            notes, baseline_token, client_ids, occurrences
        )
        actual_envelope_bytes = len(
            encode_message(
                "anki.createnotes.request",
                {
                    **request_payload,
                    "runId": self._callbacks.run_id,
                    "requestId": _PLACEHOLDER_REQUEST_ID,
                },
            ).encode("utf-8")
        )
        if actual_envelope_bytes > _MAX_CREATE_ENVELOPE_UTF8_BYTES:
            _protocol_error(
                "note_batch_too_large", "Create-note callback exceeds its byte limit"
            )
        self._raise_if_cancelled("createNotes")
        self._consumed_baseline_tokens.add(baseline_token)
        try:
            payload = self._callbacks.create_notes(request_payload)
        except AnkiCallbackError as error:
            if error.code == "post_commit_uncertain":
                _protocol_error(
                    "invalid_anki_response",
                    "Post-commit uncertainty requires an aligned createNotes result",
                )
            _raise_callback_error(error)
        return self._parse_create_notes_result(payload, client_ids)

    def create_cards_batch(
        self, word_data_list: list[Any], progress_callback: Any | None = None
    ) -> int:
        """Create cards in desktop-compatible batches and preserve partial state."""

        if not word_data_list:
            self.last_created_note_ids = []
            self.last_skipped_duplicates = 0
            self.last_media_store_failures = 0
            return 0

        # Duplicate identity is the model's first field, not Python dict order.
        # Verify once before media or note-write side effects and retain the
        # provider-reported field order for the whole adapter run.
        self.verify_card_target()

        self.last_created_note_ids = []
        self.last_skipped_duplicates = 0
        self.last_media_store_failures = 0
        all_created_ids: list[int] = []
        created_forms: list[str] = []
        skipped_duplicates = 0
        total_created = 0
        bold_used = 0
        bold_fallback = 0

        if progress_callback:
            progress_callback.on_start(len(word_data_list), "Creating Anki cards")

        stored_files = self._store_card_media(word_data_list)
        word_data_list = self._store_dictionary_media(word_data_list)

        from anki_miner.services.anki_note_builder import _strip_for_dedup, build_note

        seen_outgoing: set[str] = set()
        try:
            pending_notes: list[_PendingNote] = []
            for source_index, item in enumerate(word_data_list):
                built = build_note(item, self.config, stored_files)
                if built.used_precomputed_bold:
                    bold_used += 1
                if built.used_bold_fallback:
                    bold_fallback += 1
                pending = self._prepare_note(item, built.note, source_index)
                if not self.config.allow_duplicate_cards:
                    if pending.key in seen_outgoing:
                        skipped_duplicates += 1
                        continue
                    seen_outgoing.add(pending.key)
                pending_notes.append(pending)

            progress_reported = 0
            for callback_batch in self._chunk_pending_notes(pending_notes):
                duplicate_probes, baseline_token = self._duplicate_first_fields(
                    [
                        (pending.key, pending.first_field)
                        for pending in callback_batch
                    ]
                )
                submissions = [
                    (pending, probe.occurrence)
                    for pending, probe in zip(
                        callback_batch, duplicate_probes, strict=True
                    )
                    if not probe.is_duplicate
                ]
                skipped_duplicates += len(callback_batch) - len(submissions)
                if submissions:
                    submit_notes = [pending for pending, _ in submissions]
                    occurrences = [occurrence for _, occurrence in submissions]
                    note_ids, successful, residual_duplicates, partial_error = (
                        self._create_note_batch(
                            submit_notes, baseline_token, occurrences
                        )
                    )
                    skipped_duplicates += residual_duplicates
                    repeated_created_ids = set(all_created_ids).intersection(
                        note_id for note_id in note_ids if note_id is not None
                    )
                    if repeated_created_ids:
                        _protocol_error(
                            "invalid_anki_response",
                            "createNotes reused a note ID from an earlier batch",
                        )
                    total_created += sum(successful)
                    all_created_ids.extend(
                        note_id for note_id in note_ids if note_id is not None
                    )
                    created_forms.extend(
                        pending.payload.word.mined_form
                        for pending, was_successful in zip(
                            submit_notes, successful, strict=True
                        )
                        if was_successful
                    )
                    if partial_error is not None:
                        _raise_callback_error(partial_error)

                processed_through = callback_batch[-1].source_index + 1
                while (
                    progress_callback
                    and progress_reported + _BATCH_SIZE <= processed_through
                ):
                    progress_reported += _BATCH_SIZE
                    progress_callback.on_progress(
                        progress_reported,
                        f"Cards created: {total_created}/{len(word_data_list)}",
                    )

            if progress_callback and progress_reported < len(word_data_list):
                progress_callback.on_progress(
                    len(word_data_list),
                    f"Cards created: {total_created}/{len(word_data_list)}",
                )
        finally:
            self.last_created_note_ids = all_created_ids
            self.last_skipped_duplicates = skipped_duplicates
            if self._existing_vocab_cache is not None:
                for form in created_forms:
                    key = _strip_for_dedup(form)
                    if key and _JAPANESE_RE.search(key):
                        self._existing_vocab_cache.add(key)

        if progress_callback:
            progress_callback.on_complete()
        if skipped_duplicates:
            logger.info(
                "%d note(s) were not created (likely already in your collection).",
                skipped_duplicates,
            )
        if self.config.bold_target_in_sentence and word_data_list:
            logger.info(
                "bold_target_in_sentence=on: precomputed bold used on %d/%d cards (escape fallback: %d)",
                bold_used,
                len(word_data_list),
                bold_fallback,
            )
        return total_created


# Concise injection name for callers which do not care about the transport.
AnkiAdapter = AndroidAnkiAdapter
