"""Guard the Kotlin mirror of the engine's language-scoped config fields.

``LanguageScope.ENGINE_FIELDS`` restates ``LANGUAGE_SCOPED_FIELDS`` from the vendored
``languages/switching.py``. Kotlin's language switch parks exactly the settings that feed one of
those fields (``LanguageScopeTest`` measures that against the mirror), so a field the engine scopes
and the mirror lacks would silently leak from one language into another. An ``engine.lock`` bump
that scopes a formerly global field is exactly when the mirror goes stale.

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


if __name__ == "__main__":
    unittest.main()
