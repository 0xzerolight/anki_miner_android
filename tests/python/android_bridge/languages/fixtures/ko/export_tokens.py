"""Write ``tokens.jsonl`` from desktop's own Korean tokenizer (the ko token-parity fixture).

Run with the runtime host-test venv (CPython 3.12.13, kiwipiepy 0.23.2 and
kiwipiepy-model 0.23.0 from ``requirements-runtime-host-test.lock``) against the
desktop checkout at the SHA in ``tools/engine-sync/engine.lock``::

    python export_tokens.py "$ANKI_MINER_DESKTOP_REPO" > tokens.jsonl

The model is passed by path, the way Android loads it. Desktop's
``services`` package imports ``PyQt6.QtCore``, which this venv lacks, so the
Android ``PyQt6`` shim is appended to ``sys.path`` behind the desktop checkout:
every ``anki_miner`` module still resolves from desktop.
"""

from __future__ import annotations

import importlib.metadata
import json
import subprocess
import sys
from pathlib import Path

#: The spike F3 device corpus: the profile's smoke sentence, irregular stems, a
#: colloquial z_coda, the three honorific overrides, hanja, Latin, and astral
#: characters for offset parity.
SENTENCES = (
    "학생이 밥을 먹었어요.",
    "어제는 날씨가 너무 추웠어요.",
    "그 사람 이름은 漢字로 쓴다.",
    "공부를 열심히 하고 있습니다.",
    "빨리 가자, 우리 집에서 먹었어욥!",
    "서울大 앞에서 커피 한 잔 했어.",
    "깨끗한 방에서 Netflix 봤어.",
    "할머니, 안녕히 계세요.",
    "아버지께서 주무세요.",
    "식후에 드세요.",
    "길을 걸어서 학교에 갔어요.",
    "그러나 할 수 있는 것은 없다.",
    "𠀀 한자 😀 정말 좋아요!",
    "노래를 불렀는데 아무도 안 들었어.",
)


def main() -> int:
    desktop = Path(sys.argv[1]).resolve()
    sys.path.insert(0, str(desktop))
    sys.path.append(str(Path(__file__).resolve().parents[6] / "app" / "src" / "main" / "python"))
    import anki_miner.languages.ko.tokenizer as tokenizer

    assert Path(tokenizer.__file__).is_relative_to(desktop), tokenizer.__file__

    model = importlib.metadata.distribution("kiwipiepy-model").locate_file("kiwipiepy_model")
    tokenizer.resolve_model_path = lambda: str(model)
    tagger = tokenizer.build_tagger()
    revision = subprocess.run(
        ["git", "-C", str(desktop), "rev-parse", "HEAD"], check=True, capture_output=True, text=True
    ).stdout.strip()
    print(f"# desktop {revision} anki_miner.languages.ko.tokenizer.build_tagger()")
    print(
        f"# kiwipiepy {importlib.metadata.version('kiwipiepy')}, kiwipiepy-model "
        f"{importlib.metadata.version('kiwipiepy-model')}, CPython {sys.version.split()[0]}"
    )
    print("# regenerate: tests/python/android_bridge/languages/fixtures/ko/export_tokens.py (docstring)")
    for sentence in SENTENCES:
        tokens = [[t.surface, t.feature.pos1, t.feature.pos2, t.feature.lemma] for t in tagger.parse(sentence)]
        print(json.dumps({"sentence": sentence, "tokens": tokens}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
