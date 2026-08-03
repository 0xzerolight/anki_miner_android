"""Raw subtitle cue extraction for the S4 curator player and timing workbench.

Two callers, one op:
- During curation: ``runId`` names the parked run; its registered config parses
  the run's own subtitle copy, so cue times land on the same (offset-shifted)
  timeline as the ``CurationSentence`` payload. No offset math in Kotlin.
- Pre-run workbench: ``runId`` is null; a default config with the offset forced
  to 0.0 yields unshifted times and Kotlin applies the candidate offset itself.

The parser's ``__init__`` builds the shared tagger, which requires
``tokenizer.configure`` to have run this process; the pre-run caller does that
first. ``parse_raw_entries`` itself never tokenizes. Construction is isolated
from parsing so a construction ``RuntimeError`` maps to the tokenizer code and
nothing else does.
"""

from __future__ import annotations

import logging
import re
from collections.abc import Mapping
from pathlib import Path

from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)

_RUN_ID_RE = re.compile(r"^run_[0-9a-f]{32}$")
ALLOWED_SUFFIXES = {".ass", ".srt", ".ssa", ".vtt"}
MAX_SUBTITLE_BYTES = 32 * 1024 * 1024  # matches SafJobFileOwner's staging cap
MAX_CUES = 20_000
MAX_RESULT_UTF8_BYTES = 2 * 1024 * 1024


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def get_cues(payload: Mapping[str, object]) -> str:
    if set(payload) != {"runId", "subtitlePath"}:
        raise _fail("invalid_subtitle_cues_request", "Expected payload fields: ['runId', 'subtitlePath']")
    run_id = payload["runId"]
    if run_id is not None and (not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id)):
        raise _fail("invalid_subtitle_cues_request", "runId must be null or an opaque run ID")
    raw_path = payload["subtitlePath"]
    if not isinstance(raw_path, str) or not raw_path:
        raise _fail("invalid_subtitle_cues_request", "subtitlePath must be a non-empty string")
    path = Path(raw_path)
    if not path.is_absolute():
        raise _fail("invalid_subtitle_cues_request", "subtitlePath must be absolute")
    if path.suffix.lower() not in ALLOWED_SUFFIXES:
        raise _fail("invalid_subtitle_cues_request", f"Unsupported subtitle suffix: {path.suffix!r}")

    config = _resolve_config(run_id)

    try:
        if path.stat().st_size > MAX_SUBTITLE_BYTES:
            raise _fail("subtitle_cues_too_large", "Subtitle file exceeds the staging cap")
    except OSError as error:
        raise _fail("subtitle_cues_parse_failed", "The subtitle file could not be read") from error

    # Construction isolated: only THIS RuntimeError means "tokenizer unconfigured".
    try:
        parser = _build_parser(config)
    except RuntimeError as error:
        raise _fail("subtitle_cues_tokenizer_unconfigured", "Tokenizer backend is not configured") from error

    try:
        entries = parser.parse_raw_entries(path)
    except BridgeProtocolError:
        raise
    except Exception as error:
        logger.warning("Subtitle cue parse failed: %s", error, exc_info=error)
        raise _fail("subtitle_cues_parse_failed", "The subtitle file could not be parsed") from error

    if len(entries) > MAX_CUES:
        raise _fail("subtitle_cues_too_large", f"Subtitle has more than {MAX_CUES} cues")
    cues = [{"start": start, "end": end, "text": text} for start, end, text in entries]
    total = sum(len(cue["text"].encode("utf-8")) for cue in cues)
    if total > MAX_RESULT_UTF8_BYTES:
        raise _fail("subtitle_cues_too_large", "Cue text exceeds the transport bound")
    return encode_message(
        "subtitle.cues.result",
        {"runId": run_id, "subtitlePath": raw_path, "cues": cues},
    )


def _resolve_config(run_id: str | None) -> object:
    if run_id is None:
        from dataclasses import replace

        from anki_miner.config.config import AnkiMinerConfig

        return replace(AnkiMinerConfig(), subtitle_offset=0.0)
    from .definitions import get_run_config

    config = get_run_config(run_id)
    if config is None:
        raise _fail("subtitle_cues_run_unknown", "No active run owns this subtitle")
    return config


def _build_parser(config: object):
    from anki_miner.services.subtitle_parser import SubtitleParserService

    return SubtitleParserService(config)
