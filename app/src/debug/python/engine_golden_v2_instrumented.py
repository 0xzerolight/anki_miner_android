"""Debug-only replay of every complete v2 golden section on packaged Android."""

from __future__ import annotations

import hashlib
import html
import json
import re
import sqlite3
import tempfile
import zipfile
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any


FIXTURE_SHA256 = "3f3146c906a3f59179b724e14be1424f740651de896febcf9c12ecccec3d94b4"
SECTIONS = (
    "tokenization",
    "morphology",
    "filtering",
    "deinflection",
    "compounds",
    "dictionaries",
    "frequency",
    "pitch",
    "cards",
)
UNIDIC_FEATURE_FIELDS = (
    "pos1",
    "pos2",
    "pos3",
    "pos4",
    "cType",
    "cForm",
    "lForm",
    "lemma",
    "orth",
    "pron",
    "orthBase",
    "pronBase",
    "goshu",
    "iType",
    "iForm",
    "fType",
    "fForm",
    "kana",
    "kanaBase",
    "form",
    "formBase",
    "iConType",
    "fConType",
    "aType",
    "aConType",
    "aModeType",
)
_PNG_1X1 = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000d49444154789c63000100000005000159c8e1740000000049454e44ae426082"
)
_MARKED_DICTIONARY_IMG_RE = re.compile(
    r'<img\b[^>]*class="[^"]*\banki\-miner\-dict\-media\b[^"]*"[^>]*>',
    re.IGNORECASE,
)
_IMG_SRC_RE = re.compile(r'src="([^"]+)"', re.IGNORECASE)


