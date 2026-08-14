"""Android-owned composition and execution of one local video mining run.

Optional data sources warn and disable themselves when they cannot load, so a
missing dictionary or word list degrades the run instead of failing it. That
rule stops at ``MemoryError``, and a phone reaches it far sooner than a desktop:
the wordset union alone is roughly 45 MiB across 480K entries. Swallowing an
allocation failure would silently drop the proper-noun filter the user
configured and keep writing cards from a memory-starved interpreter. Mirrors
``service_factory`` upstream, which re-raises at the same six catches.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from contextlib import ExitStack
from dataclasses import dataclass
from html import escape
from pathlib import Path
from urllib.parse import urlsplit

from .callbacks import CallbackAdapters
from .config_map import (
    _LOCALAUDIO_APPROVED_AUDIO_ORIGINS,
    _LOCALAUDIO_AUTHENTICATED_LOOPBACK_ORIGINS,
    AndroidPaths,
    map_config_settings,
)
from .faults import record_fault
from .jobs import JobRegistry, registry
from .protocol import (
    BridgeProtocolError,
    decode_message,
    encode_message,
    normalize_integral_json_number,
    to_json_value,
)
from .unicode_contract import (
    has_leading_or_trailing_python_whitespace,
    is_category_c,
    is_nfc,
)

logger = logging.getLogger(__name__)

_VIDEO_REQUEST_FIELDS = frozenset(
    {
        "videoPath",
        "subtitlePath",
        "episodeName",
        "seriesName",
        "sourceLabel",
        "audioTrackOverride",
        "audioOnly",
        "cacheDir",
        "nativeLibraryDir",
        "configSnapshot",
    }
)
_CONFIG_SNAPSHOT_FIELDS = frozenset({"settings", "androidTtsEnabled"})
_SUBTITLE_SUFFIXES = frozenset({".ass", ".srt", ".ssa", ".vtt"})
# Expression-audio kinds the Android builder can construct: imported local
# packs plus the URL-template custom sources (the localaudio localhost server).
# The cut network kinds (jpod101/googletts) are rejected before any allocation.
_SUPPORTED_EXPRESSION_AUDIO_KINDS = frozenset({"pack", "custom", "custom_json"})
_JISHO_TOTAL_DEADLINE_SECONDS = 10.0
_JISHO_IO_TIMEOUT_SECONDS = 1.0
_JISHO_WATCH_POLL_SECONDS = 0.05
_JISHO_BODY_CHUNK_BYTES = 8192


@dataclass(frozen=True, slots=True)
class _VideoRequest:
    video_path: Path
    subtitle_path: Path
    episode_name: str
    series_name: str
    source_label: str | None
    audio_track_override: int | None
    audio_only: bool
    cache_dir: Path
    native_library_dir: Path
    settings: Mapping[str, object]
    android_tts_enabled: bool | None


class _DiagnosedPackFetcher:
    """Counts outcomes for one wrapped ``LocalAudioPackFetcher``, privacy-safe.

    The vendored pack fetcher swallows sqlite open/lookup failures to a debug
    log and returns None, so a corrupt installed index is indistinguishable
    from an ordinary miss. This wrapper probes the index once after the first
    miss (via the same public storage API the fetcher uses) and exposes
    ``pack_stats()`` — deliberately NOT named ``stats()`` so the vendored
    ``audio_stage._diagnose`` and the localaudio-only summary path never pick
    it up. Only the pack id ever appears in diagnostics (no terms, no paths).
    """

    def __init__(self, fetcher: object, db_path: Path | None) -> None:
        self._fetcher = fetcher
        self._db_path = Path(db_path) if db_path is not None else None
        self._attempts = 0
        self._hits = 0
        self._index_unreadable: bool | None = None  # memoized probe result

    @property
    def pack_id(self) -> str:
        return self._fetcher.pack_id

    def fetch(
        self,
        mined_form: str,
        reading: str,
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        return self._delegate(lambda: self._fetcher.fetch(mined_form, reading, cancelled_check))

    def fetch_candidates(
        self,
        candidates: list[tuple[str, str]],
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        return self._delegate(lambda: self._fetcher.fetch_candidates(candidates, cancelled_check))

    def _delegate(self, call: Callable[[], Path | None]) -> Path | None:
        self._attempts += 1
        path = call()
        if path is not None:
            self._hits += 1
        elif self._index_unreadable is None:
            self._index_unreadable = self._probe_once()
        return path

    # storage._LOOKUP_SQL, replicated: importing anki_miner.services here would
    # pull requests via the package __init__, breaking the requests-free lane.
    _PROBE_SQL = (
        "SELECT file, source, speaker, reading FROM entries "
        "WHERE expression = ? AND (? = '' OR reading IS NULL OR reading = ?) "
        "ORDER BY id"
    )

    def _probe_once(self) -> bool:
        """Return True when the pack's sqlite index cannot be opened/queried.

        Same read-only URI open and lookup shape as
        ``anki_miner.services.audio_packs.storage`` (open_readonly + lookup),
        stdlib-only.
        """
        import sqlite3

        if self._db_path is None:
            return False
        try:
            uri = self._db_path.resolve().as_uri() + "?mode=ro"
            conn = sqlite3.connect(uri, uri=True)
            try:
                conn.execute("PRAGMA query_only=ON")
                conn.execute(self._PROBE_SQL, ("猫", "ねこ", "ねこ")).fetchone()
            finally:
                conn.close()
        except (sqlite3.Error, OSError):
            return True
        return False

    def close(self) -> None:
        close = getattr(self._fetcher, "close", None)
        if callable(close):
            close()

    def pack_stats(self) -> dict[str, int]:
        return {
            "attempts": self._attempts,
            "hits": self._hits,
            "index_unreadable": 1 if self._index_unreadable else 0,
        }


class _ExpressionAudioSourceChain:
    """Source-priority composite over the ordered expression-audio fetchers.

    Members follow config order: the injected localaudio (localhost) source
    first, then any imported local packs as fallback. Each member is tried in
    turn; the first hit wins. A member raising is logged and skipped so a down
    localaudio server falls through to the packs.
    """

    def __init__(
        self,
        fetchers: Sequence[object],
        *,
        localaudio_fetcher: object | None = None,
        fallback_fetchers: Sequence[object] = (),
        diagnostic_callback: Callable[[str], None] | None = None,
        cache_lifetime: object | None = None,
        unavailable_pack_ids: Sequence[str] = (),
    ) -> None:
        self._fetchers = tuple(fetchers)
        self._localaudio_fetcher = localaudio_fetcher
        self._fallback_fetchers = tuple(fallback_fetchers)
        self._diagnostic_callback = diagnostic_callback
        self._fallback_hits = 0
        self._diagnostic_reported = False
        self._cache_lifetime = cache_lifetime
        self._unavailable_pack_ids = tuple(unavailable_pack_ids)

    def _record_fallback_hit(self, fetcher: object) -> None:
        if any(fetcher is fallback for fallback in self._fallback_fetchers):
            self._fallback_hits += 1

    def _localaudio_counts(self) -> dict | None:
        if self._localaudio_fetcher is None:
            return None
        stats = getattr(self._localaudio_fetcher, "stats", None)
        if not callable(stats):
            return None
        try:
            counts = stats()
        except Exception:
            return None
        return counts if isinstance(counts, dict) else None

    def _diagnostic_summary(self) -> str | None:
        details: list[str] = []
        counts = self._localaudio_counts()
        if counts is not None:
            unavailable = sum(int(counts.get(key, 0)) for key in ("ssl", "connection", "http_status"))
            fields = (
                ("localaudio unavailable", unavailable),
                ("timeouts", int(counts.get("timeout", 0))),
                ("rejected sources", int(counts.get("policy_rejection", 0))),
                ("oversized responses", int(counts.get("oversized_response", 0))),
                ("oversized lists", int(counts.get("oversized_list", 0))),
                ("malformed JSON", int(counts.get("malformed_json", 0))),
                ("non-audio responses", int(counts.get("non_audio", 0))),
                ("localaudio skipped after repeated failures", int(counts.get("circuit_skipped", 0))),
                ("fallback pack hits", self._fallback_hits),
            )
            details.extend(f"{label}={count}" for label, count in fields if count > 0)
        for fetcher in self._fallback_fetchers:
            pack_stats = getattr(fetcher, "pack_stats", None)
            if not callable(pack_stats):
                continue
            try:
                stats = pack_stats()
            except Exception:
                continue
            if stats.get("index_unreadable") and not stats.get("hits") and stats.get("attempts"):
                details.append(f"pack '{fetcher.pack_id}' index unreadable ({stats['attempts']} lookups failed)")
        if self._unavailable_pack_ids:
            details.append("enabled packs unavailable: " + ", ".join(sorted(self._unavailable_pack_ids)))
        return f"Expression audio: {'; '.join(details)}" if details else None

    def fetch(
        self,
        mined_form: str,
        reading: str,
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        for fetcher in self._fetchers:
            if cancelled_check is not None and cancelled_check():
                return None
            try:
                path = fetcher.fetch(mined_form, reading, cancelled_check)
            except MemoryError:
                raise
            except Exception:
                logger.exception("Expression-audio source fetch failed")
                continue
            if path is not None:
                pin = getattr(self._cache_lifetime, "pin", None)
                if callable(pin) and not pin(path):
                    continue
                self._record_fallback_hit(fetcher)
                return path
        return None

    def fetch_candidates(
        self,
        candidates: list[tuple[str, str]],
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        for fetcher in self._fetchers:
            if cancelled_check is not None and cancelled_check():
                return None
            try:
                path = fetcher.fetch_candidates(candidates, cancelled_check)
            except MemoryError:
                raise
            except Exception:
                logger.exception("Expression-audio source fetch failed")
                continue
            if path is not None:
                pin = getattr(self._cache_lifetime, "pin", None)
                if callable(pin) and not pin(path):
                    continue
                self._record_fallback_hit(fetcher)
                return path
        return None

    def close(self) -> None:
        if not self._diagnostic_reported:
            self._diagnostic_reported = True
            summary = self._diagnostic_summary()
            if summary is not None and self._diagnostic_callback is not None:
                try:
                    self._diagnostic_callback(summary)
                except Exception:
                    logger.exception("Failed to report expression-audio diagnostic")
        for fetcher in self._fetchers:
            close = getattr(fetcher, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:
                    logger.debug("Expression-audio source close failed", exc_info=True)
        close_lifetime = getattr(self._cache_lifetime, "close", None)
        if callable(close_lifetime):
            try:
                close_lifetime()
            except Exception:
                logger.debug("Expression-audio cache lifetime close failed", exc_info=True)


class _PostProcessCleanupError(Exception):
    """Cleanup failed after the engine had already produced a terminal result."""

    def __init__(self, result: object) -> None:
        super().__init__("post-process cleanup failed")
        self.result = result


def _invalid_request(detail: str) -> BridgeProtocolError:
    return BridgeProtocolError("invalid_video_mining_request", detail)


def _absolute_path(field_name: str, value: object) -> Path:
    if not isinstance(value, str) or not value or "\x00" in value:
        raise _invalid_request(f"{field_name} must be a non-empty path string")
    path = Path(value)
    if not path.is_absolute():
        raise _invalid_request(f"{field_name} must be an absolute path")
    return path


def _canonical_label(field_name: str, value: object) -> str:
    if not isinstance(value, str) or not value:
        raise _invalid_request(f"{field_name} must be a non-empty string")
    if has_leading_or_trailing_python_whitespace(value):
        raise _invalid_request(f"{field_name} must not have leading or trailing whitespace")
    if not is_nfc(value):
        raise _invalid_request(f"{field_name} must use NFC Unicode normalization")
    if any(is_category_c(ord(character)) for character in value):
        raise _invalid_request(f"{field_name} contains a forbidden character")
    return value


def _optional_source_label(value: object) -> str | None:
    if value is None:
        return None
    return _canonical_label("sourceLabel", value)


def _optional_audio_track(value: object) -> int | None:
    if value is None:
        return None
    converted = normalize_integral_json_number(value)
    if converted is None or converted < 0:
        raise _invalid_request("audioTrackOverride must be a non-negative integer or null")
    return converted


def _parse_request(raw_request: str) -> _VideoRequest:
    payload = decode_message(raw_request, expected_type="mining.video.run")
    if set(payload) != _VIDEO_REQUEST_FIELDS:
        raise _invalid_request(f"Expected payload fields: {sorted(_VIDEO_REQUEST_FIELDS)!r}")

    snapshot = payload["configSnapshot"]
    if not isinstance(snapshot, dict) or not set(snapshot).issubset(_CONFIG_SNAPSHOT_FIELDS):
        raise _invalid_request("configSnapshot fields are invalid")
    if "settings" not in snapshot or not isinstance(snapshot["settings"], dict):
        raise _invalid_request("configSnapshot.settings must be an object")

    android_tts_enabled = snapshot.get("androidTtsEnabled")
    if "androidTtsEnabled" in snapshot and type(android_tts_enabled) is not bool:
        raise _invalid_request("configSnapshot.androidTtsEnabled must be a boolean")

    audio_only = payload["audioOnly"]
    if type(audio_only) is not bool:
        raise _invalid_request("audioOnly must be a boolean")

    subtitle_path = _absolute_path("subtitlePath", payload["subtitlePath"])
    if subtitle_path.suffix.lower() not in _SUBTITLE_SUFFIXES:
        raise _invalid_request("subtitlePath must preserve a supported subtitle filename suffix")

    return _VideoRequest(
        video_path=_absolute_path("videoPath", payload["videoPath"]),
        subtitle_path=subtitle_path,
        episode_name=_canonical_label("episodeName", payload["episodeName"]),
        series_name=_canonical_label("seriesName", payload["seriesName"]),
        source_label=_optional_source_label(payload["sourceLabel"]),
        audio_track_override=_optional_audio_track(payload["audioTrackOverride"]),
        audio_only=audio_only,
        cache_dir=_absolute_path("cacheDir", payload["cacheDir"]),
        native_library_dir=_absolute_path("nativeLibraryDir", payload["nativeLibraryDir"]),
        settings=dict(snapshot["settings"]),
        android_tts_enabled=android_tts_enabled,
    )


def _ensure_runtime_ready() -> Path:
    """Fail before admission unless every process-global prerequisite is fixed."""

    from .bootstrap import require_initialized
    from .tokenizer_selection import selected_tokenizer_backend
    from .unidic_resource import require_registered_unidic

    home = Path(require_initialized())
    require_registered_unidic()
    if selected_tokenizer_backend() != "s1a":
        raise BridgeProtocolError(
            "tokenizer_configuration_required",
            "The selected Android S1a tokenizer must be configured before mining",
        )
    return home


def _map_config(request: _VideoRequest, files_dir: Path) -> object:
    return map_config_settings(
        request.settings,
        AndroidPaths(
            files_dir=files_dir,
            cache_dir=request.cache_dir,
            native_library_dir=request.native_library_dir,
        ),
        android_tts_enabled=request.android_tts_enabled,
    ).engine_config


def _show_optional_failure(
    presenter: object,
    message: str,
    error: Exception,
    *,
    service: str,
) -> None:
    logger.warning("optional_service_failed service=%s message=%s", service, message, exc_info=error)
    presenter.show_warning(f"{message}: {error}")


def _close_without_masking(resource: object | None, label: str) -> None:
    if resource is None:
        return
    close = getattr(resource, "close", None)
    if not callable(close):
        return
    try:
        close()
    except BaseException:
        logger.exception("Failed to close %s while preserving the primary failure", label)


def _build_expression_audio_source_chain(
    config: object,
    diagnostic_callback: Callable[[str], None] | None = None,
) -> _ExpressionAudioSourceChain | None:
    """Compose the ordered expression-audio source chain for one run.

    Mirrors the desktop ``_build_expression_audio_fetcher`` composition minus the
    cut network kinds. Config order is source priority: the injected localaudio
    (localhost) custom_json source is the default primary; imported local packs
    follow as fallback.
    """

    entries = tuple(getattr(config, "expression_audio_chain", ()))

    # Reject-before-allocate: only the cut network kinds (jpod101/googletts)
    # raise, and BEFORE any Session/import/packs-dir scan. pack/custom/custom_json
    # are supported. Validate every entry first so a bad kind cannot slip through
    # after a valid one has already allocated resources.
    if any(getattr(entry, "kind", None) not in _SUPPORTED_EXPRESSION_AUDIO_KINDS for entry in entries):
        raise BridgeProtocolError(
            "unsupported_android_feature",
            "Android expression audio supports local packs and on-device local audio only",
        )

    # Two-part fetch-gate mirror (audio_stage.py): the expression_audio Anki
    # field must be mapped and at least one entry enabled, else the fetcher is
    # never consulted and building it would be wasted I/O.
    if not getattr(config, "anki_fields", {}).get("expression_audio") or not any(
        getattr(entry, "enabled", False) for entry in entries
    ):
        return None

    # Function-local imports are load-bearing: bootstrap and tokenizer resource
    # registration must precede every engine service import. The bridge fetcher
    # pulls ``requests`` at its module top, so it is imported here (never at
    # mining.py's top) to keep the requests-free host test lane importing
    # ``mining`` cleanly.
    from anki_miner.config.paths import ANKI_MINER_HOME
    from anki_miner.services.audio_packs.registry import AudioPackRegistry

    from .expression_audio_fetcher import (
        CustomAudioFetcher,
        _RunAudioCache,
        custom_audio_slug,
    )

    # Nesting the custom cache UNDER the approved LOCAL_AUDIO_CACHE_ROOT
    # (audio_cache/local_packs) keeps the localaudio download inside the
    # already-approved media-staging prefix (startsWith approval), so bug 7 is
    # independent of the media-staging approval boundary.
    cache_root = ANKI_MINER_HOME / "audio_cache" / "local_packs"
    cache_lifetime = _RunAudioCache(cache_root)

    # Lazily scan the packs dir only when an enabled pack entry is present.
    # Each resolved fetcher is wrapped so pack lookup failures become visible
    # in the run summary instead of dying in the vendored fetcher's debug log.
    pack_fetchers_by_id: dict[str, object] = {}
    if any(getattr(entry, "kind", None) == "pack" and getattr(entry, "enabled", False) for entry in entries):
        pack_registry = AudioPackRegistry(config.audio_packs_root)
        pack_registry.load()
        metas = getattr(pack_registry, "packs", None)
        if not isinstance(metas, dict):
            metas = {}
        for pack_fetcher in pack_registry.build_fetcher_chain(config, cache_root):
            meta = metas.get(pack_fetcher.pack_id)
            db_path = getattr(meta, "db_path", None) if meta is not None else None
            pack_fetchers_by_id[pack_fetcher.pack_id] = _DiagnosedPackFetcher(pack_fetcher, db_path)

    # Config order = source priority. This loop never raises: an empty-url custom
    # entry or an unknown/missing pack is skipped (matching desktop), so combined
    # with the validate-all-first check the reject-before-allocate invariant holds.
    fetchers: list[object] = []
    localaudio_fetcher: object | None = None
    fallback_fetchers: list[object] = []
    unavailable_pack_ids: list[str] = []
    for entry in entries:
        if not getattr(entry, "enabled", False):
            continue
        kind = getattr(entry, "kind", None)
        if kind in ("custom", "custom_json"):
            url = getattr(entry, "url", None)
            if not url:
                continue
            slug = custom_audio_slug(url)
            custom_fetcher = CustomAudioFetcher(
                url_template=url,
                kind=kind,
                cache_dir=cache_root / f"custom_{slug}",
                file_prefix=f"custom_{slug}",
                # Loopback needs no throttle; delay is timing-only and never
                # affects fetched bytes, so desktop OUTPUT parity holds.
                delay=0.0,
                approved_audio_origins=_LOCALAUDIO_APPROVED_AUDIO_ORIGINS,
                authenticated_loopback_origins=_LOCALAUDIO_AUTHENTICATED_LOOPBACK_ORIGINS,
                ffprobe_path=getattr(config, "ffprobe_location", None),
                cache_lifetime=cache_lifetime,
            )
            fetchers.append(custom_fetcher)
            if kind == "custom_json":
                localaudio_fetcher = custom_fetcher
        elif kind == "pack":
            pack_id = getattr(entry, "pack_id", None)
            if pack_id is None:
                continue
            resolved = pack_fetchers_by_id.get(pack_id)
            if resolved is None:
                # Enabled in config but not resolvable on disk (corrupt index
                # skipped at scan, stale schema, missing content dir) — report
                # the id in the run summary instead of vanishing silently.
                unavailable_pack_ids.append(pack_id)
                continue
            fetchers.append(resolved)
            fallback_fetchers.append(resolved)

    return _ExpressionAudioSourceChain(
        fetchers,
        localaudio_fetcher=localaudio_fetcher,
        fallback_fetchers=fallback_fetchers,
        diagnostic_callback=diagnostic_callback,
        cache_lifetime=cache_lifetime,
        unavailable_pack_ids=unavailable_pack_ids,
    )


def _new_jisho_session() -> object:
    """Create the run-owned HTTP session after bridge bootstrap."""

    import requests

    return requests.Session()


def _is_https_endpoint(url: str) -> bool:
    try:
        parsed = urlsplit(url)
        return (
            parsed.scheme.lower() == "https"
            and parsed.hostname is not None
            and parsed.username is None
            and parsed.password is None
        )
    except ValueError:
        return False


class _AndroidOnlineDictionaryProvider:
    """Run-owned, cancellable, HTTPS-only Jisho transport and memoizer."""

    def __init__(
        self,
        provider: object,
        cancelled_check: Callable[[], bool],
    ) -> None:
        self._provider = provider
        self._cancelled_check = cancelled_check
        self._cache: dict[str, str | None] = {}
        self._api_url = str(getattr(provider, "_api_url", ""))
        self._delay = max(0.0, float(getattr(provider, "_delay", 0.0)))
        self._session: object | None = None
        self._active_response: object | None = None
        self._active_response_lock = threading.Lock()
        self._opening_request: threading.Thread | None = None

    @property
    def name(self) -> str:
        return str(self._provider.name)

    @property
    def is_online(self) -> bool:
        return True

    def is_available(self) -> bool:
        return bool(self._provider.is_available())

    def load(self) -> bool:
        return bool(self._provider.load())

    def _wait_for_delay(self) -> bool:
        delay_end = time.monotonic() + self._delay
        while True:
            if self._cancelled_check():
                return False
            remaining = delay_end - time.monotonic()
            if remaining <= 0:
                return True
            time.sleep(min(_JISHO_WATCH_POLL_SECONDS, remaining))

    def _session_for_lookup(self) -> object:
        if self._session is None:
            self._session = _new_jisho_session()
        return self._session

    def _close_session(self, session: object) -> None:
        if self._session is session:
            self._session = None
        close = getattr(session, "close", None)
        if callable(close):
            try:
                close()
            except Exception:
                logger.debug("Jisho session close failed", exc_info=True)

    def _track_response(self, response: object | None) -> None:
        with self._active_response_lock:
            self._active_response = response

    def _close_active_response(self) -> bool:
        with self._active_response_lock:
            response = self._active_response
        if response is None:
            return False
        close = getattr(response, "close", None)
        if callable(close):
            try:
                close()
            except Exception:
                logger.debug("Jisho response close failed", exc_info=True)
        return True

    def _watch_request(
        self,
        deadline: float,
        finished: threading.Event,
        aborted: threading.Event,
    ) -> None:
        while not finished.wait(_JISHO_WATCH_POLL_SECONDS):
            if not self._cancelled_check() and time.monotonic() < deadline:
                continue
            aborted.set()
            if self._close_active_response():
                return

    def _open_response(
        self,
        word: str,
        deadline: float,
        aborted: threading.Event,
    ) -> object | None:
        """Open a streamed response without parking the mining thread in headers."""

        if self._opening_request is not None and self._opening_request.is_alive():
            return None
        session = self._session_for_lookup()
        completed = threading.Event()
        responses: list[object] = []
        failures: list[BaseException] = []

        def open_request() -> None:
            try:
                timeout = min(
                    _JISHO_IO_TIMEOUT_SECONDS,
                    max(0.001, deadline - time.monotonic()),
                )
                response = session.get(
                    self._api_url,
                    params={"keyword": word},
                    timeout=(timeout, timeout),
                    stream=True,
                    allow_redirects=False,
                )
                responses.append(response)
                if aborted.is_set():
                    close = getattr(response, "close", None)
                    if callable(close):
                        close()
            except BaseException as error:
                failures.append(error)
            finally:
                completed.set()

        opener = threading.Thread(
            target=open_request,
            daemon=True,
            name="anki-miner-jisho-open",
        )
        self._opening_request = opener
        opener.start()
        while not completed.wait(_JISHO_WATCH_POLL_SECONDS):
            if not self._cancelled_check() and time.monotonic() < deadline:
                continue
            aborted.set()
            self._close_session(session)
            opener.join(_JISHO_WATCH_POLL_SECONDS * 2)
            return None

        opener.join()
        if self._cancelled_check() or time.monotonic() >= deadline:
            aborted.set()
            self._close_session(session)
            if responses:
                close = getattr(responses[0], "close", None)
                if callable(close):
                    close()
            return None
        if failures:
            raise failures[0]
        return responses[0]

    @staticmethod
    def _render_response(body: bytes, provider_name: str) -> str | None:
        data = json.loads(body.decode("utf-8"))
        results = data.get("data", [])
        if not results:
            return None

        first = results[0]
        senses = []
        for sense in first.get("senses", [])[:5]:
            definitions = sense.get("english_definitions", [])
            if definitions:
                senses.append("; ".join(escape(str(definition)) for definition in definitions))
        if not senses:
            return None

        items = "".join(f'<li class="gloss-item"><div class="gloss-content">{sense}</div></li>' for sense in senses)
        safe_name = escape(provider_name)
        return (
            '<div class="yomitan-glossary">'
            '<ol data-count="1">'
            f'<li data-dictionary="{safe_name}">'
            f"<i>({safe_name})</i>"
            f'<ul class="gloss-list" data-count="{len(senses)}">{items}</ul>'
            "</li>"
            "</ol>"
            "</div>"
        )

    def _lookup_uncached(self, word: str) -> str | None:
        if not _is_https_endpoint(self._api_url):
            logger.warning("Jisho request rejected because endpoint is not HTTPS")
            return None
        if not self._wait_for_delay():
            return None

        deadline = time.monotonic() + _JISHO_TOTAL_DEADLINE_SECONDS
        aborted = threading.Event()
        response: object | None = None
        finished: threading.Event | None = None
        watcher: threading.Thread | None = None
        try:
            response = self._open_response(word, deadline, aborted)
            if response is None:
                return None
            self._track_response(response)
            if aborted.is_set() or self._cancelled_check() or time.monotonic() >= deadline:
                return None
            finished = threading.Event()
            watcher = threading.Thread(
                target=self._watch_request,
                args=(deadline, finished, aborted),
                daemon=True,
                name="anki-miner-jisho-watch",
            )
            watcher.start()
            if getattr(response, "status_code", None) != 200:
                return None

            chunks: list[bytes] = []
            iterator = response.iter_content(chunk_size=_JISHO_BODY_CHUNK_BYTES)
            for chunk in iterator:
                if aborted.is_set() or self._cancelled_check() or time.monotonic() >= deadline:
                    return None
                if not isinstance(chunk, bytes):
                    return None
                if chunk:
                    chunks.append(chunk)
            if aborted.is_set() or self._cancelled_check() or time.monotonic() >= deadline:
                return None
            return self._render_response(b"".join(chunks), self.name)
        except MemoryError:
            raise
        except (OSError, ValueError, KeyError, UnicodeDecodeError) as error:
            logger.debug("Jisho lookup failed", exc_info=error)
            return None
        finally:
            if finished is not None:
                finished.set()
            if response is not None:
                self._close_active_response()
                self._track_response(None)
            if watcher is not None:
                watcher.join(_JISHO_WATCH_POLL_SECONDS * 2)

    def lookup(self, word: str) -> str | None:
        if word in self._cache:
            return self._cache[word]
        if self._cancelled_check():
            return None
        self._cache[word] = None
        result = self._lookup_uncached(word)
        if self._cancelled_check():
            return None
        self._cache[word] = result
        return result

    def close(self) -> None:
        self._close_active_response()
        session = self._session
        if session is not None:
            self._close_session(session)
        closer = getattr(self._provider, "close", None)
        if callable(closer):
            closer()


def _android_dictionary_provider_chain(
    providers: list[object],
    cancelled_check: Callable[[], bool],
) -> list[object]:
    return [
        (
            _AndroidOnlineDictionaryProvider(provider, cancelled_check)
            if getattr(provider, "is_online", False)
            else provider
        )
        for provider in providers
    ]


def _build_processor(
    config: object,
    adapters: CallbackAdapters,
    anki_adapter: object,
    *,
    sentence_audio_fetcher: object | None = None,
) -> object:
    """Mirror the desktop service factory without importing cut fetchers."""

    # These imports intentionally name only the vendored execution closure.
    # Importing desktop gui.utils.service_factory would eagerly pull YouTube and
    # network TTS implementations which are outside the Android product.
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.services.definition_service import DefinitionService
    from anki_miner.services.dictionary.registry import DictionaryRegistry
    from anki_miner.services.frequency.multi_frequency_service import (
        MultiFrequencyService,
    )
    from anki_miner.services.frequency.registry import FrequencySourceRegistry
    from anki_miner.services.known_word_db import KnownWordDB
    from anki_miner.services.media_extractor import MediaExtractorService
    from anki_miner.services.pitch_accent.multi_pitch_service import (
        MultiPitchAccentService,
    )
    from anki_miner.services.pitch_accent.registry import PitchSourceRegistry
    from anki_miner.services.stats_service import StatsService
    from anki_miner.services.subtitle_parser import SubtitleParserService
    from anki_miner.services.word_filter import WordFilterService
    from anki_miner.services.word_list_service import WordListService
    from anki_miner.services.wordset_service import WordsetService

    definition_service: object | None = None
    frequency_service: object | None = None
    expression_audio_fetcher: _ExpressionAudioSourceChain | None = None
    try:
        dictionary_registry = DictionaryRegistry(config.dicts_root)
        try:
            dictionary_registry.load()
        except OSError as error:
            _show_optional_failure(
                adapters.presenter,
                "Couldn't scan dictionaries folder",
                error,
                service="dictionary_registry",
            )
        providers = _android_dictionary_provider_chain(
            dictionary_registry.build_provider_chain(config),
            adapters.cancel_event.is_set,
        )
        # registry= is load-bearing, not decoration: the processor's pre-flight
        # check_offline_dictionary asks has_usable_offline_provider, which
        # returns False for a registry-less service and aborts the whole run.
        definition_service = DefinitionService(config, providers=providers, registry=dictionary_registry)

        has_indexed_dictionary = any(entry.kind == "indexed" and entry.enabled for entry in config.dictionary_chain)
        if has_indexed_dictionary:
            try:
                definition_service.ensure_loaded()
            except MemoryError:
                raise  # never an optional-source miss; see the module note
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter,
                    "Couldn't load dictionary chain",
                    error,
                    service="dictionary_chain",
                )

        subtitle_parser = SubtitleParserService(
            config,
            term_lookup=(definition_service.offline_terms_exist if has_indexed_dictionary else None),
            reading_lookup=(definition_service.offline_term_readings if has_indexed_dictionary else None),
            kana_attest_lookup=(definition_service.has_offline_definitions if has_indexed_dictionary else None),
            term_common_lookup=(definition_service.offline_term_commonness if has_indexed_dictionary else None),
        )
        word_filter = WordFilterService(config, tagger=subtitle_parser.tagger)
        media_extractor = MediaExtractorService(config)
        expression_audio_fetcher = _build_expression_audio_source_chain(
            config,
            diagnostic_callback=adapters.presenter.show_warning,
        )

        pitch_accent_service = None
        if config.pitch_active:
            try:
                pitch_registry = PitchSourceRegistry(config.pitch_root)
                pitch_registry.load()
                pitch_providers = [p for p in pitch_registry.build_sources(config) if p.load()]
                if pitch_providers:
                    pitch_accent_service = MultiPitchAccentService(pitch_providers)
                else:
                    # An enabled chain entry can still point at a missing or
                    # unreadable on-disk index. Not an error; pitch fields stay
                    # empty for the run.
                    adapters.presenter.show_warning("No pitch accent source could be loaded")
            except MemoryError:
                raise  # never an optional-source miss; see the module note
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter,
                    "Couldn't load pitch accent data",
                    error,
                    service="pitch_accent",
                )
                pitch_accent_service = None

        if config.frequency_active:
            frequency_providers: list[object] = []
            candidates: list[object] = []
            try:
                frequency_registry = FrequencySourceRegistry(config.freqs_root)
                frequency_registry.load()
                candidates = frequency_registry.build_sources(config)
                for provider in candidates:
                    if provider.load():
                        frequency_providers.append(provider)
                if frequency_providers:
                    frequency_service = MultiFrequencyService(frequency_providers)
            except MemoryError:
                raise  # never an optional-source miss; see the module note
            except Exception as error:
                for provider in candidates:
                    _close_without_masking(provider, "frequency provider")
                _show_optional_failure(
                    adapters.presenter,
                    "Couldn't load frequency data",
                    error,
                    service="frequency",
                )
                frequency_service = None

        try:
            known_word_db = KnownWordDB(config.known_words_db_path)
            if config.use_known_words_db:
                known_word_db.initialize()
        except MemoryError:
            raise  # never an optional-source miss; see the module note
        except Exception as error:
            _show_optional_failure(
                adapters.presenter,
                "Couldn't initialize known word database",
                error,
                service="known_words",
            )
            known_word_db = None

        word_list_service = None
        if config.use_blacklist or config.use_whitelist:
            try:
                word_list_service = WordListService(
                    blacklist_path=(config.blacklist_path if config.use_blacklist else None),
                    whitelist_path=(config.whitelist_path if config.use_whitelist else None),
                )
                word_list_service.load()
            except MemoryError:
                raise  # never an optional-source miss; see the module note
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter,
                    "Couldn't load word lists",
                    error,
                    service="word_lists",
                )
                word_list_service = None

        wordset_service = None
        if config.excluded_wordsets:
            try:
                wordset_service = WordsetService(enabled_ids=config.excluded_wordsets)
                wordset_service.load()
                if not wordset_service.is_available():
                    wordset_service = None
            except MemoryError:
                raise  # never an optional-source miss; see the module note
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter,
                    "Couldn't load name wordsets",
                    error,
                    service="name_wordsets",
                )
                wordset_service = None

        stats_service = StatsService(config.stats_db_path)
        if not stats_service.load():
            stats_service = None

        return EpisodeProcessor(
            config=config,
            subtitle_parser=subtitle_parser,
            word_filter=word_filter,
            media_extractor=media_extractor,
            definition_service=definition_service,
            anki_service=anki_adapter,
            presenter=adapters.presenter,
            pitch_accent_service=pitch_accent_service,
            frequency_service=frequency_service,
            known_word_db=known_word_db,
            word_list_service=word_list_service,
            wordset_service=wordset_service,
            stats_service=stats_service,
            youtube_fetcher=None,
            expression_audio_fetcher=expression_audio_fetcher,
            dictionary_registry=dictionary_registry,
            sentence_audio_fetcher=sentence_audio_fetcher,
        )
    except BaseException:
        _close_without_masking(expression_audio_fetcher, "expression-audio chain")
        _close_without_masking(frequency_service, "frequency service")
        _close_without_masking(definition_service, "definition service")
        raise


def _process_episode(
    request: _VideoRequest,
    config: object,
    adapters: CallbackAdapters,
) -> object:
    """Construct one fresh processor, invoke the desktop entry point, clean up."""

    from .anki_adapter import AndroidAnkiAdapter, AnkiOperationCancelled

    if adapters.cancel_event.is_set():
        raise AnkiOperationCancelled("runVideo", "Mining was cancelled", False)

    stack = ExitStack()
    try:
        anki_adapter = stack.enter_context(
            AndroidAnkiAdapter(
                config,
                adapters.anki,
                cancellation_check=adapters.cancel_event.is_set,
                # process_episode composes the card source field as
                # "<series> — <episode>", but Android's series is a synthetic
                # lane label ("Local video"): SAF hands over a display name,
                # never a parent folder. Strip it back off at the seam.
                source_prefix=f"{request.series_name} — ".lstrip(),
            )
        )
        processor = _build_processor(config, adapters, anki_adapter)
        stack.callback(processor.close)
        from .definitions import clear_run_dictionaries, register_run_dictionaries

        register_run_dictionaries(adapters.run_id, config)
        try:
            result = processor.process_episode(
                request.video_path,
                request.subtitle_path,
                progress_callback=adapters.progress,
                curation_callback=adapters.curate,
                cross_episode_counts=None,
                episode_name_override=request.episode_name,
                series_name_override=request.series_name,
                audio_track_override=request.audio_track_override,
                source_label_override=request.source_label,
                audio_only=request.audio_only,
                cancel_event=adapters.cancel_event,
            )
        finally:
            clear_run_dictionaries(adapters.run_id)
    except BaseException:
        try:
            stack.close()
        except BaseException:
            logger.exception("Mining cleanup failed while preserving the primary failure")
        raise
    try:
        stack.close()
    except Exception as error:
        logger.exception("Mining produced a result but resource cleanup failed")
        raise _PostProcessCleanupError(result) from error
    return result


def _result_terminal(run_id: str, result: object) -> tuple[str, str]:
    # This must remain the desktop classifier, not an Android reimplementation.
    from anki_miner.models.processing import classify_result, result_error_text

    outcome = classify_result(result).value
    terminal_error = (
        {
            "code": "processing_failed",
            "message": result_error_text(result),
        }
        if outcome == "failed"
        else None
    )
    return outcome, encode_message(
        "mining.terminal",
        {
            "runId": run_id,
            "outcome": outcome,
            "result": to_json_value(result),
            "error": terminal_error,
        },
    )


def _android_engine_message(message: str) -> str:
    """Re-word the one engine message that names desktop-only menus.

    Engine exception text crosses the bridge verbatim, and the offline-dictionary
    pre-flight tells the user to "Use Tools → Download Recommended Resources or
    Settings → Dictionaries" — two surfaces Android does not have. Matched against
    the engine's own constant rather than a substring, so an upstream re-wording
    surfaces the desktop text again (visible, and caught by the bridge test) rather
    than silently mapping the wrong message.
    """

    from anki_miner.orchestration.episode_processor import (
        _OFFLINE_DICTIONARY_REQUIRED_MESSAGE,
    )

    if message == _OFFLINE_DICTIONARY_REQUIRED_MESSAGE:
        return "No usable offline dictionary is installed. Import one in Settings, under Dictionaries."
    return message


def _exception_terminal(
    run_id: str,
    error: BaseException,
    *,
    cancelled: bool,
    log: logging.Logger,
) -> tuple[str, str]:
    """Classify a raised failure into a terminal, logging it under a fault id.

    ``log`` is the calling lane's logger, required rather than defaulted so the
    record's logger name always names the lane that failed. The caller must not
    log the same exception again, or the fault id and the traceback land in
    different records.
    """

    outcome = "cancelled" if cancelled else "failed"
    fault_id: str | None = None
    if cancelled:
        code = "cancelled"
        message = str(error) or "Mining was cancelled"
    elif isinstance(error, BridgeProtocolError):
        code = error.code
        message = str(error)
        # A stable machine code and a deliberate message already identify this
        # failure, so it needs a traceback but not a correlation key.
        log.error("Mining failed with protocol error code=%s outcome=fail", code, exc_info=error)
    else:
        # Record before importing the engine: this import is itself a known
        # failure mode (ANKI_MINER_HOME ordering, the PyQt6 shim), and if it
        # raises, the exception being classified must still have left a record.
        fault_id = record_fault(log, "Mining failed", error)
        # Only deliberate engine-domain messages cross the public boundary.
        # Raw RuntimeError/OSError text can contain filesystem/provider detail.
        from anki_miner.exceptions.base import AnkiMinerException

        if isinstance(error, AnkiMinerException):
            code = "engine_error"
            message = _android_engine_message(str(error)) or "Mining failed"
        else:
            code = "internal_error"
            message = "Internal mining failure"
    terminal_error: dict[str, object] = {"code": code, "message": message}
    if fault_id is not None:
        terminal_error["faultId"] = fault_id
    return outcome, encode_message(
        "mining.terminal",
        {
            "runId": run_id,
            "outcome": outcome,
            "result": None,
            "error": terminal_error,
        },
    )


def _cleanup_failure_terminal(run_id: str, result: object) -> tuple[str, str]:
    return "failed", encode_message(
        "mining.terminal",
        {
            "runId": run_id,
            "outcome": "failed",
            "result": to_json_value(result),
            "error": {
                "code": "cleanup_failed",
                "message": "Mining finished but resource cleanup failed",
            },
        },
    )


def _emit_terminal(adapters: CallbackAdapters, outcome: str, terminal_message: str) -> None:
    try:
        adapters.notify_terminal(terminal_message, failed=outcome == "failed")
    except Exception:
        # The identical terminal envelope is also the synchronous dispatch
        # result. A UI callback failure must not replace it with bridge.error.
        logger.exception("Failed to deliver mining terminal callback")


def run_video(
    raw_request: str,
    callbacks: object,
    *,
    job_registry: JobRegistry | None = None,
) -> str:
    """Run one local video through the unchanged desktop ``process_episode``."""

    request = _parse_request(raw_request)
    files_dir = _ensure_runtime_ready()
    from .anki_adapter import AnkiOperationCancelled

    owner = job_registry or registry()
    handle = owner.begin()
    adapters = CallbackAdapters(callbacks, owner, handle)
    try:
        adapters.register_job()
        try:
            # Registration makes the run ID visible to the control dispatcher.
            # Honor a cancellation raced through that handoff before config
            # mapping scans disk or processor composition opens resources.
            if adapters.cancel_event.is_set():
                raise AnkiOperationCancelled("runVideo", "Mining was cancelled", False)
            config = _map_config(request, files_dir)
            from anki_miner.utils.ffmpeg_resolver import resolve_ffmpeg

            if resolve_ffmpeg(config) == "ffmpeg":
                logger.error(
                    "ffmpeg_fallback_to_path outcome=fail",
                    exc_info=RuntimeError("Bundled ffmpeg resolved to the PATH fallback"),
                )
            if adapters.cancel_event.is_set():
                raise AnkiOperationCancelled("runVideo", "Mining was cancelled", False)
            result = _process_episode(request, config, adapters)
            outcome, terminal = _result_terminal(handle.run_id, result)
        except _PostProcessCleanupError as error:
            outcome, terminal = _cleanup_failure_terminal(handle.run_id, error.result)
        except AnkiOperationCancelled as error:
            outcome, terminal = _exception_terminal(handle.run_id, error, cancelled=True, log=logger)
        except Exception as error:
            outcome, terminal = _exception_terminal(handle.run_id, error, cancelled=False, log=logger)
    finally:
        owner.finish(handle.run_id)

    _emit_terminal(adapters, outcome, terminal)
    return terminal
