"""Persian: catalog pins, the data pack, the tokenizer, scoped defaults and a card field.

The hazm tables are installed from ``fixtures/fa/`` (desktop's own fixture tables,
copied with provenance) through ``resource.languagedata.install``, with the pin
pointed at the fixture archive, so nothing here touches the network. Persian
characters are written as escapes or read from the fixtures; this file carries no
invisible character of its own.
"""

from __future__ import annotations

import hashlib
import json
import zipfile
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path

import pytest
from catalog_completeness import assert_catalog_complete

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("requests", reason="runtime dependency lane: the pack installer imports the downloader")

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "fa"
ZWNJ = "\N{ZERO WIDTH NON-JOINER}"
#: raftan ("to go") and its present stem ro-: the smoke sentence's verb.
RAFTAN = "\N{ARABIC LETTER REH}\N{ARABIC LETTER FEH}\N{ARABIC LETTER TEH}\N{ARABIC LETTER NOON}"
RO = "\N{ARABIC LETTER REH}\N{ARABIC LETTER WAW}"
MI_RAVAM = f"\N{ARABIC LETTER MEEM}\N{ARABIC LETTER FARSI YEH}{ZWNJ}{RO}\N{ARABIC LETTER MEEM}"


def _corpus() -> list[dict]:
    lines = (FIXTURES / "tokens.jsonl").read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line and not line.startswith("#")]


@pytest.fixture
def home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, initialized_bridge_home: Path) -> Path:
    del initialized_bridge_home
    import android_bridge.language_data as language_data
    from anki_miner.config import paths

    files = tmp_path / "files"
    files.mkdir()
    monkeypatch.setattr(paths, "ANKI_MINER_HOME", files)
    monkeypatch.setattr(language_data, "require_initialized", lambda: str(files))
    return files


