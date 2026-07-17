"""Android-owned composition and execution of one local video mining run."""

from __future__ import annotations

import contextlib
import logging
from collections.abc import Callable, Mapping, Sequence
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path

from .callbacks import CallbackAdapters
from .config_map import AndroidPaths, map_config_settings
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
        "cacheDir",
        "nativeLibraryDir",
        "configSnapshot",
    }
)
_CONFIG_SNAPSHOT_FIELDS = frozenset({"settings", "androidTtsEnabled"})
_SUBTITLE_SUFFIXES = frozenset({".ass", ".srt", ".ssa", ".vtt"})


@dataclass(frozen=True, slots=True)
class _VideoRequest:
    video_path: Path
    subtitle_path: Path
    episode_name: str
    series_name: str
    source_label: str | None
    audio_track_override: int | None
    cache_dir: Path
    native_library_dir: Path
    settings: Mapping[str, object]
    android_tts_enabled: bool | None


class _LocalAudioPackChain:
    """Small source-priority composite containing local pack fetchers only."""

    def __init__(self, fetchers: Sequence[object]) -> None:
        self._fetchers = tuple(fetchers)

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
            except Exception:
                logger.exception("Local expression-audio pack fetch failed")
                continue
            if path is not None:
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
            except Exception:
                logger.exception("Local expression-audio pack fetch failed")
                continue
            if path is not None:
                return path
        return None

    def close(self) -> None:
        for fetcher in self._fetchers:
            close = getattr(fetcher, "close", None)
            if callable(close):
                with contextlib.suppress(Exception):
                    close()


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
        raise _invalid_request(
            f"{field_name} must not have leading or trailing whitespace"
        )
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
        raise _invalid_request(
            "audioTrackOverride must be a non-negative integer or null"
        )
    return converted


