from __future__ import annotations

import hashlib
import html
import json
import re
import sys
import types
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from android_bridge.anki_adapter import (
    AndroidAnkiAdapter,
    AnkiOperationCancelled,
    _MAX_CARD_MEDIA_BYTES,
    _MAX_CREATE_CONTENT_UTF8_BYTES,
    _MAX_CREATE_ENVELOPE_UTF8_BYTES,
    _MAX_FIELD_NAME_UTF8_BYTES,
    _MAX_FIELD_VALUE_UTF8_BYTES,
    _MAX_KNOWN_VOCABULARY_SCANNED_NOTES,
    _MAX_MEDIA_ASSET_BYTES,
    _MAX_MEDIA_CALLBACK_BYTES,
    _MAX_NOTE_CONTENT_UTF8_BYTES,
    _MAX_NOTE_FIELDS,
    _MAX_NOTE_TAGS,
    _MAX_NOTE_TAGS_UTF8_BYTES,
    _MAX_TAG_UTF8_BYTES,
    _MEDIA_HASH_CHUNK_BYTES,
    _MediaAsset,
    _chunk_media_assets,
    _dictionary_provider_preferred_name,
)
from android_bridge.callbacks import AndroidAnkiCallbacks
from android_bridge.protocol import BridgeProtocolError, encode_message

RUN_ID = "run_" + "a" * 32
_DUPLICATE_LIMITS = {
    "maxHitsPerCandidate": 100,
    "maxTotalHits": 1000,
    "maxItemUtf8Bytes": 65536,
    "maxTotalUtf8Bytes": 1048576,
}
_CREATE_SNAPSHOT_LIMITS = {
    "maxNoteIdsPerCandidate": 100,
    "maxTotalNoteIds": 1000,
}
_CREATE_LIMITS = {
    "maxNotes": 100,
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
_KNOWN_VOCABULARY_LIMITS = {
    "maxScannedNotes": 256,
    "maxTotalScannedNotes": 100000,
    "maxItems": 256,
    "maxItemUtf8Bytes": 65536,
    "maxTotalUtf8Bytes": 262144,
}
_ANKI_SCHEMA = json.loads(
    (
        Path(__file__).resolve().parents[3]
        / "app/src/main/python/android_bridge/schemas/anki.schema.json"
    ).read_text(encoding="utf-8")
)
_ANKI_VALIDATOR = Draft202012Validator(_ANKI_SCHEMA)

_ANKIDROID_STYLE_RE = re.compile(r"<style.*?>.*?</style>", re.DOTALL)
_ANKIDROID_SCRIPT_RE = re.compile(r"<script.*?>.*?</script>", re.DOTALL)
_ANKIDROID_TAG_RE = re.compile(r"<.*?>")
_ANKIDROID_IMG_RE = re.compile(r"""<img src=["']?([^"'>]+)["']? ?/?>""")


def _ankidroid_field_checksum(value: str) -> int:
    """Mirror the pinned AnkiDroid v2.24 ``Utils.fieldChecksum`` probe."""

    stripped = _ANKIDROID_IMG_RE.sub(lambda match: f" {match.group(1)} ", value)
    stripped = _ANKIDROID_STYLE_RE.sub("", stripped)
    stripped = _ANKIDROID_SCRIPT_RE.sub("", stripped)
    stripped = _ANKIDROID_TAG_RE.sub("", stripped)
    stripped = html.unescape(stripped.replace("&nbsp;", " "))
    return int.from_bytes(hashlib.sha1(stripped.encode()).digest()[:4], "big")


@pytest.fixture(scope="module", autouse=True)
def isolated_services_namespace(initialized_bridge_home: Path) -> Iterator[None]:
    """Load narrow engine helpers without importing every desktop service.

    Android packages the full runtime dependency set. The deliberately small
    host bridge-test environment does not, so bypass the eager desktop
    ``services.__init__`` while retaining normal imports of the vendored helper
    modules under test.
    """

    import anki_miner

    prefix = "anki_miner.services"
    previous = {
        name: module
        for name, module in sys.modules.items()
        if name == prefix or name.startswith(f"{prefix}.")
    }
    for name in previous:
        sys.modules.pop(name)

    package = types.ModuleType(prefix)
    package.__path__ = [str(Path(anki_miner.__file__).resolve().parent / "services")]
    sys.modules[prefix] = package
    ankiconnect = types.ModuleType(f"{prefix}._ankiconnect")

    def unexpected_http(*args: object, **kwargs: object) -> None:
        raise AssertionError("Android adapter must not call AnkiConnect")

    ankiconnect.post_action = unexpected_http  # type: ignore[attr-defined]
    ankiconnect.post_multi = unexpected_http  # type: ignore[attr-defined]
    sys.modules[ankiconnect.__name__] = ankiconnect
    try:
        yield
    finally:
        for name in list(sys.modules):
            if name == prefix or name.startswith(f"{prefix}."):
                sys.modules.pop(name)
        sys.modules.update(previous)


class FakeKotlinAnki:
    def __init__(self) -> None:
        self.requests: list[tuple[str, dict[str, Any]]] = []
        self.verify_fields: list[str] | None = None
        self.known_fields: list[str] = []
        self.known_note_decks: list[set[str]] = []
        self.duplicate_fields: list[str] = []
        self.duplicate_note_ids: dict[int, int] = {}
        self.duplicate_decks: dict[str, set[str]] = {}
        self.errors: dict[str, tuple[str, str, bool]] = {}
        self.failed_media_names: set[str] = set()
        self.media_failure_errors: dict[str, tuple[str, str, bool]] = {}
        self.media_renames: dict[str, str] = {}
        self.create_scripts: list[
            tuple[list[str], dict[str, object] | None] | None
        ] = []
        self.next_note_id = 1000
        self._baseline_counter = 0
        self._baseline_snapshots: dict[str, dict[str, Any]] = {}
        self._verified_first_fields: dict[str, str] = {}
        self._known_cursor_counter = 0
        self._known_cursors: dict[str, dict[str, Any]] = {}

    def _duplicate_records(self) -> list[tuple[int, str]]:
        return [
            (self.duplicate_note_ids.get(index, 10_000 + index), first_field)
            for index, first_field in enumerate(self.duplicate_fields)
        ]

    def _in_duplicate_scope(
        self, note_id: int, first_field: str, scope: dict[str, Any]
    ) -> bool:
        del note_id
        if scope["kind"] == "collection":
            return True
        return scope["deckName"] in self.duplicate_decks.get(first_field, set())

    def _snapshot_candidate_ids(
        self,
        candidate: dict[str, Any],
        scope: dict[str, Any],
        records: list[tuple[int, str]],
    ) -> set[int]:
        checksum = _ankidroid_field_checksum(candidate["firstField"])
        return {
            note_id
            for note_id, first_field in records
            if _ankidroid_field_checksum(first_field) == checksum
            and self._in_duplicate_scope(note_id, first_field, scope)
        }

    def _retain_duplicate_baseline(
        self,
        request: dict[str, Any],
        raw_hit_buckets: list[list[dict[str, object]]],
    ) -> str:
        scope = request["scope"]
        token = f"baseline_{self._baseline_counter:032x}"
        self._baseline_counter += 1
        self._baseline_snapshots[token] = {
            "runId": request["runId"],
            "modelName": scope["modelName"],
            "firstFieldName": scope["firstFieldName"],
            "deckName": scope["deckName"],
            "candidates": [
                (candidate["key"], candidate["firstField"])
                for candidate in scope["candidates"]
            ],
            "occurrences": list(scope["occurrences"]),
            "ids": {
                (candidate["key"], candidate["firstField"]): frozenset(
                    int(hit["noteId"]) for hit in hits
                )
                for candidate, hits in zip(
                    scope["candidates"], raw_hit_buckets, strict=True
                )
            },
        }
        return token

    def _request(self, method: str, raw: str) -> dict[str, Any]:
        envelope = json.loads(raw)
        _ANKI_VALIDATOR.validate(envelope["payload"])
        self.requests.append((method, envelope))
        return envelope["payload"]

    def _error(self, request: dict[str, Any], operation: str) -> str | None:
        configured = self.errors.get(operation)
        if configured is None:
            return None
        code, message, retryable = configured
        return encode_message(
            "anki.error",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "operation": operation,
                "code": code,
                "message": message,
                "retryable": retryable,
            },
        )

    def ankiVerifyTarget(self, raw: str) -> str:
        request = self._request("ankiVerifyTarget", raw)
        if error := self._error(request, "verifyTarget"):
            return error
        fields = (
            self.verify_fields
            if self.verify_fields is not None
            else (
                ["Expression"]
                + sorted(
                    field
                    for field in request["requiredFields"]
                    if field != "Expression"
                )
                if "Expression" in request["requiredFields"]
                else (request["requiredFields"] or ["Expression"])
            )
        )
        self._verified_first_fields[request["modelName"]] = fields[0]
        return encode_message(
            "anki.verifytarget.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "deckId": 10,
                "modelId": 20,
                "fieldNames": fields,
                "deckCreated": False,
            },
        )

    def ankiScanFirstFields(self, raw: str) -> str:
        request = self._request("ankiScanFirstFields", raw)
        if error := self._error(request, "scanFirstFields"):
            return error
        if request["scope"]["kind"] == "knownVocabulary":
            scope = request["scope"]
            cursor = scope["cursor"]
            if cursor is None:
                start = 0
                next_ordinal = 1
                scanned_before = 0
            else:
                cursor_state = self._known_cursors.pop(cursor["token"])
                assert cursor_state["runId"] == request["runId"]
                assert cursor_state["excludedDecks"] == tuple(
                    scope["excludedDecks"]
                )
                assert cursor_state["ordinal"] == cursor["ordinal"]
                start = cursor_state["start"]
                scanned_before = cursor_state["scannedNotes"]
                next_ordinal = cursor["ordinal"] + 1
            limits = scope["limits"]
            excluded = scope["excludedDecks"]
            page_fields: list[str] = []
            page_utf8_bytes = 0
            scanned_notes = 0
            for index in range(
                start,
                min(
                    start + limits["maxScannedNotes"],
                    start + limits["maxTotalScannedNotes"] - scanned_before,
                    len(self.known_fields),
                ),
            ):
                field = self.known_fields[index]
                field_bytes = len(field.encode("utf-8"))
                assert field_bytes <= limits["maxItemUtf8Bytes"]
                decks = (
                    self.known_note_decks[index]
                    if index < len(self.known_note_decks)
                    else set()
                )
                note_is_excluded = any(
                    deck == excluded_deck
                    or deck.startswith(f"{excluded_deck}::")
                    for deck in decks
                    for excluded_deck in excluded
                )
                if (
                    not note_is_excluded
                    and page_fields
                    and page_utf8_bytes + field_bytes
                    > limits["maxTotalUtf8Bytes"]
                ):
                    break
                scanned_notes += 1
                if not note_is_excluded:
                    page_fields.append(field)
                    page_utf8_bytes += field_bytes
            next_index = start + scanned_notes
            total_scanned = scanned_before + scanned_notes
            if (
                next_index < len(self.known_fields)
                and total_scanned >= limits["maxTotalScannedNotes"]
            ):
                return encode_message(
                    "anki.error",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "operation": "scanFirstFields",
                        "code": "query_failed",
                        "message": "known-vocabulary total scan ceiling reached",
                        "retryable": False,
                    },
                )
            if next_index < len(self.known_fields):
                token = f"known_cursor_{self._known_cursor_counter:032x}"
                self._known_cursor_counter += 1
                self._known_cursors[token] = {
                    "runId": request["runId"],
                    "excludedDecks": tuple(scope["excludedDecks"]),
                    "ordinal": next_ordinal,
                    "start": next_index,
                    "scannedNotes": total_scanned,
                }
                next_cursor = {"ordinal": next_ordinal, "token": token}
            else:
                next_cursor = None
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "firstFields": page_fields,
                    "scannedNotes": scanned_notes,
                    "nextCursor": next_cursor,
                },
            )

        scope = request["scope"]
        assert (
            scope["firstFieldName"]
            == self._verified_first_fields[scope["modelName"]]
        )
        deck_name = scope["deckName"]
        assert len(scope["occurrences"]) <= 100
        assert all(
            0 <= occurrence < len(scope["candidates"])
            for occurrence in scope["occurrences"]
        )
        raw_hit_buckets = []
        records = self._duplicate_records()
        for candidate in scope["candidates"]:
            candidate_checksum = _ankidroid_field_checksum(candidate["firstField"])
            hits = []
            for note_id, stored in records:
                if _ankidroid_field_checksum(stored) != candidate_checksum:
                    continue
                if deck_name is not None and deck_name not in self.duplicate_decks.get(
                    stored, set()
                ):
                    continue
                hits.append({"noteId": note_id, "firstField": stored})
            raw_hit_buckets.append(hits)
        baseline_token = self._retain_duplicate_baseline(request, raw_hit_buckets)
        return encode_message(
            "anki.scanfirstfields.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "rawFirstFieldHits": raw_hit_buckets,
                "baselineToken": baseline_token,
            },
        )

    def ankiStoreMedia(self, raw: str) -> str:
        request = self._request("ankiStoreMedia", raw)
        if error := self._error(request, "storeMedia"):
            return error
        assert request["limits"] == {
            "maxAssets": 50,
            "maxAssetBytes": _MAX_MEDIA_ASSET_BYTES,
            "maxTotalBytes": _MAX_MEDIA_CALLBACK_BYTES,
        }
        results: list[dict[str, object]] = []
        for asset in request["assets"]:
            actual_size = 0
            actual_sha256 = hashlib.sha256()
            try:
                with Path(asset["sourcePath"]).open("rb") as source:
                    while chunk := source.read(_MEDIA_HASH_CHUNK_BYTES):
                        actual_size += len(chunk)
                        if actual_size > request["limits"]["maxAssetBytes"]:
                            raise ValueError("oversized media")
                        actual_sha256.update(chunk)
            except (OSError, ValueError):
                return encode_message(
                    "anki.error",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "operation": "storeMedia",
                        "code": "invalid_request",
                        "message": "media snapshot could not be verified",
                        "retryable": False,
                    },
                )
            if (
                actual_size != asset["expectedSizeBytes"]
                or actual_sha256.hexdigest() != asset["expectedSha256"]
            ):
                return encode_message(
                    "anki.error",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "operation": "storeMedia",
                        "code": "invalid_request",
                        "message": "media changed before snapshot",
                        "retryable": False,
                    },
                )
            preferred = asset["preferredName"]
            if preferred in self.failed_media_names:
                code, message, retryable = self.media_failure_errors.get(
                    preferred,
                    ("media_store_failed", "media insert failed", True),
                )
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "failed",
                        "error": {
                            "code": code,
                            "message": message,
                            "retryable": retryable,
                        },
                    }
                )
            else:
                extension = Path(asset["sourcePath"]).suffix or ".bin"
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "stored",
                        "actualFilename": self.media_renames.get(
                            preferred, f"{preferred}_provider{extension}"
                        ),
                    }
                )
        return encode_message(
            "anki.storemedia.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "results": results,
                "error": None,
            },
        )

    def ankiCreateNotes(self, raw: str) -> str:
        request = self._request("ankiCreateNotes", raw)
        if error := self._error(request, "createNotes"):
            return error
        assert len(raw.encode("utf-8")) <= request["limits"]["maxEnvelopeUtf8Bytes"]
        assert request["limits"] == _CREATE_LIMITS
        total_content_bytes = 0
        for note in request["notes"]:
            assert len(note["fields"]) <= request["limits"]["maxFieldsPerNote"]
            note_content_bytes = 0
            for field_name, value in note["fields"].items():
                assert len(field_name.encode("utf-8")) <= request["limits"][
                    "maxFieldNameUtf8Bytes"
                ]
                assert len(value.encode("utf-8")) <= request["limits"][
                    "maxFieldValueUtf8Bytes"
                ]
                note_content_bytes += len(field_name.encode("utf-8")) + len(
                    value.encode("utf-8")
                )
            assert len(note["tags"]) <= request["limits"]["maxTagsPerNote"]
            tag_bytes = sum(len(tag.encode("utf-8")) for tag in note["tags"])
            assert all(
                len(tag.encode("utf-8")) <= request["limits"]["maxTagUtf8Bytes"]
                for tag in note["tags"]
            )
            assert tag_bytes <= request["limits"]["maxTagsUtf8BytesPerNote"]
            note_content_bytes += tag_bytes
            assert note_content_bytes <= request["limits"][
                "maxNoteContentUtf8Bytes"
            ]
            total_content_bytes += note_content_bytes
        assert total_content_bytes <= request["limits"]["maxTotalContentUtf8Bytes"]

        baseline = self._baseline_snapshots.pop(request["baselineToken"])
        assert baseline["runId"] == request["runId"]
        assert baseline["modelName"] == request["modelName"]
        assert baseline["firstFieldName"] == request["firstFieldName"]
        assert request["firstFieldName"] == self._verified_first_fields[
            request["modelName"]
        ]
        expected_deck_name = (
            request["duplicateScope"].get("deckName")
            if request["duplicateScope"]["kind"] == "exactDeck"
            else None
        )
        assert baseline["deckName"] == expected_deck_name
        previous_occurrence = -1
        for note in request["notes"]:
            candidate = note["duplicateCandidate"]
            occurrence = candidate["occurrence"]
            assert occurrence > previous_occurrence
            previous_occurrence = occurrence
            candidate_index = baseline["occurrences"][occurrence]
            assert baseline["candidates"][candidate_index] == (
                candidate["key"],
                candidate["firstField"],
            )
        # The production callback must take one complete scoped snapshot before
        # any insert. Comparing IDs with Python's probe baseline closes the
        # preprobe/write race without moving normalization into Kotlin. Notes
        # created below are deliberately absent from this immutable snapshot.
        records_snapshot = self._duplicate_records()
        script = self.create_scripts.pop(0) if self.create_scripts else None
        statuses, partial_error = (
            script
            if script is not None
            else (
                [
                    (
                        "duplicate"
                        if self._snapshot_candidate_ids(
                            note["duplicateCandidate"],
                            request["duplicateScope"],
                            records_snapshot,
                        )
                        - baseline["ids"][
                            (
                                note["duplicateCandidate"]["key"],
                                note["duplicateCandidate"]["firstField"],
                            )
                        ]
                        else "created"
                    )
                    for note in request["notes"]
                ],
                None,
            )
        )
        assert len(statuses) == len(request["notes"])
        results: list[dict[str, object]] = []
        created_rows: list[tuple[int, str]] = []
        for note, status in zip(request["notes"], statuses, strict=True):
            result: dict[str, object] = {
                "clientNoteId": note["clientNoteId"],
                "status": status,
            }
            if status in {"created", "committedFailed", "uncertain"}:
                note_id = self.next_note_id
                if status != "uncertain":
                    result["noteId"] = note_id
                self.next_note_id += 1
                created_rows.append(
                    (note_id, note["duplicateCandidate"]["firstField"])
                )
            results.append(result)
        for note_id, first_field in created_rows:
            index = len(self.duplicate_fields)
            self.duplicate_fields.append(first_field)
            self.duplicate_note_ids[index] = note_id
            self.duplicate_decks.setdefault(first_field, set()).add(
                request["deckName"]
            )
        return encode_message(
            "anki.createnotes.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "results": results,
                "error": partial_error,
            },
        )

    def requests_for(self, method: str) -> list[dict[str, Any]]:
        return [envelope for name, envelope in self.requests if name == method]


