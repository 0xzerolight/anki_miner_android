"""Debug-only measurements for the physical ARM64 S1a acceptance gate."""

from __future__ import annotations

import hashlib
import json
import re
import time
from dataclasses import replace
from pathlib import Path


_JAPANESE = re.compile(r"[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff々〆〻]")
_SELECTION_COUNT = 100
_WORKLOAD_ID = "reading-process-reading-v1"


class _MeasuredParser:
    def __init__(self, parser: object) -> None:
        self._parser = parser
        self.elapsed_ms = 0.0
        self.word_count = 0
        self.lemma_count = 0

    def parse_text_units(self, units: list[object], want_line_index: bool) -> tuple:
        started = time.perf_counter_ns()
        result = self._parser.parse_text_units(units, want_line_index)
        self.elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
        words, _line_index, counts = result
        self.word_count = len(words)
        self.lemma_count = len(counts)
        return result


class _OfflineDefinitions:
    def get_definitions_batch(
        self,
        pairs: list[tuple[str, str | None]],
        progress_callback: object | None,
        fallback_context: dict[str, tuple[str, str | None]],
    ) -> list[str]:
        del progress_callback, fallback_context
        return [f'<div class="definition">acceptance:{term}</div>' for term, _ in pairs]

    def close(self) -> None:
        pass


class _CardSink:
    def __init__(self) -> None:
        self.last_created_note_ids: list[int] = []
        self.last_media_store_failures = 0
        self.last_skipped_duplicates = 0
        self.card_payload_count = 0

    def verify_card_target(self) -> None:
        pass

    def create_cards_batch(
        self,
        card_data: list[object],
        progress_callback: object | None = None,
    ) -> int:
        del progress_callback
        self.card_payload_count = len(card_data)
        self.last_created_note_ids = list(range(1, self.card_payload_count + 1))
        return self.card_payload_count


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


def measure_representative_mining(path: str) -> str:
    """Run one bounded but complete production reading mine on a real corpus."""

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
    from anki_miner.models.reading import ReadingDocument, ReadingUnit
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.presenters import NullPresenter
    from anki_miner.services.subtitle_parser import SubtitleParserService
    from anki_miner.services.word_filter import WordFilterService

    units = [
        ReadingUnit(text=value, index=index, location_label=f"line {index + 1}")
        for index, value in enumerate(paragraphs)
    ]
    config = replace(
        AnkiMinerConfig(),
        anki_fields={},
        include_known_words=True,
        bypass_optional_filters=True,
        reading_min_occurrence=1,
        use_i_plus_one_filter=False,
    )
    parser = _MeasuredParser(SubtitleParserService(config))
    definitions = _OfflineDefinitions()
    cards = _CardSink()
    processor = EpisodeProcessor(
        config=config,
        subtitle_parser=parser,
        word_filter=WordFilterService(config),
        media_extractor=object(),
        definition_service=definitions,
        anki_service=cards,
        presenter=NullPresenter(),
    )
    document = ReadingDocument(
        title="S1a representative novel",
        kind="book",
        series="Books",
        episode="S1a representative novel",
        units=units,
    )
    candidate_count = 0

    def curate(words: list[object]) -> list[object]:
        nonlocal candidate_count
        candidate_count = len(words)
        if candidate_count < _SELECTION_COUNT:
            raise AssertionError(
                "the physical acceptance novel must produce at least "
                f"{_SELECTION_COUNT} mineable candidates, got {candidate_count}"
            )
        return list(words[:_SELECTION_COUNT])

    started = time.perf_counter_ns()
    try:
        result = processor.process_reading(document, curation_callback=curate)
    finally:
        processor.close()
    mining_elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    if parser.elapsed_ms <= 0 or mining_elapsed_ms <= 0:
        raise AssertionError("the production novel mine returned invalid timing state")
    if result.errors:
        raise AssertionError(f"the production novel mine failed: {result.errors}")
    if (
        result.new_words_found != _SELECTION_COUNT
        or result.cards_created != _SELECTION_COUNT
        or len(result.card_ids) != _SELECTION_COUNT
        or cards.card_payload_count != _SELECTION_COUNT
    ):
        raise AssertionError("the production novel mine did not construct all selected cards")
    rate = japanese_character_count * 1000.0 / parser.elapsed_ms
    return json.dumps(
        {
            "candidate_count": candidate_count,
            "card_payload_count": cards.card_payload_count,
            "cards_created": result.cards_created,
            "characters_per_second": rate,
            "completed": True,
            "corpus_sha256": hashlib.sha256(raw).hexdigest(),
            "full_mining_elapsed_ms": mining_elapsed_ms,
            "japanese_character_count": japanese_character_count,
            "lemma_count": parser.lemma_count,
            "peak_rss_bytes": _peak_rss_bytes(),
            "phase1_elapsed_ms": parser.elapsed_ms,
            "selected_count": result.new_words_found,
            "text_unit_count": len(units),
            "total_words_found": result.total_words_found,
            "word_count": parser.word_count,
            "workload_id": _WORKLOAD_ID,
        },
        sort_keys=True,
        separators=(",", ":"),
    )
