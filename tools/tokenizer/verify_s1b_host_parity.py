#!/usr/bin/env python3
"""Compare the native S1b wire against committed desktop token goldens."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import subprocess
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "app/src/main/python"))

from android_bridge.tokenizer_contract import (  # noqa: E402
    UNIDIC_FEATURE_FIELDS,
    adapt_tokens,
    decode_token_wire,
)


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
        "features": {
            name: getattr(feature, name) for name in UNIDIC_FEATURE_FIELDS
        },
        "is_unknown": getattr(token, "is_unk"),
        "offsets": {
            "codepoint_start": getattr(token, "codepoint_start"),
            "codepoint_end": getattr(token, "codepoint_end"),
            "utf16_start": getattr(token, "utf16_start"),
            "utf16_end": getattr(token, "utf16_end"),
        },
    }


def verify(driver: Path, dicdir: Path, golden: Path) -> None:
    document = json.loads(golden.read_text(encoding="utf-8"))
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
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--dicdir", type=Path, required=True)
    parser.add_argument(
        "--golden",
        type=Path,
        default=PROJECT_ROOT / "golden/engine-v1.json",
    )
    args = parser.parse_args()
    try:
        verify(
            args.driver.resolve(strict=True),
            args.dicdir.resolve(strict=True),
            args.golden.resolve(strict=True),
        )
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as exc:
        print(f"S1b host parity: {exc}", file=sys.stderr)
        return 1
    print("S1b host parity: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
