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
import re
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
_BRIDGE_FAILURE_KEYS = (
    "policy_rejection",
    "oversized_response",
    "oversized_list",
    "malformed_json",
)


class _PolicyViolation(requests.RequestException):
    """A privacy-safe network-policy rejection."""


class _CancelledRequest(requests.RequestException):
    """Internal signal for cancellation between bounded request hops."""


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
        for chunk in self._response.iter_content(*args, **kwargs):
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
        self._download_session = _PolicyDownloadSession(self)
        self._attempt_count = 0
        self._candidate_budget_active = False
        self._policy_violations_raised = 0
        self._cancellations_raised = 0
        self._active_cancelled_check: Callable[[], bool] | None = None
        self._failure_counts = new_failure_counts()
        self._failure_counts.update(dict.fromkeys(_BRIDGE_FAILURE_KEYS, 0))

    def _bump(self, key: str) -> None:
        self._failure_counts[key] += 1

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
                timeout=timeout,
                stream=stream,
                allow_redirects=False,
            )
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
        from anki_miner.services.audio_fetch_common import (
            download_audio_to_cache,
            find_cached_by_stem,
        )
        from anki_miner.utils.file_utils import safe_filename

        if not mined_form.strip() or not reading.strip():
            return None
        if cancelled_check is not None and cancelled_check():
            return None

        stem = safe_filename(f"{self._file_prefix}_{mined_form}_{reading}")
        existing = find_cached_by_stem(self._cache_dir, stem)
        if existing is not None:
            return existing

        if cancelled_check is not None and cancelled_check():
            return None
        time.sleep(self._delay)
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
            if cancelled_check is not None and cancelled_check():
                self._active_cancelled_check = None
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
                self._active_cancelled_check = None
                return None
            if result is not None:
                self._active_cancelled_check = None
                return result
        self._active_cancelled_check = None
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
                    with contextlib.suppress(ValueError):
                        if int(content_length) > _MAX_JSON_BYTES:
                            self._bump("oversized_response")
                            return []
                chunks: list[bytes] = []
                total = 0
                for chunk in response.iter_content(chunk_size=8192):
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
            logger.debug("custom_json directory request failed: %s", type(exc).__name__)
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
        try:
            return first_candidate_hit(self, candidates, cancelled_check)
        finally:
            self._candidate_budget_active = False
            self._active_cancelled_check = None

    def stats(self) -> dict[str, int]:
        """Return a copy of this run's failure-cause counts (see FAILURE_KEYS)."""
        return dict(self._failure_counts)

    def close(self) -> None:
        """Close the underlying ``requests.Session`` (release the per-run socket)."""
        self._session.close()
