from __future__ import annotations

import contextlib
import importlib.util
import logging
import subprocess
import sys
import threading
import time
import types
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[3]


@contextlib.contextmanager
def _inherited(command: list[str]):
    yield command, ()


def _module(name: str, **values: object) -> types.ModuleType:
    result = types.ModuleType(name)
    for key, value in values.items():
        setattr(result, key, value)
    return result


def _load(path: Path, name: str, stubs: dict[str, types.ModuleType]):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    with mock.patch.dict(sys.modules, {**stubs, name: module}):
        spec.loader.exec_module(module)
    return module


def _common_stubs() -> dict[str, types.ModuleType]:
    return {
        "anki_miner": _module("anki_miner"),
        "anki_miner.utils.android_fd": _module(
            "anki_miner.utils.android_fd", inherited_fd_command=_inherited
        ),
        "anki_miner.utils.subprocess_utils": _module(
            "anki_miner.utils.subprocess_utils", no_window_kwargs=lambda: {}
        ),
    }


class _BlockingProcess:
    def __init__(self, *, timeout_once: bool = False) -> None:
        self.returncode: int | None = None
        self.killed = threading.Event()
        self.entered_communicate = threading.Event()
        self.timeout_once = timeout_once
        self.communicate_calls = 0

    def __enter__(self):
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def kill(self) -> None:
        self.returncode = -9
        self.killed.set()

    def communicate(self, timeout: float | None = None) -> tuple[str, str]:
        self.communicate_calls += 1
        self.entered_communicate.set()
        if self.timeout_once and self.communicate_calls == 1:
            raise subprocess.TimeoutExpired("probe", timeout)
        if timeout is not None:
            self.killed.wait(2)
        return "", ""


class _FinishedProcess:
    def __init__(
        self,
        *,
        returncode: int = 1,
        stdout: str = "",
        stderr: str = "",
        communicate_error: Exception | None = None,
    ) -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        self.communicate_error = communicate_error
        self.killed = False

    def __enter__(self):
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def kill(self) -> None:
        self.killed = True

    def communicate(self, timeout: float | None = None) -> tuple[str, str]:
        del timeout
        if self.communicate_error is not None:
            raise self.communicate_error
        return self.stdout, self.stderr


class _Registry:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.processes: set[_BlockingProcess] = set()
        self._cancelled = False
        self.registered = threading.Event()

    @property
    def cancelled(self) -> bool:
        with self.lock:
            return self._cancelled

    def register(self, proc: _BlockingProcess) -> bool:
        with self.lock:
            if self._cancelled:
                return False
            self.processes.add(proc)
            self.registered.set()
            return True

    def unregister(self, proc: _BlockingProcess) -> None:
        with self.lock:
            self.processes.discard(proc)

    def kill_all(self) -> None:
        with self.lock:
            self._cancelled = True
            processes = list(self.processes)
        for process in processes:
            process.kill()


def _load_media_extractor():
    stubs = _common_stubs()
    qt_core = _module(
        "PyQt6.QtCore",
        QCoreApplication=SimpleNamespace(translate=lambda _ctx, value, *_args: value),
    )
    stubs.update(
        {
            "PyQt6": _module("PyQt6", QtCore=qt_core),
            "PyQt6.QtCore": qt_core,
            "anki_miner.config": _module("anki_miner.config", AnkiMinerConfig=object),
            "anki_miner.interfaces": _module("anki_miner.interfaces", ProgressCallback=object),
            "anki_miner.models": _module(
                "anki_miner.models", MediaData=object, TokenizedWord=object
            ),
            "anki_miner.utils": _module(
                "anki_miner.utils",
                AudioStream=object,
                ensure_directory=lambda _path: None,
                find_japanese_audio_stream=lambda *_args, **_kwargs: None,
                list_audio_streams=lambda *_args, **_kwargs: [],
                safe_filename=lambda value: value,
            ),
            "anki_miner.utils.audio_track_detector": _module(
                "anki_miner.utils.audio_track_detector",
                JAPANESE_LANGUAGE_CODES=frozenset({"jpn"}),
            ),
            "anki_miner.utils.ffmpeg_resolver": _module(
                "anki_miner.utils.ffmpeg_resolver",
                resolve_ffmpeg=lambda _config: "ffmpeg",
                resolve_ffprobe=lambda _config: "ffprobe",
            ),
            "anki_miner.utils.i18n": _module(
                "anki_miner.utils.i18n", tr_format=lambda value, *_args: value
            ),
            "anki_miner.utils.logging_ext": _module(
                "anki_miner.utils.logging_ext",
                log_summary=lambda *_args, **_kwargs: None,
            ),
        }
    )
    return _load(
        PROJECT_ROOT / "tools/engine-sync/overrides/anki_miner/services/media_extractor.py",
        "media_extractor_cancellation_test",
        stubs,
    )


