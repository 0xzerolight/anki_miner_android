from __future__ import annotations

import json
import sys
import types
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from android_bridge.anki_adapter import (
    AndroidAnkiAdapter,
    AnkiOperationCancelled,
    _dictionary_provider_preferred_name,
)
from android_bridge.callbacks import AndroidAnkiCallbacks
from android_bridge.protocol import BridgeProtocolError, encode_message

RUN_ID = "run_" + "a" * 32
_ANKI_SCHEMA = json.loads(
    (
        Path(__file__).resolve().parents[3]
        / "app/src/main/python/android_bridge/schemas/anki.schema.json"
    ).read_text(encoding="utf-8")
)
_ANKI_VALIDATOR = Draft202012Validator(_ANKI_SCHEMA)


@pytest.fixture(scope="module", autouse=True)
def isolated_services_namespace(initialized_bridge_home: Path) -> Iterator[None]:
    """Load narrow engine helpers without importing every desktop service.

    Android packages the full runtime dependency set. The deliberately small
    host bridge-test environment does not, so bypass the eager desktop
    ``services.__init__`` while retaining normal imports of the vendored helper
    modules under test.
    """

    import anki_miner

    prefix = "anki_miner.services"
    previous = {
        name: module
        for name, module in sys.modules.items()
        if name == prefix or name.startswith(f"{prefix}.")
    }
    for name in previous:
        sys.modules.pop(name)

    package = types.ModuleType(prefix)
    package.__path__ = [str(Path(anki_miner.__file__).resolve().parent / "services")]
    sys.modules[prefix] = package
    ankiconnect = types.ModuleType(f"{prefix}._ankiconnect")

    def unexpected_http(*args: object, **kwargs: object) -> None:
        raise AssertionError("Android adapter must not call AnkiConnect")

    ankiconnect.post_action = unexpected_http  # type: ignore[attr-defined]
    ankiconnect.post_multi = unexpected_http  # type: ignore[attr-defined]
    sys.modules[ankiconnect.__name__] = ankiconnect
    try:
        yield
    finally:
        for name in list(sys.modules):
            if name == prefix or name.startswith(f"{prefix}."):
                sys.modules.pop(name)
        sys.modules.update(previous)


class FakeKotlinAnki:
    def __init__(self) -> None:
        self.requests: list[tuple[str, dict[str, Any]]] = []
        self.verify_fields: list[str] | None = None
        self.known_fields: list[str] = []
        self.duplicate_fields: list[str] = []
        self.errors: dict[str, tuple[str, str, bool]] = {}
        self.failed_media_names: set[str] = set()
        self.media_failure_errors: dict[str, tuple[str, str, bool]] = {}
        self.media_renames: dict[str, str] = {}
        self.create_scripts: list[
            tuple[list[str], dict[str, object] | None] | None
        ] = []
        self.next_note_id = 1000

    def _request(self, method: str, raw: str) -> dict[str, Any]:
        envelope = json.loads(raw)
        _ANKI_VALIDATOR.validate(envelope["payload"])
        self.requests.append((method, envelope))
        return envelope["payload"]

    def _error(self, request: dict[str, Any], operation: str) -> str | None:
        configured = self.errors.get(operation)
        if configured is None:
            return None
        code, message, retryable = configured
        return encode_message(
            "anki.error",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "operation": operation,
                "code": code,
                "message": message,
                "retryable": retryable,
            },
        )

    def ankiVerifyTarget(self, raw: str) -> str:
        request = self._request("ankiVerifyTarget", raw)
        if error := self._error(request, "verifyTarget"):
            return error
        fields = (
            self.verify_fields
            if self.verify_fields is not None
            else (request["requiredFields"] or ["Expression"])
        )
        return encode_message(
            "anki.verifytarget.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "deckId": 10,
                "modelId": 20,
                "fieldNames": fields,
                "deckCreated": False,
            },
        )

    def ankiScanFirstFields(self, raw: str) -> str:
        request = self._request("ankiScanFirstFields", raw)
        if error := self._error(request, "scanFirstFields"):
            return error
        if request["scope"]["kind"] == "knownVocabulary":
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "firstFields": self.known_fields,
                },
            )

        from anki_miner.services.anki_note_builder import _strip_for_dedup

        duplicates = {_strip_for_dedup(field) for field in self.duplicate_fields}
        return encode_message(
            "anki.scanfirstfields.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "matches": [
                    candidate in duplicates
                    for candidate in request["scope"]["candidateKeys"]
                ],
            },
        )

    def ankiStoreMedia(self, raw: str) -> str:
        request = self._request("ankiStoreMedia", raw)
        if error := self._error(request, "storeMedia"):
            return error
        results: list[dict[str, object]] = []
        for asset in request["assets"]:
            preferred = asset["preferredName"]
            if preferred in self.failed_media_names:
                code, message, retryable = self.media_failure_errors.get(
                    preferred,
                    ("media_store_failed", "media insert failed", True),
                )
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "failed",
                        "error": {
                            "code": code,
                            "message": message,
                            "retryable": retryable,
                        },
                    }
                )
            else:
                extension = Path(asset["sourcePath"]).suffix or ".bin"
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "stored",
                        "actualFilename": self.media_renames.get(
                            preferred, f"{preferred}_provider{extension}"
                        ),
                    }
                )
        return encode_message(
            "anki.storemedia.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "results": results,
            },
        )

    def ankiCreateNotes(self, raw: str) -> str:
        request = self._request("ankiCreateNotes", raw)
        if error := self._error(request, "createNotes"):
            return error
        script = self.create_scripts.pop(0) if self.create_scripts else None
        statuses, partial_error = (
            script
            if script is not None
            else (["created"] * len(request["notes"]), None)
        )
        assert len(statuses) == len(request["notes"])
        results: list[dict[str, object]] = []
        for note, status in zip(request["notes"], statuses, strict=True):
            result: dict[str, object] = {
                "clientNoteId": note["clientNoteId"],
                "status": status,
            }
            if status == "created":
                result["noteId"] = self.next_note_id
                self.next_note_id += 1
            results.append(result)
        return encode_message(
            "anki.createnotes.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "results": results,
                "error": partial_error,
            },
        )

    def requests_for(self, method: str) -> list[dict[str, Any]]:
        return [envelope for name, envelope in self.requests if name == method]


