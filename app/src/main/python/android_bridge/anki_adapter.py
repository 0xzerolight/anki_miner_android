"""AnkiService-shaped adapter backed by synchronous Kotlin callbacks.

The Android side owns ContentProvider access.  Parity-sensitive behavior stays
in Python: desktop note construction, first-field normalization, duplicate
partitioning, content-addressed media names, batching, counters, and cache
updates.  Engine imports are deliberately function-local because bootstrap must
set ``ANKI_MINER_HOME`` before any ``anki_miner`` module is imported.
"""

from __future__ import annotations

import logging
import re
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, NoReturn
from uuid import uuid4

from .callbacks import AndroidAnkiCallbacks, AnkiCallbackError
from .config_map import _REQUIRED_ANKI_FIELD_KEYS, validate_anki_request_config
from .protocol import BridgeProtocolError, normalize_integral_json_number

logger = logging.getLogger(__name__)

_JAPANESE_RE = re.compile(r"[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\u3400-\u4DBF]")
_BATCH_SIZE = 100

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
    "internal_error",
}
_ALL_ERROR_CODES = _SETUP_ERROR_CODES | _PROTOCOL_ERROR_CODES | _CONNECTION_ERROR_CODES
_RECOVERABLE_MEDIA_ERROR_CODES = frozenset({"media_store_failed"})
_FORBIDDEN_FILENAME_CHARACTERS = frozenset('/\\<>[]:"\'')


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
    preferred_name: str
    original_name: str
    purpose: str
    media_kind: str

    def to_wire(self) -> dict[str, str]:
        return {
            "assetId": self.asset_id,
            "sourcePath": self.source_path,
            "preferredName": self.preferred_name,
            "purpose": self.purpose,
            "mediaKind": self.media_kind,
        }


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


def _expect_filename(value: object, *, context: str) -> str:
    filename = _expect_string(value, context=context, nonempty=True)
    if (
        filename in {".", ".."}
        or filename != filename.strip()
        or filename != unicodedata.normalize("NFC", filename)
        or any(character in _FORBIDDEN_FILENAME_CHARACTERS for character in filename)
        or any(unicodedata.category(character).startswith("C") for character in filename)
        or Path(filename).name != filename
    ):
        _protocol_error("invalid_anki_response", f"{context} is not a filename")
    return filename


