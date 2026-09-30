"""Parse known-word exports from external tools into a set of written forms.

Feeds the Manage Known Words "Import…" flow: users migrating from jpdb,
Migaku, AnkiMorphs (or with a plain word list, e.g. ad-hoc WaniKani exports)
bulk-load their known vocabulary as ``known_words.db`` ``source='user'`` rows.
Pure module — no Qt; runs inside a ``run_off_thread`` work callable.

Format signatures were verified against upstream sources (2026-07); if an
import misbehaves years later, check these for drift before touching the
detection order:

- jpdb review export (JSON shape + grade enum): two independent consumers,
  github.com/llvtt/jpdb_anki_import (jpdb.py) and
  github.com/daryll-ko/jpdb-stats (README schema). jpdb has no known-flag, so
  known-ness is derived from the latest review grade with an EXCLUDE-list —
  an unrecognized/future grade (e.g. never-forget markers) defaults to
  *include*, so enum drift fails safe.
- AnkiMorphs headers: exporter source constants ``"Morph-Lemma"`` /
  ``"Morph-Inflection"`` / ``"Occurrence"`` in
  ankimorphs/known_morphs_exporter.py @ github.com/mortii/anki-morphs.
- Migaku Word Exporter JSON/CSV (``Word,Reading,Language,Status``, status
  ``KNOWN``): README/source of github.com/mh-343/migaku-word-exporter
  (Migaku removed its built-in export).
- Migaku legacy add-on backup ``[[word, int], …]`` (2 = known, 1 = learning):
  raetsel migration walkthrough + Migaku-legacy-guides repo.

Stored form is the written form as exported (食べる), matching the
``mined_form`` keying convention — never re-lemmatized here.

Android adds optional bounded parsing to the desktop parser. Calls without
those options retain the pinned desktop behavior byte-for-byte at the API
boundary; Android supplies them so a large plain-text export cannot build an
over-limit set before rejection. Both paths decode through the same shared
ladder decoder; only the parse and the word-set allocation differ.
"""

from __future__ import annotations

import csv
import json
import logging
import re
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass
from itertools import chain
from pathlib import Path
from typing import Any

from anki_miner.exceptions import SetupError
from anki_miner.services.reading._util import decode_with_ladder
from anki_miner.utils.logging_ext import log_summary

logger = logging.getLogger(__name__)

# Stable keys the dialog maps to translated display labels; keep the dialog's
# mapping in lockstep (a unit test asserts completeness on both sides).
FORMAT_KEYS = ("jpdb", "migaku_json", "migaku_legacy", "ankimorphs", "migaku_csv", "generic")

# jpdb grades whose *latest* occurrence disqualifies a card. Everything else
# (okay/easy/hard/pass/known + future positive states) counts as known.
_JPDB_EXCLUDED_GRADES = frozenset({"nothing", "something", "fail", "unknown"})
_MIGAKU_CSV_HEADER = ("word", "reading", "language", "status")
_ANKIMORPHS_LEMMA_HEADER = "morph-lemma"

# Known-word exports are tiny (a jpdb/AnkiMorphs dump is well under a MB); the
# "All Files (*)" dialog filter lets a user mis-pick a large file, so cap the
# read rather than buffer+decode an arbitrary blob off-thread.
_MAX_IMPORT_BYTES = 50 * 1024 * 1024
_READ_CHUNK_BYTES = 1024 * 1024
_LINE_BOUNDARY_RE = re.compile(r"\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]")

#: Ladder for a caller that hands over no profile (tests, ad-hoc callers): the
#: pre-profile Japanese pair. utf-8-sig strips a Windows/Excel BOM that would
#: otherwise break json.loads and the exact first-cell header matches, and
#: cp932 is what a Japanese Notepad/Excel export is written in.
_DEFAULT_ENCODINGS = ("utf-8-sig", "cp932")


