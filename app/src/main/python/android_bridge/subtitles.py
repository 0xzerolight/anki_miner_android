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
from collections.abc import Callable, Iterator, Mapping
from pathlib import Path

from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)

_RUN_ID_RE = re.compile(r"^run_[0-9a-f]{32}$")
ALLOWED_SUFFIXES = {".ass", ".srt", ".ssa", ".vtt"}
MAX_SUBTITLE_BYTES = 32 * 1024 * 1024  # matches SafJobFileOwner's staging cap
MAX_CUES = 20_000
MAX_RESULT_UTF8_BYTES = 2 * 1024 * 1024
_SRT_TIMESTAMP_RE = re.compile(r"(\d{1,2}):(\d{1,2}):(\d{1,2})[.,](\d{1,3})")
_VTT_TIMESTAMP_RE = re.compile(r"(\d{0,4}:)?(\d{2}):(\d{2})\.(\d{2,3})")


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def _detect_cue_format(path: Path, encoding: str) -> str:
    with path.open("r", encoding=encoding) as subtitle:
        fragment = subtitle.read(10_000)
    from pysubs2.formats import autodetect_format

    cue_format = autodetect_format(fragment)
    if cue_format not in {"ass", "srt", "ssa", "vtt"}:
        raise ValueError(f"Unsupported subtitle content format: {cue_format}")
    return cue_format


def _iter_subrip_cues(path: Path, encoding: str, cue_format: str) -> Iterator[tuple[str, bool]]:
    timestamp_re = _VTT_TIMESTAMP_RE if cue_format == "vtt" else _SRT_TIMESTAMP_RE
    following_lines: list[str] | None = None
    buffered_utf8_bytes = 0

    with path.open("r", encoding=encoding) as subtitle:
        for line in subtitle:
            if len(timestamp_re.findall(line)) == 2:
                if following_lines is not None:
                    text = "".join(following_lines).strip()
                    yield re.sub(r"\n+ *\d+ *$", "", text), False
                following_lines = []
                buffered_utf8_bytes = 0
            elif following_lines is not None:
                buffered_utf8_bytes += len(line.encode("utf-8"))
                if buffered_utf8_bytes > MAX_RESULT_UTF8_BYTES:
                    raise _fail("subtitle_cues_too_large", "Cue text exceeds the transport bound")
                following_lines.append(line)

    if following_lines is not None:
        text = "".join(following_lines).strip()
        yield re.sub(r"\n+ *\d+ *$", "", text), False


def _iter_substation_cues(path: Path, encoding: str) -> Iterator[tuple[str, bool]]:
    with path.open("r", encoding=encoding) as subtitle:
        for line in subtitle:
            stripped = line.strip()
            if not (stripped.startswith("Dialogue:") or stripped.startswith("Comment:")):
                continue
            event_type, rest = stripped.split(":", 1)
            fields = rest.strip().split(",", 9)
            text = fields[9] if len(fields) == 10 else ""
            yield text, event_type == "Comment"


def _iter_raw_cues(path: Path, encoding: str) -> Iterator[tuple[str, bool]]:
    cue_format = _detect_cue_format(path, encoding)
    if cue_format in {"ass", "ssa"}:
        yield from _iter_substation_cues(path, encoding)
    else:
        yield from _iter_subrip_cues(path, encoding, cue_format)


def _scan_cue_budgets(
    path: Path,
    encoding: str,
    cue_text_size: Callable[[str, int], int] | None = None,
) -> None:
    cue_text_bytes = 0
    for cue_count, (raw_text, is_comment) in enumerate(_iter_raw_cues(path, encoding), 1):
        if cue_count > MAX_CUES:
            raise _fail("subtitle_cues_too_large", f"Subtitle has more than {MAX_CUES} cues")

        if cue_text_size is None:
            cue_text_bytes += len(raw_text.encode("utf-8"))
        elif not is_comment:
            cue_text_bytes += cue_text_size(raw_text, MAX_RESULT_UTF8_BYTES - cue_text_bytes)
        if cue_text_bytes > MAX_RESULT_UTF8_BYTES:
            raise _fail("subtitle_cues_too_large", "Cue text exceeds the transport bound")