def _parse_request(raw_request: str) -> _VideoRequest:
    payload = decode_message(raw_request, expected_type="mining.video.run")
    if set(payload) != _VIDEO_REQUEST_FIELDS:
        raise _invalid_request(
            f"Expected payload fields: {sorted(_VIDEO_REQUEST_FIELDS)!r}"
        )

    snapshot = payload["configSnapshot"]
    if not isinstance(snapshot, dict) or not set(snapshot).issubset(
        _CONFIG_SNAPSHOT_FIELDS
    ):
        raise _invalid_request("configSnapshot fields are invalid")
    if "settings" not in snapshot or not isinstance(snapshot["settings"], dict):
        raise _invalid_request("configSnapshot.settings must be an object")

    android_tts_enabled = snapshot.get("androidTtsEnabled")
    if "androidTtsEnabled" in snapshot and type(android_tts_enabled) is not bool:
        raise _invalid_request("configSnapshot.androidTtsEnabled must be a boolean")

    subtitle_path = _absolute_path("subtitlePath", payload["subtitlePath"])
    if subtitle_path.suffix.lower() not in _SUBTITLE_SUFFIXES:
        raise _invalid_request(
            "subtitlePath must preserve a supported subtitle filename suffix"
        )

    return _VideoRequest(
        video_path=_absolute_path("videoPath", payload["videoPath"]),
        subtitle_path=subtitle_path,
        episode_name=_canonical_label("episodeName", payload["episodeName"]),
        series_name=_canonical_label("seriesName", payload["seriesName"]),
        source_label=_optional_source_label(payload["sourceLabel"]),
        audio_track_override=_optional_audio_track(payload["audioTrackOverride"]),
        cache_dir=_absolute_path("cacheDir", payload["cacheDir"]),
        native_library_dir=_absolute_path(
            "nativeLibraryDir", payload["nativeLibraryDir"]
        ),
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


def _show_optional_failure(presenter: object, message: str, error: Exception) -> None:
    logger.warning("%s: %s", message, error)
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


def _build_local_audio_pack_chain(config: object) -> _LocalAudioPackChain | None:
    entries = tuple(getattr(config, "expression_audio_chain", ()))
    if any(getattr(entry, "kind", None) != "pack" for entry in entries):
        raise BridgeProtocolError(
            "unsupported_android_feature",
            "Android expression audio supports local packs only",
        )
    if not getattr(config, "anki_fields", {}).get("expression_audio") or not any(
        getattr(entry, "enabled", False) for entry in entries
    ):
        return None

    # Function-local imports are load-bearing: bootstrap and tokenizer resource
    # registration must precede every engine service import.
    from anki_miner.config.paths import ANKI_MINER_HOME
    from anki_miner.services.audio_packs.registry import AudioPackRegistry

    pack_registry = AudioPackRegistry(config.audio_packs_root)
    pack_registry.load()
    fetchers = pack_registry.build_fetcher_chain(
        config, ANKI_MINER_HOME / "audio_cache" / "local_packs"
    )
    return _LocalAudioPackChain(fetchers)


class _AndroidOnlineDictionaryProvider:
    """Run-scoped cancel gate and memoizer for an explicitly enabled online provider.

    Definition and glossary generation may ask the same provider for the same word. Android
    permits that term to leave the device at most once per run. Cancellation prevents every new
    request; an already in-flight provider timeout remains bounded by the provider itself.
    """

    def __init__(
        self,
        provider: object,
        cancelled_check: Callable[[], bool],
    ) -> None:
        self._provider = provider
        self._cancelled_check = cancelled_check
        self._cache: dict[str, str | None] = {}

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

    def lookup(self, word: str) -> str | None:
        if word in self._cache:
            return self._cache[word]
        if self._cancelled_check():
            return None
        result = self._provider.lookup(word)
        self._cache[word] = result
        return result

    def close(self) -> None:
        closer = getattr(self._provider, "close", None)
        if callable(closer):
            closer()


def _android_dictionary_provider_chain(
    providers: list[object],
    cancelled_check: Callable[[], bool],
) -> list[object]:
    return [
        _AndroidOnlineDictionaryProvider(provider, cancelled_check)
        if getattr(provider, "is_online", False)
        else provider
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
    from anki_miner.services.pitch_accent_service import PitchAccentService
    from anki_miner.services.stats_service import StatsService
    from anki_miner.services.subtitle_parser import SubtitleParserService
    from anki_miner.services.word_filter import WordFilterService
    from anki_miner.services.word_list_service import WordListService
    from anki_miner.services.wordset_service import WordsetService

    definition_service: object | None = None
    frequency_service: object | None = None
    expression_audio_fetcher: _LocalAudioPackChain | None = None
    try:
        dictionary_registry = DictionaryRegistry(config.dicts_root)
        try:
            dictionary_registry.load()
        except OSError as error:
            _show_optional_failure(
                adapters.presenter, "Couldn't scan dictionaries folder", error
            )
        providers = _android_dictionary_provider_chain(
            dictionary_registry.build_provider_chain(config),
            adapters.cancel_event.is_set,
        )
        definition_service = DefinitionService(config, providers=providers)

        has_indexed_dictionary = any(
            entry.kind == "indexed" and entry.enabled
            for entry in config.dictionary_chain
        )
        if has_indexed_dictionary:
            try:
                definition_service.ensure_loaded()
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter, "Couldn't load dictionary chain", error
                )

        subtitle_parser = SubtitleParserService(
            config,
            term_lookup=(
                definition_service.offline_terms_exist
                if has_indexed_dictionary
                else None
            ),
            reading_lookup=(
                definition_service.offline_term_readings
                if has_indexed_dictionary
                else None
            ),
            kana_attest_lookup=(
                definition_service.has_offline_definitions
                if has_indexed_dictionary
                else None
            ),
        )
        word_filter = WordFilterService(config, tagger=subtitle_parser.tagger)
        media_extractor = MediaExtractorService(config)
        expression_audio_fetcher = _build_local_audio_pack_chain(config)

        pitch_accent_service = None
        if config.pitch_active:
            try:
                pitch_accent_service = PitchAccentService(config.pitch_accent_path)
                pitch_accent_service.load()
                if pitch_accent_service.entry_count <= 0:
                    adapters.presenter.show_warning(
                        "Pitch accent file has no valid entries"
                    )
                    pitch_accent_service = None
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter, "Couldn't load pitch accent data", error
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
            except Exception as error:
                for provider in candidates:
                    _close_without_masking(provider, "frequency provider")
                _show_optional_failure(
                    adapters.presenter, "Couldn't load frequency data", error
                )
                frequency_service = None

        try:
            known_word_db = KnownWordDB(config.known_words_db_path)
            if config.use_known_words_db:
                known_word_db.initialize()
        except Exception as error:
            _show_optional_failure(
                adapters.presenter, "Couldn't initialize known word database", error
            )
            known_word_db = None

        word_list_service = None
        if config.use_blacklist or config.use_whitelist:
            try:
                word_list_service = WordListService(
                    blacklist_path=(
                        config.blacklist_path if config.use_blacklist else None
                    ),
                    whitelist_path=(
                        config.whitelist_path if config.use_whitelist else None
                    ),
                )
                word_list_service.load()
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter, "Couldn't load word lists", error
                )
                word_list_service = None

        wordset_service = None
        if config.excluded_wordsets:
            try:
                wordset_service = WordsetService(
                    enabled_ids=config.excluded_wordsets
                )
                wordset_service.load()
                if not wordset_service.is_available():
                    wordset_service = None
            except Exception as error:
                _show_optional_failure(
                    adapters.presenter, "Couldn't load name wordsets", error
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
            )
        )
        processor = _build_processor(config, adapters, anki_adapter)
        stack.callback(processor.close)
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
            audio_only=False,
            cancel_event=adapters.cancel_event,
        )
    except BaseException:
        try:
            stack.close()
        except BaseException:
            logger.exception(
                "Mining cleanup failed while preserving the primary failure"
            )
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


