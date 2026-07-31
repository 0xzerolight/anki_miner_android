"""Strict Android settings snapshot -> frozen desktop engine config mapping."""

from __future__ import annotations

import logging
import math
import os
import re
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from typing import cast

from .bootstrap import require_initialized
from .protocol import (
    BridgeProtocolError,
    decode_message,
    normalize_integral_json_number,
)
from .unicode_contract import (
    has_leading_or_trailing_python_whitespace,
    is_category_c,
    is_nfc,
)

logger = logging.getLogger(__name__)

_RESOURCE_ID_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?$")

# Mirrored from ``anki_miner.services.anki_note_builder.REQUIRED_FIELD_KEYS``.
# Keeping this bridge-side copy avoids importing the eager ``services`` package
# before Android bootstrap. ``AndroidAnkiAdapter`` checks it against the engine
# constant when the engine is first touched, so desktop drift fails closed.
_REQUIRED_ANKI_FIELD_KEYS = frozenset(
    {
        "word",
        "sentence",
        "definition",
        "picture",
        "audio",
        "expression_furigana",
        "sentence_furigana",
    }
)

_STRING_FIELDS = frozenset(
    {
        "anki_deck_name",
        "anki_note_type",
        "anki_tags",
        "subtitle_regex_filter",
        "subtitle_regex_replacement",
    }
)
_BOOL_FIELDS = frozenset(
    {
        "allow_duplicate_cards",
        "use_known_words_db",
        "exclude_hiragana_only_words",
        "exclude_katakana_only_words",
        "use_blacklist",
        "use_whitelist",
        "use_subtitle_regex_filter",
        "strip_subtitle_annotations",
        "bold_target_in_sentence",
        "deduplicate_sentences",
        "use_i_plus_one_filter",
        "use_sentence_length_filter",
    }
)
_FLOAT_RANGES: Mapping[str, tuple[float | None, float | None]] = {
    "audio_padding": (0.0, None),
    "screenshot_offset": (0.0, None),
    "subtitle_offset": (None, None),
    # Desktop explicitly warns not to reduce this delay.
    "jisho_delay": (0.5, None),
    "max_sentence_duration_seconds": (0.0, None),
}
_INT_RANGES: Mapping[str, tuple[int | None, int | None]] = {
    "audio_bitrate": (1, None),
    "max_frequency_rank": (0, None),
    "max_sentence_chars": (0, None),
    "reading_min_occurrence": (1, None),
    "max_parallel_workers": (1, 32),
}
_LITERAL_FIELDS: Mapping[str, frozenset[str]] = {
    "card_type": frozenset({"", "word_and_sentence", "click", "sentence", "audio"}),
    "audio_format": frozenset({"mp3", "opus"}),
    "pitch_category_format": frozenset({"jp", "romaji"}),
}
_STRING_TUPLE_FIELDS = frozenset({"excluded_decks", "allowed_pos", "excluded_subtypes", "excluded_wordsets"})
_OPTIONAL_PATH_FIELDS = frozenset({"blacklist_path", "whitelist_path"})
_MAPPING_FIELDS = frozenset({"anki_fields", "card_type_marker_fields"})
_CHAIN_FIELDS = frozenset({"dictionary_chain", "frequency_chain", "pitch_chain", "expression_audio_chain"})
_STATIC_SCREENSHOT_FIELD = "screenshot_animated"

_EXPOSED_CONFIG_FIELDS = frozenset(
    _STRING_FIELDS
    | _BOOL_FIELDS
    | set(_FLOAT_RANGES)
    | set(_INT_RANGES)
    | set(_LITERAL_FIELDS)
    | _STRING_TUPLE_FIELDS
    | _OPTIONAL_PATH_FIELDS
    | _MAPPING_FIELDS
    | _CHAIN_FIELDS
    | {_STATIC_SCREENSHOT_FIELD}
)

