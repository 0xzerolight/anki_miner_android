"""Android-only final gate for dictionary HTML stored in Anki.

Desktop Yomitan rendering intentionally permits HTTP(S) glossary images.  An
Anki card would load those URLs automatically in its webview, which would add
an undeclared network egress path to the Android product.  Keep the desktop
renderer byte-for-byte vendored and remove unsafe image loads at the Android
Anki seam instead.

The sanitizer is deliberately lexical rather than a parse-and-reserialize
round trip: ordinary text and all unrelated renderer markup remain byte exact.
Only attributes capable of loading an image, plus renderer-envelope attributes
which repeat a rejected image URL, are removed.  A caller-provided predicate
admits renderer-marked media only after it has been resolved to an app-private
dictionary file or an already acknowledged Anki media name.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from html import unescape as html_unescape
from urllib.parse import urlsplit

from .protocol import BridgeProtocolError

_DICT_MEDIA_CLASS = "anki-miner-dict-media"

# Yomitan's renderer quotes attributes, but accepting either quote style and
# unquoted legacy HTML makes the final boundary fail closed for older indexes.
_START_TAG_RE = re.compile(
    r"""<[A-Za-z][A-Za-z0-9:-]*(?:[^>"']|"[^"]*"|'[^']*')*>""",
    re.DOTALL,
)
_TAG_NAME_RE = re.compile(r"<\s*([A-Za-z][A-Za-z0-9:-]*)")
_ATTR_RE = re.compile(
    r"""(?P<leading>\s+)(?P<name>[^\s"'<>/=]+)
        (?:\s*=\s*(?P<value>"[^"]*"|'[^']*'|[^\s"'=<>`]+))?""",
    re.DOTALL | re.VERBOSE,
)

_AUTOLOAD_ATTRIBUTES = frozenset({"src", "srcset", "poster", "background"})
_REPEATED_URL_ATTRIBUTES = frozenset({"data-path", "style"})


def _attribute_value(raw: str | None) -> str:
    if raw is None:
        return ""
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "\"'":
        raw = raw[1:-1]
    return html_unescape(raw)


def _attributes(tag: str) -> list[re.Match[str]]:
    name = _TAG_NAME_RE.match(tag)
    if name is None:
        return []
    return list(_ATTR_RE.finditer(tag, name.end(), len(tag) - 1))


def _attribute_map(tag: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for match in _attributes(tag):
        values.setdefault(
            match.group("name").lower(),
            _attribute_value(match.group("value")),
        )
    return values


def _is_remote_url(value: str) -> bool:
    candidate = value.strip()
    if candidate.startswith("//"):
        return True
    try:
        return urlsplit(candidate).scheme.lower() in {"http", "https"}
    except ValueError:
        # Malformed URL-like values are not safe local media names.
        return False


def _contains_remote_url(value: str) -> bool:
    # Attribute values may be CSS rather than a bare URL.  Scheme matching is
    # intentionally ASCII-case-insensitive and runs after HTML entity decode.
    lowered = value.lower()
    return "http://" in lowered or "https://" in lowered or lowered.lstrip().startswith("//")


def _marked_local_image(
    attrs: dict[str, str],
    local_source_allowed: Callable[[str], bool],
) -> bool:
    source = attrs.get("src")
    if not source:
        return False
    classes = attrs.get("class", "").split()
    return _DICT_MEDIA_CLASS in classes and local_source_allowed(source)


def _tag_has_one_marked_local_source(
    tag: str,
    local_source_allowed: Callable[[str], bool],
) -> bool:
    attributes = _attributes(tag)
    if sum(match.group("name").lower() == "src" for match in attributes) != 1:
        return False
    return _marked_local_image(_attribute_map(tag), local_source_allowed)


def sanitize_dictionary_html(
    value: str,
    *,
    local_source_allowed: Callable[[str], bool],
) -> str:
    """Remove auto-loading dictionary media except acknowledged local files.

    The function never changes character data or unrelated tags/attributes.
    Unrelated HTTP(S) links in ordinary ``<a href>`` glossary prose remain
    links; unlike image sources, they require explicit user action. If a link
    repeats a rejected image URL, that attribute is removed too so the image
    endpoint does not survive anywhere in the stored renderer envelope.
    """

    if not value or "<" not in value:
        return value

    rejected_remote_urls: set[str] = set()
    image_allowance: dict[tuple[int, int], bool] = {}
    for tag_match in _START_TAG_RE.finditer(value):
        tag = tag_match.group(0)
        name = _TAG_NAME_RE.match(tag)
        if name is None or name.group(1).lower() != "img":
            continue
        attrs = _attribute_map(tag)
        allowed = _tag_has_one_marked_local_source(tag, local_source_allowed)
        image_allowance[tag_match.span()] = allowed
        source = attrs.get("src")
        if source and not allowed and _is_remote_url(source):
            rejected_remote_urls.add(source)

    def sanitize_tag(tag_match: re.Match[str]) -> str:
        tag = tag_match.group(0)
        name_match = _TAG_NAME_RE.match(tag)
        if name_match is None:
            return tag
        tag_name = name_match.group(1).lower()
        allowed_image = image_allowance.get(tag_match.span(), False)
        removals: list[tuple[int, int]] = []

        for attribute in _attributes(tag):
            name = attribute.group("name").lower()
            decoded = _attribute_value(attribute.group("value"))
            remove = False

            if tag_name == "img" and name in _AUTOLOAD_ATTRIBUTES:
                # Renderer-marked src is the only image-loading attribute the
                # Android product admits. srcset/background remain forbidden.
                remove = not (allowed_image and name == "src")
            elif (
                name in _AUTOLOAD_ATTRIBUTES
                and _contains_remote_url(decoded)
                or name in _REPEATED_URL_ATTRIBUTES
                and _contains_remote_url(decoded)
            ):
                remove = True
            elif any(remote in decoded for remote in rejected_remote_urls):
                # The renderer repeats an image path in data-path, style and
                # occasionally title/alt. Remove those copies without touching
                # visible character data or deliberate hyperlink hrefs.
                remove = True

            if remove:
                removals.append(attribute.span())

        if not removals:
            return tag
        pieces: list[str] = []
        cursor = 0
        for start, end in removals:
            pieces.append(tag[cursor:start])
            cursor = end
        pieces.append(tag[cursor:])
        return "".join(pieces)

    sanitized = _START_TAG_RE.sub(sanitize_tag, value)

    # A second lexical pass is a fail-closed invariant: future renderer markup
    # must not silently reintroduce an image-loading attribute this version did
    # not understand.
    for tag in _START_TAG_RE.findall(sanitized):
        name = _TAG_NAME_RE.match(tag)
        if name is None or name.group(1).lower() != "img":
            continue
        attrs = _attribute_map(tag)
        if "src" in attrs and not _tag_has_one_marked_local_source(tag, local_source_allowed):
            raise BridgeProtocolError(
                "unsafe_dictionary_html",
                "Dictionary HTML contains an unsafe image source",
            )
        if any(attribute in attrs for attribute in _AUTOLOAD_ATTRIBUTES - {"src"}):
            raise BridgeProtocolError(
                "unsafe_dictionary_html",
                "Dictionary HTML contains an unsafe image loading attribute",
            )
    return sanitized
