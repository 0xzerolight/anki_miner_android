from __future__ import annotations

import copy
import hashlib
import json
import re
from pathlib import Path
from typing import Any, NoReturn

import pytest

from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message
from request_digest_reference import (
    ANKI_REQUEST_DIGEST_DOMAIN,
    ANKI_REQUEST_DIGEST_VERSION,
    _compute_prevalidated_request_digest as _reference_digest,
)
from test_anki_protocol_corpus import (
    _ContractRejection,
    _validate_schema,
    _validate_semantics,
)

PROJECT_ROOT = Path(__file__).resolve().parents[3]
FIXTURE_PATH = PROJECT_ROOT / "golden/bridge/anki-request-digest-v1.jsonl"
MUTATION_PATH = PROJECT_ROOT / "golden/bridge/anki-request-digest-mutations-v1.jsonl"

_MESSAGE_TYPES = {
    "verifyTarget": "anki.verifytarget.request",
    "scanFirstFields": "anki.scanfirstfields.request",
    "storeMedia": "anki.storemedia.request",
    "createNotes": "anki.createnotes.request",
    "releaseRunState": "anki.releaserunstate.request",
}

_EXPECTED_MUTATION_LEAVES = {
    "verify.runId",
    "verify.requestId",
    "verify.deckName",
    "verify.modelName",
    "verify.requiredFields.item",
    "verify.requiredFields.append",
    "verify.requiredFields.order",
    "scan.runId",
    "scan.requestId",
    "scan.known.excludedDecks.item",
    "scan.known.excludedDecks.append",
    "scan.known.cursor.ordinal",
    "scan.known.cursor.token",
    "scan.known.cursor.nullable",
    "scan.duplicates.modelName",
    "scan.duplicates.firstFieldName",
    "scan.duplicates.deckName.nullable",
    "scan.duplicates.candidate.key",
    "scan.duplicates.candidate.firstField",
    "scan.duplicates.occurrences.list",
    "scan.duplicates.invalidateBaselineToken.nullable",
    "store.runId",
    "store.requestId",
    "store.assets.order",
    "store.asset.assetId",
    "store.asset.sourcePath",
    "store.asset.requestedFilename",
    "store.asset.mediaKind",
    "store.asset.expectedSizeBytes",
    "store.asset.expectedSha256",
    "store.assets.dictionary.append",
    "create.runId",
    "create.requestId",
    "create.deckName",
    "create.modelName",
    "create.firstFieldName",
    "create.baselineToken",
    "create.duplicateScope.variant",
    "create.notes.append",
    "create.note.clientNoteId",
    "create.note.fields.key",
    "create.note.fields.value",
    "create.note.tags.item",
    "create.note.tags.append",
    "create.note.duplicateCandidate.key",
    "create.note.duplicateCandidate.firstField",
    "create.note.duplicateCandidate.occurrence",
    "create.note.mediaBindings.append",
    "create.note.mediaBinding.assetId",
    "create.note.mediaBinding.actualFilename",
    "release.runId",
    "release.requestId",
    "release.acknowledgeTerminalResponses",
    "reject.cursor.longMin",
    "reject.cursor.longMinMinusOne",
    "reject.occurrence.intMaxPlusOne",
}


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate fixture key: {key}")
        result[key] = value
    return result


def _load_jsonl(path: Path) -> tuple[dict[str, Any], ...]:
    raw = path.read_bytes()
    assert raw and raw.endswith(b"\n") and not raw.startswith(b"\xef\xbb\xbf")
    records: list[dict[str, Any]] = []
    for encoded_line in raw.splitlines():
        records.append(
            json.loads(
                encoded_line.decode("utf-8", errors="strict"),
                object_pairs_hook=_reject_duplicate_keys,
            )
        )
    return tuple(records)


