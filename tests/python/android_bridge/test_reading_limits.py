from __future__ import annotations

import importlib.util
import json
import sys
import threading
import zipfile
from pathlib import Path
from types import ModuleType, SimpleNamespace

import android_bridge.reading_limits as reading_limits
import android_bridge.reading_mining as reading_mining
import pytest
from android_bridge.anki_adapter import AnkiOperationCancelled
from android_bridge.protocol import BridgeProtocolError
from android_bridge.reading_limits import ZipArchiveLimits


def _zip(path: Path, members: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in members.items():
            archive.writestr(name, payload)
    return path


def _limits(**changes: int) -> ZipArchiveLimits:
    values = {
        "label": "test archive",
        "max_members": 20,
        "max_central_directory_bytes": 64 * 1024,
        "max_member_uncompressed_bytes": 1024,
        "max_total_uncompressed_bytes": 4096,
        "max_text_member_uncompressed_bytes": 1024,
        "max_total_text_uncompressed_bytes": 4096,
    }
    values.update(changes)
    return ZipArchiveLimits(**values)


def _stub_pillow(monkeypatch: pytest.MonkeyPatch) -> None:
    class DecompressionBombWarning(Warning):
        pass

    class DecompressionBombError(Exception):
        pass

    class UnidentifiedImageError(OSError):
        pass

    image = SimpleNamespace(
        DecompressionBombWarning=DecompressionBombWarning,
        DecompressionBombError=DecompressionBombError,
        open=lambda _source: pytest.fail("Image.open must not run after archive.open fails"),
    )
    pillow = ModuleType("PIL")
    pillow.Image = image
    pillow.UnidentifiedImageError = UnidentifiedImageError
    monkeypatch.setitem(sys.modules, "PIL", pillow)


def test_normal_epub_archive_passes_mobile_expansion_preflight(tmp_path: Path) -> None:
    epub = _zip(
        tmp_path / "normal.epub",
        {
            "mimetype": b"application/epub+zip",
            "META-INF/container.xml": b"<container/>",
            "OEBPS/content.opf": b"<package/>",
            "OEBPS/chapter.xhtml": "<p>猫を見た。</p>".encode(),
            "OEBPS/cover.jpg": b"\xff\xd8\xffsmall",
        },
    )

    reading_limits.validate_zip_archive(
        epub,
        reading_limits.EPUB_ARCHIVE_LIMITS,
    )


def test_epub_cumulative_uncompressed_bomb_is_rejected(tmp_path: Path) -> None:
    epub = _zip(
        tmp_path / "cumulative.epub",
        {
            "one.xhtml": b"a" * 80,
            "two.xhtml": b"b" * 80,
        },
    )

    with pytest.raises(BridgeProtocolError) as error:
        reading_limits.validate_zip_archive(
            epub,
            _limits(
                max_total_uncompressed_bytes=120,
                max_total_text_uncompressed_bytes=120,
            ),
        )

    assert error.value.code == "reading_source_too_large"
    assert "cumulative" in str(error.value)


def test_epub_member_count_and_per_member_limits_are_independent(
    tmp_path: Path,
) -> None:
    too_many = _zip(
        tmp_path / "members.epub",
        {f"chapter-{index}.xhtml": b"x" for index in range(3)},
    )
    with pytest.raises(BridgeProtocolError) as member_count:
        reading_limits.validate_zip_archive(
            too_many,
            _limits(max_members=2),
        )
    assert member_count.value.code == "reading_source_too_large"
    assert "members" in str(member_count.value)

    oversized = _zip(tmp_path / "member.epub", {"chapter.xhtml": b"x" * 101})
    with pytest.raises(BridgeProtocolError) as member_size:
        reading_limits.validate_zip_archive(
            oversized,
            _limits(
                max_member_uncompressed_bytes=100,
                max_text_member_uncompressed_bytes=100,
            ),
        )
    assert member_size.value.code == "reading_source_too_large"
    assert "member" in str(member_size.value)


def test_high_ratio_member_is_rejected_before_any_decompression(tmp_path: Path) -> None:
    archive = _zip(tmp_path / "ratio.epub", {"chapter.xhtml": b"0" * 100_000})

    with pytest.raises(BridgeProtocolError) as error:
        reading_limits.validate_zip_archive(
            archive,
            _limits(
                max_member_uncompressed_bytes=200_000,
                max_total_uncompressed_bytes=200_000,
                max_text_member_uncompressed_bytes=200_000,
                max_total_text_uncompressed_bytes=200_000,
            ),
        )

    assert error.value.code == "reading_source_too_large"
    assert "compressed" in str(error.value)


def test_archive_path_and_duplicate_member_ambiguity_fail_stably(
    tmp_path: Path,
) -> None:
    traversal = _zip(tmp_path / "traversal.cbz", {"../page.jpg": b"image"})
    with pytest.raises(BridgeProtocolError) as traversal_error:
        reading_limits.validate_zip_archive(
            traversal,
            reading_limits.MOKURO_ARCHIVE_LIMITS,
        )
    assert traversal_error.value.code == "invalid_reading_source_archive"

    duplicate = tmp_path / "duplicate.cbz"
    with zipfile.ZipFile(duplicate, "w") as archive:
        archive.writestr("page.jpg", b"first")
        with pytest.warns(UserWarning):
            archive.writestr("page.jpg", b"second")
    with pytest.raises(BridgeProtocolError) as duplicate_error:
        reading_limits.validate_zip_archive(
            duplicate,
            reading_limits.MOKURO_ARCHIVE_LIMITS,
        )
    assert duplicate_error.value.code == "invalid_reading_source_archive"


def test_mokuro_json_fanout_and_txt_bytes_are_bounded_before_detector(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    sidecar = tmp_path / "volume.mokuro"
    sidecar.write_text(
        json.dumps({"pages": [{"blocks": []}, {"blocks": []}]}),
        encoding="utf-8",
    )
    monkeypatch.setattr(reading_limits, "MAX_MOKURO_PAGES", 1)

    with pytest.raises(BridgeProtocolError) as pages:
        reading_limits.validate_source_before_load(
            source_kind="mokuro",
            source_path=sidecar,
            image_archive_path=None,
        )
    assert pages.value.code == "reading_source_too_large"
    assert "pages" in str(pages.value)

    text = tmp_path / "novel.txt"
    text.write_bytes(b"x" * 11)
    monkeypatch.setattr(reading_limits, "MAX_TXT_SOURCE_BYTES", 10)
    with pytest.raises(BridgeProtocolError) as text_size:
        reading_limits.validate_source_before_load(
            source_kind="txt",
            source_path=text,
            image_archive_path=None,
        )
    assert text_size.value.code == "reading_source_too_large"
    assert "choose a smaller source" in str(text_size.value)


def test_mokuro_page_cap_precedes_json_graph_materialization(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    sidecar = tmp_path / "preflight-first.mokuro"
    sidecar.write_text('{"pages":[{},{}]}', encoding="utf-8")
    monkeypatch.setattr(reading_limits, "MAX_MOKURO_PAGES", 1)

    def fail_materialization(*_args: object, **_kwargs: object) -> object:
        raise AssertionError("JSON graph was materialized before the page cap")

    monkeypatch.setattr(json, "loads", fail_materialization)

    with pytest.raises(BridgeProtocolError) as error:
        reading_limits.validate_source_before_load(
            source_kind="mokuro",
            source_path=sidecar,
            image_archive_path=None,
        )

    assert error.value.code == "reading_source_too_large"
    assert "pages" in str(error.value)


def test_mokuro_preflight_skips_the_same_malformed_nested_records_as_engine(
    tmp_path: Path,
) -> None:
    sidecar = tmp_path / "mixed-records.mokuro"
    sidecar.write_text(
        json.dumps(
            {
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
                ]
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    reading_limits.validate_source_before_load(
        source_kind="mokuro",
        source_path=sidecar,
        image_archive_path=None,
    )


def test_loaded_document_unit_and_cumulative_text_limits_are_exact(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    units = [
        SimpleNamespace(text="猫", location_label="p.1", image_ref=None),
        SimpleNamespace(text="犬", location_label="p.2", image_ref=None),
    ]
    document = SimpleNamespace(units=units)
    monkeypatch.setattr(reading_limits, "MAX_DOCUMENT_UNITS", 1)
    with pytest.raises(BridgeProtocolError) as unit_error:
        reading_limits.validate_loaded_document(
            document,
            source_kind="txt",
            source_path=tmp_path / "book.txt",
            image_archive_path=None,
        )
    assert unit_error.value.code == "reading_source_too_large"

    monkeypatch.setattr(reading_limits, "MAX_DOCUMENT_UNITS", 10)
    monkeypatch.setattr(reading_limits, "MAX_DOCUMENT_TEXT_UTF8_BYTES", 5)
    with pytest.raises(BridgeProtocolError) as text_error:
        reading_limits.validate_loaded_document(
            document,
            source_kind="txt",
            source_path=tmp_path / "book.txt",
            image_archive_path=None,
        )
    assert text_error.value.code == "reading_source_too_large"
    assert "retained-text" in str(text_error.value)


def test_reading_unit_limit_stops_loader_before_the_excess_unit_is_retained(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    source = tmp_path / "book.txt"
    source.write_text("猫。犬。", encoding="utf-8")

    class CountingDetector:
        def __init__(self) -> None:
            self.created: list[object] = []
            self.ref = SimpleNamespace(kind="txt", image_root=None)

        def detect(self, _path: Path) -> list[object]:
            return [self.ref]

        def load(
            self,
            _ref: object,
            *,
            strip_subtitle_annotations: bool,
        ) -> object:
            assert strip_subtitle_annotations is True
            from anki_miner.models.reading import ReadingDocument, ReadingUnit

            splitter_path = (
                Path(__file__).resolve().parents[3]
                / "app/src/main/python/anki_miner/services/reading/sentence_splitter.py"
            )
            spec = importlib.util.spec_from_file_location("bounded_sentence_splitter", splitter_path)
            assert spec is not None and spec.loader is not None
            splitter = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(splitter)

            document = ReadingDocument(
                title="book",
                kind="book",
                series="book",
                episode="book",
            )
            for index, text in enumerate(splitter.split_sentences("猫。犬。")):
                unit = ReadingUnit(
                    text=text,
                    index=index,
                    location_label="¶1",
                )
                self.created.append(unit)
                document.units.append(unit)
            return document

    detector = CountingDetector()
    monkeypatch.setattr(reading_mining, "_reading_detector", lambda: detector)
    monkeypatch.setattr(reading_limits, "MAX_DOCUMENT_UNITS", 1)
    request = reading_mining._ReadingRequest(
        source_kind="txt",
        source_path=source,
        image_archive_path=None,
        series_name=None,
        cache_dir=tmp_path,
        native_library_dir=tmp_path,
        settings={},
        android_tts_enabled=False,
    )

    with pytest.raises(BridgeProtocolError) as error:
        reading_mining._load_document(request)

    assert error.value.code == "reading_source_too_large"
    assert detector.created == []


def test_image_preflight_skips_unsupported_members_like_engine(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    _stub_pillow(monkeypatch)
    archive_path = tmp_path / "unsupported.cbz"
    archive_path.write_bytes(b"placeholder")
    image_ref = SimpleNamespace(source=archive_path, entry="page.png")
    document = SimpleNamespace(
        units=[
            SimpleNamespace(
                text="猫。",
                location_label="p.1",
                image_ref=image_ref,
            )
        ]
    )

    class UnsupportedArchive:
        def __enter__(self) -> UnsupportedArchive:
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def open(self, _entry: str) -> object:
            raise NotImplementedError("unsupported ZIP compression")

    monkeypatch.setattr(
        reading_limits.zipfile,
        "ZipFile",
        lambda _path: UnsupportedArchive(),
    )

    reading_limits.validate_loaded_document(
        document,
        source_kind="mokuro",
        source_path=tmp_path / "volume.mokuro",
        image_archive_path=archive_path,
    )


def test_image_preflight_never_swallows_memory_error(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    _stub_pillow(monkeypatch)
    archive_path = tmp_path / "memory.cbz"
    archive_path.write_bytes(b"placeholder")
    image_ref = SimpleNamespace(source=archive_path, entry="page.png")
    document = SimpleNamespace(
        units=[
            SimpleNamespace(
                text="猫。",
                location_label="p.1",
                image_ref=image_ref,
            )
        ]
    )

    class ExhaustedArchive:
        def __enter__(self) -> ExhaustedArchive:
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def open(self, _entry: str) -> object:
            raise MemoryError("allocation failed")

    monkeypatch.setattr(
        reading_limits.zipfile,
        "ZipFile",
        lambda _path: ExhaustedArchive(),
    )

    with pytest.raises(MemoryError, match="allocation failed"):
        reading_limits.validate_loaded_document(
            document,
            source_kind="mokuro",
            source_path=tmp_path / "volume.mokuro",
            image_archive_path=archive_path,
        )


def test_cancellation_interrupts_archive_preflight(tmp_path: Path) -> None:
    archive = _zip(
        tmp_path / "cancel.epub",
        {f"chapter-{index}.xhtml": b"text" for index in range(100)},
    )
    checks = 0

    def cancelled() -> bool:
        nonlocal checks
        checks += 1
        return checks >= 3

    with pytest.raises(AnkiOperationCancelled):
        reading_limits.validate_zip_archive(
            archive,
            _limits(
                max_members=200,
                max_central_directory_bytes=256 * 1024,
            ),
            cancellation_check=cancelled,
        )


def test_already_cancelled_source_preflight_opens_no_file(tmp_path: Path) -> None:
    source = tmp_path / "missing.txt"
    cancelled = threading.Event()
    cancelled.set()

    with pytest.raises(AnkiOperationCancelled):
        reading_limits.validate_source_before_load(
            source_kind="txt",
            source_path=source,
            image_archive_path=None,
            cancellation_check=cancelled.is_set,
        )
