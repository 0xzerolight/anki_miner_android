#!/usr/bin/env python3
"""Assert the generated FFmpeg config contains the Android v1 media surface."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys


_DEFINE_RE = re.compile(r"^#define ([A-Z][A-Z0-9_]*) ([01])$")

REQUIRED_ENABLED = frozenset(
    {
        "CONFIG_FFMPEG",
        "CONFIG_FFPROBE",
        "CONFIG_ZLIB",
        # SAF inputs and the S3 Matroska fixture.
        "CONFIG_FILE_PROTOCOL",
        "CONFIG_PIPE_PROTOCOL",
        "CONFIG_MATROSKA_DEMUXER",
        "CONFIG_MATROSKA_MUXER",
        "CONFIG_LAVFI_INDEV",
        "CONFIG_TESTSRC2_FILTER",
        "CONFIG_SINE_FILTER",
        "CONFIG_MPEG4_ENCODER",
        "CONFIG_MPEG4_DECODER",
        "CONFIG_PCM_S16LE_ENCODER",
        "CONFIG_PCM_S16LE_DECODER",
        # Static screenshot path.
        "CONFIG_MJPEG_ENCODER",
        "CONFIG_IMAGE2_MUXER",
        "CONFIG_SCALE_FILTER",
        # Mining audio outputs and full-audio WAV extraction.
        "CONFIG_LIBMP3LAME_ENCODER",
        "CONFIG_MP3_MUXER",
        "CONFIG_LIBOPUS_ENCODER",
        "CONFIG_OPUS_MUXER",
        "CONFIG_WAV_MUXER",
    }
)

REQUIRED_DISABLED = frozenset(
    {
        "CONFIG_FFPLAY",
        "CONFIG_NETWORK",
        "CONFIG_GPL",
        "CONFIG_GPLV3",
        "CONFIG_NONFREE",
        "CONFIG_HTTP_PROTOCOL",
        "CONFIG_HTTPS_PROTOCOL",
        "CONFIG_TCP_PROTOCOL",
        "CONFIG_TLS_PROTOCOL",
        "CONFIG_UDP_PROTOCOL",
    }
)


class ConfigurationError(ValueError):
    pass


def read_configuration(path: Path) -> dict[str, int]:
    if not path.is_file():
        raise ConfigurationError(f"generated config is missing: {path}")
    values: dict[str, int] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = _DEFINE_RE.fullmatch(line)
        if match is None:
            continue
        key, raw_value = match.groups()
        if key in values:
            raise ConfigurationError(f"duplicate {key} at line {line_number}")
        values[key] = int(raw_value)
    return values


def assert_configuration(path: Path) -> None:
    values = read_configuration(path)
    failures = [
        *(f"{key}=1 required, found {values.get(key)!r}" for key in sorted(REQUIRED_ENABLED) if values.get(key) != 1),
        *(f"{key}=0 required, found {values.get(key)!r}" for key in sorted(REQUIRED_DISABLED) if values.get(key) != 0),
    ]
    if failures:
        raise ConfigurationError("; ".join(failures))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("config_h", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        assert_configuration(args.config_h)
    except (ConfigurationError, OSError, UnicodeError) as error:
        print(f"FFmpeg configuration check failed: {error}", file=sys.stderr)
        return 1
    print(
        "FFmpeg configuration OK: Matroska, static JPEG, MP3/Opus/WAV, "
        "local protocols only"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
