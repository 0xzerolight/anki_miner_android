"""anki.notetype.fill: desktop's "Fill in automatically" proposal for one note type.

Desktop's Settings button and setup wizard share one Qt-free helper,
``services/note_presets.fill_note_type_fields``: it recognises Lapis, Kiku,
Senren and Anki Miner Note by their field names and otherwise runs the keyword
table. The bridge calls it unchanged and hands Kotlin the proposal; Kotlin
writes it under Android's own ownership rules (``AnkiFieldMapPolicy``).

Detection is by field names only, as on desktop: a note type named "Lapis"
whose fields differ is a fork the presets have not seen, so its name proves
nothing (``preset_for_note_type_name`` has no caller there either).

Japanese reads its profile like every other language: ``anki_miner.services``
imports the subtitle parser (pysubs2) anyway, so this op has no registry-free
path to keep for the host test lane.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from .anki_limits import ANKI_LIMITS_V1
from .languages import get_profile, validated_language
from .protocol import BridgeProtocolError, encode_message

_INVALID_REQUEST = "invalid_note_type_fill_request"
_NAME_LIMITS = ANKI_LIMITS_V1["names"]
_MAX_FIELDS = _NAME_LIMITS["targetFields"]["maxItems"]
_MAX_FIELDS_UTF8_BYTES = _NAME_LIMITS["targetFields"]["maxTotalUtf8Bytes"]
_MAX_FIELD_NAME_UTF8_BYTES = _NAME_LIMITS["field"]["maxUtf8Bytes"]


def _invalid(message: str) -> BridgeProtocolError:
    return BridgeProtocolError(_INVALID_REQUEST, message)


def _field_names(value: object) -> list[str]:
    """The note type's fields, as AnkiDroid lists them: 1-64 distinct names within the Anki limits."""

    if not isinstance(value, list) or not value or len(value) > _MAX_FIELDS:
        raise _invalid(f"fieldNames must list 1 to {_MAX_FIELDS} field names")
    total = 0
    for name in value:
        if not isinstance(name, str) or not name:
            raise _invalid("fieldNames must be non-empty strings")
        size = len(name.encode("utf-8"))
        if size > _MAX_FIELD_NAME_UTF8_BYTES:
            raise _invalid(f"A field name exceeds {_MAX_FIELD_NAME_UTF8_BYTES} UTF-8 bytes")
        total += size
    if total > _MAX_FIELDS_UTF8_BYTES:
        raise _invalid(f"fieldNames exceed {_MAX_FIELDS_UTF8_BYTES} UTF-8 bytes")
    if len(set(value)) != len(value):
        raise _invalid("fieldNames must be distinct")
    return value


def _preset_wire(preset: Any) -> dict[str, object] | None:
    if preset is None:
        return None
    return {
        "id": preset.id,
        "name": preset.name,
        "pitchCategoryFormat": preset.pitch_category_format,
        "cardTypeMarkerFields": dict(preset.card_type_marker_fields),
        "supportedCardTypes": list(preset.supported_card_types),
        "boldTargetInSentence": preset.bold_target_in_sentence,
    }


def fill_note_type(payload: Mapping[str, object]) -> str:
    """``anki.notetype.fill``: the preset and mappings desktop would propose for these field names."""

    if set(payload) != {"fieldNames", "language"}:
        raise _invalid("Expected payload fields: ['fieldNames', 'language']")
    field_names = _field_names(payload["fieldNames"])
    language = validated_language(payload["language"])

    # Deferred: ANKI_MINER_HOME freezes at anki_miner import time.
    from anki_miner.services.note_presets import fill_note_type_fields

    profile = get_profile(language)
    fill = fill_note_type_fields(
        field_names,
        # Lapis, Kiku and Senren are Japanese note types; Anki Miner Note needs no capability.
        allow_presets="note_presets" in profile.capabilities,
        extra_specs=profile.extra_card_fields,
    )
    return encode_message(
        "anki.notetype.fill.result",
        {
            "preset": _preset_wire(fill.preset),
            "fields": dict(fill.fields),
            "extraFields": dict(fill.extra_fields),
        },
    )
