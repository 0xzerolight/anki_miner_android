#!/usr/bin/env python3
"""Derive reading-loader and Mokuro process_reading output from desktop."""

from __future__ import annotations

import argparse
import base64
import collections
import hashlib
import json
import os
import re
import sys
import tempfile
import zipfile
from dataclasses import replace
from pathlib import Path
from typing import Any


class ReadingExportError(RuntimeError):
    """The reading fixture cannot be derived without ambiguity."""


def _load_corpus(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReadingExportError(f"invalid reading corpus: {exc}") from exc
    if not isinstance(payload, dict) or set(payload) != {"schema_version", "sources"}:
        raise ReadingExportError("reading corpus root has an unexpected shape")
    if payload["schema_version"] != 1:
        raise ReadingExportError("reading corpus schema_version must be 1")
    sources = payload["sources"]
    if not isinstance(sources, dict) or set(sources) != {
        "aozora",
        "subtitle",
        "epub",
        "mokuro",
    }:
        raise ReadingExportError("reading corpus source set is invalid")
    return payload


def _zip_entries(path: Path, entries: list[dict[str, Any]]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) not in (
                {"name", "text"},
                {"name", "base64"},
            ):
                raise ReadingExportError("archive entry has an unexpected shape")
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
    for name in ("aozora", "subtitle", "epub", "mokuro"):
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
        self, units: list[Any], want_line_index: bool, *, subtitle_cleanup: bool = False
    ) -> tuple[list[Any], None, collections.Counter[str]]:
        if want_line_index:
            raise ReadingExportError("reading golden unexpectedly requested a line index")
        if subtitle_cleanup:
            # The processor keys per-cue cleanup on the document kind; this
            # snapshot is a Mokuro volume, so it must never ask for it.
            raise ReadingExportError("Mokuro reading unexpectedly requested subtitle cleanup")
        if [unit.text for unit in units] != ["猫を見る。", "犬もいる。"]:
            raise ReadingExportError("Mokuro units differ before process_reading")
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
        self.closed = False

    def get_definitions_batch(
        self,
        pairs: list[tuple[str, str | None]],
        progress_callback: object | None,
        fallback_context: dict[str, tuple[str, str | None]],
        **_kwargs: object,
    ) -> list[str]:
        if progress_callback is not None:
            raise ReadingExportError("reading golden received unexpected progress")
        self.lookup_pairs = list(pairs)
        self.fallback_context = dict(fallback_context)
        return ['<div class="definition">cat</div>']

    def offline_term_identities(
        self,
        pairs: list[tuple[str, str]],
    ) -> dict[tuple[str, str], set[tuple[str, int, str]]]:
        """Drives the within-run orthographic alias collapse.

        Empty keeps every candidate distinct, which is what this fixture
        freezes; the real service resolves JMdict identities.
        """
        return {}

    def close(self) -> None:
        self.closed = True


class _AnkiService:
    def __init__(self) -> None:
        self.last_created_note_ids: list[int] = []
        self.last_media_store_failures = 0
        self.last_skipped_duplicates = 0
        self.verified = False
        self.card_snapshot: dict[str, Any] | None = None
        self.last_created_mined_forms: list[str] = []

    def set_cancelled_check(self, cancelled: object) -> None:
        # _phase5 installs its probe before the batch and clears it after.
        return None

    def verify_card_target(self) -> None:
        self.verified = True

    def create_cards_batch(
        self, card_data: list[Any], progress_callback: object | None = None
    ) -> list[int]:
        if progress_callback is not None or len(card_data) != 1:
            raise ReadingExportError("reading golden card sink received invalid input")
        from PIL import Image

        card = card_data[0]
        screenshot = card.media.screenshot_path
        if screenshot is None or not screenshot.is_file():
            raise ReadingExportError("Mokuro card screenshot was not materialized")
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
        # The processor reads the known-words receipt off the service now
        # rather than deriving it from what it submitted, so the sink must
        # report the forms it confirmed.
        self.last_created_mined_forms = [payload.word.mined_form for payload in card_data]
        # The service contract returns the created ids; the processor takes
        # len() of this and stamps them onto the result.
        return [4242]


def _process_snapshot(document: Any, home: Path) -> dict[str, Any]:
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.models import TokenizedWord
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.presenters import NullPresenter
    from anki_miner.services.known_word_db import KnownWordDB
    from anki_miner.services.word_filter import WordFilterService

    config = replace(
        AnkiMinerConfig(),
        # The reading image phase is gated on the picture field being mapped, so an
        # empty mapping would snapshot an imageless card.
        anki_fields={"picture": "Picture"},
        include_known_words=True,
        bypass_optional_filters=True,
        reading_min_occurrence=1,
        use_i_plus_one_filter=False,
        media_temp_folder=home / "media",
    )
    parser = _DocumentParser(TokenizedWord)
    definitions = _DefinitionService()
    anki = _AnkiService()
    # mined_forms is the known-words insert receipt now: only rows this run wrote
    # may be reverted by Undo. Every real run owns a database (the bridge always
    # constructs one), so the snapshot needs one or it would freeze an empty list
    # the app never produces.
    known_word_db = KnownWordDB(config.known_words_db_path)
    known_word_db.initialize()
    processor = EpisodeProcessor(
        config=config,
        subtitle_parser=parser,
        word_filter=WordFilterService(config),
        media_extractor=object(),
        definition_service=definitions,
        anki_service=anki,
        presenter=NullPresenter(),
        known_word_db=known_word_db,
    )
    try:
        result = processor.process_reading(document)
    finally:
        processor.close()
    if anki.card_snapshot is None:
        raise ReadingExportError("Mokuro process_reading created no card payload")
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


def derive(*, engine_root: Path, corpus_path: Path) -> dict[str, Any]:
    preloaded = sorted(
        name
        for name in sys.modules
        if name == "anki_miner" or name.startswith("anki_miner.")
    )
    if preloaded:
        raise ReadingExportError(
            "engine modules were loaded before reading derivation: " + ", ".join(preloaded)
        )
    corpus = _load_corpus(corpus_path)
    sys.path.insert(0, os.fspath(engine_root))
    try:
        from anki_miner.services.reading import detector

        with tempfile.TemporaryDirectory(prefix="reading-golden-") as temporary:
            root = Path(temporary)
            paths = _materialize_sources(corpus["sources"], root)
            documents: dict[str, Any] = {}
            loaded: dict[str, Any] = {}
            for name in ("aozora", "subtitle", "epub", "mokuro"):
                source, _archive = paths[name]
                refs = detector.detect(source)
                expected_kind = corpus["sources"][name]["kind"]
                if len(refs) != 1 or refs[0].kind != expected_kind:
                    raise ReadingExportError(f"{name} detector output is invalid")
                # Mirrors the bridge, which passes no cancellation here: the
                # per-cue annotation strip is unconditional in the engine now,
                # so there is no longer a kwarg to keep the two in step.
                document = detector.load(refs[0])
                if name == "subtitle":
                    document.series = corpus["sources"][name]["series_name"]
                loaded[name] = document
                documents[name] = _document_snapshot(document)
            process = _process_snapshot(loaded["mokuro"], root)
        return {"documents": documents, "mokuro_process_reading": process}
    finally:
        sys.path.remove(os.fspath(engine_root))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-root", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        output = derive(
            engine_root=args.engine_root.resolve(strict=True),
            corpus_path=args.corpus.resolve(strict=True),
        )
        args.output.write_text(
            json.dumps(output, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, ReadingExportError) as exc:
        print(f"reading golden export: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
