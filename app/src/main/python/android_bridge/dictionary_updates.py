"""Dictionary updates: ask each publisher for its latest revision, then rebuild one slot in place.

Desktop parity over the vendored ``anki_miner.services.resource_updates``. A
Yomitan index can name its own latest version (``isUpdatable``, ``indexUrl``,
``downloadUrl``), and every slot keeps the zip it was built from.

``resource.updates.check`` takes the active language's chain ids from Kotlin
(chain order, disabled entries included) and asks each publisher once. The
vendored default fetch follows a redirect to any scheme, and the index it
fetches picks the host of an archive nothing pins, so this module fetches with
every hop held to https. Errors are logged here; only their count crosses.

``resource.update.install`` rebuilds one slot from the archive Kotlin
downloaded, through the family's own import: same validation as a custom
import, overwrite in place under the slot's id, so chain order and on/off stay
as the user left them. Kotlin is the trusted caller, as for every import.

Engine imports stay function-local (``bootstrap`` sets ``ANKI_MINER_HOME`` first).
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any
from urllib.parse import urljoin

from . import local_resources
from . import resources as core
from .bootstrap import require_initialized
from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)

#: Vendored ``UpdatableResource.kind`` → the bridge's resource kind.
_WIRE_KINDS = {"dict": "dictionary", "freq": "frequency", "pitch": "pitch"}
#: Kind → (storage root, largest archive its import accepts).
_FAMILIES: dict[str, tuple[Callable[[Path], Path], int]] = {
    "dictionary": (core._dictionary_root, core._MAX_CUSTOM_DICTIONARY_ARCHIVE_BYTES),
    "frequency": (local_resources._frequency_root, local_resources._FREQUENCY_ARCHIVE_LIMIT),
    "pitch": (local_resources._pitch_root, local_resources._PITCH_ARCHIVE_LIMIT),
}
_CHAIN_FIELDS = ("dictionaryIds", "frequencyIds", "pitchIds")
_MAX_CHAIN_IDS = 128
_MAX_WIRE_TEXT_BYTES = 4096
_MAX_REDIRECTS = 10


def _session() -> Any:
    import requests

    return requests.Session()


def fetch_https_index(url: str) -> dict[str, Any]:
    """The vendored ``fetch_remote_index``, with every redirect hop refused unless it is https.

    Redirects are followed here, one hop at a time (a GitHub
    ``releases/latest/download`` link always answers with one). Same headers,
    timeouts and 1 MiB body cap as the vendored fetch; raises on any failure,
    which the vendored check counts against that publisher alone.
    """

    from anki_miner.services import resource_updates as vendored

    target = url
    with _session() as session:
        for _hop in range(_MAX_REDIRECTS + 1):
            if not vendored._is_https(target):
                raise ValueError("refused an index URL that is not https")
            response = session.get(
                target,
                headers={"User-Agent": vendored._USER_AGENT, "Accept": "application/json"},
                timeout=vendored._INDEX_TIMEOUT,
                stream=True,
                allow_redirects=False,
            )
            try:
                if response.is_redirect:
                    target = urljoin(target, response.headers["location"])
                    continue
                response.raise_for_status()
                body = bytearray()
                for chunk in response.iter_content(chunk_size=65536):
                    body.extend(chunk)
                    if len(body) > vendored._MAX_REMOTE_INDEX_BYTES:
                        raise ValueError(f"index.json exceeds the {vendored._MAX_REMOTE_INDEX_BYTES:,}-byte cap")
            finally:
                response.close()
            index = json.loads(body.decode("utf-8"))
            if not isinstance(index, dict):
                raise ValueError("index.json is not a JSON object")
            return index
    raise ValueError("index.json redirected too many times")


def _chain_ids(value: object, *, name: str) -> list[str]:
    if not isinstance(value, list) or len(value) > _MAX_CHAIN_IDS:
        raise core._fail("invalid_resource_request", f"{name} must list at most {_MAX_CHAIN_IDS} slot ids")
    return [core._slot_id(item) for item in value]


def _display_title(*candidates: str) -> str:
    """The first candidate the install's ``displayName`` contract accepts; the last is the slot id."""

    for candidate in candidates:
        try:
            return local_resources._display_name(candidate, label="title")
        except BridgeProtocolError:
            continue
    return candidates[-1]


def _wire_update(update: Any) -> dict[str, object] | None:
    """One update as it crosses the bridge, or None when the publisher's answer cannot."""

    resource = update.resource
    kind = _WIRE_KINDS[resource.kind]
    current = core._inventory_text(resource.revision, maximum_bytes=_MAX_WIRE_TEXT_BYTES, allow_empty=True)
    latest = core._inventory_text(update.latest_revision, maximum_bytes=_MAX_WIRE_TEXT_BYTES)
    download_url = core._inventory_text(update.download_url, maximum_bytes=_MAX_WIRE_TEXT_BYTES)
    if current is None or latest is None or download_url is None:
        return None
    return {
        "kind": kind,
        "slotId": resource.slot_id,
        "currentRevision": current,
        "latestRevision": latest,
        "title": _display_title(update.latest_title, resource.title, resource.slot_id),
        "downloadUrl": download_url,
        "maxArchiveBytes": _FAMILIES[kind][1],
    }


