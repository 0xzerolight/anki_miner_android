"""Android: spaCy models and pymorphy3 dictionaries load by path from downloaded data.

Decision 2: the engines ship in the APK and only data is downloaded. Desktop
imports a model package from its pack root on ``sys.path``. Android never puts
``filesDir`` on ``sys.path`` and never downloads code, so the pack manifests
(``languages/<code>/pack.py`` overlays) extract a model without its
``__init__.py``, and this module does what that ``__init__`` would:
``spacy.util.load_model_from_init_py`` on the extracted directory. That function
reads only the ``meta.json`` beside the path it is given, so the result is the
pipeline desktop's ``import_module(package).load`` builds.

Two models need more than their data:

- ``hu_core_news_md`` names factories its package registers at import. Those
  modules ship in the APK (``languages/hu/huspacy_components``).
- ru and uk lemmatise through pymorphy3, whose analyser spaCy builds during the
  load. The dictionaries are found through pymorphy3's own
  ``PYMORPHY2_DICT_PATH`` override, set for that load only. Tagger builds are
  serialised by ``tagger_provider``'s lock.

A model or dictionary that is not downloaded raises ``ImportError``, which
``tagger_provider`` turns into its handled ``ValueError``, as desktop does for an
install without the language's pack.
"""

from __future__ import annotations

import os
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from pathlib import Path
from typing import Any

#: pymorphy3's dictionary-path override (``MorphAnalyzer.DICT_PATH_ENV_VARIABLE``).
PYMORPHY_DICT_PATH_VARIABLE = "PYMORPHY2_DICT_PATH"
#: The model whose pipeline needs huspacy's component modules registered first.
HUNGARIAN_MODEL = "hu_core_news_md"


def model_language(package: str) -> str:
    """The mining language whose pack declares *package*."""

    from anki_miner.languages import AVAILABLE_LANGUAGES
    from anki_miner.services.language_pack_installer import load_pack

    for code in AVAILABLE_LANGUAGES:
        pack = load_pack(code)
        if pack is not None and any(component.import_name == package for component in pack.components):
            return code
    raise ImportError(f"No language pack declares {package}")


def downloaded_component(code: str, import_name: str) -> Path:
    """The extracted directory of *code*'s *import_name*; ImportError when it is not downloaded."""

    from anki_miner.services.language_pack_installer import component_path

    directory = component_path(code, import_name)
    if directory is None:
        raise ImportError(f"{import_name} is not downloaded")
    return directory


def pymorphy_dictionary_component(code: str) -> str | None:
    """The pymorphy3 dictionary package *code*'s pack declares, if any (ru, uk)."""

    from anki_miner.services.language_pack_installer import load_pack

    name = f"pymorphy3_dicts_{code}"
    pack = load_pack(code)
    if pack is None or not any(component.import_name == name for component in pack.components):
        return None
    return name


@contextmanager
def _pymorphy_dictionaries(code: str) -> Iterator[None]:
    name = pymorphy_dictionary_component(code)
    if name is None:
        yield
        return
    previous = os.environ.get(PYMORPHY_DICT_PATH_VARIABLE)
    os.environ[PYMORPHY_DICT_PATH_VARIABLE] = str(downloaded_component(code, name) / "data")
    try:
        yield
    finally:
        if previous is None:
            os.environ.pop(PYMORPHY_DICT_PATH_VARIABLE, None)
        else:
            os.environ[PYMORPHY_DICT_PATH_VARIABLE] = previous


def load_downloaded_model(package: str, *, exclude: Sequence[str]) -> Any:
    """Load *package*'s pipeline from its downloaded data, minus *exclude*."""

    from spacy.util import load_model_from_init_py

    code = model_language(package)
    directory = downloaded_component(code, package)
    if package == HUNGARIAN_MODEL:
        from anki_miner.languages.hu import huspacy_components  # noqa: F401  (registers the factories)
    with _pymorphy_dictionaries(code):
        return load_model_from_init_py(directory / "__init__.py", exclude=list(exclude))
