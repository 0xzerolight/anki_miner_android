from __future__ import annotations

import json
import sys
import types
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from android_bridge.anki_adapter import AndroidAnkiAdapter
from android_bridge.callbacks import AndroidAnkiCallbacks
from android_bridge.protocol import BridgeProtocolError, encode_message

RUN_ID = "run_" + "a" * 32


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
        self.media_renames: dict[str, str] = {}
        self.create_scripts: list[
            tuple[list[str], dict[str, object] | None] | None
        ] = []
        self.next_note_id = 1000

    def _request(self, method: str, raw: str) -> dict[str, Any]:
        envelope = json.loads(raw)
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
        fields = self.verify_fields or request["requiredFields"]
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
        fields = (
            self.known_fields
            if request["scope"]["kind"] == "knownVocabulary"
            else self.duplicate_fields
        )
        return encode_message(
            "anki.scanfirstfields.result",
            {
                "runId": request["runId"],
                "requestId": request["requestId"],
                "firstFields": fields,
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
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "failed",
                        "errorCode": "media_store_failed",
                    }
                )
            else:
                results.append(
                    {
                        "assetId": asset["assetId"],
                        "status": "stored",
                        "actualFilename": self.media_renames.get(preferred, preferred),
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
    assert progress.events == [
        ("start", 101, "Creating Anki cards"),
        ("progress", 100, "Cards created: 100/101"),
        ("progress", 101, "Cards created: 101/101"),
        ("complete",),
    ]


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
    preferred = _content_addressed_name("shot.webp", b"same image")
    kotlin = FakeKotlinAnki()
    kotlin.media_renames[preferred] = "provider-shot.webp"
    adapter = _adapter(_config(initialized_bridge_home), kotlin)

    created = adapter.create_cards_batch(
        [_card("猫", media=media_a), _card("犬", media=media_b)]
    )

    assert created == 2
    assert adapter.last_media_store_failures == 1
    assert media_a.screenshot_filename == "provider-shot.webp"
    assert media_b.screenshot_filename == "provider-shot.webp"
    store_payload = kotlin.requests_for("ankiStoreMedia")[0]["payload"]
    assert len(store_payload["assets"]) == 1
    assert store_payload["assets"][0]["preferredName"] == preferred
    assert store_payload["assets"][0]["sourcePath"] == str(image.resolve())
    note_fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"]
    assert all(
        note["fields"]["Picture"] == '<img src="provider-shot.webp">'
        for note in note_fields
    )


def test_declared_media_failure_is_nonfatal_and_omits_reference(
    initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData
    from anki_miner.services.anki_media_store import _content_addressed_name

    audio = tmp_path / "clip.opus"
    audio.write_bytes(b"audio")
    preferred = _content_addressed_name("clip.opus", b"audio")
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
    media_path.parent.mkdir(parents=True)
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
    assert asset["preferredName"] == "dict__pic.png"
    assert asset["sourcePath"] == str(media_path.resolve())


def test_partial_create_response_preserves_ids_counters_and_vocab_cache(
    initialized_bridge_home: Path,
) -> None:
    from anki_miner.exceptions import AnkiConnectionError

    kotlin = FakeKotlinAnki()
    kotlin.known_fields = ["既知"]
    kotlin.create_scripts = [
        (
            ["created", "rejected", "notAttempted"],
            {"code": "write_failed", "message": "mid-batch", "retryable": True},
        )
    ]
    adapter = _adapter(_config(initialized_bridge_home), kotlin)
    assert adapter.get_existing_vocabulary() == {"既知"}

    with pytest.raises(AnkiConnectionError, match="mid-batch"):
        adapter.create_cards_batch([_card("猫"), _card("犬"), _card("鳥")])

    assert adapter.last_created_note_ids == [1000]
    assert adapter.last_skipped_duplicates == 1
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
