"""Card media named after a format-character mined form is stored under a Cf-free name (AU-002).

MediaExtractor names clips ``f"{safe_filename(word.mined_form)}_{ms}_{seq}.<ext>"`` and the Persian
normaliser keeps ZWNJ, so a word such as دانش‌آموز yields a clip name with U+200C. That logical name
never crosses to Kotlin: the stored name drops the format characters and the note references the
name AnkiDroid returns. Every other category-C code point stays refused.
"""

from __future__ import annotations

import hashlib
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from android_bridge.anki_adapter import _content_addressed_name_from_digest
from android_bridge.protocol import BridgeProtocolError
from test_anki_adapter import (
    FakeKotlinAnki,
    _adapter,
    _card,
    _config,
    isolated_services_namespace,  # noqa: F401  (autouse: host lane lacks desktop services)
)

ZWNJ = "‌"
MINED_FORM = f"دانش{ZWNJ}آموز"
STORED_STEM = "دانشآموز"


def _persian_card(media: Any) -> Any:
    from anki_miner.models import CardPayload, TokenizedWord

    word = TokenizedWord(
        surface=MINED_FORM,
        lemma=MINED_FORM,
        reading="",
        sentence=f"{MINED_FORM} است",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
    )
    return CardPayload(word=word, media=media, definition="student")


def _engine_clip(directory: Path, extension: str, content: bytes) -> Path:
    from anki_miner.utils.file_utils import safe_filename

    path = directory / f"{safe_filename(MINED_FORM)}_1000_0.{extension}"
    path.write_bytes(content)
    return path


@pytest.mark.parametrize("language", ["ja", "fa"])
def test_zwnj_card_media_is_stored_under_a_format_free_name(
    language: str, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    if language == "fa":
        pytest.importorskip("pysubs2", reason="runtime dependency lane: build_note resolves the fa profile")
    from anki_miner.models import MediaData

    audio = _engine_clip(tmp_path, "mp3", b"persian audio")
    picture = _engine_clip(tmp_path, "jpg", b"persian picture")
    assert ZWNJ in audio.name and ZWNJ in picture.name, "safe_filename keeps U+200C"
    media = MediaData(
        audio_path=audio,
        audio_filename=audio.name,
        screenshot_path=picture,
        screenshot_filename=picture.name,
    )
    config = replace(_config(initialized_bridge_home), language=language)
    audio_field = config.anki_fields["audio"]
    picture_field = config.anki_fields["picture"]
    assert audio_field and picture_field, "the default config maps sentence audio and picture"
    kotlin = FakeKotlinAnki()

    created = _adapter(config, kotlin).create_cards_batch([_persian_card(media)])

    assert len(created) == 1
    assets = kotlin.requests_for("ankiStoreMedia")[0]["payload"]["assets"]
    audio_sha1 = hashlib.sha1(b"persian audio").hexdigest()[:12]
    picture_sha1 = hashlib.sha1(b"persian picture").hexdigest()[:12]
    assert sorted(asset["requestedFilename"] for asset in assets) == sorted(
        [f"{STORED_STEM}_1000_0_{audio_sha1}.mp3", f"{STORED_STEM}_1000_0_{picture_sha1}.jpg"]
    )
    assert all(ZWNJ not in asset["preferredName"] for asset in assets)
    # The note references the provider's stored names; the word itself keeps its ZWNJ.
    assert ZWNJ not in media.audio_filename and ZWNJ not in media.screenshot_filename
    fields = kotlin.requests_for("ankiCreateNotes")[0]["payload"]["notes"][0]["fields"]
    assert fields[audio_field] == f"[sound:{media.audio_filename}]"
    assert fields[picture_field] == f'<img src="{media.screenshot_filename}">'
    assert MINED_FORM in fields[config.anki_fields["word"]]


def test_content_addressed_name_drops_every_format_character() -> None:
    assert _content_addressed_name_from_digest(f"a{ZWNJ}b‮﻿_1_0.mp3", "0" * 12) == "ab_1_0_000000000000.mp3"
    assert _content_addressed_name_from_digest("plain_1_0.mp3", "0" * 12) == "plain_1_0_000000000000.mp3"


@pytest.mark.parametrize(
    ("filename", "message"),
    [
        ("clip_1000_0.mp3", "is not a media basename"),  # private use (Co)
        ("clip\u0085_1000_0.mp3", "is not a media basename"),  # C1 control (Cc)
        ("clip͸_1000_0.mp3", "is not a media basename"),  # unassigned (Cn)
        # Dropping the ZWNJ lets alef and maddah compose: not NFC, refused like any non-NFC name.
        ("ا‌ٓ_1000_0.mp3", "is not a safe provider filename"),
    ],
    ids=["private-use", "c1-control", "unassigned", "non-nfc-after-strip"],
)
def test_other_category_c_card_media_names_still_fail_the_whole_call(
    filename: str, message: str, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "clip.mp3"
    audio.write_bytes(b"audio")
    kotlin = FakeKotlinAnki()

    with pytest.raises(BridgeProtocolError) as error:
        _adapter(_config(initialized_bridge_home), kotlin).create_cards_batch(
            [_card("猫"), _card("犬", media=MediaData(audio_path=audio, audio_filename=filename))]
        )

    assert error.value.code == "invalid_note"
    assert message in str(error.value)
    assert kotlin.requests_for("ankiStoreMedia") == []
    assert kotlin.requests_for("ankiCreateNotes") == []
