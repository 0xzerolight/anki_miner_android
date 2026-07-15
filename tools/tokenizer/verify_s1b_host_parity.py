#!/usr/bin/env python3
"""Compare the native S1b wire against committed desktop token goldens."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import struct
import subprocess
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "app/src/main/python"))
sys.path.insert(0, str(PROJECT_ROOT / "tools/engine-sync"))

from android_bridge.tokenizer_contract import (  # noqa: E402
    UNIDIC_FEATURE_FIELDS,
    adapt_tokens,
    decode_token_wire,
)
from android_bridge.unidic_resource import UNIDIC_REQUIRED_FILES  # noqa: E402
from engine_sync.golden_contract import (  # noqa: E402
    GoldenContractError,
    sha256_tree,
)

_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")


def _read_exact(stream: object, size: int) -> bytes:
    read = getattr(stream, "read")
    output = read(size)
    if len(output) != size:
        raise RuntimeError("native parity driver returned a truncated frame")
    return output


def _actual_token(token: object) -> dict[str, object]:
    feature = getattr(token, "feature")
    return {
        "surface": getattr(token, "surface"),
        "features": {name: getattr(feature, name) for name in UNIDIC_FEATURE_FIELDS},
        "is_unknown": getattr(token, "is_unk"),
        "offsets": {
            "codepoint_start": getattr(token, "codepoint_start"),
            "codepoint_end": getattr(token, "codepoint_end"),
            "utf16_start": getattr(token, "utf16_start"),
            "utf16_end": getattr(token, "utf16_end"),
        },
    }


def verify_dictionary_provenance(
    dicdir: Path,
    document: dict[str, object],
) -> str:
    """Bind a parity run to the exact dictionary recorded by the golden."""

    try:
        provenance = document["provenance"]
        assert isinstance(provenance, dict)
        data = provenance["data"]
        assert isinstance(data, dict)
        assets = data["assets_sha256"]
        assert isinstance(assets, dict)
        expected = assets["unidic_dicdir"]
    except (AssertionError, KeyError, TypeError) as exc:
        raise RuntimeError("golden has no UniDic dictionary provenance hash") from exc
    if not isinstance(expected, str) or _SHA256_RE.fullmatch(expected) is None:
        raise RuntimeError("golden UniDic dictionary hash is malformed")

    missing = [name for name in UNIDIC_REQUIRED_FILES if not (dicdir / name).is_file()]
    if missing:
        raise RuntimeError(f"UniDic directory is incomplete: {missing!r}")
    try:
        actual = sha256_tree(dicdir)
    except GoldenContractError as exc:
        raise RuntimeError(f"UniDic directory cannot be verified: {exc}") from exc
    if actual != expected:
        raise RuntimeError(
            f"UniDic dictionary provenance mismatch: {actual} != {expected}"
        )
    return actual


def verify(driver: Path, dicdir: Path, golden: Path) -> None:
    document = json.loads(golden.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise RuntimeError("golden root is not an object")
    verify_dictionary_provenance(dicdir, document)
    cases = document["cases"]["tokenization"]
    process = subprocess.Popen(
        [str(driver), str(dicdir)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
    )
    assert process.stdin is not None
    assert process.stdout is not None
    try:
        for case in cases:
            text = case["text"]
            encoded = text.encode("utf-8")
            process.stdin.write(struct.pack("<I", len(encoded)) + encoded)
            process.stdin.flush()
            wire_size = struct.unpack("<I", _read_exact(process.stdout, 4))[0]
            wire = _read_exact(process.stdout, wire_size)
            actual = [
                _actual_token(token)
                for token in adapt_tokens(text, decode_token_wire(wire, text))
            ]
            if actual != case["tokens"]:
                raise RuntimeError(
                    f"native parity mismatch in {case['id']}:\n"
                    f"expected={json.dumps(case['tokens'], ensure_ascii=False)}\n"
                    f"actual={json.dumps(actual, ensure_ascii=False)}"
                )
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError("native parity driver failed")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--driver", type=Path)
    parser.add_argument("--dicdir", type=Path, required=True)
    parser.add_argument(
        "--golden",
        type=Path,
        default=PROJECT_ROOT / "golden/engine-v1.json",
    )
    parser.add_argument("--check-dictionary-only", action="store_true")
    args = parser.parse_args()
    try:
        dicdir = args.dicdir.resolve(strict=True)
        golden = args.golden.resolve(strict=True)
        if args.check_dictionary_only:
            document = json.loads(golden.read_text(encoding="utf-8"))
            if not isinstance(document, dict):
                raise RuntimeError("golden root is not an object")
            verify_dictionary_provenance(dicdir, document)
        else:
            if args.driver is None:
                parser.error("--driver is required unless checking only")
            verify(args.driver.resolve(strict=True), dicdir, golden)
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as exc:
        print(f"S1b host parity: {exc}", file=sys.stderr)
        return 1
    print("S1b host parity: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
