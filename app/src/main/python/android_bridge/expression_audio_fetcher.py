"""Bridge-side custom URL / custom-JSON expression-audio fetcher.

Ported from the desktop ``anki_miner.services.custom_audio_fetcher`` so the
Android port can query AnkiConnect-Android's on-device local-audio server
(``http://localhost:8765/localaudio/...``) without importing the desktop
service factory (which eagerly pulls the cut JPod101/Google-TTS fetchers).

The local-audio-yomichan integration contract: a URL template containing
``{term}`` / ``{reading}`` / ``{language}`` placeholders.

* ``custom``      — the templated URL returns the audio bytes directly.
* ``custom_json`` — the templated URL returns an ``audioSourceList`` JSON
  document (``{"type": "audioSourceList", "audioSources": [{"url": ...}, ...]}``);
  each listed URL is tried in order, first successful download wins.

Reuses the vendored ``anki_miner.services.audio_fetch_common`` toolkit (shared
Session/UA/size-cap/atomic-download/failure-classification plumbing). No
``.miss`` negative markers (a custom server's contents change, so a miss now may
be a hit later); the cache directory is per-source. Never raises — the Phase-3
pipeline loop has no try/except by design.

The ONLY deliberate deviation from the desktop file is that every
``from anki_miner...`` import is FUNCTION-LOCAL: ``config/paths.py`` freezes
``ANKI_MINER_HOME`` at import, so no bridge module may pull an ``anki_miner``
package at its top level (see the CLAUDE.md import-freeze gotcha). ``requests``
and the standard library stay top-level, matching ``jisho_provider``.
"""

from __future__ import annotations

import contextlib
import hashlib
import ipaddress
import json
import logging
import math
import os
import re
import subprocess
import threading
import time
from collections.abc import Callable, Iterable
from pathlib import Path
from urllib.parse import urljoin, urlsplit

import requests

logger = logging.getLogger(__name__)

# Matches a ``{placeholder}`` token. Ported (with _substitute_custom_url below)
# from Yomitan ext/js/media/audio-downloader.js AudioDownloader._getCustomUrl,
# upstream commit e2ed450c2f11a591922822e77f008e70a87daf0c.
_PLACEHOLDER_RE = re.compile(r"\{([^}]*)\}")
_HOST_LABEL_RE = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", re.IGNORECASE)
_LOOPBACK_HOSTS = frozenset({"localhost", "127.0.0.1"})
_REVIEWED_LOOPBACK_ORIGINS = frozenset(
    {
        ("http", "localhost", 8765),
        ("http", "127.0.0.1", 8765),
    }
)
_REDIRECT_STATUSES = frozenset({301, 302, 303, 307, 308})
_MAX_JSON_BYTES = 256 * 1024
_MAX_AUDIO_SOURCES = 8
_MAX_URL_BYTES = 4096
_MAX_REDIRECTS = 3
_MAX_TOTAL_ATTEMPTS = 16
_FETCH_DEADLINE_SECONDS = 30.0
_MAX_CACHED_AUDIO_BYTES = 5 * 1024 * 1024
_AUDIO_PROBE_TIMEOUT_SECONDS = 5.0
_BRIDGE_FAILURE_KEYS = (
    "policy_rejection",
    "oversized_response",
    "oversized_list",
    "malformed_json",
    "circuit_skipped",
)
# Consecutive whole-fetch transport failures (timeout/connection) that open the
# per-run circuit: further fetches skip the network and fall through to packs
# instead of paying the full deadline per word against a hung localaudio server.
_CIRCUIT_BREAKER_THRESHOLD = 3


class _PolicyViolation(requests.RequestException):
    """A privacy-safe network-policy rejection."""


class _CancelledRequest(requests.RequestException):
    """Internal signal for cancellation between bounded request hops."""


class _DeadlineExceeded(requests.Timeout):
    """Internal signal for an exhausted per-word request deadline."""


