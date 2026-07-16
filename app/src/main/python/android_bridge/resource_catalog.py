"""Strict models for the immutable Android resource catalog."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlsplit

from .protocol import BridgeProtocolError

CATALOG_SCHEMA_VERSION = 1
_CATALOG_PATH = Path(__file__).with_name("resource_catalog_v1.json")
_MAX_CATALOG_BYTES = 64 * 1024
_ID_RE = re.compile(r"(?!.*\.\.)[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
_SLOT_ID_RE = re.compile(
    r"(?!.*(?:\.\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?"
)
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
    if (
        not isinstance(value, str)
        or not value
        or len(value.encode("utf-8")) > max_bytes
    ):
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
        raise _error(
            f"{context} must be an absolute HTTPS URL without credentials or fragment"
        )
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
            copyright=_text(
                item["copyright"], context="attribution copyright", max_bytes=512
            ),
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
    def parse(cls, value: Any, *, expected_format: str) -> ArchiveIdentity:
        item = _exact(
            value,
            {"url", "sha256", "sizeBytes", "format"},
            context="archive identity",
        )
        archive_format = _text(item["format"], context="archive format", max_bytes=16)
        if archive_format != expected_format:
            raise _error(f"Archive format must be {expected_format!r}")
        return cls(
            url=_https_url(item["url"], context="archive URL"),
            sha256=_sha256(item["sha256"], context="archive hash"),
            size_bytes=_positive_int(item["sizeBytes"], context="archive size"),
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
        prefix = _text(
            item["memberPrefix"], context="UniDic member prefix", max_bytes=256
        )
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
        member_limit = _positive_int(
            item["archiveMemberLimit"], context="UniDic archive member limit"
        )
        if member_limit < file_count:
            raise _error(
                "UniDic archive member limit is below the installed file count"
            )
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
        member_count = _positive_int(
            item["memberCount"], context="Yomitan member count"
        )
        uncompressed = _positive_int(
            item["uncompressedBytes"], context="Yomitan uncompressed size"
        )
        member_limit = _positive_int(
            item["archiveMemberLimit"], context="Yomitan archive member limit"
        )
        total_limit = _positive_int(
            item["uncompressedBytesLimit"], context="Yomitan uncompressed size limit"
        )
        file_limit = _positive_int(
            item["fileBytesLimit"], context="Yomitan file size limit"
        )
        if (
            member_count > member_limit
            or uncompressed > total_limit
            or file_limit > total_limit
        ):
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


PinnedResource = UniDicResource | YomitanResource


@dataclass(frozen=True, slots=True)
class ResourceCatalog:
    resources: tuple[PinnedResource, ...]
    schema_version: int = CATALOG_SCHEMA_VERSION

    def get(self, resource_id: str) -> PinnedResource:
        for resource in self.resources:
            if resource.resource_id == resource_id:
                return resource
        raise BridgeProtocolError(
            "unknown_resource", f"Unknown pinned resource: {resource_id}"
        )

    def payload(self) -> dict[str, object]:
        return {
            "schemaVersion": self.schema_version,
            "resources": [resource.payload() for resource in self.resources],
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
            display_name=_text(
                item["displayName"], context="display name", max_bytes=256
            ),
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
            display_name=_text(
                item["displayName"], context="display name", max_bytes=256
            ),
            slot_id=_slot_id(item["slotId"], context="dictionary slot id"),
            archive=ArchiveIdentity.parse(item["archive"], expected_format="zip"),
            dictionary=YomitanDictionaryIdentity.parse(item["dictionary"]),
            attribution=_attribution(item["attribution"]),
        )
    raise _error(f"Unsupported resource kind: {kind!r}")


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
    root = _exact(document, {"schemaVersion", "resources"}, context="resource catalog")
    if root["schemaVersion"] != CATALOG_SCHEMA_VERSION:
        raise _error("Unsupported resource catalog schema")
    values = root["resources"]
    if not isinstance(values, list) or not values or len(values) > 32:
        raise _error("Resource catalog resources must be a non-empty bounded array")
    resources = tuple(_parse_resource(value) for value in values)
    ids = [resource.resource_id for resource in resources]
    if len(set(ids)) != len(ids):
        raise _error("Resource catalog contains duplicate resource ids")
    return ResourceCatalog(resources=resources)


@lru_cache(maxsize=1)
def load_resource_catalog() -> ResourceCatalog:
    try:
        raw = _CATALOG_PATH.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise _error("Bundled resource catalog cannot be read") from exc
    return parse_catalog_json(raw)
