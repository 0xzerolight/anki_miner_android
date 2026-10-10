"""Korean: kiwipiepy ships in the APK, the Kiwi model is downloaded language data read by path.

The runtime lane installs ``kiwipiepy-model`` only because pip resolves it as a
kiwipiepy dependency; ``conftest`` hides the package (as the APK lacks it), so
every test here reaches the model the Android way: ``component_path("ko",
"kiwipiepy_model")`` under ``language_packs/``.
"""

from __future__ import annotations

import hashlib
import importlib.metadata
import io
import json
import tarfile
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("kiwipiepy", reason="runtime dependency lane: the Korean engine")

_FIXTURES = Path(__file__).parent / "fixtures" / "ko"


def _jsonl(name: str) -> list[dict]:
    lines = (_FIXTURES / name).read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line and not line.startswith("#")]


@pytest.fixture
def korean_home(initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[Path]:
    """An app home whose ``language_packs/ko/kiwipiepy_model`` is the lane's real model.

    Function-scoped: the home patch must end with the test that asked for it, or
    a later test that expects no model would see this one.
    """

    del initialized_bridge_home
    from anki_miner.config import paths
    from anki_miner.languages import tagger_provider

    model = Path(importlib.metadata.distribution("kiwipiepy-model").locate_file("kiwipiepy_model"))
    home = tmp_path / "korean-home"
    component = home / "language_packs" / "ko" / "kiwipiepy_model"
    component.parent.mkdir(parents=True)
    component.symlink_to(model, target_is_directory=True)
    monkeypatch.setattr(paths, "ANKI_MINER_HOME", home)
    tagger_provider.evict("ko")
    yield home
    tagger_provider.evict("ko")


def _tokens(text: str) -> list[list[str]]:
    from anki_miner.languages.tagger_provider import get_tagger

    return [[t.surface, t.feature.pos1, t.feature.pos2, t.feature.lemma] for t in get_tagger("ko").parse(text)]


def test_the_profile_loads_and_korean_is_vendored(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.languages.registry import available_languages, get_profile

    profile = get_profile("ko")

    assert "ko" in available_languages()
    assert (profile.code, profile.english_name, profile.content_style.direction) == ("ko", "Korean", "ltr")


def test_without_the_model_korean_waits_for_its_language_data(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from importlib.util import find_spec

    from android_bridge.languages import get_profile, unavailable_reason_code

    assert find_spec("kiwipiepy") is not None, "the engine ships in the APK"
    assert find_spec("kiwipiepy_model") is None, "the model is never an importable package on Android"
    assert unavailable_reason_code(get_profile("ko")) == "language_data_required"


def test_the_model_is_read_by_path_from_the_language_data(korean_home: Path) -> None:
    from android_bridge.languages import get_profile, unavailable_reason_code
    from anki_miner.languages.ko.tokenizer import resolve_model_path

    assert resolve_model_path() == str(korean_home / "language_packs" / "ko" / "kiwipiepy_model")
    assert unavailable_reason_code(get_profile("ko")) is None


def test_the_tagger_tokenises_the_smoke_sentence(korean_home: Path) -> None:
    del korean_home
    from anki_miner.languages.registry import get_profile

    tokens = _tokens(get_profile("ko").smoke_sentence)

    assert [surface for surface, *_ in tokens] == ["학생", "이", "밥", "을", "먹", "었", "어요", "."]


def test_the_smoke_sentence_matches_desktops_token_fixture(korean_home: Path) -> None:
    del korean_home
    from anki_miner.languages.registry import get_profile

    expected = [[row["surface"], row["pos1"], row["pos2"], row["lemma"]] for row in _jsonl("desktop_tokens.jsonl")]

    assert _tokens(get_profile("ko").smoke_sentence)[: len(expected)] == expected


def test_every_corpus_sentence_matches_desktops_tokenizer(korean_home: Path) -> None:
    """Irregular stems, z_coda, the honorific overrides, hanja, Latin and astral offsets."""

    del korean_home
    rows = _jsonl("tokens.jsonl")

    assert len(rows) == 14
    for row in rows:
        assert _tokens(row["sentence"]) == row["tokens"], row["sentence"]


def test_switching_to_korean_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import base_config
    from anki_miner.languages.ko import KO_SUBTITLE_REGEX
    from anki_miner.languages.ko.morphology import KO_ALLOWED_POS
    from anki_miner.languages.registry import get_profile

    config = base_config("ko")
    defaults = get_profile("ko").scoped_defaults

    assert config.language == "ko"
    assert config.use_subtitle_regex_filter is True
    assert config.subtitle_regex_filter == KO_SUBTITLE_REGEX == defaults["subtitle_regex_filter"]
    assert config.allowed_pos == KO_ALLOWED_POS
    assert config.anki_fields["hanja"] == ""
    assert config.anki_fields["expression_furigana"] == ""


def test_the_hanja_card_field_survives_to_the_note(initialized_bridge_home: Path, tmp_path: Path) -> None:
    import os

    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.config_map import AndroidPaths, map_config_settings
    from anki_miner.languages.profile import CARD_FRONT_KEY
    from anki_miner.languages.registry import get_profile
    from anki_miner.models import CardPayload, MediaData, TokenizedWord
    from anki_miner.services.anki_note_builder import build_note

    del initialized_bridge_home
    config = map_config_settings(
        {"language": "ko", "anki_note_type": "Basic", "anki_fields": {"hanja": "Hanja"}},
        AndroidPaths(Path(os.environ["ANKI_MINER_HOME"]), tmp_path / "cache", tmp_path / "native"),
    ).engine_config
    # A KRDICT hanja-keyed row, headword span kept and gloss trimmed as desktop's
    # test_ko_render_hooks.py does: the bold hangul headword is the word's card front.
    definition = (
        '<span class="gloss-sc-span" lang="ko"><span class="gloss-sc-span" lang="ko" style="font-weight: bold">'
        '한자</span><span class="gloss-sc-span" lang="ko"> 〔漢字〕</span></span>Chinese characters'
    )
    word = TokenizedWord(
        surface="漢字",
        lemma="漢字",
        reading="",
        sentence="漢字로 쓴다.",
        start_time=1.0,
        end_time=2.0,
        duration=1.0,
        definition_html=definition,
    )
    # EpisodeProcessor._apply_render_hooks stashes the definition on the word, then every hook of
    # the profile fills extra_fields.
    extra_fields: dict[str, str] = {}
    for hook in get_profile("ko").render_hooks:
        extra_fields.update(hook.render(word, config=config))

    built = build_note(
        CardPayload(word=word, media=MediaData(), definition=definition, extra_fields=extra_fields),
        config,
        set(),
        **_note_builder_kwargs(config),
    )

    # An all-Hanja word is carded under KRDICT's hangul headword, with the Hanja in its own
    # field (desktop 2b4c84fee); mined_form stays the Hanja for lookups and known words.
    assert extra_fields == {"hanja": "漢字", CARD_FRONT_KEY: "한자"}
    assert built.note["fields"]["Hanja"] == "漢字"
    assert built.note["fields"][config.anki_fields["word"]] == "한자"
    assert word.mined_form == "漢字"


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete("ko", pinned={"krdict-en": "krdict-en-1.0.0"}, excluded={})


def test_installing_the_pinned_model_entry_gives_kiwi_its_path(
    initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The catalog entry installs through the bridge op: data lands, the sdist's ``.py`` files do not."""

    import android_bridge.language_data as language_data
    from android_bridge import boundary
    from android_bridge.protocol import decode_envelope, encode_message
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.config import paths
    from anki_miner.services import language_pack_installer
    from anki_miner.services.language_pack_installer import component_path

    del initialized_bridge_home
    home = tmp_path / "files"
    home.mkdir()
    monkeypatch.setattr(paths, "ANKI_MINER_HOME", home)
    monkeypatch.setattr(language_data, "require_initialized", lambda: str(home))
    (entry,) = load_resource_catalog("ko").language_data
    prefix = entry.install.member_prefix
    members = {prefix + name: b"model" for name in entry.install.sentinels}
    members |= {prefix + "__init__.py": b"from ._version import *\n", prefix + "_version.py": b"x = 1\n"}
    members |= {"kiwipiepy_model-0.23.0/PKG-INFO": b"Name: kiwipiepy_model\n"}
    archive = tmp_path / "download.part"
    with tarfile.open(archive, "w:gz") as bundle:
        for name, content in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(content)
            bundle.addfile(info, io.BytesIO(content))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    pinned = replace(entry, archive=replace(entry.archive, sha256=digest, size_bytes=archive.stat().st_size))
    real = language_pack_installer.load_pack("ko")
    pack = replace(
        real,
        components=tuple(
            (
                replace(item, universal=replace(item.universal, sha256=digest))
                if item.import_name == entry.import_name
                else item
            )
            for item in real.components
        ),
    )
    monkeypatch.setattr(language_pack_installer, "load_pack", lambda code: pack if code == "ko" else None)
    monkeypatch.setattr(language_data, "find_catalog_resource", lambda _id: ("ko", pinned))
    assert component_path("ko", "kiwipiepy_model") is None

    raw = boundary.dispatch(
        encode_message(
            "resource.languagedata.install",
            {"operationId": "ko-model", "resourceId": entry.resource_id, "archivePath": str(archive)},
        )
    )

    assert decode_envelope(raw).payload["importName"] == "kiwipiepy_model"
    installed = component_path("ko", "kiwipiepy_model")
    assert installed == home / "language_packs" / "ko" / "kiwipiepy_model"
    assert sorted(path.name for path in installed.iterdir()) == sorted(entry.install.sentinels)
    assert entry.resource_id in language_data.installed_language_data(home)
