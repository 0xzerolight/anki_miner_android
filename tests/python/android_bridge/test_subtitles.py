from __future__ import annotations

import json
import sys
from pathlib import Path
from types import ModuleType

import pytest
from android_bridge import boundary
from android_bridge.protocol import encode_message

SRT = "1\n00:00:01,000 --> 00:00:02,500\nこんにちは\n\n2\n00:00:04,000 --> 00:00:05,000\n<i>さようなら</i>\n"


def dispatch_json(message: dict[str, object]) -> dict[str, object]:
    return json.loads(boundary.dispatch(encode_message(message["type"], message["payload"])))


def _install_subtitle_parser(
    monkeypatch: pytest.MonkeyPatch,
    parser_type: type[object],
) -> None:
    import anki_miner

    # Common host tests intentionally omit runtime pip packages. Match the
    # tokenizer-selection suites: load only the engine seam this bridge calls.
    services = ModuleType("anki_miner.services")
    services.__path__ = [str(Path(anki_miner.__file__).parent / "services")]
    parser_module = ModuleType("anki_miner.services.subtitle_parser")
    parser_module.SubtitleParserService = parser_type
    monkeypatch.setitem(sys.modules, "anki_miner.services", services)
    monkeypatch.setitem(sys.modules, "anki_miner.services.subtitle_parser", parser_module)


@pytest.fixture
def configured_tokenizer_bridge(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> Path:
    class _Parser:
        def __init__(self, config: object) -> None:
            self.config = config

        def parse_raw_entries(self, path: Path) -> list[tuple[float, float, str]]:
            assert path.read_text(encoding="utf-8") == SRT
            offset = self.config.subtitle_offset
            return [
                (max(0.0, 1.0 + offset), max(0.0, 2.5 + offset), "こんにちは"),
                (max(0.0, 4.0 + offset), max(0.0, 5.0 + offset), "さようなら"),
            ]

    _install_subtitle_parser(monkeypatch, _Parser)
    return initialized_bridge_home


def test_cues_without_run_parse_offset_zero(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
) -> None:
    del configured_tokenizer_bridge
    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )
    assert result["type"] == "subtitle.cues.result"
    cues = result["payload"]["cues"]
    assert cues[0] == {"start": 1.0, "end": 2.5, "text": "こんにちは"}
    assert len(cues) == 2


def test_cues_with_registered_run_uses_run_offset(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
) -> None:
    del configured_tokenizer_bridge
    from dataclasses import replace

    from android_bridge import definitions
    from anki_miner.config.config import AnkiMinerConfig

    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    run_id = "run_" + "a" * 32
    definitions.register_run_dictionaries(
        run_id,
        replace(AnkiMinerConfig(), subtitle_offset=2.0),
    )
    try:
        result = dispatch_json(
            {
                "type": "subtitle.cues",
                "payload": {"runId": run_id, "subtitlePath": str(sub)},
            }
        )
        assert result["payload"]["cues"][0]["start"] == 3.0
    finally:
        definitions.clear_run_dictionaries(run_id)


def test_cues_unknown_run_rejected(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
) -> None:
    del configured_tokenizer_bridge
    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {
                "runId": "run_" + "0" * 32,
                "subtitlePath": str(sub),
            },
        }
    )
    assert result["payload"]["code"] == "subtitle_cues_run_unknown"


def test_cues_payload_field_check(configured_tokenizer_bridge: Path) -> None:
    del configured_tokenizer_bridge
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"subtitlePath": "/x.srt"},
        }
    )
    assert result["payload"]["code"] == "invalid_subtitle_cues_request"


def test_cues_relative_path_rejected(configured_tokenizer_bridge: Path) -> None:
    del configured_tokenizer_bridge
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": "ep.srt"},
        }
    )
    assert result["payload"]["code"] == "invalid_subtitle_cues_request"


def test_cues_bad_suffix_rejected(configured_tokenizer_bridge: Path) -> None:
    del configured_tokenizer_bridge
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": "/tmp/ep.txt"},
        }
    )
    assert result["payload"]["code"] == "invalid_subtitle_cues_request"


def test_cues_missing_file_parse_failed(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
) -> None:
    del configured_tokenizer_bridge
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {
                "runId": None,
                "subtitlePath": str(tmp_path / "absent.srt"),
            },
        }
    )
    assert result["payload"]["code"] == "subtitle_cues_parse_failed"


def test_cues_oversized_file_too_large(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del configured_tokenizer_bridge
    from android_bridge import subtitles

    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    monkeypatch.setattr(subtitles, "MAX_SUBTITLE_BYTES", 1)
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )
    assert result["payload"]["code"] == "subtitle_cues_too_large"


def test_cues_oversized_text_too_large(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del configured_tokenizer_bridge
    from android_bridge import subtitles

    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    monkeypatch.setattr(subtitles, "MAX_RESULT_UTF8_BYTES", 1)
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )
    assert result["payload"]["code"] == "subtitle_cues_too_large"


def test_cues_unconfigured_tokenizer(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home

    class _UnconfiguredParser:
        def __init__(self, config: object) -> None:
            del config
            raise RuntimeError("Android tokenizer backend has not been configured")

    _install_subtitle_parser(monkeypatch, _UnconfiguredParser)
    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )
    assert result["payload"]["code"] == "subtitle_cues_tokenizer_unconfigured"


def test_cues_parse_runtime_error_is_parse_failed(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del configured_tokenizer_bridge
    from android_bridge import subtitles

    class _Parser:
        def parse_raw_entries(self, path: Path) -> list[tuple[float, float, str]]:
            del path
            raise RuntimeError("parse failed after construction")

    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    monkeypatch.setattr(subtitles, "_build_parser", lambda config: _Parser())
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )
    assert result["payload"]["code"] == "subtitle_cues_parse_failed"


def test_cues_engine_default_offset_is_zero() -> None:
    from anki_miner.config.config import AnkiMinerConfig

    assert AnkiMinerConfig().subtitle_offset == 0.0
