from __future__ import annotations

import hashlib
import html
import json
import logging
import re
import sys
import time
import types
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
from typing import Any

import android_bridge.anki_adapter as anki_adapter_module
import pytest
from android_bridge.anki_adapter import (
    _MAX_CARD_MEDIA_BYTES,
    _MAX_CARDS_PER_NOTE,
    _MAX_CREATE_CALL_MEDIA_BYTES,
    _MAX_CREATE_CALL_MEDIA_REFS,
    _MAX_CREATE_CALL_NOTE_UTF8_BYTES,
    _MAX_CREATE_CALL_SOURCE_ITEMS,
    _MAX_CREATE_CALL_SOURCE_UTF8_BYTES,
    _MAX_CREATE_CONTENT_UTF8_BYTES,
    _MAX_CREATE_ENVELOPE_UTF8_BYTES,
    _MAX_DECK_NAME_UTF8_BYTES,
    _MAX_EXCLUDED_DECKS,
    _MAX_EXCLUDED_DECKS_UTF8_BYTES,
    _MAX_FIELD_NAME_UTF8_BYTES,
    _MAX_FIELD_VALUE_UTF8_BYTES,
    _MAX_KNOWN_VOCABULARY_SCANNED_NOTES,
    _MAX_MEDIA_ASSET_BYTES,
    _MAX_MEDIA_BINDINGS_PER_NOTE,
    _MAX_MEDIA_BINDINGS_TOTAL,
    _MAX_MEDIA_CALLBACK_BYTES,
    _MAX_MEDIA_SOURCE_PATH_UTF8_BYTES,
    _MAX_MODEL_NAME_UTF8_BYTES,
    _MAX_NOTE_CONTENT_UTF8_BYTES,
    _MAX_NOTE_FIELDS,
    _MAX_NOTE_TAGS,
    _MAX_NOTE_TAGS_UTF8_BYTES,
    _MAX_TAG_UTF8_BYTES,
    _MAX_TARGET_FIELDS,
    _MAX_TARGET_FIELDS_UTF8_BYTES,
    _MEDIA_HASH_CHUNK_BYTES,
    AndroidAnkiAdapter,
    AnkiOperationCancelled,
    _chunk_media_assets,
    _dictionary_provider_preferred_name,
    _expect_bounded_utf8,
    _expect_media_basename,
    _expect_media_source_path,
    _MediaAsset,
    _strict_utf8_bytes,
)
from android_bridge.callbacks import AndroidAnkiCallbacks, AnkiCallbackError
from android_bridge.protocol import BridgeProtocolError, encode_message
from jsonschema import Draft202012Validator

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
_KNOWN_VOCABULARY_LIMITS = {
    "maxScannedNotes": 256,
    "maxTotalScannedNotes": 100000,
    "maxItems": 256,
    "maxItemUtf8Bytes": 65536,
    "maxTotalUtf8Bytes": 262144,
}
_ANKI_SCHEMA = json.loads(
    (Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/schemas/anki.schema.json").read_text(
        encoding="utf-8"
    )
)
_ANKI_VALIDATOR = Draft202012Validator(_ANKI_SCHEMA)
_SOURCE_PATH_CORPUS = (
    Path(__file__).resolve().parents[3] / "app/src/test/resources/contracts/anki_media_source_path_v1.json"
)

_ANKIDROID_STYLE_RE = re.compile(r"<style.*?>.*?</style>", re.DOTALL)
_ANKIDROID_SCRIPT_RE = re.compile(r"<script.*?>.*?</script>", re.DOTALL)
_ANKIDROID_TAG_RE = re.compile(r"<.*?>")
_ANKIDROID_IMG_RE = re.compile(r"""<img src=["']?([^"'>]+)["']? ?/?>""")


def _source_path_cases() -> list[dict[str, Any]]:
    corpus = json.loads(_SOURCE_PATH_CORPUS.read_text(encoding="utf-8"))
    assert corpus["version"] == 1
    cases: list[dict[str, Any]] = []
    for raw_case in corpus["cases"]:
        case = dict(raw_case)
        recipe = case.pop("pathRecipe", None)
        if recipe is not None:
            case["path"] = recipe["prefix"] + recipe["unit"] * recipe["repeat"]
        cases.append(case)
    return cases


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
    orchestration_prefix = "anki_miner.orchestration"
    previous = {name: module for name, module in sys.modules.items() if name == prefix or name.startswith(f"{prefix}.")}
    previous_orchestration = {
        name: module
        for name, module in sys.modules.items()
        if name == orchestration_prefix or name.startswith(f"{orchestration_prefix}.")
    }
    for name in previous:
        sys.modules.pop(name)

    package = types.ModuleType(prefix)
    package.__path__ = [str(Path(anki_miner.__file__).resolve().parent / "services")]
    # episode_processor imports these facade names from services.__init__.
    # Host bridge tests need only their runtime identities, not the desktop
    # package's eager dependency graph.
    for service_name in (
        "AnkiService",
        "DefinitionService",
        "MediaExtractorService",
        "SubtitleParserService",
        "WordFilterService",
    ):
        setattr(package, service_name, type(service_name, (), {}))
    sys.modules[prefix] = package
    ankiconnect = types.ModuleType(f"{prefix}._ankiconnect")

    def unexpected_http(*args: object, **kwargs: object) -> None:
        raise AssertionError("Android adapter must not call AnkiConnect")

    ankiconnect.post_action = unexpected_http  # type: ignore[attr-defined]
    ankiconnect.post_multi = unexpected_http  # type: ignore[attr-defined]
    # anki_service also imports the response validator now. Nothing can reach it
    # while both posters are hard failures, so it gets the same guard.
    ankiconnect._expect_list = unexpected_http  # type: ignore[attr-defined]
    sys.modules[ankiconnect.__name__] = ankiconnect
    reading_images = types.ModuleType(f"{prefix}.reading.images")
    reading_images.prepare_card_image = unexpected_http  # type: ignore[attr-defined]
    # episode_processor now imports the loader's two error types alongside it.
    reading_images.ReadingImageArchiveError = type("ReadingImageArchiveError", (Exception,), {})
    reading_images.ReadingImageMemberError = type("ReadingImageMemberError", (Exception,), {})
    sys.modules[reading_images.__name__] = reading_images
    try:
        yield
    finally:
        for name in list(sys.modules):
            if name == prefix or name.startswith(f"{prefix}."):
                sys.modules.pop(name)
            if name == orchestration_prefix or name.startswith(f"{orchestration_prefix}."):
                sys.modules.pop(name)
        sys.modules.update(previous)
        sys.modules.update(previous_orchestration)


