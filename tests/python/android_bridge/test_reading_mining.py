from __future__ import annotations

import ast
import threading
from dataclasses import dataclass, field
from pathlib import Path
from types import SimpleNamespace

import android_bridge.anki_adapter as anki_adapter_module
import android_bridge.boundary as boundary
import android_bridge.jobs as jobs_module
import android_bridge.mining as video_mining
import android_bridge.reading_limits as reading_limits
import android_bridge.reading_mining as reading_mining
import pytest
from android_bridge.anki_adapter import AnkiOperationCancelled
from android_bridge.jobs import JobRegistry
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message

PROJECT_ROOT = Path(__file__).resolve().parents[3]


@dataclass
class FakeResult:
    total_words_found: int = 8
    new_words_found: int = 3
    cards_created: int = 2
    errors: object = field(default_factory=list)
    elapsed_time: float = 1.25
    comprehension_percentage: float = 62.5
    card_ids: list[int] = field(default_factory=lambda: [101, 102])
    video_file: str = ""
    subtitle_file: str = ""
    mined_forms: list[str] = field(default_factory=lambda: ["猫", "食べる"])


def _payload(cache_dir: Path | str = "/cache", **overrides: object) -> dict[str, object]:
    cache = Path(cache_dir)
    payload: dict[str, object] = {
        "sourceKind": "txt",
        "sourcePath": str(cache / "reading-job-v1-a" / "book.txt"),
        "imageArchivePath": None,
        "seriesName": None,
        "cacheDir": str(cache),
        "nativeLibraryDir": "/native",
        "configSnapshot": {"settings": {}, "androidTtsEnabled": False},
    }
    payload.update(overrides)
    return payload


def _request(cache_dir: Path | str = "/cache", **overrides: object) -> str:
    return encode_message("mining.reading.run", _payload(cache_dir, **overrides))


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
        return encode_message("job.registration.accepted", {"runId": request.payload["runId"]})

    def _terminal(self, channel: str, raw: str) -> None:
        assert self.registry is None or self.registry.active_run_id is None
        self.events.append(channel)
        self.terminals.append((channel, raw))

    def onComplete(self, raw: str) -> None:
        self._terminal("complete", raw)

    def onError(self, raw: str) -> None:
        self._terminal("error", raw)


@pytest.mark.parametrize(
    ("kind", "filename", "archive_name", "series_name"),
    [
        ("txt", "book.TXT", None, None),
        ("epub", "book.EPUB", None, None),
        ("subtitle", "dialogue.VTT", None, "Subtitles"),
        ("mokuro", "volume.MOKURO", "volume.CBZ", None),
    ],
)
def test_request_parses_every_reading_kind_with_exact_private_paths(
    tmp_path: Path,
    kind: str,
    filename: str,
    archive_name: str | None,
    series_name: str | None,
) -> None:
    job_dir = tmp_path / "reading-job-v1-a"
    source = job_dir / filename
    archive = job_dir / archive_name if archive_name else None

    parsed = reading_mining._parse_request(
        _request(
            tmp_path,
            sourceKind=kind,
            sourcePath=str(source),
            imageArchivePath=str(archive) if archive else None,
            seriesName=series_name,
        )
    )

    assert parsed.source_kind == kind
    assert parsed.source_path == source
    assert parsed.image_archive_path == archive
    assert parsed.series_name == series_name
    assert parsed.cache_dir == tmp_path


