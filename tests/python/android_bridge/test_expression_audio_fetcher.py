"""Tests for the bridge port of CustomAudioFetcher (localaudio/custom sources).

Mirrors the desktop ``tests/unit/test_custom_audio_fetcher.py`` for the ported
fetcher. Runs only on the runtime lane where ``requests`` is installed; the
requests-free host lane skips the whole module at collection.
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest

pytest.importorskip("requests", reason="runtime dependency lane")

import requests  # noqa: E402
from android_bridge.expression_audio_fetcher import (  # noqa: E402
    CustomAudioFetcher,
    _substitute_custom_url,
    custom_audio_slug,
)

# Minimal valid ID3v2-tagged MP3 body.
_VALID_MP3 = b"ID3" + b"\x00" * 7 + b"\xff\xfb\x90\x00" + b"\x00" * 100


@pytest.fixture(autouse=True)
def _bootstrap_fetcher(initialized_bridge_home: Path) -> Path:
    """Bootstrap ANKI_MINER_HOME so the fetcher's function-local engine imports resolve."""

    return initialized_bridge_home


def _audio_response(content: bytes = _VALID_MP3, content_type: str | None = None, status: int = 200) -> MagicMock:
    resp = MagicMock()
    resp.status_code = status
    resp.url = "http://localhost:8765/audio"
    resp.headers = {"Content-Type": content_type} if content_type is not None else {}
    resp.iter_content.side_effect = lambda chunk_size=8192: iter([content])
    return resp


def _json_response(payload: object, status: int = 200, url: str = "http://localhost:8765/localaudio/get") -> MagicMock:
    resp = MagicMock()
    resp.status_code = status
    resp.url = url
    resp.json.return_value = payload
    return resp


# ---------------------------------------------------------------------------
# _substitute_custom_url (ported _getCustomUrl)
# ---------------------------------------------------------------------------


class TestSubstituteCustomUrl:
    def test_substitutes_term_and_reading(self) -> None:
        out = _substitute_custom_url("http://h/?t={term}&r={reading}", "食べる", "たべる", "ja")
        assert out == "http://h/?t=食べる&r=たべる"

    def test_substitutes_language(self) -> None:
        assert _substitute_custom_url("http://h/{language}", "x", "y", "ja") == "http://h/ja"

    def test_unknown_placeholder_left_intact(self) -> None:
        # Matches upstream: an unrecognized {name} is preserved verbatim.
        assert _substitute_custom_url("http://h/{bogus}?t={term}", "w", "r", "ja") == "http://h/{bogus}?t=w"

    def test_localaudio_template_substituted(self) -> None:
        # The verbatim AnkiConnect-Android endpoint the bridge injects by default.
        out = _substitute_custom_url(
            "http://localhost:8765/localaudio/get/?term={term}&reading={reading}",
            "食べる",
            "たべる",
            "ja",
        )
        assert out == "http://localhost:8765/localaudio/get/?term=食べる&reading=たべる"


class TestCustomAudioSlug:
    def test_stable_and_short(self) -> None:
        s1 = custom_audio_slug("http://localhost:8765/?t={term}")
        s2 = custom_audio_slug("http://localhost:8765/?t={term}")
        assert s1 == s2
        assert 0 < len(s1) <= 10
        assert s1.isalnum()

    def test_distinct_urls_distinct_slugs(self) -> None:
        assert custom_audio_slug("http://a/") != custom_audio_slug("http://b/")


# ---------------------------------------------------------------------------
# CustomAudioFetcher — custom (direct URL)
# ---------------------------------------------------------------------------


