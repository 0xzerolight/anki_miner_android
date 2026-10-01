"""Language data: data-only pack components, downloaded and extracted, never code.

Every ``language-data`` catalog entry is generated from a vendored ``pack.py``.
The install runs the vendored ``pack_installer._extract_component`` behind a
pre-filter that refuses an archive carrying any code member, into exactly the
layout ``language_pack_installer.component_path`` reads. The bridge never calls
the engine's own installer or its ``sys.path`` hooks: that code is vendored into
the APK only because the availability probes import it.
"""

from __future__ import annotations

import ast
import hashlib
import io
import tarfile
import zipfile
from dataclasses import replace
from pathlib import Path

import android_bridge.language_data as language_data
import android_bridge.local_resources as local_resources
import android_bridge.resources as resources
import pytest
from android_bridge import boundary
from android_bridge.languages import DOWNLOADABLE_DATA_COMPONENTS
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message
from android_bridge.resource_catalog import (
    InnerDigest,
    LanguageDataResource,
    load_resource_catalog,
    load_resource_catalogs,
    parse_catalog_json,
)

_PROJECT = Path(__file__).resolve().parents[3]
_BRIDGE_SOURCES = (
    _PROJECT / "app" / "src" / "main" / "python" / "android_bridge",
    _PROJECT / "app" / "src" / "debug" / "python",
)
#: The engine's installer and sys.path hooks. Vendored (the availability probes
#: import their module), never called: Android downloads data, not packages.
_FORBIDDEN = frozenset(
    {"install_language_pack", "install_components", "append_to_syspath", "ensure_language_packs_on_syspath"}
)


def _data_entries() -> list[tuple[str, LanguageDataResource]]:
    return [(catalog.language, entry) for catalog in load_resource_catalogs() for entry in catalog.language_data]


# --------------------------------------------------------------- host lane


def test_the_bridge_never_calls_the_engines_pack_installer_or_syspath_hooks() -> None:
    offenders = []
    for root in _BRIDGE_SOURCES:
        for path in sorted(root.rglob("*.py")):
            for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
                names = []
                if isinstance(node, ast.Name):
                    names.append(node.id)
                elif isinstance(node, ast.Attribute):
                    names.append(node.attr)
                elif isinstance(node, ast.alias):
                    names.append(node.name.rsplit(".", 1)[-1])
                elif isinstance(node, ast.Constant) and isinstance(node.value, str):
                    names.append(node.value)
                offenders += [
                    f"{path.relative_to(_PROJECT)}:{getattr(node, 'lineno', '?')} {name}"
                    for name in names
                    if name in _FORBIDDEN
                ]

    assert not offenders, offenders


def test_the_guard_sees_every_way_a_call_could_be_spelled(tmp_path: Path) -> None:
    probe = (
        "from anki_miner.services.pack_installer import append_to_syspath\n"
        "import anki_miner.services.language_pack_installer as installer\n"
        "installer.install_language_pack('ar')\n"
        "getattr(installer, 'ensure_language_packs_on_syspath')()\n"
    )
    seen = set()
    for node in ast.walk(ast.parse(probe)):
        if isinstance(node, ast.alias):
            seen.add(node.name.rsplit(".", 1)[-1])
        elif isinstance(node, ast.Attribute):
            seen.add(node.attr)
        elif isinstance(node, ast.Constant) and isinstance(node.value, str):
            seen.add(node.value)
    assert {"append_to_syspath", "install_language_pack", "ensure_language_packs_on_syspath"} <= seen & _FORBIDDEN


def test_the_catalog_language_data_is_exactly_the_downloadable_component_table() -> None:
    """``language_data_required`` is promised only for data a catalog actually pins."""

    assert {(code, entry.import_name) for code, entry in _data_entries()} == DOWNLOADABLE_DATA_COMPONENTS