class _RequestDeadline:
    """One monotonic budget with a timer that closes the active response."""

    def __init__(
        self,
        seconds: float,
        monotonic: Callable[[], float],
        expire_active_response: Callable[[], None],
    ) -> None:
        self._monotonic = monotonic
        self._expires_at = monotonic() + seconds
        self._expired = threading.Event()
        self._timer = threading.Timer(seconds, self._expire, args=(expire_active_response,))
        self._timer.daemon = True
        self._timer.start()

    def _expire(self, expire_active_response: Callable[[], None]) -> None:
        self._expired.set()
        expire_active_response()

    def check(self) -> None:
        if self._expired.is_set() or self._monotonic() >= self._expires_at:
            self._expired.set()
            raise _DeadlineExceeded("expression audio deadline exceeded")

    def remaining(self) -> float:
        self.check()
        return max(0.001, self._expires_at - self._monotonic())

    @property
    def expired(self) -> bool:
        try:
            self.check()
        except _DeadlineExceeded:
            return True
        return False

    def close(self) -> None:
        self._timer.cancel()


class _RunAudioCache:
    """Pins source copies for one run and removes only unreferenced cache files.

    Pruning happens at construction (prior-run leftovers), via ``discard()``
    (rejected files), and at ``close()`` (run end) — never on ``pin()``, which
    would be an O(n²) full walk per hit and would bump the cache directory's
    mtime, invalidating the ``find_cached_by_stem`` signature index.
    """

    def __init__(self, root: Path) -> None:
        self._root = root.resolve(strict=False)
        self._pinned: set[Path] = set()
        self._closed = False
        self._lock = threading.RLock()
        self.prune_unreferenced()

    def pin(self, path: Path) -> bool:
        with self._lock:
            if self._closed:
                return False
            try:
                resolved = path.resolve(strict=True)
                resolved.relative_to(self._root)
            except (OSError, RuntimeError, ValueError):
                return False
            if not resolved.is_file():
                return False
            self._pinned.add(resolved)
            return True

    def discard(self, path: Path) -> None:
        with self._lock:
            try:
                resolved = path.resolve(strict=False)
                resolved.relative_to(self._root)
            except (OSError, RuntimeError, ValueError):
                return
            self._pinned.discard(resolved)
            with contextlib.suppress(OSError):
                path.unlink()

    def prune_unreferenced(self) -> None:
        with self._lock:
            self._prune_locked()

    def _prune_locked(self) -> None:
        if not self._root.is_dir():
            return
        try:
            entries = list(os.walk(self._root, topdown=False, followlinks=False))
        except OSError:
            return
        for directory, child_dirs, filenames in entries:
            parent = Path(directory)
            for filename in filenames:
                candidate = parent / filename
                if candidate.resolve(strict=False) in self._pinned:
                    continue
                with contextlib.suppress(OSError):
                    candidate.unlink()
            for child_dir in child_dirs:
                candidate = parent / child_dir
                try:
                    if candidate.is_symlink():
                        candidate.unlink()
                    else:
                        candidate.rmdir()
                except OSError:
                    continue

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._pinned.clear()
            self._prune_locked()
            self._closed = True


class _PolicyDownloadSession:
    """Session-shaped adapter used by the unchanged vendored audio downloader."""

    def __init__(self, fetcher: CustomAudioFetcher) -> None:
        self._fetcher = fetcher

    def get(self, url: str, **kwargs: object) -> object:
        timeout = kwargs.get("timeout", 10)
        stream = bool(kwargs.get("stream", False))
        response = self._fetcher._get_with_policy(
            url,
            timeout=timeout,
            stream=stream,
            directory_only=False,
            cancelled_check=self._fetcher._active_cancelled_check,
        )
        return _CancellationAwareResponse(
            response,
            self._fetcher,
            self._fetcher._active_cancelled_check,
        )


class _CancellationAwareResponse:
    """Response proxy which checks cancellation between streamed audio chunks."""

    def __init__(
        self,
        response: requests.Response,
        fetcher: CustomAudioFetcher,
        cancelled_check: Callable[[], bool] | None,
    ) -> None:
        self._response = response
        self._fetcher = fetcher
        self._cancelled_check = cancelled_check

    def __getattr__(self, name: str) -> object:
        return getattr(self._response, name)

    def iter_content(self, *args: object, **kwargs: object):
        chunks = iter(self._response.iter_content(*args, **kwargs))
        while True:
            self._fetcher._check_deadline()
            try:
                chunk = next(chunks)
            except StopIteration:
                return
            self._fetcher._check_deadline()
            if self._cancelled_check is not None and self._cancelled_check():
                self._fetcher._cancellations_raised += 1
                raise _CancelledRequest("request cancelled")
            yield chunk