def _config(home: Path, **changes: object) -> Any:
    from anki_miner.config import AnkiMinerConfig

    base = AnkiMinerConfig(dicts_root=home / "dicts")
    return replace(base, **changes)


def _adapter(config: Any, kotlin: FakeKotlinAnki) -> AndroidAnkiAdapter:
    return AndroidAnkiAdapter(config, AndroidAnkiCallbacks(kotlin, RUN_ID))


def _card(surface: str, *, definition: str = "definition", media: Any = None) -> Any:
    from anki_miner.models import CardPayload, MediaData, TokenizedWord

    word = TokenizedWord(
        surface=surface,
        lemma=surface,
        reading="よみ",
        sentence=f"{surface}だ",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        expression_furigana=f"{surface}[よみ]",
        expression_reading="よみ",
        sentence_furigana=f"{surface}[よみ]だ",
        sentence_reading="よみだ",
        pos="名詞",
    )
    return CardPayload(
        word=word,
        media=media or MediaData(),
        definition=definition,
    )


def test_verify_target_sends_only_nonempty_fields_and_active_marker(
    initialized_bridge_home: Path,
) -> None:
    config = _config(
        initialized_bridge_home,
        card_type="click",
        anki_fields={
            "word": "Expression",
            "sentence": "Sentence",
            "definition": "Definition",
            "picture": "Picture",
            "audio": "Audio",
            "expression_furigana": "ExpressionFurigana",
            "sentence_furigana": "SentenceFurigana",
            "glossary": "",
        },
    )
    kotlin = FakeKotlinAnki()

    _adapter(config, kotlin).verify_card_target()

    payload = kotlin.requests_for("ankiVerifyTarget")[0]["payload"]
    assert payload["requiredFields"] == sorted(
        {
            "Expression",
            "Sentence",
            "Definition",
            "Picture",
            "Audio",
            "ExpressionFurigana",
            "SentenceFurigana",
            "IsClickCard",
        }
    )


def test_verify_target_maps_setup_and_provider_failures(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError, SetupError

    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (
        "note_type_not_found",
        "missing model",
        False,
    )
    with pytest.raises(SetupError, match="missing model"):
        _adapter(config, kotlin).verify_card_target()

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (
        "provider_unavailable",
        "AnkiDroid unavailable",
        True,
    )
    with pytest.raises(AnkiConnectionError, match="AnkiDroid unavailable"):
        _adapter(config, kotlin).verify_card_target()


def test_verify_target_defensively_checks_returned_field_ordering_set(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import SetupError

    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.verify_fields = ["Expression"]

    with pytest.raises(SetupError, match=r"Field\(s\)"):
        _adapter(config, kotlin).verify_card_target()


def test_blank_required_and_active_marker_mappings_match_desktop_builder(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.services.anki_note_builder import build_note

    base = _config(initialized_bridge_home)
    fields = {**base.anki_fields, "word": ""}
    markers = {**base.card_type_marker_fields, "click": ""}
    config = replace(
        base,
        anki_fields=fields,
        card_type="click",
        card_type_marker_fields=markers,
    )
    card = _card("猫")
    kotlin = FakeKotlinAnki()
    adapter = _adapter(config, kotlin)

    adapter.verify_card_target()
    assert adapter.create_cards_batch([card]) == 1

    verify = kotlin.requests_for("ankiVerifyTarget")[0]["payload"]
    assert "Expression" not in verify["requiredFields"]
    assert "IsClickCard" not in verify["requiredFields"]
    created = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]
    desktop_built = build_note(card, config, set()).note
    assert created["fields"] == desktop_built["fields"]
    assert "Expression" not in created["fields"]
    assert "IsClickCard" not in created["fields"]
    duplicate_scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"][
        "scope"
    ]
    assert duplicate_scope["candidateKeys"] == ["猫だ"]


def test_all_blank_mappings_are_valid_for_target_preflight(
    initialized_bridge_home: Path,
) -> None:
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={key: "" for key in base.anki_fields},
        card_type="click",
        card_type_marker_fields={key: "" for key in base.card_type_marker_fields},
    )
    kotlin = FakeKotlinAnki()

    _adapter(config, kotlin).verify_card_target()

    assert kotlin.requests_for("ankiVerifyTarget")[0]["payload"][
        "requiredFields"
    ] == []