# Compatibility-only input.  It is captured into AndroidConfigSnapshot and is
# never assigned to AnkiMinerConfig, because that would build the desktop
# Google/Papago sentence-TTS chain.
_LEGACY_ANDROID_TTS_FIELD = "reading_tts_enabled"
_ANDROID_BUNDLED_WORDSETS = frozenset({"surnames", "given-names", "place-names", "org-product"})

# AnkiConnect-Android serves word pronunciation audio from an on-device
# local-audio server (loopback IPC, not network egress). This custom_json
# URL template — verbatim from the user's report — is injected as the default
# PRIMARY expression-audio source; imported local packs remain an ordered
# fallback. ``{term}``/``{reading}`` are substituted per word by the bridge
# CustomAudioFetcher; the endpoint returns an ``audioSourceList`` document.
_LOCALAUDIO_URL = "http://localhost:8765/localaudio/get/?term={term}&reading={reading}"
# AnkiConnect-Android documents local-audio delivery on its loopback server.
# Remote origins stay fail-closed until a provider origin is separately reviewed
# and added here; the fetcher still accepts loopback URLs and loopback redirects.
_LOCALAUDIO_APPROVED_AUDIO_ORIGINS: frozenset[str] = frozenset()


@dataclass(frozen=True)
class AndroidPaths:
    """Process paths supplied by Android, never by the settings snapshot."""

    files_dir: Path
    cache_dir: Path
    native_library_dir: Path

    def __post_init__(self) -> None:
        for name in ("files_dir", "cache_dir", "native_library_dir"):
            value = getattr(self, name)
            if isinstance(value, str):
                value = Path(value)
                object.__setattr__(self, name, value)
            if not isinstance(value, Path) or not value.is_absolute():
                raise BridgeProtocolError("invalid_android_path", f"{name} must be an absolute path")

    @classmethod
    def from_strings(cls, files_dir: str, cache_dir: str, native_library_dir: str) -> AndroidPaths:
        return cls(Path(files_dir), Path(cache_dir), Path(native_library_dir))


@dataclass(frozen=True)
class AndroidConfigSnapshot:
    """Engine config plus Android-only, non-engine execution options."""

    engine_config: object
    android_tts_enabled: bool


def exposed_config_fields() -> frozenset[str]:
    """Return the stable set Kotlin may place in ``settings``."""

    return _EXPOSED_CONFIG_FIELDS


def _invalid(field_name: str, detail: str) -> BridgeProtocolError:
    return BridgeProtocolError("invalid_config_field", f"{field_name}: {detail}")


def _string(field_name: str, value: object) -> str:
    if not isinstance(value, str):
        raise _invalid(field_name, "expected a string")
    return value


def _canonical_nonempty_string(field_name: str, value: object) -> str:
    """Require the exact user-visible name which will cross to AnkiDroid.

    Trimming or Unicode-normalizing here would silently change a persisted
    deck/model/field identity. Reject non-canonical input instead so Kotlin can
    point the user at the setting which needs correction.
    """

    if not isinstance(value, str) or not value:
        raise _invalid(field_name, "expected a non-empty string")
    if has_leading_or_trailing_python_whitespace(value):
        raise _invalid(field_name, "must not have leading or trailing whitespace")
    if not is_nfc(value):
        raise _invalid(field_name, "must use NFC Unicode normalization")
    if any(is_category_c(ord(character)) for character in value):
        raise _invalid(field_name, "must not contain control or format characters")
    return value


def _boolean(field_name: str, value: object) -> bool:
    if type(value) is not bool:
        raise _invalid(field_name, "expected a boolean")
    return value


def _float(field_name: str, value: object, bounds: tuple[float | None, float | None]) -> float:
    if type(value) not in (int, float):
        raise _invalid(field_name, "expected a number")
    converted = float(cast(int | float, value))
    if not math.isfinite(converted):
        raise _invalid(field_name, "expected a finite number")
    minimum, maximum = bounds
    if minimum is not None and converted < minimum:
        raise _invalid(field_name, f"must be at least {minimum}")
    if maximum is not None and converted > maximum:
        raise _invalid(field_name, f"must be at most {maximum}")
    return converted


