from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path, PurePosixPath
from typing import Any, NoReturn

import pytest
from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from android_bridge.anki_limits import ANKI_ENVELOPE_LIMITS_V1, ANKI_LIMITS_V1
from android_bridge.protocol import (
    BridgeProtocolError,
    decode_envelope,
    normalize_integral_json_number,
)
from android_bridge.unicode_contract import (
    has_leading_or_trailing_python_whitespace,
    is_category_c,
    is_nfc,
    scalar_count,
    strict_utf8_length,
)

from anki_protocol_corpus import CorpusCase, CorpusFormatError, load_anki_protocol_corpus

PROJECT_ROOT = Path(__file__).resolve().parents[3]
CORPUS_PATH = PROJECT_ROOT / "golden/bridge/anki-protocol-v1.jsonl"
SCHEMA_PATH = PROJECT_ROOT / "app/src/main/python/android_bridge/schemas/anki.schema.json"

_REQUEST_TYPES = {
    "verifyTarget": "anki.verifytarget.request",
    "scanFirstFields": "anki.scanfirstfields.request",
    "storeMedia": "anki.storemedia.request",
    "createNotes": "anki.createnotes.request",
    "releaseRunState": "anki.releaserunstate.request",
}
_RESULT_TYPES = {
    callback: message_type.replace(".request", ".result") for callback, message_type in _REQUEST_TYPES.items()
}
_SCHEMA_DEFS = {
    "anki.verifytarget.request": "verifyTargetRequest",
    "anki.verifytarget.result": "verifyTargetResult",
    "anki.storemedia.request": "storeMediaRequest",
    "anki.storemedia.result": "storeMediaResult",
    "anki.createnotes.request": "createNotesRequest",
    "anki.createnotes.result": "createNotesResult",
    "anki.releaserunstate.request": "releaseRunStateRequest",
    "anki.releaserunstate.result": "releaseRunStateResult",
    "anki.error": "error",
}
_ERROR_CODES = frozenset(
    {
        "api_disabled",
        "permission_required",
        "note_type_not_found",
        "field_missing",
        "field_mapping_invalid",
        "target_invalid",
        "provider_unavailable",
        "query_failed",
        "write_failed",
        "timeout",
        "cancelled",
        "media_store_failed",
        "post_commit_uncertain",
        "invalid_request",
        "unsupported_operation",
        "internal_error",
    }
)

_CORPUS = load_anki_protocol_corpus(CORPUS_PATH)
_ANKI_SCHEMA = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


class _ContractRejection(ValueError):
    def __init__(self, category: str) -> None:
        super().__init__(category)
        self.category = category


def _reject(category: str) -> NoReturn:
    raise _ContractRejection(category)


def _bridge_error_category(error: BridgeProtocolError) -> str:
    return {
        "invalid_message_type": "invalid_envelope",
        "integer_out_of_range": "invalid_value",
        "invalid_json_number": "invalid_value",
        "non_finite_number": "invalid_value",
    }.get(error.code, error.code)


def _strict_raw_utf8_size(raw: str) -> int:
    try:
        return len(raw.encode("utf-8"))
    except UnicodeEncodeError:
        _reject("invalid_utf8")


def _schema_def_for(message_type: str, payload: dict[str, Any]) -> str:
    if message_type == "anki.scanfirstfields.request":
        return "scanFirstFieldsRequest"
    if message_type == "anki.scanfirstfields.result":
        if "firstFields" in payload:
            return "scanFirstFieldsResult"
        if "rawFirstFieldHits" in payload:
            return "duplicateLookupResult"
        return "scanFirstFieldsResult"
    try:
        return _SCHEMA_DEFS[message_type]
    except KeyError:
        _reject("unexpected_message_type")


