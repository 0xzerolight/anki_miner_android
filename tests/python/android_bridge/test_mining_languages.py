"""A non-ja mining run is composed from its own profile, never from Japanese defaults.

Every site here was a silent break before the language layer: a Hebrew run built
the Japanese parser, filtered with the kana gate, keyed known words into
``known_words.db`` and fetched word audio with the Japanese candidate ladder, and
none of it raised. Hebrew is the probe language because it is data-free: its
tokenizer is a regex and its profile needs no download.

The profile registry cannot be imported on the host lane (it pulls in pysubs2),
so everything that builds a non-ja profile runs on the runtime lane only.
"""

from __future__ import annotations

import os
import threading
from pathlib import Path
from types import SimpleNamespace

import android_bridge.mining as mining
import pytest
from android_bridge.config_map import AndroidPaths, map_config_settings
from android_bridge.protocol import BridgeProtocolError


def _runtime_lane() -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane")


@pytest.fixture(autouse=True)
def _bootstrap(initialized_bridge_home: Path) -> None:
    assert Path(os.environ["ANKI_MINER_HOME"]).resolve() == initialized_bridge_home.resolve()


def _hebrew_config(tmp_path: Path, **settings: object) -> object:
    paths = AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native")
    return map_config_settings({"language": "he", "anki_note_type": "Basic", **settings}, paths).engine_config


class _CapturedProcessor:
    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs


def _compose(monkeypatch: pytest.MonkeyPatch, config: object) -> dict[str, object]:
    import anki_miner.orchestration.episode_processor as episode_processor

    monkeypatch.setattr(episode_processor, "EpisodeProcessor", _CapturedProcessor)
    adapters = SimpleNamespace(
        presenter=SimpleNamespace(show_warning=lambda _message: None),
        cancel_event=threading.Event(),
    )
    processor = mining._build_processor(config, adapters, object())
    assert isinstance(processor, _CapturedProcessor)
    return processor.kwargs


# ---------------------------------------------------------------- run readiness


def test_a_hebrew_run_needs_neither_unidic_nor_the_japanese_tokenizer(
    monkeypatch: pytest.MonkeyPatch,
    initialized_bridge_home: Path,
) -> None:
    _runtime_lane()
    import android_bridge.unidic_resource as unidic_resource

    def unidic_required() -> None:
        raise AssertionError("a Hebrew run must not ask for UniDic")

    monkeypatch.setattr(unidic_resource, "require_registered_unidic", unidic_required)
    assert mining._ensure_runtime_ready({"language": "he"}) == initialized_bridge_home


def test_a_japanese_run_still_requires_unidic(monkeypatch: pytest.MonkeyPatch) -> None:
    import android_bridge.unidic_resource as unidic_resource

    def unidic_missing() -> None:
        raise BridgeProtocolError("unidic_not_registered", "missing")

    monkeypatch.setattr(unidic_resource, "require_registered_unidic", unidic_missing)
    for settings in ({}, {"language": "ja"}):
        with pytest.raises(BridgeProtocolError) as error:
            mining._ensure_runtime_ready(settings)
        assert error.value.code == "unidic_not_registered"


def test_a_run_in_a_language_waiting_for_its_data_is_refused_before_admission() -> None:
    _runtime_lane()
    with pytest.raises(BridgeProtocolError) as error:
        mining._ensure_runtime_ready({"language": "ar"})
    assert error.value.code == "language_unavailable"
    assert str(error.value) == "language_data_required"


def test_an_unvendored_language_is_refused_before_admission() -> None:
    _runtime_lane()
    with pytest.raises(BridgeProtocolError) as error:
        mining._ensure_runtime_ready({"language": "zh"})
    assert error.value.code == "unsupported_language"


# ---------------------------------------------------------------- composition


def test_hebrew_composition_threads_the_profile_through_every_service(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    initialized_bridge_home: Path,
) -> None:
    _runtime_lane()
    from anki_miner.languages.registry import get_profile

    profile = get_profile("he")
    config = _hebrew_config(tmp_path, use_known_words_db=True)
    captured = _compose(monkeypatch, config)

    assert captured["profile"] is profile
    parser = captured["subtitle_parser"]
    # Built by the profile's own factory: the Japanese branch injects none of these.
    assert type(parser._mined_form_policy).__name__ == "HebrewMinedForm"
    assert type(parser._reading_support).__name__ == "HebrewReadingSupport"
    assert parser._normalize is not None
    assert parser._tagger_language == "he"
    word_filter = captured["word_filter"]
    assert word_filter._script is profile.script
    assert word_filter._dedup_fold is profile.dedup_fold
    assert word_filter._sentence_annotation is False
    assert captured["definition_service"]._lookup is profile.lookup
    known_words = captured["known_word_db"]
    assert known_words._db_path == initialized_bridge_home / "known_words.he.db"
    assert known_words._language == "he"
    assert (initialized_bridge_home / "known_words.he.db").is_file()
    assert captured["stats_service"].language == "he"