class FakeKotlinAnki:
    def __init__(self) -> None:
        self.requests: list[tuple[str, dict[str, Any]]] = []
        self.verify_fields: list[str] | None = None
        self.allow_invalid_verify_result = False
        self.known_fields: list[str] = []
        self.known_note_decks: list[set[str]] = []
        self.duplicate_fields: list[str] = []
        self.duplicate_note_ids: dict[int, int] = {}
        self.duplicate_decks: dict[str, set[str]] = {}
        self.errors: dict[str, tuple[str, str, bool]] = {}
        self.failed_media_names: set[str] = set()
        self.media_failure_errors: dict[str, tuple[str, str, bool]] = {}
        self.media_renames: dict[str, str] = {}
        self.create_scripts: list[tuple[list[str], dict[str, object] | None] | None] = []
        self.next_note_id = 1000
        self._baseline_counter = 0
        self._baseline_snapshots: dict[str, dict[str, Any]] = {}
        self._outstanding_baseline_by_run: dict[str, str] = {}
        self._verified_first_fields: dict[tuple[str, str], str] = {}
        self._known_cursor_counter = 0
        self._known_cursors: dict[str, dict[str, Any]] = {}
        self._media_acknowledgements_by_run: dict[str, dict[str, str]] = {}
        # Admission belongs to the coordinator: callback handlers may consult
        # it but never lazily recreate a finalized run object.
        self._admitted_runs: set[str] = {RUN_ID}
        self._provider_tasks_by_run: dict[str, dict[str, str]] = {}
        self._task_cursors_by_run: dict[str, set[str]] = {}
        self._uri_grants_by_run: dict[str, set[str]] = {}
        self._provider_staging_by_run: dict[str, set[str]] = {}
        self._release_requested_runs: set[str] = set()
        self._quarantined_runs: set[str] = set()
        self._cleanup_registered_runs: set[str] = set()
        self.cleanup_finalization_count = 0
        self._registry_lock_held = False
        self.task_reconciliation_hook: Any = None
        self.final_cleanup_hook: Any = None
        self.lifecycle_events: list[str] = []
        self.release_states: list[str] = []
        self.release_acknowledgements: list[bool] = []

    def _duplicate_records(self) -> list[tuple[int, str]]:
        return [
            (self.duplicate_note_ids.get(index, 10_000 + index), first_field)
            for index, first_field in enumerate(self.duplicate_fields)
        ]

    def _in_duplicate_scope(self, note_id: int, first_field: str, scope: dict[str, Any]) -> bool:
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
        run_id = request["runId"]
        prior_token = self._outstanding_baseline_by_run.get(run_id)
        assert scope["invalidateBaselineToken"] == prior_token
        if prior_token is not None:
            self._baseline_snapshots.pop(prior_token)
        token = f"baseline_{self._baseline_counter:032x}"
        self._baseline_counter += 1
        self._baseline_snapshots[token] = {
            "runId": request["runId"],
            "modelName": scope["modelName"],
            "firstFieldName": scope["firstFieldName"],
            "deckName": scope["deckName"],
            "candidates": [(candidate["key"], candidate["firstField"]) for candidate in scope["candidates"]],
            "occurrences": list(scope["occurrences"]),
            "ids": {
                (candidate["key"], candidate["firstField"]): frozenset(int(hit["noteId"]) for hit in hits)
                for candidate, hits in zip(scope["candidates"], raw_hit_buckets, strict=True)
            },
        }
        self._outstanding_baseline_by_run[run_id] = token
        return token

    def _consume_duplicate_baseline(self, request: dict[str, Any]) -> dict[str, Any]:
        token = request["baselineToken"]
        assert self._outstanding_baseline_by_run.pop(request["runId"]) == token
        return self._baseline_snapshots.pop(token)

    def _has_retained_run_state(self, run_id: str) -> bool:
        return any(
            (
                run_id in self._outstanding_baseline_by_run,
                any(state["runId"] == run_id for state in self._baseline_snapshots.values()),
                any(state["runId"] == run_id for state in self._known_cursors.values()),
                any(key[0] == run_id for key in self._verified_first_fields),
                run_id in self._media_acknowledgements_by_run,
                bool(self._provider_tasks_by_run.get(run_id)),
                bool(self._task_cursors_by_run.get(run_id)),
                bool(self._uri_grants_by_run.get(run_id)),
                bool(self._provider_staging_by_run.get(run_id)),
            )
        )

    @contextmanager
    def _registry_lock(self) -> Iterator[None]:
        assert not self._registry_lock_held
        self._registry_lock_held = True
        try:
            yield
        finally:
            self._registry_lock_held = False

    def _destroy_logical_run_state_locked(self, run_id: str) -> None:
        assert self._registry_lock_held
        assert not self._provider_tasks_by_run.get(run_id)
        assert not self._known_cursors_for_run(run_id)
        assert not self._task_cursors_by_run.get(run_id)
        assert not self._uri_grants_by_run.get(run_id)
        assert not self._provider_staging_by_run.get(run_id)
        self._provider_tasks_by_run.pop(run_id, None)
        outstanding = self._outstanding_baseline_by_run.pop(run_id, None)
        if outstanding is not None:
            self._baseline_snapshots.pop(outstanding, None)
        for token, state in list(self._baseline_snapshots.items()):
            if state["runId"] == run_id:
                self._baseline_snapshots.pop(token)
        for key in list(self._verified_first_fields):
            if key[0] == run_id:
                self._verified_first_fields.pop(key)
        self._media_acknowledgements_by_run.pop(run_id, None)
        self._task_cursors_by_run.pop(run_id, None)
        self._uri_grants_by_run.pop(run_id, None)
        self._provider_staging_by_run.pop(run_id, None)

    def _known_cursors_for_run(self, run_id: str) -> list[str]:
        return [token for token, state in self._known_cursors.items() if state["runId"] == run_id]

    def _register_cleanup_task_locked(self, run_id: str) -> str:
        assert self._registry_lock_held
        assert run_id not in self._cleanup_registered_runs
        cleanup_id = f"cleanup:{run_id}"
        self._provider_tasks_by_run.setdefault(run_id, {})[cleanup_id] = "cleanup"
        self._cleanup_registered_runs.add(run_id)
        self.lifecycle_events.append(f"register-cleanup:{run_id}")
        return cleanup_id

    def _close_task_resources_outside_lock(self, run_id: str, task_id: str) -> None:
        assert not self._registry_lock_held
        self.lifecycle_events.append(f"reconcile:{task_id}")
        if self.task_reconciliation_hook is not None:
            self.task_reconciliation_hook(self, run_id, task_id)
        for resources, event in (
            (self._task_cursors_by_run, "close-cursor"),
            (self._uri_grants_by_run, "revoke-grant"),
            (self._provider_staging_by_run, "delete-staging"),
        ):
            owned = resources.get(run_id)
            if owned is not None and task_id in owned:
                self.lifecycle_events.append(f"{event}:{task_id}")
                owned.remove(task_id)
                if not owned:
                    resources.pop(run_id)

    def _run_final_cleanup_task(self, run_id: str, cleanup_id: str) -> None:
        assert not self._registry_lock_held
        assert self._provider_tasks_by_run[run_id].get(cleanup_id) == "cleanup"
        self.lifecycle_events.append(f"cleanup-outside-lock:{run_id}")
        if self.final_cleanup_hook is not None:
            self.final_cleanup_hook(self, run_id, cleanup_id)

        for token in self._known_cursors_for_run(run_id):
            self.lifecycle_events.append(f"close-known-cursor:{token}")
            self._known_cursors.pop(token)
        for resources, event in (
            (self._task_cursors_by_run, "close-residual-cursor"),
            (self._uri_grants_by_run, "revoke-residual-grant"),
            (self._provider_staging_by_run, "delete-residual-staging"),
        ):
            for resource in sorted(resources.pop(run_id, set())):
                self.lifecycle_events.append(f"{event}:{resource}")

        with self._registry_lock():
            tasks = self._provider_tasks_by_run[run_id]
            assert tasks == {cleanup_id: "cleanup"}
            tasks.pop(cleanup_id)
            self._provider_tasks_by_run.pop(run_id)
            self._cleanup_registered_runs.remove(run_id)
            self._destroy_logical_run_state_locked(run_id)
            self._quarantined_runs.discard(run_id)
            self._release_requested_runs.discard(run_id)
            self._admitted_runs.remove(run_id)
            self.cleanup_finalization_count += 1
            self.lifecycle_events.append(f"finalize:{run_id}")

    def begin_provider_task(self, run_id: str, task_id: str, kind: str) -> None:
        """Register one read, local-staging, or write task and its resources."""

        assert kind in {"read", "localStaging", "write"}
        with self._registry_lock():
            assert run_id in self._admitted_runs
            assert run_id not in self._release_requested_runs
            tasks = self._provider_tasks_by_run.setdefault(run_id, {})
            assert task_id not in tasks
            tasks[task_id] = kind
            if kind in {"read", "write"}:
                self._task_cursors_by_run.setdefault(run_id, set()).add(task_id)
            if kind == "write":
                self._uri_grants_by_run.setdefault(run_id, set()).add(task_id)
            if kind in {"localStaging", "write"}:
                self._provider_staging_by_run.setdefault(run_id, set()).add(task_id)
            self.lifecycle_events.append(f"register-{kind}:{task_id}")

    def complete_provider_task(self, run_id: str, task_id: str) -> bool:
        """Reconcile and revoke resources before task deregistration."""

        with self._registry_lock():
            task_kind = self._provider_tasks_by_run.get(run_id, {}).get(task_id)
            if task_kind not in {"read", "localStaging", "write"}:
                return False

        # Provider reconciliation and resource revocation may block, so the
        # task remains registered while all of it runs outside the registry
        # lock. A concurrent release therefore observes a live owner.
        self._close_task_resources_outside_lock(run_id, task_id)

        cleanup_id: str | None = None
        with self._registry_lock():
            tasks = self._provider_tasks_by_run[run_id]
            assert tasks.get(task_id) == task_kind
            other_tasks = set(tasks) - {task_id}
            if not other_tasks and run_id in self._release_requested_runs:
                cleanup_id = self._register_cleanup_task_locked(run_id)
            tasks.pop(task_id)
            self.lifecycle_events.append(f"deregister:{task_id}")
            if not tasks:
                self._provider_tasks_by_run.pop(run_id)

        if cleanup_id is None:
            return False
        self._run_final_cleanup_task(run_id, cleanup_id)
        return True

    def release_run_state(self, run_id: str) -> str:
        """Direct coordinator request-release transition for the fake registry."""

        cleanup_id: str | None = None
        retained = False
        with self._registry_lock():
            if run_id not in self._admitted_runs:
                return "absent"
            self._release_requested_runs.add(run_id)
            tasks = self._provider_tasks_by_run.get(run_id)
            if tasks:
                self._quarantined_runs.add(run_id)
                return "deferred"
            retained = self._has_retained_run_state(run_id)
            if retained:
                cleanup_id = self._register_cleanup_task_locked(run_id)
            else:
                self._release_requested_runs.remove(run_id)
                self._admitted_runs.remove(run_id)

        if not retained:
            return "absent"
        assert cleanup_id is not None
        self._run_final_cleanup_task(run_id, cleanup_id)
        return "released"

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

    def _release_requested_error(self, request: dict[str, Any], operation: str) -> str | None:
        run_id = request["runId"]
        if run_id in self._admitted_runs and run_id not in self._release_requested_runs:
            return None
        return encode_message(
            "anki.error",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "operation": operation,
                "code": "invalid_request",
                "message": "Anki run state is closing",
                "retryable": False,
            },
        )

    def ankiVerifyTarget(self, raw: str) -> str:
        request = self._request("ankiVerifyTarget", raw)
        if error := self._release_requested_error(request, "verifyTarget"):
            return error
        if error := self._error(request, "verifyTarget"):
            return error
        fields = (
            self.verify_fields
            if self.verify_fields is not None
            else (
                ["Expression"] + sorted(field for field in request["requiredFields"] if field != "Expression")
                if "Expression" in request["requiredFields"]
                else (request["requiredFields"] or ["Expression"])
            )
        )
        field_utf8_bytes = [len(field.encode("utf-8")) for field in fields]
        if not self.allow_invalid_verify_result and (
            len(fields) > _MAX_TARGET_FIELDS
            or any(size > _MAX_FIELD_NAME_UTF8_BYTES for size in field_utf8_bytes)
            or sum(field_utf8_bytes) > _MAX_TARGET_FIELDS_UTF8_BYTES
        ):
            return encode_message(
                "anki.error",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "operation": "verifyTarget",
                    "code": "target_invalid",
                    "message": "Target model exceeds the v1 field limits",
                    "retryable": False,
                },
            )
        self._verified_first_fields[(request["runId"], request["modelName"])] = fields[0]
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
        if error := self._release_requested_error(request, "scanFirstFields"):
            return error
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
                assert cursor_state["excludedDecks"] == tuple(scope["excludedDecks"])
                assert cursor_state["deckName"] == scope.get("deckName")
                assert cursor_state["ordinal"] == cursor["ordinal"]
                start = cursor_state["start"]
                scanned_before = cursor_state["scannedNotes"]
                next_ordinal = cursor["ordinal"] + 1
            limits = scope["limits"]
            excluded = scope["excludedDecks"]
            target_deck = scope.get("deckName")
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
                decks = self.known_note_decks[index] if index < len(self.known_note_decks) else set()
                note_is_excluded = any(
                    deck == excluded_deck or deck.startswith(f"{excluded_deck}::")
                    for deck in decks
                    for excluded_deck in excluded
                )
                note_is_in_scope = target_deck is None or target_deck in decks
                if (
                    note_is_in_scope
                    and not note_is_excluded
                    and page_fields
                    and page_utf8_bytes + field_bytes > limits["maxTotalUtf8Bytes"]
                ):
                    break
                scanned_notes += 1
                if note_is_in_scope and not note_is_excluded:
                    page_fields.append(field)
                    page_utf8_bytes += field_bytes
            next_index = start + scanned_notes
            total_scanned = scanned_before + scanned_notes
            if next_index < len(self.known_fields) and total_scanned >= limits["maxTotalScannedNotes"]:
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
                    "deckName": target_deck,
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
        assert scope["firstFieldName"] == self._verified_first_fields[(request["runId"], scope["modelName"])]
        deck_name = scope["deckName"]
        assert len(scope["occurrences"]) <= 100
        assert all(0 <= occurrence < len(scope["candidates"]) for occurrence in scope["occurrences"])
        raw_hit_buckets = []
        records = self._duplicate_records()
        for candidate in scope["candidates"]:
            candidate_checksum = _ankidroid_field_checksum(candidate["firstField"])
            hits = []
            for note_id, stored in records:
                if _ankidroid_field_checksum(stored) != candidate_checksum:
                    continue
                if deck_name is not None and deck_name not in self.duplicate_decks.get(stored, set()):
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
        if error := self._release_requested_error(request, "storeMedia"):
            return error
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
            if actual_size != asset["expectedSizeBytes"] or actual_sha256.hexdigest() != asset["expectedSha256"]:
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
                        "actualFilename": self.media_renames.get(preferred, f"{preferred}_provider{extension}"),
                    }
                )
        acknowledgements = self._media_acknowledgements_by_run.setdefault(request["runId"], {})
        acknowledgements.update(
            {
                str(result["assetId"]): str(result["actualFilename"])
                for result in results
                if result["status"] == "stored"
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

    def ankiReleaseRunState(self, raw: str) -> str:
        request = self._request("ankiReleaseRunState", raw)
        self.release_acknowledgements.append(request["acknowledgeTerminalResponses"])
        state = self.release_run_state(request["runId"])
        self.release_states.append(state)
        if error := self._error(request, "releaseRunState"):
            return error
        return encode_message(
            "anki.releaserunstate.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "state": state,
            },
        )

    def ankiCreateNotes(self, raw: str) -> str:
        request = self._request("ankiCreateNotes", raw)
        if error := self._release_requested_error(request, "createNotes"):
            return error
        if error := self._error(request, "createNotes"):
            return error
        assert len(raw.encode("utf-8")) <= request["limits"]["maxEnvelopeUtf8Bytes"]
        assert request["limits"] == _CREATE_LIMITS
        total_content_bytes = 0
        total_media_bindings = 0
        acknowledgements = self._media_acknowledgements_by_run.get(request["runId"], {})
        requested_asset_ids = {
            asset["assetId"]
            for store_request in self.requests_for("ankiStoreMedia")
            if store_request["payload"]["runId"] == request["runId"]
            for asset in store_request["payload"]["assets"]
        }
        for note in request["notes"]:
            assert len(note["fields"]) <= request["limits"]["maxFieldsPerNote"]
            note_content_bytes = 0
            for field_name, value in note["fields"].items():
                assert len(field_name.encode("utf-8")) <= request["limits"]["maxFieldNameUtf8Bytes"]
                assert len(value.encode("utf-8")) <= request["limits"]["maxFieldValueUtf8Bytes"]
                note_content_bytes += len(field_name.encode("utf-8")) + len(value.encode("utf-8"))
            assert len(note["tags"]) <= request["limits"]["maxTagsPerNote"]
            tag_bytes = sum(len(tag.encode("utf-8")) for tag in note["tags"])
            assert all(len(tag.encode("utf-8")) <= request["limits"]["maxTagUtf8Bytes"] for tag in note["tags"])
            assert tag_bytes <= request["limits"]["maxTagsUtf8BytesPerNote"]
            note_content_bytes += tag_bytes
            assert len(note["mediaBindings"]) <= request["limits"]["maxMediaBindingsPerNote"]
            binding_asset_ids: set[str] = set()
            for binding in note["mediaBindings"]:
                assert set(binding) == {"assetId", "actualFilename"}
                assert binding["assetId"] not in binding_asset_ids
                binding_asset_ids.add(binding["assetId"])
                acknowledged_filename = acknowledgements.get(binding["assetId"])
                assert acknowledged_filename == binding["actualFilename"] or (
                    acknowledged_filename is None and binding["assetId"] in requested_asset_ids
                )
                note_content_bytes += len(binding["assetId"].encode("utf-8"))
                note_content_bytes += len(binding["actualFilename"].encode("utf-8"))
            total_media_bindings += len(note["mediaBindings"])
            assert note_content_bytes <= request["limits"]["maxNoteContentUtf8Bytes"]
            total_content_bytes += note_content_bytes
        assert total_content_bytes <= request["limits"]["maxTotalContentUtf8Bytes"]
        assert total_media_bindings <= request["limits"]["maxMediaBindingsTotal"]

        baseline = self._consume_duplicate_baseline(request)

        assert baseline["runId"] == request["runId"]
        assert baseline["modelName"] == request["modelName"]
        assert baseline["firstFieldName"] == request["firstFieldName"]
        assert request["firstFieldName"] == self._verified_first_fields[(request["runId"], request["modelName"])]
        expected_deck_name = (
            request["duplicateScope"].get("deckName") if request["duplicateScope"]["kind"] == "exactDeck" else None
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
                created_rows.append((note_id, note["duplicateCandidate"]["firstField"]))
            results.append(result)
        for note_id, first_field in created_rows:
            index = len(self.duplicate_fields)
            self.duplicate_fields.append(first_field)
            self.duplicate_note_ids[index] = note_id
            self.duplicate_decks.setdefault(first_field, set()).add(request["deckName"])
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
    *,
    target_verified: bool = True,
) -> AndroidAnkiAdapter:
    adapter = AndroidAnkiAdapter(
        config,
        AndroidAnkiCallbacks(kotlin, RUN_ID),
        cancellation_check=cancellation_check,
    )
    if target_verified:
        required = {value for value in config.anki_fields.values() if value}
        if config.card_type:
            marker = config.card_type_marker_fields.get(config.card_type, "")
            if marker:
                required.add(marker)
        fields = kotlin.verify_fields
        if fields is None:
            fields = (
                ["Expression", *sorted(required - {"Expression"})]
                if "Expression" in required
                else (sorted(required) or ["Expression"])
            )
        adapter._verified_field_names = tuple(fields)
        kotlin._verified_first_fields[(RUN_ID, config.anki_note_type)] = fields[0]
    return adapter


def _assert_fake_run_state_released(kotlin: FakeKotlinAnki) -> None:
    assert RUN_ID not in kotlin._outstanding_baseline_by_run
    assert all(state["runId"] != RUN_ID for state in kotlin._baseline_snapshots.values())
    assert all(state["runId"] != RUN_ID for state in kotlin._known_cursors.values())
    assert all(run_id != RUN_ID for run_id, _ in kotlin._verified_first_fields)
    assert RUN_ID not in kotlin._media_acknowledgements_by_run
    assert RUN_ID not in kotlin._provider_tasks_by_run
    assert RUN_ID not in kotlin._task_cursors_by_run
    assert RUN_ID not in kotlin._uri_grants_by_run
    assert RUN_ID not in kotlin._provider_staging_by_run
    assert RUN_ID not in kotlin._quarantined_runs
    assert RUN_ID not in kotlin._cleanup_registered_runs
    assert RUN_ID not in kotlin._release_requested_runs
    assert RUN_ID not in kotlin._admitted_runs


def test_duplicate_destination_is_rejected_before_note_builder_can_overwrite_word(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home)
    duplicate_fields = {**config.anki_fields, "word": "Sentence", "sentence": "Sentence"}
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as error:
        AndroidAnkiAdapter(
            replace(config, anki_fields=duplicate_fields),
            AndroidAnkiCallbacks(kotlin, RUN_ID),
        )

    assert error.value.code == "invalid_config_field"
    assert "Sentence" in str(error.value)
    assert kotlin.requests == []


def test_active_card_marker_cannot_overwrite_word_at_adapter_boundary(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home)
    marker_fields = {**config.card_type_marker_fields, "click": "Expression"}
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as error:
        AndroidAnkiAdapter(
            replace(
                config,
                card_type="click",
                card_type_marker_fields=marker_fields,
            ),
            AndroidAnkiCallbacks(kotlin, RUN_ID),
        )

    assert error.value.code == "invalid_config_field"
    assert "Expression" in str(error.value)
    assert "word" in str(error.value)
    assert "card_type_marker_fields.click" in str(error.value)
    assert kotlin.requests == []


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

    _adapter(config, kotlin, target_verified=False).verify_card_target()

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


def test_verify_target_deck_uncertainty_uses_existing_nonretryable_error_shape(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (
        "post_commit_uncertain",
        "deck creation outcome could not be proven",
        False,
    )

    with pytest.raises(AnkiConnectionError, match="could not be proven"):
        _adapter(
            _config(initialized_bridge_home),
            kotlin,
            target_verified=False,
        ).verify_card_target()

    assert len(kotlin.requests_for("ankiVerifyTarget")) == 1
    assert not kotlin.requests_for("ankiScanFirstFields")


def test_verify_target_rejects_content_provider_deck_created_true(
    initialized_bridge_home: Path,
) -> None:
    class InvalidDeckCreatedKotlin(FakeKotlinAnki):
        def ankiVerifyTarget(self, raw: str) -> str:
            request = self._request("ankiVerifyTarget", raw)
            return encode_message(
                "anki.verifytarget.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "deckId": 10,
                    "modelId": 20,
                    "fieldNames": ["Expression"],
                    "deckCreated": True,
                },
            )

    kotlin = InvalidDeckCreatedKotlin()
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        target_verified=False,
    )

    with pytest.raises(BridgeProtocolError) as error:
        adapter.verify_card_target()
    assert error.value.code == "invalid_anki_response"
    assert "deckCreated=false" in str(error.value)

    adapter.close()
    assert kotlin.release_acknowledgements == [False]


