"""Debug-only end-to-end S1a parity probe."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from android_bridge.tokenizer_contract import UNIDIC_FEATURE_FIELDS
from android_bridge.unidic_resource import register_unidic
from tokenizer_instrumented_selection import acquire_tagger_for_instrumentation


def _serialized(token: object) -> dict[str, Any]:
    feature = getattr(token, "feature")
    return {
        "surface": getattr(token, "surface"),
        "features": {
            name: None if (value := getattr(feature, name)) == "*" else value
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


def _mapped(path: Path, maps: str) -> bool:
    expected = path.resolve()
    for line in maps.splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) == 6 and fields[5].startswith("/"):
            try:
                if Path(fields[5].removesuffix(" (deleted)")).resolve() == expected:
                    return True
            except OSError:
                pass
    return False


def run(golden_json: str, dicdir: str, native_library_dir: str) -> str:
    document = json.loads(golden_json)
    expected_hash = document["provenance"]["data"]["assets_sha256"]["unidic_dicdir"]
    registration = register_unidic(
        dicdir,
        resource_id=f"golden-unidic-{expected_hash[:16]}",
        expected_tree_sha256=expected_hash,
    )
    tagger, tagger_path, selected_backend = acquire_tagger_for_instrumentation(
        "s1a", registration
    )
    unknown_count = 0
    astral = None
    raw_astral_oov = None
    for case in document["cases"]["tokenization"]:
        raw_tokens = list(tagger(case["text"]))
        actual = [_serialized(token) for token in raw_tokens]
        if actual != case["tokens"]:
            raise AssertionError(f"S1a parity mismatch in {case['id']}")
        for raw_token, token in zip(raw_tokens, actual, strict=True):
            if token["is_unknown"]:
                unknown_count += 1
                if case["id"] == "astral-oov-offsets":
                    astral = (token["offsets"]["utf16_start"], token["offsets"]["utf16_end"])
                    feature = getattr(raw_token, "feature")
                    raw_astral_oov = (feature.pos3, feature.lForm)
    if astral != (1, 7):
        raise AssertionError(f"astral OOV UTF-16 span is {astral!r}")
    if raw_astral_oov != ("*", None):
        raise AssertionError(
            "astral OOV must preserve literal pos3 '*' separately from absent lForm"
        )
    maps = Path("/proc/self/maps").read_text(encoding="utf-8")
    for path in (registration.sys_dic, registration.dicdir / "matrix.bin"):
        if not _mapped(path, maps):
            raise AssertionError(f"external dictionary file is not mapped: {path.name}")
        if str(path).startswith(native_library_dir):
            raise AssertionError("dictionary mapped from native library directory")
    return json.dumps(
        {
            "case_count": len(document["cases"]["tokenization"]),
            "dictionary_sha256": registration.tree_sha256,
            "feature_field_count": len(UNIDIC_FEATURE_FIELDS),
            "unknown_count": unknown_count,
            "raw_oov_lform_is_none": raw_astral_oov[1] is None,
            "raw_oov_pos3": raw_astral_oov[0],
            "selected_backend": selected_backend,
            "tagger_path": tagger_path,
        },
        sort_keys=True,
    )
