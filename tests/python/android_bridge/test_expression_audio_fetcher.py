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
from android_bridge import expression_audio_fetcher as expression_audio_fetcher_module  # noqa: E402
from android_bridge.expression_audio_fetcher import (  # noqa: E402
    _MAX_AUDIO_SOURCES,
    _MAX_JSON_BYTES,
    _MAX_REDIRECTS,
    _MAX_TOTAL_ATTEMPTS,
    _MAX_URL_BYTES,
    CustomAudioFetcher,
    _substitute_custom_url,
    custom_audio_slug,
)

# Minimal ID3v2-tagged MPEG-1 Layer III frame (128 kbps, 44.1 kHz, 417 bytes).
_VALID_MP3 = b"ID3" + b"\x00" * 7 + b"\xff\xfb\x90\x00" + b"\x00" * 413


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


def _json_response(
    payload: object,
    status: int = 200,
    url: str = "http://localhost:8765/localaudio/get",
    *,
    raw_body: bytes | None = None,
) -> MagicMock:
    resp = MagicMock()
    resp.status_code = status
    resp.url = url
    body = json.dumps(payload).encode("utf-8") if raw_body is None else raw_body
    resp.headers = {"Content-Type": "application/json", "Content-Length": str(len(body))}
    resp.iter_content.side_effect = lambda chunk_size=8192: (
        body[offset : offset + chunk_size] for offset in range(0, len(body), chunk_size)
    )
    resp.json.return_value = payload
    return resp


