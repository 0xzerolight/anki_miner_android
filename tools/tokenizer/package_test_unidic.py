#!/usr/bin/env python3
"""Create the deterministic external UniDic ZIP used by tokenizer spikes."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import stat
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, os.fspath(ROOT / "app/src/main/python"))

from android_bridge.unidic_resource import calculate_unidic_tree_sha256  # noqa: E402

REQUIRED = ("char.bin", "dicrc", "matrix.bin", "mecabrc", "sys.dic", "unk.dic")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dicdir", type=Path, required=True)
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.dicdir.resolve(strict=True)
    document = json.loads(args.golden.read_text(encoding="utf-8"))
    expected_hash = document["provenance"]["data"]["assets_sha256"]["unidic_dicdir"]
    actual_hash = calculate_unidic_tree_sha256(root)
    if actual_hash != expected_hash:
        parser.error(
            f"UniDic tree hash {actual_hash} does not match golden hash {expected_hash}",
        )
    files: list[tuple[str, Path]] = []
    for path in sorted(root.rglob("*"), key=lambda value: value.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode) or not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            parser.error(f"UniDic contains an unsupported entry: {relative}")
        if stat.S_ISREG(mode):
            files.append((relative, path))
    missing = set(REQUIRED) - {relative for relative, _ in files}
    if missing:
        parser.error(f"UniDic is missing {sorted(missing)}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(
        args.output,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        for relative, path in files:
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            with path.open("rb") as source, archive.open(info, "w", force_zip64=True) as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
