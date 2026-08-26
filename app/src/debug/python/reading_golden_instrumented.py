"""Replay the desktop reading fixture through the packaged Android bridge."""

from __future__ import annotations

import base64
import collections
import hashlib
import json
import re
import tempfile
import zipfile
from dataclasses import replace
from pathlib import Path
from typing import Any


_SOURCE_NAMES = ("aozora", "subtitle", "epub", "mokuro")


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _zip_entries(path: Path, entries: list[dict[str, Any]]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for entry in entries:
            data = (
                entry["text"].encode("utf-8")
                if "text" in entry
                else base64.b64decode(entry["base64"], validate=True)
            )
            archive.writestr(entry["name"], data)


def _materialize_sources(
    sources: dict[str, Any], root: Path
) -> dict[str, tuple[Path, Path | None]]:
    result: dict[str, tuple[Path, Path | None]] = {}
    for name in _SOURCE_NAMES:
        spec = sources[name]
        job = root / name
        job.mkdir()
        source = job / spec["filename"]
        archive_path: Path | None = None
        if name == "aozora":
            source.write_bytes(spec["text"].encode(spec["encoding"]))
        elif name == "subtitle":
            source.write_text(spec["text"], encoding="utf-8")
        elif name == "epub":
            _zip_entries(source, spec["entries"])
        else:
            source.write_text(
                json.dumps(
                    spec["document"],
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ),
                encoding="utf-8",
            )
            archive_path = job / spec["image_archive_filename"]
            _zip_entries(archive_path, spec["images"])
        result[name] = (source, archive_path)
    return result


def _request_json(
    *,
    cache_dir: Path,
    source_kind: str,
    source_path: Path,
    archive_path: Path | None,
    series_name: str | None,
) -> str:
    from android_bridge.protocol import encode_message

    return encode_message(
        "mining.reading.run",
        {
            "sourceKind": source_kind,
            "sourcePath": str(source_path),
            "imageArchivePath": str(archive_path) if archive_path is not None else None,
            "seriesName": series_name,
            "cacheDir": str(cache_dir),
            "nativeLibraryDir": str(cache_dir / "native"),
            "configSnapshot": {"settings": {}, "androidTtsEnabled": False},
        },
    )


def _image_ref_snapshot(image_ref: Any | None) -> dict[str, Any] | None:
    if image_ref is None:
        return None
    return {"source_filename": image_ref.source.name, "entry": image_ref.entry}


def _document_snapshot(document: Any) -> dict[str, Any]:
    return {
        "title": document.title,
        "kind": document.kind,
        "series": document.series,
        "episode": document.episode,
        "warnings": list(document.warnings),
        "units": [
            {
                "text": unit.text,
                "index": unit.index,
                "location": unit.location_label,
                "image": _image_ref_snapshot(unit.image_ref),
                "block_box": list(unit.block_box) if unit.block_box is not None else None,
            }
            for unit in document.units
        ],
    }


class _DocumentParser:
    def __init__(self, tokenized_word_type: type) -> None:
        self._tokenized_word_type = tokenized_word_type
        self.received_units: list[Any] = []

    def parse_text_units(
        self, units: list[Any], want_line_index: bool
    ) -> tuple[list[Any], None, collections.Counter[str]]:
        if want_line_index or [unit.text for unit in units] != ["猫を見る。", "犬もいる。"]:
            raise AssertionError("packaged Mokuro units differ before process_reading")
        self.received_units = list(units)
        word = self._tokenized_word_type(
            surface="猫",
            lemma="猫",
            reading="ネコ",
            sentence=units[0].text,
            start_time=float(units[0].index),
            end_time=float(units[0].index),
            duration=0.0,
            expression_furigana="猫[ねこ]",
            expression_reading="ねこ",
            lemma_reading="ねこ",
            sentence_furigana="猫[ねこ]を見[み]る。",
            sentence_reading="ねこをみる。",
            pos="名詞",
            surface_start=0,
            surface_end=1,
            highlight_end=1,
        )
        return [word], None, collections.Counter({"猫": 1})


class _DefinitionService:
    def __init__(self) -> None:
        self.lookup_pairs: list[tuple[str, str | None]] = []
        self.fallback_context: dict[str, tuple[str, str | None]] = {}
        self.run_cache_clears = 0
        self.closed = False

    def get_definitions_batch(
        self,
        pairs: list[tuple[str, str | None]],
        progress_callback: object | None,
        fallback_context: dict[str, tuple[str, str | None]],
    ) -> list[str]:
        if progress_callback is not None:
            raise AssertionError("reading replay received unexpected progress")
        self.lookup_pairs = list(pairs)
        self.fallback_context = dict(fallback_context)
        return ['<div class="definition">cat</div>']

    def clear_run_cache(self) -> None:
        """Per-run attest-quality cache reset; the engine calls it in ``finally``."""
        self.run_cache_clears += 1

    def close(self) -> None:
        self.closed = True


class _AnkiService:
    def __init__(self) -> None:
        self.last_created_note_ids: list[int] = []
        self.last_media_store_failures = 0
        self.last_skipped_duplicates = 0
        self.verified = False
        self.card_snapshot: dict[str, Any] | None = None

    def verify_card_target(self) -> None:
        self.verified = True

    def create_cards_batch(
        self, card_data: list[Any], progress_callback: object | None = None
    ) -> int:
        if progress_callback is not None or len(card_data) != 1:
            raise AssertionError("reading replay card sink received invalid input")
        from PIL import Image

        card = card_data[0]
        screenshot = card.media.screenshot_path
        if screenshot is None or not screenshot.is_file():
            raise AssertionError("packaged Mokuro screenshot was not materialized")
        with Image.open(screenshot) as image:
            image_format = image.format
            image_size = list(image.size)
            rgba_sha256 = hashlib.sha256(image.convert("RGBA").tobytes()).hexdigest()
        self.card_snapshot = {
            "word": {
                "surface": card.word.surface,
                "lemma": card.word.lemma,
                "mined_form": card.word.mined_form,
                "sentence": card.word.sentence,
            },
            "definition": card.definition,
            "extra_fields": card.extra_fields,
            "media": {
                "screenshot_filename_matches_contract": bool(
                    re.fullmatch(r"reading_[0-9a-f]{12}\.jpg", card.media.screenshot_filename or "")
                ),
                "format": image_format,
                "size": image_size,
                "rgba_sha256": rgba_sha256,
                "audio_filename": card.media.audio_filename,
                "expression_audio_filename": card.media.expression_audio_filename,
            },
        }
        self.last_created_note_ids = [4242]
        return 1


def _process_snapshot(document: Any, config: Any) -> dict[str, Any]:
    from anki_miner.models import TokenizedWord
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.presenters import NullPresenter
    from anki_miner.services.word_filter import WordFilterService

    parser = _DocumentParser(TokenizedWord)
    definitions = _DefinitionService()
    anki = _AnkiService()
    processor = EpisodeProcessor(
        config=config,
        subtitle_parser=parser,
        word_filter=WordFilterService(config),
        media_extractor=object(),
        definition_service=definitions,
        anki_service=anki,
        presenter=NullPresenter(),
    )
    try:
        result = processor.process_reading(document)
    finally:
        processor.close()
    if anki.card_snapshot is None:
        raise AssertionError("packaged process_reading produced no card")
    return {
        "result": {
            "total_words_found": result.total_words_found,
            "new_words_found": result.new_words_found,
            "cards_created": result.cards_created,
            "errors": result.errors,
            "card_ids": result.card_ids,
            "mined_forms": result.mined_forms,
            "video_file": result.video_file,
            "subtitle_file": result.subtitle_file,
        },
        "parser_received_all_units": parser.received_units == document.units,
        "definition_lookup_pairs": [list(pair) for pair in definitions.lookup_pairs],
        "definition_fallback_context": {
            key: list(value) for key, value in definitions.fallback_context.items()
        },
        "definition_service_closed": definitions.closed,
        "anki_target_verified": anki.verified,
        "card": anki.card_snapshot,
    }


def run(fixture_json: str, expected_home: str) -> str:
    fixture = json.loads(fixture_json)
    corpus = fixture["case"]["input"]
    expected_output = fixture["case"]["output"]
    expected_output_sha256 = fixture["provenance"]["output_sha256"]
    if hashlib.sha256(_canonical_bytes(expected_output)).hexdigest() != expected_output_sha256:
        raise AssertionError("reading fixture output provenance is invalid")

    from android_bridge.bootstrap import require_initialized
    import android_bridge.reading_mining as reading_mining

    home = require_initialized(expected_home)
    with tempfile.TemporaryDirectory(prefix="reading-golden-", dir=home) as temporary:
        root = Path(temporary)
        (root / "native").mkdir()
        paths = _materialize_sources(corpus["sources"], root)
        documents: dict[str, Any] = {}
        loaded: dict[str, Any] = {}
        requests: dict[str, Any] = {}
        for name in _SOURCE_NAMES:
            spec = corpus["sources"][name]
            source, archive = paths[name]
            raw_request = _request_json(
                cache_dir=root,
                source_kind=spec["kind"],
                source_path=source,
                archive_path=archive,
                series_name=spec.get("series_name"),
            )
            request = reading_mining._parse_request(raw_request)
            document = reading_mining._load_document(request)
            requests[name] = request
            loaded[name] = document
            documents[name] = _document_snapshot(document)

        config = replace(
            reading_mining._map_config(requests["mokuro"], Path(home)),
            anki_fields={},
            include_known_words=True,
            bypass_optional_filters=True,
            reading_min_occurrence=1,
            use_i_plus_one_filter=False,
        )
        actual_output = {
            "documents": documents,
            "mokuro_process_reading": _process_snapshot(loaded["mokuro"], config),
        }

    if actual_output != expected_output:
        raise AssertionError(
            "packaged reading output differs from desktop fixture: "
            + json.dumps(
                {"expected": expected_output, "actual": actual_output},
                ensure_ascii=False,
                sort_keys=True,
            )
        )
    actual_hash = hashlib.sha256(_canonical_bytes(actual_output)).hexdigest()
    if actual_hash != expected_output_sha256:
        raise AssertionError("packaged reading output hash differs from fixture")
    return json.dumps(
        {
            "output_sha256": actual_hash,
            "source_count": len(documents),
            "cards_created": actual_output["mokuro_process_reading"]["result"][
                "cards_created"
            ],
            "screenshot_verified": actual_output["mokuro_process_reading"]["card"][
                "media"
            ]["screenshot_filename_matches_contract"],
        },
        sort_keys=True,
    )
