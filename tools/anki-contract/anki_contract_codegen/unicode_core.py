"""Generate pinned Unicode 15.1 validators for Python and Kotlin."""

from __future__ import annotations

import hashlib
import json
import sys
import unicodedata
from dataclasses import dataclass
from functools import cache
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, NoReturn, Sequence

from .core import (
    ContractError,
    _atomic_write,
    _open_repo,
    _read_regular_file,
)

UNICODE_VERSION = "15.1.0"
UNICODE_ROOT = PurePosixPath("tools/anki-contract/unicode/15.1.0")
MANIFEST_PATH = UNICODE_ROOT / "manifest.json"
WHITESPACE_PATH = UNICODE_ROOT / "python-3.13-isspace.json"
PYTHON_OUTPUT_PATH = PurePosixPath("app/src/main/python/android_bridge/unicode_contract.py")
KOTLIN_OUTPUT_PATH = PurePosixPath("app/src/main/kotlin/com/ankiminer/android/anki/generated/UnicodeContractV151.kt")

_MAX_CODE_POINT = 0x10FFFF
_RANGE_MASK = (1 << 21) - 1
_MAX_INPUT_BYTES = 4 * 1024 * 1024
_MAX_OUTPUT_BYTES = 2 * 1024 * 1024
_EXPECTED_FILES = frozenset(
    {
        "CompositionExclusions.txt",
        "DerivedNormalizationProps.txt",
        "LICENSE.txt",
        "NormalizationTest.txt",
        "PropList.txt",
        "ReadMe.txt",
        "UnicodeData.txt",
    }
)


@dataclass(frozen=True)
class UnicodeTables:
    category_c_ranges: tuple[tuple[int, int], ...]
    whitespace_ranges: tuple[tuple[int, int], ...]
    combining_ranges: tuple[tuple[int, int, int], ...]
    nfc_no_ranges: tuple[tuple[int, int], ...]
    nfc_maybe_ranges: tuple[tuple[int, int], ...]
    decompositions: tuple[tuple[int, tuple[int, ...]], ...]
    compositions: tuple[tuple[int, int, int], ...]


def _fail(message: str) -> NoReturn:
    raise ContractError(message)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _fail(f"Unicode input contains duplicate JSON key: {key}")
        result[key] = value
    return result


def _decode_json(raw: bytes, description: str) -> dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_object)
    except ContractError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        _fail(f"{description} is not strict UTF-8 JSON: {exc}")
    if type(value) is not dict:
        _fail(f"{description} must be a JSON object")
    return value


def _read_inputs(repo_fd: int) -> tuple[dict[str, bytes], tuple[tuple[int, int], ...]]:
    manifest_raw = _read_regular_file(
        repo_fd,
        MANIFEST_PATH,
        description="Unicode provenance manifest",
        max_bytes=128 * 1024,
    )
    manifest = _decode_json(manifest_raw, "Unicode provenance manifest")
    expected_manifest_keys = {
        "schemaVersion",
        "unicodeVersion",
        "retrievedOn",
        "licenseSpdx",
        "pythonWhitespace",
        "files",
    }
    if set(manifest) != expected_manifest_keys:
        _fail("Unicode provenance manifest has missing or unknown fields")
    if manifest["schemaVersion"] != 1 or manifest["unicodeVersion"] != UNICODE_VERSION:
        _fail("Unicode provenance manifest version is invalid")
    if manifest["retrievedOn"] != "2026-07-15" or manifest["licenseSpdx"] != "Unicode-3.0":
        _fail("Unicode provenance metadata drifted")

    entries = manifest["files"]
    if type(entries) is not dict or set(entries) != _EXPECTED_FILES:
        _fail("Unicode provenance manifest file inventory is invalid")

    inputs: dict[str, bytes] = {}
    for filename in sorted(_EXPECTED_FILES):
        entry = entries[filename]
        if type(entry) is not dict or set(entry) != {"url", "bytes", "sha256"}:
            _fail(f"Unicode provenance entry is invalid: {filename}")
        expected_url = (
            "https://www.unicode.org/license.txt"
            if filename == "LICENSE.txt"
            else f"https://www.unicode.org/Public/{UNICODE_VERSION}/ucd/{filename}"
        )
        if entry["url"] != expected_url:
            _fail(f"Unicode provenance URL drifted: {filename}")
        if type(entry["bytes"]) is not int or not 1 <= entry["bytes"] <= _MAX_INPUT_BYTES:
            _fail(f"Unicode provenance byte count is invalid: {filename}")
        if (
            type(entry["sha256"]) is not str
            or len(entry["sha256"]) != 64
            or any(character not in "0123456789abcdef" for character in entry["sha256"])
        ):
            _fail(f"Unicode provenance hash is invalid: {filename}")
        raw = _read_regular_file(
            repo_fd,
            UNICODE_ROOT / filename,
            description=f"Unicode input {filename}",
            max_bytes=_MAX_INPUT_BYTES,
        )
        if len(raw) != entry["bytes"]:
            _fail(f"Unicode input byte count drifted: {filename}")
        if hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            _fail(f"Unicode input hash drifted: {filename}")
        inputs[filename] = raw

    whitespace_entry = manifest["pythonWhitespace"]
    if type(whitespace_entry) is not dict or set(whitespace_entry) != {
        "path",
        "source",
        "bytes",
        "sha256",
    }:
        _fail("Python whitespace provenance is invalid")
    if whitespace_entry["path"] != WHITESPACE_PATH.name:
        _fail("Python whitespace provenance path drifted")
    if whitespace_entry["source"] != ("CPython 3.13 str.isspace() with unicodedata 15.1.0"):
        _fail("Python whitespace provenance source drifted")
    whitespace_raw = _read_regular_file(
        repo_fd,
        WHITESPACE_PATH,
        description="Python whitespace truth data",
        max_bytes=64 * 1024,
    )
    if (
        len(whitespace_raw) != whitespace_entry["bytes"]
        or hashlib.sha256(whitespace_raw).hexdigest() != whitespace_entry["sha256"]
    ):
        _fail("Python whitespace truth data drifted")
    whitespace = _parse_whitespace(whitespace_raw)

    readme = inputs["ReadMe.txt"].decode("utf-8")
    if "final data files" not in readme or f"Version {UNICODE_VERSION}" not in readme:
        _fail("Unicode ReadMe does not identify final 15.1.0 data")
    return inputs, whitespace