def _config(home: Path, **changes: object) -> Any:
    from anki_miner.config import AnkiMinerConfig

    base = AnkiMinerConfig(dicts_root=home / "dicts")
    return replace(base, **changes)


def _adapter(
    config: Any,
    kotlin: FakeKotlinAnki,
    cancellation_check: Any = None,
) -> AndroidAnkiAdapter:
    return AndroidAnkiAdapter(
        config,
        AndroidAnkiCallbacks(kotlin, RUN_ID),
        cancellation_check=cancellation_check,
    )


def _card(surface: str, *, definition: str = "definition", media: Any = None) -> Any:
    from anki_miner.models import CardPayload, MediaData, TokenizedWord

    word = TokenizedWord(
        surface=surface,
        lemma=surface,
        reading="よみ",
        sentence=f"{surface}だ",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        expression_furigana=f"{surface}[よみ]",
        expression_reading="よみ",
        sentence_furigana=f"{surface}[よみ]だ",
        sentence_reading="よみだ",
        pos="名詞",
    )
    return CardPayload(
        word=word,
        media=media or MediaData(),
        definition=definition,
    )


def test_verify_target_sends_only_nonempty_fields_and_active_marker(
    initialized_bridge_home: Path,
) -> None:
    config = _config(
        initialized_bridge_home,
        card_type="click",
        anki_fields={
            "word": "Expression",
            "sentence": "Sentence",
            "definition": "Definition",
            "picture": "Picture",
            "audio": "Audio",
            "expression_furigana": "ExpressionFurigana",
            "sentence_furigana": "SentenceFurigana",
            "glossary": "",
        },
    )
    kotlin = FakeKotlinAnki()

    _adapter(config, kotlin).verify_card_target()

    payload = kotlin.requests_for("ankiVerifyTarget")[0]["payload"]
    assert payload["requiredFields"] == sorted(
        {
            "Expression",
            "Sentence",
            "Definition",
            "Picture",
            "Audio",
            "ExpressionFurigana",
            "SentenceFurigana",
            "IsClickCard",
        }
    )


