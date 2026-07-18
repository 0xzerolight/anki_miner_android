from __future__ import annotations

import ast
import struct
from pathlib import Path

import android_bridge.tokenizer_s1b as tokenizer_s1b
import pytest
from android_bridge.tokenizer_contract import (
    TOKEN_WIRE_HEADER_FORMAT,
    TOKEN_WIRE_MAGIC,
    TOKEN_WIRE_RECORD_FORMAT,
    TOKEN_WIRE_VERSION,
    TokenizerContractError,
)
from android_bridge.tokenizer_s1b import S1bTokenizerBackend, create_s1b_tagger
from android_bridge.unidic_resource import RegisteredUniDic


def _registration(root: Path) -> RegisteredUniDic:
    return RegisteredUniDic(
        resource_id="unidic-lite-1.0.8",
        dicdir=root,
        mecabrc=root / "mecabrc",
        sys_dic=root / "sys.dic",
        tree_sha256="0" * 64,
        file_count=6,
        total_bytes=1,
    )


def _wire(text: str, feature: str) -> bytes:
    encoded = text.encode("utf-8")
    feature_bytes = feature.encode("utf-8")
    return b"".join(
        (
            struct.pack(
                TOKEN_WIRE_HEADER_FORMAT,
                TOKEN_WIRE_MAGIC,
                TOKEN_WIRE_VERSION,
                0,
                len(encoded),
                1,
            ),
            struct.pack(
                TOKEN_WIRE_RECORD_FORMAT,
                0,
                len(encoded),
                len(encoded),
                1,
                2,
                0,
                b"\x00\x00\x00",
                len(feature_bytes),
            ),
            feature_bytes,
        )
    )


class FakeNative:
    def __init__(self, root: Path, payload: bytes) -> None:
        self.root = root
        self.payload = payload
        self.init_argv: list[tuple[str, ...]] = []
        self.calls: list[tuple[bytes, tuple[str, ...]]] = []

    def loaded_dictionary_filenames(self, argv: tuple[str, ...]) -> tuple[str, ...]:
        self.init_argv.append(argv)
        return (str(self.root / "sys.dic"),)

    def tokenize(self, input_utf8: bytes, argv: tuple[str, ...]) -> bytes:
        self.calls.append((input_utf8, argv))
        return self.payload


def test_backend_passes_exact_utf8_and_complete_mecab_new_argv(tmp_path: Path) -> None:
    root = tmp_path.resolve()
    for name in ("mecabrc", "sys.dic"):
        (root / name).touch()
    feature = "名詞,普通名詞,一般,*,*,*,ネコ"
    native = FakeNative(root, _wire("猫", feature))

    records = S1bTokenizerBackend(_registration(root), native).tokenize("猫")

    expected_argv = (
        "anki_miner",
        "-C",
        "-r",
        str(root / "mecabrc"),
        "-d",
        str(root),
    )
    assert native.init_argv == [expected_argv]
    assert native.calls == [("猫".encode(), expected_argv)]
    assert records[0].features[:4] == ("名詞", "普通名詞", "一般", "*")


def test_tagger_factory_returns_copied_fugashi_shape(tmp_path: Path) -> None:
    root = tmp_path.resolve()
    for name in ("mecabrc", "sys.dic"):
        (root / name).touch()
    feature = "名詞,普通名詞,一般,*,*,*,ネコ,猫,猫,ネコ"
    tagger = create_s1b_tagger(
        _registration(root),
        native=FakeNative(root, _wire("猫", feature)),
    )

    token = tagger("猫")[0]

    assert token.surface == "猫"
    assert token.posid == 1
    assert token.char_type == 2
    assert token.feature.lemma == "猫"


def test_backend_rejects_dictionary_outside_registration(tmp_path: Path) -> None:
    root = tmp_path.resolve()
    (root / "mecabrc").touch()
    (root / "sys.dic").touch()
    native = FakeNative(root, _wire("猫", "名詞"))
    native.root = (tmp_path / "other").resolve()
    native.root.mkdir()
    (native.root / "sys.dic").touch()

    with pytest.raises(TokenizerContractError) as error:
        S1bTokenizerBackend(_registration(root), native)
    assert error.value.code == "unidic_provenance_mismatch"


def test_backend_translates_native_failures_without_masking_wire_errors(
    tmp_path: Path,
) -> None:
    root = tmp_path.resolve()
    (root / "mecabrc").touch()
    (root / "sys.dic").touch()

    class FailingNative(FakeNative):
        def tokenize(self, input_utf8: bytes, argv: tuple[str, ...]) -> bytes:
            raise RuntimeError("native detail")

    backend = S1bTokenizerBackend(_registration(root), FailingNative(root, b""))
    with pytest.raises(TokenizerContractError) as native_error:
        backend.tokenize("猫")
    assert native_error.value.code == "native_tokenizer_failed"

    malformed = S1bTokenizerBackend(_registration(root), FakeNative(root, b"not a wire"))
    with pytest.raises(TokenizerContractError) as wire_error:
        malformed.tokenize("猫")
    assert wire_error.value.code == "invalid_token_wire"


def test_java_import_is_lazy_and_confined_to_s1b_adapter() -> None:
    tree = ast.parse(Path(tokenizer_s1b.__file__).read_text(encoding="utf-8"))
    top_level_imports = {
        alias.name.split(".", 1)[0] for node in tree.body if isinstance(node, ast.Import) for alias in node.names
    }
    top_level_imports.update(
        node.module.split(".", 1)[0] for node in tree.body if isinstance(node, ast.ImportFrom) and node.module
    )

    assert "java" not in top_level_imports


def test_default_api_reports_stable_initialization_error_off_android(
    tmp_path: Path,
) -> None:
    root = tmp_path.resolve()
    (root / "mecabrc").touch()
    (root / "sys.dic").touch()

    with pytest.raises(TokenizerContractError) as error:
        S1bTokenizerBackend(_registration(root))

    assert error.value.code == "native_tokenizer_initialization_failed"