def _parse_whitespace(raw: bytes) -> tuple[tuple[int, int], ...]:
    value = _decode_json(raw, "Python whitespace truth data")
    if set(value) != {"schemaVersion", "pythonVersion", "unicodeVersion", "ranges"}:
        _fail("Python whitespace truth data has an invalid shape")
    if (
        value["schemaVersion"] != 1
        or value["pythonVersion"] != "3.13"
        or value["unicodeVersion"] != UNICODE_VERSION
        or type(value["ranges"]) is not list
    ):
        _fail("Python whitespace truth data has invalid metadata")
    ranges: list[tuple[int, int]] = []
    previous_end = -1
    for item in value["ranges"]:
        if type(item) is not list or len(item) != 2 or any(type(component) is not int for component in item):
            _fail("Python whitespace range is invalid")
        start, end = item
        if not 0 <= start <= end <= _MAX_CODE_POINT or start <= previous_end:
            _fail("Python whitespace ranges are not sorted and disjoint")
        ranges.append((start, end))
        previous_end = end

    if sys.version_info[:2] != (3, 13) or unicodedata.unidata_version != UNICODE_VERSION:
        _fail("Unicode generation requires Python 3.13 with Unicode 15.1.0")
    actual = tuple(_compress_boolean([chr(code_point).isspace() for code_point in range(_MAX_CODE_POINT + 1)]))
    if tuple(ranges) != actual:
        _fail("Python whitespace truth data does not match the pinned interpreter")
    return tuple(ranges)


def _parse_code_point_range(token: str, description: str) -> tuple[int, int]:
    components = token.strip().split("..")
    if len(components) not in {1, 2}:
        _fail(f"{description} has an invalid code-point range")
    try:
        start = int(components[0], 16)
        end = int(components[-1], 16)
    except ValueError:
        _fail(f"{description} has a non-hexadecimal code point")
    if not 0 <= start <= end <= _MAX_CODE_POINT:
        _fail(f"{description} has an out-of-range code point")
    return start, end


def _data_lines(raw: bytes, description: str) -> Iterable[tuple[int, str]]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        _fail(f"{description} is not UTF-8: {exc}")
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.split("#", 1)[0].strip()
        if line:
            yield line_number, line


def _parse_property_ranges(
    raw: bytes,
    description: str,
    property_name: str,
    property_value: str | None = None,
) -> tuple[tuple[int, int], ...]:
    ranges: list[tuple[int, int]] = []
    for line_number, line in _data_lines(raw, description):
        fields = [field.strip() for field in line.split(";")]
        if len(fields) < 2:
            _fail(f"{description}:{line_number} has too few fields")
        if fields[1] != property_name:
            continue
        if property_value is not None and (len(fields) < 3 or fields[2] != property_value):
            continue
        ranges.append(_parse_code_point_range(fields[0], f"{description}:{line_number}"))
    return _merge_ranges(ranges, description)


