"""Debug-only emulator smoke for the vendored engine composition."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


def _engine_modules() -> list[str]:
    return sorted(
        name
        for name in sys.modules
        if name == "anki_miner" or name.startswith("anki_miner.")
    )


def preflight(expected_home: str | None = None) -> str:
    """Report bootstrap ordering without importing any additional engine module."""

    loaded_before = _engine_modules()
    from android_bridge.bootstrap import (
        engine_modules_before_initialize,
        require_initialized,
    )

    failure_code = None
    home = None
    try:
        home = require_initialized(expected_home)
    except Exception as exc:
        failure_code = getattr(exc, "code", type(exc).__name__)
    loaded_after = _engine_modules()
    result = {
        "bootstrap_engine_modules_before": list(
            engine_modules_before_initialize() or ()
        ),
        "engine_modules_after": loaded_after,
        "engine_modules_before": loaded_before,
    }
    if failure_code is not None:
        result["require_initialized_failure"] = failure_code
    if home is not None:
        result["home"] = home
    return json.dumps(result, sort_keys=True)


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _elapsed_ms(start_ns: int) -> float:
    return (time.perf_counter_ns() - start_ns) / 1_000_000


def _word_record(word: Any) -> dict[str, Any]:
    return {
        "surface": word.surface,
        "lemma": word.lemma,
        "orth_base": word.orth_base,
        "mined_form": word.mined_form,
        "reading": word.reading,
        "pos": word.pos,
        "surface_start": word.surface_start,
        "surface_end": word.surface_end,
        "highlight_end": word.highlight_end,
        "sentence": word.sentence,
        "start_time": word.start_time,
        "end_time": word.end_time,
        "duration": word.duration,
        "expression_furigana": word.expression_furigana,
        "expression_reading": word.expression_reading,
        "sentence_furigana": word.sentence_furigana,
        "sentence_reading": word.sentence_reading,
    }


def run(fixture_json: str, dicdir: str, expected_home: str) -> str:
    """Replay the desktop S4 fixture through the packaged production seams."""

    total_started = time.perf_counter_ns()
    fixture = json.loads(fixture_json)
    fixture_input = fixture["case"]["input"]
    expected_output = fixture["case"]["output"]
    expected_unidic_hash = fixture["provenance"]["input"][
        "unidic_dicdir_sha256"
    ]

    from android_bridge.bootstrap import require_initialized

    home = require_initialized(expected_home)
    registration_started = time.perf_counter_ns()
    from android_bridge.unidic_resource import register_unidic

    registration = register_unidic(
        dicdir,
        resource_id=f"golden-unidic-{expected_unidic_hash[:16]}",
        expected_tree_sha256=expected_unidic_hash,
    )
    registration_ms = _elapsed_ms(registration_started)

    tokenizer_started = time.perf_counter_ns()
    from android_bridge.tokenizer_selection import configure_tokenizer_backend

    selected_backend = configure_tokenizer_backend("s1a")
    from anki_miner.services.tagger import get_shared_tagger

    shared_tagger = get_shared_tagger()
    tokenizer_init_ms = _elapsed_ms(tokenizer_started)

    episode_processor_import_started = time.perf_counter_ns()
    import anki_miner.orchestration.episode_processor  # noqa: F401
    episode_processor_import_ms = _elapsed_ms(episode_processor_import_started)
    from PyQt6.QtCore import QCoreApplication
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.services.dictionary.providers.indexed_provider import (
        IndexedDictProvider,
    )
    from anki_miner.services.dictionary.storage import (
        SCHEMA_VERSION,
        DictRow,
        bulk_insert,
        create_index,
        write_meta,
    )
    from anki_miner.services.dictionary.yomitan_renderer import (
        render_glossary_entry,
    )
    from anki_miner.services.subtitle_parser import SubtitleParserService
    from anki_miner.services.word_filter import WordFilterService
    from anki_miner.utils.i18n import tr_format

    qt_contract = {
        "plain": QCoreApplication.translate("S4", "Ready"),
        "plural": QCoreApplication.translate("S4", "%n cards", "", 2),
        "positional": tr_format("Step %1 of %2", 1, 5),
    }
    if qt_contract != {
        "plain": "Ready",
        "plural": "2 cards",
        "positional": "Step 1 of 5",
    }:
        raise AssertionError(f"PyQt6 compatibility contract differs: {qt_contract!r}")

    with tempfile.TemporaryDirectory(prefix="s4-smoke-", dir=home) as temp:
        root = Path(temp)
        subtitle_path = root / "fixture.srt"
        subtitle_path.write_text(fixture_input["srt"], encoding="utf-8")
        config = AnkiMinerConfig()

        tokenization_started = time.perf_counter_ns()
        parsed = SubtitleParserService(config).parse_subtitle_file(subtitle_path)
        tokenization_ms = _elapsed_ms(tokenization_started)
        filtered = WordFilterService(config).filter_unknown(
            parsed, set(fixture_input["known_vocabulary"])
        )
        dictionary = fixture_input["dictionary"]
        if len(filtered) != 1 or filtered[0].mined_form != dictionary["term"]:
            raise AssertionError("S4 filter did not leave exactly the dictionary target")

        rendered_content = render_glossary_entry(dictionary["glossary"])
        db_path = root / "index.sqlite"
        create_index(db_path)
        bulk_insert(
            db_path,
            [
                DictRow(
                    term=dictionary["term"],
                    reading=dictionary["reading"],
                    content=rendered_content,
                    tags=dictionary["tags"],
                    rules=dictionary["rules"],
                    score=dictionary["score"],
                    sequence=dictionary["sequence"],
                )
            ],
        )
        write_meta(
            db_path,
            {
                "schema_version": str(SCHEMA_VERSION),
                "source_name": dictionary["display_name"],
            },
        )
        provider = IndexedDictProvider(
            dictionary["dict_id"],
            db_path,
            display_name=dictionary["display_name"],
        )
        if not provider.load():
            raise AssertionError("packaged indexed provider rejected the smoke index")
        try:
            lookup_html = provider.lookup(filtered[0].mined_form)
        finally:
            provider.close()
        if lookup_html is None:
            raise AssertionError("packaged indexed provider missed the smoke target")

        actual_output = {
            "parsed_words": [_word_record(word) for word in parsed],
            "filtered_words": [_word_record(word) for word in filtered],
            "selected_mined_form": filtered[0].mined_form,
            "rendered_content": rendered_content,
            "lookup_html": lookup_html,
        }

    if actual_output != expected_output:
        raise AssertionError(
            "S4 packaged output differs from the pinned desktop fixture: "
            + json.dumps(
                {"expected": expected_output, "actual": actual_output},
                ensure_ascii=False,
                sort_keys=True,
            )
        )
    actual_output_hash = hashlib.sha256(_canonical_bytes(actual_output)).hexdigest()
    if actual_output_hash != fixture["provenance"]["output_sha256"]:
        raise AssertionError("S4 output hash differs from fixture provenance")

    return json.dumps(
        {
            "backend": selected_backend,
            "dictionary_sha256": registration.tree_sha256,
            "engine_module_count": len(_engine_modules()),
            "home": home,
            "implementation": platform.python_implementation(),
            "metrics": {
                "episode_processor_import_ms": episode_processor_import_ms,
                "registration_ms": registration_ms,
                "tokenization_ms": tokenization_ms,
                "tokenizer_init_ms": tokenizer_init_ms,
                "total_python_smoke_ms": _elapsed_ms(total_started),
            },
            "output_sha256": actual_output_hash,
            "python": list(sys.version_info[:3]),
            "qt": qt_contract,
            "shared_tagger_type": type(shared_tagger).__name__,
            "tagger_path": "engine_shared_tagger",
        },
        sort_keys=True,
    )