def _integer(field_name: str, value: object, bounds: tuple[int | None, int | None]) -> int:
    converted = normalize_integral_json_number(value)
    if converted is None:
        raise _invalid(field_name, "expected an integer")
    minimum, maximum = bounds
    if minimum is not None and converted < minimum:
        raise _invalid(field_name, f"must be at least {minimum}")
    if maximum is not None and converted > maximum:
        raise _invalid(field_name, f"must be at most {maximum}")
    return converted


def _literal(field_name: str, value: object, allowed: frozenset[str]) -> str:
    if not isinstance(value, str) or value not in allowed:
        raise _invalid(field_name, f"expected one of {sorted(allowed)!r}")
    return value


def _string_tuple(field_name: str, value: object) -> tuple[str, ...]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)):
        raise _invalid(field_name, "expected an array of strings")
    if any(not isinstance(item, str) for item in value):
        raise _invalid(field_name, "expected an array of strings")
    return tuple(value)


def _excluded_decks(value: object) -> tuple[str, ...]:
    items = _string_tuple("excluded_decks", value)
    canonical = tuple(_canonical_nonempty_string(f"excluded_decks[{index}]", item) for index, item in enumerate(items))
    if len(set(canonical)) != len(canonical):
        raise _invalid("excluded_decks", "deck names must be unique")
    return canonical


def _excluded_wordsets(value: object) -> tuple[str, ...]:
    items = _string_tuple("excluded_wordsets", value)
    if len(items) > len(_ANDROID_BUNDLED_WORDSETS):
        raise _invalid("excluded_wordsets", "too many wordsets")
    if len(set(items)) != len(items):
        raise _invalid("excluded_wordsets", "wordset IDs must be unique")
    unknown = set(items) - _ANDROID_BUNDLED_WORDSETS
    if unknown:
        raise _invalid("excluded_wordsets", f"unknown Android wordsets: {sorted(unknown)!r}")
    return items


def _optional_path(field_name: str, value: object) -> Path | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise _invalid(field_name, "expected an absolute path or null")
    path = Path(value)
    if not path.is_absolute():
        raise _invalid(field_name, "expected an absolute path")
    return path


def _mapping_overlay(field_name: str, value: object, defaults: Mapping[str, str]) -> dict[str, str]:
    if not isinstance(value, Mapping):
        raise _invalid(field_name, "expected an object of string values")
    unknown = set(value) - set(defaults)
    if unknown:
        raise _invalid(field_name, f"unknown keys: {sorted(unknown)!r}")
    if any(not isinstance(key, str) or not isinstance(item, str) for key, item in value.items()):
        raise _invalid(field_name, "expected an object of string values")
    return {**dict(defaults), **dict(value)}


def _anki_mapping_overlay(field_name: str, value: object, defaults: Mapping[str, str]) -> dict[str, str]:
    result = _mapping_overlay(field_name, value, defaults)
    for key, mapped_name in result.items():
        if mapped_name:
            _canonical_nonempty_string(f"{field_name}.{key}", mapped_name)
    if field_name == "anki_fields":
        _validate_unique_anki_destinations(result)
    return result


def _validate_unique_anki_destinations(
    fields: Mapping[str, str],
    active_marker: tuple[str, str] | None = None,
) -> None:
    """Mirror Kotlin AnkiFieldMapPolicy at both Python pre-builder entry points."""

    owners: dict[str, list[str]] = {}
    for key, destination in fields.items():
        if destination:
            owners.setdefault(destination, []).append(key)
    if active_marker is not None:
        marker_owner, marker_destination = active_marker
        if marker_destination:
            owners.setdefault(marker_destination, []).append(marker_owner)
    for destination, logical_keys in owners.items():
        if len(logical_keys) > 1:
            raise _invalid(
                "anki_fields",
                f"destination {destination!r} is mapped by multiple logical fields: {logical_keys!r}",
            )