class KnownWordsImportError(Exception):
    """Raised when a file yields no importable known words.

    ``reason`` distinguishes the failure class for user messaging, and the
    three read-side reasons are deliberately distinct because the dialog
    states what actually happened: ``"too_large"`` (over the size cap — the
    file is stat-ed, never opened), ``"unreadable"`` (an ``OSError`` opening
    or reading it), ``"undecodable"`` (bytes read fine, every encoding in the
    ladder failed). Then ``"unrecognized"`` (format not matched — including
    valid JSON matching no known signature) and ``"no_known_words"`` (format
    matched but zero entries qualified; ``format_key`` carries the detected
    format in that case).

    Android bounded imports can additionally raise ``"limit_exceeded"`` when
    a supplied word or the distinct-word set exceeds its limit, or
    ``"cancelled"`` when ``cancel_check`` requests cancellation.
    """

    def __init__(self, reason: str, format_key: str | None = None):
        super().__init__(reason)
        self.reason = reason
        self.format_key = format_key


@dataclass(frozen=True)
class KnownWordsImportResult:
    """Outcome of parsing one export file."""

    format_key: str
    words: frozenset[str]
    total_entries: int
    skipped_malformed: int = 0


def parse_known_words_file(
    path: Path,
    *,
    encodings: tuple[str, ...] | None = None,
    script_check: Callable[[str], bool] | None = None,
    max_words: int | None = None,
    max_word_bytes: int | None = None,
    cancel_check: Callable[[], bool] | None = None,
) -> KnownWordsImportResult:
    """Detect the export format of ``path`` and extract its known words.

    Content is tried as JSON first; valid JSON that matches no known JSON
    signature raises ``unrecognized`` rather than falling through to the line
    reader (which would ingest syntax fragments as words). ``.json`` files
    are never read as generic word lists for the same reason.

    *encodings* is the caller's decode ladder — the mining language's
    ``get_profile(...).import_encodings``. ``None`` keeps the historical
    two-leg Japanese default; ``()`` would be an EMPTY ladder, so never pass
    it as the "use the default" sentinel. *script_check* validates a
    single-byte leg (``utils.subtitle_encoding.script_check_kwarg``).

    ``max_words`` and ``max_word_bytes`` are Android-only allocation guards.
    When any bounded option is supplied, the decoded text is parsed
    incrementally: delimited, text, and structured JSON arrays are walked one
    line or one array item at a time, and the word set is checked as it grows.

    Omitting all Android-only options follows the pinned desktop materialized
    parsing path unchanged.
    """

    if max_words is not None and max_words <= 0:
        raise ValueError("max_words must be positive")
    if max_word_bytes is not None and max_word_bytes <= 0:
        raise ValueError("max_word_bytes must be positive")
    if max_words is not None or max_word_bytes is not None or cancel_check is not None:
        return _parse_bounded(
            path,
            encodings=encodings,
            script_check=script_check,
            max_words=max_words,
            max_word_bytes=max_word_bytes,
            cancel_check=cancel_check,
        )
    return _parse_materialized(path, encodings=encodings, script_check=script_check)


def _parse_materialized(
    path: Path,
    *,
    encodings: tuple[str, ...] | None,
    script_check: Callable[[str], bool] | None,
) -> KnownWordsImportResult:
    try:
        if path.stat().st_size > _MAX_IMPORT_BYTES:
            raise KnownWordsImportError("too_large")
        raw = path.read_bytes()
    except OSError as exc:
        raise KnownWordsImportError("unreadable") from exc
    text, encoding = _decode(raw, encodings, script_check)

    try:
        data = json.loads(text)
    except ValueError:
        if path.suffix.lower() == ".json":
            raise KnownWordsImportError("unrecognized") from None
        result = _parse_delimited_or_text(text)
    else:
        result = _parse_json(data)
    _log_import(path, result, encoding)
    return result


def _log_import(path: Path, result: KnownWordsImportResult, encoding: str) -> None:
    # Only the completed import gets a receipt. Every failure leaves through
    # KnownWordsImportError, whose ``reason`` the caller already reports; a
    # second line here would double-report the same refusal.
    log_summary(
        logger,
        "Known words import done",
        file=path,
        format=result.format_key,
        rows=result.total_entries,
        imported=len(result.words),
        skipped_malformed=result.skipped_malformed,
        encoding=encoding,
    )


