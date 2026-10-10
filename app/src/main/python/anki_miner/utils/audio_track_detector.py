"""Detect Japanese audio streams in video files via ffprobe."""

import json
import logging
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from anki_miner.utils.android_fd import inherited_fd_command
from anki_miner.utils.subprocess_log import log_command, log_command_result, tail_for_log
from anki_miner.utils.subprocess_utils import no_window_kwargs

logger = logging.getLogger(__name__)

# A probe that returns something other than JSON returned an error page, a
# usage banner or a shell wrapper's complaint; the head of it names which.
_MALFORMED_STDOUT_CHARS = 200

JAPANESE_LANGUAGE_CODES = frozenset({"jpn", "ja", "japanese", "jp"})

# Image-based subtitle codecs: these carry rendered bitmaps, not extractable
# text, so the condenser detects and reports them but never attempts extraction.
BITMAP_SUBTITLE_CODECS = frozenset({"hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub"})


class ProcessRegistry(Protocol):
    @property
    def cancelled(self) -> bool: ...

    def register(self, proc: "subprocess.Popen[str]") -> bool: ...

    def unregister(self, proc: "subprocess.Popen[str]") -> None: ...


def _kill_quietly(proc: "subprocess.Popen[str]") -> None:
    try:
        proc.kill()
    except OSError:
        pass


@dataclass(frozen=True)
class AudioStream:
    """Full metadata for a single audio stream from ffprobe.

    `global_index` is the ffprobe stream index, suitable for ffmpeg `-map 0:N`.
    `audio_index` is the position within the audio-only track list (0-indexed,
    demuxer order); the mpv preview maps it to `aid = N + 1`.
    """

    global_index: int
    audio_index: int
    language_tag: str | None
    title_tag: str | None
    codec: str | None
    channels: int | None
    is_default: bool


@dataclass(frozen=True)
class SubtitleStream:
    """Full metadata for a single subtitle stream from ffprobe.

    `index` is the ffprobe global stream index, suitable for ffmpeg `-map 0:N`.
    `sub_index` is the position within the subtitle-only track list (0-indexed),
    suitable for ffmpeg `-map 0:s:N`.
    `is_text` is False for image-based codecs (:data:`BITMAP_SUBTITLE_CODECS`),
    whose bitmaps cannot be extracted as text.
    `is_forced` / `is_default` mirror the ffprobe disposition flags. Forced
    tracks carry only foreign-dialogue lines, which makes them useless as a
    retiming reference (see ``services/retime_reference.py``); they default to
    False so callers constructing a stream by hand stay unaffected.
    """

    index: int
    sub_index: int
    codec_name: str | None
    language_tag: str | None
    title: str | None
    is_text: bool
    is_forced: bool = False
    is_default: bool = False


def matches_language_tag(language_tag: str | None, codes: frozenset[str]) -> bool:
    """Whether *language_tag* names one of *codes*.

    Accepts the exact tag and a BCP 47 regional variant whose primary subtag
    is in *codes* (``ja-JP`` -> ``ja``, ``ko-KR`` -> ``ko``). A code that is
    merely a prefix (``jav``) never matches: the variant must be
    dash-separated.
    """
    if language_tag is None:
        return False
    normalized = language_tag.lower()
    if normalized in codes:
        return True
    primary, sep, _ = normalized.partition("-")
    return bool(sep) and primary in codes


def is_japanese_language_tag(language_tag: str | None) -> bool:
    """Return whether *language_tag* identifies Japanese.

    Android keeps this name for ``android_bridge/media_probe.py``; it shares
    :func:`matches_language_tag` so the audio-track picker agrees with the
    engine's own auto-detect.
    """
    return matches_language_tag(language_tag, JAPANESE_LANGUAGE_CODES)


