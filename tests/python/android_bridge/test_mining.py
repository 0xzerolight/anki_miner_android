from __future__ import annotations

import ast
import importlib
import json
import re
import threading
from contextlib import nullcontext
from dataclasses import dataclass, field
from pathlib import Path
from types import SimpleNamespace

import android_bridge.anki_adapter as anki_adapter_module
import android_bridge.jobs as jobs_module
import android_bridge.mining as mining
import pytest
from android_bridge.anki_adapter import AnkiOperationCancelled
from android_bridge.faults import FAULT_ID_PATTERN
from android_bridge.jobs import JobRegistry
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message

PROJECT_ROOT = Path(__file__).resolve().parents[3]
LIFECYCLE_RUN_ID = "run_" + "3" * 32


def _snapshot_present(run_id: str) -> bool:
    from android_bridge import definitions

    return definitions._run_configs.get(run_id) is not None


@dataclass
class FakeResult:
    total_words_found: int = 8
    new_words_found: int = 3
    cards_created: int = 2
    errors: object = field(default_factory=list)
    elapsed_time: float = 1.25
    comprehension_percentage: float = 62.5
    card_ids: list[int] = field(default_factory=lambda: [101, 102])
    video_file: str = "/proc/self/fd/41"
    subtitle_file: str = "/cache/subtitle.srt"
    mined_forms: list[str] = field(default_factory=lambda: ["猫", "食べる"])


def _payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "videoPath": "/proc/self/fd/41",
        "subtitlePath": "/cache/subtitle.srt",
        "episodeName": "Episode 1",
        "seriesName": "Series",
        "sourceLabel": "Series — Episode 1",
        "audioTrackOverride": None,
        "audioOnly": False,
        "cacheDir": "/cache",
        "nativeLibraryDir": "/native",
        "configSnapshot": {"settings": {}, "androidTtsEnabled": False},
    }
    payload.update(overrides)
    return payload


def _request(**overrides: object) -> str:
    return encode_message("mining.video.run", _payload(**overrides))


class RecordingCallbacks:
    def __init__(
        self,
        events: list[str] | None = None,
        registry: JobRegistry | None = None,
    ) -> None:
        self.events = events if events is not None else []
        self.registry = registry
        self.register_requests: list[str] = []
        self.terminals: list[tuple[str, str]] = []

    def registerJob(self, raw: str) -> str:
        self.events.append("register")
        self.register_requests.append(raw)
        request = decode_envelope(raw, expected_type="job.registration.request")
        assert set(request.payload) == {"runId"}
        return encode_message("job.registration.accepted", {"runId": request.payload["runId"]})

    def _terminal(self, channel: str, raw: str) -> None:
        assert self.registry is None or self.registry.active_run_id is None
        self.events.append(channel)
        self.terminals.append((channel, raw))

    def onComplete(self, raw: str) -> None:
        self._terminal("complete", raw)

    def onError(self, raw: str) -> None:
        self._terminal("error", raw)


def _stub_execution(
    monkeypatch: pytest.MonkeyPatch,
    result: object,
    *,
    events: list[str] | None = None,
) -> object:
    config = object()
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))

    def map_config(request: object, files_dir: Path) -> object:
        assert files_dir == Path("/files")
        if events is not None:
            events.append("map")
        return config

    def process(request: object, mapped: object, adapters: object) -> object:
        assert mapped is config
        if events is not None:
            events.append("process")
        return result

    monkeypatch.setattr(mining, "_map_config", map_config)
    monkeypatch.setattr(mining, "_process_episode", process)
    return config


def test_run_admits_before_config_or_engine_work_and_returns_same_terminal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    registry = JobRegistry()
    callbacks = RecordingCallbacks(events, registry)
    _stub_execution(monkeypatch, FakeResult(), events=events)

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    assert events == ["register", "map", "process", "complete"]
    assert callbacks.terminals == [("complete", returned)]
    registration = decode_envelope(callbacks.register_requests[0], expected_type="job.registration.request")
    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["runId"] == registration.payload["runId"]
    assert terminal.payload == {
        "runId": registration.payload["runId"],
        "outcome": "success",
        "result": {
            "totalWordsFound": 8,
            "newWordsFound": 3,
            "cardsCreated": 2,
            "errors": [],
            "elapsedTime": 1.25,
            "comprehensionPercentage": 62.5,
            "cardIds": [101, 102],
            "videoFile": "/proc/self/fd/41",
            "subtitleFile": "/cache/subtitle.srt",
            "minedForms": ["猫", "食べる"],
        },
        "error": None,
    }
    assert registry.active_run_id is None


def test_terminal_callback_can_idempotently_cancel_just_finished_video_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    late_cancellations: list[bool] = []

    class LateCancellationCallbacks(RecordingCallbacks):
        def _terminal(self, channel: str, raw: str) -> None:
            terminal = decode_envelope(raw, expected_type="mining.terminal")
            late_cancellations.append(registry.cancel(terminal.payload["runId"]))
            super()._terminal(channel, raw)

    callbacks = LateCancellationCallbacks(registry=registry)
    _stub_execution(monkeypatch, FakeResult())

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    assert late_cancellations == [False]
    assert decode_envelope(returned, expected_type="mining.terminal").payload["outcome"] == "success"
    assert callbacks.terminals == [("complete", returned)]


@pytest.mark.parametrize(
    ("errors", "expected_outcome", "expected_channel"),
    [
        ([], "success", "complete"),
        (["Processing cancelled by user"], "cancelled", "complete"),
        (["bad subtitle"], "failed", "error"),
        (
            ["bad write", "Processing cancelled by user"],
            "cancelled",
            "complete",
        ),
        ("not a list", "success", "complete"),
    ],
)
def test_run_uses_desktop_result_classifier_exactly(
    monkeypatch: pytest.MonkeyPatch,
    errors: object,
    expected_outcome: str,
    expected_channel: str,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    result = FakeResult(errors=errors)
    _stub_execution(monkeypatch, result)

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["outcome"] == expected_outcome
    assert terminal.payload["error"] == (
        {"code": "processing_failed", "message": "; ".join(errors)}
        if expected_outcome == "failed" and isinstance(errors, list)
        else None
    )
    if expected_outcome == "failed":
        assert terminal.payload["result"]["cardIds"] == [101, 102]
    assert callbacks.terminals == [(expected_channel, returned)]


def test_none_return_is_failed_result_not_an_exception_terminal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    _stub_execution(monkeypatch, None)

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["outcome"] == "failed"
    assert terminal.payload["result"] is None
    assert terminal.payload["error"] == {
        "code": "processing_failed",
        "message": "Mining failed",
    }
    assert callbacks.terminals == [("error", returned)]


def test_registration_failure_releases_python_job_without_composition(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    composed = False
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))

    def map_config(*_: object) -> object:
        nonlocal composed
        composed = True
        return object()

    monkeypatch.setattr(mining, "_map_config", map_config)

    class RejectingCallbacks(RecordingCallbacks):
        def registerJob(self, raw: str) -> str:
            self.register_requests.append(raw)
            return encode_message("job.registration.accepted", {"runId": "run_" + "0" * 32})

    callbacks = RejectingCallbacks(registry=registry)
    with pytest.raises(BridgeProtocolError) as error:
        mining.run_video(_request(), callbacks, job_registry=registry)

    assert error.value.code == "mismatched_callback_response"
    assert not composed
    assert callbacks.terminals == []
    assert registry.active_run_id is None


def test_registration_callback_exception_is_protocol_error_and_releases_job(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))

    class ExplodingCallbacks(RecordingCallbacks):
        def registerJob(self, raw: str) -> str:
            raise RuntimeError("private coordinator detail")

    callbacks = ExplodingCallbacks(registry=registry)
    with pytest.raises(BridgeProtocolError) as error:
        mining.run_video(_request(), callbacks, job_registry=registry)

    assert error.value.code == "callback_failed"
    assert "private coordinator detail" not in str(error.value)
    assert callbacks.terminals == []
    assert registry.active_run_id is None


def test_synchronous_callback_failure_has_one_terminal_stack_owner(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    from anki_miner.utils import ffmpeg_resolver

    registry = JobRegistry()
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))
    monkeypatch.setattr(mining, "_map_config", lambda *_: object())
    monkeypatch.setattr(ffmpeg_resolver, "resolve_ffmpeg", lambda _config: "/native/libffmpeg.so")

    class ExplodingCallbacks(RecordingCallbacks):
        def ankiVerifyTarget(self, _raw: str) -> str:
            raise RuntimeError("private callback failure")

    def process(_request: object, _config: object, adapters: object) -> object:
        adapters.anki.verify_target({})
        raise AssertionError("unreachable")

    monkeypatch.setattr(mining, "_process_episode", process)
    callbacks = ExplodingCallbacks(registry=registry)

    with caplog.at_level("ERROR"):
        returned = mining.run_video(_request(), callbacks, job_registry=registry)

    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["error"]["code"] == "callback_failed"
    owners = [record for record in caplog.records if record.exc_info]
    assert len(owners) == 1
    assert owners[0].name == mining.logger.name
    assert "code=callback_failed" in owners[0].getMessage()
    assert "outcome=fail" in owners[0].getMessage()
    assert "private callback failure" in caplog.text


