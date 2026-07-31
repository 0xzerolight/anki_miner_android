#!/usr/bin/env python3
"""Audit Android locale catalogs against the default English string resources."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ElementTree
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RESOURCE_ROOT = REPO_ROOT / "app/src/main/res"
LOCALE_DIRECTORY = re.compile(
    r"values-(?:[a-z]{2}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9]{2,8}(?:\+[A-Za-z0-9]{1,8})*)$",
    re.IGNORECASE,
)
PRINTF_SPECIFIER = re.compile(
    r"%(?:(?P<index>[1-9][0-9]*)\$)?"
    r"(?P<flags>[-#+ 0,(<]*)"
    r"(?P<width>[0-9]*)"
    r"(?:\.(?P<precision>[0-9]+))?"
    r"(?P<datetime>[tT])?"
    r"(?P<conversion>[a-zA-Z%])"
)


class CatalogError(ValueError):
    """A catalog cannot be audited safely."""


@dataclass(frozen=True)
class StringResource:
    text: str
    format_signature: tuple[tuple[str, int], ...]
    xliff_signature: tuple[tuple[str, int], ...]


@dataclass(frozen=True)
class CatalogResource:
    kind: str
    items: dict[str, StringResource]


def _counter_signature(values: list[str]) -> tuple[tuple[str, int], ...]:
    return tuple(sorted(Counter(values).items()))


def _printf_signature(text: str, *, path: Path, key: str) -> tuple[tuple[str, int], ...]:
    tokens: list[str] = []
    implicit_index = 1
    previous_index: int | None = None
    offset = 0
    while True:
        marker = text.find("%", offset)
        if marker < 0:
            break
        match = PRINTF_SPECIFIER.match(text, marker)
        if match is None:
            raise CatalogError(f"{path}: invalid printf marker for {key} at character {marker}")

        flags = match.group("flags")
        conversion = match.group("conversion")
        datetime = match.group("datetime") or ""
        width = match.group("width")
        precision = match.group("precision") or ""
        explicit_index = match.group("index")

        if conversion == "%":
            tokens.append("literal-percent")
        elif conversion == "n":
            tokens.append("newline")
        else:
            if "<" in flags:
                if explicit_index is not None or previous_index is None:
                    raise CatalogError(f"{path}: invalid '<' printf reuse for {key}")
                argument_index = previous_index
            elif explicit_index is not None:
                argument_index = int(explicit_index)
            else:
                argument_index = implicit_index
                implicit_index += 1
            previous_index = argument_index
            normalized_flags = flags.replace("<", "")
            tokens.append(
                f"arg={argument_index};flags={normalized_flags};width={width};"
                f"precision={precision};conversion={datetime}{conversion}"
            )
        offset = match.end()
    return _counter_signature(tokens)


def _xliff_signature(element: ElementTree.Element) -> tuple[tuple[str, int], ...]:
    placeholders: list[str] = []
    for child in element.iter():
        if child.tag.rsplit("}", 1)[-1] != "g":
            continue
        placeholder_id = child.attrib.get("id")
        if placeholder_id:
            placeholders.append(placeholder_id)
    return _counter_signature(placeholders)


def _string_resource(
    element: ElementTree.Element,
    *,
    path: Path,
    key: str,
) -> StringResource:
    text = "".join(element.itertext())
    return StringResource(
        text=text,
        format_signature=_printf_signature(text, path=path, key=key),
        xliff_signature=_xliff_signature(element),
    )


def read_catalog(path: Path) -> dict[str, CatalogResource]:
    try:
        root = ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError) as failure:
        raise CatalogError(f"{path}: could not parse XML: {failure}") from failure
    if root.tag != "resources":
        raise CatalogError(f"{path}: root element must be <resources>")

    resources: dict[str, CatalogResource] = {}
    for element in root.findall("string"):
        key = element.attrib.get("name", "").strip()
        if not key:
            raise CatalogError(f"{path}: <string> is missing a name")
        if key in resources:
            raise CatalogError(f"{path}: duplicate key: {key}")
        resources[key] = CatalogResource(
            kind="string",
            items={"value": _string_resource(element, path=path, key=key)},
        )
    for element in root.findall("plurals"):
        key = element.attrib.get("name", "").strip()
        if not key:
            raise CatalogError(f"{path}: <plurals> is missing a name")
        if key in resources:
            raise CatalogError(f"{path}: duplicate key: {key}")
        items: dict[str, StringResource] = {}
        for item in element.findall("item"):
            quantity = item.attrib.get("quantity", "").strip()
            if not quantity:
                raise CatalogError(f"{path}: <plurals> {key} has an item without quantity")
            if quantity in items:
                raise CatalogError(f"{path}: duplicate plural quantity for {key}: {quantity}")
            items[quantity] = _string_resource(item, path=path, key=f"{key}[{quantity}]")
        if "other" not in items:
            raise CatalogError(f"{path}: <plurals> {key} is missing quantity=other")
        resources[key] = CatalogResource(kind="plurals", items=items)
    return resources


def locale_catalogs(resource_root: Path) -> list[Path]:
    if not resource_root.is_dir():
        raise CatalogError(f"{resource_root}: resource root is not a directory")
    catalogs = []
    for directory in resource_root.iterdir():
        if directory.is_dir() and LOCALE_DIRECTORY.fullmatch(directory.name):
            catalog = directory / "strings.xml"
            if catalog.is_file():
                catalogs.append(catalog)
    return sorted(catalogs, key=lambda path: path.parent.name.casefold())


def audit(resource_root: Path) -> tuple[int, list[Path]]:
    source_path = resource_root / "values/strings.xml"
    source = read_catalog(source_path)
    if not source:
        raise CatalogError(f"{source_path}: source catalog has no resources")

    catalogs = locale_catalogs(resource_root)
    failures: list[str] = []
    source_keys = set(source)
    for catalog_path in catalogs:
        translation = read_catalog(catalog_path)
        translation_keys = set(translation)
        relative = catalog_path.relative_to(resource_root)
        missing = sorted(source_keys - translation_keys)
        extra = sorted(translation_keys - source_keys)
        if missing:
            failures.append(f"{relative}: missing keys: {', '.join(missing)}")
        if extra:
            failures.append(f"{relative}: extra keys: {', '.join(extra)}")
        for key in sorted(source_keys & translation_keys):
            source_resource = source[key]
            translated_resource = translation[key]
            if source_resource.kind != translated_resource.kind:
                failures.append(
                    f"{relative}: resource kind mismatch for {key}: "
                    f"source={source_resource.kind}; translation={translated_resource.kind}"
                )
                continue
            for quantity, translated_item in translated_resource.items.items():
                source_item = source_resource.items.get(quantity) or source_resource.items.get("other")
                if source_item is None:
                    failures.append(f"{relative}: source plural {key} has no comparable quantity for {quantity}")
                    continue
                source_signature = (
                    source_item.format_signature,
                    source_item.xliff_signature,
                )
                translation_signature = (
                    translated_item.format_signature,
                    translated_item.xliff_signature,
                )
                label = key if source_resource.kind == "string" else f"{key}[{quantity}]"
                if source_signature != translation_signature:
                    failures.append(
                        f"{relative}: format mismatch for {label}: "
                        f"source={source_signature}; translation={translation_signature}"
                    )

    if failures:
        raise CatalogError("\n".join(failures))
    return len(source), catalogs


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--resource-root",
        type=Path,
        default=DEFAULT_RESOURCE_ROOT,
        help="Android res directory containing values/strings.xml",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        source_count, catalogs = audit(args.resource_root.resolve())
    except CatalogError as failure:
        print(f"localization audit failed: {failure}", file=sys.stderr)
        return 1

    for catalog in catalogs:
        print(f"{catalog.relative_to(args.resource_root.resolve())}: {source_count} resources verified")
    print(f"Localization audit passed: {source_count} source resources; {len(catalogs)} locale catalog(s) verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