def _parse_bounded(
    path: Path,
    *,
    encodings: tuple[str, ...] | None,
    script_check: Callable[[str], bool] | None,
    max_words: int | None,
    max_word_bytes: int | None,
    cancel_check: Callable[[], bool] | None,
) -> KnownWordsImportResult:
    try:
        if path.stat().st_size > _MAX_IMPORT_BYTES:
            raise KnownWordsImportError("too_large")
        _raise_if_cancelled(cancel_check)
        raw = path.read_bytes()
    except OSError as exc:
        raise KnownWordsImportError("unreadable") from exc
    _raise_if_cancelled(cancel_check)
    # The same shared ladder decoder as the desktop path, never a streaming
    # copy of it: its BOM precedence, Big5 guard, lossy mostly-UTF-8 leg and
    # single-byte NFC all need the whole input. The limits below guard the
    # word set, which is what outgrows the capped text.
    text, encoding = _decode(raw, encodings, script_check)
    del raw
    try:
        result = _parse_json_streamed(
            text,
            max_words=max_words,
            max_word_bytes=max_word_bytes,
            cancel_check=cancel_check,
        )
    except KnownWordsImportError as exc:
        if exc.reason != "invalid_json":
            raise
        if path.suffix.lower() == ".json":
            raise KnownWordsImportError("unrecognized") from None
        result = _parse_delimited_or_text_streamed(
            text,
            max_words=max_words,
            max_word_bytes=max_word_bytes,
            cancel_check=cancel_check,
        )
    _log_import(path, result, encoding)
    return result


def _result(
    format_key: str,
    words: set[str],
    total: int,
    skipped_malformed: int = 0,
) -> KnownWordsImportResult:
    if not words:
        raise KnownWordsImportError("no_known_words", format_key=format_key)
    return KnownWordsImportResult(
        format_key=format_key,
        words=frozenset(words),
        total_entries=total,
        skipped_malformed=skipped_malformed,
    )


def _add_bounded(
    words: set[str],
    word: str,
    *,
    max_words: int | None,
    max_word_bytes: int | None,
) -> None:
    if max_word_bytes is not None and len(word.encode("utf-8")) > max_word_bytes:
        raise KnownWordsImportError("limit_exceeded")
    words.add(word)
    if max_words is not None and len(words) > max_words:
        raise KnownWordsImportError("limit_exceeded")


def _raise_if_cancelled(cancel_check: Callable[[], bool] | None) -> None:
    if cancel_check is not None and cancel_check():
        raise KnownWordsImportError("cancelled")


class _TextChunks:
    """``read(size)`` over already-decoded text, for the incremental parsers.

    Not ``io.StringIO``: it copies its initial value into a buffer of four
    bytes per character, several times the capped input, where a slice keeps
    only one chunk alongside the text.
    """

    def __init__(self, text: str) -> None:
        self._text = text
        self._position = 0

    def read(self, size: int) -> str:
        chunk = self._text[self._position : self._position + size]
        self._position += len(chunk)
        return chunk


def _iter_lines(
    stream: _TextChunks,
    cancel_check: Callable[[], bool] | None,
) -> Iterator[str]:
    pending = ""
    while True:
        _raise_if_cancelled(cancel_check)
        chunk = stream.read(_READ_CHUNK_BYTES)
        eof = not chunk
        pending += chunk
        held_carriage_return = ""
        if not eof and pending.endswith("\r"):
            pending = pending[:-1]
            held_carriage_return = "\r"
        start = 0
        for match in _LINE_BOUNDARY_RE.finditer(pending):
            yield pending[start : match.start()]
            start = match.end()
        pending = pending[start:] + held_carriage_return
        if eof:
            if pending:
                yield pending
            return


def _row(line: str) -> list[str]:
    return next(csv.reader([line]))