def _run_ffprobe_json(
    video_path: Path,
    select_streams: str,
    ffprobe_cmd: str,
    proc_registry: ProcessRegistry | None = None,
) -> dict | None:
    """Run ffprobe for ``select_streams`` and return the parsed JSON object.

    Returns ``None`` if ffprobe fails, times out, raises an OSError, returns a
    non-zero exit code, or returns output that does not parse as JSON. Callers
    translate ``None`` into their documented empty/None fallback.

    ffprobe always emits UTF-8 JSON, so stdout is decoded with
    ``encoding="utf-8", errors="replace"`` — not the platform locale codec,
    which on Windows (cp1252/cp932) raises ``UnicodeDecodeError`` on non-ASCII
    stream titles (ubiquitous in anime MKVs). ``UnicodeDecodeError`` is a
    ``ValueError`` and is also caught defensively below.

    ``ffprobe_cmd`` becomes ``cmd[0]``; config-bearing callers should pass
    ``resolve_ffprobe(config)`` so frozen bundles use the bundled binary.
    Android SAF procfs inputs are duplicated per child and explicitly inherited.
    """
    cmd = [
        ffprobe_cmd,
        "-v",
        "quiet",
        "-print_format",
        "json",
        "-show_streams",
        "-select_streams",
        select_streams,
        str(video_path),
    ]

    if proc_registry is not None and proc_registry.cancelled:
        return None
    log_command(logger, "ffprobe", cmd, timeout_s=30)
    started_at = time.monotonic()
    try:
        with inherited_fd_command(cmd) as (child_cmd, pass_fds):
            proc = subprocess.Popen(
                child_cmd,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                pass_fds=pass_fds,
                **no_window_kwargs(),  # hide the Windows cmd.exe flash (Issue #79)
            )
    except (subprocess.SubprocessError, OSError, ValueError) as e:
        # Nothing ran, so there is no stderr: the argv is the whole diagnosis.
        log_command(logger, "ffprobe", cmd, timeout_s=30, level=logging.WARNING)
        logger.warning("Error probing %s (select=%s): %s: %s", video_path, select_streams, type(e).__name__, e)
        return None

    if proc_registry is not None and not proc_registry.register(proc):
        with proc:
            _kill_quietly(proc)
            try:
                proc.communicate()
            except (subprocess.SubprocessError, OSError, ValueError):
                pass
        return None

    stdout = ""
    stderr = ""
    try:
        with proc:
            try:
                stdout, stderr = proc.communicate(timeout=30)
            except subprocess.TimeoutExpired:
                _kill_quietly(proc)
                proc.communicate()
                logger.warning("ffprobe timed out for %s", video_path)
                log_command_result(
                    logger,
                    "ffprobe",
                    cmd,
                    returncode=proc.returncode,
                    state="timed_out",
                    elapsed_s=time.monotonic() - started_at,
                    level=logging.WARNING,
                )
                return None
            except (subprocess.SubprocessError, OSError, ValueError) as e:
                _kill_quietly(proc)
                logger.warning("Error probing %s (select=%s): %s: %s", video_path, select_streams, type(e).__name__, e)
                log_command_result(
                    logger,
                    "ffprobe",
                    cmd,
                    returncode=proc.returncode,
                    state="communicate_failed",
                    elapsed_s=time.monotonic() - started_at,
                    level=logging.WARNING,
                )
                return None
    finally:
        if proc_registry is not None:
            proc_registry.unregister(proc)

    elapsed_s = time.monotonic() - started_at
    if proc.returncode != 0:
        if proc_registry is not None and proc_registry.cancelled:
            logger.debug("ffprobe cancelled for %s", video_path)
            return None
        log_command_result(
            logger,
            "ffprobe",
            cmd,
            returncode=proc.returncode,
            stderr_tail=tail_for_log(stderr or ""),
            elapsed_s=elapsed_s,
            level=logging.WARNING,
        )
        return None

    try:
        data: dict = json.loads(stdout)
    except json.JSONDecodeError as e:
        # ffprobe exited 0 and still produced non-JSON: log the head of what it
        # did produce, which names the wrapper or error page that answered.
        logger.warning(
            "ffprobe returned malformed JSON for %s: %s: stdout[:%d]=%r",
            video_path,
            e,
            _MALFORMED_STDOUT_CHARS,
            (stdout or "")[:_MALFORMED_STDOUT_CHARS],
        )
        return None
    log_command_result(logger, "ffprobe", cmd, returncode=0, elapsed_s=elapsed_s)
    return data


def list_audio_streams(
    video_path: Path,
    ffprobe_cmd: str = "ffprobe",
    *,
    proc_registry: ProcessRegistry | None = None,
) -> list[AudioStream]:
    """Probe a video file with ffprobe and return all audio streams.

    Returns an empty list if ffprobe fails, times out, raises an OSError,
    returns a non-zero exit code, or returns malformed JSON. Streams missing
    the top-level ``index`` field are skipped, but still consume an
    ``audio_index`` slot (preserving parity with the original enumeration
    behavior).

    ``ffprobe_cmd`` is the executable to invoke (``cmd[0]``); it defaults to the
    bare ``"ffprobe"`` literal so direct callers are unaffected. Config-bearing
    callers should pass ``resolve_ffprobe(config)`` so frozen bundles use the
    bundled binary.
    """
    data = _run_ffprobe_json(video_path, "a", ffprobe_cmd, proc_registry)
    if data is None:
        return []

    raw_streams = data.get("streams", [])
    result: list[AudioStream] = []

    for audio_index, stream in enumerate(raw_streams):
        try:
            global_index = int(stream["index"])
        except (KeyError, TypeError, ValueError):
            # audio_index slot is consumed but stream is skipped
            continue

        tags = stream.get("tags", {}) or {}
        lang_raw = tags.get("language")
        language_tag = lang_raw.lower() if lang_raw else None
        title_tag = tags.get("title") or None

        codec = stream.get("codec_name") or None

        channels_raw = stream.get("channels")
        channels: int | None = None
        if channels_raw is not None:
            try:
                channels = int(channels_raw)
            except (ValueError, TypeError):
                channels = None

        disposition = stream.get("disposition") or {}
        is_default = disposition.get("default") == 1

        result.append(
            AudioStream(
                global_index=global_index,
                audio_index=audio_index,
                language_tag=language_tag,
                title_tag=title_tag,
                codec=codec,
                channels=channels,
                is_default=is_default,
            )
        )

    return result


