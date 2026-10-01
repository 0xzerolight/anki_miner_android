"""Shared check: every desktop catalog row of a language is pinned on Android or excluded.

Desktop's ``profile.catalog`` rows are the resources its setup wizard offers for a
language. Android downloads only what ``resource_catalog/<code>.json`` pins (a
moving desktop URL becomes an immutable one with a SHA-256 and a length), so each
row is either mapped to the Android resource id that pins it or excluded with a
reason. A new desktop row fails here until it is classified.
"""

from __future__ import annotations

from collections.abc import Mapping

from android_bridge.resource_catalog import CATALOG_LANGUAGES, load_resource_catalog

#: Desktop ``ResourceSpec.kind`` -> Android catalog kind.
_KINDS = {"dict": "yomitan-dictionary", "freq": "frequency", "pitch": "pitch"}
#: Kinds Android pins that desktop's catalog never lists (engine data, the tokenizer).
_NOT_DESKTOP_ROWS = frozenset({"unidic", "language-data"})
#: Every exclusion that only waits for the per-language pinning task.
PENDING_PIN = "not pinned yet: the per-language fan-out (C.7) pins it"


def assert_catalog_complete(
    code: str,
    *,
    pinned: Mapping[str, str],
    excluded: Mapping[str, str],
    android_only: Mapping[str, str] | None = None,
) -> None:
    from anki_miner.languages.registry import get_profile

    rows = {spec.id: spec for spec in get_profile(code).catalog}
    assert set(pinned).isdisjoint(excluded), "a desktop row is both pinned and excluded"
    assert set(pinned) | set(excluded) == set(rows), (
        f"{code}: unclassified desktop rows {sorted(set(rows) - set(pinned) - set(excluded))}, "
        f"stale entries {sorted((set(pinned) | set(excluded)) - set(rows))}"
    )
    assert all(reason.strip() for reason in excluded.values()), "every exclusion needs a reason"

    if code not in CATALOG_LANGUAGES:
        assert not pinned, f"{code} pins resources but has no catalog file"
        return
    catalog = load_resource_catalog(code)
    for desktop_id, resource_id in pinned.items():
        assert catalog.get(resource_id).kind == _KINDS[rows[desktop_id].kind], desktop_id
    accounted = set(pinned.values()) | set(android_only or {})
    assert all(reason.strip() for reason in (android_only or {}).values())
    unaccounted = {
        resource.resource_id
        for resource in catalog.resources
        if resource.kind not in _NOT_DESKTOP_ROWS and resource.resource_id not in accounted
    }
    assert not unaccounted, f"{code}: pins no desktop row accounts for: {sorted(unaccounted)}"
    assert set(catalog.recommended) <= set(pinned.values()) | set(android_only or {})