def validate_anki_request_config(
    config: object,
    *,
    verified_field_names: Sequence[str] | None = None,
) -> None:
    """Validate every config value emitted by the Anki callback adapter."""

    _canonical_nonempty_string("anki_deck_name", getattr(config, "anki_deck_name", None))
    _canonical_nonempty_string("anki_note_type", getattr(config, "anki_note_type", None))

    fields = getattr(config, "anki_fields", None)
    if not isinstance(fields, Mapping) or any(
        not isinstance(key, str) or not isinstance(value, str) for key, value in fields.items()
    ):
        raise _invalid("anki_fields", "expected an object of string values")
    missing = _REQUIRED_ANKI_FIELD_KEYS - set(fields)
    if missing:
        raise _invalid("anki_fields", f"missing required keys: {sorted(missing)!r}")
    for key, mapped_name in fields.items():
        if mapped_name:
            _canonical_nonempty_string(f"anki_fields.{key}", mapped_name)
    marker_fields = getattr(config, "card_type_marker_fields", None)
    if not isinstance(marker_fields, Mapping) or any(
        not isinstance(key, str) or not isinstance(value, str) for key, value in marker_fields.items()
    ):
        raise _invalid("card_type_marker_fields", "expected an object of string values")
    for key, mapped_name in marker_fields.items():
        if mapped_name:
            _canonical_nonempty_string(f"card_type_marker_fields.{key}", mapped_name)
    card_type = getattr(config, "card_type", "")
    active_marker = None
    if isinstance(card_type, str) and card_type:
        marker_destination = marker_fields.get(card_type, "")
        active_marker = (f"card_type_marker_fields.{card_type}", marker_destination)
    _validate_unique_anki_destinations(fields, active_marker)

    if verified_field_names is not None:
        first_field = verified_field_names[0] if verified_field_names else ""
        word_destination = fields["word"]
        if not first_field or word_destination != first_field:
            raise _invalid(
                "anki_fields.word",
                f"must map to the note type's first field {first_field!r}; got {word_destination!r}",
            )
    excluded = getattr(config, "excluded_decks", None)
    _excluded_decks(excluded)


def _resource_id(field_name: str, value: object) -> str:
    if not isinstance(value, str) or not _RESOURCE_ID_RE.fullmatch(value) or ".." in value:
        raise _invalid(field_name, "contains an unsafe resource ID")
    return value


def _chain_items(field_name: str, value: object) -> Sequence[object]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)):
        raise _invalid(field_name, "expected an array")
    return value


def _entry_mapping(field_name: str, item: object, allowed_keys: frozenset[str]) -> Mapping[str, object]:
    if not isinstance(item, Mapping) or any(not isinstance(key, str) for key in item):
        raise _invalid(field_name, "chain entries must be objects")
    unknown = set(item) - allowed_keys
    if unknown:
        raise _invalid(field_name, f"unknown entry keys: {sorted(unknown)!r}")
    return item


def _enabled(field_name: str, item: Mapping[str, object]) -> bool:
    return _boolean(field_name, item.get("enabled", True))


def _dictionary_chain(value: object, constructor: Callable[..., object]) -> tuple[object, ...]:
    result: list[object] = []
    identities: set[tuple[str, str | None]] = set()
    for raw in _chain_items("dictionary_chain", value):
        item = _entry_mapping("dictionary_chain", raw, frozenset({"kind", "dict_id", "enabled"}))
        kind = item.get("kind")
        if kind == "indexed":
            dict_id: str | None = _resource_id("dictionary_chain.dict_id", item.get("dict_id"))
        elif kind == "jisho":
            if item.get("dict_id") is not None:
                raise _invalid("dictionary_chain.dict_id", "jisho entries must use null")
            dict_id = None
        else:
            raise _invalid("dictionary_chain.kind", "expected 'indexed' or 'jisho'")
        identity = (kind, dict_id)
        if identity in identities:
            raise _invalid("dictionary_chain", "duplicate provider")
        identities.add(identity)
        result.append(
            constructor(
                kind=kind,
                dict_id=dict_id,
                enabled=_enabled("dictionary_chain.enabled", item),
            )
        )
    return tuple(result)


