#!/usr/bin/env python3
"""Create a strict S1a receipt from raw physical-device instrumentation logs."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

try:
    from . import s1a_acceptance as acceptance
except ImportError:  # Direct script execution keeps this directory on sys.path.
    import s1a_acceptance as acceptance


PARITY_MARKER = "ANKI_MINER_S1A_PARITY="
COLD_MARKER = "ANKI_MINER_S1A_COLD="
WORKLOAD_MARKER = "ANKI_MINER_S1A_WORKLOAD="
MIN_NOVEL_JAPANESE_CHARACTERS = acceptance.MIN_NOVEL_JAPANESE_CHARACTERS


def _run(command: list[str], *, cwd: Path | None = None) -> str:
    result = subprocess.run(
        command,
        cwd=cwd,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise acceptance.AcceptanceError(f"command failed: {' '.join(command)}")
    return result.stdout.replace("\r", "").strip()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _exact(value: object, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise acceptance.AcceptanceError(f"{label} evidence fields are invalid")
    return value


def _read_marker(path: Path, marker: str, keys: set[str]) -> dict[str, Any]:
    try:
        lines = path.read_text(encoding="utf-8").replace("\r", "").splitlines()
    except OSError as error:
        raise acceptance.AcceptanceError(f"cannot read instrumentation log: {path}") from error
    values: list[dict[str, Any]] = []
    decoder = json.JSONDecoder()
    for line in lines:
        offset = line.find(marker)
        if offset < 0:
            continue
        raw = line[offset + len(marker) :]
        try:
            parsed, end = decoder.raw_decode(raw)
        except json.JSONDecodeError as error:
            raise acceptance.AcceptanceError(f"malformed {marker[:-1]} evidence") from error
        if raw[end:].strip():
            raise acceptance.AcceptanceError(f"trailing data in {marker[:-1]} evidence")
        values.append(_exact(parsed, keys, marker[:-1]))
    if not values:
        raise acceptance.AcceptanceError(f"{marker[:-1]} evidence is missing")
    canonical = {json.dumps(value, sort_keys=True, separators=(",", ":")) for value in values}
    if len(canonical) != 1:
        raise acceptance.AcceptanceError(f"{marker[:-1]} evidence is ambiguous")
    return values[0]


def _adb(adb: str, serial: str, *command: str) -> str:
    return _run([adb, "-s", serial, *command])


def _property(adb: str, serial: str, name: str) -> str:
    return _adb(adb, serial, "shell", "getprop", name)


def _device(adb: str, serial: str) -> dict[str, Any]:
    if _adb(adb, serial, "get-state") != "device":
        raise acceptance.AcceptanceError("the requested physical device is not online")
    qemu_values = {
        _property(adb, serial, "ro.kernel.qemu").lower(),
        _property(adb, serial, "ro.boot.qemu").lower(),
    }
    hardware = _property(adb, serial, "ro.hardware").lower()
    fingerprint = _property(adb, serial, "ro.build.fingerprint")
    if qemu_values & {"1", "true"} or hardware in {"goldfish", "ranchu", "cutf_cvm"}:
        raise acceptance.AcceptanceError("S1a acceptance requires physical hardware, not an emulator")
    lowered_fingerprint = fingerprint.lower()
    if any(value in lowered_fingerprint for value in ("generic/", "sdk_gphone", "emulator")):
        raise acceptance.AcceptanceError("the Android build fingerprint identifies an emulator")

    meminfo = _adb(adb, serial, "shell", "cat", "/proc/meminfo")
    match = re.search(r"^MemTotal:\s*([0-9]+)\s+kB$", meminfo, re.MULTILINE)
    if match is None:
        raise acceptance.AcceptanceError("the device total memory is unreadable")
    return {
        "serial": serial,
        "manufacturer": _property(adb, serial, "ro.product.manufacturer"),
        "model": _property(adb, serial, "ro.product.model"),
        "build_fingerprint": fingerprint,
        "api_level": int(_property(adb, serial, "ro.build.version.sdk")),
        "abi": _property(adb, serial, "ro.product.cpu.abi"),
        "page_size_bytes": int(_adb(adb, serial, "shell", "getconf", "PAGE_SIZE")),
        "total_memory_bytes": int(match.group(1)) * 1024,
    }


def collect(args: argparse.Namespace) -> dict[str, Any]:
    repo = args.repo_root.resolve(strict=True)
    output = args.output.resolve()
    try:
        output.relative_to(repo)
    except ValueError:
        pass
    else:
        raise acceptance.AcceptanceError("write the acceptance receipt outside the Git checkout")

    manifest_path = args.manifest.resolve(strict=True)
    golden_path = args.golden.resolve(strict=True)
    apk_path = args.apk.resolve(strict=True)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    golden = json.loads(golden_path.read_text(encoding="utf-8"))
    source = acceptance._source_identity(repo)
    expected_dictionary = golden["provenance"]["data"]["assets_sha256"]["unidic_dicdir"]
    expected_corpus = golden["provenance"]["data"]["corpus_sha256"]

    parity = _read_marker(
        args.parity_log.resolve(strict=True),
        PARITY_MARKER,
        {"assertion_count", "corpus_sha256", "passed", "test_class"},
    )
    if parity != {
        "assertion_count": parity.get("assertion_count"),
        "corpus_sha256": expected_corpus,
        "passed": True,
        "test_class": acceptance.EXPECTED_TEST_CLASS,
    } or type(parity["assertion_count"]) is not int or parity["assertion_count"] <= 0:
        raise acceptance.AcceptanceError("tokenizer parity instrumentation is incomplete or stale")

    cold_values: list[float] = []
    cold_processes: set[tuple[int, int]] = set()
    for path in args.cold_log:
        cold = _read_marker(
            path.resolve(strict=True),
            COLD_MARKER,
            {"cold_init_ms", "dictionary_sha256", "pid", "process_start_uptime_ms"},
        )
        if cold["dictionary_sha256"] != expected_dictionary:
            raise acceptance.AcceptanceError("cold run used a different UniDic tree")
        if type(cold["pid"]) is not int or cold["pid"] <= 0:
            raise acceptance.AcceptanceError("cold run process identity is invalid")
        started = cold["process_start_uptime_ms"]
        if type(started) is not int or started <= 0:
            raise acceptance.AcceptanceError("cold run start identity is invalid")
        cold_processes.add((cold["pid"], started))
        value = cold["cold_init_ms"]
        if type(value) not in {int, float} or float(value) <= 0:
            raise acceptance.AcceptanceError("cold run duration is invalid")
        cold_values.append(float(value))
    if len(cold_values) != 3:
        raise acceptance.AcceptanceError("exactly three cold instrumentation logs are required")
    if len(cold_processes) != 3:
        raise acceptance.AcceptanceError("cold instrumentation did not use three fresh processes")

    workload = _read_marker(
        args.workload_log.resolve(strict=True),
        WORKLOAD_MARKER,
        {
            "characters_per_second",
            "corpus_sha256",
            "elapsed_ms",
            "japanese_character_count",
            "lemma_count",
            "peak_rss_bytes",
            "text_unit_count",
            "word_count",
        },
    )
    if (
        type(workload["japanese_character_count"]) is not int
        or workload["japanese_character_count"] < MIN_NOVEL_JAPANESE_CHARACTERS
    ):
        raise acceptance.AcceptanceError("the novel corpus is too small for physical acceptance")

    application_id = _run([args.apkanalyzer, "manifest", "application-id", str(apk_path)])
    document: dict[str, Any] = {
        "schema": acceptance.SCHEMA,
        "source": source,
        "publication": {
            "manifest_path": str(manifest_path),
            "manifest_sha256": _sha256(manifest_path),
            "recipe_key": manifest["recipe_key"],
            "build_key": manifest["build_key"],
        },
        "artifact": {
            "path": str(apk_path),
            "sha256": _sha256(apk_path),
            "size_bytes": apk_path.stat().st_size,
            "application_id": application_id,
            "variant": "deviceDebug",
            "abi": "arm64-v8a",
        },
        "device": _device(args.adb, args.serial),
        "measurements": {
            "cold_init_ms": cold_values,
            "peak_rss_bytes": workload["peak_rss_bytes"],
        },
        "tokenizer_parity": parity,
        "novel_throughput": {
            "corpus_sha256": workload["corpus_sha256"],
            "japanese_character_count": workload["japanese_character_count"],
            "elapsed_ms": workload["elapsed_ms"],
            "characters_per_second": workload["characters_per_second"],
        },
        "thresholds": {
            "cold_init_max_ms_exclusive": acceptance.COLD_INIT_LIMIT_MS,
            "peak_rss_max_bytes_inclusive": acceptance.PEAK_RSS_LIMIT_BYTES,
        },
    }
    document["payload_sha256"] = hashlib.sha256(
        acceptance._canonical_payload(document)
    ).hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_name(f".{output.name}.staging")
    staging.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    acceptance.validate(staging, manifest_path, repo, golden_path)
    staging.replace(output)
    return document


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--golden", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--apkanalyzer", required=True)
    parser.add_argument("--parity-log", required=True, type=Path)
    parser.add_argument("--cold-log", required=True, type=Path, action="append")
    parser.add_argument("--workload-log", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        document = collect(args)
    except (acceptance.AcceptanceError, KeyError, OSError, TypeError, ValueError) as error:
        print(f"S1a acceptance collection: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"output": str(args.output.resolve()), "payload_sha256": document["payload_sha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
