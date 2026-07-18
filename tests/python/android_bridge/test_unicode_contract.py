from __future__ import annotations

import json
from pathlib import Path

import pytest
from android_bridge import unicode_contract

PROJECT_ROOT = Path(__file__).resolve().parents[3]
UNICODE_ROOT = PROJECT_ROOT / "tools" / "anki-contract" / "unicode" / "15.1.0"
MAX_CODE_POINT = 0x10FFFF


def _category_c_truth() -> bytearray:
    truth = bytearray(b"\x01") * (MAX_CODE_POINT + 1)
    pending: tuple[int, bool] | None = None
    for line in (UNICODE_ROOT / "UnicodeData.txt").read_text(encoding="ascii").splitlines():
        fields = line.split(";")
        code_point = int(fields[0], 16)
        name = fields[1]
        is_category_c = fields[2].startswith("C")
        if name.endswith(", First>"):
            assert pending is None
            pending = (code_point, is_category_c)
        elif name.endswith(", Last>"):
            assert pending is not None
            start, first_is_category_c = pending
            assert first_is_category_c == is_category_c
            truth[start : code_point + 1] = bytes([is_category_c]) * (code_point - start + 1)
            pending = None
        else:
            assert pending is None
            truth[code_point] = is_category_c
    assert pending is None
    return truth


def _whitespace_truth() -> bytearray:
    document = json.loads((UNICODE_ROOT / "python-3.13-isspace.json").read_text(encoding="utf-8"))
    assert document["pythonVersion"] == "3.13"
    assert document["unicodeVersion"] == "15.1.0"
    truth = bytearray(MAX_CODE_POINT + 1)
    for start, end in document["ranges"]:
        truth[start : end + 1] = b"\x01" * (end - start + 1)
    return truth


def _normalization_rows() -> list[tuple[str, str, str, str, str]]:
    rows: list[tuple[str, str, str, str, str]] = []
    for raw_line in (UNICODE_ROOT / "NormalizationTest.txt").read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line or line.startswith("@"):
            continue
        fields = [field.strip() for field in line.split(";")]
        assert len(fields) == 6 and not fields[-1]
        values = tuple("".join(chr(int(token, 16)) for token in field.split()) for field in fields[:5])
        rows.append(values)
    assert len(rows) == 19_074
    return rows


def test_scalar_category_c_and_python_whitespace_match_pinned_truth() -> None:
    category_c = _category_c_truth()
    whitespace = _whitespace_truth()
    mismatches: list[tuple[int, str]] = []

    for code_point in range(MAX_CODE_POINT + 1):
        is_scalar = not 0xD800 <= code_point <= 0xDFFF
        if unicode_contract.is_unicode_scalar(code_point) != is_scalar:
            mismatches.append((code_point, "scalar"))
        if is_scalar and unicode_contract.is_category_c(code_point) != bool(category_c[code_point]):
            mismatches.append((code_point, "category C"))
        if is_scalar and unicode_contract.is_python_whitespace(code_point) != bool(whitespace[code_point]):
            mismatches.append((code_point, "whitespace"))
        if len(mismatches) == 20:
            break

    assert mismatches == []
    for invalid in (-1, 0xD800, 0xDFFF, 0x110000, True):
        assert not unicode_contract.is_unicode_scalar(invalid)
        assert not unicode_contract.is_category_c(invalid)
        assert not unicode_contract.is_python_whitespace(invalid)


def test_complete_unicode_15_1_normalization_corpus() -> None:
    mismatches: list[tuple[int, int, bool, bool]] = []
    for row_number, values in enumerate(_normalization_rows(), start=1):
        c1, c2, c3, c4, c5 = values
        for column, (value, target) in enumerate(
            ((c1, c2), (c2, c2), (c3, c2), (c4, c4), (c5, c4)),
            start=1,
        ):
            expected = value == target
            actual = unicode_contract.is_nfc(value)
            if actual != expected:
                mismatches.append((row_number, column, expected, actual))
                if len(mismatches) == 20:
                    break
        if len(mismatches) == 20:
            break

    assert mismatches == []


def test_nontrivial_reordering_and_scalar_helpers() -> None:
    non_normalized = "\u1e0a\u0323"
    normalized = "\u1e0c\u0307"

    assert not unicode_contract.is_nfc(non_normalized)
    assert unicode_contract.is_nfc(normalized)
    assert unicode_contract.scalar_count("A😀") == 2
    assert unicode_contract.strict_utf8_length("A😀") == 5
    assert unicode_contract.has_leading_or_trailing_python_whitespace("\u001cA")
    assert not unicode_contract.has_leading_or_trailing_python_whitespace("A😀")


@pytest.mark.parametrize("value", ["\ud800", "A\udfff", "\ud800\udfff"])
def test_non_scalar_strings_are_rejected(value: str) -> None:
    assert unicode_contract.scalar_count(value) is None
    assert unicode_contract.strict_utf8_length(value) is None
    assert not unicode_contract.is_nfc(value)
    assert not unicode_contract.has_leading_or_trailing_python_whitespace(value)


def test_generated_runtime_has_no_host_unicode_table_dependency() -> None:
    source = Path(unicode_contract.__file__).read_text(encoding="utf-8")

    for forbidden in (
        "import unicodedata",
        "from unicodedata",
        ".isspace(",
        ".strip(",
    ):
        assert forbidden not in source
