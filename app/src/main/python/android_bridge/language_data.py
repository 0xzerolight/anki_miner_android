"""Install the data-only components of a vendored language pack.

Desktop downloads a language's pack with ``install_language_pack`` and puts the
pack root on ``sys.path``. Android never does either: engine code ships in the
APK, and only data is downloaded (decision 2). Kotlin downloads a pinned
``language-data`` catalog entry with ``PinnedResourceDownloader``; this module
re-verifies the archive, refuses it outright if the vendored extractor would
write a single code member, and then runs the vendored
``pack_installer._extract_component`` into ``language_packs/<code>/`` -- the
exact layout ``language_pack_installer.component_path`` reads, so the engine's
own availability probe answers for it.

Nothing here appends to ``sys.path``: a data directory is read by path.

Engine imports are function-local (``bootstrap`` must set ``ANKI_MINER_HOME``
first).
"""

from __future__ import annotations

import logging
import stat
import zipfile
from collections.abc import Mapping
from pathlib import Path, PurePosixPath
from typing import Any

from . import resources as core
from .bootstrap import require_initialized
from .protocol import BridgeProtocolError, encode_message
from .resource_catalog import LanguageDataResource, find_catalog_resource, load_resource_catalogs

logger = logging.getLogger(__name__)

#: Executable members the extraction never writes (decision 2). A versioned
#: shared object (``libfoo.so.1``) is caught by the ``.so.`` infix. Matched on
#: the lowercased file name, so ``a.PY`` and ``lib.So.1`` are code too.
_CODE_SUFFIXES = (".py", ".pyc", ".so")


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def is_code_member(name: str) -> bool:
    base = PurePosixPath(name.replace("\\", "/")).name.lower()
    return base.endswith(_CODE_SUFFIXES) or ".so." in base


def _vendored_component(language: str, resource: LanguageDataResource) -> tuple[Any, Any]:
    """The vendored ``(PackComponent, ArtifactSpec)`` the catalog entry was generated from."""

    from anki_miner.services.language_pack_installer import load_pack

    pack = load_pack(language)
    component = (
        None
        if pack is None
        else next((item for item in pack.components if item.import_name == resource.import_name), None)
    )
    spec = None if component is None else component.universal
    install = resource.install
    if (
        spec is None
        or component.per_platform is not None
        or component.abi is not None
        or spec.root_members
        or spec.url != resource.archive.url
        or spec.sha256 != resource.archive.sha256
        or spec.kind != resource.archive.format
        or spec.member_prefix != install.member_prefix
        or tuple(spec.exclude) != install.exclude
        or tuple(component.sentinels) != install.sentinels
        or tuple(spec.inner_sha256) != tuple((digest.path, digest.sha256) for digest in install.inner_sha256)
    ):
        raise _fail(
            "resource_catalog_mismatch",
            "Pinned language data does not match the vendored pack manifest",
        )
    return component, spec


def _refuse_code_members(archive: Path, spec: Any) -> None:
    """Fail closed if the vendored extractor would write any code member.

    Uses the extractor's own member selection (``_wanted`` / ``_wanted_root``),
    so the check covers exactly what ``_extract_component`` writes.
    """

    from anki_miner.services.pack_installer import _wanted, _wanted_root

    try:
        with zipfile.ZipFile(archive) as bundle:
            names = [name for name in bundle.namelist() if not name.endswith("/")]
    except (OSError, zipfile.BadZipFile) as exc:
        raise _fail("language_data_install_failed", "The language data archive is not a valid zip") from exc
    code = [name for name in names if (_wanted(name, spec) or _wanted_root(name, spec)) and is_code_member(name)]
    if code:
        logger.warning(
            "language_data_code_refused outcome=fail members=%d first=%s", len(code), PurePosixPath(code[0]).name
        )
        raise _fail("language_data_rejected", "The language data archive contains executable code")


def install_language_data(payload: Mapping[str, object]) -> str:
    core._exact(payload, {"operationId", "resourceId", "archivePath"}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    resource_id = core._bounded_text(payload["resourceId"], name="resourceId", max_bytes=64)
    archive = core._absolute_path(payload["archivePath"], name="archivePath")
    language, resource = find_catalog_resource(resource_id)
    if not isinstance(resource, LanguageDataResource):
        raise _fail("invalid_resource_kind", "Requested resource is not language data")
    require_initialized()

    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._hash_archive(
            archive,
            operation,
            maximum_bytes=resource.archive.size_bytes,
            expected_size=resource.archive.size_bytes,
            expected_sha256=resource.archive.sha256,
        )
        component, spec = _vendored_component(language, resource)
        _refuse_code_members(archive, spec)
        operation.check()

        from anki_miner.exceptions import SetupError
        from anki_miner.services._install_common import sweep_stale
        from anki_miner.services.language_pack_installer import component_path, language_pack_root
        from anki_miner.services.pack_installer import _extract_component

        root = language_pack_root(language)
        with core._PROMOTION_LOCK:
            try:
                root.mkdir(parents=True, exist_ok=True)
                # A killed install leaves a .staging-* tree inside the root.
                sweep_stale(root)
                _extract_component(archive, root, component, spec)
            except SetupError as exc:
                core._raise_if_storage_exhausted(exc.__cause__ or exc)
                raise _fail("language_data_install_failed", "The language data could not be installed") from exc
            except OSError as exc:
                core._raise_if_storage_exhausted(exc)
                raise _fail("language_data_install_failed", "The language data could not be installed") from exc
        if component_path(language, resource.import_name) is None:
            raise _fail("language_data_install_failed", "The installed language data is incomplete")
    logger.info(
        "language_data_installed outcome=ok resource=%s language=%s component=%s",
        resource_id,
        language,
        resource.import_name,
    )
    return encode_message(
        "resource.languagedata.installed",
        {"resourceId": resource_id, "language": language, "importName": resource.import_name},
    )


def _component_complete(directory: Path, sentinels: tuple[str, ...]) -> bool:
    """The engine's sentinel rule (``pack_installer.component_complete``), without the engine."""

    try:
        if stat.S_ISLNK(directory.lstat().st_mode) or not directory.is_dir():
            return False
        for name in sentinels:
            mode = (directory / name).lstat().st_mode
            if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
                return False
    except OSError:
        return False
    return True


def installed_language_data(home: Path) -> list[str]:
    """Resource ids of every pinned language-data component complete on disk."""

    return [
        resource.resource_id
        for catalog in load_resource_catalogs()
        for resource in catalog.language_data
        if _component_complete(
            home / "language_packs" / catalog.language / resource.import_name,
            resource.install.sentinels,
        )
    ]
