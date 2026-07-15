"""Checked-in v1 resource limits shared by the Anki bridge boundary."""

from __future__ import annotations

import json
from importlib.resources import files
from typing import Any, Final


def _load_v1_limits() -> dict[str, Any]:
    resource = files("android_bridge").joinpath("anki_limits_v1.json")
    decoded = json.loads(resource.read_text(encoding="utf-8"))
    required = {
        "schemaVersion",
        "units",
        "wire",
        "names",
        "targetModel",
        "verifyTarget",
        "scanFirstFields",
        "storeMedia",
        "createNotes",
        "releaseRunState",
        "createCall",
    }
    if not isinstance(decoded, dict) or set(decoded) != required:
        raise RuntimeError("Anki limits v1 manifest has an invalid top-level shape")
    if decoded["schemaVersion"] != 1:
        raise RuntimeError("Anki limits manifest version is not supported")
    return decoded


ANKI_LIMITS_V1: Final = _load_v1_limits()

ANKI_ENVELOPE_LIMITS_V1: Final = {
    operation: (
        ANKI_LIMITS_V1[operation]["requestEnvelopeMaxUtf8Bytes"],
        ANKI_LIMITS_V1[operation]["resultEnvelopeMaxUtf8Bytes"],
    )
    for operation in (
        "verifyTarget",
        "scanFirstFields",
        "storeMedia",
        "createNotes",
        "releaseRunState",
    )
}