def test_known_vocabulary_is_normalized_filtered_and_cached(
    initialized_bridge_home: Path,
) -> None:
    config = _config(
        initialized_bridge_home, excluded_decks=("Ignored", "Ignored::Child")
    )
    kotlin = FakeKotlinAnki()
    kotlin.known_fields = [
        " <b>猫</b>&nbsp; ",
        "[sound:x.mp3]犬",
        "plain English",
        "猫",
    ]
    adapter = _adapter(config, kotlin)

    first = adapter.get_existing_vocabulary()
    kotlin.known_fields = ["鳥"]
    second = adapter.get_existing_vocabulary()

    assert first == {"猫", "犬"}
    assert second is first
    requests = kotlin.requests_for("ankiScanFirstFields")
    assert len(requests) == 1
    assert requests[0]["payload"]["scope"] == {
        "kind": "knownVocabulary",
        "excludedDecks": ["Ignored", "Ignored::Child"],
    }


def test_retryable_vocab_timeout_degrades_without_poisoning_cache(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home)
    kotlin = FakeKotlinAnki()
    kotlin.errors["scanFirstFields"] = ("timeout", "slow query", True)
    adapter = _adapter(config, kotlin)

    assert adapter.get_existing_vocabulary() == set()
    assert adapter.get_existing_vocabulary() == set()
    assert len(kotlin.requests_for("ankiScanFirstFields")) == 2


def test_nonretryable_vocab_query_failure_is_hard(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.errors["scanFirstFields"] = ("query_failed", "bad cursor", False)
    with pytest.raises(AnkiConnectionError, match="bad cursor"):
        _adapter(_config(initialized_bridge_home), kotlin).get_existing_vocabulary()


def test_note_building_dedup_and_first_occurrence_semantics_are_python_owned(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, anki_tags="mine mobile")
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["<b>既存</b>"]
    adapter = _adapter(config, kotlin)

    created = adapter.create_cards_batch([_card("既存"), _card("猫"), _card("猫")])

    assert created == 1
    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 2
    create_payload = kotlin.requests_for("ankiCreateNotes")[0]["payload"]
    assert len(create_payload["notes"]) == 1
    assert create_payload["notes"][0]["fields"]["Expression"] == "猫"
    assert create_payload["notes"][0]["fields"]["Sentence"] == "猫だ"
    assert create_payload["notes"][0]["tags"] == ["mine", "mobile"]


def test_allow_duplicate_cards_scopes_probe_to_target_deck(
    initialized_bridge_home: Path,
) -> None:
    config = _config(initialized_bridge_home, allow_duplicate_cards=True)
    kotlin = FakeKotlinAnki()

    _adapter(config, kotlin).create_cards_batch([_card("猫")])

    scope = kotlin.requests_for("ankiScanFirstFields")[0]["payload"]["scope"]
    assert scope == {
        "kind": "duplicates",
        "modelName": config.anki_note_type,
        "deckName": config.anki_deck_name,
        "candidateKeys": ["猫"],
    }


def test_create_notes_batches_at_100_and_reports_cumulative_progress(
    initialized_bridge_home: Path,
) -> None:
    class Progress:
        def __init__(self) -> None:
            self.events: list[tuple[object, ...]] = []

        def on_start(self, total: int, description: str) -> None:
            self.events.append(("start", total, description))

        def on_progress(self, current: int, description: str) -> None:
            self.events.append(("progress", current, description))

        def on_complete(self) -> None:
            self.events.append(("complete",))

    kotlin = FakeKotlinAnki()
    progress = Progress()
    cards = [_card(f"語{index}") for index in range(101)]

    created = _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
        cards, progress
    )

    assert created == 101
    assert [
        len(request["payload"]["notes"])
        for request in kotlin.requests_for("ankiCreateNotes")
    ] == [100, 1]
    duplicate_scopes = [
        request["payload"]["scope"]
        for request in kotlin.requests_for("ankiScanFirstFields")
    ]
    assert [len(scope["candidateKeys"]) for scope in duplicate_scopes] == [100, 1]
    assert duplicate_scopes[0]["candidateKeys"] == [
        f"語{index}" for index in range(100)
    ]
    assert duplicate_scopes[1]["candidateKeys"] == ["語100"]
    assert progress.events == [
        ("start", 101, "Creating Anki cards"),
        ("progress", 100, "Cards created: 100/101"),
        ("progress", 101, "Cards created: 101/101"),
        ("complete",),
    ]


