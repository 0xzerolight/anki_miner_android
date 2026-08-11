from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

PROJECT_ROOT = Path(__file__).resolve().parents[3]
OVERRIDES_ROOT = PROJECT_ROOT / "tools/engine-sync/overrides"


def _load_module(
    path: Path, name: str, stubs: dict[str, types.ModuleType] | None = None
):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    with mock.patch.dict(sys.modules, {**(stubs or {}), name: module}):
        spec.loader.exec_module(module)
    return module


def _load_known_words_import():
    return _load_module(
        OVERRIDES_ROOT / "anki_miner/services/known_words_import.py",
        "known_words_import_resource_bounds_test",
    )


def _load_reading_overrides():
    anki_miner = types.ModuleType("anki_miner")
    models = types.ModuleType("anki_miner.models")
    reading_name = "anki_miner.models.reading"
    reading = _load_module(
        OVERRIDES_ROOT / "anki_miner/models/reading.py",
        reading_name,
        {
            "anki_miner": anki_miner,
            "anki_miner.models": models,
        },
    )
    splitter = _load_module(
        OVERRIDES_ROOT / "anki_miner/services/reading/sentence_splitter.py",
        "sentence_splitter_resource_bounds_test",
        {
            "anki_miner": anki_miner,
            "anki_miner.models": models,
            reading_name: reading,
        },
    )
    return reading, splitter


class KnownWordsImportResourceBoundsTests(unittest.TestCase):
    def test_deep_ignored_graph_stops_at_structural_limit(self) -> None:
        known_words = _load_known_words_import()
        nesting = 9
        payload = (
            '{"words":[{"word":"猫","status":"KNOWN","extra":'
            + "[" * nesting
            + "null"
            + "]" * nesting
            + "}]}"
        )

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "migaku.json"
            source.write_text(payload, encoding="utf-8")
            with mock.patch.object(
                known_words, "_MAX_JSON_NESTING_DEPTH", nesting - 1, create=True
            ):
                with self.assertRaises(known_words.KnownWordsImportError) as raised:
                    known_words.parse_known_words_file(
                        source,
                        max_words=10,
                        max_word_bytes=32,
                    )

        self.assertEqual("limit_exceeded", raised.exception.reason)

    def test_structured_items_never_reach_raw_decoder(self) -> None:
        known_words = _load_known_words_import()
        ignored = [{} for _ in range(4_096)]
        cases = (
            (
                "migaku",
                {"words": [{"word": "猫", "status": "KNOWN", "extra": ignored}]},
                "migaku_json",
                frozenset({"猫"}),
            ),
            (
                "jpdb",
                {
                    "cards_vocabulary_jp_en": [
                        {
                            "spelling": "犬",
                            "reviews": [
                                {
                                    "grade": "okay",
                                    "timestamp": 1,
                                    "extra": ignored,
                                }
                            ],
                            "extra": ignored,
                        }
                    ]
                },
                "jpdb",
                frozenset({"犬"}),
            ),
            (
                "migaku-legacy",
                [["鳥", 2]],
                "migaku_legacy",
                frozenset({"鳥"}),
            ),
        )
        real_decoder = json.JSONDecoder

        class ScalarOnlyDecoder:
            def __init__(self) -> None:
                self._delegate = real_decoder()

            def raw_decode(self, source: str, *args: object, **kwargs: object):
                if source.lstrip().startswith(("{", "[")):
                    raise AssertionError("ignored JSON graph reached raw_decode")
                return self._delegate.raw_decode(source, *args, **kwargs)

        for label, payload, expected_format, expected_words in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / f"{label}.json"
                source.write_text(
                    json.dumps(payload, ensure_ascii=False), encoding="utf-8"
                )
                with (
                    mock.patch.object(known_words, "_READ_CHUNK_BYTES", 31),
                    mock.patch.object(
                        known_words.json, "JSONDecoder", ScalarOnlyDecoder
                    ),
                ):
                    result = known_words.parse_known_words_file(
                        source,
                        max_words=10,
                        max_word_bytes=32,
                    )

                self.assertEqual(expected_format, result.format_key)
                self.assertEqual(expected_words, result.words)
                self.assertEqual(1, result.total_entries)

    def test_ignored_number_split_across_chunk_matches_materialized_parse(self) -> None:
        known_words = _load_known_words_import()
        chunk_bytes = 32
        for number in ("1234", "12.34", "12e34", "12e+34"):
            with self.subTest(
                number=number
            ), tempfile.TemporaryDirectory() as directory:
                fixed_prefix = '{"padding":"'
                middle = '","metadata":12'
                padding = "x" * (-(len(fixed_prefix) + len(middle)) % chunk_bytes)
                payload = (
                    fixed_prefix
                    + padding
                    + middle
                    + number.removeprefix("12")
                    + ',"words":[{"word":"猫","status":"KNOWN"}]}'
                )
                self.assertEqual(
                    0, (len(fixed_prefix) + len(padding) + len(middle)) % chunk_bytes
                )
                source = Path(directory) / "migaku.json"
                source.write_text(payload, encoding="utf-8")

                materialized = known_words.parse_known_words_file(source)
                with mock.patch.object(known_words, "_READ_CHUNK_BYTES", chunk_bytes):
                    streamed = known_words.parse_known_words_file(
                        source,
                        max_words=10,
                        max_word_bytes=32,
                    )

                self.assertEqual(materialized, streamed)


class SentenceSplitterResourceBoundsTests(unittest.TestCase):
    def test_matched_openers_use_bitset_storage(self) -> None:
        _reading, splitter = _load_reading_overrides()
        real_array = splitter.array
        typecodes: list[str] = []

        def tracking_array(typecode: str):
            typecodes.append(typecode)
            return real_array(typecode)

        with mock.patch.object(splitter, "array", tracking_array):
            matched = splitter._matched_openers("(" * 4_096)

        self.assertEqual(["I"], typecodes)
        self.assertIsInstance(matched, bytearray)
        self.assertEqual(512, len(matched))

    def test_pending_sentence_limit_counts_utf8_bytes(self) -> None:
        reading, splitter = _load_reading_overrides()
        exact_limit = "猫" * ((64 * 1024) // 3) + "a"

        with reading.reading_unit_budget(10, precount_sentences=True):
            self.assertEqual([exact_limit], splitter.split_sentences(exact_limit))
        with reading.reading_unit_budget(10, precount_sentences=True):
            with self.assertRaises(reading.ReadingUnitLimitExceeded):
                splitter.split_sentences(exact_limit + "a")

    def test_over_limit_unmatched_opener_sentence_is_rejected_in_splitter(self) -> None:
        reading, splitter = _load_reading_overrides()
        text = "(" * (64 * 1024 + 1)

        with reading.reading_unit_budget(10, precount_sentences=True):
            with self.assertRaises(reading.ReadingUnitLimitExceeded):
                splitter.split_sentences(text)


if __name__ == "__main__":
    unittest.main()