class MediaProcessCancellationTests(unittest.TestCase):
    def _audio_detector(self):
        return _load(
            PROJECT_ROOT
            / "tools/engine-sync/overrides/anki_miner/utils/audio_track_detector.py",
            "audio_track_detector_cancellation_test",
            _common_stubs(),
        )

    def _media_extractor(self):
        return _load_media_extractor()

    def test_ffprobe_cancel_kills_reaps_and_unregisters(self) -> None:
        detector = self._audio_detector()
        process = _BlockingProcess()
        registry = _Registry()
        result: list[object] = []
        with mock.patch.object(detector.subprocess, "Popen", return_value=process):
            worker = threading.Thread(
                target=lambda: result.append(
                    detector._run_ffprobe_json(
                        Path("video.mkv"), "a", "ffprobe", registry
                    )
                )
            )
            worker.start()
            self.assertTrue(registry.registered.wait(1))
            registry.kill_all()
            worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual([None], result)
        self.assertTrue(process.killed.is_set())
        self.assertGreaterEqual(process.communicate_calls, 1)
        self.assertEqual(set(), registry.processes)

    def test_ffprobe_timeout_kills_and_reaps(self) -> None:
        detector = self._audio_detector()
        process = _BlockingProcess(timeout_once=True)
        with mock.patch.object(detector.subprocess, "Popen", return_value=process):
            self.assertIsNone(
                detector._run_ffprobe_json(Path("video.mkv"), "a", "ffprobe")
            )
        self.assertTrue(process.killed.is_set())
        self.assertEqual(2, process.communicate_calls)

    def test_encoder_probe_cancel_does_not_poison_cache(self) -> None:
        media = self._media_extractor()
        service = object.__new__(media.MediaExtractorService)
        service.config = object()
        service._encoder_probe_lock = threading.Lock()
        service._animated_encoder_ok = {}
        process = _BlockingProcess()
        registry = media._FfmpegProcRegistry()
        result: list[bool] = []
        with mock.patch.object(media.subprocess, "Popen", return_value=process):
            worker = threading.Thread(
                target=lambda: result.append(
                    service._check_encoder_available("libmp3lame", registry)
                )
            )
            worker.start()
            self.assertTrue(process.entered_communicate.wait(1))
            registry.kill_all()
            worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual([False], result)
        self.assertTrue(process.killed.is_set())
        self.assertEqual({}, service._animated_encoder_ok)

    def test_audio_stream_cache_is_single_flight_and_threads_registry(self) -> None:
        media = self._media_extractor()
        service = object.__new__(media.MediaExtractorService)
        service.config = object()
        service._cache_lock = threading.Lock()
        service._audio_stream_cache = {}
        service._audio_stream_list_cache = {}
        registry = media._FfmpegProcRegistry()
        calls: list[object] = []
        release = threading.Event()
        probe_started = threading.Event()

        def find(*_args: object, **kwargs: object):
            calls.append(kwargs.get("proc_registry"))
            probe_started.set()
            release.wait(1)
            return SimpleNamespace(global_index=7)

        results: list[int | None] = []
        errors: list[BaseException] = []

        def resolve() -> None:
            try:
                results.append(
                    service._get_japanese_audio_stream(Path("video.mkv"), registry)
                )
            except BaseException as error:
                errors.append(error)

        with mock.patch.object(media, "find_japanese_audio_stream", side_effect=find):
            workers = [
                threading.Thread(
                    target=resolve,
                    name=f"audio-stream-worker-{index}",
                    daemon=True,
                )
                for index in range(2)
            ]
            for worker in workers:
                worker.start()
            try:
                reached_probe = probe_started.wait(1)
            finally:
                release.set()
            deadline = time.monotonic() + 2
            for worker in workers:
                worker.join(max(0.0, deadline - time.monotonic()))
        alive = [worker.name for worker in workers if worker.is_alive()]
        self.assertEqual([], alive, f"audio stream workers did not stop; errors={errors!r}")
        self.assertTrue(
            reached_probe,
            f"audio stream probe was not reached; errors={errors!r}",
        )
        self.assertEqual([], errors)
        self.assertEqual([registry], calls)
        self.assertEqual([7, 7], sorted(results))

    def test_ffmpeg_exception_warnings_keep_mined_context_debug_only(self) -> None:
        media = self._media_extractor()
        service = object.__new__(media.MediaExtractorService)
        context = "殺す_100_1.jpg"
        cases = (
            (OSError("spawn failed"), None),
            (None, _BlockingProcess(timeout_once=True)),
            (None, _FinishedProcess(communicate_error=ValueError("decode failed"))),
        )

        for spawn_error, process in cases:
            with self.subTest(spawn_error=spawn_error, process=type(process).__name__):
                popen = (
                    mock.Mock(side_effect=spawn_error)
                    if spawn_error is not None
                    else mock.Mock(return_value=process)
                )
                with (
                    mock.patch.object(media.subprocess, "Popen", popen),
                    self.assertLogs(media.logger, level=logging.DEBUG) as captured,
                ):
                    self.assertFalse(
                        service._run_ffmpeg(
                            ["ffmpeg", "-i", "video.mkv", context],
                            "clip extraction",
                            timeout=1,
                            context=context,
                        )
                    )

                debug_messages = [
                    record.getMessage()
                    for record in captured.records
                    if record.levelno == logging.DEBUG
                ]
                warnings = [
                    record
                    for record in captured.records
                    if record.levelno == logging.WARNING
                ]
                self.assertTrue(any(context in message for message in debug_messages))
                self.assertEqual(1, len(warnings))
                self.assertNotIn(context, warnings[0].getMessage())
                self.assertNotIn("殺す", warnings[0].getMessage())
                self.assertIsNotNone(warnings[0].exc_info)

    def test_ffmpeg_nonzero_warning_bounds_redacted_stderr_and_has_throwable(self) -> None:
        media = self._media_extractor()
        service = object.__new__(media.MediaExtractorService)
        context = "殺す_100_1.jpg"
        process = _FinishedProcess(
            returncode=17,
            stderr=f"codec failure writing {context}: " + ("x" * 4096),
        )

        with (
            mock.patch.object(media.subprocess, "Popen", return_value=process),
            self.assertLogs(media.logger, level=logging.DEBUG) as captured,
        ):
            self.assertFalse(
                service._run_ffmpeg(
                    ["ffmpeg", "-i", "video.mkv", context],
                    "clip extraction",
                    timeout=1,
                    context=context,
                )
            )

        debug_message = next(
            record.getMessage()
            for record in captured.records
            if record.levelno == logging.DEBUG
        )
        warning = next(
            record
            for record in captured.records
            if record.levelno == logging.WARNING
        )
        bounded_stderr = warning.getMessage().partition("stderr=")[2]
        self.assertIn(context, debug_message)
        self.assertIn("codec failure", bounded_stderr)
        self.assertLessEqual(len(bounded_stderr), 2048)
        self.assertNotIn(context, warning.getMessage())
        self.assertNotIn("殺す", warning.getMessage())
        self.assertIsNotNone(warning.exc_info)

    def test_cancelled_batch_separates_cancelled_items_from_failures(self) -> None:
        media = self._media_extractor()
        service = object.__new__(media.MediaExtractorService)
        service.config = SimpleNamespace(
            max_parallel_workers=1,
            screenshot_animated=False,
        )
        service._CANCEL_POLL_INTERVAL = 0.01
        cancel = threading.Event()
        call_lock = threading.Lock()
        calls = 0

        def extract(*_args: object, **kwargs: object):
            nonlocal calls
            with call_lock:
                calls += 1
                call_number = calls
            if call_number > 1:
                registry = kwargs["proc_registry"]
                while not registry.cancelled:
                    threading.Event().wait(0.001)
            return SimpleNamespace(
                has_audio=True,
                has_screenshot=True,
                screenshot_path=None,
                screenshot_filename=None,
            )

        class Progress:
            def on_start(self, *_args: object) -> None:
                pass

            def on_progress(self, *_args: object) -> None:
                cancel.set()

            def on_error(self, *_args: object) -> None:
                raise AssertionError("successful extraction must not report an error")

            def on_complete(self) -> None:
                raise AssertionError("cancelled extraction must not report completion")

        words = [SimpleNamespace(lemma=value) for value in ("one", "two", "three")]
        service.extract_media = extract

        with self.assertLogs(media.logger, level=logging.INFO) as captured:
            result = service.extract_media_batch(
                Path("video.mkv"),
                words,
                progress_callback=Progress(),
                cancelled_check=cancel.is_set,
                animated_format=None,
            )

        self.assertEqual(1, len(result))
        summary = captured.records[-1].getMessage()
        self.assertIn("attempted=3", summary)
        self.assertIn("ok=1", summary)
        self.assertIn("failed=0", summary)
        self.assertIn("cancelled=2", summary)
        self.assertIn("outcome=skip", summary)
    def test_media_batch_never_queues_more_than_the_worker_bound(self) -> None:
        media = self._media_extractor()
        service = object.__new__(media.MediaExtractorService)
        service.config = SimpleNamespace(
            max_parallel_workers=2,
            media_temp_folder=Path("."),
            screenshot_animated=False,
        )
        release = threading.Event()
        two_started = threading.Event()
        started = 0
        started_lock = threading.Lock()

        def extract_media(*_args: object, **_kwargs: object) -> object:
            nonlocal started
            with started_lock:
                started += 1
                if started == 2:
                    two_started.set()
            release.wait(2)
            return SimpleNamespace(has_screenshot=True, has_audio=False)

        service.extract_media = extract_media

        class TrackingExecutor(ThreadPoolExecutor):
            latest: TrackingExecutor | None = None

            def __init__(self, *args: object, **kwargs: object) -> None:
                super().__init__(*args, **kwargs)
                self._tracking_lock = threading.Lock()
                self.outstanding = 0
                self.max_outstanding = 0
                TrackingExecutor.latest = self

            def submit(self, *args: object, **kwargs: object):  # type: ignore[no-untyped-def]
                with self._tracking_lock:
                    self.outstanding += 1
                    self.max_outstanding = max(self.max_outstanding, self.outstanding)
                try:
                    future = super().submit(*args, **kwargs)
                except BaseException:
                    with self._tracking_lock:
                        self.outstanding -= 1
                    raise

                def complete(_future: object) -> None:
                    with self._tracking_lock:
                        self.outstanding -= 1

                future.add_done_callback(complete)
                return future

        words = [SimpleNamespace(lemma=f"word-{index}") for index in range(6)]
        results: list[object] = []
        with mock.patch.object(media, "ThreadPoolExecutor", TrackingExecutor):
            worker = threading.Thread(
                target=lambda: results.append(
                    service.extract_media_batch(
                        Path("video.mkv"),
                        words,
                        animated_format=None,
                        include_audio=False,
                    )
                )
            )
            worker.start()
            self.assertTrue(two_started.wait(1))
            executor = TrackingExecutor.latest
            self.assertIsNotNone(executor)
            assert executor is not None
            self.assertLessEqual(executor.max_outstanding, 2)
            release.set()
            worker.join(2)

        self.assertFalse(worker.is_alive())
        self.assertEqual(6, len(results[0]))


