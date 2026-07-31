"""Offline Android TextToSpeech adapter for the desktop sentence-audio protocol."""

from __future__ import annotations

import logging
import re
from collections.abc import Callable
from pathlib import Path
from uuid import uuid4

from .callbacks import _invoke_result
from .protocol import BridgeProtocolError, decode_envelope, encode_message

logger = logging.getLogger(__name__)

_MAX_REQUEST_UTF8_BYTES = 32 * 1024
_MAX_RESULT_UTF8_BYTES = 8 * 1024
_MAX_SENTENCE_UTF8_BYTES = 16 * 1024
_MAX_PATH_UTF8_BYTES = 4_096
_MAX_AUDIO_BYTES = 16 * 1024 * 1024
_CACHE_DIRECTORY = "sentence-audio-v1"
_AUDIO_FILENAME = re.compile(r"^android_tts_v1_[0-9a-f]{64}\.wav$")
_OUTCOMES = frozenset({"ready", "unavailable", "failed", "cancelled"})
_ERROR_CODES = frozenset(
    {
        "audio_output_too_large",
        "cache_full",
        "cache_publish_failed",
        "cache_unavailable",
        "cancelled",
        "internal_error",
        "invalid_audio_output",
        "invalid_sentence",
        "main_thread_forbidden",
        "network_voice_rejected",
        "offline_japanese_voice_unavailable",
        "offline_voice_changed",
        "synthesis_failed",
        "synthesis_timeout",
        "synthesizer_closed",
        "tts_engine_unavailable",
        "tts_initialization_timeout",
    }
)


def _utf8_size(value: str, *, context: str) -> int:
    try:
        return len(value.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise BridgeProtocolError("invalid_tts_callback", f"{context} contains invalid Unicode") from exc


class AndroidSentenceAudioFetcher:
    """Never-raising adapter which delegates synthesis to Kotlin's offline TTS.

    No desktop provider builder is imported or consulted. The similarly named
    desktop Google/Papago config bits are compatibility gates only; this class
    is the sole sentence fetcher Android reading composition can inject.
    """

    def __init__(
        self,
        callbacks: object,
        run_id: str,
        cache_dir: Path,
        warning_callback: Callable[[str], None] | None = None,
    ) -> None:
        self._callbacks = callbacks
        self._run_id = run_id
        self._cache_root = (cache_dir / _CACHE_DIRECTORY).resolve(strict=False)
        self._warning_callback = warning_callback
        self._warning_reported = False
        self._logged_callback_failure = False

    def fetch(
        self,
        sentence: str,
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        """Return one verified private WAV path, or None. Never raises."""

        try:
            if cancelled_check is not None and cancelled_check():
                return None
            if (
                not isinstance(sentence, str)
                or not sentence
                or "\x00" in sentence
                or _utf8_size(sentence, context="sentence") > _MAX_SENTENCE_UTF8_BYTES
            ):
                return None
            request_id = f"tts_{uuid4().hex}"
            raw_request = encode_message(
                "tts.sentence.request",
                {
                    "runId": self._run_id,
                    "requestId": request_id,
                    "sentence": sentence,
                },
            )
            if _utf8_size(raw_request, context="TTS request") > _MAX_REQUEST_UTF8_BYTES:
                return None
            raw_result = _invoke_result(self._callbacks, "synthesizeSentenceAudio", raw_request)
            if _utf8_size(raw_result, context="TTS result") > _MAX_RESULT_UTF8_BYTES:
                return None
            message = decode_envelope(
                raw_result,
                expected_type="tts.sentence.result",
            )
            payload = message.payload
            if set(payload) != {
                "runId",
                "requestId",
                "outcome",
                "path",
                "errorCode",
            }:
                return None
            if payload["runId"] != self._run_id or payload["requestId"] != request_id:
                return None
            outcome = payload["outcome"]
            if not isinstance(outcome, str) or outcome not in _OUTCOMES:
                return None
            if cancelled_check is not None and cancelled_check():
                return None
            if outcome == "ready":
                if payload["errorCode"] is not None:
                    return None
                return self._validated_path(payload["path"])
            if payload["path"] is not None:
                return None
            error_code = payload["errorCode"]
            if not isinstance(error_code, str) or error_code not in _ERROR_CODES:
                return None
            if outcome == "cancelled" and error_code != "cancelled":
                return None
            if outcome != "cancelled" and error_code == "cancelled":
                return None
            if outcome != "cancelled":
                self._report_warning(error_code)
            return None
        except Exception as error:
            # Sentence audio is optional. Never let an unavailable Android engine,
            # a callback exception, or a malformed response abort reading mining.
            if not self._logged_callback_failure:
                self._logged_callback_failure = True
                logger.warning("Android sentence TTS skipped", exc_info=error)
            self._report_warning("invalid_tts_callback")
            return None

    def close(self) -> None:
        """Kotlin owns the run session and closes it after Python returns."""

    def _validated_path(self, raw_path: object) -> Path | None:
        if (
            not isinstance(raw_path, str)
            or not raw_path
            or "\x00" in raw_path
            or _utf8_size(raw_path, context="TTS path") > _MAX_PATH_UTF8_BYTES
        ):
            return None
        candidate = Path(raw_path)
        if not candidate.is_absolute() or not _AUDIO_FILENAME.fullmatch(candidate.name):
            return None
        try:
            resolved = candidate.resolve(strict=True)
        except (OSError, RuntimeError):
            return None
        if resolved.parent != self._cache_root or not resolved.is_file():
            return None
        try:
            size = resolved.stat().st_size
        except OSError:
            return None
        return resolved if 0 < size <= _MAX_AUDIO_BYTES else None

    def _report_warning(self, error_code: str) -> None:
        if self._warning_reported or self._warning_callback is None:
            return
        self._warning_reported = True
        if error_code in {
            "offline_japanese_voice_unavailable",
            "tts_engine_unavailable",
            "tts_initialization_timeout",
        }:
            message = (
                "Offline Japanese sentence audio is unavailable. Install an offline "
                "Japanese voice in Android speech settings."
            )
        elif error_code in {"cache_full", "cache_unavailable", "cache_publish_failed"}:
            message = "Offline sentence audio was skipped because private cache storage is " "unavailable or full."
        else:
            message = "Offline sentence audio could not be synthesized for one or more selected " "sentences."
        try:
            self._warning_callback(message)
        except Exception:
            # Optional warning delivery must never turn optional sentence audio into a fatal run.
            logger.warning("Android sentence TTS warning callback failed", exc_info=True)
