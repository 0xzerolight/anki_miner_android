from __future__ import annotations

import importlib.util
import shlex
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from android_bridge.tokenizer_contract import TokenizerContractError
from android_bridge.tokenizer_s1a import S1aTokenizerBackend, create_s1a_tagger
from android_bridge.unidic_resource import RegisteredUniDic


def _registration(tmp_path: Path) -> RegisteredUniDic:
    (tmp_path / "sys.dic").write_bytes(b"test")
    return RegisteredUniDic(
        resource_id="test-unidic",
        dicdir=tmp_path,
        mecabrc=tmp_path / "mecab rc",
        sys_dic=tmp_path / "sys.dic",
        tree_sha256="0" * 64,
        file_count=6,
        total_bytes=1,
    )


def _features(**values: str) -> str:
    names = [
        "pos1",
        "pos2",
        "pos3",
        "pos4",
        "cType",
        "cForm",
        "lForm",
        "lemma",
        "orth",
        "pron",
        "orthBase",
        "pronBase",
        "goshu",
        "iType",
        "iForm",
        "fType",
        "fForm",
        "kana",
        "kanaBase",
        "form",
        "formBase",
        "iConType",
        "fConType",
        "aType",
        "aConType",
        "aModeType",
    ]
    return ",".join(values.get(name, "*") for name in names)


class FakeTagger:
    def __init__(self, sys_dic: Path, nodes: list[SimpleNamespace]) -> None:
        self.dictionary_info = [{"filename": str(sys_dic)}]
        self.nodes = nodes

    def __call__(self, _text: str) -> list[SimpleNamespace]:
        return self.nodes


def _node(surface: str, raw_length: int, **features: str) -> SimpleNamespace:
    encoded = surface.encode("utf-8")
    return SimpleNamespace(
        surface=surface,
        feature_raw=_features(**features),
        length=len(encoded),
        rlength=raw_length,
        posid=1,
        char_type=2,
        stat=0,
    )


def test_constructor_quotes_arguments_and_rejects_other_dictionary(tmp_path: Path) -> None:
    registration = _registration(tmp_path)
    captured: list[str] = []

    def factory(arguments: str) -> FakeTagger:
        captured.append(arguments)
        return FakeTagger(registration.sys_dic, [])

    S1aTokenizerBackend(registration, tagger_factory=factory)
    assert shlex.split(captured[0]) == list(registration.mecab_arguments)

    with pytest.raises(TokenizerContractError) as mismatch:
        (tmp_path / "other.dic").write_bytes(b"other")
        S1aTokenizerBackend(
            registration,
            tagger_factory=lambda _args: FakeTagger(tmp_path / "other.dic", []),
        )
    assert mismatch.value.code == "unidic_provenance_mismatch"


def test_backend_copies_nodes_and_preserves_literal_sentinels(tmp_path: Path) -> None:
    registration = _registration(tmp_path)
    nodes = [
        _node("猫", 4, pos1="名詞", pos2="普通名詞", lemma="猫", orthBase="猫"),
        _node("犬", 5, pos1="名詞", pos2="普通名詞", lemma="犬", orthBase="犬"),
    ]
    tagger = create_s1a_tagger(
        registration,
        tagger_factory=lambda _args: FakeTagger(registration.sys_dic, nodes),
    )

    tokens = tagger(" 猫  犬 ")
    nodes[0].feature_raw = "corrupted"
    nodes[0].surface = "壊"

    assert [token.surface for token in tokens] == ["猫", "犬"]
    assert [token.white_space for token in tokens] == [" ", "  "]
    assert tokens[0].feature.pos4 == "*"
    assert tokens[0].feature.aModeType == "*"


