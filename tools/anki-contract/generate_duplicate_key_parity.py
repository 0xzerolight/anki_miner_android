#!/usr/bin/env python3
"""Generate the duplicate-key parity corpus from the vendored engine's ``_strip_for_dedup``.

Kotlin ``DuplicateFirstFieldNormalizer`` re-derives the duplicate key the bridge sends with every
duplicate probe, and the run-state registry refuses a probe whose two keys disagree, which fails
the whole create batch. Each case records the vendored function's own key for one first-field
value, so ``DuplicateFirstFieldParityTest`` holds the Kotlin port to the engine instead of to a
hand-written expectation. ``tests/python/android_bridge/test_duplicate_first_field_corpus.py``
re-derives every key against the live engine in both pytest lanes, so an engine re-pin that
changes the key goes red there.

Run it with the runtime-lane interpreter, the device's CPython:

    "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests/bin/python" \\
        tools/anki-contract/generate_duplicate_key_parity.py \\
        app/src/test/resources/contracts/duplicate_first_field_v1.json
"""

from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unicodedata

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "app" / "src" / "main" / "python"))

ZWNJ = "‌"
PERSIAN = f"دانش{ZWNJ}آموز"

CASES: list[tuple[str, str]] = [
    # name, first-field value
    ("persian-zwnj-compound", PERSIAN),
    ("persian-zwnj-in-markup", f"<b>{PERSIAN}</b>[sound:clip_1000_0.mp3]"),
    ("zwj-emoji-sequence", "\U0001f9d1‍\U0001f393 vocab"),
    ("rlm-trailing-hebrew", "שלום‏"),
    ("lre-netflix-subtitle", "‪寮"),
    ("pdf-closing-embedding", "寮‬"),
    ("bom-leading", "﻿猫"),
    ("zero-width-space", "a​b"),
    ("soft-hyphen", "Wort­teil"),
    ("arabic-letter-mark", "؜كتاب"),
    ("word-joiner", "a⁠b"),
    ("bidi-isolates", "⁦abc⁩"),
    ("tag-sequence-flag", "\U0001f3f4\U000e0067\U000e0062\U000e0065\U000e006e\U000e0067\U000e007f"),
    ("escaped-numeric-lre", "&#8234;寮"),
    ("escaped-hex-zwnj", "a&#x200C;b"),
    ("escaped-named-zwnj", "a&zwnj;b"),
    ("escaped-named-lrm", "&lrm;abc"),
    ("format-only-field", "‌‍﻿"),
    ("format-around-whitespace", " ‏ a ‎ b "),
    ("format-between-base-and-mark", "e‌́"),
    ("format-splits-hangul-jamo", "ᄀ‌ᅡ"),
    ("control-character-kept", "a\u0007b"),
    ("html-entities-and-tags", "<div>&lt;猫&gt; &amp; 犬</div>"),
    ("furigana-brackets-kept", "食べる[たべる]"),
    ("whitespace-collapse", "\t猫　 犬\n"),
    ("nfc-composition", "Café"),
]


def _every_format_character() -> str:
    return "".join(
        chr(code_point) for code_point in range(sys.maxunicode + 1) if unicodedata.category(chr(code_point)) == "Cf"
    )


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: generate_duplicate_key_parity.py <output.json>")
    out = pathlib.Path(sys.argv[1])
    cases = [*CASES, ("every-format-character", f"a{_every_format_character()}b")]
    names = [name for name, _ in cases]
    if len(set(names)) != len(names):
        raise SystemExit("duplicate case name")
    with tempfile.TemporaryDirectory() as home:
        from android_bridge.bootstrap import initialize

        initialize(home)
        from anki_miner.services.anki_note_builder import _strip_for_dedup

        records = [{"name": name, "value": value, "key": _strip_for_dedup(value)} for name, value in cases]
    document = {
        "schema_version": 1,
        "description": (
            "First-field values and the vendored engine's _strip_for_dedup key for each. The Kotlin "
            "DuplicateFirstFieldParityTest must reproduce every key. Regenerate with "
            "tools/anki-contract/generate_duplicate_key_parity.py under the runtime-lane interpreter."
        ),
        "python_version": f"{sys.version_info.major}.{sys.version_info.minor}",
        "unicode_version": unicodedata.unidata_version,
        "cases": records,
    }
    # ASCII escapes keep every invisible character visible in review.
    out.write_text(json.dumps(document, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    changed = sum(1 for record in records if record["value"] != record["key"])
    print(f"{len(records)} cases -> {out} ({changed} change under normalization)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
