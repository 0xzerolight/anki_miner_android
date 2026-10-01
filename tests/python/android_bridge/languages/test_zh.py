"""Chinese, as the Cantonese engine vendors it: profile modules only, no engine.

yue's measure-word hook imports ``zh.render``, so the vendored tree carries the
zh profile package without its tokenizer, its pack manifest or the jieba,
pypinyin and OpenCC wheels. The registry still registers zh; the bridge reports
it unsupported, which no download can change. The Chinese wiring (D5) replaces
this module with the language's own tests.
"""

from __future__ import annotations

from pathlib import Path

import pytest

pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")


def test_chinese_is_listed_but_cannot_mine_without_its_engine(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    from android_bridge.languages import get_profile, unavailable_reason_code

    assert unavailable_reason_code(get_profile("zh")) == "language_unsupported"