class TestCustomAudioFetcherDirect:
    def _fetcher(self, tmp_path: Path, kind: str = "custom", template: str = "http://h/?t={term}&r={reading}"):
        f = CustomAudioFetcher(
            url_template=template,
            kind=kind,
            cache_dir=tmp_path / "cache",
            file_prefix="custom_abc",
            delay=0,
        )
        f._session = MagicMock()
        return f

    def test_empty_reading_or_form_skips(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        assert f.fetch("食べる", "") is None
        assert f.fetch("", "たべる") is None
        f._session.get.assert_not_called()

    def test_success_downloads_and_names_by_prefix(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3)
        result = f.fetch("食べる", "たべる")
        assert result is not None
        assert result.name == "custom_abc_食べる_たべる.mp3"
        assert result.exists()

    def test_url_template_substituted(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3)
        f.fetch("食べる", "たべる")
        called_url = f._session.get.call_args[0][0]
        assert called_url == "http://h/?t=食べる&r=たべる"

    def test_cache_hit_skips_network(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3)
        first = f.fetch("食べる", "たべる")
        assert first is not None
        f._session.get.reset_mock()
        second = f.fetch("食べる", "たべる")
        assert second == first
        f._session.get.assert_not_called()

    def test_non_audio_returns_none(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(b"<html>", content_type="text/html")
        assert f.fetch("食べる", "たべる") is None

    def test_never_raises_on_network_error(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.side_effect = requests.ConnectionError("server down")
        assert f.fetch("食べる", "たべる") is None

    def test_never_raises_on_non_200(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3, status=404)
        assert f.fetch("食べる", "たべる") is None

    def test_cancelled_short_circuits(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3)
        assert f.fetch("食べる", "たべる", cancelled_check=lambda: True) is None
        f._session.get.assert_not_called()


# ---------------------------------------------------------------------------
# CustomAudioFetcher — custom_json (audioSourceList; localaudio's format)
# ---------------------------------------------------------------------------


class TestCustomAudioFetcherJson:
    def _fetcher(self, tmp_path: Path, template: str = "http://h/list?t={term}"):
        f = CustomAudioFetcher(
            url_template=template,
            kind="custom_json",
            cache_dir=tmp_path / "cache",
            file_prefix="custom_json1",
            delay=0,
        )
        f._session = MagicMock()
        return f

    def test_valid_list_downloads_first_source(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [
                {"name": "a", "url": "http://h/media/a.mp3"},
                {"name": "b", "url": "http://h/media/b.mp3"},
            ],
        }
        f._session.get.side_effect = [
            _json_response(payload),
            _audio_response(_VALID_MP3),
        ]
        result = f.fetch("食べる", "たべる")
        assert result is not None
        assert result.name == "custom_json1_食べる_たべる.mp3"
        # first GET = the JSON list, second GET = the first audio source URL
        assert f._session.get.call_args_list[0][0][0] == "http://h/list?t=食べる"
        assert f._session.get.call_args_list[1][0][0] == "http://h/media/a.mp3"

    def test_relative_urls_normalized_against_endpoint(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/media/rel.mp3"}]}
        f._session.get.side_effect = [
            _json_response(payload, url="http://localhost:8765/localaudio/get?t=x"),
            _audio_response(_VALID_MP3),
        ]
        f.fetch("食べる", "たべる")
        assert f._session.get.call_args_list[1][0][0] == "http://localhost:8765/media/rel.mp3"

    def test_first_source_miss_falls_to_second(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [{"url": "http://h/a.mp3"}, {"url": "http://h/b.mp3"}],
        }
        f._session.get.side_effect = [
            _json_response(payload),
            _audio_response(b"<html>", content_type="text/html"),  # first is not audio
            _audio_response(_VALID_MP3),  # second succeeds
        ]
        result = f.fetch("食べる", "たべる")
        assert result is not None
        assert f._session.get.call_args_list[2][0][0] == "http://h/b.mp3"

    def test_malformed_source_url_skipped_not_fatal(self, tmp_path: Path) -> None:
        """A malformed audioSources URL is skipped (never raises); good URLs remain."""
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [
                {"url": "http://[bad"},  # urljoin -> ValueError (Invalid IPv6 URL)
                {"url": "http://h/media/ok.mp3"},
            ],
        }
        f._session.get.return_value = _json_response(payload)
        urls = f._resolve_json_sources("http://h/list")
        assert urls == ["http://h/media/ok.mp3"]

    def test_wrong_type_returns_none(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _json_response({"type": "somethingElse", "audioSources": []})
        assert f.fetch("食べる", "たべる") is None

    def test_missing_audiosources_returns_none(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _json_response({"type": "audioSourceList"})
        assert f.fetch("食べる", "たべる") is None

    def test_malformed_json_never_raises(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        bad = _json_response(None)
        bad.json.side_effect = json.JSONDecodeError("bad", "", 0)
        f._session.get.return_value = bad
        assert f.fetch("食べる", "たべる") is None

    def test_non_200_json_returns_none(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _json_response({}, status=500)
        assert f.fetch("食べる", "たべる") is None

    def test_cancelled_between_candidates_short_circuits(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3)
        # Cancelled before any network work: no GET issued, no hit.
        assert f.fetch_candidates([("食べる", "たべる")], cancelled_check=lambda: True) is None
        f._session.get.assert_not_called()

    def test_stats_and_close(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        assert set(f.stats()) >= {"non_audio", "http_status", "connection"}
        f.close()  # must not raise
