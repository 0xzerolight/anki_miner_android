"""media.audiotracks: enumerate audio streams for the per-run track picker.

The auto pick is the stream the run would take, by the extractor's own rule
(``pick_audio_stream``): a track tagged in the mining language (the profile's
``audio_track_codes``), else an untagged track whose title names the language;
the default-disposition one among several, else the first. The optional
``language`` (absent = ja) names it.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping
from pathlib import Path

from .languages import get_profile, payload_language, without_language
from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)

MAX_TRACKS = 128


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def get_audio_tracks(payload: Mapping[str, object]) -> str:
    if set(without_language(payload)) != {"videoPath", "nativeLibraryDir"}:
        raise _fail(
            "invalid_audio_tracks_request",
            "Expected payload fields: ['nativeLibraryDir', 'videoPath'] and an optional 'language'",
        )
    raw_path = payload["videoPath"]
    raw_native = payload["nativeLibraryDir"]
    for name, value in (("videoPath", raw_path), ("nativeLibraryDir", raw_native)):
        if not isinstance(value, str) or not value or "\x00" in value:
            raise _fail("invalid_audio_tracks_request", f"{name} must be a non-empty string")
        if not Path(value).is_absolute():
            raise _fail("invalid_audio_tracks_request", f"{name} must be absolute")

    language = payload_language(payload)

    # Deferred: ANKI_MINER_HOME freezes at anki_miner import time.
    from anki_miner.utils.audio_track_detector import (
        _run_ffprobe_json,
        list_audio_streams,
        pick_audio_stream,
    )

    video_path = Path(raw_path)
    ffprobe = str(Path(raw_native) / "libffprobe.so")
    streams = list_audio_streams(video_path, ffprobe_cmd=ffprobe)
    # list_audio_streams returns [] for both "no audio" and "probe failed"; disambiguate.
    if not streams and _run_ffprobe_json(video_path, "a", ffprobe) is None:
        raise _fail("audio_tracks_probe_failed", "The file could not be probed for audio tracks")
    if len(streams) > MAX_TRACKS:
        raise _fail("audio_tracks_probe_failed", f"More than {MAX_TRACKS} audio tracks")

    from anki_miner.languages.registry import audio_title_language

    auto = pick_audio_stream(streams, get_profile(language).audio_track_codes, audio_title_language(language))

    return encode_message(
        "media.audiotracks.result",
        {
            "videoPath": raw_path,
            "autoAudioIndex": auto.audio_index if auto is not None else None,
            "tracks": [
                {
                    "audioIndex": s.audio_index,
                    "globalIndex": s.global_index,
                    "languageTag": s.language_tag,
                    "title": s.title_tag,
                    "codec": s.codec,
                    "channels": s.channels,
                    "isDefault": s.is_default,
                }
                for s in streams
            ],
        },
    )