class AnimatedEncoderOverlayTests(unittest.TestCase):
    """The Android overlay encodes AVIF with libaom, not the desktop libsvtav1."""

    def _animated_command(self, fmt: str) -> list[str]:
        media = _load_media_extractor()
        service = object.__new__(media.MediaExtractorService)
        service.config = SimpleNamespace(
            screenshot_animated=True,
            screenshot_animated_format=fmt,
            screenshot_animated_match_audio=False,
            screenshot_animated_clip_duration=2.0,
            screenshot_animated_fps=20,
            screenshot_animated_height=720,
            screenshot_animated_quality=30,
            audio_padding=0.5,
        )
        captured: list[list[str]] = []

        def run(cmd: list[str], *_args: object, **_kwargs: object) -> bool:
            captured.append(cmd)
            return False  # stops before the output-file existence check

        service._run_ffmpeg = run
        service._check_encoder_available = lambda *_a, **_k: True

        self.assertFalse(
            service._extract_animated_screenshot(
                Path("video.mkv"), 1.0, 2.0, Path(f"out.{fmt}")
            )
        )
        self.assertEqual(1, len(captured))
        return captured[0]

    def test_encoder_names_are_the_ones_this_build_carries(self) -> None:
        media = _load_media_extractor()
        self.assertEqual("libaom-av1", media.MediaExtractorService._encoder_for_format("avif"))
        self.assertEqual("libwebp_anim", media.MediaExtractorService._encoder_for_format("webp"))

    def test_animated_command_carries_the_resolved_encoder(self) -> None:
        # Regression guard: the AVIF branch used to hardcode "libsvtav1"
        # independently of _encoder_for_format, so _check_encoder_available
        # could pass while ffmpeg was handed an encoder this build lacks.
        avif = self._animated_command("avif")
        self.assertNotIn("libsvtav1", avif)
        self.assertEqual("libaom-av1", avif[avif.index("-c:v") + 1])

        webp = self._animated_command("webp")
        self.assertEqual("libwebp_anim", webp[webp.index("-c:v") + 1])

    def test_avif_command_carries_speed_flags_for_the_60s_timeout(self) -> None:
        avif = self._animated_command("avif")
        self.assertEqual("8", avif[avif.index("-cpu-used") + 1])
        self.assertEqual("1", avif[avif.index("-row-mt") + 1])

    def test_both_formats_abort_on_empty_output(self) -> None:
        # A clip window past EOF encodes nothing.  libwebp_anim exits non-zero on
        # its own, but libaom exits 0 and writes a frame-less 285-byte AVIF that
        # the caller's exists() check accepts, putting a broken image on a card.
        for fmt in ("avif", "webp"):
            cmd = self._animated_command(fmt)
            self.assertEqual("empty_output", cmd[cmd.index("-abort_on") + 1], fmt)

    def test_avif_realtime_usage_with_crf_offset(self) -> None:
        # Phone-CPU headroom: libaom's realtime usage encodes ~4x faster at
        # equal SSIM, which is what keeps a heavy clip inside the 60s timeout.
        # Realtime rate control overshoots the good-mode size at equal CRF, so
        # the mapped CRF is offset (+12, clamped to 63) to restore size parity.
        avif = self._animated_command("avif")
        self.assertEqual("realtime", avif[avif.index("-usage") + 1])
        # quality=30 maps to CRF 44 in good mode; realtime carries the offset.
        self.assertEqual("56", avif[avif.index("-crf") + 1])

        webp = self._animated_command("webp")
        self.assertNotIn("-usage", webp)


