"""Create calls that break a v1 size ceiling (AU-028), and a Stop mid-call (AU-026).

A note over a per-note limit is skipped and the rest are created; a call over a
byte budget is created in sequential sub-calls; any other invalid note still
fails the whole call before a write. A Stop after a commit returns the written
notes and reports nothing about the batch it interrupted.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

import android_bridge.anki_adapter as anki_adapter_module
import pytest
from android_bridge.anki_adapter import (
    _MAX_FIELD_VALUE_UTF8_BYTES,
    _MAX_NOTE_TAGS,
    AndroidAnkiAdapter,
    _dictionary_provider_preferred_name,
)
from android_bridge.protocol import BridgeProtocolError
from test_anki_adapter import (  # noqa: F401 - importing the autouse fixture applies it here
    FakeKotlinAnki,
    _adapter,
    _card,
    _config,
    isolated_services_namespace,
)

_OVERSIZED = "x" * (_MAX_FIELD_VALUE_UTF8_BYTES + 1)


class _Progress:
    def __init__(self) -> None:
        self.events: list[tuple[object, ...]] = []

    def on_start(self, *args: object) -> None:
        self.events.append(("start", *args))

    def on_progress(self, *args: object) -> None:
        self.events.append(("progress", *args))

    def on_complete(self) -> None:
        self.events.append(("complete",))


def _keys(kotlin: FakeKotlinAnki) -> list[list[str]]:
    """The duplicate key of every note, per createNotes callback."""

    return [
        [note["duplicateCandidate"]["key"] for note in request["payload"]["notes"]]
        for request in kotlin.requests_for("ankiCreateNotes")
    ]


def _one_note_per_sub_call(adapter: AndroidAnkiAdapter, card: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    """Shrink the built-note budget to exactly one note the size of ``card``."""

    budget = adapter._preflight_create_call([card]).note_utf8_bytes
    monkeypatch.setattr(anki_adapter_module, "_MAX_CREATE_CALL_NOTE_UTF8_BYTES", budget)


def _shared_dictionary_image(home: Path, name: str) -> tuple[str, str]:
    """A marked dictionary image on disk: its source name and a definition using it."""

    media_path = home / "dicts" / "dict" / "media" / name
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    source = f"dict__{name}"
    return source, f'<img class="anki-miner-dict-media" src="{source}">'


def test_note_over_a_size_limit_is_skipped_and_the_rest_are_created(
    initialized_bridge_home: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    caplog.set_level(logging.WARNING, logger=anki_adapter_module.__name__)

    created = adapter.create_cards_batch([_card("猫"), _card("犬", definition=_OVERSIZED), _card("鳥")])

    assert created == [1000, 1001]
    assert _keys(kotlin) == [["猫", "鳥"]]
    assert len(adapter.last_created_mined_forms) == 2
    # Not a duplicate: the engine's Phase 5 receipt reports it under failures.
    assert adapter.last_skipped_duplicates == 0
    (record,) = [record for record in caplog.records if "over a size limit" in record.getMessage()]
    assert record.getMessage() == "Anki create skipped 1 of 3 note(s) over a size limit outcome=skip"
    assert record.exc_info is not None
    assert record.exc_info[1].code == "note_too_large"


def test_note_that_fits_only_before_media_storage_is_skipped(initialized_bridge_home: Path) -> None:
    # The worst-case provider filename (1024 bytes, html-escaped) pushes this
    # field past its limit even though the note fits as built.
    _source, marked = _shared_dictionary_image(initialized_bridge_home, "headroom.png")
    definition = marked + "x" * (_MAX_FIELD_VALUE_UTF8_BYTES - len(marked.encode("utf-8")))
    kotlin = FakeKotlinAnki()

    created = _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
        [_card("猫", definition=definition), _card("鳥")]
    )

    assert created == [1000]
    assert _keys(kotlin) == [["鳥"]]
    assert kotlin.requests_for("ankiStoreMedia") == []


@pytest.mark.parametrize("oversized_first", [True, False], ids=["oversized-first", "invalid-first"])
def test_invalid_note_fails_the_call_even_when_other_notes_are_size_skipped(
    oversized_first: bool,
    initialized_bridge_home: Path,
) -> None:
    # An empty Expression has no duplicate identity. That is invalid_note, not
    # a size limit, so it must fail the call instead of being dropped.
    oversized, invalid = _card("犬", definition=_OVERSIZED), _card("")
    cards = [oversized, invalid] if oversized_first else [invalid, oversized]
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch([*cards, _card("鳥")])

    assert exc_info.value.code == "invalid_note"
    assert kotlin.requests == []


def test_call_where_no_note_fits_fails_with_the_size_error(initialized_bridge_home: Path) -> None:
    # Tags come from the config, so a tag limit breaks every note. That stays
    # a failure naming the limit, never an empty success.
    tags = " ".join(f"t{index}" for index in range(_MAX_NOTE_TAGS + 1))
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError, match="too many Anki tags") as exc_info:
        _adapter(_config(initialized_bridge_home, anki_tags=tags), kotlin).create_cards_batch(
            [_card("猫"), _card("鳥")]
        )

    assert exc_info.value.code == "note_too_large"
    assert kotlin.requests == []


@pytest.mark.parametrize(
    ("budget", "measure"),
    [
        ("_MAX_CREATE_CALL_SOURCE_UTF8_BYTES", "source_utf8_bytes"),
        ("_MAX_CREATE_CALL_NOTE_UTF8_BYTES", "note_utf8_bytes"),
    ],
)
def test_call_over_a_byte_budget_is_created_in_sequential_sub_calls(
    budget: str,
    measure: str,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    cards = [_card(f"語{index}") for index in range(5)]
    monkeypatch.setattr(anki_adapter_module, budget, 2 * getattr(adapter._preflight_create_call(cards[:1]), measure))
    progress = _Progress()

    created = adapter.create_cards_batch(cards, progress)

    assert created == [1000, 1001, 1002, 1003, 1004]
    assert adapter.last_created_note_ids == created
    assert _keys(kotlin) == [["語0", "語1"], ["語2", "語3"], ["語4"]]
    assert progress.events == [
        ("start", 5, "Creating Anki cards"),
        ("progress", 5, "Cards created: 5/5"),
        ("complete",),
    ]


def test_call_over_the_media_byte_budget_is_created_in_sequential_sub_calls(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData

    cards = []
    for index in range(3):
        audio = tmp_path / f"clip-{index}.opus"
        audio.write_bytes(f"{index:06d}".encode())
        cards.append(_card(f"語{index}", media=MediaData(audio_path=audio, audio_filename=audio.name)))
    # Two six-byte clips per sub-call; the runtime hashing budget reads the same constant.
    monkeypatch.setattr(anki_adapter_module, "_MAX_CREATE_CALL_MEDIA_BYTES", 12)
    kotlin = FakeKotlinAnki()

    created = _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert created == [1000, 1001, 1002]
    assert [len(request["payload"]["assets"]) for request in kotlin.requests_for("ankiStoreMedia")] == [2, 1]
    assert _keys(kotlin) == [["語0", "語1"], ["語2"]]


def test_repeated_word_in_a_later_sub_call_is_skipped_as_a_duplicate(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    _one_note_per_sub_call(adapter, _card("猫"), monkeypatch)

    assert adapter.create_cards_batch([_card("猫"), _card("猫")]) == [1000]
    assert adapter.last_skipped_duplicates == 1
    assert _keys(kotlin) == [["猫"]]


def test_excluded_deck_admission_spans_sub_calls(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Admission submits with allowDuplicates, so Kotlin's probe would not stop
    # the second note: the fronts already taken must carry across sub-calls.
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home, excluded_decks=("Archive",)), kotlin)
    _one_note_per_sub_call(adapter, _card("猫"), monkeypatch)

    assert adapter.create_cards_batch([_card("猫"), _card("猫")]) == [1000]
    assert adapter.last_skipped_duplicates == 1
    assert _keys(kotlin) == [["猫"]]


def test_dictionary_image_shared_by_two_sub_calls_is_stored_once(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, definition = _shared_dictionary_image(initialized_bridge_home, "shared-sub-call.png")
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    cards = [_card("猫", definition=definition), _card("鳥", definition=definition)]
    _one_note_per_sub_call(adapter, cards[0], monkeypatch)

    assert adapter.create_cards_batch(cards) == [1000, 1001]

    assert len(kotlin.requests_for("ankiStoreMedia")) == 1
    assert adapter.last_media_store_failures == 0
    actual = f"{_dictionary_provider_preferred_name(source)}_provider.png"
    notes = [note for request in kotlin.requests_for("ankiCreateNotes") for note in request["payload"]["notes"]]
    assert len(kotlin.requests_for("ankiCreateNotes")) == 2
    assert [note["fields"]["MainDefinition"] for note in notes] == [
        f'<img class="anki-miner-dict-media" src="{actual}">'
    ] * 2
    assert [[binding["actualFilename"] for binding in note["mediaBindings"]] for note in notes] == [[actual]] * 2


def test_dictionary_image_that_failed_to_store_is_not_retried_by_a_later_sub_call(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, definition = _shared_dictionary_image(initialized_bridge_home, "failed-sub-call.png")
    kotlin = FakeKotlinAnki()
    kotlin.failed_media_names.add(_dictionary_provider_preferred_name(source))
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    cards = [_card("猫", definition=definition), _card("鳥", definition=definition)]
    _one_note_per_sub_call(adapter, cards[0], monkeypatch)

    assert adapter.create_cards_batch(cards) == [1000, 1001]

    assert len(kotlin.requests_for("ankiStoreMedia")) == 1
    assert adapter.last_media_store_failures == 1
    notes = [note for request in kotlin.requests_for("ankiCreateNotes") for note in request["payload"]["notes"]]
    assert len(kotlin.requests_for("ankiCreateNotes")) == 2
    assert [note["fields"]["MainDefinition"] for note in notes] == ['<img class="anki-miner-dict-media">'] * 2
    assert [note["mediaBindings"] for note in notes] == [[], []]


def test_stop_between_sub_calls_returns_the_committed_notes(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    kotlin = FakeKotlinAnki()
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: bool(kotlin.requests_for("ankiCreateNotes")),
    )
    _one_note_per_sub_call(adapter, _card("猫"), monkeypatch)

    assert adapter.create_cards_batch([_card("猫"), _card("鳥")]) == [1000]
    assert _keys(kotlin) == [["猫"]]


def test_stop_during_a_later_media_upload_reports_no_media_failure(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    cards = []
    for index in range(101):
        audio = tmp_path / f"clip-{index}.opus"
        audio.write_bytes(f"audio-{index}".encode())
        cards.append(_card(f"語{index}", media=MediaData(audio_path=audio, audio_filename=audio.name)))
    kotlin = FakeKotlinAnki()
    # Stop lands after batch 1 committed and batch 2's duplicate probe, so it
    # interrupts batch 2's media upload.
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: len(kotlin.requests_for("ankiScanFirstFields")) >= 2,
    )

    assert adapter.create_cards_batch(cards) == list(range(1000, 1100))

    assert len(kotlin.requests_for("ankiCreateNotes")) == 1
    # Batch 2's notes never went out, so no created card is missing media.
    assert adapter.last_media_store_failures == 0


def test_stop_during_a_later_dictionary_upload_reports_no_media_failure(
    initialized_bridge_home: Path,
) -> None:
    cards = []
    for index in range(101):
        _source, definition = _shared_dictionary_image(initialized_bridge_home, f"stop-upload-{index}.png")
        cards.append(_card(f"語{index}", definition=definition))
    kotlin = FakeKotlinAnki()
    adapter = _adapter(
        _config(initialized_bridge_home),
        kotlin,
        cancellation_check=lambda: len(kotlin.requests_for("ankiScanFirstFields")) >= 2,
    )

    created = adapter.create_cards_batch(cards)

    # Worst-case binding headroom makes batch 1 smaller than 100 notes.
    (first_create,) = kotlin.requests_for("ankiCreateNotes")
    assert created == list(range(1000, 1000 + len(first_create["payload"]["notes"])))
    assert adapter.last_media_store_failures == 0