class _JsonReader:
    """Incremental JSON reader that materializes at most one array item."""

    def __init__(
        self,
        stream: _TextChunks,
        cancel_check: Callable[[], bool] | None,
    ) -> None:
        self._stream = stream
        self._cancel_check = cancel_check
        self._decoder = json.JSONDecoder()
        self._buffer = ""
        self._position = 0
        self._eof = False

    def _compact(self) -> None:
        if self._position:
            self._buffer = self._buffer[self._position :]
            self._position = 0

    def _fill(self) -> None:
        if self._eof:
            return
        _raise_if_cancelled(self._cancel_check)
        chunk = self._stream.read(_READ_CHUNK_BYTES)
        if chunk:
            self._buffer += chunk
        else:
            self._eof = True

    def _skip_whitespace(self) -> None:
        while True:
            while self._position < len(self._buffer) and self._buffer[self._position].isspace():
                self._position += 1
            if self._position < len(self._buffer) or self._eof:
                return
            self._compact()
            self._fill()

    def peek(self) -> str | None:
        self._skip_whitespace()
        if self._position < len(self._buffer):
            return self._buffer[self._position]
        return None

    def consume(self, expected: str) -> None:
        if self.peek() != expected:
            raise KnownWordsImportError("invalid_json")
        self._position += 1

    def decode_value(self) -> Any:
        self._skip_whitespace()
        self._compact()
        while True:
            try:
                value, end = self._decoder.raw_decode(self._buffer)
            except json.JSONDecodeError as exc:
                if self._eof:
                    raise KnownWordsImportError("invalid_json") from exc
                self._fill()
                continue
            self._position = end
            return value

    def iter_array(self) -> Iterator[Any]:
        self.consume("[")
        if self.peek() == "]":
            self._position += 1
            return
        while True:
            yield self.decode_value()
            delimiter = self.peek()
            if delimiter == "]":
                self._position += 1
                return
            if delimiter != ",":
                raise KnownWordsImportError("invalid_json")
            self._position += 1

    def finish(self) -> None:
        if self.peek() is not None:
            raise KnownWordsImportError("invalid_json")


def _collect_jpdb(
    cards: Iterable[Any],
    *,
    max_words: int | None = None,
    max_word_bytes: int | None = None,
    cancel_check: Callable[[], bool] | None = None,
) -> tuple[set[str], int, int]:
    words: set[str] = set()
    total = 0
    skipped_malformed = 0
    for card in cards:
        _raise_if_cancelled(cancel_check)
        if not isinstance(card, dict) or not _clean(card.get("spelling")):
            skipped_malformed += 1
            continue
        total += 1
        raw_reviews = card.get("reviews")
        if raw_reviews is None:
            raw_reviews = []
        if not isinstance(raw_reviews, list):
            skipped_malformed += 1
            continue
        reviews = []
        for review in raw_reviews:
            if not isinstance(review, dict) or "grade" not in review:
                skipped_malformed += 1
                continue
            grade = review["grade"]
            if not isinstance(grade, str) or not grade:
                skipped_malformed += 1
                continue
            reviews.append(review)
        if not reviews:
            continue
        latest = max(reviews, key=_review_timestamp)
        if latest["grade"] not in _JPDB_EXCLUDED_GRADES:
            _add_bounded(
                words,
                _clean(card["spelling"]),
                max_words=max_words,
                max_word_bytes=max_word_bytes,
            )
    return words, total, skipped_malformed