def _preflight_cue_budgets(path: Path) -> str:
    utf8_failure: UnicodeDecodeError
    try:
        _scan_cue_budgets(path, "utf-8")
        return "utf-8"
    except UnicodeDecodeError as error:
        utf8_failure = error

    try:
        _scan_cue_budgets(path, "cp932")
        return "cp932"
    except UnicodeDecodeError:
        from anki_miner.utils.subtitle_encoding import _detect_encoding

        encoding = _detect_encoding(path)
        if encoding is not None:
            try:
                _scan_cue_budgets(path, encoding)
                return encoding
            except (LookupError, UnicodeDecodeError):
                pass
        raise utf8_failure from None


def _filtered_utf8_size(
    pattern: re.Pattern[str],
    replacement: str,
    text: str,
    max_bytes: int,
) -> int:
    total = 0
    seen_text = False
    pending_space = False

    def count_chunk(chunk: str) -> None:
        nonlocal pending_space, seen_text, total
        for char in chunk:
            if char.isspace():
                pending_space = seen_text
                continue
            if pending_space:
                total += 1
                pending_space = False
            total += len(char.encode("utf-8"))
            seen_text = True
            if total > max_bytes:
                raise _fail("subtitle_cues_too_large", "Cue text exceeds the transport bound")

    cursor = 0
    for match in pattern.finditer(text):
        count_chunk(text[cursor : match.start()])
        max_group_chars = max(
            (end - start for start, end in (match.span(group) for group in range(pattern.groups + 1)) if start >= 0),
            default=0,
        )
        expansion_bound = 4 * (len(replacement) + replacement.count("\\") * max_group_chars)
        if expansion_bound > MAX_RESULT_UTF8_BYTES:
            raise _fail("subtitle_cues_too_large", "Cue text exceeds the transport bound")
        count_chunk(match.expand(replacement))
        cursor = match.end()
    count_chunk(text[cursor:])
    return total


def _cleaned_cue_utf8_size(
    parser: object,
    config: object,
    raw_text: str,
    max_bytes: int,
) -> int:
    from anki_miner.services import subtitle_parser

    cleaned = subtitle_parser.clean_subtitle_text(
        raw_text,
        strip_annotations=config.strip_subtitle_annotations,
    )
    pattern = getattr(parser, "_filter_pattern", None)
    if pattern is None:
        size = len(cleaned.encode("utf-8"))
        if size > max_bytes:
            raise _fail("subtitle_cues_too_large", "Cue text exceeds the transport bound")
        return size
    return _filtered_utf8_size(
        pattern,
        config.subtitle_regex_replacement,
        cleaned,
        max_bytes,
    )


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

    try:
        encoding = _preflight_cue_budgets(path)
    except BridgeProtocolError:
        raise
    except Exception as error:
        logger.warning("Subtitle cue preflight failed: %s", error, exc_info=error)
        raise _fail("subtitle_cues_parse_failed", "The subtitle file could not be read") from error

    # Construction isolated: only THIS RuntimeError means "tokenizer unconfigured".
    try:
        parser = _build_parser(config)
    except RuntimeError as error:
        raise _fail("subtitle_cues_tokenizer_unconfigured", "Tokenizer backend is not configured") from error

    try:
        _scan_cue_budgets(
            path,
            encoding,
            lambda raw_text, max_bytes: _cleaned_cue_utf8_size(
                parser,
                config,
                raw_text,
                max_bytes,
            ),
        )
    except BridgeProtocolError:
        raise
    except Exception as error:
        logger.warning("Subtitle cue transform preflight failed: %s", error, exc_info=error)
        raise _fail("subtitle_cues_parse_failed", "The subtitle file could not be read") from error

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
