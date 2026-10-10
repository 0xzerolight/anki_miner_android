"""Guard the Kotlin mirror of the engine's language-scoped config fields.

``LanguageScope.ENGINE_FIELDS`` restates ``LANGUAGE_SCOPED_FIELDS`` from the vendored
``languages/switching.py``. Kotlin's language switch parks exactly the settings that feed one of
those fields (``LanguageScopeTest`` measures that against the mirror), so a field the engine scopes
and the mirror lacks would silently leak from one language into another. An ``engine.lock`` bump
that scopes a formerly global field is exactly when the mirror goes stale.

``BridgeJsonCodec`` restates two engine key sets the same way: ``SCOPED_DEFAULT_KEYS`` (the
``language.profiles`` scoped defaults, decoded with an exact key check, so a newly scoped field fails
every startup) and ``ANKI_FIELDS`` (the logical card-field keys a snapshot may map).

Both sides are read with ``ast``/text parsing rather than imported, so this runs in the secretless
host job without the engine's runtime dependencies.
"""

from __future__ import annotations

import ast
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
SWITCHING_PY = REPO_ROOT / "app/src/main/python/anki_miner/languages/switching.py"
SCOPE_KT = REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/settings/LanguageScope.kt"
CONFIG_PY = REPO_ROOT / "app/src/main/python/anki_miner/config/config.py"
LANGUAGES_PY = REPO_ROOT / "app/src/main/python/android_bridge/languages.py"
CODEC_KT = REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/engine/BridgeJsonCodec.kt"

KOTLIN_LIST = re.compile(r"val ENGINE_FIELDS: List<String> =\s*listOf\((?P<body>.*?)\)", re.DOTALL)
KOTLIN_STRING = re.compile(r'"(?P<value>[a-z_]+)"')


def _engine_fields() -> list[str]:
    module = ast.parse(SWITCHING_PY.read_text(encoding="utf-8"))
    for node in module.body:
        if (
            isinstance(node, ast.AnnAssign)
            and isinstance(node.target, ast.Name)
            and node.target.id == "LANGUAGE_SCOPED_FIELDS"
            and node.value is not None
        ):
            return list(ast.literal_eval(node.value))
    raise AssertionError("LANGUAGE_SCOPED_FIELDS not found in switching.py")


def _desktop_only_scoped_fields() -> set[str]:
    """``languages._DESKTOP_ONLY_SCOPED_FIELDS``: the scoped names config_map never accepts."""
    module = ast.parse(LANGUAGES_PY.read_text(encoding="utf-8"))
    for node in module.body:
        if (
            isinstance(node, ast.Assign)
            and [getattr(target, "id", None) for target in node.targets] == ["_DESKTOP_ONLY_SCOPED_FIELDS"]
            and isinstance(node.value, ast.Call)
        ):
            return set(ast.literal_eval(node.value.args[0]))
    raise AssertionError("_DESKTOP_ONLY_SCOPED_FIELDS not found in languages.py")


def _engine_anki_field_keys() -> set[str]:
    """The keys of ``AnkiMinerConfig().anki_fields``, read from its default factory."""
    module = ast.parse(CONFIG_PY.read_text(encoding="utf-8"))
    for node in ast.walk(module):
        if (
            isinstance(node, ast.AnnAssign)
            and isinstance(node.target, ast.Name)
            and node.target.id == "anki_fields"
            and isinstance(node.value, ast.Call)
        ):
            (factory,) = (keyword.value for keyword in node.value.keywords if keyword.arg == "default_factory")
            assert isinstance(factory, ast.Lambda) and isinstance(factory.body, ast.Dict)
            return {ast.literal_eval(key) for key in factory.body.keys if key is not None}
    raise AssertionError("AnkiMinerConfig.anki_fields not found in config.py")


def _kotlin_codec_set(name: str) -> set[str]:
    match = re.search(rf"private val {name} =\s*setOf\((?P<body>.*?)\)", CODEC_KT.read_text(encoding="utf-8"), re.DOTALL)
    if match is None:
        raise AssertionError(f"{name} not found in BridgeJsonCodec.kt")
    return {found.group("value") for found in KOTLIN_STRING.finditer(match.group("body"))}


def _kotlin_fields() -> list[str]:
    match = KOTLIN_LIST.search(SCOPE_KT.read_text(encoding="utf-8"))
    if match is None:
        raise AssertionError("ENGINE_FIELDS not found in LanguageScope.kt")
    return [found.group("value") for found in KOTLIN_STRING.finditer(match.group("body"))]


class LanguageScopedFieldsMirrorTest(unittest.TestCase):
    def test_kotlin_mirror_matches_the_vendored_tuple_in_order(self) -> None:
        engine = _engine_fields()
        self.assertTrue(engine, "no fields parsed from switching.py")
        self.assertEqual(engine, _kotlin_fields())

    def test_codec_scoped_default_keys_are_the_vendored_tuple_minus_the_desktop_only_names(self) -> None:
        desktop_only = _desktop_only_scoped_fields()
        self.assertEqual(desktop_only, {"downloader_subtitle_langs", "downloader_audio_lang"})
        self.assertEqual(set(_engine_fields()) - desktop_only, _kotlin_codec_set("SCOPED_DEFAULT_KEYS"))

    def test_codec_anki_fields_are_the_engine_field_map_keys(self) -> None:
        engine = _engine_anki_field_keys()
        self.assertIn("word", engine)
        self.assertEqual(engine, _kotlin_codec_set("ANKI_FIELDS"))


if __name__ == "__main__":
    unittest.main()
