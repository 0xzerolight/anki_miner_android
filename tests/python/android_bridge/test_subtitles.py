from __future__ import annotations

import json
import re
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

    try:
        from pysubs2.formats import autodetect_format as _autodetect_format  # noqa: F401
    except ModuleNotFoundError:
        pysubs2 = ModuleType("pysubs2")
        formats = ModuleType("pysubs2.formats")

        def autodetect_format(fragment: str) -> str:
            if "http://www.w3.org/ns/ttml" in fragment:
                return "ttml"
            if re.search(r"V4\+ Styles", fragment, re.IGNORECASE):
                return "ass"
            if re.search(r"V4 Styles", fragment, re.IGNORECASE):
                return "ssa"
            if fragment.lstrip().startswith("WEBVTT"):
                return "vtt"
            timestamp = re.compile(r"(\d{1,2}):(\d{1,2}):(\d{1,2})[.,](\d{1,3})")
            if any(len(timestamp.findall(line)) == 2 for line in fragment.splitlines()):
                return "srt"
            raise ValueError("unknown subtitle format")

        formats.__dict__["autodetect_format"] = autodetect_format
        pysubs2.__dict__["formats"] = formats
        monkeypatch.setitem(sys.modules, "pysubs2", pysubs2)
        monkeypatch.setitem(sys.modules, "pysubs2.formats", formats)

    # Common host tests intentionally omit runtime pip packages. Match the
    # tokenizer-selection suites: load only the engine seam this bridge calls.
    services = ModuleType("anki_miner.services")
    services.__path__ = [str(Path(anki_miner.__file__).parent / "services")]
    parser_module = ModuleType("anki_miner.services.subtitle_parser")
    parser_module.SubtitleParserService = parser_type
    parser_module.clean_subtitle_text = lambda text, **_kwargs: " ".join(text.split())
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


def test_cues_count_limit_rejects_before_parser_materialization(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del configured_tokenizer_bridge
    from android_bridge import subtitles

    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    monkeypatch.setattr(subtitles, "MAX_CUES", 1)
    parser_built = False

    def forbidden_parser(_config: object) -> object:
        nonlocal parser_built
        parser_built = True
        raise AssertionError("oversized cue graph reached parser construction")

    monkeypatch.setattr(subtitles, "_build_parser", forbidden_parser)
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )

    assert result["payload"]["code"] == "subtitle_cues_too_large"
    assert parser_built is False


def test_cues_text_limit_rejects_before_parser_materialization(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del configured_tokenizer_bridge
    from android_bridge import subtitles

    sub = tmp_path / "ep.srt"
    sub.write_text(SRT, encoding="utf-8")
    monkeypatch.setattr(subtitles, "MAX_RESULT_UTF8_BYTES", 1)
    parser_built = False

    def forbidden_parser(_config: object) -> object:
        nonlocal parser_built
        parser_built = True
        raise AssertionError("oversized cue text reached parser construction")

    monkeypatch.setattr(subtitles, "_build_parser", forbidden_parser)
    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )

    assert result["payload"]["code"] == "subtitle_cues_too_large"
    assert parser_built is False


def test_arrow_in_cue_text_is_not_counted_as_another_cue(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    from android_bridge import subtitles

    class _Parser:
        def __init__(self, config: object) -> None:
            self.config = config
            self._filter_pattern = None

        def parse_raw_entries(self, path: Path) -> list[tuple[float, float, str]]:
            del path
            return [(1.0, 2.0, "left --> right")]

    _install_subtitle_parser(monkeypatch, _Parser)
    monkeypatch.setattr(subtitles, "MAX_CUES", 1)
    sub = tmp_path / "ep.srt"
    sub.write_text("1\n00:00:01,000 --> 00:00:02,000\nleft --> right\n", encoding="utf-8")

    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )

    assert result["type"] == "subtitle.cues.result"
    assert result["payload"]["cues"] == [{"start": 1.0, "end": 2.0, "text": "left --> right"}]


def test_regex_expansion_limit_rejects_before_parser_materialization(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    from dataclasses import replace

    from android_bridge import subtitles
    from anki_miner.config.config import AnkiMinerConfig

    parser_called = False

    class _Parser:
        def __init__(self, config: object) -> None:
            self.config = config
            self._filter_pattern = re.compile("a")

        def parse_raw_entries(self, path: Path) -> list[tuple[float, float, str]]:
            nonlocal parser_called
            del path
            parser_called = True
            return [(1.0, 2.0, "aaaaaa")]

    config = replace(
        AnkiMinerConfig(),
        use_subtitle_regex_filter=True,
        subtitle_regex_filter="a",
        subtitle_regex_replacement="aaaaaa",
    )
    _install_subtitle_parser(monkeypatch, _Parser)
    monkeypatch.setattr(subtitles, "MAX_RESULT_UTF8_BYTES", 5)
    monkeypatch.setattr(subtitles, "_resolve_config", lambda _run_id: config)
    sub = tmp_path / "ep.srt"
    sub.write_text("1\n00:00:01,000 --> 00:00:02,000\na\n", encoding="utf-8")

    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )

    assert result["payload"]["code"] == "subtitle_cues_too_large"
    assert parser_called is False


def test_disguised_unsupported_format_rejects_before_parser_materialization(
    tmp_path: Path,
    configured_tokenizer_bridge: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del configured_tokenizer_bridge
    from android_bridge import subtitles

    parser_built = False

    def forbidden_parser(_config: object) -> object:
        nonlocal parser_built
        parser_built = True
        raise AssertionError("unsupported format reached parser construction")

    monkeypatch.setattr(subtitles, "_build_parser", forbidden_parser)
    sub = tmp_path / "disguised.srt"
    sub.write_text(
        '<tt xmlns="http://www.w3.org/ns/ttml"><body><p '
        'begin="00:00:01.000" end="00:00:02.000">secret</p></body></tt>',
        encoding="utf-8",
    )

    result = dispatch_json(
        {
            "type": "subtitle.cues",
            "payload": {"runId": None, "subtitlePath": str(sub)},
        }
    )

    assert result["payload"]["code"] == "subtitle_cues_parse_failed"
    assert parser_built is False


def test_cue_preflight_preserves_decode_failure_when_fallbacks_fail(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from android_bridge import subtitles

    sub = tmp_path / "ep.srt"
    sub.write_bytes(b"\x81")
    subtitle_encoding = ModuleType("anki_miner.utils.subtitle_encoding")
    subtitle_encoding.__dict__["_detect_encoding"] = lambda _path: None
    monkeypatch.setitem(
        sys.modules,
        "anki_miner.utils.subtitle_encoding",
        subtitle_encoding,
    )

    with pytest.raises(UnicodeDecodeError):
        subtitles._preflight_cue_budgets(sub)


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
