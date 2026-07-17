from __future__ import annotations

import contextlib
import importlib.util
import subprocess
import sys
import threading
import types
import unittest
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


class MediaProcessCancellationTests(unittest.TestCase):
    def _audio_detector(self):
        return _load(
            PROJECT_ROOT
            / "tools/engine-sync/overrides/anki_miner/utils/audio_track_detector.py",
            "audio_track_detector_cancellation_test",
            _common_stubs(),
        )

    def _media_extractor(self):
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
            }
        )
        return _load(
            PROJECT_ROOT
            / "tools/engine-sync/overrides/anki_miner/services/media_extractor.py",
            "media_extractor_cancellation_test",
            stubs,
        )

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

        def find(*_args: object, **kwargs: object):
            calls.append(kwargs.get("proc_registry"))
            release.wait(1)
            return SimpleNamespace(global_index=7)

        results: list[int | None] = []
        with mock.patch.object(media, "find_japanese_audio_stream", side_effect=find):
            workers = [
                threading.Thread(
                    target=lambda: results.append(
                        service._get_japanese_audio_stream(Path("video.mkv"), registry)
                    )
                )
                for _ in range(2)
            ]
            for worker in workers:
                worker.start()
            while not calls:
                threading.Event().wait(0.01)
            release.set()
            for worker in workers:
                worker.join(2)
        self.assertEqual([registry], calls)
        self.assertEqual([7, 7], sorted(results))


if __name__ == "__main__":
    unittest.main()