def _schema_category(error: ValidationError) -> str:
    pending = [error]
    while pending:
        current = pending.pop()
        pending.extend(current.context)
        if current.validator == "const" and "limits" in current.absolute_path:
            return "limit_mismatch"
        if current.validator == "const" and "deckCreated" in current.absolute_path:
            return "invalid_value"
        if current.validator in {"pattern", "minimum", "maximum", "uniqueItems"}:
            return "invalid_value"
        if current.validator == "type" and current.validator_value == "integer" and type(current.instance) is float:
            return "invalid_value"
    return "invalid_payload"


def _validate_schema(message_type: str, payload: dict[str, Any]) -> None:
    definition = _schema_def_for(message_type, payload)
    if message_type == "anki.scanfirstfields.request" and isinstance(payload.get("scope"), dict):
        scope_definition = {
            "knownVocabulary": "knownVocabularyScope",
            "duplicates": "duplicateScope",
        }.get(payload["scope"].get("kind"))
        if scope_definition is not None:
            root: dict[str, Any] = {
                "type": "object",
                "additionalProperties": False,
                "required": ["runId", "requestId", "scope"],
                "properties": {
                    "runId": {"$ref": "#/$defs/runId"},
                    "requestId": {"$ref": "#/$defs/requestId"},
                    "scope": {"$ref": f"#/$defs/{scope_definition}"},
                },
            }
        else:
            root = {"$ref": f"#/$defs/{definition}"}
    else:
        root = {"$ref": f"#/$defs/{definition}"}
    validator = Draft202012Validator(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            **root,
            "$defs": _ANKI_SCHEMA["$defs"],
        }
    )
    try:
        validator.validate(payload)
    except ValidationError as error:
        _reject(_schema_category(error))


def _plain_string(
    value: str,
    *,
    allow_empty: bool = True,
    max_scalars: int | None = None,
    max_utf8_bytes: int | None = None,
) -> tuple[int, int]:
    scalars = scalar_count(value)
    utf8_bytes = strict_utf8_length(value)
    if scalars is None or utf8_bytes is None:
        _reject("invalid_utf8")
    if not allow_empty and scalars == 0:
        _reject("invalid_value")
    if max_scalars is not None and scalars > max_scalars:
        _reject("invalid_value")
    if max_utf8_bytes is not None and utf8_bytes > max_utf8_bytes:
        _reject("invalid_value")
    return scalars, utf8_bytes


def _canonical_name(value: str, kind: str) -> int:
    limits = ANKI_LIMITS_V1["names"][kind]
    _, utf8_bytes = _plain_string(
        value,
        allow_empty=False,
        max_scalars=limits["maxCodePoints"],
        max_utf8_bytes=limits["maxUtf8Bytes"],
    )
    if (
        not is_nfc(value)
        or has_leading_or_trailing_python_whitespace(value)
        or any(is_category_c(ord(character)) for character in value)
    ):
        _reject("invalid_value")
    return utf8_bytes


def _media_basename(value: str, *, preferred: bool = False, actual: bool = False) -> int:
    limits = ANKI_LIMITS_V1["storeMedia"]
    _, utf8_bytes = _plain_string(
        value,
        allow_empty=False,
        max_scalars=limits["filenameMaxCodePoints"],
        max_utf8_bytes=limits["filenameMaxUtf8Bytes"],
    )
    if value in {".", ".."} or "/" in value or "\\" in value:
        _reject("invalid_value")
    if any(is_category_c(ord(character)) for character in value):
        _reject("invalid_value")
    if preferred:
        if (
            not is_nfc(value)
            or has_leading_or_trailing_python_whitespace(value)
            or any(character in '/\\<>[]:"' for character in value)
        ):
            _reject("invalid_value")
    if actual and (value.startswith("[sound:") or value.startswith("<img")):
        _reject("invalid_value")
    return utf8_bytes


def _card_provider_preferred_name(requested_filename: str) -> str:
    path = PurePosixPath(requested_filename)
    preferred = (path.stem if path.suffix else requested_filename).replace(" ", "_")
    return preferred if len(preferred) >= 2 else f"{preferred}_"


