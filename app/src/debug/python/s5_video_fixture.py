"""Debug-only generator for the production S5 video-mining acceptance lane."""

from __future__ import annotations

import subprocess
from pathlib import Path


def create_video(ffmpeg: str, output: str, duration_seconds: int) -> None:
    if not 30 <= duration_seconds <= 180:
        raise ValueError("S5 fixture duration is outside its bounded test range")
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    completed = subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=640x360:rate=24",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=660:sample_rate=24000",
            "-t",
            str(duration_seconds),
            "-c:v",
            "mpeg4",
            "-q:v",
            "7",
            "-c:a",
            "pcm_s16le",
            "-metadata:s:a:0",
            "language=jpn",
            str(target),
        ],
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=120,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"S5 fixture generation failed ({completed.returncode}): "
            f"{completed.stderr[-2000:]}"
        )
    if not target.is_file() or target.stat().st_size == 0:
        raise RuntimeError("S5 fixture generation produced no MKV")
