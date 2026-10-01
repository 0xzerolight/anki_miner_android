#!/usr/bin/env python3
"""Export a language's expected tokens from desktop's own import path.

For a language whose desktop checkout has no ``tests/fixtures/<code>/tokens.jsonl``,
this writes one: it runs desktop's ``anki_miner.languages.<code>.tokenizer.build_tagger``
at the revision in ``tools/engine-sync/engine.lock`` over the profile's smoke sentence and
desktop's ``tests/fixtures/<code>/lemma_gold.jsonl`` (a record with a sentence is tagged
whole; a record without one is tagged word by word, as desktop's lemma corpus test does).

Run it with a Python 3.12 interpreter that has the language's engine at the pinned
versions (the runtime host-test lock). The desktop tree is taken from Git with
``git archive``, never from a working copy, and only the Android PyQt6 shim is added
beside it. The fixture's first line records the command, revision and package versions.

    python export_desktop_tokens.py --code tr --package zeyrek --package nltk --package regex \
        --output tests/python/android_bridge/languages/fixtures/tr/tokens.jsonl
"""

from __future__ import annotations

import argparse
import importlib
import importlib.metadata
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[5]
ENGINE_LOCK = REPO_ROOT / "tools/engine-sync/engine.lock"
PYQT6_SHIM = REPO_ROOT / "tools/engine-sync/overrides/PyQt6"


def _token(token: object) -> list[object]:
    feature = token.feature  # type: ignore[attr-defined]
    return [
        token.surface,  # type: ignore[attr-defined]
        feature.pos1,
        feature.pos2,
        feature.lemma,
        list(getattr(feature, "lemma_pos", ()) or ()),
    ]


def _texts(code: str, root: Path) -> list[str]:
    from anki_miner.languages.registry import get_profile

    texts = [get_profile(code).smoke_sentence]
    gold = root / "tests/fixtures" / code / "lemma_gold.jsonl"
    for line in gold.read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        if record["sentence"]:
            texts.append(record["sentence"])
        else:
            texts.extend(surface for surface, _lemma in record["tokens"])
    return texts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--code", required=True)
    parser.add_argument("--package", action="append", default=[], help="distribution whose version to record")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()

    desktop = Path(os.environ.get("ANKI_MINER_DESKTOP_REPO", REPO_ROOT.parent / "anki_miner"))
    revision = ENGINE_LOCK.read_text(encoding="utf-8").strip()
    with tempfile.TemporaryDirectory(prefix="anki-miner-desktop-tokens-") as temporary:
        root = Path(temporary)
        archive = subprocess.run(
            ["git", "-C", str(desktop), "archive", revision, "anki_miner", f"tests/fixtures/{arguments.code}"],
            check=True,
            capture_output=True,
        ).stdout
        subprocess.run(["tar", "-x", "-C", str(root)], input=archive, check=True)
        shutil.copytree(PYQT6_SHIM, root / "PyQt6")
        sys.path.insert(0, str(root))

        tagger = importlib.import_module(f"anki_miner.languages.{arguments.code}.tokenizer").build_tagger()
        records = [
            {"text": text, "tokens": [_token(token) for token in tagger(text)]} for text in _texts(arguments.code, root)
        ]

    header = {
        "provenance": f"desktop anki_miner {revision} (tools/engine-sync/engine.lock), desktop import path",
        "command": " ".join(
            ["python", "tests/python/android_bridge/languages/fixtures/export_desktop_tokens.py", *sys.argv[1:]]
        ),
        "python": platform.python_version(),
        "versions": {name: importlib.metadata.version(name) for name in sorted(arguments.package)},
    }
    lines = [json.dumps(header, ensure_ascii=False, sort_keys=True)]
    lines.extend(json.dumps(record, ensure_ascii=False, sort_keys=True) for record in records)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(records)} records to {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
