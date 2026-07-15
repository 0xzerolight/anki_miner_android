"""Single-job registry and parked-thread curation handshake."""

from __future__ import annotations

import threading
import uuid
import re
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, cast

from .protocol import BridgeProtocolError, decode_message, encode_message

_RUN_ID_RE = re.compile(r"^run_[0-9a-f]{32}$")
_REQUEST_ID_RE = re.compile(r"^curation_[0-9a-f]{32}$")
_CANDIDATE_ID_RE = re.compile(r"^candidate_[0-9a-f]{32}$")
_SENTENCE_ID_RE = re.compile(r"^sentence_[0-9a-f]{32}$")


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


@dataclass
class _CurationGate:
    request_id: str
    candidates: Mapping[str, _CandidateRef]
    request_json: str
    event: threading.Event = field(default_factory=threading.Event)
    resolved: bool = False
    response: list[object] | None = None


@dataclass
class _JobState:
    handle: JobHandle
    curation: _CurationGate | None = None
    last_request_id: str | None = None


def _opaque_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def _as_string(value: object, *, fallback: str = "") -> str:
    return value if isinstance(value, str) else fallback


def _as_number(value: object, *, fallback: int | float = 0) -> int | float:
    if type(value) in (int, float):
        return value  # type: ignore[return-value]
    return fallback


def _same_sentence(first: object, second: object) -> bool:
    return getattr(first, "sentence", None) == getattr(
        second, "sentence", None
    ) and getattr(first, "start_time", None) == getattr(second, "start_time", None)


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


def _candidate_payload(
    candidate_id: str, word: object
) -> tuple[dict[str, Any], _CandidateRef]:
    default_sentence_id = _opaque_id("sentence")
    sentence_objects: dict[str, object] = {default_sentence_id: word}
    sentence_payloads = [_sentence_payload(default_sentence_id, word)]

    alternatives = getattr(word, "sentence_candidates", ()) or ()
    if isinstance(alternatives, Sequence) and not isinstance(
        alternatives, (str, bytes, bytearray)
    ):
        for alternative in alternatives:
            if alternative is word or _same_sentence(alternative, word):
                continue
            sentence_id = _opaque_id("sentence")
            sentence_objects[sentence_id] = alternative
            sentence_payloads.append(_sentence_payload(sentence_id, alternative))

    mined_form = getattr(word, "mined_form", None)
    payload = {
        "candidateId": candidate_id,
        "minedForm": _as_string(
            mined_form, fallback=_as_string(getattr(word, "lemma", ""))
        ),
        "surface": _as_string(getattr(word, "surface", "")),
        "lemma": _as_string(getattr(word, "lemma", "")),
        "reading": _as_string(getattr(word, "reading", "")),
        "expressionReading": _as_string(getattr(word, "expression_reading", "")),
        "partOfSpeech": (
            getattr(word, "pos", None)
            if isinstance(getattr(word, "pos", None), str)
            else None
        ),
        "frequencyRank": (
            getattr(word, "frequency_rank", None)
            if type(getattr(word, "frequency_rank", None)) is int
            else None
        ),
        "occurrenceCount": (
            getattr(word, "occurrence_count", 0)
            if type(getattr(word, "occurrence_count", 0)) is int
            else 0
        ),
        "defaultSentenceId": default_sentence_id,
        "sentences": sentence_payloads,
    }
    return payload, _CandidateRef(
        original=word,
        default_sentence_id=default_sentence_id,
        sentences=sentence_objects,
    )