def _validate_provider_filename(
    actual: str, preferred: str, *, purpose: str, context: str
) -> None:
    """Constrain AnkiDroid's returned name to its documented insertion shape.

    The v2.24 provider creates a temporary file from the preferred *stem* plus
    ``_`` and a random suffix, then preserves that file's extension through
    ``media.addFile``. A direct-name implementation may return the preferred
    name unchanged. Kotlin must unwrap the provider URI/``[sound:]``/``<img>``
    response before returning this raw basename.
    """

    preferred_path = Path(preferred)
    actual_path = Path(actual)
    if not preferred_path.suffix or not actual_path.suffix:
        _protocol_error(
            "unexpected_media_name", f"{context} must preserve a file extension"
        )
    if actual_path.suffix.casefold() != preferred_path.suffix.casefold():
        _protocol_error(
            "unexpected_media_name", f"{context} changed the requested extension"
        )
    if purpose == "dictionary":
        if actual != preferred:
            _protocol_error(
                "unexpected_media_name",
                "Dictionary media must be stored under its requested filename",
            )
        return
    if actual != preferred and not actual_path.stem.startswith(
        f"{preferred_path.stem}_"
    ):
        _protocol_error(
            "unexpected_media_name",
            f"{context} is unrelated to the requested media name",
        )


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

    def __init__(self, config: Any, callbacks: AndroidAnkiCallbacks) -> None:
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
        self.last_created_note_ids: list[int] = []
        self.last_skipped_duplicates = 0
        self.last_media_store_failures = 0
        self._existing_vocab_cache: set[str] | None = None
        self._dict_media_uploaded: set[str] = set()

    def verify_card_target(self) -> None:
        """Validate model/fields and create the target deck only after checks."""

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

    def _scan_first_fields(self, scope: dict[str, Any]) -> list[str]:
        try:
            payload = self._callbacks.scan_first_fields({"scope": scope})
        except AnkiCallbackError:
            raise
        result = _expect_exact_keys(
            payload,
            {"runId", "requestId", "firstFields"},
            context="scanFirstFields result",
        )
        return _expect_string_list(result["firstFields"], context="firstFields")

    def get_existing_vocabulary(self) -> set[str]:
        """Return cached, desktop-normalized Japanese first fields."""

        if self._existing_vocab_cache is not None:
            return self._existing_vocab_cache

        try:
            raw_fields = self._scan_first_fields(
                {
                    "kind": "knownVocabulary",
                    "excludedDecks": list(self.config.excluded_decks),
                }
            )
        except AnkiCallbackError as error:
            if error.code == "timeout" and error.retryable:
                logger.warning(
                    "Failed to fetch existing vocabulary (filtering disabled): %s",
                    error,
                )
                return set()
            _raise_callback_error(error)

        from anki_miner.services.anki_note_builder import _strip_for_dedup

        existing = {
            normalized
            for raw in raw_fields
            if (normalized := _strip_for_dedup(raw)) and _JAPANESE_RE.search(normalized)
        }
        self._existing_vocab_cache = existing
        return existing

    def invalidate_existing_vocabulary_cache(self) -> None:
        self._existing_vocab_cache = None

    def _parse_store_media_result(
        self, payload: dict[str, Any], assets: Sequence[_MediaAsset]
    ) -> dict[str, str]:
        result = _expect_exact_keys(
            payload,
            {"runId", "requestId", "results"},
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
            for claimed_name in (asset.preferred_name, asset.original_name):
                claims.setdefault(claimed_name, set()).add(asset.asset_id)

        stored: dict[str, str] = {}
        actual_names: set[str] = set()
        for index, (row_value, asset) in enumerate(
            zip(rows, assets, strict=True)
        ):
            if not isinstance(row_value, dict):
                _protocol_error(
                    "invalid_anki_response", f"storeMedia result {index} is invalid"
                )
            status = row_value.get("status")
            if status == "stored":
                row = _expect_exact_keys(
                    row_value,
                    {"assetId", "status", "actualFilename"},
                    context=f"storeMedia result {index}",
                )
                actual = _expect_filename(
                    row["actualFilename"], context=f"storeMedia result {index} filename"
                )
                _validate_provider_filename(
                    actual,
                    asset.preferred_name,
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
                actual_names.add(actual)
                stored[asset.asset_id] = actual
            elif status == "failed":
                row = _expect_exact_keys(
                    row_value,
                    {"assetId", "status", "error"},
                    context=f"storeMedia result {index}",
                )
                error = _expect_error_detail(
                    row["error"], operation="storeMedia"
                )
                if error.code not in _RECOVERABLE_MEDIA_ERROR_CODES:
                    _raise_callback_error(error)
                logger.warning(
                    "Failed to store media asset %s: %s", asset.original_name, error
                )
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
        return stored

    def _store_assets(self, assets: list[_MediaAsset]) -> dict[str, str]:
        if not assets:
            return {}
        asset_ids: set[str] = set()
        preferred_names: set[str] = set()
        requested_name_owners: dict[str, str] = {}
        for index, asset in enumerate(assets):
            if asset.asset_id in asset_ids:
                _protocol_error("invalid_anki_request", "Media asset IDs must be unique")
            asset_ids.add(asset.asset_id)
            _expect_filename(
                asset.preferred_name, context=f"storeMedia asset {index} preferredName"
            )
            _expect_filename(
                asset.original_name, context=f"storeMedia asset {index} originalName"
            )
            if asset.preferred_name in preferred_names:
                _protocol_error(
                    "invalid_anki_request", "Media preferred names must be unique"
                )
            preferred_names.add(asset.preferred_name)
            for claimed_name in {asset.preferred_name, asset.original_name}:
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
            if asset.purpose not in {"card", "dictionary"} or asset.media_kind not in {
                "audio",
                "image",
            }:
                _protocol_error("invalid_anki_request", "Media asset metadata is invalid")
        try:
            payload = self._callbacks.store_media(
                {"assets": [asset.to_wire() for asset in assets]}
            )
        except AnkiCallbackError as error:
            _raise_callback_error(error)
        return self._parse_store_media_result(payload, assets)

    def _store_card_media(self, word_data_list: Sequence[Any]) -> set[str]:
        from anki_miner.services.anki_media_store import (
            _MEDIA_FIELD_ATTRS,
            _content_addressed_name,
        )

        paths_by_filename: dict[str, Path] = {}
        vanished: set[str] = set()
        refs: dict[str, list[tuple[Any, str]]] = {}
        kinds: dict[str, str] = {}

        for item in word_data_list:
            media = item.media
            for filename_attr, path_attr in _MEDIA_FIELD_ATTRS:
                filename = getattr(media, filename_attr)
                source_path = getattr(media, path_attr)
                if not filename or not source_path:
                    continue
                refs.setdefault(filename, []).append((media, filename_attr))
                kinds.setdefault(
                    filename,
                    "image" if filename_attr == "screenshot_filename" else "audio",
                )
                path = Path(source_path)
                if not path.exists():
                    if filename not in paths_by_filename and filename not in vanished:
                        logger.warning(
                            "Media source file vanished before upload: %s", filename
                        )
                        vanished.add(filename)
                    continue
                if filename not in paths_by_filename:
                    paths_by_filename[filename] = path

        assets: list[_MediaAsset] = []
        originals_by_id: dict[str, str] = {}
        for filename, source_path in paths_by_filename.items():
            try:
                content = source_path.read_bytes()
            except OSError as error:
                logger.warning("Failed to read media file %s: %s", filename, error)
                continue
            asset_id = f"asset_{uuid4().hex}"
            originals_by_id[asset_id] = filename
            assets.append(
                _MediaAsset(
                    asset_id=asset_id,
                    source_path=str(source_path.resolve()),
                    preferred_name=_content_addressed_name(filename, content),
                    original_name=filename,
                    purpose="card",
                    media_kind=kinds[filename],
                )
            )

        stored_by_id = self._store_assets(assets)
        stored_names: set[str] = set()
        renamed_originals: set[str] = set()
        for asset_id, actual in stored_by_id.items():
            original = originals_by_id[asset_id]
            renamed_originals.add(original)
            stored_names.add(actual)
            for media, filename_attr in refs.get(original, []):
                setattr(media, filename_attr, actual)

        self.last_media_store_failures = (
            len(paths_by_filename) - len(renamed_originals) + len(vanished)
        )
        return stored_names

    def _store_dictionary_media(self, word_data_list: Sequence[Any]) -> None:
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
            asset_id = f"asset_{uuid4().hex}"
            sources_by_id[asset_id] = source
            assets.append(
                _MediaAsset(
                    asset_id=asset_id,
                    source_path=str(path.resolve()),
                    preferred_name=source,
                    original_name=source,
                    purpose="dictionary",
                    media_kind="image",
                )
            )

        stored_by_id = self._store_assets(assets)
        for asset_id, actual in stored_by_id.items():
            source = sources_by_id[asset_id]
            # Exact-name enforcement happens transactionally in the parser,
            # before any success is cached or CardPayload is mutated.
            self._dict_media_uploaded.add(source)

    def _duplicate_first_fields(self) -> set[str]:
        scope = {
            "kind": "duplicates",
            "modelName": self.config.anki_note_type,
            "deckName": (
                self.config.anki_deck_name
                if self.config.allow_duplicate_cards
                else None
            ),
        }
        try:
            raw_fields = self._scan_first_fields(scope)
        except AnkiCallbackError as error:
            _raise_callback_error(error)

        from anki_miner.services.anki_note_builder import _strip_for_dedup

        return {key for raw in raw_fields if (key := _strip_for_dedup(raw))}

    def _parse_create_notes_result(
        self, payload: dict[str, Any], client_ids: Sequence[str]
    ) -> tuple[list[int | None], int, AnkiCallbackError | None]:
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
        duplicates = 0
        saw_failed = False
        saw_not_attempted = False
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
            if status == "created":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status", "noteId"},
                    context=f"createNotes result {index}",
                )
                note_id = _expect_positive_int(
                    row["noteId"], context=f"noteId {index}"
                )
                if note_id in created_ids:
                    _protocol_error(
                        "invalid_anki_response",
                        "createNotes returned duplicate note IDs",
                    )
                created_ids.add(note_id)
                note_ids.append(note_id)
            elif status == "duplicate":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status"},
                    context=f"createNotes result {index}",
                )
                note_ids.append(None)
                duplicates += 1
            elif status == "failed":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status"},
                    context=f"createNotes result {index}",
                )
                note_ids.append(None)
                saw_failed = True
            elif status == "notAttempted":
                row = _expect_exact_keys(
                    row_value,
                    {"clientNoteId", "status"},
                    context=f"createNotes result {index}",
                )
                note_ids.append(None)
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
        if (saw_failed or saw_not_attempted) and error is None:
            _protocol_error(
                "invalid_anki_response",
                "failed/notAttempted createNotes rows require a top-level error",
            )
        if error is not None and not (saw_failed or saw_not_attempted):
            _protocol_error(
                "invalid_anki_response",
                "createNotes top-level error requires a failed or notAttempted row",
            )
        return note_ids, duplicates, error

    def _create_note_batch(
        self, notes: Sequence[Mapping[str, Any]]
    ) -> tuple[list[int | None], int, AnkiCallbackError | None]:
        client_ids: list[str] = []
        wire_notes: list[dict[str, Any]] = []
        for note in notes:
            client_id = f"note_{uuid4().hex}"
            client_ids.append(client_id)
            fields = note.get("fields")
            tags = note.get("tags")
            if not isinstance(fields, dict) or not fields:
                _protocol_error(
                    "invalid_note", "A note must contain at least one field"
                )
            if not all(isinstance(key, str) and key for key in fields):
                _protocol_error(
                    "invalid_note", "Anki field names must be non-empty strings"
                )
            if not all(isinstance(value, str) for value in fields.values()):
                _protocol_error("invalid_note", "Anki field values must be strings")
            if not isinstance(tags, list) or not all(
                isinstance(tag, str) and tag for tag in tags
            ):
                _protocol_error("invalid_note", "Anki tags must be non-empty strings")
            wire_notes.append(
                {"clientNoteId": client_id, "fields": dict(fields), "tags": list(tags)}
            )

        try:
            payload = self._callbacks.create_notes(
                {
                    "deckName": self.config.anki_deck_name,
                    "modelName": self.config.anki_note_type,
                    "notes": wire_notes,
                }
            )
        except AnkiCallbackError as error:
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
        self._store_dictionary_media(word_data_list)

        from anki_miner.services.anki_note_builder import _strip_for_dedup, build_note

        seen_outgoing: set[str] = set()
        try:
            for offset in range(0, len(word_data_list), _BATCH_SIZE):
                batch = word_data_list[offset : offset + _BATCH_SIZE]
                built_notes: list[dict[str, Any]] = []
                for item in batch:
                    built = build_note(item, self.config, stored_files)
                    if built.used_precomputed_bold:
                        bold_used += 1
                    if built.used_bold_fallback:
                        bold_fallback += 1
                    built_notes.append(built.note)
                existing = self._duplicate_first_fields()

                submit_notes: list[dict[str, Any]] = []
                submit_payloads: list[Any] = []
                for item, note in zip(batch, built_notes, strict=True):
                    fields = note.get("fields") or {}
                    first_value = next(iter(fields.values()), "")
                    key = _strip_for_dedup(first_value)
                    duplicate = key in existing or key in seen_outgoing
                    if duplicate:
                        skipped_duplicates += 1
                        continue
                    seen_outgoing.add(key)
                    submit_notes.append(note)
                    submit_payloads.append(item)

                if submit_notes:
                    note_ids, residual_duplicates, partial_error = (
                        self._create_note_batch(submit_notes)
                    )
                else:
                    note_ids, residual_duplicates, partial_error = [], 0, None

                skipped_duplicates += residual_duplicates
                repeated_created_ids = set(all_created_ids).intersection(
                    note_id for note_id in note_ids if note_id is not None
                )
                if repeated_created_ids:
                    _protocol_error(
                        "invalid_anki_response",
                        "createNotes reused a note ID from an earlier batch",
                    )
                created_in_batch = sum(note_id is not None for note_id in note_ids)
                total_created += created_in_batch
                all_created_ids.extend(
                    note_id for note_id in note_ids if note_id is not None
                )
                created_forms.extend(
                    item.word.mined_form
                    for item, note_id in zip(submit_payloads, note_ids, strict=True)
                    if note_id is not None
                )

                if partial_error is not None:
                    _raise_callback_error(partial_error)

                if progress_callback:
                    progress_callback.on_progress(
                        min(offset + _BATCH_SIZE, len(word_data_list)),
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
