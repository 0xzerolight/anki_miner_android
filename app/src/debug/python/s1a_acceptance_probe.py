"""Debug-only measurements for the physical ARM64 S1a acceptance gate."""

from __future__ import annotations

import hashlib
import json
import re
import time
from pathlib import Path


_JAPANESE = re.compile(r"[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff々〆〻]")


def initialize(dicdir: str, expected_hash: str, expected_home: str) -> str:
    """Reach the frozen ready-to-mine boundary through production engine seams."""

    from android_bridge.bootstrap import require_initialized
    from android_bridge.tokenizer_selection import configure_tokenizer_backend
    from android_bridge.unidic_resource import register_unidic

    home = require_initialized(expected_home)
    registration = register_unidic(
        dicdir,
        resource_id=f"acceptance-unidic-{expected_hash[:16]}",
        expected_tree_sha256=expected_hash,
    )
    backend = configure_tokenizer_backend("s1a")
    from anki_miner.services.tagger import get_shared_tagger

    tagger = get_shared_tagger()
    import anki_miner.orchestration.episode_processor  # noqa: F401

    return json.dumps(
        {
            "backend": backend,
            "dictionary_sha256": registration.tree_sha256,
            "home": home,
            "shared_tagger_type": type(tagger).__name__,
            "tagger_path": "engine_shared_tagger",
        },
        sort_keys=True,
        separators=(",", ":"),
    )


def _peak_rss_bytes() -> int:
    for line in Path("/proc/self/status").read_text(encoding="utf-8").splitlines():
        if line.startswith("VmHWM:"):
            fields = line.split()
            if len(fields) == 3 and fields[2] == "kB":
                value = int(fields[1]) * 1024
                if value > 0:
                    return value
    raise AssertionError("the kernel did not expose a positive VmHWM peak RSS")


def measure_novel(path: str) -> str:
    """Measure the exact production reading tokenization phase on a real corpus."""

    source = Path(path)
    raw = source.read_bytes()
    text = raw.decode("utf-8", errors="strict")
    japanese_character_count = len(_JAPANESE.findall(text))
    if japanese_character_count < 50_000:
        raise AssertionError(
            "the physical acceptance novel must contain at least 50,000 "
            f"Japanese characters, got {japanese_character_count}"
        )
    paragraphs = [line.strip() for line in text.splitlines() if line.strip()]
    if not paragraphs:
        raise AssertionError("the physical acceptance novel has no non-empty text units")

    from anki_miner.config import AnkiMinerConfig
    from anki_miner.models.reading import ReadingUnit
    from anki_miner.services.subtitle_parser import SubtitleParserService

    units = [
        ReadingUnit(text=value, index=index, location_label=f"line {index + 1}")
        for index, value in enumerate(paragraphs)
    ]
    parser = SubtitleParserService(AnkiMinerConfig())
    started = time.perf_counter_ns()
    words, line_index, counts = parser.parse_text_units(units, want_line_index=False)
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    if line_index is not None or elapsed_ms <= 0:
        raise AssertionError("the production novel parse returned invalid measurement state")
    rate = japanese_character_count * 1000.0 / elapsed_ms
    return json.dumps(
        {
            "characters_per_second": rate,
            "corpus_sha256": hashlib.sha256(raw).hexdigest(),
            "elapsed_ms": elapsed_ms,
            "japanese_character_count": japanese_character_count,
            "lemma_count": len(counts),
            "peak_rss_bytes": _peak_rss_bytes(),
            "text_unit_count": len(units),
            "word_count": len(words),
        },
        sort_keys=True,
        separators=(",", ":"),
    )
