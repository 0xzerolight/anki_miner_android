from __future__ import annotations

import json
import threading
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

import android_bridge.reading_limits as reading_limits
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