def test_ffmpeg_path_fallback_error_has_outcome_and_throwable(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    _stub_execution(monkeypatch, FakeResult())

    with caplog.at_level("ERROR", logger=mining.logger.name):
        mining.run_video(_request(), callbacks, job_registry=registry)

    fallback = [record for record in caplog.records if "ffmpeg_fallback_to_path" in record.msg]
    assert len(fallback) == 1
    assert "outcome=fail" in fallback[0].getMessage()
    assert fallback[0].exc_info is not None


def test_cancellation_raced_through_registration_skips_expensive_composition(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    events: list[str] = []
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))

    class CancelOnRegistration(RecordingCallbacks):
        def registerJob(self, raw: str) -> str:
            response = super().registerJob(raw)
            run_id = decode_envelope(raw, expected_type="job.registration.request").payload["runId"]
            assert registry.cancel(run_id)
            events.append("cancel")
            return response

    def forbidden(*_: object) -> object:
        events.append("composed")
        raise AssertionError("cancelled run must not be composed")

    monkeypatch.setattr(mining, "_map_config", forbidden)
    monkeypatch.setattr(mining, "_process_episode", forbidden)
    callbacks = CancelOnRegistration(events, registry)

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload == {
        "runId": decode_envelope(callbacks.register_requests[0]).payload["runId"],
        "outcome": "cancelled",
        "result": None,
        "error": {"code": "cancelled", "message": "Mining was cancelled"},
    }
    assert events == ["register", "cancel", "complete"]
    assert registry.active_run_id is None


def test_cancellation_during_config_mapping_stops_before_processor_composition(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))

    def map_then_cancel(*_: object) -> object:
        run_id = registry.active_run_id
        assert run_id is not None
        assert registry.cancel(run_id)
        return object()

    def forbidden(*_: object) -> object:
        raise AssertionError("cancelled run must not compose a processor")

    monkeypatch.setattr(mining, "_map_config", map_then_cancel)
    monkeypatch.setattr(mining, "_process_episode", forbidden)

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["outcome"] == "cancelled"
    assert terminal.payload["result"] is None
    assert terminal.payload["error"] == {
        "code": "cancelled",
        "message": "Mining was cancelled",
    }
    assert callbacks.terminals == [("complete", returned)]
    assert registry.active_run_id is None


@pytest.mark.parametrize("resolution", ["empty", "null", "cancel"])
def test_run_video_threaded_curation_parks_and_resumes_through_control_seams(
    monkeypatch: pytest.MonkeyPatch,
    resolution: str,
) -> None:
    registry = JobRegistry()
    monkeypatch.setattr(jobs_module, "_REGISTRY", registry)
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))
    monkeypatch.setattr(mining, "_map_config", lambda *_: object())
    curation_emitted = threading.Event()
    curation_returned = threading.Event()
    callback_requests: list[str] = []
    curated: list[list[object] | None] = []
    candidate = SimpleNamespace(
        surface="猫",
        lemma="猫",
        reading="ネコ",
        expression_reading="ねこ",
        pos="名詞",
        frequency_rank=100,
        occurrence_count=2,
        sentence="猫だ。",
        sentence_furigana="猫[ねこ]だ。",
        sentence_reading="ねこだ。",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        sentence_candidates=[],
        mined_form="猫",
    )

    class CurationCallbacks(RecordingCallbacks):
        def onCurationNeeded(self, raw: str) -> None:
            callback_requests.append(raw)
            curation_emitted.set()

    callbacks = CurationCallbacks(registry=registry)

    def process(_: object, __: object, adapters: object) -> FakeResult:
        selection = adapters.curate([candidate])
        curated.append(selection)
        curation_returned.set()
        if selection is None:
            return FakeResult(
                cards_created=0,
                errors=["Processing cancelled by user"],
                card_ids=[],
                mined_forms=[],
            )
        assert selection == []
        return FakeResult(cards_created=0, card_ids=[], mined_forms=[])

    monkeypatch.setattr(mining, "_process_episode", process)
    returned: list[str] = []
    raised: list[BaseException] = []

    def worker() -> None:
        try:
            returned.append(mining.run_video(_request(), callbacks))
        except BaseException as error:
            raised.append(error)

    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    try:
        assert curation_emitted.wait(5), "curation request was not emitted"
        assert not curation_returned.wait(0.05), "engine thread did not park"
        request = decode_envelope(callback_requests[0], expected_type="curation.request")
        if resolution == "cancel":
            response = jobs_module.cancel_job(encode_message("job.cancel", {"runId": request.payload["runId"]}))
            assert decode_envelope(response, expected_type="job.cancelled").payload == {
                "runId": request.payload["runId"],
                "newlyCancelled": True,
            }
        else:
            response = jobs_module.submit_curation(
                encode_message(
                    "curation.response",
                    {
                        "runId": request.payload["runId"],
                        "requestId": request.payload["requestId"],
                        "selection": [] if resolution == "empty" else None,
                    },
                )
            )
            assert decode_envelope(response, expected_type="curation.accepted").payload == {
                "runId": request.payload["runId"],
                "requestId": request.payload["requestId"],
            }
        thread.join(5)
    finally:
        if thread.is_alive() and registry.active_run_id is not None:
            registry.cancel(registry.active_run_id)
        thread.join(5)

    assert not thread.is_alive()
    assert raised == []
    assert len(returned) == 1
    assert curated == ([[]] if resolution == "empty" else [None])
    terminal = decode_envelope(returned[0], expected_type="mining.terminal")
    assert terminal.payload["outcome"] == ("success" if resolution == "empty" else "cancelled")
    assert callbacks.terminals == [("complete", returned[0])]
    assert registry.active_run_id is None


def test_preflight_and_single_job_admission_fail_before_register_callback(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)

    def not_ready() -> Path:
        raise BridgeProtocolError("unidic_registration_required", "missing")

    monkeypatch.setattr(mining, "_ensure_runtime_ready", not_ready)
    with pytest.raises(BridgeProtocolError) as preflight:
        mining.run_video(_request(), callbacks, job_registry=registry)
    assert preflight.value.code == "unidic_registration_required"
    assert callbacks.register_requests == []
    assert registry.active_run_id is None

    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))
    first = registry.begin()
    with pytest.raises(BridgeProtocolError) as concurrent:
        mining.run_video(_request(), callbacks, job_registry=registry)
    assert concurrent.value.code == "job_already_active"
    assert callbacks.register_requests == []
    registry.finish(first.run_id)


@pytest.mark.parametrize("during", ["map", "process"])
def test_ordinary_failure_after_admission_becomes_failed_terminal(
    monkeypatch: pytest.MonkeyPatch,
    during: str,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))

    def map_config(*_: object) -> object:
        if during == "map":
            raise BridgeProtocolError("invalid_config_field", "bad setting")
        return object()

    def process(*_: object) -> object:
        raise RuntimeError("engine exploded")

    monkeypatch.setattr(mining, "_map_config", map_config)
    monkeypatch.setattr(mining, "_process_episode", process)

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["outcome"] == "failed"
    assert terminal.payload["result"] is None
    error = terminal.payload["error"]
    if during == "map":
        # A protocol error already names itself; it gets no correlation key.
        assert error == {"code": "invalid_config_field", "message": "bad setting"}
    else:
        assert re.fullmatch(FAULT_ID_PATTERN, error.pop("faultId"))
        assert error == {"code": "internal_error", "message": "Internal mining failure"}
    if during == "process":
        assert "engine exploded" not in returned
    assert callbacks.terminals == [("error", returned)]
    assert registry.active_run_id is None


def test_anki_baseexception_is_clean_cancellation_but_other_baseexceptions_escape(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))
    monkeypatch.setattr(mining, "_map_config", lambda *_: object())

    def cancelled(*_: object) -> object:
        raise AnkiOperationCancelled("createNotes", "stopped", False)

    monkeypatch.setattr(mining, "_process_episode", cancelled)
    returned = mining.run_video(_request(), callbacks, job_registry=registry)
    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["outcome"] == "cancelled"
    assert terminal.payload["error"] == {
        "code": "cancelled",
        "message": "stopped",
    }
    assert callbacks.terminals == [("complete", returned)]
    assert registry.active_run_id is None

    class Fatal(BaseException):
        pass

    callbacks.terminals.clear()

    def fatal(*_: object) -> object:
        raise Fatal("stop interpreter")

    monkeypatch.setattr(mining, "_process_episode", fatal)
    with pytest.raises(Fatal):
        mining.run_video(_request(), callbacks, job_registry=registry)
    assert callbacks.terminals == []
    assert registry.active_run_id is None


def test_terminal_callback_failure_does_not_replace_synchronous_terminal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    _stub_execution(monkeypatch, FakeResult())

    class BrokenTerminalCallbacks(RecordingCallbacks):
        def onComplete(self, raw: str) -> None:
            raise RuntimeError("UI disappeared")

    returned = mining.run_video(_request(), BrokenTerminalCallbacks(registry=registry), job_registry=registry)

    assert decode_envelope(returned).payload["outcome"] == "success"
    assert registry.active_run_id is None


