"""Guard the Kotlin mirror of the engine's field auto-map keyword table.

``AnkiFieldAutoMap.FIELD_KEYWORDS`` restates ``note_presets.FIELD_KEYWORDS`` so the Android
"auto-map" button proposes the same note-type fields desktop would. The table only grows with an
``engine.lock`` bump (``sentence_translation`` arrived that way), and nothing else notices when
the Kotlin copy falls behind: the key simply never auto-maps.

Both sides are read with ``ast``/text parsing rather than imported, so this runs in the secretless
host job without the engine's runtime dependencies.
"""

from __future__ import annotations

import ast
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
PRESETS_PY = REPO_ROOT / "app/src/main/python/anki_miner/services/note_presets.py"
AUTO_MAP_KT = REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/anki/provider/AnkiFieldAutoMap.kt"

_TABLE = re.compile(r"FIELD_KEYWORDS: Map<String, List<String>> =\s*linkedMapOf\((?P<body>.*?)\n\s*\)\n", re.DOTALL)
_ENTRY = re.compile(r'"(?P<key>[a-z_]+)"\s+to\s+listOf\((?P<keywords>[^)]*)\)')
_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')


def _engine_keywords() -> dict[str, list[str]]:
    module = ast.parse(PRESETS_PY.read_text(encoding="utf-8"))
    for node in module.body:
        if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            if node.target.id == "FIELD_KEYWORDS" and node.value is not None:
                return ast.literal_eval(node.value)
    raise AssertionError("FIELD_KEYWORDS not found in note_presets.py")


def _kotlin_keywords() -> dict[str, list[str]]:
    table = _TABLE.search(AUTO_MAP_KT.read_text(encoding="utf-8"))
    if table is None:
        raise AssertionError("FIELD_KEYWORDS linkedMapOf(...) not found in AnkiFieldAutoMap.kt")
    entries = {
        match.group("key"): _STRING.findall(match.group("keywords")) for match in _ENTRY.finditer(table.group("body"))
    }
    if not entries:
        raise AssertionError("no entries parsed from AnkiFieldAutoMap.FIELD_KEYWORDS")
    return entries


class FieldKeywordsMirrorTest(unittest.TestCase):
    def test_kotlin_table_equals_the_engine_table(self) -> None:
        self.assertEqual(_engine_keywords(), _kotlin_keywords())


if __name__ == "__main__":
    unittest.main()