@pytest.mark.parametrize(
    ("config_attribute", "max_utf8_bytes"),
    [
        ("anki_deck_name", _MAX_DECK_NAME_UTF8_BYTES),
        ("anki_note_type", _MAX_MODEL_NAME_UTF8_BYTES),
    ],
)
def test_verify_target_name_utf8_bounds_fail_before_callback(
    config_attribute: str,
    max_utf8_bytes: int,
    initialized_bridge_home: Path,
) -> None:
    exact = "界" * (max_utf8_bytes // 3) + "x" * (max_utf8_bytes % 3)
    exact_kotlin = FakeKotlinAnki()
    exact_config = replace(_config(initialized_bridge_home), **{config_attribute: exact})

    _adapter(exact_config, exact_kotlin, target_verified=False).verify_card_target()
    assert len(exact.encode("utf-8")) == max_utf8_bytes
    assert len(exact_kotlin.requests_for("ankiVerifyTarget")) == 1

    oversized_kotlin = FakeKotlinAnki()
    oversized_config = replace(
        _config(initialized_bridge_home),
        **{config_attribute: exact + "界"},
    )
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(oversized_config, oversized_kotlin, target_verified=False).verify_card_target()
    assert exc_info.value.code == "invalid_anki_request"
    assert not oversized_kotlin.requests


def test_verify_required_field_utf8_bound_fails_before_callback(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    oversized_field = "界" * (_MAX_FIELD_NAME_UTF8_BYTES // 3 + 1)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "word": oversized_field},
    )
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin, target_verified=False).verify_card_target()

    assert exc_info.value.code == "invalid_anki_request"
    assert not kotlin.requests


def test_verify_result_field_count_matches_create_cap_and_stops_later_access(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import SetupError

    config = _config(initialized_bridge_home)
    required = {value for value in config.anki_fields.values() if value}
    marker = config.card_type_marker_fields.get(config.card_type, "")
    if marker:
        required.add(marker)
    exact_fields = sorted(required)
    exact_fields.extend(f"BoundedField{index}" for index in range(_MAX_TARGET_FIELDS - len(exact_fields)))
    exact_kotlin = FakeKotlinAnki()
    exact_kotlin.verify_fields = exact_fields
    _adapter(config, exact_kotlin, target_verified=False).verify_card_target()
    assert len(exact_fields) == _MAX_TARGET_FIELDS

    bounded_rejection_kotlin = FakeKotlinAnki()
    bounded_rejection_kotlin.verify_fields = [*exact_fields, "OneFieldTooMany"]
    with pytest.raises(SetupError, match="v1 field limits"):
        _adapter(config, bounded_rejection_kotlin, target_verified=False).verify_card_target()
    assert [method for method, _ in bounded_rejection_kotlin.requests] == ["ankiVerifyTarget"]
    assert not bounded_rejection_kotlin._verified_first_fields

    oversized_kotlin = FakeKotlinAnki()
    oversized_kotlin.verify_fields = [*exact_fields, "OneFieldTooMany"]
    oversized_kotlin.allow_invalid_verify_result = True
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, oversized_kotlin, target_verified=False).verify_card_target()

    assert exc_info.value.code == "invalid_anki_response"
    assert [method for method, _ in oversized_kotlin.requests] == ["ankiVerifyTarget"]


def test_verify_result_field_utf8_and_aggregate_bounds_are_defensive(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.allow_invalid_verify_result = True
    kotlin.verify_fields = [
        "界" * (_MAX_FIELD_NAME_UTF8_BYTES // 3 + 1),
    ]

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin, target_verified=False).verify_card_target()
    assert exc_info.value.code == "invalid_anki_response"

    assert _MAX_TARGET_FIELDS * _MAX_FIELD_NAME_UTF8_BYTES == (_MAX_TARGET_FIELDS_UTF8_BYTES)


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
        _adapter(config, kotlin, target_verified=False).verify_card_target()

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (
        "provider_unavailable",
        "AnkiDroid unavailable",
        True,
    )
    with pytest.raises(AnkiConnectionError, match="AnkiDroid unavailable"):
        _adapter(config, kotlin, target_verified=False).verify_card_target()


def test_verify_target_defensively_checks_returned_field_ordering_set(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import SetupError

    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.verify_fields = ["Expression"]

    with pytest.raises(SetupError, match=r"Field\(s\)"):
        _adapter(config, kotlin, target_verified=False).verify_card_target()


def test_blank_word_is_rejected_after_verify_before_any_card_creation(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    fields = {**base.anki_fields, "word": ""}
    markers = {**base.card_type_marker_fields, "click": ""}
    config = replace(
        base,
        anki_fields=fields,
        card_type="click",
        card_type_marker_fields=markers,
    )
    kotlin = FakeKotlinAnki()
    required_fields = {value for value in config.anki_fields.values() if value}
    kotlin.verify_fields = [
        "Sentence",
        *sorted(required_fields - {"Sentence"}),
    ]
    adapter = _adapter(config, kotlin, target_verified=False)

    with pytest.raises(BridgeProtocolError) as error:
        adapter.verify_card_target()

    assert error.value.code == "invalid_config_field"
    assert "word" in str(error.value)
    assert adapter._verified_field_names is None
    assert len(kotlin.requests_for("ankiVerifyTarget")) == 1
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_all_blank_mappings_are_rejected_for_target_preflight(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields=dict.fromkeys(base.anki_fields, ""),
        card_type="click",
        card_type_marker_fields=dict.fromkeys(base.card_type_marker_fields, ""),
    )
    kotlin = FakeKotlinAnki()
    adapter = _adapter(config, kotlin, target_verified=False)

    with pytest.raises(BridgeProtocolError) as error:
        adapter.verify_card_target()

    assert error.value.code == "invalid_config_field"
    assert adapter._verified_field_names is None
    assert kotlin.requests_for("ankiVerifyTarget")[0]["payload"]["requiredFields"] == []
    assert not kotlin.requests_for("ankiCreateNotes")


def test_cancelled_target_preflight_stops_before_kotlin_callback(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: True,
        target_verified=False,
    )

    with pytest.raises(AnkiOperationCancelled) as cancelled:
        adapter.verify_card_target()

    assert cancelled.value.operation == "verifyTarget"
    assert kotlin.requests_for("ankiVerifyTarget") == []


def test_known_vocabulary_is_normalized_filtered_and_cached(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, excluded_decks=("Ignored", "Ignored::Child"))
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


def test_allow_duplicate_cards_scopes_known_vocabulary_to_exact_target_deck(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["猫", "犬", "鳥"]
    kotlin.known_note_decks = [
        {"Japanese::Other"},
        {config.anki_deck_name},
        {f"{config.anki_deck_name}::Child"},
    ]
    adapter = _adapter(config, kotlin)

    assert adapter.get_existing_vocabulary() == {"犬"}
    assert kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]["deckName"] == config.anki_deck_name


def test_known_vocabulary_cancellation_stops_before_the_next_page(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.known_fields = [f"語{index}" for index in range(513)]
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: bool(kotlin.requests_for("ankiScanFirstFields")),
    )

    with pytest.raises(AnkiOperationCancelled) as cancelled:
        adapter.get_existing_vocabulary()

    assert cancelled.value.operation == "scanFirstFields"
    assert len(kotlin.requests_for("ankiScanFirstFields")) == 1


def test_known_vocabulary_excluded_deck_item_limit_is_exact_and_pre_callback(
    initialized_bridge_home: Path,
) -> None:
    exact_decks = tuple(f"Excluded::{index}" for index in range(_MAX_EXCLUDED_DECKS))
    exact_kotlin = FakeKotlinAnki()
    exact_config = replace(_config(initialized_bridge_home), excluded_decks=exact_decks)

    assert _adapter(exact_config, exact_kotlin).get_existing_vocabulary() == set()
    assert len(exact_kotlin.requests_for("ankiScanFirstFields")) == 1

    oversized_kotlin = FakeKotlinAnki()
    oversized_config = replace(
        _config(initialized_bridge_home),
        excluded_decks=(*exact_decks, "OneTooMany"),
    )
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(oversized_config, oversized_kotlin).get_existing_vocabulary()
    assert exc_info.value.code == "invalid_anki_request"
    assert not oversized_kotlin.requests


def test_known_vocabulary_excluded_deck_utf8_limits_are_exact(
    initialized_bridge_home: Path,
) -> None:
    item_limit = _MAX_DECK_NAME_UTF8_BYTES
    exact_item = "界" * (item_limit // 3) + "x" * (item_limit % 3)
    item_kotlin = FakeKotlinAnki()
    item_config = replace(_config(initialized_bridge_home), excluded_decks=(exact_item,))
    assert _adapter(item_config, item_kotlin).get_existing_vocabulary() == set()

    oversized_item_kotlin = FakeKotlinAnki()
    oversized_item_config = replace(
        _config(initialized_bridge_home),
        excluded_decks=(exact_item + "界",),
    )
    with pytest.raises(BridgeProtocolError):
        _adapter(oversized_item_config, oversized_item_kotlin).get_existing_vocabulary()
    assert not oversized_item_kotlin.requests

    names: list[str] = []
    for index in range(_MAX_EXCLUDED_DECKS_UTF8_BYTES // item_limit):
        suffix = str(index)
        names.append("d" * (item_limit - len(suffix)) + suffix)
    assert sum(len(name.encode("utf-8")) for name in names) == (_MAX_EXCLUDED_DECKS_UTF8_BYTES)
    aggregate_kotlin = FakeKotlinAnki()
    aggregate_config = replace(_config(initialized_bridge_home), excluded_decks=tuple(names))
    assert _adapter(aggregate_config, aggregate_kotlin).get_existing_vocabulary() == set()

    oversized_aggregate_kotlin = FakeKotlinAnki()
    oversized_aggregate_config = replace(
        _config(initialized_bridge_home),
        excluded_decks=(*names, "overflow"),
    )
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(oversized_aggregate_config, oversized_aggregate_kotlin).get_existing_vocabulary()
    assert exc_info.value.code == "invalid_anki_request"
    assert not oversized_aggregate_kotlin.requests


def test_known_vocabulary_scan_uses_monotonic_bounded_pages(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.known_fields = [f"語{index}" for index in range(513)]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.get_existing_vocabulary() == set(kotlin.known_fields)

    scopes = [request["payload"]["scope"] for request in kotlin.requests_for("ankiScanFirstFields")]
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
                force_continuation and self.scanned == _MAX_KNOWN_VOCABULARY_SCANNED_NOTES
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
    adapter = _adapter(_config(initialized_bridge_home, excluded_decks=("Ignored",)), kotlin)

    assert adapter.get_existing_vocabulary() == {"採用"}


def test_known_vocabulary_later_page_timeout_discards_the_partial_scan(
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

    from anki_miner.exceptions import AnkiConnectionError

    kotlin = TimeoutSecondPage()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    # Page one's words must not become the answer: they are a fraction of the collection, and
    # caching them would filter the rest of the run against a set that is known to be short.
    for _ in range(2):
        with pytest.raises(AnkiConnectionError, match="slow second page"):
            adapter.get_existing_vocabulary()
        assert adapter._existing_vocab_cache is None
    assert len(kotlin.requests_for("ankiScanFirstFields")) == 4


def test_dictionary_media_read_failure_has_one_stack_owner(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    source = "dict__unreadable.png"
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "unreadable.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    adapter = _adapter(_config(initialized_bridge_home), FakeKotlinAnki())
    plan = types.SimpleNamespace(
        dictionary_media_sources=(source,),
        dictionary_media_paths={source: media_path.resolve()},
    )
    monkeypatch.setattr(
        adapter,
        "_stream_media_digest",
        lambda *_args: (_ for _ in ()).throw(OSError("read failed")),
    )

    with caplog.at_level(logging.DEBUG, logger=anki_adapter_module.logger.name):
        prepared = adapter._prepare_dictionary_media(plan, object())

    assert prepared.assets == ()
    records = [record for record in caplog.records if "Dictionary media read failed" in record.msg]
    assert len(records) == 2
    assert sum(record.exc_info is not None for record in records) == 1
    assert all("outcome=" in record.getMessage() for record in records)


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
        _adapter(_config(initialized_bridge_home), ReusedCursorKotlin()).get_existing_vocabulary()

    assert exc_info.value.code == "invalid_anki_response"


def test_retryable_vocab_timeout_is_reported_instead_of_disabling_filtering(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    # The scan is one ceiling-bounded walk on a bulk deadline, not a 1000-note HTTP batch, so a
    # timeout means it never finished. Degrading to an empty set re-mined the user's whole existing
    # deck for the pre-insert duplicate check to reject, and said nothing about why.
    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.errors["scanFirstFields"] = ("timeout", "slow query", True)
    adapter = _adapter(config, kotlin)

    with pytest.raises(AnkiConnectionError, match="slow query"):
        adapter.get_existing_vocabulary()
    assert adapter._existing_vocab_cache is None
    assert len(kotlin.requests_for("ankiScanFirstFields")) == 1


def test_scan_limit_refusal_reaches_the_engine_as_an_actionable_error(
    initialized_bridge_home: Path,
) -> None:
    # A collection over the scan ceiling is a condition of the user's collection. Answering with a
    # protocol code made it a BridgeProtocolError -- a ValueError, outside the engine's
    # AnkiMinerException handler -- so the engine reported it as "Unexpected error" and logged a
    # stack as an app bug.
    from anki_miner.exceptions import AnkiMinerException

    kotlin = FakeKotlinAnki()
    kotlin.errors["scanFirstFields"] = (
        "query_failed",
        "Known-word filtering supports at most 100000 notes in an Anki collection",
        False,
    )

    with pytest.raises(AnkiMinerException, match="at most 100000 notes"):
        _adapter(_config(initialized_bridge_home), kotlin).get_existing_vocabulary()


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

    created_ids = adapter.create_cards_batch([_card("既存"), _card("猫"), _card("猫")])

    assert len(created_ids) == 1
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

    assert len(adapter.create_cards_batch([_card("猫")])) == 1
    assert adapter.last_skipped_duplicates == 0


def test_duplicate_probe_sends_exact_markup_first_field_for_checksum(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["&lt;b&gt;猫&lt;/b&gt;"]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    created_ids = adapter.create_cards_batch([_card("<b>猫</b>")])
    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert len(created_ids) == 0
    assert scope["candidates"] == [{"key": "<b>猫</b>", "firstField": "&lt;b&gt;猫&lt;/b&gt;"}]


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
                        [{"noteId": 20_000 + index, "firstField": value}] for index, value in enumerate(raw_values)
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

    assert len(adapter.create_cards_batch(cards)) == 0
    assert adapter.last_skipped_duplicates == len(cards)
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    "raw_hit_buckets",
    [
        [[{"noteId": 30_000 + index, "firstField": f"猫{index}"} for index in range(101)]],
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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫")])

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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫")])

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
        "invalidateBaselineToken": None,
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

    assert len(adapter.create_cards_batch([_card(" x 猫")])) == 1
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

    assert len(adapter.create_cards_batch([_card(" x 猫")] * 100)) == 100
    notes = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert len(notes) == 100
    assert [note["duplicateCandidate"]["occurrence"] for note in notes] == list(range(100))
    assert all("baselineNoteIds" not in note["duplicateCandidate"] for note in notes)
    scan_scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scan_scope["occurrences"] == [0] * 100


def test_empty_normalized_first_field_fails_before_provider_callbacks(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card(" \t\n ")])

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

    assert len(adapter.create_cards_batch([_card("猫")])) == expected_created
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

    assert len(adapter.create_cards_batch([_card("猫"), _card("犬")])) == 1
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

    created_ids = adapter.create_cards_batch([_card("猫"), _card("猫"), _card("猫")])

    assert len(created_ids) == expected_created
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


def test_outgoing_duplicate_media_is_not_stored(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    duplicate_audio = tmp_path / "duplicate.opus"
    duplicate_audio.write_bytes(b"duplicate audio")
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    created_ids = adapter.create_cards_batch(
        [
            _card("猫"),
            _card(
                "猫",
                media=MediaData(
                    audio_path=duplicate_audio,
                    audio_filename=duplicate_audio.name,
                ),
            ),
        ]
    )

    assert len(created_ids) == 1
    assert adapter.last_skipped_duplicates == 1
    assert kotlin.requests_for("ankiStoreMedia") == []


def test_unfiltered_provider_duplicate_media_is_not_stored(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "provider-duplicate.opus"
    audio.write_bytes(b"duplicate audio")
    dictionary_path = initialized_bridge_home / "dicts" / "dict" / "media" / "duplicate.png"
    dictionary_path.parent.mkdir(parents=True, exist_ok=True)
    dictionary_path.write_bytes(b"png")
    definition = '<img class="anki-miner-dict-media" src="dict__duplicate.png">'
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["猫"]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    # The known-word scan does not see this note -- it is a card the scan's scope misses, not a
    # failed scan -- so the word reaches creation and only the pre-insert duplicate check stops it.
    assert adapter.get_existing_vocabulary() == set()

    created_ids = adapter.create_cards_batch(
        [
            _card(
                "猫",
                definition=definition,
                media=MediaData(
                    audio_path=audio,
                    audio_filename=audio.name,
                ),
            )
        ]
    )

    assert created_ids == []
    assert adapter.last_skipped_duplicates == 1
    assert kotlin.requests_for("ankiStoreMedia") == []


def test_allow_duplicate_cards_fans_existing_target_hit_to_repeated_candidates(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["猫"]
    kotlin.duplicate_decks = {"猫": {config.anki_deck_name}}
    adapter = _adapter(config, kotlin)

    assert len(adapter.create_cards_batch([_card("猫"), _card("猫")])) == 0
    assert adapter.last_skipped_duplicates == 2
    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scope["candidates"] == [{"key": "猫", "firstField": "猫"}]
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize("allow_duplicate_cards", [False, True])
def test_baseline_token_binds_ordered_occurrences_in_both_duplicate_scopes(
    allow_duplicate_cards: bool,
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=allow_duplicate_cards)
    kotlin = FakeKotlinAnki()

    assert len(_adapter(config, kotlin).create_cards_batch([_card("猫"), _card("犬")])) == 2

    scan = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    create = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert scan["occurrences"] == [0, 1]
    assert [note["duplicateCandidate"]["occurrence"] for note in create["notes"]] == [
        0,
        1,
    ]
    assert create["baselineToken"].startswith("baseline_")
    assert create["baselineToken"] not in kotlin._baseline_snapshots
    assert RUN_ID not in kotlin._outstanding_baseline_by_run
    assert scan["deckName"] == (config.anki_deck_name if allow_duplicate_cards else None)


@pytest.mark.parametrize("allow_duplicate_cards", [False, True])
def test_all_duplicate_probes_keep_one_outstanding_baseline_per_run(
    allow_duplicate_cards: bool,
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=allow_duplicate_cards)
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["猫"]
    if allow_duplicate_cards:
        kotlin.duplicate_decks = {"猫": {config.anki_deck_name}}
    adapter = _adapter(config, kotlin)

    for _ in range(25):
        assert len(adapter.create_cards_batch([_card("猫")])) == 0
        assert len(kotlin._baseline_snapshots) == 1
        assert len(kotlin._outstanding_baseline_by_run) == 1

    scopes = [request["payload"]["scope"] for request in kotlin.requests_for("ankiScanFirstFields")]
    assert scopes[0]["invalidateBaselineToken"] is None
    assert [scope["invalidateBaselineToken"] for scope in scopes[1:]] == [
        f"baseline_{index:032x}" for index in range(24)
    ]

    kotlin.duplicate_fields = []
    kotlin.duplicate_decks = {}
    assert len(adapter.create_cards_batch([_card("犬")])) == 1
    assert not kotlin._baseline_snapshots
    assert not kotlin._outstanding_baseline_by_run
    assert adapter._outstanding_baseline_token is None


def test_close_releases_abandoned_all_duplicate_baseline_idempotently(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["猫"]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert len(adapter.create_cards_batch([_card("猫")])) == 0
    assert RUN_ID in kotlin._outstanding_baseline_by_run

    adapter.close()
    adapter.close()

    _assert_fake_run_state_released(kotlin)
    releases = kotlin.requests_for("ankiReleaseRunState")
    assert len(releases) == 1
    assert set(releases[0]["payload"]) == {
        "runId",
        "requestId",
        "acknowledgeTerminalResponses",
    }
    assert kotlin.release_states == ["released"]
    assert kotlin.release_acknowledgements == [True]
    assert adapter._outstanding_baseline_token is None


def test_release_run_state_handler_is_idempotent(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    adapter.verify_card_target()
    callbacks = AndroidAnkiCallbacks(kotlin, RUN_ID)
    cleanup_observations: list[tuple[bool, dict[str, str]]] = []

    def observe_cleanup(fake: FakeKotlinAnki, run_id: str, cleanup_id: str) -> None:
        cleanup_observations.append(
            (
                fake._registry_lock_held,
                dict(fake._provider_tasks_by_run[run_id]),
            )
        )
        assert cleanup_id in fake._provider_tasks_by_run[run_id]

    kotlin.final_cleanup_hook = observe_cleanup

    assert callbacks.release_run_state()["state"] == "released"
    assert callbacks.release_run_state()["state"] == "absent"

    assert cleanup_observations == [(False, {f"cleanup:{RUN_ID}": "cleanup"})]
    assert kotlin.lifecycle_events[-3:] == [
        f"register-cleanup:{RUN_ID}",
        f"cleanup-outside-lock:{RUN_ID}",
        f"finalize:{RUN_ID}",
    ]
    _assert_fake_run_state_released(kotlin)


def test_release_defers_until_last_provider_task_then_cleans_exactly_once(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["猫"]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert len(adapter.create_cards_batch([_card("猫")])) == 0
    token = kotlin._outstanding_baseline_by_run[RUN_ID]
    retained_baseline = kotlin._baseline_snapshots[token]
    kotlin._media_acknowledgements_by_run[RUN_ID] = {"asset_ack": "ack.opus"}
    kotlin.begin_provider_task(RUN_ID, "provider_read", "read")
    kotlin.begin_provider_task(RUN_ID, "local_snapshot", "localStaging")
    kotlin.begin_provider_task(RUN_ID, "blocked_write", "write")

    adapter.close()

    assert kotlin.release_states == ["deferred"]
    assert RUN_ID in kotlin._release_requested_runs
    assert RUN_ID in kotlin._quarantined_runs
    assert kotlin._baseline_snapshots[token] is retained_baseline
    assert kotlin._media_acknowledgements_by_run[RUN_ID] == {"asset_ack": "ack.opus"}
    assert kotlin._provider_staging_by_run[RUN_ID] == {
        "local_snapshot",
        "blocked_write",
    }
    assert kotlin._task_cursors_by_run[RUN_ID] == {
        "provider_read",
        "blocked_write",
    }
    assert kotlin._uri_grants_by_run[RUN_ID] == {"blocked_write"}
    assert kotlin.cleanup_finalization_count == 0

    # releaseRequested fails closed for new work while the blocked task may
    # still read every quarantined capability it owned before the timeout.
    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.verify_card_target()
    assert exc_info.value.code == "invalid_request"
    callbacks = AndroidAnkiCallbacks(kotlin, RUN_ID)
    assert callbacks.release_run_state()["state"] == "deferred"
    assert kotlin.complete_provider_task(RUN_ID, "provider_read") is False
    assert kotlin._baseline_snapshots[token] is retained_baseline
    assert kotlin._provider_staging_by_run[RUN_ID] == {
        "local_snapshot",
        "blocked_write",
    }
    assert kotlin.complete_provider_task(RUN_ID, "local_snapshot") is False
    assert kotlin._provider_staging_by_run[RUN_ID] == {"blocked_write"}

    hook_release_states: list[str] = []

    def release_twice_while_write_is_registered(fake: FakeKotlinAnki, run_id: str, task_id: str) -> None:
        assert fake._registry_lock_held is False
        assert fake._provider_tasks_by_run[run_id][task_id] == "write"
        assert fake._baseline_snapshots[token] is retained_baseline
        hook_release_states.extend(
            (
                callbacks.release_run_state()["state"],
                callbacks.release_run_state()["state"],
            )
        )

    cleanup_observations: list[tuple[bool, dict[str, str]]] = []

    def observe_registered_cleanup(fake: FakeKotlinAnki, run_id: str, cleanup_id: str) -> None:
        cleanup_observations.append(
            (
                fake._registry_lock_held,
                dict(fake._provider_tasks_by_run[run_id]),
            )
        )
        assert fake._baseline_snapshots[token] is retained_baseline
        assert not fake._task_cursors_by_run.get(run_id)
        assert not fake._uri_grants_by_run.get(run_id)
        assert not fake._provider_staging_by_run.get(run_id)
        assert cleanup_id in fake._provider_tasks_by_run[run_id]

    kotlin.task_reconciliation_hook = release_twice_while_write_is_registered
    kotlin.final_cleanup_hook = observe_registered_cleanup

    assert kotlin.complete_provider_task(RUN_ID, "blocked_write") is True
    assert hook_release_states == ["deferred", "deferred"]
    assert cleanup_observations == [(False, {f"cleanup:{RUN_ID}": "cleanup"})]
    _assert_fake_run_state_released(kotlin)
    assert kotlin.cleanup_finalization_count == 1
    assert kotlin.complete_provider_task(RUN_ID, "blocked_write") is False
    assert callbacks.release_run_state()["state"] == "absent"
    assert kotlin.cleanup_finalization_count == 1
    ordered = kotlin.lifecycle_events
    assert ordered.index("reconcile:blocked_write") < ordered.index("close-cursor:blocked_write")
    assert ordered.index("close-cursor:blocked_write") < ordered.index("revoke-grant:blocked_write")
    assert ordered.index("revoke-grant:blocked_write") < ordered.index("delete-staging:blocked_write")
    assert ordered.index("delete-staging:blocked_write") < ordered.index(f"register-cleanup:{RUN_ID}")
    assert ordered.index(f"register-cleanup:{RUN_ID}") < ordered.index("deregister:blocked_write")
    assert ordered.index("deregister:blocked_write") < ordered.index(f"cleanup-outside-lock:{RUN_ID}")
    assert ordered.index(f"cleanup-outside-lock:{RUN_ID}") < ordered.index(f"finalize:{RUN_ID}")


def test_close_accepts_absent_run_state(initialized_bridge_home: Path) -> None:
    kotlin = FakeKotlinAnki()

    _adapter(_config(initialized_bridge_home), kotlin, target_verified=False).close()

    assert kotlin.release_states == ["absent"]
    _assert_fake_run_state_released(kotlin)


def test_close_releases_abandoned_baseline_after_clean_cancellation(
    initialized_bridge_home: Path,
) -> None:
    cancelled = False
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["猫"]
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: cancelled,
    )
    assert len(adapter.create_cards_batch([_card("猫")])) == 0
    cancelled = True

    with pytest.raises(AnkiOperationCancelled):
        adapter.create_cards_batch([_card("犬")])
    adapter.close()

    _assert_fake_run_state_released(kotlin)
    assert len(kotlin.requests_for("ankiReleaseRunState")) == 1
    assert kotlin.release_acknowledgements == [True]


def test_close_releases_interrupted_known_vocabulary_cursor_after_error(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    class FailingSecondPageKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            response = super().ankiScanFirstFields(raw)
            if request["scope"]["kind"] == "knownVocabulary" and request["scope"]["cursor"] is None:
                self.errors["scanFirstFields"] = (
                    "query_failed",
                    "provider scan interrupted",
                    False,
                )
            return response

    kotlin = FailingSecondPageKotlin()
    kotlin.known_fields = [f"語{index}" for index in range(300)]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(AnkiConnectionError, match="provider scan interrupted"):
        adapter.get_existing_vocabulary()
    assert kotlin._known_cursors
    adapter.close()

    _assert_fake_run_state_released(kotlin)


def test_context_exit_releases_normal_media_ack_state_without_provider_mutation(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "normal-close.opus"
    audio.write_bytes(b"audio")
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with adapter:
        assert (
            len(
                adapter.create_cards_batch(
                    [
                        _card(
                            "猫",
                            media=MediaData(
                                audio_path=audio,
                                audio_filename=audio.name,
                            ),
                        )
                    ]
                )
            )
            == 1
        )
        assert kotlin._media_acknowledgements_by_run[RUN_ID]
        created_rows = list(kotlin.duplicate_fields)
        next_note_id = kotlin.next_note_id

    _assert_fake_run_state_released(kotlin)
    assert kotlin.duplicate_fields == created_rows
    assert kotlin.next_note_id == next_note_id
    assert not adapter._stored_media_name_owners
    assert not adapter._reserved_media_name_owners
    assert len(kotlin.requests_for("ankiReleaseRunState")) == 1
    assert kotlin.release_acknowledgements == [True]


def test_close_failure_cannot_mask_primary_error_and_direct_release_is_fallback(
    initialized_bridge_home: Path,
) -> None:
    class CallbackFailureKotlin(FakeKotlinAnki):
        def ankiReleaseRunState(self, raw: str) -> str:
            self._request("ankiReleaseRunState", raw)
            raise RuntimeError("callback transport failed")

    kotlin = CallbackFailureKotlin()
    kotlin.duplicate_fields = ["猫"]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert len(adapter.create_cards_batch([_card("猫")])) == 0

    with pytest.raises(RuntimeError, match="primary mining failure"):
        try:
            raise RuntimeError("primary mining failure")
        finally:
            adapter.close()

    assert RUN_ID in kotlin._outstanding_baseline_by_run
    assert kotlin.release_run_state(RUN_ID) == "released"
    _assert_fake_run_state_released(kotlin)
    adapter.close()
    assert len(kotlin.requests_for("ankiReleaseRunState")) == 1


def test_semantically_invalid_terminal_response_makes_release_ack_false(
    initialized_bridge_home: Path,
) -> None:
    class InvalidVerifyKotlin(FakeKotlinAnki):
        def ankiVerifyTarget(self, raw: str) -> str:
            request = json.loads(raw)["payload"]
            return encode_message(
                "anki.verifytarget.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "deckId": 0,
                    "modelId": 2,
                    "fieldNames": ["Expression"],
                    "deckCreated": False,
                },
            )

    kotlin = InvalidVerifyKotlin()
    adapter = _adapter(_config(initialized_bridge_home), kotlin, target_verified=False)

    with pytest.raises(BridgeProtocolError) as error:
        adapter.verify_card_target()
    assert error.value.code == "invalid_anki_response"
    adapter.close()

    assert kotlin.release_acknowledgements == [False]


def test_semantically_invalid_known_scan_makes_release_ack_false(
    initialized_bridge_home: Path,
) -> None:
    class InvalidKnownScanKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "firstFields": [],
                    "scannedNotes": -1,
                    "nextCursor": None,
                },
            )

    kotlin = InvalidKnownScanKotlin()
    adapter = _adapter(_config(initialized_bridge_home), kotlin, target_verified=False)
    adapter.verify_card_target()

    with pytest.raises(BridgeProtocolError) as error:
        adapter.get_existing_vocabulary()
    assert error.value.code == "invalid_anki_response"
    adapter.close()

    assert kotlin.release_acknowledgements == [False]


def test_semantically_invalid_duplicate_scan_makes_release_ack_false(
    initialized_bridge_home: Path,
) -> None:
    class InvalidDuplicateScanKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "rawFirstFieldHits": [],
                    "baselineToken": f"baseline_{'f' * 32}",
                },
            )

    kotlin = InvalidDuplicateScanKotlin()
    adapter = _adapter(_config(initialized_bridge_home), kotlin, target_verified=False)
    adapter.verify_card_target()

    with pytest.raises(BridgeProtocolError) as error:
        adapter._duplicate_first_fields([("猫", "猫")])
    assert error.value.code == "invalid_anki_response"
    adapter.close()

    assert kotlin.release_acknowledgements == [False]


def test_semantically_accepted_callback_error_can_be_acknowledged_on_release(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import SetupError

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (
        "note_type_not_found",
        "missing target",
        False,
    )
    adapter = _adapter(_config(initialized_bridge_home), kotlin, target_verified=False)

    with pytest.raises(SetupError, match="missing target"):
        adapter.verify_card_target()
    adapter.close()

    assert kotlin.release_acknowledgements == [True]


def test_close_failure_cannot_replace_successful_primary_result(
    initialized_bridge_home: Path,
) -> None:
    class CallbackFailureKotlin(FakeKotlinAnki):
        def ankiReleaseRunState(self, raw: str) -> str:
            self._request("ankiReleaseRunState", raw)
            raise RuntimeError("callback transport failed")

    kotlin = CallbackFailureKotlin()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    adapter.verify_card_target()

    def finish_run() -> int:
        try:
            return 7
        finally:
            adapter.close()

    assert finish_run() == 7
    assert kotlin.release_run_state(RUN_ID) == "released"
    _assert_fake_run_state_released(kotlin)


def test_duplicate_probe_fans_matches_by_exact_first_field_candidate(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = [" 猫 "]
    kotlin.duplicate_decks = {" 猫 ": {config.anki_deck_name}}
    adapter = _adapter(config, kotlin)

    assert len(adapter.create_cards_batch([_card("猫"), _card(" 猫 "), _card("猫")])) == 2
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

    created_ids = _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards, progress)

    assert len(created_ids) == 101
    assert [len(request["payload"]["notes"]) for request in kotlin.requests_for("ankiCreateNotes")] == [100, 1]
    duplicate_scopes = [request["payload"]["scope"] for request in kotlin.requests_for("ankiScanFirstFields")]
    assert [len(scope["candidates"]) for scope in duplicate_scopes] == [100, 1]
    assert duplicate_scopes[0]["candidates"] == [
        {"key": f"語{index}", "firstField": f"語{index}"} for index in range(100)
    ]
    assert duplicate_scopes[1]["candidates"] == [{"key": "語100", "firstField": "語100"}]
    assert progress.events == [
        ("start", 101, "Creating Anki cards"),
        ("progress", 100, "Cards created: 100/101"),
        ("progress", 101, "Cards created: 101/101"),
        ("complete",),
    ]


def test_non_first_word_mapping_is_rejected_after_verify_before_any_card_creation(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={
            **base.anki_fields,
            "word": "Sentence",
            "sentence": "Expression",
        },
    )
    kotlin = FakeKotlinAnki()
    required = {value for value in config.anki_fields.values() if value}
    kotlin.verify_fields = ["Expression", *sorted(required - {"Expression"})]
    adapter = _adapter(config, kotlin, target_verified=False)

    with pytest.raises(BridgeProtocolError) as error:
        adapter.verify_card_target()

    assert error.value.code == "invalid_config_field"
    assert "word" in str(error.value)
    assert "Sentence" in str(error.value)
    assert "Expression" in str(error.value)
    assert adapter._verified_field_names is None
    assert len(kotlin.requests_for("ankiVerifyTarget")) == 1
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


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
        len(
            _adapter(_config(initialized_bridge_home), exact_kotlin).create_cards_batch([_card("猫", definition=exact)])
        )
        == 1
    )
    oversized_kotlin = FakeKotlinAnki()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), oversized_kotlin).create_cards_batch(
            [_card("猫", definition=oversized)]
        )

    assert exc_info.value.code == "note_too_large"
    assert not oversized_kotlin.requests_for("ankiScanFirstFields")
    assert not oversized_kotlin.requests_for("ankiCreateNotes")


def test_create_call_source_item_limit_is_exact_and_pre_callback(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home)
    adapter = _adapter(config, FakeKotlinAnki())
    card = _card("猫")

    adapter._preflight_create_call([card] * _MAX_CREATE_CALL_SOURCE_ITEMS)

    kotlin = FakeKotlinAnki()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch([card] * (_MAX_CREATE_CALL_SOURCE_ITEMS + 1))

    assert exc_info.value.code == "create_call_too_large"
    assert not kotlin.requests


def test_create_call_source_utf8_limit_precedes_note_build_and_callbacks(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    definition = "界" * (_MAX_CREATE_CALL_SOURCE_UTF8_BYTES // 3 + 1)

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", definition=definition)])

    assert exc_info.value.code == "create_call_too_large"
    assert not kotlin.requests


def test_oversized_marked_dictionary_html_fails_before_media_regex_or_callback(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.services import anki_media_store

    def unexpected_scan(_html: str) -> list[str]:
        raise AssertionError("oversized dictionary HTML must not be scanned")

    monkeypatch.setattr(anki_media_store, "_extract_dict_media_srcs", unexpected_scan)
    marked = '<img class="anki-miner-dict-media" src="dict__pic.png">'
    glossary = marked + "x" * _MAX_FIELD_VALUE_UTF8_BYTES
    card = replace(_card("猫"), extra_fields={"glossary": glossary})
    kotlin = FakeKotlinAnki()
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "glossary": "Glossary"},
    )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch([card])

    assert exc_info.value.code == "note_too_large"
    assert not kotlin.requests


def test_create_call_aggregate_built_note_utf8_limit_is_pre_callback(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "source": "Source"},
    )
    cards = [replace(_card(f"語{index}"), extra_fields={"source": "&" * 15_000}) for index in range(225)]
    kotlin = FakeKotlinAnki()

    assert 225 * len(html.escape("&" * 15_000).encode("utf-8")) > (_MAX_CREATE_CALL_NOTE_UTF8_BYTES)
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "create_call_too_large"
    assert not kotlin.requests


def test_card_provider_filename_headroom_is_reserved_before_callbacks(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_note_builder import build_note

    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "source": "Source"},
    )
    picture = tmp_path / "picture.png"
    picture.write_bytes(b"png")
    cards = [
        replace(
            _card(
                f"語{index}",
                media=MediaData(
                    screenshot_path=picture,
                    screenshot_filename=f"picture-{index}.png",
                ),
            ),
            extra_fields={"source": "&" * 15_000},
        )
        for index in range(220)
    ]
    current_content_bytes = sum(
        AndroidAnkiAdapter._validated_note_content(build_note(card, config, {card.media.screenshot_filename}).note)[2]
        for card in cards
    )
    kotlin = FakeKotlinAnki()

    assert current_content_bytes < _MAX_CREATE_CALL_NOTE_UTF8_BYTES
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "create_call_too_large"
    assert not kotlin.requests


def test_dictionary_provider_filename_headroom_is_reserved_before_callbacks(
    initialized_bridge_home: Path,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "x.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    marked = '<img class="anki-miner-dict-media" src="dict__x.png">'
    definition = marked + "x" * (_MAX_FIELD_VALUE_UTF8_BYTES - len(marked.encode("utf-8")))
    kotlin = FakeKotlinAnki()

    assert len(definition.encode("utf-8")) == _MAX_FIELD_VALUE_UTF8_BYTES
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", definition=definition)])

    assert exc_info.value.code == "note_too_large"
    assert not kotlin.requests


def test_create_call_media_reference_limit_counts_marked_dictionary_html(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    missing = tmp_path / "missing.bin"
    media = MediaData(
        screenshot_path=missing,
        screenshot_filename="picture.png",
        audio_path=missing,
        audio_filename="sentence.opus",
        expression_audio_path=missing,
        expression_audio_filename="expression.opus",
    )
    marked = '<img class="anki-miner-dict-media" src="dict__pic.png">'
    cards = [
        _card(
            f"語{index}",
            definition=marked * (2 if index == 0 else 1),
            media=media,
        )
        for index in range(_MAX_CREATE_CALL_SOURCE_ITEMS)
    ]
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert _MAX_CREATE_CALL_MEDIA_REFS == 8_000
    assert exc_info.value.code == "create_call_too_large"
    assert not kotlin.requests


def test_create_call_rejects_card_filename_whose_hashed_name_exceeds_limit(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    # The original basename fits 1 KiB, but `_0123456789ab` is inserted before
    # the extension by the content-addressed card-media contract.
    filename = "x" * 1_012 + ".opus"
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [
                _card(
                    "猫",
                    media=MediaData(
                        audio_path=tmp_path / "missing.opus",
                        audio_filename=filename,
                    ),
                )
            ]
        )

    assert exc_info.value.code == "invalid_note"
    assert not kotlin.requests


def test_create_call_total_media_bytes_are_bounded_before_hashing_or_callback(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    cards = []
    for card_index in range(3):
        paths = []
        for media_index in range(3):
            path = tmp_path / f"media-{card_index}-{media_index}.bin"
            with path.open("wb") as output:
                output.truncate(_MAX_MEDIA_ASSET_BYTES)
            paths.append(path)
        cards.append(
            _card(
                f"語{card_index}",
                media=MediaData(
                    screenshot_path=paths[0],
                    screenshot_filename=f"picture-{card_index}.png",
                    audio_path=paths[1],
                    audio_filename=f"sentence-{card_index}.opus",
                    expression_audio_path=paths[2],
                    expression_audio_filename=f"expression-{card_index}.opus",
                ),
            )
        )
    kotlin = FakeKotlinAnki()
    # expression_audio is unmapped by default and the engine now skips media for
    # unmapped fields, so map it to keep all three slots per card in the count.
    base = _config(initialized_bridge_home)
    config = replace(base, anki_fields={**base.anki_fields, "expression_audio": "WordAudio"})

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch(cards)

    assert _MAX_CREATE_CALL_MEDIA_BYTES == 8 * _MAX_MEDIA_ASSET_BYTES
    assert exc_info.value.code == "create_call_too_large"
    assert not kotlin.requests


def test_create_call_media_bytes_count_distinct_logical_names_separately(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    shared = tmp_path / "shared.bin"
    with shared.open("wb") as output:
        output.truncate(_MAX_MEDIA_ASSET_BYTES)
    cards = [
        _card(
            f"語{index}",
            media=MediaData(
                screenshot_path=shared,
                screenshot_filename=f"picture-{index}.png",
                audio_path=shared,
                audio_filename=f"sentence-{index}.opus",
                expression_audio_path=shared,
                expression_audio_filename=f"expression-{index}.opus",
            ),
        )
        for index in range(3)
    ]
    kotlin = FakeKotlinAnki()
    base = _config(initialized_bridge_home)
    config = replace(base, anki_fields={**base.anki_fields, "expression_audio": "WordAudio"})

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "create_call_too_large"
    assert not kotlin.requests


def test_create_call_media_bytes_dedupe_repeated_filename_path_association(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    shared = tmp_path / "shared-repeat.opus"
    with shared.open("wb") as output:
        output.truncate(_MAX_MEDIA_ASSET_BYTES)
    cards = [
        _card(
            f"語{index}",
            media=MediaData(
                audio_path=shared,
                audio_filename="shared-repeat.opus",
            ),
        )
        for index in range(9)
    ]
    kotlin = FakeKotlinAnki()

    _adapter(_config(initialized_bridge_home), kotlin)._preflight_create_call(cards)

    assert not kotlin.requests


@pytest.mark.parametrize(
    "tags",
    [
        ["x" * (_MAX_TAG_UTF8_BYTES + 1)],
        [f"t{index}" for index in range(_MAX_NOTE_TAGS + 1)],
        ["x" * 129 for _ in range(_MAX_NOTE_TAGS)],
    ],
    ids=["single-tag", "tag-count", "tag-total-bytes"],
)
def test_note_tag_limits_fail_before_duplicate_probe(tags: list[str], initialized_bridge_home: Path) -> None:
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

    assert len(_adapter(config, kotlin).create_cards_batch([_card("猫")])) == 1
    request_tags = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["tags"]
    assert len(request_tags) == _MAX_NOTE_TAGS
    assert sum(len(tag.encode("utf-8")) for tag in request_tags) == (_MAX_NOTE_TAGS_UTF8_BYTES)


def test_json_escaping_drives_exact_envelope_batching(
    initialized_bridge_home: Path,
) -> None:
    cards = [_card(f"語{index}", definition="\\" * 50_000) for index in range(6)]
    kotlin = FakeKotlinAnki()

    assert len(_adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)) == 6

    requests = kotlin.requests_for("ankiCreateNotes")
    assert len(requests) > 1
    assert all(
        len(encode_message(request["type"], request["payload"]).encode("utf-8")) <= _MAX_CREATE_ENVELOPE_UTF8_BYTES
        for request in requests
    )


def test_nul_heavy_exact_field_limit_fails_complete_preflight_without_side_effects(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData

    class Progress:
        def __init__(self) -> None:
            self.events: list[tuple[object, ...]] = []

        def on_start(self, *args: object) -> None:
            self.events.append(("start", *args))

        def on_progress(self, *args: object) -> None:
            self.events.append(("progress", *args))

        def on_complete(self) -> None:
            self.events.append(("complete",))

    audio = tmp_path / "must-not-be-hashed.opus"
    audio.write_bytes(b"audio")
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    progress = Progress()

    def unexpected_hash(_path: Path) -> object:
        raise AssertionError("failed create preflight must not hash media")

    monkeypatch.setattr(adapter, "_stream_media_digest", unexpected_hash)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(
            [
                _card(
                    "猫",
                    definition="\0" * _MAX_FIELD_VALUE_UTF8_BYTES,
                    media=MediaData(
                        audio_path=audio,
                        audio_filename="must-not-be-hashed.opus",
                    ),
                )
            ],
            progress,
        )

    assert exc_info.value.code == "note_batch_too_large"
    assert progress.events == []
    assert kotlin.requests == []


def test_create_requires_the_engine_target_preflight_before_any_callback(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        target_verified=False,
    )

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch([_card("猫")])

    assert exc_info.value.code == "invalid_note"
    assert kotlin.requests == []


def test_multibyte_content_drives_aggregate_batching(
    initialized_bridge_home: Path,
) -> None:
    cards = [_card(f"語{index}", definition="界" * 20_000) for index in range(7)]
    kotlin = FakeKotlinAnki()

    assert len(_adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)) == 7
    assert [len(request["payload"]["notes"]) for request in kotlin.requests_for("ankiCreateNotes")] == [6, 1]


def test_repeated_identity_group_that_exceeds_byte_budget_fails_atomically(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch([_card("猫", definition="\\" * 50_000) for _ in range(10)])

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

    assert len(adapter.create_cards_batch(cards)) == 202
    assert adapter.last_skipped_duplicates == 3

    scopes = [request["payload"]["scope"] for request in kotlin.requests_for("ankiScanFirstFields")]
    assert all(len(scope["candidates"]) <= 100 for scope in scopes)
    assert [candidate for scope in scopes for candidate in scope["candidates"]] == [
        {"key": f"語{index}", "firstField": f"語{index}"} for index in range(205)
    ]
    assert all("definition" not in scope for scope in scopes)
    create_requests = kotlin.requests_for("ankiCreateNotes")
    assert sum(len(request["payload"]["notes"]) for request in create_requests) == 202
    assert all(
        len(json.dumps(request, ensure_ascii=False).encode("utf-8")) <= _MAX_CREATE_ENVELOPE_UTF8_BYTES
        for request in create_requests
    )


def test_duplicate_candidate_key_size_is_bounded_before_callback(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    oversized = "語" * 4097

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card(oversized)])

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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫")])

    assert exc_info.value.code == "invalid_anki_response"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_first_occurrence_wins_across_batch_boundary_when_duplicates_disallowed(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    cards = [_card(f"語{index}") for index in range(100)] + [_card("語0")]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert len(adapter.create_cards_batch(cards)) == 100
    assert adapter.last_skipped_duplicates == 1
    assert [len(request["payload"]["notes"]) for request in kotlin.requests_for("ankiCreateNotes")] == [100]
    assert [
        len(request["payload"]["scope"]["candidates"]) for request in kotlin.requests_for("ankiScanFirstFields")
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


def test_more_than_100_repeated_identities_fail_before_progress_hash_or_callback(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData

    class Progress:
        def __init__(self) -> None:
            self.events: list[str] = []

        def on_start(self, *_: object) -> None:
            self.events.append("start")

        def on_progress(self, *_: object) -> None:
            self.events.append("progress")

        def on_complete(self) -> None:
            self.events.append("complete")

    audio = tmp_path / "repeated-must-not-be-hashed.opus"
    audio.write_bytes(b"audio")
    media = MediaData(
        audio_path=audio,
        audio_filename="repeated-must-not-be-hashed.opus",
    )
    kotlin = FakeKotlinAnki()
    adapter = _adapter(
        _config(initialized_bridge_home, allow_duplicate_cards=True),
        kotlin,
    )
    progress = Progress()

    def unexpected_hash(_path: Path) -> object:
        raise AssertionError("failed repeated-identity preflight must not hash media")

    monkeypatch.setattr(adapter, "_stream_media_digest", unexpected_hash)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch([_card("猫", media=media) for _ in range(101)], progress)

    assert exc_info.value.code == "note_batch_too_large"
    assert progress.events == []
    assert kotlin.requests == []


def test_dictionary_rewrite_collision_is_rejected_before_progress_hash_or_callback(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Progress:
        def __init__(self) -> None:
            self.events: list[str] = []

        def on_start(self, *_: object) -> None:
            self.events.append("start")

        def on_progress(self, *_: object) -> None:
            self.events.append("progress")

        def on_complete(self) -> None:
            self.events.append("complete")

    source = "dict__a.png"
    encoded_source = "dict__&#97;.png"
    preferred = _dictionary_provider_preferred_name(source)
    missing_provider_name = f"{preferred}__missing.png"
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "a.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")

    def marked(name: str) -> str:
        return f'<img class="anki-miner-dict-media" src="{name}">猫'

    config = _config(
        initialized_bridge_home,
        allow_duplicate_cards=True,
    )
    kotlin = FakeKotlinAnki()
    required = {value for value in config.anki_fields.values() if value}
    kotlin.verify_fields = [
        "MainDefinition",
        *sorted(required - {"MainDefinition"}),
    ]
    # This is a valid provider-shaped result for `source`, but also a marked
    # missing dictionary basename retained verbatim by the other 33 notes.
    kotlin.media_renames[preferred] = missing_provider_name
    adapter = _adapter(config, kotlin)
    progress = Progress()
    cards = [
        *[_card(f"甲{index}", definition=marked(source)) for index in range(34)],
        *[_card(f"乙{index}", definition=marked(encoded_source)) for index in range(34)],
        *[_card(f"丙{index}", definition=marked(missing_provider_name)) for index in range(33)],
    ]

    def unexpected_hash(_path: Path) -> object:
        raise AssertionError("failed dictionary preflight must not hash media")

    monkeypatch.setattr(adapter, "_stream_media_digest", unexpected_hash)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(cards, progress)

    assert exc_info.value.code == "media_name_collision"
    assert progress.events == []
    assert kotlin.requests == []
    assert not adapter._reserved_media_name_owners


def test_uploadable_dictionary_html_spellings_form_one_preflight_block(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = "dict__canonical.png"
    encoded_source = "dict__c&#97;nonical.png"
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "canonical.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")

    def marked(name: str) -> str:
        return f'<img class="anki-miner-dict-media" src="{name}">猫'

    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    required = {value for value in config.anki_fields.values() if value}
    kotlin.verify_fields = [
        "MainDefinition",
        *sorted(required - {"MainDefinition"}),
    ]
    adapter = _adapter(config, kotlin)
    cards = [
        *[_card(f"甲{index}", definition=marked(source)) for index in range(51)],
        *[_card(f"乙{index}", definition=marked(encoded_source)) for index in range(50)],
    ]

    def unexpected_hash(_path: Path) -> object:
        raise AssertionError("failed canonical block preflight must not hash media")

    monkeypatch.setattr(adapter, "_stream_media_digest", unexpected_hash)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(cards)

    assert exc_info.value.code == "note_batch_too_large"
    assert kotlin.requests == []


def test_missing_dictionary_html_spellings_keep_exact_preflight_identity(
    initialized_bridge_home: Path,
) -> None:
    source_spellings = [f"missing-dict__&#{'0' * leading_zeroes}122;.png" for leading_zeroes in range(101)]

    def marked(name: str) -> str:
        return f'<img class="anki-miner-dict-media" src="{name}">猫'

    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    required = {value for value in config.anki_fields.values() if value}
    kotlin.verify_fields = [
        "MainDefinition",
        *sorted(required - {"MainDefinition"}),
    ]
    adapter = _adapter(config, kotlin)
    cards = [_card(f"語{index}", definition=marked(source)) for index, source in enumerate(source_spellings)]

    plan = adapter._preflight_create_call(cards)

    assert plan.dictionary_media_paths == {}
    assert not kotlin.requests


def test_cached_dictionary_actual_name_cannot_be_reintroduced_as_source(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = "dict__cached-owner.png"
    preferred = _dictionary_provider_preferred_name(source)
    actual = f"{preferred}_provider.png"
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "cached-owner.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")

    def marked(name: str) -> str:
        return f'<img class="anki-miner-dict-media" src="{name}">definition'

    kotlin = FakeKotlinAnki()
    kotlin.media_renames[preferred] = actual
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert len(adapter.create_cards_batch([_card("猫", definition=marked(source))])) == 1
    prior_requests = len(kotlin.requests)

    def unexpected_hash(_path: Path) -> object:
        raise AssertionError("cached-name collision must fail before hashing")

    monkeypatch.setattr(adapter, "_stream_media_digest", unexpected_hash)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch([_card("犬", definition=marked(actual))])

    assert exc_info.value.code == "media_name_collision"
    assert len(kotlin.requests) == prior_requests


def test_cached_dictionary_source_spellings_share_preflight_block_identity(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = "dict__cached-spellings.png"
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "cached-spellings.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")

    def marked(name: str) -> str:
        return f'<img class="anki-miner-dict-media" src="{name}">猫'

    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()
    required = {value for value in config.anki_fields.values() if value}
    kotlin.verify_fields = [
        "MainDefinition",
        *sorted(required - {"MainDefinition"}),
    ]
    adapter = _adapter(config, kotlin)
    assert len(adapter.create_cards_batch([_card("初", definition=marked(source))])) == 1
    prior_requests = len(kotlin.requests)
    spellings = [f"dict__&#{'0' * leading_zeroes}99;ached-spellings.png" for leading_zeroes in range(101)]

    def unexpected_hash(_path: Path) -> object:
        raise AssertionError("cached source preflight must not hash media")

    monkeypatch.setattr(adapter, "_stream_media_digest", unexpected_hash)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(
            [_card(f"語{index}", definition=marked(spelling)) for index, spelling in enumerate(spellings)]
        )

    assert exc_info.value.code == "note_batch_too_large"
    assert len(kotlin.requests) == prior_requests


def test_reserved_missing_dictionary_name_rejects_card_provider_alias(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    audio = tmp_path / "reserved-alias.opus"
    audio.write_bytes(b"audio")
    requested = _content_addressed_name(audio.name, b"audio")
    preferred = Path(requested).stem
    reserved_name = f"{preferred}_provider.opus"
    definition = f'<img class="anki-miner-dict-media" src="{reserved_name}">definition'
    kotlin = FakeKotlinAnki()
    kotlin.media_renames[preferred] = reserved_name
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(
            [
                _card(
                    "猫",
                    definition=definition,
                    media=MediaData(audio_path=audio, audio_filename=audio.name),
                )
            ]
        )

    assert exc_info.value.code == "media_name_collision"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_cross_purpose_provider_prefix_collision_fails_before_any_callback(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from android_bridge import anki_adapter as anki_adapter_module
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    card_path = tmp_path / "cross-purpose.opus"
    card_path.write_bytes(b"audio")
    requested_card_name = _content_addressed_name(card_path.name, b"audio")
    card_prefix = Path(requested_card_name).stem
    dictionary_path = initialized_bridge_home / "dicts" / "dict" / "media" / "image.png"
    dictionary_path.parent.mkdir(parents=True, exist_ok=True)
    dictionary_path.write_bytes(b"image")
    monkeypatch.setattr(
        anki_adapter_module,
        "_dictionary_provider_preferred_name",
        lambda _source: card_prefix,
    )
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(
            [
                _card(
                    "猫",
                    definition=('<img class="anki-miner-dict-media" src="dict__image.png">'),
                    media=MediaData(
                        audio_path=card_path,
                        audio_filename=card_path.name,
                    ),
                )
            ]
        )

    assert exc_info.value.code == "media_name_collision"
    assert kotlin.requests == []
    assert adapter._reserved_media_name_owners == {}


def test_runtime_media_budget_counts_card_and_dictionary_growth_together(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from android_bridge import anki_adapter as anki_adapter_module
    from anki_miner.models import MediaData

    card_path = tmp_path / "growing.opus"
    card_path.write_bytes(b"a")
    dictionary_path = initialized_bridge_home / "dicts" / "dict" / "media" / "growing.png"
    dictionary_path.parent.mkdir(parents=True, exist_ok=True)
    dictionary_path.write_bytes(b"b")
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    original_prepare = adapter._prepare_card_media

    def grow_then_prepare(word_data_list: list[Any], work_budget: Any) -> Any:
        card_path.write_bytes(b"aaa")
        dictionary_path.write_bytes(b"bbb")
        return original_prepare(word_data_list, work_budget)

    monkeypatch.setattr(anki_adapter_module, "_MAX_CREATE_CALL_MEDIA_BYTES", 4)
    monkeypatch.setattr(adapter, "_prepare_card_media", grow_then_prepare)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(
            [
                _card(
                    "猫",
                    definition=('<img class="anki-miner-dict-media" src="dict__growing.png">'),
                    media=MediaData(
                        audio_path=card_path,
                        audio_filename=card_path.name,
                    ),
                )
            ]
        )

    assert exc_info.value.code == "create_call_too_large"
    assert kotlin.requests == []
    assert adapter._reserved_media_name_owners == {}


def test_runtime_media_budget_counts_dictionary_file_appearing_after_preflight(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from android_bridge import anki_adapter as anki_adapter_module
    from anki_miner.models import MediaData

    card_path = tmp_path / "existing.opus"
    card_path.write_bytes(b"aaa")
    dictionary_path = initialized_bridge_home / "dicts" / "dict" / "media" / "appeared.png"
    dictionary_path.parent.mkdir(parents=True, exist_ok=True)
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    original_prepare = adapter._prepare_card_media

    def appear_then_prepare(word_data_list: list[Any], work_budget: Any) -> Any:
        dictionary_path.write_bytes(b"bbb")
        return original_prepare(word_data_list, work_budget)

    monkeypatch.setattr(anki_adapter_module, "_MAX_CREATE_CALL_MEDIA_BYTES", 4)
    monkeypatch.setattr(adapter, "_prepare_card_media", appear_then_prepare)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter.create_cards_batch(
            [
                _card(
                    "猫",
                    definition=('<img class="anki-miner-dict-media" src="dict__appeared.png">'),
                    media=MediaData(
                        audio_path=card_path,
                        audio_filename=card_path.name,
                    ),
                )
            ]
        )

    assert exc_info.value.code == "create_call_too_large"
    assert kotlin.requests == []
    assert adapter._reserved_media_name_owners == {}


def test_same_purpose_different_owner_claim_rejects_provider_namespace(
    initialized_bridge_home: Path,
) -> None:
    def asset(
        suffix: str,
        *,
        requested: str,
        original: str,
    ) -> _MediaAsset:
        return _MediaAsset(
            asset_id=f"asset_{suffix * 32}",
            source_path=f"/tmp/{suffix}.opus",
            preferred_name=Path(requested).stem,
            requested_name=requested,
            original_name=original,
            purpose="card",
            media_kind="audio",
            expected_size_bytes=0,
            expected_sha256=suffix * 64,
        )

    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(BridgeProtocolError) as exc_info:
        adapter._store_assets(
            [
                asset("a", requested="alpha.opus", original="first.opus"),
                asset(
                    "b",
                    requested="beta.opus",
                    original="alpha_claim.opus",
                ),
            ]
        )

    assert exc_info.value.code == "media_name_collision"
    assert not kotlin.requests_for("ankiStoreMedia")


@pytest.mark.parametrize(
    "case",
    _source_path_cases(),
    ids=lambda case: case["id"],
)
def test_python_media_source_path_semantics_match_shared_contract_corpus(
    case: dict[str, Any],
) -> None:
    if case["semanticValid"]:
        assert (
            _expect_media_source_path(case["path"], context="shared corpus", code="invalid_anki_request")
            == case["path"]
        )
    else:
        with pytest.raises(BridgeProtocolError) as exc_info:
            _expect_media_source_path(case["path"], context="shared corpus", code="invalid_anki_request")
        assert exc_info.value.code == "invalid_anki_request"


def test_bounded_utf8_preserves_the_callers_error_code_for_a_surrogate() -> None:
    with pytest.raises(BridgeProtocolError) as exc_info:
        _expect_bounded_utf8(
            "\ud800",
            context="note field",
            max_bytes=64,
            code="invalid_note",
        )

    assert exc_info.value.code == "invalid_note"
    assert str(exc_info.value) == "note field contains an invalid Unicode scalar"
    assert isinstance(exc_info.value.__cause__, UnicodeEncodeError)


@pytest.mark.parametrize(
    "code",
    ["invalid_anki_request", "invalid_anki_response", "invalid_note"],
)
def test_strict_utf8_encoder_preserves_each_anki_boundary_code(code: str) -> None:
    with pytest.raises(BridgeProtocolError) as exc_info:
        _strict_utf8_bytes("\ud800", context="Anki test value", code=code)

    assert exc_info.value.code == code
    assert str(exc_info.value) == ("Anki test value contains an invalid Unicode scalar")
    assert isinstance(exc_info.value.__cause__, UnicodeEncodeError)


@pytest.mark.parametrize(
    ("note", "message"),
    [
        (
            {"fields": {"\ud800": "value"}, "tags": []},
            "Anki field name contains an invalid Unicode scalar",
        ),
        (
            {"fields": {"Expression": "\ud800"}, "tags": []},
            "Anki field 'Expression' value contains an invalid Unicode scalar",
        ),
        (
            {"fields": {"Expression": "猫"}, "tags": ["\ud800"]},
            "Anki tag contains an invalid Unicode scalar",
        ),
    ],
    ids=["field-name", "field-value", "tag"],
)
def test_note_content_rejects_surrogates_with_invalid_note(note: dict[str, object], message: str) -> None:
    with pytest.raises(BridgeProtocolError) as exc_info:
        AndroidAnkiAdapter._validated_note_content(note)

    assert exc_info.value.code == "invalid_note"
    assert str(exc_info.value) == message
    assert isinstance(exc_info.value.__cause__, UnicodeEncodeError)


def test_media_name_and_hash_input_reject_surrogates_with_caller_codes() -> None:
    with pytest.raises(BridgeProtocolError) as basename_error:
        _expect_media_basename(
            "media\ud800.png",
            context="storeMedia requestedFilename",
            code="invalid_anki_request",
        )
    assert basename_error.value.code == "invalid_anki_request"
    assert isinstance(basename_error.value.__cause__, UnicodeEncodeError)

    with pytest.raises(BridgeProtocolError) as logical_name_error:
        _dictionary_provider_preferred_name("dict__\ud800.png")
    assert logical_name_error.value.code == "invalid_note"
    assert isinstance(logical_name_error.value.__cause__, UnicodeEncodeError)


def test_store_media_rejects_surrogate_source_path_as_invalid_request(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    asset = _MediaAsset(
        asset_id="asset_" + "a" * 32,
        source_path="/tmp/media\ud800.opus",
        preferred_name="media",
        requested_name="media.opus",
        original_name="media.opus",
        purpose="card",
        media_kind="audio",
        expected_size_bytes=0,
        expected_sha256="0" * 64,
    )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin)._store_assets([asset])

    assert exc_info.value.code == "invalid_anki_request"
    assert isinstance(exc_info.value.__cause__, UnicodeEncodeError)
    assert not kotlin.requests


@pytest.mark.parametrize(
    "case",
    [
        "built-field-name",
        "built-field-value",
        "tag",
        "definition",
        "media-filename",
        "media-source-path",
    ],
)
def test_create_preflight_rejects_python_surrogates_before_callbacks(
    case: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services import anki_note_builder

    config = _config(initialized_bridge_home)
    card = _card("猫")
    if case == "tag":
        config = replace(config, anki_tags="valid \ud800")
    elif case == "definition":
        card = _card("猫", definition="definition\ud800")
    elif case == "media-filename":
        card = _card(
            "猫",
            media=MediaData(
                audio_filename="audio\ud800.opus",
                audio_path=tmp_path / "audio.opus",
            ),
        )
    elif case == "media-source-path":
        card = _card(
            "猫",
            media=MediaData(
                audio_filename="audio.opus",
                audio_path=Path(f"{tmp_path}/audio\ud800.opus"),
            ),
        )
    else:
        original_build_note = anki_note_builder.build_note

        def build_note_with_surrogate(*args: object, **kwargs: object) -> Any:
            built = original_build_note(*args, **kwargs)
            note = {
                **built.note,
                "fields": dict(built.note["fields"]),
                "tags": list(built.note["tags"]),
            }
            if case == "built-field-name":
                note["fields"]["\ud800"] = "value"
            else:
                note["fields"]["Definition"] = "\ud800"
            return replace(built, note=note)

        monkeypatch.setattr(anki_note_builder, "build_note", build_note_with_surrogate)

    kotlin = FakeKotlinAnki()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(config, kotlin).create_cards_batch([card])

    assert exc_info.value.code == "invalid_note"
    assert isinstance(exc_info.value.__cause__, UnicodeEncodeError)
    assert not kotlin.requests


def test_store_media_source_path_utf8_limit_is_exact_before_provider_callback(
    initialized_bridge_home: Path,
) -> None:
    def asset(source_path: str) -> _MediaAsset:
        return _MediaAsset(
            asset_id="asset_" + "a" * 32,
            source_path=source_path,
            preferred_name="media",
            requested_name="media.opus",
            original_name="media.opus",
            purpose="card",
            media_kind="audio",
            expected_size_bytes=0,
            expected_sha256="0" * 64,
        )

    exact_path = "/" + "a" * (_MAX_MEDIA_SOURCE_PATH_UTF8_BYTES - 1)
    exact_kotlin = FakeKotlinAnki()
    exact_outcome = _adapter(_config(initialized_bridge_home), exact_kotlin)._store_assets([asset(exact_path)])
    assert len(exact_path.encode("utf-8")) == _MAX_MEDIA_SOURCE_PATH_UTF8_BYTES
    assert exact_outcome.error is not None
    assert len(exact_kotlin.requests_for("ankiStoreMedia")) == 1

    oversized_path = "/" + "界" * (_MAX_MEDIA_SOURCE_PATH_UTF8_BYTES // 3 + 1)
    assert len(oversized_path) < _MAX_MEDIA_SOURCE_PATH_UTF8_BYTES
    assert len(oversized_path.encode("utf-8")) > _MAX_MEDIA_SOURCE_PATH_UTF8_BYTES
    oversized_kotlin = FakeKotlinAnki()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), oversized_kotlin)._store_assets([asset(oversized_path)])
    assert exc_info.value.code == "invalid_anki_request"
    assert not oversized_kotlin.requests


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

    created_ids = adapter.create_cards_batch([_card("猫", media=media_a), _card("犬", media=media_b)])

    assert len(created_ids) == 2
    assert adapter.last_media_store_failures == 1
    assert media_a.screenshot_filename == provider_name
    assert media_b.screenshot_filename == provider_name
    store_payload = kotlin.requests_for("ankiStoreMedia")[0]["payload"]
    assert len(store_payload["assets"]) == 1
    assert store_payload["assets"][0]["preferredName"] == preferred
    assert store_payload["assets"][0]["requestedFilename"] == requested
    assert store_payload["assets"][0]["sourcePath"] == str(image.resolve())
    assert store_payload["assets"][0]["expectedSizeBytes"] == len(b"same image")
    assert store_payload["assets"][0]["expectedSha256"] == hashlib.sha256(b"same image").hexdigest()
    note_fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert all(note["fields"]["Picture"] == f'<img src="{provider_name}">' for note in note_fields)
    asset_id = store_payload["assets"][0]["assetId"]
    assert [note["mediaBindings"] for note in note_fields] == [
        [{"assetId": asset_id, "actualFilename": provider_name}],
        [{"assetId": asset_id, "actualFilename": provider_name}],
    ]


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
        len(
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
        )
        == 1
    )
    requested = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"][0]["requestedFilename"]
    expected_digest = hashlib.sha1(content).hexdigest()[:12]
    assert requested == f"streamed_{expected_digest}.opus"


def test_media_for_an_unmapped_field_is_neither_stored_nor_bound(initialized_bridge_home: Path, tmp_path: Path) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "unmapped.opus"
    audio.write_bytes(b"audio")
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "audio": ""},
    )
    kotlin = FakeKotlinAnki()

    assert (
        len(
            _adapter(config, kotlin).create_cards_batch(
                [
                    _card(
                        "猫",
                        media=MediaData(
                            audio_path=audio,
                            audio_filename=audio.name,
                        ),
                    )
                ]
            )
        )
        == 1
    )

    # The engine skips media whose Anki field is unmapped, so the asset is not
    # even prepared: no store call is made, and nothing binds. Previously the
    # bytes were uploaded and then left unreferenced.
    assert kotlin.requests_for("ankiStoreMedia") == []
    note = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    assert note["fields"].get("SentenceAudio") in {None, ""}
    assert note["mediaBindings"] == []


@pytest.mark.parametrize(
    ("note_key", "definition", "extra_fields"),
    [
        (
            "definition",
            '<img class="anki-miner-dict-media" src="dict__unmapped.png">',
            {},
        ),
        (
            "glossary",
            "definition",
            {"glossary": ('<img class="anki-miner-dict-media" ' 'src="dict__unmapped.png">')},
        ),
    ],
    ids=["definition", "glossary"],
)
def test_dictionary_media_for_unmapped_fields_is_neither_stored_nor_bound(
    note_key: str,
    definition: str,
    extra_fields: dict[str, str],
    initialized_bridge_home: Path,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "unmapped.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    base = _config(initialized_bridge_home)
    mapped_field = base.anki_fields.get(note_key, "")
    config = replace(
        base,
        anki_fields={**base.anki_fields, note_key: ""},
    )
    card = replace(
        _card("猫", definition=definition),
        extra_fields=extra_fields,
    )
    kotlin = FakeKotlinAnki()

    assert len(_adapter(config, kotlin).create_cards_batch([card])) == 1

    assert kotlin.requests_for("ankiStoreMedia") == []
    note = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    if mapped_field:
        assert mapped_field not in note["fields"]
    assert note["mediaBindings"] == []


@pytest.mark.parametrize("note_key", ["definition", "glossary"])
def test_oversized_unmapped_dictionary_html_does_not_reject_materialized_note(
    note_key: str,
    initialized_bridge_home: Path,
) -> None:
    marked = '<img class="anki-miner-dict-media" src="dict__unused.png">' + "x" * _MAX_FIELD_VALUE_UTF8_BYTES
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, note_key: ""},
    )
    card = _card("猫", definition=marked if note_key == "definition" else "definition")
    if note_key == "glossary":
        card = replace(card, extra_fields={"glossary": marked})
    kotlin = FakeKotlinAnki()

    assert len(_adapter(config, kotlin).create_cards_batch([card])) == 1

    assert kotlin.requests_for("ankiStoreMedia") == []
    assert kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["mediaBindings"] == []


def test_media_binding_bytes_are_part_of_the_note_content_budget() -> None:
    asset_id = "asset_" + "a" * 32
    actual_filename = "voice.opus"
    fields, tags, content_bytes = AndroidAnkiAdapter._validated_note_content(
        {"fields": {"Expression": "猫"}, "tags": []},
        [(asset_id, actual_filename)],
    )

    assert fields == {"Expression": "猫"}
    assert tags == []
    assert content_bytes == sum(len(value.encode("utf-8")) for value in ("Expression", "猫", asset_id, actual_filename))

    too_many = [(f"asset_{index:032x}", "x") for index in range(_MAX_MEDIA_BINDINGS_PER_NOTE + 1)]
    with pytest.raises(BridgeProtocolError, match="too many media bindings") as error:
        AndroidAnkiAdapter._validated_note_content(
            {"fields": {"Expression": "猫"}, "tags": []},
            too_many,
        )
    assert error.value.code == "note_too_large"


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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", media=media)])

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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", definition=definition)])

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
        len(
            _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
                [_card("猫", media=first_media), _card("犬", media=second_media)]
            )
        )
        == 2
    )

    assets = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"]
    assert len(assets) == 1
    assert assets[0]["sourcePath"] == str(first.resolve())
    assert Path(assets[0]["preferredName"]).suffix == ""
    assert first_media.audio_filename == second_media.audio_filename


def test_media_store_is_bounded_to_fifty_assets_per_callback(initialized_bridge_home: Path, tmp_path: Path) -> None:
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

    assert len(_adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)) == 51

    requests = kotlin.requests_for("ankiStoreMedia")
    assert [len(request["payload"]["assets"]) for request in requests] == [50, 1]
    assert all("requestedFilename" in asset for request in requests for asset in request["payload"]["assets"])
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

    assert [len(request["payload"]["assets"]) for request in kotlin.requests_for("ankiStoreMedia")] == [50, 1]
    assert all(media.audio_filename.endswith("_provider.opus") for media in media_rows[:50])
    assert media_rows[50].audio_filename == "cancel-50.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize("error_code", ["cancelled", "timeout"])
def test_nonretryable_partial_media_error_preserves_aligned_successes(
    error_code: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError
    from anki_miner.models import MediaData

    class PartialErrorKotlin(FakeKotlinAnki):
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
                            "actualFilename": (f"{first['preferredName']}_provider.opus"),
                        },
                        {
                            "assetId": second["assetId"],
                            "status": "notAttempted",
                        },
                    ],
                    "error": {
                        "code": error_code,
                        "message": "stopped after first snapshot",
                        "retryable": False,
                    },
                },
            )

    paths = [tmp_path / "partial-a.opus", tmp_path / "partial-b.opus"]
    for index, path in enumerate(paths):
        path.write_bytes(f"audio-{index}".encode())
    media = [MediaData(audio_path=path, audio_filename=path.name) for path in paths]
    kotlin = PartialErrorKotlin()
    expected_error: type[BaseException] = AnkiOperationCancelled if error_code == "cancelled" else AnkiConnectionError

    with pytest.raises(expected_error):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media[0]), _card("犬", media=media[1])]
        )

    assert media[0].audio_filename.endswith("_provider.opus")
    assert media[1].audio_filename == "partial-b.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


def _parser_media_assets(count: int) -> list[_MediaAsset]:
    return [
        _MediaAsset(
            asset_id=f"asset_{index:032x}",
            source_path=f"/tmp/parser-media-{index}.opus",
            preferred_name=f"parser_media_{index}",
            requested_name=f"parser_media_{index}.opus",
            original_name=f"parser-media-{index}.opus",
            purpose="card",
            media_kind="audio",
            expected_size_bytes=0,
            expected_sha256="0" * 64,
        )
        for index in range(count)
    ]


@pytest.mark.parametrize(
    "statuses",
    [
        ["uncertain"],
        ["stored", "uncertain"],
        ["stored", "uncertain", "notAttempted"],
    ],
    ids=["only", "final", "strict-suffix"],
)
def test_uncertain_media_parser_accepts_only_or_final_aligned_uncertainty(
    statuses: list[str], initialized_bridge_home: Path
) -> None:
    assets = _parser_media_assets(len(statuses))
    rows: list[dict[str, object]] = []
    for asset, status in zip(assets, statuses, strict=True):
        row: dict[str, object] = {"assetId": asset.asset_id, "status": status}
        if status == "stored":
            row["actualFilename"] = f"{asset.preferred_name}_provider.opus"
        rows.append(row)
    adapter = _adapter(_config(initialized_bridge_home), FakeKotlinAnki())

    outcome = adapter._parse_store_media_result(
        {
            "runId": RUN_ID,
            "requestId": "anki_" + "b" * 32,
            "results": rows,
            "error": {
                "code": "post_commit_uncertain",
                "message": "media insert outcome is unknown",
                "retryable": False,
            },
        },
        assets,
    )

    expected_stored = {
        asset.asset_id: f"{asset.preferred_name}_provider.opus"
        for asset, status in zip(assets, statuses, strict=True)
        if status == "stored"
    }
    assert outcome.stored == expected_stored
    assert outcome.error is not None
    assert outcome.error.code == "post_commit_uncertain"
    assert outcome.error.retryable is False
    assert set(expected_stored.values()) <= adapter._stored_media_name_owners.keys()


@pytest.mark.parametrize(
    ("statuses", "error", "message"),
    [
        (
            ["uncertain"],
            {"code": "timeout", "message": "wrong code", "retryable": False},
            "Uncertain media requires a non-retryable post-commit error",
        ),
        (
            ["uncertain"],
            {
                "code": "post_commit_uncertain",
                "message": "unsafe retry",
                "retryable": True,
            },
            "Uncertain media requires a non-retryable post-commit error",
        ),
        (
            ["uncertain", "stored"],
            {
                "code": "post_commit_uncertain",
                "message": "nonterminal uncertainty",
                "retryable": False,
            },
            "An uncertain storeMedia row must end provider attempts",
        ),
        (
            ["uncertain"],
            None,
            "uncertain/notAttempted storeMedia rows require a top-level error",
        ),
        (
            ["notAttempted"],
            {
                "code": "post_commit_uncertain",
                "message": "orphan uncertainty",
                "retryable": False,
            },
            "A post-commit media error requires an uncertain row",
        ),
    ],
    ids=[
        "wrong-code",
        "retryable",
        "nonterminal",
        "missing-error",
        "orphan-post-commit",
    ],
)
def test_uncertain_media_parser_rejects_invalid_temporal_shapes(
    statuses: list[str],
    error: dict[str, object] | None,
    message: str,
    initialized_bridge_home: Path,
) -> None:
    assets = _parser_media_assets(len(statuses))
    rows: list[dict[str, object]] = []
    for asset, status in zip(assets, statuses, strict=True):
        row: dict[str, object] = {"assetId": asset.asset_id, "status": status}
        if status == "stored":
            row["actualFilename"] = f"{asset.preferred_name}_provider.opus"
        rows.append(row)
    adapter = _adapter(_config(initialized_bridge_home), FakeKotlinAnki())

    with pytest.raises(BridgeProtocolError, match=message) as exc_info:
        adapter._parse_store_media_result(
            {
                "runId": RUN_ID,
                "requestId": "anki_" + "b" * 32,
                "results": rows,
                "error": error,
            },
            assets,
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert not adapter._stored_media_name_owners


def test_full_create_preserves_stored_media_before_aligned_uncertainty(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.exceptions import AnkiConnectionError
    from anki_miner.models import MediaData

    class UncertainMediaKotlin(FakeKotlinAnki):
        actual_filename: str | None = None

        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            first, second, third = request["assets"]
            self.actual_filename = f"{first['preferredName']}_provider.opus"
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": first["assetId"],
                            "status": "stored",
                            "actualFilename": self.actual_filename,
                        },
                        {"assetId": second["assetId"], "status": "uncertain"},
                        {"assetId": third["assetId"], "status": "notAttempted"},
                    ],
                    "error": {
                        "code": "post_commit_uncertain",
                        "message": "provider outcome could not be proven",
                        "retryable": False,
                    },
                },
            )

    paths = [tmp_path / f"uncertain-{index}.opus" for index in range(3)]
    for index, path in enumerate(paths):
        path.write_bytes(f"audio-{index}".encode())
    media_rows = [MediaData(audio_path=path, audio_filename=path.name) for path in paths]
    kotlin = UncertainMediaKotlin()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(AnkiConnectionError, match="could not be proven"):
        adapter.create_cards_batch([_card(f"語{index}", media=media) for index, media in enumerate(media_rows)])

    assert kotlin.actual_filename is not None
    assert media_rows[0].audio_filename == kotlin.actual_filename
    assert media_rows[1].audio_filename == "uncertain-1.opus"
    assert media_rows[2].audio_filename == "uncertain-2.opus"
    assert adapter._stored_media_name_owners[kotlin.actual_filename] == (
        "card",
        "uncertain-0.opus",
    )
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    ("statuses", "error"),
    [
        (
            ["notAttempted", "stored"],
            {"code": "cancelled", "message": "bad", "retryable": False},
        ),
        (["stored", "notAttempted"], None),
        (
            ["stored", "notAttempted"],
            {"code": "timeout", "message": "unsafe retry", "retryable": True},
        ),
        (
            ["stored", "stored"],
            {"code": "cancelled", "message": "orphan", "retryable": False},
        ),
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
                    row["actualFilename"] = f"{asset['preferredName']}_provider.opus"
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
                                "actualFilename": (f"{asset['preferredName']}_provider.opus"),
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
    assert all(media.audio_filename == f"align-{index}.opus" for index, media in enumerate(media_rows))
    assert not kotlin.requests_for("ankiCreateNotes")


def test_media_provider_prefix_overlap_fails_before_callback(
    initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from android_bridge import anki_adapter as anki_adapter_module
    from anki_miner.models import MediaData

    monkeypatch.setattr(
        anki_adapter_module,
        "_content_addressed_name_from_digest",
        lambda filename, _digest: filename,
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
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "media_name_collision"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert all(media.audio_filename == name for media, name in zip(media_rows, names, strict=True))
    assert not kotlin.requests_for("ankiCreateNotes")


def test_declared_media_failure_is_nonfatal_and_omits_reference(initialized_bridge_home: Path, tmp_path: Path) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    preferred = Path(_content_addressed_name("clip.opus", b"audio")).stem
    kotlin = FakeKotlinAnki()
    kotlin.failed_media_names.add(preferred)
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert (
        len(
            adapter.create_cards_batch(
                [
                    _card(
                        "猫",
                        media=MediaData(audio_path=audio, audio_filename="clip.opus"),
                    )
                ]
            )
        )
        == 1
    )
    assert adapter.last_media_store_failures == 1
    fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["fields"]
    assert fields["SentenceAudio"] == ""
    assert kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["mediaBindings"] == []


def test_declared_dictionary_media_failure_is_nonfatal_and_omits_reference(
    initialized_bridge_home: Path,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "failed.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    source = "dict__failed.png"
    definition = f'<img class="anki-miner-dict-media" src="{source}">'
    kotlin = FakeKotlinAnki()
    kotlin.failed_media_names.add(_dictionary_provider_preferred_name(source))
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert len(adapter.create_cards_batch([_card("猫", definition=definition)])) == 1

    assert adapter.last_media_store_failures == 1
    note = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    assert note["fields"]["MainDefinition"] == '<img class="anki-miner-dict-media">'
    assert note["mediaBindings"] == []


def test_missing_dictionary_media_is_nonfatal_and_omits_reference(
    initialized_bridge_home: Path,
) -> None:
    definition = '<img class="anki-miner-dict-media" src="dict__missing-at-create.png">'
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert len(adapter.create_cards_batch([_card("猫", definition=definition)])) == 1

    assert kotlin.requests_for("ankiStoreMedia") == []
    assert adapter.last_media_store_failures == 1
    note = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    assert note["fields"]["MainDefinition"] == '<img class="anki-miner-dict-media">'
    assert note["mediaBindings"] == []


def test_unreadable_dictionary_media_is_nonfatal_and_omits_reference(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "unreadable.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    definition = '<img class="anki-miner-dict-media" src="dict__unreadable.png">'
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    def unreadable(_path: Path, _budget: object) -> object:
        raise OSError("read denied")

    monkeypatch.setattr(adapter, "_stream_media_digest", unreadable)

    assert len(adapter.create_cards_batch([_card("猫", definition=definition)])) == 1

    assert kotlin.requests_for("ankiStoreMedia") == []
    assert adapter.last_media_store_failures == 1
    note = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    assert note["fields"]["MainDefinition"] == '<img class="anki-miner-dict-media">'
    assert note["mediaBindings"] == []


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
    assert asset["preferredName"] == _dictionary_provider_preferred_name("dict__pic.png")
    assert asset["requestedFilename"] == "dict__pic.png"
    assert asset["sourcePath"] == str(media_path.resolve())
    actual = kotlin._media_acknowledgements_by_run[RUN_ID][asset["assetId"]]
    assert [request["payload"]["notes"][0]["mediaBindings"] for request in kotlin.requests_for("ankiCreateNotes")] == [
        [{"assetId": asset["assetId"], "actualFilename": actual}],
        [{"assetId": asset["assetId"], "actualFilename": actual}],
    ]


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
        len(_adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", definition=definition)]))
        == 1
    )

    asset = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"][0]
    assert asset["preferredName"] == _dictionary_provider_preferred_name(filename)
    assert asset["requestedFilename"] == filename
    fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["fields"]
    assert fields["MainDefinition"] == definition
    assert kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["mediaBindings"] == [
        {"assetId": asset["assetId"], "actualFilename": filename}
    ]


def test_remote_yomitan_image_never_reaches_anki_or_media_callbacks(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.services.dictionary.yomitan_renderer import (
        structured_content_to_html,
    )

    remote = "https://tracker.example.test/glossary/cat.png?source=anki"
    definition = structured_content_to_html(
        {
            "tag": "div",
            "content": [
                "ordinary text before ",
                {
                    "tag": "img",
                    "path": remote,
                    "alt": "remote illustration",
                    "appearance": "monochrome",
                },
                " ordinary text after",
            ],
        },
        dict_id="custom-dictionary",
        media_collector=set(),
    )
    assert remote in definition

    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert len(adapter.create_cards_batch([_card("猫", definition=definition)])) == 1

    assert kotlin.requests_for("ankiStoreMedia") == []
    create_request = kotlin.requests_for("ankiCreateNotes")[0]
    encoded_request = json.dumps(create_request, ensure_ascii=False)
    stored_definition = create_request["payload"]["notes"][0]["fields"]["MainDefinition"]
    assert remote not in encoded_request
    assert "tracker.example.test" not in encoded_request
    assert "ordinary text before" in stored_definition
    assert "ordinary text after" in stored_definition
    assert "remote illustration" in stored_definition


def test_dictionary_media_random_names_rewrite_marked_html_and_strip_unmarked_src(
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
    glossary = '<span><img class="anki-miner-dict-media" src="dict__pic&amp;one.png"></span>'
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "glossary": "Glossary"},
    )
    first_card = replace(_card("猫", definition=definition), extra_fields={"glossary": glossary})
    kotlin = FakeKotlinAnki()
    first_preferred = _dictionary_provider_preferred_name("dict__pic&one.png")
    second_preferred = _dictionary_provider_preferred_name("dict__other.svg")
    kotlin.media_renames = {
        first_preferred: f"{first_preferred}_random.jpeg",
        second_preferred: f"{second_preferred}_random.webp",
    }
    adapter = _adapter(config, kotlin)

    assert len(adapter.create_cards_batch([first_card])) == 1
    assert (
        len(
            adapter.create_cards_batch(
                [
                    replace(
                        _card("犬", definition=definition),
                        extra_fields={"glossary": glossary},
                    )
                ]
            )
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
        '<img class="ordinary">'
        f'<img src="{second_preferred}_random.webp" class="anki-miner-dict-media">'
    )
    expected_glossary = '<span><img class="anki-miner-dict-media" ' f'src="{first_preferred}_random.jpeg"></span>'
    for request in kotlin.requests_for("ankiCreateNotes"):
        note = request["payload"]["notes"][0]
        fields = note["fields"]
        assert fields["MainDefinition"] == expected_definition
        assert fields["Glossary"] == expected_glossary
        assert note["mediaBindings"] == [
            {
                "assetId": assets[0]["assetId"],
                "actualFilename": f"{first_preferred}_random.jpeg",
            },
            {
                "assetId": assets[1]["assetId"],
                "actualFilename": f"{second_preferred}_random.webp",
            },
        ]
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


def test_committed_routing_uncertainty_retains_row_id_and_classification(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [
        (
            ["created", "committedFailed", "notAttempted"],
            {
                "code": "post_commit_uncertain",
                "message": "card deck could not be read back",
                "retryable": False,
            },
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(AnkiConnectionError, match="could not be read back") as exc_info:
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert isinstance(exc_info.value.__cause__, AnkiCallbackError)
    assert exc_info.value.__cause__.code == "post_commit_uncertain"
    assert adapter.last_created_note_ids == [1000, 1001]
    assert adapter.last_skipped_duplicates == 0


def test_unknown_post_commit_state_never_invents_id_or_updates_vocab_cache(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError
    from anki_miner.models import AnkiWriteState

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
    assert adapter.anki_write_state is AnkiWriteState.NOTE_WRITE_UNCERTAIN


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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫")])

    assert exc_info.value.code == "invalid_anki_response"
    assert kotlin.next_note_id == 1000


def test_misaligned_create_results_are_protocol_errors(
    initialized_bridge_home: Path,
) -> None:
    class MisalignedKotlin(FakeKotlinAnki):
        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            self._consume_duplicate_baseline(request)
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
        _adapter(_config(initialized_bridge_home), MisalignedKotlin()).create_cards_batch([_card("猫")])
    assert exc_info.value.code == "mismatched_callback_response"


def test_no_bridge_module_imports_engine_before_bootstrap() -> None:
    bridge_file = Path(__file__).resolve().parents[3] / "app/src/main/python/android_bridge/anki_adapter.py"
    source_lines = bridge_file.read_text(encoding="utf-8").splitlines()

    assert not any(line.startswith(("import anki_miner", "from anki_miner")) for line in source_lines)


@pytest.mark.parametrize("code", ["api_disabled", "permission_required", "field_missing"])
def test_user_action_and_field_errors_are_setup_failures(code: str, initialized_bridge_home: Path) -> None:
    from anki_miner.exceptions import SetupError

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (code, f"setup: {code}", False)

    with pytest.raises(SetupError, match=code):
        _adapter(
            _config(initialized_bridge_home),
            kotlin,
            target_verified=False,
        ).verify_card_target()


@pytest.mark.parametrize("operation", ["verifyTarget", "scanFirstFields", "storeMedia", "createNotes"])
def test_cancelled_callbacks_escape_as_bridge_cancellation(
    operation: str, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    kotlin = FakeKotlinAnki()
    kotlin.errors[operation] = ("cancelled", "cancelled by user", False)
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        target_verified=operation != "verifyTarget",
    )
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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", media=media)])

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_callback_wide_media_post_commit_error_requires_aligned_uncertainty(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "uncertain-callback.opus"
    audio.write_bytes(b"audio")
    media = MediaData(audio_path=audio, audio_filename=audio.name)
    kotlin = FakeKotlinAnki()
    kotlin.errors["storeMedia"] = (
        "post_commit_uncertain",
        "callback-wide uncertainty is unsafe",
        False,
    )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", media=media)])

    assert exc_info.value.code == "invalid_anki_response"
    assert str(exc_info.value) == ("Post-commit uncertainty requires an aligned storeMedia result")
    assert len(kotlin.requests_for("ankiStoreMedia")) == 1
    assert media.audio_filename == "uncertain-callback.opus"
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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", media=media)])

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    "returned_name",
    [
        "[sound:clip.opus]",
        "[SoUnD:clip.opus]",
        '<img src="clip.opus">',
        '<ImG src="clip.opus">',
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
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([_card("猫", media=media)])

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
                second_preferred if collision_kind == "reserved" else f"{Path(second_preferred).stem}_provider.opus"
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
        _adapter(_config(initialized_bridge_home), CollidingKotlin()).create_cards_batch(
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

    assert len(adapter.create_cards_batch([_card("猫"), _card("犬")])) == 1
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
            {
                "code": "write_failed",
                "message": "wrong temporal code",
                "retryable": False,
            },
        ),
        (
            ["notAttempted"],
            {
                "code": "post_commit_uncertain",
                "message": "orphan uncertainty",
                "retryable": False,
            },
        ),
        (
            ["created", "failed", "notAttempted"],
            {
                "code": "post_commit_uncertain",
                "message": "earlier commit is not a carrier",
                "retryable": False,
            },
        ),
        (
            ["committedFailed", "notAttempted"],
            {"code": "cancelled", "message": "known commit", "retryable": False},
        ),
        (
            ["failed", "notAttempted"],
            {"code": "cancelled", "message": "unsafe retry", "retryable": True},
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
            self._consume_duplicate_baseline(request)
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
        _adapter(_config(initialized_bridge_home), DuplicateIdKotlin()).create_cards_batch([_card("猫"), _card("犬")])


def test_created_note_ids_must_remain_unique_across_batches(
    initialized_bridge_home: Path,
) -> None:
    class ReusedIdKotlin(FakeKotlinAnki):
        def __init__(self) -> None:
            super().__init__()
            self.batch = 0

        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            self._consume_duplicate_baseline(request)
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

    kotlin = ReusedIdKotlin()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    with pytest.raises(BridgeProtocolError, match="earlier batch"):
        adapter.create_cards_batch([_card(f"語{index}") for index in range(101)])

    assert adapter.last_created_note_ids == list(range(1, 101))
    adapter.close()
    assert kotlin.release_acknowledgements == [False]


def test_partial_create_cancellation_remains_row_local_nonretryable_cancellation(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [
        (
            ["created", "failed", "notAttempted"],
            {"code": "cancelled", "message": "user stopped", "retryable": False},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    from anki_miner.exceptions import AnkiConnectionError

    with pytest.raises(AnkiConnectionError, match="user stopped") as exc_info:
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert exc_info.value.code == "cancelled"  # type: ignore[attr-defined]
    assert exc_info.value.retryable is False  # type: ignore[attr-defined]
    assert isinstance(exc_info.value.__cause__, AnkiOperationCancelled)
    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 0


def test_cancellation_between_create_callbacks_is_a_nonretryable_partial_error(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: bool(kotlin.requests_for("ankiCreateNotes")),
    )

    with pytest.raises(AnkiConnectionError) as exc_info:
        adapter.create_cards_batch([_card(f"語{index}") for index in range(101)])

    assert exc_info.value.retryable is False  # type: ignore[attr-defined]
    assert exc_info.value.code == "cancelled"  # type: ignore[attr-defined]
    assert isinstance(exc_info.value.__cause__, AnkiOperationCancelled)
    assert adapter.last_created_note_ids == list(range(1000, 1100))
    assert len(kotlin.requests_for("ankiCreateNotes")) == 1


def test_vendored_episode_processor_harvests_ids_on_intercallback_cancellation(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # episode_processor now imports anki_service at module level (for the
    # transient-transport classifier), which pulls requests. The lean host-test
    # env has no runtime deps; the runtime-host lane still runs this for real.
    pytest.importorskip("requests")
    from anki_miner.orchestration.episode_processor import (
        EpisodeProcessor,
        _EpisodeContext,
    )

    class Presenter:
        def __init__(self) -> None:
            self.errors: list[str] = []

        def show_error(self, message: str) -> None:
            self.errors.append(message)

    kotlin = FakeKotlinAnki()
    monkeypatch.delenv("ANKI_MINER_KEEP_TEMP", raising=False)
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: bool(kotlin.requests_for("ankiCreateNotes")),
    )
    presenter = Presenter()
    processor = EpisodeProcessor.__new__(EpisodeProcessor)
    processor.anki_service = adapter
    processor.presenter = presenter
    processor._external_cancel = None
    processor.check_dictionary_staleness = lambda: None
    # _run_pipeline gained a third pre-flight gate at this pin; it asks the
    # definition service for a usable offline provider, which this partial
    # processor has no reason to own.
    processor.check_offline_dictionary = lambda: None
    processor._preflight_card_target = lambda: None
    run_temp = tmp_path / "partial-run"

    def allocate_temp() -> Path:
        run_temp.mkdir()
        return run_temp

    processor._allocate_run_temp_folder = allocate_temp
    ctx = _EpisodeContext(
        start_time=time.time(),
        video_file_str="episode.mkv",
        subtitle_file_str="episode.srt",
        episode_name="Episode 1",
        series_name="Series",
        source_label="Series - Episode 1",
    )
    cards = [_card(f"語{index}") for index in range(101)]

    def body(_run_temp: Path) -> Any:
        created_ids = adapter.create_cards_batch(cards)
        return ctx.build_result(
            cards_created=len(created_ids),
            card_ids=list(adapter.last_created_note_ids),
        )

    result = processor._run_pipeline(ctx, None, body)

    assert result.cards_created == 100
    assert result.card_ids == list(range(1000, 1100))
    assert any("cancelled after 100 note(s)" in error for error in result.errors)
    assert any("remain in Anki" in error for error in result.errors)
    assert presenter.errors
    assert not run_temp.exists()


def test_vendored_episode_processor_preserves_clean_prewrite_cancellation(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # episode_processor now imports anki_service at module level (for the
    # transient-transport classifier), which pulls requests. The lean host-test
    # env has no runtime deps; the runtime-host lane still runs this for real.
    pytest.importorskip("requests")
    from anki_miner.orchestration.episode_processor import (
        EpisodeProcessor,
        _EpisodeContext,
    )

    class Presenter:
        def __init__(self) -> None:
            self.errors: list[str] = []

        def show_error(self, message: str) -> None:
            self.errors.append(message)

    kotlin = FakeKotlinAnki()
    monkeypatch.delenv("ANKI_MINER_KEEP_TEMP", raising=False)
    adapter = _adapter(_config(initialized_bridge_home), kotlin, cancellation_check=lambda: True)
    presenter = Presenter()
    processor = EpisodeProcessor.__new__(EpisodeProcessor)
    processor.anki_service = adapter
    processor.presenter = presenter
    processor._external_cancel = None
    processor.check_dictionary_staleness = lambda: None
    # _run_pipeline gained a third pre-flight gate at this pin; it asks the
    # definition service for a usable offline provider, which this partial
    # processor has no reason to own.
    processor.check_offline_dictionary = lambda: None
    processor._preflight_card_target = lambda: None
    run_temp = tmp_path / "cancelled-run"

    def allocate_temp() -> Path:
        run_temp.mkdir()
        return run_temp

    processor._allocate_run_temp_folder = allocate_temp
    ctx = _EpisodeContext(
        start_time=time.time(),
        video_file_str="episode.mkv",
        subtitle_file_str="episode.srt",
        episode_name="Episode 1",
        series_name="Series",
        source_label="Series - Episode 1",
    )

    def body(_run_temp: Path) -> Any:
        adapter.create_cards_batch([_card("猫")])
        raise AssertionError("clean cancellation must escape before this line")

    with pytest.raises(AnkiOperationCancelled):
        processor._run_pipeline(ctx, None, body)

    assert adapter.last_created_note_ids == []
    assert not kotlin.requests
    assert not presenter.errors
    assert not run_temp.exists()


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

    assert ("bold_target_in_sentence=on: precomputed bold used on 1/2 cards " "(escape fallback: 1)") in caplog.messages