def test_duplicate_lookup_is_candidate_bounded_across_large_batches(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.duplicate_fields = ["<b>語0</b>", "語100", "語204"]
    cards = [
        _card(f"語{index}", definition="x" * 20_000) for index in range(205)
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch(cards) == 202
    assert adapter.last_skipped_duplicates == 3

    scopes = [
        request["payload"]["scope"]
        for request in kotlin.requests_for("ankiScanFirstFields")
    ]
    assert [len(scope["candidateKeys"]) for scope in scopes] == [100, 100, 5]
    assert [
        scope["candidateKeys"] for scope in scopes
    ] == [
        [f"語{index}" for index in range(0, 100)],
        [f"語{index}" for index in range(100, 200)],
        [f"語{index}" for index in range(200, 205)],
    ]
    assert all("definition" not in scope for scope in scopes)
    assert [
        len(request["payload"]["notes"])
        for request in kotlin.requests_for("ankiCreateNotes")
    ] == [99, 99, 4]


def test_duplicate_candidate_key_size_is_bounded_before_callback(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    oversized = "語" * 4097

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card(oversized)]
        )

    assert exc_info.value.code == "invalid_note"
    assert not kotlin.requests_for("ankiScanFirstFields")
    assert not kotlin.requests_for("ankiCreateNotes")


def test_duplicate_lookup_rejects_misaligned_sparse_array_semantics(
    initialized_bridge_home: Path,
) -> None:
    class UnrelatedDuplicateKotlin(FakeKotlinAnki):
        def ankiScanFirstFields(self, raw: str) -> str:
            request = self._request("ankiScanFirstFields", raw)
            return encode_message(
                "anki.scanfirstfields.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "matches": [True, False],
                },
            )

    kotlin = UnrelatedDuplicateKotlin()
    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫")]
        )

    assert exc_info.value.code == "invalid_anki_response"
    assert not kotlin.requests_for("ankiCreateNotes")


def test_first_occurrence_wins_across_batch_boundary(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    cards = [_card(f"語{index}") for index in range(100)] + [_card("語0")]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch(cards) == 100
    assert adapter.last_skipped_duplicates == 1
    assert [
        len(request["payload"]["notes"])
        for request in kotlin.requests_for("ankiCreateNotes")
    ] == [100]


def test_card_media_is_content_addressed_deduped_and_actual_name_propagates(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    image = tmp_path / "source.webp"
    image.write_bytes(b"same image")
    missing = tmp_path / "missing.opus"
    media_a = MediaData(screenshot_path=image, screenshot_filename="shot.webp")
    media_b = MediaData(
        screenshot_path=image,
        screenshot_filename="shot.webp",
        audio_path=missing,
        audio_filename="gone.opus",
    )
    requested = _content_addressed_name("shot.webp", b"same image")
    preferred = Path(requested).stem
    kotlin = FakeKotlinAnki()
    provider_name = f"{Path(preferred).stem}_provider.webp"
    kotlin.media_renames[preferred] = provider_name
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    created = adapter.create_cards_batch(
        [_card("猫", media=media_a), _card("犬", media=media_b)]
    )

    assert created == 2
    assert adapter.last_media_store_failures == 1
    assert media_a.screenshot_filename == provider_name
    assert media_b.screenshot_filename == provider_name
    store_payload = kotlin.requests_for("ankiStoreMedia")[0]["payload"]
    assert len(store_payload["assets"]) == 1
    assert store_payload["assets"][0]["preferredName"] == preferred
    assert store_payload["assets"][0]["requestedFilename"] == requested
    assert store_payload["assets"][0]["sourcePath"] == str(image.resolve())
    note_fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert all(
        note["fields"]["Picture"] == f'<img src="{provider_name}">'
        for note in note_fields
    )


def test_same_logical_media_name_with_different_bytes_fails_before_provider(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    first = tmp_path / "first.opus"
    second = tmp_path / "second.opus"
    first.write_bytes(b"first")
    second.write_bytes(b"second")
    first_media = MediaData(audio_path=first, audio_filename="shared.opus")
    second_media = MediaData(audio_path=second, audio_filename="shared.opus")
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=first_media), _card("犬", media=second_media)]
        )

    assert exc_info.value.code == "media_content_collision"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")
    assert first_media.audio_filename == "shared.opus"
    assert second_media.audio_filename == "shared.opus"


def test_same_logical_media_name_with_live_and_missing_paths_fails_closed(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    live = tmp_path / "live.opus"
    live.write_bytes(b"audio")
    missing = tmp_path / "missing.opus"
    live_media = MediaData(audio_path=live, audio_filename="shared.opus")
    missing_media = MediaData(audio_path=missing, audio_filename="shared.opus")
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=live_media), _card("犬", media=missing_media)]
        )

    assert exc_info.value.code == "media_content_collision"
    assert not kotlin.requests_for("ankiStoreMedia")
    assert not kotlin.requests_for("ankiCreateNotes")
    assert live_media.audio_filename == "shared.opus"
    assert missing_media.audio_filename == "shared.opus"


def test_same_logical_media_name_with_identical_bytes_dedupes_across_paths(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    first = tmp_path / "first.opus"
    second = tmp_path / "second.opus"
    first.write_bytes(b"same")
    second.write_bytes(b"same")
    first_media = MediaData(audio_path=first, audio_filename="shared.opus")
    second_media = MediaData(audio_path=second, audio_filename="shared.opus")
    kotlin = FakeKotlinAnki()

    assert (
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=first_media), _card("犬", media=second_media)]
        )
        == 2
    )

    assets = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"]
    assert len(assets) == 1
    assert assets[0]["sourcePath"] == str(first.resolve())
    assert Path(assets[0]["preferredName"]).suffix == ""
    assert first_media.audio_filename == second_media.audio_filename