def _dictionary_provider_preferred_name(requested_filename: str) -> str:
    digest = hashlib.sha256(requested_filename.encode("utf-8")).hexdigest()
    return f"anki_miner_dict_{digest}"


def _strict_filename_stem(value: str) -> str | None:
    try:
        _media_basename(value, preferred=True)
    except _ContractRejection:
        return None
    path = PurePosixPath(value)
    return path.stem if path.suffix else None


def _positive_long(value: object) -> int:
    normalized = normalize_integral_json_number(value)
    if normalized is None or normalized < 1:
        _reject("invalid_value")
    return normalized


def _validate_error_detail(error: dict[str, Any]) -> None:
    _plain_string(error["message"], allow_empty=False)
    if error["code"] == "post_commit_uncertain" and error["retryable"]:
        _reject("invalid_value")
    if error["code"] == "cancelled" and error["retryable"]:
        _reject("invalid_value")


def _validate_scan_payload(payload: dict[str, Any], *, response: bool) -> None:
    limits = ANKI_LIMITS_V1["scanFirstFields"]
    if response:
        if "firstFields" in payload:
            total = sum(
                _plain_string(
                    value,
                    max_scalars=limits["firstFieldMaxCodePoints"],
                    max_utf8_bytes=limits["firstFieldMaxUtf8Bytes"],
                )[1]
                for value in payload["firstFields"]
            )
            if total > limits["knownPageMaxUtf8Bytes"]:
                _reject("invalid_value")
            cursor = payload["nextCursor"]
            if cursor is not None:
                _positive_long(cursor["ordinal"])
                _plain_string(
                    cursor["token"],
                    allow_empty=False,
                    max_scalars=limits["knownCursorMaxCodePoints"],
                    max_utf8_bytes=limits["knownCursorMaxUtf8Bytes"],
                )
            return

        seen_hits = 0
        seen_bytes = 0
        for bucket in payload["rawFirstFieldHits"]:
            note_ids: set[int] = set()
            for hit in bucket:
                note_id = _positive_long(hit["noteId"])
                if note_id in note_ids:
                    _reject("invalid_value")
                note_ids.add(note_id)
                seen_hits += 1
                seen_bytes += _plain_string(
                    hit["firstField"],
                    max_scalars=limits["firstFieldMaxCodePoints"],
                    max_utf8_bytes=limits["firstFieldMaxUtf8Bytes"],
                )[1]
        if seen_hits > limits["duplicateHitsTotalMaxItems"] or seen_bytes > limits["duplicateHitsTotalMaxUtf8Bytes"]:
            _reject("invalid_value")
        return

    scope = payload["scope"]
    if scope["kind"] == "knownVocabulary":
        total = sum(_canonical_name(deck, "deck") for deck in scope["excludedDecks"])
        if total > ANKI_LIMITS_V1["names"]["excludedDecks"]["maxTotalUtf8Bytes"]:
            _reject("invalid_value")
        cursor = scope["cursor"]
        if cursor is not None:
            _positive_long(cursor["ordinal"])
            _plain_string(
                cursor["token"],
                allow_empty=False,
                max_scalars=limits["knownCursorMaxCodePoints"],
                max_utf8_bytes=limits["knownCursorMaxUtf8Bytes"],
            )
        return

    _canonical_name(scope["modelName"], "model")
    _canonical_name(scope["firstFieldName"], "field")
    if scope["deckName"] is not None:
        _canonical_name(scope["deckName"], "deck")
    if len({json.dumps(item, sort_keys=True) for item in scope["candidates"]}) != len(scope["candidates"]):
        _reject("invalid_value")
    for candidate in scope["candidates"]:
        _plain_string(
            candidate["key"],
            allow_empty=False,
            max_scalars=limits["duplicateKeyMaxCodePoints"],
        )
        _plain_string(
            candidate["firstField"],
            allow_empty=False,
            max_scalars=limits["duplicateFirstFieldMaxCodePoints"],
        )
    if any(
        normalize_integral_json_number(index) not in range(len(scope["candidates"])) for index in scope["occurrences"]
    ):
        _reject("invalid_value")