def _url_size(value: str) -> int:
    try:
        return len(value.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise _PolicyViolation("URL contains invalid Unicode") from exc


def _parse_http_origin(url: str) -> tuple[str, str, int]:
    """Return normalized HTTP(S) origin or raise a privacy-safe rejection."""

    if not isinstance(url, str) or not url or _url_size(url) > _MAX_URL_BYTES:
        raise _PolicyViolation("URL exceeds policy bounds")
    if any(ord(character) < 0x20 for character in url) or "\\" in url:
        raise _PolicyViolation("URL contains forbidden characters")
    try:
        parsed = urlsplit(url)
        scheme = parsed.scheme.lower()
        host = parsed.hostname
        username = parsed.username
        password = parsed.password
        port = parsed.port
    except ValueError as exc:
        raise _PolicyViolation("URL authority is malformed") from exc
    if scheme not in {"http", "https"}:
        raise _PolicyViolation("URL scheme is unsupported")
    if username is not None or password is not None:
        raise _PolicyViolation("URL credentials are forbidden")
    if host is None or not host or host.endswith("."):
        raise _PolicyViolation("URL host is malformed")
    host = host.lower()
    if port is not None and not 1 <= port <= 65535:
        raise _PolicyViolation("URL port is invalid")

    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        labels = host.split(".")
        if len(host) > 253 or any(not _HOST_LABEL_RE.fullmatch(label) for label in labels):
            raise _PolicyViolation("URL host is malformed") from None
    else:
        if host != "127.0.0.1" and not address.is_global:
            raise _PolicyViolation("Private network targets are forbidden")

    return scheme, host, port or (443 if scheme == "https" else 80)


def _normalize_approved_origin(origin: str) -> tuple[str, str, int]:
    parsed = urlsplit(origin)
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValueError("approved audio origins must not contain paths, queries, or fragments")
    try:
        normalized = _parse_http_origin(origin)
    except _PolicyViolation as exc:
        raise ValueError("approved audio origin is invalid") from exc
    if normalized[1] in _LOOPBACK_HOSTS:
        raise ValueError("loopback origins are already allowed by policy")
    return normalized


def _substitute_custom_url(template: str, term: str, reading: str, language: str) -> str:
    """Substitute ``{term}``/``{reading}``/``{language}`` in *template*.

    Ported from Yomitan ``AudioDownloader._getCustomUrl`` (commit e2ed450). An
    unknown ``{name}`` placeholder is left intact, exactly as upstream does.
    """
    data = {"term": term, "reading": reading, "language": language}

    def _replace(match: re.Match[str]) -> str:
        key = match.group(1)
        return data[key] if key in data else match.group(0)

    return _PLACEHOLDER_RE.sub(_replace, template)


def custom_audio_slug(url_template: str) -> str:
    """Return a short, stable, filesystem-safe slug for a custom-source URL.

    Keys the per-source cache directory (``custom_<slug>``) and the cached Anki
    media filename so two custom sources never collide. A hash (not the raw URL)
    keeps the name short and free of URL metacharacters.
    """
    return hashlib.sha1(url_template.encode("utf-8")).hexdigest()[:10]


def _mp3_frame_length(header: bytes) -> int | None:
    if len(header) < 4 or header[0] != 0xFF or header[1] & 0xE0 != 0xE0:
        return None
    version = (header[1] >> 3) & 0x03
    layer = (header[1] >> 1) & 0x03
    bitrate_index = (header[2] >> 4) & 0x0F
    sample_index = (header[2] >> 2) & 0x03
    if version == 1 or layer != 1 or bitrate_index in {0, 15} or sample_index == 3:
        return None
    bitrates = (
        (32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        if version == 3
        else (8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    )
    sample_rates = {
        3: (44_100, 48_000, 32_000),
        2: (22_050, 24_000, 16_000),
        0: (11_025, 12_000, 8_000),
    }
    bitrate = bitrates[bitrate_index - 1]
    sample_rate = sample_rates[version][sample_index]
    coefficient = 144_000 if version == 3 else 72_000
    return (coefficient * bitrate // sample_rate) + ((header[2] >> 1) & 0x01)


def _valid_mp3(body: bytes) -> bool:
    offset = 0
    if body.startswith(b"ID3"):
        if len(body) < 10 or any(byte & 0x80 for byte in body[6:10]):
            return False
        tag_size = sum(byte << shift for byte, shift in zip(body[6:10], (21, 14, 7, 0), strict=True))
        offset = 10 + tag_size + (10 if body[5] & 0x10 else 0)
        if offset >= len(body):
            return False
    search_end = min(len(body) - 3, offset + 4_096)
    for frame_offset in range(offset, search_end):
        frame_length = _mp3_frame_length(body[frame_offset : frame_offset + 4])
        if frame_length is not None and frame_offset + frame_length <= len(body):
            return True
    return False


def _valid_aac(body: bytes) -> bool:
    if len(body) < 7 or body[0] != 0xFF or body[1] & 0xF6 != 0xF0:
        return False
    frame_length = ((body[3] & 0x03) << 11) | (body[4] << 3) | (body[5] >> 5)
    return frame_length >= 7 and frame_length <= len(body)


def _valid_wav(body: bytes) -> bool:
    if len(body) < 44 or body[:4] != b"RIFF" or body[8:12] != b"WAVE":
        return False
    container_end = int.from_bytes(body[4:8], "little") + 8
    if container_end > len(body):
        return False
    offset = 12
    has_format = False
    has_audio = False
    while offset + 8 <= container_end:
        chunk_id = body[offset : offset + 4]
        chunk_size = int.from_bytes(body[offset + 4 : offset + 8], "little")
        chunk_end = offset + 8 + chunk_size
        if chunk_end > container_end:
            return False
        has_format = has_format or (chunk_id == b"fmt " and chunk_size >= 16)
        has_audio = has_audio or (chunk_id == b"data" and chunk_size > 0)
        offset = chunk_end + (chunk_size & 1)
    return has_format and has_audio


def _valid_flac(body: bytes) -> bool:
    if len(body) < 42 or not body.startswith(b"fLaC"):
        return False
    offset = 4
    saw_stream_info = False
    while offset + 4 <= len(body):
        header = body[offset]
        block_type = header & 0x7F
        block_length = int.from_bytes(body[offset + 1 : offset + 4], "big")
        offset += 4
        if offset + block_length > len(body):
            return False
        if block_type == 0:
            saw_stream_info = block_length == 34
        offset += block_length
        if header & 0x80:
            return saw_stream_info and offset + 2 <= len(body) and body[offset] == 0xFF
    return False


def _valid_ogg(body: bytes) -> bool:
    offset = 0
    pages = 0
    codec_seen = False
    end_seen = False
    while offset < len(body):
        if offset + 27 > len(body) or body[offset : offset + 4] != b"OggS" or body[offset + 4] != 0:
            return False
        segment_count = body[offset + 26]
        table_end = offset + 27 + segment_count
        if table_end > len(body):
            return False
        payload_length = sum(body[offset + 27 : table_end])
        page_end = table_end + payload_length
        if page_end > len(body):
            return False
        payload = body[table_end:page_end]
        if pages == 0:
            codec_seen = payload.startswith((b"OpusHead", b"\x01vorbis", b"fLaC"))
        end_seen = bool(body[offset + 5] & 0x04)
        pages += 1
        offset = page_end
    return codec_seen and pages >= 2 and end_seen


def _valid_mp4(body: bytes) -> bool:
    offset = 0
    box_types: set[bytes] = set()
    while offset + 8 <= len(body):
        box_size = int.from_bytes(body[offset : offset + 4], "big")
        box_type = body[offset + 4 : offset + 8]
        header_size = 8
        if box_size == 1:
            if offset + 16 > len(body):
                return False
            box_size = int.from_bytes(body[offset + 8 : offset + 16], "big")
            header_size = 16
        elif box_size == 0:
            box_size = len(body) - offset
        if box_size < header_size or offset + box_size > len(body):
            return False
        box_types.add(box_type)
        offset += box_size
    return offset == len(body) and {b"ftyp", b"moov", b"mdat"} <= box_types


def _valid_webm(body: bytes) -> bool:
    head = body[: 64 * 1024]
    return (
        len(body) >= 32
        and body.startswith(b"\x1aE\xdf\xa3")
        and b"webm" in head.lower()
        and (b"A_OPUS" in head or b"A_VORBIS" in head)
    )


def _has_valid_audio_structure(path: Path) -> bool:
    try:
        size = path.stat().st_size
        if size not in range(2, _MAX_CACHED_AUDIO_BYTES + 1):
            return False
        body = path.read_bytes()
    except OSError:
        return False
    validators: dict[str, Callable[[bytes], bool]] = {
        ".aac": _valid_aac,
        ".flac": _valid_flac,
        ".mp3": _valid_mp3,
        ".mp4": _valid_mp4,
        ".ogg": _valid_ogg,
        ".opus": _valid_ogg,
        ".wav": _valid_wav,
        ".webm": _valid_webm,
    }
    validator = validators.get(path.suffix.lower())
    # An unrecognised container is NOT evidence of non-audio: the desktop chain
    # stores whatever the configured source returns, so refusing an extension we
    # have no validator for would drop audio that works today.
    return validator(body) if validator is not None else True


def _ffprobe_accepts_audio(path: Path, ffprobe_path: Path, timeout: float) -> bool | None:
    try:
        completed = subprocess.run(
            [
                str(ffprobe_path),
                "-v",
                "error",
                "-select_streams",
                "a:0",
                "-show_entries",
                "stream=codec_type",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(path),
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired):
        # The probe itself failed, which says nothing about the payload.
        return None
    if completed.returncode != 0:
        return None
    return completed.stdout.strip() == b"audio"


class CustomAudioFetcher:
    """Fetches word pronunciation audio from a user-configured URL template.

    Conforms to the ``anki_miner.interfaces.ExpressionAudioFetcher`` Protocol
    structurally (never raises; returns Path or None).
    """

    def __init__(
        self,
        url_template: str,
        kind: str,
        cache_dir: Path,
        file_prefix: str,
        delay: float = 0.2,
        language: str = "ja",
        approved_audio_origins: Iterable[str] = (),
        deadline_seconds: float = _FETCH_DEADLINE_SECONDS,
        monotonic: Callable[[], float] = time.monotonic,
        ffprobe_path: Path | None = None,
        cache_lifetime: _RunAudioCache | None = None,
    ) -> None:
        """Initialize the fetcher.

        Args:
            url_template: The user's URL with ``{term}``/``{reading}``/
                ``{language}`` placeholders.
            kind: ``"custom"`` (direct audio) or ``"custom_json"`` (audioSourceList).
            cache_dir: Per-source cache directory (``custom_<slug>/`` under the
                approved local-pack staging root).
            file_prefix: Prefix for the cached Anki media filename; must be
                globally unique across sources (the builder uses ``custom_<slug>``).
            delay: Seconds to wait before the first network request per word.
            language: Value substituted for ``{language}`` (fixed "ja" here).
            approved_audio_origins: Exact remote HTTP(S) origins allowed for
                custom audio URLs and their redirects. Loopback remains allowed
                without listing it; all other origins fail closed.
        """
        # Function-local: the vendored toolkit imports through
        # ``anki_miner.services.audio_fetch_common``, which must not load before
        # bootstrap freezes ANKI_MINER_HOME.
        from anki_miner.services.audio_fetch_common import (
            new_browser_session,
            new_failure_counts,
        )

        self._url_template = url_template
        self._kind = kind
        self._cache_dir = cache_dir
        self._file_prefix = file_prefix
        # NaN must clamp to 0.0 (time.sleep(nan) raises); the >= comparison is
        # False for nan, so the else branch handles it.
        self._delay = delay if delay >= 0.0 else 0.0
        self._language = language
        self._session = new_browser_session()
        self._session.trust_env = False
        self._approved_audio_origins = frozenset(
            _normalize_approved_origin(origin) for origin in approved_audio_origins
        )
        if not math.isfinite(deadline_seconds) or deadline_seconds <= 0:
            raise ValueError("deadline_seconds must be finite and positive")
        self._deadline_seconds = deadline_seconds
        self._monotonic = monotonic
        self._ffprobe_path = ffprobe_path
        self._cache_lifetime = cache_lifetime or _RunAudioCache(cache_dir)
        self._owns_cache_lifetime = cache_lifetime is None
        self._download_session = _PolicyDownloadSession(self)
        self._attempt_count = 0
        self._candidate_budget_active = False
        self._policy_violations_raised = 0
        self._cancellations_raised = 0
        self._active_cancelled_check: Callable[[], bool] | None = None
        self._active_deadline: _RequestDeadline | None = None
        self._active_response: requests.Response | None = None
        self._active_response_lock = threading.Lock()
        self._failure_counts = new_failure_counts()
        self._failure_counts.update(dict.fromkeys(_BRIDGE_FAILURE_KEYS, 0))
        self._consecutive_transport_failures = 0
        self._circuit_open = False

    def _bump(self, key: str) -> None:
        self._failure_counts[key] += 1

    def _start_deadline(self) -> None:
        if self._active_deadline is not None:
            return
        self._active_deadline = _RequestDeadline(
            self._deadline_seconds,
            self._monotonic,
            self._expire_active_response,
        )

    def _finish_deadline(self) -> None:
        deadline = self._active_deadline
        self._active_deadline = None
        if deadline is not None:
            deadline.close()
        with self._active_response_lock:
            self._active_response = None

    def _expire_active_response(self) -> None:
        with self._active_response_lock:
            response = self._active_response
        if response is not None:
            with contextlib.suppress(Exception):
                response.close()

    def _track_response(self, response: requests.Response) -> None:
        with self._active_response_lock:
            self._active_response = response

    def _check_deadline(self) -> None:
        deadline = self._active_deadline
        if deadline is not None:
            deadline.check()

    def _remaining_timeout(self, requested: object) -> float:
        deadline = self._active_deadline
        if deadline is None:
            raise _DeadlineExceeded("expression audio deadline is not active")
        try:
            requested_seconds = float(requested)
        except (TypeError, ValueError):
            requested_seconds = 10.0
        return min(max(0.001, requested_seconds), deadline.remaining())

    def _deadline_expired(self) -> bool:
        deadline = self._active_deadline
        return deadline is not None and deadline.expired

    def _accept_audio(self, path: Path) -> bool:
        """Reject only payloads positively identified as non-audio.

        Anything we cannot classify is accepted, because the desktop expression
        audio chain stores whatever the configured source returns. Refusing an
        unfamiliar container here would drop audio that mines correctly today.
        """
        try:
            if path.stat().st_size not in range(2, _MAX_CACHED_AUDIO_BYTES + 1):
                return False
        except OSError:
            return False
        deadline = self._active_deadline
        if self._ffprobe_path is None or deadline is None:
            return _has_valid_audio_structure(path)
        timeout = min(_AUDIO_PROBE_TIMEOUT_SECONDS, deadline.remaining())
        probed = _ffprobe_accepts_audio(path, self._ffprobe_path, timeout)
        self._check_deadline()
        if probed is None:
            # The probe could not reach a verdict; fall back to structure.
            return _has_valid_audio_structure(path)
        return probed

    def _discard_non_audio(self, path: Path) -> None:
        self._cache_lifetime.discard(path)
        self._bump("non_audio")

    def _validate_url(self, url: str, *, directory_only: bool) -> None:
        origin = _parse_http_origin(url)
        if origin in _REVIEWED_LOOPBACK_ORIGINS:
            return
        if origin[1] in _LOOPBACK_HOSTS:
            raise _PolicyViolation("Loopback origin is not approved")
        if directory_only or origin not in self._approved_audio_origins:
            raise _PolicyViolation("URL origin is not approved")

    def _raise_policy_violation(self, detail: str) -> None:
        self._policy_violations_raised += 1
        raise _PolicyViolation(detail)

    def _get_with_policy(
        self,
        url: str,
        *,
        timeout: object,
        stream: bool,
        directory_only: bool,
        cancelled_check: Callable[[], bool] | None,
    ) -> requests.Response:
        """GET with a shared attempt budget and manual hop-by-hop redirects."""

        current = url
        seen: set[str] = set()
        redirects = 0
        while True:
            if cancelled_check is not None and cancelled_check():
                self._cancellations_raised += 1
                raise _CancelledRequest("request cancelled")
            try:
                self._validate_url(current, directory_only=directory_only)
            except _PolicyViolation as exc:
                self._raise_policy_violation(str(exc))
            if current in seen:
                self._raise_policy_violation("redirect loop rejected")
            seen.add(current)
            if self._attempt_count >= _MAX_TOTAL_ATTEMPTS:
                self._raise_policy_violation("request attempt limit reached")
            self._attempt_count += 1
            response = self._session.get(
                current,
                timeout=self._remaining_timeout(timeout),
                stream=stream,
                allow_redirects=False,
            )
            self._track_response(response)
            self._check_deadline()
            if response.status_code not in _REDIRECT_STATUSES:
                return response

            location = response.headers.get("Location")
            response.close()
            if not isinstance(location, str) or not location:
                self._raise_policy_violation("redirect location is invalid")
            if redirects >= _MAX_REDIRECTS:
                self._raise_policy_violation("redirect limit reached")
            try:
                next_url = urljoin(current, location)
            except ValueError as exc:
                self._raise_policy_violation("redirect URL is malformed")
                raise AssertionError("unreachable") from exc
            redirects += 1
            current = next_url

    def fetch(
        self,
        mined_form: str,
        reading: str,
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        """Fetch pronunciation audio for a word. Never raises.

        An empty/whitespace ``mined_form`` or ``reading`` skips the fetch: the
        reading feeds the URL template and disambiguates homographs, matching the
        empty-reading skip every other fetcher applies.
        """
        if not mined_form.strip() or not reading.strip():
            return None
        if cancelled_check is not None and cancelled_check():
            return None

        owns_deadline = self._active_deadline is None
        if owns_deadline:
            self._start_deadline()
        transport_before = self._transport_failure_count()
        result: Path | None = None
        try:
            result = self._fetch_with_active_deadline(mined_form, reading, cancelled_check)
            return result
        except _DeadlineExceeded:
            self._bump("timeout")
            return None
        finally:
            self._active_cancelled_check = None
            if owns_deadline:
                self._finish_deadline()
            self._note_transport_outcome(result, transport_before)

    def _transport_failure_count(self) -> int:
        return self._failure_counts["timeout"] + self._failure_counts["connection"]

    def _note_transport_outcome(self, result: Path | None, transport_before: int) -> None:
        """Track consecutive transport failures; open the circuit at the threshold.

        A miss that only bumped ``http_status``/``non_audio``/policy counters means
        the server answered — it neither trips nor resets the streak.
        """
        if result is not None:
            self._consecutive_transport_failures = 0
        elif self._transport_failure_count() > transport_before:
            self._consecutive_transport_failures += 1
            if self._consecutive_transport_failures >= _CIRCUIT_BREAKER_THRESHOLD:
                self._circuit_open = True

    def _fetch_with_active_deadline(
        self,
        mined_form: str,
        reading: str,
        cancelled_check: Callable[[], bool] | None,
    ) -> Path | None:
        from anki_miner.services.audio_fetch_common import (
            download_audio_to_cache,
            find_cached_by_stem,
        )
        from anki_miner.utils.file_utils import safe_filename

        self._check_deadline()
        stem = safe_filename(f"{self._file_prefix}_{mined_form}_{reading}")
        existing = find_cached_by_stem(self._cache_dir, stem)
        if existing is not None:
            if self._accept_audio(existing):
                return existing if self._cache_lifetime.pin(existing) else None
            else:
                self._discard_non_audio(existing)

        if self._circuit_open:
            # The server failed _CIRCUIT_BREAKER_THRESHOLD consecutive fetches on
            # transport; skip the network for the rest of the run (cache hits
            # above keep serving) so packs get their fallback chance immediately.
            self._bump("circuit_skipped")
            return None

        if cancelled_check is not None and cancelled_check():
            return None
        time.sleep(self._delay)
        self._check_deadline()
        if cancelled_check is not None and cancelled_check():
            return None

        if not self._candidate_budget_active:
            self._attempt_count = 0
        self._active_cancelled_check = cancelled_check
        endpoint = _substitute_custom_url(self._url_template, mined_form, reading, self._language)
        if self._kind == "custom_json":
            audio_urls = self._resolve_json_sources(endpoint, cancelled_check)
        else:
            audio_urls = [endpoint]

        for audio_url in audio_urls:
            if self._deadline_expired():
                return None
            if cancelled_check is not None and cancelled_check():
                return None
            policy_before = self._policy_violations_raised
            cancellations_before = self._cancellations_raised
            result = download_audio_to_cache(
                self._download_session,
                audio_url,
                self._cache_dir,
                stem,
                failure_counts=self._failure_counts,
            )
            policy_failures = self._policy_violations_raised - policy_before
            cancellations = self._cancellations_raised - cancellations_before
            if policy_failures:
                self._failure_counts["connection"] = max(
                    0,
                    self._failure_counts["connection"] - policy_failures,
                )
                self._failure_counts["policy_rejection"] += policy_failures
            if cancellations:
                self._failure_counts["connection"] = max(
                    0,
                    self._failure_counts["connection"] - cancellations,
                )
                return None
            if result is not None:
                if self._accept_audio(result):
                    return result if self._cache_lifetime.pin(result) else None
                else:
                    self._discard_non_audio(result)
        return None

    def _resolve_json_sources(
        self,
        url: str,
        cancelled_check: Callable[[], bool] | None = None,
    ) -> list[str]:
        """GET the custom-json endpoint and return its audio URLs (never raises).

        Manual shape check (no JSON-Schema engine): the document must be an
        object with ``type == "audioSourceList"`` and an ``audioSources`` list;
        each item contributes its string ``url`` (relative URLs normalized
        against the endpoint). Any malformed/failed response yields ``[]``.
        """
        if self._active_deadline is None:
            self._start_deadline()
            try:
                return self._resolve_json_sources(url, cancelled_check)
            finally:
                self._finish_deadline()
        try:
            response = self._get_with_policy(
                url,
                timeout=10,
                stream=True,
                directory_only=True,
                cancelled_check=cancelled_check,
            )
            try:
                if response.status_code != 200:
                    self._bump("http_status")
                    return []
                content_length = response.headers.get("Content-Length")
                if isinstance(content_length, str):
                    try:
                        if int(content_length) > _MAX_JSON_BYTES:
                            self._bump("oversized_response")
                            return []
                    except ValueError:
                        logger.debug("custom_json Content-Length is invalid", exc_info=True)
                chunks: list[bytes] = []
                total = 0
                for chunk in response.iter_content(chunk_size=8192):
                    self._check_deadline()
                    if cancelled_check is not None and cancelled_check():
                        return []
                    if not isinstance(chunk, bytes):
                        self._bump("malformed_json")
                        return []
                    total += len(chunk)
                    if total > _MAX_JSON_BYTES:
                        self._bump("oversized_response")
                        return []
                    if chunk:
                        chunks.append(chunk)
                self._check_deadline()
                body = b"".join(chunks)
                base = response.url
            finally:
                response.close()
        except _CancelledRequest:
            return []
        except _PolicyViolation:
            self._bump("policy_rejection")
            return []
        except (requests.RequestException, OSError) as exc:
            from anki_miner.services.audio_fetch_common import classify_request_exception

            self._bump(classify_request_exception(exc))
            logger.debug("custom_json directory request failed", exc_info=exc)
            return []

        try:
            data = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError, RecursionError):
            self._bump("malformed_json")
            return []

        if not isinstance(data, dict) or data.get("type") != "audioSourceList":
            self._bump("malformed_json")
            return []
        sources = data.get("audioSources")
        if not isinstance(sources, list):
            self._bump("malformed_json")
            return []
        if len(sources) > _MAX_AUDIO_SOURCES:
            self._bump("oversized_list")
            return []
        urls: list[str] = []
        malformed_members = False
        for item in sources:
            if not isinstance(item, dict):
                malformed_members = True
                continue
            candidate = item.get("url")
            if not isinstance(candidate, str) or not candidate:
                malformed_members = True
                continue
            try:
                if _url_size(candidate) > _MAX_URL_BYTES:
                    raise _PolicyViolation("source URL exceeds policy bounds")
                normalized = urljoin(base, candidate)
                self._validate_url(normalized, directory_only=False)
            except (ValueError, _PolicyViolation):
                self._bump("policy_rejection")
                continue
            urls.append(normalized)
        if malformed_members:
            self._bump("malformed_json")
        return urls

    def fetch_candidates(
        self,
        candidates: list[tuple[str, str]],
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        """Try each candidate form, returning the first custom-source hit."""
        from anki_miner.services.audio_fetch_common import first_candidate_hit

        self._attempt_count = 0
        self._candidate_budget_active = True
        owns_deadline = self._active_deadline is None
        if owns_deadline:
            self._start_deadline()
        try:
            return first_candidate_hit(self, candidates, cancelled_check)
        finally:
            self._candidate_budget_active = False
            self._active_cancelled_check = None
            if owns_deadline:
                self._finish_deadline()

    def stats(self) -> dict[str, int]:
        """Return a copy of this run's failure-cause counts (see FAILURE_KEYS)."""
        return dict(self._failure_counts)

    def close(self) -> None:
        """Close the underlying ``requests.Session`` (release the per-run socket)."""
        self._expire_active_response()
        self._finish_deadline()
        self._session.close()
        if self._owns_cache_lifetime:
            self._cache_lifetime.close()