def _canonical(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _mapping(value: Any, location: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise AssertionError(f"{location} must be an object")
    return value


def _list(value: Any, location: str) -> list[Any]:
    if not isinstance(value, list) or not value:
        raise AssertionError(f"{location} must be a non-empty array")
    return value


def _string(value: Any, location: str) -> str:
    if not isinstance(value, str) or not value:
        raise AssertionError(f"{location} must be a non-empty string")
    return value


def _utf16_offset(text: str, offset: int) -> int:
    return len(text[:offset].encode("utf-16-le")) // 2


def _tokenization(corpus: Sequence[Mapping[str, Any]], tagger: Any) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for case in corpus:
        text = str(case["text"])
        cursor = 0
        records: list[dict[str, Any]] = []
        for token in tagger(text):
            surface = str(token.surface)
            start = text.find(surface, cursor)
            if not surface or start < 0 or (text[cursor:start] and not text[cursor:start].isspace()):
                raise AssertionError(f"unlocatable token in {case['id']}")
            end = start + len(surface)
            feature = token.feature
            records.append(
                {
                    "surface": surface,
                    "is_unknown": bool(getattr(token, "is_unk", False)),
                    "offsets": {
                        "codepoint_start": start,
                        "codepoint_end": end,
                        "utf16_start": _utf16_offset(text, start),
                        "utf16_end": _utf16_offset(text, end),
                    },
                    "features": {
                        name: (
                            None
                            if (value := getattr(feature, name)) is None or value == "*"
                            else str(value)
                        )
                        for name in UNIDIC_FEATURE_FIELDS
                    },
                }
            )
            cursor = end
        if text[cursor:] and not text[cursor:].isspace():
            raise AssertionError(f"token stream omitted text in {case['id']}")
        output.append({"id": case["id"], "text": text, "tokens": records})
    return output


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
        "expression_furigana": word.expression_furigana,
        "expression_reading": word.expression_reading,
        "sentence_furigana": word.sentence_furigana,
        "sentence_reading": word.sentence_reading,
    }


def _morphology_and_compounds(
    corpus: Sequence[Mapping[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.models.reading import ReadingUnit
    from anki_miner.services.subtitle_parser import SubtitleParserService

    morphology: list[dict[str, Any]] = []
    compounds: list[dict[str, Any]] = []
    for case in corpus:
        dictionary_terms = frozenset(str(value) for value in case.get("dictionary_terms", []))

        def term_lookup(
            candidates: list[str],
            terms: frozenset[str] = dictionary_terms,
        ) -> set[str]:
            return set(candidates) & terms

        parser = SubtitleParserService(
            AnkiMinerConfig(),
            term_lookup=term_lookup if dictionary_terms else None,
        )
        words, _line_index, _counts = parser.parse_text_units(
            [
                ReadingUnit(
                    text=str(case["text"]),
                    index=0,
                    location_label=str(case["id"]),
                )
            ],
            want_line_index=False,
        )
        record = {
            "id": case["id"],
            "input": {"text": case["text"]},
            "output": {"words": [_word_record(word) for word in words]},
        }
        morphology.append(record)
        if dictionary_terms:
            compounds.append(
                {
                    "id": case["id"],
                    "input": {
                        "text": case["text"],
                        "dictionary_terms": sorted(dictionary_terms),
                    },
                    "output": record["output"],
                }
            )
    return morphology, compounds


def _word_from_input(record: Mapping[str, Any]) -> Any:
    from anki_miner.models import TokenizedWord

    surface = _string(record.get("surface"), "filtering word.surface")
    sentence = _string(record.get("sentence"), "filtering word.sentence")
    start = sentence.index(surface)
    start_time = float(record["start_time"])
    return TokenizedWord(
        surface=surface,
        lemma=_string(record.get("lemma"), "filtering word.lemma"),
        reading=_string(record.get("reading"), "filtering word.reading"),
        sentence=sentence,
        start_time=start_time,
        end_time=start_time + 1.0,
        duration=1.0,
        orth_base=_string(record.get("orth_base"), "filtering word.orth_base"),
        expression_furigana=surface,
        expression_reading=str(record.get("expression_reading", "")),
        lemma_reading=str(record.get("lemma_reading", "")),
        sentence_furigana=sentence,
        sentence_reading=sentence,
        pos=str(record["pos"]) if record.get("pos") is not None else None,
        surface_start=start,
        surface_end=start + len(surface),
        highlight_end=start + len(surface),
    )


class _Presenter:
    def __init__(self) -> None:
        self.events: list[dict[str, str]] = []

    def _append(self, level: str, message: str) -> None:
        self.events.append({"level": level, "message": str(message)})

    def show_info(self, message: str) -> None:
        self._append("info", message)

    def show_success(self, message: str) -> None:
        self._append("success", message)

    def show_warning(self, message: str) -> None:
        self._append("warning", message)

    def show_error(self, message: str) -> None:
        self._append("error", message)


class _AnkiRead:
    def __init__(self, existing: set[str]) -> None:
        self.existing = existing

    def get_existing_vocabulary(self) -> set[str]:
        return set(self.existing)


def _filtering_run(
    value: Mapping[str, Any],
    frequency_service: Any,
    definition_service: Any,
    allow_duplicates: bool,
) -> dict[str, Any]:
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.orchestration.episode_processor import EpisodeProcessor, _EpisodeContext
    from anki_miner.services.anki_note_builder import _strip_for_dedup
    from anki_miner.services.word_filter import WordFilterService

    raw_fields = _list(value.get("existing_first_fields"), "filtering existing fields")
    normalized = [_strip_for_dedup(_string(item, "existing field")) for item in raw_fields]
    words: list[Any] = []
    ids: dict[int, str] = {}
    for raw in _list(value.get("words"), "filtering words"):
        record = _mapping(raw, "filtering word")
        word = _word_from_input(record)
        words.append(word)
        ids[id(word)] = _string(record.get("id"), "filtering word.id")
    config = AnkiMinerConfig(
        bypass_optional_filters=True,
        allow_duplicate_cards=allow_duplicates,
    )
    presenter = _Presenter()
    processor = EpisodeProcessor(
        config=config,
        subtitle_parser=None,
        word_filter=WordFilterService(config),
        media_extractor=None,
        definition_service=definition_service,
        anki_service=_AnkiRead(set(normalized)),
        presenter=presenter,
        frequency_service=frequency_service,
    )
    context = _EpisodeContext(
        start_time=0.0,
        video_file_str="golden-video.mkv",
        subtitle_file_str="golden-subtitles.srt",
        episode_name="Golden Episode",
        series_name="Golden Series",
        source_label="Golden Episode",
    )
    survivors = processor._phase2_filter(context, words, None, None)
    return {
        "allow_duplicate_cards": allow_duplicates,
        "normalized_existing_first_fields": [
            {"raw": raw, "normalized": result}
            for raw, result in zip(raw_fields, normalized, strict=True)
        ],
        "survivor_ids": [ids[id(word)] for word in survivors],
        "survivors": [
            {
                "id": ids[id(word)],
                "mined_form": word.mined_form,
                "frequency_sources": [list(source) for source in word.frequency_sources],
                "frequency_rank": word.frequency_rank,
                "frequency_harmonic_rank": word.frequency_harmonic_rank,
            }
            for word in survivors
        ],
        "candidate_words_found": context.candidate_words_found,
        "new_words_found": context.new_words_found,
        "comprehension_percentage": context.comprehension_percentage,
        "events": presenter.events,
    }


def _deinflection(cases: Sequence[Any]) -> list[dict[str, Any]]:
    from anki_miner.services.deinflection import get_japanese_deinflector

    deinflector = get_japanese_deinflector()
    output: list[dict[str, Any]] = []
    for raw in cases:
        case = _mapping(raw, "deinflection case")
        source = _string(case.get("source"), "deinflection source")
        target = _string(case.get("target"), "deinflection target")
        matches = [result for result in deinflector.transform(source) if result.text == target]
        matches.sort(key=lambda result: (len(result.trace), result.trace, result.conditions))
        if not matches:
            raise AssertionError(f"deinflection did not reach {target}")
        output.append(
            {
                "id": _string(case.get("id"), "deinflection id"),
                "input": {"source": source, "target": target},
                "output": [
                    {
                        "text": result.text,
                        "conditions": result.conditions,
                        "trace_surface_first": [
                            {
                                "transform_id": frame[0],
                                "rule_index": frame[1],
                                "source_text": frame[2],
                            }
                            for frame in result.trace
                        ],
                        "inflection_rules_attachment_order": [
                            frame[0] for frame in reversed(result.trace)
                        ],
                    }
                    for result in matches
                ],
            }
        )
    return output


def _zip_write(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    archive.writestr(
        info,
        data,
        compress_type=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    )


def _build_dictionary_zip(path: Path, value: Mapping[str, Any]) -> None:
    term_bank: list[list[Any]] = []
    has_media = False
    for raw in _list(value.get("entries"), "dictionary entries"):
        entry = _mapping(raw, "dictionary entry")
        glossary = _list(entry.get("glossary"), "dictionary glossary")
        has_media = has_media or "images/wager.png" in json.dumps(
            glossary, ensure_ascii=False
        )
        term_bank.append(
            [
                _string(entry.get("term"), "dictionary term"),
                _string(entry.get("reading"), "dictionary reading"),
                str(entry.get("definition_tags", "")),
                str(entry.get("rules", "")),
                int(entry.get("score", 0)),
                glossary,
                int(entry["sequence"]) if entry.get("sequence") is not None else None,
                str(entry.get("term_tags", "")),
            ]
        )
    index = {
        "title": _string(value.get("display_name"), "dictionary display name"),
        "revision": "golden-v2",
        "format": 3,
        "sequenced": True,
    }
    tags = [
        ["v1", "partOfSpeech", -3, "Ichidan verb", 1],
        ["n", "partOfSpeech", -3, "noun", 1],
        ["common", "frequency", 0, "common term", 1],
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        _zip_write(archive, "index.json", _canonical(index))
        _zip_write(archive, "term_bank_1.json", _canonical(term_bank))
        _zip_write(archive, "tag_bank_1.json", _canonical(tags))
        if has_media:
            _zip_write(archive, "images/wager.png", _PNG_1X1)


def _dictionary_resolution(
    service: Any,
    providers: Sequence[Any],
    query: Mapping[str, Any],
    output: str | None,
) -> dict[str, Any] | None:
    if output is None:
        return None
    term = str(query["term"])
    reading = str(query["reading"])
    for provider in providers:
        if provider.lookup_many([(term, reading)]).get(term) == output:
            return {"provider": provider.name, "candidate": term, "mode": "exact"}
    orth_base = str(query.get("fallback_orth_base", ""))
    ctype = (
        str(query["fallback_ctype"])
        if query.get("fallback_ctype") is not None
        else None
    )
    for candidate, conditions in service._fallback_candidates(term, orth_base, ctype):
        for provider in providers:
            if provider.lookup_fallback(candidate, conditions) == output:
                return {
                    "provider": provider.name,
                    "candidate": candidate,
                    "conditions": conditions,
                    "mode": "fallback",
                }
    raise AssertionError(f"cannot attribute dictionary output for {term}")


def _dictionaries(
    root: Path,
    value: Mapping[str, Any],
    config: Any,
) -> tuple[list[dict[str, Any]], Any, list[Any]]:
    from anki_miner.services.definition_service import DefinitionService
    from anki_miner.services.dictionary.importers.yomitan_importer import import_yomitan_zip
    from anki_miner.services.dictionary.providers.indexed_provider import IndexedDictProvider

    providers: list[Any] = []
    identities: list[dict[str, Any]] = []
    for raw in _list(value.get("providers"), "dictionary providers"):
        spec = _mapping(raw, "dictionary provider")
        dict_id = _string(spec.get("dict_id"), "dictionary id")
        display_name = _string(spec.get("display_name"), "dictionary display name")
        source = root / f"{dict_id}.zip"
        _build_dictionary_zip(source, spec)
        imported = import_yomitan_zip(source, config.dicts_root, dict_id=dict_id)
        database = config.dicts_root / imported.dict_id / "index.sqlite"
        provider = IndexedDictProvider(imported.dict_id, database, display_name=display_name)
        if not provider.load():
            raise AssertionError(f"cannot load dictionary {dict_id}")
        providers.append(provider)
        connection = sqlite3.connect(database)
        try:
            rows = connection.execute(
                "SELECT id, term, reading, sequence, score, rules FROM entries ORDER BY id"
            ).fetchall()
        finally:
            connection.close()
        identities.extend(
            {
                "provider": display_name,
                "row_id": row[0],
                "term": row[1],
                "reading": row[2],
                "sequence": row[3],
                "score": row[4],
                "rules": row[5],
            }
            for row in rows
        )
    service = DefinitionService(config, providers)
    queries = [
        _mapping(item, "dictionary query")
        for item in _list(value.get("queries"), "dictionary queries")
    ]
    pairs = [(str(query["term"]), str(query["reading"])) for query in queries]
    fallback_context = {
        str(query["term"]): (
            str(query.get("fallback_orth_base", "")),
            (
                str(query["fallback_ctype"])
                if query.get("fallback_ctype") is not None
                else None
            ),
        )
        for query in queries
        if "fallback_orth_base" in query
    }
    first_hits = service.get_definitions_batch(pairs, fallback_context=fallback_context)
    glossaries = service.get_glossaries_batch(pairs)
    outputs: list[dict[str, Any]] = []
    for query, first_hit, glossary in zip(queries, first_hits, glossaries, strict=True):
        term = str(query["term"])
        outputs.append(
            {
                "id": _string(query.get("id"), "dictionary query id"),
                "input": {
                    "term": term,
                    "reading": str(query["reading"]),
                    "fallback_orth_base": query.get("fallback_orth_base"),
                    "fallback_ctype": query.get("fallback_ctype"),
                },
                "output": {
                    "first_hit": first_hit,
                    "first_hit_resolution": _dictionary_resolution(
                        service, providers, query, first_hit
                    ),
                    "glossary": glossary,
                    "offline_hits": [
                        {"provider": name, "html": rendered}
                        for name, rendered in service.lookup_all_offline(term)
                    ],
                },
            }
        )
    return [{"entry_identity": identities, "queries": outputs}], service, providers


def _frequency(
    root: Path,
    value: Mapping[str, Any],
) -> tuple[list[dict[str, Any]], Any, list[Any]]:
    from anki_miner.services.frequency import storage
    from anki_miner.services.frequency.multi_frequency_service import (
        MultiFrequencyService,
        harmonic_rank,
        min_rank,
    )
    from anki_miner.services.frequency.providers.indexed_freq_provider import (
        IndexedFreqProvider,
    )

    providers: list[Any] = []
    for raw in _list(value.get("providers"), "frequency providers"):
        spec = _mapping(raw, "frequency provider")
        source_id = _string(spec.get("source_id"), "frequency source id")
        display_name = _string(spec.get("display_name"), "frequency display name")
        rows: list[tuple[str, str | None, int, str | None]] = []
        for raw_row in _list(spec.get("rows"), "frequency rows"):
            row = _list(raw_row, "frequency row")
            if len(row) != 4:
                raise AssertionError("frequency row must have four columns")
            rows.append(
                (
                    str(row[0]),
                    str(row[1]) if row[1] is not None else None,
                    int(row[2]),
                    row[3],
                )
            )
        database = root / source_id / "index.sqlite"
        storage.build_index(
            database,
            rows,
            {
                "schema_version": str(storage.SCHEMA_VERSION),
                "source_name": display_name,
                "source_revision": "golden-v2",
                "format": "golden",
                "entry_count": str(len(rows)),
                "is_categorical": "0",
            },
        )
        provider = IndexedFreqProvider(source_id, database, display_name)
        if not provider.load():
            raise AssertionError(f"cannot load frequency source {source_id}")
        providers.append(provider)
    service = MultiFrequencyService(providers)
    output: list[dict[str, Any]] = []
    for raw in _list(value.get("queries"), "frequency queries"):
        query = _mapping(raw, "frequency query")
        term = _string(query.get("term"), "frequency term")
        reading = _string(query.get("reading"), "frequency reading")
        sources = service.lookup_all(term, reading)
        output.append(
            {
                "id": _string(query.get("id"), "frequency id"),
                "input": {"term": term, "reading": reading},
                "output": {
                    "sources": [list(source) for source in sources],
                    "minimum_rank": min_rank(sources),
                    "harmonic_rank": harmonic_rank(sources),
                },
            }
        )
    return output, service, providers


def _pitch(root: Path, value: Mapping[str, Any]) -> tuple[list[dict[str, Any]], Any]:
    from anki_miner.services.pitch_accent.render import (
        render_pitch_graph_field,
        render_pitch_text_field,
    )
    from anki_miner.services.pitch_accent_service import PitchAccentService

    root.mkdir(parents=True, exist_ok=True)
    path = root / "pitch.csv"
    lines: list[str] = []
    for raw in _list(value.get("rows"), "pitch rows"):
        row = _list(raw, "pitch row")
        if len(row) != 5 or any(not isinstance(item, str) for item in row):
            raise AssertionError("pitch rows must have five strings")
        lines.append(",".join(row))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    service = PitchAccentService(path)
    if not service.load():
        raise AssertionError("cannot load pitch fixture")
    output: list[dict[str, Any]] = []
    for raw in _list(value.get("queries"), "pitch queries"):
        query = _mapping(raw, "pitch query")
        term = _string(query.get("term"), "pitch term")
        reading = _string(query.get("reading"), "pitch reading")
        pos = _string(query.get("pos"), "pitch pos")
        pattern, category = service.lookup_detailed(term, reading, pos)
        entry = service.lookup_entry(term, reading)
        output.append(
            {
                "id": _string(query.get("id"), "pitch id"),
                "input": {"term": term, "reading": reading, "pos": pos},
                "output": {
                    "pattern": pattern,
                    "category": category,
                    "nasal_morae": list(entry.nasal) if entry else [],
                    "devoiced_morae": list(entry.devoice) if entry else [],
                    "graph_html": (
                        render_pitch_graph_field(pattern, reading) if pattern else ""
                    ),
                    "text_html": (
                        render_pitch_text_field(
                            pattern,
                            reading,
                            entry.nasal if entry else (),
                            entry.devoice if entry else (),
                        )
                        if pattern
                        else ""
                    ),
                },
            }
        )
    return output, service


def _content_addressed_name(filename: str, raw: bytes) -> str:
    path = Path(filename)
    return f"{path.stem}_{hashlib.sha1(raw).hexdigest()[:12]}{path.suffix}"


def _provider_actual_name(requested: str) -> str:
    path = Path(requested)
    preferred = (path.stem if path.suffix else requested).replace(" ", "_")
    if len(preferred) < 2:
        preferred = f"{preferred}_"
    return f"{preferred}_provider{path.suffix}"


def _dictionary_actual_name(source: str) -> str:
    digest = hashlib.sha256(source.encode("utf-8")).hexdigest()
    return f"anki_miner_dict_{digest}_provider{Path(source).suffix or '.bin'}"


def _rewrite_dictionary_html(value: str, names: Mapping[str, str]) -> str:
    from anki_miner.services.anki_media_store import _DICT_MEDIA_IMG_RE, _IMG_SRC_RE

    def rewrite(match: re.Match[str]) -> str:
        tag = match.group(0)
        source = _IMG_SRC_RE.search(tag)
        if source is None:
            return tag
        original = html.unescape(source.group(1))
        actual = names.get(original)
        if actual is None or actual == original:
            return tag
        return (
            tag[: source.start(1)]
            + html.escape(actual, quote=True)
            + tag[source.end(1) :]
        )

    result = _DICT_MEDIA_IMG_RE.sub(rewrite, value)
    for original, actual in names.items():
        result = result.replace(
            f"url(&quot;{html.escape(original, quote=True)}&quot;)",
            f"url(&quot;{html.escape(actual, quote=True)}&quot;)",
        )
    return result


def _marked_sources(value: str) -> list[str]:
    output: list[str] = []
    for tag in _MARKED_DICTIONARY_IMG_RE.findall(value):
        match = _IMG_SRC_RE.search(tag)
        if match is not None:
            output.append(html.unescape(match.group(1)))
    return output


def _cards(
    root: Path,
    value: Mapping[str, Any],
    identity: Mapping[str, Any],
    filtering_input: Mapping[str, Any],
    dictionary_section: Sequence[dict[str, Any]],
    frequency_service: Any,
    pitch_service: Any,
) -> list[dict[str, Any]]:
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.models import CardPayload, MediaData
    from anki_miner.services.anki_media_store import _extract_dict_media_srcs
    from anki_miner.services.anki_note_builder import _strip_for_dedup, build_note
    from anki_miner.services.frequency.multi_frequency_service import harmonic_rank
    from anki_miner.services.frequency.render import render_frequency_html
    from anki_miner.services.pitch_accent.render import (
        render_pitch_graph_field,
        render_pitch_text_field,
    )

    root.mkdir(parents=True, exist_ok=True)
    word_id = _string(value.get("word_id"), "card word id")
    word_record = next(
        (
            _mapping(item, "filtering word")
            for item in _list(filtering_input.get("words"), "filtering words")
            if isinstance(item, dict) and item.get("id") == word_id
        ),
        None,
    )
    if word_record is None:
        raise AssertionError("card word is absent from filtering input")
    word = _word_from_input(word_record)
    word.sentence_bolded = _string(value.get("sentence_bolded"), "card sentence bolded")
    word.sentence_furigana = _string(value.get("sentence_furigana"), "card sentence furigana")
    word.sentence_furigana_bolded = _string(
        value.get("sentence_furigana_bolded"), "card sentence furigana bolded"
    )
    word.sentence_reading = _string(value.get("sentence_reading"), "card sentence reading")
    word.expression_furigana = "賭[か]ける"
    frequency_sources = frequency_service.lookup_all(
        word.mined_form, word.expression_reading
    )
    word.frequency_sources = frequency_sources
    word.frequency_rank = min((row[1] for row in frequency_sources), default=None)
    word.frequency_harmonic_rank = harmonic_rank(frequency_sources)
    pattern, category = pitch_service.lookup_detailed(
        word.lemma, word.lemma_reading, word.pos
    )
    pitch_entry = pitch_service.lookup_entry(word.lemma, word.lemma_reading)
    if pattern is None:
        raise AssertionError("card pitch input did not resolve")
    dictionary_record = next(
        record
        for record in dictionary_section[0]["queries"]
        if record["id"] == "first-hit-and-glossary-order"
    )
    definition = dictionary_record["output"]["first_hit"]
    glossary = dictionary_record["output"]["glossary"]
    if not isinstance(definition, str) or not isinstance(glossary, str):
        raise AssertionError("card dictionary input did not resolve")
    sources = sorted(
        set(_extract_dict_media_srcs(definition))
        | set(_extract_dict_media_srcs(glossary))
    )
    if len(sources) != 1:
        raise AssertionError("card fixture requires one dictionary image")
    dictionary_source = sources[0]
    dictionary_actual = _dictionary_actual_name(dictionary_source)
    rewrites = {dictionary_source: dictionary_actual}
    rewritten_definition = _rewrite_dictionary_html(definition, rewrites)
    rewritten_glossary = _rewrite_dictionary_html(glossary, rewrites)

    def direct_asset(config_key: str, identity_key: str, media_kind: str) -> dict[str, Any]:
        spec = _mapping(value.get(config_key), f"card {config_key}")
        original = _string(spec.get("original_filename"), "card original filename")
        raw = _string(spec.get("content_utf8"), "card content").encode("utf-8")
        path = root / original
        path.write_bytes(raw)
        requested = _content_addressed_name(original, raw)
        return {
            "asset_id": identity[identity_key],
            "source_fixture": config_key,
            "purpose": "card",
            "media_kind": media_kind,
            "source_path": path,
            "original_filename": original,
            "requested_filename": requested,
            "actual_filename": _provider_actual_name(requested),
            "size_bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
        }

    audio = direct_asset("card_audio", "card_audio_asset_id", "audio")
    image = direct_asset("card_image", "card_image_asset_id", "image")
    media = MediaData(
        screenshot_path=image["source_path"],
        audio_path=audio["source_path"],
        screenshot_filename=image["actual_filename"],
        audio_filename=audio["actual_filename"],
    )
    field_mapping = _mapping(value.get("field_mapping"), "card field mapping")
    config_args = {
        "anki_deck_name": _string(value.get("deck_name"), "card deck"),
        "anki_note_type": _string(value.get("model_name"), "card model"),
        "anki_fields": dict(field_mapping),
        "anki_tags": _string(value.get("tags"), "card tags"),
        "card_type": _string(value.get("card_type"), "card type"),
        "bold_target_in_sentence": True,
    }
    extra_fields = {
        "glossary": rewritten_glossary,
        "pitch_position": pattern,
        "pitch_category": category or "",
        "pitch_graph": render_pitch_graph_field(pattern, word.lemma_reading),
        "pitch_text": render_pitch_text_field(
            pattern,
            word.lemma_reading,
            pitch_entry.nasal if pitch_entry else (),
            pitch_entry.devoice if pitch_entry else (),
        ),
        "frequency": render_frequency_html(frequency_sources),
        "frequency_sort": str(word.frequency_harmonic_rank),
        "source": _string(value.get("source"), "card source"),
    }
    payload = CardPayload(
        word=word,
        media=media,
        definition=rewritten_definition,
        extra_fields=extra_fields,
    )
    stored_files = {str(audio["actual_filename"]), str(image["actual_filename"])}
    normal_config = AnkiMinerConfig(**config_args, allow_duplicate_cards=False)
    duplicate_config = AnkiMinerConfig(**config_args, allow_duplicate_cards=True)
    normal_note = build_note(payload, normal_config, stored_files).note
    duplicate_note = build_note(payload, duplicate_config, stored_files).note
    field_order = list(normal_note["fields"])
    fields = [{"name": name, "value": normal_note["fields"][name]} for name in field_order]
    first_field_name = str(field_mapping["word"])
    first_field = str(normal_note["fields"][first_field_name])
    duplicate_key = _strip_for_dedup(first_field)
    media_assets = [
        {
            key: asset[key]
            for key in (
                "asset_id",
                "source_fixture",
                "purpose",
                "media_kind",
                "original_filename",
                "requested_filename",
                "actual_filename",
                "size_bytes",
                "sha256",
            )
        }
        for asset in (audio, image)
    ]
    media_assets.append(
        {
            "asset_id": identity["dictionary_image_asset_id"],
            "source_fixture": "dictionary_image",
            "purpose": "dictionary",
            "media_kind": "image",
            "original_filename": dictionary_source,
            "requested_filename": dictionary_source,
            "actual_filename": dictionary_actual,
            "size_bytes": len(_PNG_1X1),
            "sha256": hashlib.sha256(_PNG_1X1).hexdigest(),
        }
    )
    bindings = [
        {"assetId": asset["asset_id"], "actualFilename": asset["actual_filename"]}
        for asset in media_assets
    ]
    request = {
        "runId": identity["run_id"],
        "requestId": identity["request_id"],
        "deckName": normal_note["deckName"],
        "modelName": normal_note["modelName"],
        "firstFieldName": first_field_name,
        "notes": [
            {
                "clientNoteId": identity["client_note_id"],
                "fieldOrder": field_order,
                "fields": fields,
                "tags": normal_note["tags"],
                "mediaBindings": bindings,
                "duplicateCandidate": {
                    "key": duplicate_key,
                    "firstField": first_field,
                    "occurrence": 0,
                },
            }
        ],
    }
    result = {
        "runId": identity["run_id"],
        "requestId": identity["request_id"],
        "results": [
            {
                "clientNoteId": identity["client_note_id"],
                "status": "created",
                "noteId": identity["note_id"],
            }
        ],
    }
    return [
        {
            "id": "desktop-note-and-android-transport",
            "input": {
                "word_id": word_id,
                "unrewritten_definition": definition,
                "unrewritten_glossary": glossary,
                "media_store_request": {
                    "runId": identity["run_id"],
                    "requestId": identity["request_id"],
                    "assets": media_assets,
                },
                "media_store_result": {
                    "runId": identity["run_id"],
                    "requestId": identity["request_id"],
                    "results": [
                        {
                            "assetId": asset["asset_id"],
                            "status": "stored",
                            "actualFilename": asset["actual_filename"],
                        }
                        for asset in media_assets
                    ],
                },
            },
            "output": {
                "rewritten_definition": rewritten_definition,
                "rewritten_glossary": rewritten_glossary,
                "route": {
                    "deck_name": normal_note["deckName"],
                    "model_name": normal_note["modelName"],
                    "tags": normal_note["tags"],
                    "card_type": value["card_type"],
                    "card_type_field": normal_config.card_type_marker_fields[
                        normal_config.card_type
                    ],
                },
                "normal_duplicate_options": normal_note.get("options"),
                "allow_duplicate_options": duplicate_note.get("options"),
                "create_notes_request": request,
                "create_notes_result": result,
            },
        }
    ]


def _assert_card_transport(card: Mapping[str, Any]) -> None:
    request = card["output"]["create_notes_request"]
    result = card["output"]["create_notes_result"]
    media_request = card["input"]["media_store_request"]
    for key in ("runId", "requestId"):
        if request[key] != result[key] or request[key] != media_request[key]:
            raise AssertionError(f"card transport lost {key}")
    note = request["notes"][0]
    if [field["name"] for field in note["fields"]] != note["fieldOrder"]:
        raise AssertionError("card transport lost explicit field order")
    rendered = "\n".join(field["value"] for field in note["fields"])
    dictionary_html = card["output"]["rewritten_definition"] + card["output"]["rewritten_glossary"]
    for asset in media_request["assets"]:
        if asset["purpose"] == "card":
            if asset["actual_filename"] not in rendered:
                raise AssertionError("card field lost provider media filename")
            if asset["requested_filename"] in rendered:
                raise AssertionError("card field retained requested media filename")
        else:
            sources = set(_marked_sources(dictionary_html))
            if asset["actual_filename"] not in sources:
                raise AssertionError("dictionary HTML lost provider media filename")
            if asset["requested_filename"] in dictionary_html:
                raise AssertionError("dictionary HTML retained logical media filename")


def run(
    fixture_json: str,
    corpus_json: str,
    input_json: str,
    dicdir: str,
    expected_home: str,
) -> str:
    """Recompute all nine sections through packaged engine code and compare exactly."""

    fixture = _mapping(json.loads(fixture_json), "fixture")
    corpus_document = _mapping(json.loads(corpus_json), "tokenizer corpus")
    contract_input = _mapping(json.loads(input_json), "contract input")
    canonical_fixture = _canonical(fixture) + b"\n"
    if hashlib.sha256(canonical_fixture).hexdigest() != FIXTURE_SHA256:
        raise AssertionError("packaged v2 fixture hash changed")
    if fixture.get("schema_version") != 2 or contract_input.get("schema_version") != 2:
        raise AssertionError("packaged complete contract is not v2")
    if set(fixture.get("cases", {})) != set(SECTIONS):
        raise AssertionError("packaged v2 sections changed")
    for section in SECTIONS:
        if fixture["section_status"].get(section) != {"state": "implemented"}:
            raise AssertionError(f"packaged section is not implemented: {section}")
    expected_input_hash = fixture["provenance"]["data"]["contract_input_sha256"]
    if hashlib.sha256(input_json.encode("utf-8")).hexdigest() != expected_input_hash:
        raise AssertionError("packaged contract input provenance changed")

    from android_bridge.bootstrap import require_initialized
    from android_bridge.tokenizer_selection import configure_tokenizer_backend
    from android_bridge.unidic_resource import register_unidic

    home = require_initialized(expected_home)
    unidic = fixture["provenance"]["data"]["unidic"]
    registration = register_unidic(
        dicdir,
        resource_id=unidic["resource_id"],
        expected_tree_sha256=unidic["tree"]["sha256"],
    )
    backend = configure_tokenizer_backend("s1a")
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.services.tagger import get_shared_tagger

    tagger = get_shared_tagger()
    corpus = _list(corpus_document.get("cases"), "tokenizer corpus cases")
    providers: list[Any] = []
    frequency_providers: list[Any] = []
    with tempfile.TemporaryDirectory(prefix="golden-v2-", dir=home) as raw_root:
        root = Path(raw_root)
        config = AnkiMinerConfig(
            dicts_root=root / "dicts",
            freqs_root=root / "freqs",
            pitch_accent_path=root / "pitch.csv",
        )
        actual: dict[str, list[dict[str, Any]]] = {}
        actual["tokenization"] = _tokenization(corpus, tagger)
        actual["morphology"], actual["compounds"] = _morphology_and_compounds(corpus)
        try:
            actual["dictionaries"], definitions, providers = _dictionaries(
                root / "dictionary-build",
                _mapping(contract_input["dictionaries"], "dictionaries"),
                config,
            )
            actual["frequency"], frequencies, frequency_providers = _frequency(
                root / "frequency-build",
                _mapping(contract_input["frequency"], "frequency"),
            )
            actual["pitch"], pitch = _pitch(
                root / "pitch-build",
                _mapping(contract_input["pitch"], "pitch"),
            )
            filtering = _mapping(contract_input["filtering"], "filtering")
            actual["filtering"] = [
                {
                    "id": "phase2-known-and-within-run-duplicates",
                    "input": filtering,
                    "output": {
                        "normal": _filtering_run(
                            filtering, frequencies, definitions, False
                        ),
                        "allow_duplicates": _filtering_run(
                            filtering, frequencies, definitions, True
                        ),
                    },
                }
            ]
            actual["deinflection"] = _deinflection(
                _list(contract_input["deinflection"], "deinflection")
            )
            actual["cards"] = _cards(
                root / "card-build",
                _mapping(contract_input["card"], "card"),
                _mapping(contract_input["identity"], "identity"),
                filtering,
                actual["dictionaries"],
                frequencies,
                pitch,
            )
        finally:
            for provider in reversed(frequency_providers):
                provider.close()
            for provider in reversed(providers):
                provider.close()

    section_hashes: dict[str, str] = {}
    case_counts: dict[str, int] = {}
    for section in SECTIONS:
        expected = fixture["cases"][section]
        if actual[section] != expected:
            raise AssertionError(f"packaged engine golden mismatch: {section}")
        section_hashes[section] = hashlib.sha256(_canonical(actual[section])).hexdigest()
        case_counts[section] = len(actual[section])
    _assert_card_transport(actual["cards"][0])
    return json.dumps(
        {
            "backend": backend,
            "case_counts": case_counts,
            "dictionary_sha256": registration.tree_sha256,
            "fixture_sha256": FIXTURE_SHA256,
            "home": home,
            "section_hashes": section_hashes,
            "tagger_path": "engine_shared_tagger",
        },
        sort_keys=True,
    )
