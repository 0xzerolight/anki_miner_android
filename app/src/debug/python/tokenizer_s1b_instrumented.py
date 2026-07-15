"""Debug-only end-to-end S1b instrumentation harness."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from android_bridge.tokenizer_contract import UNIDIC_FEATURE_FIELDS
from android_bridge.unidic_resource import register_unidic
from tokenizer_instrumented_selection import acquire_tagger_for_instrumentation


def _golden_feature(value: Any) -> Any:
    """Canonical goldens collapse MeCab's explicit missing sentinel to null."""

    return None if value == "*" else value


def _actual_token(token: object) -> dict[str, Any]:
    feature = getattr(token, "feature")
    return {
        "surface": getattr(token, "surface"),
        "features": {
            name: _golden_feature(getattr(feature, name))
            for name in UNIDIC_FEATURE_FIELDS
        },
        "is_unknown": getattr(token, "is_unk"),
        "offsets": {
            "codepoint_start": getattr(token, "codepoint_start"),
            "codepoint_end": getattr(token, "codepoint_end"),
            "utf16_start": getattr(token, "utf16_start"),
            "utf16_end": getattr(token, "utf16_end"),
        },
    }


def _is_mapped(path: Path, maps: str) -> bool:
    expected = path.resolve()
    for line in maps.splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) != 6 or not fields[5].startswith("/"):
            continue
        if fields[5].endswith(" (deleted)"):
            continue
        try:
            if Path(fields[5]).resolve() == expected:
                return True
        except OSError:
            continue
    return False


def run(golden_json: str, dicdir: str) -> str:
    """Verify the complete Python -> Kotlin -> JNI -> MeCab path."""

    document = json.loads(golden_json)
    expected_hash = document["provenance"]["data"]["assets_sha256"]["unidic_dicdir"]
    registration = register_unidic(
        dicdir,
        resource_id=f"golden-unidic-{expected_hash[:16]}",
        expected_tree_sha256=expected_hash,
    )
    tagger, tagger_path, selected_backend = acquire_tagger_for_instrumentation(
        "s1b", registration
    )
    cases = document["cases"]["tokenization"]
    unknown_count = 0
    astral_utf16: tuple[int, int] | None = None

    if tuple(document["unidic_feature_fields"]) != UNIDIC_FEATURE_FIELDS:
        raise AssertionError("golden UniDic field order differs from the bridge")
    for case in cases:
        engine_tokens = tagger(case["text"])
        if case["id"] == "astral-oov-offsets":
            unknown_tokens = [
                token for token in engine_tokens if getattr(token, "is_unk")
            ]
            if len(unknown_tokens) != 1:
                raise AssertionError(
                    "astral-oov-offsets must contain exactly one unknown token"
                )
            oov = unknown_tokens[0]
            if oov.feature.pos3 != "*" or oov.feature.lForm is not None:
                raise AssertionError(
                    "S1b collapsed an explicit UniDic star or invented an absent field"
                )
        actual = [_actual_token(token) for token in engine_tokens]
        expected = case["tokens"]
        if actual != expected:
            raise AssertionError(
                f"S1b Android parity mismatch in {case['id']}: "
                f"expected={json.dumps(expected, ensure_ascii=False)} "
                f"actual={json.dumps(actual, ensure_ascii=False)}"
            )
        for token in actual:
            if len(token["features"]) != len(UNIDIC_FEATURE_FIELDS):
                raise AssertionError("S1b did not expose all 26 UniDic fields")
            if token["is_unknown"]:
                unknown_count += 1
                if case["id"] == "astral-oov-offsets":
                    offsets = token["offsets"]
                    astral_utf16 = (
                        offsets["utf16_start"],
                        offsets["utf16_end"],
                    )

    if astral_utf16 != (1, 7):
        raise AssertionError(f"astral OOV UTF-16 span is {astral_utf16!r}")
    maps = Path("/proc/self/maps").read_text(encoding="utf-8")
    sys_dic_mapped = _is_mapped(registration.sys_dic, maps)
    matrix_mapped = _is_mapped(registration.dicdir / "matrix.bin", maps)
    if not sys_dic_mapped or not matrix_mapped:
        raise AssertionError("MeCab dictionary files are not memory-mapped")

    return json.dumps(
        {
            "case_count": len(cases),
            "dictionary_sha256": registration.tree_sha256,
            "feature_field_count": len(UNIDIC_FEATURE_FIELDS),
            "matrix_mapped": matrix_mapped,
            "oov_utf16_end": astral_utf16[1],
            "oov_utf16_start": astral_utf16[0],
            "selected_backend": selected_backend,
            "sys_dic_mapped": sys_dic_mapped,
            "tagger_path": tagger_path,
            "unknown_count": unknown_count,
        },
        sort_keys=True,
    )
