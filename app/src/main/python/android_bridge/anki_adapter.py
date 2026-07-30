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
from collections.abc import Callable, Mapping, Sequence, Set
from dataclasses import dataclass
from html import escape as html_escape
from html import unescape as html_unescape
from pathlib import Path
from typing import Any, NoReturn
from uuid import uuid4

from .anki_limits import ANKI_LIMITS_V1
from .callbacks import AndroidAnkiCallbacks, AnkiCallbackError
from .config_map import _REQUIRED_ANKI_FIELD_KEYS, validate_anki_request_config
from .protocol import (
    BridgeProtocolError,
    encode_message,
    normalize_integral_json_number,
)
from .unicode_contract import (
    has_leading_or_trailing_python_whitespace,
    is_category_c,
    is_nfc,
)

logger = logging.getLogger(__name__)

_JAPANESE_RE = re.compile(r"[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\u3400-\u4DBF]")
_NAME_LIMITS = ANKI_LIMITS_V1["names"]
_SCAN_LIMITS = ANKI_LIMITS_V1["scanFirstFields"]
_MEDIA_LIMITS = ANKI_LIMITS_V1["storeMedia"]
_NOTE_LIMITS = ANKI_LIMITS_V1["createNotes"]
_CREATE_CALL_LIMITS = ANKI_LIMITS_V1["createCall"]

_MAX_DECK_NAME_UTF8_BYTES = _NAME_LIMITS["deck"]["maxUtf8Bytes"]
_MAX_MODEL_NAME_UTF8_BYTES = _NAME_LIMITS["model"]["maxUtf8Bytes"]
_MAX_FIELD_NAME_UTF8_BYTES = _NAME_LIMITS["field"]["maxUtf8Bytes"]
_MAX_TARGET_FIELDS = _NAME_LIMITS["targetFields"]["maxItems"]
_MAX_TARGET_FIELDS_UTF8_BYTES = _NAME_LIMITS["targetFields"]["maxTotalUtf8Bytes"]
_MAX_EXCLUDED_DECKS = _NAME_LIMITS["excludedDecks"]["maxItems"]
_MAX_EXCLUDED_DECKS_UTF8_BYTES = _NAME_LIMITS["excludedDecks"]["maxTotalUtf8Bytes"]

_BATCH_SIZE = _NOTE_LIMITS["maxNotes"]
_MEDIA_BATCH_SIZE = _MEDIA_LIMITS["maxAssets"]
_MAX_DUPLICATE_KEY_CHARS = _SCAN_LIMITS["duplicateKeyMaxCodePoints"]
_MAX_DUPLICATE_FIRST_FIELD_CHARS = _SCAN_LIMITS["duplicateFirstFieldMaxCodePoints"]
_MAX_DUPLICATE_HITS_PER_CANDIDATE = _SCAN_LIMITS["duplicateHitsPerCandidateMaxItems"]
_MAX_DUPLICATE_TOTAL_HITS = _SCAN_LIMITS["duplicateHitsTotalMaxItems"]
_MAX_RAW_FIRST_FIELD_UTF8_BYTES = _SCAN_LIMITS["firstFieldMaxUtf8Bytes"]
_MAX_DUPLICATE_HITS_UTF8_BYTES = _SCAN_LIMITS["duplicateHitsTotalMaxUtf8Bytes"]
_KNOWN_VOCABULARY_PAGE_ITEMS = _SCAN_LIMITS["knownPageMaxItems"]
_KNOWN_VOCABULARY_PAGE_UTF8_BYTES = _SCAN_LIMITS["knownPageMaxUtf8Bytes"]
_MAX_KNOWN_VOCABULARY_SCANNED_NOTES = _SCAN_LIMITS["knownTotalScannedNotes"]
_MAX_KNOWN_CURSOR_UTF8_BYTES = _SCAN_LIMITS["knownCursorMaxUtf8Bytes"]
_MAX_NOTE_FIELDS = _NOTE_LIMITS["maxFieldsPerNote"]
_MAX_CARDS_PER_NOTE = _NOTE_LIMITS["maxCardsPerNote"]
_MAX_FIELD_VALUE_UTF8_BYTES = _NOTE_LIMITS["fieldValueMaxUtf8Bytes"]
_MAX_NOTE_TAGS = _NOTE_LIMITS["maxTagsPerNote"]
_MAX_TAG_UTF8_BYTES = _NOTE_LIMITS["tagMaxUtf8Bytes"]
_MAX_NOTE_TAGS_UTF8_BYTES = _NOTE_LIMITS["tagsPerNoteMaxUtf8Bytes"]
_MAX_NOTE_CONTENT_UTF8_BYTES = _NOTE_LIMITS["noteContentMaxUtf8Bytes"]
_MAX_CREATE_CONTENT_UTF8_BYTES = _NOTE_LIMITS["callbackContentMaxUtf8Bytes"]
_MAX_MEDIA_BINDINGS_PER_NOTE = _NOTE_LIMITS["maxMediaBindingsPerNote"]
_MAX_MEDIA_BINDINGS_TOTAL = _NOTE_LIMITS["maxMediaBindingsTotal"]
_MAX_CREATE_ENVELOPE_UTF8_BYTES = _NOTE_LIMITS["requestEnvelopeMaxUtf8Bytes"]
# One engine create call is preflighted after the pipeline's cached target
# verification and before progress, hashing, or a create-phase provider
# callback. These ceilings keep the retained Python
# note graph in the low tens of MiB on a phone while still allowing twenty
# maximum-size provider note batches. Media is streamed, but bounding its
# complete workload prevents a single curation confirmation from scheduling
# unbounded file I/O and private-snapshot storage. These constants are the
# source for the planned generated cross-language limits manifest.
_MAX_CREATE_CALL_SOURCE_ITEMS = _CREATE_CALL_LIMITS["maxSourceItems"]
_MAX_CREATE_CALL_SOURCE_UTF8_BYTES = _CREATE_CALL_LIMITS["sourceMaxUtf8Bytes"]
_MAX_CREATE_CALL_NOTE_UTF8_BYTES = _CREATE_CALL_LIMITS["builtNotesMaxUtf8Bytes"]
_MAX_CREATE_CALL_MEDIA_REFS = _CREATE_CALL_LIMITS["maxMediaReferences"]
_MAX_CREATE_CALL_MEDIA_BYTES = _CREATE_CALL_LIMITS["mediaWorkMaxBytes"]
_MAX_MEDIA_ASSET_BYTES = _MEDIA_LIMITS["maxAssetBytes"]
_MAX_MEDIA_CALLBACK_BYTES = _MEDIA_LIMITS["maxTotalBytes"]
_MAX_MEDIA_FILENAME_UTF8_BYTES = _MEDIA_LIMITS["filenameMaxUtf8Bytes"]
_MAX_MEDIA_SOURCE_PATH_UTF8_BYTES = _MEDIA_LIMITS["sourcePathMaxUtf8Bytes"]
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
_ASSET_ID_RE = re.compile(r"^asset_[0-9a-f]{32}$")
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
    media_bindings: tuple[tuple[str, str], ...] = ()
    # Preflight can carry an exact worst-case wire representation while the
    # logical identity above remains the one produced by the unmutated card.
    # Provider filename rewrites need their longest valid representation for
    # envelope sizing. Their equality relation is modeled independently below.
    wire_key: str | None = None
    wire_first_field: str | None = None
    # Preflight may need to model equality after a successful dictionary-media
    # rewrite. This is deliberately separate from the runtime candidate above:
    # provider duplicate semantics remain (normalized key, exact first field).
    preflight_block_identity: tuple[str, tuple[tuple[str, str], ...]] | None = None


@dataclass(frozen=True)
class _CreatePreflightPlan:
    """Filesystem snapshot needed to preserve preflight rewrite assumptions."""

    dictionary_media_paths: dict[str, Path]
    dictionary_media_sources: tuple[str, ...]
    pending_media_name_reservations: dict[str, tuple[str, str]]


@dataclass
class _MediaWorkBudget:
    """Actual bytes read while preparing all media for one create call."""

    consumed_bytes: int = 0

    def consume(self, byte_count: int) -> None:
        self.consumed_bytes += byte_count
        if self.consumed_bytes > _MAX_CREATE_CALL_MEDIA_BYTES:
            _protocol_error(
                "create_call_too_large",
                "The create call exceeds its runtime media-work limit",
            )


@dataclass(frozen=True)
class _PreparedCardMedia:
    assets: tuple[_MediaAsset, ...]
    originals_by_id: dict[str, str]
    refs: dict[str, list[_CardMediaRef]]


@dataclass(frozen=True)
class _PreparedDictionaryMedia:
    assets: tuple[_MediaAsset, ...]
    sources_by_id: dict[str, str]
    confirmed_missing_sources: frozenset[str]
    unavailable_sources: frozenset[str]


@dataclass(frozen=True)
class _MediaDigest:
    size: int
    sha1_prefix: str
    sha256: str


@dataclass(frozen=True)
class _StoreAssetsOutcome:
    stored: dict[str, str]
    error: AnkiCallbackError | None = None


@dataclass(frozen=True)
class _MediaAcknowledgement:
    asset_id: str
    actual_filename: str
    purpose: str
    media_kind: str


@dataclass(frozen=True)
class _StoredCardMedia:
    filenames: frozenset[str]
    bindings_by_media_identity: dict[int, tuple[_MediaAcknowledgement, ...]]


def _protocol_error(code: str, message: str) -> NoReturn:
    raise BridgeProtocolError(code, message)


def _strict_utf8_bytes(value: str, *, context: str, code: str) -> bytes:
    """Encode one caller-owned string without leaking Python codec failures."""

    try:
        return value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise BridgeProtocolError(code, f"{context} contains an invalid Unicode scalar") from exc


