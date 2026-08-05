from __future__ import annotations

import ast
import errno
import hashlib
import importlib.util
import io
import json
import stat
import tarfile
import zipfile
from dataclasses import replace
from pathlib import Path

import android_bridge.local_resources as local_resources
import android_bridge.resources as resources
import pytest
from android_bridge import boundary
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message
from android_bridge.resource_catalog import (
    ResourceCatalog,
    UniDicResource,
    YomitanResource,
    load_resource_catalog,
    parse_catalog_json,
)
from android_bridge.unidic_resource import calculate_unidic_tree_sha256


def test_dictionary_schema_version_matches_vendored_engine() -> None:
    storage_source = (
        Path(resources.__file__).resolve().parents[1] / "anki_miner" / "services" / "dictionary" / "storage.py"
    )
    module = ast.parse(storage_source.read_text(encoding="utf-8"))
    schema_versions = [
        ast.literal_eval(node.value)
        for node in module.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == "SCHEMA_VERSION" for target in node.targets)
    ]

    assert schema_versions == [resources._DICTIONARY_SCHEMA_VERSION]


def _catalog_unidic() -> UniDicResource:
    resource = load_resource_catalog().get("unidic-lite-1.0.8")
    assert isinstance(resource, UniDicResource)
    return resource


def _fixture_dicdir(root: Path) -> Path:
    dicdir = root / "dicdir"
    dicdir.mkdir(parents=True)
    filenames = (
        "AUTHORS",
        "BSD",
        "COPYING",
        "ChangeLog",
        "GPL",
        "INSTALL",
        "LGPL",
        "README.md",
        "char.bin",
        "dicrc",
        "left-id.def",
        "matrix.bin",
        "mecabrc",
        "rewrite.def",
        "right-id.def",
        "sys.dic",
        "unidic-mecab.pdf",
        "unk.dic",
        "version",
    )
    for index, filename in enumerate(filenames):
        (dicdir / filename).write_bytes(f"fixture-{index}:{filename}\n".encode())
    return dicdir


def _tar_bytes(
    dicdir: Path,
    *,
    additions: list[tarfile.TarInfo] | None = None,
    duplicate: str | None = None,
) -> bytes:
    output = io.BytesIO()
    prefix = "fixture-1.0/unidic_lite/dicdir"
    with tarfile.open(fileobj=output, mode="w:gz", format=tarfile.PAX_FORMAT) as archive:
        root = tarfile.TarInfo("fixture-1.0")
        root.type = tarfile.DIRTYPE
        archive.addfile(root)
        for path in sorted(dicdir.iterdir()):
            content = path.read_bytes()
            info = tarfile.TarInfo(f"{prefix}/{path.name}")
            info.size = len(content)
            archive.addfile(info, io.BytesIO(content))
            if duplicate == path.name:
                archive.addfile(info, io.BytesIO(content))
        for info in additions or []:
            content = b"extra" if info.isreg() else None
            if info.isreg():
                info.size = len(content)
            archive.addfile(info, io.BytesIO(content) if content is not None else None)
    return output.getvalue()


def _fixture_unidic_resource(dicdir: Path, archive: bytes) -> UniDicResource:
    source = _catalog_unidic()
    total = sum(path.stat().st_size for path in dicdir.iterdir())
    return replace(
        source,
        archive=replace(
            source.archive,
            size_bytes=len(archive),
            sha256=hashlib.sha256(archive).hexdigest(),
        ),
        install=replace(
            source.install,
            member_prefix="fixture-1.0/unidic_lite/dicdir/",
            tree_sha256=calculate_unidic_tree_sha256(dicdir),
            file_count=19,
            size_bytes=total,
        ),
    )


def _write(path: Path, content: bytes) -> Path:
    path.write_bytes(content)
    return path


def test_catalog_freezes_external_identity_and_attribution() -> None:
    catalog = load_resource_catalog()
    unidic = catalog.get("unidic-lite-1.0.8")
    jitendex = catalog.get("jitendex-2026.07.09.0")

    assert isinstance(unidic, UniDicResource)
    assert unidic.archive.sha256 == "db9d4572d9fdd4d00a97949d4b0741ec480ee05a7e7e2e32f547500dae27b245"
    assert unidic.archive.size_bytes == 47_356_746
    assert unidic.install.file_count == 19
    assert unidic.install.size_bytes == 260_467_176
    assert unidic.install.tree_sha256 == "bd942f1b395aa7c56fe20321dc7f021930e29107f6b2949a49f5c56caab55ea7"
    assert {notice.license for notice in unidic.attribution} == {"MIT", "BSD-3-Clause"}

    assert isinstance(jitendex, YomitanResource)
    assert jitendex.slot_id == "jitendex"
    assert jitendex.archive.sha256 == "807d911114af9d2154d270702972aafb2b6a6c2dc2400afa98db870d035c1a0b"
    assert jitendex.dictionary.title == "Jitendex.org [2026-07-09]"
    assert jitendex.dictionary.revision == "2026.07.09.0"
    assert jitendex.dictionary.member_count == 473
    assert jitendex.dictionary.uncompressed_bytes == 540_565_403
    assert jitendex.dictionary.file_bytes_limit == 16 * 1024 * 1024
    assert {notice.license for notice in jitendex.attribution} >= {
        "CC-BY-SA-4.0",
        "EDRDG-Licence",
        "CC-BY-2.0-FR",
    }


def test_catalog_parser_rejects_duplicate_keys_unknown_fields_and_mutable_urls() -> None:
    with pytest.raises(BridgeProtocolError, match="duplicate key"):
        parse_catalog_json('{"schemaVersion":1,"schemaVersion":1,"resources":[]}')

    payload = load_resource_catalog().payload()
    payload["unexpected"] = True
    with pytest.raises(BridgeProtocolError, match="exactly"):
        parse_catalog_json(json.dumps(payload))

    payload.pop("unexpected")
    payload["resources"][0]["archive"]["url"] = "http://example.invalid/latest"
    with pytest.raises(BridgeProtocolError, match="HTTPS"):
        parse_catalog_json(json.dumps(payload))

    payload["resources"][0]["archive"]["url"] = load_resource_catalog().payload()["resources"][0]["archive"]["url"]
    payload["resources"][1]["slotId"] = "ambiguous--slot"
    with pytest.raises(BridgeProtocolError, match="slot id is invalid"):
        parse_catalog_json(json.dumps(payload))


