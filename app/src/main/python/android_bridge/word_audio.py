"""Android TextToSpeech word audio: the ``android_tts`` member of the word-audio chain.

Every non-ja desktop profile defaults its word audio to googletts or edgetts, and
both are network services Android does not ship. This fetcher speaks the word
with the device's offline voice instead, through the same Kotlin callback reading
mining uses for sentence audio (``AndroidSentenceAudioFetcher``).

Kotlin names its output by content (``android_tts_v1_<sha256>.wav``), which the
word-audio protocol cannot use as an Anki media name: that name must be unique
per (source, mined_form, reading). The fetcher therefore copies each result into
the run's word-audio cache under its own name; the chain pins it there and the
run cache prunes it when the run ends.

No engine module is imported: the bridge owns this source end to end.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
from collections.abc import Callable
from pathlib import Path
from uuid import uuid4

from .sentence_audio import AndroidSentenceAudioFetcher

logger = logging.getLogger(__name__)

#: The bridge-only ``expression_audio_chain`` kind this fetcher serves. The
#: vendored engine has no fetcher for it and skips it like any non-pack kind.
ANDROID_TTS_KIND = "android_tts"

_MEDIA_PREFIX = "androidtts"


class AndroidWordAudioFetcher:
    """Never-raising ``ExpressionAudioFetcher`` over Kotlin's offline TextToSpeech."""

    def __init__(
        self,
        callbacks: object,
        run_id: str,
        cache_dir: Path,
        media_dir: Path,
        *,
        language: str,
        speakable: Callable[[str, str], str | None] | None,
    ) -> None:
        # ``cache_dir`` is the app cache Kotlin synthesises into; ``media_dir``
        # is where this run's uniquely named copies live.
        self._synthesizer = AndroidSentenceAudioFetcher(callbacks, run_id, cache_dir, language=language)
        self._media_dir = media_dir
        self._language = language
        self._speakable = speakable
        self._logged_failure = False

    def media_name(self, mined_form: str, reading: str) -> str:
        """The Anki media filename for one ``(mined_form, reading)`` pair.

        A digest rather than the word itself: it is filesystem-safe in every
        script, never truncated into a collision, and distinct per language.
        """

        # JSON keeps the pair unambiguous whatever either half contains.
        pair = json.dumps([mined_form, reading], ensure_ascii=False)
        digest = hashlib.sha256(pair.encode("utf-8", "surrogatepass")).hexdigest()[:32]
        return f"{_MEDIA_PREFIX}_{self._language}_{digest}.wav"

    def _speakable_text(self, mined_form: str, reading: str) -> str | None:
        # Desktop GoogleTranslateAudioFetcher._speakable_text, minus the ja kana
        # branch: this source is only ever built for a profile that has one.
        if not mined_form.strip() or self._speakable is None:
            return None
        text = self._speakable(mined_form, reading)
        return text if text and text.strip() else None

    def fetch(
        self,
        mined_form: str,
        reading: str,
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        """Return this pair's spoken audio, or None. Never raises."""

        try:
            if cancelled_check is not None and cancelled_check():
                return None
            text = self._speakable_text(mined_form, reading)
            if text is None:
                return None
            source = self._synthesizer.fetch(text, cancelled_check)
            if source is None or (cancelled_check is not None and cancelled_check()):
                return None
            return self._publish(source, self.media_name(mined_form, reading))
        except MemoryError:
            # As desktop's synthetic fetchers: memory exhaustion is the run's to stop on.
            raise
        except Exception as error:
            # Word audio is optional: a broken voice must never abort the run.
            if not self._logged_failure:
                self._logged_failure = True
                logger.warning("Android word TTS skipped", exc_info=error)
            return None

    def fetch_candidates(
        self,
        candidates: list[tuple[str, str]],
        cancelled_check: Callable[[], bool] | None = None,
    ) -> Path | None:
        """The first candidate this voice speaks; the whole ladder before the next source."""

        for mined_form, reading in candidates:
            if cancelled_check is not None and cancelled_check():
                return None
            path = self.fetch(mined_form, reading, cancelled_check)
            if path is not None:
                return path
        return None

    def close(self) -> None:
        """Kotlin owns the TextToSpeech session and the run cache owns the copies."""

    def _publish(self, source: Path, name: str) -> Path:
        self._media_dir.mkdir(parents=True, exist_ok=True)
        target = self._media_dir / name
        temporary = self._media_dir / f".{name}.{uuid4().hex}.part"
        try:
            shutil.copyfile(source, temporary)
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)
        return target