@pytest.mark.parametrize(
    "overrides",
    [
        {"sourceKind": "pdf"},
        {"sourcePath": "relative.txt"},
        {"sourcePath": "/outside/book.txt"},
        {"sourcePath": "/cache/book.epub"},
        {"sourcePath": "/cache/book\x00.txt"},
        {"cacheDir": "relative"},
        {"nativeLibraryDir": "relative"},
        {"seriesName": "Books"},
        {
            "sourceKind": "subtitle",
            "sourcePath": "/cache/dialogue.srt",
            "seriesName": None,
        },
        {
            "sourceKind": "subtitle",
            "sourcePath": "/cache/dialogue.srt",
            "seriesName": " Subtitles",
        },
        {
            "sourceKind": "subtitle",
            "sourcePath": "/cache/dialogue.srt",
            "seriesName": "bad\u0000label",
        },
        {
            "sourceKind": "subtitle",
            "sourcePath": "/cache/dialogue.srt",
            "seriesName": "\u1e0a\u0323",
        },
        {"imageArchivePath": "/cache/book.zip"},
        {
            "sourceKind": "mokuro",
            "sourcePath": "/cache/volume.mokuro",
            "imageArchivePath": "/outside/volume.cbz",
        },
        {
            "sourceKind": "mokuro",
            "sourcePath": "/cache/volume.mokuro",
            "imageArchivePath": "/cache/other.cbz",
        },
        {
            "sourceKind": "mokuro",
            "sourcePath": "/cache/a/volume.mokuro",
            "imageArchivePath": "/cache/b/volume.cbz",
        },
        {
            "sourceKind": "mokuro",
            "sourcePath": "/cache/volume.mokuro",
            "imageArchivePath": "/cache/volume.rar",
        },
    ],
)
def test_request_rejects_invalid_kind_paths_labels_and_pairing(
    overrides: dict[str, object],
) -> None:
    with pytest.raises(BridgeProtocolError) as error:
        reading_mining._parse_request(_request(**overrides))
    assert error.value.code == "invalid_reading_mining_request"


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
        reading_mining._parse_request(_request(configSnapshot=snapshot))
    assert error.value.code == "invalid_reading_mining_request"


def test_request_requires_exact_fields_and_enforces_utf8_bounds() -> None:
    missing = _payload()
    del missing["seriesName"]
    with pytest.raises(BridgeProtocolError):
        reading_mining._parse_request(encode_message("mining.reading.run", missing))

    with pytest.raises(BridgeProtocolError):
        reading_mining._parse_request(_request(unknown=True))

    oversized_path = "/cache/" + "猫" * 1_400 + ".txt"
    with pytest.raises(BridgeProtocolError) as path_error:
        reading_mining._parse_request(_request(sourcePath=oversized_path))
    assert path_error.value.code == "invalid_reading_mining_request"

    oversized_request = " " * (reading_mining._MAX_READING_REQUEST_UTF8_BYTES + 1)
    with pytest.raises(BridgeProtocolError) as request_error:
        reading_mining._parse_request(oversized_request)
    assert request_error.value.code == "invalid_reading_mining_request"


@pytest.mark.parametrize(
    ("kind", "suffix", "document_kind", "series_name"),
    [
        ("txt", ".txt", "book", None),
        ("epub", ".epub", "book", None),
        ("subtitle", ".srt", "subtitle", "My Subtitles"),
        ("mokuro", ".mokuro", "manga", None),
    ],
)
def test_load_document_calls_desktop_detect_then_load_for_every_kind(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    kind: str,
    suffix: str,
    document_kind: str,
    series_name: str | None,
) -> None:
    monkeypatch.setattr(reading_limits, "validate_source_before_load", lambda **_: None)
    monkeypatch.setattr(reading_limits, "validate_loaded_document", lambda *_, **__: None)
    job_dir = tmp_path / "reading-job-v1-nonce"
    job_dir.mkdir()
    source = job_dir / f"source{suffix}"
    source.write_bytes(b"source")
    archive = job_dir / "source.cbz" if kind == "mokuro" else None
    if archive is not None:
        archive.write_bytes(b"archive")

    ref = SimpleNamespace(kind=kind, image_root=archive)
    document = SimpleNamespace(kind=document_kind, series="reading-job-v1-nonce")
    calls: list[tuple[str, object]] = []

    def detect(path: Path) -> list[object]:
        calls.append(("detect", path))
        return [ref]

    def load(received: object, *, strip_subtitle_annotations: bool) -> object:
        calls.append(("load", received, strip_subtitle_annotations))
        return document

    detector = SimpleNamespace(detect=detect, load=load)
    monkeypatch.setattr(reading_mining, "_reading_detector", lambda: detector)
    request = reading_mining._parse_request(
        _request(
            tmp_path,
            sourceKind=kind,
            sourcePath=str(source),
            imageArchivePath=str(archive) if archive else None,
            seriesName=series_name,
        )
    )

    assert reading_mining._load_document(request) is document
    # The per-cue annotation strip is an engine kwarg that defaults to False;
    # the bridge must forward the product default (on) or the reading path
    # silently mines speaker tags and SFX captions.
    assert calls == [("detect", source), ("load", ref, True)]
    assert document.series == (series_name if kind == "subtitle" else "reading-job-v1-nonce")