def test_verify_target_maps_setup_and_provider_failures(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError, SetupError

    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (
        "note_type_not_found",
        "missing model",
        False,
    )
    with pytest.raises(SetupError, match="missing model"):
        _adapter(config, kotlin).verify_card_target()

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (
        "provider_unavailable",
        "AnkiDroid unavailable",
        True,
    )
    with pytest.raises(AnkiConnectionError, match="AnkiDroid unavailable"):
        _adapter(config, kotlin).verify_card_target()


def test_verify_target_defensively_checks_returned_field_ordering_set(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import SetupError

    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.verify_fields = ["Expression"]

    with pytest.raises(SetupError, match=r"Field\(s\)"):
        _adapter(config, kotlin).verify_card_target()


def test_blank_required_and_active_marker_mappings_match_desktop_builder(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.services.anki_note_builder import build_note

    base = _config(initialized_bridge_home)
    fields = {**base.anki_fields, "word": ""}
    markers = {**base.card_type_marker_fields, "click": ""}
    config = replace(
        base,
        anki_fields=fields,
        card_type="click",
        card_type_marker_fields=markers,
    )
    card = _card("猫")
    kotlin = FakeKotlinAnki()
    required_fields = {value for value in config.anki_fields.values() if value}
    kotlin.verify_fields = [
        "Sentence",
        *sorted(required_fields - {"Sentence"}),
    ]
    adapter = _adapter(config, kotlin)

    adapter.verify_card_target()
    assert adapter.create_cards_batch([card]) == 1

    verify = kotlin.requests_for("ankiVerifyTarget")[0]["payload"]
    assert "Expression" not in verify["requiredFields"]
    assert "IsClickCard" not in verify["requiredFields"]
    created = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    desktop_built = build_note(card, config, set()).note
    assert created["fields"] == desktop_built["fields"]
    assert "Expression" not in created["fields"]
    assert "IsClickCard" not in created["fields"]
    duplicate_scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert duplicate_scope["candidates"] == [{"key": "猫だ", "firstField": "猫だ"}]


def test_all_blank_mappings_are_valid_for_target_preflight(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={key: "" for key in base.anki_fields},
        card_type="click",
        card_type_marker_fields={key: "" for key in base.card_type_marker_fields},
    )
    kotlin = FakeKotlinAnki()

    _adapter(config, kotlin).verify_card_target()

    assert kotlin.requests_for("ankiVerifyTarget")[0]["payload"]["requiredFields"] == []


def test_known_vocabulary_is_normalized_filtered_and_cached(
    initialized_bridge_home: Path,
) -> None:
    config = _config(
        initialized_bridge_home, excluded_decks=("Ignored", "Ignored::Child")
    )
    kotlin = FakeKotlinAnki()
    kotlin.known_fields = [
        " <b>猫</b>&nbsp; ",
        "[sound:x.mp3]犬",
        "plain English",
        "猫",
    ]
    adapter = _adapter(config, kotlin)

    first = adapter.get_existing_vocabulary()
    kotlin.known_fields = ["鳥"]
    second = adapter.get_existing_vocabulary()

    assert first == {"猫", "犬"}
    assert second is first
    requests = kotlin.requests_for("ankiScanFirstFields")
    assert len(requests) == 1
    assert requests[0]["payload"]["scope"] == {
        "kind": "knownVocabulary",
        "excludedDecks": ["Ignored", "Ignored::Child"],
        "cursor": None,
        "limits": _KNOWN_VOCABULARY_LIMITS,
    }


def test_known_vocabulary_scan_uses_monotonic_bounded_pages(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.known_fields = [f"語{index}" for index in range(513)]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.get_existing_vocabulary() == set(kotlin.known_fields)

    scopes = [
        request["payload"]["scope"]
        for request in kotlin.requests_for("ankiScanFirstFields")
    ]
    assert scopes[0]["cursor"] is None
    assert [scope["cursor"]["ordinal"] for scope in scopes[1:]] == [1, 2]
    cursor_tokens = [scope["cursor"]["token"] for scope in scopes[1:]]
    assert len(set(cursor_tokens)) == 2
    assert all(token.startswith("known_cursor_") for token in cursor_tokens)
    assert kotlin._known_cursors == {}
    assert all(scope["limits"] == _KNOWN_VOCABULARY_LIMITS for scope in scopes)


@pytest.mark.parametrize(
    ("total_notes", "force_continuation", "expect_error"),
    [
        (_MAX_KNOWN_VOCABULARY_SCANNED_NOTES, False, False),
        (_MAX_KNOWN_VOCABULARY_SCANNED_NOTES, True, True),
        (_MAX_KNOWN_VOCABULARY_SCANNED_NOTES + 1, False, True),
    ],
    ids=["exact-terminal", "exact-with-cursor", "over-ceiling"],
)
def test_known_vocabulary_total_scan_ceiling_is_defensively_enforced(
    total_notes: int,
    force_continuation: bool,
    expect_error: bool,
    initialized_bridge_home: Path,
) -> None:
    class TotalScanKotlin(FakeKotlinAnki):
        def __init__(self) -> None:
            super().__init__()
            self.scanned = 0
            self.page = 0

        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            cursor = request["scope"]["cursor"]
            assert cursor is None if self.page == 0 else cursor["ordinal"] == self.page
            page_size = min(256, total_notes - self.scanned)
            self.scanned += page_size
            self.page += 1
            has_more = self.scanned < total_notes or (
                force_continuation
                and self.scanned == _MAX_KNOWN_VOCABULARY_SCANNED_NOTES
            )
            next_cursor = (
                {
                    "ordinal": self.page,
                    "token": f"total-page-{self.page}",
                }
                if has_more
                else None
            )
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "firstFields": [],
                    "scannedNotes": page_size,
                    "nextCursor": next_cursor,
                },
            )

    kotlin = TotalScanKotlin()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    if expect_error:
        with pytest.raises(BridgeProtocolError) as exc_info:
            adapter.get_existing_vocabulary()
        assert exc_info.value.code == "invalid_anki_response"
    else:
        assert adapter.get_existing_vocabulary() == set()
    assert kotlin.scanned >= _MAX_KNOWN_VOCABULARY_SCANNED_NOTES


def test_known_vocabulary_excludes_parent_descendants_and_whole_mixed_note(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["親", "子", "混在", "採用"]
    kotlin.known_note_decks = [
        {"Ignored"},
        {"Ignored::Child"},
        {"Included", "Ignored::Grandchild"},
        {"Included"},
    ]
    adapter = _adapter(
        _config(initialized_bridge_home, excluded_decks=("Ignored",)), kotlin
    )

    assert adapter.get_existing_vocabulary() == {"採用"}


def test_known_vocabulary_later_page_timeout_discards_partial_scan_and_retries(
    initialized_bridge_home: Path,
) -> None:
    class TimeoutSecondPage(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            cursor = request["scope"]["cursor"]
            if cursor is not None:
                return encode_message(
                    "anki.error",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "operation": "scanFirstFields",
                        "code": "timeout",
                        "message": "slow second page",
                        "retryable": True,
                    },
                )
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "firstFields": ["猫"],
                    "scannedNotes": 1,
                    "nextCursor": {"ordinal": 1, "token": "page-2"},
                },
            )

    kotlin = TimeoutSecondPage()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.get_existing_vocabulary() == set()
    assert adapter.get_existing_vocabulary() == set()
    assert len(kotlin.requests_for("ankiScanFirstFields")) == 4


@pytest.mark.parametrize(
    ("first_fields", "scanned_notes", "next_cursor"),
    [
        (["猫"] * 257, 256, None),
        (["界" * 21_846], 1, None),
        (["猫" * 20_000] * 5, 5, None),
        (["猫"], 0, None),
        (["猫"], 1, {"ordinal": 2, "token": "skipped-page"}),
        (["猫"], 1, {"ordinal": 1, "token": "界" * 342}),
    ],
    ids=[
        "item-count",
        "item-utf8",
        "page-utf8",
        "scanned-count",
        "cursor-ordinal",
        "cursor-utf8",
    ],
)
def test_known_vocabulary_response_enforces_page_and_cursor_bounds(
    initialized_bridge_home: Path,
    first_fields: list[str],
    scanned_notes: int,
    next_cursor: dict[str, object] | None,
) -> None:
    class InvalidPageKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "firstFields": first_fields,
                    "scannedNotes": scanned_notes,
                    "nextCursor": next_cursor,
                },
            )

    kotlin = InvalidPageKotlin()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).get_existing_vocabulary()

    assert exc_info.value.code == "invalid_anki_response"


def test_known_vocabulary_response_rejects_reused_opaque_cursor_token(
    initialized_bridge_home: Path,
) -> None:
    class ReusedCursorKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            cursor = request["scope"]["cursor"]
            ordinal = 1 if cursor is None else cursor["ordinal"] + 1
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "firstFields": ["猫"],
                    "scannedNotes": 1,
                    "nextCursor": {"ordinal": ordinal, "token": "same-token"},
                },
            )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(
            _config(initialized_bridge_home), ReusedCursorKotlin()
        ).get_existing_vocabulary()

    assert exc_info.value.code == "invalid_anki_response"