def test_short_oov_row_distinguishes_literal_stars_from_absent_fields(
    tmp_path: Path,
) -> None:
    registration = _registration(tmp_path)
    node = _node("𠮟𠮟𠮟", 12)
    node.feature_raw = "補助記号,一般,*,*,*,*"
    node.stat = 1
    tagger = create_s1a_tagger(
        registration,
        tagger_factory=lambda _args: FakeTagger(registration.sys_dic, [node]),
    )

    token = tagger("𠮟𠮟𠮟")[0]

    assert token.is_unk is True
    assert token.feature.pos3 == "*"
    assert token.feature.cForm == "*"
    assert token.feature.lForm is None
    assert token.feature.orthBase is None


def test_surface_association_is_mandatory(tmp_path: Path) -> None:
    registration = _registration(tmp_path)
    node = _node("犬", 3, pos1="名詞")
    backend = S1aTokenizerBackend(
        registration,
        tagger_factory=lambda _args: FakeTagger(registration.sys_dic, [node]),
    )

    with pytest.raises(TokenizerContractError) as error:
        backend.tokenize("猫")
    assert error.value.code == "s1a_surface_mismatch"


def test_parse_and_copy_are_serialized(tmp_path: Path) -> None:
    registration = _registration(tmp_path)
    active = 0
    maximum = 0
    guard = threading.Lock()

    class SlowTagger(FakeTagger):
        def __call__(self, text: str) -> list[SimpleNamespace]:
            nonlocal active, maximum
            with guard:
                active += 1
                maximum = max(maximum, active)
            time.sleep(0.02)
            try:
                return [_node(text, len(text.encode("utf-8")), pos1="名詞")]
            finally:
                with guard:
                    active -= 1

    backend = S1aTokenizerBackend(
        registration,
        tagger_factory=lambda _args: SlowTagger(registration.sys_dic, []),
    )
    threads = [threading.Thread(target=backend.tokenize, args=("猫",)) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert maximum == 1


def test_default_factory_is_lazy(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    registration = _registration(tmp_path)
    sys.modules.pop("fugashi", None)
    imported: list[str] = []

    def fake_import(name: str) -> object:
        imported.append(name)
        return SimpleNamespace(GenericTagger=lambda _args: FakeTagger(registration.sys_dic, []))

    monkeypatch.setattr("android_bridge.tokenizer_s1a.importlib.import_module", fake_import)
    S1aTokenizerBackend(registration)
    assert imported == ["fugashi"]


def test_literal_star_reaches_compound_morphology(tmp_path: Path) -> None:
    morphology_path = Path(__file__).resolve().parents[3] / "app/src/main/python/anki_miner/services/morphology.py"
    spec = importlib.util.spec_from_file_location("s1a_morphology_probe", morphology_path)
    assert spec is not None and spec.loader is not None
    morphology = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = morphology
    spec.loader.exec_module(morphology)

    registration = _registration(tmp_path)
    nodes = [
        _node("不", 3, pos1="接頭辞", pos2="*", lemma="不", kana="フ"),
        _node("可能", 6, pos1="形状詞", pos2="*", lemma="可能", kana="カノウ"),
    ]
    tagger = create_s1a_tagger(
        registration,
        tagger_factory=lambda _args: FakeTagger(registration.sys_dic, nodes),
    )

    raw_tokens = tagger("不可能")
    assert raw_tokens[1].feature.pos2 == "*"
    merged = morphology.merge_compound_suffixes(raw_tokens)
    assert len(merged) == 1
    assert merged[0].surface == "不可能"
    assert merged[0].feature.pos2 == "普通名詞"


def test_invalid_registration_and_tagger_errors_are_stable(tmp_path: Path) -> None:
    registration = _registration(tmp_path)
    with pytest.raises(TokenizerContractError) as invalid:
        S1aTokenizerBackend(object())  # type: ignore[arg-type]
    assert invalid.value.code == "invalid_unidic_registration"

    backend = S1aTokenizerBackend(
        registration,
        tagger_factory=lambda _args: FakeTagger(registration.sys_dic, []),
    )
    backend._tagger = lambda _text: (_ for _ in ()).throw(RuntimeError("boom"))  # type: ignore[method-assign]
    with pytest.raises(TokenizerContractError) as failed:
        backend.tokenize("猫")
    assert failed.value.code == "s1a_tokenizer_failed"
