"""Strict models for the immutable Android resource catalogs, one per mining language.

Each language's pins live in ``resource_catalog/<code>.json`` (schema 3), a file
whose ``language`` names its code. Japanese holds what the single schema-2
``resource_catalog_v1.json`` held, unchanged: the same resource ids, archives,
identities and attribution, so every Japanese resource installed under the old
file (UniDic's install manifest, a dictionary sidecar's ``catalogResourceId``)
still resolves. Resource ids are unique across every catalog: Kotlin persists
them (operation retry, dictionary sidecars) without a language beside them.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import cache, lru_cache
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlsplit

from ..protocol import BridgeProtocolError

CATALOG_SCHEMA_VERSION = 3
#: Every language with a catalog file, in the order ``resource.catalog`` lists them.
#: A fixed tuple rather than a directory listing: the packaged tree is read by
#: path on device, and ``test_resources`` binds this to the files present.
CATALOG_LANGUAGES: tuple[str, ...] = ("ja", "ar", "fa", "he", "id", "th")
_CATALOG_DIR = Path(__file__).parent
_LANGUAGE_RE = re.compile(r"[a-z]{2,3}")
_MAX_CATALOG_BYTES = 64 * 1024
# Mirrors local_resources._FREQUENCY_FORMATS / _PITCH_FORMATS. For a local resource the pinned
# archive format IS the importer's wire format: the bridge renames the download to
# ``source.<format>`` and the engine dispatches on that suffix.
_FREQUENCY_FORMATS = frozenset({"zip", "csv", "tsv", "txt"})
_PITCH_FORMATS = frozenset({"zip", "csv", "tsv"})
# Mirrors local_resources._FREQUENCY_ARCHIVE_LIMIT / _FREQUENCY_TEXT_LIMIT. A pin the importer
# would refuse must fail here, before the bytes are fetched.
_LOCAL_ARCHIVE_LIMIT = 512 * 1024 * 1024
_LOCAL_TEXT_LIMIT = 64 * 1024 * 1024
_MAX_RECOMMENDED = 8
#: Pack artifact kinds a data-only component may use. An sdist is code by nature.
_LANGUAGE_DATA_FORMATS = frozenset({"zip", "wheel"})
#: Mirrors the vendored ``pack_installer.MAX_ARTIFACT_BYTES``.
_LANGUAGE_DATA_ARCHIVE_LIMIT = 200 * 1024 * 1024
_IMPORT_NAME_RE = re.compile(r"[a-z_][a-z0-9_]{0,63}")
_MAX_INSTALL_PATHS = 64
_ID_RE = re.compile(r"(?!.*\.\.)[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
_SLOT_ID_RE = re.compile(r"(?!.*(?:\.\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
_SHA256_RE = re.compile(r"[0-9a-f]{64}")


def _error(message: str) -> BridgeProtocolError:
    return BridgeProtocolError("invalid_resource_catalog", message)


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _error(f"Resource catalog contains duplicate key {key!r}")
        result[key] = value
    return result


def _exact(value: Any, keys: set[str], *, context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise _error(f"{context} must contain exactly {sorted(keys)!r}")
    return value


def _text(value: Any, *, context: str, max_bytes: int = 4096) -> str:
    if not isinstance(value, str) or not value or len(value.encode("utf-8")) > max_bytes:
        raise _error(f"{context} must be a non-empty bounded string")
    return value


def _positive_int(value: Any, *, context: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise _error(f"{context} must be a positive integer")
    return value


def _resource_id(value: Any, *, context: str) -> str:
    candidate = _text(value, context=context, max_bytes=64)
    if not _ID_RE.fullmatch(candidate):
        raise _error(f"{context} is invalid")
    return candidate


def _slot_id(value: Any, *, context: str) -> str:
    candidate = _text(value, context=context, max_bytes=64)
    if not _SLOT_ID_RE.fullmatch(candidate):
        raise _error(f"{context} is invalid")
    return candidate


def _sha256(value: Any, *, context: str) -> str:
    candidate = _text(value, context=context, max_bytes=64)
    if not _SHA256_RE.fullmatch(candidate):
        raise _error(f"{context} must be lowercase SHA-256")
    return candidate


def _https_url(value: Any, *, context: str) -> str:
    candidate = _text(value, context=context)
    parsed = urlsplit(candidate)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise _error(f"{context} must be an absolute HTTPS URL without credentials or fragment")
    return candidate


@dataclass(frozen=True, slots=True)
class Attribution:
    name: str
    copyright: str
    license: str
    url: str

    @classmethod
    def parse(cls, value: Any) -> Attribution:
        item = _exact(
            value,
            {"name", "copyright", "license", "url"},
            context="attribution entry",
        )
        return cls(
            name=_text(item["name"], context="attribution name", max_bytes=256),
            copyright=_text(item["copyright"], context="attribution copyright", max_bytes=512),
            license=_text(item["license"], context="attribution license", max_bytes=64),
            url=_https_url(item["url"], context="attribution URL"),
        )

    def payload(self) -> dict[str, object]:
        return {
            "name": self.name,
            "copyright": self.copyright,
            "license": self.license,
            "url": self.url,
        }


@dataclass(frozen=True, slots=True)
class ArchiveIdentity:
    url: str
    sha256: str
    size_bytes: int
    format: str

    @classmethod
    def parse(
        cls,
        value: Any,
        *,
        expected_format: str | None = None,
        allowed_formats: frozenset[str] | None = None,
    ) -> ArchiveIdentity:
        item = _exact(
            value,
            {"url", "sha256", "sizeBytes", "format"},
            context="archive identity",
        )
        archive_format = _text(item["format"], context="archive format", max_bytes=16)
        if expected_format is not None and archive_format != expected_format:
            raise _error(f"Archive format must be {expected_format!r}")
        if allowed_formats is not None and archive_format not in allowed_formats:
            raise _error(f"Archive format must be one of {sorted(allowed_formats)!r}")
        size_bytes = _positive_int(item["sizeBytes"], context="archive size")
        if allowed_formats is not None:
            limit = _LOCAL_ARCHIVE_LIMIT if archive_format == "zip" else _LOCAL_TEXT_LIMIT
            if size_bytes > limit:
                raise _error("Pinned local resource exceeds its importer limit")
        return cls(
            url=_https_url(item["url"], context="archive URL"),
            sha256=_sha256(item["sha256"], context="archive hash"),
            size_bytes=size_bytes,
            format=archive_format,
        )

    def payload(self) -> dict[str, object]:
        return {
            "url": self.url,
            "sha256": self.sha256,
            "sizeBytes": self.size_bytes,
            "format": self.format,
        }


@dataclass(frozen=True, slots=True)
class UniDicInstallIdentity:
    member_prefix: str
    tree_sha256: str
    file_count: int
    size_bytes: int
    archive_member_limit: int

    @classmethod
    def parse(cls, value: Any) -> UniDicInstallIdentity:
        item = _exact(
            value,
            {
                "memberPrefix",
                "treeSha256",
                "fileCount",
                "sizeBytes",
                "archiveMemberLimit",
            },
            context="UniDic install identity",
        )
        prefix = _text(item["memberPrefix"], context="UniDic member prefix", max_bytes=256)
        path = PurePosixPath(prefix)
        if (
            not prefix.endswith("/")
            or path.is_absolute()
            or ".." in path.parts
            or "\\" in prefix
            or len(path.parts) < 3
        ):
            raise _error("UniDic member prefix is unsafe")
        file_count = _positive_int(item["fileCount"], context="UniDic file count")
        member_limit = _positive_int(item["archiveMemberLimit"], context="UniDic archive member limit")
        if member_limit < file_count:
            raise _error("UniDic archive member limit is below the installed file count")
        return cls(
            member_prefix=prefix,
            tree_sha256=_sha256(item["treeSha256"], context="UniDic tree hash"),
            file_count=file_count,
            size_bytes=_positive_int(item["sizeBytes"], context="UniDic tree size"),
            archive_member_limit=member_limit,
        )

    def payload(self) -> dict[str, object]:
        return {
            "memberPrefix": self.member_prefix,
            "treeSha256": self.tree_sha256,
            "fileCount": self.file_count,
            "sizeBytes": self.size_bytes,
            "archiveMemberLimit": self.archive_member_limit,
        }


@dataclass(frozen=True, slots=True)
class YomitanDictionaryIdentity:
    title: str
    revision: str
    format: int
    member_count: int
    uncompressed_bytes: int
    archive_member_limit: int
    uncompressed_bytes_limit: int
    file_bytes_limit: int

    @classmethod
    def parse(cls, value: Any) -> YomitanDictionaryIdentity:
        item = _exact(
            value,
            {
                "title",
                "revision",
                "format",
                "memberCount",
                "uncompressedBytes",
                "archiveMemberLimit",
                "uncompressedBytesLimit",
                "fileBytesLimit",
            },
            context="Yomitan dictionary identity",
        )
        format_version = _positive_int(item["format"], context="Yomitan format")
        member_count = _positive_int(item["memberCount"], context="Yomitan member count")
        uncompressed = _positive_int(item["uncompressedBytes"], context="Yomitan uncompressed size")
        member_limit = _positive_int(item["archiveMemberLimit"], context="Yomitan archive member limit")
        total_limit = _positive_int(item["uncompressedBytesLimit"], context="Yomitan uncompressed size limit")
        file_limit = _positive_int(item["fileBytesLimit"], context="Yomitan file size limit")
        if member_count > member_limit or uncompressed > total_limit or file_limit > total_limit:
            raise _error("Yomitan pinned identity exceeds its import limits")
        return cls(
            title=_text(item["title"], context="Yomitan title", max_bytes=512),
            revision=_text(item["revision"], context="Yomitan revision", max_bytes=128),
            format=format_version,
            member_count=member_count,
            uncompressed_bytes=uncompressed,
            archive_member_limit=member_limit,
            uncompressed_bytes_limit=total_limit,
            file_bytes_limit=file_limit,
        )

    def payload(self) -> dict[str, object]:
        return {
            "title": self.title,
            "revision": self.revision,
            "format": self.format,
            "memberCount": self.member_count,
            "uncompressedBytes": self.uncompressed_bytes,
            "archiveMemberLimit": self.archive_member_limit,
            "uncompressedBytesLimit": self.uncompressed_bytes_limit,
            "fileBytesLimit": self.file_bytes_limit,
        }


@dataclass(frozen=True, slots=True)
class UniDicResource:
    resource_id: str
    display_name: str
    archive: ArchiveIdentity
    install: UniDicInstallIdentity
    attribution: tuple[Attribution, ...]
    kind: str = "unidic"

    def payload(self) -> dict[str, object]:
        return {
            "resourceId": self.resource_id,
            "kind": self.kind,
            "displayName": self.display_name,
            "archive": self.archive.payload(),
            "install": self.install.payload(),
            "attribution": [item.payload() for item in self.attribution],
        }


@dataclass(frozen=True, slots=True)
class YomitanResource:
    resource_id: str
    display_name: str
    slot_id: str
    archive: ArchiveIdentity
    dictionary: YomitanDictionaryIdentity
    attribution: tuple[Attribution, ...]
    kind: str = "yomitan-dictionary"

    def payload(self) -> dict[str, object]:
        return {
            "resourceId": self.resource_id,
            "kind": self.kind,
            "displayName": self.display_name,
            "slotId": self.slot_id,
            "archive": self.archive.payload(),
            "dictionary": self.dictionary.payload(),
            "attribution": [item.payload() for item in self.attribution],
        }


@dataclass(frozen=True, slots=True)
class FrequencyResource:
    """A pinned frequency source installed through ``resource.frequency.import``.

    Carries no entry-count or size-limit identity of its own: the importer
    already validates the archive it is handed, and the downloader already pins
    the bytes by hash and length.
    """

    resource_id: str
    display_name: str
    source_id: str
    archive: ArchiveIdentity
    attribution: tuple[Attribution, ...]
    kind: str = "frequency"

    def payload(self) -> dict[str, object]:
        return {
            "resourceId": self.resource_id,
            "kind": self.kind,
            "displayName": self.display_name,
            "sourceId": self.source_id,
            "archive": self.archive.payload(),
            "attribution": [item.payload() for item in self.attribution],
        }


@dataclass(frozen=True, slots=True)
class PitchResource:
    """A pinned pitch-accent source installed through ``resource.pitch.import``."""

    resource_id: str
    display_name: str
    source_id: str
    archive: ArchiveIdentity
    attribution: tuple[Attribution, ...]
    kind: str = "pitch"

    def payload(self) -> dict[str, object]:
        return {
            "resourceId": self.resource_id,
            "kind": self.kind,
            "displayName": self.display_name,
            "sourceId": self.source_id,
            "archive": self.archive.payload(),
            "attribution": [item.payload() for item in self.attribution],
        }


def _relative_path(value: Any, *, context: str, directory: bool = False) -> str:
    """A package-relative POSIX path; *directory* allows (and keeps) one trailing ``/``."""

    candidate = _text(value, context=context, max_bytes=256)
    body = candidate[:-1] if directory and candidate.endswith("/") else candidate
    parts = body.split("/")
    if "\\" in candidate or candidate.startswith("/") or any(part in {"", ".", ".."} for part in parts):
        raise _error(f"{context} is unsafe")
    return candidate


def _path_list(value: Any, *, context: str, directory: bool = False, allow_empty: bool = True) -> tuple[str, ...]:
    if not isinstance(value, list) or len(value) > _MAX_INSTALL_PATHS or (not value and not allow_empty):
        raise _error(f"{context} must be a bounded array")
    paths = tuple(_relative_path(item, context=context, directory=directory) for item in value)
    if len(set(paths)) != len(paths):
        raise _error(f"{context} repeats a path")
    return paths


@dataclass(frozen=True, slots=True)
class InnerDigest:
    path: str
    sha256: str

    def payload(self) -> dict[str, object]:
        return {"path": self.path, "sha256": self.sha256}


@dataclass(frozen=True, slots=True)
class LanguageDataInstallIdentity:
    """How the vendored installer unpacks one data component (its ``ArtifactSpec``).

    Generated from ``languages/<code>/pack.py``; the bridge re-checks it against
    the vendored manifest before extracting, and reads ``sentinels`` to report
    an install without importing the engine.
    """

    member_prefix: str
    exclude: tuple[str, ...]
    sentinels: tuple[str, ...]
    inner_sha256: tuple[InnerDigest, ...]

    @classmethod
    def parse(cls, value: Any) -> LanguageDataInstallIdentity:
        item = _exact(
            value,
            {"memberPrefix", "exclude", "sentinels", "innerSha256"},
            context="language-data install identity",
        )
        prefix = item["memberPrefix"]
        if prefix != "":
            prefix = _relative_path(prefix, context="language-data member prefix", directory=True)
            if not prefix.endswith("/"):
                raise _error("language-data member prefix must name a directory")
        digests = item["innerSha256"]
        if not isinstance(digests, list) or len(digests) > _MAX_INSTALL_PATHS:
            raise _error("language-data inner digests must be a bounded array")
        inner = tuple(
            InnerDigest(
                path=_relative_path(entry["path"], context="language-data inner path"),
                sha256=_sha256(entry["sha256"], context="language-data inner hash"),
            )
            for entry in (_exact(raw, {"path", "sha256"}, context="language-data inner digest") for raw in digests)
        )
        if len({digest.path for digest in inner}) != len(inner):
            raise _error("language-data inner digests repeat a path")
        return cls(
            member_prefix=prefix,
            exclude=_path_list(item["exclude"], context="language-data exclude", directory=True),
            sentinels=_path_list(item["sentinels"], context="language-data sentinel", allow_empty=False),
            inner_sha256=inner,
        )

    def payload(self) -> dict[str, object]:
        return {
            "memberPrefix": self.member_prefix,
            "exclude": list(self.exclude),
            "sentinels": list(self.sentinels),
            "innerSha256": [digest.payload() for digest in self.inner_sha256],
        }


@dataclass(frozen=True, slots=True)
class LanguageDataResource:
    """A data-only component of a vendored language pack (``language_packs/<code>/<importName>/``).

    Generated by ``tools/language-data/generate_language_data.py`` from the
    vendored ``pack.py``; installed through ``resource.languagedata.install``.
    """

    resource_id: str
    display_name: str
    import_name: str
    archive: ArchiveIdentity
    install: LanguageDataInstallIdentity
    attribution: tuple[Attribution, ...]
    kind: str = "language-data"

    def payload(self) -> dict[str, object]:
        return {
            "resourceId": self.resource_id,
            "kind": self.kind,
            "displayName": self.display_name,
            "importName": self.import_name,
            "archive": self.archive.payload(),
            "install": self.install.payload(),
            "attribution": [item.payload() for item in self.attribution],
        }


PinnedResource = UniDicResource | YomitanResource | FrequencyResource | PitchResource | LanguageDataResource


@dataclass(frozen=True, slots=True)
class ResourceCatalog:
    resources: tuple[PinnedResource, ...]
    recommended: tuple[str, ...]
    language: str = "ja"
    schema_version: int = CATALOG_SCHEMA_VERSION

    @property
    def language_data(self) -> tuple[LanguageDataResource, ...]:
        return tuple(resource for resource in self.resources if isinstance(resource, LanguageDataResource))

    def get(self, resource_id: str) -> PinnedResource:
        for resource in self.resources:
            if resource.resource_id == resource_id:
                return resource
        raise BridgeProtocolError("unknown_resource", f"Unknown pinned resource: {resource_id}")

    def payload(self) -> dict[str, object]:
        return {
            "schemaVersion": self.schema_version,
            "language": self.language,
            "resources": [resource.payload() for resource in self.resources],
            "recommended": list(self.recommended),
        }


def _attribution(value: Any) -> tuple[Attribution, ...]:
    if not isinstance(value, list) or not value or len(value) > 32:
        raise _error("Resource attribution must be a non-empty bounded array")
    return tuple(Attribution.parse(item) for item in value)


def _parse_resource(value: Any) -> PinnedResource:
    if not isinstance(value, dict):
        raise _error("Resource entry must be an object")
    kind = value.get("kind")
    if kind == "unidic":
        item = _exact(
            value,
            {"resourceId", "kind", "displayName", "archive", "install", "attribution"},
            context="UniDic resource",
        )
        return UniDicResource(
            resource_id=_resource_id(item["resourceId"], context="resource id"),
            display_name=_text(item["displayName"], context="display name", max_bytes=256),
            archive=ArchiveIdentity.parse(item["archive"], expected_format="tar.gz"),
            install=UniDicInstallIdentity.parse(item["install"]),
            attribution=_attribution(item["attribution"]),
        )
    if kind == "yomitan-dictionary":
        item = _exact(
            value,
            {
                "resourceId",
                "kind",
                "displayName",
                "slotId",
                "archive",
                "dictionary",
                "attribution",
            },
            context="Yomitan resource",
        )
        return YomitanResource(
            resource_id=_resource_id(item["resourceId"], context="resource id"),
            display_name=_text(item["displayName"], context="display name", max_bytes=256),
            slot_id=_slot_id(item["slotId"], context="dictionary slot id"),
            archive=ArchiveIdentity.parse(item["archive"], expected_format="zip"),
            dictionary=YomitanDictionaryIdentity.parse(item["dictionary"]),
            attribution=_attribution(item["attribution"]),
        )
    if kind in {"frequency", "pitch"}:
        item = _exact(
            value,
            {"resourceId", "kind", "displayName", "sourceId", "archive", "attribution"},
            context=f"{kind} resource",
        )
        formats = _FREQUENCY_FORMATS if kind == "frequency" else _PITCH_FORMATS
        factory = FrequencyResource if kind == "frequency" else PitchResource
        return factory(
            resource_id=_resource_id(item["resourceId"], context="resource id"),
            display_name=_text(item["displayName"], context="display name", max_bytes=256),
            source_id=_slot_id(item["sourceId"], context="local source id"),
            archive=ArchiveIdentity.parse(item["archive"], allowed_formats=formats),
            attribution=_attribution(item["attribution"]),
        )
    if kind == "language-data":
        item = _exact(
            value,
            {"resourceId", "kind", "displayName", "importName", "archive", "install", "attribution"},
            context="language-data resource",
        )
        archive = ArchiveIdentity.parse(item["archive"])
        if archive.format not in _LANGUAGE_DATA_FORMATS:
            raise _error(f"Archive format must be one of {sorted(_LANGUAGE_DATA_FORMATS)!r}")
        if archive.size_bytes > _LANGUAGE_DATA_ARCHIVE_LIMIT:
            raise _error("Pinned language data exceeds the pack artifact limit")
        import_name = _text(item["importName"], context="language-data import name", max_bytes=64)
        if not _IMPORT_NAME_RE.fullmatch(import_name):
            raise _error("language-data import name is invalid")
        return LanguageDataResource(
            resource_id=_resource_id(item["resourceId"], context="resource id"),
            display_name=_text(item["displayName"], context="display name", max_bytes=256),
            import_name=import_name,
            archive=archive,
            install=LanguageDataInstallIdentity.parse(item["install"]),
            attribution=_attribution(item["attribution"]),
        )
    raise _error(f"Unsupported resource kind: {kind!r}")


def _parse_recommended(value: Any, *, known: set[str]) -> tuple[str, ...]:
    # Empty is legal: a language whose only pins are its engine data has no
    # dictionary or list to recommend yet.
    if not isinstance(value, list) or len(value) > _MAX_RECOMMENDED:
        raise _error("Recommended set must be a bounded array")
    recommended = tuple(_resource_id(item, context="recommended resource id") for item in value)
    if len(set(recommended)) != len(recommended):
        raise _error("Recommended set repeats a resource id")
    if not set(recommended) <= known:
        raise _error("Recommended set names an unknown resource")
    return recommended


def parse_catalog_json(raw: str) -> ResourceCatalog:
    try:
        encoded = raw.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise _error("Resource catalog is not valid Unicode") from exc
    if not encoded or len(encoded) > _MAX_CATALOG_BYTES:
        raise _error("Resource catalog exceeds its size limit")
    try:
        document = json.loads(raw, object_pairs_hook=_object_pairs)
    except BridgeProtocolError:
        raise
    except (json.JSONDecodeError, TypeError, ValueError) as exc:
        raise _error("Resource catalog is not valid JSON") from exc
    root = _exact(
        document,
        {"schemaVersion", "language", "resources", "recommended"},
        context="resource catalog",
    )
    if type(root["schemaVersion"]) is not int or root["schemaVersion"] != CATALOG_SCHEMA_VERSION:
        raise _error("Unsupported resource catalog schema")
    language = root["language"]
    if not isinstance(language, str) or not _LANGUAGE_RE.fullmatch(language):
        raise _error("Resource catalog language is invalid")
    values = root["resources"]
    if not isinstance(values, list) or not values or len(values) > 32:
        raise _error("Resource catalog resources must be a non-empty bounded array")
    resources = tuple(_parse_resource(value) for value in values)
    ids = [resource.resource_id for resource in resources]
    if len(set(ids)) != len(ids):
        raise _error("Resource catalog contains duplicate resource ids")
    recommended = _parse_recommended(root["recommended"], known=set(ids))
    data = [resource for resource in resources if isinstance(resource, LanguageDataResource)]
    if len({resource.import_name for resource in data}) != len(data):
        raise _error("Resource catalog pins one language-data component twice")
    # Engine data is installed with the language, never picked from the set.
    if {resource.resource_id for resource in data} & set(recommended):
        raise _error("Recommended set names language data")
    return ResourceCatalog(resources=resources, recommended=recommended, language=language)


@cache
def load_resource_catalog(language: str = "ja") -> ResourceCatalog:
    """The bundled catalog of *language*; ja when no language is named."""

    if language not in CATALOG_LANGUAGES:
        raise BridgeProtocolError("unknown_resource", f"No resource catalog for language: {language!r}")
    try:
        raw = (_CATALOG_DIR / f"{language}.json").read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise _error("Bundled resource catalog cannot be read") from exc
    catalog = parse_catalog_json(raw)
    if catalog.language != language:
        raise _error(f"Resource catalog {language}.json names another language")
    return catalog


@lru_cache(maxsize=1)
def load_resource_catalogs() -> tuple[ResourceCatalog, ...]:
    """Every bundled catalog, in ``CATALOG_LANGUAGES`` order, ids unique across all."""

    catalogs = tuple(load_resource_catalog(language) for language in CATALOG_LANGUAGES)
    ids = [resource.resource_id for catalog in catalogs for resource in catalog.resources]
    if len(set(ids)) != len(ids):
        raise _error("Resource catalogs share a resource id")
    return catalogs


def find_catalog_resource(resource_id: str) -> tuple[str, PinnedResource]:
    """``(language, resource)`` for a pinned id from any catalog."""

    for catalog in load_resource_catalogs():
        for resource in catalog.resources:
            if resource.resource_id == resource_id:
                return catalog.language, resource
    raise BridgeProtocolError("unknown_resource", f"Unknown pinned resource: {resource_id}")


def catalogs_payload() -> dict[str, object]:
    """The ``resource.catalog`` payload: every language's catalog."""

    return {"catalogs": [catalog.payload() for catalog in load_resource_catalogs()]}