def _load_vectors() -> tuple[dict[str, Any], ...]:
    vectors = _load_jsonl(FIXTURE_PATH)
    seen: set[str] = set()
    for vector in vectors:
        assert set(vector) == {
            "id",
            "operation",
            "raw",
            "canonical",
            "sha256",
            "rejectCategory",
        }
        assert re.fullmatch(r"[a-z0-9]+(?:_[a-z0-9]+)*", vector["id"])
        assert vector["id"] not in seen
        assert vector["operation"] in _MESSAGE_TYPES
        assert isinstance(vector["raw"], str)
        if vector["rejectCategory"] is None:
            assert isinstance(vector["canonical"], str)
            assert re.fullmatch(r"[0-9a-f]{64}", vector["sha256"])
        else:
            assert vector["canonical"] is None and vector["sha256"] is None
            assert isinstance(vector["rejectCategory"], str)
        seen.add(vector["id"])
    return vectors


def _load_mutations() -> tuple[dict[str, Any], ...]:
    mutations = _load_jsonl(MUTATION_PATH)
    seen: set[str] = set()
    leaves: set[str] = set()
    for mutation in mutations:
        assert set(mutation) == {
            "id",
            "base",
            "leaf",
            "kind",
            "path",
            "value",
            "sha256",
            "rejectCategory",
        }
        assert re.fullmatch(r"[a-z0-9]+(?:_[a-z0-9]+)*", mutation["id"])
        assert mutation["id"] not in seen
        assert isinstance(mutation["base"], str)
        assert mutation["kind"] in {"replace", "append", "renameKey", "reverse"}
        assert isinstance(mutation["path"], list) and mutation["path"]
        assert all(
            isinstance(part, str) or (type(part) is int and part >= 0)
            for part in mutation["path"]
        )
        assert isinstance(mutation["leaf"], str) and mutation["leaf"] not in leaves
        if mutation["rejectCategory"] is None:
            assert re.fullmatch(r"[0-9a-f]{64}", mutation["sha256"])
        else:
            assert mutation["sha256"] is None
        seen.add(mutation["id"])
        leaves.add(mutation["leaf"])
    assert leaves == _EXPECTED_MUTATION_LEAVES
    return mutations


VECTORS = _load_vectors()
MUTATIONS = _load_mutations()


def _validated_payload(raw: str, operation: str) -> dict[str, Any]:
    try:
        message = decode_envelope(raw, expected_type=_MESSAGE_TYPES[operation])
    except BridgeProtocolError as error:
        raise _ContractRejection(error.code) from error
    _validate_schema(message.message_type, message.payload)
    _validate_semantics(message.message_type, message.payload, operation)
    return message.payload


def _validated_reference(
    operation: str,
    payload: dict[str, Any],
) -> tuple[int, bytes, str]:
    message_type = _MESSAGE_TYPES[operation]
    try:
        raw = encode_message(message_type, payload)
    except BridgeProtocolError as error:
        raise _ContractRejection(error.code) from error
    validated = _validated_payload(raw, operation)
    return _reference_digest(operation, validated)


def _resolve(container: object, path: list[object]) -> object:
    current = container
    for part in path:
        if isinstance(part, str) and isinstance(current, dict):
            current = current[part]
        elif type(part) is int and isinstance(current, list):
            current = current[part]
        else:
            raise AssertionError(f"invalid mutation path segment: {part!r}")
    return current