def _parse_json_streamed(
    text: str,
    *,
    max_words: int | None,
    max_word_bytes: int | None,
    cancel_check: Callable[[], bool] | None,
) -> KnownWordsImportResult:
    reader = _JsonReader(_TextChunks(text), cancel_check)
    first = reader.peek()
    if first == "[":
        words: set[str] = set()
        total = 0
        valid = True
        for pair in reader.iter_array():
            total += 1
            if not (
                isinstance(pair, list) and len(pair) == 2 and isinstance(pair[0], str) and isinstance(pair[1], int)
            ):
                valid = False
                continue
            if pair[1] == 2 and _clean(pair[0]):
                _add_bounded(
                    words,
                    _clean(pair[0]),
                    max_words=max_words,
                    max_word_bytes=max_word_bytes,
                )
        reader.finish()
        if total and valid:
            return _result("migaku_legacy", words, total)
        raise KnownWordsImportError("unrecognized")

    if first != "{":
        reader.decode_value()
        reader.finish()
        raise KnownWordsImportError("unrecognized")

    reader.consume("{")
    jpdb: tuple[set[str], int, int] | None = None
    migaku: tuple[set[str], int] | None = None
    if reader.peek() != "}":
        while True:
            key = reader.decode_value()
            if not isinstance(key, str):
                raise KnownWordsImportError("invalid_json")
            reader.consume(":")
            if key == "cards_vocabulary_jp_en" and reader.peek() == "[":
                jpdb = _collect_jpdb(
                    reader.iter_array(),
                    max_words=max_words,
                    max_word_bytes=max_word_bytes,
                    cancel_check=cancel_check,
                )
            elif key == "words" and reader.peek() == "[":
                found_words: set[str] = set()
                entries = 0
                for item in reader.iter_array():
                    _raise_if_cancelled(cancel_check)
                    if not isinstance(item, dict) or "word" not in item or "status" not in item:
                        continue
                    entries += 1
                    word = _clean(item["word"])
                    if item["status"] == "KNOWN" and word:
                        _add_bounded(
                            found_words,
                            word,
                            max_words=max_words,
                            max_word_bytes=max_word_bytes,
                        )
                migaku = (found_words, entries) if entries else None
            else:
                reader.decode_value()
                if key == "cards_vocabulary_jp_en":
                    jpdb = None
                elif key == "words":
                    migaku = None
            delimiter = reader.peek()
            if delimiter == "}":
                break
            if delimiter != ",":
                raise KnownWordsImportError("invalid_json")
            reader.consume(",")
    reader.consume("}")
    reader.finish()

    if jpdb is not None:
        words, total, skipped_malformed = jpdb
        return _result("jpdb", words, total, skipped_malformed)
    if migaku is not None:
        words, total = migaku
        return _result("migaku_json", words, total)
    raise KnownWordsImportError("unrecognized")


def _parse_delimited_or_text_streamed(
    text: str,
    *,
    max_words: int | None,
    max_word_bytes: int | None,
    cancel_check: Callable[[], bool] | None,
) -> KnownWordsImportResult:
    try:
        lines = _iter_lines(_TextChunks(text), cancel_check)
        first_line = next(lines, None)
        header_row = _row(first_line) if first_line is not None else []
        header = tuple(cell.strip().lower() for cell in header_row)
        words: set[str] = set()
        total = 0

        if header[:4] == _MIGAKU_CSV_HEADER:
            for row in csv.reader(lines):
                if not row or not _clean(row[0]):
                    continue
                total += 1
                if len(row) >= 4 and row[3].strip().upper() == "KNOWN":
                    word = row[0].strip()
                    if word:
                        _add_bounded(
                            words,
                            word,
                            max_words=max_words,
                            max_word_bytes=max_word_bytes,
                        )
            return _result("migaku_csv", words, total)

        if header[:1] == (_ANKIMORPHS_LEMMA_HEADER,):
            for row in csv.reader(lines):
                if not row or not _clean(row[0]):
                    continue
                total += 1
                _add_bounded(
                    words,
                    row[0].strip(),
                    max_words=max_words,
                    max_word_bytes=max_word_bytes,
                )
            return _result("ankimorphs", words, total)

        for line in chain(() if first_line is None else (first_line,), lines):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if "," in stripped or "\t" in stripped:
                delimiter = "," if "," in stripped else "\t"
                stripped = next(csv.reader([stripped], delimiter=delimiter))[0].strip()
                if not stripped:
                    continue
            total += 1
            _add_bounded(
                words,
                stripped,
                max_words=max_words,
                max_word_bytes=max_word_bytes,
            )
        return _result("generic", words, total)
    except csv.Error as exc:
        raise KnownWordsImportError("unreadable") from exc


# ----------------------------------------------------------------------
# JSON formats
# ----------------------------------------------------------------------