@pytest.mark.parametrize(
    ("name", "code"),
    [
        ("pkg/__init__.py", True),
        ("pkg/module.pyc", True),
        ("_native.abi3.so", True),
        ("libs/libfoo.so.1", True),
        # Case variants: the suffix is code whatever its case.
        ("a.PY", True),
        ("a.Py", True),
        ("pkg/module.PYC", True),
        ("lib.SO", True),
        ("lib.So.1", True),
        # Traversal and absolute spellings are judged by their normalised path.
        ("../x.py", True),
        ("/abs/evil.py", True),
        ("hazm/data/../../evil.So", True),
        ("x\\evil.PY", True),
        # A trailing ".." or "." the extractor's resolve() collapses: it writes evil.py / b.so.
        ("evil.py/junk/..", True),
        ("lib.so/x/..", True),
        ("x/evil.py/junk/..", True),
        ("x/evil.PY/./", True),
        ("x/a.so/../b.so", True),
        # Any component named like code, not only the last.
        ("x.so/data.bin", True),
        ("morphology.db", False),
        ("hazm/data/words.dat", False),
        ("LICENSE", False),
        ("notes.py.txt", False),
    ],
)
def test_code_members_are_recognised_by_name(name: str, code: bool) -> None:
    assert language_data.is_code_member(name) is code


@pytest.mark.parametrize(
    ("target", "escaping"),
    [
        ("../x.dat", True),
        ("a/../../x.dat", True),
        ("/abs/x.dat", True),
        ("..", True),
        ("a/../b.dat", False),
        ("sub/x/..", False),
        ("words.dat", False),
    ],
)
def test_an_extraction_target_that_leaves_its_directory_is_recognised(target: str, escaping: bool) -> None:
    assert language_data.escapes(target) is escaping


def test_language_data_cannot_be_recommended_or_pinned_twice() -> None:
    payload = load_resource_catalog("ar").payload()
    payload["recommended"] = [payload["resources"][0]["resourceId"]]
    with pytest.raises(BridgeProtocolError, match="names language data"):
        parse_catalog_json(__import__("json").dumps(payload))

    payload = load_resource_catalog("ar").payload()
    duplicate = dict(payload["resources"][0], resourceId="ar-calima-msa-copy")
    payload["resources"].append(duplicate)
    with pytest.raises(BridgeProtocolError, match="twice"):
        parse_catalog_json(__import__("json").dumps(payload))


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("memberPrefix", "../escape/"),
        ("memberPrefix", "no-trailing-slash"),
        ("sentinels", []),
        ("sentinels", ["/absolute"]),
        ("exclude", ["a/../b"]),
    ],
)
def test_the_parser_refuses_an_unsafe_install_identity(field: str, value: object) -> None:
    import json

    payload = load_resource_catalog("fa").payload()
    payload["resources"][0]["install"][field] = value

    with pytest.raises(BridgeProtocolError) as failure:
        parse_catalog_json(json.dumps(payload))
    assert failure.value.code == "invalid_resource_catalog"


def test_inventory_reports_only_complete_components(tmp_path: Path) -> None:
    (code, entry), *_ = _data_entries()
    component = tmp_path / "language_packs" / code / entry.import_name
    component.mkdir(parents=True)

    assert entry.resource_id not in language_data.installed_language_data(tmp_path)

    for name in entry.install.sentinels:
        (component / name).write_bytes(b"x")
    assert entry.resource_id in language_data.installed_language_data(tmp_path)

    sentinel = component / entry.install.sentinels[0]
    sentinel.unlink()
    sentinel.symlink_to(tmp_path / "elsewhere")
    assert entry.resource_id not in language_data.installed_language_data(tmp_path)


