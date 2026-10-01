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
CC_BY_NC_SA_3 = ("LICENSE-CC-BY-NC-SA-3.0.txt", "8812f83442fd0eca14eb0208988e190fdcbfebec58fa5459d3218edfdfdc5a32")
CC_BY_SA_4 = ("wiktionary-LICENSE.CC-BY-SA-4.0", "3b2890eacd851373001c4a14623458e3adaf1b1967939aa9c38a318e28d61c00")
GPL_3 = ("LICENSE-GPL-3.0.txt", "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986")
LGPL_LR = ("LICENSE-LGPL-LR.txt", "fcfeb5f3a67aa25d659d1162b4f4a9c52a92171d4df68a11d87c52e3af982a17")

#: pins.json component -> the texts desktop's ``licenses/<dir>/`` ships for it.
EXPECTED = {
    # licenses/calima-msa-r13/LICENSE
    "ar/calima_msa": (GPL_2_CALIMA,),
    # licenses/el_core_news_sm/{LICENSE,LICENSES_SOURCES}
    "el/el_core_news_sm": (
        CC_BY_NC_SA_3,
        ("el_core_news_sm-LICENSES_SOURCES", "a92aff7b31fdeffa221a769d6db23ebd5b91f42d5951f5063e376753a82ff807"),
    ),
    # licenses/it_core_news_sm/{LICENSE,LICENSES_SOURCES}
    "it/it_core_news_sm": (
        CC_BY_NC_SA_3,
        ("it_core_news_sm-LICENSES_SOURCES", "0e20be146e089dace24416abef19f7cc5c76732121a526127c054f62101df271"),
    ),
    # licenses/hu_core_news_md/{LICENSE,LICENSE.CC-BY-NC-SA-3.0}
    "hu/hu_core_news_md": (CC_BY_SA_4, CC_BY_NC_SA_3),
    # licenses/{ca,es,pl}_core_news_sm/COPYING.GPLv3
    "ca/ca_core_news_sm": (GPL_3,),
    "es/es_core_news_sm": (GPL_3,),
    "pl/pl_core_news_sm": (GPL_3,),
    # licenses/pymorphy3_dicts_uk/ carries no text: GPL-3.0, the app's own licence
    "uk/pymorphy3_dicts_uk": (GPL_3,),
    # licenses/fr_core_news_sm/LICENSE
    "fr/fr_core_news_sm": (LGPL_LR,),
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