def _apply_mutation(payload: dict[str, Any], mutation: dict[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(payload)
    path = mutation["path"]
    kind = mutation["kind"]
    if kind == "append":
        target = _resolve(result, path)
        assert isinstance(target, list)
        target.append(copy.deepcopy(mutation["value"]))
        return result
    if kind == "reverse":
        target = _resolve(result, path)
        assert isinstance(target, list)
        target.reverse()
        return result

    parent = _resolve(result, path[:-1])
    last = path[-1]
    if kind == "replace":
        if isinstance(last, str) and isinstance(parent, dict):
            assert last in parent
            parent[last] = copy.deepcopy(mutation["value"])
        elif type(last) is int and isinstance(parent, list):
            parent[last] = copy.deepcopy(mutation["value"])
        else:
            raise AssertionError("replace mutation path is invalid")
        return result

    assert kind == "renameKey" and isinstance(parent, dict) and isinstance(last, str)
    new_key = mutation["value"]
    assert isinstance(new_key, str) and last in parent and new_key not in parent
    parent[new_key] = parent.pop(last)
    return result


def _materialize_mutations() -> dict[str, tuple[str, dict[str, Any]]]:
    materialized: dict[str, tuple[str, dict[str, Any]]] = {}
    for vector in VECTORS:
        if vector["rejectCategory"] is None:
            materialized[vector["id"]] = (
                vector["operation"],
                _validated_payload(vector["raw"], vector["operation"]),
            )
    for mutation in MUTATIONS:
        assert mutation["base"] in materialized
        operation, base_payload = materialized[mutation["base"]]
        materialized[mutation["id"]] = (
            operation,
            _apply_mutation(base_payload, mutation),
        )
    return materialized


MATERIALIZED = _materialize_mutations()


@pytest.mark.parametrize("vector", VECTORS, ids=lambda vector: vector["id"])
def test_shared_request_digest_vectors(vector: dict[str, Any]) -> None:
    if vector["rejectCategory"] is not None:
        with pytest.raises(_ContractRejection) as raised:
            _validated_payload(vector["raw"], vector["operation"])
        assert raised.value.category == vector["rejectCategory"]
        return

    payload = _validated_payload(vector["raw"], vector["operation"])
    version, canonical, digest = _validated_reference(vector["operation"], payload)
    assert version == ANKI_REQUEST_DIGEST_VERSION == 1
    assert canonical == vector["canonical"].encode("utf-8")
    assert digest == vector["sha256"] == hashlib.sha256(canonical).hexdigest()
    assert canonical.startswith(
        (
            '{"domain":"'
            + ANKI_REQUEST_DIGEST_DOMAIN
            + '","digestVersion":1,'
        ).encode()
    )
    assert digest != hashlib.sha256(vector["raw"].encode()).hexdigest()


@pytest.mark.parametrize("mutation", MUTATIONS, ids=lambda mutation: mutation["id"])
def test_shared_validated_one_leaf_mutations(mutation: dict[str, Any]) -> None:
    operation, payload = MATERIALIZED[mutation["id"]]
    if mutation["rejectCategory"] is not None:
        with pytest.raises(_ContractRejection) as raised:
            _validated_reference(operation, payload)
        assert raised.value.category == mutation["rejectCategory"]
        return

    version, canonical, digest = _validated_reference(operation, payload)
    assert version == 1
    assert digest == mutation["sha256"] == hashlib.sha256(canonical).hexdigest()
    base_operation, base_payload = MATERIALIZED[mutation["base"]]
    assert base_operation == operation
    assert digest != _validated_reference(base_operation, base_payload)[2]


def test_rejected_cases_never_reach_reference_hashing(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = 0

    def forbidden_reference(*_args: object, **_kwargs: object) -> NoReturn:
        nonlocal calls
        calls += 1
        raise AssertionError("invalid request reached reference digest")

    monkeypatch.setattr(
        "test_request_digest._reference_digest",
        forbidden_reference,
    )
    for mutation in MUTATIONS:
        if mutation["rejectCategory"] is None:
            continue
        operation, payload = MATERIALIZED[mutation["id"]]
        with pytest.raises(_ContractRejection):
            _validated_reference(operation, payload)
    assert calls == 0


def test_wire_order_map_order_and_numeric_aliases_do_not_change_identity() -> None:
    digests = _accepted_vector_digests()
    assert digests["scan_known_integer"] == digests["scan_known_float"]
    assert digests["scan_known_integer"] == digests["scan_known_exponent"]
    assert digests["create_fields_reverse"] == digests["create_fields_forward"]


def _accepted_vector_digests() -> dict[str, str]:
    result: dict[str, str] = {}
    for vector in VECTORS:
        if vector["rejectCategory"] is None:
            payload = _validated_payload(vector["raw"], vector["operation"])
            result[vector["id"]] = _validated_reference(
                vector["operation"], payload
            )[2]
    return result
