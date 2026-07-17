#!/usr/bin/env python3
"""Validate the physical ARM64 acceptance evidence required by release builds."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


SCHEMA = "anki-miner-s1a-arm64-acceptance-v2"
SCHEMA_PATH = Path(__file__).with_name("s1a-arm64-acceptance-v2.schema.json")
COLD_INIT_LIMIT_MS = 4_000.0
PEAK_RSS_LIMIT_BYTES = 384 * 1024 * 1024
MIN_NOVEL_JAPANESE_CHARACTERS = 50_000
REPRESENTATIVE_SELECTION_COUNT = 100
REPRESENTATIVE_WORKLOAD_ID = "reading-process-reading-v1"
ACCEPTANCE_APK_CHANNEL = "device-acceptance"
EXPECTED_TEST_CLASS = "com.ankiminer.android.TokenizerS1aInstrumentedTest"
SHA256 = re.compile(r"[0-9a-f]{64}")
GIT_ID = re.compile(r"[0-9a-f]{40}")


class AcceptanceError(RuntimeError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_payload(document: dict[str, Any]) -> bytes:
    unsigned = {key: value for key, value in document.items() if key != "payload_sha256"}
    return json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _exact(value: object, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise AcceptanceError(f"{label} fields are invalid")
    return value


def _run(repo: Path, *command: str) -> str:
    result = subprocess.run(
        command,
        cwd=repo,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise AcceptanceError(f"command failed: {' '.join(command)}")
    return result.stdout.strip()


def _source_identity(repo: Path) -> dict[str, str]:
    status = _run(repo, "git", "status", "--porcelain=v2", "--untracked-files=all")
    if status:
        raise AcceptanceError("release source must be clean")
    return {
        "commit": _run(repo, "git", "rev-parse", "HEAD"),
        "tree": _run(repo, "git", "rev-parse", "HEAD^{tree}"),
    }


def _positive_number(value: object, label: str) -> float:
    if type(value) not in {int, float} or not float(value) > 0:
        raise AcceptanceError(f"{label} must be positive")
    return float(value)


def validate(
    receipt_path: Path,
    manifest_path: Path,
    apk_path: Path,
    repo_root: Path,
    golden_path: Path,
) -> dict[str, Any]:
    try:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        document = json.loads(receipt_path.read_text(encoding="utf-8"))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        golden = json.loads(golden_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise AcceptanceError(f"acceptance input cannot be read: {error}") from error
    if schema.get("properties", {}).get("schema", {}).get("const") != SCHEMA:
        raise AcceptanceError("acceptance schema file does not match the verifier")

    document = _exact(
        document,
        {
            "schema",
            "source",
            "publication",
            "artifact",
            "device",
            "measurements",
            "tokenizer_parity",
            "novel_throughput",
            "representative_mining",
            "thresholds",
            "payload_sha256",
        },
        "receipt",
    )
    if document["schema"] != SCHEMA:
        raise AcceptanceError("acceptance receipt schema is unsupported")
    expected_payload_hash = hashlib.sha256(_canonical_payload(document)).hexdigest()
    if document["payload_sha256"] != expected_payload_hash:
        raise AcceptanceError("acceptance receipt payload hash mismatch")

    source = _exact(document["source"], {"commit", "tree"}, "source")
    if not all(isinstance(source[key], str) and GIT_ID.fullmatch(source[key]) for key in source):
        raise AcceptanceError("source identity is invalid")
    if source != _source_identity(repo_root):
        raise AcceptanceError("acceptance receipt belongs to different source")

    publication = _exact(
        document["publication"],
        {"manifest_sha256", "recipe_key", "build_key"},
        "publication",
    )
    canonical_manifest = manifest_path.resolve(strict=True)
    if publication["manifest_sha256"] != _sha256(canonical_manifest):
        raise AcceptanceError("accepted S1a publication changed")
    for key in ("recipe_key", "build_key"):
        if not isinstance(publication[key], str) or SHA256.fullmatch(publication[key]) is None:
            raise AcceptanceError(f"publication {key} is invalid")
        if manifest.get(key) != publication[key]:
            raise AcceptanceError(f"accepted S1a publication {key} changed")

    artifact = _exact(
        document["artifact"],
        {
            "filename",
            "sha256",
            "size_bytes",
            "application_id",
            "variant",
            "abi",
            "source_commit",
            "release_channel",
        },
        "artifact",
    )
    canonical_apk = apk_path.resolve(strict=True)
    if (
        artifact["filename"] != canonical_apk.name
        or Path(artifact["filename"]).name != artifact["filename"]
        or artifact["sha256"] != _sha256(canonical_apk)
        or artifact["size_bytes"] != canonical_apk.stat().st_size
        or artifact["application_id"] != "com.ankiminer.android"
        or artifact["variant"] != "deviceDebug"
        or artifact["abi"] != "arm64-v8a"
        or artifact["source_commit"] != source["commit"]
        or artifact["release_channel"] != ACCEPTANCE_APK_CHANNEL
    ):
        raise AcceptanceError("accepted ARM64 APK identity changed or is invalid")

    device = _exact(
        document["device"],
        {
            "manufacturer",
            "model",
            "build_fingerprint",
            "api_level",
            "abi",
            "page_size_bytes",
            "total_memory_bytes",
        },
        "device",
    )
    for key in ("manufacturer", "model", "build_fingerprint"):
        if not isinstance(device[key], str) or not device[key].strip():
            raise AcceptanceError(f"device {key} is invalid")
    if (
        type(device["api_level"]) is not int
        or device["api_level"] < 26
        or device["abi"] != "arm64-v8a"
        or device["page_size_bytes"] not in {4096, 16384}
        or type(device["total_memory_bytes"]) is not int
        or not 3 * 1024**3 <= device["total_memory_bytes"] <= 5 * 1024**3
    ):
        raise AcceptanceError("physical ARM64 device identity is outside the frozen gate")

    thresholds = _exact(
        document["thresholds"],
        {"cold_init_max_ms_exclusive", "peak_rss_max_bytes_inclusive"},
        "thresholds",
    )
    if thresholds != {
        "cold_init_max_ms_exclusive": COLD_INIT_LIMIT_MS,
        "peak_rss_max_bytes_inclusive": PEAK_RSS_LIMIT_BYTES,
    }:
        raise AcceptanceError("acceptance thresholds differ from the frozen gate")

    measurements = _exact(
        document["measurements"],
        {"cold_init_ms"},
        "measurements",
    )
    cold = measurements["cold_init_ms"]
    if not isinstance(cold, list) or len(cold) != 3:
        raise AcceptanceError("exactly three cold initialization measurements are required")
    if any(_positive_number(value, "cold initialization") >= COLD_INIT_LIMIT_MS for value in cold):
        raise AcceptanceError("cold initialization exceeds the frozen threshold")
    parity = _exact(
        document["tokenizer_parity"],
        {"passed", "test_class", "corpus_sha256", "assertion_count"},
        "tokenizer parity",
    )
    expected_corpus = golden["provenance"]["data"]["corpus_sha256"]
    if (
        parity["passed"] is not True
        or parity["test_class"] != EXPECTED_TEST_CLASS
        or parity["corpus_sha256"] != expected_corpus
        or type(parity["assertion_count"]) is not int
        or parity["assertion_count"] <= 0
    ):
        raise AcceptanceError("tokenizer parity evidence is incomplete or stale")

    throughput = _exact(
        document["novel_throughput"],
        {
            "corpus_sha256",
            "japanese_character_count",
            "elapsed_ms",
            "characters_per_second",
            "text_unit_count",
            "word_count",
            "lemma_count",
        },
        "novel throughput",
    )
    if (
        not isinstance(throughput["corpus_sha256"], str)
        or SHA256.fullmatch(throughput["corpus_sha256"]) is None
        or type(throughput["japanese_character_count"]) is not int
        or throughput["japanese_character_count"] < MIN_NOVEL_JAPANESE_CHARACTERS
    ):
        raise AcceptanceError("novel throughput corpus identity is invalid")
    elapsed = _positive_number(throughput["elapsed_ms"], "novel elapsed time")
    measured_rate = _positive_number(
        throughput["characters_per_second"], "novel throughput rate"
    )
    calculated_rate = throughput["japanese_character_count"] * 1000.0 / elapsed
    if abs(measured_rate - calculated_rate) > max(0.01, calculated_rate * 0.001):
        raise AcceptanceError("novel throughput rate does not match its raw measurement")
    for key in ("text_unit_count", "word_count", "lemma_count"):
        if type(throughput[key]) is not int or throughput[key] <= 0:
            raise AcceptanceError(f"novel throughput {key} must be positive")

    mining = _exact(
        document["representative_mining"],
        {
            "workload_id",
            "corpus_sha256",
            "total_words_found",
            "candidate_count",
            "selected_count",
            "card_payload_count",
            "cards_created",
            "elapsed_ms",
            "peak_rss_bytes",
            "completed",
        },
        "representative mining",
    )
    if (
        mining["workload_id"] != REPRESENTATIVE_WORKLOAD_ID
        or mining["completed"] is not True
        or mining["corpus_sha256"] != throughput["corpus_sha256"]
        or mining["total_words_found"] != throughput["word_count"]
        or type(mining["candidate_count"]) is not int
        or mining["candidate_count"] < REPRESENTATIVE_SELECTION_COUNT
        or mining["candidate_count"] > mining["total_words_found"]
        or mining["selected_count"] != REPRESENTATIVE_SELECTION_COUNT
        or mining["card_payload_count"] != REPRESENTATIVE_SELECTION_COUNT
        or mining["cards_created"] != REPRESENTATIVE_SELECTION_COUNT
    ):
        raise AcceptanceError("representative mining evidence is incomplete or stale")
    _positive_number(mining["elapsed_ms"], "representative mining elapsed time")
    if (
        type(mining["peak_rss_bytes"]) is not int
        or mining["peak_rss_bytes"] <= 0
        or mining["peak_rss_bytes"] > PEAK_RSS_LIMIT_BYTES
    ):
        raise AcceptanceError("representative mining peak RSS exceeds the frozen threshold")

    return {
        "schema": 2,
        "source_commit": source["commit"],
        "publication_build_key": publication["build_key"],
        "device_api_level": device["api_level"],
        "device_fingerprint": device["build_fingerprint"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("verify", choices=["verify"])
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--golden", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = validate(
            args.receipt.resolve(strict=True),
            args.manifest.resolve(strict=True),
            args.apk.resolve(strict=True),
            args.repo_root.resolve(strict=True),
            args.golden.resolve(strict=True),
        )
    except (AcceptanceError, KeyError, OSError, TypeError, ValueError) as error:
        print(f"S1a acceptance: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
