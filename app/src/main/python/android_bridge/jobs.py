"""Single-job registry and parked-thread curation handshake."""

from __future__ import annotations

import logging
import re
import threading
import uuid
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, cast

from . import log_context
from .anki_limits import ANKI_LIMITS_V1
from .protocol import BridgeProtocolError, decode_envelope, decode_message, encode_message

_RUN_ID_RE = re.compile(r"^run_[0-9a-f]{32}$")
_REQUEST_ID_RE = re.compile(r"^curation_[0-9a-f]{32}$")
_CANDIDATE_ID_RE = re.compile(r"^candidate_[0-9a-f]{32}$")
_SENTENCE_ID_RE = re.compile(r"^sentence_[0-9a-f]{32}$")

# A page stays comfortably below the generic 32 MiB bridge envelope while keeping a
# single Compose list and its decoded object graph bounded. These limits are mirrored by
# BridgeJsonCodec and curation.schema.json.
CURATION_PAGE_MAX_CANDIDATES = 100
CURATION_PAGE_MAX_UTF8_BYTES = 512 * 1024
_MAX_CURATED_SOURCE_ITEMS = ANKI_LIMITS_V1["createCall"]["maxSourceItems"]
_CURATION_CANCELLATION_POLL_SECONDS = 0.05
logger = logging.getLogger(__name__)