def list_subtitle_streams(
    video_path: Path,
    ffprobe_cmd: str = "ffprobe",
    *,
    proc_registry: ProcessRegistry | None = None,
) -> list[SubtitleStream]:
    """Probe a video file with ffprobe and return all subtitle streams.

    Returns an empty list if ffprobe fails, times out, raises an OSError,
    returns a non-zero exit code, or returns malformed JSON. Streams missing the
    top-level ``index`` field are skipped, but still consume a ``sub_index``
    slot (preserving parity with :func:`list_audio_streams`).

    ``sub_index`` is the position within the subtitle-only track list, suitable
    for ffmpeg ``-map 0:s:N``; ``index`` is the global stream index. These
    diverge whenever subtitle streams are interleaved with audio/video in the
    container.

    ``ffprobe_cmd`` is the executable to invoke (``cmd[0]``); it defaults to the
    bare ``"ffprobe"`` literal so direct callers are unaffected. Config-bearing
    callers should pass ``resolve_ffprobe(config)`` so frozen bundles use the
    bundled binary.
    """
    data = _run_ffprobe_json(video_path, "s", ffprobe_cmd, proc_registry)
    if data is None:
        return []

    raw_streams = data.get("streams", [])
    result: list[SubtitleStream] = []

    for sub_index, stream in enumerate(raw_streams):
        try:
            index = int(stream["index"])
        except (KeyError, TypeError, ValueError):
            # sub_index slot is consumed but stream is skipped
            continue

        tags = stream.get("tags", {}) or {}
        lang_raw = tags.get("language")
        language_tag = lang_raw.lower() if lang_raw else None
        title = tags.get("title") or None

        codec_name = stream.get("codec_name") or None
        is_text = codec_name not in BITMAP_SUBTITLE_CODECS

        disposition = stream.get("disposition") or {}

        result.append(
            SubtitleStream(
                index=index,
                sub_index=sub_index,
                codec_name=codec_name,
                language_tag=language_tag,
                title=title,
                is_text=is_text,
                is_forced=disposition.get("forced") == 1,
                is_default=disposition.get("default") == 1,
            )
        )

    return result


def find_japanese_audio_stream(
    video_file: Path,
    ffprobe_cmd: str = "ffprobe",
    *,
    codes: frozenset[str] | None = None,
    proc_registry: ProcessRegistry | None = None,
) -> AudioStream | None:
    """Probe a video file with ffprobe and return its mining-language audio stream.

    Returns None if ffprobe fails, returns malformed JSON, or no audio stream
    carries a matching language tag.

    ``ffprobe_cmd`` is forwarded to :func:`list_audio_streams`; defaults to the
    bare ``"ffprobe"`` literal so direct callers are unaffected.

    ``codes`` is the mining language's ``LanguageProfile.audio_track_codes``;
    it is keyword-only so the pre-existing positional ``ffprobe_cmd`` callers
    stay untouched, and ``None`` means :data:`JAPANESE_LANGUAGE_CODES`.
    """
    wanted = JAPANESE_LANGUAGE_CODES if codes is None else codes
    streams = list_audio_streams(
        video_file,
        ffprobe_cmd=ffprobe_cmd,
        proc_registry=proc_registry,
    )

    japanese_streams = [stream for stream in streams if matches_language_tag(stream.language_tag, wanted)]
    if japanese_streams:
        stream = next((candidate for candidate in japanese_streams if candidate.is_default), japanese_streams[0])
        logger.info(
            "Found matching audio: global stream %d, audio track %d (language: %s)",
            stream.global_index,
            stream.audio_index,
            stream.language_tag,
        )
        return stream

    available_langs = [s.language_tag or "unknown" for s in streams]
    logger.warning(
        "No audio tagged %s found in %s. Available languages: %s", sorted(wanted), video_file, available_langs
    )
    return None


def get_primary_video_codec(
    video_file: Path,
    ffprobe_cmd: str = "ffprobe",
    *,
    proc_registry: ProcessRegistry | None = None,
) -> str | None:
    """Return the codec_name of the first video stream (lowercased), or None.

    Returns None on any ffprobe failure (timeout, OSError, non-zero exit,
    malformed JSON), or when the file has no video stream / no ``codec_name``.
    Callers treat None as "assume supported" so a probe failure never disables
    an otherwise-working preview.

    ``ffprobe_cmd`` is the executable to invoke; defaults to the bare
    ``"ffprobe"`` literal. Config-bearing callers should pass
    ``resolve_ffprobe(config)`` so frozen bundles use the bundled binary.
    """
    data = _run_ffprobe_json(video_file, "v:0", ffprobe_cmd, proc_registry)
    if data is None:
        return None

    streams = data.get("streams", [])
    if not streams:
        return None

    codec = streams[0].get("codec_name")
    return codec.lower() if codec else None
