"""The adapter's own log lines and errors never carry media names (AU-005, AU-027).

``filesDir/anki_miner.log`` ships in the diagnostics bundle the UI calls redacted. A card media
name is ``safe_filename(mined_form)_<ms>_<seq>.<ext>`` (media_extractor), so it starts with the
mined word; a dictionary asset's source is the Yomitan basename. LogRedactor has no rule that
recognises either outside a path, so these tests read what the production file handler wrote.
"""

from __future__ import annotations

import errno
import logging
import logging.handlers
import re
import types
from pathlib import Path

import android_bridge.anki_adapter as anki_adapter_module
import pytest
from android_bridge import log_context
from android_bridge.protocol import BridgeProtocolError

# isolated_services_namespace is test_anki_adapter's module-scoped autouse fixture; importing it
# activates it here, because the host lane cannot import the eager desktop services package.
from test_anki_adapter import FakeKotlinAnki, _adapter, _card, _config, isolated_services_namespace  # noqa: F401

_ASSET_LINE = re.compile(r"Stored media asset \[asset_[0-9a-f]{32}\] purpose=(card|dictionary) kind=(audio|image)")


def _file_handler() -> logging.handlers.RotatingFileHandler:
    return next(
        handler for handler in logging.getLogger().handlers if isinstance(handler, logging.handlers.RotatingFileHandler)
    )


def _log_offset() -> int:
    handler = _file_handler()
    handler.flush()
    path = Path(handler.baseFilename)
    return path.stat().st_size if path.exists() else 0


def _log_since(offset: int) -> str:
    handler = _file_handler()
    handler.flush()
    with Path(handler.baseFilename).open("rb") as stream:
        stream.seek(offset)
        return stream.read().decode("utf-8")


def test_stored_card_media_lines_name_only_id_purpose_and_kind(
    initialized_bridge_home: Path,
    tmp_path: Path,
) -> None:
    from anki_miner.models import MediaData

    # The judges' repro: a Hangul and a Latin mined form, named the way media_extractor names them.
    audio = tmp_path / "사랑_83120_0.opus"
    audio.write_bytes(b"hangul audio")
    picture = tmp_path / "amor_91000_1.jpg"
    picture.write_bytes(b"\xff\xd8\xff\xe0latin picture")
    adapter = _adapter(_config(initialized_bridge_home), FakeKotlinAnki())
    offset = _log_offset()

    created = adapter.create_cards_batch(
        [
            _card("사랑", media=MediaData(audio_path=audio, audio_filename=audio.name)),
            _card("amor", media=MediaData(screenshot_path=picture, screenshot_filename=picture.name)),
        ]
    )
    adapter.close()

    assert len(created) == 2
    written = _log_since(offset)
    assert "사랑" not in written
    assert "amor" not in written
    stored = [line for line in written.splitlines() if "Stored media asset" in line]
    assert len(stored) == 2
    assert {match.group(1, 2) for line in stored if (match := _ASSET_LINE.search(line))} == {
        ("card", "audio"),
        ("card", "image"),
    }


