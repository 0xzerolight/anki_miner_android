"""A non-Japanese word list is rewritten as UTF-8 through its language's ladder.

The engine reads a blacklist or whitelist with the mining language's
``import_encodings`` ladder (``mining._word_list_seams``); Kotlin installs only
UTF-8. ``resource.wordlist.transcode`` closes that gap by decoding the staged
copy with the engine's own ``decode_with_ladder``. Japanese never comes here:
its runs read UTF-8 alone and Kotlin keeps a strict gate for it.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from android_bridge import boundary, word_lists
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message

# Over the engine's prefers_big5 threshold: a Big5 list under roughly ten words
# still decodes as gb18030 (see decode_with_ladder's docstring).
_BIG5_WORDS = "貓\n狗\n書\n電腦\n學習\n語言\n中文\n朋友\n老師\n學生\n電話\n"


def _runtime_lane() -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane")


@pytest.fixture(autouse=True)
def _bootstrap(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home  # boundary.dispatch and the engine need the bootstrap


def _request(source: Path, language: str) -> dict[str, object]:
    return {"sourcePath": str(source), "language": language}


@pytest.mark.parametrize(
    ("language", "encoding", "text"),
    [
        ("zh", "gb18030", "猫\n# 注释\n狗\n"),
        ("zh", "big5", _BIG5_WORDS),
        ("ru", "cp1251", "кошка\n# комментарий\nсобака\n"),
        ("ko", "cp949", "고양이\n개\n"),
        # Excel's "Unicode Text": the BOM overrides the ladder.
        ("zh", "utf-16", "猫\n狗\n"),
        ("ru", "utf-8-sig", "кошка\n"),
    ],
)
def test_a_list_is_rewritten_as_the_bom_less_utf8_its_run_would_read(
    tmp_path: Path,
    language: str,
    encoding: str,
    text: str,
) -> None:
    _runtime_lane()
    from android_bridge.languages import get_profile
    from android_bridge.mining import _word_list_seams
    from anki_miner.services.word_list_service import WordListService

    original = tmp_path / "original.txt"
    original.write_bytes(text.encode(encoding))
    staged = tmp_path / "staged.txt"
    staged.write_bytes(original.read_bytes())

    raw = word_lists.transcode_word_list(_request(staged, language))

    assert decode_envelope(raw, expected_type="resource.wordlist.transcoded").payload == {}
    assert staged.read_bytes() == text.encode("utf-8")
    # The installed file loads exactly what the run would have loaded from the
    # file the user picked, through the seam mining hands WordListService.
    seams = _word_list_seams(get_profile(language))
    before = WordListService(whitelist_path=original, **seams)
    before.load()
    after = WordListService(whitelist_path=staged, **seams)
    after.load()
    assert after.whitelist_entries() == before.whitelist_entries()


@pytest.mark.parametrize("language", ["zh", "ko"])
def test_a_list_no_ladder_leg_decodes_is_refused_and_left_alone(tmp_path: Path, language: str) -> None:
    _runtime_lane()
    staged = tmp_path / "staged.txt"
    garbage = b"\x80\xff\x80\xff\n"
    staged.write_bytes(garbage)

    with pytest.raises(BridgeProtocolError) as refused:
        word_lists.transcode_word_list(_request(staged, language))

    assert refused.value.code == "word_list_not_utf8"
    assert staged.read_bytes() == garbage


def test_a_japanese_list_is_never_transcoded(tmp_path: Path) -> None:
    """ja runs read UTF-8 alone, not the ja profile's cp932/euc_jp ladder."""

    staged = tmp_path / "staged.txt"
    shift_jis = "猫\n".encode("shift_jis")
    staged.write_bytes(shift_jis)

    with pytest.raises(BridgeProtocolError) as refused:
        word_lists.transcode_word_list(_request(staged, "ja"))

    assert refused.value.code == "invalid_resource_request"
    assert staged.read_bytes() == shift_jis


def test_unknown_payload_fields_are_refused(tmp_path: Path) -> None:
    with pytest.raises(BridgeProtocolError) as refused:
        word_lists.transcode_word_list({**_request(tmp_path / "staged.txt", "zh"), "operationId": "x"})

    assert refused.value.code == "invalid_resource_request"


def test_the_boundary_routes_the_transcode_and_its_refusal(tmp_path: Path) -> None:
    staged = tmp_path / "staged.txt"
    staged.write_bytes("猫\n".encode("shift_jis"))

    raw = boundary.dispatch(encode_message("resource.wordlist.transcode", _request(staged, "ja")))

    assert decode_envelope(raw, expected_type="bridge.error").payload["code"] == "invalid_resource_request"


def test_the_boundary_returns_the_transcoded_envelope_and_the_not_utf8_code(tmp_path: Path) -> None:
    _runtime_lane()
    good = tmp_path / "good.txt"
    good.write_bytes("кошка\n".encode("cp1251"))
    bad = tmp_path / "bad.txt"
    bad.write_bytes(b"\x80\xff\x80\xff\n")

    transcoded = boundary.dispatch(encode_message("resource.wordlist.transcode", _request(good, "ru")))
    refused = boundary.dispatch(encode_message("resource.wordlist.transcode", _request(bad, "zh")))

    assert decode_envelope(transcoded, expected_type="resource.wordlist.transcoded").payload == {}
    assert good.read_text(encoding="utf-8") == "кошка\n"
    assert decode_envelope(refused, expected_type="bridge.error").payload["code"] == "word_list_not_utf8"