def test_media_store_is_bounded_to_fifty_assets_per_callback(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    cards = []
    media_rows = []
    for index in range(51):
        path = tmp_path / f"clip-{index}.opus"
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=f"clip-{index}.opus")
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = FakeKotlinAnki()

    assert _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards) == 51

    requests = kotlin.requests_for("ankiStoreMedia")
    assert [len(request["payload"]["assets"]) for request in requests] == [50, 1]
    assert all(
        "requestedFilename" in asset
        for request in requests
        for asset in request["payload"]["assets"]
    )
    assert all(media.audio_filename.endswith("_provider.opus") for media in media_rows)


def test_second_media_chunk_cancellation_does_not_mutate_payloads(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    class CancelSecondMediaChunk(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            if len(self.requests_for("ankiStoreMedia")) == 1:
                request = self._request("ankiStoreMedia", raw)
                return encode_message(
                    "anki.error",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "operation": "storeMedia",
                        "code": "cancelled",
                        "message": "cancelled",
                        "retryable": False,
                    },
                )
            return super().ankiStoreMedia(raw)

    cards = []
    media_rows = []
    for index in range(51):
        path = tmp_path / f"cancel-{index}.opus"
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=f"cancel-{index}.opus")
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = CancelSecondMediaChunk()

    with pytest.raises(AnkiOperationCancelled):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert [
        len(request["payload"]["assets"])
        for request in kotlin.requests_for("ankiStoreMedia")
    ] == [50, 1]
    assert all(
        media.audio_filename == f"cancel-{index}.opus"
        for index, media in enumerate(media_rows)
    )
    assert not kotlin.requests_for("ankiCreateNotes")


def test_second_media_chunk_misalignment_does_not_mutate_payloads(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    class MisalignSecondMediaChunk(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            if len(self.requests_for("ankiStoreMedia")) == 1:
                request = self._request("ankiStoreMedia", raw)
                asset = request["assets"][0]
                return encode_message(
                    "anki.storemedia.result",
                    {
                        "runId": request["runId"],
                        "requestId": request["requestId"],
                        "results": [
                            {
                                "assetId": "asset_" + "f" * 32,
                                "status": "stored",
                                "actualFilename": (
                                    f'{asset["preferredName"]}_provider.opus'
                                ),
                            }
                        ],
                    },
                )
            return super().ankiStoreMedia(raw)

    cards = []
    media_rows = []
    for index in range(51):
        path = tmp_path / f"align-{index}.opus"
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=f"align-{index}.opus")
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = MisalignSecondMediaChunk()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "mismatched_callback_response"
    assert all(
        media.audio_filename == f"align-{index}.opus"
        for index, media in enumerate(media_rows)
    )
    assert not kotlin.requests_for("ankiCreateNotes")


def test_media_name_ownership_is_enforced_across_callback_chunks(
    initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services import anki_media_store

    monkeypatch.setattr(
        anki_media_store,
        "_content_addressed_name",
        lambda filename, _content: filename,
    )

    class CollideAcrossChunks(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            results = []
            for asset in request["assets"]:
                actual = (
                    "ab_x_provider.opus"
                    if asset["requestedFilename"] in {"ab.opus", "ab_x.opus"}
                    else f'{asset["preferredName"]}_provider.opus'
                )
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "stored",
                        "actualFilename": actual,
                    }
                )
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": results,
                },
            )

    names = ["ab.opus", *[f"middle-{index}.opus" for index in range(49)], "ab_x.opus"]
    cards = []
    media_rows = []
    for index, name in enumerate(names):
        path = tmp_path / name
        path.write_bytes(f"audio-{index}".encode())
        media = MediaData(audio_path=path, audio_filename=name)
        media_rows.append(media)
        cards.append(_card(f"語{index}", media=media))
    kotlin = CollideAcrossChunks()

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(cards)

    assert exc_info.value.code == "media_name_collision"
    assert [
        len(request["payload"]["assets"])
        for request in kotlin.requests_for("ankiStoreMedia")
    ] == [50, 1]
    assert all(media.audio_filename == name for media, name in zip(media_rows, names))
    assert not kotlin.requests_for("ankiCreateNotes")


def test_declared_media_failure_is_nonfatal_and_omits_reference(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    preferred = Path(_content_addressed_name("clip.opus", b"audio")).stem
    kotlin = FakeKotlinAnki()
    kotlin.failed_media_names.add(preferred)
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert (
        adapter.create_cards_batch(
            [
                _card(
                    "猫",
                    media=MediaData(audio_path=audio, audio_filename="clip.opus"),
                )
            ]
        )
        == 1
    )
    assert adapter.last_media_store_failures == 1
    fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["fields"]
    assert fields["SentenceAudio"] == ""


def test_dictionary_media_uses_source_name_and_success_cache(
    initialized_bridge_home: Path,
) -> None:
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "pic.png"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"png")
    definition = '<img class="anki-miner-dict-media" src="dict__pic.png">'
    kotlin = FakeKotlinAnki()
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    adapter.create_cards_batch([_card("猫", definition=definition)])
    adapter.create_cards_batch([_card("犬", definition=definition)])

    dictionary_requests = [
        request
        for request in kotlin.requests_for("ankiStoreMedia")
        if request["payload"]["assets"][0]["purpose"] == "dictionary"
    ]
    assert len(dictionary_requests) == 1
    asset = dictionary_requests[0]["payload"]["assets"][0]
    assert asset["preferredName"] == _dictionary_provider_preferred_name(
        "dict__pic.png"
    )
    assert asset["requestedFilename"] == "dict__pic.png"
    assert asset["sourcePath"] == str(media_path.resolve())