def _merge_ranges(ranges: Iterable[tuple[int, int]], description: str) -> tuple[tuple[int, int], ...]:
    ordered = sorted(ranges)
    merged: list[tuple[int, int]] = []
    for start, end in ordered:
        if merged and start <= merged[-1][1]:
            _fail(f"{description} contains overlapping ranges")
        if merged and start == merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], end)
        else:
            merged.append((start, end))
    return tuple(merged)


def _compress_boolean(values: Sequence[bool]) -> Iterable[tuple[int, int]]:
    start: int | None = None
    for index, value in enumerate(values):
        if value and start is None:
            start = index
        elif not value and start is not None:
            yield start, index - 1
            start = None
    if start is not None:
        yield start, len(values) - 1


def _compress_bytes(values: Sequence[int]) -> Iterable[tuple[int, int, int]]:
    start: int | None = None
    current = 0
    for index, value in enumerate(values):
        if value == 0:
            if start is not None:
                yield start, index - 1, current
                start = None
            continue
        if start is None:
            start = index
            current = value
        elif value != current:
            yield start, index - 1, current
            start = index
            current = value
    if start is not None:
        yield start, len(values) - 1, current


def _parse_unicode_data(
    raw: bytes,
) -> tuple[bytearray, bytearray, dict[int, tuple[int, ...]]]:
    category_c = bytearray(b"\x01") * (_MAX_CODE_POINT + 1)
    combining = bytearray(_MAX_CODE_POINT + 1)
    decompositions: dict[int, tuple[int, ...]] = {}
    pending_range: tuple[int, str, int, str] | None = None

    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError as exc:
        _fail(f"UnicodeData.txt is not ASCII: {exc}")
    for line_number, line in enumerate(text.splitlines(), start=1):
        fields = line.split(";")
        if len(fields) != 15:
            _fail(f"UnicodeData.txt:{line_number} does not have 15 fields")
        try:
            code_point = int(fields[0], 16)
            combining_class = int(fields[3])
        except ValueError:
            _fail(f"UnicodeData.txt:{line_number} has invalid numeric data")
        if not 0 <= code_point <= _MAX_CODE_POINT or not 0 <= combining_class <= 255:
            _fail(f"UnicodeData.txt:{line_number} has out-of-range data")
        category = fields[2]
        if len(category) != 2:
            _fail(f"UnicodeData.txt:{line_number} has an invalid category")
        name = fields[1]
        decomposition_field = fields[5]

        if name.endswith(", First>"):
            if pending_range is not None:
                _fail("UnicodeData.txt has nested First ranges")
            pending_range = (code_point, category, combining_class, decomposition_field)
            continue
        if name.endswith(", Last>"):
            if pending_range is None:
                _fail("UnicodeData.txt has a Last range without First")
            start, first_category, first_combining, first_decomposition = pending_range
            if (
                code_point < start
                or category != first_category
                or combining_class != first_combining
                or decomposition_field != first_decomposition
            ):
                _fail("UnicodeData.txt First/Last range metadata differs")
            for current in range(start, code_point + 1):
                category_c[current] = int(category.startswith("C"))
                combining[current] = combining_class
            pending_range = None
            continue
        if pending_range is not None:
            _fail("UnicodeData.txt First range is not immediately closed")

        category_c[code_point] = int(category.startswith("C"))
        combining[code_point] = combining_class
        if decomposition_field and not decomposition_field.startswith("<"):
            try:
                mapping = tuple(int(token, 16) for token in decomposition_field.split())
            except ValueError:
                _fail(f"UnicodeData.txt:{line_number} has invalid decomposition data")
            if not mapping or any(not 0 <= item <= _MAX_CODE_POINT for item in mapping):
                _fail(f"UnicodeData.txt:{line_number} has invalid decomposition data")
            decompositions[code_point] = mapping
    if pending_range is not None:
        _fail("UnicodeData.txt has an unterminated First range")
    return category_c, combining, decompositions


def _hangul_decomposition(code_point: int) -> tuple[int, ...] | None:
    s_base = 0xAC00
    s_count = 11172
    if not s_base <= code_point < s_base + s_count:
        return None
    l_base = 0x1100
    v_base = 0x1161
    t_base = 0x11A7
    n_count = 588
    t_count = 28
    s_index = code_point - s_base
    lead = l_base + s_index // n_count
    v = v_base + (s_index % n_count) // t_count
    t_index = s_index % t_count
    return (lead, v) if t_index == 0 else (lead, v, t_base + t_index)


