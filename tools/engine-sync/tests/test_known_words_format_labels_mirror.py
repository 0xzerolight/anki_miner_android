"""Guard the Kotlin mirror of the engine's known-word format keys.

``known_words_import.py`` documents that the importing dialog's label mapping must stay in
lockstep with ``FORMAT_KEYS``. A key with no Kotlin entry renders as a raw wire token
(``migaku_legacy``) in the import confirmation; a Kotlin entry with no key is a label nothing can
ever show. An ``engine.lock`` bump that adds a parser format is exactly when the mapping goes
stale.

Both sides are read with ``ast``/text parsing rather than imported, so this runs in the secretless
host job without the engine's runtime dependencies.
"""

from __future__ import annotations

import ast
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
IMPORT_PY = REPO_ROOT / "app/src/main/python/anki_miner/services/known_words_import.py"
FORMATS_KT = REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/settings/KnownWordsImportFormat.kt"

WIRE_VALUE = re.compile(r'^\s*[A-Z_]+\("(?P<wire>[a-z_]+)",\s*R\.string\.', re.MULTILINE)


def _engine_format_keys() -> set[str]:
    module = ast.parse(IMPORT_PY.read_text(encoding="utf-8"))
    for node in module.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "FORMAT_KEYS" for target in node.targets
        ):
            return set(ast.literal_eval(node.value))
    raise AssertionError("FORMAT_KEYS not found in known_words_import.py")


class KnownWordsFormatLabelMirrorTest(unittest.TestCase):
    def test_every_format_key_has_a_kotlin_label(self) -> None:
        kotlin = {match.group("wire") for match in WIRE_VALUE.finditer(FORMATS_KT.read_text(encoding="utf-8"))}
        self.assertTrue(kotlin, "no wire values parsed from KnownWordsImportFormat.kt")
        self.assertEqual(_engine_format_keys(), kotlin)


if __name__ == "__main__":
    unittest.main()
