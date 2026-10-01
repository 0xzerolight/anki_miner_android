"""The spaCy family on Android: engines in the APK, models and dictionaries downloaded as data.

Decision 2. Desktop imports a model package from a pack root on ``sys.path``;
Android extracts only the model's data (the ``languages/<code>/pack.py``
overlays exclude its Python members) and ``languages/_spaced/android_models``
loads it by path. huspacy's three component modules ship in the APK, and the
pymorphy3 dictionaries reach ru/uk's lemmatizer through pymorphy3's own path
override. The parity tests compare the Android tagger with desktop's own
(``export_spacy_tokens.py``) on every sentence of the fixture; they skip when the
pinned data is not cached (``fetch_language_data.py``).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest
from language_data_fixtures import (
    assert_tagger_matches,
    install_language_data,
    install_sentinels,
    language_data_home,
)

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
pytest.importorskip("spacy", reason="runtime dependency lane: the spaCy family ships in the APK")

FIXTURES = Path(__file__).parent / "fixtures"
SPACY_LANGUAGES = (
    "en", "ca", "de", "pt", "fr", "es", "it", "nl", "nb", "ro", "el",
    "fi", "hu", "hr", "sv", "pl", "lt", "da", "sl", "ru", "uk",
)  # fmt: skip
#: Every Python member of the pinned archives, read from the wheels: what a download must never write.
_CODE_MEMBERS = {
    "hu_core_news_md": ("__init__.py", "edit_tree_lemmatizer.py", "lemma_postprocessing.py", "lookup_lemmatizer.py"),
    "pymorphy3_dicts_ru": ("__init__.py", "version.py"),
    "pymorphy3_dicts_uk": ("__init__.py", "version.py"),
}


class _Loaded(Exception):
    """Raised by a stand-in loader once it has seen what the real one would load."""


def _data_components(code: str) -> list:
    from android_bridge.languages import DOWNLOADABLE_DATA_COMPONENTS
    from anki_miner.services.language_pack_installer import load_pack

    return [
        component
        for component in load_pack(code).components
        if (code, component.import_name) in DOWNLOADABLE_DATA_COMPONENTS
    ]


@pytest.mark.parametrize("code", SPACY_LANGUAGES)
def test_a_spacy_language_downloads_its_data_and_nothing_else(initialized_bridge_home: Path, code: str) -> None:
    del initialized_bridge_home
    components = _data_components(code)
    names = sorted(component.import_name for component in components)
    assert names == sorted(
        [f"{code}_core_web_sm" if code == "en" else f"{code}_core_news_{'md' if code == 'hu' else 'sm'}"]
        + ([f"pymorphy3_dicts_{code}"] if code in ("ru", "uk") else [])
    )
    for component in components:
        spec = component.universal
        assert spec.exclude == _CODE_MEMBERS.get(component.import_name, ("__init__.py",)), component.import_name
        assert not spec.root_members, component.import_name
        assert not any(name.endswith((".py", ".pyc", ".so")) for name in component.sentinels)
        if "_core_" in component.import_name:
            # The model's own meta.json replaces __init__.py: load_model_from_init_py reads it first.
            assert component.sentinels[0] == "meta.json"


def test_a_model_belongs_to_the_language_whose_pack_declares_it(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from anki_miner.languages._spaced.android_models import model_language

    assert model_language("ru_core_news_sm") == "ru"
    assert model_language("hu_core_news_md") == "hu"
    assert model_language("nb_core_news_sm") == "nb"
    with pytest.raises(ImportError):
        model_language("xx_core_news_sm")


def test_a_language_whose_model_is_not_downloaded_has_no_tagger(initialized_bridge_home: Path, tmp_path: Path) -> None:
    del initialized_bridge_home
    from android_bridge import languages
    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.tagger_provider import get_tagger

    with language_data_home(tmp_path / "home"):
        assert languages.unavailable_reason_code(get_profile("en")) == "language_data_required"
        with pytest.raises(ValueError, match="en_core_web_sm is not downloaded"):
            get_tagger("en")


def test_the_model_loads_by_path_from_its_extracted_directory(
    initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    del initialized_bridge_home
    import spacy.util
    from android_bridge import languages
    from anki_miner.languages._spaced.tokenizer import load_spacy_model
    from anki_miner.languages.registry import get_profile

    seen = {}

    def loader(init_file: object, *, exclude: list[str]) -> None:
        seen.update(path=Path(str(init_file)), exclude=exclude)
        raise _Loaded

    monkeypatch.setattr(spacy.util, "load_model_from_init_py", loader)
    path_before = list(sys.path)
    with language_data_home(tmp_path / "home") as home:
        install_sentinels("en", home)
        assert languages.unavailable_reason_code(get_profile("en")) is None
        with pytest.raises(_Loaded):
            load_spacy_model("en_core_web_sm", keep_parser=False)

    assert seen == {
        "path": home / "language_packs" / "en" / "en_core_web_sm" / "__init__.py",
        "exclude": ["ner", "senter", "parser"],
    }
    assert sys.path == path_before
    assert "en_core_web_sm" not in sys.modules


@pytest.mark.parametrize("previous", [None, "/elsewhere"])
def test_pymorphy_finds_the_dictionaries_by_path_during_the_load_only(
    initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, previous: str | None
) -> None:
    del initialized_bridge_home
    import spacy.util
    from anki_miner.languages._spaced.android_models import PYMORPHY_DICT_PATH_VARIABLE, load_downloaded_model

    if previous is None:
        monkeypatch.delenv(PYMORPHY_DICT_PATH_VARIABLE, raising=False)
    else:
        monkeypatch.setenv(PYMORPHY_DICT_PATH_VARIABLE, previous)
    seen = {}

    def loader(init_file: object, *, exclude: list[str]) -> None:
        seen["path"] = os.environ.get(PYMORPHY_DICT_PATH_VARIABLE)
        raise _Loaded

    monkeypatch.setattr(spacy.util, "load_model_from_init_py", loader)
    with language_data_home(tmp_path / "home") as home:
        install_sentinels("uk", home)
        with pytest.raises(_Loaded):
            load_downloaded_model("uk_core_news_sm", exclude=[])

    assert seen["path"] == str(home / "language_packs" / "uk" / "pymorphy3_dicts_uk" / "data")
    assert os.environ.get(PYMORPHY_DICT_PATH_VARIABLE) == previous


def test_hungarian_registers_huspacy_factories_before_its_model_loads(
    initialized_bridge_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    del initialized_bridge_home
    import spacy.util
    from anki_miner.languages._spaced.android_models import load_downloaded_model

    seen = {}

    def loader(init_file: object, *, exclude: list[str]) -> None:
        names = ("hu.lookup_lemmatizer", "trainable_lemmatizer_v2", "hu.lemma_smoother")
        seen.update({name: spacy.util.registry.has("factories", name) for name in names})
        raise _Loaded

    monkeypatch.setattr(spacy.util, "load_model_from_init_py", loader)
    with language_data_home(tmp_path / "home") as home:
        install_sentinels("hu", home)
        with pytest.raises(_Loaded):
            load_downloaded_model("hu_core_news_md", exclude=[])

    assert seen == {"hu.lookup_lemmatizer": True, "trainable_lemmatizer_v2": True, "hu.lemma_smoother": True}


# One language per loader branch: a plain model, pymorphy3 dictionaries, huspacy components.
@pytest.mark.parametrize("code", ["en", "ru", "hu"])
def test_the_android_tagger_matches_desktop_on_downloaded_data(
    initialized_bridge_home: Path, tmp_path_factory: pytest.TempPathFactory, code: str
) -> None:
    del initialized_bridge_home
    with language_data_home(tmp_path_factory.mktemp(f"{code}-home")) as home:
        install_language_data(code, home)
        assert assert_tagger_matches(code, FIXTURES / code / "tokens.jsonl") > 20
        written = [path for path in (home / "language_packs").rglob("*") if path.is_file()]
    assert written
    assert not [path for path in written if path.suffix in (".py", ".pyc", ".so")]