def _validate_store_payload(payload: dict[str, Any], *, response: bool) -> None:
    if not response:
        asset_ids: set[str] = set()
        requested_name_owners: dict[str, str] = {}
        namespace_prefixes: list[tuple[str, str]] = []
        concrete_claims: list[tuple[str, str]] = []
        total_bytes = 0
        for asset in payload["assets"]:
            if asset["assetId"] in asset_ids:
                _reject("invalid_value")
            asset_ids.add(asset["assetId"])
            _plain_string(
                asset["sourcePath"],
                allow_empty=False,
                max_scalars=ANKI_LIMITS_V1["storeMedia"]["sourcePathMaxCodePoints"],
                max_utf8_bytes=ANKI_LIMITS_V1["storeMedia"]["sourcePathMaxUtf8Bytes"],
            )
            if not asset["sourcePath"].startswith("/") or "\0" in asset["sourcePath"]:
                _reject("invalid_value")
            _media_basename(asset["preferredName"], preferred=True)
            if asset["purpose"] == "card":
                _media_basename(asset["requestedFilename"], preferred=True)
                expected_preferred = _card_provider_preferred_name(asset["requestedFilename"])
            else:
                _media_basename(asset["requestedFilename"])
                expected_preferred = _dictionary_provider_preferred_name(asset["requestedFilename"])
            if asset["preferredName"] != expected_preferred:
                _reject("invalid_value")
            prior_owner = requested_name_owners.setdefault(asset["requestedFilename"], asset["assetId"])
            if prior_owner != asset["assetId"]:
                _reject("invalid_value")
            namespace_prefixes.append((f'{asset["preferredName"]}_', asset["assetId"]))
            requested_stem = _strict_filename_stem(asset["requestedFilename"])
            if requested_stem is not None:
                concrete_claims.append((requested_stem, asset["assetId"]))
            size = normalize_integral_json_number(asset["expectedSizeBytes"])
            if size is None:
                _reject("invalid_value")
            total_bytes += size
        if total_bytes > ANKI_LIMITS_V1["storeMedia"]["maxTotalBytes"]:
            _reject("invalid_value")
        for index, (prefix, owner) in enumerate(namespace_prefixes):
            for other_prefix, other_owner in namespace_prefixes[index + 1 :]:
                if owner != other_owner and (prefix.startswith(other_prefix) or other_prefix.startswith(prefix)):
                    _reject("invalid_value")
            if any(concrete_owner != owner and stem.startswith(prefix) for stem, concrete_owner in concrete_claims):
                _reject("invalid_value")
        return

    asset_ids: set[str] = set()
    actual_names: set[str] = set()
    terminal_seen = False
    known_write_seen = False
    uncertain_seen = False
    for row in payload["results"]:
        if row["assetId"] in asset_ids:
            _reject("invalid_value")
        asset_ids.add(row["assetId"])
        status = row["status"]
        if terminal_seen and status != "notAttempted":
            _reject("invalid_value")
        if status == "stored":
            known_write_seen = True
            filename = row["actualFilename"]
            _media_basename(filename, actual=True)
            if filename in actual_names:
                _reject("invalid_value")
            actual_names.add(filename)
        elif status == "failed":
            _validate_error_detail(row["error"])
            if row["error"]["code"] != "media_store_failed":
                _reject("invalid_value")
        elif status == "uncertain":
            terminal_seen = True
            uncertain_seen = True
        elif status == "notAttempted":
            terminal_seen = True

    error = payload["error"]
    if terminal_seen != (error is not None):
        _reject("invalid_value")
    if error is not None:
        _validate_error_detail(error)
        if known_write_seen and error["retryable"]:
            _reject("invalid_value")
        if uncertain_seen and (error["code"] != "post_commit_uncertain" or error["retryable"]):
            _reject("invalid_value")
        if error["code"] == "post_commit_uncertain" and not uncertain_seen:
            _reject("invalid_value")


