"""Fugashi tokenizer candidate over the shared copied-record contract.

The module deliberately does not select S1a for the vendored engine.  Fugashi
is imported only while constructing the opt-in candidate, so ordinary Android
builds remain usable without the custom wheels.
"""

from __future__ import annotations

import importlib
import shlex
import threading
from collections.abc import Callable
from typing import Protocol

from .tokenizer_contract import (
    TaggerAdapter,
    TokenRecord,
    TokenizerContractError,
    Utf8OffsetMap,
    decode_mecab_feature_csv,
    validate_token_records,
)
from .unidic_resource import RegisteredUniDic, validate_loaded_dictionary_filenames


class _Node(Protocol):
    surface: str
    feature_raw: str
    length: int
    rlength: int
    posid: int
    char_type: int
    stat: int


class _Tagger(Protocol):
    dictionary_info: object

    def __call__(self, text: str) -> object: ...


TaggerFactory = Callable[[str], _Tagger]


def _default_tagger_factory(arguments: str) -> _Tagger:
    fugashi = importlib.import_module("fugashi")
    return fugashi.GenericTagger(arguments)


class S1aTokenizerBackend:
    """Copy all native-backed Fugashi node data before releasing the lock."""

    __slots__ = ("_lock", "_tagger")

    def __init__(
        self,
        registration: RegisteredUniDic,
        *,
        tagger_factory: TaggerFactory | None = None,
    ) -> None:
        if not isinstance(registration, RegisteredUniDic):
            raise TokenizerContractError(
                "invalid_unidic_registration",
                "S1a requires a verified RegisteredUniDic",
            )
        factory = tagger_factory or _default_tagger_factory
        try:
            tagger = factory(shlex.join(registration.mecab_arguments))
            infos = tagger.dictionary_info
            filenames = tuple(str(info["filename"]) for info in infos)  # type: ignore[index]
            validate_loaded_dictionary_filenames(filenames, registration=registration)
        except TokenizerContractError:
            raise
        except Exception as exc:
            raise TokenizerContractError(
                "s1a_tokenizer_initialization_failed",
                "Could not initialize the S1a Fugashi tokenizer",
            ) from exc
        self._tagger = tagger
        self._lock = threading.RLock()

    def tokenize(self, text: str) -> tuple[TokenRecord, ...]:
        offsets = Utf8OffsetMap(text)
        records: list[TokenRecord] = []
        previous_end = 0
        try:
            with self._lock:
                nodes = self._tagger(text)
                for node_value in nodes:  # type: ignore[union-attr]
                    node: _Node = node_value
                    feature_raw = node.feature_raw
                    length = node.length
                    raw_length = node.rlength
                    pos_id = node.posid
                    char_type = node.char_type
                    status = node.stat
                    surface = node.surface

                    byte_end = previous_end + raw_length
                    byte_start = byte_end - length
                    if (
                        byte_start < 0
                        or byte_end > offsets.byte_length
                        or surface.encode("utf-8")
                        != offsets.encoded[byte_start:byte_end]
                    ):
                        raise TokenizerContractError(
                            "s1a_surface_mismatch",
                            "Fugashi node surface does not match its MeCab byte span",
                        )
                    records.append(
                        TokenRecord(
                            byte_start=byte_start,
                            byte_end=byte_end,
                            raw_length=raw_length,
                            pos_id=pos_id,
                            char_type=char_type,
                            status=status,
                            features=decode_mecab_feature_csv(feature_raw),
                        )
                    )
                    previous_end = byte_end
        except TokenizerContractError:
            raise
        except Exception as exc:
            raise TokenizerContractError(
                "s1a_tokenizer_failed",
                "The S1a Fugashi tokenizer could not parse the input",
            ) from exc

        validate_token_records(text, records)
        return tuple(records)


def create_s1a_tagger(
    registration: RegisteredUniDic,
    *,
    tagger_factory: TaggerFactory | None = None,
) -> TaggerAdapter:
    """Build a fugashi-shaped callable without changing engine selection."""

    return TaggerAdapter(S1aTokenizerBackend(registration, tagger_factory=tagger_factory))
