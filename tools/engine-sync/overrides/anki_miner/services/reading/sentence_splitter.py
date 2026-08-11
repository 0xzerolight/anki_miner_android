"""Depth-gated Japanese sentence splitter for the reading-tab loaders.

Net-new and self-contained: this module owns the character policy (there is no
shared scanner to reuse — the old Yomitan-derived one was deleted in 3e10353).
A single left-to-right pass tracks bracket/quote depth; a terminator run at
depth 0 ends a sentence, a run inside brackets does not. A run of two-or-more
``．`` (or the ellipsis marks ``…‥``) is an ellipsis, not a terminator, so
``……。`` still splits on the ``。``. Only *matched* bracket pairs gate depth: a
pre-scan (``_matched_openers``) pairs openers to closers, so an unmatched opener
and an unmatched closer are both treated as ordinary characters and cannot
suppress splitting. The unterminated tail is flushed.
"""

from __future__ import annotations

from array import array

from anki_miner.models.reading import (
    check_reading_unit_capacity,
    check_reading_unit_text_capacity,
)

# Terminators that always end a sentence at depth 0.
_HARD_TERMINATORS = frozenset("。！？!?‼⁉⁇⁈")
# Full-width period: a lone one terminates, a run of 2+ is an ellipsis.
_DOT = "．"
# Pure ellipsis marks — never terminate on their own.
_ELLIPSIS = frozenset("…‥")
_SENTENCE_PUNCT = _HARD_TERMINATORS | _ELLIPSIS | {_DOT}

# Bracket/quote pairs; depth rises on an opener, falls on a matching closer.
_OPENERS = frozenset("「『（〔［｛〈《【([{｟〝")
_CLOSERS = frozenset("」』）〕］｝〉》】)]}｠〟")
_CANCELLATION_CHECK_INTERVAL = 1_024


def _run_is_terminating(run: str) -> bool:
    """Whether a maximal run of sentence punctuation ends a sentence.

    Hard terminators always do; a lone ``．`` does; a 2+ run of ``．`` and the
    ellipsis marks do not.
    """
    if any(ch in _HARD_TERMINATORS for ch in run):
        return True
    i = 0
    n = len(run)
    while i < n:
        if run[i] == _DOT:
            j = i
            while j < n and run[j] == _DOT:
                j += 1
            if j - i == 1:  # a lone full-width period terminates
                return True
            i = j
        else:
            i += 1
    return False


def _matched_openers(text: str) -> bytearray:
    """Indices of openers that have a matching closer later in ``text``.

    A plain LIFO stack: any closer pops the nearest still-open opener (bracket
    *family* is not checked — a shorter split on cross-family OCR garbage is
    harmless). Openers still on the stack at the end are unmatched and must not
    gate depth, so an unbalanced ``「`` no longer suppresses every terminator
    after it (the mokuro cover-blurb "wall of text" bug).
    """
    stack = array("I")
    matched = bytearray((len(text) + 7) // 8)
    for i, ch in enumerate(text):
        if i % _CANCELLATION_CHECK_INTERVAL == 0:
            check_reading_unit_capacity(0)
        if ch in _OPENERS:
            stack.append(i)
        elif ch in _CLOSERS and stack:
            opener = stack.pop()
            matched[opener >> 3] |= 1 << (opener & 7)
    return matched


def _is_matched_opener(matched: bytearray, index: int) -> bool:
    return bool(matched[index >> 3] & (1 << (index & 7)))


def _utf8_bytes(character: str) -> int:
    code_point = ord(character)
    if code_point <= 0x7F:
        return 1
    if code_point <= 0x7FF:
        return 2
    if code_point <= 0xFFFF:
        return 3
    return 4


def _append_text(
    buf: list[str], value: str, retained_bytes: int, value_bytes: int | None = None
) -> int:
    observed = retained_bytes + (
        sum(_utf8_bytes(character) for character in value)
        if value_bytes is None
        else value_bytes
    )
    check_reading_unit_text_capacity(observed)
    buf.append(value)
    return observed


def _append_segment(segments: list[str], segment: str) -> None:
    if segment.strip():
        check_reading_unit_capacity(len(segments) + 1)
    segments.append(segment)


def split_sentences(text: str, *, split_adjacent_quotes: bool = False) -> list[str]:
    """Split ``text`` into sentences; empty/whitespace-only results dropped.

    ``split_adjacent_quotes`` inserts a break between an adjacent ``」「`` pair
    at depth 0 (used only by the mokuro overflow fallback).
    """
    matched_openers = _matched_openers(text)
    segments: list[str] = []
    buf: list[str] = []
    segment_bytes = 0
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        if i % _CANCELLATION_CHECK_INTERVAL == 0:
            check_reading_unit_capacity(0)
        c = text[i]
        if c in _OPENERS:
            if _is_matched_opener(
                matched_openers, i
            ):  # unmatched openers stay depth-neutral
                depth += 1
            segment_bytes = _append_text(buf, c, segment_bytes)
            i += 1
        elif c in _CLOSERS:
            if depth > 0:  # unmatched closer: never goes negative
                depth -= 1
            segment_bytes = _append_text(buf, c, segment_bytes)
            i += 1
            if split_adjacent_quotes and c == "」" and depth == 0 and i < n and text[i] == "「":
                _append_segment(segments, "".join(buf))
                buf = []
                segment_bytes = 0
        elif depth == 0 and c in _SENTENCE_PUNCT:
            j = i
            run_bytes = 0
            while j < n and text[j] in _SENTENCE_PUNCT:  # absorb the run
                run_bytes += _utf8_bytes(text[j])
                check_reading_unit_text_capacity(segment_bytes + run_bytes)
                j += 1
            run = text[i:j]
            segment_bytes = _append_text(buf, run, segment_bytes, run_bytes)
            i = j
            if _run_is_terminating(run):
                _append_segment(segments, "".join(buf))
                buf = []
                segment_bytes = 0
        else:
            segment_bytes = _append_text(buf, c, segment_bytes)
            i += 1
    if buf:  # flush the unterminated tail
        _append_segment(segments, "".join(buf))
    return [s for s in (seg.strip() for seg in segments) if s]