def _expect_exact_keys(value: object, required: set[str], *, context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != required:
        _protocol_error("invalid_anki_response", f"{context} has missing or unknown fields")
    return value


def _expect_string(value: object, *, context: str, nonempty: bool = False) -> str:
    if not isinstance(value, str) or (nonempty and not value):
        _protocol_error("invalid_anki_response", f"{context} must be a string")
    return value


def _expect_string_list(value: object, *, context: str, unique: bool = False, nonempty: bool = False) -> list[str]:
    if not isinstance(value, list):
        _protocol_error("invalid_anki_response", f"{context} must be an array")
    result = [
        _expect_string(item, context=f"{context}[{index}]", nonempty=nonempty) for index, item in enumerate(value)
    ]
    if unique and len(set(result)) != len(result):
        _protocol_error("invalid_anki_response", f"{context} must be unique")
    return result


def _expect_bounded_utf8(
    value: object,
    *,
    context: str,
    max_bytes: int,
    code: str,
    nonempty: bool = True,
) -> str:
    if not isinstance(value, str) or (nonempty and not value):
        _protocol_error(code, f"{context} must be a string")
    encoded = _strict_utf8_bytes(value, context=context, code=code)
    if len(encoded) > max_bytes:
        _protocol_error(code, f"{context} exceeds its UTF-8 byte limit")
    return value


def _expect_media_source_path(
    value: object,
    *,
    context: str,
    code: str,
) -> str:
    """Validate the POSIX path contract before any ``Path`` operation."""

    source_path = _expect_bounded_utf8(
        value,
        context=context,
        max_bytes=_MAX_MEDIA_SOURCE_PATH_UTF8_BYTES,
        code=code,
    )
    if "\0" in source_path or not Path(source_path).is_absolute():
        _protocol_error(code, f"{context} must be an absolute NUL-free path")
    return source_path


def _expect_bounded_canonical_name(
    value: object,
    *,
    context: str,
    max_bytes: int,
    code: str,
) -> str:
    name = _expect_bounded_utf8(
        value,
        context=context,
        max_bytes=max_bytes,
        code=code,
    )
    if (
        has_leading_or_trailing_python_whitespace(name)
        or not is_nfc(name)
        or any(is_category_c(ord(character)) for character in name)
    ):
        _protocol_error(code, f"{context} is not canonical")
    return name


def _expect_bounded_string_list(
    value: object,
    *,
    context: str,
    max_items: int,
    max_item_bytes: int,
    max_total_bytes: int,
    code: str,
    unique: bool = True,
) -> list[str]:
    if not isinstance(value, list) or len(value) > max_items:
        _protocol_error(code, f"{context} exceeds its item limit")
    result: list[str] = []
    total_bytes = 0
    for index, item in enumerate(value):
        bounded = _expect_bounded_canonical_name(
            item,
            context=f"{context}[{index}]",
            max_bytes=max_item_bytes,
            code=code,
        )
        total_bytes += len(
            _strict_utf8_bytes(
                bounded,
                context=f"{context}[{index}]",
                code=code,
            )
        )
        if total_bytes > max_total_bytes:
            _protocol_error(code, f"{context} exceeds its total UTF-8 byte limit")
        result.append(bounded)
    if unique and len(set(result)) != len(result):
        _protocol_error(code, f"{context} must be unique")
    return result


def _expect_positive_int(value: object, *, context: str) -> int:
    converted = normalize_integral_json_number(value)
    if converted is None or converted <= 0:
        _protocol_error("invalid_anki_response", f"{context} must be a positive integer")
    return converted


def _expect_media_basename(value: object, *, context: str, code: str = "invalid_anki_response") -> str:
    if not isinstance(value, str) or not value:
        _protocol_error(code, f"{context} must be a non-empty string")
    filename = value
    if (
        len(_strict_utf8_bytes(filename, context=context, code=code)) > _MAX_MEDIA_FILENAME_UTF8_BYTES
        or filename in {".", ".."}
        or "/" in filename
        or "\\" in filename
        or any(is_category_c(ord(character)) for character in filename)
        or Path(filename).name != filename
    ):
        _protocol_error(code, f"{context} is not a media basename")
    return filename


def _expect_filename(value: object, *, context: str, code: str = "invalid_anki_response") -> str:
    filename = _expect_media_basename(value, context=context, code=code)
    if (
        has_leading_or_trailing_python_whitespace(filename)
        or not is_nfc(filename)
        or any(character in _FORBIDDEN_FILENAME_CHARACTERS for character in filename)
    ):
        _protocol_error(code, f"{context} is not a safe provider filename")
    return filename


def _starts_with_ascii_case_insensitive(value: str, prefix: str) -> bool:
    """Compare an ASCII protocol prefix without host Unicode case tables."""

    if len(value) < len(prefix):
        return False
    for character, expected in zip(value[: len(prefix)], prefix, strict=True):
        code_point = ord(character)
        if ord("A") <= code_point <= ord("Z"):
            code_point += ord("a") - ord("A")
        if code_point != ord(expected):
            return False
    return True


def _expect_actual_media_basename(value: object, *, context: str) -> str:
    filename = _expect_media_basename(value, context=context)
    if _starts_with_ascii_case_insensitive(filename, "[sound:") or _starts_with_ascii_case_insensitive(
        filename, "<img"
    ):
        _protocol_error("invalid_anki_response", f"{context} must be a raw media basename")
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
        _protocol_error("unexpected_media_name", f"{context} must include a provider extension")
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


def _dictionary_provider_preferred_name(
    logical_filename: str,
    *,
    context: str = "dictionary media filename",
    code: str = "invalid_note",
) -> str:
    """Return a safe deterministic prefix for an arbitrary Yomitan basename."""

    digest = hashlib.sha256(_strict_utf8_bytes(logical_filename, context=context, code=code)).hexdigest()
    return f"anki_miner_dict_{digest}"


def _provider_rename_candidate_stem(filename: str) -> str | None:
    try:
        _expect_filename(
            filename,
            context="reserved dictionary media filename",
            code="invalid_note",
        )
    except BridgeProtocolError:
        return None
    path = Path(filename)
    return path.stem if path.suffix else None


def _is_possible_provider_rename(filename: str, preferred: str) -> bool:
    """Whether ``filename`` is inside one provider prefix's accepted namespace."""

    stem = _provider_rename_candidate_stem(filename)
    return stem is not None and stem.startswith(f"{preferred}_")


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
            len(chunk) >= _MEDIA_BATCH_SIZE or chunk_bytes + asset.expected_size_bytes > _MAX_MEDIA_CALLBACK_BYTES
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
    detail = _expect_exact_keys(value, {"code", "message", "retryable"}, context=f"{operation} error")
    code = _expect_string(detail["code"], context=f"{operation} error code")
    if code not in _ALL_ERROR_CODES:
        _protocol_error("invalid_anki_response", f"Unknown Anki error code: {code}")
    message = _expect_string(detail["message"], context=f"{operation} error message", nonempty=True)
    retryable = detail["retryable"]
    if not isinstance(retryable, bool):
        _protocol_error("invalid_anki_response", f"{operation} retryable must be boolean")
    if code == "cancelled" and retryable:
        _protocol_error("invalid_anki_response", f"{operation} cancellation cannot be retryable")
    return AnkiCallbackError(operation, code, message, retryable)


def _raise_callback_error(error: AnkiCallbackError) -> NoReturn:
    if error.code not in _ALL_ERROR_CODES:
        raise BridgeProtocolError("invalid_anki_response", f"Unknown Anki error code: {error.code}") from error
    if error.code in _PROTOCOL_ERROR_CODES:
        raise BridgeProtocolError(error.code, error.message) from error
    if error.code == "cancelled":
        raise AnkiOperationCancelled(error.operation, error.message, error.retryable) from error

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
        # Note-write provenance (engine D30). EpisodeProcessor reads this off
        # the service after every run and fails closed to UNCERTAIN when it is
        # not a real AnkiWriteState, so an adapter that never set it would
        # report every clean run as unretryable. Function-local import: the
        # bridge must not touch anki_miner at module import time.
        from anki_miner.models import AnkiWriteState

        self.anki_write_state: Any = AnkiWriteState.NO_NOTE_WRITE
        self._verified_field_names: tuple[str, ...] | None = None
        self._outstanding_baseline_token: str | None = None
        self._existing_vocab_cache: set[str] | None = None
        self._dict_media_uploaded: set[str] = set()
        self._dict_media_missing_sources: set[str] = set()
        self._dict_media_actual_names: dict[str, str] = {}
        self._dict_media_bindings: dict[str, _MediaAcknowledgement] = {}
        self._stored_media_name_owners: dict[str, tuple[str, str]] = {}
        self._reserved_media_name_owners: dict[str, tuple[str, str]] = {}
        self._closed = False

    def __enter__(self) -> AndroidAnkiAdapter:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def close(self) -> None:
        """Best-effort, idempotent release of all state scoped to this run.

        The composition layer registers the adapter with its ``ExitStack``.
        Cleanup deliberately cannot replace a successful mining result or the
        primary mining exception. Kotlin's coordinator also releases its run
        registry directly in ``finally`` in case this callback cannot arrive.
        """

        if self._closed:
            return
        self._closed = True
        try:
            payload = self._callbacks.release_run_state()
            result = _expect_exact_keys(
                payload,
                {"runId", "requestId", "state"},
                context="releaseRunState result",
            )
            if not isinstance(result["state"], str) or result["state"] not in {
                "released",
                "deferred",
                "absent",
            }:
                _protocol_error(
                    "invalid_anki_response",
                    "releaseRunState state is invalid",
                )
        except BaseException:
            # This is an unusually intentional BaseException boundary: even a
            # malformed cancellation response during teardown must not mask
            # the already-determined mining result. The coordinator's direct
            # registry release is the authoritative fallback.
            logger.exception("Failed to release Kotlin Anki run state")
        finally:
            self._verified_field_names = None
            self._outstanding_baseline_token = None
            self._existing_vocab_cache = None
            self._dict_media_uploaded.clear()
            self._dict_media_missing_sources.clear()
            self._dict_media_actual_names.clear()
            self._dict_media_bindings.clear()
            self._stored_media_name_owners.clear()
            self._reserved_media_name_owners.clear()

    def _raise_if_cancelled(self, operation: str) -> None:
        if self._cancellation_check():
            raise AnkiOperationCancelled(
                operation,
                f"Anki {operation} preparation was cancelled",
                False,
            )

    def _accept_terminal_payload(self, payload: Mapping[str, Any]) -> None:
        request_id = _expect_string(
            payload.get("requestId"),
            context="Anki terminal response requestId",
            nonempty=True,
        )
        self._callbacks.accept_terminal_response(request_id)

    def _accept_callback_error(self, error: AnkiCallbackError) -> None:
        if error.code not in _ALL_ERROR_CODES:
            if error.request_id is not None:
                self._callbacks.reject_terminal_response(error.request_id)
            _raise_callback_error(error)
        if error.request_id is not None:
            self._callbacks.accept_terminal_response(error.request_id)

    def _reject_terminal_payload(self, payload: Mapping[str, Any]) -> None:
        request_id = payload.get("requestId")
        if isinstance(request_id, str):
            self._callbacks.reject_terminal_response(request_id)

    def _reject_callback_error(self, error: AnkiCallbackError) -> None:
        if error.request_id is not None:
            self._callbacks.reject_terminal_response(error.request_id)

    def _stream_media_digest(self, source_path: Path, work_budget: _MediaWorkBudget) -> _MediaDigest:
        """Hash one bounded regular media file without retaining its contents."""

        self._raise_if_cancelled("storeMedia")
        _expect_media_source_path(
            str(source_path),
            context="storeMedia sourcePath",
            code="invalid_anki_request",
        )
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
                work_budget.consume(len(chunk))
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

        self._raise_if_cancelled("verifyTarget")
        if self._verified_field_names is not None:
            return

        required = {value for value in self.config.anki_fields.values() if value}
        if self.config.card_type:
            marker = self.config.card_type_marker_fields.get(self.config.card_type, "")
            if marker:
                required.add(marker)
        deck_name = _expect_bounded_canonical_name(
            self.config.anki_deck_name,
            context="verifyTarget deckName",
            max_bytes=_MAX_DECK_NAME_UTF8_BYTES,
            code="invalid_anki_request",
        )
        model_name = _expect_bounded_canonical_name(
            self.config.anki_note_type,
            context="verifyTarget modelName",
            max_bytes=_MAX_MODEL_NAME_UTF8_BYTES,
            code="invalid_anki_request",
        )
        required_fields = _expect_bounded_string_list(
            sorted(required),
            context="verifyTarget requiredFields",
            max_items=_MAX_TARGET_FIELDS,
            max_item_bytes=_MAX_FIELD_NAME_UTF8_BYTES,
            max_total_bytes=_MAX_TARGET_FIELDS_UTF8_BYTES,
            code="invalid_anki_request",
        )
        try:
            payload = self._callbacks.verify_target(
                {
                    "deckName": deck_name,
                    "modelName": model_name,
                    "requiredFields": required_fields,
                }
            )
        except AnkiCallbackError as error:
            self._accept_callback_error(error)
            _raise_callback_error(error)

        try:
            result = _expect_exact_keys(
                payload,
                {
                    "runId",
                    "requestId",
                    "deckId",
                    "modelId",
                    "fieldNames",
                    "deckCreated",
                },
                context="verifyTarget result",
            )
            _expect_positive_int(result["deckId"], context="deckId")
            _expect_positive_int(result["modelId"], context="modelId")
            fields = _expect_bounded_string_list(
                result["fieldNames"],
                context="fieldNames",
                max_items=_MAX_TARGET_FIELDS,
                max_item_bytes=_MAX_FIELD_NAME_UTF8_BYTES,
                max_total_bytes=_MAX_TARGET_FIELDS_UTF8_BYTES,
                code="invalid_anki_response",
                unique=True,
            )
            if not fields:
                _protocol_error("invalid_anki_response", "fieldNames must not be empty")
            if not isinstance(result["deckCreated"], bool):
                _protocol_error("invalid_anki_response", "deckCreated must be boolean")
            if result["deckCreated"]:
                _protocol_error(
                    "invalid_anki_response",
                    "ContentProvider verifyTarget must report deckCreated=false",
                )

            validate_anki_request_config(
                self.config,
                verified_field_names=fields,
            )
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
        except Exception:
            self._reject_terminal_payload(payload)
            raise
        self._accept_terminal_payload(payload)
        self._verified_field_names = tuple(fields)

    def _scan_known_vocabulary_page(
        self, cursor: dict[str, Any] | None
    ) -> tuple[list[str], int, dict[str, Any] | None]:
        self._raise_if_cancelled("scanFirstFields")
        excluded_decks = _expect_bounded_string_list(
            list(self.config.excluded_decks),
            context="scanFirstFields excludedDecks",
            max_items=_MAX_EXCLUDED_DECKS,
            max_item_bytes=_MAX_DECK_NAME_UTF8_BYTES,
            max_total_bytes=_MAX_EXCLUDED_DECKS_UTF8_BYTES,
            code="invalid_anki_request",
        )
        limits = {
            "maxScannedNotes": _KNOWN_VOCABULARY_PAGE_ITEMS,
            "maxTotalScannedNotes": _MAX_KNOWN_VOCABULARY_SCANNED_NOTES,
            "maxItems": _KNOWN_VOCABULARY_PAGE_ITEMS,
            "maxItemUtf8Bytes": _MAX_RAW_FIRST_FIELD_UTF8_BYTES,
            "maxTotalUtf8Bytes": _KNOWN_VOCABULARY_PAGE_UTF8_BYTES,
        }
        try:
            deck_scope = {"deckName": self.config.anki_deck_name} if self.config.allow_duplicate_cards else {}
            payload = self._callbacks.scan_first_fields(
                {
                    "scope": {
                        "kind": "knownVocabulary",
                        **deck_scope,
                        "excludedDecks": excluded_decks,
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
        raw_fields = _expect_string_list(result["firstFields"], context="firstFields")
        if len(raw_fields) > _KNOWN_VOCABULARY_PAGE_ITEMS:
            _protocol_error(
                "invalid_anki_response",
                "Known-vocabulary page contains too many fields",
            )
        total_utf8_bytes = 0
        for index, raw_field in enumerate(raw_fields):
            raw_bytes = len(
                _strict_utf8_bytes(
                    raw_field,
                    context=f"Known-vocabulary first field {index}",
                    code="invalid_anki_response",
                )
            )
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
        if scanned_notes is None or scanned_notes < len(raw_fields) or scanned_notes > _KNOWN_VOCABULARY_PAGE_ITEMS:
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
            if (
                len(
                    _strict_utf8_bytes(
                        token,
                        context="Known-vocabulary cursor token",
                        code="invalid_anki_response",
                    )
                )
                > _MAX_KNOWN_CURSOR_UTF8_BYTES
            ):
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

        self._raise_if_cancelled("scanFirstFields")
        if self._existing_vocab_cache is not None:
            return self._existing_vocab_cache

        from anki_miner.services.anki_note_builder import _strip_for_dedup

        existing: set[str] = set()
        cursor: dict[str, Any] | None = None
        seen_cursor_tokens: set[str] = set()
        total_scanned_notes = 0
        try:
            while True:
                raw_fields, scanned_notes, next_cursor = self._scan_known_vocabulary_page(cursor)
                total_scanned_notes += scanned_notes
                if total_scanned_notes > _MAX_KNOWN_VOCABULARY_SCANNED_NOTES:
                    _protocol_error(
                        "invalid_anki_response",
                        "Known-vocabulary scan exceeds its total note ceiling",
                    )
                if total_scanned_notes == _MAX_KNOWN_VOCABULARY_SCANNED_NOTES and next_cursor is not None:
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
        except Exception:
            self._callbacks.mark_response_failure()
            raise

        self._existing_vocab_cache = existing
        return existing

    def invalidate_existing_vocabulary_cache(self) -> None:
        self._existing_vocab_cache = None

    def _parse_store_media_result(self, payload: dict[str, Any], assets: Sequence[_MediaAsset]) -> _StoreAssetsOutcome:
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
        pending_name_owners: dict[str, tuple[str, str]] = {}
        saw_not_attempted = False
        saw_uncertain = False
        for index, (row_value, asset) in enumerate(zip(rows, assets, strict=True)):
            if not isinstance(row_value, dict):
                _protocol_error("invalid_anki_response", f"storeMedia result {index} is invalid")
            status = row_value.get("status")
            if saw_not_attempted and status != "notAttempted":
                _protocol_error(
                    "invalid_anki_response",
                    "notAttempted storeMedia rows must form a strict suffix",
                )
            if saw_uncertain and status != "notAttempted":
                _protocol_error(
                    "invalid_anki_response",
                    "An uncertain storeMedia row must end provider attempts",
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
                owner = (asset.purpose, asset.original_name)
                reserved_owner = self._reserved_media_name_owners.get(actual)
                if reserved_owner is not None and reserved_owner != owner:
                    _protocol_error(
                        "media_name_collision",
                        "storeMedia filename collides with reserved dictionary media",
                    )
                prior_owner = self._stored_media_name_owners.get(actual)
                if prior_owner is not None and prior_owner != owner:
                    _protocol_error(
                        "media_name_collision",
                        "storeMedia filename was already assigned to different media",
                    )
                actual_names.add(actual)
                pending_name_owners[actual] = owner
                stored[asset.asset_id] = actual
            elif status == "failed":
                row = _expect_exact_keys(
                    row_value,
                    {"assetId", "status", "error"},
                    context=f"storeMedia result {index}",
                )
                row_error = _expect_error_detail(row["error"], operation="storeMedia")
                if row_error.code not in _RECOVERABLE_MEDIA_ERROR_CODES:
                    _raise_callback_error(row_error)
                # The asset id is what ties this line to an identity named in the error itself
                # (a namespace refusal reports the colliding pair by id, never by filename).
                logger.warning(
                    "Failed to store media asset %s [%s]: %s",
                    asset.original_name,
                    asset.asset_id,
                    row_error,
                )
            elif status == "uncertain":
                row = _expect_exact_keys(
                    row_value,
                    {"assetId", "status"},
                    context=f"storeMedia result {index}",
                )
                saw_uncertain = True
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
        error = None if raw_error is None else _expect_error_detail(raw_error, operation="storeMedia")
        if (saw_uncertain or saw_not_attempted) and error is None:
            _protocol_error(
                "invalid_anki_response",
                "uncertain/notAttempted storeMedia rows require a top-level error",
            )
        if error is not None and not (saw_uncertain or saw_not_attempted):
            _protocol_error(
                "invalid_anki_response",
                "storeMedia top-level errors require an uncertain row or notAttempted suffix",
            )
        if error is not None:
            if saw_uncertain and (error.code != "post_commit_uncertain" or error.retryable):
                _protocol_error(
                    "invalid_anki_response",
                    "Uncertain media requires a non-retryable post-commit error",
                )
            if error.code == "post_commit_uncertain" and not saw_uncertain:
                _protocol_error(
                    "invalid_anki_response",
                    "A post-commit media error requires an uncertain row",
                )
            if stored and error.retryable:
                _protocol_error(
                    "invalid_anki_response",
                    "A partial media write cannot be declared safely retryable",
                )
        self._stored_media_name_owners.update(pending_name_owners)
        return _StoreAssetsOutcome(stored, error)

    def _validate_asset_provider_namespaces(
        self,
        assets: Sequence[_MediaAsset],
        *,
        additional_reservations: Mapping[str, tuple[str, str]] | None = None,
    ) -> None:
        """Prove all accepted provider results are owner-disjoint pre-callback."""

        claim_owners: dict[str, set[tuple[str, str]]] = {}

        def add_claim(name: str, owner: tuple[str, str]) -> None:
            claim_owners.setdefault(name, set()).add(owner)

        for name, owner in self._reserved_media_name_owners.items():
            add_claim(name, owner)
        for name, owner in (additional_reservations or {}).items():
            add_claim(name, owner)
        for name, owner in self._stored_media_name_owners.items():
            add_claim(name, owner)
        asset_owners: list[tuple[str, str]] = []
        for asset in assets:
            owner = (asset.purpose, asset.original_name)
            asset_owners.append(owner)
            add_claim(asset.requested_name, owner)
            add_claim(asset.original_name, owner)

        namespace_events: list[
            tuple[
                str,
                int,
                tuple[str, str] | set[tuple[str, str]],
            ]
        ] = [(f"{asset.preferred_name}_", 0, owner) for asset, owner in zip(assets, asset_owners, strict=True)]
        for claimed_name, owners in claim_owners.items():
            stem = _provider_rename_candidate_stem(claimed_name)
            if stem is not None:
                namespace_events.append((stem, 1, owners))

        # A lexical sweep models a compact prefix trie without allocating one
        # dictionary node per filename character. Namespace events sort before
        # equal concrete claims and remain on the stack for every descendant.
        active_prefixes: list[tuple[str, tuple[str, str]]] = []
        for value, kind, event_owners in sorted(
            namespace_events,
            key=lambda event: (event[0], event[1]),
        ):
            while active_prefixes and not value.startswith(active_prefixes[-1][0]):
                active_prefixes.pop()
            if kind == 0:
                owner = event_owners
                if not isinstance(owner, tuple):
                    _protocol_error(
                        "invalid_anki_request",
                        "Media provider namespace owner is invalid",
                    )
                if any(active_owner != owner for _, active_owner in active_prefixes):
                    _protocol_error(
                        "media_name_collision",
                        "Media provider rename namespaces overlap",
                    )
                active_prefixes.append((value, owner))
            else:
                owners = event_owners
                if not isinstance(owners, set):
                    _protocol_error(
                        "invalid_anki_request",
                        "Media provider claim owners are invalid",
                    )
                if any(
                    claimed_owner != active_owner for _, active_owner in active_prefixes for claimed_owner in owners
                ):
                    _protocol_error(
                        "media_name_collision",
                        "Media provider namespace contains differently-owned media",
                    )

        for asset, owner in zip(assets, asset_owners, strict=True):
            if any(claimed_owner != owner for claimed_owner in claim_owners.get(asset.requested_name, ())):
                _protocol_error(
                    "media_name_collision",
                    "Media direct-name fallback collides with differently-owned media",
                )

    def _store_assets(self, assets: list[_MediaAsset]) -> _StoreAssetsOutcome:
        if not assets:
            return _StoreAssetsOutcome({})
        asset_ids: set[str] = set()
        requested_name_owners: dict[str, str] = {}
        for index, asset in enumerate(assets):
            if asset.asset_id in asset_ids:
                _protocol_error("invalid_anki_request", "Media asset IDs must be unique")
            asset_ids.add(asset.asset_id)
            if asset.purpose not in {"card", "dictionary"} or asset.media_kind not in {
                "audio",
                "image",
            }:
                _protocol_error("invalid_anki_request", "Media asset metadata is invalid")
            _expect_filename(
                asset.preferred_name,
                context=f"storeMedia asset {index} preferredName",
                code="invalid_anki_request",
            )
            name_validator = _expect_media_basename if asset.purpose == "dictionary" else _expect_filename
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
                _dictionary_provider_preferred_name(
                    asset.original_name,
                    context=f"storeMedia asset {index} originalName",
                    code="invalid_anki_request",
                )
                if asset.purpose == "dictionary"
                else _provider_preferred_name(asset.requested_name)
            )
            if asset.preferred_name != expected_preferred:
                _protocol_error(
                    "invalid_anki_request",
                    "Media preferredName is inconsistent with the requested media",
                )
            for claimed_name in {asset.requested_name, asset.original_name}:
                prior_owner = requested_name_owners.setdefault(claimed_name, asset.asset_id)
                if prior_owner != asset.asset_id:
                    _protocol_error(
                        "media_name_collision",
                        "Requested media names collide before provider insertion",
                    )
            _expect_media_source_path(
                asset.source_path,
                context=f"storeMedia asset {index} sourcePath",
                code="invalid_anki_request",
            )
            if (
                type(asset.expected_size_bytes) is not int
                or not 0 <= asset.expected_size_bytes <= _MAX_MEDIA_ASSET_BYTES
                or not _SHA256_RE.fullmatch(asset.expected_sha256)
            ):
                _protocol_error("invalid_anki_request", "Media integrity metadata is invalid")

        self._validate_asset_provider_namespaces(assets)

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
                    AnkiCallbackError("storeMedia", "cancelled", str(error), error.retryable),
                )
            except AnkiCallbackError as error:
                if error.code == "post_commit_uncertain":
                    self._reject_callback_error(error)
                    _protocol_error(
                        "invalid_anki_response",
                        "Post-commit uncertainty requires an aligned storeMedia result",
                    )
                self._accept_callback_error(error)
                return _StoreAssetsOutcome(stored, error)
            try:
                chunk_outcome = self._parse_store_media_result(payload, chunk)
            except Exception:
                self._reject_terminal_payload(payload)
                raise
            self._accept_terminal_payload(payload)
            if stored.keys() & chunk_outcome.stored.keys():
                _protocol_error(
                    "mismatched_callback_response",
                    "storeMedia returned an asset more than once",
                )
            stored.update(chunk_outcome.stored)
            if chunk_outcome.error is not None:
                return _StoreAssetsOutcome(stored, chunk_outcome.error)
        return _StoreAssetsOutcome(stored)

    def _prepare_card_media(
        self,
        word_data_list: Sequence[Any],
        work_budget: _MediaWorkBudget,
    ) -> _PreparedCardMedia:
        """Hash and validate every card asset without provider side effects."""

        from anki_miner.services.anki_media_store import (
            _MEDIA_FIELD_ATTRS,
        )

        paths_by_filename: dict[str, list[Path]] = {}
        refs: dict[str, list[_CardMediaRef]] = {}
        kinds: dict[str, set[str]] = {}

        for item in word_data_list:
            media = item.media
            # The engine's tuple gained a leading Anki field key and it now
            # skips media whose field is unmapped. Both sites here mirror that
            # gate, or Android would upload assets desktop never stores.
            for field_key, filename_attr, path_attr in _MEDIA_FIELD_ATTRS:
                if not self.config.anki_fields.get(field_key):
                    continue
                filename = getattr(media, filename_attr)
                source_path = getattr(media, path_attr)
                if not filename or not source_path:
                    continue
                _expect_media_source_path(
                    str(source_path),
                    context="create-call card media source path",
                    code="invalid_note",
                )
                path = Path(source_path)
                try:
                    resolved_path = path.resolve()
                except OSError:
                    resolved_path = path.absolute()
                refs.setdefault(filename, []).append(_CardMediaRef(media, filename_attr, resolved_path))
                kinds.setdefault(filename, set()).add("image" if filename_attr == "screenshot_filename" else "audio")
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
                    digest = self._stream_media_digest(source_path, work_budget)
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
                    logger.warning("Failed to read media file %s: %s", filename, unreadable[0][1])
                continue
            source_path, digest = readable[0]
            if any((candidate.size, candidate.sha256) != (digest.size, digest.sha256) for _, candidate in readable[1:]):
                _protocol_error(
                    "media_content_collision",
                    f"Media filename {filename!r} refers to different file contents",
                )

            requested_name = _content_addressed_name_from_digest(filename, digest.sha1_prefix)
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

        return _PreparedCardMedia(tuple(assets), originals_by_id, refs)

    def _store_prepared_card_media(self, prepared: _PreparedCardMedia) -> _StoredCardMedia:
        """Store a fully prepared card-media plan and apply acknowledgements."""

        outcome = self._store_assets(list(prepared.assets))
        stored_names: set[str] = set()
        renamed_originals: set[str] = set()
        bindings_by_media_identity: dict[int, list[_MediaAcknowledgement]] = {}
        assets_by_id = {asset.asset_id: asset for asset in prepared.assets}
        for asset_id, actual in outcome.stored.items():
            original = prepared.originals_by_id[asset_id]
            asset = assets_by_id[asset_id]
            acknowledgement = _MediaAcknowledgement(
                asset_id=asset_id,
                actual_filename=actual,
                purpose="card",
                media_kind=asset.media_kind,
            )
            renamed_originals.add(original)
            stored_names.add(actual)
            for ref in prepared.refs.get(original, []):
                setattr(ref.media, ref.filename_attr, actual)
                bindings = bindings_by_media_identity.setdefault(id(ref.media), [])
                if all(binding.asset_id != asset_id for binding in bindings):
                    bindings.append(acknowledgement)

        self.last_media_store_failures = len(prepared.refs) - len(renamed_originals)
        if outcome.error is not None:
            _raise_callback_error(outcome.error)
        return _StoredCardMedia(
            filenames=frozenset(stored_names),
            bindings_by_media_identity={
                identity: tuple(bindings) for identity, bindings in bindings_by_media_identity.items()
            },
        )

    def _is_allowed_dictionary_image_source(self, source: str) -> bool:
        """Admit only private dictionary media or acknowledged Anki names."""

        try:
            _expect_media_basename(
                source,
                context="dictionary HTML image source",
                code="invalid_note",
            )
        except BridgeProtocolError:
            return False

        # A renderer marker is provenance for an imported dictionary basename.
        # Keep missing basenames in the existing reservation/collision proof:
        # the file may legitimately disappear or appear between preflight and
        # hashing, and those transitions are already fail-closed. A basename
        # cannot trigger network I/O, but reject scheme-like spellings anyway.
        colon = source.find(":")
        slash_positions = [position for position in (source.find("/"), source.find("\\")) if position >= 0]
        first_slash = min(slash_positions, default=-1)
        if source.startswith("//") or (colon >= 0 and (first_slash < 0 or colon < first_slash)):
            return False

        if source in self._dict_media_actual_names.values():
            return True
        if source in self._dict_media_bindings:
            return True

        # Preserve unresolved renderer basenames for the media preflight and
        # collision proof. Once absence is confirmed, later cards omit the
        # source instead of materializing another broken reference.
        return source not in self._dict_media_missing_sources

    def _sanitize_dictionary_payloads(
        self,
        word_data_list: Sequence[Any],
        rejected_sources: Set[str] = frozenset(),
    ) -> list[Any]:
        """Remove remotely loading glossary media before note preflight."""

        from dataclasses import replace

        from .dictionary_html import sanitize_dictionary_html

        def source_allowed(source: str) -> bool:
            return source not in rejected_sources and self._is_allowed_dictionary_image_source(source)

        sanitized_payloads: list[Any] = []
        for item in word_data_list:
            definition = item.definition
            sanitized_definition = (
                sanitize_dictionary_html(
                    definition,
                    local_source_allowed=source_allowed,
                )
                if self.config.anki_fields.get("definition") and isinstance(definition, str)
                else definition
            )

            extra_fields = item.extra_fields
            sanitized_extra = extra_fields
            if (
                self.config.anki_fields.get("glossary")
                and extra_fields
                and isinstance(extra_fields.get("glossary"), str)
            ):
                glossary = extra_fields["glossary"]
                sanitized_glossary = sanitize_dictionary_html(
                    glossary,
                    local_source_allowed=source_allowed,
                )
                if sanitized_glossary != glossary:
                    sanitized_extra = {
                        **extra_fields,
                        "glossary": sanitized_glossary,
                    }

            if sanitized_definition != definition or sanitized_extra is not extra_fields:
                item = replace(
                    item,
                    definition=sanitized_definition,
                    extra_fields=sanitized_extra,
                )
            sanitized_payloads.append(item)
        return sanitized_payloads

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

    @staticmethod
    def _canonical_dictionary_first_field_identity(
        value: str, canonical_sources: set[str]
    ) -> tuple[tuple[str, str], ...] | None:
        """Model exact first-field equality after source-specific rewrites.

        Literal HTML remains byte-exact. Only the encoded contents of a marked
        ``src`` which can be rewritten become a structural source token. Thus
        ``dict__a.png`` and ``dict__&#97;.png`` group together for the same
        upload, while distinct logical sources remain distinct regardless of
        the synthetic longest filename used for callback byte sizing.
        """

        if not canonical_sources:
            return None
        from anki_miner.services.anki_media_store import (
            _DICT_MEDIA_IMG_RE,
            _IMG_SRC_RE,
        )

        pieces: list[tuple[str, str]] = []
        cursor = 0
        replaced = False
        for tag_match in _DICT_MEDIA_IMG_RE.finditer(value):
            tag = tag_match.group(0)
            src_match = _IMG_SRC_RE.search(tag)
            if src_match is None:
                continue
            source = html_unescape(src_match.group(1))
            if source not in canonical_sources:
                continue
            source_start = tag_match.start() + src_match.start(1)
            source_end = tag_match.start() + src_match.end(1)
            pieces.append(("literal", value[cursor:source_start]))
            pieces.append(("dictionarySource", source))
            cursor = source_end
            replaced = True
        if not replaced:
            return None
        pieces.append(("literal", value[cursor:]))
        return tuple(pieces)

    def _pending_dictionary_media_reservations(
        self,
        sources: Sequence[str],
        upload_sources: set[str],
    ) -> dict[str, tuple[str, str]]:
        """Validate and stage dictionary basenames without mutating run state."""

        pending: dict[str, tuple[str, str]] = {}
        for source in sources:
            owner = ("dictionary", source)
            reserved_owner = self._reserved_media_name_owners.get(source)
            if reserved_owner is not None and reserved_owner != owner:
                _protocol_error(
                    "media_name_collision",
                    "Dictionary media filename is reserved by different media",
                )
            stored_owner = self._stored_media_name_owners.get(source)
            if stored_owner is not None and stored_owner != owner:
                _protocol_error(
                    "media_name_collision",
                    "Dictionary media filename aliases provider-stored media",
                )
            pending[source] = owner

        reservations = {**self._reserved_media_name_owners, **pending}
        preferred_owners: dict[str, tuple[str, str]] = {}
        for source in upload_sources:
            preferred = _dictionary_provider_preferred_name(source)
            owner = ("dictionary", source)
            prior_owner = preferred_owners.setdefault(preferred, owner)
            if prior_owner != owner:
                _protocol_error(
                    "media_name_collision",
                    "Dictionary media provider prefixes collide",
                )

        # Every dictionary provider prefix has the same fixed length. Indexing
        # by that prefix avoids an O(sources^2) scan at the 8,000-reference cap.
        preferred_length = len(_dictionary_provider_preferred_name(""))
        for reserved_name, reserved_owner in reservations.items():
            stem = Path(reserved_name).stem
            preferred = stem[:preferred_length]
            upload_owner = preferred_owners.get(preferred)
            if (
                upload_owner is not None
                and upload_owner != reserved_owner
                and _is_possible_provider_rename(reserved_name, preferred)
            ):
                _protocol_error(
                    "media_name_collision",
                    "Dictionary media filename overlaps a provider rename namespace",
                )
        return pending

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
                self._rewrite_dictionary_html(definition, rewrite_names) if isinstance(definition, str) else definition
            )
            extra_fields = item.extra_fields
            rewritten_extra = extra_fields
            if extra_fields and isinstance(extra_fields.get("glossary"), str):
                glossary = extra_fields["glossary"]
                rewritten_glossary = self._rewrite_dictionary_html(glossary, rewrite_names)
                if rewritten_glossary != glossary:
                    rewritten_extra = {**extra_fields, "glossary": rewritten_glossary}
            if rewritten_definition != definition or rewritten_extra is not extra_fields:
                item = replace(
                    item,
                    definition=rewritten_definition,
                    extra_fields=rewritten_extra,
                )
            rewritten_payloads.append(item)
        return rewritten_payloads

    def _prepare_dictionary_media(
        self,
        preflight_plan: _CreatePreflightPlan,
        work_budget: _MediaWorkBudget,
    ) -> _PreparedDictionaryMedia:
        """Hash the complete dictionary-media plan before provider mutation."""

        from anki_miner.services.anki_media_store import (
            _resolve_dict_media_path,
        )

        assets: list[_MediaAsset] = []
        sources_by_id: dict[str, str] = {}
        confirmed_missing_sources: set[str] = set()
        unavailable_sources: set[str] = set()
        for source in preflight_plan.dictionary_media_sources:
            if source in self._dict_media_uploaded:
                continue
            planned_path = preflight_plan.dictionary_media_paths.get(source)
            runtime_path = _resolve_dict_media_path(source, self.config.dicts_root)
            if runtime_path is None:
                if planned_path is None:
                    confirmed_missing_sources.add(source)
                else:
                    logger.warning("Dict media file disappeared from disk: %s", source)
                unavailable_sources.add(source)
                continue
            try:
                resolved_path = runtime_path.resolve()
                digest = self._stream_media_digest(resolved_path, work_budget)
            except OSError as error:
                logger.warning("Failed to read dict media file %s: %s", source, error)
                unavailable_sources.add(source)
                continue
            if planned_path is None or resolved_path != planned_path:
                # The bytes were deliberately charged to the runtime work
                # budget first. Rewriting a source which was absent (or pointed
                # elsewhere) during structural preflight would invalidate the
                # proven first-field grouping, so fail the whole call instead.
                _protocol_error(
                    "media_changed",
                    "Dictionary media changed after create preflight",
                )
            asset_id = f"asset_{uuid4().hex}"
            sources_by_id[asset_id] = source
            assets.append(
                _MediaAsset(
                    asset_id=asset_id,
                    source_path=str(resolved_path),
                    preferred_name=_dictionary_provider_preferred_name(source),
                    requested_name=source,
                    original_name=source,
                    purpose="dictionary",
                    media_kind="image",
                    expected_size_bytes=digest.size,
                    expected_sha256=digest.sha256,
                )
            )

        return _PreparedDictionaryMedia(
            tuple(assets),
            sources_by_id,
            frozenset(confirmed_missing_sources),
            frozenset(unavailable_sources),
        )

    def _store_prepared_dictionary_media(
        self,
        word_data_list: Sequence[Any],
        prepared: _PreparedDictionaryMedia,
    ) -> list[Any]:
        """Store prepared dictionary assets and rewrite acknowledged HTML."""

        for source in prepared.confirmed_missing_sources:
            if source not in self._dict_media_uploaded:
                logger.warning("Dict media file missing on disk: %s", source)
                self._dict_media_uploaded.add(source)
                self._dict_media_missing_sources.add(source)

        outcome = self._store_assets(list(prepared.assets))
        assets_by_id = {asset.asset_id: asset for asset in prepared.assets}
        for asset_id, actual in outcome.stored.items():
            source = prepared.sources_by_id[asset_id]
            asset = assets_by_id[asset_id]
            self._dict_media_uploaded.add(source)
            self._dict_media_uploaded.add(actual)
            self._dict_media_actual_names[source] = actual
            self._dict_media_bindings[source] = _MediaAcknowledgement(
                asset_id=asset_id,
                actual_filename=actual,
                purpose="dictionary",
                media_kind=asset.media_kind,
            )
        failed_sources = set(prepared.unavailable_sources)
        failed_sources.update(
            source for asset_id, source in prepared.sources_by_id.items() if asset_id not in outcome.stored
        )
        if failed_sources:
            self.last_media_store_failures += len(failed_sources)
            word_data_list = self._sanitize_dictionary_payloads(
                word_data_list,
                failed_sources,
            )
        rewritten = self._rewrite_dictionary_payloads(word_data_list)
        if outcome.error is not None:
            _raise_callback_error(outcome.error)
        return rewritten

    def _relevant_source_utf8_bytes(self, payload: Any) -> int:
        """Count strings retained or rendered by one card payload.

        Sentence-candidate alternatives are deliberately excluded: phase 5 has
        already selected one sentence, and the note builder never traverses the
        alternatives. Materialized definition/glossary HTML, every direct word
        string, media names, source paths, and mapped optional fields are
        included.
        """

        values: list[object] = []
        if self.config.anki_fields.get("definition"):
            values.append(payload.definition)
        values.extend(vars(payload.word).values())
        values.extend(vars(payload.media).values())
        if payload.extra_fields:
            for key, value in payload.extra_fields.items():
                if key == "glossary" and not self.config.anki_fields.get("glossary"):
                    continue
                values.extend((key, value))
        total = 0
        for value in values:
            if isinstance(value, str):
                total += len(
                    _strict_utf8_bytes(
                        value,
                        context="create-call source value",
                        code="invalid_note",
                    )
                )
            elif isinstance(value, Path):
                total += len(
                    _strict_utf8_bytes(
                        str(value),
                        context="create-call source path",
                        code="invalid_note",
                    )
                )
        return total

    @staticmethod
    def _validated_note_content(
        note: Mapping[str, Any],
        media_bindings: Sequence[tuple[str, str]] = (),
    ) -> tuple[dict[str, str], list[str], int]:
        """Validate per-note wire limits without requiring target field order."""

        fields = note.get("fields")
        tags = note.get("tags")
        if not isinstance(fields, dict) or not fields:
            _protocol_error("invalid_note", "A note must contain at least one field")
        if len(fields) > _MAX_NOTE_FIELDS:
            _protocol_error("note_too_large", "A note contains too many fields")

        content_utf8_bytes = 0
        for field_name, value in fields.items():
            if not isinstance(field_name, str) or not field_name:
                _protocol_error("invalid_note", "Anki field names must be non-empty strings")
            if not isinstance(value, str):
                _protocol_error("invalid_note", "Anki field values must be strings")
            name_bytes = len(
                _strict_utf8_bytes(
                    field_name,
                    context="Anki field name",
                    code="invalid_note",
                )
            )
            value_bytes = len(
                _strict_utf8_bytes(
                    value,
                    context=f"Anki field {field_name!r} value",
                    code="invalid_note",
                )
            )
            if name_bytes > _MAX_FIELD_NAME_UTF8_BYTES:
                _protocol_error("note_too_large", "An Anki field name is too large")
            if value_bytes > _MAX_FIELD_VALUE_UTF8_BYTES:
                _protocol_error("note_too_large", "An Anki field value is too large")
            content_utf8_bytes += name_bytes + value_bytes

        if not isinstance(tags, list) or not all(isinstance(tag, str) and tag for tag in tags):
            _protocol_error("invalid_note", "Anki tags must be non-empty strings")
        if len(tags) > _MAX_NOTE_TAGS:
            _protocol_error("note_too_large", "A note contains too many Anki tags")
        tags_utf8_bytes = 0
        for tag in tags:
            tag_bytes = len(
                _strict_utf8_bytes(
                    tag,
                    context="Anki tag",
                    code="invalid_note",
                )
            )
            if tag_bytes > _MAX_TAG_UTF8_BYTES:
                _protocol_error("note_too_large", "An Anki tag is too large")
            tags_utf8_bytes += tag_bytes
        if tags_utf8_bytes > _MAX_NOTE_TAGS_UTF8_BYTES:
            _protocol_error("note_too_large", "Anki tags exceed the note byte limit")
        content_utf8_bytes += tags_utf8_bytes
        if len(media_bindings) > _MAX_MEDIA_BINDINGS_PER_NOTE:
            _protocol_error("note_too_large", "A note contains too many media bindings")
        seen_asset_ids: set[str] = set()
        for asset_id, actual_filename in media_bindings:
            if _ASSET_ID_RE.fullmatch(asset_id) is None or asset_id in seen_asset_ids:
                _protocol_error("invalid_note", "Anki media binding asset IDs must be unique")
            seen_asset_ids.add(asset_id)
            filename = _expect_media_basename(
                actual_filename,
                context="Anki media binding actualFilename",
                code="invalid_note",
            )
            if _starts_with_ascii_case_insensitive(filename, "[sound:") or _starts_with_ascii_case_insensitive(
                filename, "<img"
            ):
                _protocol_error(
                    "invalid_note",
                    "Anki media bindings require raw provider filenames",
                )
            content_utf8_bytes += len(asset_id.encode("ascii")) + len(
                _strict_utf8_bytes(
                    filename,
                    context="Anki media binding actualFilename",
                    code="invalid_note",
                )
            )
        if content_utf8_bytes > _MAX_NOTE_CONTENT_UTF8_BYTES:
            _protocol_error("note_too_large", "An Anki note exceeds the byte limit")
        return dict(fields), list(tags), content_utf8_bytes

    def _materialized_media_bindings(
        self,
        fields: Mapping[str, str],
        card_media_bindings: Sequence[_MediaAcknowledgement],
    ) -> tuple[tuple[str, str], ...]:
        """Derive explicit bindings only from acknowledged materialized media."""

        from anki_miner.services.anki_media_store import _extract_dict_media_srcs

        field_values = tuple(fields.values())
        bindings: list[tuple[str, str]] = []
        seen_asset_ids: set[str] = set()

        def append_if_new(acknowledgement: _MediaAcknowledgement) -> None:
            if acknowledgement.asset_id in seen_asset_ids:
                return
            seen_asset_ids.add(acknowledgement.asset_id)
            bindings.append(
                (
                    acknowledgement.asset_id,
                    acknowledgement.actual_filename,
                )
            )

        for acknowledgement in card_media_bindings:
            filename = acknowledgement.actual_filename
            if acknowledgement.media_kind == "image":
                rendered = f'<img src="{html_escape(filename)}">'
            else:
                rendered = f"[sound:{filename}]"
            if rendered in field_values:
                append_if_new(acknowledgement)

        dictionary_by_actual = {
            acknowledgement.actual_filename: acknowledgement for acknowledgement in self._dict_media_bindings.values()
        }
        for field_value in field_values:
            for actual_filename in _extract_dict_media_srcs(field_value):
                acknowledgement = dictionary_by_actual.get(actual_filename)
                if acknowledgement is not None:
                    append_if_new(acknowledgement)

        if len(bindings) > _MAX_MEDIA_BINDINGS_PER_NOTE:
            _protocol_error("note_too_large", "A note contains too many media bindings")
        return tuple(bindings)

    def _preflight_create_call(self, word_data_list: Sequence[Any]) -> _CreatePreflightPlan:
        """Prove the complete create plan before progress, hashing, or callbacks.

        ``EpisodeProcessor._run_pipeline`` verifies the target before any
        mining phase.  That ordering is an adapter invariant: the provider's
        ordered first field is needed to prove duplicate identities and
        inseparable callback blocks without making a speculative provider
        call here.
        """

        if self._verified_field_names is None:
            _protocol_error(
                "invalid_note",
                "The Anki target must be verified before create preflight",
            )

        if len(word_data_list) > _MAX_CREATE_CALL_SOURCE_ITEMS:
            _protocol_error(
                "create_call_too_large",
                "The create call contains too many source cards",
            )

        source_utf8_bytes = 0
        for payload in word_data_list:
            source_utf8_bytes += self._relevant_source_utf8_bytes(payload)
            if source_utf8_bytes > _MAX_CREATE_CALL_SOURCE_UTF8_BYTES:
                _protocol_error(
                    "create_call_too_large",
                    "The create call exceeds its source UTF-8 limit",
                )

        from anki_miner.services.anki_media_store import (
            _MEDIA_FIELD_ATTRS,
            _extract_dict_media_srcs,
            _resolve_dict_media_path,
        )

        intended_stored_files: set[str] = set()
        card_media_paths: list[tuple[str, Path]] = []
        card_note_refs: list[list[tuple[str, str]]] = [[] for _ in word_data_list]
        note_binding_sources: list[set[tuple[str, str]]] = [set() for _ in word_data_list]
        dictionary_sources: list[str] = []
        dictionary_source_paths: dict[str, Path | None] = {}
        media_refs = 0
        media_bytes = 0
        counted_files: set[tuple[str, str, str]] = set()

        def account_path(path: Path, *, purpose: str, logical_name: str) -> None:
            nonlocal media_bytes
            _expect_media_source_path(
                str(path),
                context="create-call media source path",
                code="invalid_note",
            )
            try:
                metadata = path.stat()
            except OSError:
                return
            if not stat.S_ISREG(metadata.st_mode):
                return
            if metadata.st_size > _MAX_MEDIA_ASSET_BYTES:
                _protocol_error(
                    "media_too_large",
                    f"Media exceeds the {_MAX_MEDIA_ASSET_BYTES}-byte limit",
                )
            try:
                resolved_path = path.resolve()
            except OSError:
                resolved_path = path.absolute()
            # Match the real read/upload work: card media is deduplicated by
            # logical filename plus resolved path, while dictionary media is
            # deduplicated by its marked source name. Aliases under different
            # logical names are separate hashes and provider snapshots.
            identity = (purpose, logical_name, str(resolved_path))
            if identity in counted_files:
                return
            counted_files.add(identity)
            media_bytes += metadata.st_size
            if media_bytes > _MAX_CREATE_CALL_MEDIA_BYTES:
                _protocol_error(
                    "create_call_too_large",
                    "The create call exceeds its total media-byte limit",
                )

        for payload_index, payload in enumerate(word_data_list):
            for field_key, filename_attr, path_attr in _MEDIA_FIELD_ATTRS:
                if not self.config.anki_fields.get(field_key):
                    continue
                filename = getattr(payload.media, filename_attr)
                source_path = getattr(payload.media, path_attr)
                if not filename or not source_path:
                    continue
                _expect_filename(
                    filename,
                    context="create-call card media filename",
                    code="invalid_note",
                )
                _expect_filename(
                    _content_addressed_name_from_digest(filename, "0" * 12),
                    context="create-call content-addressed media filename",
                    code="invalid_note",
                )
                _expect_media_source_path(
                    str(source_path),
                    context="create-call card media source path",
                    code="invalid_note",
                )
                media_refs += 1
                if media_refs > _MAX_CREATE_CALL_MEDIA_REFS:
                    _protocol_error(
                        "create_call_too_large",
                        "The create call contains too many media references",
                    )
                intended_stored_files.add(filename)
                card_media_paths.append((filename, Path(source_path)))
                card_note_refs[payload_index].append((field_key, filename))

        # Validate the exact current note graph before running a regex over
        # marked dictionary HTML or touching media on disk. This makes a
        # single oversized definition/glossary fail without a scan or upload.
        from anki_miner.services.anki_note_builder import build_note

        built_notes: list[dict[str, Any]] = []
        built_fields: list[dict[str, str]] = []
        note_utf8_bytes = 0
        for payload in word_data_list:
            built = build_note(payload, self.config, intended_stored_files)
            fields, tags, content_bytes = self._validated_note_content(built.note)
            built_notes.append({"fields": fields, "tags": tags})
            built_fields.append(fields)
            note_utf8_bytes += content_bytes
            if note_utf8_bytes > _MAX_CREATE_CALL_NOTE_UTF8_BYTES:
                _protocol_error(
                    "create_call_too_large",
                    "The create call exceeds its built-note UTF-8 limit",
                )

        # Materialize the exact longest valid field representation, rather
        # than estimating from content bytes. This preserves JSON escaping in
        # the original content (notably NUL, quote, and backslash) and lets the
        # canonical envelope encoder prove the real callback ceiling. A valid
        # 1024-byte filename can consist entirely of apostrophes; html.escape
        # expands each to the six-byte ``&#x27;`` entity, which is the maximum
        # permitted expansion under the filename validator.
        longest_provider_filename = "'" * _MAX_MEDIA_FILENAME_UTF8_BYTES
        longest_escaped_filename = html_escape(longest_provider_filename, quote=True)
        longest_picture_field = f'<img src="{longest_escaped_filename}">'
        longest_sound_field = f"[sound:{'x' * _MAX_MEDIA_FILENAME_UTF8_BYTES}]"
        worst_fields = [dict(fields) for fields in built_fields]
        for payload_index, refs in enumerate(card_note_refs):
            fields = built_fields[payload_index]
            worst = worst_fields[payload_index]
            for note_key, filename in refs:
                field_name = self.config.anki_fields.get(note_key, "")
                if not field_name:
                    continue
                if note_key == "picture":
                    current_value = f'<img src="{html_escape(filename)}">'
                    worst_value = longest_picture_field
                else:
                    current_value = f"[sound:{filename}]"
                    worst_value = longest_sound_field
                if fields.get(field_name) == current_value:
                    worst[field_name] = worst_value
                    note_binding_sources[payload_index].add(("card", filename))

        for payload_index, payload in enumerate(word_data_list):
            html_fields = (
                ("definition", payload.definition),
                (
                    "glossary",
                    payload.extra_fields.get("glossary") if payload.extra_fields else None,
                ),
            )
            for note_key, html_field in html_fields:
                field_name = self.config.anki_fields.get(note_key, "")
                if (
                    not isinstance(html_field, str)
                    or not field_name
                    or built_fields[payload_index].get(field_name) != html_field
                ):
                    continue
                sources = _extract_dict_media_srcs(html_field)
                for source in sources:
                    _expect_media_basename(
                        source,
                        context="create-call dictionary media filename",
                        code="invalid_note",
                    )
                    media_refs += 1
                    if media_refs > _MAX_CREATE_CALL_MEDIA_REFS:
                        _protocol_error(
                            "create_call_too_large",
                            "The create call contains too many media references",
                        )
                    if source not in dictionary_source_paths:
                        dictionary_sources.append(source)
                        dictionary_source_paths[source] = (
                            None
                            if source in self._dict_media_uploaded
                            else _resolve_dict_media_path(source, self.config.dicts_root)
                        )

                rewritable_sources = {
                    source
                    for source in sources
                    if dictionary_source_paths[source] is not None or source in self._dict_media_actual_names
                }
                if rewritable_sources:
                    worst_fields[payload_index][field_name] = self._rewrite_dictionary_html(
                        html_field,
                        dict.fromkeys(rewritable_sources, longest_provider_filename),
                    )
                    note_binding_sources[payload_index].update(("dictionary", source) for source in rewritable_sources)

        planned_dictionary_paths = {
            source: path
            for source, path in dictionary_source_paths.items()
            if path is not None and source not in self._dict_media_uploaded
        }
        canonical_dictionary_sources = set(planned_dictionary_paths) | (
            set(dictionary_sources) & self._dict_media_actual_names.keys()
        )
        pending_reservations = self._pending_dictionary_media_reservations(
            dictionary_sources,
            set(planned_dictionary_paths),
        )

        worst_note_utf8_bytes = 0
        pending_notes: list[_PendingNote] = []
        seen_outgoing: set[str] = set()
        binding_ids: dict[tuple[str, str], str] = {}
        for source_index, (payload, built_note, fields) in enumerate(
            zip(
                word_data_list,
                built_notes,
                worst_fields,
                strict=True,
            )
        ):
            worst_note = {
                "fields": fields,
                "tags": list(built_note["tags"]),
            }
            preflight_bindings: tuple[tuple[str, str], ...] = tuple(
                (
                    binding_ids.setdefault(
                        source,
                        f"asset_{len(binding_ids):032x}",
                    ),
                    longest_provider_filename,
                )
                for source in sorted(note_binding_sources[source_index])
            )
            first_field = built_note["fields"].get(self._verified_field_names[0])
            _, _, worst_content_bytes = self._validated_note_content(worst_note, preflight_bindings)
            worst_note_utf8_bytes += worst_content_bytes
            if worst_note_utf8_bytes > _MAX_CREATE_CALL_NOTE_UTF8_BYTES:
                _protocol_error(
                    "create_call_too_large",
                    "The create call may exceed its built-note UTF-8 limit after media storage",
                )
            pending = self._prepare_note(
                payload,
                built_note,
                source_index,
                wire_note=worst_note,
                canonical_first_field=(
                    self._canonical_dictionary_first_field_identity(
                        first_field,
                        canonical_dictionary_sources,
                    )
                    if isinstance(first_field, str)
                    else None
                ),
                preflight_media_bindings=preflight_bindings,
            )
            if not self.config.allow_duplicate_cards:
                if pending.key in seen_outgoing:
                    continue
                seen_outgoing.add(pending.key)
            pending_notes.append(pending)

        # This is the same block planner used for provider submission. Byte
        # sizing uses exact worst-case wire notes; block identity additionally
        # canonicalizes HTML-equivalent spellings of one rewritable dictionary
        # source without merging distinct exact provider candidates.
        self._chunk_pending_notes(pending_notes)

        for logical_name, path in card_media_paths:
            account_path(path, purpose="card", logical_name=logical_name)
        for logical_name, path in planned_dictionary_paths.items():
            account_path(path, purpose="dictionary", logical_name=logical_name)

        return _CreatePreflightPlan(
            planned_dictionary_paths,
            tuple(dictionary_sources),
            pending_reservations,
        )

    @staticmethod
    def _duplicate_identity(fields: Mapping[str, str], first_field_name: str) -> tuple[str, str]:
        """Return one validated desktop-normalized duplicate identity."""

        first_field = fields.get(first_field_name)
        if not isinstance(first_field, str):
            _protocol_error(
                "invalid_note",
                f"The verified first field {first_field_name!r} is missing",
            )
        first_field_bytes = len(
            _strict_utf8_bytes(
                first_field,
                context="Verified Anki first field",
                code="invalid_note",
            )
        )
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
        return key, first_field

    def _prepare_note(
        self,
        payload: Any,
        note: Mapping[str, Any],
        source_index: int,
        *,
        wire_note: Mapping[str, Any] | None = None,
        canonical_first_field: tuple[tuple[str, str], ...] | None = None,
        card_media_bindings: Sequence[_MediaAcknowledgement] = (),
        preflight_media_bindings: Sequence[tuple[str, str]] | None = None,
    ) -> _PendingNote:
        """Validate one built note before any duplicate/provider side effect."""

        if self._verified_field_names is None:
            _protocol_error("invalid_note", "The Anki target must be verified first")
        first_field_name = self._verified_field_names[0]
        fields, tags, _ = self._validated_note_content(note)
        media_bindings = (
            tuple(preflight_media_bindings)
            if preflight_media_bindings is not None
            else self._materialized_media_bindings(fields, card_media_bindings)
        )
        fields, tags, content_utf8_bytes = self._validated_note_content(note, media_bindings)
        key, first_field = self._duplicate_identity(fields, first_field_name)

        wire_key: str | None = None
        wire_first_field: str | None = None
        if wire_note is not None:
            fields, tags, content_utf8_bytes = self._validated_note_content(wire_note, media_bindings)
            wire_key, wire_first_field = self._duplicate_identity(fields, first_field_name)
        preflight_block_identity = (
            (wire_key if wire_key is not None else key, canonical_first_field)
            if canonical_first_field is not None
            else None
        )
        return _PendingNote(
            payload=payload,
            note={"fields": fields, "tags": tags},
            key=key,
            first_field=first_field,
            content_utf8_bytes=content_utf8_bytes,
            source_index=source_index,
            media_bindings=media_bindings,
            wire_key=wire_key,
            wire_first_field=wire_first_field,
            preflight_block_identity=preflight_block_identity,
        )

    @staticmethod
    def _create_limits() -> dict[str, int]:
        return {
            "maxNotes": _BATCH_SIZE,
            "maxFieldsPerNote": _MAX_NOTE_FIELDS,
            "maxCardsPerNote": _MAX_CARDS_PER_NOTE,
            "maxFieldNameUtf8Bytes": _MAX_FIELD_NAME_UTF8_BYTES,
            "maxFieldValueUtf8Bytes": _MAX_FIELD_VALUE_UTF8_BYTES,
            "maxTagsPerNote": _MAX_NOTE_TAGS,
            "maxTagUtf8Bytes": _MAX_TAG_UTF8_BYTES,
            "maxTagsUtf8BytesPerNote": _MAX_NOTE_TAGS_UTF8_BYTES,
            "maxNoteContentUtf8Bytes": _MAX_NOTE_CONTENT_UTF8_BYTES,
            "maxTotalContentUtf8Bytes": _MAX_CREATE_CONTENT_UTF8_BYTES,
            "maxMediaBindingsPerNote": _MAX_MEDIA_BINDINGS_PER_NOTE,
            "maxMediaBindingsTotal": _MAX_MEDIA_BINDINGS_TOTAL,
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
    def _wire_note(pending: _PendingNote, client_id: str, occurrence: int) -> dict[str, Any]:
        return {
            "clientNoteId": client_id,
            "fields": dict(pending.note["fields"]),
            "tags": list(pending.note["tags"]),
            "mediaBindings": [
                {"assetId": asset_id, "actualFilename": actual_filename}
                for asset_id, actual_filename in pending.media_bindings
            ],
            "duplicateCandidate": {
                "key": pending.wire_key or pending.key,
                "firstField": pending.wire_first_field or pending.first_field,
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
        if sum(len(note.media_bindings) for note in notes) > _MAX_MEDIA_BINDINGS_TOTAL:
            _protocol_error(
                "note_batch_too_large",
                "Create-note callback exceeds its media-binding limit",
            )
        return {
            "deckName": self.config.anki_deck_name,
            "modelName": self.config.anki_note_type,
            "firstFieldName": self._verified_field_names[0],
            "baselineToken": baseline_token,
            "duplicateScope": self._create_duplicate_scope(),
            "limits": self._create_limits(),
            "notes": [
                self._wire_note(note, client_id, occurrence)
                for note, client_id, occurrence in zip(notes, client_ids, occurrences, strict=True)
            ],
        }

    def _create_envelope_utf8_bytes(self, notes: Sequence[_PendingNote]) -> int:
        client_ids = [f"note_{index:032x}" for index in range(len(notes))]
        # A create submission may be any increasing subset of the 0..99 probe
        # occurrences. The largest values maximize canonical JSON digit count;
        # sizing with them is therefore the exact worst envelope for this note
        # count, including sparse duplicate-filtered submissions.
        occurrences = list(range(_BATCH_SIZE - len(notes), _BATCH_SIZE))
        payload = self._create_request_payload(
            notes,
            _PLACEHOLDER_BASELINE_TOKEN,
            client_ids,
            occurrences,
        )
        envelope = encode_message(
            "anki.createnotes.request",
            {
                **payload,
                "runId": self._callbacks.run_id,
                "requestId": _PLACEHOLDER_REQUEST_ID,
            },
        )
        return len(
            _strict_utf8_bytes(
                envelope,
                context="createNotes request envelope",
                code="invalid_note",
            )
        )

    def _create_batch_fits(self, notes: Sequence[_PendingNote]) -> bool:
        return bool(notes) and (
            len(notes) <= _BATCH_SIZE
            and sum(len(note.media_bindings) for note in notes) <= _MAX_MEDIA_BINDINGS_TOTAL
            and sum(note.content_utf8_bytes for note in notes) <= _MAX_CREATE_CONTENT_UTF8_BYTES
            and self._create_envelope_utf8_bytes(notes) <= _MAX_CREATE_ENVELOPE_UTF8_BYTES
        )

    def _chunk_pending_notes(self, notes: Sequence[_PendingNote]) -> list[list[_PendingNote]]:
        """Pack exact-size callbacks without splitting repeated identities."""

        if not notes:
            return []
        identities = [note.preflight_block_identity or (note.key, note.first_field) for note in notes]
        last_occurrence = {identity: index for index, identity in enumerate(identities)}
        blocks: list[list[_PendingNote]] = []
        start = 0
        while start < len(notes):
            end = last_occurrence[identities[start]]
            cursor = start
            while cursor <= end:
                end = max(end, last_occurrence[identities[cursor]])
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

    def _duplicate_first_fields(self, candidates: Sequence[tuple[str, str]]) -> tuple[list[_DuplicateProbeResult], str]:
        if not candidates:
            _protocol_error("invalid_note", "Duplicate candidates must not be empty")
        if self._verified_field_names is None:
            _protocol_error("invalid_note", "The Anki target must be verified first")
        from anki_miner.services.anki_note_builder import _strip_for_dedup

        candidate_list = list(candidates)
        if (
            len(candidate_list) > _BATCH_SIZE
            or any(
                not isinstance(key, str) or not key or len(key) > _MAX_DUPLICATE_KEY_CHARS for key, _ in candidate_list
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
        candidate_indexes = {candidate: index for index, candidate in enumerate(unique_candidates)}
        invalidated_token = self._outstanding_baseline_token
        scope = {
            "kind": "duplicates",
            "modelName": self.config.anki_note_type,
            "firstFieldName": self._verified_field_names[0],
            "deckName": (self.config.anki_deck_name if self.config.allow_duplicate_cards else None),
            "candidates": [{"key": key, "firstField": first_field} for key, first_field in unique_candidates],
            "occurrences": [candidate_indexes[candidate] for candidate in candidate_list],
            # Kotlin atomically discards this abandoned all-duplicate
            # snapshot before retaining the replacement. A run therefore has
            # at most one outstanding baseline even across repeated calls.
            "invalidateBaselineToken": invalidated_token,
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
        try:
            return self._parse_duplicate_first_fields_result(
                payload,
                candidate_list=candidate_list,
                unique_candidates=unique_candidates,
                invalidated_token=invalidated_token,
            )
        except Exception:
            self._callbacks.mark_response_failure()
            raise

    def _parse_duplicate_first_fields_result(
        self,
        payload: dict[str, Any],
        *,
        candidate_list: Sequence[tuple[str, str]],
        unique_candidates: Sequence[tuple[str, str]],
        invalidated_token: str | None,
    ) -> tuple[list[_DuplicateProbeResult], str]:
        from anki_miner.services.anki_note_builder import _strip_for_dedup

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
        if _BASELINE_TOKEN_RE.fullmatch(baseline_token) is None or baseline_token == invalidated_token:
            _protocol_error(
                "invalid_anki_response",
                "Duplicate lookup baselineToken is malformed or reused",
            )
        raw_buckets = result["rawFirstFieldHits"]
        if not isinstance(raw_buckets, list) or len(raw_buckets) != len(unique_candidates):
            _protocol_error(
                "invalid_anki_response",
                "Duplicate lookup raw fields must align with candidates",
            )
        total_hits = 0
        total_utf8_bytes = 0
        probe_results: list[_DuplicateProbeResult] = []
        for index, ((key, _), bucket) in enumerate(zip(unique_candidates, raw_buckets, strict=True)):
            if not isinstance(bucket, list) or len(bucket) > (_MAX_DUPLICATE_HITS_PER_CANDIDATE):
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
                note_id = _expect_positive_int(row["noteId"], context=f"duplicate noteId {index}:{raw_index}")
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
                raw_bytes = len(
                    _strict_utf8_bytes(
                        raw_value,
                        context=f"Duplicate raw field {index}:{raw_index}",
                        code="invalid_anki_response",
                    )
                )
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
            probe_results.append(_DuplicateProbeResult(is_duplicate=normalized_match, occurrence=-1))
        results_by_candidate = dict(zip(unique_candidates, probe_results, strict=True))
        self._outstanding_baseline_token = baseline_token
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
        for index, (row_value, expected_id) in enumerate(zip(rows, client_ids, strict=True)):
            if not isinstance(row_value, dict):
                _protocol_error("invalid_anki_response", f"createNotes result {index} is invalid")
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
        error = None if raw_error is None else _expect_error_detail(raw_error, operation="createNotes")
        if saw_not_attempted and not saw_terminal_failure:
            _protocol_error(
                "invalid_anki_response",
                "notAttempted createNotes rows require a preceding terminal carrier",
            )
        if saw_terminal_failure and error is None:
            _protocol_error(
                "invalid_anki_response",
                "terminal createNotes rows require a top-level error",
            )
        if error is not None and not saw_terminal_failure:
            _protocol_error(
                "invalid_anki_response",
                "createNotes top-level error requires a row-local terminal carrier",
            )
        if error is not None:
            if saw_uncertain and (error.code != "post_commit_uncertain" or error.retryable):
                _protocol_error(
                    "invalid_anki_response",
                    "An uncertain write requires a non-retryable post-commit error",
                )
            if error.code == "post_commit_uncertain" and not (saw_uncertain or saw_committed_failure):
                _protocol_error(
                    "invalid_anki_response",
                    "A post-commit create error requires a row-local carrier",
                )
            if saw_committed_failure and (error.code == "cancelled" or error.retryable):
                _protocol_error(
                    "invalid_anki_response",
                    "A known post-commit failure must be non-retryable",
                )
            if created_ids and error.retryable:
                _protocol_error(
                    "invalid_anki_response",
                    "A partial write cannot be declared safely retryable",
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
        if _BASELINE_TOKEN_RE.fullmatch(baseline_token) is None or baseline_token != self._outstanding_baseline_token:
            _protocol_error("invalid_note", "Create-note duplicate baseline token is invalid")
        if (
            len(occurrences) != len(notes)
            or any(type(value) is not int or value < 0 for value in occurrences)
            or list(occurrences) != sorted(set(occurrences))
        ):
            _protocol_error("invalid_note", "Create-note duplicate occurrences are invalid")
        if not self._create_batch_fits(notes):
            _protocol_error("note_batch_too_large", "Create-note callback exceeds its byte limit")
        client_ids = [f"note_{uuid4().hex}" for _ in notes]
        request_payload = self._create_request_payload(notes, baseline_token, client_ids, occurrences)
        actual_envelope = encode_message(
            "anki.createnotes.request",
            {
                **request_payload,
                "runId": self._callbacks.run_id,
                "requestId": _PLACEHOLDER_REQUEST_ID,
            },
        )
        actual_envelope_bytes = len(
            _strict_utf8_bytes(
                actual_envelope,
                context="createNotes request envelope",
                code="invalid_note",
            )
        )
        if actual_envelope_bytes > _MAX_CREATE_ENVELOPE_UTF8_BYTES:
            _protocol_error("note_batch_too_large", "Create-note callback exceeds its byte limit")
        self._raise_if_cancelled("createNotes")
        # Initiating createNotes consumes the sole outstanding capability.
        # Kotlin enforces the same one-use transition before any insert.
        self._outstanding_baseline_token = None
        # From here until a VALIDATED response is parsed the honest answer is
        # "cannot tell": a dead provider or an unreadable reply is
        # indistinguishable from notes that were written and whose reply was
        # lost. Only the validated reply below downgrades this again, and only
        # to what held BEFORE this batch, so an all-duplicate batch cannot erase
        # an earlier batch's confirmed write.
        from anki_miner.models import AnkiWriteState

        state_before_request = self.anki_write_state
        self.anki_write_state = AnkiWriteState.NOTE_WRITE_UNCERTAIN
        try:
            payload = self._callbacks.create_notes(request_payload)
        except AnkiCallbackError as error:
            if error.code == "post_commit_uncertain":
                self._reject_callback_error(error)
                _protocol_error(
                    "invalid_anki_response",
                    "Post-commit uncertainty requires an aligned createNotes result",
                )
            self._accept_callback_error(error)
            _raise_callback_error(error)
        try:
            outcome = self._parse_create_notes_result(payload, client_ids)
        except Exception:
            self._reject_terminal_payload(payload)
            raise
        self._accept_terminal_payload(payload)
        if any(note_id is not None for note_id in outcome[0]):
            self.anki_write_state = AnkiWriteState.NOTE_WRITE_CONFIRMED
        else:
            self.anki_write_state = state_before_request
        return outcome

    def create_cards_batch(self, word_data_list: list[Any], progress_callback: Any | None = None) -> list[int]:
        """Create cards in desktop-compatible batches and preserve partial state.

        Returns the ordered created note ids, matching the desktop service: the
        processor takes ``len(...)`` of this as the card count and stamps the ids
        onto the result, so returning a count would break both.
        """

        if not word_data_list:
            self.last_created_note_ids = []
            self.last_skipped_duplicates = 0
            self.last_media_store_failures = 0
            return []

        self.last_created_note_ids = []
        self.last_skipped_duplicates = 0
        self.last_media_store_failures = 0
        # Desktop deliberately renders HTTP(S) glossary images. Android strips
        # every auto-loading image except a renderer-marked private dictionary
        # asset before any note identity, media scan, or provider mutation.
        word_data_list = self._sanitize_dictionary_payloads(word_data_list)
        preflight_plan = self._preflight_create_call(word_data_list)
        media_work_budget = _MediaWorkBudget()
        prepared_card_media = self._prepare_card_media(word_data_list, media_work_budget)
        prepared_dictionary_media = self._prepare_dictionary_media(preflight_plan, media_work_budget)
        all_prepared_assets = [
            *prepared_card_media.assets,
            *prepared_dictionary_media.assets,
        ]
        self._validate_asset_provider_namespaces(
            all_prepared_assets,
            additional_reservations=(preflight_plan.pending_media_name_reservations),
        )
        self._raise_if_cancelled("createNotes")
        # Commit reservations only after every digest and the combined card /
        # dictionary namespace proof has succeeded. A rejected call therefore
        # produces neither provider mutation nor poisoned adapter state.
        self._reserved_media_name_owners.update(preflight_plan.pending_media_name_reservations)

        all_created_ids: list[int] = []
        created_first_fields: list[str] = []
        skipped_duplicates = 0
        total_created = 0
        bold_used = 0
        bold_fallback = 0

        if progress_callback:
            progress_callback.on_start(len(word_data_list), "Creating Anki cards")

        stored_card_media = self._store_prepared_card_media(prepared_card_media)
        word_data_list = self._store_prepared_dictionary_media(word_data_list, prepared_dictionary_media)

        from anki_miner.services.anki_note_builder import _strip_for_dedup, build_note

        seen_outgoing: set[str] = set()
        try:
            pending_notes: list[_PendingNote] = []
            for source_index, item in enumerate(word_data_list):
                built = build_note(
                    item,
                    self.config,
                    set(stored_card_media.filenames),
                )
                if built.used_precomputed_bold:
                    bold_used += 1
                if built.used_bold_fallback:
                    bold_fallback += 1
                pending = self._prepare_note(
                    item,
                    built.note,
                    source_index,
                    card_media_bindings=(stored_card_media.bindings_by_media_identity.get(id(item.media), ())),
                )
                if not self.config.allow_duplicate_cards:
                    if pending.key in seen_outgoing:
                        skipped_duplicates += 1
                        continue
                    seen_outgoing.add(pending.key)
                pending_notes.append(pending)

            progress_reported = 0
            for callback_batch in self._chunk_pending_notes(pending_notes):
                self._raise_if_cancelled("createNotes")
                duplicate_probes, baseline_token = self._duplicate_first_fields(
                    [(pending.key, pending.first_field) for pending in callback_batch]
                )
                submissions = [
                    (pending, probe.occurrence)
                    for pending, probe in zip(callback_batch, duplicate_probes, strict=True)
                    if not probe.is_duplicate
                ]
                skipped_duplicates += len(callback_batch) - len(submissions)
                if submissions:
                    submit_notes = [pending for pending, _ in submissions]
                    occurrences = [occurrence for _, occurrence in submissions]
                    note_ids, successful, residual_duplicates, partial_error = self._create_note_batch(
                        submit_notes, baseline_token, occurrences
                    )
                    skipped_duplicates += residual_duplicates
                    repeated_created_ids = set(all_created_ids).intersection(
                        note_id for note_id in note_ids if note_id is not None
                    )
                    if repeated_created_ids:
                        self._callbacks.mark_response_failure()
                        _protocol_error(
                            "invalid_anki_response",
                            "createNotes reused a note ID from an earlier batch",
                        )
                    total_created += sum(successful)
                    all_created_ids.extend(note_id for note_id in note_ids if note_id is not None)
                    created_first_fields.extend(
                        pending.first_field
                        for pending, was_successful in zip(submit_notes, successful, strict=True)
                        if was_successful
                    )
                    if partial_error is not None:
                        _raise_callback_error(partial_error)

                processed_through = callback_batch[-1].source_index + 1
                while progress_callback and progress_reported + _BATCH_SIZE <= processed_through:
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
            if progress_callback:
                progress_callback.on_complete()
        except AnkiOperationCancelled as error:
            if not all_created_ids:
                raise
            # BaseException is intentional for a clean pre-write stop, but it
            # would bypass EpisodeProcessor's partial-ID harvest after an
            # earlier callback committed notes. Convert only that temporal
            # state to a catchable cancellation; prior commits alone do not
            # make the active row's outcome uncertain.
            from anki_miner.exceptions import AnkiConnectionError

            partial_error = AnkiConnectionError(
                "Anki card creation was cancelled after " f"{len(all_created_ids)} note(s) were committed: {error}"
            )
            partial_error.code = "cancelled"  # type: ignore[attr-defined]
            partial_error.retryable = False  # type: ignore[attr-defined]
            raise partial_error from error
        finally:
            self.last_created_note_ids = all_created_ids
            self.last_skipped_duplicates = skipped_duplicates
            if self._existing_vocab_cache is not None:
                for first_field in created_first_fields:
                    key = _strip_for_dedup(first_field)
                    if key and _JAPANESE_RE.search(key):
                        self._existing_vocab_cache.add(key)

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
        return list(all_created_ids)


# Concise injection name for callers which do not care about the transport.
AnkiAdapter = AndroidAnkiAdapter
