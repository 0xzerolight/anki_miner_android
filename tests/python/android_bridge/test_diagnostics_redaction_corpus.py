"""Re-derive the Python half of the diagnostics redaction corpus from the live log pipeline.

``app/src/test/resources/contracts/diagnostics_redaction_v1/python_log.txt`` feeds the Kotlin
``DiagnosticsBundleRedactionTest``: verbose engine records carrying mined words in the scripts the
app mines, rendered by the production file-handler chain (``RunContextFilter``,
``DefaultLogPrivacyFilter``, ``_StructuredLogFormatter``). Comparing the committed file to a fresh
rendering keeps that Kotlin test honest: a change to the sentinel, the record grammar or the filter
fails here before the bundle test reads a stale fixture. (Not ``*.log``: the repository ignores it.)

After an intended change, regenerate from the repository root with
``python3.13 tests/python/android_bridge/test_diagnostics_redaction_corpus.py``.
"""

from __future__ import annotations

import logging
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS = REPO_ROOT / "app/src/test/resources/contracts/diagnostics_redaction_v1"
PYTHON_LOG = CORPUS / "python_log.txt"
MINED_WORDS = CORPUS / "mined_words.txt"

_RUN_ID = "run_" + "c" * 32
_FIRST_RECORD_AT = 1791547200.0  # 2026-10-09T12:00:00Z
# (logger, level, format, arguments): engine call shapes taken from the vendored tree where one
# exists (it/morphology, vi/tokenizer, word_filter, sentence_edit, reading/_util), one record per
# mined script, a path argument, a DEBUG exception argument, and one bridge-owned line that must
# stay readable.
_RECORDS: tuple[tuple[str, int, str, tuple[object, ...]], ...] = (
    (
        "anki_miner.languages.it.morphology",
        logging.DEBUG,
        "Lemma %r not attested; fronting the surface %r",
        ("andare", "andiamo"),
    ),
    (
        "anki_miner.languages.vi.tokenizer",
        logging.DEBUG,
        "Vietnamese word %r not found in its line; dropped",
        ("tiếng",),
    ),
    (
        "anki_miner.services.word_filter",
        logging.WARNING,
        "line expansion: no cue matches %r at %.3fs; keeping the original line",
        ("¿Dónde está mi corazón?", 12.5),
    ),
    (
        "anki_miner.services.sentence_edit",
        logging.WARNING,
        "sentence edit: no mineable word at %d-%d in %r; keeping the original word %r",
        (7, 12, "Я тебя люблю", "люблю"),
    ),
    ("anki_miner.languages.ar.morphology", logging.DEBUG, "Root %r for surface %r", ("كتب", "كتاب")),
    (
        "anki_miner.languages.ko.tokenizer",
        logging.DEBUG,
        "Token %s has no dictionary form; keeping %s",
        ("사랑해", "사랑"),
    ),
    ("anki_miner.languages.th.tokenizer", logging.DEBUG, "Thai segment %r", ("ความรัก",)),
    (
        "anki_miner.services.media_extractor",
        logging.DEBUG,
        "Extracted clip %s",
        ("/data/user/0/com.ankiminer.android/cache/media/andiamo_91000_1.opus",),
    ),
    (
        "anki_miner.services.reading._util",
        logging.DEBUG,
        "Reading archive member missing: archive=%s entry=%s error=%s detail=%s",
        (
            "/data/user/0/com.ankiminer.android/cache/reading/volume.cbz",
            "andiamo vol 1/0001.jpg",
            "KeyError",
            KeyError("There is no item named 'andiamo vol 1/0001.jpg' in the archive"),
        ),
    ),
    (
        "android_bridge.anki_adapter",
        logging.INFO,
        "Stored media asset [%s] purpose=%s kind=%s",
        ("asset_" + "d" * 32, "card", "audio"),
    ),
)


def render_python_log() -> str:
    """Render every corpus record exactly as the installed file handler writes it."""

    from android_bridge import bootstrap, log_context

    log_context.set_active_run(_RUN_ID)
    log_context.set_first_party_log_level(logging.DEBUG)
    try:
        formatter = bootstrap._StructuredLogFormatter()
        lines = []
        for index, (name, level, message, arguments) in enumerate(_RECORDS):
            record = logging.LogRecord(name, level, __file__, 1, message, arguments, None)
            record.created = _FIRST_RECORD_AT + index
            record.msecs = 250.0
            log_context.RunContextFilter().filter(record)
            log_context.DefaultLogPrivacyFilter().filter(record)
            lines.append(formatter.format(record))
        return "\n".join(lines) + "\n"
    finally:
        log_context.set_active_run(None)
        log_context.set_first_party_log_level(logging.INFO)


def _secrets() -> list[str]:
    return [line.split(" ", 1)[1] for line in MINED_WORDS.read_text(encoding="utf-8").splitlines() if line]


def test_committed_python_log_matches_the_live_pipeline() -> None:
    assert PYTHON_LOG.read_text(encoding="utf-8") == render_python_log()


def test_every_mined_word_in_an_engine_record_sits_inside_a_sentinel_span() -> None:
    from android_bridge.log_context import SENTINEL_CLOSE, SENTINEL_OPEN

    rendered = render_python_log()
    # Written raw: json.dumps(ensure_ascii=False) must not turn the sentinels into \u escapes.
    assert SENTINEL_OPEN in rendered
    assert "\\u27e6" not in rendered
    spans = re.compile(f"{SENTINEL_OPEN}[^{SENTINEL_OPEN}{SENTINEL_CLOSE}]*{SENTINEL_CLOSE}")
    outside = spans.sub("", rendered)
    present = [secret for secret in _secrets() if secret in rendered]
    assert present, "the corpus must carry mined words to protect"
    assert [secret for secret in present if secret in outside] == []
    # The bridge's own line is not an engine record and stays readable.
    assert "purpose=card kind=audio" in outside


if __name__ == "__main__":
    sys.path.insert(0, str(REPO_ROOT / "app/src/main/python"))
    PYTHON_LOG.parent.mkdir(parents=True, exist_ok=True)
    PYTHON_LOG.write_text(render_python_log(), encoding="utf-8")
