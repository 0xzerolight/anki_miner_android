"""Android-owned composition and execution of one staged reading source."""

from __future__ import annotations

import logging
from collections.abc import Callable, Mapping
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path

from .callbacks import CallbackAdapters
from .config_map import AndroidPaths, map_config_settings
from .jobs import JobRegistry, registry
from .mining import (
    _build_processor,
    _cleanup_failure_terminal,
    _emit_terminal,
    _ensure_runtime_ready,
    _exception_terminal,
    _PostProcessCleanupError,
    _result_terminal,
)
from .protocol import BridgeProtocolError, decode_message
from .unicode_contract import (
    has_leading_or_trailing_python_whitespace,
    is_category_c,
    is_nfc,
)

logger = logging.getLogger(__name__)

_READING_REQUEST_FIELDS = frozenset(
    {
        "sourceKind",
        "sourcePath",
        "imageArchivePath",
        "seriesName",
        "cacheDir",
        "nativeLibraryDir",
        "configSnapshot",
    }
)
_CONFIG_SNAPSHOT_FIELDS = frozenset({"settings", "androidTtsEnabled"})
_SOURCE_SUFFIXES = {
    "txt": frozenset({".txt"}),
    "text": frozenset({".text"}),
    "epub": frozenset({".epub"}),
    "subtitle": frozenset({".ass", ".srt", ".ssa", ".vtt"}),
    "mokuro": frozenset({".mokuro"}),
}
_ARCHIVE_SUFFIXES = frozenset({".cbz", ".zip"})

# A settings snapshot is small, but its nested arrays make a pure structural
# ceiling insufficient. Bound the complete operation before doing a second
# decode, and bound each path in UTF-8 because Java/Kotlin ultimately carries
# the same bytes to native and Python filesystem APIs.
_MAX_READING_REQUEST_UTF8_BYTES = 1_048_576
_MAX_READING_PATH_UTF8_BYTES = 4_096
_MAX_READING_LABEL_UTF8_BYTES = 1_024


@dataclass(frozen=True, slots=True)
class _ReadingRequest:
    source_kind: str
    source_path: Path
    image_archive_path: Path | None
    series_name: str | None
    cache_dir: Path
    native_library_dir: Path
    settings: Mapping[str, object]
    android_tts_enabled: bool | None


def _invalid_request(detail: str) -> BridgeProtocolError:
    return BridgeProtocolError("invalid_reading_mining_request", detail)


