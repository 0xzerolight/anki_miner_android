"""The mining-language layer: which language a request means, and what Kotlin may know of it.

Every bridge site that depends on the mining language reads it through here, so
the rules live in one place:

- ``"ja"`` is the default everywhere a request carries no language, exactly as
  every engine API defaults to it. It never touches the profile registry, so the
  host test lane (which cannot import a profile: the registry pulls in
  ``subtitle_parser`` and with it pysubs2) keeps every Japanese path.
- Any other code must be one the vendored registry built a profile for, and is
  refused with the Android-owned ``unsupported_language`` code otherwise. The
  engine itself would silently mine an unknown code as Japanese
  (``registry.config_language``), which is the one outcome worse than an error.
- ``language.profiles`` hands Kotlin a JSON view of each profile. Engine text
  never crosses: an unavailable language carries an Android reason code, and
  Kotlin says the sentence from its own catalogs.

Engine imports are function-local: ``bootstrap.initialize`` must set
``ANKI_MINER_HOME`` before anything under ``anki_miner`` is imported.
"""

from __future__ import annotations

import functools
import logging
import pkgutil
import re
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)

JAPANESE = "ja"
UNSUPPORTED_LANGUAGE = "unsupported_language"

#: The language can mine once its downloadable data is installed (ar, fa).
LANGUAGE_DATA_REQUIRED = "language_data_required"
#: The language cannot mine on this device and no download helps (an engine
#: that should ship in the APK is missing). Kotlin offers no switch.
LANGUAGE_UNSUPPORTED = "language_unsupported"

_LANGUAGE_CODE_RE = re.compile(r"^[a-z]{2,3}$")

#: Models Android downloads apart from a pack component whose code ships in the
#: APK: the APK wheel is repacked without them, and an engine override loads them
#: by path. ``(language, import name) -> the vendored component they come from``;
#: the catalog entry (``tools/language-data/pins.json`` ``split``) says which
#: members of that component's own pinned archive they are.
SPLIT_DATA_COMPONENTS: Mapping[tuple[str, str], str] = {
    ("vi", "underthesea_models"): "underthesea",  # the two CRF models
    ("yue", "pycantonese_models"): "pycantonese",  # the segmenter and tagger models
}

#: The language data Android downloads, as ``(language, import_name)``. Data
#: only (decision 2): every other component a vendored pack declares is code,
#: which ships in the APK or not at all. ``test_languages`` pins the split, so a
#: newly vendored pack fails there until each of its components is classified.
DOWNLOADABLE_DATA_COMPONENTS: frozenset[tuple[str, str]] = frozenset(
    {
        ("ar", "calima_msa"),  # the CAMeL morphology database (a plain zip)
        ("fa", "hazm_data"),  # hazm's five .dat tables (the wheel's data/ directory only)
        # spaCy pipelines, loaded by path without their package __init__ (languages/_spaced/android_models)
        ("en", "en_core_web_sm"),
        ("ca", "ca_core_news_sm"),
        ("de", "de_core_news_sm"),
        ("pt", "pt_core_news_sm"),
        ("fr", "fr_core_news_sm"),
        ("es", "es_core_news_sm"),
        ("it", "it_core_news_sm"),
        ("nl", "nl_core_news_sm"),
        ("nb", "nb_core_news_sm"),
        ("ro", "ro_core_news_sm"),
        ("el", "el_core_news_sm"),
        ("fi", "fi_core_news_sm"),
        ("hu", "hu_core_news_md"),
        ("hr", "hr_core_news_sm"),
        ("sv", "sv_core_news_sm"),
        ("pl", "pl_core_news_sm"),
        ("lt", "lt_core_news_sm"),
        ("da", "da_core_news_sm"),
        ("sl", "sl_core_news_sm"),
        ("ru", "ru_core_news_sm"),
        ("uk", "uk_core_news_sm"),
        # pymorphy3's dictionaries (data/ only), passed to the lemmatizer by path
        ("ru", "pymorphy3_dicts_ru"),
        ("uk", "pymorphy3_dicts_uk"),
        ("ko", "kiwipiepy_model"),  # the Kiwi model files (the sdist minus its two .py files)
        *SPLIT_DATA_COMPONENTS,
    }
)

#: The ``script_variant`` values a profile capability unlocks, in desktop's combo
#: order (``mining_language_settings_panel``). A profile with neither capability
#: has no variant picker and only accepts ``""``.
_SCRIPT_VARIANTS_BY_CAPABILITY: Mapping[str, tuple[str, ...]] = {
    "script_variants": ("", "simplified", "traditional"),
    "regional_variants": ("br", "pt"),
}

#: ``LANGUAGE_SCOPED_FIELDS`` names config_map never accepts from Kotlin: they
#: drive desktop's YouTube downloader, which Android does not have.
_DESKTOP_ONLY_SCOPED_FIELDS = frozenset({"downloader_subtitle_langs", "downloader_audio_lang"})