def test_retryable_vocab_timeout_degrades_without_poisoning_cache(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.errors["scanFirstFields"] = ("timeout", "slow query", True)
    adapter = _adapter(config, kotlin)

    assert adapter.get_existing_vocabulary() == set()
    assert adapter.get_existing_vocabulary() == set()
    assert len(kotlin.requests_for("ankiScanFirstFields")) == 2


def test_nonretryable_vocab_query_failure_is_hard(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.errors["scanFirstFields"] = ("query_failed", "bad cursor", False)
    with pytest.raises(AnkiConnectionError, match="bad cursor"):
        _adapter(_config(initialized_bridge_home), kotlin).get_existing_vocabulary()


def test_note_building_dedup_and_first_occurrence_semantics_are_python_owned(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, anki_tags="mine mobile")
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["<b>既存</b>"]
    adapter = _adapter(config, kotlin)

    created = adapter.create_cards_batch([_card("既存"), _card("猫"), _card("猫")])

    assert created == 1
    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 2
    create_payload = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert len(create_payload["notes"]) == 1
    assert create_payload["notes"][0]["fields"]["Expression"] == "猫"
    assert create_payload["notes"][0]["fields"]["Sentence"] == "猫だ"
    assert create_payload["notes"][0]["tags"] == ["mine", "mobile"]
    assert create_payload["duplicateScope"] == {
        "kind": "collection",
        "limits": _CREATE_SNAPSHOT_LIMITS,
    }
    assert create_payload["notes"][0]["duplicateCandidate"] == {
        "key": "猫",
        "firstField": "猫",
        "occurrence": 1,
    }
    assert create_payload["baselineToken"].startswith("baseline_")
    duplicate_scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert duplicate_scope["candidates"][0] == {
        "key": "既存",
        "firstField": "既存",
    }


def test_duplicate_fake_requires_pinned_checksum_match_before_normalized_match(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = [" 猫 "]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch([_card("猫")]) == 1
    assert adapter.last_skipped_duplicates == 0


def test_duplicate_probe_sends_exact_markup_first_field_for_checksum(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["&lt;b&gt;猫&lt;/b&gt;"]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    created = adapter.create_cards_batch([_card("<b>猫</b>")])
    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert created == 0
    assert scope["candidates"] == [
        {"key": "<b>猫</b>", "firstField": "&lt;b&gt;猫&lt;/b&gt;"}
    ]


def test_python_normalizes_bounded_raw_duplicate_hits(
    initialized_bridge_home: Path,
) -> None:
    class RawHitKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            raw_values = [
                "<b>猫</b>",
                "[sound:clip.opus]  犬 ",
                "&#38;狐",
                "は\u3099",
                "\t鳥  ",
            ]
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "rawFirstFieldHits": [
                        [{"noteId": 20_000 + index, "firstField": value}]
                        for index, value in enumerate(raw_values)
                    ],
                    "baselineToken": "baseline_" + "f" * 32,
                },
            )

    kotlin = RawHitKotlin()
    cards = [
        _card("猫"),
        _card("犬"),
        _card("&狐"),
        _card("は\u3099"),
        _card("  鳥\n"),
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch(cards) == 0
    assert adapter.last_skipped_duplicates == len(cards)
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    "raw_hit_buckets",
    [
        [
            [
                {"noteId": 30_000 + index, "firstField": f"猫{index}"}
                for index in range(101)
            ]
        ],
        [[{"noteId": 40_000, "firstField": "界" * 21_846}]],
    ],
)
def test_duplicate_raw_hit_response_enforces_item_and_utf8_limits(
    initialized_bridge_home: Path,
    raw_hit_buckets: list[list[dict[str, object]]],
) -> None:
    class OversizedRawHitKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "rawFirstFieldHits": raw_hit_buckets,
                    "baselineToken": "baseline_" + "f" * 32,
                },
            )

    kotlin = OversizedRawHitKotlin()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫")]
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_duplicate_raw_hit_response_enforces_aggregate_hit_limit(
    initialized_bridge_home: Path,
) -> None:
    class TooManyRawHitsKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "rawFirstFieldHits": [
                        [
                            {
                                "noteId": 50_000 + bucket * 100 + index,
                                "firstField": f"語{bucket}-{index}",
                            }
                            for index in range(100)
                        ]
                        for bucket in range(11)
                    ],
                    "baselineToken": "baseline_" + "f" * 32,
                },
            )

    kotlin = TooManyRawHitsKotlin()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card(f"語{index}") for index in range(11)]
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_duplicate_raw_hit_response_enforces_aggregate_utf8_limit(
    initialized_bridge_home: Path,
) -> None:
    class TooManyRawBytesKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "rawFirstFieldHits": [
                        [
                            {
                                "noteId": 60_000 + index,
                                "firstField": f"{index}" + "界" * 21_840,
                            }
                            for index in range(17)
                        ]
                    ],
                    "baselineToken": "baseline_" + "f" * 32,
                },
            )

    kotlin = TooManyRawBytesKotlin()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫")]
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_allow_duplicate_cards_scopes_probe_to_target_deck(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()

    _adapter(config, kotlin).create_cards_batch([_card("猫")])

    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scope == {
        "kind": "duplicates",
        "modelName": config.anki_note_type,
        "firstFieldName": "Expression",
        "deckName": config.anki_deck_name,
        "candidates": [{"key": "猫", "firstField": "猫"}],
        "occurrences": [0],
        "limits": _DUPLICATE_LIMITS,
    }
    create_payload = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert create_payload["duplicateScope"] == {
        "kind": "exactDeck",
        "deckName": config.anki_deck_name,
        "includeChildren": False,
        "limits": _CREATE_SNAPSHOT_LIMITS,
    }


def test_create_race_baseline_preserves_preprobe_checksum_collision(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    # AnkiDroid's checksum keeps an img src while Python strips the entire tag:
    # same provider checksum, deliberately different Python duplicate identity.
    kotlin.duplicate_fields = ['<img src="x">猫']
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch([_card(" x 猫")]) == 1
    create_note = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    assert create_note["duplicateCandidate"] == {
        "key": "x 猫",
        "firstField": " x 猫",
        "occurrence": 0,
    }
    assert "baselineNoteIds" not in create_note["duplicateCandidate"]


def test_create_baseline_limit_counts_unique_ids_across_repeated_candidates(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    collision = '<img src="x">猫'
    kotlin.duplicate_fields = [collision] * 100
    kotlin.duplicate_decks = {collision: {config.anki_deck_name}}
    adapter = _adapter(config, kotlin)

    assert adapter.create_cards_batch([_card(" x 猫")] * 100) == 100
    notes = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert len(notes) == 100
    assert [note["duplicateCandidate"]["occurrence"] for note in notes] == list(
        range(100)
    )
    assert all("baselineNoteIds" not in note["duplicateCandidate"] for note in notes)
    scan_scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scan_scope["occurrences"] == [0] * 100


def test_empty_normalized_first_field_fails_before_provider_callbacks(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card(" \t\n ")]
        )

    assert exc_info.value.code == "invalid_note"
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    ("allow_duplicate_cards", "external_deck", "expected_created"),
    [
        (False, "Japanese::Other", 0),
        (True, "__target__", 0),
        (True, "Japanese::Other", 1),
    ],
)
def test_create_snapshot_rejects_only_new_ids_in_configured_scope(
    initialized_bridge_home: Path,
    allow_duplicate_cards: bool,
    external_deck: str,
    expected_created: int,
) -> None:
    class RacingKotlin(FakeKotlinAnki):
        def ankiCreateNotes(self, raw: str) -> str:
            self.duplicate_fields.append("<b>猫</b>")
            self.duplicate_decks.setdefault("<b>猫</b>", set()).add(external_deck)
            return super().ankiCreateNotes(raw)

    config = _config(
        initialized_bridge_home,
        allow_duplicate_cards=allow_duplicate_cards,
    )
    if external_deck == "__target__":
        external_deck = config.anki_deck_name
    kotlin = RacingKotlin()
    adapter = _adapter(config, kotlin)

    assert adapter.create_cards_batch([_card("猫")]) == expected_created
    assert adapter.last_skipped_duplicates == 1 - expected_created
    request = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert request["notes"][0]["duplicateCandidate"]["occurrence"] == 0
    assert request["baselineToken"].startswith("baseline_")


def test_allow_duplicate_cards_filters_checksum_hits_to_target_deck(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    other_deck_note = "<b>猫</b>"
    target_deck_note = "<b>犬</b>"
    kotlin.duplicate_fields = [other_deck_note, target_deck_note]
    kotlin.duplicate_decks = {
        other_deck_note: {"Japanese::Other"},
        target_deck_note: {config.anki_deck_name},
    }
    adapter = _adapter(config, kotlin)

    assert adapter.create_cards_batch([_card("猫"), _card("犬")]) == 1
    assert adapter.last_skipped_duplicates == 1
    notes = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert [note["fields"]["Expression"] for note in notes] == ["猫"]


@pytest.mark.parametrize(
    ("allow_duplicate_cards", "expected_created", "expected_skipped"),
    [(False, 1, 2), (True, 3, 0)],
)
def test_repeated_expressions_in_one_batch_follow_desktop_duplicate_mode(
    initialized_bridge_home: Path,
    allow_duplicate_cards: bool,
    expected_created: int,
    expected_skipped: int,
) -> None:
    config = _config(
        initialized_bridge_home,
        allow_duplicate_cards=allow_duplicate_cards,
    )
    kotlin = FakeKotlinAnki()
    adapter = _adapter(config, kotlin)

    created = adapter.create_cards_batch([_card("猫"), _card("猫"), _card("猫")])

    assert created == expected_created
    assert adapter.last_skipped_duplicates == expected_skipped
    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scope["candidates"] == [{"key": "猫", "firstField": "猫"}]
    notes = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert len(notes) == expected_created
    if allow_duplicate_cards:
        assert [note["duplicateCandidate"]["occurrence"] for note in notes] == [
            0,
            1,
            2,
        ]
        assert scope["occurrences"] == [0, 0, 0]


def test_allow_duplicate_cards_fans_existing_target_hit_to_repeated_candidates(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["猫"]
    kotlin.duplicate_decks = {"猫": {config.anki_deck_name}}
    adapter = _adapter(config, kotlin)

    assert adapter.create_cards_batch([_card("猫"), _card("猫")]) == 0
    assert adapter.last_skipped_duplicates == 2
    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scope["candidates"] == [{"key": "猫", "firstField": "猫"}]
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize("allow_duplicate_cards", [False, True])
def test_baseline_token_binds_ordered_occurrences_in_both_duplicate_scopes(
    allow_duplicate_cards: bool,
    initialized_bridge_home: Path,
) -> None:
    config = _config(
        initialized_bridge_home, allow_duplicate_cards=allow_duplicate_cards
    )
    kotlin = FakeKotlinAnki()

    assert _adapter(config, kotlin).create_cards_batch([_card("猫"), _card("犬")]) == 2

    scan = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    create = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert scan["occurrences"] == [0, 1]
    assert [
        note["duplicateCandidate"]["occurrence"] for note in create["notes"]
    ] == [0, 1]
    assert create["baselineToken"].startswith("baseline_")
    assert create["baselineToken"] not in kotlin._baseline_snapshots
    assert scan["deckName"] == (
        config.anki_deck_name if allow_duplicate_cards else None
    )


@pytest.mark.parametrize("allow_duplicate_cards", [False, True])
def test_reused_provider_baseline_token_is_rejected_before_second_write(
    allow_duplicate_cards: bool,
    initialized_bridge_home: Path,
) -> None:
    class ReusedTokenKotlin(FakeKotlinAnki):
        def __init__(self) -> None:
            super().__init__()
            self.first_token: str | None = None

        def ankiScanFirstFields(self, raw: str) -> str:
            response = super().ankiScanFirstFields(raw)
            envelope = json.loads(response)
            payload = envelope["payload"]
            token = payload["baselineToken"]
            if self.first_token is None:
                self.first_token = token
            else:
                payload["baselineToken"] = self.first_token
            return encode_message(envelope["type"], payload)

    config = _config(
        initialized_bridge_home, allow_duplicate_cards=allow_duplicate_cards
    )
    kotlin = ReusedTokenKotlin()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch(
            [_card(f"語{index}") for index in range(101)]
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert len(kotlin.requests_for("ankiCreateNotes")) == 1


def test_duplicate_probe_fans_matches_by_exact_first_field_candidate(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = [" 猫 "]
    kotlin.duplicate_decks = {" 猫 ": {config.anki_deck_name}}
    adapter = _adapter(config, kotlin)

    assert adapter.create_cards_batch([_card("猫"), _card(" 猫 "), _card("猫")]) == 2
    assert adapter.last_skipped_duplicates == 1
    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scope["candidates"] == [
        {"key": "猫", "firstField": "猫"},
        {"key": "猫", "firstField": " 猫 "},
    ]
    notes = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert [note["fields"]["Expression"] for note in notes] == ["猫", "猫"]


def test_create_notes_batches_at_100_and_reports_cumulative_progress(
    initialized_bridge_home: Path,
) -> None:
    class Progress:
        def __init__(self) -> None:
            self.events: list[tuple[object, ...]] = []

        def on_start(self, total: int, description: str) -> None:
            self.events.append(("start", total, description))

        def on_progress(self, current: int, description: str) -> None:
            self.events.append(("progress", current, description))

        def on_complete(self) -> None:
            self.events.append(("complete",))

    kotlin = FakeKotlinAnki()
    progress = Progress()
    cards = [_card(f"語{index}") for index in range(101)]

    created = _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
        cards, progress
    )

    assert created == 101
    assert [
        len(request["payload"]["notes"])
        for request in kotlin.requests_for("ankiCreateNotes")
    ] == [100, 1]
    duplicate_scopes = [
        request["payload"]["scope"]
        for request in kotlin.requests_for("ankiScanFirstFields")
    ]
    assert [len(scope["candidates"]) for scope in duplicate_scopes] == [100, 1]
    assert duplicate_scopes[0]["candidates"] == [
        {"key": f"語{index}", "firstField": f"語{index}"} for index in range(100)
    ]
    assert duplicate_scopes[1]["candidates"] == [
        {"key": "語100", "firstField": "語100"}
    ]
    assert progress.events == [
        ("start", 101, "Creating Anki cards"),
        ("progress", 100, "Cards created: 100/101"),
        ("progress", 101, "Cards created: 101/101"),
        ("complete",),
    ]


def test_duplicate_identity_uses_verified_model_first_field(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home)
    required = {value for value in config.anki_fields.values() if value}
    kotlin = FakeKotlinAnki()
    kotlin.verify_fields = ["Sentence", *sorted(required - {"Sentence"})]

    assert _adapter(config, kotlin).create_cards_batch([_card("猫")]) == 1

    scan = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    create = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert scan["firstFieldName"] == "Sentence"
    assert scan["candidates"] == [{"key": "猫だ", "firstField": "猫だ"}]
    assert create["firstFieldName"] == "Sentence"
    assert create["notes"][0]["duplicateCandidate"] == {
        "key": "猫だ",
        "firstField": "猫だ",
        "occurrence": 0,
    }


def test_missing_verified_model_first_field_fails_before_duplicate_probe(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    config = replace(base, anki_fields={**base.anki_fields, "word": ""})
    required = {value for value in config.anki_fields.values() if value}
    kotlin = FakeKotlinAnki()
    kotlin.verify_fields = ["Expression", *sorted(required)]

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch([_card("猫")])

    assert exc_info.value.code == "invalid_note"
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    ("exact", "oversized"),
    [
        ("x" * _MAX_FIELD_VALUE_UTF8_BYTES, "x" * (_MAX_FIELD_VALUE_UTF8_BYTES + 1)),
        (
            "界" * (_MAX_FIELD_VALUE_UTF8_BYTES // 3),
            "界" * (_MAX_FIELD_VALUE_UTF8_BYTES // 3 + 1),
        ),
    ],
    ids=["ascii", "multibyte"],
)
def test_note_field_value_limit_uses_exact_utf8_bytes(
    exact: str,
    oversized: str,
    initialized_bridge_home: Path,
) -> None:
    exact_kotlin = FakeKotlinAnki()
    assert (
        _adapter(_config(initialized_bridge_home), exact_kotlin).create_cards_batch(
            [_card("猫", definition=exact)]
        )
        == 1
    )

    oversized_kotlin = FakeKotlinAnki()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(
            _config(initialized_bridge_home), oversized_kotlin
        ).create_cards_batch([_card("猫", definition=oversized)])

    assert exc_info.value.code == "note_too_large"
    assert not oversized_kotlin.requests_for("ankiScanFirstFields")
    assert not oversized_kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    "tags",
    [
        ["x" * (_MAX_TAG_UTF8_BYTES + 1)],
        [f"t{index}" for index in range(_MAX_NOTE_TAGS + 1)],
        ["x" * 129 for _ in range(_MAX_NOTE_TAGS)],
    ],
    ids=["single-tag", "tag-count", "tag-total-bytes"],
)
def test_note_tag_limits_fail_before_duplicate_probe(
    tags: list[str], initialized_bridge_home: Path
) -> None:
    kotlin = FakeKotlinAnki()
    config = _config(initialized_bridge_home, anki_tags=" ".join(tags))

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch([_card("猫")])

    assert exc_info.value.code == "note_too_large"
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_exact_tag_count_and_aggregate_utf8_boundary_is_accepted(
    initialized_bridge_home: Path,
) -> None:
    tags = ["x" * 128 for _ in range(_MAX_NOTE_TAGS)]
    kotlin = FakeKotlinAnki()
    config = _config(initialized_bridge_home, anki_tags=" ".join(tags))

    assert _adapter(config, kotlin).create_cards_batch([_card("猫")]) == 1
    request_tags = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0][
        "tags"
    ]
    assert len(request_tags) == _MAX_NOTE_TAGS
    assert sum(len(tag.encode("utf-8")) for tag in request_tags) == (
        _MAX_NOTE_TAGS_UTF8_BYTES
    )


def test_json_escaping_drives_exact_envelope_batching(
    initialized_bridge_home: Path,
) -> None:
    cards = [
        _card(f"語{index}", definition="\\" * 50_000) for index in range(6)
    ]
    kotlin = FakeKotlinAnki()

    assert _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards) == 6

    requests = kotlin.requests_for("ankiCreateNotes")
    assert len(requests) > 1
    assert all(
        len(encode_message(request["type"], request["payload"]).encode("utf-8"))
        <= _MAX_CREATE_ENVELOPE_UTF8_BYTES
        for request in requests
    )


def test_multibyte_content_drives_aggregate_batching(
    initialized_bridge_home: Path,
) -> None:
    cards = [_card(f"語{index}", definition="界" * 20_000) for index in range(7)]
    kotlin = FakeKotlinAnki()

    assert _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards) == 7
    assert [
        len(request["payload"]["notes"])
        for request in kotlin.requests_for("ankiCreateNotes")
    ] == [6, 1]


def test_repeated_identity_group_that_exceeds_byte_budget_fails_atomically(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch(
            [_card("猫", definition="\\" * 50_000) for _ in range(10)]
        )

    assert exc_info.value.code == "note_batch_too_large"
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_duplicate_lookup_is_candidate_bounded_across_large_batches(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["<b>語0</b>", "語100", "語204"]
    cards = [_card(f"語{index}", definition="x" * 20_000) for index in range(205)]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch(cards) == 202
    assert adapter.last_skipped_duplicates == 3

    scopes = [
        request["payload"]["scope"]
        for request in kotlin.requests_for("ankiScanFirstFields")
    ]
    assert all(len(scope["candidates"]) <= 100 for scope in scopes)
    assert [candidate for scope in scopes for candidate in scope["candidates"]] == [
        {"key": f"語{index}", "firstField": f"語{index}"}
        for index in range(205)
    ]
    assert all("definition" not in scope for scope in scopes)
    create_requests = kotlin.requests_for("ankiCreateNotes")
    assert sum(len(request["payload"]["notes"]) for request in create_requests) == 202
    assert all(
        len(json.dumps(request, ensure_ascii=False).encode("utf-8"))
        <= _MAX_CREATE_ENVELOPE_UTF8_BYTES
        for request in create_requests
    )


def test_duplicate_candidate_key_size_is_bounded_before_callback(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    oversized = "語" * 4097

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card(oversized)]
        )

    assert exc_info.value.code == "invalid_note"
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_duplicate_lookup_rejects_misaligned_sparse_array_semantics(
    initialized_bridge_home: Path,
) -> None:
    class UnrelatedDuplicateKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "rawFirstFieldHits": [[], []],
                    "baselineToken": "baseline_" + "f" * 32,
                },
            )

    kotlin = UnrelatedDuplicateKotlin()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫")]
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_first_occurrence_wins_across_batch_boundary_when_duplicates_disallowed(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    cards = [_card(f"語{index}") for index in range(100)] + [_card("語0")]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch(cards) == 100
    assert adapter.last_skipped_duplicates == 1
    assert [
        len(request["payload"]["notes"])
        for request in kotlin.requests_for("ankiCreateNotes")
    ] == [100]
    assert [
        len(request["payload"]["scope"]["candidates"])
        for request in kotlin.requests_for("ankiScanFirstFields")
    ] == [100]


def test_repeated_identity_spanning_hard_batch_boundary_fails_before_probe(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    cards = [_card(f"語{index}") for index in range(100)] + [_card("語0")]
    adapter = _adapter(config, kotlin)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(cards)

    assert exc_info.value.code == "note_batch_too_large"
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_card_media_is_content_addressed_deduped_and_actual_name_propagates(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    image = tmp_path / "source.webp"
    image.write_bytes(b"same image")
    missing = tmp_path / "missing.opus"
    media_a = MediaData(screenshot_path=image, screenshot_filename="shot.webp")
    media_b = MediaData(
        screenshot_path=image,
        screenshot_filename="shot.webp",
        audio_path=missing,
        audio_filename="gone.opus",
    )
    requested = _content_addressed_name("shot.webp", b"same image")
    preferred = Path(requested).stem
    kotlin = FakeKotlinAnki()
    provider_name = f"{Path(preferred).stem}_provider.webp"
    kotlin.media_renames[preferred] = provider_name
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    created = adapter.create_cards_batch(
        [_card("猫", media=media_a), _card("犬", media=media_b)]
    )

    assert created == 2
    assert adapter.last_media_store_failures == 1
    assert media_a.screenshot_filename == provider_name
    assert media_b.screenshot_filename == provider_name
    store_payload = kotlin.requests_for("ankiStoreMedia")[0]["payload"]
    assert len(store_payload["assets"]) == 1
    assert store_payload["assets"][0]["preferredName"] == preferred
    assert store_payload["assets"][0]["requestedFilename"] == requested
    assert store_payload["assets"][0]["sourcePath"] == str(image.resolve())
    assert store_payload["assets"][0]["expectedSizeBytes"] == len(b"same image")
    assert store_payload["assets"][0]["expectedSha256"] == hashlib.sha256(
        b"same image"
    ).hexdigest()
    note_fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert all(
        note["fields"]["Picture"] == f'<img src="{provider_name}">'
        for note in note_fields
    )


def test_card_media_hashing_never_uses_unbounded_read_bytes(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "streamed.opus"
    content = b"audio" * (_MEDIA_HASH_CHUNK_BYTES // 5 + 3)
    audio.write_bytes(content)

    def forbid_read_bytes(_path: Path) -> bytes:
        raise AssertionError("card media must be streamed")

    monkeypatch.setattr(Path, "read_bytes", forbid_read_bytes)
    kotlin = FakeKotlinAnki()

    assert (
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [
                _card(
                    "猫",
                    media=MediaData(
                        audio_path=audio,
                        audio_filename="streamed.opus",
                    ),
                )
            ]
        )
        == 1
    )
    requested = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"][0][
        "requestedFilename"
    ]
    expected_digest = hashlib.sha1(content).hexdigest()[:12]
    assert requested == f"streamed_{expected_digest}.opus"


def test_oversized_card_media_fails_from_stat_before_open_or_callback(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "oversized.opus"
    with audio.open("wb") as output:
        output.truncate(_MAX_CARD_MEDIA_BYTES + 1)
    real_open = Path.open

    def guarded_open(path: Path, *args: object, **kwargs: object) -> Any:
        if path == audio and args and args[0] == "rb":
            raise AssertionError("oversized media must be rejected before reading")
        return real_open(path, *args, **kwargs)

    monkeypatch.setattr(Path, "open", guarded_open)
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [
                _card(
                    "猫",
                    media=MediaData(
                        audio_path=audio,
                        audio_filename="oversized.opus",
                    ),
                )
            ]
        )

    assert exc_info.value.code == "media_too_large"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_card_media_hashing_cancellation_stops_before_kotlin_callback(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "cancel-hash.opus"
    content = b"x" * (_MEDIA_HASH_CHUNK_BYTES * 3)
    audio.write_bytes(content)
    real_open = Path.open
    bytes_read = 0

    class TrackingReader:
        def __init__(self, wrapped: Any) -> None:
            self._wrapped = wrapped

        def __enter__(self) -> TrackingReader:
            self._wrapped.__enter__()
            return self

        def __exit__(self, *args: object) -> object:
            return self._wrapped.__exit__(*args)

        def fileno(self) -> int:
            return self._wrapped.fileno()

        def read(self, size: int = -1) -> bytes:
            nonlocal bytes_read
            chunk = self._wrapped.read(size)
            bytes_read += len(chunk)
            return chunk

    def tracking_open(path: Path, *args: object, **kwargs: object) -> Any:
        opened = real_open(path, *args, **kwargs)
        if path == audio and args and args[0] == "rb":
            return TrackingReader(opened)
        return opened

    monkeypatch.setattr(Path, "open", tracking_open)
    kotlin = FakeKotlinAnki()
    media = MediaData(audio_path=audio, audio_filename="cancel-hash.opus")
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: bytes_read >= _MEDIA_HASH_CHUNK_BYTES,
    )

    with pytest.raises(AnkiOperationCancelled) as exc_info:
        adapter.create_cards_batch([_card("猫", media=media)])

    assert exc_info.value.operation == "storeMedia"
    assert bytes_read == _MEDIA_HASH_CHUNK_BYTES
    assert bytes_read < len(content)
    assert media.audio_filename == "cancel-hash.opus"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_media_callbacks_are_chunked_by_count_and_cumulative_bytes() -> None:
    def asset(index: int, size: int) -> _MediaAsset:
        return _MediaAsset(
            asset_id=f"asset_{index:032x}",
            source_path=f"/tmp/media-{index}.opus",
            preferred_name=f"media_{index}",
            requested_name=f"media_{index}.opus",
            original_name=f"media_{index}.opus",
            purpose="card",
            media_kind="audio",
            expected_size_bytes=size,
            expected_sha256="0" * 64,
        )

    byte_chunks = _chunk_media_assets(
        [
            asset(1, 40 * 1024 * 1024),
            asset(2, 24 * 1024 * 1024),
            asset(3, 1),
        ]
    )
    count_chunks = _chunk_media_assets([asset(index, 0) for index in range(51)])

    assert [len(chunk) for chunk in byte_chunks] == [2, 1]
    assert [sum(item.expected_size_bytes for item in chunk) for chunk in byte_chunks] == [
        _MAX_MEDIA_CALLBACK_BYTES,
        1,
    ]
    assert [len(chunk) for chunk in count_chunks] == [50, 1]


def test_post_hash_media_mutation_is_rejected_by_snapshot_contract(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    class MutatingKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            Path(request["assets"][0]["sourcePath"]).write_bytes(b"other")
            return super().ankiStoreMedia(raw)

    audio = tmp_path / "mutable.opus"
    audio.write_bytes(b"audio")
    media = MediaData(audio_path=audio, audio_filename="mutable.opus")
    kotlin = MutatingKotlin()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media)]
        )

    assert exc_info.value.code == "invalid_request"
    assert media.audio_filename == "mutable.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_oversized_dictionary_media_fails_before_callback(
    initialized_bridge_home: Path,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "huge.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    with media_path.open("wb") as output:
        output.truncate(_MAX_MEDIA_ASSET_BYTES + 1)
    definition = '<img class="anki-miner-dict-media" src="dict__huge.png">'
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", definition=definition)]
        )

    assert exc_info.value.code == "media_too_large"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_dictionary_media_hashing_honors_cancellation(
    initialized_bridge_home: Path,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "stop.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    definition = '<img class="anki-miner-dict-media" src="dict__stop.png">'
    kotlin = FakeKotlinAnki()

    with pytest.raises(AnkiOperationCancelled):
        _adapter(
            _config(initialized_bridge_home),
            kotlin,
            cancellation_check=lambda: True,
        ).create_cards_batch([_card("猫", definition=definition)])

    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_same_logical_media_name_with_different_bytes_fails_before_provider(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    first = tmp_path / "first.opus"
    second = tmp_path / "second.opus"
    first.write_bytes(b"first")
    second.write_bytes(b"second")
    first_media = MediaData(audio_path=first, audio_filename="shared.opus")
    second_media = MediaData(audio_path=second, audio_filename="shared.opus")
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=first_media), _card("犬", media=second_media)]
        )

    assert exc_info.value.code == "media_content_collision"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")
    assert first_media.audio_filename == "shared.opus"
    assert second_media.audio_filename == "shared.opus"