def _redirect_response(location: str, *, url: str = "http://localhost:8765/redirect") -> MagicMock:
    resp = MagicMock()
    resp.status_code = 302
    resp.url = url
    resp.headers = {"Location": location}
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
    def _fetcher(
        self,
        tmp_path: Path,
        kind: str = "custom",
        template: str = "http://localhost:8765/?t={term}&r={reading}",
        **options: object,
    ):
        f = CustomAudioFetcher(
            url_template=template,
            kind=kind,
            cache_dir=tmp_path / "cache",
            file_prefix="custom_abc",
            delay=0,
            **options,
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
        assert called_url == "http://localhost:8765/?t=食べる&r=たべる"

    def test_unapproved_template_is_rejected_before_network(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path, template="http://h/?t={term}&r={reading}")
        f._session.get.return_value = _audio_response(_VALID_MP3)

        assert f.fetch("食べる", "たべる") is None
        f._session.get.assert_not_called()
        assert f.stats()["policy_rejection"] == 1

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

    def test_mislabeled_or_truncated_mp3_is_deleted_as_non_audio(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        truncated = b"ID3" + b"\x00" * 7 + b"\xff\xfb\x90\x00" + b"\x00" * 8
        f._session.get.return_value = _audio_response(truncated, content_type="audio/mpeg")

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["non_audio"] == 1
        assert not list((tmp_path / "cache").glob("*"))

    def test_invalid_cache_entry_is_deleted_and_refetched(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3)
        cached = f.fetch("食べる", "たべる")
        assert cached is not None
        cached.write_bytes(b"<html>")
        f._session.get.reset_mock()
        f._session.get.return_value = _audio_response(_VALID_MP3)

        refreshed = f.fetch("食べる", "たべる")

        assert refreshed == cached
        assert refreshed.read_bytes() == _VALID_MP3
        f._session.get.assert_called_once()
        assert f.stats()["non_audio"] == 1

    def test_ffprobe_rejection_deletes_structurally_valid_download(
        self,
        tmp_path: Path,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        probe = MagicMock(return_value=False)
        monkeypatch.setattr(expression_audio_fetcher_module, "_ffprobe_accepts_audio", probe)
        f = self._fetcher(tmp_path, ffprobe_path=Path("/bundled/ffprobe"))
        f._session.get.return_value = _audio_response(_VALID_MP3)

        assert f.fetch("食べる", "たべる") is None
        probe.assert_called_once()
        assert f.stats()["non_audio"] == 1
        assert not list((tmp_path / "cache").glob("*"))

    def test_returned_files_remain_pinned_until_close_then_are_pruned(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _audio_response(_VALID_MP3)

        first = f.fetch("食べる", "たべる")
        second = f.fetch("猫", "ねこ")

        assert first is not None and first.exists()
        assert second is not None and second.exists()
        f.close()
        assert not first.exists()
        assert not second.exists()

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
    def _fetcher(
        self,
        tmp_path: Path,
        template: str = "http://localhost:8765/list?t={term}",
        *,
        approved_audio_origins: tuple[str, ...] = (),
        **extra_options: object,
    ):
        options: dict[str, object] = {}
        if approved_audio_origins:
            options["approved_audio_origins"] = approved_audio_origins
        options.update(extra_options)
        f = CustomAudioFetcher(
            url_template=template,
            kind="custom_json",
            cache_dir=tmp_path / "cache",
            file_prefix="custom_json1",
            delay=0,
            **options,
        )
        f._session = MagicMock()
        return f

    def test_valid_list_downloads_first_source(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [
                {"name": "a", "url": "http://localhost:8765/media/a.mp3"},
                {"name": "b", "url": "http://localhost:8765/media/b.mp3"},
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
        assert f._session.get.call_args_list[0][0][0] == "http://localhost:8765/list?t=食べる"
        assert f._session.get.call_args_list[1][0][0] == "http://localhost:8765/media/a.mp3"

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
            "audioSources": [
                {"url": "http://localhost:8765/a.mp3"},
                {"url": "http://localhost:8765/b.mp3"},
            ],
        }
        f._session.get.side_effect = [
            _json_response(payload),
            _audio_response(b"<html>", content_type="text/html"),  # first is not audio
            _audio_response(_VALID_MP3),  # second succeeds
        ]
        result = f.fetch("食べる", "たべる")
        assert result is not None
        assert f._session.get.call_args_list[2][0][0] == "http://localhost:8765/b.mp3"

    def test_malformed_source_url_skipped_not_fatal(self, tmp_path: Path) -> None:
        """A malformed audioSources URL is skipped (never raises); good URLs remain."""
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [
                {"url": "http://[bad"},  # urljoin -> ValueError (Invalid IPv6 URL)
                {"url": "http://localhost:8765/media/ok.mp3"},
            ],
        }
        f._session.get.return_value = _json_response(payload)
        urls = f._resolve_json_sources("http://localhost:8765/list")
        assert urls == ["http://localhost:8765/media/ok.mp3"]

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
        bad = _json_response(None, raw_body=b"{")
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

    def test_non_loopback_directory_endpoint_is_rejected_before_network(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path, template="https://directory.example/list?t={term}")

        assert f.fetch("食べる", "たべる") is None
        f._session.get.assert_not_called()
        assert f.stats()["policy_rejection"] == 1

    def test_json_size_boundary_skips_parse_above_cap(
        self,
        tmp_path: Path,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        valid_body = b'{"type":"audioSourceList","audioSources":[]}'
        exact_body = valid_body + (b" " * (_MAX_JSON_BYTES - len(valid_body)))
        oversized_body = exact_body + b" "
        assert len(exact_body) == _MAX_JSON_BYTES
        assert len(oversized_body) == _MAX_JSON_BYTES + 1

        loads_spy = MagicMock(wraps=json.loads)
        monkeypatch.setattr(expression_audio_fetcher_module.json, "loads", loads_spy)

        at_cap = self._fetcher(tmp_path)
        at_cap_response = _json_response(None, raw_body=exact_body)
        at_cap_response.headers.pop("Content-Length")
        at_cap._session.get.return_value = at_cap_response
        assert at_cap._resolve_json_sources("http://localhost:8765/list") == []
        loads_spy.assert_called_once()
        assert at_cap.stats()["oversized_response"] == 0

        loads_spy.reset_mock()
        above_cap = self._fetcher(tmp_path)
        above_cap_response = _json_response(None, raw_body=oversized_body)
        above_cap_response.headers.pop("Content-Length")
        above_cap._session.get.return_value = above_cap_response
        assert above_cap._resolve_json_sources("http://localhost:8765/list") == []
        loads_spy.assert_not_called()
        assert above_cap.stats()["oversized_response"] == 1

    def test_source_count_boundary_accepts_cap_and_rejects_cap_plus_one(self, tmp_path: Path) -> None:
        def payload(count: int) -> dict[str, object]:
            return {
                "type": "audioSourceList",
                "audioSources": [{"url": f"http://localhost:8765/{index}.mp3"} for index in range(count)],
            }

        at_cap = self._fetcher(tmp_path)
        at_cap._session.get.return_value = _json_response(payload(_MAX_AUDIO_SOURCES))
        urls = at_cap._resolve_json_sources("http://localhost:8765/list")
        assert len(urls) == _MAX_AUDIO_SOURCES
        assert at_cap.stats()["oversized_list"] == 0

        above_cap = self._fetcher(tmp_path)
        above_cap._session.get.return_value = _json_response(payload(_MAX_AUDIO_SOURCES + 1))
        assert above_cap._resolve_json_sources("http://localhost:8765/list") == []
        assert above_cap.stats()["oversized_list"] == 1

    def test_source_url_length_boundary_accepts_cap_and_rejects_cap_plus_one(self, tmp_path: Path) -> None:
        prefix = "http://localhost:8765/"
        exact_url = prefix + ("x" * (_MAX_URL_BYTES - len(prefix.encode("utf-8"))))
        oversized_url = exact_url + "x"
        assert len(exact_url.encode("utf-8")) == _MAX_URL_BYTES
        assert len(oversized_url.encode("utf-8")) == _MAX_URL_BYTES + 1

        at_cap = self._fetcher(tmp_path)
        at_cap._session.get.return_value = _json_response(
            {"type": "audioSourceList", "audioSources": [{"url": exact_url}]}
        )
        assert at_cap._resolve_json_sources("http://localhost:8765/list") == [exact_url]
        assert at_cap.stats()["policy_rejection"] == 0

        above_cap = self._fetcher(tmp_path)
        above_cap._session.get.return_value = _json_response(
            {"type": "audioSourceList", "audioSources": [{"url": oversized_url}]}
        )
        assert above_cap._resolve_json_sources("http://localhost:8765/list") == []
        assert above_cap.stats()["policy_rejection"] == 1

    def test_redirect_loop_is_rejected(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.side_effect = [
            _redirect_response("/list-b", url="http://localhost:8765/list-a"),
            _redirect_response(
                "http://localhost:8765/list?t=食べる",
                url="http://localhost:8765/list-b",
            ),
        ]

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == 2

    def test_unsupported_scheme_change_in_audio_redirect_is_rejected(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio.mp3"}]}
        f._session.get.side_effect = [
            _json_response(payload),
            _redirect_response("file:///data/local/private.mp3", url="http://localhost:8765/audio.mp3"),
        ]

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == 2

    @pytest.mark.parametrize(
        "target",
        [
            "https://evil.example/audio.mp3",
            "http://10.0.0.20/audio.mp3",
        ],
    )
    def test_loopback_audio_redirect_cannot_pivot_to_unapproved_network(
        self,
        tmp_path: Path,
        target: str,
    ) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio.mp3"}]}
        f._session.get.side_effect = [
            _json_response(payload),
            _redirect_response(target, url="http://localhost:8765/audio.mp3"),
        ]

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == 2

    @pytest.mark.parametrize("pivot_hop", [2, 3])
    def test_later_loopback_audio_redirect_cannot_pivot_to_unapproved_network(
        self,
        tmp_path: Path,
        pivot_hop: int,
    ) -> None:
        f = self._fetcher(tmp_path)
        remote_url = "https://evil.example/audio.mp3"
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio-0.mp3"}]}
        redirects = []
        for hop in range(pivot_hop):
            location = remote_url if hop == pivot_hop - 1 else f"/audio-{hop + 1}.mp3"
            redirects.append(_redirect_response(location, url=f"http://localhost:8765/audio-{hop}.mp3"))
        f._session.get.side_effect = [_json_response(payload), *redirects]

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == pivot_hop + 1
        assert remote_url not in [call.args[0] for call in f._session.get.call_args_list]

    def test_directory_redirect_cannot_leave_loopback_even_when_audio_origin_is_approved(
        self,
        tmp_path: Path,
    ) -> None:
        f = self._fetcher(tmp_path, approved_audio_origins=("https://cdn.example",))
        f._session.get.return_value = _redirect_response("https://cdn.example/list")

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == 1

    def test_exact_redirect_cap_is_accepted(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio-0.mp3"}]}
        f._session.get.side_effect = [
            _json_response(payload),
            *[
                _redirect_response(
                    f"/audio-{index + 1}.mp3",
                    url=f"http://localhost:8765/audio-{index}.mp3",
                )
                for index in range(_MAX_REDIRECTS)
            ],
            _audio_response(_VALID_MP3),
        ]

        assert f.fetch("食べる", "たべる") is not None
        assert f.stats()["policy_rejection"] == 0
        assert f._session.get.call_count == _MAX_REDIRECTS + 2

    def test_redirect_cap_plus_one_is_rejected(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio-0.mp3"}]}
        f._session.get.side_effect = [
            _json_response(payload),
            *[
                _redirect_response(
                    f"/audio-{index + 1}.mp3",
                    url=f"http://localhost:8765/audio-{index}.mp3",
                )
                for index in range(_MAX_REDIRECTS + 1)
            ],
        ]

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == _MAX_REDIRECTS + 2

    def test_loopback_redirects_are_validated_and_allowed(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio-a.mp3"}]}
        f._session.get.side_effect = [
            _redirect_response(
                "http://127.0.0.1:8765/list-final",
                url="http://localhost:8765/list",
            ),
            _json_response(payload, url="http://127.0.0.1:8765/list-final"),
            _redirect_response(
                "http://localhost:8765/audio-final.mp3",
                url="http://127.0.0.1:8765/audio-a.mp3",
            ),
            _audio_response(_VALID_MP3),
        ]

        assert f.fetch("食べる", "たべる") is not None
        assert [call.args[0] for call in f._session.get.call_args_list] == [
            "http://localhost:8765/list?t=食べる",
            "http://127.0.0.1:8765/list-final",
            "http://127.0.0.1:8765/audio-a.mp3",
            "http://localhost:8765/audio-final.mp3",
        ]

    @pytest.mark.parametrize(
        "url",
        [
            "http://127.0.0.1:8080/admin.mp3",
            "https://localhost:8765/audio.mp3",
        ],
    )
    def test_unreviewed_loopback_origin_is_rejected(self, tmp_path: Path, url: str) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": url}]}
        f._session.get.return_value = _json_response(payload)

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == 1

    @pytest.mark.parametrize(
        "url",
        [
            "http://user:secret@localhost:8765/audio.mp3",
            "file:///data/local/audio.mp3",
            "ftp://localhost/audio.mp3",
            "http://[bad",
            "http://192.168.1.20/audio.mp3",
        ],
    )
    def test_unsafe_source_urls_are_rejected(self, tmp_path: Path, url: str) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": url}]}
        f._session.get.return_value = _json_response(payload)

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == 1

    def test_unapproved_remote_source_is_rejected(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "https://evil.example/a.mp3"}]}
        f._session.get.return_value = _json_response(payload)

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["policy_rejection"] == 1
        assert f._session.get.call_count == 1

    def test_explicitly_approved_remote_source_is_allowed(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path, approved_audio_origins=("https://cdn.example",))
        payload = {"type": "audioSourceList", "audioSources": [{"url": "https://cdn.example/a.mp3"}]}
        f._session.get.side_effect = [_json_response(payload), _audio_response(_VALID_MP3)]

        assert f.fetch("食べる", "たべる") is not None
        assert f._session.get.call_args_list[1].args[0] == "https://cdn.example/a.mp3"

    def test_loopback_audio_can_redirect_to_explicitly_approved_remote_origin(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path, approved_audio_origins=("https://cdn.example",))
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio.mp3"}]}
        f._session.get.side_effect = [
            _json_response(payload),
            _redirect_response("https://cdn.example/final.mp3", url="http://localhost:8765/audio.mp3"),
            _audio_response(_VALID_MP3),
        ]

        assert f.fetch("食べる", "たべる") is not None
        assert f._session.get.call_args_list[2].args[0] == "https://cdn.example/final.mp3"

    def test_malformed_json_has_distinct_counter(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _json_response(None, raw_body=b"not-json")

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["malformed_json"] == 1

    @pytest.mark.parametrize(
        "raw_body",
        [
            (b"[" * 2_000) + b"0" + (b"]" * 2_000),
            b'{"type":"audioSourceList","audioSources":[],"number":' + (b"9" * 5_000) + b"}",
        ],
    )
    def test_bounded_pathological_json_never_raises(self, tmp_path: Path, raw_body: bytes) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.return_value = _json_response(None, raw_body=raw_body)

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["malformed_json"] == 1

    def test_invalid_source_members_are_counted_while_valid_sibling_remains_usable(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [None, {"url": 42}, {"url": "/valid.mp3"}],
        }
        f._session.get.side_effect = [_json_response(payload), _audio_response(_VALID_MP3)]

        assert f.fetch("食べる", "たべる") is not None
        assert f.stats()["malformed_json"] == 1

    def test_directory_timeout_has_distinct_counter(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        f._session.get.side_effect = requests.Timeout("late")

        assert f.fetch("食べる", "たべる") is None
        assert f.stats()["timeout"] == 1

    def test_absolute_deadline_stops_trickling_directory_body(self, tmp_path: Path) -> None:
        now = [0.0]
        f = self._fetcher(
            tmp_path,
            deadline_seconds=1.0,
            monotonic=lambda: now[0],
        )
        response = _json_response(None, raw_body=b"{}")

        def trickle(chunk_size: int = 8192):
            del chunk_size
            now[0] = 0.75
            yield b"{"
            now[0] = 1.01
            yield b"}"

        response.iter_content.side_effect = trickle
        f._session.get.return_value = response

        assert f.fetch("食べる", "たべる") is None
        assert f._session.get.call_count == 1
        assert f.stats()["timeout"] == 1
        response.close.assert_called()

    def test_absolute_deadline_stops_trickling_audio_body(self, tmp_path: Path) -> None:
        now = [0.0]
        f = self._fetcher(
            tmp_path,
            deadline_seconds=1.0,
            monotonic=lambda: now[0],
        )
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio.mp3"}]}
        audio = _audio_response(_VALID_MP3)

        def trickle(chunk_size: int = 8192):
            del chunk_size
            now[0] = 0.75
            yield _VALID_MP3[:20]
            now[0] = 1.01
            yield _VALID_MP3[20:]

        audio.iter_content.side_effect = trickle
        f._session.get.side_effect = [_json_response(payload), audio]

        assert f.fetch("食べる", "たべる") is None
        assert f._session.get.call_count == 2
        assert f.stats()["timeout"] == 1
        audio.close.assert_called()
        assert not list((tmp_path / "cache").glob("*"))

    def test_total_raw_attempts_are_capped_across_sources_and_redirects(self, tmp_path: Path) -> None:
        assert _MAX_TOTAL_ATTEMPTS == 16
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [{"url": f"http://localhost:8765/{index}-a.mp3"} for index in range(_MAX_AUDIO_SOURCES)],
        }
        responses: list[MagicMock] = [_json_response(payload)]
        for index in range(_MAX_AUDIO_SOURCES):
            responses.extend(
                [
                    _redirect_response(
                        f"http://localhost:8765/{index}-b.mp3",
                        url=f"http://localhost:8765/{index}-a.mp3",
                    ),
                    _audio_response(b"<html>", content_type="text/html"),
                ]
            )
        f._session.get.side_effect = responses

        assert f.fetch("食べる", "たべる") is None
        assert f._session.get.call_count == _MAX_TOTAL_ATTEMPTS
        assert f.stats()["policy_rejection"] == 1

    def test_total_attempt_budget_spans_fetch_candidates(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {
            "type": "audioSourceList",
            "audioSources": [{"url": f"http://localhost:8765/{index}-a.mp3"} for index in range(_MAX_AUDIO_SOURCES)],
        }

        def respond(url: str, **_: object) -> MagicMock:
            if "/list?" in url:
                return _json_response(payload, url=url)
            if url.endswith("-a.mp3"):
                return _redirect_response(url.replace("-a.mp3", "-b.mp3"), url=url)
            return _audio_response(b"<html>", content_type="text/html")

        f._session.get.side_effect = respond

        assert f.fetch_candidates([("食べる", "たべる"), ("食う", "くう")]) is None
        assert f._session.get.call_count == _MAX_TOTAL_ATTEMPTS
        assert f.stats()["policy_rejection"] >= 1

    def test_cancellation_during_json_stream_stops_before_audio_request(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        response = _json_response({"type": "audioSourceList", "audioSources": [{"url": "/audio.mp3"}]})
        checks = 0

        def cancelled() -> bool:
            nonlocal checks
            checks += 1
            return checks >= 5

        f._session.get.return_value = response

        assert f.fetch("食べる", "たべる", cancelled_check=cancelled) is None
        assert f._session.get.call_count == 1
        assert sum(f.stats().values()) == 0

    def test_cancellation_during_audio_stream_stops_without_failure_counter(self, tmp_path: Path) -> None:
        f = self._fetcher(tmp_path)
        payload = {"type": "audioSourceList", "audioSources": [{"url": "/audio.mp3"}]}
        audio = _audio_response()
        audio_stream_started = False

        def audio_chunks(chunk_size: int = 8192):
            del chunk_size
            nonlocal audio_stream_started
            audio_stream_started = True
            yield _VALID_MP3[:20]
            yield _VALID_MP3[20:]

        def cancelled() -> bool:
            return audio_stream_started

        audio.iter_content.side_effect = audio_chunks
        f._session.get.side_effect = [_json_response(payload), audio]

        assert f.fetch("食べる", "たべる", cancelled_check=cancelled) is None
        assert f._session.get.call_count == 2
        assert sum(f.stats().values()) == 0
        assert not list((tmp_path / "cache").glob("*"))


class TestUnknownContainerParity:
    """An unrecognised container must not be treated as evidence of non-audio.

    The desktop expression-audio chain stores whatever the configured source
    returns, so refusing a container we happen to have no validator for would
    silently drop pack audio that mines correctly today.
    """

    def test_unknown_extension_is_accepted(self, tmp_path: Path) -> None:
        payload = tmp_path / "word.m4a"
        payload.write_bytes(_VALID_MP3)

        assert expression_audio_fetcher_module._has_valid_audio_structure(payload) is True

    def test_recognised_container_is_still_structurally_checked(self, tmp_path: Path) -> None:
        payload = tmp_path / "word.mp3"
        payload.write_bytes(b"<html>not audio at all</html>")

        assert expression_audio_fetcher_module._has_valid_audio_structure(payload) is False

    def test_unreadable_probe_verdict_falls_back_to_structure(self, tmp_path: Path) -> None:
        payload = tmp_path / "word.mp3"
        payload.write_bytes(b"<html>not audio at all</html>")

        assert (
            expression_audio_fetcher_module._ffprobe_accepts_audio(payload, tmp_path / "no-such-ffprobe", 1.0) is None
        )


class TestRunAudioCache:
    def test_pin_does_not_prune_unreferenced_cache_entries(self, tmp_path: Path) -> None:
        """pin() only records the pin; pruning happens at construction and close.

        Prune-on-pin was an O(n²) full walk per hit, and every unlink bumped the
        cache directory's mtime, invalidating the ``find_cached_by_stem`` index.
        """
        root = tmp_path / "cache"
        root.mkdir()
        cache = expression_audio_fetcher_module._RunAudioCache(root)
        stray = root / "stray.mp3"
        stray.write_bytes(b"x")  # created AFTER the construction prune
        kept = root / "kept.mp3"
        kept.write_bytes(b"y")

        assert cache.pin(kept) is True
        assert stray.exists()  # pin no longer walks or prunes

        cache.close()
        assert not stray.exists()  # close still prunes unreferenced files
        assert not kept.exists()