def test_the_local_inventory_carries_the_installed_language_data(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(local_resources, "require_initialized", lambda: str(tmp_path))
    monkeypatch.setattr(resources, "require_initialized", lambda: str(tmp_path))
    monkeypatch.setattr(local_resources, "installed_language_data", lambda home: ["ar-calima-msa"])

    listed = decode_envelope(local_resources.list_local_resources({}), expected_type="resource.local.listed")

    assert listed.payload["languageData"] == ["ar-calima-msa"]


# --------------------------------------------------------------- runtime lane: the vendored extractor


def _dropped_code(entry: LanguageDataResource) -> list[str]:
    """The code members the catalog entry drops (an sdist's ``__init__.py``)."""

    return [name for name in entry.install.exclude if language_data.is_code_member(name)]


def _fixture_sdist(path: Path, entry: LanguageDataResource, extra: dict[str, bytes]) -> Path:
    """A ``.tar.gz`` shaped like a model sdist: data and the package's own ``.py`` files under the prefix."""

    prefix = entry.install.member_prefix
    members = {prefix + name: f"fixture {name}".encode() for name in entry.install.sentinels}
    members.update({prefix + name: b"raise SystemExit\n" for name in _dropped_code(entry)})
    members["model-1.0/PKG-INFO"] = b"Name: model\n"
    members["model-1.0/setup.py"] = b"raise SystemExit\n"
    members.update(extra)
    with tarfile.open(path, "w:gz") as bundle:
        for name, content in sorted(members.items()):
            info = tarfile.TarInfo(name)
            info.size = len(content)
            bundle.addfile(info, io.BytesIO(content))
    return path


def _fixture_archive(path: Path, entry: LanguageDataResource, *, extra: dict[str, bytes] | None = None) -> Path:
    """An archive shaped like the pinned one: every sentinel and inner file under the prefix."""

    if entry.archive.format == "sdist":
        return _fixture_sdist(path, entry, extra or {})
    prefix = entry.install.member_prefix
    with zipfile.ZipFile(path, "w") as bundle:
        for name in sorted({*entry.install.sentinels, *(digest.path for digest in entry.install.inner_sha256)}):
            bundle.writestr(prefix + name, f"fixture {name}".encode())
        if prefix:
            # Outside the prefix: a wheel's own package code and metadata are never selected.
            # A flat archive (prefix "") extracts whole, so it carries none.
            bundle.writestr("pkg/__init__.py", b"raise SystemExit\n")
            bundle.writestr("pkg-1.0.dist-info/RECORD", b"")
        for name, content in (extra or {}).items():
            bundle.writestr(name, content)
    return path


def _pin_to_fixture(monkeypatch: pytest.MonkeyPatch, code: str, entry: LanguageDataResource, archive: Path):
    """Point the catalog entry and the vendored manifest at the fixture's bytes."""

    from anki_miner.services import language_pack_installer

    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    inner = tuple(
        InnerDigest(item.path, hashlib.sha256(f"fixture {item.path}".encode()).hexdigest())
        for item in entry.install.inner_sha256
    )
    pinned = replace(
        entry,
        archive=replace(entry.archive, sha256=digest, size_bytes=archive.stat().st_size),
        install=replace(entry.install, inner_sha256=inner),
    )
    real = language_pack_installer.load_pack(code)
    components = tuple(
        (
            replace(
                component,
                universal=replace(
                    component.universal,
                    sha256=digest,
                    inner_sha256=tuple((item.path, item.sha256) for item in inner),
                ),
            )
            if component.import_name == entry.import_name
            else component
        )
        for component in real.components
    )
    pack = replace(real, components=components)
    monkeypatch.setattr(language_pack_installer, "load_pack", lambda requested: pack if requested == code else None)
    monkeypatch.setattr(language_data, "find_catalog_resource", lambda _id: (code, pinned))
    return pinned


@pytest.fixture
def home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, initialized_bridge_home: Path) -> Path:
    pytest.importorskip("requests", reason="runtime dependency lane: the pack installer imports the downloader")
    del initialized_bridge_home
    from anki_miner.config import paths

    files = tmp_path / "files"
    files.mkdir()
    monkeypatch.setattr(paths, "ANKI_MINER_HOME", files)
    monkeypatch.setattr(language_data, "require_initialized", lambda: str(files))
    return files


def _install(entry: LanguageDataResource, archive: Path, operation: str = "language-data") -> dict:
    raw = boundary.dispatch(
        encode_message(
            "resource.languagedata.install",
            {"operationId": operation, "resourceId": entry.resource_id, "archivePath": str(archive)},
        )
    )
    return decode_envelope(raw).payload