def test_subtitle_series_never_leaks_random_staging_directory(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    job_dir = tmp_path / "reading-job-v1-deadbeef"
    job_dir.mkdir()
    source = job_dir / "episode.srt"
    source.write_text("1\n00:00:00,000 --> 00:00:01,000\n猫\n", encoding="utf-8")
    loaded = SimpleNamespace(kind="subtitle", series=job_dir.name, units=[])
    ref = SimpleNamespace(kind="subtitle", image_root=None)
    detector = SimpleNamespace(detect=lambda _: [ref], load=lambda _, **_kwargs: loaded)
    monkeypatch.setattr(reading_mining, "_reading_detector", lambda: detector)

    request = reading_mining._parse_request(
        _request(
            tmp_path,
            sourceKind="subtitle",
            sourcePath=str(source),
            seriesName="Subtitles",
        )
    )

    document = reading_mining._load_document(request)
    assert document.series == "Subtitles"
    assert job_dir.name not in document.series


def test_load_document_rejects_missing_or_undeclared_inputs(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    missing = reading_mining._parse_request(_request(tmp_path, sourcePath=str(tmp_path / "missing.txt")))
    with pytest.raises(BridgeProtocolError) as missing_error:
        reading_mining._load_document(missing)
    assert missing_error.value.code == "invalid_reading_mining_request"

    source = tmp_path / "volume.mokuro"
    source.write_text("{}", encoding="utf-8")
    undeclared = tmp_path / "volume.cbz"
    undeclared.write_bytes(b"archive")
    monkeypatch.setattr(reading_limits, "validate_source_before_load", lambda **_: None)
    detector = SimpleNamespace(
        detect=lambda _: [SimpleNamespace(kind="mokuro", image_root=undeclared)],
        load=lambda _: pytest.fail("mismatched companion must fail before load"),
    )
    monkeypatch.setattr(reading_mining, "_reading_detector", lambda: detector)
    request = reading_mining._parse_request(
        _request(
            tmp_path,
            sourceKind="mokuro",
            sourcePath=str(source),
        )
    )
    with pytest.raises(BridgeProtocolError) as companion_error:
        reading_mining._load_document(request)
    assert companion_error.value.code == "invalid_reading_mining_request"


def test_process_reading_receives_exact_desktop_contract_and_cleans_lifo(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    cancel_event = threading.Event()
    config = object()
    document = object()
    result = object()
    progress = object()
    anki_callbacks = object()

    def curate(candidates: list[object]) -> list[object] | None:
        return candidates

    adapters = SimpleNamespace(
        anki=anki_callbacks,
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
        ) -> None:
            assert received_config is config
            assert received_callbacks is anki_callbacks
            assert callable(cancellation_check)
            events.append("adapter-init")

        def __enter__(self) -> FakeAdapter:
            events.append("adapter-enter")
            return self

        def __exit__(self, *_: object) -> None:
            events.append("adapter-exit")

    class FakeProcessor:
        def process_reading(self, *args: object, **kwargs: object) -> object:
            events.append("process")
            assert args == (document,)
            assert kwargs == {
                "progress_callback": progress,
                "curation_callback": curate,
                "cancel_event": cancel_event,
            }
            return result

        def close(self) -> None:
            events.append("processor-close")

    def build(
        received_config: object,
        received_adapters: object,
        adapter: object,
        *,
        sentence_audio_fetcher: object | None,
    ) -> object:
        assert received_config is config
        assert received_adapters is adapters
        assert isinstance(adapter, FakeAdapter)
        assert sentence_audio_fetcher is None
        events.append("build")
        return FakeProcessor()

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(reading_mining, "_build_processor", build)

    assert reading_mining._process_reading(document, config, adapters) is result
    assert events == [
        "adapter-init",
        "adapter-enter",
        "build",
        "process",
        "processor-close",
        "adapter-exit",
    ]


def test_process_reading_injects_only_android_sentence_audio_when_enabled(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    from android_bridge.sentence_audio import AndroidSentenceAudioFetcher

    cancel_event = threading.Event()
    callbacks = object()
    config = SimpleNamespace(
        reading_tts_enabled=True,
        media_temp_folder=tmp_path / "anki_miner_temp",
    )
    adapters = SimpleNamespace(
        anki=object(),
        callbacks=callbacks,
        run_id="run_00000000000000000000000000000000",
        cancel_event=cancel_event,
        progress=object(),
        curate=lambda candidates: candidates,
        presenter=SimpleNamespace(show_warning=lambda message: None),
    )
    captured: dict[str, object] = {}

    class FakeAdapter:
        def __init__(self, *_: object, **__: object) -> None:
            pass

        def __enter__(self) -> FakeAdapter:
            return self

        def __exit__(self, *_: object) -> None:
            pass

    class FakeProcessor:
        def process_reading(self, *_: object, **__: object) -> object:
            return "result"

        def close(self) -> None:
            pass

    def build(*_: object, **kwargs: object) -> FakeProcessor:
        captured.update(kwargs)
        return FakeProcessor()

    monkeypatch.setattr(anki_adapter_module, "AndroidAnkiAdapter", FakeAdapter)
    monkeypatch.setattr(reading_mining, "_build_processor", build)

    assert reading_mining._process_reading(object(), config, adapters) == "result"
    fetcher = captured["sentence_audio_fetcher"]
    assert isinstance(fetcher, AndroidSentenceAudioFetcher)
    assert fetcher._callbacks is callbacks
    assert callable(fetcher._warning_callback)


def test_process_reading_cancelled_at_entry_opens_no_resources(
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
        reading_mining._process_reading(object(), object(), adapters)
    assert cancelled.value.operation == "runReading"


def _stub_run(
    monkeypatch: pytest.MonkeyPatch,
    result: object,
    *,
    events: list[str] | None = None,
) -> tuple[object, object]:
    # The run forwards config.strip_subtitle_annotations to the loader.
    config = SimpleNamespace(strip_subtitle_annotations=True)
    document = object()
    monkeypatch.setattr(reading_mining, "_ensure_runtime_ready", lambda: Path("/files"))

    def map_config(request: object, files_dir: Path) -> object:
        assert files_dir == Path("/files")
        if events is not None:
            events.append("map")
        return config

    def load_document(
        request: object,
        cancellation_check: object,
        *,
        strip_subtitle_annotations: bool = True,
    ) -> object:
        assert callable(cancellation_check)
        if events is not None:
            events.append("load")
        return document

    def process(received: object, mapped: object, adapters: object) -> object:
        assert received is document
        assert mapped is config
        if events is not None:
            events.append("process")
        return result

    monkeypatch.setattr(reading_mining, "_map_config", map_config)
    monkeypatch.setattr(reading_mining, "_load_document", load_document)
    monkeypatch.setattr(reading_mining, "_process_reading", process)
    return config, document


def test_run_reading_admits_loads_and_returns_same_terminal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    registry = JobRegistry()
    callbacks = RecordingCallbacks(events, registry)
    _stub_run(monkeypatch, FakeResult(), events=events)

    returned = reading_mining.run_reading(_request(), callbacks, job_registry=registry)

    assert events == ["register", "map", "load", "process", "complete"]
    assert callbacks.terminals == [("complete", returned)]
    terminal = decode_envelope(returned, expected_type="mining.terminal")
    registration = decode_envelope(callbacks.register_requests[0])
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
            "videoFile": "",
            "subtitleFile": "",
            "minedForms": ["猫", "食べる"],
        },
        "error": None,
    }
    assert registry.active_run_id is None


def test_cancellation_after_document_load_stops_before_processor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registry = JobRegistry()
    callbacks = RecordingCallbacks(registry=registry)
    monkeypatch.setattr(reading_mining, "_ensure_runtime_ready", lambda: Path("/files"))
    monkeypatch.setattr(
        reading_mining,
        "_map_config",
        # The run reads strip_subtitle_annotations off the mapped config to
        # forward it to the loader, so the double must carry it.
        lambda *_: SimpleNamespace(strip_subtitle_annotations=True),
    )

    def load_then_cancel(_: object, __: object, **_kwargs: object) -> object:
        run_id = registry.active_run_id
        assert run_id is not None
        assert registry.cancel(run_id)
        return object()

    monkeypatch.setattr(reading_mining, "_load_document", load_then_cancel)
    monkeypatch.setattr(
        reading_mining,
        "_process_reading",
        lambda *_: pytest.fail("cancelled run must not compose a processor"),
    )

    returned = reading_mining.run_reading(_request(), callbacks, job_registry=registry)
    terminal = decode_envelope(returned, expected_type="mining.terminal")
    assert terminal.payload["outcome"] == "cancelled"
    assert terminal.payload["result"] is None
    assert terminal.payload["error"] == {
        "code": "cancelled",
        "message": "Mining was cancelled",
    }
    assert registry.active_run_id is None


@pytest.mark.parametrize("resolution", ["empty", "null", "cancel"])
def test_reading_curation_parks_and_preserves_none_vs_empty_semantics(
    monkeypatch: pytest.MonkeyPatch,
    resolution: str,
) -> None:
    registry = JobRegistry()
    monkeypatch.setattr(jobs_module, "_REGISTRY", registry)
    monkeypatch.setattr(reading_mining, "_ensure_runtime_ready", lambda: Path("/files"))
    monkeypatch.setattr(
        reading_mining,
        "_map_config",
        # The run reads strip_subtitle_annotations off the mapped config to
        # forward it to the loader, so the double must carry it.
        lambda *_: SimpleNamespace(strip_subtitle_annotations=True),
    )
    monkeypatch.setattr(reading_mining, "_load_document", lambda *_, **_kwargs: object())
    emitted = threading.Event()
    returned_from_curation = threading.Event()
    curation_requests: list[str] = []
    selections: list[list[object] | None] = []
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
            curation_requests.append(raw)
            emitted.set()

    callbacks = CurationCallbacks(registry=registry)

    def process(_: object, __: object, adapters: object) -> FakeResult:
        selection = adapters.curate([candidate])
        selections.append(selection)
        returned_from_curation.set()
        if selection is None:
            return FakeResult(
                cards_created=0,
                errors=["Processing cancelled by user"],
                card_ids=[],
                mined_forms=[],
            )
        assert selection == []
        return FakeResult(cards_created=0, card_ids=[], mined_forms=[])

    monkeypatch.setattr(reading_mining, "_process_reading", process)
    terminal_results: list[str] = []
    raised: list[BaseException] = []

    def worker() -> None:
        try:
            terminal_results.append(reading_mining.run_reading(_request(), callbacks))
        except BaseException as error:
            raised.append(error)

    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    try:
        assert emitted.wait(5), "curation request was not emitted"
        assert not returned_from_curation.wait(0.05), "engine thread did not park"
        request = decode_envelope(curation_requests[0], expected_type="curation.request")
        if resolution == "cancel":
            jobs_module.cancel_job(encode_message("job.cancel", {"runId": request.payload["runId"]}))
        else:
            jobs_module.submit_curation(
                encode_message(
                    "curation.response",
                    {
                        "runId": request.payload["runId"],
                        "requestId": request.payload["requestId"],
                        "selection": [] if resolution == "empty" else None,
                    },
                )
            )
        assert returned_from_curation.wait(5)
        thread.join(5)
        assert not thread.is_alive()
    finally:
        if thread.is_alive() and registry.active_run_id is not None:
            registry.cancel(registry.active_run_id)
        thread.join(5)

    assert raised == []
    assert selections == ([[]] if resolution == "empty" else [None])
    terminal = decode_envelope(terminal_results[0], expected_type="mining.terminal")
    assert terminal.payload["outcome"] == ("success" if resolution == "empty" else "cancelled")


def test_boundary_routes_reading_without_changing_video_dispatch(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    callback = object()
    reading_request = encode_message("mining.reading.run", {})
    video_request = encode_message("mining.video.run", {})
    received: list[tuple[str, str, object]] = []

    def run_reading(raw: str, callbacks: object) -> str:
        received.append(("reading", raw, callbacks))
        return encode_message("mining.terminal", {"reading": True})

    def run_video(raw: str, callbacks: object) -> str:
        received.append(("video", raw, callbacks))
        return encode_message("mining.terminal", {"video": True})

    monkeypatch.setattr(reading_mining, "run_reading", run_reading)
    monkeypatch.setattr(video_mining, "run_video", run_video)

    missing = decode_envelope(boundary.dispatch(reading_request), expected_type="bridge.error")
    reading_result = boundary.dispatch(reading_request, callback)
    video_result = boundary.dispatch(video_request, callback)

    assert missing.payload["code"] == "missing_callbacks"
    assert decode_envelope(reading_result).payload == {"reading": True}
    assert decode_envelope(video_result).payload == {"video": True}
    assert received == [
        ("reading", reading_request, callback),
        ("video", video_request, callback),
    ]


def test_reading_module_has_no_top_level_engine_imports() -> None:
    path = PROJECT_ROOT / "app" / "src" / "main" / "python" / "android_bridge" / "reading_mining.py"
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    top_level_engine_imports = [
        node
        for node in tree.body
        if (isinstance(node, ast.Import) and any(alias.name.startswith("anki_miner") for alias in node.names))
        or (isinstance(node, ast.ImportFrom) and (node.module or "").startswith("anki_miner"))
    ]
    assert top_level_engine_imports == []
