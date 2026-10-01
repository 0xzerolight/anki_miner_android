"""Catalog schema 3: one ``resource_catalog/<code>.json`` per mining language.

The Japanese file is the old single schema-2 ``resource_catalog_v1.json`` moved
in place. Every identity a Japanese install persisted under the old file (the
UniDic install manifest and completion marker, a dictionary sidecar's
``catalogResourceId`` and attribution) is a function of its catalog entry alone,
so the entries must be equal for an upgrading user's resources to keep working.
"""

from __future__ import annotations

import json
from pathlib import Path

import android_bridge.resource_catalog as resource_catalog
import android_bridge.resources as resources
import pytest
from android_bridge.protocol import BridgeProtocolError, decode_envelope
from android_bridge.resource_catalog import (
    CATALOG_LANGUAGES,
    YomitanResource,
    find_catalog_resource,
    load_resource_catalog,
    load_resource_catalogs,
    parse_catalog_json,
)

_V1_FIXTURE = Path(__file__).with_name("fixtures") / "resource_catalog_v1.json"
_CATALOG_DIR = Path(resource_catalog.__file__).parent


def _v1() -> dict:
    return json.loads(_V1_FIXTURE.read_text(encoding="utf-8"))


def test_every_catalog_file_is_listed_and_names_its_own_language() -> None:
    on_disk = sorted(path.stem for path in _CATALOG_DIR.glob("*.json"))

    assert sorted(CATALOG_LANGUAGES) == on_disk
    assert CATALOG_LANGUAGES[0] == "ja"
    for language in CATALOG_LANGUAGES:
        raw = json.loads((_CATALOG_DIR / f"{language}.json").read_text(encoding="utf-8"))
        assert raw["language"] == language
        assert load_resource_catalog(language).language == language


def test_catalog_files_are_canonical_json() -> None:
    """The language-data generator rewrites these files, so hand edits must match its output."""

    for language in CATALOG_LANGUAGES:
        path = _CATALOG_DIR / f"{language}.json"
        text = path.read_text(encoding="utf-8")
        assert text == json.dumps(json.loads(text), indent=2, ensure_ascii=False) + "\n", path.name


def test_japanese_catalog_carries_the_v1_entries_unchanged() -> None:
    v1 = _v1()
    ja = load_resource_catalog("ja").payload()

    assert v1["schemaVersion"] == 2
    assert ja["schemaVersion"] == 3
    assert ja["language"] == "ja"
    assert ja["resources"] == v1["resources"]
    assert ja["recommended"] == v1["recommended"]


def test_a_unidic_install_written_under_v1_still_validates() -> None:
    """The manifest and marker Kotlin's first install wrote are compared byte for byte."""

    entry = next(item for item in _v1()["resources"] if item["kind"] == "unidic")
    written_by_v1_manifest = {
        "schemaVersion": 1,
        "resourceId": entry["resourceId"],
        "archiveSha256": entry["archive"]["sha256"],
        "archiveSizeBytes": entry["archive"]["sizeBytes"],
        "treeSha256": entry["install"]["treeSha256"],
        "treeSizeBytes": entry["install"]["sizeBytes"],
        "fileCount": entry["install"]["fileCount"],
    }
    written_by_v1_marker = (
        f"anki-miner-tokenizer-v1\nresourceId={entry['resourceId']}\ntreeSha256={entry['install']['treeSha256']}\n"
    ).encode()

    resource = load_resource_catalog().get(entry["resourceId"])

    assert resources._unidic_manifest(resource) == written_by_v1_manifest
    assert resources._compatibility_marker(resource) == written_by_v1_marker


@pytest.mark.parametrize("resource_id", ["jmdict-en-2026-07-17", "jitendex-2026.07.09.0"])
def test_a_dictionary_sidecar_written_under_v1_keeps_its_catalog_identity(tmp_path: Path, resource_id: str) -> None:
    entry = next(item for item in _v1()["resources"] if item["resourceId"] == resource_id)
    slot = tmp_path / entry["slotId"]
    slot.mkdir()
    (slot / "android-resource.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "slotId": entry["slotId"],
                "archiveSha256": entry["archive"]["sha256"],
                "archiveSizeBytes": entry["archive"]["sizeBytes"],
                "catalogResourceId": resource_id,
                "sourceName": entry["dictionary"]["title"],
                "sourceRevision": entry["dictionary"]["revision"],
                "attribution": entry["attribution"],
            }
        ),
        encoding="utf-8",
    )

    sidecar = resources._read_dictionary_sidecar(slot, slot_id=entry["slotId"])

    assert sidecar is not None
    assert sidecar.catalog_resource_id == resource_id
    assert sidecar.attribution == entry["attribution"]


def test_resource_ids_are_unique_across_every_catalog() -> None:
    ids = [resource.resource_id for catalog in load_resource_catalogs() for resource in catalog.resources]

    assert len(ids) == len(set(ids))
    for catalog in load_resource_catalogs():
        for resource in catalog.resources:
            assert find_catalog_resource(resource.resource_id) == (catalog.language, resource)