@pytest.mark.parametrize(("code", "entry"), _data_entries(), ids=lambda value: getattr(value, "resource_id", value))
def test_every_data_component_installs_where_the_engine_looks_for_it(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, code: str, entry: LanguageDataResource
) -> None:
    from anki_miner.services.language_pack_installer import component_path

    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)
    assert component_path(code, entry.import_name) is None

    installed = _install(pinned, archive)

    assert installed == {"resourceId": entry.resource_id, "language": code, "importName": entry.import_name}
    path = component_path(code, entry.import_name)
    assert path == home / "language_packs" / code / entry.import_name
    assert entry.resource_id in language_data.installed_language_data(home)
    landed = sorted(
        item.relative_to(home).as_posix() for item in (home / "language_packs").rglob("*") if item.is_file()
    )
    assert landed == sorted(
        f"language_packs/{code}/{entry.import_name}/{name}"
        for name in {*entry.install.sentinels, *(digest.path for digest in entry.install.inner_sha256)}
    )
    assert not any(language_data.is_code_member(name) for name in landed)
    # Kotlin downloaded the archive; the bridge reads it and leaves it for Kotlin to discard.
    assert archive.is_file()


@pytest.mark.parametrize(
    "member",
    [
        "__init__.py",
        "sub/_speedups.so",
        "cache/table.cpython-312.pyc",
        "a.PY",
        "lib.SO",
        "libs/lib.So.1",
        "../x.py",
        "evil.py/junk/..",
        "lib.so/x/..",
        "x/evil.py/junk/..",
        "x/evil.PY/./",
        "x/a.so/../b.so",
        "../escape.dat",
    ],
)
def test_an_archive_with_a_code_member_is_refused_before_anything_lands(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, member: str
) -> None:
    code, entry = _data_entries()[0]
    archive = _fixture_archive(
        tmp_path / "download.part", entry, extra={entry.install.member_prefix + member: b"import os\n"}
    )
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)

    payload = _install(pinned, archive)

    assert payload["code"] == "language_data_rejected"
    assert not (home / "language_packs" / code / entry.import_name).exists()


def test_an_archive_that_is_not_the_pinned_one_is_refused(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    code, entry = _data_entries()[0]
    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)
    archive.write_bytes(archive.read_bytes()[:-1] + b"\x01")

    assert _install(pinned, archive)["code"] == "resource_archive_mismatch"
    assert not (home / "language_packs").exists()


def test_a_catalog_entry_that_drifted_from_the_vendored_manifest_is_refused(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    code, entry = _data_entries()[0]
    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)
    drifted = replace(pinned, install=replace(pinned.install, sentinels=(*pinned.install.sentinels, "extra.dat")))
    monkeypatch.setattr(language_data, "find_catalog_resource", lambda _id: (code, drifted))

    assert _install(drifted, archive)["code"] == "resource_catalog_mismatch"


def test_a_payload_that_fails_its_inner_digest_is_not_promoted(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    entries = [(code, entry) for code, entry in _data_entries() if entry.install.inner_sha256]
    assert entries, "the Arabic database is pinned by its inner digest"
    code, entry = entries[0]
    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)
    rebuilt = io.BytesIO()
    with zipfile.ZipFile(archive) as source, zipfile.ZipFile(rebuilt, "w") as target:
        for item in source.infolist():
            content = source.read(item)
            if item.filename == entry.install.member_prefix + entry.install.inner_sha256[0].path:
                content = b"tampered"
            target.writestr(item, content)
    archive.write_bytes(rebuilt.getvalue())
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)

    assert _install(pinned, archive)["code"] == "language_data_install_failed"
    assert not (home / "language_packs" / code / entry.import_name).exists()
    assert not list((home / "language_packs" / code).glob(".staging-*"))