def _unsupported(code: object) -> BridgeProtocolError:
    return BridgeProtocolError(UNSUPPORTED_LANGUAGE, f"Mining language is not available: {code!r}")


def validated_language(value: object) -> str:
    """Return *value* when it names a mining language this build can load."""

    if not isinstance(value, str) or not _LANGUAGE_CODE_RE.fullmatch(value):
        raise _unsupported(value)
    if value == JAPANESE:
        return value
    from anki_miner.languages.registry import available_languages

    if value not in available_languages():
        raise _unsupported(value)
    return value


def payload_language(payload: Mapping[str, object]) -> str:
    """The optional ``language`` of a request payload; ``"ja"`` when absent."""

    return validated_language(payload["language"]) if "language" in payload else JAPANESE


def without_language(payload: Mapping[str, object]) -> dict[str, object]:
    """*payload* minus its optional ``language``, for an exact-key check of the rest."""

    return {key: value for key, value in payload.items() if key != "language"}


def get_profile(code: str) -> Any:
    """The vendored profile for an already validated *code*."""

    from anki_miner.languages.registry import get_profile as registry_get_profile

    return registry_get_profile(code)


def base_config(code: str) -> Any:
    """The engine config a snapshot is overlaid onto for mining language *code*.

    Japanese is the dataclass default itself. Any other language is desktop's
    first visit (``switch_language`` from a default config), so every
    language-scoped field starts at that profile's ``scoped_defaults`` and no
    Japanese-shaped default (the jmdict chain, Lapis, the ja POS gate) leaks into
    it. The stash that switch parks ja's values in is dropped: Kotlin owns
    ``language_stash`` and never sends it.
    """

    from dataclasses import replace

    from anki_miner.config import AnkiMinerConfig

    if code == JAPANESE:
        return AnkiMinerConfig()
    from anki_miner.languages.switching import switch_language

    return replace(switch_language(AnkiMinerConfig(), code), language_stash={})


def language_kwarg(language: str) -> dict[str, str]:
    """``{"language": language}``, or nothing for Japanese.

    The engine's own idiom (``_sqlite_index.language_kwarg``): every engine API
    defaults to ``"ja"``, so omitting the keyword keeps a Japanese call
    byte-identical to the pre-transition one.
    """

    return {} if language == JAPANESE else {"language": language}


def profile_parser(config: object) -> Any:
    """A parser built like the run's, for display-only parsing and its text seams.

    Desktop ``service_factory.create_profile_parser``: Japanese keeps the literal
    class, every other language its profile factory, so cue text is cleaned
    with the same normaliser and bilingual-line gate mining applies.
    """

    language = config_language(config)
    if language == JAPANESE:
        from anki_miner.services.subtitle_parser import SubtitleParserService

        return SubtitleParserService(config)
    return get_profile(language).create_parser(config)


def release_other_taggers(language: str) -> None:
    """Evict every cached tagger except *language*'s, before work in *language* builds its own.

    Desktop releases the outgoing language's engine on every durable switch
    (``language_switch.commit_language_change``, S23: Arabic's holds ~400 MB). An
    Android switch only saves settings, so the bridge does it where a language's
    tagger is about to be built: run, cue-view and lemmatisation admission. As on
    desktop, a parser still holding a tagger keeps it alive until it lets go, ja's
    shared tagger outlives its cache entry, and module-level engine state (jieba's
    dictionary, underthesea's and pycantonese's models) is not freed.
    """

    from anki_miner.languages import tagger_provider

    released = [code for code in tuple(tagger_provider._TAGGERS) if code != language and tagger_provider.evict(code)]
    if released:
        logger.info("Released the cached taggers of %s before %s work", ",".join(released), language)


def config_language(config: object) -> str:
    """The mining language a mapped engine config carries (``"ja"`` for test doubles)."""

    language = getattr(config, "language", JAPANESE)
    return language if isinstance(language, str) and language else JAPANESE


def known_words_db_path(base: Path, language: str) -> Path:
    """The known-words database for *language*, beside *base*.

    Mirrors desktop ``gui.utils.service_factory.resolve_known_words_db_path``
    (not vendored): Japanese keeps ``known_words.db`` byte for byte, every other
    language gets its own ``known_words.<lang>.db`` so a word known in one
    language is never known in another.
    """

    if language == JAPANESE:
        return base
    return base.with_name(f"{base.stem}.{language}{base.suffix}")


def script_variants(profile: Any) -> tuple[str, ...]:
    """The ``script_variant`` values *profile* offers; empty when it has no picker."""

    variants: tuple[str, ...] = ()
    for capability, values in _SCRIPT_VARIANTS_BY_CAPABILITY.items():
        if capability in profile.capabilities:
            variants += values
    return variants


