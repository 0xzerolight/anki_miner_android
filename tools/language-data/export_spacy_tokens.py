#!/usr/bin/env python3
"""Export desktop's own tagger output for a spaCy language, as Android test fixtures.

Runs the desktop engine at ``tools/engine-sync/engine.lock`` exactly as desktop
does: ``anki_miner.languages.<code>.tokenizer.build_tagger()`` imports the model
package by name from a pack root on ``sys.path``, and pymorphy3 finds its
dictionaries through their entry points. Android loads the same data by path
(``languages/_spaced/android_models``), so a bridge test comparing its tagger
with this file checks that nothing changes between the two.

Run it with the runtime host-test venv (Python 3.12 at the APK's pins) after
``fetch_language_data.py <code>`` has cached the language's data::

    .venv-runtime/bin/python tools/language-data/export_spacy_tokens.py ru \\
        --out tests/python/android_bridge/languages/fixtures/ru/tokens.jsonl

Sentences: the profile's ``smoke_sentence``, then every ``sentence`` of desktop's
``tests/fixtures/<code>/pos_corpus.jsonl`` and ``tokens.jsonl`` (where present).
Each output line is ``{"id", "sentence", "tokens"}``, a token being
``[surface, pos1, pos2, lemma, kana, morph]``. ``#`` lines are the header: the
desktop revision, the versions and archives used, and this command.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import importlib.metadata
import json
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_language_data import FetchError, cache_name, cache_root, language_data  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
ENGINE_LOCK = REPO_ROOT / "tools/engine-sync/engine.lock"
PYQT_SHIM = REPO_ROOT / "app/src/main/python/PyQt6"
DESKTOP_ENV = "ANKI_MINER_DESKTOP_REPO"
#: The distributions whose versions decide the tags; each must equal the runtime host-test lock.
PINNED = ("spacy", "thinc", "blis", "numpy", "srsly", "pymorphy3", "dawg2-python", "spacy-legacy")
FIXTURE_FILES = ("pos_corpus.jsonl", "tokens.jsonl")


def _lock_versions() -> dict[str, str]:
    versions = {}
    for line in (REPO_ROOT / "requirements-runtime-host-test.lock").read_text(encoding="utf-8").splitlines():
        if "==" in line and not line.startswith((" ", "#")):
            name, _, rest = line.partition("==")
            versions[name.lower()] = rest.split()[0]
    return versions


def _check_versions() -> dict[str, str]:
    expected = _lock_versions()
    found = {name: importlib.metadata.version(name) for name in PINNED}
    wrong = {name: version for name, version in found.items() if expected.get(name) != version}
    if wrong or sys.version_info[:2] != (3, 12):
        raise SystemExit(f"run with the runtime host-test venv (Python 3.12, lock pins); mismatched: {wrong}")
    return found


def _desktop_tree(desktop: Path, revision: str, code: str, into: Path) -> Path:
    paths = ["anki_miner"] + [f"tests/fixtures/{code}"]
    archive = subprocess.run(
        ["git", "-C", str(desktop), "archive", revision, *paths], check=True, capture_output=True
    ).stdout
    with tarfile.open(fileobj=__import__("io").BytesIO(archive)) as tree:
        tree.extractall(into, filter="data")
    return into


def _pack_root(code: str, cache: Path, into: Path) -> list[tuple[str, str]]:
    """Extract each data archive of *code* whole (package and ``.dist-info``), as desktop's pack root holds it."""

    used = []
    for entry in language_data(REPO_ROOT, code):
        path = cache / cache_name(entry["archive"])
        if not path.is_file():
            raise FetchError(f"{entry['resourceId']} is not cached: run fetch_language_data.py {code}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["archive"]["sha256"]:
            raise FetchError(f"{path} does not match its pin")
        with zipfile.ZipFile(path) as wheel:
            wheel.extractall(into)
        used.append((entry["archive"]["url"].rsplit("/", 1)[1], entry["archive"]["sha256"]))
    return used


def _sentences(fixtures: Path, smoke: str) -> list[dict[str, str]]:
    rows = [{"id": "smoke", "sentence": smoke}]
    for name in FIXTURE_FILES:
        path = fixtures / name
        if path.is_file():
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    row = json.loads(line)
                    rows.append({"id": f"{path.stem}:{row['id']}", "sentence": row["sentence"]})
    return rows


def token_row(token: Any) -> list[str]:
    feature = token.feature
    return [token.surface, feature.pos1, feature.pos2, feature.lemma, feature.kana, getattr(token, "morph", "")]


def export(code: str, out: Path, desktop: Path, cache: Path, command: str) -> int:
    versions = _check_versions()
    revision = ENGINE_LOCK.read_text(encoding="utf-8").strip()
    with tempfile.TemporaryDirectory(prefix="export-spacy-tokens-") as scratch:
        root = Path(scratch)
        source = _desktop_tree(desktop, revision, code, root / "desktop")
        shutil.copytree(PYQT_SHIM, root / "shim" / "PyQt6")
        used = _pack_root(code, cache, root / "pack")
        sys.path[:0] = [str(source), str(root / "shim"), str(root / "pack")]
        importlib.invalidate_caches()
        from anki_miner.languages.registry import get_profile

        profile = get_profile(code)
        tagger = importlib.import_module(f"anki_miner.languages.{code}.tokenizer").build_tagger()
        rows = _sentences(source / "tests" / "fixtures" / code, profile.smoke_sentence)
        for row in rows:
            row["tokens"] = [token_row(token) for token in tagger(row["sentence"])]
    header = [
        f"# desktop anki_miner {revision}: anki_miner.languages.{code}.tokenizer.build_tagger(), package import",
        "# " + ", ".join(f"{name} {version}" for name, version in versions.items()),
        *(f"# archive {name} sha256 {digest}" for name, digest in used),
        f"# command: {command}",
    ]
    out.parent.mkdir(parents=True, exist_ok=True)
    body = [json.dumps(row, ensure_ascii=False) for row in rows]
    out.write_text("\n".join(header + body) + "\n", encoding="utf-8")
    return len(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("code", help="a vendored spaCy language code")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--desktop-repo", type=Path, help=f"desktop checkout (default: ${DESKTOP_ENV})")
    parser.add_argument("--cache", type=Path, help="language-data cache (default: fetch_language_data.py's)")
    args = parser.parse_args(argv)
    import os

    desktop = args.desktop_repo or (Path(os.environ[DESKTOP_ENV]) if os.environ.get(DESKTOP_ENV) else None)
    if desktop is None:
        parser.error(f"pass --desktop-repo or set {DESKTOP_ENV}")
    command = shlex.join(
        ["tools/language-data/export_spacy_tokens.py", args.code, "--out", os.path.relpath(args.out, REPO_ROOT)]
    )
    try:
        count = export(args.code, args.out, desktop, args.cache or cache_root(), command)
    except FetchError as exc:
        print(f"export_spacy_tokens: {exc}", file=sys.stderr)
        return 1
    print(f"{args.out}: {count} sentences")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