def _parse_json(data: Any) -> KnownWordsImportResult:
    if isinstance(data, dict) and isinstance(data.get("cards_vocabulary_jp_en"), list):
        return _parse_jpdb(data["cards_vocabulary_jp_en"])
    if isinstance(data, dict) and isinstance(data.get("words"), list):
        entries = [w for w in data["words"] if isinstance(w, dict) and "word" in w and "status" in w]
        if entries:
            words = {_clean(w["word"]) for w in entries if w["status"] == "KNOWN" and _clean(w["word"])}
            return _result("migaku_json", words, len(entries))
    if (
        isinstance(data, list)
        and data
        and all(
            isinstance(pair, list) and len(pair) == 2 and isinstance(pair[0], str) and isinstance(pair[1], int)
            for pair in data
        )
    ):
        words = {_clean(word) for word, status in data if status == 2 and _clean(word)}
        return _result("migaku_legacy", words, len(data))
    raise KnownWordsImportError("unrecognized")


def _parse_jpdb(cards: list[Any]) -> KnownWordsImportResult:
    words, total, skipped_malformed = _collect_jpdb(cards)
    return _result("jpdb", words, total, skipped_malformed)


# ----------------------------------------------------------------------
# Delimited / plain-text formats
# ----------------------------------------------------------------------


def _parse_delimited_or_text(text: str) -> KnownWordsImportResult:
    rows = list(csv.reader(text.splitlines()))
    header = tuple(cell.strip().lower() for cell in rows[0]) if rows else ()

    if header[:4] == _MIGAKU_CSV_HEADER:
        entries = [row for row in rows[1:] if row and _clean(row[0])]
        words = {row[0].strip() for row in entries if len(row) >= 4 and row[3].strip().upper() == "KNOWN"}
        return _result("migaku_csv", words, len(entries))

    if header[:1] == (_ANKIMORPHS_LEMMA_HEADER,):
        entries = [row for row in rows[1:] if row and _clean(row[0])]
        # Lemma column only: verbs/adjectives mine as lemma and nouns mine as
        # surface == lemma, so inflected surfaces could never match a card front.
        words = {row[0].strip() for row in entries}
        return _result("ankimorphs", words, len(entries))

    return _parse_generic(text)


def _parse_generic(text: str) -> KnownWordsImportResult:
    """One word per line; first csv/tab cell when the line is delimited."""
    words: set[str] = set()
    total = 0
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "," in stripped or "\t" in stripped:
            delimiter = "," if "," in stripped else "\t"
            stripped = next(csv.reader([stripped], delimiter=delimiter))[0].strip()
            if not stripped:
                continue
        total += 1
        words.add(stripped)
    return _result("generic", words, total)


def _decode(
    raw: bytes,
    encodings: tuple[str, ...] | None = None,
    script_check: Callable[[str], bool] | None = None,
) -> tuple[str, str]:
    # The shared ladder decoder, not a second copy: a Taiwanese export is Big5,
    # which gb18030 swallows into PUA mojibake without raising, and only that
    # one has the guard for it.
    #
    # `is None`, not truthiness: `()` is an EMPTY ladder the caller asked for,
    # not a request for the Japanese default. Truthiness would decode a
    # profile's deliberately-empty ladder as Japanese.
    try:
        return decode_with_ladder(
            raw,
            encodings=_DEFAULT_ENCODINGS if encodings is None else encodings,
            script_check=script_check,
        )
    except SetupError as exc:
        # Every candidate failing => "undecodable", not "unreadable": the bytes
        # were read, so only the encoding is at fault.
        raise KnownWordsImportError("undecodable") from exc


def _clean(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


def _review_timestamp(review: dict[str, Any]) -> float:
    """Sort key for jpdb reviews: the numeric ``timestamp``, else 0.

    A non-numeric ``timestamp`` (a drifted/hand-edited export) is coerced to 0
    rather than fed to ``max()`` — otherwise comparing a str against the int-0
    default would raise TypeError and escape the KnownWordsImportError contract
    as a generic "unexpected error". ``bool`` is an ``int`` subclass and floats
    fine.
    """
    ts = review.get("timestamp", 0)
    return float(ts) if isinstance(ts, (int, float)) else 0.0