def test_hebrew_word_lists_read_with_the_profile_ladder_and_fold(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    _runtime_lane()
    from anki_miner.languages.registry import get_profile

    profile = get_profile("he")
    whitelist = tmp_path / "whitelist.txt"
    # cp1255 is the Windows Hebrew codepage: the ja/UTF-8 default rejects it.
    whitelist.write_bytes("ספר\n".encode("cp1255"))
    config = _hebrew_config(tmp_path, use_whitelist=True, whitelist_path=str(whitelist))
    service = _compose(monkeypatch, config)["word_list_service"]

    assert service._encodings == profile.import_encodings
    assert service._dedup_fold is profile.dedup_fold
    assert service._script_check is not None
    assert service.is_whitelisted("ספר")


def test_japanese_composition_keeps_the_known_words_file_and_language(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    initialized_bridge_home: Path,
) -> None:
    _runtime_lane()
    import anki_miner.services.subtitle_parser as subtitle_parser

    class JapaneseParser:
        # The S1a tagger needs an installed UniDic; the composition does not.
        def __init__(self, config: object, **lookups: object) -> None:
            self.tagger = object()

    monkeypatch.setattr(subtitle_parser, "SubtitleParserService", JapaneseParser)
    paths = AndroidPaths(initialized_bridge_home, tmp_path / "cache", tmp_path / "native")
    config = map_config_settings({}, paths).engine_config
    captured = _compose(monkeypatch, config)

    assert captured["known_word_db"]._db_path == initialized_bridge_home / "known_words.db"
    assert captured["known_word_db"]._language == "ja"
    assert captured["stats_service"].language == "ja"
    assert "profile" not in captured
    assert isinstance(captured["subtitle_parser"], JapaneseParser)
    assert captured["definition_service"]._lookup is None


# ---------------------------------------------------------------- expression audio ladder


class _RecordingPack:
    pack_id = "recording"

    def __init__(self) -> None:
        self.ladders: list[list[tuple[str, str]]] = []

    def fetch(self, mined_form: str, reading: str, cancelled_check: object = None) -> None:
        return None

    def fetch_candidates(self, candidates: list[tuple[str, str]], cancelled_check: object = None) -> None:
        self.ladders.append(list(candidates))
        return None


def test_the_chain_answers_candidates_for_with_the_profile_ladder() -> None:
    _runtime_lane()
    from anki_miner.languages.registry import get_profile
    from anki_miner.orchestration.audio_stage import _candidate_ladder

    profile = get_profile("he")
    chain = mining._ExpressionAudioSourceChain([], candidates=profile.audio.candidates)
    word = SimpleNamespace(mined_form="ספר", expression_reading="סֵפֶר")

    ladder = _candidate_ladder(chain, word)

    assert ladder == profile.audio.candidates(word)
    # The vocalised reading leads: the Hebrew ladder, not the kana one.
    assert ladder[0] == ("ספר", "סֵפֶר")


def test_a_hebrew_audio_stage_fetches_the_hebrew_ladder_end_to_end() -> None:
    """The engine's own AudioStage probes the chain's TYPE and feeds packs the profile ladder."""
    _runtime_lane()
    from anki_miner.languages.registry import get_profile
    from anki_miner.orchestration.audio_stage import _candidate_ladder

    pack = _RecordingPack()
    profile = get_profile("he")
    chain = mining._ExpressionAudioSourceChain([pack], candidates=profile.audio.candidates)
    word = SimpleNamespace(mined_form="ספר", expression_reading="סֵפֶר")

    assert chain.fetch_candidates(_candidate_ladder(chain, word)) is None
    assert pack.ladders == [profile.audio.candidates(word)]


def test_the_japanese_chain_keeps_the_engine_ladder() -> None:
    _runtime_lane()
    from anki_miner.services.audio_fetch_common import expression_audio_candidates

    chain = mining._ExpressionAudioSourceChain([])
    word = SimpleNamespace(mined_form="チップ", expression_reading="ちっぷ", lemma="チップ", lemma_reading="ちっぷ")
    assert chain.candidates_for(word) == expression_audio_candidates(word)


def test_the_built_chain_carries_the_active_profile_ladder(tmp_path: Path) -> None:
    _runtime_lane()
    from anki_miner.languages.registry import get_profile

    config = _hebrew_config(
        tmp_path,
        anki_fields={"expression_audio": "WordAudio"},
        expression_audio_chain=[{"kind": "pack", "pack_id": "absent-pack"}],
    )
    chain = mining._build_expression_audio_source_chain(config)
    assert chain is not None
    assert chain._candidates is get_profile("he").audio.candidates
    chain.close()


# ---------------------------------------------------------------- curation "mark known"


def _hebrew_word(surface: str) -> SimpleNamespace:
    return SimpleNamespace(
        surface=surface,
        lemma=surface,
        mined_form=surface,
        sentence=f"{surface}.",
        start_time=0.0,
        end_time=1.0,
        duration=1.0,
    )


def test_marked_known_words_land_in_the_run_languages_own_database(
    initialized_bridge_home: Path,
) -> None:
    """Desktop ``_mining_tab_base`` writes curation marks to the active language's file, keyed by its fold."""
    _runtime_lane()
    import json
    import sqlite3

    from android_bridge.jobs import JobRegistry, KnownWordsTarget
    from android_bridge.protocol import encode_message
    from anki_miner.languages.registry import get_profile

    db_path = initialized_bridge_home / "known_words.he.db"
    db_path.unlink(missing_ok=True)
    registry = JobRegistry()
    handle = registry.begin()
    emitted = threading.Event()
    request: dict[str, object] = {}
    returned: list[object] = []

    def emit(raw: str) -> None:
        request.update(json.loads(raw))
        emitted.set()

    pointed = "סֵפֶר"  # vocalised: the Hebrew fold strips the points
    thread = threading.Thread(
        target=lambda: returned.append(
            registry.await_curation(
                handle.run_id,
                [_hebrew_word(pointed), _hebrew_word("ילד")],
                emit,
                known_words_target=KnownWordsTarget(db_path, "he"),
            )
        )
    )
    thread.start()
    assert emitted.wait(1)
    payload = request["payload"]
    ids = [candidate["candidateId"] for candidate in payload["candidates"]]
    registry.resolve_curation(
        encode_message(
            "curation.response",
            {
                "runId": handle.run_id,
                "requestId": payload["requestId"],
                "selection": [{"candidateId": ids[1]}],
                "knownCandidateIds": [ids[0]],
            },
        )
    )
    thread.join(1)
    registry.finish(handle.run_id)

    rows = sqlite3.connect(db_path).execute("SELECT lemma, source FROM known_words").fetchall()
    assert rows == [(get_profile("he").dedup_fold(pointed), "user")]
    db_path.unlink()


def test_the_run_hands_curation_its_languages_known_words_target(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    initialized_bridge_home: Path,
) -> None:
    _runtime_lane()
    from android_bridge.jobs import KnownWordsTarget

    config = _hebrew_config(tmp_path)
    assert mining._known_words_target(config) == KnownWordsTarget(initialized_bridge_home / "known_words.he.db", "he")


# ---------------------------------------------------------------- reading loader seams


def test_a_hebrew_novel_is_decoded_and_split_with_the_hebrew_seams(tmp_path: Path) -> None:
    """Desktop ``reading_queue_worker.load_reading_source``: the run's ladder, rules and parser seams."""
    _runtime_lane()
    import android_bridge.reading_mining as reading_mining
    from android_bridge.protocol import encode_message

    job = tmp_path / "reading-job-v1-a"
    job.mkdir()
    source = job / "book.txt"
    # cp1255, the Windows Hebrew codepage: not UTF-8, so only the Hebrew ladder decodes it.
    source.write_bytes("הילד קרא ספר. הוא אהב אותו.".encode("cp1255"))
    request = reading_mining._parse_request(
        encode_message(
            "mining.reading.run",
            {
                "sourceKind": "txt",
                "sourcePath": str(source),
                "imageArchivePath": None,
                "seriesName": None,
                "cacheDir": str(tmp_path),
                "nativeLibraryDir": "/native",
                "configSnapshot": {"settings": {"language": "he", "anki_note_type": "Basic"}},
            },
        )
    )
    config = _hebrew_config(tmp_path)
    loader_kwargs = reading_mining._reading_loader_kwargs(config)

    from anki_miner.languages.registry import get_profile

    profile = get_profile("he")
    assert loader_kwargs["encodings"] == profile.import_encodings
    assert loader_kwargs["rules"] is profile.sentence_rules
    assert loader_kwargs["normalize"] is profile.normalize

    document = reading_mining._load_document(request, lambda: False, loader_kwargs=loader_kwargs)
    assert [unit.text for unit in document.units] == ["הילד קרא ספר.", "הוא אהב אותו."]


def test_a_japanese_reading_run_keeps_the_pre_transition_loader_call(tmp_path: Path) -> None:
    import android_bridge.reading_mining as reading_mining

    config = SimpleNamespace(known_words_db_path=tmp_path / "known_words.db")
    assert reading_mining._reading_loader_kwargs(config) == {}


# ---------------------------------------------------------------- subtitle cues


_HEBREW_SRT = "1\n00:00:01,000 --> 00:00:02,500\nהילד קרא ספר.\n\n2\n00:00:04,000 --> 00:00:05,000\nשלום\n"


def _cues(payload: dict[str, object]) -> dict[str, object]:
    import json

    from android_bridge import boundary
    from android_bridge.protocol import encode_message

    return json.loads(boundary.dispatch(encode_message("subtitle.cues", payload)))


def test_hebrew_workbench_cues_need_no_japanese_tokenizer(tmp_path: Path) -> None:
    """A ja display parser needs UniDic; a Hebrew user's cue view must not."""
    _runtime_lane()
    subtitle = tmp_path / "episode.srt"
    subtitle.write_text(_HEBREW_SRT, encoding="utf-8")

    hebrew = _cues({"runId": None, "subtitlePath": str(subtitle), "language": "he"})

    assert hebrew["type"] == "subtitle.cues.result", hebrew
    assert [cue["text"] for cue in hebrew["payload"]["cues"]] == ["הילד קרא ספר.", "שלום"]
    assert hebrew["payload"]["cues"][0]["start"] == 1.0


def test_workbench_cues_refuse_an_unavailable_language(tmp_path: Path) -> None:
    _runtime_lane()
    subtitle = tmp_path / "episode.srt"
    subtitle.write_text(_HEBREW_SRT, encoding="utf-8")

    response = _cues({"runId": None, "subtitlePath": str(subtitle), "language": "zh"})

    assert response["payload"]["code"] == "unsupported_language"


def test_run_owned_cues_refuse_another_language(tmp_path: Path) -> None:
    _runtime_lane()
    from android_bridge.definitions import clear_run_dictionaries, register_run_dictionaries

    run_id = "run_" + "7" * 32
    subtitle = tmp_path / "episode.srt"
    subtitle.write_text(_HEBREW_SRT, encoding="utf-8")
    register_run_dictionaries(run_id, _hebrew_config(tmp_path))
    try:
        mismatched = _cues({"runId": run_id, "subtitlePath": str(subtitle), "language": "ja"})
        matched = _cues({"runId": run_id, "subtitlePath": str(subtitle), "language": "he"})
    finally:
        clear_run_dictionaries(run_id)

    assert mismatched["payload"]["code"] == "invalid_subtitle_cues_request"
    assert matched["type"] == "subtitle.cues.result"


# ---------------------------------------------------------------- audio track auto pick


def _streams() -> list[object]:
    import anki_miner.utils.audio_track_detector as detector

    def stream(index: int, tag: str, default: bool) -> object:
        return detector.AudioStream(
            global_index=index + 1,
            audio_index=index,
            language_tag=tag,
            title_tag=None,
            codec="aac",
            channels=2,
            is_default=default,
        )

    return [stream(0, "jpn", True), stream(1, "heb", False), stream(2, "iw", True)]


def _tracks(monkeypatch: pytest.MonkeyPatch, **extra: object) -> dict[str, object]:
    import json

    import anki_miner.utils.audio_track_detector as detector
    from android_bridge import boundary
    from android_bridge.protocol import encode_message

    monkeypatch.setattr(detector, "list_audio_streams", lambda *_args, **_kwargs: _streams())
    payload = {"videoPath": "/videos/ep1.mkv", "nativeLibraryDir": "/native", **extra}
    return json.loads(boundary.dispatch(encode_message("media.audiotracks", payload)))


def test_audio_track_auto_pick_follows_the_mining_language(monkeypatch: pytest.MonkeyPatch) -> None:
    _runtime_lane()
    # The default-disposition Hebrew track, tagged with the legacy "iw" code.
    assert _tracks(monkeypatch, language="he")["payload"]["autoAudioIndex"] == 2
    assert _tracks(monkeypatch, language="ja")["payload"]["autoAudioIndex"] == 0
    assert _tracks(monkeypatch)["payload"]["autoAudioIndex"] == 0
    assert _tracks(monkeypatch, language="th")["payload"]["autoAudioIndex"] is None


def test_audio_tracks_refuse_an_unavailable_language(monkeypatch: pytest.MonkeyPatch) -> None:
    _runtime_lane()
    response = _tracks(monkeypatch, language="zh")
    assert response["payload"]["code"] == "unsupported_language"


# ---------------------------------------------------------------- curation definition pane


def test_the_curation_pane_uses_the_run_languages_lookup_ladder(tmp_path: Path) -> None:
    _runtime_lane()
    from android_bridge import definitions
    from anki_miner.languages.registry import get_profile

    service = definitions._build_service(_hebrew_config(tmp_path))
    try:
        assert service._lookup is get_profile("he").lookup
    finally:
        service.close()


# ---------------------------------------------------------------- device-voice word audio


def test_a_hebrew_chain_keeps_the_device_voice_in_its_place(tmp_path: Path) -> None:
    _runtime_lane()
    from anki_miner.config import AudioSourceEntry

    config = _hebrew_config(
        tmp_path,
        expression_audio_chain=[
            {"kind": "pack", "pack_id": "forvo-he"},
            {"kind": "android_tts", "enabled": False},
        ],
    )
    assert config.expression_audio_chain == (
        AudioSourceEntry(kind="pack", pack_id="forvo-he"),
        AudioSourceEntry(kind="android_tts", enabled=False),
    )


@pytest.mark.parametrize("language", ["he", "ar", "id", "th", "fa"])
def test_the_profiles_synthetic_default_becomes_the_device_voice(tmp_path: Path, language: str) -> None:
    _runtime_lane()
    from anki_miner.config import AudioSourceEntry

    paths = AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native")
    config = map_config_settings({"language": language, "anki_note_type": "Basic"}, paths).engine_config
    assert config.expression_audio_chain == (AudioSourceEntry(kind="android_tts"),)


@pytest.mark.parametrize(
    "chain",
    [
        [{"kind": "android_tts"}, {"kind": "android_tts", "enabled": False}],
        [{"kind": "android_tts", "pack_id": "x"}],
    ],
    ids=["duplicate", "pack-id"],
)
def test_a_malformed_device_voice_entry_is_refused(tmp_path: Path, chain: list[object]) -> None:
    _runtime_lane()
    with pytest.raises(BridgeProtocolError) as error:
        _hebrew_config(tmp_path, expression_audio_chain=chain)
    assert error.value.code == "invalid_config_field"


def test_the_built_chain_speaks_with_the_device_voice_after_the_packs(tmp_path: Path) -> None:
    _runtime_lane()
    from android_bridge.word_audio import AndroidWordAudioFetcher

    config = _hebrew_config(
        tmp_path,
        anki_fields={"expression_audio": "WordAudio"},
        expression_audio_chain=[{"kind": "android_tts"}],
    )
    chain = mining._build_expression_audio_source_chain(
        config,
        tts_callbacks=object(),
        run_id="run_00000000000000000000000000000000",
    )
    assert chain is not None
    (member,) = chain._fetchers
    assert isinstance(member, AndroidWordAudioFetcher)
    assert member.media_name("ספר", "").startswith("androidtts_he_")
    chain.close()

    # Without the run's callbacks (a test double, a pre-language caller) the
    # entry builds nothing rather than a source that cannot speak.
    silent = mining._build_expression_audio_source_chain(config)
    assert silent is not None
    assert silent._fetchers == ()
    silent.close()


def test_the_processor_hands_the_runs_callbacks_to_the_device_voice(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    _runtime_lane()
    import anki_miner.orchestration.episode_processor as episode_processor
    from android_bridge.word_audio import AndroidWordAudioFetcher

    monkeypatch.setattr(episode_processor, "EpisodeProcessor", _CapturedProcessor)
    config = _hebrew_config(
        tmp_path,
        anki_fields={"expression_audio": "WordAudio"},
        expression_audio_chain=[{"kind": "android_tts"}],
    )
    adapters = SimpleNamespace(
        presenter=SimpleNamespace(show_warning=lambda _message: None),
        cancel_event=threading.Event(),
        callbacks=object(),
        run_id="run_00000000000000000000000000000000",
    )
    processor = mining._build_processor(config, adapters, object())
    chain = processor.kwargs["expression_audio_fetcher"]
    assert [type(member) for member in chain._fetchers] == [AndroidWordAudioFetcher]
    chain.close()
