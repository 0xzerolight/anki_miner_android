"""Count a word as known only after its card has been reviewed, inside a real mining run.

Each test runs ``test_secondary_subtitle._run``: one wire request through
``mining._process_episode`` into the real vendored ``EpisodeProcessor``, whose phase 2
reads the collection through the real ``AndroidAnkiAdapter`` and the in-memory Kotlin
Anki double. The line holds 猫, whose card is already in Anki, and 犬, which is not.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from test_anki_adapter import FakeKotlinAnki, MarkedScanFailingKotlin
from test_secondary_subtitle import _run

_LINE = "猫と犬を見る。"


def _word(surface: str) -> Any:
    from anki_miner.models import TokenizedWord

    return TokenizedWord(
        surface=surface,
        lemma=surface,
        reading="ヨミ",
        sentence=_LINE,
        start_time=1.0,
        end_time=3.0,
        duration=2.0,
        expression_furigana=f"{surface}[よみ]",
        expression_reading="よみ",
        lemma_reading="よみ",
        pos="名詞",
    )


@pytest.mark.parametrize(
    ("setting", "reviewed", "line_unknowns"),
    [
        (True, False, 2),
        (True, True, 1),
        (False, False, 1),
    ],
    ids=["on-unreviewed", "on-reviewed", "off"],
)
def test_an_unreviewed_card_scores_as_unknown_but_is_never_mined_again(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
    setting: bool,
    reviewed: bool,
    line_unknowns: int,
) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    from anki_miner.models.word import LineLemmas

    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["猫"]
    kotlin.known_note_reviewed = [reviewed]
    curated: list[Any] = []

    def curate(words: list[Any]) -> list[Any]:
        curated.extend(words)
        return list(words)

    result, kotlin, presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=None,
        offset_ms=0,
        kotlin=kotlin,
        words=[_word("猫"), _word("犬")],
        curate=curate,
        config_changes={"include_known_words": False, "known_from_reviewed_cards": setting},
        line_index=[LineLemmas(_LINE, frozenset({"猫", "犬"}), 1.0, 3.0, 2.0)],
    )

    assert (result.errors, presenter.errors) == ([], [])
    # Mining still excludes every card in Anki, reviewed or not.
    assert [word.mined_form for word in curated] == ["犬"]
    # The curator's unknowns column is the i+1 basis: an unreviewed 猫 still counts there.
    assert curated[0].line_unknown_count == line_unknowns
    assert result.cards_created == 1


def test_a_failed_reviewed_search_scores_against_every_card_and_the_run_completes(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Desktop degrades only its reviewed query; so does the marked walk here, which phase 2's
    # every-card read makes with the setting on.
    pytest.importorskip("requests", reason="runtime dependency lane")
    from anki_miner.models.word import LineLemmas

    kotlin = MarkedScanFailingKotlin()
    kotlin.known_fields = ["猫"]
    kotlin.known_note_reviewed = [False]
    curated: list[Any] = []

    def curate(words: list[Any]) -> list[Any]:
        curated.extend(words)
        return list(words)

    result, kotlin, presenter = _run(
        tmp_path,
        initialized_bridge_home,
        monkeypatch,
        translation=None,
        offset_ms=0,
        kotlin=kotlin,
        words=[_word("猫"), _word("犬")],
        curate=curate,
        config_changes={"include_known_words": False, "known_from_reviewed_cards": True},
        line_index=[LineLemmas(_LINE, frozenset({"猫", "犬"}), 1.0, 3.0, 2.0)],
    )

    assert (result.errors, presenter.errors) == ([], [])
    assert [word.mined_form for word in curated] == ["犬"]
    # Every card counts as known: the unreviewed 猫 no longer adds to the line's unknowns.
    assert curated[0].line_unknown_count == 1
    assert result.cards_created == 1
