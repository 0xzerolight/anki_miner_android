"""Real vendored reading-source and orchestration smokes.

The bridge protocol tests isolate detection/loading and ``process_reading``
with monkeypatches so their failure modes stay precise.  This module closes the
complementary integration gap: tiny files cross the real Android bridge into
the vendored desktop detector/loaders, and one archive-backed Mokuro document
crosses the real ``EpisodeProcessor.process_reading`` orchestration.  External
tokenizer, dictionary, and Anki boundaries remain deterministic in-memory
doubles; no network, device, UniDic tree, or Anki collection is required.
"""

from __future__ import annotations

import base64
import collections
import json
import re
import zipfile
from dataclasses import replace
from pathlib import Path

import android_bridge.reading_mining as reading_mining
import pytest
from android_bridge.protocol import encode_message

_PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk" "+A8AAQUBAScY42YAAAAASUVORK5CYII="
)

_AOZORA_TEXT = "\n".join(
    (
        "吾輩は猫である",
        "夏目漱石",
        "",
        "　吾輩は｜猫《ねこ》である。名前はまだ無い。",
        "",
        "底本：『吾輩は猫である』",
        "青空文庫作成ファイル：",
    )
)

_SRT_TEXT = """\
1
00:00:01,000 --> 00:00:03,000
<i>猫を見る。</i>

2
00:01:23,500 --> 00:01:25,000
犬もいる。
"""

_EPUB_CONTAINER = """\
<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

_EPUB_OPF = """\
<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>試験小説</dc:title>
  </metadata>
  <manifest>
    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="chapter"/></spine>
</package>
"""

_EPUB_CHAPTER = """\
<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>第一章</title></head>
  <body><p>吾輩は猫である。名前はまだ無い。</p></body>