def test_dictionary_media_preserves_quote_filename_for_direct_fallback(
    initialized_bridge_home: Path,
) -> None:
    class DirectNameMediaKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": asset["assetId"],
                            "status": "stored",
                            "actualFilename": asset["requestedFilename"],
                        }
                        for asset in request["assets"]
                    ],
                },
            )

    filename = 'dict__a";b.svg'
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / 'a";b.svg'
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"svg")
    definition = (
        '<img class="anki-miner-dict-media" src="dict__a&quot;;b.svg">'
    )
    kotlin = DirectNameMediaKotlin()

    assert (
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", definition=definition)]
        )
        == 1
    )

    asset = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"][0]
    assert asset["preferredName"] == _dictionary_provider_preferred_name(filename)
    assert asset["requestedFilename"] == filename
    fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["fields"]
    assert fields["MainDefinition"] == definition


def test_dictionary_media_random_names_rewrite_only_marked_html_safely(
    initialized_bridge_home: Path,
) -> None:
    media_root = initialized_bridge_home / "dicts" / "dict" / "media"
    media_root.mkdir(parents=True, exist_ok=True)
    first_path = media_root / "pic&one.png"
    second_path = media_root / "other.svg"
    first_path.write_bytes(b"png")
    second_path.write_bytes(b"svg")
    definition = (
        '<img class="anki-miner-dict-media" src="dict__pic&amp;one.png">'
        '<img class="anki-miner-dict-media" src="dict__pic&amp;one.png">'
        '<img class="ordinary" src="dict__pic&amp;one.png">'
        '<img src="dict__other.svg" class="anki-miner-dict-media">'
    )
    glossary = (
        '<span><img class="anki-miner-dict-media" '
        'src="dict__pic&amp;one.png"></span>'
    )
    base = _config(initialized_bridge_home)
    config = replace(
        base,
        anki_fields={**base.anki_fields, "glossary": "Glossary"},
    )
    first_card = replace(_card("猫", definition=definition), extra_fields={"glossary": glossary})
    kotlin = FakeKotlinAnki()
    first_preferred = _dictionary_provider_preferred_name("dict__pic&one.png")
    second_preferred = _dictionary_provider_preferred_name("dict__other.svg")
    kotlin.media_renames = {
        first_preferred: f"{first_preferred}_random.jpeg",
        second_preferred: f"{second_preferred}_random.webp",
    }
    adapter = _adapter(config, kotlin)

    assert adapter.create_cards_batch([first_card]) == 1
    assert adapter.create_cards_batch(
        [replace(_card("犬", definition=definition), extra_fields={"glossary": glossary})]
    ) == 1

    dictionary_requests = [
        request
        for request in kotlin.requests_for("ankiStoreMedia")
        if request["payload"]["assets"][0]["purpose"] == "dictionary"
    ]
    assert len(dictionary_requests) == 1
    assets = dictionary_requests[0]["payload"]["assets"]
    assert [asset["preferredName"] for asset in assets] == [
        first_preferred,
        second_preferred,
    ]
    assert [asset["requestedFilename"] for asset in assets] == [
        "dict__pic&one.png",
        "dict__other.svg",
    ]
    assert all(Path(asset["preferredName"]).suffix == "" for asset in assets)

    expected_definition = (
        '<img class="anki-miner-dict-media" '
        f'src="{first_preferred}_random.jpeg">'
        '<img class="anki-miner-dict-media" '
        f'src="{first_preferred}_random.jpeg">'
        '<img class="ordinary" src="dict__pic&amp;one.png">'
        f'<img src="{second_preferred}_random.webp" class="anki-miner-dict-media">'
    )
    expected_glossary = (
        '<span><img class="anki-miner-dict-media" '
        f'src="{first_preferred}_random.jpeg"></span>'
    )
    for request in kotlin.requests_for("ankiCreateNotes"):
        fields = request["payload"]["notes"][0]["fields"]
        assert fields["MainDefinition"] == expected_definition
        assert fields["Glossary"] == expected_glossary
    assert first_card.definition == definition
    assert first_card.extra_fields == {"glossary": glossary}


def test_partial_create_response_preserves_ids_counters_and_vocab_cache(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["既知"]
    kotlin.create_scripts = [
        (
            ["created", "failed", "notAttempted"],
            {"code": "write_failed", "message": "mid-batch", "retryable": True},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert adapter.get_existing_vocabulary() == {"既知"}

    with pytest.raises(AnkiConnectionError, match="mid-batch"):
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 0
    assert adapter.get_existing_vocabulary() == {"既知", "猫"}


def test_misaligned_create_results_are_protocol_errors(
    initialized_bridge_home: Path,
) -> None:
    class MisalignedKotlin(FakeKotlinAnki):
        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            return encode_message(
                "anki.createnotes.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "clientNoteId": "note_" + "f" * 32,
                            "status": "created",
                            "noteId": 1,
                        }
                    ],
                    "error": None,
                },
            )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(
            _config(initialized_bridge_home), MisalignedKotlin()
        ).create_cards_batch([_card("猫")])
    assert exc_info.value.code == "mismatched_callback_response"