@pytest.mark.parametrize("audio_only", [False, True])
def test_process_episode_receives_exact_desktop_contract_and_cleans_lifo(
    monkeypatch: pytest.MonkeyPatch,
    audio_only: bool,
) -> None:
    events: list[str] = []
    cancel_event = threading.Event()
    config = object()
    result = object()
    progress = object()
    anki_callbacks = object()

    def curate(candidates: list[object]) -> list[object] | None:
        return candidates

    adapters = SimpleNamespace(
        anki=anki_callbacks,
        run_id=LIFECYCLE_RUN_ID,
        cancel_event=cancel_event,
        progress=progress,
        curate=curate,
    )

    class FakeAdapter:
        def __init__(
            self,
            received_config: object,
            received_callbacks: object,
            cancellation_check: object,
            source_prefix: object,
        ) -> None:
            assert received_config is config
            assert received_callbacks is anki_callbacks
            assert callable(cancellation_check)
            assert not cancellation_check()
            # The engine composes "<series> — <episode>" into the card source
            # field; the seam strips the synthetic lane label back off.
            assert source_prefix == "Series — "
            events.append("adapter-init")

        def __enter__(self) -> FakeAdapter:
            events.append("adapter-enter")
            return self

        def __exit__(self, *_: object) -> None:
            events.append("adapter-exit")

    class FakeProcessor:
        def process_episode(self, *args: object, **kwargs: object) -> object:
            events.append("process")
            assert args == (Path("/video.mkv"), Path("/subtitle.SRT"))
            assert kwargs == {
                "progress_callback": progress,
                "curation_callback": curate,
                "episode_name_override": "Episode",
                "series_name_override": "Series",
                "audio_track_override": 2,
                "source_label_override": "Source",
                "audio_only": audio_only,
                "cancel_event": cancel_event,
            }
            return result

        def close(self) -> None:
            events.append("processor-close")

    processor = FakeProcessor()

    def build(received_config: object, received_adapters: object, adapter: object) -> object:
        assert received_config is config
        assert received_adapters is adapters
        assert isinstance(adapter, FakeAdapter)
        events.append("build")
        return processor

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(mining, "_build_processor", build)
    request = mining._VideoRequest(
        video_path=Path("/video.mkv"),
        subtitle_path=Path("/subtitle.SRT"),
        episode_name="Episode",
        series_name="Series",
        source_label="Source",
        audio_track_override=2,
        audio_only=audio_only,
        cache_dir=Path("/cache"),
        native_library_dir=Path("/native"),
        settings={},
        android_tts_enabled=False,
    )

    assert mining._process_episode(request, config, adapters) is result
    assert events == [
        "adapter-init",
        "adapter-enter",
        "build",
        "process",
        "processor-close",
        "adapter-exit",
    ]


