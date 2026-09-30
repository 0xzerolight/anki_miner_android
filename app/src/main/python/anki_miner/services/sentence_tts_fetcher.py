"""Android shadow of desktop ``anki_miner/services/sentence_tts_fetcher.py``.

Android has no network sentence TTS: the desktop module imports
``google_translate_audio_fetcher`` and through it ``gtts``, which is a Play
red line, and it scrapes Naver Papago. The vendored ja language profile still
imports ``PAPAGO_SPEAKER_JA`` from here at module top, so this shadow exports
that constant alone, with the desktop value, and nothing else.

Importers at the pinned desktop SHA: ``languages/ja/__init__.py``
(``PAPAGO_SPEAKER_JA``). ``gui/utils/service_factory.py`` imports the fetcher
classes but is desktop-only and not vendored.
"""

PAPAGO_SPEAKER_JA = "yuri"
