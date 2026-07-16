from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import stat
import tarfile
import zipfile
from dataclasses import replace
from pathlib import Path

import pytest

import android_bridge.resources as resources
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
    with tarfile.open(
        fileobj=output, mode="w:gz", format=tarfile.PAX_FORMAT
    ) as archive:
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
    assert (
        unidic.archive.sha256
        == "db9d4572d9fdd4d00a97949d4b0741ec480ee05a7e7e2e32f547500dae27b245"
    )
    assert unidic.archive.size_bytes == 47_356_746
    assert unidic.install.file_count == 19
    assert unidic.install.size_bytes == 260_467_176
    assert (
        unidic.install.tree_sha256
        == "bd942f1b395aa7c56fe20321dc7f021930e29107f6b2949a49f5c56caab55ea7"
    )
    assert {notice.license for notice in unidic.attribution} == {"MIT", "BSD-3-Clause"}

    assert isinstance(jitendex, YomitanResource)
    assert jitendex.slot_id == "jitendex"
    assert (
        jitendex.archive.sha256
        == "807d911114af9d2154d270702972aafb2b6a6c2dc2400afa98db870d035c1a0b"
    )
    assert jitendex.dictionary.title == "Jitendex.org [2026-07-09]"
    assert jitendex.dictionary.revision == "2026.07.09.0"
    assert jitendex.dictionary.member_count == 473
    assert jitendex.dictionary.uncompressed_bytes == 540_565_403
    assert {notice.license for notice in jitendex.attribution} >= {
        "CC-BY-SA-4.0",
        "EDRDG-Licence",
        "CC-BY-2.0-FR",
    }


def test_catalog_parser_rejects_duplicate_keys_unknown_fields_and_mutable_urls() -> (
    None
):
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

    payload["resources"][0]["archive"]["url"] = (
        load_resource_catalog().payload()["resources"][0]["archive"]["url"]
    )
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
    assert json.loads(
        (final / resources._MANIFEST_NAME).read_text()
    ) == resources._unidic_manifest(resource)
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
def test_unidic_extractor_rejects_unsafe_or_excess_members(
    tmp_path: Path, case: str
) -> None:
    archive, resource = _unsafe_tar_case(tmp_path, case)
    source = _write(tmp_path / f"{case}.tar.gz", archive)
    staging = tmp_path / f"staging-{case}"
    staging.mkdir()

    with pytest.raises(BridgeProtocolError) as error:
        resources._extract_unidic(source, staging, resource, resources._Operation(case))
    assert error.value.code in {"unsafe_resource_archive", "resource_archive_too_large"}


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

    listed = decode_envelope(
        resources.list_dictionaries({}), expected_type="resource.dictionary.listed"
    )
    assert listed.payload["dictionaries"][0]["embeddedAttribution"] == {
        "author": "Fixture Author",
        "attribution": "Fixture Attribution",
    }
    lookup = decode_envelope(
        resources.lookup_dictionary({"slotId": "fixture", "term": "猫"}),
        expected_type="resource.dictionary.lookup.result",
    )
    assert "cat" in lookup.payload["html"]

    second = _yomitan_zip(
        tmp_path / "second.zip", term="犬", meaning="dog", revision="2"
    )
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
        )
    assert cancelled.value.code == "resource_operation_cancelled"


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

    decoded = decode_envelope(
        resources.cleanup_resources({}), expected_type="resource.cleanup.result"
    )

    assert decoded.payload == {"clean": True}
    assert (
        home / "dicts" / "recovered" / "index.sqlite"
    ).read_bytes() == b"sqlite fixture"
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


def test_resource_bridge_has_no_eager_engine_imports() -> None:
    source = Path(resources.__file__).read_text(encoding="utf-8")
    prefix = source.split("def install_unidic", 1)[0]
    assert "from anki_miner" not in prefix
    assert "import anki_miner" not in prefix