def test_no_bridge_module_imports_engine_before_bootstrap() -> None:
    bridge_file = (
        Path(__file__).resolve().parents[3]
        / "app/src/main/python/android_bridge/anki_adapter.py"
    )
    source_lines = bridge_file.read_text(encoding="utf-8").splitlines()

    assert not any(
        line.startswith(("import anki_miner", "from anki_miner"))
        for line in source_lines
    )


@pytest.mark.parametrize(
    "code", ["api_disabled", "permission_required", "field_missing"]
)
def test_user_action_and_field_errors_are_setup_failures(
    code: str, initialized_bridge_home: Path
) -> None:
    from anki_miner.exceptions import SetupError

    kotlin = FakeKotlinAnki()
    kotlin.errors["verifyTarget"] = (code, f"setup: {code}", False)

    with pytest.raises(SetupError, match=code):
        _adapter(_config(initialized_bridge_home), kotlin).verify_card_target()


@pytest.mark.parametrize(
    "operation", ["verifyTarget", "scanFirstFields", "storeMedia", "createNotes"]
)
def test_cancelled_callbacks_escape_as_bridge_cancellation(
    operation: str, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    kotlin = FakeKotlinAnki()
    kotlin.errors[operation] = ("cancelled", "cancelled by user", False)
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    audio = tmp_path / f"{operation}.opus"
    audio.write_bytes(b"audio")

    with pytest.raises(AnkiOperationCancelled) as exc_info:
        if operation == "verifyTarget":
            adapter.verify_card_target()
        elif operation == "scanFirstFields":
            adapter.get_existing_vocabulary()
        else:
            adapter.create_cards_batch(
                [
                    _card(
                        "猫",
                        media=(
                            MediaData(audio_path=audio, audio_filename="clip.opus")
                            if operation == "storeMedia"
                            else None
                        ),
                    )
                ]
            )

    assert exc_info.value.code == "cancelled"
    assert exc_info.value.operation == operation
    assert exc_info.value.retryable is False


@pytest.mark.parametrize(
    ("code", "expected"),
    [
        ("api_disabled", "SetupError"),
        ("permission_required", "SetupError"),
        ("provider_unavailable", "AnkiConnectionError"),
        ("timeout", "AnkiConnectionError"),
    ],
)
def test_callback_wide_media_failures_abort_without_mutating_payload(
    code: str,
    expected: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError, SetupError
    from anki_miner.models import MediaData

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    media = MediaData(audio_path=audio, audio_filename="clip.opus")
    kotlin = FakeKotlinAnki()
    kotlin.errors["storeMedia"] = (code, f"media: {code}", True)
    error_type = SetupError if expected == "SetupError" else AnkiConnectionError

    with pytest.raises(error_type, match=code):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media)]
        )

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    ("code", "expected"),
    [
        ("permission_required", "SetupError"),
        ("provider_unavailable", "AnkiConnectionError"),
        ("cancelled", "AnkiOperationCancelled"),
    ],
)
def test_only_explicit_per_media_store_failure_may_degrade(
    code: str,
    expected: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError, SetupError
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    preferred = Path(_content_addressed_name("clip.opus", b"audio")).stem
    media = MediaData(audio_path=audio, audio_filename="clip.opus")
    kotlin = FakeKotlinAnki()
    kotlin.failed_media_names.add(preferred)
    kotlin.media_failure_errors[preferred] = (code, f"row: {code}", False)
    error_type: type[BaseException]
    if expected == "SetupError":
        error_type = SetupError
    elif expected == "AnkiConnectionError":
        error_type = AnkiConnectionError
    else:
        error_type = AnkiOperationCancelled

    with pytest.raises(error_type, match=code):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media)]
        )

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize(
    "returned_name",
    [
        "[sound:clip.opus]",
        '<img src="clip.opus">',
        "clip\n.opus",
        "clip\u200e.opus",
        "../clip.opus",
        "clip.mp3",
        "unrelated.opus",
    ],
)
def test_unsafe_or_unrelated_provider_names_are_rejected_before_mutation(
    returned_name: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    class RenamingKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": request["assets"][0]["assetId"],
                            "status": "stored",
                            "actualFilename": returned_name,
                        }
                    ],
                },
            )

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    media = MediaData(audio_path=audio, audio_filename="clip.opus")
    kotlin = RenamingKotlin()

    with pytest.raises(BridgeProtocolError):
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫", media=media)]
        )

    assert media.audio_filename == "clip.opus"
    assert not kotlin.requests_for("ankiCreateNotes")


