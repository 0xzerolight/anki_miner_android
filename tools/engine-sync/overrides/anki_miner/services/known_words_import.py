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

Android adds optional streaming limits to the desktop parser. Calls without
those options retain the pinned desktop behavior byte-for-byte at the API
boundary; Android supplies them so a large plain-text export cannot build an
over-limit set before rejection.
"""

from __future__ import annotations

import codecs
import csv
import json
import re
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass
from itertools import chain
from pathlib import Path
from typing import Any, TextIO

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
_MAX_JSON_NESTING_DEPTH = 256
_LINE_BOUNDARY_RE = re.compile(r"\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]")


class KnownWordsImportError(Exception):
    """Raised when a file yields no importable known words.

    ``reason`` distinguishes the failure class for user messaging:
    ``"unreadable"`` (missing/undecodable file), ``"unrecognized"`` (format
    not matched — including valid JSON matching no known signature), or
    ``"no_known_words"`` (format matched but zero entries qualified;
    ``format_key`` carries the detected format in that case).

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
    max_words: int | None = None,
    max_word_bytes: int | None = None,
    cancel_check: Callable[[], bool] | None = None,
) -> KnownWordsImportResult:
    """Detect the export format of ``path`` and extract its known words.

    Content is tried as JSON first; valid JSON that matches no known JSON
    signature raises ``unrecognized`` rather than falling through to the line
    reader (which would ingest syntax fragments as words). ``.json`` files
    are never read as generic word lists for the same reason.

    Bytes are decoded utf-8-sig first (BOM-stripping), then cp932 — the
    Japanese Windows/Excel default for hand-made lists — before giving up.

    ``max_words`` and ``max_word_bytes`` are Android-only allocation guards.
    Delimited, text, and structured JSON arrays are decoded and parsed
    incrementally when any bounded option is supplied.

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
            max_words=max_words,
            max_word_bytes=max_word_bytes,
            cancel_check=cancel_check,
        )
    return _parse_materialized(path)


def _parse_materialized(path: Path) -> KnownWordsImportResult:
    try:
        if path.stat().st_size > _MAX_IMPORT_BYTES:
            raise KnownWordsImportError("unreadable")
        raw = path.read_bytes()
    except OSError as exc:
        raise KnownWordsImportError("unreadable") from exc
    text = _decode(raw)

    try:
        data = json.loads(text)
    except ValueError:
        if path.suffix.lower() == ".json":
            raise KnownWordsImportError("unrecognized") from None
        return _parse_delimited_or_text(text)
    return _parse_json(data)


def _parse_bounded(
    path: Path,
    *,
    max_words: int | None,
    max_word_bytes: int | None,
    cancel_check: Callable[[], bool] | None,
) -> KnownWordsImportResult:
    try:
        if path.stat().st_size > _MAX_IMPORT_BYTES:
            raise KnownWordsImportError("unreadable")
    except OSError as exc:
        raise KnownWordsImportError("unreadable") from exc
    _raise_if_cancelled(cancel_check)
    encoding = _stream_encoding(path, cancel_check)
    try:
        return _parse_json_streamed(
            path,
            encoding,
            max_words=max_words,
            max_word_bytes=max_word_bytes,
            cancel_check=cancel_check,
        )
    except KnownWordsImportError as exc:
        if exc.reason != "invalid_json":
            raise
        if path.suffix.lower() == ".json":
            raise KnownWordsImportError("unrecognized") from None
    return _parse_delimited_or_text_streamed(
        path,
        encoding,
        max_words=max_words,
        max_word_bytes=max_word_bytes,
        cancel_check=cancel_check,
    )


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


def _stream_encoding(
    path: Path,
    cancel_check: Callable[[], bool] | None,
) -> str:
    for encoding in ("utf-8-sig", "cp932"):
        decoder = codecs.getincrementaldecoder(encoding)()
        try:
            with path.open("rb", buffering=0) as stream:
                while True:
                    _raise_if_cancelled(cancel_check)
                    chunk = stream.read(_READ_CHUNK_BYTES)
                    if not chunk:
                        decoder.decode(b"", final=True)
                        return encoding
                    decoder.decode(chunk)
        except UnicodeDecodeError:
            continue
        except OSError as exc:
            raise KnownWordsImportError("unreadable") from exc
    raise KnownWordsImportError("unreadable")


def _iter_lines(
    stream: TextIO,
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
    """Incremental JSON reader with token-wise container skipping."""

    def __init__(
        self,
        stream: TextIO,
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

    def _decode_number(self) -> int | float:
        self._skip_whitespace()
        self._compact()
        while True:
            end = 0
            while end < len(self._buffer):
                character = self._buffer[end]
                if character.isspace() or character in ",]}":
                    break
                end += 1
            if end == len(self._buffer) and not self._eof:
                self._fill()
                continue
            token = self._buffer[:end]
            try:
                value, decoded_end = self._decoder.raw_decode(token)
            except json.JSONDecodeError as exc:
                raise KnownWordsImportError("invalid_json") from exc
            if decoded_end != len(token) or type(value) not in (int, float):
                raise KnownWordsImportError("invalid_json")
            self._position = end
            return value

    def decode_value(self) -> Any:
        first = self.peek()
        if first is not None and (first == "-" or first.isdigit()):
            return self._decode_number()
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

    def iter_array(
        self,
        read_item: Callable[[], Any] | None = None,
    ) -> Iterator[Any]:
        self.consume("[")
        if self.peek() == "]":
            self._position += 1
            return
        while True:
            yield self.decode_value() if read_item is None else read_item()
            delimiter = self.peek()
            if delimiter == "]":
                self._position += 1
                return
            if delimiter != ",":
                raise KnownWordsImportError("invalid_json")
            self._position += 1

    def iter_object(self) -> Iterator[str]:
        self.consume("{")
        if self.peek() == "}":
            self._position += 1
            return
        while True:
            key = self.decode_value()
            if not isinstance(key, str):
                raise KnownWordsImportError("invalid_json")
            self.consume(":")
            yield key
            delimiter = self.peek()
            if delimiter == "}":
                self._position += 1
                return
            if delimiter != ",":
                raise KnownWordsImportError("invalid_json")
            self._position += 1

    def skip_value(self) -> None:
        first = self.peek()
        if first not in {"[", "{"}:
            self.decode_value()
            return
        if _MAX_JSON_NESTING_DEPTH < 1:
            raise KnownWordsImportError("limit_exceeded")
        self._position += 1
        stack: list[tuple[str, str]] = [
            ("array", "value_or_end") if first == "[" else ("object", "key_or_end")
        ]
        while stack:
            kind, state = stack[-1]
            if kind == "array":
                if state == "value_or_end" and self.peek() == "]":
                    self._position += 1
                    stack.pop()
                elif state in {"value_or_end", "value"}:
                    stack[-1] = (kind, "comma_or_end")
                    self._skip_scalar_or_push(stack)
                elif self.peek() == "]":
                    self._position += 1
                    stack.pop()
                else:
                    self.consume(",")
                    stack[-1] = (kind, "value")
            elif state == "key_or_end" and self.peek() == "}":
                self._position += 1
                stack.pop()
            elif state in {"key_or_end", "key"}:
                key = self.decode_value()
                if not isinstance(key, str):
                    raise KnownWordsImportError("invalid_json")
                self.consume(":")
                stack[-1] = (kind, "value")
            elif state == "value":
                stack[-1] = (kind, "comma_or_end")
                self._skip_scalar_or_push(stack)
            elif self.peek() == "}":
                self._position += 1
                stack.pop()
            else:
                self.consume(",")
                stack[-1] = (kind, "key")

    def _skip_scalar_or_push(self, stack: list[tuple[str, str]]) -> None:
        first = self.peek()
        if first == "[":
            if len(stack) >= _MAX_JSON_NESTING_DEPTH:
                raise KnownWordsImportError("limit_exceeded")
            self._position += 1
            stack.append(("array", "value_or_end"))
        elif first == "{":
            if len(stack) >= _MAX_JSON_NESTING_DEPTH:
                raise KnownWordsImportError("limit_exceeded")
            self._position += 1
            stack.append(("object", "key_or_end"))
        else:
            self.decode_value()

    def finish(self) -> None:
        if self.peek() is not None:
            raise KnownWordsImportError("invalid_json")


_JSON_CONTAINER = object()


def _read_json_leaf(reader: _JsonReader) -> Any:
    if reader.peek() in {"[", "{"}:
        reader.skip_value()
        return _JSON_CONTAINER
    return reader.decode_value()


def _read_migaku_item(reader: _JsonReader) -> tuple[bool, str, Any]:
    _raise_if_cancelled(reader._cancel_check)
    if reader.peek() != "{":
        reader.skip_value()
        return False, "", None
    word: Any = _JSON_CONTAINER
    status: Any = _JSON_CONTAINER
    word_seen = False
    status_seen = False
    for key in reader.iter_object():
        if key == "word":
            word_seen = True
            word = _read_json_leaf(reader)
        elif key == "status":
            status_seen = True
            status = _read_json_leaf(reader)
        else:
            reader.skip_value()
    return word_seen and status_seen, _clean(word), status


def _read_migaku_legacy_pair(reader: _JsonReader) -> tuple[str, int] | None:
    if reader.peek() != "[":
        reader.skip_value()
        return None
    first: Any = _JSON_CONTAINER
    second: Any = _JSON_CONTAINER
    count = 0
    for value in reader.iter_array(lambda: _read_json_leaf(reader)):
        count += 1
        if count == 1:
            first = value
        elif count == 2:
            second = value
    if count != 2 or not isinstance(first, str) or not isinstance(second, int):
        return None
    return first, second


def _read_jpdb_review(reader: _JsonReader) -> tuple[str, float] | None:
    if reader.peek() != "{":
        reader.skip_value()
        return None
    grade: Any = _JSON_CONTAINER
    timestamp: Any = 0
    for key in reader.iter_object():
        if key == "grade":
            grade = _read_json_leaf(reader)
        elif key == "timestamp":
            timestamp = _read_json_leaf(reader)
        else:
            reader.skip_value()
    if not isinstance(grade, str) or not grade:
        return None
    numeric_timestamp = float(timestamp) if isinstance(timestamp, (int, float)) else 0.0
    return grade, numeric_timestamp


def _read_jpdb_reviews(reader: _JsonReader) -> tuple[str | None, int]:
    latest_grade: str | None = None
    latest_timestamp = 0.0
    skipped_malformed = 0
    for review in reader.iter_array(lambda: _read_jpdb_review(reader)):
        if review is None:
            skipped_malformed += 1
            continue
        grade, timestamp = review
        if latest_grade is None or timestamp > latest_timestamp:
            latest_grade = grade
            latest_timestamp = timestamp
    return latest_grade, skipped_malformed


def _read_jpdb_card(reader: _JsonReader) -> tuple[int, int, str | None]:
    _raise_if_cancelled(reader._cancel_check)
    if reader.peek() != "{":
        reader.skip_value()
        return 0, 1, None
    spelling: Any = _JSON_CONTAINER
    reviews_valid = True
    latest_grade: str | None = None
    review_skips = 0
    for key in reader.iter_object():
        if key == "spelling":
            spelling = _read_json_leaf(reader)
        elif key == "reviews":
            if reader.peek() == "[":
                latest_grade, review_skips = _read_jpdb_reviews(reader)
                reviews_valid = True
            else:
                raw_reviews = _read_json_leaf(reader)
                reviews_valid = raw_reviews is None
                latest_grade = None
                review_skips = 0
        else:
            reader.skip_value()
    word = _clean(spelling)
    if not word:
        return 0, 1, None
    if not reviews_valid:
        return 1, 1, None
    if latest_grade is not None and latest_grade not in _JPDB_EXCLUDED_GRADES:
        return 1, review_skips, word
    return 1, review_skips, None


def _collect_jpdb_streamed(
    reader: _JsonReader,
    *,
    max_words: int | None,
    max_word_bytes: int | None,
) -> tuple[set[str], int, int]:
    words: set[str] = set()
    total = 0
    skipped_malformed = 0
    for card_total, card_skips, word in reader.iter_array(
        lambda: _read_jpdb_card(reader)
    ):
        total += card_total
        skipped_malformed += card_skips
        if word is not None:
            _add_bounded(
                words,
                word,
                max_words=max_words,
                max_word_bytes=max_word_bytes,
            )
    return words, total, skipped_malformed


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
    path: Path,
    encoding: str,
    *,
    max_words: int | None,
    max_word_bytes: int | None,
    cancel_check: Callable[[], bool] | None,
) -> KnownWordsImportResult:
    try:
        with path.open("r", encoding=encoding, newline="") as stream:
            reader = _JsonReader(stream, cancel_check)
            first = reader.peek()
            if first == "[":
                words: set[str] = set()
                total = 0
                valid = True
                for pair in reader.iter_array(lambda: _read_migaku_legacy_pair(reader)):
                    total += 1
                    if pair is None:
                        valid = False
                        continue
                    word, status = pair
                    if status == 2 and _clean(word):
                        _add_bounded(
                            words,
                            word,
                            max_words=max_words,
                            max_word_bytes=max_word_bytes,
                        )
                reader.finish()
                if total and valid:
                    return _result("migaku_legacy", words, total)
                raise KnownWordsImportError("unrecognized")

            if first != "{":
                reader.skip_value()
                reader.finish()
                raise KnownWordsImportError("unrecognized")

            jpdb: tuple[set[str], int, int] | None = None
            migaku: tuple[set[str], int] | None = None
            for key in reader.iter_object():
                if key == "cards_vocabulary_jp_en" and reader.peek() == "[":
                    jpdb = _collect_jpdb_streamed(
                        reader,
                        max_words=max_words,
                        max_word_bytes=max_word_bytes,
                    )
                elif key == "words" and reader.peek() == "[":
                    found_words: set[str] = set()
                    entries = 0
                    for valid_item, word, status in reader.iter_array(
                        lambda: _read_migaku_item(reader)
                    ):
                        if not valid_item:
                            continue
                        entries += 1
                        if status == "KNOWN" and word:
                            _add_bounded(
                                found_words,
                                word,
                                max_words=max_words,
                                max_word_bytes=max_word_bytes,
                            )
                    migaku = (found_words, entries) if entries else None
                else:
                    reader.skip_value()
                    if key == "cards_vocabulary_jp_en":
                        jpdb = None
                    elif key == "words":
                        migaku = None
            reader.finish()
    except KnownWordsImportError:
        raise
    except (OSError, UnicodeError) as exc:
        raise KnownWordsImportError("unreadable") from exc

    if jpdb is not None:
        words, total, skipped_malformed = jpdb
        return _result("jpdb", words, total, skipped_malformed)
    if migaku is not None:
        words, total = migaku
        return _result("migaku_json", words, total)
    raise KnownWordsImportError("unrecognized")


def _parse_delimited_or_text_streamed(
    path: Path,
    encoding: str,
    *,
    max_words: int | None,
    max_word_bytes: int | None,
    cancel_check: Callable[[], bool] | None,
) -> KnownWordsImportResult:
    try:
        with path.open("r", encoding=encoding, newline="") as stream:
            lines = _iter_lines(stream, cancel_check)
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
    except KnownWordsImportError:
        raise
    except (OSError, UnicodeError, csv.Error) as exc:
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
        words = {word for word, status in data if status == 2 and _clean(word)}
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


def _decode(raw: bytes) -> str:
    # utf-8-sig strips a Windows/Excel BOM that would otherwise break json.loads
    # and the exact first-cell header matches; cp932 covers Shift-JIS lists
    # exported from Japanese Notepad/Excel. Both failing ⇒ truly unreadable.
    for encoding in ("utf-8-sig", "cp932"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise KnownWordsImportError("unreadable")


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
