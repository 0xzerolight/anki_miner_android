"""Configure the settled tokenizer through one guarded runtime operation."""

from __future__ import annotations

import os
import threading
from collections.abc import Mapping

from .protocol import BridgeProtocolError, encode_message
from .tokenizer_contract import TokenizerContractError
from .tokenizer_selection import configure_tokenizer_backend
from .unidic_resource import (
    RegisteredUniDic,
    register_unidic,
    validate_unidic_identity_inputs,
)

_LOCK = threading.Lock()
_configuration_requires_restart = False


def _safe_contract_error(error: TokenizerContractError) -> BridgeProtocolError:
    return BridgeProtocolError(
        error.code,
        "Tokenizer resource configuration was rejected",
    )


def _restart_required_error() -> BridgeProtocolError:
    return BridgeProtocolError(
        "tokenizer_restart_required",
        "Tokenizer setup cannot continue until the Python process is restarted",
    )


def _validate_request(
    payload: Mapping[str, object],
) -> tuple[str, str, str, str]:
    dic_dir = payload["dicDir"]
    resource_id = payload["resourceId"]
    tree_sha256 = payload["treeSha256"]
    backend = payload["backend"]

    if (
        not isinstance(dic_dir, str)
        or not dic_dir
        or "\x00" in dic_dir
        or not os.path.isabs(dic_dir)
    ):
        raise BridgeProtocolError(
            "invalid_unidic_path",
            "Tokenizer dictionary path must be a non-empty absolute string",
        )
    if backend != "s1a":
        raise BridgeProtocolError(
            "invalid_tokenizer_backend",
            "Tokenizer backend must be 's1a'",
        )
    try:
        validate_unidic_identity_inputs(resource_id, tree_sha256)
    except TokenizerContractError as error:
        raise _safe_contract_error(error) from error

    # The identity validator proves both values are strings.
    assert isinstance(resource_id, str)
    assert isinstance(tree_sha256, str)
    assert isinstance(backend, str)
    return dic_dir, resource_id, tree_sha256, backend


def _ready_message(registration: RegisteredUniDic, backend: str) -> str:
    return encode_message(
        "tokenizer.ready",
        {
            "backend": backend,
            "resourceId": registration.resource_id,
            "dicDir": os.fspath(registration.dicdir),
            "treeSha256": registration.tree_sha256,
            "fileCount": registration.file_count,
            "totalBytes": registration.total_bytes,
        },
    )


def configure_tokenizer(payload: Mapping[str, object]) -> str:
    """Verify external UniDic provenance and bind the settled S1a backend.

    The caller must enforce the exact payload shape and bootstrap ordering.
    Cheap, mutation-free validation happens before the operation lock. Once
    dictionary registration succeeds, any ordinary backend-binding failure
    poisons this process: retrying could otherwise observe a partly mutated
    engine tagger seam.
    """

    dic_dir, resource_id, tree_sha256, backend = _validate_request(payload)

    global _configuration_requires_restart
    with _LOCK:
        if _configuration_requires_restart:
            raise _restart_required_error()

        try:
            registration = register_unidic(
                dic_dir,
                resource_id=resource_id,
                expected_tree_sha256=tree_sha256,
            )
        except TokenizerContractError as error:
            raise _safe_contract_error(error) from error

        try:
            selected_backend = configure_tokenizer_backend(backend)
        except Exception as error:
            _configuration_requires_restart = True
            raise _restart_required_error() from error

        return _ready_message(registration, selected_backend)
