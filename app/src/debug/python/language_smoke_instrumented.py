"""Debug-only language smoke: tokenise each profile's ``smoke_sentence`` through the packaged engine.

A token row is ``[surface, pos1, pos2, lemma]``, the shape desktop's per-language tokenizer tests
compare. Expected rows, by provenance (desktop SHA = ``tools/engine-sync/engine.lock``,
a1259f4e5b8f97385660389eef6d76fb403c1e38):

* he: desktop ``tests/fixtures/he/tokens.jsonl`` row ``he01``, verbatim.
* id: desktop ``tests/fixtures/id/tokens.jsonl`` row ``id01`` plus the trailing ``.`` PUNCT row
  that desktop's own test filters out before comparing.
* th, ar, fa: no desktop row pins the whole smoke sentence with the full hazm data. th
  ``pos_corpus.jsonl`` ``th01`` and ar ``ar01`` list only the lemmas to mine, which these rows
  contain; fa ``tokens.jsonl``'s first row lists these content rows, but over a trimmed lexicon.
  Exported on the host from the vendored engine at the pinned versions, with ar and fa reading
  their pinned catalog archives::

      PYTHONPATH=app/src/debug/python:app/src/main/python \\
        "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests/bin/python" \\
        -c 'import language_smoke_instrumented as s; print(s.export("<home>", "<archive dir>"))'

he, id and th need no downloaded data, so the CI lane runs them. ar and fa need their
``language-data`` archives, so they run only on a local lane that pushes the archives first.

Engine imports stay function-local: ``bootstrap`` must set ``ANKI_MINER_HOME`` first.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

CI_CODES = ("he", "id", "th")
#: Code -> the pinned ``language-data`` catalog entry its tagger reads.
LOCAL_RESOURCES = {"ar": "ar-calima-msa", "fa": "fa-hazm-data"}

EXPECTED: dict[str, list[list[str]]] = {
    "he": [
        ["הילד", "WORD", "", "הילד"],
        ["קרא", "WORD", "", "קרא"],
        ["ספר", "WORD", "", "ספר"],
        ["מעניין", "WORD", "", "מעניין"],
        ["אתמול", "WORD", "", "אתמול"],
        [".", "PUNCT", "", "."],
    ],
    "id": [
        ["Saya", "WORD", "stopword", "saya"],
        ["sedang", "WORD", "stopword", "sedang"],
        ["membaca", "WORD", "", "membaca"],
        ["buku", "WORD", "", "buku"],
        ["di", "WORD", "stopword", "di"],
        ["rumah", "WORD", "", "rumah"],
        [".", "PUNCT", "", "."],
    ],
    "th": [
        ["วันนี้", "NOUN", "", "วันนี้"],
        ["อากาศ", "NOUN", "", "อากาศ"],
        ["ดีมาก", "ADV", "", "ดีมาก"],
    ],
    "ar": [
        ["ذهب", "verb", "", "ذهب"],
        ["الطالب", "noun", "clitic", "طالب"],
        ["إلى", "prep", "", "إلى"],
        ["المدرسة", "noun", "clitic", "مدرسة"],
        ["صباحا\N{ARABIC FATHATAN}", "noun", "", "صباح"],
        [".", "punc", "", "."],
    ],
    "fa": [
        ["من", "N", "stopword", "من"],
        ["هر", "DET", "stopword", "هر"],
        ["روز", "N", "", "روز"],
        ["به", "P", "stopword", "به"],
        ["مدرسه", "N", "", "مدرسه"],
        ["می\N{ZERO WIDTH NON-JOINER}روم", "V", "", "رفتن"],
        [".", "PUNCT", "", "."],
    ],
}


def smoke(code: str) -> str:
    """Tokenise *code*'s smoke sentence; report the rows next to the expected ones."""

    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.tagger_provider import get_tagger

    profile = get_profile(code)
    reason = None if profile.unavailable_reason is None else profile.unavailable_reason()
    tokens = [
        [token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma]
        for token in get_tagger(code)(profile.smoke_sentence)
    ]
    return json.dumps(
        {
            "code": code,
            "sentence": profile.smoke_sentence,
            "unavailable_reason": reason,
            "tokens": tokens,
            "expected": EXPECTED.get(code),
        },
        ensure_ascii=False,
    )


def install(code: str, archive_path: str) -> str:
    """Install *code*'s pinned ``language-data`` archive through the production bridge path."""

    from android_bridge.language_data import install_language_data

    return install_language_data(
        {
            "operationId": f"language-smoke-{code}",
            "resourceId": LOCAL_RESOURCES[code],
            "archivePath": archive_path,
        }
    )


def thai_footprint(home: str) -> str:
    """Where pythainlp lives once Thai has tokenised, and whether it made a data directory.

    ``th/_engine.py`` sets ``PYTHAINLP_READ_ONLY`` so the library never creates
    ``$HOME/pythainlp-data``; the package itself is whatever the runtime extracted to disk.
    """

    import pythainlp

    package = Path(pythainlp.__file__).resolve().parent
    files = [path for path in package.rglob("*") if path.is_file()]
    suffixes: dict[str, int] = {}
    for path in files:
        suffixes[path.suffix or "<none>"] = suffixes.get(path.suffix or "<none>", 0) + 1
    data_dirs = [
        str(candidate)
        for candidate in (
            Path(os.path.expanduser("~")) / "pythainlp-data",
            Path(home) / "pythainlp-data",
        )
        if candidate.exists()
    ]
    return json.dumps(
        {
            "package_dir": str(package),
            "file_count": len(files),
            "size_bytes": sum(path.stat().st_size for path in files),
            "suffixes": dict(sorted(suffixes.items())),
            "pythainlp_data_dirs": data_dirs,
            "env_read_only": os.environ.get("PYTHAINLP_READ_ONLY"),
            "env_offline": os.environ.get("PYTHAINLP_OFFLINE"),
        },
        sort_keys=True,
    )


def archive_name(code: str) -> str:
    """The file name a local lane pushes *code*'s pinned archive under: the catalog URL's last segment."""

    from android_bridge.resource_catalog import find_catalog_resource

    _, resource = find_catalog_resource(LOCAL_RESOURCES[code])
    return resource.archive.url.rsplit("/", 1)[1]


def evict(code: str) -> bool:
    """Drop *code*'s cached tagger, so the next language's peak is not stacked on it."""

    import gc

    from anki_miner.languages.tagger_provider import evict as evict_tagger

    evicted = evict_tagger(code)
    gc.collect()
    return evicted


def export(home: str, archive_dir: str) -> str:
    """Host exporter for :data:`EXPECTED`: every code's rows, ar and fa from their pinned archives."""

    from android_bridge.bootstrap import initialize

    initialize(home)
    rows = {}
    for code in (*CI_CODES, *LOCAL_RESOURCES):
        if code in LOCAL_RESOURCES:
            install(code, str(Path(archive_dir) / archive_name(code)))
        rows[code] = json.loads(smoke(code))["tokens"]
    return json.dumps(rows, ensure_ascii=False)
