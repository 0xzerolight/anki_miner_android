#!/usr/bin/env python3
"""Derive desktop HEAD v2 output for advisory semantic-drift reporting."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from engine_sync.head_golden_exporter import (
    HeadGoldenExporterError,
    materialize_desktop_head_exporter,
)


def main() -> int:
    script_root = Path(__file__).resolve().parent
    project_root = script_root.parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", required=True, type=Path)
    parser.add_argument("--desktop-root", required=True, type=Path)
    parser.add_argument("--dicdir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    args = parser.parse_args()
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be positive")
    python = args.python.expanduser().absolute()
    if not python.is_file() or not os.access(python, os.X_OK):
        parser.error("--python must be executable")
    try:
        dicdir = args.dicdir.resolve(strict=True)
        if not dicdir.is_dir():
            raise HeadGoldenExporterError("--dicdir must be a directory")
        output = args.output.expanduser().absolute()
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="anki-miner-head-golden-v2-") as temporary:
            root = Path(temporary)
            exporter, _revision = materialize_desktop_head_exporter(
                args.desktop_root,
                root / "exporter",
            )
            home = root / "home"
            home.mkdir()
            environment = {
                "HOME": os.fspath(home),
                "LANG": "C.UTF-8",
                "LC_ALL": "C.UTF-8",
                "PATH": os.environ.get("PATH", ""),
                "PYTHONDONTWRITEBYTECODE": "1",
                "PYTHONHASHSEED": "0",
                "PYTHONIOENCODING": "utf-8",
                "PYTHONNOUSERSITE": "1",
                "PYTHONUTF8": "1",
                "SOURCE_DATE_EPOCH": "315532800",
                "TZ": "UTC",
            }
            command = [
                os.fspath(python),
                "-s",
                "-P",
                "-B",
                os.fspath(script_root / "engine_sync/_golden_v2_bootstrap.py"),
                os.fspath(exporter),
                "--schema-version",
                "2",
                "--engine-root",
                os.fspath(args.desktop_root.resolve(strict=True)),
                "--corpus",
                os.fspath(project_root / "golden/corpus/tokenizer-v1.json"),
                "--v2-input",
                os.fspath(project_root / "golden/corpus/engine-v2-input.json"),
                "--dicdir",
                os.fspath(dicdir),
                "--compact",
                "--output",
                os.fspath(output),
            ]
            completed = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                env=environment,
                timeout=args.timeout_seconds,
            )
            if completed.returncode != 0:
                detail = completed.stderr.strip() or completed.stdout.strip()
                raise HeadGoldenExporterError(
                    f"desktop HEAD exporter failed: {detail}"
                )
    except (HeadGoldenExporterError, OSError, subprocess.TimeoutExpired) as error:
        print(f"desktop HEAD golden warning: {error}", file=sys.stderr)
        return 1
    print(f"wrote desktop HEAD fixture to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
