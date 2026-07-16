#!/usr/bin/env python3
"""Verify the exact pinned AnkiconnectAndroid fallback APK without installing it."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO_ROOT / "third_party" / "ankiconnect-fallback" / "manifest.json"
CERTIFICATE_PREFIX = "Signer #1 certificate SHA-256 digest: "
EXPECTED_TOP_LEVEL_KEYS = {
    "schemaVersion",
    "decision",
    "artifact",
    "license",
    "probe",
    "source",
}
EXPECTED_ARTIFACT_KEYS = {
    "assetUrl",
    "certificateSha256",
    "compileSdk",
    "minSdk",
    "nativeAbis",
    "packageName",
    "sha256",
    "signingSchemes",
    "sizeBytes",
    "targetSdk",
    "versionCode",
    "versionName",
}


class VerificationError(RuntimeError):
    pass


def _load_manifest(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot read manifest: {error}") from error
    if not isinstance(payload, dict) or set(payload) != EXPECTED_TOP_LEVEL_KEYS:
        raise VerificationError("fallback manifest has an unexpected top-level shape")
    if payload.get("schemaVersion") != 1:
        raise VerificationError("fallback manifest schema is unsupported")
    artifact = payload.get("artifact")
    if not isinstance(artifact, dict) or set(artifact) != EXPECTED_ARTIFACT_KEYS:
        raise VerificationError("fallback artifact identity has an unexpected shape")
    url = artifact.get("assetUrl")
    if (
        not isinstance(url, str)
        or not url.startswith("https://github.com/KamWithK/AnkiconnectAndroid/releases/download/1.15/")
        or "latest" in url.casefold()
    ):
        raise VerificationError("fallback asset URL is not the immutable 1.15 release")
    for key in ("sha256", "certificateSha256"):
        if not isinstance(artifact.get(key), str) or not re.fullmatch(r"[0-9a-f]{64}", artifact[key]):
            raise VerificationError(f"fallback {key} is not a canonical SHA-256")
    if payload.get("decision") != {
        "fallbackRole": "s2-capability-probe-only",
        "productionPath": "ankidroid-content-provider",
        "secondProductionExporterRequired": False,
    }:
        raise VerificationError("fallback decision changed")
    return payload


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _run(*command: str) -> str:
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise VerificationError(f"command failed ({' '.join(command)}): {detail}")
    return result.stdout.strip()


def _manifest_value(apkanalyzer: str, apk: Path, field: str) -> str:
    return _run(apkanalyzer, "manifest", field, str(apk))


def verify(
    apk: Path,
    manifest_path: Path,
    apkanalyzer: str,
    apksigner: str,
) -> None:
    payload = _load_manifest(manifest_path)
    artifact = payload["artifact"]
    try:
        apk = apk.resolve(strict=True)
    except OSError as error:
        raise VerificationError(f"fallback APK is missing: {error}") from error
    if not apk.is_file():
        raise VerificationError("fallback APK is not a regular file")
    if apk.stat().st_size != artifact["sizeBytes"] or _sha256(apk) != artifact["sha256"]:
        raise VerificationError("fallback APK size or SHA-256 mismatch")

    try:
        with zipfile.ZipFile(apk) as archive:
            bad_member = archive.testzip()
            abis = sorted(
                {
                    name.split("/", 2)[1]
                    for name in archive.namelist()
                    if name.startswith("lib/") and name.count("/") >= 2
                }
            )
    except (OSError, zipfile.BadZipFile) as error:
        raise VerificationError(f"fallback APK is not a valid ZIP: {error}") from error
    if bad_member is not None:
        raise VerificationError(f"fallback APK member failed CRC: {bad_member}")
    if abis != artifact["nativeAbis"]:
        raise VerificationError("fallback APK native ABI inventory changed")

    actual_manifest = {
        "packageName": _manifest_value(apkanalyzer, apk, "application-id"),
        "versionName": _manifest_value(apkanalyzer, apk, "version-name"),
        "versionCode": int(_manifest_value(apkanalyzer, apk, "version-code")),
        "minSdk": int(_manifest_value(apkanalyzer, apk, "min-sdk")),
        "targetSdk": int(_manifest_value(apkanalyzer, apk, "target-sdk")),
    }
    for key, actual in actual_manifest.items():
        if actual != artifact[key]:
            raise VerificationError(f"fallback APK {key} mismatch")

    signer_output = _run(apksigner, "verify", "--verbose", "--print-certs", str(apk))
    certificate_lines = [
        line.removeprefix(CERTIFICATE_PREFIX)
        for line in signer_output.splitlines()
        if line.startswith(CERTIFICATE_PREFIX)
    ]
    if certificate_lines != [artifact["certificateSha256"]]:
        raise VerificationError("fallback APK signing certificate mismatch")
    verified_schemes = sorted(
        match.group(1)
        for line in signer_output.splitlines()
        if (match := re.fullmatch(r"Verified using (v[0-9.]+) scheme.*: true", line))
    )
    if verified_schemes != sorted(artifact["signingSchemes"]):
        raise VerificationError("fallback APK signing scheme inventory changed")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--apksigner", default="apksigner")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        verify(args.apk, args.manifest, args.apkanalyzer, args.apksigner)
    except (KeyError, TypeError, ValueError, VerificationError) as error:
        print(f"ankiconnect fallback: {error}", file=sys.stderr)
        return 1
    print("AnkiconnectAndroid fallback APK: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
