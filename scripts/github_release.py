#!/usr/bin/env python3
"""Fail-closed preparation and verification for GitHub APK prereleases."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import stat
import tarfile
import xml.etree.ElementTree as ElementTree
import zipfile


APPLICATION_ID = "com.ankiminer.android"
EXPECTED_ABI = "arm64-v8a"
EXPECTED_MIN_SDK = "26"
EXPECTED_TARGET_SDK = "36"
_VERSION_FILE = Path(__file__).resolve().parents[1] / "release/version.json"
try:
    _VERSION = json.loads(_VERSION_FILE.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as error:  # pragma: no cover
    raise RuntimeError(f"cannot read {_VERSION_FILE}: {error}") from error
if (
    not isinstance(_VERSION, dict)
    or set(_VERSION) != {"schema", "version_code", "version_name"}
    or _VERSION.get("schema") != 1
    or type(_VERSION.get("version_code")) is not int
    or not isinstance(_VERSION.get("version_name"), str)
):  # pragma: no cover
    raise RuntimeError(f"invalid release version contract: {_VERSION_FILE}")
EXPECTED_VERSION_NAME = str(_VERSION["version_name"])
EXPECTED_VERSION_CODE = str(_VERSION["version_code"])
RELEASE_CHANNEL = "github-apk-alpha"
RECORD_SCHEMA_VERSION = 2
APPROVAL_SCHEMA = "anki-miner-github-apk-approvals-v2"
ACCEPTANCE_SCHEMA = "anki-miner-s1a-arm64-acceptance-v2"
SOURCE_ARCHIVE_SCHEMA = "anki-miner-corresponding-source-v1"
SOURCE_MANIFEST_NAME = "anki-miner-source-manifest.json"
EXTERNAL_SOURCE_INVENTORY_SCHEMA = "anki-miner-external-source-inventory-v1"
EXTERNAL_SOURCE_INVENTORY_NAME = "anki-miner-external-source-inventory.json"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
SOURCE_META_DATA = "com.ankiminer.android.SOURCE_COMMIT"
CHANNEL_META_DATA = "com.ankiminer.android.RELEASE_CHANNEL"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+")
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")
MAX_ENTRIES = 100_000
MAX_ENTRY_SIZE = 1024 * 1024 * 1024
MAX_TOTAL_SIZE = 4 * 1024 * 1024 * 1024
MAX_CERTIFICATE_SIZE = 64 * 1024
MAX_SOURCE_ARCHIVE_ENTRIES = 1_000_000
MAX_SOURCE_ARCHIVE_ENTRY_SIZE = 8 * 1024 * 1024 * 1024
MAX_SOURCE_ARCHIVE_TOTAL_SIZE = 32 * 1024 * 1024 * 1024
MAX_SOURCE_MANIFEST_SIZE = 64 * 1024
MAX_SOURCE_INVENTORY_SIZE = 64 * 1024 * 1024
REQUIRED_SOURCE_PATHS = {
    "LICENSE",
    "SOURCE_AND_RELINKING.md",
    "app/src/main/python/.engine-sync-manifest.json",
    "gradle/verification-metadata.xml",
    "gradlew",
    "settings.gradle.kts",
    "tools/engine-sync/engine.lock",
    "tools/ffmpeg/sources.lock",
}
FORBIDDEN_ENTRY_FRAGMENTS = (
    "scaffold_probe",
    "runtime_dependencies_probe",
    "tokenizer_s1a_instrumented",
    "tokenizers1ainstrumentedtest",
    "tokenizer_s1b_instrumented",
    "s4_engine_smoke",
    "engine_golden_v2_instrumented",
    "enginegoldenv2instrumentedtest",
    "reading_golden_instrumented",
    "readinggoldeninstrumentedtest",
    "s4-engine-smoke-v1.json",
    "engine-v1.json",
    "engine-v2.json",
    "reading-v1.json",
)
REQUIRED_DECLARATIONS = {
    "android_developer_verification",
    "corresponding_source",
    "foreground_service_behavior",
    "privacy_policy",
    "relinking_installation_information",
    "security_review",
    "support_contact",
    "third_party_notices",
}
PLAY_ONLY_DECLARATIONS = {"play_data_safety", "play_foreground_service_submission"}
REQUIRED_GATES = {
    "accessibility_review",
    "ankidroid_provider_roundtrip",
    "emulator_compatibility_matrix",
    "host_health",
    "legal_distribution_review",
    "lifecycle_and_cancellation",
    "physical_s1a_acceptance",
    "private_repository_rehearsal",
    "reading_source_matrix",
    "resource_download_resume",
    "signed_apk_physical_smoke",
    "video_mining_acceptance",
}
APPROVAL_TIME_PATTERN = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")


class ReleaseError(RuntimeError):
    """A candidate violates the GitHub APK release contract."""


def _run(*command: str, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=cwd,
        check=False,
        capture_output=True,
        text=True,
    )
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ReleaseError(f"command failed ({' '.join(command)}): {detail}")
    return result


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _safe_zip_name(raw_name: str) -> PurePosixPath:
    if not raw_name or "\x00" in raw_name or "\\" in raw_name:
        raise ReleaseError(f"unsafe APK entry: {raw_name!r}")
    normalized = raw_name[:-1] if raw_name.endswith("/") else raw_name
    parts = normalized.split("/")
    if (
        not normalized
        or normalized.startswith("/")
        or any(part in {"", ".", ".."} for part in parts)
    ):
        raise ReleaseError(f"unsafe APK entry: {raw_name!r}")
    return PurePosixPath(normalized)


def _is_signature_entry(name: PurePosixPath) -> bool:
    if len(name.parts) != 2 or name.parts[0].upper() != "META-INF":
        return False
    basename = name.name.upper()
    return basename == "MANIFEST.MF" or basename.endswith(SIGNATURE_SUFFIXES)


def payload_inventory(apk: Path) -> tuple[str, list[dict[str, object]], list[str]]:
    """Hash logical ZIP payload, excluding only standard APK v1 signature entries."""
    apk = apk.resolve(strict=True)
    if not apk.is_file() or apk.suffix != ".apk":
        raise ReleaseError("artifact must be an APK file")
    inventory: list[dict[str, object]] = []
    names: set[str] = set()
    abis: set[str] = set()
    total_size = 0
    try:
        with zipfile.ZipFile(apk) as archive:
            entries = archive.infolist()
            if not entries or len(entries) > MAX_ENTRIES:
                raise ReleaseError("APK entry count is empty or excessive")
            for info in entries:
                path = _safe_zip_name(info.filename)
                name = path.as_posix()
                if name in names:
                    raise ReleaseError(f"duplicate APK entry: {name}")
                names.add(name)
                if info.is_dir():
                    continue
                if info.file_size < 0 or info.file_size > MAX_ENTRY_SIZE:
                    raise ReleaseError(f"APK entry is too large: {name}")
                total_size += info.file_size
                if total_size > MAX_TOTAL_SIZE:
                    raise ReleaseError("APK expanded payload is too large")
                if len(path.parts) >= 3 and path.parts[0] == "lib":
                    abis.add(path.parts[1])
                lowered = name.casefold()
                if "unidic" in lowered:
                    raise ReleaseError(f"base APK contains forbidden UniDic payload: {name}")
                if any(fragment in lowered for fragment in FORBIDDEN_ENTRY_FRAGMENTS):
                    raise ReleaseError(f"APK contains a debug/test payload: {name}")
                if _is_signature_entry(path):
                    continue
                digest = hashlib.sha256()
                with archive.open(info) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(block)
                inventory.append(
                    {"name": name, "sha256": digest.hexdigest(), "size": info.file_size}
                )
    except (OSError, zipfile.BadZipFile) as error:
        raise ReleaseError(f"cannot inspect APK: {error}") from error
    inventory.sort(key=lambda item: str(item["name"]))
    digest = hashlib.sha256(_canonical_json(inventory)).hexdigest()
    return digest, inventory, sorted(abis)


def _manifest_value(apk: Path, field: str) -> str:
    return _run("apkanalyzer", "manifest", field, str(apk)).stdout.strip()


def _manifest_identity(apk: Path) -> dict[str, str]:
    raw = _run("apkanalyzer", "manifest", "print", str(apk)).stdout
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError as error:
        raise ReleaseError(f"apkanalyzer returned invalid manifest XML: {error}") from error
    application = root.find("application")
    if application is None:
        raise ReleaseError("APK manifest has no application")
    if application.get(f"{{{ANDROID_NS}}}debuggable") == "true":
        raise ReleaseError("release APK is debuggable")
    if application.get(f"{{{ANDROID_NS}}}testOnly") == "true":
        raise ReleaseError("release APK is test-only")
    metadata: dict[str, str] = {}
    for element in application.findall("meta-data"):
        name = element.get(f"{{{ANDROID_NS}}}name")
        value = element.get(f"{{{ANDROID_NS}}}value")
        if name in {SOURCE_META_DATA, CHANNEL_META_DATA} and value is not None:
            if name in metadata:
                raise ReleaseError(f"duplicate release metadata: {name}")
            metadata[name] = value
    if set(metadata) != {SOURCE_META_DATA, CHANNEL_META_DATA}:
        raise ReleaseError("release source/channel metadata is missing")
    return {
        "application_id": _manifest_value(apk, "application-id"),
        "version_name": _manifest_value(apk, "version-name"),
        "version_code": _manifest_value(apk, "version-code"),
        "min_sdk": _manifest_value(apk, "min-sdk"),
        "target_sdk": _manifest_value(apk, "target-sdk"),
        "source_commit": metadata[SOURCE_META_DATA],
        "release_channel": metadata[CHANNEL_META_DATA],
    }


def _signature_fingerprint(apk: Path) -> str:
    result = _run(
        "apksigner",
        "verify",
        "--verbose",
        "--print-certs",
        "-Werr",
        "--min-sdk-version",
        "26",
        "--max-sdk-version",
        "36",
        str(apk),
    )
    pattern = re.compile(
        r"Signer #([1-9][0-9]*) certificate SHA-256 digest: ([0-9A-Fa-f]{64})"
    )
    matches = [
        (int(match.group(1)), match.group(2).casefold())
        for line in result.stdout.splitlines()
        if (match := pattern.fullmatch(line.strip())) is not None
    ]
    if len(matches) != 1 or matches[0][0] != 1:
        raise ReleaseError("APK must have exactly one signing certificate")
    return matches[0][1]


def _validate_single_certificate_pem(payload: bytes) -> None:
    if not payload or len(payload) > MAX_CERTIFICATE_SIZE:
        raise ReleaseError("public signing certificate size is invalid")
    stripped = payload.strip(b" \t\r\n")
    lines = stripped.splitlines()
    if (
        len(lines) < 3
        or lines[0] != b"-----BEGIN CERTIFICATE-----"
        or lines[-1] != b"-----END CERTIFICATE-----"
        or stripped.count(b"-----BEGIN CERTIFICATE-----") != 1
        or stripped.count(b"-----END CERTIFICATE-----") != 1
    ):
        raise ReleaseError("certificate must contain exactly one public X.509 PEM block")
    body_lines = lines[1:-1]
    if any(
        re.fullmatch(rb"[A-Za-z0-9+/]{1,64}={0,2}", line) is None
        for line in body_lines
    ):
        raise ReleaseError("public signing certificate PEM body is malformed")
    body = b"".join(body_lines)
    try:
        decoded = base64.b64decode(body, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ReleaseError("public signing certificate PEM body is malformed") from error
    if not decoded:
        raise ReleaseError("public signing certificate PEM body is empty")


def _canonical_certificate(certificate: Path) -> tuple[bytes, str]:
    certificate = certificate.resolve(strict=True)
    try:
        payload = certificate.read_bytes()
    except OSError as error:
        raise ReleaseError(f"cannot read public signing certificate: {error}") from error
    _validate_single_certificate_pem(payload)
    canonical_text = _run(
        "openssl",
        "x509",
        "-in",
        str(certificate),
        "-outform",
        "PEM",
    ).stdout
    try:
        canonical = canonical_text.encode("ascii")
    except UnicodeEncodeError as error:  # pragma: no cover - OpenSSL contract
        raise ReleaseError("OpenSSL returned a non-ASCII certificate") from error
    _validate_single_certificate_pem(canonical)
    result = _run(
        "openssl",
        "x509",
        "-in",
        str(certificate),
        "-noout",
        "-fingerprint",
        "-sha256",
    ).stdout.strip()
    prefix = "sha256 Fingerprint="
    if not result.casefold().startswith(prefix.casefold()):
        raise ReleaseError("cannot read the public signing certificate fingerprint")
    fingerprint = result.split("=", 1)[1].replace(":", "").casefold()
    if SHA256_PATTERN.fullmatch(fingerprint) is None:
        raise ReleaseError("public signing certificate fingerprint is invalid")
    return canonical, fingerprint


def _certificate_fingerprint(certificate: Path) -> str:
    _canonical, fingerprint = _canonical_certificate(certificate)
    return fingerprint


def inspect_apk(
    apk: Path,
    *,
    expected_source: str,
    signed: bool,
    expected_certificate: str | None = None,
) -> dict[str, object]:
    if COMMIT_PATTERN.fullmatch(expected_source) is None:
        raise ReleaseError("expected source must be an exact lowercase Git commit")
    apk = apk.resolve(strict=True)
    payload_digest, _inventory, abis = payload_inventory(apk)
    if abis != [EXPECTED_ABI]:
        raise ReleaseError(f"release APK ABI set is {abis}, expected {[EXPECTED_ABI]}")
    manifest = _manifest_identity(apk)
    expected_manifest = {
        "application_id": APPLICATION_ID,
        "version_name": EXPECTED_VERSION_NAME,
        "version_code": EXPECTED_VERSION_CODE,
        "min_sdk": EXPECTED_MIN_SDK,
        "target_sdk": EXPECTED_TARGET_SDK,
        "source_commit": expected_source,
        "release_channel": RELEASE_CHANNEL,
    }
    if manifest != expected_manifest:
        differing = sorted(
            key for key, value in expected_manifest.items() if manifest.get(key) != value
        )
        raise ReleaseError(f"release APK manifest identity differs: {differing}")
    signature_check = _run("apksigner", "verify", str(apk), check=False)
    certificate = None
    if signed:
        if signature_check.returncode != 0:
            raise ReleaseError("release APK is not signed")
        certificate = _signature_fingerprint(apk)
        if expected_certificate is not None and certificate != expected_certificate.casefold():
            raise ReleaseError("release APK signing certificate is unexpected")
    elif signature_check.returncode == 0:
        raise ReleaseError("unsigned candidate unexpectedly has a valid signature")
    return {
        **manifest,
        "abi": EXPECTED_ABI,
        "apk_sha256": _sha256(apk),
        "apk_size": apk.stat().st_size,
        "payload_inventory_sha256": payload_digest,
        "signing_certificate_sha256": certificate,
    }


def _load_acceptance(path: Path, source_commit: str) -> dict[str, object]:
    path = path.resolve(strict=True)
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseError(f"cannot read physical acceptance receipt: {error}") from error
    if not isinstance(document, dict) or document.get("schema") != ACCEPTANCE_SCHEMA:
        raise ReleaseError("physical acceptance receipt is not schema v2")
    source = document.get("source")
    if not isinstance(source, dict) or source.get("commit") != source_commit:
        raise ReleaseError("physical acceptance receipt belongs to another source commit")
    payload_hash = document.get("payload_sha256")
    unsigned = {key: value for key, value in document.items() if key != "payload_sha256"}
    if payload_hash != hashlib.sha256(_canonical_json(unsigned)).hexdigest():
        raise ReleaseError("physical acceptance receipt payload hash is invalid")
    publication = document.get("publication")
    artifact = document.get("artifact")
    device = document.get("device")
    thresholds = document.get("thresholds")
    representative = document.get("representative_mining")
    measurements = document.get("measurements")
    if not all(
        isinstance(value, dict)
        for value in (publication, artifact, device, thresholds, representative, measurements)
    ):
        raise ReleaseError("physical acceptance receipt summary is incomplete")
    return {
        "schema": ACCEPTANCE_SCHEMA,
        "sha256": _sha256(path),
        "payload_sha256": payload_hash,
        "source_commit": source_commit,
        "source_tree": source.get("tree"),
        "publication_build_key": publication.get("build_key"),
        "accepted_artifact": {
            "filename": artifact.get("filename"),
            "sha256": artifact.get("sha256"),
            "size": artifact.get("size_bytes"),
        },
        "device_class": {
            "manufacturer": device.get("manufacturer"),
            "model": device.get("model"),
            "api_level": device.get("api_level"),
            "abi": device.get("abi"),
            "page_size_bytes": device.get("page_size_bytes"),
            "total_memory_bytes": device.get("total_memory_bytes"),
        },
        "cold_init_ms": measurements.get("cold_init_ms"),
        "representative_mining": {
            "workload_id": representative.get("workload_id"),
            "selected_count": representative.get("selected_count"),
            "cards_created": representative.get("cards_created"),
            "elapsed_ms": representative.get("elapsed_ms"),
            "peak_rss_bytes": representative.get("peak_rss_bytes"),
            "completed": representative.get("completed"),
        },
        "thresholds": thresholds,
    }


def _validate_approval_entry(
    raw: object,
    *,
    label: str,
    required: bool,
    allow_not_run: bool = False,
) -> dict[str, object]:
    expected_keys = {
        "required",
        "outcome",
        "procedure",
        "completed_utc",
        "operator",
        "evidence_sha256",
    }
    if not isinstance(raw, dict) or set(raw) != expected_keys:
        raise ReleaseError(f"approval entry fields differ: {label}")
    expected_outcomes = (
        {"passed", "not_run"}
        if required and allow_not_run
        else {"passed" if required else "not_applicable_to_channel"}
    )
    if raw.get("required") is not required or raw.get("outcome") not in expected_outcomes:
        raise ReleaseError(f"approval entry has not passed for this channel: {label}")
    procedure = raw.get("procedure")
    operator = raw.get("operator")
    completed = raw.get("completed_utc")
    evidence = raw.get("evidence_sha256")
    if (
        not isinstance(procedure, str)
        or not procedure
        or procedure.startswith("/")
        or "\\" in procedure
        or not isinstance(operator, str)
        or not re.fullmatch(r"[A-Za-z0-9_.@-]{1,80}", operator)
        or not isinstance(completed, str)
        or APPROVAL_TIME_PATTERN.fullmatch(completed) is None
        or not isinstance(evidence, str)
        or SHA256_PATTERN.fullmatch(evidence) is None
    ):
        raise ReleaseError(f"approval entry evidence is invalid: {label}")
    return dict(raw)


def _validate_approvals(
    raw: object,
    *,
    tag: str,
    source_commit: str,
    signed_apk_sha256: str,
    allow_private_rehearsal_pending: bool = False,
) -> dict[str, object]:
    expected_top = {
        "schema",
        "tag",
        "source_commit",
        "signed_apk_sha256",
        "declarations",
        "gates",
    }
    if not isinstance(raw, dict) or set(raw) != expected_top:
        raise ReleaseError("release approval document fields differ")
    if (
        raw.get("schema") != APPROVAL_SCHEMA
        or raw.get("tag") != tag
        or raw.get("source_commit") != source_commit
        or raw.get("signed_apk_sha256") != signed_apk_sha256
        or SHA256_PATTERN.fullmatch(signed_apk_sha256) is None
    ):
        raise ReleaseError("release approvals belong to another tag, source, or signed APK")
    declarations = raw.get("declarations")
    gates = raw.get("gates")
    declaration_names = REQUIRED_DECLARATIONS | PLAY_ONLY_DECLARATIONS
    if not isinstance(declarations, dict) or set(declarations) != declaration_names:
        raise ReleaseError("release declaration set differs")
    if not isinstance(gates, dict) or set(gates) != REQUIRED_GATES:
        raise ReleaseError("release gate set differs")
    return {
        "schema": APPROVAL_SCHEMA,
        "tag": tag,
        "source_commit": source_commit,
        "signed_apk_sha256": signed_apk_sha256,
        "declarations": {
            name: _validate_approval_entry(
                declarations[name],
                label=f"declaration {name}",
                required=name in REQUIRED_DECLARATIONS,
            )
            for name in sorted(declarations)
        },
        "gates": {
            name: _validate_approval_entry(
                gates[name],
                label=f"gate {name}",
                required=True,
                allow_not_run=(
                    allow_private_rehearsal_pending
                    and name == "private_repository_rehearsal"
                ),
            )
            for name in sorted(gates)
        },
    }


def _load_approvals(
    path: Path,
    *,
    tag: str,
    source_commit: str,
    signed_apk_sha256: str,
    allow_private_rehearsal_pending: bool = False,
) -> dict[str, object]:
    try:
        raw = json.loads(path.resolve(strict=True).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseError(f"cannot read release approvals: {error}") from error
    return _validate_approvals(
        raw,
        tag=tag,
        source_commit=source_commit,
        signed_apk_sha256=signed_apk_sha256,
        allow_private_rehearsal_pending=allow_private_rehearsal_pending,
    )


def _source_identity(repo_root: Path, tag: str) -> dict[str, str]:
    repo_root = repo_root.resolve(strict=True)
    status = _run(
        "git", "status", "--porcelain=v2", "--untracked-files=all", cwd=repo_root
    ).stdout
    if status:
        raise ReleaseError("release checkout must be clean")
    head = _run("git", "rev-parse", "HEAD", cwd=repo_root).stdout.strip()
    tree = _run("git", "rev-parse", "HEAD^{tree}", cwd=repo_root).stdout.strip()
    tag_commit = _run("git", "rev-list", "-n", "1", tag, cwd=repo_root).stdout.strip()
    tag_type = _run("git", "cat-file", "-t", tag, cwd=repo_root).stdout.strip()
    if tag_type != "tag" or tag_commit != head:
        raise ReleaseError("release tag must be annotated and point at HEAD")
    return {"commit": head, "tree": tree}


def _copy_asset(source: Path, destination: Path) -> dict[str, object]:
    source = source.resolve(strict=True)
    if not source.is_file():
        raise ReleaseError(f"release asset is not a file: {source}")
    shutil.copyfile(source, destination)
    return {"filename": destination.name, "sha256": _sha256(destination), "size": destination.stat().st_size}


def _write_asset(payload: bytes, destination: Path) -> dict[str, object]:
    destination.write_bytes(payload)
    return {
        "filename": destination.name,
        "sha256": _sha256(destination),
        "size": destination.stat().st_size,
    }


def _publication_identity(path: Path, *, schema: int, label: str) -> dict[str, object]:
    path = path.resolve(strict=True)
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseError(f"cannot read {label} manifest: {error}") from error
    if (
        not isinstance(document, dict)
        or document.get("schema") != schema
        or not isinstance(document.get("build_key"), str)
        or SHA256_PATTERN.fullmatch(str(document["build_key"])) is None
    ):
        raise ReleaseError(f"{label} manifest identity is invalid")
    return {
        "build_key": document["build_key"],
        "sha256": _sha256(path),
    }


def _tracked_tree(repo_root: Path, tree: str) -> dict[str, tuple[str, str]]:
    repo_root = repo_root.resolve(strict=True)
    if COMMIT_PATTERN.fullmatch(tree) is None:
        raise ReleaseError("tracked source tree identity is invalid")
    object_format = _run(
        "git", "rev-parse", "--show-object-format", cwd=repo_root
    ).stdout.strip()
    if object_format != "sha1":
        raise ReleaseError(f"unsupported Git object format: {object_format}")
    if _run("git", "cat-file", "-t", tree, cwd=repo_root).stdout.strip() != "tree":
        raise ReleaseError("recorded source tree is not a Git tree")
    output = _run(
        "git", "ls-tree", "-rz", "--full-tree", tree, cwd=repo_root
    ).stdout
    inventory: dict[str, tuple[str, str]] = {}
    for entry in output.split("\x00"):
        if not entry:
            continue
        metadata, separator, raw_path = entry.partition("\t")
        fields = metadata.split()
        if not separator or len(fields) != 3:
            raise ReleaseError("cannot parse tracked source tree")
        mode, object_type, oid = fields
        path = _safe_tar_name(raw_path).as_posix()
        if (
            object_type != "blob"
            or mode not in {"100644", "100755"}
            or COMMIT_PATTERN.fullmatch(oid) is None
            or path in inventory
            or PurePosixPath(path).name
            in {SOURCE_MANIFEST_NAME, EXTERNAL_SOURCE_INVENTORY_NAME}
        ):
            raise ReleaseError(f"unsupported tracked source entry: {raw_path!r}")
        inventory[path] = (mode, oid)
    if not inventory:
        raise ReleaseError("tracked source tree is empty")
    return inventory


def _tracked_tree_inventory_sha256(
    inventory: dict[str, tuple[str, str]],
) -> str:
    payload = [
        {"blob_oid": oid, "mode": mode, "path": path}
        for path, (mode, oid) in sorted(inventory.items())
    ]
    return hashlib.sha256(_canonical_json(payload)).hexdigest()


def _tar_git_blob_oid(archive: tarfile.TarFile, member: tarfile.TarInfo) -> str:
    stream = archive.extractfile(member)
    if stream is None:  # pragma: no cover - tarfile contract
        raise ReleaseError(f"cannot read tracked source entry: {member.name}")
    digest = hashlib.sha1()  # noqa: S324 - Git SHA-1 object identity, not trust alone.
    digest.update(f"blob {member.size}\0".encode("ascii"))
    remaining = member.size
    while remaining:
        block = stream.read(min(1024 * 1024, remaining))
        if not block:
            raise ReleaseError(f"tracked source entry is truncated: {member.name}")
        digest.update(block)
        remaining -= len(block)
    if stream.read(1):  # pragma: no cover - tarfile bounds the member stream
        raise ReleaseError(f"tracked source entry exceeds its declared size: {member.name}")
    stream.close()
    return digest.hexdigest()


def _tar_file_sha256(archive: tarfile.TarFile, member: tarfile.TarInfo) -> str:
    stream = archive.extractfile(member)
    if stream is None:  # pragma: no cover - tarfile contract
        raise ReleaseError(f"cannot read source inventory entry: {member.name}")
    digest = hashlib.sha256()
    remaining = member.size
    while remaining:
        block = stream.read(min(1024 * 1024, remaining))
        if not block:
            raise ReleaseError(f"source inventory entry is truncated: {member.name}")
        digest.update(block)
        remaining -= len(block)
    stream.close()
    return digest.hexdigest()


def _external_source_inventory_payload(entries: list[dict[str, object]]) -> bytes:
    document = {
        "schema": EXTERNAL_SOURCE_INVENTORY_SCHEMA,
        "entries": sorted(entries, key=lambda entry: str(entry["path"])),
    }
    return (
        json.dumps(document, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    ).encode("utf-8")


def _filesystem_source_inventory(
    source_root: Path,
    tracked_tree: dict[str, tuple[str, str]],
) -> list[dict[str, object]]:
    source_root = source_root.resolve(strict=True)
    if not source_root.is_dir():
        raise ReleaseError("corresponding-source staging root is not a directory")
    tracked_seen: set[str] = set()
    external: list[dict[str, object]] = []
    reserved = {SOURCE_MANIFEST_NAME, EXTERNAL_SOURCE_INVENTORY_NAME}
    for path in sorted(source_root.rglob("*"), key=lambda candidate: candidate.as_posix()):
        relative = path.relative_to(source_root).as_posix()
        safe_relative = _safe_tar_name(relative)
        if safe_relative.name in reserved:
            raise ReleaseError(f"reserved source metadata path already exists: {relative}")
        metadata = path.stat(follow_symlinks=False)
        if stat.S_ISDIR(metadata.st_mode):
            continue
        permissions = stat.S_IMODE(metadata.st_mode)
        expected = tracked_tree.get(relative)
        if expected is not None:
            expected_mode, expected_oid = expected
            expected_permissions = 0o755 if expected_mode == "100755" else 0o644
            if not stat.S_ISREG(metadata.st_mode) or permissions != expected_permissions:
                raise ReleaseError(f"tracked source mode or type differs: {relative}")
            with path.open("rb") as stream:
                digest = hashlib.sha1()  # noqa: S324 - Git object identity.
                digest.update(f"blob {metadata.st_size}\0".encode("ascii"))
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
            if digest.hexdigest() != expected_oid:
                raise ReleaseError(f"tracked source bytes differ: {relative}")
            tracked_seen.add(relative)
            continue
        if stat.S_ISLNK(metadata.st_mode):
            target = os.readlink(path)
            _safe_tar_link(
                PurePosixPath("source-root") / safe_relative,
                target,
                symbolic=True,
            )
            external.append(
                {
                    "mode": f"{permissions:04o}",
                    "path": relative,
                    "target": target,
                    "type": "symlink",
                }
            )
        elif stat.S_ISREG(metadata.st_mode):
            external.append(
                {
                    "mode": f"{permissions:04o}",
                    "path": relative,
                    "sha256": _sha256(path),
                    "size": metadata.st_size,
                    "type": "file",
                }
            )
        else:
            raise ReleaseError(f"unsupported corresponding-source staging entry: {relative}")
    if tracked_seen != set(tracked_tree):
        missing = sorted(set(tracked_tree) - tracked_seen)
        raise ReleaseError(f"corresponding-source staging tree is incomplete: {missing[:20]}")
    if not external:
        raise ReleaseError("corresponding-source external source inventory is empty")
    return external


def _source_manifest(
    *,
    source: dict[str, str],
    engine_revision: str,
    runtime_build_key: object,
    s1a_build_key: object,
    tracked_tree_inventory_sha256: object,
    external_source_inventory_sha256: object | None = None,
) -> dict[str, str]:
    document = {
        "schema": SOURCE_ARCHIVE_SCHEMA,
        "source_commit": source["commit"],
        "source_tree": source["tree"],
        "engine_revision": engine_revision,
        "runtime_wheel_build_key": runtime_build_key,
        "s1a_publication_build_key": s1a_build_key,
        "tracked_tree_inventory_sha256": tracked_tree_inventory_sha256,
    }
    if external_source_inventory_sha256 is not None:
        document["external_source_inventory_sha256"] = external_source_inventory_sha256
    if (
        not all(isinstance(value, str) for value in document.values())
        or COMMIT_PATTERN.fullmatch(document["source_commit"]) is None
        or COMMIT_PATTERN.fullmatch(document["source_tree"]) is None
        or COMMIT_PATTERN.fullmatch(document["engine_revision"]) is None
        or SHA256_PATTERN.fullmatch(document["runtime_wheel_build_key"]) is None
        or SHA256_PATTERN.fullmatch(document["s1a_publication_build_key"]) is None
        or SHA256_PATTERN.fullmatch(document["tracked_tree_inventory_sha256"]) is None
        or (
            external_source_inventory_sha256 is not None
            and SHA256_PATTERN.fullmatch(
                document["external_source_inventory_sha256"]
            )
            is None
        )
    ):
        raise ReleaseError("corresponding-source manifest identity is invalid")
    return document


def _safe_tar_name(raw_name: str) -> PurePosixPath:
    if not raw_name or "\x00" in raw_name or "\\" in raw_name:
        raise ReleaseError(f"unsafe corresponding-source entry: {raw_name!r}")
    normalized = raw_name[:-1] if raw_name.endswith("/") else raw_name
    parts = normalized.split("/")
    if (
        not normalized
        or normalized.startswith("/")
        or any(part in {"", ".", ".."} for part in parts)
    ):
        raise ReleaseError(f"unsafe corresponding-source entry: {raw_name!r}")
    return PurePosixPath(normalized)


def _safe_tar_link(member_path: PurePosixPath, raw_target: str, *, symbolic: bool) -> None:
    if not raw_target or "\x00" in raw_target or "\\" in raw_target:
        raise ReleaseError(f"unsafe corresponding-source link: {raw_target!r}")
    target = PurePosixPath(raw_target)
    if target.is_absolute():
        raise ReleaseError(f"unsafe corresponding-source link: {raw_target!r}")
    parts = list(member_path.parent.parts if symbolic else ())
    for part in target.parts:
        if part in {"", "."}:
            continue
        if part == "..":
            if not parts:
                raise ReleaseError(f"unsafe corresponding-source link: {raw_target!r}")
            parts.pop()
        else:
            parts.append(part)
    if not parts or parts[0] != member_path.parts[0]:
        raise ReleaseError(f"corresponding-source link leaves its archive root: {raw_target!r}")


def _inspect_source_archive(
    archive_path: Path,
    expected_manifest: dict[str, str],
    tracked_tree: dict[str, tuple[str, str]],
) -> dict[str, str]:
    archive_path = archive_path.resolve(strict=True)
    if not archive_path.is_file() or not archive_path.name.endswith(".tar.zst"):
        raise ReleaseError("corresponding source must be a .tar.zst file")
    try:
        process = subprocess.Popen(
            ["zstd", "--decompress", "--stdout", "--quiet", "--", str(archive_path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise ReleaseError(f"cannot start corresponding-source decompression: {error}") from error
    assert process.stdout is not None
    assert process.stderr is not None
    names: set[str] = set()
    roots: set[str] = set()
    manifest_payload: bytes | None = None
    inventory_payload: bytes | None = None
    tracked_seen: set[str] = set()
    external_entries: list[dict[str, object]] = []
    total_size = 0
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for index, member in enumerate(archive, start=1):
                if index > MAX_SOURCE_ARCHIVE_ENTRIES:
                    raise ReleaseError("corresponding-source entry count is excessive")
                path = _safe_tar_name(member.name)
                name = path.as_posix()
                if name in names:
                    raise ReleaseError(f"duplicate corresponding-source entry: {name}")
                names.add(name)
                roots.add(path.parts[0])
                if member.size < 0 or member.size > MAX_SOURCE_ARCHIVE_ENTRY_SIZE:
                    raise ReleaseError(f"corresponding-source entry is too large: {name}")
                total_size += member.size
                if total_size > MAX_SOURCE_ARCHIVE_TOTAL_SIZE:
                    raise ReleaseError("corresponding-source expanded payload is too large")
                if member.islnk():
                    raise ReleaseError(f"hard links are not allowed in corresponding source: {name}")
                if member.issym():
                    _safe_tar_link(path, member.linkname, symbolic=True)
                elif not (member.isdir() or member.isreg()):
                    raise ReleaseError(f"unsupported corresponding-source entry type: {name}")
                if len(path.parts) == 1:
                    if not member.isdir():
                        raise ReleaseError("corresponding-source root must be a directory")
                    continue
                relative = "/".join(path.parts[1:])
                expected_tracked = tracked_tree.get(relative)
                if expected_tracked is not None:
                    expected_mode, expected_oid = expected_tracked
                    expected_permissions = 0o755 if expected_mode == "100755" else 0o644
                    if not member.isreg() or member.mode & 0o777 != expected_permissions:
                        raise ReleaseError(
                            f"tracked source mode or type differs: {relative}"
                        )
                    if _tar_git_blob_oid(archive, member) != expected_oid:
                        raise ReleaseError(f"tracked source bytes differ: {relative}")
                    tracked_seen.add(relative)
                elif path.name == SOURCE_MANIFEST_NAME:
                    if len(path.parts) != 2 or not member.isreg() or manifest_payload is not None:
                        raise ReleaseError("corresponding-source manifest placement is invalid")
                    if member.size > MAX_SOURCE_MANIFEST_SIZE:
                        raise ReleaseError("corresponding-source manifest is too large")
                    stream = archive.extractfile(member)
                    if stream is None:  # pragma: no cover - tarfile contract
                        raise ReleaseError("cannot read corresponding-source manifest")
                    manifest_payload = stream.read(MAX_SOURCE_MANIFEST_SIZE + 1)
                    stream.close()
                elif path.name == EXTERNAL_SOURCE_INVENTORY_NAME:
                    if len(path.parts) != 2 or not member.isreg() or inventory_payload is not None:
                        raise ReleaseError("external source inventory placement is invalid")
                    if member.size > MAX_SOURCE_INVENTORY_SIZE:
                        raise ReleaseError("external source inventory is too large")
                    stream = archive.extractfile(member)
                    if stream is None:  # pragma: no cover - tarfile contract
                        raise ReleaseError("cannot read external source inventory")
                    inventory_payload = stream.read(MAX_SOURCE_INVENTORY_SIZE + 1)
                    stream.close()
                elif member.isreg():
                    external_entries.append(
                        {
                            "mode": f"{member.mode & 0o777:04o}",
                            "path": relative,
                            "sha256": _tar_file_sha256(archive, member),
                            "size": member.size,
                            "type": "file",
                        }
                    )
                elif member.issym():
                    external_entries.append(
                        {
                            "mode": f"{member.mode & 0o777:04o}",
                            "path": relative,
                            "target": member.linkname,
                            "type": "symlink",
                        }
                    )
            for block in iter(lambda: process.stdout.read(1024 * 1024), b""):
                pass
        stderr = process.stderr.read().decode("utf-8", errors="replace").strip()
        returncode = process.wait()
        process.stdout.close()
        process.stderr.close()
        if returncode != 0:
            raise ReleaseError(
                f"cannot decompress corresponding-source archive: {stderr or returncode}"
            )
    except (OSError, tarfile.TarError) as error:
        process.kill()
        process.communicate()
        raise ReleaseError(f"cannot inspect corresponding-source archive: {error}") from error
    except BaseException:
        if process.poll() is None:
            process.kill()
        process.communicate()
        raise
    if len(roots) != 1:
        raise ReleaseError("corresponding-source archive must contain exactly one root")
    root = next(iter(roots))
    required_names = {f"{root}/{relative}" for relative in REQUIRED_SOURCE_PATHS}
    if not required_names.issubset(names):
        missing = sorted(required_names - names)
        raise ReleaseError(f"corresponding-source archive is incomplete: {missing}")
    if tracked_seen != set(tracked_tree):
        missing_tracked = sorted(set(tracked_tree) - tracked_seen)
        raise ReleaseError(
            "corresponding-source archive does not contain the exact tracked tree: "
            f"{missing_tracked[:20]}"
        )
    if f"{root}/release.json" in names:
        raise ReleaseError("final release.json must not be embedded in corresponding source")
    if manifest_payload is None:
        raise ReleaseError("corresponding-source archive has no identity manifest")
    if inventory_payload is None:
        raise ReleaseError("corresponding-source archive has no external source inventory")
    expected_inventory_payload = _external_source_inventory_payload(external_entries)
    if inventory_payload != expected_inventory_payload or not external_entries:
        raise ReleaseError("external source inventory differs from archive contents")
    try:
        manifest = json.loads(manifest_payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseError(f"corresponding-source manifest is invalid JSON: {error}") from error
    expected_manifest_keys = {
        "schema",
        "source_commit",
        "source_tree",
        "engine_revision",
        "runtime_wheel_build_key",
        "s1a_publication_build_key",
        "tracked_tree_inventory_sha256",
        "external_source_inventory_sha256",
    }
    if (
        not isinstance(manifest, dict)
        or set(manifest) != expected_manifest_keys
        or any(manifest.get(key) != value for key, value in expected_manifest.items())
        or manifest.get("external_source_inventory_sha256")
        != hashlib.sha256(inventory_payload).hexdigest()
    ):
        raise ReleaseError("corresponding-source manifest identity differs")
    return dict(manifest)


def write_source_manifest(args: argparse.Namespace) -> dict[str, str]:
    repo_root = Path(args.repo_root).resolve(strict=True)
    source = _source_identity(repo_root, args.tag)
    runtime = _publication_identity(
        Path(args.runtime_manifest), schema=1, label="runtime wheel"
    )
    s1a = _publication_identity(Path(args.s1a_manifest), schema=2, label="S1a wheel")
    engine_revision = (repo_root / "tools/engine-sync/engine.lock").read_text(
        encoding="utf-8"
    ).strip()
    tracked_tree = _tracked_tree(repo_root, source["tree"])
    source_root = Path(args.source_root).resolve(strict=True)
    output = Path(args.output).resolve()
    inventory_output = source_root / EXTERNAL_SOURCE_INVENTORY_NAME
    if output.parent != source_root or output.name != SOURCE_MANIFEST_NAME:
        raise ReleaseError(
            f"source manifest must be {SOURCE_MANIFEST_NAME} directly under the staging root"
        )
    if output.exists() or inventory_output.exists():
        raise ReleaseError("refusing to overwrite corresponding-source metadata")
    external_entries = _filesystem_source_inventory(source_root, tracked_tree)
    inventory_payload = _external_source_inventory_payload(external_entries)
    manifest = _source_manifest(
        source=source,
        engine_revision=engine_revision,
        runtime_build_key=runtime["build_key"],
        s1a_build_key=s1a["build_key"],
        tracked_tree_inventory_sha256=_tracked_tree_inventory_sha256(tracked_tree),
        external_source_inventory_sha256=hashlib.sha256(inventory_payload).hexdigest(),
    )
    try:
        inventory_output.write_bytes(inventory_payload)
        output.write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except BaseException:
        inventory_output.unlink(missing_ok=True)
        output.unlink(missing_ok=True)
        raise
    return manifest


def prepare_assets(args: argparse.Namespace) -> dict[str, object]:
    version = args.tag.removeprefix("v")
    if args.tag != f"v{version}" or VERSION_PATTERN.fullmatch(version) is None:
        raise ReleaseError("tag must be an alpha semantic version such as v0.1.0-alpha.1")
    if version != EXPECTED_VERSION_NAME:
        raise ReleaseError("tag differs from the configured app version")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        raise ReleaseError("repository must use owner/name syntax")
    source = _source_identity(Path(args.repo_root), args.tag)
    release_url = f"https://github.com/{args.repository}/releases/tag/{args.tag}"
    release_download_url = (
        f"https://github.com/{args.repository}/releases/download/{args.tag}"
    )
    repo_root = Path(args.repo_root).resolve(strict=True)
    runtime_manifest = Path(args.runtime_manifest).resolve(strict=True)
    s1a_manifest = Path(args.s1a_manifest).resolve(strict=True)
    acceptance_receipt = Path(args.acceptance_receipt).resolve(strict=True)
    accepted_debug_apk = Path(args.accepted_debug_apk).resolve(strict=True)
    golden = Path(args.golden).resolve(strict=True)
    _run(
        sys.executable,
        str(repo_root / "tools/wheels/s1a_acceptance.py"),
        "verify",
        "--receipt",
        str(acceptance_receipt),
        "--manifest",
        str(s1a_manifest),
        "--apk",
        str(accepted_debug_apk),
        "--repo-root",
        str(repo_root),
        "--golden",
        str(golden),
    )
    runtime_publication = _publication_identity(
        runtime_manifest, schema=1, label="runtime wheel"
    )
    s1a_publication = _publication_identity(
        s1a_manifest, schema=2, label="S1a wheel"
    )
    unsigned = inspect_apk(
        Path(args.unsigned_apk), expected_source=source["commit"], signed=False
    )
    certificate_path = Path(args.certificate)
    canonical_certificate, certificate_sha256 = _canonical_certificate(certificate_path)
    if certificate_sha256 != args.expected_certificate.casefold():
        raise ReleaseError("selected certificate is not the permanent signing identity")
    signed = inspect_apk(
        Path(args.signed_apk),
        expected_source=source["commit"],
        signed=True,
        expected_certificate=certificate_sha256,
    )
    if unsigned["payload_inventory_sha256"] != signed["payload_inventory_sha256"]:
        raise ReleaseError("signing changed the logical APK payload")
    acceptance = _load_acceptance(acceptance_receipt, source["commit"])
    if acceptance["publication_build_key"] != s1a_publication["build_key"]:
        raise ReleaseError("physical acceptance and S1a publication differ")
    engine_revision = (repo_root / "tools/engine-sync/engine.lock").read_text(
        encoding="utf-8"
    ).strip()
    if COMMIT_PATTERN.fullmatch(engine_revision) is None:
        raise ReleaseError("engine revision lock is invalid")
    tracked_tree = _tracked_tree(repo_root, source["tree"])
    source_manifest_identity = _source_manifest(
        source=source,
        engine_revision=engine_revision,
        runtime_build_key=runtime_publication["build_key"],
        s1a_build_key=s1a_publication["build_key"],
        tracked_tree_inventory_sha256=_tracked_tree_inventory_sha256(tracked_tree),
    )
    source_manifest = _inspect_source_archive(
        Path(args.corresponding_source), source_manifest_identity, tracked_tree
    )
    approvals = _load_approvals(
        Path(args.approvals),
        tag=args.tag,
        source_commit=source["commit"],
        signed_apk_sha256=str(signed["apk_sha256"]),
        allow_private_rehearsal_pending=args.private_rehearsal,
    )

    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ReleaseError("release output directory must be empty")
    apk_name = f"anki-miner-android-{version}-arm64-v8a.apk"
    source_name = f"anki-miner-android-{version}-corresponding-source.tar.zst"
    notices_name = f"anki-miner-android-{version}-notices.tar.zst"
    assets = {
        "apk": _copy_asset(Path(args.signed_apk), output / apk_name),
        "certificate": _write_asset(
            canonical_certificate, output / "app-signing-certificate.pem"
        ),
        "corresponding_source": _copy_asset(
            Path(args.corresponding_source), output / source_name
        ),
        "notices": _copy_asset(Path(args.notices), output / notices_name),
    }
    certificate_fingerprint_path = output / "app-signing-certificate.sha256"
    certificate_fingerprint_path.write_text(certificate_sha256 + "\n", encoding="ascii")
    assets["certificate_fingerprint"] = {
        "filename": certificate_fingerprint_path.name,
        "sha256": _sha256(certificate_fingerprint_path),
        "size": certificate_fingerprint_path.stat().st_size,
    }
    if args.redacted_evidence is not None:
        evidence_name = f"anki-miner-android-{version}-redacted-evidence.tar.zst"
        assets["redacted_evidence"] = _copy_asset(
            Path(args.redacted_evidence), output / evidence_name
        )
    asset_allowlist = sorted(
        [*(value["filename"] for value in assets.values()), "release.json", "SHA256SUMS"]
    )
    source_asset = assets["corresponding_source"]
    record: dict[str, object] = {
        "releaseSchemaVersion": RECORD_SCHEMA_VERSION,
        "channel": (
            "github-apk-private-rehearsal"
            if args.private_rehearsal
            else "github-apk-prerelease"
        ),
        "tag": args.tag,
        "releaseUrl": release_url,
        "versionName": version,
        "versionCode": int(EXPECTED_VERSION_CODE),
        "sourceCommit": source["commit"],
        "sourceTree": source["tree"],
        "engineRevision": engine_revision,
        "runtimeWheelBuildKey": runtime_publication["build_key"],
        "s1aPublicationBuildKey": s1a_publication["build_key"],
        "s1aAcceptance": acceptance,
        "artifacts": [
            {
                "filename": apk_name,
                "applicationId": APPLICATION_ID,
                "versionName": version,
                "versionCode": int(EXPECTED_VERSION_CODE),
                "minSdk": int(EXPECTED_MIN_SDK),
                "targetSdk": int(EXPECTED_TARGET_SDK),
                "abis": [EXPECTED_ABI],
                "unsignedSha256": unsigned["apk_sha256"],
                "signedSha256": signed["apk_sha256"],
                "signedSize": signed["apk_size"],
                "payloadInventoryBeforeSigning": unsigned["payload_inventory_sha256"],
                "payloadInventoryAfterSigning": signed["payload_inventory_sha256"],
                "signingCertificateSha256": certificate_sha256,
                "zipalignVerified": True,
                "signatureVerified": True,
            }
        ],
        "assetAllowlist": asset_allowlist,
        "sourceArchive": {
            **source_asset,
            "manifest": source_manifest,
            "url": f"{release_download_url}/{source_asset['filename']}",
        },
        "toolchain": {
            "androidBuildTools": "36.0.0",
            "androidNdk": "28.2.13676358",
            "runtimeManifestSha256": runtime_publication["sha256"],
            "s1aManifestSha256": s1a_publication["sha256"],
        },
        "declarations": approvals["declarations"],
        "gates": approvals["gates"],
    }
    record_path = output / "release.json"
    record_path.write_text(
        json.dumps(record, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    checksummed = [
        *(Path(value["filename"]) for value in assets.values()),
        Path("release.json"),
    ]
    checksum_lines = [
        f"{_sha256(output / path)}  {path.as_posix()}" for path in sorted(checksummed)
    ]
    (output / "SHA256SUMS").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    verify_assets_directory(
        output,
        args.tag,
        certificate_sha256,
        expected_repository=args.repository,
        private_rehearsal=args.private_rehearsal,
        repo_root=repo_root,
    )
    return record


def _parse_checksums(path: Path) -> dict[str, str]:
    checksums: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        digest, separator, filename = line.partition("  ")
        if (
            not separator
            or SHA256_PATTERN.fullmatch(digest) is None
            or Path(filename).name != filename
            or filename in checksums
        ):
            raise ReleaseError("SHA256SUMS is malformed")
        checksums[filename] = digest
    return checksums


def verify_assets_directory(
    directory: Path,
    tag: str,
    expected_certificate: str | None = None,
    expected_repository: str | None = None,
    private_rehearsal: bool = False,
    repo_root: Path = Path("."),
) -> dict[str, object]:
    directory = directory.resolve(strict=True)
    version = tag.removeprefix("v")
    expected_names = {
        f"anki-miner-android-{version}-arm64-v8a.apk",
        f"anki-miner-android-{version}-corresponding-source.tar.zst",
        f"anki-miner-android-{version}-notices.tar.zst",
        "app-signing-certificate.pem",
        "app-signing-certificate.sha256",
        "release.json",
        "SHA256SUMS",
    }
    actual_names = {path.name for path in directory.iterdir() if path.is_file()}
    evidence_name = f"anki-miner-android-{version}-redacted-evidence.tar.zst"
    if evidence_name in actual_names:
        expected_names.add(evidence_name)
    if actual_names != expected_names or any(path.is_dir() for path in directory.iterdir()):
        raise ReleaseError(
            f"release asset allowlist differs: expected {sorted(expected_names)}, got {sorted(actual_names)}"
        )
    checksums = _parse_checksums(directory / "SHA256SUMS")
    if set(checksums) != expected_names - {"SHA256SUMS"}:
        raise ReleaseError("SHA256SUMS does not cover the exact sibling asset set")
    for filename, expected in checksums.items():
        if _sha256(directory / filename) != expected:
            raise ReleaseError(f"release asset hash mismatch: {filename}")
    try:
        record = json.loads((directory / "release.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseError(f"cannot read release.json: {error}") from error
    expected_record_keys = {
        "releaseSchemaVersion",
        "channel",
        "tag",
        "releaseUrl",
        "versionName",
        "versionCode",
        "sourceCommit",
        "sourceTree",
        "engineRevision",
        "runtimeWheelBuildKey",
        "s1aPublicationBuildKey",
        "s1aAcceptance",
        "artifacts",
        "assetAllowlist",
        "sourceArchive",
        "toolchain",
        "declarations",
        "gates",
    }
    if not isinstance(record, dict) or set(record) != expected_record_keys:
        raise ReleaseError("release record fields differ from schema v2")
    expected_release_url = None
    if expected_repository is not None:
        if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", expected_repository) is None:
            raise ReleaseError("expected repository must use owner/name syntax")
        expected_release_url = (
            f"https://github.com/{expected_repository}/releases/tag/{tag}"
        )
    if (
        record.get("releaseSchemaVersion") != RECORD_SCHEMA_VERSION
        or record.get("tag") != tag
        or record.get("channel")
        != (
            "github-apk-private-rehearsal"
            if private_rehearsal
            else "github-apk-prerelease"
        )
        or record.get("versionName") != version
        or record.get("versionCode") != int(EXPECTED_VERSION_CODE)
        or not isinstance(record.get("releaseUrl"), str)
        or not str(record["releaseUrl"]).endswith(f"/releases/tag/{tag}")
        or (
            expected_release_url is not None
            and record.get("releaseUrl") != expected_release_url
        )
    ):
        raise ReleaseError("release record channel, tag, URL, or version differs")
    source_commit = record.get("sourceCommit")
    source_tree = record.get("sourceTree")
    if (
        not isinstance(source_commit, str)
        or COMMIT_PATTERN.fullmatch(source_commit) is None
        or not isinstance(source_tree, str)
        or COMMIT_PATTERN.fullmatch(source_tree) is None
        or COMMIT_PATTERN.fullmatch(str(record.get("engineRevision"))) is None
        or SHA256_PATTERN.fullmatch(str(record.get("runtimeWheelBuildKey"))) is None
        or SHA256_PATTERN.fullmatch(str(record.get("s1aPublicationBuildKey"))) is None
    ):
        raise ReleaseError("release record source or publication identity is invalid")
    checked_out_source = _source_identity(repo_root, tag)
    if checked_out_source != {"commit": source_commit, "tree": source_tree}:
        raise ReleaseError("release record source differs from the checked-out tag")
    tracked_tree = _tracked_tree(repo_root, source_tree)
    if record.get("assetAllowlist") != sorted(expected_names):
        raise ReleaseError("release record asset allowlist differs")
    certificate_path = directory / "app-signing-certificate.pem"
    canonical_certificate, certificate = _canonical_certificate(certificate_path)
    if certificate_path.read_bytes() != canonical_certificate:
        raise ReleaseError("public signing certificate asset is not canonical public-only PEM")
    recorded_fingerprint = (directory / "app-signing-certificate.sha256").read_text(
        encoding="ascii"
    )
    if recorded_fingerprint != certificate + "\n":
        raise ReleaseError("public certificate fingerprint file differs")
    if expected_certificate is not None and certificate != expected_certificate.casefold():
        raise ReleaseError("release certificate differs from the permanent identity")
    acceptance = record.get("s1aAcceptance")
    if (
        not isinstance(acceptance, dict)
        or acceptance.get("schema") != ACCEPTANCE_SCHEMA
        or SHA256_PATTERN.fullmatch(str(acceptance.get("sha256"))) is None
        or SHA256_PATTERN.fullmatch(str(acceptance.get("payload_sha256"))) is None
        or "path" in acceptance
        or acceptance.get("source_commit") != source_commit
        or acceptance.get("source_tree") != source_tree
        or acceptance.get("publication_build_key")
        != record.get("s1aPublicationBuildKey")
    ):
        raise ReleaseError("release record physical acceptance identity differs")
    apk_name = f"anki-miner-android-{version}-arm64-v8a.apk"
    signed = inspect_apk(
        directory / apk_name,
        expected_source=source_commit,
        signed=True,
        expected_certificate=certificate,
    )
    artifacts = record.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 1 or not isinstance(artifacts[0], dict):
        raise ReleaseError("release record must contain exactly one APK artifact")
    artifact = artifacts[0]
    expected_artifact_keys = {
        "filename",
        "applicationId",
        "versionName",
        "versionCode",
        "minSdk",
        "targetSdk",
        "abis",
        "unsignedSha256",
        "signedSha256",
        "signedSize",
        "payloadInventoryBeforeSigning",
        "payloadInventoryAfterSigning",
        "signingCertificateSha256",
        "zipalignVerified",
        "signatureVerified",
    }
    if set(artifact) != expected_artifact_keys:
        raise ReleaseError("release APK record fields differ")
    if (
        artifact.get("filename") != apk_name
        or artifact.get("applicationId") != APPLICATION_ID
        or artifact.get("versionName") != version
        or artifact.get("versionCode") != int(EXPECTED_VERSION_CODE)
        or artifact.get("minSdk") != int(EXPECTED_MIN_SDK)
        or artifact.get("targetSdk") != int(EXPECTED_TARGET_SDK)
        or artifact.get("abis") != [EXPECTED_ABI]
        or SHA256_PATTERN.fullmatch(str(artifact.get("unsignedSha256"))) is None
        or artifact.get("signedSha256") != signed["apk_sha256"]
        or artifact.get("signedSize") != signed["apk_size"]
        or artifact.get("payloadInventoryBeforeSigning")
        != artifact.get("payloadInventoryAfterSigning")
        or artifact.get("payloadInventoryAfterSigning")
        != signed["payload_inventory_sha256"]
        or artifact.get("signingCertificateSha256") != certificate
        or artifact.get("zipalignVerified") is not True
        or artifact.get("signatureVerified") is not True
    ):
        raise ReleaseError("release record APK identity differs")
    _validate_approvals(
        {
            "schema": APPROVAL_SCHEMA,
            "tag": tag,
            "source_commit": source_commit,
            "signed_apk_sha256": artifact.get("signedSha256"),
            "declarations": record.get("declarations"),
            "gates": record.get("gates"),
        },
        tag=tag,
        source_commit=source_commit,
        signed_apk_sha256=str(artifact.get("signedSha256")),
        allow_private_rehearsal_pending=private_rehearsal,
    )
    source_name = f"anki-miner-android-{version}-corresponding-source.tar.zst"
    source_archive = record.get("sourceArchive")
    expected_source_url = None
    if expected_repository is not None:
        expected_source_url = (
            f"https://github.com/{expected_repository}/releases/download/{tag}/{source_name}"
        )
    if (
        not isinstance(source_archive, dict)
        or set(source_archive) != {"filename", "sha256", "size", "manifest", "url"}
        or source_archive.get("filename") != source_name
        or source_archive.get("sha256") != checksums.get(source_name)
        or source_archive.get("size") != (directory / source_name).stat().st_size
        or not str(source_archive.get("url", "")).endswith(
            f"/releases/download/{tag}/{source_name}"
        )
        or (
            expected_source_url is not None
            and source_archive.get("url") != expected_source_url
        )
    ):
        raise ReleaseError("release corresponding-source record differs")
    toolchain = record.get("toolchain")
    if (
        not isinstance(toolchain, dict)
        or set(toolchain)
        != {
            "androidBuildTools",
            "androidNdk",
            "runtimeManifestSha256",
            "s1aManifestSha256",
        }
        or toolchain.get("androidBuildTools") != "36.0.0"
        or toolchain.get("androidNdk") != "28.2.13676358"
        or SHA256_PATTERN.fullmatch(str(toolchain.get("runtimeManifestSha256"))) is None
        or SHA256_PATTERN.fullmatch(str(toolchain.get("s1aManifestSha256"))) is None
    ):
        raise ReleaseError("release toolchain record differs")
    expected_source_manifest = _source_manifest(
        source={"commit": source_commit, "tree": source_tree},
        engine_revision=str(record.get("engineRevision")),
        runtime_build_key=record.get("runtimeWheelBuildKey"),
        s1a_build_key=record.get("s1aPublicationBuildKey"),
        tracked_tree_inventory_sha256=_tracked_tree_inventory_sha256(tracked_tree),
    )
    inspected_source_manifest = _inspect_source_archive(
        directory / source_name, expected_source_manifest, tracked_tree
    )
    if source_archive.get("manifest") != inspected_source_manifest:
        raise ReleaseError("release corresponding-source manifest record differs")
    return record


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    payload = subparsers.add_parser("payload-digest")
    payload.add_argument("--apk", required=True)

    for name in ("verify-unsigned", "verify-signed"):
        command = subparsers.add_parser(name)
        command.add_argument("--apk", required=True)
        command.add_argument("--expected-source", required=True)
        if name == "verify-signed":
            command.add_argument("--expected-certificate", required=True)

    certificate = subparsers.add_parser("verify-certificate")
    certificate.add_argument("--certificate", required=True)
    certificate.add_argument("--expected-certificate", required=True)

    source_manifest = subparsers.add_parser("write-source-manifest")
    source_manifest.add_argument("--repo-root", default=".")
    source_manifest.add_argument("--tag", required=True)
    source_manifest.add_argument("--runtime-manifest", required=True)
    source_manifest.add_argument("--s1a-manifest", required=True)
    source_manifest.add_argument("--source-root", required=True)
    source_manifest.add_argument("--output", required=True)

    prepare = subparsers.add_parser("prepare-assets")
    prepare.add_argument("--repo-root", default=".")
    prepare.add_argument("--repository", required=True)
    prepare.add_argument("--tag", required=True)
    prepare.add_argument("--unsigned-apk", required=True)
    prepare.add_argument("--signed-apk", required=True)
    prepare.add_argument("--certificate", required=True)
    prepare.add_argument("--expected-certificate", required=True)
    prepare.add_argument("--acceptance-receipt", required=True)
    prepare.add_argument("--accepted-debug-apk", required=True)
    prepare.add_argument("--runtime-manifest", required=True)
    prepare.add_argument("--s1a-manifest", required=True)
    prepare.add_argument("--golden", default="golden/engine-v1.json")
    prepare.add_argument("--approvals", required=True)
    prepare.add_argument("--corresponding-source", required=True)
    prepare.add_argument("--notices", required=True)
    prepare.add_argument("--redacted-evidence")
    prepare.add_argument("--output-dir", required=True)
    prepare.add_argument("--private-rehearsal", action="store_true")

    verify = subparsers.add_parser("verify-assets")
    verify.add_argument("--directory", required=True)
    verify.add_argument("--tag", required=True)
    verify.add_argument("--expected-certificate")
    verify.add_argument("--repository")
    verify.add_argument("--private-rehearsal", action="store_true")
    verify.add_argument("--repo-root", default=".")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "payload-digest":
            digest, _inventory, _abis = payload_inventory(Path(args.apk))
            result: object = {"payload_inventory_sha256": digest}
        elif args.command == "verify-unsigned":
            result = inspect_apk(
                Path(args.apk), expected_source=args.expected_source, signed=False
            )
        elif args.command == "verify-signed":
            result = inspect_apk(
                Path(args.apk),
                expected_source=args.expected_source,
                signed=True,
                expected_certificate=args.expected_certificate.casefold(),
            )
        elif args.command == "verify-certificate":
            _canonical, fingerprint = _canonical_certificate(Path(args.certificate))
            if fingerprint != args.expected_certificate.casefold():
                raise ReleaseError(
                    "selected certificate is not the permanent signing identity"
                )
            result = {"signing_certificate_sha256": fingerprint}
        elif args.command == "write-source-manifest":
            result = write_source_manifest(args)
        elif args.command == "prepare-assets":
            result = prepare_assets(args)
        elif args.command == "verify-assets":
            result = verify_assets_directory(
                Path(args.directory),
                args.tag,
                args.expected_certificate,
                expected_repository=args.repository,
                private_rehearsal=args.private_rehearsal,
                repo_root=Path(args.repo_root),
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except (OSError, ReleaseError, ValueError) as error:
        print(f"github-release: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