def _build_tables(inputs: dict[str, bytes], whitespace: tuple[tuple[int, int], ...]) -> UnicodeTables:
    category_c, combining, raw_decompositions = _parse_unicode_data(inputs["UnicodeData.txt"])
    derived = inputs["DerivedNormalizationProps.txt"]
    nfc_no = _parse_property_ranges(derived, "DerivedNormalizationProps.txt", "NFC_QC", "N")
    nfc_maybe = _parse_property_ranges(derived, "DerivedNormalizationProps.txt", "NFC_QC", "M")
    full_exclusions = set()
    for start, end in _parse_property_ranges(
        derived,
        "DerivedNormalizationProps.txt",
        "Full_Composition_Exclusion",
    ):
        full_exclusions.update(range(start, end + 1))

    explicit_exclusions: set[int] = set()
    for line_number, line in _data_lines(inputs["CompositionExclusions.txt"], "CompositionExclusions.txt"):
        if ";" in line:
            _fail(f"CompositionExclusions.txt:{line_number} has an unexpected field")
        start, end = _parse_code_point_range(line, f"CompositionExclusions.txt:{line_number}")
        explicit_exclusions.update(range(start, end + 1))
    if not explicit_exclusions <= full_exclusions:
        _fail("CompositionExclusions.txt is not covered by derived full exclusions")

    ucd_whitespace = _parse_property_ranges(inputs["PropList.txt"], "PropList.txt", "White_Space")
    expected_python_whitespace = _merge_ranges([*ucd_whitespace, (0x1C, 0x1F)], "Python/UCD whitespace cross-check")
    if whitespace != expected_python_whitespace:
        _fail("Python whitespace differs from UCD White_Space plus U+001C..U+001F")

    @cache
    def expand(code_point: int) -> tuple[int, ...]:
        hangul = _hangul_decomposition(code_point)
        if hangul is not None:
            return hangul
        mapping = raw_decompositions.get(code_point)
        if mapping is None:
            return (code_point,)
        expanded: list[int] = []
        for child in mapping:
            expanded.extend(expand(child))
        return tuple(expanded)

    decompositions = tuple((code_point, expand(code_point)) for code_point in sorted(raw_decompositions))
    if any(len(mapping) > 255 for _, mapping in decompositions):
        _fail("Expanded canonical decomposition exceeds its packed length")

    compositions: list[tuple[int, int, int]] = []
    for composite, mapping in raw_decompositions.items():
        if len(mapping) != 2 or composite in full_exclusions:
            continue
        starter, combiner = mapping
        if combining[starter] != 0:
            _fail("Canonical composition starter has a non-zero combining class")
        compositions.append((starter, combiner, composite))
    compositions.sort()
    if len({(starter, combiner) for starter, combiner, _ in compositions}) != len(compositions):
        _fail("Canonical composition pairs are not unique")

    tables = UnicodeTables(
        category_c_ranges=tuple(_compress_boolean(category_c)),
        whitespace_ranges=whitespace,
        combining_ranges=tuple(_compress_bytes(combining)),
        nfc_no_ranges=nfc_no,
        nfc_maybe_ranges=nfc_maybe,
        decompositions=decompositions,
        compositions=tuple(compositions),
    )
    _validate_table_counts(tables)
    return tables


def _validate_table_counts(tables: UnicodeTables) -> None:
    expected = {
        "category C ranges": (len(tables.category_c_ranges), 712),
        "whitespace ranges": (len(tables.whitespace_ranges), 10),
        "combining ranges": (len(tables.combining_ranges), 388),
        "NFC_QC=No ranges": (len(tables.nfc_no_ranges), 73),
        "NFC_QC=Maybe ranges": (len(tables.nfc_maybe_ranges), 42),
        "decompositions": (len(tables.decompositions), 2061),
        "compositions": (len(tables.compositions), 941),
    }
    mismatches = [
        f"{name}: expected {wanted}, found {actual}" for name, (actual, wanted) in expected.items() if actual != wanted
    ]
    if mismatches:
        _fail("Unicode generated table counts drifted: " + "; ".join(mismatches))


def _pack_ranges(ranges: Sequence[tuple[int, int]]) -> tuple[int, ...]:
    return tuple((start << 21) | end for start, end in ranges)


def _pack_combining(ranges: Sequence[tuple[int, int, int]]) -> tuple[int, ...]:
    return tuple((start << 29) | (end << 8) | value for start, end, value in ranges)


def _pack_decompositions(
    decompositions: Sequence[tuple[int, tuple[int, ...]]],
) -> tuple[tuple[int, ...], tuple[int, ...]]:
    metadata: list[int] = []
    data: list[int] = []
    for code_point, mapping in decompositions:
        metadata.append((code_point << 32) | (len(data) << 8) | len(mapping))
        data.extend(mapping)
    return tuple(metadata), tuple(data)


def _pack_compositions(
    compositions: Sequence[tuple[int, int, int]],
) -> tuple[int, ...]:
    return tuple((starter << 42) | (combiner << 21) | composite for starter, combiner, composite in compositions)