def test_audio_only_true_from_wire_reaches_process_episode(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    result = object()

    class FakeProcessor:
        def process_episode(self, *args: object, **kwargs: object) -> object:
            captured["args"] = args
            captured["kwargs"] = kwargs
            return result

        def close(self) -> None:
            pass

    monkeypatch.setattr(
        anki_adapter_module,
        "AndroidAnkiAdapter",
        lambda *_args, **_kwargs: nullcontext(object()),
    )
    monkeypatch.setattr(mining, "_build_processor", lambda *_args: FakeProcessor())
    adapters = SimpleNamespace(
        anki=object(),
        run_id=LIFECYCLE_RUN_ID,
        cancel_event=threading.Event(),
        progress=object(),
        curate=object(),
    )
    request = mining._parse_request(_request(audioOnly=True, videoPath="/cache/input.media"))

    assert mining._process_episode(request, object(), adapters) is result
    assert captured["args"] == (Path("/cache/input.media"), Path("/cache/subtitle.srt"))
    assert captured["kwargs"]["audio_only"] is True


def _episode_lifecycle_harness(
    monkeypatch: pytest.MonkeyPatch,
    processor: object,
    cancel_event: threading.Event,
) -> tuple[object, object, object]:
    class FakeAdapter:
        def __init__(self, *_: object, **__: object) -> None:
            pass

        def __enter__(self) -> FakeAdapter:
            return self

        def __exit__(self, *_: object) -> None:
            pass

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(mining, "_build_processor", lambda *_: processor)
    adapters = SimpleNamespace(
        anki=object(),
        run_id=LIFECYCLE_RUN_ID,
        cancel_event=cancel_event,
        progress=object(),
        curate=lambda candidates: candidates,
    )
    return mining._parse_request(_request()), object(), adapters


def test_process_episode_registers_snapshot_during_success_and_clears_after(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    visible: list[bool] = []

    class FakeProcessor:
        def process_episode(self, *_: object, **__: object) -> str:
            visible.append(_snapshot_present(LIFECYCLE_RUN_ID))
            return "result"

        def close(self) -> None:
            pass

    args = _episode_lifecycle_harness(monkeypatch, FakeProcessor(), threading.Event())
    assert mining._process_episode(*args) == "result"
    assert visible == [True]
    assert not _snapshot_present(LIFECYCLE_RUN_ID)


def test_process_episode_clears_snapshot_when_engine_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    visible: list[bool] = []

    class FakeProcessor:
        def process_episode(self, *_: object, **__: object) -> object:
            visible.append(_snapshot_present(LIFECYCLE_RUN_ID))
            raise RuntimeError("engine failed")

        def close(self) -> None:
            pass

    args = _episode_lifecycle_harness(monkeypatch, FakeProcessor(), threading.Event())
    with pytest.raises(RuntimeError, match="engine failed"):
        mining._process_episode(*args)
    assert visible == [True]
    assert not _snapshot_present(LIFECYCLE_RUN_ID)


def test_process_episode_clears_snapshot_when_processing_is_cancelled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    visible: list[bool] = []
    cancel_event = threading.Event()

    class FakeProcessor:
        def process_episode(self, *_: object, **__: object) -> str:
            visible.append(_snapshot_present(LIFECYCLE_RUN_ID))
            cancel_event.set()
            return "cancelled"

        def close(self) -> None:
            pass

    args = _episode_lifecycle_harness(monkeypatch, FakeProcessor(), cancel_event)
    assert mining._process_episode(*args) == "cancelled"
    assert visible == [True]
    assert cancel_event.is_set()
    assert not _snapshot_present(LIFECYCLE_RUN_ID)


def test_process_episode_cancelled_at_entry_opens_no_resources(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cancel_event = threading.Event()
    cancel_event.set()
    adapters = SimpleNamespace(cancel_event=cancel_event)

    class ForbiddenAdapter:
        def __init__(self, *_: object, **__: object) -> None:
            raise AssertionError("cancelled run must not open the Anki adapter")

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", ForbiddenAdapter)

    with pytest.raises(AnkiOperationCancelled) as cancelled:
        mining._process_episode(mining._parse_request(_request()), object(), adapters)

    assert cancelled.value.operation == "runVideo"


def test_process_episode_cleans_resources_when_engine_raises_baseexception(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class Fatal(BaseException):
        pass

    class FakeAdapter:
        def __init__(self, *_: object, **__: object) -> None:
            pass

        def __enter__(self) -> FakeAdapter:
            return self

        def __exit__(self, *_: object) -> None:
            events.append("adapter-exit")

    class FakeProcessor:
        def process_episode(self, *_: object, **__: object) -> object:
            raise Fatal()

        def close(self) -> None:
            events.append("processor-close")

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(mining, "_build_processor", lambda *_: FakeProcessor())
    request = mining._parse_request(_request())
    adapters = SimpleNamespace(
        anki=object(),
        run_id=LIFECYCLE_RUN_ID,
        cancel_event=threading.Event(),
        progress=object(),
        curate=lambda _: [],
    )

    with pytest.raises(Fatal):
        mining._process_episode(request, object(), adapters)
    assert events == ["processor-close", "adapter-exit"]


def test_cleanup_failure_does_not_mask_engine_baseexception(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class Fatal(BaseException):
        pass

    fatal = Fatal("primary")

    class FakeAdapter:
        def __init__(self, *_: object, **__: object) -> None:
            pass

        def __enter__(self) -> FakeAdapter:
            return self

        def __exit__(self, *_: object) -> None:
            events.append("adapter-exit")

    class FakeProcessor:
        def process_episode(self, *_: object, **__: object) -> object:
            raise fatal

        def close(self) -> None:
            events.append("processor-close")
            raise RuntimeError("secondary cleanup failure")

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(mining, "_build_processor", lambda *_: FakeProcessor())
    request = mining._parse_request(_request())
    adapters = SimpleNamespace(
        anki=object(),
        run_id=LIFECYCLE_RUN_ID,
        cancel_event=threading.Event(),
        progress=object(),
        curate=lambda _: [],
    )

    with pytest.raises(Fatal) as raised:
        mining._process_episode(request, object(), adapters)
    assert raised.value is fatal
    assert events == ["processor-close", "adapter-exit"]


def test_processor_close_failure_still_exits_adapter(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class FakeAdapter:
        def __init__(self, *_: object, **__: object) -> None:
            pass

        def __enter__(self) -> FakeAdapter:
            return self

        def __exit__(self, *_: object) -> None:
            events.append("adapter-exit")

    class FakeProcessor:
        def process_episode(self, *_: object, **__: object) -> object:
            events.append("process")
            return object()

        def close(self) -> None:
            events.append("processor-close")
            raise RuntimeError("close failed")

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(mining, "_build_processor", lambda *_: FakeProcessor())
    request = mining._parse_request(_request())
    adapters = SimpleNamespace(
        anki=object(),
        run_id=LIFECYCLE_RUN_ID,
        cancel_event=threading.Event(),
        progress=object(),
        curate=lambda _: [],
    )

    with pytest.raises(mining._PostProcessCleanupError) as cleanup:
        mining._process_episode(request, object(), adapters)
    assert cleanup.value.result is not None
    assert isinstance(cleanup.value.__cause__, RuntimeError)
    assert str(cleanup.value.__cause__) == "close failed"
    assert events == ["process", "processor-close", "adapter-exit"]


def test_post_success_cleanup_baseexception_is_not_converted_to_failed_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class Fatal(BaseException):
        pass

    fatal = Fatal("process-control failure")

    class FakeAdapter:
        def __init__(self, *_: object, **__: object) -> None:
            pass

        def __enter__(self) -> FakeAdapter:
            return self

        def __exit__(self, *_: object) -> None:
            events.append("adapter-exit")

    class FakeProcessor:
        def process_episode(self, *_: object, **__: object) -> object:
            events.append("process")
            return object()

        def close(self) -> None:
            events.append("processor-close")
            raise fatal

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(mining, "_build_processor", lambda *_: FakeProcessor())
    request = mining._parse_request(_request())
    adapters = SimpleNamespace(
        anki=object(),
        run_id=LIFECYCLE_RUN_ID,
        cancel_event=threading.Event(),
        progress=object(),
        curate=lambda _: [],
    )

    with pytest.raises(Fatal) as raised:
        mining._process_episode(request, object(), adapters)
    assert raised.value is fatal
    assert events == ["process", "processor-close", "adapter-exit"]


def test_post_process_cleanup_failure_preserves_result_and_partial_card_ids(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    result = FakeResult(errors=["write warning"], card_ids=[501, 502])
    monkeypatch.setattr(mining, "_ensure_runtime_ready", lambda: Path("/files"))
    monkeypatch.setattr(mining, "_map_config", lambda *_: object())

    def cleanup_failed(*_: object) -> object:
        raise mining._PostProcessCleanupError(result)

    monkeypatch.setattr(mining, "_process_episode", cleanup_failed)

    returned = mining.run_video(_request(), callbacks, job_registry=registry)

    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["outcome"] == "failed"
    assert terminal.payload["result"]["cardIds"] == [501, 502]
    assert terminal.payload["result"]["errors"] == ["write warning"]
    assert terminal.payload["error"] == {
        "code": "cleanup_failed",
        "message": "Mining finished but resource cleanup failed",
    }
    assert callbacks.terminals == [("error", returned)]
    assert registry.active_run_id is None


def test_expression_audio_chain_is_source_first_cancellable_and_best_effort() -> None:
    calls: list[str] = []
    hit = Path("/cache/audio.mp3")

    class Fetcher:
        def __init__(self, name: str, result: Path | None, fail: bool = False) -> None:
            self.name = name
            self.result = result
            self.fail = fail

        def fetch_candidates(self, candidates: object, cancelled_check: object) -> Path | None:
            calls.append(f"candidates:{self.name}")
            if self.fail:
                raise RuntimeError("broken pack")
            return self.result

        def fetch(self, *_: object) -> Path | None:
            calls.append(f"fetch:{self.name}")
            if self.fail:
                raise RuntimeError("broken pack")
            return self.result

        def close(self) -> None:
            calls.append(f"close:{self.name}")

    chain = mining._ExpressionAudioSourceChain(
        [Fetcher("broken", None, True), Fetcher("miss", None), Fetcher("hit", hit)]
    )
    assert chain.fetch_candidates([("猫", "ねこ")]) == hit
    assert calls == ["candidates:broken", "candidates:miss", "candidates:hit"]

    calls.clear()
    assert chain.fetch("猫", "ねこ", lambda: True) is None
    assert calls == []
    chain.close()
    assert calls == ["close:broken", "close:miss", "close:hit"]


@pytest.mark.parametrize(
    ("method_name", "args"),
    [
        ("fetch", ("猫", "ねこ")),
        ("fetch_candidates", ([("猫", "ねこ")],)),
    ],
)
def test_expression_audio_chain_reraises_memory_error(
    method_name: str,
    args: tuple[object, ...],
) -> None:
    calls: list[str] = []

    class ExhaustedFetcher:
        def fetch(self, *_: object) -> None:
            calls.append("exhausted")
            raise MemoryError("interpreter exhausted")

        def fetch_candidates(self, *_: object) -> None:
            calls.append("exhausted")
            raise MemoryError("interpreter exhausted")

    class ForbiddenFallback:
        def fetch(self, *_: object) -> None:
            calls.append("fallback")
            return None

        def fetch_candidates(self, *_: object) -> None:
            calls.append("fallback")
            return None

    chain = mining._ExpressionAudioSourceChain([ExhaustedFetcher(), ForbiddenFallback()])

    with pytest.raises(MemoryError, match="interpreter exhausted"):
        getattr(chain, method_name)(*args)

    assert calls == ["exhausted"]


def test_expression_audio_chain_pins_pack_copy_until_run_close(tmp_path: Path) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    from android_bridge.expression_audio_fetcher import _RunAudioCache

    cache_root = tmp_path / "audio_cache" / "local_packs"
    cache_root.mkdir(parents=True)
    stale = cache_root / "stale.mp3"
    stale.write_bytes(b"old")
    lifetime = _RunAudioCache(cache_root)
    assert not stale.exists()

    active = cache_root / "pack_word_reading.mp3"

    class Pack:
        def fetch(self, *_: object) -> Path:
            active.write_bytes(b"audio")
            return active

        def close(self) -> None:
            return None

    chain = mining._ExpressionAudioSourceChain([Pack()], cache_lifetime=lifetime)
    assert chain.fetch("猫", "ねこ") == active
    assert active.exists()

    unreferenced = cache_root / "unreferenced.mp3"
    unreferenced.write_bytes(b"old")
    lifetime.prune_unreferenced()
    assert active.exists()
    assert not unreferenced.exists()

    chain.close()
    assert not active.exists()


def test_failed_localaudio_falls_through_and_reports_privacy_safe_pack_fallback() -> None:
    hit = Path("/cache/audio.mp3")
    notices: list[str] = []

    class Localaudio:
        def fetch(self, *_: object) -> None:
            return None

        def stats(self) -> dict[str, int]:
            return {
                "ssl": 0,
                "connection": 1,
                "timeout": 1,
                "http_status": 0,
                "non_audio": 1,
                "policy_rejection": 2,
                "oversized_response": 0,
                "oversized_list": 1,
                "malformed_json": 1,
            }

        def close(self) -> None:
            return None

    class Pack:
        def fetch(self, *_: object) -> Path:
            return hit

        def close(self) -> None:
            return None

    localaudio = Localaudio()
    pack = Pack()
    chain = mining._ExpressionAudioSourceChain(
        [localaudio, pack],
        localaudio_fetcher=localaudio,
        fallback_fetchers=(pack,),
        diagnostic_callback=notices.append,
    )

    assert chain.fetch("猫", "ねこ") == hit
    chain.close()

    assert notices == [
        "Expression audio: localaudio unavailable=1; timeouts=1; rejected sources=2; "
        "oversized lists=1; malformed JSON=1; non-audio responses=1; fallback pack hits=1"
    ]
    assert "http" not in notices[0]
    assert "/cache" not in notices[0]


def test_circuit_skips_appear_in_run_summary() -> None:
    notices: list[str] = []

    class Localaudio:
        def fetch(self, *_: object) -> None:
            return None

        def stats(self) -> dict[str, int]:
            return {"circuit_skipped": 4, "timeout": 3}

        def close(self) -> None:
            return None

    localaudio = Localaudio()
    chain = mining._ExpressionAudioSourceChain(
        [localaudio],
        localaudio_fetcher=localaudio,
        fallback_fetchers=(),
        diagnostic_callback=notices.append,
    )

    chain.close()

    assert notices == ["Expression audio: timeouts=3; localaudio skipped after repeated failures=4"]


def test_unreadable_pack_index_is_counted_and_reported(tmp_path: Path) -> None:
    notices: list[str] = []
    garbage_db = tmp_path / "index.sqlite"
    garbage_db.write_bytes(b"not a sqlite database at all")

    class StubPack:
        pack_id = "my-pack"

        def fetch(self, *_: object) -> None:
            return None

        def close(self) -> None:
            return None

    wrapped = mining._DiagnosedPackFetcher(StubPack(), garbage_db)
    chain = mining._ExpressionAudioSourceChain(
        [wrapped],
        fallback_fetchers=(wrapped,),
        diagnostic_callback=notices.append,
    )

    assert chain.fetch("猫", "ねこ") is None
    assert chain.fetch("犬", "いぬ") is None
    chain.close()

    assert notices == ["Expression audio: pack 'my-pack' index unreadable (2 lookups failed)"]
    pack_fragment = notices[0].split("pack ")[1]
    assert "/" not in pack_fragment
    assert "猫" not in notices[0]


def test_enabled_but_unresolvable_pack_ids_reported_on_close(
    initialized_bridge_home: Path,
) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    notices: list[str] = []

    config = SimpleNamespace(
        expression_audio_chain=(SimpleNamespace(kind="pack", pack_id="ghost", url=None, enabled=True),),
        anki_fields={"expression_audio": "Audio"},
        audio_packs_root=initialized_bridge_home / "audio_packs",
    )
    chain = mining._build_expression_audio_source_chain(config, diagnostic_callback=notices.append)
    assert chain is not None
    chain.close()

    assert notices == ["Expression audio: enabled packs unavailable: ghost"]


def test_pack_wrapper_probe_never_raises_and_memoizes(tmp_path: Path) -> None:
    class StubPack:
        pack_id = "my-pack"

        def fetch(self, *_: object) -> None:
            return None

        def close(self) -> None:
            return None

    wrapped = mining._DiagnosedPackFetcher(StubPack(), tmp_path / "does-not-exist.sqlite")
    probes: list[int] = []
    original = wrapped._probe_once
    wrapped._probe_once = lambda: probes.append(1) or original()  # type: ignore[method-assign]

    assert wrapped.fetch("猫", "ねこ", None) is None
    assert wrapped.fetch("犬", "いぬ", None) is None
    assert wrapped.fetch("鳥", "とり", None) is None

    assert probes == [1]  # memoized after the first miss
    assert wrapped.pack_stats() == {"attempts": 3, "hits": 0, "index_unreadable": 1}


@pytest.mark.parametrize("kind", ["jpod101", "googletts"])
def test_expression_audio_builder_rejects_cut_network_kinds_before_allocation(kind: str) -> None:
    # custom/custom_json are now deliberately accepted (localaudio + local-audio-
    # yomichan); only the CUT network kinds must still raise, and before any
    # Session/import/packs-dir scan.
    config = SimpleNamespace(
        expression_audio_chain=(SimpleNamespace(kind=kind, pack_id=None, url=None, enabled=True),),
        anki_fields={"expression_audio": "Audio"},
    )
    with pytest.raises(BridgeProtocolError) as error:
        mining._build_expression_audio_source_chain(config)
    assert error.value.code == "unsupported_android_feature"


def test_expression_audio_builder_rejects_cut_kind_before_any_packs_scan(
    monkeypatch: pytest.MonkeyPatch,
    initialized_bridge_home: Path,
) -> None:
    # A cut network kind AFTER a valid pack entry must still raise before the
    # packs-dir is ever scanned (validate-all-first / reject-before-allocate).
    # Monkeypatching the registry by path imports anki_miner.services (requests).
    pytest.importorskip("requests", reason="runtime dependency lane")
    scanned: list[str] = []

    class RecordingRegistry:
        def __init__(self, root: object) -> None:
            scanned.append("constructed")

        def load(self) -> None:
            scanned.append("loaded")

        def build_fetcher_chain(self, config: object, cache_dir: object) -> list[object]:
            scanned.append("built")
            return []

    monkeypatch.setattr(
        "anki_miner.services.audio_packs.registry.AudioPackRegistry",
        RecordingRegistry,
    )
    config = SimpleNamespace(
        expression_audio_chain=(
            SimpleNamespace(kind="pack", pack_id="my-pack", url=None, enabled=True),
            SimpleNamespace(kind="jpod101", pack_id=None, url=None, enabled=True),
        ),
        anki_fields={"expression_audio": "Audio"},
        audio_packs_root=initialized_bridge_home / "audio_packs",
    )
    with pytest.raises(BridgeProtocolError) as error:
        mining._build_expression_audio_source_chain(config)
    assert error.value.code == "unsupported_android_feature"
    assert scanned == []


def test_expression_audio_builder_returns_none_when_field_unmapped() -> None:
    # Even with the always-enabled injected localaudio entry, an unmapped
    # expression_audio field means the fetcher is never consulted -> None.
    config = SimpleNamespace(
        expression_audio_chain=(
            SimpleNamespace(kind="custom_json", pack_id=None, url="http://localhost:8765/x", enabled=True),
        ),
        anki_fields={"expression_audio": ""},
    )
    assert mining._build_expression_audio_source_chain(config) is None


def test_expression_audio_builder_builds_localaudio_custom_json_source(
    initialized_bridge_home: Path,
) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    from android_bridge.config_map import _LOCALAUDIO_URL
    from android_bridge.expression_audio_fetcher import CustomAudioFetcher, custom_audio_slug
    from anki_miner.config.paths import ANKI_MINER_HOME

    config = SimpleNamespace(
        expression_audio_chain=(SimpleNamespace(kind="custom_json", pack_id=None, url=_LOCALAUDIO_URL, enabled=True),),
        anki_fields={"expression_audio": "Audio"},
        audio_packs_root=initialized_bridge_home / "audio_packs",
    )
    chain = mining._build_expression_audio_source_chain(config)
    assert chain is not None
    try:
        fetchers = chain._fetchers
        assert len(fetchers) == 1
        fetcher = fetchers[0]
        assert isinstance(fetcher, CustomAudioFetcher)
        # Cached UNDER the approved local-pack staging root (bug-6-independent).
        slug = custom_audio_slug(_LOCALAUDIO_URL)
        assert fetcher._cache_dir == ANKI_MINER_HOME / "audio_cache" / "local_packs" / f"custom_{slug}"
    finally:
        chain.close()


def test_expression_audio_builder_authenticates_the_localaudio_loopback_origin(
    initialized_bridge_home: Path,
) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    from android_bridge.config_map import _LOCALAUDIO_URL

    config = SimpleNamespace(
        expression_audio_chain=(SimpleNamespace(kind="custom_json", pack_id=None, url=_LOCALAUDIO_URL, enabled=True),),
        anki_fields={"expression_audio": "Audio"},
        audio_packs_root=initialized_bridge_home / "audio_packs",
    )
    chain = mining._build_expression_audio_source_chain(config)
    assert chain is not None
    try:
        fetcher = chain._fetchers[0]
        # The production construction site is the only place that declares loopback
        # trust; without it every localaudio fetch is rejected before the request.
        fetcher._validate_url(_LOCALAUDIO_URL.format(term="食べる", reading="たべる"), directory_only=False)
        fetcher._validate_url("http://127.0.0.1:8765/localaudio/audio.mp3", directory_only=False)
    finally:
        chain.close()


def test_expression_audio_builder_orders_localaudio_primary_pack_fallback(
    monkeypatch: pytest.MonkeyPatch,
    initialized_bridge_home: Path,
) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    from android_bridge.config_map import _LOCALAUDIO_URL
    from android_bridge.expression_audio_fetcher import CustomAudioFetcher

    class FakePackFetcher:
        def __init__(self, pack_id: str) -> None:
            self.pack_id = pack_id

        def close(self) -> None:
            return None

    fake_pack = FakePackFetcher("my-pack")

    class FakeRegistry:
        def __init__(self, root: object) -> None:
            return None

        def load(self) -> None:
            return None

        def build_fetcher_chain(self, config: object, cache_dir: object) -> list[object]:
            return [fake_pack]

    monkeypatch.setattr(
        "anki_miner.services.audio_packs.registry.AudioPackRegistry",
        FakeRegistry,
    )
    config = SimpleNamespace(
        expression_audio_chain=(
            SimpleNamespace(kind="custom_json", pack_id=None, url=_LOCALAUDIO_URL, enabled=True),
            SimpleNamespace(kind="pack", pack_id="my-pack", url=None, enabled=True),
        ),
        anki_fields={"expression_audio": "Audio"},
        audio_packs_root=initialized_bridge_home / "audio_packs",
    )
    chain = mining._build_expression_audio_source_chain(config)
    assert chain is not None
    try:
        fetchers = chain._fetchers
        assert len(fetchers) == 2
        # Config order == source priority: localaudio primary, pack fallback.
        assert isinstance(fetchers[0], CustomAudioFetcher)
        # The pack fetcher is wrapped for diagnostics; identity holds beneath it.
        assert isinstance(fetchers[1], mining._DiagnosedPackFetcher)
        assert fetchers[1].pack_id == "my-pack"
        assert fetchers[1]._fetcher is fake_pack
    finally:
        chain.close()


def test_online_dictionary_provider_is_memoized_and_cancel_gated_per_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, object]] = []

    class Response:
        status_code = 200
        headers: dict[str, str] = {}

        def iter_content(self, *, chunk_size: int) -> object:
            assert chunk_size == 8192
            yield json.dumps(
                {
                    "data": [
                        {
                            "senses": [
                                {"english_definitions": ["cat & companion"]},
                            ]
                        }
                    ]
                }
            ).encode()

        def close(self) -> None:
            calls.append(("response-close", None))

    class Session:
        def get(self, url: str, **kwargs: object) -> Response:
            calls.append((url, kwargs))
            return Response()

        def close(self) -> None:
            calls.append(("session-close", None))

    class OnlineProvider:
        name = "online"
        is_online = True
        _api_url = "https://jisho.org/api/v1/search/words"
        _delay = 0.0

        def is_available(self) -> bool:
            return True

        def load(self) -> bool:
            return True

        def lookup(self, word: str) -> str | None:
            raise AssertionError("Android wrapper must own Jisho transport")

        def close(self) -> None:
            calls.append(("provider-close", None))

    monkeypatch.setattr(mining, "_new_jisho_session", Session, raising=False)
    cancelled = threading.Event()
    wrapped = mining._android_dictionary_provider_chain(
        [OnlineProvider()],
        cancelled.is_set,
    )[0]

    # Definition and glossary passes reuse the one consented network lookup.
    expected = (
        '<div class="yomitan-glossary"><ol data-count="1">'
        '<li data-dictionary="online"><i>(online)</i>'
        '<ul class="gloss-list" data-count="1"><li class="gloss-item">'
        '<div class="gloss-content">cat &amp; companion</div></li></ul></li></ol></div>'
    )
    assert wrapped.lookup("猫") == expected
    assert wrapped.lookup("猫") == expected
    request_calls = [call for call in calls if call[0].startswith("https://")]
    assert len(request_calls) == 1

    cancelled.set()
    assert wrapped.lookup("犬") is None
    assert len([call for call in calls if call[0].startswith("https://")]) == 1
    wrapped.close()
    assert calls[-2:] == [("session-close", None), ("provider-close", None)]


def test_online_dictionary_provider_memoizes_exact_term_before_malformed_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request_count = 0

    class Response:
        status_code = 200

        def iter_content(self, *, chunk_size: int) -> object:
            assert chunk_size == 8192
            yield b"[]"

        def close(self) -> None:
            return None

    class Session:
        def get(self, _url: str, **_kwargs: object) -> Response:
            nonlocal request_count
            request_count += 1
            return Response()

        def close(self) -> None:
            return None

    provider = SimpleNamespace(
        name="Jisho API",
        is_online=True,
        _api_url="https://jisho.org/api/v1/search/words",
        _delay=0.0,
        is_available=lambda: True,
        load=lambda: True,
        close=lambda: None,
    )
    monkeypatch.setattr(mining, "_new_jisho_session", Session)
    wrapped = mining._AndroidOnlineDictionaryProvider(provider, lambda: False)
    try:
        with pytest.raises(AttributeError):
            wrapped.lookup("猫")

        assert wrapped.lookup("猫") is None
        assert request_count == 1
    finally:
        wrapped.close()


def test_online_dictionary_cancellation_closes_trickling_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cancelled = threading.Event()
    body_started = threading.Event()
    response_closed = threading.Event()
    delegated_release = threading.Event()

    class TricklingResponse:
        status_code = 200
        headers: dict[str, str] = {}

        def iter_content(self, *, chunk_size: int) -> object:
            assert chunk_size == 8192
            body_started.set()
            while not response_closed.wait(0.01):
                yield b" "

        def close(self) -> None:
            response_closed.set()

    class Session:
        def get(self, _url: str, **_kwargs: object) -> TricklingResponse:
            return TricklingResponse()

        def close(self) -> None:
            return None

    class OnlineProvider:
        name = "Jisho API"
        is_online = True
        _api_url = "https://jisho.org/api/v1/search/words"
        _delay = 0.0

        def is_available(self) -> bool:
            return True

        def load(self) -> bool:
            return True

        def lookup(self, _word: str) -> str | None:
            delegated_release.wait(5)
            return "delegated"

        def close(self) -> None:
            return None

    monkeypatch.setattr(mining, "_new_jisho_session", Session, raising=False)
    wrapped = mining._AndroidOnlineDictionaryProvider(OnlineProvider(), cancelled.is_set)
    results: list[str | None] = []
    thread = threading.Thread(target=lambda: results.append(wrapped.lookup("猫")), daemon=True)
    thread.start()
    try:
        assert body_started.wait(1), "Android wrapper did not start its owned streamed request"
        cancelled.set()
        thread.join(1)
        assert not thread.is_alive(), "in-flight lookup ignored cancellation"
        assert response_closed.is_set(), "cancellation did not close active response"
        assert results == [None]
    finally:
        delegated_release.set()
        thread.join(1)
        wrapped.close()


def test_online_dictionary_cancellation_returns_while_response_headers_are_pending(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cancelled = threading.Event()
    request_started = threading.Event()
    session_closed = threading.Event()

    class Session:
        def get(self, _url: str, **_kwargs: object) -> object:
            request_started.set()
            session_closed.wait(5)
            raise OSError("session closed")

        def close(self) -> None:
            session_closed.set()

    provider = SimpleNamespace(
        name="Jisho API",
        is_online=True,
        _api_url="https://jisho.org/api/v1/search/words",
        _delay=0.0,
        is_available=lambda: True,
        load=lambda: True,
        lookup=lambda _word: "delegated",
        close=lambda: None,
    )
    monkeypatch.setattr(mining, "_new_jisho_session", Session)
    wrapped = mining._AndroidOnlineDictionaryProvider(provider, cancelled.is_set)
    results: list[str | None] = []
    thread = threading.Thread(target=lambda: results.append(wrapped.lookup("猫")), daemon=True)
    thread.start()
    try:
        assert request_started.wait(1), "Jisho request did not enter the response-header phase"
        cancelled.set()
        thread.join(0.2)
        assert not thread.is_alive(), "header-phase request ignored cancellation"
        assert session_closed.is_set(), "cancellation did not close the request session"
        assert results == [None]
    finally:
        session_closed.set()
        thread.join(1)
        wrapped.close()


def test_online_dictionary_does_not_stack_requests_after_header_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request_count = 0
    release_requests = threading.Event()

    class Session:
        def get(self, _url: str, **_kwargs: object) -> object:
            nonlocal request_count
            request_count += 1
            release_requests.wait(5)
            raise OSError("late transport failure")

        def close(self) -> None:
            # Requests closes adapters/pools but does not guarantee interruption
            # of a request already reading response headers.
            return None

    provider = SimpleNamespace(
        name="Jisho API",
        is_online=True,
        _api_url="https://jisho.org/api/v1/search/words",
        _delay=0.0,
        is_available=lambda: True,
        load=lambda: True,
        lookup=lambda _word: "delegated",
        close=lambda: None,
    )
    monkeypatch.setattr(mining, "_new_jisho_session", Session)
    monkeypatch.setattr(mining, "_JISHO_TOTAL_DEADLINE_SECONDS", 0.05)
    monkeypatch.setattr(mining, "_JISHO_WATCH_POLL_SECONDS", 0.005)
    wrapped = mining._AndroidOnlineDictionaryProvider(provider, lambda: False)
    try:
        assert wrapped.lookup("猫") is None
        assert wrapped.lookup("犬") is None
        assert request_count == 1
    finally:
        release_requests.set()
        wrapped.close()


def test_online_dictionary_total_deadline_stops_trickling_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response_closed = threading.Event()

    class TricklingResponse:
        status_code = 200
        headers: dict[str, str] = {}

        def iter_content(self, *, chunk_size: int) -> object:
            assert chunk_size == 8192
            while not response_closed.wait(0.005):
                yield b" "

        def close(self) -> None:
            response_closed.set()

    class Session:
        def get(self, _url: str, **_kwargs: object) -> TricklingResponse:
            return TricklingResponse()

        def close(self) -> None:
            return None

    provider = SimpleNamespace(
        name="Jisho API",
        is_online=True,
        _api_url="https://jisho.org/api/v1/search/words",
        _delay=0.0,
        is_available=lambda: True,
        load=lambda: True,
        lookup=lambda _word: "delegated",
        close=lambda: None,
    )
    monkeypatch.setattr(mining, "_new_jisho_session", Session, raising=False)
    monkeypatch.setattr(mining, "_JISHO_TOTAL_DEADLINE_SECONDS", 0.05, raising=False)
    monkeypatch.setattr(mining, "_JISHO_WATCH_POLL_SECONDS", 0.005, raising=False)
    wrapped = mining._AndroidOnlineDictionaryProvider(provider, lambda: False)
    try:
        assert wrapped.lookup("猫") is None
        assert response_closed.wait(0.2), "total deadline did not close trickling response"
    finally:
        wrapped.close()


def test_online_dictionary_rejects_http_redirect_without_following(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, dict[str, object]]] = []
    response_closed = False

    class RedirectResponse:
        status_code = 302
        headers = {"Location": "http://jisho.org/api/v1/search/words?keyword=猫"}

        def iter_content(self, **_kwargs: object) -> object:
            raise AssertionError("redirect bodies must not be consumed")

        def close(self) -> None:
            nonlocal response_closed
            response_closed = True

    class Session:
        def get(self, url: str, **kwargs: object) -> RedirectResponse:
            calls.append((url, kwargs))
            return RedirectResponse()

        def close(self) -> None:
            return None

    provider = SimpleNamespace(
        name="Jisho API",
        is_online=True,
        _api_url="https://jisho.org/api/v1/search/words",
        _delay=0.0,
        is_available=lambda: True,
        load=lambda: True,
        lookup=lambda _word: "cleartext injected definition",
        close=lambda: None,
    )
    monkeypatch.setattr(mining, "_new_jisho_session", Session, raising=False)
    wrapped = mining._AndroidOnlineDictionaryProvider(provider, lambda: False)
    try:
        assert wrapped.lookup("猫") is None
    finally:
        wrapped.close()

    assert response_closed
    assert calls == [
        (
            "https://jisho.org/api/v1/search/words",
            {
                "params": {"keyword": "猫"},
                "timeout": (1.0, 1.0),
                "stream": True,
                "allow_redirects": False,
            },
        )
    ]


def test_offline_dictionary_error_is_reworded_for_android() -> None:
    pytest.importorskip("requests")
    from anki_miner.exceptions import SetupError
    from anki_miner.orchestration.episode_processor import (
        _OFFLINE_DICTIONARY_REQUIRED_MESSAGE,
    )

    # The engine pre-flight names desktop menus ("Tools -> Download Recommended
    # Resources"); its text crosses the bridge verbatim, so the run must not tell
    # an Android user to use surfaces the app does not have.
    _outcome, terminal = mining._exception_terminal(
        "run_" + "a" * 32,
        SetupError(_OFFLINE_DICTIONARY_REQUIRED_MESSAGE),
        cancelled=False,
        log=mining.logger,
    )
    payload = json.loads(terminal)["payload"]
    assert payload["error"]["code"] == "engine_error"
    assert "Tools" not in payload["error"]["message"]
    assert payload["error"]["message"] == (
        "No usable offline dictionary is installed. Import one in Settings, under Dictionaries."
    )

    # Every other engine message still crosses unchanged.
    _outcome, other = mining._exception_terminal(
        "run_" + "b" * 32,
        SetupError("Something else went wrong"),
        cancelled=False,
        log=mining.logger,
    )
    assert json.loads(other)["payload"]["error"]["message"] == "Something else went wrong"


def test_raised_failures_carry_a_fault_id_matching_their_logged_traceback(
    caplog: pytest.LogCaptureFixture,
) -> None:
    pytest.importorskip("requests")
    from anki_miner.exceptions import SetupError

    with caplog.at_level("ERROR", logger=mining.logger.name):
        _outcome, engine_terminal = mining._exception_terminal(
            "run_" + "a" * 32,
            SetupError("Something else went wrong"),
            cancelled=False,
            log=mining.logger,
        )
        _outcome, internal_terminal = mining._exception_terminal(
            "run_" + "b" * 32,
            RuntimeError("secret /storage/emulated/0/episode.mkv"),
            cancelled=False,
            log=mining.logger,
        )
        _outcome, cancelled_terminal = mining._exception_terminal(
            "run_" + "c" * 32,
            AnkiOperationCancelled("createNotes", "stopped", False),
            cancelled=True,
            log=mining.logger,
        )

    engine_error = json.loads(engine_terminal)["payload"]["error"]
    internal_error = json.loads(internal_terminal)["payload"]["error"]
    engine_fault = engine_error.pop("faultId")
    internal_fault = internal_error.pop("faultId")

    # Messages stay byte-identical: the id rides beside them, never inside them.
    assert engine_error == {"code": "engine_error", "message": "Something else went wrong"}
    assert internal_error == {"code": "internal_error", "message": "Internal mining failure"}
    assert json.loads(cancelled_terminal)["payload"]["error"] == {
        "code": "cancelled",
        "message": "stopped",
    }

    assert re.fullmatch(FAULT_ID_PATTERN, engine_fault)
    assert re.fullmatch(FAULT_ID_PATTERN, internal_fault)
    assert engine_fault != internal_fault
    assert "secret" not in internal_terminal
    assert "/storage/emulated/0" not in internal_terminal

    faults = [record for record in caplog.records if record.exc_info]
    assert len(faults) == 2
    assert [engine_fault in record.getMessage() for record in faults] == [True, False]
    assert [internal_fault in record.getMessage() for record in faults] == [False, True]
    assert "secret /storage/emulated/0/episode.mkv" in caplog.text


def _vendored_method_params(module_path: str, class_name: str, method: str) -> set[str]:
    """Parameter names of a vendored engine method, read without importing it.

    ``anki_miner.services`` imports ``requests`` at package scope, which the
    host lane does not carry — the same reason every bridge import of the
    engine is function-local. Parsing keeps this contract check in the lane
    that runs on every push, rather than in the runtime-dependency lane.
    """
    source = (PROJECT_ROOT / "app/src/main/python" / module_path).read_text(encoding="utf-8")
    tree = ast.parse(source)
    for node in tree.body:
        if not isinstance(node, ast.ClassDef) or node.name != class_name:
            continue
        for item in node.body:
            if isinstance(item, ast.FunctionDef) and item.name == method:
                args = item.args
                names = {arg.arg for arg in (*args.posonlyargs, *args.args, *args.kwonlyargs)}
                return names - {"self"}
        raise AssertionError(f"{class_name} has no method {method}")
    raise AssertionError(f"{module_path} has no class {class_name}")


def test_engine_composition_seams_still_have_the_parameters_the_bridge_passes() -> None:
    """The import-closure gate proves imports resolve, not that signatures match.

    Every seam here is one the bridge hand-mirrors from the desktop service
    factory. A re-pin that adds an optional kwarg changes which words get mined
    with no exception anywhere, so the names are asserted rather than inferred.
    """
    processor_params = _vendored_method_params(
        "anki_miner/orchestration/episode_processor.py", "EpisodeProcessor", "__init__"
    )
    assert {"frequency_registry", "pitch_registry"} <= processor_params

    parser_params = _vendored_method_params(
        "anki_miner/services/subtitle_parser.py", "SubtitleParserService", "__init__"
    )
    assert {"name_lookup", "term_rules_lookup"} <= parser_params

    episode_params = _vendored_method_params(
        "anki_miner/orchestration/episode_processor.py", "EpisodeProcessor", "process_episode"
    )
    assert "cross_episode_counts" not in episode_params

    # Renamed upstream when the gate grew past dictionaries.
    _vendored_method_params(
        "anki_miner/orchestration/episode_processor.py", "EpisodeProcessor", "check_resource_staleness"
    )


def _bridge_call_keywords(function: str, callee: str) -> set[str]:
    """Keyword names the bridge passes at one call site inside *function*."""
    source = (PROJECT_ROOT / "app/src/main/python/android_bridge/mining.py").read_text(encoding="utf-8")
    for node in ast.walk(ast.parse(source)):
        if not isinstance(node, ast.FunctionDef) or node.name != function:
            continue
        for call in ast.walk(node):
            if not isinstance(call, ast.Call):
                continue
            target = call.func
            name = target.attr if isinstance(target, ast.Attribute) else getattr(target, "id", None)
            if name == callee:
                return {keyword.arg for keyword in call.keywords if keyword.arg is not None}
        raise AssertionError(f"{function} never calls {callee}")
    raise AssertionError(f"mining.py has no function {function}")


def test_bridge_passes_every_new_engine_seam() -> None:
    """The bridge must actually supply what the engine grew, not merely tolerate it.

    Omitting ``term_rules_lookup`` makes the deinflection resolver fail closed
    to ``orth_base``; omitting ``name_lookup`` stops multi-token name spans
    merging; omitting either registry narrows the staleness gate back to
    dictionaries. None of the three raises, and none shows up in a run report.
    """
    parser_kwargs = _bridge_call_keywords("_build_processor", "SubtitleParserService")
    assert {"name_lookup", "term_rules_lookup"} <= parser_kwargs

    processor_kwargs = _bridge_call_keywords("_build_processor", "EpisodeProcessor")
    assert {"frequency_registry", "pitch_registry"} <= processor_kwargs

    episode_kwargs = _bridge_call_keywords("_process_episode", "process_episode")
    assert "cross_episode_counts" not in episode_kwargs


def test_runtime_composition_injects_only_android_video_services(
    monkeypatch: pytest.MonkeyPatch,
    initialized_bridge_home: Path,
) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    modules = {
        name: importlib.import_module(name)
        for name in (
            "anki_miner.orchestration.episode_processor",
            "anki_miner.services.definition_service",
            "anki_miner.services.dictionary.registry",
            "anki_miner.services.frequency.multi_frequency_service",
            "anki_miner.services.frequency.registry",
            "anki_miner.services.known_word_db",
            "anki_miner.services.media_extractor",
            "anki_miner.services.pitch_accent.multi_pitch_service",
            "anki_miner.services.pitch_accent.registry",
            "anki_miner.services.stats_service",
            "anki_miner.services.subtitle_parser",
            "anki_miner.services.word_filter",
            "anki_miner.services.word_list_service",
            "anki_miner.services.wordset_service",
        )
    }
    events: list[str] = []
    captured: dict[str, object] = {}
    tagger = object()
    expected_tagger = tagger
    expected_term_lookup = object()
    expected_reading_lookup = object()
    expected_kana_attest_lookup = object()
    expected_term_common_lookup = object()

    class Registry:
        def __init__(self, root: Path) -> None:
            events.append("dictionary-registry")

        def load(self) -> None:
            events.append("dictionary-load")

        def build_provider_chain(self, config: object) -> list[object]:
            return []

    class Definition:
        def __init__(self, config: object, providers: list[object], *, registry: object) -> None:
            # registry= is what makes the processor's check_offline_dictionary
            # pre-flight see a usable provider; a registry-less service aborts
            # every run.
            assert isinstance(registry, Registry)
            events.append("definition")
            self.offline_terms_exist = expected_term_lookup
            self.offline_term_readings = expected_reading_lookup
            self.has_offline_definitions = expected_kana_attest_lookup
            self.offline_term_commonness = expected_term_common_lookup

        def ensure_loaded(self) -> None:
            events.append("definition-load")

        def close(self) -> None:
            events.append("definition-close")

    class SubtitleParser:
        def __init__(
            self,
            config: object,
            *,
            term_lookup: object,
            reading_lookup: object,
            kana_attest_lookup: object,
            term_common_lookup: object,
        ) -> None:
            assert term_lookup is expected_term_lookup
            assert reading_lookup is expected_reading_lookup
            assert kana_attest_lookup is expected_kana_attest_lookup
            assert term_common_lookup is expected_term_common_lookup
            self.tagger = tagger
            events.append("subtitle-parser")

    class WordFilter:
        def __init__(self, config: object, *, tagger: object) -> None:
            assert tagger is expected_tagger
            events.append("word-filter")

    class MediaExtractor:
        def __init__(self, config: object) -> None:
            events.append("media-extractor")

    class KnownWords:
        def __init__(self, path: Path) -> None:
            events.append("known-words")

    class Stats:
        def __init__(self, path: Path) -> None:
            events.append("stats")

        def load(self) -> bool:
            return True

    class Processor:
        def __init__(self, **kwargs: object) -> None:
            captured.update(kwargs)
            events.append("processor")

    replacements = {
        "anki_miner.orchestration.episode_processor": ("EpisodeProcessor", Processor),
        "anki_miner.services.definition_service": ("DefinitionService", Definition),
        "anki_miner.services.dictionary.registry": ("DictionaryRegistry", Registry),
        "anki_miner.services.known_word_db": ("KnownWordDB", KnownWords),
        "anki_miner.services.media_extractor": (
            "MediaExtractorService",
            MediaExtractor,
        ),
        "anki_miner.services.stats_service": ("StatsService", Stats),
        "anki_miner.services.subtitle_parser": (
            "SubtitleParserService",
            SubtitleParser,
        ),
        "anki_miner.services.word_filter": ("WordFilterService", WordFilter),
    }
    for module_name, (attribute, replacement) in replacements.items():
        monkeypatch.setattr(modules[module_name], attribute, replacement)

    config = SimpleNamespace(
        dicts_root=Path("/files/dicts"),
        dictionary_chain=(SimpleNamespace(kind="indexed", enabled=True),),
        expression_audio_chain=(),
        anki_fields={},
        pitch_active=False,
        frequency_active=False,
        known_words_db_path=Path("/files/known.db"),
        use_known_words_db=False,
        use_blacklist=False,
        use_whitelist=False,
        excluded_wordsets=(),
        stats_db_path=Path("/files/stats.db"),
    )
    presenter = SimpleNamespace(show_warning=lambda _message: None)
    adapters = SimpleNamespace(presenter=presenter, cancel_event=threading.Event())
    anki_adapter = object()

    processor = mining._build_processor(config, adapters, anki_adapter)

    assert isinstance(processor, Processor)
    assert events == [
        "dictionary-registry",
        "dictionary-load",
        "definition",
        "definition-load",
        "subtitle-parser",
        "word-filter",
        "media-extractor",
        "known-words",
        "stats",
        "processor",
    ]
    assert captured["anki_service"] is anki_adapter
    assert captured["presenter"] is presenter
    assert captured["youtube_fetcher"] is None
    assert captured["sentence_audio_fetcher"] is None
    assert captured["expression_audio_fetcher"] is None
    assert isinstance(captured["dictionary_registry"], Registry)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("videoPath", "relative.mkv"),
        ("videoPath", "/video\x00.mkv"),
        ("subtitlePath", "relative.srt"),
        ("subtitlePath", "/cache/subtitle.txt"),
        ("cacheDir", "relative"),
        ("nativeLibraryDir", "relative"),
        ("episodeName", " Episode"),
        ("seriesName", "Series "),
        ("seriesName", "\u1e0a\u0323"),
        ("sourceLabel", "bad\u0000label"),
        ("audioTrackOverride", -1),
        ("audioTrackOverride", True),
        ("audioTrackOverride", 1.5),
    ],
)
def test_request_rejects_invalid_scalar_fields(field: str, value: object) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        mining._parse_request(_request(**{field: value}))
    assert error.value.code == "invalid_video_mining_request"


@pytest.mark.parametrize(
    "snapshot",
    [
        None,
        {},
        {"settings": []},
        {"settings": {}, "androidTtsEnabled": None},
        {"settings": {}, "unknown": True},
    ],
)
def test_request_rejects_invalid_config_snapshot(snapshot: object) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        mining._parse_request(_request(configSnapshot=snapshot))
    assert error.value.code == "invalid_video_mining_request"


def test_request_requires_audio_only() -> None:
    payload = _payload()
    del payload["audioOnly"]

    with pytest.raises(BridgeProtocolError):
        mining._parse_request(encode_message("mining.video.run", payload))


@pytest.mark.parametrize("audio_only", [1, 0, "true", None, [], {}])
def test_request_rejects_non_boolean_audio_only(audio_only: object) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        mining._parse_request(_request(audioOnly=audio_only))
    assert error.value.code == "invalid_video_mining_request"
    assert str(error.value) == "audioOnly must be a boolean"


def test_audio_only_request_allows_media_video_path_suffix() -> None:
    parsed = mining._parse_request(_request(videoPath="/cache/input.media", audioOnly=True))

    assert parsed.video_path == Path("/cache/input.media")
    assert parsed.audio_only is True


def test_request_accepts_an_empty_episode_name() -> None:
    # Kotlin sends an empty episode when the picked file has no usable name.
    # _resolve_identity honours "", so the engine composes "<series> — " and
    # the seam's strip leaves a bare "@ 00:12:34" instead of a lane label.
    parsed = mining._parse_request(_request(episodeName=""))

    assert parsed.episode_name == ""
    assert parsed.series_name == "Series"


def test_request_requires_exact_fields_and_preserves_fd_paths_and_identity() -> None:
    payload = _payload()
    assert set(payload) == {
        "videoPath",
        "subtitlePath",
        "episodeName",
        "seriesName",
        "sourceLabel",
        "audioTrackOverride",
        "audioOnly",
        "cacheDir",
        "nativeLibraryDir",
        "configSnapshot",
    }
    del payload["sourceLabel"]
    with pytest.raises(BridgeProtocolError):
        mining._parse_request(encode_message("mining.video.run", payload))

    payload = _payload(unknown=True)
    with pytest.raises(BridgeProtocolError):
        mining._parse_request(encode_message("mining.video.run", payload))

    parsed = mining._parse_request(
        _request(
            subtitlePath="/cache/subtitle.VTT",
            sourceLabel=None,
            audioTrackOverride=2.0,
        )
    )
    assert parsed.video_path == Path("/proc/self/fd/41")
    assert parsed.subtitle_path == Path("/cache/subtitle.VTT")
    assert parsed.episode_name == "Episode 1"
    assert parsed.series_name == "Series"
    assert parsed.source_label is None
    assert parsed.audio_track_override == 2
    assert parsed.audio_only is False


def test_mining_module_keeps_all_engine_imports_after_bootstrap_and_excludes_cut_modules() -> None:
    path = PROJECT_ROOT / "app" / "src" / "main" / "python" / "android_bridge" / "mining.py"
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    top_level_engine_imports = [
        node
        for node in tree.body
        if (isinstance(node, ast.Import) and any(alias.name.startswith("anki_miner") for alias in node.names))
        or (isinstance(node, ast.ImportFrom) and (node.module or "").startswith("anki_miner"))
    ]
    imported_modules = {
        alias.name for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names
    } | {node.module or "" for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)}

    assert top_level_engine_imports == []
    assert imported_modules.isdisjoint(
        {
            "anki_miner.gui.utils.service_factory",
            "anki_miner.services.youtube_fetcher",
            "anki_miner.services.sentence_tts_fetcher",
            "anki_miner.services.expression_audio_fetcher",
            "anki_miner.services.google_translate_audio_fetcher",
        }
    )


def test_optional_source_memory_error_fails_the_run(monkeypatch: pytest.MonkeyPatch) -> None:
    """Exhaustion must not disable the filter and let mining keep writing cards."""
    source = Path(mining.__file__).read_text(encoding="utf-8")
    tree = ast.parse(source)

    guarded = 0
    optional = 0
    for node in ast.walk(tree):
        if not isinstance(node, ast.Try):
            continue
        warns_and_disables = any(
            isinstance(handler.type, ast.Name)
            and handler.type.id == "Exception"
            and "_show_optional_failure" in ast.dump(handler)
            for handler in node.handlers
        )
        if not warns_and_disables:
            continue
        optional += 1
        for handler in node.handlers:
            if isinstance(handler.type, ast.Name) and handler.type.id == "MemoryError":
                assert any(isinstance(stmt, ast.Raise) for stmt in handler.body)
                guarded += 1
                break

    assert optional > 0
    assert guarded == optional, "every optional-source catch must re-raise MemoryError"