def _frequency_chain(value: object, constructor: Callable[..., object]) -> tuple[object, ...]:
    result: list[object] = []
    identities: set[str] = set()
    for raw in _chain_items("frequency_chain", value):
        item = _entry_mapping("frequency_chain", raw, frozenset({"source_id", "enabled"}))
        source_id = _resource_id("frequency_chain.source_id", item.get("source_id"))
        if source_id in identities:
            raise _invalid("frequency_chain", "duplicate source")
        identities.add(source_id)
        result.append(constructor(source_id=source_id, enabled=_enabled("frequency_chain.enabled", item)))
    return tuple(result)


def _pitch_chain(value: object, constructor: Callable[..., object]) -> tuple[object, ...]:
    result: list[object] = []
    identities: set[str] = set()
    for raw in _chain_items("pitch_chain", value):
        item = _entry_mapping("pitch_chain", raw, frozenset({"source_id", "enabled"}))
        source_id = _resource_id("pitch_chain.source_id", item.get("source_id"))
        if source_id in identities:
            raise _invalid("pitch_chain", "duplicate source")
        identities.add(source_id)
        result.append(constructor(source_id=source_id, enabled=_enabled("pitch_chain.enabled", item)))
    return tuple(result)


def _expression_audio_chain(value: object, constructor: Callable[..., object]) -> tuple[object, ...]:
    result: list[object] = []
    identities: set[str] = set()
    for raw in _chain_items("expression_audio_chain", value):
        item = _entry_mapping("expression_audio_chain", raw, frozenset({"kind", "pack_id", "enabled"}))
        if item.get("kind") != "pack":
            raise BridgeProtocolError(
                "unsupported_audio_source",
                "Android expression audio supports local packs only",
            )
        pack_id = _resource_id("expression_audio_chain.pack_id", item.get("pack_id"))
        if pack_id in identities:
            raise _invalid("expression_audio_chain", "duplicate pack")
        identities.add(pack_id)
        result.append(
            constructor(
                kind="pack",
                pack_id=pack_id,
                url=None,
                enabled=_enabled("expression_audio_chain.enabled", item),
            )
        )
    return tuple(result)


def _android_path_overrides(paths: AndroidPaths) -> dict[str, Path]:
    home = paths.files_dir
    return {
        "media_temp_folder": paths.cache_dir / "anki_miner_temp",
        "jmdict_path": home / "JMdict_e",
        "dicts_root": home / "dicts",
        "audio_packs_root": home / "audio_packs",
        "pitch_accent_path": home / "pitch_accent.csv",
        "pitch_root": home / "pitch",
        "freqs_root": home / "freqs",
        "known_words_db_path": home / "known_words.db",
        "stats_db_path": home / "stats.db",
        "log_path": home / "anki_miner.log",
        "ffmpeg_location": paths.native_library_dir / "libffmpeg.so",
        "ffprobe_location": paths.native_library_dir / "libffprobe.so",
        "asr_models_root": home / "asr_models",
        "cuda_libs_root": home / "cuda_libs",
        "onnx_pack_root": home / "onnx_pack",
        "bin_root": home / "bin",
        "themes_root": home / "themes",
    }


def _verify_ffmpeg_binary(tool: str, path: Path) -> None:
    resolved = path.resolve(strict=False)
    try:
        stat_result = os.stat(resolved)
    except OSError:
        logger.error(
            "ffmpeg_binary_verification_failed tool=%s path=%s reason=stat_failed",
            tool,
            resolved,
            exc_info=True,
        )
        return
    if not os.access(resolved, os.X_OK):
        logger.error(
            "ffmpeg_binary_verification_failed tool=%s path=%s reason=not_executable",
            tool,
            resolved,
        )
        return
    logger.info(
        "ffmpeg_binary_verified tool=%s path=%s size=%d",
        tool,
        resolved,
        stat_result.st_size,
    )