def _render_python_tuple(name: str, values: Sequence[int], per_line: int) -> list[str]:
    lines = [f"{name}: tuple[int, ...] = ("]
    for index in range(0, len(values), per_line):
        lines.append("    " + ", ".join(str(value) for value in values[index : index + per_line]) + ",")
    lines.append(")")
    return lines


def generate_python(tables: UnicodeTables) -> bytes:
    decomposition_metadata, decomposition_data = _pack_decompositions(tables.decompositions)
    blocks = [
        _render_python_tuple("_CATEGORY_C_RANGES", _pack_ranges(tables.category_c_ranges), 4),
        _render_python_tuple("_WHITESPACE_RANGES", _pack_ranges(tables.whitespace_ranges), 4),
        _render_python_tuple("_COMBINING_RANGES", _pack_combining(tables.combining_ranges), 4),
        _render_python_tuple("_NFC_NO_RANGES", _pack_ranges(tables.nfc_no_ranges), 4),
        _render_python_tuple("_NFC_MAYBE_RANGES", _pack_ranges(tables.nfc_maybe_ranges), 4),
        _render_python_tuple("_DECOMPOSITION_METADATA", decomposition_metadata, 4),
        _render_python_tuple("_DECOMPOSITION_DATA", decomposition_data, 12),
        _render_python_tuple("_COMPOSITIONS", _pack_compositions(tables.compositions), 4),
    ]
    lines = [
        '"""Generated pinned Unicode 15.1 contract helpers; do not edit."""',
        "",
        "from __future__ import annotations",
        "",
        "_RANGE_MASK = (1 << 21) - 1",
        "",
    ]
    for index, block in enumerate(blocks):
        if index:
            lines.append("")
        lines.extend(block)
    lines.extend("""

def is_unicode_scalar(code_point: int) -> bool:
    return type(code_point) is int and 0 <= code_point <= 0x10FFFF and not 0xD800 <= code_point <= 0xDFFF


def _contains_range(ranges: tuple[int, ...], code_point: int) -> bool:
    low = 0
    high = len(ranges) - 1
    while low <= high:
        middle = (low + high) // 2
        packed = ranges[middle]
        start = packed >> 21
        if code_point < start:
            high = middle - 1
        elif code_point > (packed & _RANGE_MASK):
            low = middle + 1
        else:
            return True
    return False


def is_python_whitespace(code_point: int) -> bool:
    return is_unicode_scalar(code_point) and _contains_range(_WHITESPACE_RANGES, code_point)


def is_category_c(code_point: int) -> bool:
    return is_unicode_scalar(code_point) and _contains_range(_CATEGORY_C_RANGES, code_point)


def _scalar_values(value: str) -> list[int] | None:
    if not isinstance(value, str):
        return None
    values = [ord(character) for character in value]
    return values if all(is_unicode_scalar(code_point) for code_point in values) else None


def scalar_count(value: str) -> int | None:
    values = _scalar_values(value)
    return None if values is None else len(values)


def strict_utf8_length(value: str) -> int | None:
    values = _scalar_values(value)
    if values is None:
        return None
    return sum(1 if item <= 0x7F else 2 if item <= 0x7FF else 3 if item <= 0xFFFF else 4 for item in values)


def has_leading_or_trailing_python_whitespace(value: str) -> bool:
    values = _scalar_values(value)
    return bool(values) and (is_python_whitespace(values[0]) or is_python_whitespace(values[-1]))


def _combining_class(code_point: int) -> int:
    low = 0
    high = len(_COMBINING_RANGES) - 1
    while low <= high:
        middle = (low + high) // 2
        packed = _COMBINING_RANGES[middle]
        start = packed >> 29
        end = (packed >> 8) & _RANGE_MASK
        if code_point < start:
            high = middle - 1
        elif code_point > end:
            low = middle + 1
        else:
            return packed & 0xFF
    return 0


def _decomposition(code_point: int) -> tuple[int, ...] | None:
    if 0xAC00 <= code_point < 0xAC00 + 11172:
        index = code_point - 0xAC00
        leading = 0x1100 + index // 588
        vowel = 0x1161 + (index % 588) // 28
        trailing_index = index % 28
        return (leading, vowel) if trailing_index == 0 else (leading, vowel, 0x11A7 + trailing_index)
    low = 0
    high = len(_DECOMPOSITION_METADATA) - 1
    while low <= high:
        middle = (low + high) // 2
        packed = _DECOMPOSITION_METADATA[middle]
        candidate = packed >> 32
        if code_point < candidate:
            high = middle - 1
        elif code_point > candidate:
            low = middle + 1
        else:
            offset = (packed >> 8) & 0xFFFFFF
            length = packed & 0xFF
            return _DECOMPOSITION_DATA[offset : offset + length]
    return None


def _compose_pair(starter: int, combiner: int) -> int | None:
    leading_index = starter - 0x1100
    if 0 <= leading_index < 19 and 0x1161 <= combiner < 0x1161 + 21:
        return 0xAC00 + (leading_index * 21 + combiner - 0x1161) * 28
    syllable_index = starter - 0xAC00
    if 0 <= syllable_index < 11172 and syllable_index % 28 == 0 and 0x11A8 <= combiner < 0x11A7 + 28:
        return starter + combiner - 0x11A7
    key = (starter << 21) | combiner
    low = 0
    high = len(_COMPOSITIONS) - 1
    while low <= high:
        middle = (low + high) // 2
        packed = _COMPOSITIONS[middle]
        candidate = packed >> 21
        if key < candidate:
            high = middle - 1
        elif key > candidate:
            low = middle + 1
        else:
            return packed & _RANGE_MASK
    return None


def _normalized_nfc(values: list[int]) -> list[int]:
    ordered: list[int] = []
    for code_point in values:
        decomposition = _decomposition(code_point)
        for child in decomposition if decomposition is not None else (code_point,):
            ordered.append(child)
            child_class = _combining_class(child)
            if child_class:
                position = len(ordered) - 1
                while position > 0:
                    prior_class = _combining_class(ordered[position - 1])
                    if prior_class == 0 or prior_class <= child_class:
                        break
                    ordered[position - 1], ordered[position] = ordered[position], ordered[position - 1]
                    position -= 1
    if not ordered:
        return ordered
    result = [ordered[0]]
    starter_index = 0
    starter = ordered[0]
    last_class = _combining_class(starter)
    for code_point in ordered[1:]:
        current_class = _combining_class(code_point)
        composite = _compose_pair(starter, code_point) if last_class == 0 or last_class < current_class else None
        if composite is not None:
            result[starter_index] = composite
            starter = composite
        else:
            if current_class == 0:
                starter_index = len(result)
                starter = code_point
            result.append(code_point)
            last_class = current_class
    return result


def is_nfc(value: str) -> bool:
    values = _scalar_values(value)
    if values is None:
        return False
    maybe = False
    last_class = 0
    for code_point in values:
        if _contains_range(_NFC_NO_RANGES, code_point):
            return False
        current_class = _combining_class(code_point)
        if current_class and last_class > current_class:
            return False
        last_class = current_class
        maybe = maybe or _contains_range(_NFC_MAYBE_RANGES, code_point)
    return not maybe or _normalized_nfc(values) == values
""".strip("\n").splitlines())
    lines.append("")
    return "\n".join(lines).encode("utf-8")


