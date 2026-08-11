#!/usr/bin/env python3
"""Derive desktop HEAD v2 output for advisory semantic-drift reporting."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from engine_sync.head_golden_exporter import (
    HeadGoldenExporterError,
    materialize_desktop_head_exporter,
)


def _case_map(value: object) -> dict[str, object] | None:
    if not isinstance(value, list):
        return None
    result: dict[str, object] = {}
    for item in value:
        if not isinstance(item, dict):
            return None
        identifier = item.get("id")
        if not isinstance(identifier, str) or not identifier or identifier in result:
            return None
        result[identifier] = item
    return result


def semantic_drift(
    committed: dict[str, object],
    derived: dict[str, object],
) -> tuple[str, ...]:
    drift: list[str] = []
    committed_status = committed.get("section_status")
    derived_status = derived.get("section_status")
    if isinstance(committed_status, dict) and isinstance(derived_status, dict):
        for section in sorted(set(committed_status) | set(derived_status)):
            if committed_status.get(section) != derived_status.get(section):
                drift.append(f"section_status.{section}")
    elif committed_status != derived_status:
        drift.append("section_status")

    committed_cases = committed.get("cases")
    derived_cases = derived.get("cases")
    if isinstance(committed_cases, dict) and isinstance(derived_cases, dict):
        for section in sorted(set(committed_cases) | set(derived_cases)):
            committed_section = committed_cases.get(section)
            derived_section = derived_cases.get(section)
            if committed_section == derived_section:
                continue
            committed_by_id = _case_map(committed_section)
            derived_by_id = _case_map(derived_section)
            if committed_by_id is None or derived_by_id is None:
                drift.append(f"cases.{section}")
                continue
            for identifier in sorted(set(committed_by_id) | set(derived_by_id)):
                if committed_by_id.get(identifier) != derived_by_id.get(identifier):
                    drift.append(f"cases.{section}.{identifier}")
    elif committed_cases != derived_cases:
        drift.append("cases")
    return tuple(drift)


def _load_fixture(path: Path) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise HeadGoldenExporterError(f"cannot read golden fixture {path}: {error}") from error
    if not isinstance(document, dict):
        raise HeadGoldenExporterError(f"golden fixture is not an object: {path}")
    return document


def report_semantic_drift(
    committed_path: Path,
    derived_path: Path,
) -> tuple[str, ...]:
    drift = semantic_drift(
        _load_fixture(committed_path),
        _load_fixture(derived_path),
    )
    if drift:
        print("desktop HEAD semantic drift detected:")
        for identifier in drift:
            print(f"  {identifier}")
    else:
        print("desktop HEAD semantic drift: none")
    return drift


def paths_alias(first: Path, second: Path) -> bool:
    if first.resolve() == second.resolve():
        return True
    return first.exists() and first.samefile(second)


def main() -> int:
    script_root = Path(__file__).resolve().parent
    project_root = script_root.parents[1]
    committed_fixture = project_root / "golden/engine-v2.json"
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
        if paths_alias(output, committed_fixture):
            raise HeadGoldenExporterError(
                "--output must not replace committed golden/engine-v2.json"
            )
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
        report_semantic_drift(committed_fixture, output)
    except (HeadGoldenExporterError, OSError, subprocess.TimeoutExpired) as error:
        print(f"desktop HEAD golden warning: {error}", file=sys.stderr)
        return 1
    print(f"wrote desktop HEAD fixture to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
