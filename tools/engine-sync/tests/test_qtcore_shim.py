from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


def _load_qtcore():
    path = Path(__file__).parents[1] / "overrides/PyQt6/QtCore.py"
    spec = importlib.util.spec_from_file_location("android_qtcore_shim", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class QtCoreShimTests(unittest.TestCase):
    def test_plain_and_numerus_translation(self) -> None:
        qtcore = _load_qtcore()
        self.assertEqual(qtcore.QCoreApplication.translate("ctx", "Ready"), "Ready")
        self.assertEqual(
            qtcore.QCoreApplication.translate("ctx", "%n card(s)", "", 3), "3 card(s)"
        )

    def test_positional_substitution_matches_qt_helper_contract(self) -> None:
        qtcore = _load_qtcore()
        self.assertEqual(qtcore.substitute_args("%1 of %2 (%10)", 2, 5), "2 of 5 (%10)")


if __name__ == "__main__":
    unittest.main()