def check_updates(payload: Mapping[str, object]) -> str:
    """``resource.updates.check``: which installed slots of the language have a newer published revision."""

    from dataclasses import replace

    from .languages import base_config, validated_language

    core._exact(payload, {"operationId", "language", *_CHAIN_FIELDS}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    language = validated_language(payload["language"])
    dictionary_ids, frequency_ids, pitch_ids = (_chain_ids(payload[name], name=name) for name in _CHAIN_FIELDS)
    home = Path(require_initialized())

    from anki_miner.config import ChainEntry, FreqEntry, PitchSourceEntry
    from anki_miner.services.resource_updates import check_for_updates, updatable_resources

    config = replace(
        base_config(language),
        dicts_root=core._dictionary_root(home),
        freqs_root=local_resources._frequency_root(home),
        pitch_root=local_resources._pitch_root(home),
        dictionary_chain=tuple(ChainEntry(kind="indexed", dict_id=slot_id) for slot_id in dictionary_ids),
        frequency_chain=tuple(FreqEntry(source_id=slot_id) for slot_id in frequency_ids),
        pitch_chain=tuple(PitchSourceEntry(source_id=slot_id) for slot_id in pitch_ids),
    )
    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        # The vendored check polls cancelled between publishers.
        result = check_for_updates(
            updatable_resources(config),
            fetch=fetch_https_index,
            cancelled=operation.cancelled.is_set,
        )
        operation.check()

    updates: list[dict[str, object]] = []
    failed = len(result.failures)
    for update in result.updates:
        entry = _wire_update(update)
        if entry is None:
            logger.warning(
                "resource_update_dropped outcome=fail slot=%s reason=answer_exceeds_bridge_contract",
                update.resource.slot_id,
            )
            failed += 1
            continue
        updates.append(entry)
    logger.info(
        "resource_update_check outcome=ok checked=%d updates=%d failed=%d",
        result.checked,
        len(updates),
        failed,
    )
    return encode_message(
        "resource.updates.checked",
        {
            "checked": result.checked,
            # The vendored UpdateCheck.reached, over the failures that include
            # answers this bridge could not carry.
            "reached": result.checked == 0 or failed < result.checked,
            "failedCount": failed,
            "updates": updates,
        },
    )


def install_update(payload: Mapping[str, object], *, callbacks: object | None = None) -> str:
    """``resource.update.install``: rebuild one slot in place from the publisher's archive.

    Answers with the family import's own envelope (``resource.dictionary.imported``,
    ``resource.frequency.imported`` or ``resource.pitch.imported``). A slot that is
    gone, not the app's, or stamped for another language than the check's is
    refused as ``resource_update_stale``: the install would land in the wrong
    chain, or nowhere.
    """

    from .languages import validated_language

    core._exact(
        payload,
        {"operationId", "kind", "slotId", "sourcePath", "displayName", "language"},
        code="invalid_resource_request",
    )
    operation_id = core._operation_id(payload["operationId"])
    kind = payload["kind"]
    if not isinstance(kind, str) or kind not in _FAMILIES:
        raise core._fail("invalid_resource_request", "kind is invalid")
    slot_id = core._slot_id(payload["slotId"])
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")
    display_name = local_resources._display_name(payload["displayName"], label="displayName")
    language = validated_language(payload["language"])
    home = Path(require_initialized())
    root = _FAMILIES[kind][0](home)

    from anki_miner.services._sqlite_index import prove_owned_slot, read_slot_language

    if not prove_owned_slot(root, slot_id, kind) or read_slot_language(root / slot_id) != language:
        logger.warning("resource_update_install outcome=fail slot=%s reason=stale", slot_id)
        raise core._fail("resource_update_stale", "The slot changed after the update check")
    request: dict[str, object] = {
        "operationId": operation_id,
        "sourcePath": str(source),
        "overwrite": True,
        "language": language,
    }
    if kind == "dictionary":
        previous = core._read_dictionary_sidecar(root / slot_id, slot_id=slot_id)
        return core.import_dictionary(
            {
                **request,
                "slotId": slot_id,
                "catalogResourceId": previous.catalog_resource_id if previous else None,
            },
            callbacks=callbacks,
            publisher_update=True,
        )
    request.update(sourceId=slot_id, sourceName=display_name, sourceFormat="zip")
    if kind == "frequency":
        return local_resources.import_frequency(request, callbacks=callbacks, publisher_update=True)
    return local_resources.import_pitch(request, callbacks=callbacks)
