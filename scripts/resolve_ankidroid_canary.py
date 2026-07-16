#!/usr/bin/env python3
"""Resolve and verify one exact official AnkiDroid prerelease canary APK."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


SCHEMA = "anki-miner-ankidroid-prerelease-canary-v1"
REPOSITORY = "ankidroid/Anki-Android"
OFFICIAL_CERT_SHA256 = "2071534f0f4b5e54ae952dd275d70da6e3459ee69909d2ab1b4843c4c5b21a45"
TAG = re.compile(r"v(?P<version>[0-9]+\.[0-9]+\.[0-9]+(?:alpha|beta|rc)[0-9]+)\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
TIMESTAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z")
MAX_APK_BYTES = 300 * 1024 * 1024


class CanaryError(RuntimeError):
    pass


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise CanaryError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def _load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_strict_object)
    except (json.JSONDecodeError, OSError) as error:
        raise CanaryError(f"cannot read JSON input {path}: {error}") from error


def _canonical_payload(document: dict[str, Any]) -> bytes:
    unsigned = {key: value for key, value in document.items() if key != "payload_sha256"}
    return json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _positive_int(value: object, label: str) -> int:
    if type(value) is not int or value <= 0:
        raise CanaryError(f"{label} must be a positive integer")
    return value


def _exact(value: object, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise CanaryError(f"{label} fields are invalid")
    return value


def resolve_release(raw: object) -> dict[str, Any]:
    if not isinstance(raw, list):
        raise CanaryError("GitHub releases response must be a list")
    candidates: list[tuple[str, int, dict[str, Any], re.Match[str]]] = []
    for release in raw:
        if not isinstance(release, dict):
            raise CanaryError("GitHub release entry must be an object")
        if release.get("draft") is not False or release.get("prerelease") is not True:
            continue
        tag = release.get("tag_name")
        published_at = release.get("published_at")
        match = TAG.fullmatch(tag) if isinstance(tag, str) else None
        if match is None or not isinstance(published_at, str) or TIMESTAMP.fullmatch(published_at) is None:
            raise CanaryError("official prerelease tag or publication time is unsupported")
        release_id = _positive_int(release.get("id"), "release ID")
        candidates.append((published_at, release_id, release, match))
    if not candidates:
        raise CanaryError("GitHub returned no supported non-draft prerelease")
    _published_at, _release_id, release, tag_match = max(candidates, key=lambda item: (item[0], item[1]))

    tag = release["tag_name"]
    version_name = tag_match.group("version")
    expected_asset_name = f"variant-abi-AnkiDroid-{version_name}-x86_64.apk"
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise CanaryError("official prerelease assets are missing")
    selected = [asset for asset in assets if isinstance(asset, dict) and asset.get("name") == expected_asset_name]
    if len(selected) != 1:
        raise CanaryError("official prerelease has no unique x86_64 ABI APK")
    asset = selected[0]
    digest = asset.get("digest")
    if not isinstance(digest, str) or not digest.startswith("sha256:") or SHA256.fullmatch(digest[7:]) is None:
        raise CanaryError("official prerelease asset has no usable GitHub SHA-256 digest")
    expected_url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{expected_asset_name}"
    if asset.get("browser_download_url") != expected_url:
        raise CanaryError("official prerelease asset URL is outside the expected release")
    size = _positive_int(asset.get("size"), "asset size")
    if not 1024 * 1024 <= size <= MAX_APK_BYTES:
        raise CanaryError("official prerelease APK size is outside the safety bound")
    if asset.get("state") != "uploaded":
        raise CanaryError("official prerelease APK is not in uploaded state")
    html_url = f"https://github.com/{REPOSITORY}/releases/tag/{tag}"
    if release.get("html_url") != html_url:
        raise CanaryError("official prerelease page URL is invalid")

    document: dict[str, Any] = {
        "schema": SCHEMA,
        "repository": REPOSITORY,
        "release": {
            "id": release["id"],
            "tag": tag,
            "version_name": version_name,
            "published_at": release["published_at"],
            "html_url": html_url,
        },
        "asset": {
            "id": _positive_int(asset.get("id"), "asset ID"),
            "name": expected_asset_name,
            "url": expected_url,
            "sha256": digest[7:],
            "size_bytes": size,
        },
        "signing_certificate_sha256": OFFICIAL_CERT_SHA256,
    }
    document["payload_sha256"] = hashlib.sha256(_canonical_payload(document)).hexdigest()
    validate_manifest(document)
    return document


def validate_manifest(raw: object) -> dict[str, Any]:
    document = _exact(
        raw,
        {
            "schema",
            "repository",
            "release",
            "asset",
            "signing_certificate_sha256",
            "payload_sha256",
        },
        "canary manifest",
    )
    if document["schema"] != SCHEMA or document["repository"] != REPOSITORY:
        raise CanaryError("canary manifest identity is unsupported")
    if document["signing_certificate_sha256"] != OFFICIAL_CERT_SHA256:
        raise CanaryError("canary signing certificate differs from the official pin")
    if document["payload_sha256"] != hashlib.sha256(_canonical_payload(document)).hexdigest():
        raise CanaryError("canary manifest payload hash mismatch")
    release = _exact(document["release"], {"id", "tag", "version_name", "published_at", "html_url"}, "release")
    asset = _exact(document["asset"], {"id", "name", "url", "sha256", "size_bytes"}, "asset")
    _positive_int(release["id"], "release ID")
    _positive_int(asset["id"], "asset ID")
    match = TAG.fullmatch(release["tag"]) if isinstance(release["tag"], str) else None
    if match is None or release["version_name"] != match.group("version"):
        raise CanaryError("canary release tag and version name differ")
    if not isinstance(release["published_at"], str) or TIMESTAMP.fullmatch(release["published_at"]) is None:
        raise CanaryError("canary publication timestamp is invalid")
    expected_name = f"variant-abi-AnkiDroid-{release['version_name']}-x86_64.apk"
    expected_url = f"https://github.com/{REPOSITORY}/releases/download/{release['tag']}/{expected_name}"
    expected_html = f"https://github.com/{REPOSITORY}/releases/tag/{release['tag']}"
    if release["html_url"] != expected_html or asset["name"] != expected_name or asset["url"] != expected_url:
        raise CanaryError("canary release or asset URL identity is invalid")
    if not isinstance(asset["sha256"], str) or SHA256.fullmatch(asset["sha256"]) is None:
        raise CanaryError("canary asset SHA-256 is invalid")
    size = _positive_int(asset["size_bytes"], "asset size")
    if not 1024 * 1024 <= size <= MAX_APK_BYTES:
        raise CanaryError("canary asset size is outside the safety bound")
    return document


def _run(command: list[str]) -> str:
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        raise CanaryError(f"command failed: {' '.join(command)}")
    return result.stdout.replace("\r", "").strip()


def verify_apk(
    manifest_path: Path,
    apk_path: Path,
    apkanalyzer: str,
    apksigner: str,
) -> dict[str, Any]:
    manifest = validate_manifest(_load(manifest_path))
    apk = apk_path.resolve(strict=True)
    asset = manifest["asset"]
    release = manifest["release"]
    if apk.stat().st_size != asset["size_bytes"] or _sha256(apk) != asset["sha256"]:
        raise CanaryError("downloaded prerelease APK differs from the resolved GitHub asset")
    application_id = _run([apkanalyzer, "manifest", "application-id", str(apk)])
    version_name = _run([apkanalyzer, "manifest", "version-name", str(apk)])
    raw_version_code = _run([apkanalyzer, "manifest", "version-code", str(apk)])
    raw_min_sdk = _run([apkanalyzer, "manifest", "min-sdk", str(apk)])
    if application_id != "com.ichi2.anki" or version_name != release["version_name"]:
        raise CanaryError("prerelease APK package or version name is invalid")
    try:
        version_code = int(raw_version_code)
        min_sdk = int(raw_min_sdk)
    except ValueError as error:
        raise CanaryError("prerelease APK manifest versions are invalid") from error
    if version_code <= 0 or not 1 <= min_sdk <= 36:
        raise CanaryError("prerelease APK cannot run on the pinned API 36 canary lane")
    certificate_output = _run([apksigner, "verify", "--print-certs", str(apk)])
    certificates = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", certificate_output, re.MULTILINE)
    if certificates != [OFFICIAL_CERT_SHA256]:
        raise CanaryError("prerelease APK signing certificate is not the official AnkiDroid certificate")
    return {
        "application_id": application_id,
        "version_name": version_name,
        "version_code": version_code,
        "min_sdk": min_sdk,
        "sha256": asset["sha256"],
        "certificate_sha256": OFFICIAL_CERT_SHA256,
    }


def _write(path: Path, document: dict[str, Any]) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    staging = path.with_name(f".{path.name}.staging")
    staging.write_text(json.dumps(document, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    staging.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    resolve = subparsers.add_parser("resolve")
    resolve.add_argument("--releases", required=True, type=Path)
    resolve.add_argument("--manifest", required=True, type=Path)
    verify = subparsers.add_parser("verify-apk")
    verify.add_argument("--manifest", required=True, type=Path)
    verify.add_argument("--apk", required=True, type=Path)
    verify.add_argument("--apkanalyzer", required=True)
    verify.add_argument("--apksigner", required=True)
    field = subparsers.add_parser("field")
    field.add_argument("--manifest", required=True, type=Path)
    field.add_argument("--name", required=True, choices=("asset.url", "asset.sha256", "release.version_name"))
    args = parser.parse_args()
    try:
        if args.command == "resolve":
            document = resolve_release(_load(args.releases))
            _write(args.manifest, document)
            result: object = {"tag": document["release"]["tag"], "sha256": document["asset"]["sha256"]}
        elif args.command == "verify-apk":
            result = verify_apk(args.manifest, args.apk, args.apkanalyzer, args.apksigner)
        else:
            document = validate_manifest(_load(args.manifest))
            section, name = args.name.split(".", 1)
            print(document[section][name])
            return 0
    except (CanaryError, KeyError, OSError, TypeError, ValueError) as error:
        print(f"AnkiDroid prerelease canary: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
