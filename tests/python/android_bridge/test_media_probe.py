from __future__ import annotations

import json
from pathlib import Path

import pytest
from android_bridge import boundary
from android_bridge.protocol import encode_message


def _payload(video_path: str = "/videos/ep1.mkv", native: str = "/native") -> dict[str, object]:
    return {"videoPath": video_path, "nativeLibraryDir": native}


def dispatch_json(payload: dict[str, object]) -> dict[str, object]:
    return json.loads(boundary.dispatch(encode_message("media.audiotracks", payload)))


def _audio_track_detector():
    import anki_miner.utils.audio_track_detector as audio_track_detector

    return audio_track_detector


def test_returns_tracks_and_auto_index(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    streams = [
        audio_track_detector.AudioStream(
            global_index=1,
            audio_index=0,
            language_tag="eng",
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=True,
        ),
        audio_track_detector.AudioStream(
            global_index=2,
            audio_index=1,
            language_tag="jpn",
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=False,
        ),
    ]
    monkeypatch.setattr(audio_track_detector, "list_audio_streams", lambda *a, **k: streams)

    result = dispatch_json(_payload())

    assert result["type"] == "media.audiotracks.result"
    assert result["payload"]["videoPath"] == "/videos/ep1.mkv"
    assert result["payload"]["tracks"][1]["languageTag"] == "jpn"
    assert result["payload"]["autoAudioIndex"] == 1


def test_auto_index_prefers_default_disposition(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    streams = [
        audio_track_detector.AudioStream(
            global_index=1,
            audio_index=0,
            language_tag="jpn",
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=False,
        ),
        audio_track_detector.AudioStream(
            global_index=2,
            audio_index=1,
            language_tag="jpn",
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=True,
        ),
    ]
    monkeypatch.setattr(audio_track_detector, "list_audio_streams", lambda *a, **k: streams)

    result = dispatch_json(_payload())

    assert result["payload"]["autoAudioIndex"] == 1


def test_auto_index_accepts_bcp47_prefix(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    streams = [
        audio_track_detector.AudioStream(
            global_index=1,
            audio_index=0,
            language_tag="ja-jp",
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=False,
        ),
    ]
    monkeypatch.setattr(audio_track_detector, "list_audio_streams", lambda *a, **k: streams)

    result = dispatch_json(_payload())

    assert result["payload"]["autoAudioIndex"] == 0


def test_auto_index_null_without_japanese(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    streams = [
        audio_track_detector.AudioStream(
            global_index=1,
            audio_index=0,
            language_tag="eng",
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=True,
        ),
    ]
    monkeypatch.setattr(audio_track_detector, "list_audio_streams", lambda *a, **k: streams)

    result = dispatch_json(_payload())

    assert result["payload"]["autoAudioIndex"] is None


def test_probe_failure_is_an_error(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    monkeypatch.setattr(audio_track_detector, "list_audio_streams", lambda *a, **k: [])
    monkeypatch.setattr(audio_track_detector, "_run_ffprobe_json", lambda *a, **k: None)

    result = dispatch_json(_payload())

    assert result["type"] == "bridge.error"
    assert result["payload"]["code"] == "audio_tracks_probe_failed"


def test_zero_audio_tracks_is_success(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    monkeypatch.setattr(audio_track_detector, "list_audio_streams", lambda *a, **k: [])
    monkeypatch.setattr(audio_track_detector, "_run_ffprobe_json", lambda *a, **k: {"streams": []})

    result = dispatch_json(_payload())

    assert result["type"] == "media.audiotracks.result"
    assert result["payload"]["tracks"] == []
    assert result["payload"]["autoAudioIndex"] is None


def test_rejects_wrong_field_set(initialized_bridge_home: Path) -> None:
    payload = _payload()
    payload["extra"] = "unexpected"

    result = dispatch_json(payload)

    assert result["payload"]["code"] == "invalid_audio_tracks_request"


def test_rejects_relative_path(initialized_bridge_home: Path) -> None:
    result = dispatch_json(_payload(video_path="ep1.mkv"))

    assert result["payload"]["code"] == "invalid_audio_tracks_request"


def test_rejects_nul_in_path(initialized_bridge_home: Path) -> None:
    result = dispatch_json(_payload(video_path="/videos/ep1\x00.mkv"))

    assert result["payload"]["code"] == "invalid_audio_tracks_request"


def test_rejects_more_than_128_tracks(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    streams = [
        audio_track_detector.AudioStream(
            global_index=index,
            audio_index=index,
            language_tag=None,
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=False,
        )
        for index in range(129)
    ]
    monkeypatch.setattr(audio_track_detector, "list_audio_streams", lambda *a, **k: streams)

    result = dispatch_json(_payload())

    assert result["type"] == "bridge.error"
    assert result["payload"]["code"] == "audio_tracks_probe_failed"


def test_ffprobe_path_is_derived_from_native_library_dir(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    audio_track_detector = _audio_track_detector()
    captured: dict[str, object] = {}

    def fake_list_audio_streams(video_path: Path, ffprobe_cmd: str) -> list[object]:
        captured["ffprobe_cmd"] = ffprobe_cmd
        return [
            audio_track_detector.AudioStream(
                global_index=1,
                audio_index=0,
                language_tag=None,
                title_tag=None,
                codec="aac",
                channels=2,
                is_default=False,
            )
        ]

    monkeypatch.setattr(audio_track_detector, "list_audio_streams", fake_list_audio_streams)

    dispatch_json(_payload(native="/native/lib"))

    assert captured["ffprobe_cmd"] == "/native/lib/libffprobe.so"
