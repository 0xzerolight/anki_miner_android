#!/usr/bin/env python3
"""Derive the S4 engine smoke output from a pinned desktop checkout."""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any


class S4ExportError(RuntimeError):
    """The smoke fixture cannot be derived without ambiguity."""


def _resolved_file(path: Path, label: str) -> Path:
    unresolved = path.expanduser().absolute()
    if unresolved.is_symlink():
        raise S4ExportError(f"{label} must not be a symlink: {unresolved}")
    try:
        resolved = unresolved.resolve(strict=True)
    except OSError as exc:
        raise S4ExportError(f"{label} does not exist: {unresolved}") from exc
    if not resolved.is_file():
        raise S4ExportError(f"{label} must be a regular file: {resolved}")
    return resolved


def _resolved_directory(path: Path, label: str) -> Path:
    unresolved = path.expanduser().absolute()
    if unresolved.is_symlink():
        raise S4ExportError(f"{label} must not be a symlink: {unresolved}")
    try:
        resolved = unresolved.resolve(strict=True)
    except OSError as exc:
        raise S4ExportError(f"{label} does not exist: {unresolved}") from exc
    if not resolved.is_dir():
        raise S4ExportError(f"{label} must be a directory: {resolved}")
    return resolved


def _load_corpus(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise S4ExportError(f"invalid S4 corpus: {exc}") from exc
    if not isinstance(payload, dict) or set(payload) != {
        "schema_version",
        "srt",
        "known_vocabulary",
        "dictionary",
    }:
        raise S4ExportError("S4 corpus root has an unexpected shape")
    if payload["schema_version"] != 1:
        raise S4ExportError("S4 corpus schema_version must be 1")
    if not isinstance(payload["srt"], str) or not payload["srt"].strip():
        raise S4ExportError("S4 corpus srt must be non-empty")
    known = payload["known_vocabulary"]
    if (
        not isinstance(known, list)
        or not known
        or any(not isinstance(value, str) or not value for value in known)
        or len(set(known)) != len(known)
    ):
        raise S4ExportError("S4 corpus known_vocabulary is invalid")
    dictionary = payload["dictionary"]
    expected_dictionary_keys = {
        "dict_id",
        "display_name",
        "term",
        "reading",
        "glossary",
        "tags",
        "rules",
        "score",
        "sequence",
    }
    if not isinstance(dictionary, dict) or set(dictionary) != expected_dictionary_keys:
        raise S4ExportError("S4 corpus dictionary has an unexpected shape")
    for key in ("dict_id", "display_name", "term", "reading"):
        if not isinstance(dictionary[key], str) or not dictionary[key]:
            raise S4ExportError(f"S4 corpus dictionary.{key} must be non-empty")
    if not isinstance(dictionary["glossary"], list) or not dictionary["glossary"]:
        raise S4ExportError("S4 corpus dictionary.glossary must be non-empty")
    for key in ("tags", "rules"):
        if not isinstance(dictionary[key], str):
            raise S4ExportError(f"S4 corpus dictionary.{key} must be a string")
    for key in ("score", "sequence"):
        if not isinstance(dictionary[key], int) or isinstance(dictionary[key], bool):
            raise S4ExportError(f"S4 corpus dictionary.{key} must be an integer")
    return payload


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


def _make_tagger(dicdir: Path) -> Any:
    import fugashi

    if any(character.isspace() for character in os.fspath(dicdir)):
        raise S4ExportError("--dicdir paths containing whitespace are unsupported")
    mecabrc = dicdir / "mecabrc"
    sys_dic = dicdir / "sys.dic"
    if not mecabrc.is_file() or not sys_dic.is_file():
        raise S4ExportError("--dicdir is not a complete UniDic directory")
    tagger = fugashi.Tagger(f'-r "{mecabrc}" -d "{dicdir}"')
    loaded = {
        Path(str(info["filename"])).resolve()
        for info in tagger.dictionary_info
        if isinstance(info, Mapping) and info.get("filename")
    }
    if loaded != {sys_dic.resolve()}:
        raise S4ExportError(f"fugashi loaded unexpected dictionaries: {loaded!r}")
    return tagger


def _install_desktop_shared_tagger(tagger: Any) -> None:
    from anki_miner.services import tagger as tagger_module

    tagger_module._tagger = tagger
    tagger_module._locked_tagger = tagger_module.LockedTagger(tagger)


def derive(*, engine_root: Path, corpus_path: Path, dicdir: Path) -> dict[str, Any]:
    preloaded = sorted(
        name
        for name in sys.modules
        if name == "anki_miner" or name.startswith("anki_miner.")
    )
    if preloaded:
        raise S4ExportError(
            "engine modules were loaded before S4 derivation: " + ", ".join(preloaded)
        )
    engine_root = _resolved_directory(engine_root, "--engine-root")
    corpus_path = _resolved_file(corpus_path, "--corpus")
    dicdir = _resolved_directory(dicdir, "--dicdir")
    corpus = _load_corpus(corpus_path)

    sys.path.insert(0, os.fspath(engine_root))
    try:
        tagger = _make_tagger(dicdir)
        _install_desktop_shared_tagger(tagger)

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

        home = Path(os.environ["ANKI_MINER_HOME"])
        home.mkdir(parents=True, exist_ok=True)
        subtitle_path = home / "s4-smoke.srt"
        subtitle_path.write_text(corpus["srt"], encoding="utf-8")
        config = AnkiMinerConfig()
        parsed = SubtitleParserService(config).parse_subtitle_file(subtitle_path)
        filtered = WordFilterService(config).filter_unknown(
            parsed, set(corpus["known_vocabulary"])
        )
        dictionary = corpus["dictionary"]
        if len(filtered) != 1 or filtered[0].mined_form != dictionary["term"]:
            raise S4ExportError(
                "S4 chain must leave exactly the dictionary target after filtering"
            )

        rendered_content = render_glossary_entry(dictionary["glossary"])
        with tempfile.TemporaryDirectory(prefix="s4-dictionary-", dir=home) as temp:
            db_path = Path(temp) / "index.sqlite"
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
                raise S4ExportError("desktop indexed provider rejected the smoke index")
            try:
                lookup_html = provider.lookup(filtered[0].mined_form)
            finally:
                provider.close()
        if lookup_html is None:
            raise S4ExportError("desktop indexed provider missed the smoke target")

        return {
            "parsed_words": [_word_record(word) for word in parsed],
            "filtered_words": [_word_record(word) for word in filtered],
            "selected_mined_form": filtered[0].mined_form,
            "rendered_content": rendered_content,
            "lookup_html": lookup_html,
        }
    finally:
        sys.path.remove(os.fspath(engine_root))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-root", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--dicdir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        output = derive(
            engine_root=args.engine_root,
            corpus_path=args.corpus,
            dicdir=args.dicdir,
        )
    except S4ExportError as exc:
        print(f"S4 export failed: {exc}", file=sys.stderr)
        return 2
    args.output.write_text(
        json.dumps(output, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