def _render_kotlin_array(
    object_name: str,
    property_name: str,
    kind: str,
    values: Sequence[int],
    per_line: int,
) -> list[str]:
    suffix = "L" if kind == "longArrayOf" else ""
    lines = [f"    private object {object_name} {{", f"        val {property_name} = {kind}("]
    for index in range(0, len(values), per_line):
        rendered = ", ".join(f"{value}{suffix}" for value in values[index : index + per_line])
        lines.append(f"            {rendered},")
    lines.extend(["        )", "    }"])
    return lines


def generate_kotlin(tables: UnicodeTables) -> bytes:
    decomposition_metadata, decomposition_data = _pack_decompositions(tables.decompositions)
    blocks = [
        _render_kotlin_array("CategoryCData", "ranges", "longArrayOf", _pack_ranges(tables.category_c_ranges), 4),
        _render_kotlin_array("WhitespaceData", "ranges", "longArrayOf", _pack_ranges(tables.whitespace_ranges), 4),
        _render_kotlin_array("CombiningData", "ranges", "longArrayOf", _pack_combining(tables.combining_ranges), 4),
        _render_kotlin_array("NfcNoData", "ranges", "longArrayOf", _pack_ranges(tables.nfc_no_ranges), 4),
        _render_kotlin_array("NfcMaybeData", "ranges", "longArrayOf", _pack_ranges(tables.nfc_maybe_ranges), 4),
        _render_kotlin_array("DecompositionMetadata", "values", "longArrayOf", decomposition_metadata, 4),
        _render_kotlin_array("DecompositionData", "values", "intArrayOf", decomposition_data, 12),
        _render_kotlin_array("CompositionData", "values", "longArrayOf", _pack_compositions(tables.compositions), 4),
    ]
    lines = [
        "// Generated by tools/anki-contract/generate_unicode_contract.py --refresh.",
        "// Source: tools/anki-contract/unicode/15.1.0/manifest.json",
        "// Do not edit by hand.",
        "",
        "package com.ankiminer.android.anki.generated",
        "",
        "internal object UnicodeContractV151 {",
        "    private const val RANGE_MASK = (1 shl 21) - 1",
        "",
    ]
    for index, block in enumerate(blocks):
        if index:
            lines.append("")
        lines.extend(block)
    lines.extend("""

    fun isUnicodeScalar(codePoint: Int): Boolean =
        codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF

    private inline fun forEachScalar(value: String, block: (Int) -> Unit): Boolean {
        var index = 0
        while (index < value.length) {
            val first = value[index].code
            val codePoint =
                when {
                    first in 0xD800..0xDBFF -> {
                        if (index + 1 >= value.length) return false
                        val second = value[index + 1].code
                        if (second !in 0xDC00..0xDFFF) return false
                        index += 1
                        0x10000 + ((first - 0xD800) shl 10) + second - 0xDC00
                    }
                    first in 0xDC00..0xDFFF -> return false
                    else -> first
                }
            block(codePoint)
            index += 1
        }
        return true
    }

    fun scalarCount(value: String): Int? {
        var count = 0
        return if (forEachScalar(value) { count += 1 }) count else null
    }

    fun strictUtf8Length(value: String): Int? {
        var count = 0
        val valid =
            forEachScalar(value) { codePoint ->
                count +=
                    when {
                        codePoint <= 0x7F -> 1
                        codePoint <= 0x7FF -> 2
                        codePoint <= 0xFFFF -> 3
                        else -> 4
                    }
            }
        return if (valid) count else null
    }

    private fun containsRange(ranges: LongArray, codePoint: Int): Boolean {
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val packed = ranges[middle]
            val start = (packed ushr 21).toInt()
            val end = (packed and RANGE_MASK.toLong()).toInt()
            when {
                codePoint < start -> high = middle - 1
                codePoint > end -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    fun isPythonWhitespace(codePoint: Int): Boolean =
        isUnicodeScalar(codePoint) && containsRange(WhitespaceData.ranges, codePoint)

    fun isCategoryC(codePoint: Int): Boolean =
        isUnicodeScalar(codePoint) && containsRange(CategoryCData.ranges, codePoint)

    fun hasLeadingOrTrailingPythonWhitespace(value: String): Boolean {
        var first: Int? = null
        var last: Int? = null
        val valid =
            forEachScalar(value) { codePoint ->
                if (first == null) first = codePoint
                last = codePoint
            }
        return valid && first != null &&
            (isPythonWhitespace(first!!) || isPythonWhitespace(last!!))
    }

    private fun combiningClass(codePoint: Int): Int {
        val ranges = CombiningData.ranges
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val packed = ranges[middle]
            val start = (packed ushr 29).toInt()
            val end = ((packed ushr 8) and RANGE_MASK.toLong()).toInt()
            when {
                codePoint < start -> high = middle - 1
                codePoint > end -> low = middle + 1
                else -> return (packed and 0xFF).toInt()
            }
        }
        return 0
    }

    private fun decomposition(codePoint: Int): IntArray? {
        if (codePoint in 0xAC00 until 0xAC00 + 11172) {
            val index = codePoint - 0xAC00
            val leading = 0x1100 + index / 588
            val vowel = 0x1161 + (index % 588) / 28
            val trailingIndex = index % 28
            return if (trailingIndex == 0) {
                intArrayOf(leading, vowel)
            } else {
                intArrayOf(leading, vowel, 0x11A7 + trailingIndex)
            }
        }
        val metadata = DecompositionMetadata.values
        var low = 0
        var high = metadata.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val packed = metadata[middle]
            val candidate = (packed ushr 32).toInt()
            when {
                codePoint < candidate -> high = middle - 1
                codePoint > candidate -> low = middle + 1
                else -> {
                    val offset = ((packed ushr 8) and 0xFFFFFF).toInt()
                    val length = (packed and 0xFF).toInt()
                    return DecompositionData.values.copyOfRange(offset, offset + length)
                }
            }
        }
        return null
    }

    private fun composePair(starter: Int, combiner: Int): Int? {
        val leadingIndex = starter - 0x1100
        if (leadingIndex in 0 until 19 && combiner in 0x1161 until 0x1161 + 21) {
            return 0xAC00 + (leadingIndex * 21 + combiner - 0x1161) * 28
        }
        val syllableIndex = starter - 0xAC00
        if (
            syllableIndex in 0 until 11172 &&
                syllableIndex % 28 == 0 &&
                combiner in 0x11A8 until 0x11A7 + 28
        ) {
            return starter + combiner - 0x11A7
        }
        val key = (starter.toLong() shl 21) or combiner.toLong()
        val values = CompositionData.values
        var low = 0
        var high = values.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val packed = values[middle]
            val candidate = packed ushr 21
            when {
                key < candidate -> high = middle - 1
                key > candidate -> low = middle + 1
                else -> return (packed and RANGE_MASK.toLong()).toInt()
            }
        }
        return null
    }

    private fun normalizedNfc(values: IntArray): IntArray {
        val ordered = ArrayList<Int>(values.size)
        for (codePoint in values) {
            val children = decomposition(codePoint) ?: intArrayOf(codePoint)
            for (child in children) {
                ordered.add(child)
                val childClass = combiningClass(child)
                if (childClass != 0) {
                    var position = ordered.lastIndex
                    while (position > 0) {
                        val priorClass = combiningClass(ordered[position - 1])
                        if (priorClass == 0 || priorClass <= childClass) break
                        val prior = ordered[position - 1]
                        ordered[position - 1] = ordered[position]
                        ordered[position] = prior
                        position -= 1
                    }
                }
            }
        }
        if (ordered.isEmpty()) return IntArray(0)
        val result = ArrayList<Int>(ordered.size)
        result.add(ordered[0])
        var starterIndex = 0
        var starter = ordered[0]
        var lastClass = combiningClass(starter)
        for (index in 1 until ordered.size) {
            val codePoint = ordered[index]
            val currentClass = combiningClass(codePoint)
            val composite =
                if (lastClass == 0 || lastClass < currentClass) {
                    composePair(starter, codePoint)
                } else {
                    null
                }
            if (composite != null) {
                result[starterIndex] = composite
                starter = composite
            } else {
                if (currentClass == 0) {
                    starterIndex = result.size
                    starter = codePoint
                }
                result.add(codePoint)
                lastClass = currentClass
            }
        }
        return result.toIntArray()
    }

    /** Exact Unicode 15.1 NFC normalization, or null for an invalid UTF-16 scalar sequence. */
    fun normalizeNfc(value: String): String? {
        val values = ArrayList<Int>()
        if (!forEachScalar(value) { values.add(it) }) return null
        return buildString {
            for (codePoint in normalizedNfc(values.toIntArray())) appendCodePoint(codePoint)
        }
    }

    fun isNfc(value: String): Boolean {
        val values = ArrayList<Int>()
        if (!forEachScalar(value) { values.add(it) }) return false
        var maybe = false
        var lastClass = 0
        for (codePoint in values) {
            if (containsRange(NfcNoData.ranges, codePoint)) return false
            val currentClass = combiningClass(codePoint)
            if (currentClass != 0 && lastClass > currentClass) return false
            lastClass = currentClass
            maybe = maybe || containsRange(NfcMaybeData.ranges, codePoint)
        }
        return !maybe || normalizedNfc(values.toIntArray()).contentEquals(values.toIntArray())
    }
}
""".strip("\n").splitlines())
    lines.append("")
    return "\n".join(lines).encode("utf-8")