def _validate_create_payload(payload: dict[str, Any], *, response: bool) -> None:
    if not response:
        _canonical_name(payload["deckName"], "deck")
        _canonical_name(payload["modelName"], "model")
        _canonical_name(payload["firstFieldName"], "field")
        if payload["duplicateScope"]["kind"] == "exactDeck":
            _canonical_name(payload["duplicateScope"]["deckName"], "deck")
            if payload["duplicateScope"]["deckName"] != payload["deckName"]:
                _reject("invalid_value")

        note_ids: set[str] = set()
        total_content = 0
        total_bindings = 0
        previous_occurrence = -1
        limits = ANKI_LIMITS_V1["createNotes"]
        for note in payload["notes"]:
            if note["clientNoteId"] in note_ids:
                _reject("invalid_value")
            note_ids.add(note["clientNoteId"])
            note_content = 0
            for name, value in note["fields"].items():
                note_content += _canonical_name(name, "field")
                note_content += _plain_string(
                    value,
                    max_scalars=limits["fieldValueMaxCodePoints"],
                    max_utf8_bytes=limits["fieldValueMaxUtf8Bytes"],
                )[1]
            tag_bytes = sum(
                _plain_string(
                    tag,
                    allow_empty=False,
                    max_scalars=limits["tagMaxCodePoints"],
                    max_utf8_bytes=limits["tagMaxUtf8Bytes"],
                )[1]
                for tag in note["tags"]
            )
            if tag_bytes > limits["tagsPerNoteMaxUtf8Bytes"]:
                _reject("invalid_value")
            note_content += tag_bytes

            binding_ids: set[str] = set()
            for binding in note["mediaBindings"]:
                if binding["assetId"] in binding_ids:
                    _reject("invalid_value")
                binding_ids.add(binding["assetId"])
                note_content += len(binding["assetId"].encode("utf-8"))
                note_content += _media_basename(binding["actualFilename"], actual=True)
            total_bindings += len(note["mediaBindings"])
            if note_content > limits["noteContentMaxUtf8Bytes"]:
                _reject("invalid_value")
            total_content += note_content

            candidate = note["duplicateCandidate"]
            occurrence = normalize_integral_json_number(candidate["occurrence"])
            if occurrence is None or occurrence <= previous_occurrence:
                _reject("invalid_value")
            previous_occurrence = occurrence
        if total_bindings > limits["maxMediaBindingsTotal"] or total_content > limits["callbackContentMaxUtf8Bytes"]:
            _reject("invalid_value")
        return

    client_ids: set[str] = set()
    note_ids: set[int] = set()
    terminal_seen = False
    terminal_carrier_seen = False
    not_attempted_seen = False
    known_write_seen = False
    uncertain_seen = False
    committed_failure_seen = False
    for row in payload["results"]:
        if row["clientNoteId"] in client_ids:
            _reject("invalid_value")
        client_ids.add(row["clientNoteId"])
        status = row["status"]
        if terminal_seen and status != "notAttempted":
            _reject("invalid_value")
        if status in {"created", "committedFailed"}:
            note_id = _positive_long(row["noteId"])
            if note_id in note_ids:
                _reject("invalid_value")
            note_ids.add(note_id)
            known_write_seen = True
        if status in {"failed", "committedFailed", "uncertain", "notAttempted"}:
            terminal_seen = True
        if status in {"failed", "committedFailed", "uncertain"}:
            terminal_carrier_seen = True
        if status == "notAttempted":
            not_attempted_seen = True
        if status == "committedFailed":
            committed_failure_seen = True
        if status == "uncertain":
            uncertain_seen = True

    error = payload["error"]
    if not_attempted_seen and not terminal_carrier_seen:
        _reject("invalid_value")
    if terminal_carrier_seen != (error is not None):
        _reject("invalid_value")
    if error is not None:
        _validate_error_detail(error)
        if known_write_seen and error["retryable"]:
            _reject("invalid_value")
        if uncertain_seen and (error["code"] != "post_commit_uncertain" or error["retryable"]):
            _reject("invalid_value")
        if error["code"] == "post_commit_uncertain" and not (uncertain_seen or committed_failure_seen):
            _reject("invalid_value")
        if committed_failure_seen and error["code"] == "cancelled":
            _reject("invalid_value")