def test_same_logical_media_name_with_live_and_missing_paths_fails_closed(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    live = tmp_path / "live.opus"
    live.write_bytes(b"audio")
    missing = tmp_path / "missing.opus"
    live_media = MediaData(audio_path=live, audio_filename="shared.opus")
    missing_media = MediaData(audio_path=missing, audio_filename="shared.opus")
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=live_media), _card("犬", media=missing_media)]
        )

    assert exc_info.value.code == "media_content_collision"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")
    assert live_media.audio_filename == "shared.opus"
    assert missing_media.audio_filename == "shared.opus"


def test_same_logical_media_name_with_identical_bytes_dedupes_across_paths(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    first = tmp_path / "first.opus"
    second = tmp_path / "second.opus"
    first.write_bytes(b"same")
    second.write_bytes(b"same")
    first_media = MediaData(audio_path=first, audio_filename="shared.opus")
    second_media = MediaData(audio_path=second, audio_filename="shared.opus")
    kotlin = FakeKotlinAnki()

    assert (
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=first_media), _card("犬", media=second_media)]
        )
        == 2
    )

    assets = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"]
    assert len(assets) == 1
    assert assets[0]["sourcePath"] == str(first.resolve())
    assert Path(assets[0]["preferredName"]).suffix == ""
    assert first_media.audio_filename == second_media.audio_filename