def load_tables(repo_root: Path) -> UnicodeTables:
    with _open_repo(repo_root) as repo_fd:
        inputs, whitespace = _read_inputs(repo_fd)
    return _build_tables(inputs, whitespace)


def _check_output(
    repo_fd: int,
    path: PurePosixPath,
    expected: bytes,
    description: str,
) -> None:
    actual = _read_regular_file(
        repo_fd,
        path,
        description=description,
        max_bytes=_MAX_OUTPUT_BYTES,
    )
    if actual != expected:
        _fail(f"{description} drifted; run " "tools/anki-contract/generate_unicode_contract.py --refresh")


def refresh(repo_root: Path) -> None:
    with _open_repo(repo_root) as repo_fd:
        inputs, whitespace = _read_inputs(repo_fd)
        tables = _build_tables(inputs, whitespace)
        python_output = generate_python(tables)
        kotlin_output = generate_kotlin(tables)
        if len(python_output) > _MAX_OUTPUT_BYTES or len(kotlin_output) > _MAX_OUTPUT_BYTES:
            _fail("generated Unicode output exceeds its size bound")
        _atomic_write(repo_fd, PYTHON_OUTPUT_PATH, python_output)
        _atomic_write(repo_fd, KOTLIN_OUTPUT_PATH, kotlin_output)
        _check_output(repo_fd, PYTHON_OUTPUT_PATH, python_output, "generated Python Unicode contract")
        _check_output(repo_fd, KOTLIN_OUTPUT_PATH, kotlin_output, "generated Kotlin Unicode contract")


def check(repo_root: Path) -> None:
    with _open_repo(repo_root) as repo_fd:
        inputs, whitespace = _read_inputs(repo_fd)
        tables = _build_tables(inputs, whitespace)
        _check_output(
            repo_fd,
            PYTHON_OUTPUT_PATH,
            generate_python(tables),
            "generated Python Unicode contract",
        )
        _check_output(
            repo_fd,
            KOTLIN_OUTPUT_PATH,
            generate_kotlin(tables),
            "generated Kotlin Unicode contract",
        )