def _exception_terminal(
    run_id: str,
    error: BaseException,
    *,
    cancelled: bool,
) -> tuple[str, str]:
    outcome = "cancelled" if cancelled else "failed"
    if cancelled:
        code = "cancelled"
        message = str(error) or "Mining was cancelled"
    elif isinstance(error, BridgeProtocolError):
        code = error.code
        message = str(error)
    else:
        # Only deliberate engine-domain messages cross the public boundary.
        # Raw RuntimeError/OSError text can contain filesystem/provider detail.
        from anki_miner.exceptions.base import AnkiMinerException

        if isinstance(error, AnkiMinerException):
            code = "engine_error"
            message = str(error) or "Mining failed"
        else:
            code = "internal_error"
            message = "Internal mining failure"
    return outcome, encode_message(
        "mining.terminal",
        {
            "runId": run_id,
            "outcome": outcome,
            "result": None,
            "error": {"code": code, "message": message},
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


def _emit_terminal(
    adapters: CallbackAdapters, outcome: str, terminal_message: str
) -> None:
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
                raise AnkiOperationCancelled(
                    "runVideo", "Mining was cancelled", False
                )
            config = _map_config(request, files_dir)
            if adapters.cancel_event.is_set():
                raise AnkiOperationCancelled(
                    "runVideo", "Mining was cancelled", False
                )
            result = _process_episode(request, config, adapters)
            outcome, terminal = _result_terminal(handle.run_id, result)
        except _PostProcessCleanupError as error:
            outcome, terminal = _cleanup_failure_terminal(
                handle.run_id, error.result
            )
        except AnkiOperationCancelled as error:
            outcome, terminal = _exception_terminal(
                handle.run_id, error, cancelled=True
            )
        except Exception as error:
            logger.exception("Video mining failed")
            outcome, terminal = _exception_terminal(
                handle.run_id, error, cancelled=False
            )
    finally:
        owner.finish(handle.run_id)

    _emit_terminal(adapters, outcome, terminal)
    return terminal