@pytest.mark.parametrize("collision_kind", ["reserved", "duplicate_actual"])
def test_provider_media_name_collisions_are_rejected_transactionally(
    collision_kind: str,
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    first_path = tmp_path / "first.opus"
    second_path = tmp_path / "second.opus"
    first_path.write_bytes(b"first")
    second_path.write_bytes(b"second")
    first_preferred = _content_addressed_name("clip.opus", b"first")
    second_original = f"{Path(first_preferred).stem}_extra.opus"
    second_preferred = _content_addressed_name(second_original, b"second")

    class CollidingKotlin(FakeKotlinAnki):
        def ankiStoreMedia(self, raw: str) -> str:
            request = self._request("ankiStoreMedia", raw)
            actual = (
                second_preferred
                if collision_kind == "reserved"
                else f"{Path(second_preferred).stem}_provider.opus"
            )
            return encode_message(
                "anki.storemedia.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "assetId": asset["assetId"],
                            "status": "stored",
                            "actualFilename": actual,
                        }
                        for asset in request["assets"]
                    ],
                },
            )

    first_media = MediaData(audio_path=first_path, audio_filename="clip.opus")
    second_media = MediaData(
        audio_path=second_path, audio_filename=second_original
    )

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(
            _config(initialized_bridge_home), CollidingKotlin()
        ).create_cards_batch(
            [_card("猫", media=first_media), _card("犬", media=second_media)]
        )

    assert exc_info.value.code == "media_name_collision"
    assert first_media.audio_filename == "clip.opus"
    assert second_media.audio_filename == second_original


def test_provider_duplicate_status_is_the_only_residual_duplicate_count(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [(["created", "duplicate"], None)]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    assert adapter.create_cards_batch([_card("猫"), _card("犬")]) == 1
    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 1


def test_provider_failed_status_propagates_and_is_never_counted_as_duplicate(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [
        (
            ["created", "failed", "notAttempted"],
            {"code": "write_failed", "message": "write exploded", "retryable": False},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(AnkiConnectionError, match="write exploded"):
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 0


@pytest.mark.parametrize(
    ("statuses", "error"),
    [
        (
            ["notAttempted", "created"],
            {"code": "write_failed", "message": "bad suffix", "retryable": False},
        ),
        (["failed"], None),
        (
            ["created"],
            {"code": "write_failed", "message": "orphan error", "retryable": False},
        ),
    ],
)
def test_create_result_failure_shape_is_strict(
    statuses: list[str],
    error: dict[str, object] | None,
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [(statuses, error)]

    with pytest.raises(BridgeProtocolError) as exc_info:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card(f"語{index}") for index in range(len(statuses))]
        )

    assert exc_info.value.code == "invalid_anki_response"


def test_duplicate_created_note_ids_are_rejected(
    initialized_bridge_home: Path,
) -> None:
    class DuplicateIdKotlin(FakeKotlinAnki):
        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            return encode_message(
                "anki.createnotes.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "clientNoteId": note["clientNoteId"],
                            "status": "created",
                            "noteId": 123,
                        }
                        for note in request["notes"]
                    ],
                    "error": None,
                },
            )

    with pytest.raises(BridgeProtocolError, match="duplicate note IDs"):
        _adapter(
            _config(initialized_bridge_home), DuplicateIdKotlin()
        ).create_cards_batch([_card("猫"), _card("犬")])


def test_created_note_ids_must_remain_unique_across_batches(
    initialized_bridge_home: Path,
) -> None:
    class ReusedIdKotlin(FakeKotlinAnki):
        def __init__(self) -> None:
            super().__init__()
            self.batch = 0

        def ankiCreateNotes(self, raw: str) -> str:
            request = self._request("ankiCreateNotes", raw)
            self.batch += 1
            first_id = 1 if self.batch == 2 else 0
            return encode_message(
                "anki.createnotes.result",
                {
                    "runId": request["runId"],
                    "requestId": request["requestId"],
                    "results": [
                        {
                            "clientNoteId": note["clientNoteId"],
                            "status": "created",
                            "noteId": first_id + index + 1,
                        }
                        for index, note in enumerate(request["notes"])
                    ],
                    "error": None,
                },
            )

    adapter = _adapter(_config(initialized_bridge_home), ReusedIdKotlin())
    with pytest.raises(BridgeProtocolError, match="earlier batch"):
        adapter.create_cards_batch([_card(f"語{index}") for index in range(101)])

    assert adapter.last_created_note_ids == list(range(1, 101))


def test_partial_create_cancellation_preserves_committed_ids_and_aborts(
    initialized_bridge_home: Path,
) -> None:
    kotlin = FakeKotlinAnki()
    kotlin.create_scripts = [
        (
            ["created", "notAttempted"],
            {"code": "cancelled", "message": "user stopped", "retryable": False},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    with pytest.raises(AnkiOperationCancelled, match="user stopped"):
        adapter.create_cards_batch([_card("猫"), _card("犬")])

    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 0


def test_bold_note_builder_diagnostics_match_desktop_behavior(
    initialized_bridge_home: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    precomputed = _card("猫")
    precomputed.word.sentence_bolded = "<b>猫</b>だ"
    precomputed.word.sentence_furigana_bolded = "<b>猫[よみ]</b>だ"
    fallback = _card("犬")

    with caplog.at_level("INFO", logger="android_bridge.anki_adapter"):
        _adapter(
            _config(initialized_bridge_home, bold_target_in_sentence=True),
            FakeKotlinAnki(),
        ).create_cards_batch([precomputed, fallback])

    assert (
        "bold_target_in_sentence=on: precomputed bold used on 1/2 cards "
        "(escape fallback: 1)"
    ) in caplog.messages
