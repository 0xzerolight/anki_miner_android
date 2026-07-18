#!/usr/bin/env python3
"""Fail-closed verification for an exact signed Anki Miner APK candidate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Callable
import xml.etree.ElementTree as ET


ANDROID_NS = "http://schemas.android.com/apk/res/android"
APPLICATION_ID = "com.ankiminer.android"
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
HASH_PATTERN = re.compile(r"[0-9a-f]{64}")
VERSION_PATTERN = re.compile(
    r"[0-9]+\.[0-9]+\.[0-9]+"
    r"(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
    r"(?:\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
)
CERTIFICATE_PATTERN = re.compile(
    r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9A-Fa-f:]+)"
)
VERIFIED_SCHEME_PATTERN = re.compile(r"Verified using v([23](?:\.[0-9]+)?) scheme.*: true")


class CandidateError(RuntimeError):
    """The requested APK is not an exact releasable candidate."""


def _run(command: list[str], cwd: Path) -> str:
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as error:
        raise CandidateError(f"cannot run {command[0]}: {error}") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise CandidateError(f"command failed ({' '.join(command)}): {detail}")
    return result.stdout.strip()


def _regular_file(path: Path, label: str) -> Path:
    if path.is_symlink():
        raise CandidateError(f"{label} must not be a symlink: {path}")
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise CandidateError(f"{label} is missing: {path}") from error
    if not resolved.is_file():
        raise CandidateError(f"{label} is not a regular file: {path}")
    return resolved


Runner = Callable[[list[str], Path], str]


def _publication_manifest(path: Path, label: str, prefix: str) -> tuple[Path, str]:
    if path.is_symlink() or path.parent.is_symlink():
        raise CandidateError(f"{label} path must identify an immutable directory directly")
    resolved = _regular_file(path, label)
    try:
        payload = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CandidateError(f"{label} is invalid: {error}") from error
    build_key = payload.get("build_key") if isinstance(payload, dict) else None
    if not isinstance(build_key, str) or HASH_PATTERN.fullmatch(build_key) is None:
        raise CandidateError(f"{label} has no valid build_key")
    if resolved.name != "manifest.json" or resolved.parent.name != f"{prefix}-{build_key}":
        raise CandidateError(f"{label} is outside its immutable build-key directory")
    return resolved, build_key


def _normalize_certificate(value: str) -> str:
    normalized = value.replace(":", "").casefold()
    if HASH_PATTERN.fullmatch(normalized) is None:
        raise CandidateError("expected certificate SHA-256 must be 64 hexadecimal digits")
    return normalized


def validate_policy(
    *,
    mode: str,
    channel: str,
    version_code: int,
    version_name: str,
    source_commit: str,
    s1a_accepted: bool,
) -> None:
    if version_code <= 0:
        raise CandidateError("version code must be positive")
    if VERSION_PATTERN.fullmatch(version_name) is None:
        raise CandidateError("version name must be an explicit semantic version")
    if COMMIT_PATTERN.fullmatch(source_commit) is None:
        raise CandidateError("source commit must be lowercase 40-hex")
    if mode == "test":
        if channel != "ci":
            raise CandidateError("test mode requires the non-distributable ci channel")
        return
    if channel not in {"github-alpha", "production"}:
        raise CandidateError("distribution mode requires github-alpha or production")
    if not s1a_accepted:
        raise CandidateError(
            "distribution requires accepted ARM64 S1a physical evidence"
        )


def _manifest_metadata(xml: str) -> dict[str, str]:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise CandidateError(f"apkanalyzer returned invalid manifest XML: {error}") from error
    result: dict[str, str] = {}
    for entry in root.findall("./application/meta-data"):
        name = entry.get(f"{{{ANDROID_NS}}}name")
        value = entry.get(f"{{{ANDROID_NS}}}value")
        if name is not None and value is not None:
            if name in result:
                raise CandidateError(f"duplicate manifest metadata: {name}")
            result[name] = value
    return result


def _verify_git(repo_root: Path, source_commit: str, run: Runner) -> None:
    head = run(["git", "rev-parse", "HEAD"], repo_root)
    if head != source_commit:
        raise CandidateError("source commit does not equal the checked-out HEAD")
    status = run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        repo_root,
    )
    if status:
        raise CandidateError("distribution candidates require a clean Git worktree")


def verify_candidate(arguments: argparse.Namespace, run: Runner = _run) -> None:
    repo_root = arguments.repo_root.resolve(strict=True)
    artifact = _regular_file(arguments.artifact, "APK")
    runtime_manifest, runtime_build_key = _publication_manifest(
        arguments.runtime_manifest,
        "runtime publication manifest",
        "runtime-wheels",
    )
    s1a_manifest, s1a_build_key = _publication_manifest(
        arguments.s1a_manifest,
        "S1a publication manifest",
        "s1a-wheels",
    )
    expected_certificate = _normalize_certificate(arguments.expected_cert_sha256)
    accepted = arguments.expected_s1a_arm64_accepted == "true"
    validate_policy(
        mode=arguments.mode,
        channel=arguments.expected_channel,
        version_code=arguments.expected_version_code,
        version_name=arguments.expected_version_name,
        source_commit=arguments.expected_source_commit,
        s1a_accepted=accepted,
    )
    if arguments.mode == "distribution":
        _verify_git(repo_root, arguments.expected_source_commit, run)

    run(
        [
            sys.executable,
            str(repo_root / "tools/runtime-wheels/runtime_wheels.py"),
            "verify-publication",
            "--manifest",
            str(runtime_manifest),
        ],
        repo_root,
    )
    run(
        [
            sys.executable,
            str(repo_root / "tools/wheels/s1a_wheels.py"),
            "verify-publication",
            "--manifest",
            str(s1a_manifest),
        ],
        repo_root,
    )

    abi = arguments.abi
    native_entry_prefix = f"lib/{abi}"
    run(
        [
            str(repo_root / "scripts/check-native-artifact.sh"),
            "--artifact",
            str(artifact),
            "--allow-abi",
            abi,
            "--require-app-imy",
            "--reject-base-unidic",
            "--require-s1a",
            "--s1a-manifest",
            str(s1a_manifest),
            "--require-entry",
            f"{native_entry_prefix}/libanki_miner_mecab.so",
            "--require-entry",
            f"{native_entry_prefix}/libffmpeg.so",
            "--require-entry",
            f"{native_entry_prefix}/libffprobe.so",
        ],
        repo_root,
    )
    run(
        [
            sys.executable,
            str(repo_root / "scripts/check_runtime_artifact.py"),
            "--artifact",
            str(artifact),
            "--runtime-manifest",
            str(runtime_manifest),
            "--s1a-manifest",
            str(s1a_manifest),
            "--allow-abi",
            abi,
        ],
        repo_root,
    )

    def manifest_value(field: str) -> str:
        return run([arguments.apkanalyzer, "manifest", field, str(artifact)], repo_root)

    if manifest_value("application-id") != APPLICATION_ID:
        raise CandidateError("APK application ID differs")
    if manifest_value("version-code") != str(arguments.expected_version_code):
        raise CandidateError("APK version code differs")
    if manifest_value("version-name") != arguments.expected_version_name:
        raise CandidateError("APK version name differs")
    if manifest_value("min-sdk") != "26" or manifest_value("target-sdk") != "36":
        raise CandidateError("APK SDK contract differs")

    metadata = _manifest_metadata(manifest_value("print"))
    expected_metadata = {
        "com.ankiminer.android.SOURCE_COMMIT": arguments.expected_source_commit,
        "com.ankiminer.android.RELEASE_CHANNEL": arguments.expected_channel,
        "com.ankiminer.android.S1A_ARM64_ACCEPTED": str(accepted).lower(),
        "com.ankiminer.android.RUNTIME_WHEEL_BUILD_KEY": runtime_build_key,
        "com.ankiminer.android.S1A_PUBLICATION_BUILD_KEY": s1a_build_key,
    }
    for name, expected in expected_metadata.items():
        if metadata.get(name) != expected:
            raise CandidateError(f"APK manifest metadata differs: {name}")

    signer_output = run(
        [arguments.apksigner, "verify", "--verbose", "--print-certs", str(artifact)],
        repo_root,
    )
    certificates = [
        _normalize_certificate(match.group(1))
        for match in CERTIFICATE_PATTERN.finditer(signer_output)
    ]
    if certificates != [expected_certificate]:
        raise CandidateError("APK signing certificate differs or has multiple signers")
    schemes = VERIFIED_SCHEME_PATTERN.findall(signer_output)
    if not any(value.startswith("2") or value.startswith("3") for value in schemes):
        raise CandidateError("APK has no verified v2/v3 signing scheme")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--mode", choices=("distribution", "test"), required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), required=True)
    parser.add_argument("--runtime-manifest", type=Path, required=True)
    parser.add_argument("--s1a-manifest", type=Path, required=True)
    parser.add_argument("--expected-cert-sha256", required=True)
    parser.add_argument("--expected-version-code", type=int, required=True)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-source-commit", required=True)
    parser.add_argument("--expected-channel", required=True)
    parser.add_argument(
        "--expected-s1a-arm64-accepted",
        choices=("true", "false"),
        required=True,
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--apksigner", default="apksigner")
    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        verify_candidate(arguments)
    except (CandidateError, OSError, ValueError) as error:
        print(f"release-candidate: {error}", file=sys.stderr)
        return 1
    print(f"release-candidate: OK ({arguments.mode}; verification does not publish)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
