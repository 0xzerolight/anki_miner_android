#!/usr/bin/env python3
"""Import the complete tokenizer-neutral Android Python runtime."""

from __future__ import annotations

import argparse
import importlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys
import tempfile

from PIL import features

EXPECTED_DISTRIBUTIONS = {
    "certifi": "2026.6.17",
    "charset-normalizer": "3.4.7",
    "idna": "3.18",
    "lxml": "6.1.1",
    "pillow": "12.2.0",
    "pysubs2": "1.8.1",
    "requests": "2.34.2",
    "urllib3": "2.7.0",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-root", type=Path, required=True)
    parser.add_argument("--expected-version", required=True)
    arguments = parser.parse_args()

    root = arguments.python_root.resolve(strict=True)
    manifest = json.loads((root / ".engine-sync-manifest.json").read_text(encoding="utf-8"))
    if platform.python_implementation() != "CPython":
        raise RuntimeError("runtime smoke requires CPython")
    if platform.python_version() != arguments.expected_version:
        raise RuntimeError(f"runtime Python is {platform.python_version()}, " f"expected {arguments.expected_version}")

    sys.path.insert(0, os.fspath(root))
    with tempfile.TemporaryDirectory(prefix="anki-miner-runtime-home-") as home:
        from android_bridge.bootstrap import initialize

        initialize(home)
        for module_name in manifest["modules"]:
            importlib.import_module(module_name)

    distributions = {
        distribution.metadata["Name"].lower()
        for distribution in importlib.metadata.distributions()
        if distribution.metadata["Name"]
    }
    forbidden = {"fugashi", "gtts", "unidic", "unidic-lite", "yt-dlp"}
    present_forbidden = forbidden & distributions
    if present_forbidden:
        raise RuntimeError(f"forbidden runtime distributions are installed: {sorted(present_forbidden)}")
    for name, expected_version in EXPECTED_DISTRIBUTIONS.items():
        actual_version = importlib.metadata.version(name)
        if actual_version != expected_version:
            raise RuntimeError(f"runtime distribution {name} is {actual_version}, " f"expected {expected_version}")
    missing_codecs = [codec for codec in ("jpg", "webp", "zlib") if not features.check(codec)]
    if missing_codecs:
        raise RuntimeError(f"Pillow codecs are unavailable: {missing_codecs}")
    external_imports = set(manifest["external_imports"]["eager"])
    external_imports.update(manifest["external_imports"]["deferred"])
    expected_external = {"PIL", "charset_normalizer", "lxml", "pysubs2", "requests"}
    if external_imports != expected_external:
        raise RuntimeError(
            "neutral engine external imports differ: "
            f"expected {sorted(expected_external)}, got {sorted(external_imports)}"
        )

    qt = sys.modules.get("PyQt6")
    if qt is None or not getattr(qt, "__file__", None):
        raise RuntimeError("the local PyQt6 compatibility shim was not imported")
    qt_path = Path(qt.__file__).resolve(strict=True)
    if root not in qt_path.parents:
        raise RuntimeError("PyQt6 resolved outside the local compatibility shim")

    print(
        json.dumps(
            {
                "engine_modules": len(manifest["modules"]),
                "implementation": platform.python_implementation(),
                "version": platform.python_version(),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
