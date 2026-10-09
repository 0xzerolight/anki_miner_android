"""Card creation inside a real mining run: Stop and oversized notes.

Each test runs ``test_secondary_subtitle._run``: one wire request through
``mining._process_episode`` into the real vendored ``EpisodeProcessor``, whose
``_phase5_create`` hands the cards to the real ``AndroidAnkiAdapter`` and the
in-memory Kotlin Anki double. The known-word database is a recorder, so each
test can see what phase 5 recorded as mined.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

import android_bridge.mining as mining
import pytest
from test_anki_adapter import RUN_ID, FakeKotlinAnki
from test_secondary_subtitle import _run


class _KnownWords:
    """The two ``KnownWordDB`` calls ``_phase5_create`` makes."""

    def __init__(self) -> None:
        self.added: list[tuple[set[str], str]] = []

    def is_available(self) -> bool:
        return True

    def add_words_with_receipt(self, words: set[str], source: str) -> set[str]:
        self.added.append((set(words), source))
        return set(words)


def _words(count: int, *, oversized_sentence_at: int | None = None) -> list[Any]:
    from android_bridge.anki_adapter import _MAX_FIELD_VALUE_UTF8_BYTES
    from anki_miner.models import TokenizedWord

    oversized = "あ" * (_MAX_FIELD_VALUE_UTF8_BYTES // 3 + 1)
    return [
        TokenizedWord(
            surface=f"語{index}",
            lemma=f"語{index}",
            reading="ゴ",
            sentence=oversized if index == oversized_sentence_at else "猫を見る。",
            start_time=1.0,
            end_time=3.0,
            duration=2.0,
            expression_furigana=f"語{index}[ご]",
            expression_reading="ご",
            lemma_reading="ご",
            sentence_furigana="猫[ねこ]を見[み]る。",
            sentence_reading="ねこをみる。",
            pos="名詞",
        )
        for index in range(count)
    ]


def _written_keys(request: dict[str, Any], *, created: int) -> set[str]:
    """The first ``created`` duplicate keys of one createNotes request."""

    return {note["duplicateCandidate"]["key"] for note in request["payload"]["notes"][:created]}


def test_stop_between_create_callbacks_ends_cancelled_with_its_cards(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cancel_event = threading.Event()

    class StopAfterFirstCreate(FakeKotlinAnki):
        """Kotlin's control dispatch sets the job's event once 100 notes are written."""

        def ankiCreateNotes(self, raw: str) -> str:
            response = super().ankiCreateNotes(raw)
            cancel_event.set()
            return response

    known = _KnownWords()
    result, kotlin, _presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=None,
        offset_ms=0,
        kotlin=StopAfterFirstCreate(),
        cancel_event=cancel_event,
        words=_words(101),
        known_word_db=known,
    )
    from anki_miner.models.processing import CANCELLED_ERROR, MiningOutcome, classify_result

    assert classify_result(result) is MiningOutcome.CANCELLED
    assert result.errors == [CANCELLED_ERROR]
    assert result.cards_created == 100
    assert result.card_ids == list(range(1000, 1100))
    (first_create,) = kotlin.requests_for("ankiCreateNotes")
    written = _written_keys(first_create, created=100)
    assert len(written) == 100
    # Phase 5's tail ran: exactly the written words are recorded as mined.
    assert known.added == [(written, "mined")]
    outcome, terminal = mining._result_terminal(RUN_ID, result)
    payload = json.loads(terminal)["payload"]
    assert outcome == "cancelled"
    assert payload["error"] is None
    assert payload["result"]["cardsCreated"] == 100


def test_stop_reported_inside_a_create_callback_ends_cancelled(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Kotlin cancels its own run token first and forwards job.cancel to Python
    # on another thread, so the callback's cancelled row can arrive while the
    # job's event is still clear.
    cancel_event = threading.Event()
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [
        (["created", "failed"], {"code": "cancelled", "message": "user stopped", "retryable": False}),
    ]
    known = _KnownWords()
    result, kotlin, _presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=None,
        offset_ms=0,
        kotlin=kotlin,
        cancel_event=cancel_event,
        words=_words(2),
        known_word_db=known,
    )
    from anki_miner.models.processing import CANCELLED_ERROR, MiningOutcome, classify_result

    assert classify_result(result) is MiningOutcome.CANCELLED
    assert result.errors == [CANCELLED_ERROR]
    assert result.cards_created == 1
    (create,) = kotlin.requests_for("ankiCreateNotes")
    assert known.added == [(_written_keys(create, created=1), "mined")]
    assert cancel_event.is_set()


def test_note_over_a_size_limit_is_skipped_and_the_run_succeeds(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # One curated word's sentence passes the per-field limit. Desktop creates
    # every other card; so must the run.
    known = _KnownWords()
    result, kotlin, _presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=None,
        offset_ms=0,
        words=_words(4, oversized_sentence_at=1),
        known_word_db=known,
    )
    from anki_miner.models.processing import MiningOutcome, classify_result

    assert classify_result(result) is MiningOutcome.SUCCESS
    assert result.cards_created == 3
    (create,) = kotlin.requests_for("ankiCreateNotes")
    written = _written_keys(create, created=3)
    assert len(written) == 3
    assert "語1" not in written
    assert known.added == [(written, "mined")]