@pytest.fixture
def installed(home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[Path]:
    """The fixture tables installed through the bridge op, as Kotlin's verified download would be."""

    import android_bridge.language_data as language_data
    from android_bridge import boundary
    from android_bridge.protocol import decode_envelope, encode_message
    from android_bridge.resource_catalog import load_resource_catalog
    from anki_miner.languages.fa import script as fa_script
    from anki_miner.languages.fa import tokenizer as fa_tokenizer
    from anki_miner.languages.tagger_provider import evict
    from anki_miner.services import language_pack_installer

    (entry,) = load_resource_catalog("fa").language_data
    archive = tmp_path / "download.part"
    with zipfile.ZipFile(archive, "w") as bundle:
        for name in entry.install.sentinels:
            bundle.write(FIXTURES / name, entry.install.member_prefix + name)
        # Outside the prefix, like the wheel's own package code: never selected.
        bundle.writestr("hazm/__init__.py", b"raise SystemExit\n")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    pinned = replace(entry, archive=replace(entry.archive, sha256=digest, size_bytes=archive.stat().st_size))
    real = language_pack_installer.load_pack("fa")
    (component,) = real.components
    pack = replace(real, components=(replace(component, universal=replace(component.universal, sha256=digest)),))
    monkeypatch.setattr(language_pack_installer, "load_pack", lambda code: pack if code == "fa" else None)
    monkeypatch.setattr(language_data, "find_catalog_resource", lambda _id: ("fa", pinned))
    # Building a lexicon arms module state the engine keeps for the process: put it back after.
    monkeypatch.setattr(fa_script, "FA_SEPARATE_MI_HOOK", fa_script.FA_SEPARATE_MI_HOOK)
    monkeypatch.setattr(fa_tokenizer, "_ACTIVE_LEXICON", fa_tokenizer._ACTIVE_LEXICON)
    evict("fa")

    raw = boundary.dispatch(
        encode_message(
            "resource.languagedata.install",
            {"operationId": "fa-data", "resourceId": entry.resource_id, "archivePath": str(archive)},
        )
    )
    assert decode_envelope(raw, expected_type="resource.languagedata.installed").payload["language"] == "fa"
    yield home
    evict("fa")


def test_every_desktop_catalog_row_is_pinned_or_excluded(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    assert_catalog_complete(
        "fa",
        pinned={
            "wty-fa-en": "wty-fa-en-2026.09.20",
            "opensubtitles-fa": "opensubtitles-fa-2018",
        },
        excluded={},
    )


def test_the_profile_loads_and_is_registered(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import get_profile
    from anki_miner.languages.registry import available_languages

    assert "fa" in available_languages()
    profile = get_profile("fa")
    assert profile.code == "fa"
    assert profile.content_style.direction == "rtl"


def test_the_data_pack_is_missing_until_installed_then_found(home: Path, request: pytest.FixtureRequest) -> None:
    from android_bridge.languages import get_profile, unavailable_reason_code
    from anki_miner.services.language_pack_installer import component_path

    assert component_path("fa", "hazm_data") is None
    assert unavailable_reason_code(get_profile("fa")) == "language_data_required"

    request.getfixturevalue("installed")

    assert component_path("fa", "hazm_data") == home / "language_packs" / "fa" / "hazm_data"
    assert unavailable_reason_code(get_profile("fa")) is None


def test_the_tagger_tokenises_the_smoke_sentence(installed: Path) -> None:
    del installed
    from android_bridge.languages import get_profile
    from anki_miner.languages.tagger_provider import get_tagger

    profile = get_profile("fa")
    tagger = get_tagger("fa")
    tokens = [token for token in tagger(profile.normalize(profile.smoke_sentence)) if token.feature.pos1 != "PUNCT"]

    assert len(tokens) == 6
    assert (tokens[-1].surface, tokens[-1].feature.lemma) == (MI_RAVAM, RAFTAN)


@pytest.mark.parametrize("row", _corpus(), ids=lambda row: row["note"][:40])
def test_tokens_match_the_desktop_corpus(installed: Path, row: dict) -> None:
    del installed
    from android_bridge.languages import get_profile
    from anki_miner.languages.fa.morphology import PersianMinedForm
    from anki_miner.languages.tagger_provider import get_tagger

    tagger = get_tagger("fa")  # first: building the lexicon arms the mi- split normalise relies on
    normalized = get_profile("fa").normalize(row["line"])
    assert normalized == row["normalized"], row["note"]

    produced = [token for token in tagger(normalized) if token.feature.pos1 != "PUNCT"]
    policy = PersianMinedForm()
    actual = [
        {
            "surface": token.surface,
            "lemma": token.feature.lemma,
            "pos1": token.feature.pos1,
            "pos2": token.feature.pos2,
            "mined": policy.mined_form(token.feature.pos1, token.feature.lemma, token.feature.lemma, token.surface),
        }
        for token in produced
    ]
    expected = [{key: token[key] for key in ("surface", "lemma", "pos1", "pos2", "mined")} for token in row["tokens"]]
    assert actual == expected, row["note"]
    for token, want in zip(produced, row["tokens"], strict=True):
        if want.get("surface_formal"):
            assert token.feature.surface_formal == want["surface_formal"], token.surface


def test_switching_to_persian_applies_its_scoped_defaults(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import base_config, get_profile
    from anki_miner.languages.fa.script import FA_SUBTITLE_REGEX

    profile = get_profile("fa")
    config = base_config("fa")

    assert config.language == "fa"
    for name, value in profile.scoped_defaults.items():
        assert getattr(config, name) == value, name
    assert config.use_subtitle_regex_filter is True
    assert config.subtitle_regex_filter == FA_SUBTITLE_REGEX
    assert config.downloader_subtitle_langs == "fa"
    assert config.anki_fields["present_stem"] == ""


def test_the_present_stem_reaches_the_note(installed: Path, tmp_path: Path) -> None:
    """Tokenizer morph -> the profile's render hook -> ``build_note`` under the mapped field name."""

    del installed
    from android_bridge.anki_adapter import _note_builder_kwargs
    from android_bridge.languages import base_config, get_profile, profile_parser
    from anki_miner.models import CardPayload, MediaData
    from anki_miner.services.anki_note_builder import build_note

    profile = get_profile("fa")
    config = base_config("fa")
    config = replace(config, anki_fields={**dict(config.anki_fields), "present_stem": "PresentStem"})
    subtitle = tmp_path / "episode.srt"
    subtitle.write_text(f"1\n00:00:01,000 --> 00:00:03,000\n{profile.smoke_sentence}\n", encoding="utf-8")

    words = profile_parser(config).parse_subtitle_file(subtitle)
    (verb,) = [word for word in words if word.mined_form == RAFTAN]
    extra_fields: dict[str, str] = {}
    for hook in profile.render_hooks:
        extra_fields.update(hook.render(verb, config=config))
    assert extra_fields == {"present_stem": RO}

    built = build_note(
        CardPayload(word=verb, media=MediaData(), definition="to go", extra_fields=extra_fields),
        config,
        set(),
        **_note_builder_kwargs(config),
    )

    assert built.note["fields"]["PresentStem"] == RO
    assert 'dir="rtl"' in built.note["fields"][config.anki_fields["word"]]
