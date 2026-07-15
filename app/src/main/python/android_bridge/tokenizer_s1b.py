"""S1b JNI tokenizer candidate over the shared copied-record contract.

This module does not select S1b for the engine. Candidate selection remains a
later parity decision, so the fugashi/S1a path can coexist unchanged.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

from .tokenizer_contract import (
    TaggerAdapter,
    TokenRecord,
    TokenizerContractError,
    Utf8OffsetMap,
    decode_token_wire,
)
from .unidic_resource import (
    RegisteredUniDic,
    validate_loaded_dictionary_filenames,
)


class NativeTokenizerApi(Protocol):
    """Copied-value JVM surface used by the Python backend."""

    def tokenize(self, input_utf8: bytes, argv: tuple[str, ...]) -> bytes:
        """Return one complete AMTK wire buffer."""

    def loaded_dictionary_filenames(
        self, argv: tuple[str, ...]
    ) -> Sequence[str]:
        """Return copied MeCab dictionary-info filenames."""


class _ChaquopyNativeTokenizerApi:
    """Lazy Java adapter kept out of backend-neutral contract modules."""

    def __init__(self) -> None:
        from java import jarray, jbyte, jclass  # type: ignore[import-not-found]

        self._jarray = jarray
        self._jbyte = jbyte
        self._string = jclass("java.lang.String")
        self._tokenizer = jclass(
            "com.ankiminer.android.tokenizer.MecabNativeTokenizer"
        )

    def _java_argv(self, argv: tuple[str, ...]) -> object:
        return self._jarray(self._string)(argv)

    def tokenize(self, input_utf8: bytes, argv: tuple[str, ...]) -> bytes:
        java_input = self._jarray(self._jbyte)(input_utf8)
        return bytes(self._tokenizer.tokenize(java_input, self._java_argv(argv)))

    def loaded_dictionary_filenames(
        self, argv: tuple[str, ...]
    ) -> tuple[str, ...]:
        values = self._tokenizer.loadedDictionaryFilenames(self._java_argv(argv))
        return tuple(str(value) for value in values)


class S1bTokenizerBackend:
    """MeCab-NDK backend which materializes every node before returning."""

    __slots__ = ("_argv", "_native")

    def __init__(
        self,
        registration: RegisteredUniDic,
        native: NativeTokenizerApi | None = None,
    ) -> None:
        if not isinstance(registration, RegisteredUniDic):
            raise TokenizerContractError(
                "invalid_unidic_registration",
                "S1b requires a verified RegisteredUniDic",
            )
        self._argv = registration.mecab_new_argv
        try:
            self._native = (
                native if native is not None else _ChaquopyNativeTokenizerApi()
            )
            filenames = tuple(
                self._native.loaded_dictionary_filenames(self._argv)
            )
        except Exception as exc:
            raise TokenizerContractError(
                "native_tokenizer_initialization_failed",
                "Could not initialize the S1b native tokenizer",
            ) from exc
        validate_loaded_dictionary_filenames(
            filenames,
            registration=registration,
        )

    def tokenize(self, text: str) -> tuple[TokenRecord, ...]:
        offsets = Utf8OffsetMap(text)
        try:
            payload = self._native.tokenize(offsets.encoded, self._argv)
        except Exception as exc:
            raise TokenizerContractError(
                "native_tokenizer_failed",
                "The S1b native tokenizer could not parse the input",
            ) from exc
        return decode_token_wire(payload, text)


def create_s1b_tagger(
    registration: RegisteredUniDic,
    *,
    native: NativeTokenizerApi | None = None,
) -> TaggerAdapter:
    """Build a fugashi-shaped callable without changing engine selection."""

    return TaggerAdapter(S1bTokenizerBackend(registration, native))