def _utf8_size(value: str, *, field_name: str) -> int:
    try:
        return len(value.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise _invalid_request(f"{field_name} contains an invalid Unicode scalar") from exc


def _absolute_path(field_name: str, value: object) -> Path:
    if not isinstance(value, str) or not value or "\x00" in value:
        raise _invalid_request(f"{field_name} must be a non-empty path string")
    if _utf8_size(value, field_name=field_name) > _MAX_READING_PATH_UTF8_BYTES:
        raise _invalid_request(f"{field_name} exceeds its UTF-8 byte limit")
    path = Path(value)
    if not path.is_absolute():
        raise _invalid_request(f"{field_name} must be an absolute path")
    try:
        return path.resolve(strict=False)
    except (OSError, RuntimeError) as exc:
        raise _invalid_request(f"{field_name} cannot be resolved safely") from exc


def _canonical_label(field_name: str, value: object) -> str:
    if not isinstance(value, str) or not value:
        raise _invalid_request(f"{field_name} must be a non-empty string")
    if _utf8_size(value, field_name=field_name) > _MAX_READING_LABEL_UTF8_BYTES:
        raise _invalid_request(f"{field_name} exceeds its UTF-8 byte limit")
    if has_leading_or_trailing_python_whitespace(value):
        raise _invalid_request(f"{field_name} must not have leading or trailing whitespace")
    if not is_nfc(value):
        raise _invalid_request(f"{field_name} must use NFC Unicode normalization")
    if any(is_category_c(ord(character)) for character in value):
        raise _invalid_request(f"{field_name} contains a forbidden character")
    return value


def _private_source_path(field_name: str, value: object, cache_dir: Path) -> Path:
    path = _absolute_path(field_name, value)
    if not path.is_relative_to(cache_dir):
        raise _invalid_request(f"{field_name} must be inside cacheDir")
    return path


def _parse_request(raw_request: str) -> _ReadingRequest:
    if not isinstance(raw_request, str):
        raise _invalid_request("Reading request must be a JSON string")
    if _utf8_size(raw_request, field_name="Reading request") > _MAX_READING_REQUEST_UTF8_BYTES:
        raise _invalid_request("Reading request exceeds its UTF-8 byte limit")

    payload = decode_message(raw_request, expected_type="mining.reading.run")
    if set(payload) != _READING_REQUEST_FIELDS:
        raise _invalid_request(f"Expected payload fields: {sorted(_READING_REQUEST_FIELDS)!r}")

    source_kind = payload["sourceKind"]
    if not isinstance(source_kind, str) or source_kind not in _SOURCE_SUFFIXES:
        raise _invalid_request(f"sourceKind must be one of {sorted(_SOURCE_SUFFIXES)!r}")

    snapshot = payload["configSnapshot"]
    if not isinstance(snapshot, dict) or not set(snapshot).issubset(_CONFIG_SNAPSHOT_FIELDS):
        raise _invalid_request("configSnapshot fields are invalid")
    if "settings" not in snapshot or not isinstance(snapshot["settings"], dict):
        raise _invalid_request("configSnapshot.settings must be an object")
    android_tts_enabled = snapshot.get("androidTtsEnabled")
    if "androidTtsEnabled" in snapshot and type(android_tts_enabled) is not bool:
        raise _invalid_request("configSnapshot.androidTtsEnabled must be a boolean")

    cache_dir = _absolute_path("cacheDir", payload["cacheDir"])
    source_path = _private_source_path("sourcePath", payload["sourcePath"], cache_dir)
    if source_path.suffix.lower() not in _SOURCE_SUFFIXES[source_kind]:
        raise _invalid_request("sourcePath suffix does not match sourceKind")

    raw_series_name = payload["seriesName"]
    if source_kind == "subtitle":
        series_name = _canonical_label("seriesName", raw_series_name)
    else:
        if raw_series_name is not None:
            raise _invalid_request("seriesName is only valid for a subtitle source")
        series_name = None

    raw_archive_path = payload["imageArchivePath"]
    if raw_archive_path is None:
        image_archive_path = None
    else:
        if source_kind != "mokuro":
            raise _invalid_request("imageArchivePath is only valid for a mokuro source")
        image_archive_path = _private_source_path("imageArchivePath", raw_archive_path, cache_dir)
        if image_archive_path.suffix.lower() not in _ARCHIVE_SUFFIXES:
            raise _invalid_request("imageArchivePath must end in .cbz or .zip")
        if image_archive_path.parent != source_path.parent or image_archive_path.stem != source_path.stem:
            raise _invalid_request("imageArchivePath must be a same-directory, same-stem mokuro companion")

    return _ReadingRequest(
        source_kind=source_kind,
        source_path=source_path,
        image_archive_path=image_archive_path,
        series_name=series_name,
        cache_dir=cache_dir,
        native_library_dir=_absolute_path("nativeLibraryDir", payload["nativeLibraryDir"]),
        settings=dict(snapshot["settings"]),
        android_tts_enabled=android_tts_enabled,
    )


def _map_config(request: _ReadingRequest, files_dir: Path) -> object:
    return map_config_settings(
        request.settings,
        AndroidPaths(
            files_dir=files_dir,
            cache_dir=request.cache_dir,
            native_library_dir=request.native_library_dir,
        ),
        android_tts_enabled=request.android_tts_enabled,
    ).engine_config


def _reading_detector() -> object:
    """Import the vendored detector only after Android runtime bootstrap."""

    from anki_miner.services.reading import detector

    return detector


def _read_staged_text(path: Path) -> str:
    """Decode the Kotlin-staged paste. Kotlin wrote UTF-8; anything else is tampering."""

    from . import reading_limits

    try:
        data = path.read_bytes()
    except OSError as error:
        raise _invalid_request("sourcePath could not be read") from error
    # validate_source_before_load stat-bounded this; re-check the bytes actually
    # read so the stat->read window cannot smuggle a larger file through.
    if len(data) > reading_limits.MAX_PASTED_TEXT_SOURCE_BYTES:
        raise BridgeProtocolError("reading_source_too_large", "The pasted text exceeds the mobile limit")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise _invalid_request("Pasted text must be valid UTF-8") from error


def _load_document(
    request: _ReadingRequest,
    cancellation_check: Callable[[], bool] | None = None,
    *,
    strip_subtitle_annotations: bool = True,
) -> object:
    """Call the desktop detector and loader after validating the staged pair.

    ``strip_subtitle_annotations`` carries the user's setting to the per-cue
    cleanup. The engine's own kwarg defaults to False (opt-in for callers that
    predate it), but the product default is on, so the bridge default matches
    the config rather than the engine signature — a caller that forgets it gets
    desktop behaviour, not silently unfiltered cues.
    """

    if not request.source_path.is_file():
        raise _invalid_request("sourcePath must name an existing staged file")
    if request.image_archive_path is not None and not request.image_archive_path.is_file():
        raise _invalid_request("imageArchivePath must name an existing staged file")

    # Staging bounds compressed bytes only. The Android bridge additionally
    # proves ZIP expansion, Mokuro fan-out and cancellation before the desktop
    # detector or loader can allocate their parity-sensitive object graphs.
    from . import reading_limits

    reading_limits.validate_source_before_load(
        source_kind=request.source_kind,
        source_path=request.source_path,
        image_archive_path=request.image_archive_path,
        cancellation_check=cancellation_check,
    )

    # Function-local import preserves the bootstrap-before-engine-import rule.
    detector = _reading_detector()
    if request.source_kind == "text":
        from anki_miner.models.reading import ReadingSourceRef

        # detect() never emits kind="text"; the ref is constructed, exactly as
        # desktop's reading_text_tab does.
        ref = ReadingSourceRef(kind="text", title="Text", text=_read_staged_text(request.source_path))
    else:
        refs = detector.detect(request.source_path)
        if len(refs) != 1:
            raise _invalid_request("sourcePath must detect as exactly one reading source")
        ref = refs[0]
        if ref.kind != request.source_kind:
            raise _invalid_request("Detected reading kind does not match sourceKind")

        # Initial Android mokuro support stages either a sidecar alone (text-only)
        # or one explicit sibling archive. Do not silently consume an undeclared
        # archive/directory merely because the desktop detector can discover it.
        detected_image_root = ref.image_root.resolve(strict=False) if ref.image_root is not None else None
        if detected_image_root != request.image_archive_path:
            raise _invalid_request("Detected mokuro image companion does not match imageArchivePath")
    if cancellation_check is not None and cancellation_check():
        from .anki_adapter import AnkiOperationCancelled

        raise AnkiOperationCancelled("runReading", "Mining was cancelled", False)
    from anki_miner.models.reading import (
        ReadingUnitLimitExceeded,
        ReadingUnitLoadCancelled,
        reading_unit_budget,
    )

    try:
        with reading_unit_budget(
            reading_limits.MAX_DOCUMENT_UNITS,
            cancellation_check=cancellation_check,
            precount_sentences=request.source_kind in {"txt", "text", "epub"},
        ):
            document = detector.load(ref, strip_subtitle_annotations=strip_subtitle_annotations)
    except ReadingUnitLimitExceeded as error:
        raise BridgeProtocolError(
            "reading_source_too_large",
            f"The reading document contains too many units ({error.observed:,} > {error.maximum:,})",
        ) from error
    except ReadingUnitLoadCancelled as error:
        from .anki_adapter import AnkiOperationCancelled

        raise AnkiOperationCancelled(
            "runReading",
            "Mining was cancelled",
            False,
        ) from error
    reading_limits.validate_loaded_document(
        document,
        source_kind=request.source_kind,
        source_path=request.source_path,
        image_archive_path=request.image_archive_path,
        cancellation_check=cancellation_check,
    )
    if request.source_kind == "text" and getattr(document, "kind", None) != "book":
        raise _invalid_request("Text loader returned an invalid document kind")
    if request.source_kind == "subtitle":
        if getattr(document, "kind", None) != "subtitle":
            raise _invalid_request("Subtitle loader returned an invalid document kind")
        # The desktop loader derives series from path.parent.name. Android paths
        # live under nonce-named job directories, so replace only that transport
        # artifact with the stable label supplied by Kotlin before card identity
        # and Source are computed by process_reading.
        document.series = request.series_name
    return document


def _process_reading(
    document: object,
    config: object,
    adapters: CallbackAdapters,
) -> object:
    """Construct one fresh processor, invoke ``process_reading``, and clean up."""

    from .anki_adapter import AndroidAnkiAdapter, AnkiOperationCancelled

    if adapters.cancel_event.is_set():
        raise AnkiOperationCancelled("runReading", "Mining was cancelled", False)

    stack = ExitStack()
    try:
        anki_adapter = stack.enter_context(
            AndroidAnkiAdapter(
                config,
                adapters.anki,
                cancellation_check=adapters.cancel_event.is_set,
            )
        )
        sentence_audio_fetcher = None
        if getattr(config, "reading_tts_enabled", False):
            # Function-local import keeps the bridge bootstrap ordering obvious.
            # This adapter contains no engine or desktop-provider imports.
            from .sentence_audio import AndroidSentenceAudioFetcher

            sentence_audio_fetcher = AndroidSentenceAudioFetcher(
                callbacks=adapters.callbacks,
                run_id=adapters.run_id,
                cache_dir=Path(config.media_temp_folder).parent,
                warning_callback=adapters.presenter.show_warning,
            )
        processor = _build_processor(
            config,
            adapters,
            anki_adapter,
            sentence_audio_fetcher=sentence_audio_fetcher,
        )
        stack.callback(processor.close)
        from .definitions import clear_run_dictionaries, register_run_dictionaries

        register_run_dictionaries(adapters.run_id, config)
        try:
            result = processor.process_reading(
                document,
                progress_callback=adapters.progress,
                curation_callback=adapters.curate,
                cancel_event=adapters.cancel_event,
            )
        finally:
            clear_run_dictionaries(adapters.run_id)
    except BaseException:
        try:
            stack.close()
        except BaseException:
            logger.exception("Reading-mining cleanup failed while preserving the primary failure")
        raise
    try:
        stack.close()
    except Exception as error:
        logger.exception("Reading mining produced a result but cleanup failed")
        raise _PostProcessCleanupError(result) from error
    return result


def run_reading(
    raw_request: str,
    callbacks: object,
    *,
    job_registry: JobRegistry | None = None,
) -> str:
    """Run one staged source through desktop ``detect/load/process_reading``."""

    request = _parse_request(raw_request)
    files_dir = _ensure_runtime_ready()
    from .anki_adapter import AnkiOperationCancelled

    owner = job_registry or registry()
    handle = owner.begin()
    adapters = CallbackAdapters(callbacks, owner, handle)
    try:
        adapters.register_job()
        try:
            if adapters.cancel_event.is_set():
                raise AnkiOperationCancelled("runReading", "Mining was cancelled", False)
            config = _map_config(request, files_dir)
            if adapters.cancel_event.is_set():
                raise AnkiOperationCancelled("runReading", "Mining was cancelled", False)
            document = _load_document(
                request,
                adapters.cancel_event.is_set,
                strip_subtitle_annotations=bool(config.strip_subtitle_annotations),
            )
            if adapters.cancel_event.is_set():
                raise AnkiOperationCancelled("runReading", "Mining was cancelled", False)
            result = _process_reading(document, config, adapters)
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
