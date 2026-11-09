"""Ignore suspended cards (desktop 3.12.0), inside a real mining run.

Each test runs ``test_secondary_subtitle._run``: one wire request through
``mining._process_episode`` into the real vendored ``EpisodeProcessor``, whose phase 2
reads the collection through the real ``AndroidAnkiAdapter`` and the in-memory Kotlin
Anki double. The line holds 猫, whose only card in Anki is suspended, and 犬, which has none.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from test_anki_adapter import FakeKotlinAnki
from test_mining_reviewed_known import _LINE, _word
from test_secondary_subtitle import _run


@pytest.mark.parametrize(
    ("setting", "mined"),
    [(True, ["猫", "犬"]), (False, ["犬"])],
    ids=["on", "off"],
)
def test_a_word_whose_only_card_is_suspended_is_mined_again_only_when_asked(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
    setting: bool,
    mined: list[str],
) -> None:
    pytest.importorskip("requests", reason="runtime dependency lane")
    from anki_miner.models.word import LineLemmas

    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["猫"]
    kotlin.known_note_suspended = [True]
    # Anki's duplicate rule still sees the suspended note.
    kotlin.duplicate_fields = ["猫"]
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
        config_changes={"include_known_words": False, "ignore_suspended_cards": setting},
        line_index=[LineLemmas(_LINE, frozenset({"猫", "犬"}), 1.0, 3.0, 2.0)],
    )

    assert (result.errors, presenter.errors) == ([], [])
    assert [word.mined_form for word in curated] == mined
    # The narrowed scan authorises the note past Anki's duplicate rule (desktop _scan_is_narrowed).
    assert result.cards_created == len(mined)
    scopes = [
        request["payload"]["scope"]
        for request in kotlin.requests_for("ankiScanFirstFields")
        if request["payload"]["scope"]["kind"] == "knownVocabulary"
    ]
    assert scopes and all(("ignoreSuspended" in scope) is setting for scope in scopes)
    create = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert create["duplicateScope"]["kind"] == ("allowDuplicates" if setting else "collection")