def test_media_store_is_bounded_to_fifty_assets_per_callback(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    cards = []
    media_rows = []
    for index in range(51):
        path = tmp_path / f"clip-{index}.opus"
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=f"clip-{index}.opus")
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = FakeKotlinAnki()

    assert (
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)
        == 51
    )

    requests = kotlin.requests_for("ankiStoreMedia")
    assert [len(request["payload"]["assets"]) for request in requests] == [50, 1]
    assert all(
        "requestedFilename" in asset
        for request in requests
        for asset in request["payload"]["assets"]
    )
    assert all(media.audio_filename.endswith("_provider.opus") for media in media_rows)


def test_second_media_chunk_cancellation_preserves_prior_successes(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    class CancelSecondMediaChunk(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            if len(self.requests_for("ankiStoreMedia")) == 1:
                request = self._request("ankiStoreMedia", raw)
                return encode_message(
                    "anki.error",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "operation": "storeMedia",
                        "code": "cancelled",
                        "message": "cancelled",
                        "retryable": False,
                    },
                )
            return super().ankiStoreMedia(raw)

    cards = []
    media_rows = []
    for index in range(51):
        path = tmp_path / f"cancel-{index}.opus"
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=f"cancel-{index}.opus")
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = CancelSecondMediaChunk()

    with pytest.raises(AnkiOperationCancelled):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert [
        len(request["payload"]["assets"])
        for request in kotlin.requests_for("ankiStoreMedia")
    ] == [50, 1]
    assert all(
        media.audio_filename.endswith("_provider.opus")
        for media in media_rows[:50]
    )
    assert media_rows[50].audio_filename == "cancel-50.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_partial_media_cancellation_preserves_aligned_successes(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    class PartialCancelKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            first, second = request["assets"]
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": first["assetId"],
                            "status": "stored",
                            "actualFilename": (
                                f"{first['preferredName']}_provider.opus"
                            ),
                        },
                        {
                            "assetId": second["assetId"],
                            "status": "notAttempted",
                        },
                    ],
                    "error": {
                        "code": "cancelled",
                        "message": "stopped after first snapshot",
                        "retryable": False,
                    },
                },
            )

    paths = [tmp_path / "partial-a.opus", tmp_path / "partial-b.opus"]
    for index, path in enumerate(paths):
        path.write_bytes(f"audio-{index}".encode())
    media = [
        MediaData(audio_path=path, audio_filename=path.name) for path in paths
    ]
    kotlin = PartialCancelKotlin()

    with pytest.raises(AnkiOperationCancelled):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media[0]), _card("犬", media=media[1])]
        )

    assert media[0].audio_filename.endswith("_provider.opus")
    assert media[1].audio_filename == "partial-b.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    ("statuses", "error"),
    [
        (
            ["notAttempted", "stored"],
            {"code": "cancelled", "message": "bad", "retryable": False},
        ),
        (["stored", "notAttempted"], None),
        (["stored", "stored"], {"code": "cancelled", "message": "orphan", "retryable": False}),
    ],
)
def test_partial_media_result_shape_is_strict(
    statuses: list[str],
    error: dict[str, object] | None,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    class InvalidPartialKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            rows: list[dict[str, object]] = []
            for asset, status in zip(request["assets"], statuses, strict=True):
                row: dict[str, object] = {
                    "assetId": asset["assetId"],
                    "status": status,
                }
                if status == "stored":
                    row["actualFilename"] = (
                        f"{asset['preferredName']}_provider.opus"
                    )
                rows.append(row)
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": rows,
                    "error": error,
                },
            )

    paths = [tmp_path / "strict-a.opus", tmp_path / "strict-b.opus"]
    for path in paths:
        path.write_bytes(path.name.encode())
    cards = [
        _card(
            f"語{index}",
            media=MediaData(audio_path=path, audio_filename=path.name),
        )
        for index, path in enumerate(paths)
    ]

    kotlin = InvalidPartialKotlin()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "invalid_anki_response"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_second_media_chunk_misalignment_does_not_mutate_payloads(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    class MisalignSecondMediaChunk(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            if len(self.requests_for("ankiStoreMedia")) == 1:
                request = self._request("ankiStoreMedia", raw)
                asset = request["assets"][0]
                return encode_message(
                    "anki.storemedia.result",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "results": [
                            {
                                "assetId": "asset_" + "f" * 32,
                                "status": "stored",
                                "actualFilename": (
                                    f"{asset['preferredName']}_provider.opus"
                                ),
                            }
                        ],
                        "error": None,
                    },
                )
            return super().ankiStoreMedia(raw)

    cards = []
    media_rows = []
    for index in range(51):
        path = tmp_path / f"align-{index}.opus"
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=f"align-{index}.opus")
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = MisalignSecondMediaChunk()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "mismatched_callback_response"
    assert all(
        media.audio_filename == f"align-{index}.opus"
        for index, media in enumerate(media_rows)
    )
    assert not kotlin.requests_for("ankiCreateNotes")