def test_unreadable_card_media_warning_omits_the_mined_form(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from anki_miner.models import MediaData

    audio = tmp_path / "любовь_1000_0.opus"
    audio.write_bytes(b"cyrillic audio")
    adapter = _adapter(_config(initialized_bridge_home), FakeKotlinAnki())

    def unreadable(source_path: Path, _budget: object) -> object:
        raise PermissionError(errno.EACCES, "Permission denied", str(source_path))

    monkeypatch.setattr(adapter, "_stream_media_digest", unreadable)
    offset = _log_offset()

    adapter.create_cards_batch([_card("любовь", media=MediaData(audio_path=audio, audio_filename=audio.name))])
    adapter.close()

    written = _log_since(offset)
    assert f"Failed to read media file kind=audio error=PermissionError errno={errno.EACCES}" in written
    assert "любовь" not in written


@pytest.mark.parametrize("shape", ["audio-and-image", "unverifiable", "different-bytes"])
def test_media_collision_errors_omit_the_mined_form(
    initialized_bridge_home: Path,
    tmp_path: Path,
    shape: str,
) -> None:
    # mining.py forwards str(error) to Kotlin and logs it with its stack at default verbosity.
    from anki_miner.models import MediaData

    name = "andiamo_1000_0.opus"
    first = tmp_path / "first.opus"
    first.write_bytes(b"first")
    second = tmp_path / "second.opus"
    if shape == "audio-and-image":
        media = [MediaData(audio_path=first, audio_filename=name, screenshot_path=first, screenshot_filename=name)]
    elif shape == "unverifiable":
        media = [MediaData(audio_path=first, audio_filename=name), MediaData(audio_path=second, audio_filename=name)]
    else:
        second.write_bytes(b"second")
        media = [MediaData(audio_path=first, audio_filename=name), MediaData(audio_path=second, audio_filename=name)]
    offset = _log_offset()

    with pytest.raises(BridgeProtocolError) as error:
        _adapter(_config(initialized_bridge_home), FakeKotlinAnki()).create_cards_batch(
            [_card(f"word{index}", media=item) for index, item in enumerate(media)]
        )

    assert error.value.code == "media_content_collision"
    assert "andiamo" not in str(error.value)
    assert "andiamo" not in _log_since(offset)


def test_stored_dictionary_media_line_omits_the_source_basename(
    initialized_bridge_home: Path,
) -> None:
    source = "dict__portrait_of_andiamo.jpg"
    media_path = initialized_bridge_home / "dicts" / "dict" / "media" / "portrait_of_andiamo.jpg"
    media_path.parent.mkdir(parents=True, exist_ok=True)
    media_path.write_bytes(b"\xff\xd8\xff\xe0dictionary picture")
    definition = f'<img class="anki-miner-dict-media" src="{source}">'
    adapter = _adapter(_config(initialized_bridge_home), FakeKotlinAnki())
    offset = _log_offset()

    assert len(adapter.create_cards_batch([_card("猫", definition=definition)])) == 1
    adapter.close()

    written = _log_since(offset)
    assert "portrait_of_andiamo" not in written
    # The provider name is an unsalted sha256 of that public basename, so it is withheld too.
    assert "anki_miner_dict_" not in written
    stored = [line for line in written.splitlines() if "Stored media asset" in line]
    assert len(stored) == 1
    assert _ASSET_LINE.search(stored[0]).group(1, 2) == ("dictionary", "image")


def test_disappeared_and_unreadable_dictionary_media_lines_omit_the_source(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # The read-failure detail line is DEBUG; the conftest autouse fixture puts the level back.
    log_context.set_first_party_log_level(logging.DEBUG)
    gone = "dict__vanished_andiamo.png"
    unreadable = "dict__locked_andiamo.png"
    media_root = initialized_bridge_home / "dicts" / "dict" / "media"
    media_root.mkdir(parents=True, exist_ok=True)
    (media_root / "locked_andiamo.png").write_bytes(b"png")
    adapter = _adapter(_config(initialized_bridge_home), FakeKotlinAnki())
    plan = types.SimpleNamespace(
        dictionary_media_sources=(gone, unreadable),
        dictionary_media_paths={
            gone: (media_root / "vanished_andiamo.png").resolve(),
            unreadable: (media_root / "locked_andiamo.png").resolve(),
        },
    )

    def locked(source_path: Path, _budget: object) -> object:
        raise PermissionError(errno.EACCES, "Permission denied", str(source_path))

    monkeypatch.setattr(adapter, "_stream_media_digest", locked)
    offset = _log_offset()

    prepared = adapter._prepare_dictionary_media(plan, object())

    assert prepared.assets == ()
    assert anki_adapter_module.logger.isEnabledFor(logging.DEBUG)
    written = _log_since(offset)
    assert "Dict media file disappeared from disk" in written
    assert "Dictionary media read failed outcome=ignored error=PermissionError" in written
    assert "andiamo" not in written