def _reject(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def _known_words_db_path() -> Path:
    """Mirror ``config_map``'s fixed known-words location."""

    from .bootstrap import require_initialized

    return Path(require_initialized()) / "known_words.db"


def _write_user_known_words(db_path: Path, forms: set[str]) -> int:
    """Module-level seam over the engine helper, imported after bootstrap."""

    from anki_miner.services.known_word_db import add_user_known_words

    return add_user_known_words(db_path, forms)


@dataclass(frozen=True)
class JobHandle:
    """Opaque identity and cancellation token for one engine invocation."""

    run_id: str
    cancel_event: threading.Event


@dataclass(frozen=True)
class _CandidateRef:
    original: object
    default_sentence_id: str
    sentences: Mapping[str, object]


@dataclass(frozen=True)
class _CurationPagePlan:
    candidate_ids: tuple[str, ...]
    candidate_start: int


@dataclass(frozen=True)
class CurationResolution:
    paged: bool
    page_index: int | None
    final_page: bool


@dataclass
class _CurationGate:
    request_id: str
    candidates: Mapping[str, _CandidateRef]
    request_json: str | None
    pages: tuple[_CurationPagePlan, ...]
    event: threading.Event = field(default_factory=threading.Event)
    page_index: int = 0
    page_resolved: bool = False
    cancelled: bool = False
    failure: BridgeProtocolError | None = None
    selected: list[object] = field(default_factory=list)
    known_forms: set[str] = field(default_factory=set)

    @property
    def paged(self) -> bool:
        return bool(self.pages)

    @property
    def final_page(self) -> bool:
        return not self.paged or self.page_index == len(self.pages) - 1


@dataclass
class _JobState:
    handle: JobHandle
    curation: _CurationGate | None = None
    last_request_id: str | None = None
    last_page_index: int | None = None


def _opaque_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def _as_string(value: object, *, fallback: str = "") -> str:
    return value if isinstance(value, str) else fallback


def _as_number(value: object, *, fallback: int | float = 0) -> int | float:
    if type(value) in (int, float):
        return value  # type: ignore[return-value]
    return fallback


def _same_sentence(first: object, second: object) -> bool:
    return getattr(first, "sentence", None) == getattr(second, "sentence", None) and getattr(
        first, "start_time", None
    ) == getattr(second, "start_time", None)


def _sentence_payload(sentence_id: str, word: object) -> dict[str, Any]:
    return {
        "sentenceId": sentence_id,
        "sentence": _as_string(getattr(word, "sentence", "")),
        "sentenceFurigana": _as_string(getattr(word, "sentence_furigana", "")),
        "sentenceReading": _as_string(getattr(word, "sentence_reading", "")),
        "startTime": _as_number(getattr(word, "start_time", 0.0), fallback=0.0),
        "endTime": _as_number(getattr(word, "end_time", 0.0), fallback=0.0),
        "duration": _as_number(getattr(word, "duration", 0.0), fallback=0.0),
    }


def _candidate_ref(word: object) -> _CandidateRef:
    default_sentence_id = _opaque_id("sentence")
    sentence_objects: dict[str, object] = {default_sentence_id: word}

    alternatives = getattr(word, "sentence_candidates", ()) or ()
    if isinstance(alternatives, Sequence) and not isinstance(alternatives, (str, bytes, bytearray)):
        for alternative in alternatives:
            if alternative is word or _same_sentence(alternative, word):
                continue
            sentence_id = _opaque_id("sentence")
            sentence_objects[sentence_id] = alternative

    return _CandidateRef(
        original=word,
        default_sentence_id=default_sentence_id,
        sentences=sentence_objects,
    )


def _candidate_mined_form(reference: _CandidateRef) -> str:
    word = reference.original
    return _as_string(
        getattr(word, "mined_form", None),
        fallback=_as_string(getattr(word, "lemma", "")),
    )


def _candidate_payload_from_ref(candidate_id: str, reference: _CandidateRef) -> dict[str, Any]:
    word = reference.original
    sentence_payloads = [
        _sentence_payload(sentence_id, sentence) for sentence_id, sentence in reference.sentences.items()
    ]

    return {
        "candidateId": candidate_id,
        "minedForm": _candidate_mined_form(reference),
        "surface": _as_string(getattr(word, "surface", "")),
        "lemma": _as_string(getattr(word, "lemma", "")),
        "reading": _as_string(getattr(word, "reading", "")),
        "expressionReading": _as_string(getattr(word, "expression_reading", "")),
        "partOfSpeech": (getattr(word, "pos", None) if isinstance(getattr(word, "pos", None), str) else None),
        "frequencyRank": (
            getattr(word, "frequency_rank", None) if type(getattr(word, "frequency_rank", None)) is int else None
        ),
        "occurrenceCount": (
            getattr(word, "occurrence_count", 0) if type(getattr(word, "occurrence_count", 0)) is int else 0
        ),
        "defaultSentenceId": reference.default_sentence_id,
        "sentences": sentence_payloads,
    }


def _candidate_payload(candidate_id: str, word: object) -> tuple[dict[str, Any], _CandidateRef]:
    """Retain the original raw-word helper contract used by existing tests."""

    reference = _candidate_ref(word)
    return _candidate_payload_from_ref(candidate_id, reference), reference


def _utf8_size(raw: str) -> int:
    try:
        return len(raw.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise _reject("invalid_utf8", "Curation payload contains an invalid Unicode scalar") from exc


def _page_payload(
    *,
    run_id: str,
    request_id: str,
    page_index: int,
    page_count: int,
    candidate_start: int,
    total_candidates: int,
    candidates: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "runId": run_id,
        "requestId": request_id,
        "pageIndex": page_index,
        "pageCount": page_count,
        "candidateStart": candidate_start,
        "totalCandidates": total_candidates,
        "candidates": candidates,
    }


def _encoded_page_upper_bound(
    *,
    run_id: str,
    request_id: str,
    total_candidates: int,
    candidates: list[dict[str, Any]],
) -> int:
    # Every actual page metadata value has no more decimal digits than totalCandidates.
    # Using the total in every numeric slot makes this a conservative exact-JSON bound.
    raw = encode_message(
        "curation.page.request",
        _page_payload(
            run_id=run_id,
            request_id=request_id,
            page_index=total_candidates,
            page_count=total_candidates,
            candidate_start=total_candidates,
            total_candidates=total_candidates,
            candidates=candidates,
        ),
    )
    return _utf8_size(raw)


def _partition_pages(
    *,
    run_id: str,
    request_id: str,
    total_candidates: int,
    entries: Iterable[tuple[str, dict[str, Any]]],
) -> tuple[_CurationPagePlan, ...]:
    pages: list[_CurationPagePlan] = []
    current_ids: list[str] = []
    current_payloads: list[dict[str, Any]] = []
    current_start = 0

    for candidate_id, payload in entries:
        proposed_payloads = [*current_payloads, payload]
        within_count = len(proposed_payloads) <= CURATION_PAGE_MAX_CANDIDATES
        within_bytes = (
            _encoded_page_upper_bound(
                run_id=run_id,
                request_id=request_id,
                total_candidates=total_candidates,
                candidates=proposed_payloads,
            )
            <= CURATION_PAGE_MAX_UTF8_BYTES
        )
        if within_count and within_bytes:
            current_ids.append(candidate_id)
            current_payloads.append(payload)
            continue

        if not current_ids:
            raise _reject(
                "curation_candidate_too_large",
                "One curation candidate exceeds the bounded page envelope",
            )
        pages.append(_CurationPagePlan(tuple(current_ids), current_start))
        current_start += len(current_ids)
        current_ids = [candidate_id]
        current_payloads = [payload]
        if (
            _encoded_page_upper_bound(
                run_id=run_id,
                request_id=request_id,
                total_candidates=total_candidates,
                candidates=current_payloads,
            )
            > CURATION_PAGE_MAX_UTF8_BYTES
        ):
            raise _reject(
                "curation_candidate_too_large",
                "One curation candidate exceeds the bounded page envelope",
            )

    if current_ids:
        pages.append(_CurationPagePlan(tuple(current_ids), current_start))
    if len(pages) < 2:
        raise _reject(
            "invalid_curation_paging",
            "Paged curation must contain at least two non-empty pages",
        )
    return tuple(pages)


class JobRegistry:
    """Own exactly one live engine job and all of its synchronization state."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._active: _JobState | None = None
        self._last_finished_run_id: str | None = None
        self._shutdown = False

    def begin(self) -> JobHandle:
        """Register a new run with a fresh cancellation event."""

        with self._lock:
            if self._shutdown:
                raise _reject("registry_shutdown", "The job registry has shut down")
            if self._active is not None:
                raise _reject("job_already_active", "Only one Python mining job may run at a time")
            handle = JobHandle(run_id=_opaque_id("run"), cancel_event=threading.Event())
            self._active = _JobState(handle=handle)
            log_context.set_active_run(handle.run_id)
            from .bootstrap import begin_run_warning_count

            begin_run_warning_count()
            return handle

    @property
    def active_run_id(self) -> str | None:
        with self._lock:
            return self._active.handle.run_id if self._active is not None else None

    def _require_active(self, run_id: str) -> _JobState:
        if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
            raise _reject("invalid_run_id", "runId is not a valid opaque run ID")
        state = self._active
        if state is None:
            raise _reject("no_active_job", "There is no active Python mining job")
        if state.handle.run_id != run_id:
            raise _reject("stale_run", "The response belongs to a stale mining run")
        return state

    def cancel(self, run_id: str) -> bool:
        """Cancel a run and release any curation wait.

        Returns ``True`` only for the first cancellation request.  Repeated
        cancellation remains safe and keeps the wait released.
        """

        with self._lock:
            if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
                raise BridgeProtocolError("invalid_run_id", "runId is not a valid opaque run ID")
            state = self._active
            if state is None:
                if self._last_finished_run_id == run_id:
                    return False
                raise BridgeProtocolError("no_active_job", "There is no active Python mining job")
            if state.handle.run_id != run_id:
                raise BridgeProtocolError("stale_run", "The response belongs to a stale mining run")
            first = not state.handle.cancel_event.is_set()
            state.handle.cancel_event.set()
            if state.curation is not None:
                state.curation.cancelled = True
                state.curation.page_resolved = True
                state.curation.event.set()
            return first

    def finish(self, run_id: str) -> None:
        """Remove a finished run, defensively releasing any pending wait."""

        with self._lock:
            state = self._require_active(run_id)
            if state.curation is not None:
                state.curation.cancelled = True
                state.curation.page_resolved = True
                state.curation.event.set()
        from .bootstrap import emit_run_warning_summary

        try:
            emit_run_warning_summary()
        finally:
            with self._lock:
                if self._active is state:
                    self._last_finished_run_id = run_id
                    self._active = None
                    log_context.set_active_run(None)

    def shutdown(self) -> None:
        """Permanently reject new work and release the current run."""

        with self._lock:
            self._shutdown = True
            if self._active is not None:
                self._active.handle.cancel_event.set()
                if self._active.curation is not None:
                    self._active.curation.cancelled = True
                    self._active.curation.page_resolved = True
                    self._active.curation.event.set()
            else:
                # Keep the invariant _ACTIVE_RUN_ID is not None iff self._active
                # is not None. shutdown() cancels; it does not end the run --
                # the cancelled thread still unwinds through its own exception
                # handling and cleanup before its `finally: owner.finish()`, and
                # those records are exactly the ones this feature exists to
                # make sliceable, so they must keep the real run id.
                log_context.set_active_run(None)

    def await_curation(
        self,
        run_id: str,
        candidates: Sequence[object],
        emit_request: Callable[[str], None],
        cancellation_requested: Callable[[], bool] | None = None,
    ) -> list[object] | None:
        """Publish candidates and park until Kotlin confirms or cancels.

        The returned objects are the exact original candidate or sentence
        variant instances held by the engine.  No model is reconstructed from
        JSON.
        """

        if not isinstance(candidates, Sequence) or isinstance(candidates, (str, bytes, bytearray)):
            raise _reject("invalid_candidates", "Curation candidates must be a sequence")
        if not callable(emit_request):
            raise _reject("invalid_callback", "emit_request must be callable")
        if cancellation_requested is not None and not callable(cancellation_requested):
            raise _reject(
                "invalid_callback",
                "cancellation_requested must be callable",
            )

        with self._lock:
            state = self._require_active(run_id)
            if state.handle.cancel_event.is_set():
                return None
            if state.curation is not None:
                raise _reject("curation_already_pending", "A curation request is already pending")

            request_id = _opaque_id("curation")
            refs: dict[str, _CandidateRef] = {}
            for word in candidates:
                candidate_id = _opaque_id("candidate")
                reference = _candidate_ref(word)
                refs[candidate_id] = reference

            request_json: str | None = None
            pages: tuple[_CurationPagePlan, ...] = ()
            if len(refs) <= CURATION_PAGE_MAX_CANDIDATES:
                payloads = [
                    _candidate_payload_from_ref(candidate_id, reference) for candidate_id, reference in refs.items()
                ]
                small_request = encode_message(
                    "curation.request",
                    {
                        "runId": run_id,
                        "requestId": request_id,
                        "candidates": payloads,
                    },
                )
                if _utf8_size(small_request) <= CURATION_PAGE_MAX_UTF8_BYTES:
                    request_json = small_request
                else:
                    pages = _partition_pages(
                        run_id=run_id,
                        request_id=request_id,
                        total_candidates=len(refs),
                        entries=zip(refs.keys(), payloads, strict=True),
                    )
            else:
                pages = _partition_pages(
                    run_id=run_id,
                    request_id=request_id,
                    total_candidates=len(refs),
                    entries=(
                        (
                            candidate_id,
                            _candidate_payload_from_ref(candidate_id, reference),
                        )
                        for candidate_id, reference in refs.items()
                    ),
                )
            gate = _CurationGate(
                request_id=request_id,
                candidates=refs,
                request_json=request_json,
                pages=pages,
            )
            state.curation = gate

        while True:
            with self._lock:
                if state.handle.cancel_event.is_set() or gate.cancelled:
                    self._complete_curation_locked(state, gate)
                    return None
                try:
                    request_json = self._current_request_json(run_id, gate)
                except BaseException:
                    gate.cancelled = True
                    gate.page_resolved = True
                    gate.event.set()
                    self._complete_curation_locked(state, gate)
                    raise
            try:
                # Emit outside the registry lock: Kotlin fakes may answer synchronously.
                emit_request(request_json)
            except BaseException:
                with self._lock:
                    gate.cancelled = True
                    gate.page_resolved = True
                    gate.event.set()
                    self._complete_curation_locked(state, gate)
                raise

            while not gate.event.wait(
                _CURATION_CANCELLATION_POLL_SECONDS if cancellation_requested is not None else None,
            ):
                if cancellation_requested is not None and cancellation_requested():
                    self.cancel(run_id)
            with self._lock:
                if state.handle.cancel_event.is_set() or gate.cancelled:
                    self._complete_curation_locked(state, gate)
                    return None
                if gate.failure is not None:
                    failure = gate.failure
                    self._complete_curation_locked(state, gate)
                    raise failure
                if not gate.page_resolved:
                    raise _reject(
                        "invalid_curation_state",
                        "Curation wait was released without a page response",
                    )
                if gate.final_page:
                    result = list(gate.selected)
                    self._complete_curation_locked(state, gate)
                    return result
                gate.page_index += 1
                gate.page_resolved = False
                gate.event.clear()

    def _current_request_json(self, run_id: str, gate: _CurationGate) -> str:
        if not gate.paged:
            return cast(str, gate.request_json)
        plan = gate.pages[gate.page_index]
        payloads = [
            _candidate_payload_from_ref(candidate_id, gate.candidates[candidate_id])
            for candidate_id in plan.candidate_ids
        ]
        raw = encode_message(
            "curation.page.request",
            _page_payload(
                run_id=run_id,
                request_id=gate.request_id,
                page_index=gate.page_index,
                page_count=len(gate.pages),
                candidate_start=plan.candidate_start,
                total_candidates=len(gate.candidates),
                candidates=payloads,
            ),
        )
        if len(payloads) > CURATION_PAGE_MAX_CANDIDATES or _utf8_size(raw) > CURATION_PAGE_MAX_UTF8_BYTES:
            raise _reject(
                "invalid_curation_paging",
                "A planned curation page exceeds its runtime bound",
            )
        return raw

    @staticmethod
    def _complete_curation_locked(
        state: _JobState,
        gate: _CurationGate,
    ) -> None:
        if state.curation is gate:
            state.curation = None
            state.last_request_id = gate.request_id
            state.last_page_index = gate.page_index if gate.paged else None

    def resolve_curation(self, raw_response: str) -> CurationResolution:
        """Validate and apply a single or paged curation response."""

        decoded = decode_envelope(raw_response)
        if decoded.message_type not in {"curation.response", "curation.page.response"}:
            raise _reject("invalid_curation_response", "Unsupported curation response type")
        payload = decoded.payload
        paged_response = decoded.message_type == "curation.page.response"
        expected_fields = (
            {"runId", "requestId", "pageIndex", "selection"} if paged_response else {"runId", "requestId", "selection"}
        )
        if set(payload) - {"knownCandidateIds"} != expected_fields:
            raise _reject("invalid_curation_response", "Curation response fields are invalid")
        run_id = payload["runId"]
        request_id = payload["requestId"]
        if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
            raise _reject("invalid_curation_response", "runId is not a valid opaque run ID")
        if not isinstance(request_id, str) or not _REQUEST_ID_RE.fullmatch(request_id):
            raise _reject(
                "invalid_curation_response",
                "requestId is not a valid opaque request ID",
            )
        raw_page_index = payload.get("pageIndex")
        if paged_response and (type(raw_page_index) is not int or cast(int, raw_page_index) < 0):
            raise _reject(
                "invalid_curation_response",
                "pageIndex must be a non-negative integer",
            )
        page_index = cast(int, raw_page_index) if paged_response else None

        with self._lock:
            state = self._require_active(run_id)
            gate = state.curation
            if gate is None:
                if state.last_request_id == request_id and state.last_page_index == page_index:
                    raise _reject("duplicate_curation_response", "Curation was already resolved")
                raise _reject(
                    "stale_curation_request",
                    "The curation request is no longer pending",
                )
            if gate.request_id != request_id:
                raise _reject(
                    "stale_curation_request",
                    "The response belongs to a stale curation request",
                )
            if gate.paged != paged_response:
                raise _reject(
                    "invalid_curation_response",
                    "Curation response type does not match the pending request",
                )
            if gate.paged and page_index != gate.page_index:
                raise _reject(
                    "stale_curation_page",
                    "The response belongs to a stale curation page",
                )
            if gate.page_resolved:
                raise _reject("duplicate_curation_response", "Curation was already resolved")

            selection = payload["selection"]
            if selection is None:
                gate.cancelled = True
            elif isinstance(selection, list):
                if len(selection) > CURATION_PAGE_MAX_CANDIDATES:
                    raise _reject(
                        "invalid_curation_response",
                        "Curation selection exceeds the page item limit",
                    )
                allowed_candidate_ids = (
                    set(gate.pages[gate.page_index].candidate_ids) if gate.paged else set(gate.candidates)
                )
                resolved = self._resolve_selection(gate, selection, allowed_candidate_ids)
                new_forms = self._known_forms(gate, payload, selection, allowed_candidate_ids)
                if len(gate.selected) + len(resolved) > _MAX_CURATED_SOURCE_ITEMS:
                    gate.failure = BridgeProtocolError(
                        "create_call_too_large",
                        "The create call contains too many source cards",
                    )
                else:
                    pending_forms = gate.known_forms | new_forms
                    if gate.final_page and pending_forms:
                        try:
                            _write_user_known_words(_known_words_db_path(), pending_forms)
                        except Exception as error:
                            gate.failure = BridgeProtocolError(
                                "known_words_write_failed",
                                "Could not save the known words",
                            )
                            logger.warning("Known-words commit failed: %s", error)
                    if gate.failure is None:
                        gate.selected.extend(resolved)
                        gate.known_forms = set() if gate.final_page else pending_forms
            else:
                raise _reject("invalid_curation_response", "selection must be null or an array")

            final_page = gate.final_page or gate.cancelled or gate.failure is not None
            gate.page_resolved = True
            gate.event.set()
            return CurationResolution(
                paged=gate.paged,
                page_index=gate.page_index if gate.paged else None,
                final_page=final_page,
            )

    @staticmethod
    def _known_forms(
        gate: _CurationGate,
        payload: Mapping[str, object],
        selection: list[object],
        allowed_candidate_ids: set[str],
    ) -> set[str]:
        """Return marked mined forms for this page without mutating the gate."""

        known_ids = payload.get("knownCandidateIds", [])
        if not isinstance(known_ids, list):
            raise _reject("invalid_curation_response", "knownCandidateIds must be an array")
        if any(not isinstance(raw, str) for raw in known_ids):
            raise _reject(
                "invalid_curation_response",
                "knownCandidateIds must contain strings",
            )
        if len(known_ids) != len(set(known_ids)):
            raise _reject(
                "invalid_curation_response",
                "knownCandidateIds contains duplicates",
            )
        selected_ids = {
            chosen["candidateId"]
            for chosen in selection
            if isinstance(chosen, dict) and isinstance(chosen.get("candidateId"), str)
        }
        forms: set[str] = set()
        for raw in known_ids:
            if raw not in allowed_candidate_ids:
                raise _reject(
                    "invalid_curation_response",
                    "knownCandidateIds contains an unknown candidate",
                )
            if raw in selected_ids:
                raise _reject(
                    "invalid_curation_response",
                    "A candidate cannot be both selected and marked known",
                )
            forms.add(_candidate_mined_form(gate.candidates[raw]))
        return forms

    @staticmethod
    def _resolve_selection(
        gate: _CurationGate,
        selection: list[object],
        allowed_candidate_ids: set[str],
    ) -> list[object]:
        resolved: list[object] = []
        seen_candidates: set[str] = set()
        for item in selection:
            if not isinstance(item, dict) or not set(item).issubset({"candidateId", "sentenceId"}):
                raise _reject(
                    "invalid_curation_response",
                    "Each selection must identify a candidate",
                )
            if "candidateId" not in item:
                raise _reject("invalid_curation_response", "candidateId is required")
            candidate_id = item["candidateId"]
            if not isinstance(candidate_id, str) or not _CANDIDATE_ID_RE.fullmatch(candidate_id):
                raise _reject("invalid_curation_response", "candidateId is not a valid opaque ID")
            has_sentence_id = "sentenceId" in item
            sentence_id = item.get("sentenceId")
            if has_sentence_id and (not isinstance(sentence_id, str) or not _SENTENCE_ID_RE.fullmatch(sentence_id)):
                raise _reject(
                    "invalid_curation_response",
                    "sentenceId must be omitted or contain a valid opaque ID",
                )
            if candidate_id in seen_candidates:
                raise _reject("duplicate_candidate", "A candidate may only be selected once")
            candidate = gate.candidates.get(candidate_id)
            if candidate is None or candidate_id not in allowed_candidate_ids:
                raise _reject("unknown_candidate", "The selected candidate is unknown")
            chosen_sentence_id = cast(str, sentence_id) if has_sentence_id else candidate.default_sentence_id
            chosen = candidate.sentences.get(chosen_sentence_id)
            if chosen is None:
                raise _reject("unknown_sentence", "The sentence does not belong to this candidate")
            seen_candidates.add(candidate_id)
            resolved.append(chosen)
        return resolved


_REGISTRY = JobRegistry()


def registry() -> JobRegistry:
    """Return the process-wide registry used by the mining entry point."""

    return _REGISTRY


def begin_job() -> JobHandle:
    return _REGISTRY.begin()


def cancel_job(raw_request: str) -> str:
    payload = decode_message(raw_request, expected_type="job.cancel")
    if set(payload) != {"runId"} or not isinstance(payload.get("runId"), str):
        raise _reject("invalid_cancel_request", "job.cancel requires exactly one string runId")
    first = _REGISTRY.cancel(payload["runId"])
    return encode_message("job.cancelled", {"runId": payload["runId"], "newlyCancelled": first})


def submit_curation(raw_response: str) -> str:
    decoded = decode_envelope(raw_response)
    resolution = _REGISTRY.resolve_curation(raw_response)
    payload = decoded.payload
    if resolution.paged:
        return encode_message(
            "curation.page.accepted",
            {
                "runId": payload["runId"],
                "requestId": payload["requestId"],
                "pageIndex": resolution.page_index,
                "finalPage": resolution.final_page,
            },
        )
    return encode_message(
        "curation.accepted",
        {"runId": payload["runId"], "requestId": payload["requestId"]},
    )


def shutdown() -> str:
    _REGISTRY.shutdown()
    return encode_message("bridge.shutdown", {})
