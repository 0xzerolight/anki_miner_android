"""Write the committed calima-msa-r13 subset ``calima_msa/morphology.db`` that ``test_ar.py`` installs.

Run from the repository root with the runtime host-test interpreter and the full pack (the pinned
``ar-calima-msa`` archive's ``morphology.db``, inner sha256 ``195bc25a…``)::

    $ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests/bin/python \\
        tests/python/android_bridge/languages/fixtures/ar/make_calima_subset.py <full morphology.db>

The header sections are kept verbatim. The prefix, suffix and stem rows are the ones whose key the
analyzer looked up while the real tagger and the real profile parser read ``tokens.jsonl`` and the
smoke sentence over the full database; the three compatibility tables keep the rows between kept
categories. The analyzer reaches entries only through those keys and categories, so every analysis
of those inputs is unchanged. Any other word gets fewer analyses than on a device.
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[5] / "app" / "src" / "main" / "python"))
os.environ.setdefault("ANKI_MINER_HOME", tempfile.mkdtemp(prefix="calima-subset-"))


class _Recording(dict):
    def __init__(self, data: dict) -> None:
        super().__init__(data)
        self.seen: set[str] = set()

    def get(self, key, default=None):  # type: ignore[override]
        self.seen.add(key)
        return super().get(key, default)


def _record_lookups(full_db: Path) -> tuple[set[str], set[str], set[str]]:
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.languages import tagger_provider
    from anki_miner.languages.ar._calima.analyzer import Analyzer
    from anki_miner.languages.ar._calima.database import MorphologyDB
    from anki_miner.languages.ar.tokenizer import ArabicTagger
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.switching import switch_language
    from anki_miner.models.reading import ReadingUnit
    from anki_miner.services.tagger import LockedTagger

    db = MorphologyDB(full_db)
    db.prefix_hash, db.suffix_hash, db.stem_hash = (
        _Recording(db.prefix_hash),
        _Recording(db.suffix_hash),
        _Recording(db.stem_hash),
    )
    tagger = ArabicTagger(Analyzer(db))
    tagger_provider._TAGGERS["ar"] = LockedTagger(tagger)
    rows = [
        json.loads(line)
        for line in (HERE / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
        if not line.startswith("#")
    ]
    profile = get_profile("ar")
    for row in rows:
        tagger(row["surface"])
    tagger(profile.smoke_sentence)
    parser = profile.create_parser(switch_language(AnkiMinerConfig(), "ar"))
    parser.parse_text_units([ReadingUnit(text=profile.smoke_sentence, index=0, location_label="t")], False)
    return db.prefix_hash.seen, db.suffix_hash.seen, db.stem_hash.seen


def _section(lines: list[str], start: str, end: str) -> tuple[int, int]:
    return lines.index(start + "\n") + 1, lines.index(end + "\n")


def main(full_db: Path) -> None:
    prefixes, suffixes, stems = _record_lookups(full_db)
    lines = full_db.read_text(encoding="utf-8").splitlines(keepends=True)
    bounds = {
        "A": _section(lines, "###PREFIXES###", "###SUFFIXES###"),
        "C": _section(lines, "###SUFFIXES###", "###STEMS###"),
        "B": _section(lines, "###STEMS###", "###TABLE AB###"),
    }
    seen = {"A": prefixes, "C": suffixes, "B": stems}
    kept: dict[str, list[str]] = {}
    cats: dict[str, set[str]] = {}
    for side, (start, end) in bounds.items():
        rows = [line for line in lines[start:end] if line.split("\t")[0].strip() in seen[side]]
        kept[side] = rows
        cats[side] = {line.split("\t")[1] for line in rows}

    def table(start: str, end: str | None, first: str, second: str) -> list[str]:
        lo = lines.index(start + "\n") + 1
        hi = lines.index(end + "\n") if end else len(lines)
        return [
            line
            for line in lines[lo:hi]
            if line.strip() and line.split()[0] in cats[first] and line.split()[1] in cats[second]
        ]

    out_lines = (
        lines[: bounds["A"][0]]
        + kept["A"]
        + ["###SUFFIXES###\n"]
        + kept["C"]
        + ["###STEMS###\n"]
        + kept["B"]
        + ["###TABLE AB###\n"]
        + table("###TABLE AB###", "###TABLE BC###", "A", "B")
        + ["###TABLE BC###\n"]
        + table("###TABLE BC###", "###TABLE AC###", "B", "C")
        + ["###TABLE AC###\n"]
        + table("###TABLE AC###", None, "A", "C")
    )
    out = HERE / "calima_msa" / "morphology.db"
    out.parent.mkdir(exist_ok=True)
    out.write_text("".join(out_lines), encoding="utf-8")
    print(f"{out}: {len(out_lines)} of {len(lines)} lines, {out.stat().st_size} bytes")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
