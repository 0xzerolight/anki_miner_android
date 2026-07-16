#!/usr/bin/env python3
"""Write and validate the immutable handoff from Gradle to connected tests."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any
import zipfile


SCHEMA = "anki-miner-android-test-receipt-v2"
EXPECTED_APP_ID = "com.ankiminer.android"
EXPECTED_TEST_APP_ID = "com.ankiminer.android.test"
EXPECTED_TASKS = [
    ":app:testEmulatorDebugUnitTest",
    ":app:lintEmulatorDebug",
    ":app:assembleEmulatorDebug",
    ":app:assembleEmulatorDebugAndroidTest",
]
EXPECTED_RELEASE_TASKS = [
    ":app:lintDeviceRelease",
    ":app:assembleDeviceRelease",
    ":app:bundleDeviceRelease",
]
EXPECTED_GRADLE_ARGUMENTS = [
    "--no-daemon",
    "--no-parallel",
    "--max-workers=1",
    "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8",
    "--stacktrace",
    "--dependency-verification",
    "strict",
]
EXPECTED_ANKIDROID = {
    "sha256": "b8aaef8c8ed13e96b7bbafbc46e690490684192147ab445db8a193c4ef6989b0",
    "certificate_sha256": "2071534f0f4b5e54ae952dd275d70da6e3459ee69909d2ab1b4843c4c5b21a45",
    "application_id": "com.ichi2.anki",
    "version_name": "2.24.0",
    "version_code": "422400300",
    "min_sdk": "24",
}


class ReceiptError(RuntimeError):
    pass


def _run(*command: str, cwd: Path | None = None) -> str:
    result = subprocess.run(
        command,
        cwd=cwd,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ReceiptError(f"command failed ({' '.join(command)}): {detail}")
    return result.stdout.strip()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_payload(payload: dict[str, Any]) -> bytes:
    unsigned = {key: value for key, value in payload.items() if key != "payload_sha256"}
    return json.dumps(
        unsigned,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _source_identity(repo_root: Path) -> dict[str, str]:
    head = _run("git", "rev-parse", "HEAD", cwd=repo_root)
    tree = _run("git", "rev-parse", "HEAD^{tree}", cwd=repo_root)
    status = _run(
        "git",
        "status",
        "--porcelain=v2",
        "--untracked-files=all",
        cwd=repo_root,
    )
    if status:
        raise ReceiptError("source checkout must be clean before writing a receipt")
    fingerprint = hashlib.sha256(f"{head}\n{tree}\n{status}".encode()).hexdigest()
    return {
        "head": head,
        "tree": tree,
        "status": status,
        "fingerprint": fingerprint,
    }


def _apk_abis(path: Path) -> list[str]:
    if path.suffix != ".apk":
        return []
    with zipfile.ZipFile(path) as archive:
        return sorted(
            {
                name.split("/", 2)[1]
                for name in archive.namelist()
                if name.startswith("lib/") and name.count("/") >= 2
            }
        )


def _apk_manifest_value(path: Path, field: str) -> str:
    return _run("apkanalyzer", "manifest", field, str(path))


def _artifact_identity(name: str, raw_path: str) -> dict[str, Any]:
    path = Path(raw_path).expanduser().resolve(strict=True)
    identity: dict[str, Any] = {
        "path": str(path),
        "sha256": _sha256(path),
        "size": path.stat().st_size,
        "abis": _apk_abis(path),
    }
    if path.suffix == ".apk":
        identity["application_id"] = _apk_manifest_value(path, "application-id")
    if name == "app_emulator_debug":
        if identity["application_id"] != EXPECTED_APP_ID:
            raise ReceiptError("emulator app APK package identity is wrong")
        if identity["abis"] != ["x86_64"]:
            raise ReceiptError("emulator app APK must contain only x86_64 native code")
        identity["variant"] = "emulatorDebug"
    elif name == "test_emulator_debug":
        if identity["application_id"] != EXPECTED_TEST_APP_ID:
            raise ReceiptError("emulator test APK package identity is wrong")
        identity["variant"] = "emulatorDebugAndroidTest"
    else:
        raise ReceiptError(f"unknown artifact name: {name}")
    return identity


def _manifest_identity(raw_path: str) -> dict[str, Any]:
    path = Path(raw_path).expanduser().resolve(strict=True)
    return {
        "path": str(path),
        "sha256": _sha256(path),
        "size": path.stat().st_size,
    }


def _ankidroid_identity(raw_path: str, reset_opt_in: bool) -> dict[str, Any]:
    path = Path(raw_path).expanduser().resolve(strict=True)
    certificate_prefix = "Signer #1 certificate SHA-256 digest: "
    certificate_lines = [
        line.removeprefix(certificate_prefix)
        for line in _run("apksigner", "verify", "--print-certs", str(path)).splitlines()
        if line.startswith(certificate_prefix)
    ]
    if len(certificate_lines) != 1:
        raise ReceiptError("AnkiDroid signing certificate output is ambiguous")
    identity = {
        "path": str(path),
        "sha256": _sha256(path),
        "size": path.stat().st_size,
        "certificate_sha256": certificate_lines[0],
        "application_id": _apk_manifest_value(path, "application-id"),
        "version_name": _apk_manifest_value(path, "version-name"),
        "version_code": _apk_manifest_value(path, "version-code"),
        "min_sdk": _apk_manifest_value(path, "min-sdk"),
        "destructive_reset_opt_in": reset_opt_in,
    }
    for field, expected in EXPECTED_ANKIDROID.items():
        if identity[field] != expected:
            raise ReceiptError(f"AnkiDroid {field} mismatch")
    if not reset_opt_in:
        raise ReceiptError("S2 receipt requires the destructive collection-reset opt-in")
    return identity


def _parse_artifacts(values: list[str]) -> dict[str, dict[str, Any]]:
    artifacts: dict[str, dict[str, Any]] = {}
    for value in values:
        name, separator, path = value.partition("=")
        if not separator or not name or not path or name in artifacts:
            raise ReceiptError(f"invalid or duplicate artifact mapping: {value}")
        artifacts[name] = _artifact_identity(name, path)
    required = {
        "app_emulator_debug",
        "test_emulator_debug",
    }
    if set(artifacts) != required:
        raise ReceiptError(f"artifact mappings must be exactly: {sorted(required)}")
    return artifacts


def write_receipt(args: argparse.Namespace) -> None:
    repo_root = Path(args.repo_root).resolve(strict=True)
    allowed_tasks = (EXPECTED_TASKS, EXPECTED_TASKS + EXPECTED_RELEASE_TASKS)
    if args.task not in allowed_tasks:
        raise ReceiptError("receipt tasks do not match the authoritative host health gate")
    if args.gradle_argument != EXPECTED_GRADLE_ARGUMENTS:
        raise ReceiptError("receipt Gradle arguments do not match the resource-safe gate")
    payload: dict[str, Any] = {
        "schema": SCHEMA,
        "repo_root": str(repo_root),
        "source": _source_identity(repo_root),
        "gradle": {
            "tasks": args.task,
            "arguments": args.gradle_argument,
        },
        "manifests": {
            "runtime": _manifest_identity(args.runtime_manifest),
            "s1a": (
                _manifest_identity(args.s1a_manifest)
                if args.s1a_manifest is not None
                else None
            ),
        },
        "artifacts": _parse_artifacts(args.artifact),
        "connected": {
            "application_id": EXPECTED_APP_ID,
            "test_application_id": EXPECTED_TEST_APP_ID,
            "abi": "x86_64",
            "variant": "emulatorDebug",
            "lanes": ["api26", "4k", "16k"],
        },
        "s2": (
            _ankidroid_identity(args.ankidroid_apk, args.s2_reset_opt_in)
            if args.ankidroid_apk is not None
            else None
        ),
    }
    payload["payload_sha256"] = hashlib.sha256(_canonical_payload(payload)).hexdigest()
    receipt_path = Path(args.receipt).expanduser().resolve()
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w",
        dir=receipt_path.parent,
        delete=False,
        encoding="utf-8",
    ) as stream:
        json.dump(payload, stream, indent=2, ensure_ascii=False, sort_keys=True)
        stream.write("\n")
        temporary = Path(stream.name)
    temporary.replace(receipt_path)
    print(receipt_path)


def _load_receipt(raw_path: str) -> tuple[Path, dict[str, Any]]:
    path = Path(raw_path).expanduser().resolve(strict=True)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ReceiptError(f"cannot read receipt: {error}") from error
    if not isinstance(payload, dict) or payload.get("schema") != SCHEMA:
        raise ReceiptError("receipt schema is missing or unsupported")
    expected_hash = hashlib.sha256(_canonical_payload(payload)).hexdigest()
    if payload.get("payload_sha256") != expected_hash:
        raise ReceiptError("receipt payload hash mismatch")
    return path, payload


def _validate_file_identity(identity: dict[str, Any], label: str) -> None:
    path = Path(identity["path"])
    if not path.is_file():
        raise ReceiptError(f"{label} is missing: {path}")
    if path.resolve() != path:
        raise ReceiptError(f"{label} path is no longer canonical: {path}")
    if path.stat().st_size != identity["size"] or _sha256(path) != identity["sha256"]:
        raise ReceiptError(f"{label} changed after host preparation")


def validate_receipt(args: argparse.Namespace) -> dict[str, Any]:
    _, payload = _load_receipt(args.receipt)
    repo_root = Path(args.repo_root).resolve(strict=True)
    if payload["repo_root"] != str(repo_root):
        raise ReceiptError("receipt belongs to a different checkout")
    if payload["source"] != _source_identity(repo_root):
        raise ReceiptError("receipt source fingerprint is stale")
    gradle = payload.get("gradle")
    if (
        not isinstance(gradle, dict)
        or gradle.get("tasks") not in (EXPECTED_TASKS, EXPECTED_TASKS + EXPECTED_RELEASE_TASKS)
        or gradle.get("arguments") != EXPECTED_GRADLE_ARGUMENTS
    ):
        raise ReceiptError("receipt did not run the authoritative resource-safe host gate")
    if payload.get("connected") != {
        "application_id": EXPECTED_APP_ID,
        "test_application_id": EXPECTED_TEST_APP_ID,
        "abi": "x86_64",
        "variant": "emulatorDebug",
        "lanes": ["api26", "4k", "16k"],
    }:
        raise ReceiptError("connected package, ABI, variant, or lane contract changed")
    for name, identity in payload["manifests"].items():
        if identity is not None:
            _validate_file_identity(identity, f"{name} manifest")
    for name, identity in payload["artifacts"].items():
        _validate_file_identity(identity, name)
    s2 = payload.get("s2")
    if args.require_s2:
        if not isinstance(s2, dict):
            raise ReceiptError("receipt does not bind an AnkiDroid S2 probe")
        if args.ankidroid_apk is None:
            raise ReceiptError("S2 validation requires --ankidroid-apk")
        expected_path = Path(args.ankidroid_apk).expanduser().resolve(strict=True)
        if s2.get("path") != str(expected_path):
            raise ReceiptError("S2 AnkiDroid APK path differs from the prepared receipt")
        current = _ankidroid_identity(str(expected_path), args.s2_reset_opt_in)
        if s2 != current:
            raise ReceiptError("S2 AnkiDroid identity or reset opt-in changed")
    elif s2 is not None:
        _validate_file_identity(s2, "AnkiDroid APK")
    return payload


def read_field(args: argparse.Namespace) -> None:
    _, payload = _load_receipt(args.receipt)
    value: Any = payload
    for component in args.name.split("."):
        if value is None:
            return
        if not isinstance(value, dict) or component not in value:
            raise ReceiptError(f"receipt field does not exist: {args.name}")
        value = value[component]
    if value is None:
        return
    if isinstance(value, (dict, list)):
        print(json.dumps(value, separators=(",", ":"), sort_keys=True))
    elif isinstance(value, bool):
        print("true" if value else "false")
    else:
        print(value)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    writer = subparsers.add_parser("write")
    writer.add_argument("--repo-root", required=True)
    writer.add_argument("--receipt", required=True)
    writer.add_argument("--runtime-manifest", required=True)
    writer.add_argument("--s1a-manifest")
    writer.add_argument("--task", action="append", default=[], required=True)
    writer.add_argument("--gradle-argument", action="append", default=[], required=True)
    writer.add_argument("--artifact", action="append", default=[], required=True)
    writer.add_argument("--ankidroid-apk")
    writer.add_argument("--s2-reset-opt-in", action="store_true")

    validator = subparsers.add_parser("validate")
    validator.add_argument("--repo-root", required=True)
    validator.add_argument("--receipt", required=True)
    validator.add_argument("--require-s2", action="store_true")
    validator.add_argument("--ankidroid-apk")
    validator.add_argument("--s2-reset-opt-in", action="store_true")

    field = subparsers.add_parser("field")
    field.add_argument("--receipt", required=True)
    field.add_argument("--name", required=True)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "write":
            write_receipt(args)
        elif args.command == "validate":
            validate_receipt(args)
        else:
            read_field(args)
    except (KeyError, OSError, ReceiptError, TypeError, ValueError, zipfile.BadZipFile) as error:
        print(f"receipt: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