def test_installing_arabic_data_makes_arabic_available(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    from android_bridge.languages import get_profile, unavailable_reason_code

    entry = load_resource_catalog("ar").language_data[0]
    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, "ar", entry, archive)
    assert unavailable_reason_code(get_profile("ar")) == "language_data_required"

    _install(pinned, archive)

    assert unavailable_reason_code(get_profile("ar")) is None


def test_the_vendored_extractor_alone_would_write_a_collapsed_code_member_the_filter_refuses(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """``hazm/data/evil.py/junk/..`` is written as ``evil.py`` by resolve(); only the pre-filter stops it."""
    from anki_miner.services import language_pack_installer
    from anki_miner.services.pack_installer import _extract_component

    code, entry = next((code, entry) for code, entry in _data_entries() if entry.install.member_prefix)
    trick = entry.install.member_prefix + "evil.py/junk/.."
    archive = _fixture_archive(tmp_path / "download.part", entry, extra={trick: b"import os\n"})
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)

    probe_root = tmp_path / "probe"
    probe_root.mkdir()
    component = next(
        c for c in language_pack_installer.load_pack(code).components if c.import_name == entry.import_name
    )
    _extract_component(archive, probe_root, component, component.universal)
    assert (probe_root / entry.import_name / "evil.py").is_file(), "the probe must show what the filter prevents"

    payload = _install(pinned, archive)

    assert payload["code"] == "language_data_rejected"
    assert not (home / "language_packs").exists() or not any(
        path.is_file() for path in (home / "language_packs").rglob("*")
    )


def _korean_model() -> tuple[str, LanguageDataResource]:
    return next((code, entry) for code, entry in _data_entries() if entry.archive.format == "sdist")


def test_an_sdist_installs_its_data_without_the_code_members_its_entry_drops(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    code, entry = _korean_model()
    assert _dropped_code(entry) == ["__init__.py", "_version.py"]
    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)

    assert _install(pinned, archive)["importName"] == "kiwipiepy_model"

    component = home / "language_packs" / code / entry.import_name
    assert sorted(path.name for path in component.iterdir()) == sorted(entry.install.sentinels)


def test_an_sdist_with_a_code_member_its_entry_does_not_drop_is_refused(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    code, entry = _korean_model()
    archive = _fixture_archive(
        tmp_path / "download.part", entry, extra={entry.install.member_prefix + "loader.py": b"import os\n"}
    )
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)

    assert _install(pinned, archive)["code"] == "language_data_rejected"
    assert not (home / "language_packs" / code / entry.import_name).exists()


@pytest.mark.parametrize(
    "exclude",
    [
        # An Android addition may only drop code: dropping data would hide it from the pin.
        ("__init__.py", "_version.py", "sj.morph"),
        ("__init__.py", "_version.py", "subdir/"),
        ("__init__.py", "_version.py", "native.so/"),
    ],
)
def test_a_catalog_exclude_may_add_only_code_members_to_the_vendored_list(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, exclude: tuple[str, ...]
) -> None:
    code, entry = _korean_model()
    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)
    drifted = replace(pinned, install=replace(pinned.install, exclude=exclude))
    monkeypatch.setattr(language_data, "find_catalog_resource", lambda _id: (code, drifted))

    assert _install(drifted, archive)["code"] == "resource_catalog_mismatch"


def test_a_catalog_exclude_must_keep_every_vendored_exclude(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from anki_miner.services import language_pack_installer

    code, entry = _korean_model()
    archive = _fixture_archive(tmp_path / "download.part", entry)
    pinned = _pin_to_fixture(monkeypatch, code, entry, archive)
    pack = language_pack_installer.load_pack(code)
    vendored = tuple(
        (
            replace(component, universal=replace(component.universal, exclude=("notes/",)))
            if component.import_name == entry.import_name
            else component
        )
        for component in pack.components
    )
    monkeypatch.setattr(
        language_pack_installer,
        "load_pack",
        lambda requested: replace(pack, components=vendored) if requested == code else None,
    )

    assert _install(pinned, archive)["code"] == "resource_catalog_mismatch"