</html>
"""


def _reading_request(
    *,
    cache_dir: Path,
    source_kind: str,
    source_path: Path,
    image_archive_path: Path | None = None,
    series_name: str | None = None,
) -> str:
    return encode_message(
        "mining.reading.run",
        {
            "sourceKind": source_kind,
            "sourcePath": str(source_path),
            "imageArchivePath": (str(image_archive_path) if image_archive_path is not None else None),
            "seriesName": series_name,
            "cacheDir": str(cache_dir),
            "nativeLibraryDir": str(cache_dir / "native"),
            "configSnapshot": {
                "settings": {},
                "androidTtsEnabled": False,
            },
        },
    )


def _load_real_document(
    *,
    cache_dir: Path,
    source_kind: str,
    source_path: Path,
    image_archive_path: Path | None = None,
    series_name: str | None = None,
) -> object:
    # Importing the vendored ``anki_miner.services`` package also imports its
    # production Anki HTTP service.  The common host lane intentionally carries
    # pytest/jsonschema only; the runtime-host lane supplies this Android
    # dependency closure and is where these real-engine assertions execute.
    pytest.importorskip("requests")
    request = reading_mining._parse_request(
        _reading_request(
            cache_dir=cache_dir,
            source_kind=source_kind,
            source_path=source_path,
            image_archive_path=image_archive_path,
            series_name=series_name,
        )
    )
    return reading_mining._load_document(request)


def _image_snapshot(image_ref: object | None, root: Path) -> object | None:
    if image_ref is None:
        return None
    return {
        "source": str(image_ref.source.relative_to(root)),
        "entry": image_ref.entry,
    }


def _document_snapshot(document: object, root: Path) -> dict[str, object]:
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
                "image": _image_snapshot(unit.image_ref, root),
                "blockBox": unit.block_box,
            }
            for unit in document.units
        ],
    }


def _write_aozora_fixture(job_dir: Path) -> Path:
    job_dir.mkdir()
    source = job_dir / "wagahai.txt"
    source.write_bytes(_AOZORA_TEXT.encode("cp932"))
    return source


def _write_subtitle_fixture(job_dir: Path) -> Path:
    job_dir.mkdir()
    source = job_dir / "episode.srt"
    source.write_text(_SRT_TEXT, encoding="utf-8")
    return source


def _write_epub_fixture(job_dir: Path) -> Path:
    job_dir.mkdir()
    source = job_dir / "novel.epub"
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip")
        archive.writestr("META-INF/container.xml", _EPUB_CONTAINER)
        archive.writestr("OEBPS/content.opf", _EPUB_OPF)
        archive.writestr("OEBPS/cover.png", _PNG_BYTES)
        archive.writestr("OEBPS/chapter.xhtml", _EPUB_CHAPTER)
    return source


def _write_mokuro_fixture(job_dir: Path) -> tuple[Path, Path]:
    job_dir.mkdir()
    source = job_dir / "volume.mokuro"
    source.write_text(
        json.dumps(
            {
                "version": "0.2.4",
                "title": "漫画作品",
                "title_uuid": "title-fixture",
                "volume": "第一巻",
                "volume_uuid": "volume-fixture",
                "pages": [
                    {
                        "version": "0.2.4",
                        "img_width": 1,
                        "img_height": 1,
                        "img_path": "pages/001.png",
                        "blocks": [
                            {
                                "box": [1, 2, 30, 40],
                                "vertical": True,
                                "font_size": 24,
                                "lines": ["猫を", "見る。"],
                                "lines_coords": [],
                            }
                        ],
                    },
                    {
                        "version": "0.2.4",
                        "img_width": 1,
                        "img_height": 1,
                        "img_path": "pages/002.png",
                        "blocks": [
                            {
                                "box": [5, 6, 35, 46],
                                "vertical": True,
                                "font_size": 24,
                                "lines": ["犬もいる。"],
                                "lines_coords": [],
                            }
                        ],
                    },
                ],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )
    image_archive = job_dir / "volume.cbz"
    with zipfile.ZipFile(image_archive, "w") as archive:
        archive.writestr("pages/001.png", _PNG_BYTES)
        archive.writestr("pages/002.png", _PNG_BYTES)
    return source, image_archive


def test_real_aozora_detector_and_loader_match_contract(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    assert initialized_bridge_home.is_absolute()
    source = _write_aozora_fixture(tmp_path / "reading-job-v1-aozora")

    document = _load_real_document(
        cache_dir=tmp_path,
        source_kind="txt",
        source_path=source,
    )

    assert _document_snapshot(document, tmp_path) == {
        "title": "吾輩は猫である",
        "kind": "book",
        "series": "Books",
        "episode": "吾輩は猫である",
        "warnings": [],
        "units": [
            {
                "text": "吾輩は猫である。",
                "index": 0,
                "location": "¶1",
                "image": None,
                "blockBox": None,
            },
            {
                "text": "名前はまだ無い。",
                "index": 1,
                "location": "¶1",
                "image": None,
                "blockBox": None,
            },
        ],
    }


def test_real_subtitle_detector_and_loader_match_contract(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    assert initialized_bridge_home.is_absolute()
    pytest.importorskip("pysubs2")
    source = _write_subtitle_fixture(tmp_path / "reading-job-v1-subtitle")

    document = _load_real_document(
        cache_dir=tmp_path,
        source_kind="subtitle",
        source_path=source,
        series_name="Imported subtitles",
    )

    assert _document_snapshot(document, tmp_path) == {
        "title": "episode",
        "kind": "subtitle",
        "series": "Imported subtitles",
        "episode": "episode",
        "warnings": [],
        "units": [
            {
                "text": "猫を見る。",
                "index": 0,
                "location": "0:01",
                "image": None,
                "blockBox": None,
            },
            {
                "text": "犬もいる。",
                "index": 1,
                "location": "1:23",
                "image": None,
                "blockBox": None,
            },
        ],
    }


def test_real_epub_detector_and_loader_match_contract(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    assert initialized_bridge_home.is_absolute()
    pytest.importorskip("lxml")
    source = _write_epub_fixture(tmp_path / "reading-job-v1-epub")

    document = _load_real_document(
        cache_dir=tmp_path,
        source_kind="epub",
        source_path=source,
    )

    expected_image = {
        "source": "reading-job-v1-epub/novel.epub",
        "entry": "OEBPS/cover.png",
    }
    assert _document_snapshot(document, tmp_path) == {
        "title": "試験小説",
        "kind": "book",
        "series": "Books",
        "episode": "試験小説",
        "warnings": [],
        "units": [
            {
                "text": "吾輩は猫である。",
                "index": 0,
                "location": "ch.0",
                "image": expected_image,
                "blockBox": None,
            },
            {
                "text": "名前はまだ無い。",
                "index": 1,
                "location": "ch.0",
                "image": expected_image,
                "blockBox": None,
            },
        ],
    }


def test_real_mokuro_archive_detector_and_loader_match_contract(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    assert initialized_bridge_home.is_absolute()
    source, image_archive = _write_mokuro_fixture(tmp_path / "reading-job-v1-mokuro")

    document = _load_real_document(
        cache_dir=tmp_path,
        source_kind="mokuro",
        source_path=source,
        image_archive_path=image_archive,
    )

    assert _document_snapshot(document, tmp_path) == {
        "title": "漫画作品",
        "kind": "manga",
        "series": "漫画作品",
        "episode": "第一巻",
        "warnings": [],
        "units": [
            {
                "text": "猫を見る。",
                "index": 0,
                "location": "p.1",
                "image": {
                    "source": "reading-job-v1-mokuro/volume.cbz",
                    "entry": "pages/001.png",
                },
                "blockBox": (1, 2, 30, 40),
            },
            {
                "text": "犬もいる。",
                "index": 1,
                "location": "p.2",
                "image": {
                    "source": "reading-job-v1-mokuro/volume.cbz",
                    "entry": "pages/002.png",
                },
                "blockBox": (5, 6, 35, 46),
            },
        ],
    }


def test_real_mokuro_loader_preserves_valid_siblings_of_malformed_records(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    assert initialized_bridge_home.is_absolute()
    job_dir = tmp_path / "reading-job-v1-mixed-mokuro"
    job_dir.mkdir()
    source = job_dir / "mixed.mokuro"
    source.write_text(
        json.dumps(
            {
                "version": "0.2.4",
                "title": "混在作品",
                "title_uuid": "mixed-title",
                "volume": "第一巻",
                "volume_uuid": "mixed-volume",
                "pages": [
                    None,
                    {"blocks": "not-a-list"},
                    {
                        "blocks": [
                            None,
                            {"lines": "not-a-list"},
                            {"lines": [7, "まとも。"]},
                        ]
                    },
                ],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )

    document = _load_real_document(
        cache_dir=tmp_path,
        source_kind="mokuro",
        source_path=source,
    )

    assert [unit.text for unit in document.units] == ["まとも。"]
    assert [unit.location_label for unit in document.units] == ["p.3"]
    assert document.warnings == [
        "text-only volume: pages have no paired images",
        "Skipped 5 malformed Mokuro record(s).",
    ]


class _DocumentParser:
    """Deterministic tokenizer boundary for the actual processor smoke."""

    def __init__(self, tokenized_word_type: type) -> None:
        self._tokenized_word_type = tokenized_word_type
        self.received_units: list[object] = []

    def parse_text_units(
        self,
        units: list[object],
        want_line_index: bool,
        *,
        subtitle_cleanup: bool = False,
    ) -> tuple[list[object], None, collections.Counter[str]]:
        assert want_line_index is False
        # The processor keys per-cue cleanup on the document kind; this fixture
        # is a mokuro volume, so cleanup must stay off.
        assert subtitle_cleanup is False
        assert [unit.text for unit in units] == ["猫を見る。", "犬もいる。"]
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
        **_kwargs: object,
    ) -> list[str]:
        assert progress_callback is None
        self.lookup_pairs = list(pairs)
        self.fallback_context = dict(fallback_context)
        return ['<div class="definition">cat</div>']

    def offline_term_identities(
        self,
        pairs: list[tuple[str, str]],
    ) -> dict[tuple[str, str], set[tuple[str, int, str]]]:
        """Drives the within-run orthographic alias collapse.

        Returning nothing keeps every candidate distinct, which is what this
        single-word fixture expects; the real service resolves JMdict
        identities so 肉じゃが and 肉ジャガ collapse to one card.
        """
        return {}

    def clear_run_cache(self) -> None:
        """Per-run attest-quality cache reset; the engine calls it in ``finally``."""
        self.run_cache_clears += 1

    def close(self) -> None:
        self.closed = True


class _AnkiService:
    def __init__(self) -> None:
        self.last_created_note_ids: list[int] = []
        self.last_created_mined_forms: list[str] = []
        self.last_media_store_failures = 0
        self.last_skipped_duplicates = 0
        self.verified = False
        self.card_data: list[object] = []
        self.image_bytes = b""
        self.cancelled_checks: list[object] = []

    def set_cancelled_check(self, cancelled: object) -> None:
        # _phase5 installs its probe before the batch and clears it in a
        # finally, so both calls land here.
        self.cancelled_checks.append(cancelled)

    def verify_card_target(self) -> None:
        self.verified = True

    def create_cards_batch(
        self,
        card_data: list[object],
        progress_callback: object | None = None,
    ) -> list[int]:
        assert progress_callback is None
        self.card_data = list(card_data)
        assert len(self.card_data) == 1
        screenshot_path = self.card_data[0].media.screenshot_path
        assert screenshot_path is not None
        self.image_bytes = screenshot_path.read_bytes()
        self.last_created_note_ids = [4242]
        # The processor now records known words from what the service confirms,
        # not from what it submitted.
        self.last_created_mined_forms = [payload.word.mined_form for payload in self.card_data]
        # The service contract returns the created ids; the processor takes
        # len() of this and stamps them onto the result.
        return [4242]


def test_actual_process_reading_mines_loaded_mokuro_document(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    # The common host lane deliberately omits runtime packages.  The pinned
    # CPython 3.12 runtime-host lane executes this complete orchestration smoke.
    pytest.importorskip("PIL")
    pytest.importorskip("lxml")
    pytest.importorskip("pysubs2")
    pytest.importorskip("requests")

    from anki_miner.models import TokenizedWord
    from anki_miner.orchestration.episode_processor import EpisodeProcessor
    from anki_miner.presenters import NullPresenter
    from anki_miner.services.word_filter import WordFilterService

    source, image_archive = _write_mokuro_fixture(tmp_path / "reading-job-v1-process")
    raw_request = _reading_request(
        cache_dir=tmp_path,
        source_kind="mokuro",
        source_path=source,
        image_archive_path=image_archive,
    )
    request = reading_mining._parse_request(raw_request)
    document = reading_mining._load_document(request)
    config = replace(
        reading_mining._map_config(request, initialized_bridge_home),
        # The reading image phase is now gated on the picture field being mapped
        # (episode_processor picture_mapped), so an empty mapping would mine the
        # mokuro volume imageless and the fixture asserts the page image.
        anki_fields={"picture": "Picture"},
        include_known_words=True,
        bypass_optional_filters=True,
        reading_min_occurrence=1,
        use_i_plus_one_filter=False,
    )
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

    assert {
        "totalWordsFound": result.total_words_found,
        "newWordsFound": result.new_words_found,
        "cardsCreated": result.cards_created,
        "errors": result.errors,
        "cardIds": result.card_ids,
        "minedForms": result.mined_forms,
        "videoFile": result.video_file,
        "subtitleFile": result.subtitle_file,
    } == {
        "totalWordsFound": 1,
        "newWordsFound": 1,
        "cardsCreated": 1,
        "errors": [],
        "cardIds": [4242],
        # mined_forms is now the known-words DB insert receipt, not "everything
        # mined": Undo may only revert rows this run actually inserted, so with
        # no DB wired here the list is legitimately empty.
        "minedForms": [],
        "videoFile": "",
        "subtitleFile": "",
    }
    assert anki.verified is True
    assert definitions.lookup_pairs == [("猫", "ねこ")]
    assert definitions.fallback_context == {"猫": ("猫", None)}
    assert definitions.closed is True
    assert len(anki.card_data) == 1
    card = anki.card_data[0]
    assert card.word.surface == "猫"
    assert card.word.mined_form == "猫"
    assert card.word.sentence == "猫を見る。"
    assert card.definition == '<div class="definition">cat</div>'
    assert card.extra_fields == {"source": "漫画作品 — 第一巻 @ p.1"}
    assert card.media.screenshot_filename is not None
    assert re.fullmatch(r"reading_[0-9a-f]{12}\.jpg", card.media.screenshot_filename)
    assert anki.image_bytes.startswith(b"\xff\xd8\xff")
    assert parser.received_units == document.units