def accepted_script_variants(profile: Any) -> tuple[str, ...]:
    """What config_map accepts for ``script_variant`` under *profile*."""

    return script_variants(profile) or ("",)


def requires_unidic(code: str) -> bool:
    """Only Japanese tokenizes with the installed UniDic (the S1a MeCab tagger)."""

    return code == JAPANESE


#: The voice a regional variety speaks with, by profile code and ``script_variant``.
#: Desktop's ``pt_gtts_lang`` picks Google's European voice (``pt-PT``) for the
#: ``pt`` variety and its Brazilian one (``pt``) otherwise.
_REGIONAL_SPEECH_TAGS: Mapping[str, Mapping[str, str]] = {"pt": {"br": "pt-BR", "pt": "pt-PT"}}


def speech_language(profile: Any) -> str:
    """The BCP-47 language Android TextToSpeech speaks *profile*'s text in.

    The profile code itself for every vendored language; a run narrows a regional
    variety's voice through :func:`speech_language_for`. Desktop resolves voices
    through ``AudioDefaults.gtts_lang``, whose codes are Google's (``iw`` for
    Hebrew), not BCP-47. zh needs no region: desktop speaks both scripts with one
    Mandarin voice (``gtts_lang`` ``zh-CN``), and ``zh`` is Android's Mandarin
    locale (``Locale.CHINESE``). yue is Android's Cantonese language (``yue-HK``
    voices).
    """

    return str(profile.code)


def speech_language_for(language: str, script_variant: str = "") -> str:
    """The run's voice tag for a validated code: :func:`speech_language`, narrowed
    to the variety's region where the voice depends on it (``pt-PT``). Japanese
    needs no profile import."""

    if language == JAPANESE:
        return JAPANESE
    code = speech_language(get_profile(language))
    return _REGIONAL_SPEECH_TAGS.get(code, {}).get(script_variant, code)


@functools.cache
def bundled_modules() -> frozenset[str]:
    """Every top-level module this process can import: the APK's (the test venv's on the host lanes).

    Read from the importers' own listing, never ``find_spec``: Chaquopy answers
    ``find_spec`` on a top-level package by extracting every data file of it
    into ``filesDir`` (about 74 MB for the bundled language engines, after each
    install or update). ``pkgutil.iter_modules`` reads the zip index the
    importer already holds. What the APK bundles is fixed at build time and the
    bridge never extends ``sys.path``, so the listing is read once.
    """

    return frozenset(module.name for module in pkgutil.iter_modules())


def _missing_components(code: str) -> list[tuple[str, str]] | None:
    """The required components *code* lacks here, as ``(pack code, import name)``; None without a pack.

    Android's satisfaction ladder (decision 2), in place of desktop's
    ``component_satisfied`` and the profile's probe, which both ask
    ``find_spec``: downloadable data is satisfied by its extracted directory,
    and every other component is code, satisfied exactly when the APK bundles
    it. The packs a manifest ``requires`` (spaCy's runtime) count too, as
    ``is_installed`` counts them.
    """

    from anki_miner.services.language_pack_installer import component_path, load_pack

    pack = load_pack(code)
    if pack is None:
        return None
    missing: list[tuple[str, str]] = []
    for pack_code in (code, *pack.requires):
        manifest = pack if pack_code == code else load_pack(pack_code)
        if manifest is None:
            missing.append((pack_code, pack_code))  # an unloadable requirement: no download supplies it
            continue
        for component in manifest.components:
            key = (pack_code, component.import_name)
            if not component.required:
                continue
            if key in DOWNLOADABLE_DATA_COMPONENTS:
                present = component_path(pack_code, component.import_name) is not None
            else:
                present = component.import_name in bundled_modules()
            if not present:
                missing.append(key)
    return missing


def _missing_split_data(code: str) -> list[str]:
    """The split models *code* needs that are not installed.

    No pack manifest declares them: they come out of a code component's wheel,
    which the APK bundles, so only this check sees them missing.
    """

    from .language_data import data_component_path

    return [
        name
        for language, name in sorted(SPLIT_DATA_COMPONENTS)
        if language == code and data_component_path(language, name) is None
    ]