def map_config_settings(
    settings: Mapping[str, object],
    paths: AndroidPaths,
    *,
    android_tts_enabled: bool | None = None,
) -> AndroidConfigSnapshot:
    """Map an allowlisted settings object onto desktop defaults.

    All engine imports are function-local so ``bootstrap.initialize`` remains
    the only legal first engine touch.  Missing fields retain the exact desktop
    default; Android-owned paths and cut network-audio seams are then forced.
    """

    if not isinstance(settings, Mapping) or any(not isinstance(key, str) for key in settings):
        raise BridgeProtocolError("invalid_config_snapshot", "settings must be a JSON object")
    unknown = set(settings) - _EXPOSED_CONFIG_FIELDS - {_LEGACY_ANDROID_TTS_FIELD}
    if unknown:
        raise BridgeProtocolError(
            "unknown_config_field",
            f"Unknown or non-Android fields: {sorted(unknown)!r}",
        )

    legacy_tts: bool | None = None
    if _LEGACY_ANDROID_TTS_FIELD in settings:
        legacy_tts = _boolean(_LEGACY_ANDROID_TTS_FIELD, settings[_LEGACY_ANDROID_TTS_FIELD])
    if android_tts_enabled is not None and type(android_tts_enabled) is not bool:
        raise _invalid("androidTtsEnabled", "expected a boolean")
    if android_tts_enabled is not None and legacy_tts is not None and android_tts_enabled != legacy_tts:
        raise BridgeProtocolError(
            "conflicting_android_tts_flags",
            "androidTtsEnabled conflicts with legacy reading_tts_enabled",
        )
    ephemeral_tts = android_tts_enabled if android_tts_enabled is not None else (legacy_tts or False)

    require_initialized(paths.files_dir)

    from anki_miner.config import (
        AnkiMinerConfig,
        AudioSourceEntry,
        ChainEntry,
        FreqEntry,
        PitchSourceEntry,
    )

    base = AnkiMinerConfig()
    updates: dict[str, object] = {}
    for field_name, value in settings.items():
        if field_name == _LEGACY_ANDROID_TTS_FIELD:
            continue
        if field_name == _STATIC_SCREENSHOT_FIELD:
            if _boolean(field_name, value):
                raise BridgeProtocolError(
                    "unsupported_android_feature",
                    "Android v1 supports static JPEG screenshots only",
                )
            updates[field_name] = False
        elif field_name in _STRING_FIELDS:
            updates[field_name] = (
                _canonical_nonempty_string(field_name, value)
                if field_name in {"anki_deck_name", "anki_note_type"}
                else _string(field_name, value)
            )
        elif field_name in _BOOL_FIELDS:
            updates[field_name] = _boolean(field_name, value)
        elif field_name in _FLOAT_RANGES:
            updates[field_name] = _float(field_name, value, _FLOAT_RANGES[field_name])
        elif field_name in _INT_RANGES:
            updates[field_name] = _integer(field_name, value, _INT_RANGES[field_name])
        elif field_name in _LITERAL_FIELDS:
            updates[field_name] = _literal(field_name, value, _LITERAL_FIELDS[field_name])
        elif field_name in _STRING_TUPLE_FIELDS:
            if field_name == "excluded_decks":
                updates[field_name] = _excluded_decks(value)
            elif field_name == "excluded_wordsets":
                updates[field_name] = _excluded_wordsets(value)
            else:
                updates[field_name] = _string_tuple(field_name, value)
        elif field_name in _OPTIONAL_PATH_FIELDS:
            updates[field_name] = _optional_path(field_name, value)
        elif field_name in _MAPPING_FIELDS:
            updates[field_name] = _anki_mapping_overlay(field_name, value, getattr(base, field_name))
        elif field_name == "dictionary_chain":
            updates[field_name] = _dictionary_chain(value, ChainEntry)
        elif field_name == "frequency_chain":
            updates[field_name] = _frequency_chain(value, FreqEntry)
        elif field_name == "pitch_chain":
            updates[field_name] = _pitch_chain(value, PitchSourceEntry)
        elif field_name == "expression_audio_chain":
            updates[field_name] = _expression_audio_chain(value, AudioSourceEntry)
        else:  # pragma: no cover - guarded by the allowlist union
            raise BridgeProtocolError("unknown_config_field", field_name)

    # These overrides are deliberately applied after user settings.
    updates.update(_android_path_overrides(paths))
    # Inject AnkiConnect-Android's on-device local-audio server as the default
    # PRIMARY expression-audio source, ahead of any imported local packs (which
    # stay as an ordered fallback). This deliberately widens the settled
    # packs-only force-override per the confirmed product decision. The parser
    # can only ever emit ``pack`` entries (untrusted-boundary guard), so the
    # prepended localaudio source can never collide with an inbound entry, and
    # the builder's field-mapped gate (mining.py) still suppresses every fetch
    # when the expression_audio Anki field is unmapped — so the unconditional
    # injection is inert unless the user actually mapped the audio field.
    updates["expression_audio_chain"] = (
        AudioSourceEntry(kind="custom_json", url=_LOCALAUDIO_URL, enabled=True),
        *updates.get("expression_audio_chain", ()),
    )
    # The compact native recipe intentionally contains the static JPEG path,
    # not the desktop-only AVIF/WebP encoders.
    updates["screenshot_animated"] = False
    # ``AudioStage.reading_tts_active`` has a settled four-part desktop gate:
    # injected fetcher, master flag, mapped audio field, and a provider bit.
    # Android uses the Google-named bit only as the final compatibility gate;
    # reading_mining composes AndroidSentenceAudioFetcher directly and never
    # imports the desktop Google/Papago/gtts provider factory.  Thus enabling
    # this bit cannot select or construct a network fetcher.
    updates["reading_tts_enabled"] = ephemeral_tts
    updates["reading_tts_google_enabled"] = ephemeral_tts
    updates["reading_tts_papago_enabled"] = False

    engine_config = replace(base, **updates)  # type: ignore[arg-type]
    validate_anki_request_config(engine_config)
    _verify_ffmpeg_binary("ffmpeg", engine_config.ffmpeg_location)
    _verify_ffmpeg_binary("ffprobe", engine_config.ffprobe_location)
    return AndroidConfigSnapshot(
        # ``updates`` is validated field-by-field above; mypy cannot preserve
        # those dependent key/value types through a heterogeneous dict.
        engine_config=engine_config,
        android_tts_enabled=ephemeral_tts,
    )