def test_media_name_ownership_is_enforced_across_callback_chunks(
    initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from android_bridge import anki_adapter as anki_adapter_module
    from anki_miner.models import MediaData

    monkeypatch.setattr(
        anki_adapter_module,
        "_content_addressed_name_from_digest",
        lambda filename, _digest: filename,
    )

    class CollideAcrossChunks(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            results = []
            for asset in request["assets"]:
                actual = (
                    "ab_x_provider.opus"
                    if asset["requestedFilename"] in {"ab.opus", "ab_x.opus"}
                    else f"{asset['preferredName']}_provider.opus"
                )
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "stored",
                        "actualFilename": actual,
                    }
                )
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": results,
                    "error": None,
                },
            )

    names = ["ab.opus", *[f"middle-{index}.opus" for index in range(49)], "ab_x.opus"]
    cards = []
    media_rows = []
    for index, name in enumerate(names):
        path = tmp_path / name
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=name)
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = CollideAcrossChunks()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "media_name_collision"
    assert [
        len(request["payload"]["assets"])
        for request in kotlin.requests_for("ankiStoreMedia")
    ] == [50, 1]
    assert all(media.audio_filename == name for media, name in zip(media_rows, names))
    assert not kotlin.requests_for("ankiCreateNotes")


def test_declared_media_failure_is_nonfatal_and_omits_reference(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    preferred = Path(_content_addressed_name("clip.opus", b"audio")).stem
    kotlin = FakeKotlinAnki()
    kotlin.failed_media_names.add(preferred)
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert (
        adapter.create_cards_batch(
            [
                _card(
                    "猫",
                    media=MediaData(audio_path=audio, audio_filename="clip.opus"),
                )
            ]
        )
        == 1
    )
    assert adapter.last_media_store_failures == 1
    fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["fields"]
    assert fields["SentenceAudio"] == ""


def test_dictionary_media_uses_source_name_and_success_cache(
    initialized_bridge_home: Path,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "pic.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    definition = '<img class="anki-miner-dict-media" src="dict__pic.png">'
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    adapter.create_cards_batch([_card("猫", definition=definition)])
    adapter.create_cards_batch([_card("犬", definition=definition)])

    dictionary_requests = [
        request
        for request in kotlin.requests_for("ankiStoreMedia")
        if request["payload"]["assets"][0]["purpose"] == "dictionary"
    ]
    assert len(dictionary_requests) == 1
    asset = dictionary_requests[0]["payload"]["assets"][0]
    assert asset["preferredName"] == _dictionary_provider_preferred_name(
        "dict__pic.png"
    )
    assert asset["requestedFilename"] == "dict__pic.png"
    assert asset["sourcePath"] == str(media_path.resolve())


def test_dictionary_media_preserves_quote_filename_for_direct_fallback(
    initialized_bridge_home: Path,
) -> None:
    class DirectNameMediaKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": asset["assetId"],
                            "status": "stored",
                            "actualFilename": asset["requestedFilename"],
                        }
                        for asset in request["assets"]
                    ],
                    "error": None,
                },
            )

    filename = 'dict__a";b.svg'
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / 'a";b.svg'
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"svg")
    definition = '<img class="anki-miner-dict-media" src="dict__a&quot;;b.svg">'
    kotlin = DirectNameMediaKotlin()

    assert (
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", definition=definition)]
        )
        == 1
    )

    asset = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"][0]
    assert asset["preferredName"] == _dictionary_provider_preferred_name(filename)
    assert asset["requestedFilename"] == filename
    fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["fields"]
    assert fields["MainDefinition"] == definition


def test_dictionary_media_random_names_rewrite_only_marked_html_safely(
    initialized_bridge_home: Path,
) -> None:
    media_root = initialized_bridge_home / "dicts" / "dict" / "media"
    media_root.mkdir(parents=True, exist_ok=True)
    first_path = media_root / "pic&one.png"
    second_path = media_root / "other.svg"
    first_path.write_bytes(b"png")
    second_path.write_bytes(b"svg")
    definition = (
        '<img class="anki-miner-dict-media" src="dict__pic&amp;one.png">'
        '<img class="anki-miner-dict-media" src="dict__pic&amp;one.png">'
        '<img class="ordinary" src="dict__pic&amp;one.png">'
        '<img src="dict__other.svg" class="anki-miner-dict-media">'
    )
    glossary = (
        '<span><img class="anki-miner-dict-media" src="dict__pic&amp;one.png"></span>'
    )
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "glossary": "Glossary"},
    )
    first_card = replace(
        _card("猫", definition=definition), extra_fields={"glossary": glossary}
    )
    kotlin = FakeKotlinAnki()
    first_preferred = _dictionary_provider_preferred_name("dict__pic&one.png")
    second_preferred = _dictionary_provider_preferred_name("dict__other.svg")
    kotlin.media_renames = {
        first_preferred: f"{first_preferred}_random.jpeg",
        second_preferred: f"{second_preferred}_random.webp",
    }
    adapter = _adapter(config, kotlin)

    assert adapter.create_cards_batch([first_card]) == 1
    assert (
        adapter.create_cards_batch(
            [
                replace(
                    _card("犬", definition=definition),
                    extra_fields={"glossary": glossary},
                )
            ]
        )
        == 1
    )

    dictionary_requests = [
        request
        for request in kotlin.requests_for("ankiStoreMedia")
        if request["payload"]["assets"][0]["purpose"] == "dictionary"
    ]
    assert len(dictionary_requests) == 1
    assets = dictionary_requests[0]["payload"]["assets"]
    assert [asset["preferredName"] for asset in assets] == [
        first_preferred,
        second_preferred,
    ]
    assert [asset["requestedFilename"] for asset in assets] == [
        "dict__pic&one.png",
        "dict__other.svg",
    ]
    assert all(Path(asset["preferredName"]).suffix == "" for asset in assets)

    expected_definition = (
        '<img class="anki-miner-dict-media" '
        f'src="{first_preferred}_random.jpeg">'
        '<img class="anki-miner-dict-media" '
        f'src="{first_preferred}_random.jpeg">'
        '<img class="ordinary" src="dict__pic&amp;one.png">'
        f'<img src="{second_preferred}_random.webp" class="anki-miner-dict-media">'
    )
    expected_glossary = (
        '<span><img class="anki-miner-dict-media" '
        f'src="{first_preferred}_random.jpeg"></span>'
    )
    for request in kotlin.requests_for("ankiCreateNotes"):
        fields = request["payload"]["notes"][0]["fields"]
        assert fields["MainDefinition"] == expected_definition
        assert fields["Glossary"] == expected_glossary
    assert first_card.definition == definition
    assert first_card.extra_fields == {"glossary": glossary}