class JobRegistry:
    """Own exactly one live engine job and all of its synchronization state."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._active: _JobState | None = None
        self._shutdown = False

    def begin(self) -> JobHandle:
        """Register a new run with a fresh cancellation event."""

        with self._lock:
            if self._shutdown:
                raise BridgeProtocolError(
                    "registry_shutdown", "The job registry has shut down"
                )
            if self._active is not None:
                raise BridgeProtocolError(
                    "job_already_active", "Only one Python mining job may run at a time"
                )
            handle = JobHandle(run_id=_opaque_id("run"), cancel_event=threading.Event())
            self._active = _JobState(handle=handle)
            return handle

    @property
    def active_run_id(self) -> str | None:
        with self._lock:
            return self._active.handle.run_id if self._active is not None else None

    def _require_active(self, run_id: str) -> _JobState:
        if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
            raise BridgeProtocolError(
                "invalid_run_id", "runId is not a valid opaque run ID"
            )
        state = self._active
        if state is None:
            raise BridgeProtocolError(
                "no_active_job", "There is no active Python mining job"
            )
        if state.handle.run_id != run_id:
            raise BridgeProtocolError(
                "stale_run", "The response belongs to a stale mining run"
            )
        return state

    def cancel(self, run_id: str) -> bool:
        """Cancel a run and release any curation wait.

        Returns ``True`` only for the first cancellation request.  Repeated
        cancellation remains safe and keeps the wait released.
        """

        with self._lock:
            state = self._require_active(run_id)
            first = not state.handle.cancel_event.is_set()
            state.handle.cancel_event.set()
            if state.curation is not None:
                state.curation.response = None
                state.curation.resolved = True
                state.curation.event.set()
            return first

    def finish(self, run_id: str) -> None:
        """Remove a finished run, defensively releasing any pending wait."""

        with self._lock:
            state = self._require_active(run_id)
            if state.curation is not None:
                state.curation.response = None
                state.curation.resolved = True
                state.curation.event.set()
            self._active = None

    def shutdown(self) -> None:
        """Permanently reject new work and release the current run."""

        with self._lock:
            self._shutdown = True
            if self._active is not None:
                self._active.handle.cancel_event.set()
                if self._active.curation is not None:
                    self._active.curation.response = None
                    self._active.curation.resolved = True
                    self._active.curation.event.set()

    def await_curation(
        self,
        run_id: str,
        candidates: Sequence[object],
        emit_request: Callable[[str], None],
    ) -> list[object] | None:
        """Publish candidates and park until Kotlin confirms or cancels.

        The returned objects are the exact original candidate or sentence
        variant instances held by the engine.  No model is reconstructed from
        JSON.
        """

        if not isinstance(candidates, Sequence) or isinstance(
            candidates, (str, bytes, bytearray)
        ):
            raise BridgeProtocolError(
                "invalid_candidates", "Curation candidates must be a sequence"
            )
        if not callable(emit_request):
            raise BridgeProtocolError(
                "invalid_callback", "emit_request must be callable"
            )

        with self._lock:
            state = self._require_active(run_id)
            if state.handle.cancel_event.is_set():
                return None
            if state.curation is not None:
                raise BridgeProtocolError(
                    "curation_already_pending", "A curation request is already pending"
                )

            request_id = _opaque_id("curation")
            refs: dict[str, _CandidateRef] = {}
            payloads: list[dict[str, Any]] = []
            for word in candidates:
                candidate_id = _opaque_id("candidate")
                payload, reference = _candidate_payload(candidate_id, word)
                refs[candidate_id] = reference
                payloads.append(payload)
            request_json = encode_message(
                "curation.request",
                {"runId": run_id, "requestId": request_id, "candidates": payloads},
            )
            gate = _CurationGate(
                request_id=request_id, candidates=refs, request_json=request_json
            )
            state.curation = gate

        try:
            # Emit outside the registry lock: a Java fake or future Kotlin
            # implementation may answer synchronously on the same thread.
            emit_request(request_json)
        except BaseException:
            with self._lock:
                gate.response = None
                gate.resolved = True
                gate.event.set()
                if state.curation is gate:
                    state.curation = None
                    state.last_request_id = request_id
            raise

        gate.event.wait()
        with self._lock:
            if state.curation is gate:
                state.curation = None
                state.last_request_id = request_id
            if state.handle.cancel_event.is_set():
                return None
            return gate.response

    def resolve_curation(self, raw_response: str) -> None:
        """Validate and apply a ``curation.response`` JSON message."""

        payload = decode_message(raw_response, expected_type="curation.response")
        if set(payload) != {"runId", "requestId", "selection"}:
            raise BridgeProtocolError(
                "invalid_curation_response", "Curation response fields are invalid"
            )
        run_id = payload["runId"]
        request_id = payload["requestId"]
        if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
            raise BridgeProtocolError(
                "invalid_curation_response", "runId is not a valid opaque run ID"
            )
        if not isinstance(request_id, str) or not _REQUEST_ID_RE.fullmatch(request_id):
            raise BridgeProtocolError(
                "invalid_curation_response",
                "requestId is not a valid opaque request ID",
            )

        with self._lock:
            state = self._require_active(run_id)
            gate = state.curation
            if gate is None:
                if state.last_request_id == request_id:
                    raise BridgeProtocolError(
                        "duplicate_curation_response", "Curation was already resolved"
                    )
                raise BridgeProtocolError(
                    "stale_curation_request",
                    "The curation request is no longer pending",
                )
            if gate.request_id != request_id:
                raise BridgeProtocolError(
                    "stale_curation_request",
                    "The response belongs to a stale curation request",
                )
            if gate.resolved:
                raise BridgeProtocolError(
                    "duplicate_curation_response", "Curation was already resolved"
                )

            selection = payload["selection"]
            if selection is None:
                resolved: list[object] | None = None
            elif isinstance(selection, list):
                resolved = self._resolve_selection(gate, selection)
            else:
                raise BridgeProtocolError(
                    "invalid_curation_response", "selection must be null or an array"
                )

            gate.response = resolved
            gate.resolved = True
            gate.event.set()

    @staticmethod
    def _resolve_selection(
        gate: _CurationGate, selection: list[object]
    ) -> list[object]:
        resolved: list[object] = []
        seen_candidates: set[str] = set()
        for item in selection:
            if not isinstance(item, dict) or not set(item).issubset(
                {"candidateId", "sentenceId"}
            ):
                raise BridgeProtocolError(
                    "invalid_curation_response",
                    "Each selection must identify a candidate",
                )
            if "candidateId" not in item:
                raise BridgeProtocolError(
                    "invalid_curation_response", "candidateId is required"
                )
            candidate_id = item["candidateId"]
            if not isinstance(candidate_id, str) or not _CANDIDATE_ID_RE.fullmatch(
                candidate_id
            ):
                raise BridgeProtocolError(
                    "invalid_curation_response", "candidateId is not a valid opaque ID"
                )
            has_sentence_id = "sentenceId" in item
            sentence_id = item.get("sentenceId")
            if has_sentence_id and (
                not isinstance(sentence_id, str)
                or not _SENTENCE_ID_RE.fullmatch(sentence_id)
            ):
                raise BridgeProtocolError(
                    "invalid_curation_response",
                    "sentenceId must be omitted or contain a valid opaque ID",
                )
            if candidate_id in seen_candidates:
                raise BridgeProtocolError(
                    "duplicate_candidate", "A candidate may only be selected once"
                )
            candidate = gate.candidates.get(candidate_id)
            if candidate is None:
                raise BridgeProtocolError(
                    "unknown_candidate", "The selected candidate is unknown"
                )
            chosen_sentence_id = (
                cast(str, sentence_id)
                if has_sentence_id
                else candidate.default_sentence_id
            )
            chosen = candidate.sentences.get(chosen_sentence_id)
            if chosen is None:
                raise BridgeProtocolError(
                    "unknown_sentence", "The sentence does not belong to this candidate"
                )
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
        raise BridgeProtocolError(
            "invalid_cancel_request", "job.cancel requires exactly one string runId"
        )
    first = _REGISTRY.cancel(payload["runId"])
    return encode_message(
        "job.cancelled", {"runId": payload["runId"], "newlyCancelled": first}
    )


def submit_curation(raw_response: str) -> str:
    _REGISTRY.resolve_curation(raw_response)
    payload = decode_message(raw_response, expected_type="curation.response")
    return encode_message(
        "curation.accepted",
        {"runId": payload["runId"], "requestId": payload["requestId"]},
    )


def shutdown() -> str:
    _REGISTRY.shutdown()
    return encode_message("bridge.shutdown", {})