def unavailable_reason_code(profile: Any) -> str | None:
    """Why *profile* cannot mine on this device, as an Android reason code; None when it can.

    Answered from the pack manifest (:func:`_missing_components`) and the split
    models at call time, so a language whose data was just installed answers
    None on the next request. The profile's own ``unavailable_reason`` asks
    ``find_spec`` (see :func:`bundled_modules`), so it runs only for a language
    without a pack. Its sentence names desktop menus ("Settings -> Mining
    Language"), so it stays in the log.
    """

    missing = _missing_components(profile.code)
    if missing is None:
        probe = profile.unavailable_reason
        detail = probe() if probe is not None else None
        if not detail:
            return None
        # No manifest names what is missing, so no download can supply it.
        code = LANGUAGE_UNSUPPORTED
    else:
        missing += [(profile.code, name) for name in _missing_split_data(profile.code)]
        if not missing:
            return None
        data_only = all(key in DOWNLOADABLE_DATA_COMPONENTS for key in missing)
        code = LANGUAGE_DATA_REQUIRED if data_only else LANGUAGE_UNSUPPORTED
        detail = f"missing: {', '.join(name for _, name in missing)}"
    logger.info(
        "language_unavailable outcome=skip language=%s reason_code=%s detail=%s",
        profile.code,
        code,
        detail,
    )
    return code


def _chain_entry_wire(name: str, entry: Any) -> dict[str, object] | None:
    if name == "dictionary_chain":
        return {"kind": entry.kind, "dict_id": entry.dict_id, "enabled": entry.enabled}
    if name in {"frequency_chain", "pitch_chain"}:
        return {"source_id": entry.source_id, "enabled": entry.enabled}
    # expression_audio_chain: imported packs are the only kind Android builds.
    # Every profile's default network kind (jpod101, googletts, edgetts) is
    # dropped exactly as config_map drops an absent chain to ().
    if entry.kind != "pack" or entry.pack_id is None:
        return None
    return {"kind": "pack", "pack_id": entry.pack_id, "enabled": entry.enabled}


def _scoped_value_wire(name: str, value: object) -> object:
    if name in {"dictionary_chain", "frequency_chain", "pitch_chain", "expression_audio_chain"}:
        entries = (_chain_entry_wire(name, entry) for entry in value)  # type: ignore[attr-defined]
        return [entry for entry in entries if entry is not None]
    if isinstance(value, Mapping):
        return {str(key): item for key, item in value.items()}
    if isinstance(value, tuple):
        return list(value)
    if isinstance(value, Path):
        return str(value)
    return value


def scoped_defaults_wire(profile: Any) -> dict[str, object]:
    """*profile*'s first-visit value for every language-scoped field Kotlin may send.

    Each value is in the settings-snapshot shape config_map accepts for that
    field, so Kotlin can store it and send it back unchanged. The one value
    config_map refuses is a blank ``anki_note_type`` (every non-ja profile's
    default): it means "pick a note type", and a run cannot start until one is.
    """

    from anki_miner.languages.switching import LANGUAGE_SCOPED_FIELDS

    from .config_map import exposed_config_fields

    exposed = exposed_config_fields()
    return {
        name: _scoped_value_wire(name, profile.scoped_defaults[name])
        for name in LANGUAGE_SCOPED_FIELDS
        if name in exposed
    }


def profile_payload(profile: Any) -> dict[str, object]:
    """The ``language.profiles`` entry for one vendored profile."""

    return {
        "code": profile.code,
        "displayName": profile.display_name,
        "englishName": profile.english_name or profile.code.upper(),
        "unavailableReason": unavailable_reason_code(profile),
        "scriptVariants": list(script_variants(profile)),
        "contentDirection": profile.content_style.direction,
        "contentLanguage": profile.code,
        "speechLanguage": speech_language(profile),
        "audioTrackCodes": sorted(profile.audio_track_codes),
        "capabilities": sorted(profile.capabilities),
        "requiresUnidic": requires_unidic(profile.code),
        "scopedDefaults": scoped_defaults_wire(profile),
        "extraCardFields": [
            {
                "key": spec.key,
                "capability": spec.capability,
                "placeholder": spec.placeholder,
                "rawHtml": spec.raw_html,
            }
            for spec in profile.extra_card_fields
        ],
    }


def language_profiles(payload: Mapping[str, object]) -> str:
    """``language.profiles``: every vendored mining language, in registry order.

    A profile that fails to build is left out, as desktop's language list leaves
    it out (``gui.utils.language_choices``); C.1 proved every vendored profile
    builds, so this only guards a broken install. A language whose availability
    check hits a storage error is left out the same way.
    """

    if payload:
        raise BridgeProtocolError("invalid_language_profiles_request", "language.profiles takes no fields")
    from anki_miner.languages.registry import available_languages

    profiles: list[dict[str, object]] = []
    for code in available_languages():
        try:
            profile = get_profile(code)
        except (LookupError, ValueError, ImportError) as error:
            logger.warning("language_profile_unavailable outcome=skip language=%s", code, exc_info=error)
            continue
        try:
            entry = profile_payload(profile)
        except OSError as error:
            # Its availability reads the device (a pack directory on a full or
            # failing disk): that fails this language, not the list the
            # onboarding wizard waits on.
            logger.warning("language_profile_unavailable outcome=skip language=%s", code, exc_info=error)
            continue
        profiles.append(entry)
    return encode_message("language.profiles.result", {"profiles": profiles})