def test_partial_create_response_preserves_ids_counters_and_vocab_cache(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["既知"]
    kotlin.create_scripts = [
        (
            ["created", "failed", "notAttempted"],
            {"code": "write_failed", "message": "mid-batch", "retryable": False},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert adapter.get_existing_vocabulary() == {"既知"}

    with pytest.raises(AnkiConnectionError, match="mid-batch"):
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 0
    assert adapter.get_existing_vocabulary() == {"既知", "猫"}


def test_committed_routing_failure_retains_id_but_not_success_or_vocab_cache(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["既知"]
    kotlin.create_scripts = [
        (
            ["created", "committedFailed", "notAttempted"],
            {
                "code": "write_failed",
                "message": "deck routing failed after insert",
                "retryable": False,
            },
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert adapter.get_existing_vocabulary() == {"既知"}

    with pytest.raises(AnkiConnectionError, match="routing failed"):
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert adapter.last_created_note_ids == [1000, 1001]
    assert adapter.last_skipped_duplicates == 0
    assert adapter.get_existing_vocabulary() == {"既知", "猫"}
    assert kotlin.next_note_id == 1002


def test_unknown_post_commit_state_never_invents_id_or_updates_vocab_cache(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["既知"]
    kotlin.create_scripts = [
        (
            ["uncertain", "notAttempted"],
            {
                "code": "post_commit_uncertain",
                "message": "provider query failed after insert",
                "retryable": False,
            },
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert adapter.get_existing_vocabulary() == {"既知"}

    with pytest.raises(AnkiConnectionError, match="query failed"):
        adapter.create_cards_batch([_card("猫"), _card("犬")])

    assert adapter.last_created_note_ids == []
    assert adapter.last_skipped_duplicates == 0
    assert adapter.get_existing_vocabulary() == {"既知"}
    assert kotlin.next_note_id == 1001


def test_callback_wide_post_commit_error_is_rejected_as_temporally_invalid(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.errors["createNotes"] = (
        "post_commit_uncertain",
        "cannot use callback-wide error after commit",
        False,
    )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫")]
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert kotlin.next_note_id == 1000


def test_misaligned_create_results_are_protocol_errors(
    initialized_bridge_home: Path,
) -> None:
    class MisalignedKotlin(FakeKotlinAnki):
        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            return encode_message(
                "anki.createnotes.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "clientNoteId": "note_" + "f" * 32,
                            "status": "created",
                            "noteId": 1,
                        }
                    ],
                    "error": None,
                },
            )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(
            _config(initialized_bridge_home), MisalignedKotlin()
        ).create_cards_batch([_card("猫")])
    assert exc_info.value.code == "mismatched_callback_response"


def test_no_bridge_module_imports_engine_before_bootstrap() -> None:
    bridge_file = (
        Path(__file__).resolve().parents[3]
        / "app/src/main/python/android_bridge/anki_adapter.py"
    )
    source_lines = bridge_file.read_text(encoding="utf-8").splitlines()

    assert not any(
        line.startswith(("import anki_miner", "from anki_miner"))
        for line in source_lines
    )


@pytest.mark.parametrize(
    "code", ["api_disabled", "permission_required", "field_missing"]
)
def test_user_action_and_field_errors_are_setup_failures(
    code: str, initialized_bridge_home: Path
) -> None:
    from anki_miner.exceptions import SetupError

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (code, f"setup: {code}", False)

    with pytest.raises(SetupError, match=code):
        _adapter(_config(initialized_bridge_home), kotlin).verify_card_target()


@pytest.mark.parametrize(
    "operation", ["verifyTarget", "scanFirstFields", "storeMedia", "createNotes"]
)
def test_cancelled_callbacks_escape_as_bridge_cancellation(
    operation: str, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    kotlin = FakeKotlinAnki()
    kotlin.errors[operation] = ("cancelled", "cancelled by user", False)
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    audio = tmp_path / f"{operation}.opus"
    audio.write_bytes(b"audio")

    with pytest.raises(AnkiOperationCancelled) as exc_info:
        if operation == "verifyTarget":
            adapter.verify_card_target()
        elif operation == "scanFirstFields":
            adapter.get_existing_vocabulary()
        else:
            adapter.create_cards_batch(
                [
                    _card(
                        "猫",
                        media=(
                            MediaData(audio_path=audio, audio_filename="clip.opus")
                            if operation == "storeMedia"
                            else None
                        ),
                    )
                ]
            )

    assert exc_info.value.code == "cancelled"
    assert exc_info.value.operation == operation
    assert exc_info.value.retryable is False


@pytest.mark.parametrize(
    ("code", "expected"),
    [
        ("api_disabled", "SetupError"),
        ("permission_required", "SetupError"),
        ("provider_unavailable", "AnkiConnectionError"),
        ("timeout", "AnkiConnectionError"),
    ],
)
def test_callback_wide_media_failures_abort_without_mutating_payload(
    code: str,
    expected: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError, SetupError
    from anki_miner.models import MediaData

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    media = MediaData(audio_path=audio, audio_filename="clip.opus")
    kotlin = FakeKotlinAnki()
    kotlin.errors["storeMedia"] = (code, f"media: {code}", True)
    error_type = SetupError if expected == "SetupError" else AnkiConnectionError

    with pytest.raises(error_type, match=code):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media)]
        )

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    ("code", "expected"),
    [
        ("permission_required", "SetupError"),
        ("provider_unavailable", "AnkiConnectionError"),
        ("cancelled", "AnkiOperationCancelled"),
    ],
)
def test_only_explicit_per_media_store_failure_may_degrade(
    code: str,
    expected: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError, SetupError
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    preferred = Path(_content_addressed_name("clip.opus", b"audio")).stem
    media = MediaData(audio_path=audio, audio_filename="clip.opus")
    kotlin = FakeKotlinAnki()
    kotlin.failed_media_names.add(preferred)
    kotlin.media_failure_errors[preferred] = (code, f"row: {code}", False)
    error_type: type[BaseException]
    if expected == "SetupError":
        error_type = SetupError
    elif expected == "AnkiConnectionError":
        error_type = AnkiConnectionError
    else:
        error_type = AnkiOperationCancelled

    with pytest.raises(error_type, match=code):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media)]
        )

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    "returned_name",
    [
        "[sound:clip.opus]",
        '<img src="clip.opus">',
        "clip\n.opus",
        "clip\u200e.opus",
        "../clip.opus",
        "clip.mp3",
        "unrelated.opus",
    ],
)
def test_unsafe_or_unrelated_provider_names_are_rejected_before_mutation(
    returned_name: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    class RenamingKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": request["assets"][0]["assetId"],
                            "status": "stored",
                            "actualFilename": returned_name,
                        }
                    ],
                    "error": None,
                },
            )

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    media = MediaData(audio_path=audio, audio_filename="clip.opus")
    kotlin = RenamingKotlin()

    with pytest.raises(BridgeProtocolError):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media)]
        )

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize("collision_kind", ["reserved", "duplicate_actual"])
def test_provider_media_name_collisions_are_rejected_transactionally(
    collision_kind: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    first_path = tmp_path / "first.opus"
    second_path = tmp_path / "second.opus"
    first_path.write_bytes(b"first")
    second_path.write_bytes(b"second")
    first_preferred = _content_addressed_name("clip.opus", b"first")
    second_original = f"{Path(first_preferred).stem}_extra.opus"
    second_preferred = _content_addressed_name(second_original, b"second")

    class CollidingKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            actual = (
                second_preferred
                if collision_kind == "reserved"
                else f"{Path(second_preferred).stem}_provider.opus"
            )
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": asset["assetId"],
                            "status": "stored",
                            "actualFilename": actual,
                        }
                        for asset in request["assets"]
                    ],
                    "error": None,
                },
            )

    first_media = MediaData(audio_path=first_path, audio_filename="clip.opus")
    second_media = MediaData(audio_path=second_path, audio_filename=second_original)

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(
            _config(initialized_bridge_home), CollidingKotlin()
        ).create_cards_batch(
            [_card("猫", media=first_media), _card("犬", media=second_media)]
        )

    assert exc_info.value.code == "media_name_collision"
    assert first_media.audio_filename == "clip.opus"
    assert second_media.audio_filename == second_original


def test_provider_duplicate_status_is_the_only_residual_duplicate_count(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [(["created", "duplicate"], None)]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch([_card("猫"), _card("犬")]) == 1
    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 1


def test_provider_failed_status_propagates_and_is_never_counted_as_duplicate(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [
        (
            ["created", "failed", "notAttempted"],
            {"code": "write_failed", "message": "write exploded", "retryable": False},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(AnkiConnectionError, match="write exploded"):
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 0


@pytest.mark.parametrize(
    ("statuses", "error"),
    [
        (
            ["notAttempted", "created"],
            {"code": "write_failed", "message": "bad suffix", "retryable": False},
        ),
        (["failed"], None),
        (
            ["created"],
            {"code": "write_failed", "message": "orphan error", "retryable": False},
        ),
        (
            ["created", "failed"],
            {"code": "write_failed", "message": "unsafe retry", "retryable": True},
        ),
        (
            ["uncertain"],
            {"code": "write_failed", "message": "wrong temporal code", "retryable": False},
        ),
    ],
)
def test_create_result_failure_shape_is_strict(
    statuses: list[str],
    error: dict[str, object] | None,
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [(statuses, error)]

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card(f"語{index}") for index in range(len(statuses))]
        )

    assert exc_info.value.code == "invalid_anki_response"


def test_duplicate_created_note_ids_are_rejected(
    initialized_bridge_home: Path,
) -> None:
    class DuplicateIdKotlin(FakeKotlinAnki):
        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            return encode_message(
                "anki.createnotes.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "clientNoteId": note["clientNoteId"],
                            "status": "created",
                            "noteId": 123,
                        }
                        for note in request["notes"]
                    ],
                    "error": None,
                },
            )

    with pytest.raises(BridgeProtocolError, match="duplicate note IDs"):
        _adapter(
            _config(initialized_bridge_home), DuplicateIdKotlin()
        ).create_cards_batch([_card("猫"), _card("犬")])


def test_created_note_ids_must_remain_unique_across_batches(
    initialized_bridge_home: Path,
) -> None:
    class ReusedIdKotlin(FakeKotlinAnki):
        def __init__(self) -> None:
            super().__init__()
            self.batch = 0

        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            self.batch += 1
            first_id = 1 if self.batch == 2 else 0
            return encode_message(
                "anki.createnotes.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "clientNoteId": note["clientNoteId"],
                            "status": "created",
                            "noteId": first_id + index + 1,
                        }
                        for index, note in enumerate(request["notes"])
                    ],
                    "error": None,
                },
            )

    adapter = _adapter(_config(initialized_bridge_home), ReusedIdKotlin())
    with pytest.raises(BridgeProtocolError, match="earlier batch"):
        adapter.create_cards_batch([_card(f"語{index}") for index in range(101)])

    assert adapter.last_created_note_ids == list(range(1, 101))


def test_partial_create_cancellation_becomes_nonretryable_post_commit_failure(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [
        (
            ["created", "notAttempted"],
            {"code": "cancelled", "message": "user stopped", "retryable": False},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    from anki_miner.exceptions import AnkiConnectionError

    with pytest.raises(AnkiConnectionError, match="user stopped"):
        adapter.create_cards_batch([_card("猫"), _card("犬")])

    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 0


def test_bold_note_builder_diagnostics_match_desktop_behavior(
    initialized_bridge_home: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    precomputed = _card("猫")
    precomputed.word.sentence_bolded = "<b>猫</b>だ"
    precomputed.word.sentence_furigana_bolded = "<b>猫[よみ]</b>だ"
    fallback = _card("犬")

    with caplog.at_level("INFO", logger="android_bridge.anki_adapter"):
        _adapter(
            _config(initialized_bridge_home, bold_target_in_sentence=True),
            FakeKotlinAnki(),
        ).create_cards_batch([precomputed, fallback])

    assert (
        "bold_target_in_sentence=on: precomputed bold used on 1/2 cards "
        "(escape fallback: 1)"
    ) in caplog.messages