class AnimatedEncodeGateTests(unittest.TestCase):
    """Concurrent animated encodes are bounded so six workers cannot starve
    each other into the 60s timeout (the per-word "media extraction failed"
    drops reported from user diagnostics bundles)."""

    def _service(self, media):
        service = object.__new__(media.MediaExtractorService)
        service.config = SimpleNamespace(
            screenshot_animated=True,
            screenshot_animated_format="avif",
            screenshot_animated_match_audio=False,
            screenshot_animated_clip_duration=2.0,
            screenshot_animated_fps=20,
            screenshot_animated_height=720,
            screenshot_animated_quality=30,
            audio_padding=0.5,
        )
        service._check_encoder_available = lambda *_a, **_k: True
        return service

    def test_concurrent_animated_encodes_never_exceed_the_gate(self) -> None:
        media = _load_media_extractor()
        service = self._service(media)
        lock = threading.Lock()
        active = 0
        peak = 0
        release = threading.Event()

        def run(_cmd: list[str], *_args: object, **_kwargs: object) -> bool:
            nonlocal active, peak
            with lock:
                active += 1
                peak = max(peak, active)
            release.wait(5)
            with lock:
                active -= 1
            return False

        service._run_ffmpeg = run
        threads = [
            threading.Thread(
                target=service._extract_animated_screenshot,
                args=(Path("video.mkv"), float(i), 2.0, Path(f"out{i}.avif")),
            )
            for i in range(6)
        ]
        for thread in threads:
            thread.start()
        # Two encodes enter and hold; the other four must be parked on the gate.
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            with lock:
                if active == 2:
                    break
            time.sleep(0.02)
        time.sleep(0.3)  # give a third encode every chance to slip through
        with lock:
            self.assertEqual(2, active)
        release.set()
        for thread in threads:
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())
        self.assertEqual(2, peak)

    def test_gate_wait_aborts_promptly_on_cancel(self) -> None:
        media = _load_media_extractor()
        service = self._service(media)
        spawned = threading.Event()
        service._run_ffmpeg = lambda *_a, **_k: spawned.set() or True
        registry = _Registry()
        registry.kill_all()  # cancelled before the encode ever queues

        gate = media._ANIMATED_ENCODE_GATE
        holders = [gate.acquire(), gate.acquire()]  # both slots taken elsewhere
        try:
            result: list[bool] = []
            worker = threading.Thread(
                target=lambda: result.append(
                    service._extract_animated_screenshot(
                        Path("video.mkv"), 1.0, 2.0, Path("out.avif"), registry
                    )
                )
            )
            worker.start()
            worker.join(timeout=5)
            self.assertFalse(worker.is_alive(), "gate wait must not hang a cancelled run")
            self.assertEqual([False], result)
            self.assertFalse(spawned.is_set())
        finally:
            del holders
            gate.release()
            gate.release()


if __name__ == "__main__":
    unittest.main()