def test_unidic_install_verifies_tree_then_publishes_completion_manifest_last(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_tree = _fixture_dicdir(tmp_path / "source")
    archive = _tar_bytes(source_tree)
    resource = _fixture_unidic_resource(source_tree, archive)
    source = _write(tmp_path / "unidic.tar.gz", archive)
    home = tmp_path / "files"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    monkeypatch.setattr(
        resources,
        "load_resource_catalog",
        lambda: ResourceCatalog(resources=(resource,)),
    )

    decoded = decode_envelope(
        resources.install_unidic(
            {
                "operationId": "install-one",
                "resourceId": resource.resource_id,
                "archivePath": str(source),
            }
        ),
        expected_type="resource.unidic.installed",
    )
    final = home / "resources" / "tokenizer" / resource.resource_id
    assert decoded.payload["alreadyInstalled"] is False
    assert decoded.payload["fileCount"] == 19
    assert resources._valid_unidic_install(final, resource)
    assert json.loads((final / resources._MANIFEST_NAME).read_text()) == resources._unidic_manifest(resource)
    assert not list(final.parent.glob(".installing-*"))

    # A proven install is idempotent and does not need to reopen the source.
    source.unlink()
    repeated = decode_envelope(
        resources.install_unidic(
            {
                "operationId": "install-two",
                "resourceId": resource.resource_id,
                "archivePath": str(source),
            }
        ),
        expected_type="resource.unidic.installed",
    )
    assert repeated.payload["alreadyInstalled"] is True


def test_unidic_install_repairs_corrupt_tree_with_intact_completion_metadata(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_tree = _fixture_dicdir(tmp_path / "source")
    archive = _tar_bytes(source_tree)
    resource = _fixture_unidic_resource(source_tree, archive)
    source = _write(tmp_path / "unidic.tar.gz", archive)
    home = tmp_path / "files"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    monkeypatch.setattr(
        resources,
        "load_resource_catalog",
        lambda: ResourceCatalog(resources=(resource,)),
    )
    request = {
        "operationId": "install-initial",
        "resourceId": resource.resource_id,
        "archivePath": str(source),
    }
    resources.install_unidic(request)

    final = home / "resources" / "tokenizer" / resource.resource_id
    expected = (source_tree / "sys.dic").read_bytes()
    (final / "dicdir" / "sys.dic").write_bytes(b"corrupt")
    assert not resources._valid_unidic_install(final, resource)

    repaired = decode_envelope(
        resources.install_unidic(
            {
                **request,
                "operationId": "install-repair",
            }
        ),
        expected_type="resource.unidic.installed",
    )
    assert repaired.payload["alreadyInstalled"] is False
    assert (final / "dicdir" / "sys.dic").read_bytes() == expected
    assert resources._valid_unidic_install(final, resource)


def _unsafe_tar_case(tmp_path: Path, case: str) -> tuple[bytes, UniDicResource]:
    tree = _fixture_dicdir(tmp_path / f"source-{case}")
    additions: list[tarfile.TarInfo] = []
    duplicate = None
    if case == "traversal":
        additions.append(tarfile.TarInfo("fixture-1.0/unidic_lite/dicdir/../escape"))
    elif case == "unexpected-root":
        additions.append(tarfile.TarInfo("other-root/file"))
    elif case == "symlink":
        item = tarfile.TarInfo("fixture-1.0/link")
        item.type = tarfile.SYMTYPE
        item.linkname = "target"
        additions.append(item)
    elif case == "device":
        item = tarfile.TarInfo("fixture-1.0/device")
        item.type = tarfile.CHRTYPE
        additions.append(item)
    elif case == "duplicate":
        duplicate = "sys.dic"
    elif case == "count-excess":
        additions.append(tarfile.TarInfo("fixture-1.0/unidic_lite/dicdir/extra"))
    else:
        raise AssertionError(case)
    archive = _tar_bytes(tree, additions=additions, duplicate=duplicate)
    return archive, _fixture_unidic_resource(tree, archive)


@pytest.mark.parametrize(
    "case",
    ["traversal", "unexpected-root", "symlink", "device", "duplicate", "count-excess"],
)
def test_unidic_extractor_rejects_unsafe_or_excess_members(tmp_path: Path, case: str) -> None:
    archive, resource = _unsafe_tar_case(tmp_path, case)
    source = _write(tmp_path / f"{case}.tar.gz", archive)
    staging = tmp_path / f"staging-{case}"
    staging.mkdir()

    with pytest.raises(BridgeProtocolError) as error:
        resources._extract_unidic(source, staging, resource, resources._Operation(case))
    assert error.value.code in {"unsafe_resource_archive", "resource_archive_too_large"}


@pytest.mark.parametrize("storage_errno", [errno.ENOSPC, getattr(errno, "EDQUOT", 122)])
def test_unidic_extractor_reports_storage_exhaustion(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    storage_errno: int,
) -> None:
    tree = _fixture_dicdir(tmp_path / "source")
    archive = _tar_bytes(tree)
    resource = _fixture_unidic_resource(tree, archive)
    source = _write(tmp_path / "unidic.tar.gz", archive)
    staging = tmp_path / "staging"
    staging.mkdir()

    def storage_full(_stream: object, _content: bytes) -> None:
        raise OSError(storage_errno, "storage exhausted")

    monkeypatch.setattr(resources, "_write_all", storage_full)
    with pytest.raises(BridgeProtocolError) as failure:
        resources._extract_unidic(
            source,
            staging,
            resource,
            resources._Operation("unidic-storage"),
        )

    assert failure.value.code == "insufficient_storage"


def test_unidic_extractor_does_not_label_write_io_as_corrupt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tree = _fixture_dicdir(tmp_path / "source")
    archive = _tar_bytes(tree)
    resource = _fixture_unidic_resource(tree, archive)
    source = _write(tmp_path / "unidic.tar.gz", archive)
    staging = tmp_path / "staging"
    staging.mkdir()
    monkeypatch.setattr(
        resources,
        "_write_all",
        lambda *_args: (_ for _ in ()).throw(OSError(errno.EIO, "write failed")),
    )

    with pytest.raises(BridgeProtocolError) as failure:
        resources._extract_unidic(
            source,
            staging,
            resource,
            resources._Operation("unidic-io"),
        )

    assert failure.value.code == "resource_install_failed"


def test_unidic_copy_rejects_hash_mismatch_and_removes_partial(tmp_path: Path) -> None:
    tree = _fixture_dicdir(tmp_path / "source")
    archive = _tar_bytes(tree)
    source = _write(tmp_path / "source.tar.gz", archive)
    destination = tmp_path / "work" / "copy.tar.gz"

    with pytest.raises(BridgeProtocolError) as error:
        resources._copy_archive(
            source,
            destination,
            resources._Operation("hash-mismatch"),
            maximum_bytes=len(archive),
            expected_size=len(archive),
            expected_sha256="0" * 64,
        )
    assert error.value.code == "resource_archive_mismatch"
    assert not destination.exists()


def _yomitan_zip(path: Path, *, term: str, meaning: str, revision: str) -> Path:
    index = {
        "title": "Fixture Dictionary",
        "revision": revision,
        "format": 3,
        "author": "Fixture Author",
        "attribution": "Fixture Attribution",
    }
    rows = [[term, "", "", "", 0, [meaning], 1, ""]]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.json", json.dumps(index, ensure_ascii=False))
        archive.writestr("term_bank_1.json", json.dumps(rows, ensure_ascii=False))
    return path


# > the retired 16 MiB per-file cap, so a term/meta bank of this size exercises
# the relaxed limits while providing enough entries for bounded bank splitting.
_OVERSIZED_MEMBER_BYTES = 17 * 1024 * 1024
_BANK_FIXTURE_ENTRY_BYTES = 64 * 1024
_STREAMED_BANK_BYTES = 4 * 1024 * 1024


def _yomitan_meta_bank_zip(path: Path, *, entry: list, frequency_mode: str | None = None) -> Path:
    """Build a Yomitan meta-bank zip (pitch/frequency) whose sole
    term_meta_bank_*.json exceeds the retired 16 MiB per-file cap.

    ``entry`` is the one usable ``[term, mode, data]`` triple. Structurally-valid
    non-target padding entries push the bank past 16 MiB without making any
    single decoded entry large.
    """
    index: dict[str, object] = {"title": "Meta Fixture", "revision": "1", "format": 3}
    if frequency_mode is not None:
        index["frequencyMode"] = frequency_mode
    padding_count = _OVERSIZED_MEMBER_BYTES // _BANK_FIXTURE_ENTRY_BYTES + 1
    rows = [
        entry,
        *[[f"pad-{index}", "x", "A" * _BANK_FIXTURE_ENTRY_BYTES] for index in range(padding_count)],
    ]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.json", json.dumps(index, ensure_ascii=False))
        archive.writestr("term_meta_bank_1.json", json.dumps(rows, ensure_ascii=False))
    return path


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="the lean host lane intentionally excludes runtime engine dependencies",
)
def test_yomitan_import_list_lookup_and_stable_overwrite(
    tmp_path: Path,
    initialized_bridge_home: Path,
) -> None:
    home = initialized_bridge_home
    first = _yomitan_zip(tmp_path / "first.zip", term="猫", meaning="cat", revision="1")

    imported = decode_envelope(
        resources.import_dictionary(
            {
                "operationId": "dict-one",
                "sourcePath": str(first),
                "slotId": "fixture",
                "overwrite": False,
                "catalogResourceId": None,
            }
        ),
        expected_type="resource.dictionary.imported",
    )
    assert imported.payload["slotId"] == "fixture"
    assert imported.payload["entryCount"] == 1
    assert (home / "dicts" / "fixture" / "android-resource.json").is_file()

    listed = decode_envelope(resources.list_dictionaries({}), expected_type="resource.dictionary.listed")
    listed_by_slot = {item["slotId"]: item for item in listed.payload["dictionaries"]}
    listed_dictionary = listed_by_slot["fixture"]
    assert listed_dictionary["occupied"] is True
    assert listed_dictionary["valid"] is True
    assert listed_dictionary["schemaOk"] is True
    assert listed_dictionary["format"] == "yomitan"
    assert listed_dictionary["embeddedAttribution"] == {
        "author": "Fixture Author",
        "attribution": "Fixture Attribution",
    }
    lookup = decode_envelope(
        resources.lookup_dictionary({"slotId": "fixture", "term": "猫"}),
        expected_type="resource.dictionary.lookup.result",
    )
    assert "cat" in lookup.payload["html"]

    second = _yomitan_zip(tmp_path / "second.zip", term="犬", meaning="dog", revision="2")
    replaced = decode_envelope(
        resources.import_dictionary(
            {
                "operationId": "dict-two",
                "sourcePath": str(second),
                "slotId": "fixture",
                "overwrite": True,
                "catalogResourceId": None,
            }
        ),
        expected_type="resource.dictionary.imported",
    )
    assert replaced.payload["sourceRevision"] == "2"
    dog = decode_envelope(
        resources.lookup_dictionary({"slotId": "fixture", "term": "犬"}),
        expected_type="resource.dictionary.lookup.result",
    )
    assert "dog" in dog.payload["html"]
    assert not any((home / "resource-work" / "dictionary-backups").iterdir())


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="the lean host lane intentionally excludes runtime engine dependencies",
)
def test_revisionless_yomitan_import_returns_and_lists_empty_revision(
    tmp_path: Path,
    initialized_bridge_home: Path,
) -> None:
    source = _yomitan_zip(tmp_path / "revisionless.zip", term="猫", meaning="cat", revision="")

    imported = decode_envelope(
        resources.import_dictionary(
            {
                "operationId": "dict-revisionless",
                "sourcePath": str(source),
                "slotId": "revisionless",
                "overwrite": False,
                "catalogResourceId": None,
            }
        ),
        expected_type="resource.dictionary.imported",
    )
    listed = decode_envelope(
        resources.list_dictionaries({}),
        expected_type="resource.dictionary.listed",
    )

    assert imported.payload["sourceRevision"] == ""
    installed = next(item for item in listed.payload["dictionaries"] if item["slotId"] == "revisionless")
    assert installed["sourceRevision"] == ""