def _validate_semantics(message_type: str, payload: dict[str, Any], callback: str) -> None:
    if message_type == "anki.error":
        if payload["operation"] != callback:
            _reject("unexpected_message_type")
        _validate_error_detail(payload)
        return
    if message_type == "anki.verifytarget.request":
        total = _canonical_name(payload["deckName"], "deck")
        total += _canonical_name(payload["modelName"], "model")
        del total
        field_bytes = sum(_canonical_name(name, "field") for name in payload["requiredFields"])
        if field_bytes > ANKI_LIMITS_V1["names"]["targetFields"]["maxTotalUtf8Bytes"]:
            _reject("invalid_value")
    elif message_type == "anki.verifytarget.result":
        _positive_long(payload["deckId"])
        _positive_long(payload["modelId"])
        field_bytes = sum(_canonical_name(name, "field") for name in payload["fieldNames"])
        if field_bytes > ANKI_LIMITS_V1["names"]["targetFields"]["maxTotalUtf8Bytes"]:
            _reject("invalid_value")
    elif message_type == "anki.scanfirstfields.request":
        _validate_scan_payload(payload, response=False)
    elif message_type == "anki.scanfirstfields.result":
        _validate_scan_payload(payload, response=True)
    elif message_type == "anki.storemedia.request":
        _validate_store_payload(payload, response=False)
    elif message_type == "anki.storemedia.result":
        _validate_store_payload(payload, response=True)
    elif message_type == "anki.createnotes.request":
        _validate_create_payload(payload, response=False)
    elif message_type == "anki.createnotes.result":
        _validate_create_payload(payload, response=True)


def _decode_and_validate(case: CorpusCase) -> tuple[str, dict[str, Any]]:
    raw_size = _strict_raw_utf8_size(case.raw)
    request_limit, result_limit = ANKI_ENVELOPE_LIMITS_V1[case.callback]
    if case.direction == "request" and raw_size > request_limit:
        _reject("input_too_large")
    if case.direction == "response" and raw_size > result_limit:
        _reject("output_too_large")

    expected_type = _REQUEST_TYPES[case.callback] if case.direction == "request" else None
    try:
        decoded = decode_envelope(case.raw, expected_type=expected_type)
    except BridgeProtocolError as error:
        _reject(_bridge_error_category(error))

    if case.direction == "response" and decoded.message_type not in {
        _RESULT_TYPES[case.callback],
        "anki.error",
    }:
        _reject("unexpected_message_type")
    _validate_schema(decoded.message_type, decoded.payload)
    _validate_semantics(decoded.message_type, decoded.payload, case.callback)
    return decoded.message_type, decoded.payload


@pytest.mark.parametrize("case", _CORPUS, ids=lambda case: case.case_id)
def test_shared_anki_protocol_vector(case: CorpusCase) -> None:
    if case.expectation.outcome == "reject":
        with pytest.raises(_ContractRejection) as caught:
            _decode_and_validate(case)
        assert caught.value.category == case.expectation.category
        return

    message_type, payload = _decode_and_validate(case)
    assert message_type == case.expectation.message_type
    assert payload == case.expectation.payload
    if case.direction == "response":
        assert case.expectation.canonical is not None
        canonical = case.expectation.canonical
        canonical_message = decode_envelope(canonical)
        assert canonical_message.message_type == message_type
        assert canonical_message.payload == case.expectation.payload
        assert json.dumps(json.loads(canonical), ensure_ascii=False, separators=(",", ":")) == canonical
    else:
        assert case.expectation.canonical is None


