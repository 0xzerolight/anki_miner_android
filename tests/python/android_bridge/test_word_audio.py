from __future__ import annotations

from pathlib import Path

import pytest
from android_bridge.protocol import decode_envelope, encode_message
from android_bridge.word_audio import AndroidWordAudioFetcher

RUN_ID = "run_00000000000000000000000000000000"


class TtsCallbacks:
    """Kotlin's synthesizeSentenceAudio, writing one content-named WAV per spoken text."""

    def __init__(self, cache_dir: Path, *, outcome: str = "ready", fail: bool = False) -> None:
        self.cache_dir = cache_dir
        self.outcome = outcome
        self.fail = fail
        self.spoken: list[tuple[str, str]] = []

    def synthesizeSentenceAudio(self, raw: str) -> str:
        if self.fail:
            raise RuntimeError("TextToSpeech died")
        request = decode_envelope(raw, expected_type="tts.sentence.request")
        text = request.payload["sentence"]
        self.spoken.append((text, request.payload["language"]))
        output = self.cache_dir / "sentence-audio-v1" / f"android_tts_v1_{len(self.spoken):064x}.wav"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"RIFF" + text.encode())
        ready = self.outcome == "ready"
        return encode_message(
            "tts.sentence.result",
            {
                "runId": request.payload["runId"],
                "requestId": request.payload["requestId"],
                "outcome": self.outcome,
                "path": str(output) if ready else None,
                "errorCode": None if ready else "offline_voice_unavailable",
            },
        )


def _speak_reading_else_term(term: str, reading: str) -> str | None:
    return reading or term or None


def _fetcher(
    tmp_path: Path,
    callbacks: object,
    *,
    speakable: object = _speak_reading_else_term,
) -> AndroidWordAudioFetcher:
    return AndroidWordAudioFetcher(
        callbacks,
        RUN_ID,
        tmp_path / "cache",
        tmp_path / "media",
        language="he",
        speakable=speakable,  # type: ignore[arg-type]
    )


def test_fetch_speaks_the_profiles_text_in_the_run_language_and_copies_it_out(tmp_path: Path) -> None:
    callbacks = TtsCallbacks(tmp_path / "cache")
    fetcher = _fetcher(tmp_path, callbacks)

    path = fetcher.fetch("ספר", "")

    assert callbacks.spoken == [("ספר", "he")]
    assert path is not None
    assert path.parent == tmp_path / "media"
    assert path.read_bytes() == "RIFFספר".encode()
    assert path.name == fetcher.media_name("ספר", "")
    assert [entry.name for entry in (tmp_path / "media").iterdir()] == [path.name]


def test_media_names_are_unique_per_form_and_reading_and_stable() -> None:
    fetcher = AndroidWordAudioFetcher(object(), RUN_ID, Path("/c"), Path("/m"), language="he", speakable=None)
    other_language = AndroidWordAudioFetcher(object(), RUN_ID, Path("/c"), Path("/m"), language="ar", speakable=None)

    names = {
        fetcher.media_name("ספר", ""),
        fetcher.media_name("ספר", "sefer"),
        fetcher.media_name("ספרים", ""),
        fetcher.media_name("a", "b\x00"),
        fetcher.media_name("a\x00b", ""),
        other_language.media_name("ספר", ""),
    }
    assert len(names) == 6
    assert fetcher.media_name("ספר", "") == fetcher.media_name("ספר", "")
    for name in names:
        assert name.startswith("androidtts_")
        assert name.endswith(".wav")
        assert name.isascii()
        assert "/" not in name


def test_unspeakable_pairs_never_reach_kotlin(tmp_path: Path) -> None:
    callbacks = TtsCallbacks(tmp_path / "cache")

    assert _fetcher(tmp_path, callbacks, speakable=lambda _term, _reading: None).fetch("x", "") is None
    assert _fetcher(tmp_path, callbacks, speakable=lambda _term, _reading: "  ").fetch("x", "") is None
    assert _fetcher(tmp_path, callbacks, speakable=None).fetch("x", "") is None
    assert _fetcher(tmp_path, callbacks).fetch("  ", "reading") is None
    assert callbacks.spoken == []


def test_fetch_never_raises(tmp_path: Path) -> None:
    def broken_speakable(_term: str, _reading: str) -> str:
        raise ValueError("profile bug")

    assert _fetcher(tmp_path, TtsCallbacks(tmp_path / "cache", fail=True)).fetch("ספר", "") is None
    assert _fetcher(tmp_path, TtsCallbacks(tmp_path / "cache"), speakable=broken_speakable).fetch("ספר", "") is None
    assert _fetcher(tmp_path, TtsCallbacks(tmp_path / "cache", outcome="unavailable")).fetch("ספר", "") is None
    assert _fetcher(tmp_path, object()).fetch("ספר", "") is None
    # A media directory that cannot be created is a miss, not a crash.
    (tmp_path / "media").write_text("not a directory")
    assert _fetcher(tmp_path, TtsCallbacks(tmp_path / "cache")).fetch("ספר", "") is None


class ExhaustedTtsCallbacks:
    def synthesizeSentenceAudio(self, raw: str) -> str:
        raise MemoryError("interpreter exhausted")


def _exhausted_speakable(_term: str, _reading: str) -> str:
    raise MemoryError("interpreter exhausted")


@pytest.mark.parametrize(
    ("callbacks_kind", "speakable"),
    [
        ("tts", _exhausted_speakable),
        ("exhausted", _speak_reading_else_term),
    ],
    ids=["speakable", "kotlin-callback"],
)
def test_memory_exhaustion_is_the_runs_to_stop_on(
    tmp_path: Path,
    callbacks_kind: str,
    speakable: object,
) -> None:
    callbacks = TtsCallbacks(tmp_path / "cache") if callbacks_kind == "tts" else ExhaustedTtsCallbacks()

    with pytest.raises(MemoryError, match="interpreter exhausted"):
        _fetcher(tmp_path, callbacks, speakable=speakable).fetch("ספר", "")


def test_cancellation_returns_none_and_writes_no_media(tmp_path: Path) -> None:
    callbacks = TtsCallbacks(tmp_path / "cache")
    fetcher = _fetcher(tmp_path, callbacks)

    assert fetcher.fetch("ספר", "", lambda: True) is None
    assert callbacks.spoken == []

    checks = iter([False, False, True])
    assert fetcher.fetch("ספר", "", lambda: next(checks)) is None
    assert callbacks.spoken == [("ספר", "he")]
    assert not (tmp_path / "media").exists()


def test_fetch_candidates_walks_the_ladder_to_the_first_spoken_pair(tmp_path: Path) -> None:
    callbacks = TtsCallbacks(tmp_path / "cache")

    def speak_only_lemmas(term: str, _reading: str) -> str | None:
        return term if term.startswith("lemma") else None

    fetcher = _fetcher(tmp_path, callbacks, speakable=speak_only_lemmas)

    path = fetcher.fetch_candidates([("surface", ""), ("lemma-a", ""), ("lemma-b", "")])

    assert path is not None
    assert path.name == fetcher.media_name("lemma-a", "")
    assert callbacks.spoken == [("lemma-a", "he")]
    assert fetcher.fetch_candidates([]) is None
    assert fetcher.fetch_candidates([("lemma-c", "")], lambda: True) is None
    fetcher.close()
