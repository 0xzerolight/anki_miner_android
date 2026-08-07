"""Debug-only, on-device S3 probe for SAF inheritance and native media tools."""

from __future__ import annotations

import json
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def _run(command: list[str]) -> tuple[subprocess.CompletedProcess[str], int]:
    from anki_miner.utils.android_fd import inherited_fd_command

    with inherited_fd_command(command) as (child_command, pass_fds):
        inherited_count = len(pass_fds)
        completed = subprocess.run(
            child_command,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            pass_fds=pass_fds,
            check=False,
        )
    return completed, inherited_count


def _require_success(
    operation: str,
    result: tuple[subprocess.CompletedProcess[str], int],
) -> tuple[subprocess.CompletedProcess[str], int]:
    completed, inherited_count = result
    if completed.returncode != 0:
        raise RuntimeError(
            f"{operation} failed ({completed.returncode}): {completed.stderr[-2000:]}"
        )
    return completed, inherited_count


def create_fixture(ffmpeg: str, output: str) -> None:
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    result = _run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=160x90:rate=10",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=800:sample_rate=16000",
            "-t",
            "1.5",
            "-c:v",
            "mpeg4",
            "-q:v",
            "8",
            "-c:a",
            "pcm_s16le",
            "-metadata:s:a:0",
            "language=jpn",
            str(target),
        ]
    )
    _require_success("fixture generation", result)
    if not target.is_file() or target.stat().st_size == 0:
        raise RuntimeError("fixture generation produced no MKV")


def create_dual_audio_fixture(ffmpeg: str, output: str) -> None:
    """Dual-audio MKV: Japanese track first, English second, both decodable everywhere.

    The two tracks are deliberately equivalent apart from their language tag and tone, so no
    channel-count or bitrate tiebreak can decide the selection: only the language does.

    Both default dispositions are cleared, which is the shape that actually reproduces the bug.
    ffmpeg's matroska muxer flags the first audio stream as default unless told otherwise, and
    `isDefaultSelectionFlag` outranks the locale tiebreak in ExoPlayer's audio comparator — a
    fixture keeping that flag selects the Japanese track no matter what the player does. The
    reported release ([Judas] Jujutsu Kaisen S02) carries default=0 on both audio tracks.
    """
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    result = _run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=160x90:rate=10",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=800:sample_rate=16000",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=300:sample_rate=16000",
            "-t",
            "1.5",
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-map",
            "2:a:0",
            "-c:v",
            "mpeg4",
            "-q:v",
            "8",
            "-c:a",
            "pcm_s16le",
            "-metadata:s:a:0",
            "language=jpn",
            "-metadata:s:a:1",
            "language=eng",
            "-disposition:a:0",
            "0",
            "-disposition:a:1",
            "0",
            str(target),
        ]
    )
    _require_success("dual audio fixture generation", result)
    if not target.is_file() or target.stat().st_size == 0:
        raise RuntimeError("dual audio fixture generation produced no MKV")


def create_av1_fixture(ffmpeg: str, output: str) -> None:
    """AV1 video the API 26 emulator cannot decode (no platform or nextlib AV1 decoder).

    The audio track stays universally decodable so the deselected-video failure mode keeps
    its signature: STATE_READY, audio playing, black picture.
    """
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    result = _run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=160x90:rate=10",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=800:sample_rate=16000",
            "-t",
            "1.5",
            "-c:v",
            "libaom-av1",
            "-cpu-used",
            "8",
            "-crf",
            "50",
            "-c:a",
            "pcm_s16le",
            "-metadata:s:a:0",
            "language=jpn",
            str(target),
        ]
    )
    _require_success("av1 fixture generation", result)
    if not target.is_file() or target.stat().st_size == 0:
        raise RuntimeError("av1 fixture generation produced no MKV")


def probe_and_extract(
    ffmpeg: str,
    ffprobe: str,
    input_path: str,
    output_dir: str,
) -> str:
    output_root = Path(output_dir)
    output_root.mkdir(parents=True, exist_ok=True)
    screenshot = output_root / "frame.jpg"
    audio = output_root / "clip.mp3"

    probe, probe_fds = _require_success(
        "ffprobe",
        _run(
            [
                ffprobe,
                "-v",
                "error",
                "-select_streams",
                "a",
                "-show_entries",
                "stream=index:stream_tags=language",
                "-of",
                "json",
                input_path,
            ]
        ),
    )
    streams = json.loads(probe.stdout).get("streams", [])

    commands = [
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            "0.2",
            "-i",
            input_path,
            "-frames:v",
            "1",
            "-q:v",
            "2",
            str(screenshot),
        ],
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            "0.1",
            "-t",
            "0.8",
            "-i",
            input_path,
            "-map",
            "0:a:0",
            "-c:a",
            "libmp3lame",
            str(audio),
        ],
    ]
    start = threading.Barrier(2)

    def run_parallel(command: list[str]) -> tuple[subprocess.CompletedProcess[str], int]:
        start.wait(timeout=10)
        return _run(command)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(run_parallel, command) for command in commands]
        results = [
            _require_success(operation, future.result(timeout=35))
            for operation, future in zip(("screenshot", "audio"), futures, strict=True)
        ]

    if not screenshot.is_file() or screenshot.stat().st_size == 0:
        raise RuntimeError("screenshot extraction produced no JPEG")
    if not audio.is_file() or audio.stat().st_size == 0:
        raise RuntimeError("audio extraction produced no MP3")

    return json.dumps(
        {
            "streams": streams,
            "probeInheritedFds": probe_fds,
            "parallelInheritedFds": [result[1] for result in results],
            "screenshotBytes": screenshot.stat().st_size,
            "audioBytes": audio.stat().st_size,
        },
        separators=(",", ":"),
        sort_keys=True,
    )


def encode_animated_clip(ffmpeg: str, input_path: str, output_dir: str, fmt: str) -> str:
    """Encode one animated screenshot with the engine's own command builder.

    Runs `MediaExtractorService._extract_animated_screenshot` verbatim against the
    shipped binary, so the encoder probe, the argument construction, and the ffmpeg
    build are all exercised together. A unit test can only assert the argv; only
    this can tell whether `libaom-av1` and `libwebp_anim` are actually compiled in
    and can produce a file.
    """
    from types import SimpleNamespace

    from anki_miner.services.media_extractor import MediaExtractorService

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"clip.{fmt}"
    target.unlink(missing_ok=True)

    service = object.__new__(MediaExtractorService)
    service.config = SimpleNamespace(
        ffmpeg_location=ffmpeg,
        screenshot_animated=True,
        screenshot_animated_format=fmt,
        screenshot_animated_match_audio=False,
        screenshot_animated_clip_duration=1.0,
        screenshot_animated_fps=10,
        screenshot_animated_height=90,
        screenshot_animated_quality=30,
        audio_padding=0.0,
    )
    service._animated_encoder_ok = {}
    service._encoder_probe_lock = threading.Lock()

    encoded = service._extract_animated_screenshot(Path(input_path), 0.1, 1.0, target)

    return json.dumps(
        {
            "encoded": bool(encoded),
            "format": fmt,
            "bytes": target.stat().st_size if target.is_file() else 0,
            "header": target.read_bytes()[:16].hex() if target.is_file() else "",
        },
        separators=(",", ":"),
        sort_keys=True,
    )