def test_corpus_freezes_all_operation_and_result_variants() -> None:
    accepted = [case for case in _CORPUS if case.expectation.outcome == "accept"]
    request_callbacks = {case.callback for case in accepted if case.direction == "request"}
    assert request_callbacks == set(_REQUEST_TYPES)

    accepted_payloads = [case.expectation.payload for case in accepted if case.expectation.payload is not None]
    assert {payload["scope"]["kind"] for payload in accepted_payloads if "scope" in payload} == {
        "knownVocabulary",
        "duplicates",
    }
    assert {payload["duplicateScope"]["kind"] for payload in accepted_payloads if "duplicateScope" in payload} == {
        "collection",
        "exactDeck",
    }
    assert {row["status"] for payload in accepted_payloads for row in payload.get("results", [])} == {
        "stored",
        "failed",
        "uncertain",
        "notAttempted",
        "created",
        "duplicate",
        "committedFailed",
    }
    assert {payload["state"] for payload in accepted_payloads if "state" in payload} == {
        "released",
        "deferred",
        "absent",
    }
    assert {payload["code"] for payload in accepted_payloads if "operation" in payload} == _ERROR_CODES


def test_corpus_constructions_and_rejection_categories_stay_covered() -> None:
    by_id = {case.case_id: case for case in _CORPUS}
    required_constructions = {
        "reject_leading_raw_bom",
        "reject_raw_unpaired_high_surrogate",
        "reject_raw_unpaired_low_surrogate",
        "request_numeric_token_positive_exact_1000",
        "reject_numeric_token_positive_1001",
        "reject_numeric_token_negative_exact_1000_semantic",
        "reject_numeric_token_negative_1001",
        "reject_input_envelope_too_large",
        "reject_output_envelope_too_large",
        "reject_decoded_depth_limit",
        "reject_verify_deck_created_true",
    }
    assert required_constructions <= by_id.keys()
    assert {case.expectation.category for case in _CORPUS if case.expectation.outcome == "reject"} == {
        "input_too_large",
        "output_too_large",
        "invalid_utf8",
        "invalid_json",
        "duplicate_json_key",
        "json_number_too_long",
        "invalid_envelope",
        "unsupported_schema_version",
        "unexpected_message_type",
        "invalid_payload",
        "invalid_value",
        "limit_mismatch",
    }


@pytest.mark.parametrize(
    ("line", "message"),
    [
        ('{"id":"x","id":"x"}\n', "duplicate corpus object key"),
        ('{"id":"x"}\n', "must contain exactly"),
        ('{"id":"x"}', "must end with a newline"),
    ],
)
def test_loader_rejects_malformed_corpus(tmp_path: Path, line: str, message: str) -> None:
    path = tmp_path / "corpus.jsonl"
    path.write_text(line, encoding="utf-8")

    with pytest.raises(CorpusFormatError, match=message):
        load_anki_protocol_corpus(path)


def test_numeric_construction_lengths_are_exact() -> None:
    by_id = {case.case_id: case.raw for case in _CORPUS}

    def number_after(raw: str, marker: str) -> str:
        suffix = raw.split(marker, 1)[1]
        return re.match(r"-?[0-9]+(?:\.[0-9]+)?", suffix).group(0)  # type: ignore[union-attr]

    assert len(number_after(by_id["request_numeric_token_positive_exact_1000"], ":")) == 1000
    assert len(number_after(by_id["reject_numeric_token_positive_1001"], ":")) == 1001
    assert (
        len(
            number_after(
                by_id["reject_numeric_token_negative_exact_1000_semantic"],
                '"deckId":',
            )
        )
        == 1000
    )
    assert len(number_after(by_id["reject_numeric_token_negative_1001"], '"deckId":')) == 1001
