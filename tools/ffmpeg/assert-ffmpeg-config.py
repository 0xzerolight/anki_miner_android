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
        # The app only reads user-selected files. Device capture is outside
        # the v1 surface and would add camera/media NDK dependencies.
        "CONFIG_ANDROID_CAMERA_INDEV",
        "CONFIG_FBDEV_INDEV",
        "CONFIG_FBDEV_OUTDEV",
        "CONFIG_V4L2_INDEV",
        "CONFIG_V4L2_OUTDEV",
    }
)
ALLOWED_ENABLED_DEVICES = frozenset({"CONFIG_LAVFI_INDEV"})


class ConfigurationError(ValueError):
    pass


def read_configuration(paths: tuple[Path, ...]) -> dict[str, int]:
    values: dict[str, int] = {}
    for path in paths:
        if not path.is_file():
            raise ConfigurationError(f"generated config is missing: {path}")
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = _DEFINE_RE.fullmatch(line)
            if match is None:
                continue
            key, raw_value = match.groups()
            value = int(raw_value)
            if key in values and values[key] != value:
                raise ConfigurationError(f"conflicting duplicate {key} at {path.name}:{line_number}")
            values[key] = value
    return values


def assert_configuration(config_h: Path, components_h: Path) -> None:
    values = read_configuration((config_h, components_h))
    failures = [
        *(f"{key}=1 required, found {values.get(key)!r}" for key in sorted(REQUIRED_ENABLED) if values.get(key) != 1),
        *(f"{key}=0 required, found {values.get(key)!r}" for key in sorted(REQUIRED_DISABLED) if values.get(key) != 0),
    ]
    unexpected_devices = sorted(
        key
        for key, value in values.items()
        if value == 1 and key.endswith(("_INDEV", "_OUTDEV")) and key not in ALLOWED_ENABLED_DEVICES
    )
    if unexpected_devices:
        failures.append("unexpected enabled devices: " + ", ".join(unexpected_devices))
    if failures:
        raise ConfigurationError("; ".join(failures))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("config_h", type=Path)
    parser.add_argument("config_components_h", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        assert_configuration(args.config_h, args.config_components_h)
    except (ConfigurationError, OSError, UnicodeError) as error:
        print(f"FFmpeg configuration check failed: {error}", file=sys.stderr)
        return 1
    print("FFmpeg configuration OK: Matroska, static JPEG, MP3/Opus/WAV, " "local protocols only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