def test_an_unknown_resource_or_language_is_refused() -> None:
    with pytest.raises(BridgeProtocolError) as unknown_id:
        find_catalog_resource("not-a-pinned-resource")
    assert unknown_id.value.code == "unknown_resource"

    with pytest.raises(BridgeProtocolError) as unknown_language:
        load_resource_catalog("xx")
    assert unknown_language.value.code == "unknown_resource"


@pytest.mark.parametrize("language", ["", "JA", "japanese", 7, None])
def test_the_parser_refuses_a_malformed_language(language: object) -> None:
    payload = load_resource_catalog().payload()
    payload["language"] = language

    with pytest.raises(BridgeProtocolError) as failure:
        parse_catalog_json(json.dumps(payload))

    assert failure.value.code == "invalid_resource_catalog"


def test_a_file_naming_another_language_is_refused(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    payload = load_resource_catalog().payload()
    payload["language"] = "he"
    (tmp_path / "ja.json").write_text(json.dumps(payload), encoding="utf-8")
    monkeypatch.setattr(resource_catalog, "_CATALOG_DIR", tmp_path)
    load_resource_catalog.cache_clear()
    try:
        with pytest.raises(BridgeProtocolError, match="names another language"):
            load_resource_catalog("ja")
    finally:
        load_resource_catalog.cache_clear()


def test_the_catalog_response_lists_every_language(initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    response = decode_envelope(resources.catalog_response({}), expected_type="resource.catalog")

    assert list(response.payload) == ["catalogs"]
    assert [item["language"] for item in response.payload["catalogs"]] == list(CATALOG_LANGUAGES)
    assert response.payload["catalogs"] == [catalog.payload() for catalog in load_resource_catalogs()]


def test_a_pinned_dictionary_cannot_be_stamped_for_another_language(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, initialized_bridge_home: Path
) -> None:
    del initialized_bridge_home
    home = tmp_path / "files"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    jmdict = load_resource_catalog().get("jmdict-en-2026-07-17")
    assert isinstance(jmdict, YomitanResource)
    source = tmp_path / "download.zip"
    source.write_bytes(b"never read")

    monkeypatch.setattr(resources, "find_catalog_resource", lambda _id: ("he", jmdict))
    with pytest.raises(BridgeProtocolError) as failure:
        resources.import_dictionary(
            {
                "operationId": "wrong-language",
                "sourcePath": str(source),
                "slotId": jmdict.slot_id,
                "overwrite": False,
                "catalogResourceId": jmdict.resource_id,
            }
        )

    assert failure.value.code == "invalid_resource_request"
    assert not (home / "dicts").exists()


def test_a_pinned_dictionary_with_an_oversized_bank_is_split_before_the_engine_reads_it(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, initialized_bridge_home: Path
) -> None:
    """wty builds put ~20 MB in term_bank_1, past what the engine importer may read whole."""

    pytest.importorskip("requests", reason="runtime dependency lane")
    import hashlib
    import zipfile
    from dataclasses import replace

    del initialized_bridge_home
    home = tmp_path / "files"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    entry_bytes = 1024
    row_count = resources._YOMITAN_BANK_INLINE_LIMIT_BYTES // entry_bytes + 2
    rows = [[f"term-{n}", "", "", "", 0, ["x" * entry_bytes], n, ""] for n in range(row_count)]
    source = tmp_path / "pinned.zip"
    index = {"title": "Pinned Fixture", "revision": "2026.09.20", "format": 3}
    with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.json", json.dumps(index))
        archive.writestr("term_bank_1.json", json.dumps(rows))
    with zipfile.ZipFile(source) as archive:
        members = archive.infolist()
    jmdict = load_resource_catalog().get("jmdict-en-2026-07-17")
    assert isinstance(jmdict, YomitanResource)
    pinned = replace(
        jmdict,
        resource_id="pinned-fixture",
        slot_id="pinned-fixture",
        archive=replace(
            jmdict.archive,
            sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
            size_bytes=source.stat().st_size,
        ),
        dictionary=replace(
            jmdict.dictionary,
            title=index["title"],
            revision=index["revision"],
            member_count=len(members),
            uncompressed_bytes=sum(member.file_size for member in members),
            file_bytes_limit=32 * 1024 * 1024,
        ),
    )
    monkeypatch.setattr(resources, "find_catalog_resource", lambda _id: ("ja", pinned))
    rewrites: list[object] = []
    original = resources._rewrite_yomitan_banks

    def record(*args: object, **kwargs: object) -> Path:
        rewrites.append(args)
        return original(*args, **kwargs)  # type: ignore[arg-type]

    monkeypatch.setattr(resources, "_rewrite_yomitan_banks", record)

    imported = decode_envelope(
        resources.import_dictionary(
            {
                "operationId": "pinned-rewrite",
                "sourcePath": str(source),
                "slotId": "pinned-fixture",
                "overwrite": False,
                "catalogResourceId": "pinned-fixture",
            }
        ),
        expected_type="resource.dictionary.imported",
    )

    assert rewrites
    assert imported.payload["catalogResourceId"] == "pinned-fixture"
    assert imported.payload["entryCount"] == row_count
    # The slot retains the verified original, not the split copy.
    retained = home / "dicts" / "pinned-fixture" / "source.zip"
    assert hashlib.sha256(retained.read_bytes()).hexdigest() == pinned.archive.sha256