def map_config_json(
    raw_snapshot: str,
    files_dir: str,
    cache_dir: str,
    native_library_dir: str,
) -> AndroidConfigSnapshot:
    """Decode a public ``config.snapshot`` message and map it."""

    payload = decode_message(raw_snapshot, expected_type="config.snapshot")
    if not set(payload).issubset({"settings", "androidTtsEnabled"}):
        raise BridgeProtocolError("invalid_config_snapshot", "Config snapshot fields are invalid")
    settings = payload.get("settings", {})
    if not isinstance(settings, dict):
        raise BridgeProtocolError("invalid_config_snapshot", "settings must be a JSON object")
    tts = payload.get("androidTtsEnabled")
    if "androidTtsEnabled" in payload and type(tts) is not bool:
        raise _invalid("androidTtsEnabled", "expected a boolean")
    return map_config_settings(
        settings,
        AndroidPaths.from_strings(files_dir, cache_dir, native_library_dir),
        android_tts_enabled=tts,
    )


def engine_config_from_json(
    raw_snapshot: str,
    files_dir: str,
    cache_dir: str,
    native_library_dir: str,
) -> object:
    """Convenience entry point for callers which do not need Android options."""

    return map_config_json(raw_snapshot, files_dir, cache_dir, native_library_dir).engine_config