def test_dictionary_inventory_surfaces_corrupt_occupied_catalog_slot(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "corrupt-catalog-home"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    catalog_resource = load_resource_catalog().get("jitendex-2026.07.09.0")
    assert isinstance(catalog_resource, YomitanResource)
    slot = home / "dicts" / catalog_resource.slot_id
    slot.mkdir(parents=True)
    (slot / "index.sqlite").write_bytes(b"not sqlite")
    archive = resources._ArchiveCopy(
        slot / "unused.zip",
        catalog_resource.archive.sha256,
        catalog_resource.archive.size_bytes,
    )
    sidecar = resources._dictionary_sidecar(
        slot_id=catalog_resource.slot_id,
        archive=archive,
        catalog_resource=catalog_resource,
        source_name=catalog_resource.dictionary.title,
        source_revision=catalog_resource.dictionary.revision,
    )
    (slot / "android-resource.json").write_bytes(resources._canonical_json_bytes(sidecar))

    listed = decode_envelope(resources.list_dictionaries({}), expected_type="resource.dictionary.listed").payload[
        "dictionaries"
    ]

    assert len(listed) == 1
    installed = listed[0]
    assert installed["slotId"] == catalog_resource.slot_id
    assert installed["occupied"] is True
    assert installed["valid"] is False
    assert installed["schemaOk"] is False
    assert installed["catalogResourceId"] == catalog_resource.resource_id
    assert installed["attribution"] == [item.payload() for item in catalog_resource.attribution]


def test_dictionary_inventory_discards_forged_catalog_sidecar(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "forged-sidecar-home"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    catalog_resource = load_resource_catalog().get("jitendex-2026.07.09.0")
    assert isinstance(catalog_resource, YomitanResource)
    slot = home / "dicts" / catalog_resource.slot_id
    slot.mkdir(parents=True)
    (slot / "index.sqlite").write_bytes(b"not sqlite")
    sidecar = resources._dictionary_sidecar(
        slot_id=catalog_resource.slot_id,
        archive=resources._ArchiveCopy(
            slot / "unused.zip",
            catalog_resource.archive.sha256,
            catalog_resource.archive.size_bytes,
        ),
        catalog_resource=catalog_resource,
        source_name=catalog_resource.dictionary.title,
        source_revision=catalog_resource.dictionary.revision,
    )
    sidecar["attribution"] = []
    (slot / "android-resource.json").write_bytes(resources._canonical_json_bytes(sidecar))

    installed = decode_envelope(resources.list_dictionaries({}), expected_type="resource.dictionary.listed").payload[
        "dictionaries"
    ][0]

    assert installed["catalogResourceId"] is None
    assert installed["attribution"] == []
    assert installed["sourceName"] == catalog_resource.slot_id


def test_dictionary_inventory_does_not_follow_slot_or_sidecar_symlinks(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "symlink-home"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "index.sqlite").write_bytes(b"outside")
    (outside / "android-resource.json").write_text("{}", encoding="utf-8")
    original_read_text = Path.read_text

    def guarded_read_text(path: Path, *args: object, **kwargs: object) -> str:
        assert path.parent != outside, "inventory followed a dictionary-slot symlink"
        return original_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", guarded_read_text)
    root = home / "dicts"
    root.mkdir()
    (root / "linked").symlink_to(outside, target_is_directory=True)

    listed = decode_envelope(resources.list_dictionaries({}), expected_type="resource.dictionary.listed").payload[
        "dictionaries"
    ]

    assert listed == [
        {
            "slotId": "linked",
            "occupied": True,
            "valid": False,
            "sourceName": "linked",
            "sourceRevision": "",
            "format": "unknown",
            "entryCount": 0,
            "schemaOk": False,
            "embeddedAttribution": {},
            "catalogResourceId": None,
            "attribution": [],
        }
    ]


def test_dictionary_inventory_rejects_unsafe_or_unbounded_slot_sets(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "unsafe-home"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    root = home / "dicts"
    root.mkdir()
    (root / "unsafe slot").mkdir()
    with pytest.raises(BridgeProtocolError) as unsafe:
        resources.list_dictionaries({})
    assert unsafe.value.code == "resource_inventory_failed"

    (root / "unsafe slot").rmdir()
    for index in range(resources._MAX_DICTIONARY_SLOTS + 1):
        (root / f"slot{index:03d}").mkdir()
    with pytest.raises(BridgeProtocolError) as unbounded:
        resources.list_dictionaries({})
    assert unbounded.value.code == "resource_inventory_failed"


def test_non_overwrite_import_treats_broken_symlink_as_occupied(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "occupied-home"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    root = home / "dicts"
    root.mkdir()
    (root / "occupied").symlink_to(tmp_path / "missing", target_is_directory=True)

    with pytest.raises(BridgeProtocolError) as failure:
        resources.import_dictionary(
            {
                "operationId": "occupied-slot",
                "sourcePath": str(tmp_path / "also-missing.zip"),
                "slotId": "occupied",
                "overwrite": False,
                "catalogResourceId": None,
            }
        )
    assert failure.value.code == "resource_already_installed"


def test_confirmed_publish_can_repair_a_non_directory_occupied_slot(
    tmp_path: Path,
) -> None:
    home = tmp_path / "repair-home"
    final = home / "dicts" / "repairable"
    final.parent.mkdir(parents=True)
    final.write_bytes(b"damaged slot")
    candidate = tmp_path / "candidate"
    candidate.mkdir()
    (candidate / "index.sqlite").write_bytes(b"replacement")

    resources._publish_dictionary(
        candidate,
        home=home,
        slot_id="repairable",
        operation_id="repair-slot",
        overwrite=True,
    )

    assert (final / "index.sqlite").read_bytes() == b"replacement"
    assert not any((home / "resource-work" / "dictionary-backups").iterdir())


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="the lean host lane intentionally excludes runtime engine dependencies",
)
def test_yomitan_setup_error_and_cancellation_use_stable_bridge_codes(
    tmp_path: Path,
    initialized_bridge_home: Path,
) -> None:
    invalid = tmp_path / "invalid.zip"
    with zipfile.ZipFile(invalid, "w") as archive:
        archive.writestr(
            "index.json",
            json.dumps({"title": "Old fixture", "revision": "1", "format": 2}),
        )
        archive.writestr("term_bank_1.json", "[]")
    request = {
        "operationId": "invalid-import",
        "sourcePath": str(invalid),
        "slotId": "invalid",
        "overwrite": False,
        "catalogResourceId": None,
    }
    with pytest.raises(BridgeProtocolError) as failed:
        resources.import_dictionary(request)
    assert failed.value.code == "dictionary_import_failed"

    operation = resources._Operation("cancel-import")
    operation.cancelled.set()
    with pytest.raises(BridgeProtocolError) as cancelled:
        operation.check()
    assert cancelled.value.code == "resource_operation_cancelled"


def test_streamed_zip_validation_rejects_links_duplicates_and_cancel(
    tmp_path: Path,
) -> None:
    linked = tmp_path / "linked.zip"
    with zipfile.ZipFile(linked, "w") as archive:
        archive.writestr("index.json", "{}")
        info = zipfile.ZipInfo("link")
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive.writestr(info, "target")
    with pytest.raises(BridgeProtocolError, match="link or special"):
        resources._validate_zip_streamed(
            linked,
            resources._Operation("link"),
            member_limit=10,
            total_limit=1024,
            file_limit=1024,
            require_root_index=False,
        )

    duplicate = tmp_path / "duplicate.zip"
    with pytest.warns(UserWarning, match="Duplicate name"):
        with zipfile.ZipFile(duplicate, "w") as archive:
            archive.writestr("index.json", "{}")
            archive.writestr("index.json", "{}")
    with pytest.raises(BridgeProtocolError, match="duplicate"):
        resources._validate_zip_streamed(
            duplicate,
            resources._Operation("duplicate"),
            member_limit=10,
            total_limit=1024,
            file_limit=1024,
            require_root_index=False,
        )

    operation = resources._Operation("cancelled")
    operation.cancelled.set()
    with pytest.raises(BridgeProtocolError) as cancelled:
        resources._validate_zip_streamed(
            linked,
            operation,
            member_limit=10,
            total_limit=1024,
            file_limit=1024,
            require_root_index=False,
        )
    assert cancelled.value.code == "resource_operation_cancelled"


def test_lookup_html_limit_is_low_memory_safe() -> None:
    assert resources._MAX_LOOKUP_HTML_BYTES == 2 * 1024 * 1024
    resources._validate_lookup_html("x" * resources._MAX_LOOKUP_HTML_BYTES)
    with pytest.raises(BridgeProtocolError) as oversized:
        resources._validate_lookup_html("x" * (resources._MAX_LOOKUP_HTML_BYTES + 1))
    assert oversized.value.code == "dictionary_result_too_large"


# The engine's authoritative uncompressed cap, inlined so the direct
# _validate_zip_streamed tests below stay in the lean host lane (no engine
# import). test_engine_uncompressed_limit_is_single_source_of_truth proves the
# production helper actually resolves to this same value.
_ENGINE_TOTAL_LIMIT = 2 * 1024 * 1024 * 1024


def _zip_with_members(path: Path, members: list[tuple[str, bytes]]) -> Path:
    """Build a plain custom zip (stdlib only) from ``(name, content)`` members."""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in members:
            archive.writestr(name, content)
    return path


def test_streamed_zip_accepts_oversized_member_but_keeps_total_cap(tmp_path: Path) -> None:
    big = _zip_with_members(tmp_path / "big.zip", [("term_bank_1.json", b"A" * _OVERSIZED_MEMBER_BYTES)])
    # The retired 16 MiB per-file cap is gone: a single >16 MiB bank is accepted.
    identity = resources._validate_zip_streamed(
        big,
        resources._Operation("big-accept"),
        member_limit=None,
        total_limit=_ENGINE_TOTAL_LIMIT,
        file_limit=None,
        require_root_index=False,
    )
    assert isinstance(identity, resources._ZipIdentity)
    assert identity.uncompressed_bytes == _OVERSIZED_MEMBER_BYTES
    # The total-uncompressed cap is retained and still rejects the same archive.
    with pytest.raises(BridgeProtocolError, match="expands beyond") as rejected:
        resources._validate_zip_streamed(
            big,
            resources._Operation("big-reject"),
            member_limit=None,
            total_limit=16 * 1024 * 1024,
            file_limit=None,
            require_root_index=False,
        )
    assert rejected.value.code == "resource_archive_too_large"


def test_streamed_zip_preflights_default_member_cap_before_zipfile_allocation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Patch the backstop rather than build an archive sized to its real value:
    # the property under test is that an undeclared limit is enforced from the
    # central directory alone, not the specific number chosen for the backstop.
    monkeypatch.setattr(resources, "_MAX_CUSTOM_ZIP_MEMBERS", 4)
    members = [(f"term_bank_{i}.json", b"") for i in range(5)]
    many = _zip_with_members(tmp_path / "many.zip", members)

    class ZipFileMustNotBeConstructed:
        def __init__(self, *_args: object, **_kwargs: object) -> None:
            raise AssertionError("ZipFile allocated before EOCD count preflight")

    monkeypatch.setattr(zipfile, "ZipFile", ZipFileMustNotBeConstructed)
    with pytest.raises(BridgeProtocolError, match="member count") as rejected:
        resources._validate_zip_streamed(
            many,
            resources._Operation("many-reject"),
            member_limit=None,
            total_limit=_ENGINE_TOTAL_LIMIT,
            file_limit=None,
            require_root_index=False,
        )
    assert rejected.value.code == "resource_archive_too_large"


def test_streamed_zip_rejects_empty_archive_even_without_member_cap(tmp_path: Path) -> None:
    empty = _zip_with_members(tmp_path / "empty.zip", [])
    with pytest.raises(BridgeProtocolError, match="member count") as rejected:
        resources._validate_zip_streamed(
            empty,
            resources._Operation("empty"),
            member_limit=None,
            total_limit=_ENGINE_TOTAL_LIMIT,
            file_limit=None,
            require_root_index=False,
        )
    assert rejected.value.code == "resource_archive_too_large"


@pytest.mark.parametrize("resource_kind", ["frequency", "pitch"])
def test_local_zip_import_checks_expanded_peak_before_rewrite(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    resource_kind: str,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = _zip_with_members(
        tmp_path / f"{resource_kind}.zip",
        [
            ("index.json", b"{}"),
            ("term_meta_bank_1.json", b"[]"),
        ],
    )
    expanded_bytes = 1_500_000_000
    identity = resources._ZipIdentity(2, expanded_bytes)
    monkeypatch.setattr(
        resources,
        "_validate_zip_streamed",
        lambda *_args, **_kwargs: identity,
    )
    monkeypatch.setattr(resources, "_engine_uncompressed_limit", lambda: _ENGINE_TOTAL_LIMIT)
    checked: list[int] = []

    def check_space(_parent: Path, required_bytes: int) -> None:
        checked.append(required_bytes)
        if len(checked) == 2:
            raise BridgeProtocolError("insufficient_storage", "fixture storage exhausted")

    monkeypatch.setattr(resources, "_check_free_space", check_space)

    def must_not_rewrite(*_args: object, **_kwargs: object) -> Path:
        raise AssertionError("bank rewrite started before expanded peak preflight")

    monkeypatch.setattr(resources, "_rewrite_yomitan_banks", must_not_rewrite)
    request = {
        "operationId": f"{resource_kind}-space",
        "sourcePath": str(source),
        "sourceId": f"{resource_kind}-space",
        "sourceName": "Space Fixture",
        "sourceFormat": "zip",
        "overwrite": False,
    }

    with pytest.raises(BridgeProtocolError) as failure:
        if resource_kind == "frequency":
            local_resources.import_frequency(request)
        else:
            local_resources.import_pitch(request)

    assert failure.value.code == "insufficient_storage"
    assert checked == [
        source.stat().st_size,
        resources._yomitan_import_peak_bytes(
            identity,
            source.stat().st_size,
            intermediate_csv=True,
        ),
    ]


def test_streamed_zip_root_index_requirement_is_opt_in(tmp_path: Path) -> None:
    no_index = _zip_with_members(tmp_path / "no-index.zip", [("term_bank_1.json", b"[]")])
    # Custom imports let the engine importer own index.json validation.
    identity = resources._validate_zip_streamed(
        no_index,
        resources._Operation("no-index-accept"),
        member_limit=None,
        total_limit=_ENGINE_TOTAL_LIMIT,
        file_limit=None,
        require_root_index=False,
    )
    assert isinstance(identity, resources._ZipIdentity)
    # The catalog-pinned path still demands a root index.json.
    with pytest.raises(BridgeProtocolError, match="no root index") as rejected:
        resources._validate_zip_streamed(
            no_index,
            resources._Operation("no-index-reject"),
            member_limit=None,
            total_limit=_ENGINE_TOTAL_LIMIT,
            file_limit=None,
            require_root_index=True,
        )
    assert rejected.value.code == "invalid_resource_archive"


def test_streamed_zip_unsupported_compression_is_distinct_from_corrupt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Build the fixture FIRST (writestr uses ZipFile.open), then patch open() so
    # only the validator's member read fails.
    fixture = _zip_with_members(tmp_path / "method.zip", [("term_bank_1.json", b"[]")])

    def _raise_not_implemented(*args: object, **kwargs: object) -> None:
        raise NotImplementedError("compression type 9 (deflate64)")

    monkeypatch.setattr(zipfile.ZipFile, "open", _raise_not_implemented)
    with pytest.raises(BridgeProtocolError) as deflate64:
        resources._validate_zip_streamed(
            fixture,
            resources._Operation("deflate64"),
            member_limit=None,
            total_limit=_ENGINE_TOTAL_LIMIT,
            file_limit=None,
            require_root_index=False,
        )
    assert deflate64.value.code == "resource_archive_unsupported_compression"

    # A missing bz2/lzma module (RuntimeError under Chaquopy) maps to the same code.
    def _raise_missing_module(*args: object, **kwargs: object) -> None:
        raise RuntimeError("Compression requires the (missing) bz2 module")

    monkeypatch.setattr(zipfile.ZipFile, "open", _raise_missing_module)
    with pytest.raises(BridgeProtocolError) as missing_bz2:
        resources._validate_zip_streamed(
            fixture,
            resources._Operation("missing-bz2"),
            member_limit=None,
            total_limit=_ENGINE_TOTAL_LIMIT,
            file_limit=None,
            require_root_index=False,
        )
    assert missing_bz2.value.code == "resource_archive_unsupported_compression"


def test_streamed_zip_reports_genuine_corruption_with_exception_class(tmp_path: Path) -> None:
    garbage = tmp_path / "garbage.zip"
    garbage.write_bytes(b"this is definitely not a zip archive")
    with pytest.raises(BridgeProtocolError) as corrupt:
        resources._validate_zip_streamed(
            garbage,
            resources._Operation("garbage"),
            member_limit=None,
            total_limit=_ENGINE_TOTAL_LIMIT,
            file_limit=None,
            require_root_index=False,
        )
    assert corrupt.value.code == "invalid_resource_archive"
    # The residual corrupt message names the exception class for diagnosability.
    assert "BadZipFile" in str(corrupt.value)


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="importing the engine zip_safety module requires the runtime dependency set",
)
def test_engine_uncompressed_limit_is_single_source_of_truth() -> None:
    from anki_miner.services.dictionary.zip_safety import MAX_UNCOMPRESSED_BYTES

    assert resources._engine_uncompressed_limit() == 2 * 1024 * 1024 * 1024
    assert resources._engine_uncompressed_limit() == MAX_UNCOMPRESSED_BYTES


def test_cleanup_restores_crash_backup_and_removes_operation_leftovers(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "files"
    home.mkdir()
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    backup = home / "resource-work" / "dictionary-backups" / "backup-recovered--crash"
    backup.mkdir(parents=True)
    (backup / "index.sqlite").write_bytes(b"sqlite fixture")
    leftover = home / "resource-work" / "operations" / "abandoned"
    leftover.mkdir(parents=True)
    (leftover / "partial").write_bytes(b"partial")

    decoded = decode_envelope(resources.cleanup_resources({}), expected_type="resource.cleanup.result")

    assert decoded.payload == {"clean": True}
    assert (home / "dicts" / "recovered" / "index.sqlite").read_bytes() == b"sqlite fixture"
    assert not leftover.exists()
    assert not backup.exists()


def test_cleanup_refuses_to_race_active_resource_work(
    initialized_bridge_home: Path,
) -> None:
    with resources._OPERATIONS.begin("still-running"):
        with pytest.raises(BridgeProtocolError) as failure:
            resources.cleanup_resources({})
        assert failure.value.code == "resource_operation_active"

    with resources._OPERATIONS.exclusive_cleanup():
        with pytest.raises(BridgeProtocolError) as failure:
            with resources._OPERATIONS.begin("started-too-late"):
                raise AssertionError("operation should not start during cleanup")
        assert failure.value.code == "resource_cleanup_active"


def test_boundary_routes_strict_resource_catalog_and_operation_cancel(
    initialized_bridge_home: Path,
) -> None:
    catalog = decode_envelope(
        boundary.dispatch(encode_message("resource.catalog.get", {})),
        expected_type="resource.catalog",
    )
    assert catalog.payload == load_resource_catalog().payload()

    invalid = decode_envelope(
        boundary.dispatch(encode_message("resource.dictionary.list", {"extra": True})),
        expected_type="bridge.error",
    )
    assert invalid.payload["code"] == "invalid_resource_request"

    with resources._OPERATIONS.begin("active-resource") as operation:
        cancelled = decode_envelope(
            boundary.dispatch(
                encode_message(
                    "resource.operation.cancel",
                    {"operationId": "active-resource"},
                )
            ),
            expected_type="resource.operation.cancel.result",
        )
        assert cancelled.payload == {
            "operationId": "active-resource",
            "accepted": True,
        }
        assert operation.cancelled.is_set()


def test_resource_cancel_is_sticky_across_pre_registration_race() -> None:
    registry = resources._OperationRegistry()

    assert registry.cancel("future-operation") is True
    with registry.begin("future-operation") as operation:
        assert operation.cancelled.is_set()
        with pytest.raises(BridgeProtocolError) as cancelled:
            operation.check()
        assert cancelled.value.code == "resource_operation_cancelled"

    # Pending tombstones are bounded, and evicting an ancient never-started ID
    # cannot cancel an unrelated newly registered operation.
    for index in range(resources._MAX_PENDING_RESOURCE_CANCELLATIONS + 1):
        assert registry.cancel(f"pending-{index}") is True
    with registry.begin("pending-0") as evicted:
        assert not evicted.cancelled.is_set()
    with registry.begin(f"pending-{resources._MAX_PENDING_RESOURCE_CANCELLATIONS}") as newest:
        assert newest.cancelled.is_set()


def test_resource_bridge_has_no_eager_engine_imports() -> None:
    source = Path(resources.__file__).read_text(encoding="utf-8")
    prefix = source.split("def install_unidic", 1)[0]
    assert "from anki_miner" not in prefix
    assert "import anki_miner" not in prefix


def _local_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    home = tmp_path / "android-files"
    home.mkdir()
    monkeypatch.setattr(local_resources, "require_initialized", lambda: str(home))
    return home


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_frequency_import_is_indexed_inventory_visible_and_no_replace_by_default(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\n猫,10\n犬,20\n", encoding="utf-8")
    request = {
        "operationId": "frequency-one",
        "sourcePath": str(source),
        "sourceId": "fixture-freq",
        "sourceName": "Fixture Frequency",
        "sourceFormat": "csv",
        "overwrite": False,
    }

    imported = decode_envelope(
        local_resources.import_frequency(request),
        expected_type="resource.frequency.imported",
    )

    assert imported.payload["sourceId"] == "fixture-freq"
    assert imported.payload["sourceName"] == "Fixture Frequency"
    assert imported.payload["entryCount"] == 2
    final = home / "freqs" / "fixture-freq"
    assert (final / "index.sqlite").is_file()
    assert (final / "source.csv").read_text(encoding="utf-8") == source.read_text(encoding="utf-8")
    listed = decode_envelope(
        local_resources.list_local_resources({}),
        expected_type="resource.local.listed",
    )
    assert listed.payload["frequencies"] == [
        {
            "sourceId": "fixture-freq",
            "sourceName": "Fixture Frequency",
            "format": "csv",
            "entryCount": 2,
            "schemaOk": True,
            "schemaVersion": 2,
            "isCategorical": False,
        }
    ]

    with pytest.raises(BridgeProtocolError) as collision:
        local_resources.import_frequency({**request, "operationId": "frequency-two"})
    assert collision.value.code == "resource_already_installed"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_v018_pitch_csv_is_migrated_without_removing_released_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    legacy = home / "pitch_accent.csv"
    legacy.write_text(
        "reading,kanji,pattern,nasal,devoice\nねこ,猫,1,,\n",
        encoding="utf-8",
    )
    sidecar = home / "pitch_accent.android-resource.json"
    sidecar.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "sourceName": "Released v0.1.8 pitch",
                "sourceRevision": "",
                "sourceFormat": "csv",
                "entryCount": 1,
                "fileSizeBytes": legacy.stat().st_size,
                "fileSha256": hashlib.sha256(legacy.read_bytes()).hexdigest(),
            }
        ),
        encoding="utf-8",
    )
    released_csv = legacy.read_bytes()
    released_sidecar = sidecar.read_bytes()

    listed = decode_envelope(
        local_resources.list_local_resources({}),
        expected_type="resource.local.listed",
    )

    assert listed.payload["pitchSources"] == [
        {
            "sourceId": "legacy-pitch",
            "sourceName": "Pitch Accent",
            "sourceRevision": "",
            "format": "csv",
            "entryCount": 1,
            "schemaOk": True,
            "schemaVersion": 1,
        }
    ]
    migrated = home / "pitch" / "legacy-pitch" / "index.sqlite"
    assert migrated.is_file()
    first_inode = migrated.stat().st_ino
    assert legacy.read_bytes() == released_csv
    assert sidecar.read_bytes() == released_sidecar

    local_resources.list_local_resources({})

    assert migrated.stat().st_ino == first_inode
    assert legacy.read_bytes() == released_csv
    assert sidecar.read_bytes() == released_sidecar


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_pitch_csv_import_publishes_its_own_slot_and_inventory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / "pitch.tsv"
    source.write_text(
        "reading\tkanji\tpattern\tnasal\tdevoice\nねこ\t猫\t1\t\t\n",
        encoding="utf-8",
    )

    imported = decode_envelope(
        local_resources.import_pitch(
            {
                "operationId": "pitch-one",
                "sourcePath": str(source),
                "sourceId": "fixture-pitch",
                "sourceName": "Fixture Pitch",
                "sourceFormat": "tsv",
                "overwrite": False,
            }
        ),
        expected_type="resource.pitch.imported",
    )

    assert imported.payload["sourceId"] == "fixture-pitch"
    assert imported.payload["entryCount"] == 1
    assert imported.payload["sourceName"] == "Fixture Pitch"
    # A pitch source is now a per-source index under the pitch root, exactly
    # like a frequency source; there is no canonical single CSV any more.
    assert (home / "pitch" / "fixture-pitch" / "index.sqlite").is_file()
    listed = decode_envelope(
        local_resources.list_local_resources({}),
        expected_type="resource.local.listed",
    )
    assert listed.payload["pitchSources"] == [
        {
            "sourceId": "fixture-pitch",
            "sourceName": "Fixture Pitch",
            "sourceRevision": "",
            "format": "csv",
            "entryCount": 1,
            "schemaOk": True,
            "schemaVersion": 1,
        }
    ]


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="pitch validation requires the runtime engine dependency set",
)
def test_malformed_pitch_slot_is_exposed_for_same_id_replacement(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    slot = home / "pitch" / "broken"
    slot.mkdir(parents=True)
    (slot / "index.sqlite").write_bytes(b"\xff")

    listed = decode_envelope(
        local_resources.list_local_resources({}),
        expected_type="resource.local.listed",
    )

    assert listed.payload["pitchSources"] == [
        {
            "sourceId": "broken",
            "sourceName": "broken",
            "sourceRevision": "",
            "format": "unknown",
            "entryCount": 0,
            "schemaOk": False,
            "schemaVersion": 0,
        }
    ]


def _ajt_audio_zip(
    path: Path,
    audio_bytes: bytes = b"fixture mp3",
    root: str = "fixture-pack",
) -> Path:
    index = {
        "headwords": {"猫": ["cat.mp3"]},
        "files": {"cat.mp3": {"kana_reading": "ねこ"}},
    }
    prefix = f"{root}/" if root else ""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(f"{prefix}index.json", json.dumps(index, ensure_ascii=False))
        archive.writestr(f"{prefix}media/cat.mp3", audio_bytes)
    return path


def _collection_members(root: str = "user_files") -> dict[str, bytes]:
    """Two upstream-shaped packs under *root*, as archive member -> bytes.

    Mirrors the local-audio-yomichan layout: an ajt pack (index.json + media/)
    beside an nhk16 pack (entries.json + audio/).
    """
    index = json.dumps(
        {"headwords": {"猫": ["cat.mp3"]}, "files": {"cat.mp3": {"kana_reading": "ねこ"}}},
        ensure_ascii=False,
    ).encode("utf-8")
    prefix = f"{root}/" if root else ""
    return {
        f"{prefix}jpod_files/index.json": index,
        f"{prefix}jpod_files/media/cat.mp3": b"jpod audio",
        f"{prefix}nhk16_files/entries.json": b"[]",
        f"{prefix}nhk16_files/audio/20170616125910.mp3": b"nhk audio",
    }


def _zip_of(path: Path, members: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in members.items():
            archive.writestr(name, data)
    return path


def _tar_xz_of(path: Path, members: dict[str, bytes]) -> Path:
    with tarfile.open(path, "w:xz") as archive:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return path


def test_audio_archive_kind_reads_the_container_from_its_bytes(tmp_path: Path) -> None:
    members = {"index.json": b"{}"}
    zipped = _zip_of(tmp_path / "named-wrong.tar.xz", members)
    tarred = _tar_xz_of(tmp_path / "named-wrong.zip", members)
    plain = tmp_path / "notes.txt"
    plain.write_bytes(b"not an archive at all")

    assert local_resources._audio_archive_kind(zipped) == "zip"
    assert local_resources._audio_archive_kind(tarred) == "tar"
    with pytest.raises(BridgeProtocolError) as failure:
        local_resources._audio_archive_kind(plain)
    assert failure.value.code == "invalid_resource_archive"


def test_audio_pack_prefix_refuses_to_widen_the_extraction(tmp_path: Path) -> None:
    assert local_resources._audio_pack_prefix("") == ()
    assert local_resources._audio_pack_prefix("user_files/jpod_files") == (
        "user_files",
        "jpod_files",
    )
    for escape in ("../elsewhere", "/absolute", "user_files/../../etc", 7):
        with pytest.raises(BridgeProtocolError) as failure:
            local_resources._audio_pack_prefix(escape)
        assert failure.value.code == "invalid_resource_request"


def test_audio_member_guard_admits_the_real_collection_nhk16_index() -> None:
    # The 2023-06-11 upstream collection ships nhk16 entries.json at 43,944,140
    # bytes. The JSON ceiling must clear the file users actually download, or
    # every pack in the collection is rejected at preflight.
    parts = ("user_files", "nhk16_files", "entries.json")
    assert local_resources._accept_audio_member(parts, 43_944_140, set()) == local_resources._AUDIO_JSON_LIMIT


def test_audio_member_guard_names_an_oversized_member() -> None:
    parts = ("user_files", "nhk16_files", "entries.json")
    with pytest.raises(BridgeProtocolError, match="oversized") as failure:
        local_resources._accept_audio_member(parts, local_resources._AUDIO_JSON_LIMIT + 1, set())
    assert failure.value.code == "resource_archive_member_oversized"


def test_audio_extractor_names_a_member_count_rejection(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(local_resources, "_AUDIO_MEMBER_LIMIT", 2)
    members = {f"pack/{index}.mp3": b"a" for index in range(3)}
    source = _zip_of(tmp_path / "many.zip", members)
    destination = tmp_path / "extracted"
    with pytest.raises(BridgeProtocolError, match="member count") as failure:
        local_resources._extract_audio_zip(
            source,
            destination,
            resources._Operation("audio-many"),
            (),
        )
    assert failure.value.code == "resource_archive_member_count"


def test_audio_extractor_names_a_total_size_rejection(tmp_path: Path) -> None:
    source = _zip_of(tmp_path / "empty-total.zip", {"pack/empty.mp3": b""})
    destination = tmp_path / "extracted"
    with pytest.raises(BridgeProtocolError, match="expands beyond") as failure:
        local_resources._extract_audio_zip(
            source,
            destination,
            resources._Operation("audio-total"),
            (),
        )
    assert failure.value.code == "resource_archive_expands_too_large"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
@pytest.mark.parametrize("build", [_zip_of, _tar_xz_of], ids=["zip", "tar.xz"])
def test_audio_pack_preflight_reports_every_pack_in_the_collection(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    build,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = build(tmp_path / "collection", _collection_members())

    preflight = decode_envelope(
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-collection",
                "sourcePath": str(source),
                "displayName": "local-yomichan-audio-collection",
            }
        ),
        expected_type="resource.audiopack.preflighted",
    )

    assert preflight.payload == {
        "packs": [
            {"packId": "jpod", "packPath": "user_files/jpod_files", "format": "ajt"},
            {"packId": "nhk16", "packPath": "user_files/nhk16_files", "format": "nhk16"},
        ]
    }


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_preflight_clears_the_real_collection_index_size(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Regression: the upstream collection's nhk16 entries.json is 43,944,140
    # bytes, which the former 32 MiB JSON ceiling rejected — every import of
    # the collection failed at preflight on a limit no user could see.
    _local_home(tmp_path, monkeypatch)
    members = _collection_members()
    members["user_files/nhk16_files/entries.json"] = bytes(43_944_140)
    source = _zip_of(tmp_path / "collection.zip", members)

    preflight = decode_envelope(
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-real-index",
                "sourcePath": str(source),
                "displayName": "local-yomichan-audio-collection",
            }
        ),
        expected_type="resource.audiopack.preflighted",
    )

    assert [pack["packId"] for pack in preflight.payload["packs"]] == ["jpod", "nhk16"]


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_preflight_descends_a_wrapper_directory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = _zip_of(tmp_path / "wrapped.zip", _collection_members("release/user_files"))

    preflight = decode_envelope(
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-wrapped",
                "sourcePath": str(source),
                "displayName": "wrapped.zip",
            }
        ),
        expected_type="resource.audiopack.preflighted",
    )

    assert [pack["packId"] for pack in preflight.payload["packs"]] == ["jpod", "nhk16"]


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_preflight_names_an_archive_that_holds_no_pack(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = _zip_of(tmp_path / "wrong.zip", {"notes/readme.txt": b"nothing to import"})

    with pytest.raises(BridgeProtocolError) as failure:
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-empty",
                "sourcePath": str(source),
                "displayName": "wrong.zip",
            }
        )

    assert failure.value.code == "audio_pack_none_detected"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_import_extracts_only_the_chosen_pack(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = _tar_xz_of(tmp_path / "collection.tar.xz", _collection_members())

    imported = decode_envelope(
        local_resources.import_audio_pack(
            {
                "operationId": "audio-chosen",
                "sourcePath": str(source),
                "packId": "jpod",
                "packPath": "user_files/jpod_files",
                "overwrite": False,
            }
        ),
        expected_type="resource.audiopack.imported",
    )

    assert imported.payload["packId"] == "jpod"
    assert imported.payload["format"] == "ajt"
    content = home / "audio_packs" / "jpod" / "content"
    assert [path.name for path in sorted(content.rglob("*.mp3"))] == ["cat.mp3"]
    assert not (home / "audio_packs" / "nhk16").exists()


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_preflight_derives_id_without_copying_or_importing(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = _ajt_audio_zip(tmp_path / "picked-name.zip", root="nhk16_files")

    monkeypatch.setattr(
        resources,
        "_copy_archive",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("preflight must read the staged archive in place")
        ),
    )

    preflight = decode_envelope(
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-preflight",
                "sourcePath": str(source),
                "displayName": "picked-name.zip",
            }
        ),
        expected_type="resource.audiopack.preflighted",
    )

    assert preflight.payload == {"packs": [{"packId": "nhk16", "packPath": "nhk16_files", "format": "ajt"}]}
    assert not (home / "audio_packs").exists()


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_preflight_uses_display_name_stem_for_flat_archive(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = _ajt_audio_zip(tmp_path / "staged.zip", root="")

    preflight = decode_envelope(
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-flat",
                "sourcePath": str(source),
                "displayName": "My Flat Audio.zip",
            }
        ),
        expected_type="resource.audiopack.preflighted",
    )

    assert preflight.payload == {"packs": [{"packId": "my-flat-audio", "packPath": "", "format": "ajt"}]}


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_preflight_keeps_detectable_legacy_root_name(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = tmp_path / "renamed.zip"
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("jpod_files/ねこ - 猫.mp3", b"fixture mp3")

    preflight = decode_envelope(
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-legacy-root",
                "sourcePath": str(source),
                "displayName": "renamed.zip",
            }
        ),
        expected_type="resource.audiopack.preflighted",
    )

    assert preflight.payload == {"packs": [{"packId": "jpod", "packPath": "jpod_files", "format": "jpod_legacy"}]}


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_preflight_rejects_reserved_derived_id(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = _ajt_audio_zip(tmp_path / "staged.zip", root="jpod101")

    with pytest.raises(BridgeProtocolError, match="reserved") as failure:
        local_resources.preflight_audio_pack(
            {
                "operationId": "audio-reserved",
                "sourcePath": str(source),
                "displayName": "ignored.zip",
            }
        )

    assert failure.value.code == "audio_pack_id_reserved"


@pytest.mark.parametrize("storage_errno", [errno.ENOSPC, getattr(errno, "EDQUOT", 122)])
def test_audio_extractor_reports_storage_exhaustion(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    storage_errno: int,
) -> None:
    source = _ajt_audio_zip(tmp_path / "audio-storage.zip")

    def storage_full(_stream: object, _content: bytes) -> None:
        raise OSError(storage_errno, "storage exhausted")

    monkeypatch.setattr(resources, "_write_all", storage_full)
    with pytest.raises(BridgeProtocolError) as failure:
        local_resources._extract_audio_archive(
            source,
            tmp_path / "extracted",
            resources._Operation("audio-storage"),
        )

    assert failure.value.code == "insufficient_storage"


def test_audio_extractor_does_not_label_write_io_as_corrupt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _ajt_audio_zip(tmp_path / "audio-io.zip")
    monkeypatch.setattr(
        resources,
        "_write_all",
        lambda *_args: (_ for _ in ()).throw(OSError(errno.EIO, "write failed")),
    )

    with pytest.raises(BridgeProtocolError) as failure:
        local_resources._extract_audio_archive(
            source,
            tmp_path / "extracted",
            resources._Operation("audio-io"),
        )

    assert failure.value.code == "resource_install_failed"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_zip_is_private_self_contained_and_inventory_visible(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = _ajt_audio_zip(tmp_path / "audio.zip")

    imported = decode_envelope(
        local_resources.import_audio_pack(
            {
                "operationId": "audio-one",
                "sourcePath": str(source),
                "packId": "fixture-pack",
                "packPath": "fixture-pack",
                "overwrite": False,
            }
        ),
        expected_type="resource.audiopack.imported",
    )

    assert imported.payload["format"] == "ajt"
    assert imported.payload["entryCount"] == 1
    final = home / "audio_packs" / "fixture-pack"
    assert (final / "content" / "media" / "cat.mp3").read_bytes() == b"fixture mp3"
    source.unlink()

    from anki_miner.services.audio_packs import storage

    meta = storage.read_meta(final / "index.sqlite")
    assert meta["pack_dir"] == str(final / "content")
    listed = decode_envelope(
        local_resources.list_local_resources({}),
        expected_type="resource.local.listed",
    )
    assert listed.payload["audioPacks"] == [
        {
            "packId": "fixture-pack",
            "sourceName": "fixture-pack",
            "format": "ajt",
            "entryCount": 1,
            "contentAvailable": True,
        }
    ]


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_replacing_audio_pack_does_not_reuse_previous_run_cache(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from android_bridge import mining
    from android_bridge.expression_audio_fetcher import _RunAudioCache
    from anki_miner.services.audio_packs.fetcher import LocalAudioPackFetcher

    home = _local_home(tmp_path, monkeypatch)
    first_source = _ajt_audio_zip(tmp_path / "audio-first.zip", b"first recording")
    request = {
        "operationId": "audio-first",
        "sourcePath": str(first_source),
        "packId": "fixture-pack",
        "packPath": "fixture-pack",
        "overwrite": False,
    }
    local_resources.import_audio_pack(request)

    installed = home / "audio_packs" / "fixture-pack"
    cache_root = home / "audio_cache" / "local_packs"

    def fetch_for_one_run() -> bytes:
        lifetime = _RunAudioCache(cache_root)
        fetcher = LocalAudioPackFetcher(
            db_path=installed / "index.sqlite",
            pack_dir=installed / "content",
            pack_id="fixture-pack",
            cache_dir=cache_root,
        )
        chain = mining._ExpressionAudioSourceChain([fetcher], cache_lifetime=lifetime)
        try:
            cached = chain.fetch("猫", "ねこ")
            assert cached is not None
            return cached.read_bytes()
        finally:
            chain.close()

    assert fetch_for_one_run() == b"first recording"
    assert list(cache_root.rglob("*")) == []

    replacement = _ajt_audio_zip(tmp_path / "audio-replacement.zip", b"corrected recording")
    local_resources.import_audio_pack(
        {
            **request,
            "operationId": "audio-replacement",
            "sourcePath": str(replacement),
            "overwrite": True,
        }
    )

    assert fetch_for_one_run() == b"corrected recording"


def test_audio_pack_streaming_extractor_rejects_links(
    tmp_path: Path,
) -> None:
    linked = tmp_path / "linked-audio.zip"
    with zipfile.ZipFile(linked, "w") as archive:
        archive.writestr("index.json", "{}")
        info = zipfile.ZipInfo("media/link.mp3")
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive.writestr(info, "target")

    with pytest.raises(BridgeProtocolError) as failure:
        local_resources._extract_audio_archive(
            linked,
            tmp_path / "extracted",
            resources._Operation("audio-link"),
        )
    assert failure.value.code == "unsafe_resource_archive"
    assert not (tmp_path / "extracted" / "media" / "link.mp3").exists()


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
@pytest.mark.parametrize(
    ("source_format", "content"),
    [
        ("txt", "一\n二\n三\n四\n"),
        (
            "json",
            json.dumps(
                {"words": [{"word": word, "status": "KNOWN"} for word in ("一", "二", "三", "四")]},
                ensure_ascii=False,
            ),
        ),
    ],
)
def test_known_words_limit_is_enforced_before_result_materialization(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    source_format: str,
    content: str,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = tmp_path / f"too-many-known-words.{source_format}"
    source.write_text(content, encoding="utf-8")
    from anki_miner.services import known_words_import

    monkeypatch.setattr(local_resources, "_MAX_KNOWN_WORDS", 3)

    def materialized_too_late(_words: object) -> object:
        raise AssertionError("known-word result materialized before count rejection")

    monkeypatch.setattr(known_words_import, "frozenset", materialized_too_late, raising=False)

    with pytest.raises(BridgeProtocolError) as failure:
        local_resources.preview_known_words(
            {
                "operationId": "known-words-limit",
                "sourcePath": str(source),
                "sourceFormat": source_format,
            }
        )

    assert failure.value.code == "known_words_import_failed"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_known_words_import_is_transactional_and_wordsets_are_bundled(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = tmp_path / "known.txt"
    source.write_text("# known words\n猫\n犬\n猫\n", encoding="utf-8")

    imported = decode_envelope(
        local_resources.import_known_words(
            {
                "operationId": "known-one",
                "sourcePath": str(source),
                "sourceFormat": "txt",
            }
        ),
        expected_type="resource.knownwords.imported",
    )
    assert imported.payload == {
        "format": "generic",
        "importedCount": 2,
        "newRowCount": 2,
        "totalEntries": 3,
        "isGeneric": True,
    }

    listed = decode_envelope(
        local_resources.list_local_resources({}),
        expected_type="resource.local.listed",
    )
    assert listed.payload["knownWords"] == {
        "totalCount": 2,
        "userCount": 2,
        "ankiCount": 0,
        "minedCount": 0,
        "schemaOk": True,
    }
    assert [item["wordsetId"] for item in listed.payload["wordsets"]] == [
        "surnames",
        "given-names",
        "place-names",
        "org-product",
    ]


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
@pytest.mark.parametrize(
    ("payload", "expected_format"),
    (
        (
            {
                "cards_vocabulary_jp_en": [
                    {
                        "spelling": "  食べる  ",
                        "reviews": [{"grade": "easy", "timestamp": 1}],
                    }
                ]
            },
            "jpdb",
        ),
        (
            {"words": [{"word": "  食べる  ", "status": "KNOWN"}]},
            "migaku_json",
        ),
    ),
)
def test_known_words_json_import_strips_words_and_reports_non_generic(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    payload: dict[str, object],
    expected_format: str,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / f"{expected_format}.json"
    source.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    imported = decode_envelope(
        local_resources.import_known_words(
            {
                "operationId": f"known-{expected_format}",
                "sourcePath": str(source),
                "sourceFormat": "json",
            }
        ),
        expected_type="resource.knownwords.imported",
    )

    assert imported.payload == {
        "format": expected_format,
        "importedCount": 1,
        "newRowCount": 1,
        "totalEntries": 1,
        "isGeneric": False,
    }
    from anki_miner.services.known_word_db import KnownWordDB

    assert KnownWordDB(home / "known_words.db").get_known_words() == {"食べる"}


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_jpdb_import_tolerates_mixed_timestamp_types(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A hand-edited export mixing str and int timestamps must import, not raise.

    ``_review_timestamp`` coerces a non-numeric timestamp to 0.0 so ``max()``
    never compares str against int. Android reaches that sort only through the
    streamed importer, which desktop's own test does not cover.
    """

    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / "jpdb.json"
    source.write_text(
        json.dumps(
            {
                "cards_vocabulary_jp_en": [
                    {
                        "spelling": "食べる",
                        "reviews": [
                            {"grade": "okay", "timestamp": 100},
                            {"grade": "nothing", "timestamp": "2026-01-01"},
                        ],
                    }
                ]
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    imported = decode_envelope(
        local_resources.import_known_words(
            {
                "operationId": "known-jpdb-mixed-timestamps",
                "sourcePath": str(source),
                "sourceFormat": "json",
            }
        ),
        expected_type="resource.knownwords.imported",
    )

    assert imported.payload["format"] == "jpdb"
    assert imported.payload["importedCount"] == 1

    from anki_miner.services.known_word_db import KnownWordDB

    assert KnownWordDB(home / "known_words.db").get_known_words() == {"食べる"}


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
@pytest.mark.parametrize(
    "separator",
    ("\r", "\n", "\v", "\f", "\x1c", "\x1d", "\x1e", "\x85", "\u2028", "\u2029"),
)
def test_known_words_import_rejects_embedded_line_separators(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    separator: str,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = tmp_path / "embedded-separator.json"
    source.write_text(
        json.dumps(
            {"words": [{"word": f"犬{separator}猫", "status": "KNOWN"}]},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    with pytest.raises(BridgeProtocolError) as failure:
        local_resources.import_known_words(
            {
                "operationId": "known-embedded-separator",
                "sourcePath": str(source),
                "sourceFormat": "json",
            }
        )

    assert failure.value.code == "known_words_import_failed"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_known_words_import_accepts_cp932_generic_lists(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / "known-cp932.txt"
    source.write_bytes("食べる\n犬\n".encode("cp932"))

    imported = decode_envelope(
        local_resources.import_known_words(
            {
                "operationId": "known-cp932",
                "sourcePath": str(source),
                "sourceFormat": "txt",
            }
        ),
        expected_type="resource.knownwords.imported",
    )

    assert imported.payload == {
        "format": "generic",
        "importedCount": 2,
        "newRowCount": 2,
        "totalEntries": 2,
        "isGeneric": True,
    }
    from anki_miner.services.known_word_db import KnownWordDB

    assert KnownWordDB(home / "known_words.db").get_known_words() == {"食べる", "犬"}


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_known_words_preview_detects_format_without_mutating_database(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / "known.txt"
    source.write_text("# known words\n犬\n猫\n犬\n", encoding="utf-8")

    preview = decode_envelope(
        local_resources.preview_known_words(
            {
                "operationId": "known-preview",
                "sourcePath": str(source),
                "sourceFormat": "txt",
            }
        ),
        expected_type="resource.knownwords.previewed",
    )

    assert preview.payload == {
        "format": "generic",
        "importedCount": 2,
        "totalEntries": 3,
        "isGeneric": True,
        "sampleWords": ["犬", "猫"],
    }
    assert not (home / "known_words.db").exists()


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="known-word management uses the runtime engine database",
)
def test_known_words_list_search_remove_export_and_scoped_resets(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    from anki_miner.services.known_word_db import KnownWordDB

    database = KnownWordDB(home / "known_words.db")
    database.initialize()
    database.add_words({"犬", "猫", "食べる"}, source="user")
    database.add_words({"既知"}, source="anki")
    database.add_words({"掘る"}, source="mined")

    first = decode_envelope(
        local_resources.list_known_words({"operationId": "known-list-one", "query": "", "offset": 0, "limit": 2}),
        expected_type="resource.knownwords.listed",
    )
    assert first.payload == {
        "query": "",
        "offset": 0,
        "totalCount": 3,
        "words": ["犬", "猫"],
        "hasMore": True,
    }

    searched = decode_envelope(
        local_resources.list_known_words({"operationId": "known-list-search", "query": "食", "offset": 0, "limit": 50}),
        expected_type="resource.knownwords.listed",
    )
    assert searched.payload["words"] == ["食べる"]
    assert searched.payload["totalCount"] == 1

    removed = decode_envelope(
        local_resources.remove_known_words({"operationId": "known-remove", "words": ["猫", "既知"]}),
        expected_type="resource.knownwords.removed",
    )
    assert removed.payload == {"removedCount": 1}
    assert database.get_words_by_source("user") == {"犬", "食べる"}
    assert database.get_words_by_source("anki") == {"既知"}

    exported = decode_envelope(
        local_resources.export_known_words({"operationId": "known-export"}),
        expected_type="resource.knownwords.exported",
    )
    export_path = Path(exported.payload["exportPath"])
    assert export_path.read_text(encoding="utf-8") == "犬\n食べる\n"
    assert exported.payload["exportedCount"] == 2
    assert exported.payload["sizeBytes"] == export_path.stat().st_size

    rebuilt = decode_envelope(
        local_resources.reset_known_words({"operationId": "known-rebuild", "scope": "cache"}),
        expected_type="resource.knownwords.reset",
    )
    assert rebuilt.payload == {"scope": "cache", "removedCount": 2}
    assert database.get_known_words() == {"犬", "食べる"}

    reset = decode_envelope(
        local_resources.reset_known_words({"operationId": "known-reset-user", "scope": "user"}),
        expected_type="resource.knownwords.reset",
    )
    assert reset.payload == {"scope": "user", "removedCount": 2}
    assert database.get_known_words() == set()


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="known-word management uses the runtime engine database",
)
def test_known_words_export_reimport_round_trips_rows_and_counts(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / "known-round-trip.txt"
    source.write_text("犬\n猫\n食べる\n", encoding="utf-8")

    first_import = decode_envelope(
        local_resources.import_known_words(
            {
                "operationId": "known-round-trip-import-one",
                "sourcePath": str(source),
                "sourceFormat": "txt",
            }
        ),
        expected_type="resource.knownwords.imported",
    )
    exported = decode_envelope(
        local_resources.export_known_words({"operationId": "known-round-trip-export"}),
        expected_type="resource.knownwords.exported",
    )
    export_path = Path(exported.payload["exportPath"])

    assert first_import.payload["importedCount"] == 3
    assert exported.payload["exportedCount"] == 3
    assert export_path.read_text(encoding="utf-8").splitlines() == ["犬", "猫", "食べる"]

    reset = decode_envelope(
        local_resources.reset_known_words({"operationId": "known-round-trip-reset", "scope": "user"}),
        expected_type="resource.knownwords.reset",
    )
    second_import = decode_envelope(
        local_resources.import_known_words(
            {
                "operationId": "known-round-trip-import-two",
                "sourcePath": str(export_path),
                "sourceFormat": "txt",
            }
        ),
        expected_type="resource.knownwords.imported",
    )
    inventory = decode_envelope(
        local_resources.list_local_resources({}),
        expected_type="resource.local.listed",
    ).payload["knownWords"]

    assert reset.payload["removedCount"] == 3
    assert second_import.payload["importedCount"] == exported.payload["exportedCount"]
    assert second_import.payload["newRowCount"] == exported.payload["exportedCount"]
    assert second_import.payload["totalEntries"] == exported.payload["exportedCount"]
    assert inventory["totalCount"] == exported.payload["exportedCount"]
    assert inventory["userCount"] == exported.payload["exportedCount"]

    from anki_miner.services.known_word_db import KnownWordDB

    assert KnownWordDB(home / "known_words.db").get_words_by_source("user") == {
        "犬",
        "猫",
        "食べる",
    }


def test_cleanup_restores_frequency_and_audio_pack_backups(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = _local_home(tmp_path, monkeypatch)
    monkeypatch.setattr(resources, "require_initialized", lambda: str(home))
    for kind, _final_root, require_content in (
        ("frequency", home / "freqs", False),
        ("audio-pack", home / "audio_packs", True),
    ):
        backup = home / "resource-work" / f"{kind}-backups" / "backup-fixture--crash"
        backup.mkdir(parents=True)
        (backup / "index.sqlite").write_bytes(b"sqlite")
        if require_content:
            (backup / "content").mkdir()

    decoded = decode_envelope(resources.cleanup_resources({}), expected_type="resource.cleanup.result")

    assert decoded.payload == {"clean": True}
    assert (home / "freqs" / "fixture" / "index.sqlite").is_file()
    assert (home / "audio_packs" / "fixture" / "content").is_dir()


def test_boundary_routes_local_resource_inventory(
    initialized_bridge_home: Path,
) -> None:
    decoded = decode_envelope(
        boundary.dispatch(encode_message("resource.local.list", {})),
        expected_type="resource.local.listed",
    )
    assert decoded.payload["frequencies"] == []
    assert decoded.payload["audioPacks"] == []

    if importlib.util.find_spec("requests") is not None:
        known = decode_envelope(
            boundary.dispatch(
                encode_message(
                    "resource.knownwords.list",
                    {"operationId": "known-boundary", "query": "", "offset": 0, "limit": 20},
                )
            ),
            expected_type="resource.knownwords.listed",
        )
        assert known.payload["words"] == []


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="the lean host lane intentionally excludes runtime engine dependencies",
)
def test_custom_dictionary_import_accepts_oversized_term_bank(
    tmp_path: Path,
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # 大辞泉-shaped: a legitimate term_bank_*.json larger than the retired 16 MiB
    # per-file cap must import now that the bridge defers to the engine's limits.
    index = {"title": "Big Fixture", "revision": "1", "format": 3}
    row_count = _OVERSIZED_MEMBER_BYTES // _BANK_FIXTURE_ENTRY_BYTES + 1
    rows = [
        [f"fixture-{index}", "", "", "", 0, ["x" * _BANK_FIXTURE_ENTRY_BYTES], index, ""] for index in range(row_count)
    ]
    source = tmp_path / "big-dict.zip"
    with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.json", json.dumps(index, ensure_ascii=False))
        archive.writestr("term_bank_1.json", json.dumps(rows, ensure_ascii=False))
    original_read_text = Path.read_text

    def reject_materialized_bank(path: Path, *args: object, **kwargs: object) -> str:
        if (
            path.name.startswith(("term_bank_", "term_meta_bank_", "tag_bank_"))
            and path.stat().st_size > _STREAMED_BANK_BYTES
        ):
            raise AssertionError("Yomitan bank exceeds streamed chunk size")
        return original_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", reject_materialized_bank)

    imported = decode_envelope(
        resources.import_dictionary(
            {
                "operationId": "big-dict",
                "sourcePath": str(source),
                "slotId": "bigdict",
                "overwrite": False,
                "catalogResourceId": None,
            }
        ),
        expected_type="resource.dictionary.imported",
    )
    assert imported.payload["slotId"] == "bigdict"
    assert imported.payload["entryCount"] >= 1


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_pitch_zip_import_accepts_oversized_meta_bank(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # NHK2016-shaped pitch route through import_pitch(zip) -> import_yomitan_pitch_zip.
    _local_home(tmp_path, monkeypatch)
    source = _yomitan_meta_bank_zip(
        tmp_path / "pitch.zip",
        entry=["猫", "pitch", {"reading": "ねこ", "pitches": [{"position": 0}]}],
    )
    original_read_text = Path.read_text

    def reject_materialized_bank(path: Path, *args: object, **kwargs: object) -> str:
        if path.name.startswith("term_meta_bank_") and path.stat().st_size > _STREAMED_BANK_BYTES:
            raise AssertionError("Yomitan meta bank exceeds streamed chunk size")
        return original_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", reject_materialized_bank)
    imported = decode_envelope(
        local_resources.import_pitch(
            {
                "operationId": "pitch-zip",
                "sourcePath": str(source),
                "sourceId": "pitch-zip",
                "sourceName": "Fixture NHK",
                "sourceFormat": "zip",
                "overwrite": False,
            }
        ),
        expected_type="resource.pitch.imported",
    )
    assert imported.payload["entryCount"] >= 1
    assert imported.payload["sourceFormat"] == "yomitan-pitch"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_frequency_zip_import_accepts_oversized_meta_bank(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Frequency route through import_frequency(zip) -> import_frequency_source.
    _local_home(tmp_path, monkeypatch)
    source = _yomitan_meta_bank_zip(
        tmp_path / "freq.zip",
        entry=["猫", "freq", 10],
        frequency_mode="rank",
    )
    original_read_text = Path.read_text

    def reject_materialized_bank(path: Path, *args: object, **kwargs: object) -> str:
        if path.name.startswith("term_meta_bank_") and path.stat().st_size > _STREAMED_BANK_BYTES:
            raise AssertionError("Yomitan meta bank exceeds streamed chunk size")
        return original_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", reject_materialized_bank)
    imported = decode_envelope(
        local_resources.import_frequency(
            {
                "operationId": "freq-zip",
                "sourcePath": str(source),
                "sourceId": "fixture-freq-zip",
                "sourceName": "Fixture Freq",
                "sourceFormat": "zip",
                "overwrite": False,
            }
        ),
        expected_type="resource.frequency.imported",
    )
    assert imported.payload["entryCount"] >= 1


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_pitch_zip_unsupported_compression_propagates_verbatim(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # The bridge validator raises before import_yomitan_pitch_zip runs; the code
    # must propagate verbatim, uncaught by the pitch (SetupError, UnicodeError,
    # csv.Error, OSError) except -> would otherwise be relabeled pitch_import_failed.
    _local_home(tmp_path, monkeypatch)
    source = _yomitan_meta_bank_zip(
        tmp_path / "pitch-method.zip",
        entry=["猫", "pitch", {"reading": "ねこ", "pitches": [{"position": 0}]}],
    )

    def _raise_not_implemented(*args: object, **kwargs: object) -> None:
        raise NotImplementedError("compression type 9 (deflate64)")

    monkeypatch.setattr(zipfile.ZipFile, "open", _raise_not_implemented)
    with pytest.raises(BridgeProtocolError) as failed:
        local_resources.import_pitch(
            {
                "operationId": "pitch-method",
                "sourcePath": str(source),
                "sourceId": "pitch-method",
                "sourceName": "Fixture NHK",
                "sourceFormat": "zip",
                "overwrite": False,
            }
        )
    assert failed.value.code == "resource_archive_unsupported_compression"


def test_hash_archive_matches_a_copy_without_writing_one(tmp_path: Path) -> None:
    source = tmp_path / "pack.zip"
    payload = b"local audio pack bytes" * 4096
    source.write_bytes(payload)
    destination = tmp_path / "copied.zip"

    copied = resources._copy_archive(
        source,
        destination,
        resources._Operation("copy"),
        maximum_bytes=len(payload),
    )
    measured = resources._hash_archive(
        source,
        resources._Operation("hash"),
        maximum_bytes=len(payload),
    )

    assert measured.sha256 == copied.sha256 == hashlib.sha256(payload).hexdigest()
    assert measured.size_bytes == copied.size_bytes == len(payload)
    # The point of the whole change: no second multi-gigabyte tree on disk.
    assert measured.path == source
    assert sorted(child.name for child in tmp_path.iterdir()) == ["copied.zip", "pack.zip"]


def test_hash_archive_rejects_an_oversized_source(tmp_path: Path) -> None:
    source = tmp_path / "pack.zip"
    source.write_bytes(b"x" * 4096)

    with pytest.raises(BridgeProtocolError, match="outside its limit") as rejected:
        resources._hash_archive(
            source,
            resources._Operation("too-big"),
            maximum_bytes=1024,
        )
    assert rejected.value.code == "resource_archive_too_large"


def test_hash_archive_rejects_a_symlinked_source(tmp_path: Path) -> None:
    real = tmp_path / "real.zip"
    real.write_bytes(b"y" * 64)
    link = tmp_path / "link.zip"
    link.symlink_to(real)

    with pytest.raises(BridgeProtocolError) as rejected:
        resources._hash_archive(
            link,
            resources._Operation("symlink"),
            maximum_bytes=1024,
        )
    assert rejected.value.code == "invalid_resource_path"


def test_hash_archive_honours_cancellation(tmp_path: Path) -> None:
    source = tmp_path / "pack.zip"
    source.write_bytes(b"z" * 4096)
    operation = resources._Operation("cancelled")
    operation.cancelled.set()

    with pytest.raises(BridgeProtocolError) as rejected:
        resources._hash_archive(source, operation, maximum_bytes=8192)
    assert rejected.value.code == "resource_operation_cancelled"


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_audio_pack_import_reads_the_staged_zip_without_copying_it(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _local_home(tmp_path, monkeypatch)
    source = _ajt_audio_zip(tmp_path / "audio.zip")

    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError("import_audio_pack must not copy the staged archive")

    monkeypatch.setattr(resources, "_copy_archive", refuse)

    imported = decode_envelope(
        local_resources.import_audio_pack(
            {
                "operationId": "audio-no-copy",
                "sourcePath": str(source),
                "packId": "fixture-pack",
                "packPath": "fixture-pack",
                "overwrite": False,
            }
        ),
        expected_type="resource.audiopack.imported",
    )

    assert imported.payload["archiveSha256"] == hashlib.sha256(source.read_bytes()).hexdigest()


@pytest.mark.skipif(
    importlib.util.find_spec("requests") is None,
    reason="local-resource importers require the runtime engine dependency set",
)
def test_known_words_import_cancelled_before_commit_writes_nothing(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A Cancel delivered after parsing must not still commit the import."""
    home = _local_home(tmp_path, monkeypatch)
    source = tmp_path / "known.txt"
    source.write_text("猫\n犬\n", encoding="utf-8")

    from anki_miner.services.known_word_db import KnownWordDB

    real_initialize = KnownWordDB.initialize

    def cancel_during_initialize(self: KnownWordDB) -> None:
        # Schema setup is the last step before the durable write; cancelling here
        # lands in the window the final check has to cover.
        real_initialize(self)
        resources._OPERATIONS.cancel("known-cancel")

    monkeypatch.setattr(KnownWordDB, "initialize", cancel_during_initialize)

    with pytest.raises(BridgeProtocolError) as cancelled:
        local_resources.import_known_words(
            {
                "operationId": "known-cancel",
                "sourcePath": str(source),
                "sourceFormat": "txt",
            }
        )

    assert cancelled.value.code == "resource_operation_cancelled"
    database = KnownWordDB(home / "known_words.db")
    monkeypatch.setattr(KnownWordDB, "initialize", real_initialize)
    assert database.get_known_words() == set()
