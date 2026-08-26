from __future__ import annotations

import json

import pytest
from android_bridge import definitions
from android_bridge.protocol import BridgeProtocolError

RUN_A = "run_" + "1" * 32
RUN_B = "run_" + "2" * 32


class _FakeService:
    def __init__(self, hits: dict[str, list[tuple[str, str]]]) -> None:
        self._hits = hits
        self.closed = False
        self.queried: list[str] = []
        self.lemmas: list[str | None] = []

    def lookup_all_offline(self, word: str, lemma: str | None = None) -> list[tuple[str, str]]:
        self.queried.append(word)
        self.lemmas.append(lemma)
        return self._hits.get(word, [])

    def close(self) -> None:
        self.closed = True


def _request(run_id: str, term: str, fallback: str | None = None) -> dict[str, object]:
    return {"runId": run_id, "term": term, "fallbackTerm": fallback}


@pytest.fixture(autouse=True)
def _clean_registry():
    yield
    definitions.clear_run_dictionaries(RUN_A)
    definitions.clear_run_dictionaries(RUN_B)


def test_define_word_rejects_an_unregistered_run() -> None:
    with pytest.raises(BridgeProtocolError) as excinfo:
        definitions.define_word(_request(RUN_A, "猫"))
    assert excinfo.value.code == "definition_run_unknown"


def test_define_word_returns_chain_ordered_entries(monkeypatch) -> None:
    service = _FakeService({"猫": [("Jitendex", "<div>cat</div>"), ("JMdict", "<div>neko</div>")]})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    payload = json.loads(definitions.define_word(_request(RUN_A, "猫")))["payload"]
    assert payload["runId"] == RUN_A
    assert payload["term"] == "猫"
    assert payload["matchedTerm"] == "猫"
    assert payload["entries"] == [
        {"source": "Jitendex", "html": "<div>cat</div>"},
        {"source": "JMdict", "html": "<div>neko</div>"},
    ]
    assert service.closed is True


def test_the_fallback_never_overrides_a_hit_on_the_mined_form(monkeypatch) -> None:
    service = _FakeService({"やる": [("Jitendex", "<div>mined</div>")], "遣る": [("Jitendex", "<div>lemma</div>")]})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    payload = json.loads(definitions.define_word(_request(RUN_A, "やる", "遣る")))["payload"]
    assert payload["matchedTerm"] == "やる"
    assert payload["entries"] == [{"source": "Jitendex", "html": "<div>mined</div>"}]
    assert service.queried == ["やる"]


def test_the_lemma_scopes_the_mined_form_lookup(monkeypatch) -> None:
    # Rule A': the same token lemma the card path scopes by is passed through as
    # the pane's homograph scope, so both name the same lexeme for a kana front.
    service = _FakeService({"ゆう": [("Jitendex", "<div>iu</div>")]})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    payload = json.loads(definitions.define_word(_request(RUN_A, "ゆう", "言う")))["payload"]
    assert payload["matchedTerm"] == "ゆう"
    assert service.queried == ["ゆう"]
    assert service.lemmas == ["言う"]


def test_no_lemma_leaves_the_lookup_unscoped(monkeypatch) -> None:
    service = _FakeService({"猫": [("Jitendex", "<div>cat</div>")]})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    json.loads(definitions.define_word(_request(RUN_A, "猫")))
    assert service.lemmas == [None]


def test_the_fallback_fires_only_on_a_total_miss(monkeypatch) -> None:
    service = _FakeService({"遣る": [("Jitendex", "<div>lemma</div>")]})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    payload = json.loads(definitions.define_word(_request(RUN_A, "やらしい", "遣る")))["payload"]
    assert payload["matchedTerm"] == "遣る"
    assert service.queried == ["やらしい", "遣る"]


def test_define_word_reports_a_clean_miss(monkeypatch) -> None:
    service = _FakeService({})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    payload = json.loads(definitions.define_word(_request(RUN_A, "ぬぬぬ")))["payload"]
    assert payload["entries"] == []
    assert payload["matchedTerm"] == "ぬぬぬ"


def test_define_word_bounds_the_response(monkeypatch) -> None:
    oversized = "x" * (definitions.MAX_DEFINITION_HTML_BYTES + 1)
    service = _FakeService({"猫": [("Jitendex", oversized)]})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    with pytest.raises(BridgeProtocolError) as excinfo:
        definitions.define_word(_request(RUN_A, "猫"))
    assert excinfo.value.code == "definition_result_too_large"


def test_a_closed_provider_chain_is_released_even_when_lookup_raises(monkeypatch) -> None:
    class _Boom(_FakeService):
        def lookup_all_offline(self, word: str, lemma: str | None = None) -> list[tuple[str, str]]:
            raise RuntimeError("index unreadable")

    service = _Boom({})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    with pytest.raises(RuntimeError):
        definitions.define_word(_request(RUN_A, "猫"))
    assert service.closed is True


def test_clearing_one_run_leaves_another_registered(monkeypatch) -> None:
    service = _FakeService({})
    monkeypatch.setattr(definitions, "_build_service", lambda config: service)
    definitions.register_run_dictionaries(RUN_A, object())
    definitions.register_run_dictionaries(RUN_B, object())
    definitions.clear_run_dictionaries(RUN_A)
    with pytest.raises(BridgeProtocolError):
        definitions.define_word(_request(RUN_A, "猫"))
    assert json.loads(definitions.define_word(_request(RUN_B, "猫")))["payload"]["entries"] == []


def test_clearing_an_unregistered_run_is_a_no_op() -> None:
    definitions.clear_run_dictionaries(RUN_A)


@pytest.mark.parametrize(
    "payload",
    [
        {"runId": RUN_A, "term": "猫"},
        {"runId": RUN_A, "term": "猫", "fallbackTerm": None, "extra": 1},
        {"runId": "not-a-run", "term": "猫", "fallbackTerm": None},
        {"runId": RUN_A, "term": "  ", "fallbackTerm": None},
        {"runId": RUN_A, "term": 5, "fallbackTerm": None},
    ],
)
def test_define_word_rejects_malformed_payloads(payload: dict[str, object]) -> None:
    with pytest.raises(BridgeProtocolError):
        definitions.define_word(payload)
