"""Downloaded language data whose terms ask for their notice: the licence text ships in the app.

Desktop ships ``licenses/<component>/`` with the application for this data even though the release
carries none of it ("This notice ships with the application regardless, because the application is
what delivers the model to the user"). The Android equivalent is ``app/src/main/assets/notices/``,
which the in-app Third-party notices screen lists in full. Each text is byte-identical to desktop's
copy at the ``engine.lock`` SHA (digests below), and NOTICE.md names the file.
"""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
NOTICES = REPO_ROOT / "app/src/main/assets/notices"
PINS = REPO_ROOT / "tools/language-data/pins.json"

GPL_2_CALIMA = ("calima-msa-r13-LICENSE", "7a71f85f07e46759dcc1c16dba64352d9932447759cf71ca3deae3729d85d23e")

#: pins.json component -> the texts desktop's ``licenses/<dir>/`` ships for it.
EXPECTED = {
    # licenses/calima-msa-r13/LICENSE
    "ar/calima_msa": (GPL_2_CALIMA,),
}


class LanguageDataNoticesTest(unittest.TestCase):
    def test_every_listed_component_is_pinned(self) -> None:
        components = json.loads(PINS.read_text(encoding="utf-8"))["components"]
        self.assertLessEqual(set(EXPECTED), set(components))

    def test_each_licence_text_ships_byte_identical_to_desktop(self) -> None:
        for component, texts in EXPECTED.items():
            for name, sha256 in texts:
                with self.subTest(component=component, notice=name):
                    path = NOTICES / name
                    self.assertTrue(path.is_file(), f"{path} is missing")
                    self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), sha256)

    def test_notice_md_names_each_text(self) -> None:
        notice = (REPO_ROOT / "NOTICE.md").read_text(encoding="utf-8")
        for name in sorted({name for texts in EXPECTED.values() for name, _sha256 in texts}):
            with self.subTest(notice=name):
                self.assertTrue(f"`{name}`" in notice, f"NOTICE.md does not name {name}")


if __name__ == "__main__":
    unittest.main()
