"""Export the zh token fixture from the DESKTOP engine at the pinned engine.lock SHA.

Desktop has no ``tests/fixtures/zh/tokens.jsonl``, so the expected tokens come
from desktop's own ``build_tagger()`` and ``ZhReadingSupport`` run on its source
tree at the pinned revision (extracted with ``git archive``, so the desktop
checkout's HEAD does not matter), in a CPython 3.12 venv holding the pinned
jieba, pypinyin and opencc (the runtime host-test lock). Sentences: the profile's
smoke sentence, its traditional spelling, and the first twelve rows of desktop's
``tests/fixtures/zh/pos_corpus.jsonl``.

    <venv>/bin/python tests/python/android_bridge/languages/fixtures/zh/export_tokens.py \\
        --desktop-repo "$ANKI_MINER_DESKTOP_REPO"
"""

from __future__ import annotations

import argparse
import importlib.metadata
import io
import json
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[5]
OUTPUT = HERE / "tokens.json"
COMMAND = (
    "<venv>/bin/python tests/python/android_bridge/languages/fixtures/zh/export_tokens.py "
    '--desktop-repo "$ANKI_MINER_DESKTOP_REPO"'
)
CORPUS_ROWS = 12
TRADITIONAL_SMOKE = "我今天早上吃了三個蘋果。"


def _features(token: object) -> list[str]:
    feature = token.feature  # type: ignore[attr-defined]
    return [feature.pos1, feature.pos2, feature.lemma]


def _git(repo: Path, *args: str) -> bytes:
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True).stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--desktop-repo", type=Path, required=True)
    args = parser.parse_args()
    sha = (REPO_ROOT / "tools/engine-sync/engine.lock").read_text(encoding="utf-8").strip()
    corpus = [
        json.loads(line)
        for line in _git(args.desktop_repo, "show", f"{sha}:tests/fixtures/zh/pos_corpus.jsonl")
        .decode("utf-8")
        .splitlines()
        if line.strip()
    ][:CORPUS_ROWS]
    with tempfile.TemporaryDirectory() as temporary:
        archive = _git(args.desktop_repo, "archive", "--format=tar", sha, "anki_miner")
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(temporary, filter="data")
        # Desktop's services package imports PyQt6 at module top; the Android
        # QCoreApplication shim stands in for it (nothing here translates).
        sys.path[:0] = [temporary, str(REPO_ROOT / "tools/engine-sync/overrides/PyQt6/..")]
        from anki_miner.languages.zh import ZH_SMOKE_SENTENCE
        from anki_miner.languages.zh.reading import ZhReadingSupport
        from anki_miner.languages.zh.tokenizer import build_tagger

        tagger = build_tagger()
        reading = ZhReadingSupport()
        sentences = [("smoke", ZH_SMOKE_SENTENCE), ("smoke-traditional", TRADITIONAL_SMOKE)]
        sentences += [(row["id"], row["sentence"]) for row in corpus]
        cases = [
            {
                "id": case_id,
                "sentence": sentence,
                "tokens": [
                    [token.surface, *_features(token), reading.word_reading(token)] for token in tagger.parse(sentence)
                ],
            }
            for case_id, sentence in sentences
        ]
    document = {
        "provenance": {
            "desktopSha": sha,
            "source": "desktop anki_miner.languages.zh build_tagger() + ZhReadingSupport.word_reading",
            "corpus": f"desktop tests/fixtures/zh/pos_corpus.jsonl, first {CORPUS_ROWS} rows",
            "python": sys.version.split()[0],
            "packages": {name: importlib.metadata.version(name) for name in ("jieba", "opencc", "pypinyin")},
            "command": COMMAND,
            "tokenFields": ["surface", "pos1", "pos2", "lemma", "reading"],
        },
        "cases": cases,
    }
    # One case per line keeps a re-export's diff readable.
    rows = ",\n".join("  " + json.dumps(case, ensure_ascii=False) for case in document.pop("cases"))
    head = json.dumps(document, ensure_ascii=False, indent=1)[:-2]
    OUTPUT.write_text(f'{head},\n "cases": [\n{rows}\n ]\n}}\n', encoding="utf-8")
    print(f"wrote {len(cases)} cases to {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
